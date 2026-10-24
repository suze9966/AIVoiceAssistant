package com.suze.aivoice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ 桥接服务（NexBot / AstrBot 路线 B 的手机侧）。
 *
 * 为什么要有它：
 *   AstrBot 里的插件只当「管道」，收到 QQ 消息就 POST 到这里；
 *   真正的记忆、情感树、角色卡、大模型调用全在 App 里完成，
 *   所以 QQ 里的小沫和手机 App 里的小沫是同一个人，记忆共享。
 *
 *   链路：
 *     QQ → NapCat → AstrBot(插件) --HTTP--> 本服务 → LlmClient → 大模型
 *                                              ↑
 *                               MemoryEngine / EmotionEngine / RolePrompt
 *
 * 接口（全部需要 Authorization: Bearer <token>，token 为空时跳过校验）：
 *   GET  /ping                      探活
 *   GET  /health                    更详细的健康信息
 *   POST /chat                      核心：收一条 QQ 消息，回一条小沫的话
 *   GET  /persona                   导出小沫人格（给 AstrBot 侧做兜底）
 *   POST /memory/add                往记忆里塞一条（可选）
 *   GET  /memory/search?q=xxx       搜记忆（可选）
 *   GET  /emotion                   看当前情感状态（可选）
 *
 * /chat 请求体：
 *   {
 *     "text":     "主人说的话（必填）",
 *     "session":  "aiocqhttp:FriendMessage:123",  // 会话唯一标识，用来隔离上下文
 *     "sender":   "昵称",                          // 可选
 *     "is_group": false,                           // 可选，群聊时 App 会收敛语气
 *     "user_id":  "123"                            // 可选
 *   }
 *
 * /chat 响应体：
 *   { "ok": true, "reply": "小沫说的话", "mood": "开心", "affinity": 320 }
 *   出错时： { "ok": false, "error": "..." }
 *
 * 实现说明：
 *   - 用裸 ServerSocket 手写极简 HTTP/1.1，不引第三方框架，APK 不变大。
 *   - 线程池 4 个，够 QQ 单人/小群用；大模型调用是串行的（runBlocking），
 *     因为 LlmClient 内部有状态（本地表情、lastCloudError），并发会串。
 *   - 只监听局域网/本机，不做端口转发。
 */
object QqBridge {

