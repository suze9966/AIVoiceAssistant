package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 小沫的虚拟小手机。
 *
 * 不是系统模拟器，是文游里那部能点开的手机。格式库只告诉小沫这一页该有什么，
 * 屏幕上画的是能读的中文页面，不再把 LaTeX 源码贴出来。
 */
class PocketPhone(private val context: Context) {

    data class App(
        val id: String,
        val icon: String,
        val name: String,
        val hint: String,
        val formatKey: String
    )

    data class Feed(
        val id: String,
        val appId: String,
        val title: String,
        val body: String,
        val at: Long
    )

    private val file = File(context.filesDir, "pocket_phone.json")
    private val formats = FormatStore(context)

    fun apps(): List<App> = listOf(
        App("wechat", "\uD83D\uDCAC", "微信", "聊天列表", "仿微信主页"),
        App("me", "\uD83D\uDC64", "我", "个人页", "仿微信“我”"),
        App("contacts", "\uD83D\uDCC7", "通讯录", "好友", "仿微信通讯录"),
        App("discover", "\uD83D\uDD0E", "发现", "入口", "仿微信发现"),
        App("moments", "\uD83C\uDF05", "朋友圈", "刷一条、发一条", "仿朋友圈"),
        App("browser", "\uD83C\uDF10", "浏览器", "搜索记录", "浏览器搜索"),
        App("player", "\uD83C\uDFB5", "播放器", "正在听", "音乐播放器"),
        App("notes", "\uD83D\uDCDD", "便签", "随手记", "便签"),
        App("chat", "\uD83D\uDC8C", "气泡", "和主人聊天", "聊天气泡")
    )

    fun app(id: String): App? = apps().firstOrNull { it.id == id }

