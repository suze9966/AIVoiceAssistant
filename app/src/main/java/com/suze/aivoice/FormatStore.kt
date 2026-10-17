package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 文游「格式库」：把主人上传的固定格式指令（比如仿微信个人资料页、状态栏、短信界面）
 * 存下来，需要时自动注入系统提示词，让角色按固定格式输出。
 *
 * 与酒馆角色卡的区别：角色卡管「我是谁」，格式库管「长什么样」——
 * 一份格式可以被任意角色/群聊复用，也能随时启用停用。
 */
data class FormatEntry(
    /** 唯一 id。内置的用 `builtin:文件名`，导入的用 `import:时间戳`。 */
    val id: String,
    /** 显示名（也作为口令）。默认取原文第一行。 */
    val title: String,
    /** 完整格式指令原文。 */
    val content: String,
    /** 是否启用（启用后才会注入提示词）。 */
    val enabled: Boolean = true,
    /** 是否内置（内置的不可删除，只能停用）。 */
    val builtin: Boolean = false,
    /** 触发时机说明（可选，比如「当需要查看个人资料页时」）。 */
    val trigger: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

class FormatStore(private val context: Context) {

    private val file = File(context.filesDir, "format_library.json")
    private val maxKeep = 5000
    private val maxContent = 4_000_000

    @Volatile
    private var cache: MutableList<FormatEntry>? = null

    /** 读取 assets/formats 下的内置格式。 */
    private fun builtins(): List<FormatEntry> = try {
        val names = context.assets.list("formats")?.toList().orEmpty()
            .filter { it.endsWith(".txt") || it.endsWith(".md") }
            .sorted()
        names.mapNotNull { n ->
            val text = runCatching {
                context.assets.open("formats/$n").bufferedReader().use { it.readText() }
            }.getOrNull() ?: return@mapNotNull null
            if (text.isBlank()) return@mapNotNull null
            FormatEntry(
                id = "builtin:$n",
                title = titleOf(text, n),
                content = text,
                enabled = false,
                builtin = true,
                trigger = triggerOf(text)
            )
        }
    } catch (_: Exception) { emptyList() }

    /** 取原文里最适合当标题的一行（优先含中文、且不是纯 LaTeX 记号的行）。 */
    private fun titleOf(text: String, fallback: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        fun isUseful(line: String): Boolean {
            if (line.length < 2) return false
            if (line.startsWith("\\")) return false
            if (line.startsWith("#")) return false
            return line.any { it.code > 0x2E80 }
        }
        val first = lines.firstOrNull { isUseful(it) }
            ?: lines.firstOrNull().orEmpty()
        // 「DeepSeek仿微信个人资料页面美化.sy」这类标题去掉模型名前缀和 .sy 后缀更好读
        var t = first
        listOf("DeepSeek", "deepseek", "ChatGPT", "GPT", "Claude", "Gemini", "豆包", "文心")
            .forEach { t = t.replace(it, "") }
        t = t.trim().removeSuffix(".sy").removeSuffix(".SY").trim()
        // 去掉行首的装饰符号与「说明：」前缀
        t = t.trimStart('·', '•', '-', '>', '·', '　', ' ')
        if (t.startsWith("说明：") || t.startsWith("说明:")) t = t.substring(3).trim()
        if (t.isBlank()) t = fallback.removeSuffix(".txt").removeSuffix(".md").replace('_', ' ')
        return t.take(40)
    }

    /** 从原文里猜一个触发时机（「当玩家…时请用以下固定格式」）。 */
    private fun triggerOf(text: String): String {
        val m = Regex("当[^。\\n]{0,60}?(?:时|的时候)[^。\\n]{0,40}").find(text)
        return m?.value?.trim()?.take(120).orEmpty()
    }

    private fun parseArray(arr: JSONArray): MutableList<FormatEntry> {
        val list = mutableListOf<FormatEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val content = o.optString("content")
            if (id.isBlank() || content.isBlank()) continue
            list.add(
                FormatEntry(
                    id = id,
                    title = o.optString("title").ifBlank { "未命名格式" },
                    content = content.take(maxContent),
                    enabled = o.optBoolean("enabled", true),
                    builtin = o.optBoolean("builtin", false),
                    trigger = o.optString("trigger"),
                    createdAt = o.optLong("createdAt")
                )
            )
        }
        return list
    }

    private fun readStored(): MutableList<FormatEntry> = try {
        if (!file.exists()) mutableListOf() else parseArray(JSONArray(file.readText()))
    } catch (_: Exception) { mutableListOf() }

    /** 内置（内容永远以 assets 为准，只沿用存过的开关）+ 自建导入项。 */
    fun load(): MutableList<FormatEntry> {
        cache?.let { return it.toMutableList() }
        val stored = readStored()
        val enabledMap = stored.associate { it.id to it.enabled }
        val contentMap = stored.associate { it.id to it }
        val list = mutableListOf<FormatEntry>()
        builtins().forEach { b ->
            val saved = contentMap[b.id]
            list.add(
                b.copy(
                    enabled = enabledMap[b.id] ?: b.enabled,
                    // 主人若改过内置条的标题/触发说明，尊重主人的
                    title = saved?.title?.takeIf { it.isNotBlank() } ?: b.title,
                    trigger = saved?.trigger ?: b.trigger
                )
            )
        }
        stored.filter { !it.builtin }.forEach { list.add(it) }
        cache = list
        return list.toMutableList()
    }

    private fun loadRef(): MutableList<FormatEntry> {
        cache?.let { return it }
        load()
        return cache ?: mutableListOf<FormatEntry>().also { cache = it }
    }

    fun save(items: List<FormatEntry>) {
        try {
            val arr = JSONArray()
            items.takeLast(maxKeep).forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("title", t.title)
                        .put("content", t.content.take(maxContent))
                        .put("enabled", t.enabled)
                        .put("builtin", t.builtin)
                        .put("trigger", t.trigger)
                        .put("createdAt", t.createdAt)
                )
            }
            file.writeText(arr.toString())
            cache = items.toMutableList()
        } catch (_: Exception) { }
    }

    /** 新增或覆盖同 id 条目。 */
    fun put(t: FormatEntry): Boolean {
        val list = loadRef()
        val idx = list.indexOfFirst { it.id == t.id }
        if (idx >= 0) list[idx] = t else list.add(t)
        save(list)
        return true
    }

    /** 按 id 删（内置项只停用不删）。 */
    fun remove(id: String): Boolean {
        val list = loadRef()
        val t = list.firstOrNull { it.id == id } ?: return false
        if (t.builtin) {
            return setEnabled(id, false)
        }
        val n = list.size
        list.removeAll { it.id == id }
        if (list.size == n) return false
        save(list)
        return true
    }

    fun setEnabled(id: String, on: Boolean): Boolean {
        val list = loadRef()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(enabled = on)
        save(list)
        return true
    }

    /** 精确 → 标题包含 → 内容包含 匹配。 */
    fun find(key: String): FormatEntry? {
        val k = key.trim()
        if (k.isEmpty()) return null
        val list = loadRef()
        list.firstOrNull { it.title == k }?.let { return it }
        list.firstOrNull { it.id == k }?.let { return it }
        list.firstOrNull { it.title.contains(k) || k.contains(it.title) }?.let { return it }
        return list.firstOrNull { it.content.contains(k) }
    }

    fun enabled(): List<FormatEntry> = loadRef().filter { it.enabled && it.content.isNotBlank() }

    /** 注入用的提示词块：只给栏目参考，禁止把 LaTeX 指令回吐进气泡。 */
    fun promptBlock(): String {
        val actives = enabled()
        if (actives.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("【格式库】下面是主人启用的场景参考。剧情需要时，用能读的中文呈现对应栏目，")
        sb.append("不要把参考原文、反斜杠命令、颜色码或版权行写进回复：\n")
        actives.forEachIndexed { i, e ->
            sb.append("\n--- 场景 ").append(i + 1).append("：").append(e.title)
            if (e.trigger.isNotBlank()) sb.append("（何时用：").append(e.trigger).append("）")
            sb.append(" ---\n")
            sb.append(readableGuide(e)).append("\n")
        }
        sb.append(outputRule())
        return sb.toString()
    }

    /**
     * 只注入**某一份**格式（文游功能菜单用：点哪个用哪个）。
     *
     * 和 [promptBlock] 的区别：这里不看启用状态，也不带其他格式，
     * 免得一堆无关排版指令互相干扰。返回空串表示没找到这份格式。
     */
    fun promptBlockFor(id: String): String {
        val list = loadRef()
        val e = list.firstOrNull { it.id == id }
            ?: list.firstOrNull { it.title == id }
            ?: list.firstOrNull { it.title.contains(id) }
            ?: return ""
        if (e.content.isBlank()) return ""
        val sb = StringBuilder()
        sb.append("【文游场景】主人本回合要点开「").append(e.title).append("」。\n")
        sb.append("请用能直接阅读的中文呈现这一页，像手机屏幕上能看见的内容，不要解释你在套格式。\n")
        sb.append("这一页大致该有这些栏目：\n")
        sb.append(readableGuide(e)).append("\n")
        sb.append(outputRule())
        return sb.toString()
    }

    /** 从格式原文里抽出能读的栏目说明，不把整份 LaTeX 塞进提示词。 */
    private fun readableGuide(e: FormatEntry): String {
        val fields = readableFields(e.content)
        val sb = StringBuilder()
        if (e.trigger.isNotBlank()) sb.append("· 何时出现：").append(e.trigger.trim()).append("\n")
        if (fields.isEmpty()) {
            sb.append("· 用短句写出这一页上能看见的中文，每行一条。")
        } else {
            fields.take(12).forEach { sb.append("· ").append(it).append("\n") }
        }
        return sb.toString().trim()
    }

    private fun outputRule(): String {
        return "\n输出要求：这一页必须用一行场景标记交给界面绘制，不要自己画符号。" +
            "标记格式只有这一行：[[scene:类型|标题|第一行|第二行|第三行]]。" +
            "类型只能是 player、moments、chat、profile、browser、contacts、note、system、danmaku、forum、plain。" +
            "播放器：[[scene:player|正在听|梦哑|任然|00:08 / 03:54]]。" +
            "朋友圈：[[scene:moments|朋友圈|小沫|刚刷到一首歌]]。" +
            "聊天：[[scene:chat|聊天|主人|我在听]]。" +
            "资料：[[scene:profile|资料|小沫|微信号|签名]]。" +
            "浏览器：[[scene:browser|浏览器|最近搜索|第一条]]。" +
            "通讯录：[[scene:contacts|通讯录|小沫|主人]]。" +
            "便签：[[scene:note|便签|记下一句]]。" +
            "系统：[[scene:system|系统|第一行|第二行]]。" +
            "弹幕：[[scene:danmaku|弹幕|直播中|弹幕飘过]]。" +
            "讨论：[[scene:forum|讨论|楼主说|回复]]。" +
            "标记前后可以有一句能读的中文，但禁止输出 LaTeX，禁止输出反斜杠命令，" +
            "禁止输出颜色码、.sy 文件名、「禁止二传」「清秋屿」这类版权行，也禁止把格式指令原文贴进回复。"
    }

    /**
     * 各聊天页复用的场景选择：把「本回合要用的那一份格式」记进 Prefs。
     *
     * 主聊天、角色酒馆、群聊三处共用同一套菜单逻辑，行为完全一致
     * （点一个用一次、注入完立刻清空）。
     */
    fun useOnce(id: String) {
        Prefs(context).sceneFormatId = id
    }

    /**
     * 取走当前待用的一次性场景格式块，并**立刻清空**，保证只影响下一条消息。
     *
     * 返回空串表示主人没点场景菜单。
     */
    fun takeSceneOnce(): String {
        val p = Prefs(context)
        val id = p.sceneFormatId
        if (id.isBlank()) return ""
        p.sceneFormatId = ""
        return promptBlockFor(id)
    }

    /** 给菜单挑一个图标（按标题关键词猜，猜不中给个通用的）。 */
    fun iconFor(title: String): String = when {
        title.contains("音乐") || title.contains("播放器") -> "\uD83C\uDFB5"
        title.contains("弹幕") -> "\uD83D\uDCAC"
        title.contains("讨论") || title.contains("帖") || title.contains("论坛") -> "\uD83D\uDCF0"
        title.contains("浏览器") || title.contains("搜索") -> "\uD83D\uDD0D"
        title.contains("群聊") || title.contains("聊天记录") -> "\uD83D\uDC65"
        title.contains("微信") || title.contains("资料") -> "\uD83D\uDCC7"
        title.contains("系统") || title.contains("控制台") || title.contains("终端") -> "\uD83D\uDDA5"
        title.contains("短信") || title.contains("聊天界面") -> "\uD83D\uDC8C"
        title.contains("微博") || title.contains("朋友圈") || title.contains("动态") -> "\uD83C\uDF10"
        title.contains("直播") || title.contains("综艺") -> "\uD83D\uDCFA"
        title.contains("外卖") || title.contains("订单") -> "\uD83C\uDF7D"
        title.contains("邮件") -> "\uD83D\uDCE7"
        else -> "\uD83C\uDFAC"
    }

    /** 导入一段格式指令原文，返回新条目（标题重复时覆盖）。 */
    fun importText(text: String, title: String = "", trigger: String = ""): FormatEntry? {
        val body = text.trim()
        if (body.isBlank()) return null
        val t = title.trim().ifBlank { titleOf(body, "导入格式") }
        val list = loadRef()
        val existing = list.firstOrNull { it.title == t && !it.builtin }
        val entry = FormatEntry(
            id = existing?.id ?: ("import:" + System.currentTimeMillis()),
            title = t,
            content = body.take(maxContent),
            enabled = true,
            builtin = false,
            trigger = trigger.trim().ifBlank { triggerOf(body) }
        )
        put(entry)
        return entry
    }

    /** 导出为 JSON 字符串（给备份用）。 */
    fun exportJson(): String = try {
        val arr = JSONArray()
        loadRef().forEach { t ->
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("title", t.title)
                    .put("content", t.content)
                    .put("enabled", t.enabled)
                    .put("builtin", t.builtin)
                    .put("trigger", t.trigger)
                    .put("createdAt", t.createdAt)
            )
        }
        arr.toString()
    } catch (_: Exception) { "[]" }

    /** 从 JSON 导入（replace=true 覆盖，false 合并）。 */
    fun importJson(text: String, replace: Boolean): Int {
        return try {
            val incoming = parseArray(JSONArray(text))
            if (incoming.isEmpty()) return 0
            if (replace) {
                save(incoming)
            } else {
                val list = loadRef()
                incoming.forEach { t ->
                    val idx = list.indexOfFirst { it.id == t.id || it.title == t.title }
                    if (idx >= 0) list[idx] = t else list.add(t)
                }
                save(list)
            }
            incoming.size
        } catch (_: Exception) { 0 }
    }

    /** 清空缓存（外部改了文件后调用）。 */
    fun invalidate() { cache = null }

    companion object {

        private val DROP_LINE = listOf(
            "禁止二传", "禁止换头", "固定格式", "美化指令", "请用以下", "请使用以下",
            "查看格式", "格式结构", "当玩家", "DeepSeek", "清秋屿", "状态栏", "导航栏",
            "底部留白", "搜索记录查看", "填写说明", "格式规则", "示例", "不要在现格式",
            "禁止出现", "改为剧情", "处写", "按剧情", "保持不变", "依次为", "可按剧情"
        )

        /** 回复里是不是整份文游格式指令被原样贴了出来。 */
        fun looksLikeLatexDump(text: String): Boolean {
            if (text.isBlank()) return false
            val marks = listOf("\\textcolor", "\\begin", "\\fcolorbox", "\\hspace", "\\scalebox", "\\end{array}")
            val hits = marks.count { text.contains(it) }
            if (hits >= 2) return true
            if (text.contains("禁止二传") && text.contains("\\")) return true
            if (text.contains(".sy") && hits >= 1) return true
            return false
        }

        /**
         * 把模型回吐的 LaTeX / 格式指令收成能读的中文。
         * 普通闲聊没有排版命令时原样返回。
         */
        fun sceneModel(raw: String): SceneCardView.Model? {
            if (raw.isBlank()) return null
            val marked = parseSceneMark(raw)
            if (marked != null) return marked
            if (!looksLikeLatexDump(raw) && !looksLikeScenePage(raw)) return null
            val head = sceneHead(raw) ?: return null
            return SceneCardView.Model(head.second, head.first, sceneLines(raw, head.first))
        }

        /** 气泡认得出的场景标记：[[scene:player|正在听|梦哑|任然]] */
        fun parseSceneMark(raw: String): SceneCardView.Model? {
            val m = Regex("\\[\\[scene:([a-z]+)\\|([^\\]]+)]]").find(raw) ?: return null
            val kind = m.groupValues[1]
            if (kind !in setOf("player", "moments", "chat", "profile", "browser", "contacts", "note", "system", "danmaku", "forum", "plain")) return null
            val parts = m.groupValues[2].split("|").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            return SceneCardView.Model(kind, parts.first(), parts.drop(1).take(6))
        }

        private fun sceneHead(raw: String): Pair<String, String>? = when {
            raw.contains("音乐名") || raw.contains("演唱") || raw.contains("播放器") || raw.contains("正在听") ->
                "正在听" to "player"
            raw.contains("朋友圈") -> "朋友圈" to "moments"
            raw.contains("通讯录") -> "通讯录" to "contacts"
            raw.contains("搜索记录") || raw.contains("浏览器") -> "浏览器" to "browser"
            raw.contains("便签") -> "便签" to "note"
            raw.contains("控制台") || raw.contains("系统管理员") -> "系统" to "system"
            raw.contains("弹幕") || raw.contains("直播") -> "弹幕" to "danmaku"
            raw.contains("讨论") || raw.contains("帖子") || raw.contains("论坛") -> "讨论" to "forum"
            raw.contains("个人资料") || raw.contains("资料页") || raw.contains("我的页面") -> "资料" to "profile"
            raw.contains("微信") || raw.contains("聊天") || raw.contains("气泡") || raw.contains("群聊") -> "聊天" to "chat"
            else -> null
        }

        private fun sceneLines(raw: String, title: String): List<String> {
            val noise = listOf(
                "音乐", "播放", "歌手", "关注", "格式", "禁止", "清秋", "示例", "填写", "顶部",
                "右侧", "后面", "循环", "上一首", "下一首", "列表", "暂停", "唱片", "色值",
                "银灰", "换行", "规则", "微信", "朋友圈", "通讯录", "搜索", "浏览器", "便签",
                "控制台", "弹幕", "讨论", "资料", "聊天", "页面", "指令", "美化", "内容", "图标",
                "正在听", "收藏", "评论", "分享", title
            )
            return plainBits(raw).filter { bit -> noise.none { bit.contains(it) } }.take(6)
        }

        /** 已经收成「正在听 / 歌名 / 歌手」这类短页时，也要能画，不只认 LaTeX。 */
        private fun looksLikeScenePage(raw: String): Boolean {
            val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(8).toList()
            if (lines.size !in 2..8) return false
            val head = lines.first()
            if (head !in setOf("正在听", "朋友圈", "通讯录", "浏览器", "便签", "系统", "弹幕", "讨论", "资料", "聊天")) {
                return false
            }
            return lines.drop(1).any { it.length in 2..16 }
        }

        fun readableScene(raw: String): String {
            if (raw.isBlank() || !looksLikeLatexDump(raw)) return raw
            val card = sceneCard(raw)
            if (card.isNotBlank()) return card
            val fields = readableFields(raw)
            if (fields.isEmpty()) return "这一页我收成能读的内容了，不过刚才那份格式里没有可显示的中文。"
            return fields.take(8).joinToString("\n")
        }

        /** 认出是哪一页后，画成能读的短卡片，不再贴指令。 */
        private fun sceneCard(raw: String): String {
            val bits = plainBits(raw)
            val head = when {
                raw.contains("音乐名") || raw.contains("演唱") || raw.contains("播放器") -> "正在听"
                raw.contains("朋友圈") -> "朋友圈"
                raw.contains("通讯录") -> "通讯录"
                raw.contains("搜索记录") || raw.contains("浏览器") -> "浏览器"
                raw.contains("便签") -> "便签"
                raw.contains("控制台") || raw.contains("系统管理员") -> "系统"
                raw.contains("弹幕") -> "弹幕"
                raw.contains("讨论") || raw.contains("帖子") -> "讨论"
                raw.contains("个人资料") || raw.contains("资料页") -> "资料"
                raw.contains("微信") || raw.contains("聊天") -> "聊天"
                else -> ""
            }
            if (head.isBlank()) return ""
            val noise = listOf(
                "音乐", "播放", "歌手", "关注", "格式", "禁止", "清秋", "示例", "填写", "顶部",
                "右侧", "后面", "循环", "上一首", "下一首", "列表", "暂停", "唱片", "色值",
                "银灰", "换行", "规则", "微信", "朋友圈", "通讯录", "搜索", "浏览器", "便签",
                "控制台", "弹幕", "讨论", "资料", "聊天", "页面", "指令", "美化", "内容", "图标"
            )
            val named = bits.filter { bit -> noise.none { bit.contains(it) } && bit.length in 2..16 }
            val sb = StringBuilder()
            sb.append(head)
            named.take(6).forEach { sb.append("\n").append(it) }
            if (head == "正在听") {
                sb.append("\n收藏    评论    分享")
                sb.append("\n上一首    播放    下一首")
            }
            return sb.toString().trim()
        }

        /** 先剥掉排版命令，再抠出短中文。 */
        private fun plainBits(raw: String): List<String> {
            var t = raw.replace("\r\n", "\n")
            t = t.replace(Regex("\\\\[A-Za-z]{1,24}\\*?"), " ")
            t = t.replace(Regex("#[0-9A-Fa-f]{3,8}"), " ")
            t = t.replace(Regex("\\d+(?:\\.\\d+)?\\s*(?:cm|mm|ex|pt|em)"), " ")
            t = t.replace(Regex("[{}$%&^~_()\\[\\]\\\\]"), " ")
            val chunks = t.split(Regex("[\\s,，。；;：:、|/]+"))
            return chunks.map { it.trim() }.filter { bit ->
                bit.length in 2..12 &&
                    bit.count { ch -> ch.code in 0x4E00..0x9FFF } >= 2 &&
                    DROP_LINE.none { bit.contains(it) } &&
                    !bit.contains("格式") &&
                    !bit.contains("美化") &&
                    !bit.contains("禁止") &&
                    !bit.contains("说明") &&
                    !bit.contains("内容") &&
                    !bit.contains("图标") &&
                    !bit.contains("时间")
            }.distinct().toList()
        }

        private fun readableFields(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            var t = raw.replace("\r\n", "\n").replace("\r", "\n")
            t = t.replace(Regex("\\\\[()\\[\\]]"), " ")
            t = t.replace(
                Regex("\\\\(?:textcolor|textsf|textbf|textit|texttt|textrm|text|colorbox|fcolorbox|scalebox|resizebox|raisebox|hspace|vspace|rule|scriptsize|tiny|small|large|Large|huge|Huge|bfseries|itshape|ttfamily|sffamily|rmfamily|begin|end|color|fontsize|selectfont|displaystyle|textstyle)\\*?"),
                " "
            )
            t = t.replace(Regex("\\\\[A-Za-z]{1,24}\\*?"), " ")
            t = t.replace(Regex("#[0-9A-Fa-f]{3,8}"), " ")
            t = t.replace(Regex("\\d+(?:\\.\\d+)?\\s*(?:cm|mm|ex|pt|em)"), " ")
            t = t.replace(Regex("[{}$%&^~_()]"), " ")
            t = t.replace(Regex("(?i)\\b(?:array|ll|id名|文案内容|第二行文案|第三行文案|消息内容|时间戳|头像色|可选)\\b"), " ")
            return t.lineSequence().map { line ->
                line.replace(Regex("[\\\\|_]{2,}"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim(' ', '-', '·', '｜', '|', '>', '<', '，', ',', '。', ':', '：', '1', '2', '3', '4', '5', '.', '、')
            }.filter { line ->
                line.length in 2..24 &&
                    DROP_LINE.none { line.contains(it) } &&
                    line.count { ch -> ch.code in 0x4E00..0x9FFF } >= 2 &&
                    !line.any { it.isDigit() && line.contains("cm") } &&
                    !line.contains("美化") &&
                    !line.contains("格式") &&
                    !line.endsWith(".sy")
            }.distinct().take(12).toList()
        }

        /** 只保留多行、且不是「普通聊天」的文本才当格式指令。 */
        fun looksLikeFormat(text: String): Boolean {
            val t = text.trim()
            if (t.length < 60) return false
            if (t.lines().size < 5) return false
            return true
        }

        /**
         * 从口令里抽「格式名」。
         *
         * 支持：`启用格式 微信资料页`、`停用格式 微信`、`删除格式 微信`、
         * `套用格式 微信`、`查看格式 微信`
         */
        fun nameFromText(text: String): String? {
            val keys = listOf(
                "启用格式指令", "开启格式指令", "停用格式指令", "关闭格式指令",
                "删除格式指令", "移除格式指令", "导入格式指令",
                "启用格式库", "开启格式库", "停用格式库", "关闭格式库", "删除格式库",
                "查看格式指令", "预览格式指令",
                "启用格式", "开启格式", "停用格式", "关闭格式", "删除格式", "移除格式",
                "套用格式", "应用格式", "查看格式", "预览格式", "导入格式", "上传格式"
            ).sortedByDescending { it.length }
            for (k in keys) {
                val idx = text.indexOf(k)
                if (idx < 0) continue
                val rest = text.substring(idx + k.length)
                    .trim()
                    .trimStart('：', ':', ' ', '，', ',', '「', '“')
                    .trim()
                var name = rest
                for (tail in listOf("吧", "呀", "哦", "啊", "。", "！", "!", ".", "的格式", "格式")) {
                    if (name.endsWith(tail)) name = name.dropLast(tail.length).trim()
                }
                // 收尾的「」”引号
                name = name.trimEnd('」', '”', '"', '\'')
                if (name.isNotBlank() && name.length <= 24) return name
            }
            return null
        }
    }
}
