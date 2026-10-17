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

    /** 注入用的提示词块：把启用的格式拼成一段「必须照此输出」的规则。 */
    fun promptBlock(): String {
        val actives = enabled()
        if (actives.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("【格式库】以下是主人指定的**固定输出格式**，当剧情需要用到对应内容时，")
        sb.append("必须严格按格式生成，不要自创排版、不要省略字段、不要解释格式本身：\n")
        actives.forEachIndexed { i, e ->
            sb.append("\n--- 格式 ").append(i + 1).append("：").append(e.title)
            if (e.trigger.isNotBlank()) sb.append("（触发：").append(e.trigger).append("）")
            sb.append(" ---\n")
            sb.append(e.content.trim()).append("\n")
        }
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
        sb.append("【文游场景·本回合专用格式】主人刚刚点名要用「").append(e.title).append("」。\n")
        sb.append("请**严格按下面这份固定格式**呈现本回合的内容，")
        sb.append("不要自创排版、不要省略字段、不要解释格式本身、不要混用别的格式：\n")
        sb.append(e.content.trim()).append("\n")
        sb.append("\n注意：只输出这一份格式的内容，不要加额外说明。").append("\n")
        return sb.toString()
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