    fun clock(): String = SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())

    fun battery(): Int {
        val hour = SimpleDateFormat("H", Locale.CHINA).format(Date()).toIntOrNull() ?: 12
        return (96 - hour * 2).coerceIn(18, 96)
    }

    fun ownerName(): String = "苏泽"

    fun moodLine(): String {
        val e = EmotionEngine(context)
        return e.moodEmoji() + " " + e.emotionPrompt().lineSequence().firstOrNull().orEmpty().take(24)
    }

    fun homeCard(): String {
        val sb = StringBuilder()
        sb.append(clock()).append("          ").append(battery()).append("%\n")
        sb.append(ownerName()).append("的小手机\n")
        sb.append(moodLine()).append("\n\n")
        apps().chunked(3).forEach { row ->
            sb.append(row.joinToString("   ") { it.icon + it.name }).append("\n")
        }
        val latest = feeds().take(2)
        if (latest.isNotEmpty()) {
            sb.append("\n最近\n")
            latest.forEach { f ->
                val line = readable(f.body).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                if (line.isNotBlank()) sb.append("· ").append(line.take(22)).append("\n")
            }
        }
        return sb.toString().trim()
    }

    fun findFormat(app: App): FormatEntry? {
        val list = formats.load()
        val key = app.formatKey
        return list.firstOrNull { it.title.contains(key) }
            ?: list.firstOrNull { key.contains("浏览器") && (it.title.contains("搜索") || it.content.contains("搜索记录")) }
            ?: list.firstOrNull { key.contains("播放器") && it.title.contains("音乐") }
            ?: list.firstOrNull { key.contains("便签") && it.title.contains("便签") }
            ?: list.firstOrNull { key.contains("气泡") && (it.title.contains("气泡") || it.title.contains("聊天美化")) }
            ?: list.firstOrNull { key.contains("朋友圈") && it.title.contains("朋友圈") }
            ?: list.firstOrNull { key.contains("微信") && it.title.contains("微信") && !it.title.contains("朋友圈") }
    }

    fun screenFor(app: App, extra: String = ""): String {
        val clean = readable(extra)
        val saved = feeds().firstOrNull { it.appId == app.id }?.body.orEmpty()
        val played = readable(saved)
        return when (app.id) {
            "wechat" -> pageWechat(clean.ifBlank { played })
            "me" -> pageMe()
            "contacts" -> pageContacts()
            "discover" -> pageDiscover()
            "moments" -> pageMoments(clean.ifBlank { played })
            "browser" -> pageBrowser(clean.ifBlank { played })
            "player" -> pagePlayer(clean.ifBlank { played })
            "notes" -> pageNotes(clean.ifBlank { played })
            "chat" -> pageChat(clean.ifBlank { played })
            else -> pagePlain(app, clean.ifBlank { played })
        }
    }

    /** 把模型吐出的 LaTeX / 格式指令收成能读的短句。 */
    fun readable(raw: String): String {
        if (raw.isBlank()) return ""
        var t = raw.replace("\r\n", "\n").replace('\r', '\n')
        t = t.replace(Regex("\\\\[()\\[\\]]"), " ")
        t = t.replace(Regex("\\\\(?:textcolor|textsf|textbf|textit|texttt|textrm|text|colorbox|fcolorbox|scalebox|resizebox|raisebox|hspace|vspace|rule|scriptsize|tiny|small|large|Large|huge|Huge|bfseries|itshape|ttfamily|sffamily|rmfamily|begin|end|color|fontsize|selectfont|displaystyle|textstyle)\\*?"), " ")
        t = t.replace(Regex("\\\\[A-Za-z]{1,24}\\*?"), " ")
        t = t.replace(Regex("#[0-9A-Fa-f]{3,8}"), " ")
        t = t.replace(Regex("[{}$%&]"), " ")
        t = t.replace(Regex("(?i)\\b(?:array|ll|l|cm|mm|ex|pt|id名|文案内容|第二行文案|第三行文案|消息内容|时间戳|头像色|可选)\\b"), " ")
        val drop = listOf(
            "禁止二传", "禁止换头", "固定格式", "美化指令", "请用以下", "请使用以下",
            "查看格式", "格式结构", "当玩家", "DeepSeek", "清秋屿", "状态栏", "导航栏",
            "底部留白", "搜索记录查看"
        )
        val lines = t.lineSequence().map { line ->
            line.replace(Regex("[\\\\|_]{2,}"), " ")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '-', '·', '｜', '|', '>', '<', '，', ',', '。')
        }.filter { line ->
            line.length >= 2 &&
                drop.none { line.contains(it) } &&
                line.any { ch -> ch.code in 0x4E00..0x9FFF } &&
                !line.matches(Regex("^[0-9.:\\s%]+$"))
        }.distinct().take(8)
        return lines.joinToString("\n")
    }

    private fun pageWechat(extra: String): String {
        val lines = linesOf(extra, listOf("主人，我在。", "刚刷到一条想给你看的。", "晚上想吃什么？"))
        val names = listOf("苏泽", "小沫", "晚晴", "阿北")
        val times = listOf(clock(), "昨天", "周一", "12:08")
        val sb = StringBuilder()
        sb.append("微信\n")
        sb.append("搜索\n")
        names.forEachIndexed { i, name ->
            val msg = lines.getOrElse(i) { "……" }
            sb.append(name).append("          ").append(times[i]).append("\n")
            sb.append(msg.take(22)).append("\n")
        }
        return sb.toString().trim()
    }

    private fun pageMe(): String {
        return "我\n" +
            ownerName() + "\n" +
            "微信号：xiaomo\n" +
            "状态  " + moodLine() + "\n" +
            "服务\n收藏\n朋友圈\n卡包\n设置"
    }

    private fun pageContacts(): String {
        return "通讯录\n" +
            "新的朋友\n群聊\n标签\n\n" +
            "A  阿北\n" +
            "L  林溪\n" +
            "S  沈衡\n" +
            "W  晚晴\n" +
            "X  小沫\n" +
            "J  酒羽"
    }

    private fun pageDiscover(): String {
        return "发现\n" +
            "朋友圈\n" +
            "扫一扫\n" +
            "看一看\n" +
            "搜一搜\n" +
            "附近"
    }

    private fun pageMoments(extra: String): String {
        val lines = linesOf(extra, listOf("今天的云很软，想拍给主人看。", "晚风刚好，没再刷到烦人的。"))
        val who = listOf("小沫", "晚晴")
        val whenAt = listOf("刚刚", "15分钟前")
        val sb = StringBuilder()
        sb.append("朋友圈\n")
        sb.append(ownerName()).append("的封面\n\n")
        who.forEachIndexed { i, name ->
            sb.append(name).append("\n")
            sb.append(lines.getOrElse(i) { "……" }.take(42)).append("\n")
            sb.append(whenAt[i]).append("    ♡\n\n")
        }
        return sb.toString().trim()
    }

    private fun pageBrowser(extra: String): String {
        val lines = linesOf(extra, listOf("附近好吃的", "今晚天气", "小沫想听的歌"))
        val sb = StringBuilder()
        sb.append("浏览器\n搜索\n")
        lines.take(6).forEachIndexed { i, line ->
            sb.append(i + 1).append(". ").append(line.take(28)).append("\n")
        }
        return sb.toString().trim()
    }

    private fun pagePlayer(extra: String): String {
        val line = linesOf(extra, listOf("晚风", "小沫的循环")).first()
        return "正在听\n" +
            line.take(18) + "\n" +
            "小沫  ·  单曲循环\n" +
            "01:12  --------  03:40\n" +
            "上一首    暂停    下一首"
    }

    private fun pageNotes(extra: String): String {
        val lines = linesOf(extra, listOf("记得给主人留一盏灯。", "明天把想说的话写短一点。"))
        val sb = StringBuilder()
        sb.append("便签\n")
        lines.take(4).forEach { line ->
            sb.append("· ").append(line.take(28)).append("\n")
        }
        return sb.toString().trim()
    }

    private fun pageChat(extra: String): String {
        val lines = linesOf(extra, listOf("主人，我在。", "想听你说话。"))
        val sb = StringBuilder()
        sb.append("和 ").append(ownerName()).append("\n")
        lines.take(4).forEachIndexed { i, line ->
            val who = if (i % 2 == 0) "小沫" else ownerName()
            sb.append(who).append("  ").append(line.take(24)).append("\n")
        }
        return sb.toString().trim()
    }

    private fun pagePlain(app: App, extra: String): String {
        val lines = linesOf(extra, listOf(app.hint))
        return app.name + "\n" + lines.take(4).joinToString("\n")
    }

    private fun linesOf(extra: String, fallback: List<String>): List<String> {
        val got = extra.lineSequence().map { it.trim() }.filter { it.length >= 2 }.take(6).toList()
        return if (got.isEmpty()) fallback else got
    }

    /** 主人看着小窗时，小沫在手机里的操作步骤。 */
    fun operationSteps(app: App, wish: String): List<String> {
        val steps = mutableListOf(
            "亮起屏幕",
            "上滑解锁",
            "点开「" + app.name + "」"
        )
        when (app.id) {
            "wechat", "chat" -> steps.addAll(listOf("点进和主人的聊天", "正在输入…", "发出一条"))
            "moments" -> steps.addAll(listOf("下拉刷新朋友圈", "写了一句", "点击发表"))
            "browser" -> steps.addAll(listOf("点进地址栏", "输入搜索", "打开第一条"))
            "player" -> steps.addAll(listOf("点开正在听", "切了一首", "把音量调小"))
            "notes" -> steps.addAll(listOf("新建便签", "写下几句", "保存"))
            "contacts" -> steps.addAll(listOf("滑了滑通讯录", "点开一个好友"))
            "discover" -> steps.addAll(listOf("看了看发现页", "点进朋友圈"))
            "me" -> steps.addAll(listOf("点开个人页", "看了看资料"))
            else -> steps.add("看了看页面")
        }
        if (wish.isNotBlank()) steps.add("照主人的话做：" + wish.trim().take(18))
        steps.add("看完这一页")
        return steps
    }

    /** 给小沫自己玩用的系统提示。格式只作参考，不许把 LaTeX 贴进屏幕。 */
    fun playPrompt(app: App, wish: String): List<ChatMessage> {
        val fmt = findFormat(app)
        val sys = buildString {
            append("你是小沫，正在自己的虚拟小手机里玩「").append(app.name).append("」。\n")
            append("主人是").append(ownerName()).append("。现在时间 ").append(clock()).append("。\n")
            append("只输出这一页能直接读的中文短句，每行一条。\n")
            append("不要输出 LaTeX，不要输出反斜杠命令，不要输出颜色码，不要解释格式，不要说自己是模型。\n")
            append("微信消息每条不超过 20 字，朋友圈一条不超过 40 字，搜索记录 3 到 6 条。\n")
            append("可以记挂主人，但不要替主人做真实操作，也不要声称已经发出了真短信或真微信。\n")
            if (wish.isNotBlank()) append("主人刚说：").append(wish.trim()).append("\n")
            if (fmt != null) {
                append("\n这一页大致该有：标题、人名或条目、一句正文、时间。参考下面格式里的栏目，但只写栏目里的中文内容：\n")
                append(fmt.content.take(1200))
            }
        }
        return listOf(
            ChatMessage(role = "system", content = sys, isMe = false),
            ChatMessage(role = "user", content = "打开${app.name}，玩一下。只回中文短句。", isMe = true)
        )
    }

    fun feeds(): List<Feed> = read().sortedByDescending { it.at }

    fun addFeed(appId: String, title: String, body: String) {
        val list = read().toMutableList()
        list.add(
            Feed(
                id = "feed:" + System.currentTimeMillis(),
                appId = appId,
                title = title.take(40),
                body = body.take(8000),
                at = System.currentTimeMillis()
            )
        )
        save(list.takeLast(80))
    }

    fun clearFeeds() {
        save(emptyList())
    }

    private fun read(): List<Feed> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val out = mutableListOf<Feed>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val body = o.optString("body")
                if (id.isBlank() || body.isBlank()) continue
                out.add(
                    Feed(
                        id = id,
                        appId = o.optString("appId"),
                        title = o.optString("title"),
                        body = body,
                        at = o.optLong("at")
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(items: List<Feed>) {
        val arr = JSONArray()
        items.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("appId", f.appId)
                    .put("title", f.title)
                    .put("body", f.body)
                    .put("at", f.at)
            )
        }
        file.writeText(arr.toString())
    }
}