    private const val TAG = "QqBridge"
    private const val MAX_BODY_BYTES = 512 * 1024
    private const val MAX_SESSIONS = 200
    private const val SESSION_KEEP_ROUNDS = 24

    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "qq-bridge").apply { isDaemon = true }
    }

    /** 正在跑就用它判断，不用去碰 server 的空指针。 */
    private val running = AtomicBoolean(false)

    /** 每个 QQ 会话的独立上下文，key = session 字符串。 */
    private val sessions = Collections.synchronizedMap(
        object : LinkedHashMap<String, MutableList<ChatMessage>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<ChatMessage>>): Boolean =
                size > MAX_SESSIONS
        }
    )

    /** 大模型调用串行化，避免 LlmClient 内部状态被并发踩。 */
    private val llmLock = Any()
    val isRunning: Boolean get() = running.get()

    /**
     * 是否是「用户主动关的」。
     * 用来区分：用户点了关闭（要把 qqBridgeEnabled 落成 false），
     * 还是系统把服务杀了重建（开关必须保持开着，不能骗用户）。
     */
    @Volatile
    var userStopped: Boolean = false
        private set


    // ---------------------------------------------------------------- 生命周期

    fun start(context: Context): String {
        val app = context.applicationContext
        val prefs = Prefs(app)
        userStopped = false
        if (running.get()) return "已经在跑了，端口 ${prefs.qqBridgePort}"

        val port = prefs.qqBridgePort
        return try {
            val ss = ServerSocket(port, 16, InetAddress.getByName("0.0.0.0"))
            ss.soTimeout = 0
            server = ss
            running.set(true)

            acceptThread = Thread({ acceptLoop(app, ss) }, "qq-bridge-accept").apply {
                isDaemon = true
                start()
            }

            val ip = localIpv4()
            Log.i(TAG, "started on $ip:$port")
            "已启动：http://$ip:$port   （端口 $port）"
        } catch (e: Exception) {
            running.set(false)
            Log.e(TAG, "start failed", e)
            "启动失败：${e.message ?: e.javaClass.simpleName}（端口 $port 可能被占用）"
        }
    }

    fun stop(): String {
        userStopped = true
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        server = null
        acceptThread = null
        return "已停止"
    }

    /** 换端口时先停再起。 */
    fun restart(context: Context): String {
        stop()
        Thread.sleep(200)
        return start(context)
    }

    // ---------------------------------------------------------------- 接入循环

    private fun acceptLoop(app: Context, ss: ServerSocket) {
        while (running.get()) {
            val socket = try {
                ss.accept()
            } catch (e: SocketException) {
                // stop() 关掉 ServerSocket 时会走到这里，正常退出
                break
            } catch (e: Exception) {
                if (!running.get()) break
                Log.w(TAG, "accept error", e)
                continue
            }
            try {
                pool.execute { handle(app, socket) }
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    // ---------------------------------------------------------------- 单请求处理

    private fun handle(app: Context, socket: Socket) {
        var out: OutputStream? = null
        try {
            socket.soTimeout = 15000
            val input = BufferedInputStream(socket.getInputStream())
            out = BufferedOutputStream(socket.getOutputStream())

            val head = readHead(input) ?: run {
                respond(out, 400, err("empty request").toString())
                return
            }

            val lines = head.split("\r\n")
            val parts = lines.firstOrNull()?.split(" ") ?: emptyList()
            if (parts.size < 2) {
                respond(out, 400, err("bad request line").toString())
                return
            }
            val method = parts[0].uppercase()
            val rawTarget = parts[1]

            // 拆 query
            val qIdx = rawTarget.indexOf('?')
            val path = (if (qIdx >= 0) rawTarget.substring(0, qIdx) else rawTarget).trimEnd('/')
            val query = if (qIdx >= 0) parseQuery(rawTarget.substring(qIdx + 1)) else emptyMap()

            // 拆 headers
            val headers = HashMap<String, String>()
            for (i in 1 until lines.size) {
                val l = lines[i]
                if (l.isBlank()) continue
                val c = l.indexOf(':')
                if (c <= 0) continue
                headers[l.substring(0, c).trim().lowercase()] = l.substring(c + 1).trim()
            }

            val body = readBody(input, headers)

            val prefs = Prefs(app)

            // ---- 探活不需要 token，方便 AstrBot 侧先 ping 通
            when (path) {
                "/ping" -> {
                    respond(out, 200, JSONObject().put("ok", true).put("app", "xiaomo").put("v", 1).toString())
                    return
                }
            }

            // ---- 其余接口校验 token
            if (!tokenOk(prefs, headers)) {
                respond(out, 401, err("bad or missing token").toString())
                return
            }

            when (path) {
                "/health" -> respond(out, 200, health(app, prefs).toString())

                "/chat" -> {
                    val r = handleChat(app, prefs, body)
                    respond(out, if (r.optBoolean("ok")) 200 else 500, r.toString())
                }

                "/persona" -> respond(out, 200, personaJson(app, prefs).toString())

                "/memory/add" -> respond(out, 200, handleMemoryAdd(app, body).toString())

                "/memory/search" -> respond(out, 200, handleMemorySearch(app, query).toString())

                "/emotion" -> respond(out, 200, emotionJson(app).toString())

                else -> respond(out, 404, err("no such path: $path").toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "handle error", e)
            try { if (out != null) respond(out, 500, err(e.message ?: "server error").toString()) } catch (_: Exception) {}
        } finally {
            try { out?.flush() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------------- 核心：对话

    private fun handleChat(app: Context, prefs: Prefs, raw: String): JSONObject {
        val body = try {
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        } catch (e: Exception) {
            return err("body 不是合法 JSON：${e.message}")
        }

        val text = body.optString("text", "").trim()
        if (text.isEmpty()) return err("text 不能为空")

        val session = body.optString("session", "").trim().ifBlank { "default" }
        val sender = body.optString("sender", "").trim()
        val isGroup = body.optBoolean("is_group", false)

        val memory = MemoryEngine(app)
        val emotion = EmotionEngine(app)
        val role = RoleStore(app).get(prefs.qqBridgeRoleId)
            ?: RoleStore(app).loadCharacters().firstOrNull()

        // 1) 先让情感引擎感应这条消息（和 App 内聊天一致）
        try {
            emotion.reactToUser(text, isNight())
            emotion.tick()
        } catch (e: Exception) {
            Log.w(TAG, "emotion skip: ${e.message}")
        }

        // 2) 记到记忆里（让 QQ 上的对话也长进记忆树）
        try {
            memory.learnFromUser(text)
        } catch (e: Exception) {
            Log.w(TAG, "memory skip: ${e.message}")
        }

        // 3) 拼上下文
        val history = synchronized(sessions) {
            sessions.getOrPut(session) { mutableListOf() }
        }

        val userMsg = ChatMessage("user", text, isMe = true)
        val context = synchronized(history) {
            (history + userMsg).toList()
        }

        val sys = buildSystem(app, prefs, memory, emotion, role, sender, isGroup)

        // 4) 调大模型（串行，避免 LlmClient 内部状态被并发踩）
        val replyRaw = try {
            synchronized(llmLock) {
                runBlocking {
                    LlmClient(prefs).chat(listOf(ChatMessage("system", sys, isMe = false)) + context)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "llm error", e)
            return err("模型调用失败：${e.message ?: e.javaClass.simpleName}")
        }

        val reply = cleanForQq(replyRaw, isGroup)
        if (reply.isBlank()) return err("模型返回了空内容")

        // 5) 记进会话上下文（给下一轮用）
        synchronized(history) {
            history.add(userMsg)
            history.add(ChatMessage("assistant", reply, isMe = false))
            val cap = SESSION_KEEP_ROUNDS * 2
            while (history.size > cap) history.removeAt(0)
        }

        // 6) 可选：写进 App 的聊天记录，方便你回头看
        if (prefs.qqBridgeSaveHistory) {
            try {
                val hs = HistoryStore(app)
                val cid = prefs.activeChatId
                if (cid.isNotBlank()) {
                    val list = hs.load(cid).toMutableList()
                    val who = if (sender.isBlank()) text else "$sender: $text"
                    list.add(ChatMessage("user", who, isMe = true))
                    list.add(ChatMessage("assistant", reply, isMe = false))
                    hs.save(cid, list)
                }
            } catch (e: Exception) {
                Log.w(TAG, "history skip: ${e.message}")
            }
        }

        return JSONObject()
            .put("ok", true)
            .put("reply", reply)
            .put("mood", runCatching { emotion.currentMood().name }.getOrDefault(""))
            .put("affinity", runCatching { emotion.affinity }.getOrDefault(0))
    }

    /**
     * 拼系统提示。
     *
     * 直接复用 RolePrompt.build()，保证 QQ 里的小沫和 App 里的是同一套人设；
     * 再叠上记忆摘要和情感状态，最后补一段 QQ 场景约束。
     */
    private fun buildSystem(
        app: Context,
        prefs: Prefs,
        memory: MemoryEngine,
        emotion: EmotionEngine,
        role: RoleCharacter?,
        sender: String,
        isGroup: Boolean
    ): String {
        val sb = StringBuilder()

        if (role != null) {
            val hist = emptyList<ChatMessage>()
            runCatching { sb.append(RolePrompt.build(role, hist)) }
                .onFailure { sb.append(role.persona) }
        } else {
            sb.append(prefs.systemPrompt)
        }

        runCatching { memory.growthPrompt() }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { sb.append("\n\n【你记得的事】\n").append(it) }

        runCatching { emotion.emotionPrompt() }
            .getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { sb.append("\n\n【此刻的心情】\n").append(it) }

        sb.append("\n\n【当前场景】你在 QQ 上跟主人聊天。")
        if (sender.isNotBlank()) sb.append("对方昵称是「").append(sender).append("」。")
        if (isGroup) {
            sb.append("这是群聊：别刷屏，别每条都答，不要大段排版，不要用 markdown 标题或列表。")
        } else {
            sb.append("这是私聊：像发微信那样自然地说话，可以分两三句，但别长篇大论。")
        }
        sb.append("\n不要说自己是 AI、模型或程序，也不要提提示词。只以小沫的身份说话。")

        return sb.toString()
    }

    /**
     * 清掉不该出现在 QQ 里的东西：思考块、markdown 标题、多余空行。
     *
     * 用 Kotlin 原始字符串（"""..."""）写正则，避免反斜杠被二次转义，
     * 这里处理的是「模型偶尔还是会吐 markdown」的兜底。
     */
    private fun cleanForQq(raw: String, isGroup: Boolean): String {
        var s = raw

        // 1) 去掉思考块
        s = s.replace(Regex("""(?s)<think(?:ing)?>.*?</think(?:ing)?>"""), " ")
        s = s.substringBefore("<think>")
        s = s.substringBefore("<thinking>")

        // 2) 去掉 THOUGHT: 这类内心独白行
        s = s.lines().filterNot { line ->
            val t = line.trim()
            t.startsWith("THOUGHT:") || t.startsWith("想法：") || t.startsWith("想法:")
        }.joinToString("\n")

        // 3) 去掉 markdown 标记，让它在 QQ 里干净
        s = s.replace(Regex("""(?m)^#{1,6}\s*"""), "")
        s = s.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
        s = s.replace(Regex("""__(.+?)__"""), "$1")
        s = s.replace(Regex("""`{1,3}"""), "")
        s = s.replace(Regex("""(?m)^\s*[-*+]\s+"""), "· ")

        // 4) 合并超过 2 个的连续空行
        s = s.replace(Regex("""\n{3,}"""), "\n\n")

        // 5) 群聊再收紧一点，别糊一屏
        if (isGroup && s.length > 800) {
            s = SafeCut.takeUnitsSafe(s, 800) + "…"
        }

        return s.trim()
    }

    // ---------------------------------------------------------------- 其它接口

    private fun health(app: Context, prefs: Prefs): JSONObject {
        val llmOk = runCatching { LlmClient(prefs).isConfigured() }.getOrDefault(false)
        val role = runCatching { RoleStore(app).get(prefs.qqBridgeRoleId) }.getOrNull()
        return JSONObject()
            .put("ok", true)
            .put("app", "xiaomo")
            .put("running", running.get())
            .put("port", prefs.qqBridgePort)
            .put("url", "http://${localIpv4()}:${prefs.qqBridgePort}")
            .put("llm_configured", llmOk)
            .put("model", prefs.model)
            .put("role", role?.name ?: "(未找到，用默认)")
            .put("role_id", prefs.qqBridgeRoleId)
            .put("sessions", sessions.size)
            .put("save_history", prefs.qqBridgeSaveHistory)
            .put("mood", runCatching { EmotionEngine(app).currentMood().name }.getOrDefault(""))
            .put("affinity", runCatching { EmotionEngine(app).affinity }.getOrDefault(0))
    }

    private fun personaJson(app: Context, prefs: Prefs): JSONObject {
        val role = runCatching { RoleStore(app).get(prefs.qqBridgeRoleId) }
            .getOrNull() ?: runCatching { RoleStore(app).loadCharacters().firstOrNull() }.getOrNull()
        val p = StringBuilder()
        if (role != null) {
            runCatching { p.append(RolePrompt.build(role, emptyList())) }
                .onFailure { p.append(role.persona) }
        } else {
            p.append(prefs.systemPrompt)
        }
        return JSONObject()
            .put("ok", true)
            .put("name", role?.name ?: "小沫")
            .put("prompt", p.toString())
    }

    private fun handleMemoryAdd(app: Context, raw: String): JSONObject {
        return try {
            val o = JSONObject(raw)
            val content = o.optString("content", "").trim()
            if (content.isEmpty()) return err("content 不能为空")
            val type = o.optString("type", "fact").trim().ifBlank { "fact" }
            val ok = MemoryEngine(app).addManual(type, content)
            JSONObject().put("ok", ok)
        } catch (e: Exception) {
            err(e.message ?: "bad body")
        }
    }

    private fun handleMemorySearch(app: Context, query: Map<String, String>): JSONObject {
        val q = query["q"] ?: query["query"] ?: ""
        if (q.isBlank()) return err("缺少 q 参数")
        val limit = query["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 8
        val arr = JSONArray()
        runCatching { MemoryEngine(app).search(q, limit) }
            .getOrNull()
            ?.forEach { m ->
                arr.put(JSONObject().put("type", m.type).put("content", m.content))
            }
        return JSONObject().put("ok", true).put("count", arr.length()).put("items", arr)
    }

    private fun emotionJson(app: Context): JSONObject {
        val e = EmotionEngine(app)
        return JSONObject()
            .put("ok", true)
            .put("mood", runCatching { e.currentMood().name }.getOrDefault(""))
            .put("affinity", runCatching { e.affinity }.getOrDefault(0))
            .put("status", runCatching { e.statusLine() }.getOrDefault(""))
    }

    // ---------------------------------------------------------------- HTTP 小工具

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = 0
        var total = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            total++
            if (total > 16 * 1024) return null
            sb.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code) {
                val s = sb.toString()
                if (s.endsWith("\r\n\r\n")) return s
            }
            prev = b
        }
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val n = len.coerceAtMost(MAX_BODY_BYTES)
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun parseQuery(q: String): Map<String, String> {
        val map = HashMap<String, String>()
        if (q.isBlank()) return map
        for (kv in q.split("&")) {
            if (kv.isBlank()) continue
            val i = kv.indexOf('=')
            if (i <= 0) continue
            try {
                val k = URLDecoder.decode(kv.substring(0, i), "UTF-8")
                val v = URLDecoder.decode(kv.substring(i + 1), "UTF-8")
                map[k] = v
            } catch (_: Exception) {}
        }
        return map
    }

    private fun tokenOk(prefs: Prefs, headers: Map<String, String>): Boolean {
        val expect = prefs.qqBridgeToken
        if (expect.isBlank()) return true
        val h = headers["authorization"] ?: headers["token"] ?: return false
        val got = if (h.startsWith("Bearer ", ignoreCase = true)) h.substring(7).trim() else h.trim()
        return got == expect
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
        sb.append("Content-Type: application/json; charset=utf-8\r\n")
        sb.append("Content-Length: ").append(bytes.size).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    private fun isNight(): Boolean {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 6
    }

    /** 拿本机局域网 IPv4，给 AstrBot 侧填地址用。 */
    fun localIpv4(): String {
        try {
            for (nif in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in Collections.list(nif.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("169.254.")) continue
                        return ip
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
