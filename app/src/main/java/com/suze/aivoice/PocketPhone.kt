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
 * 不是系统模拟器，是文游里那部能点开的手机：微信、浏览器、播放器、便签、朋友圈
 * 都走格式库里的固定排版。主人可以自己点，也可以让小沫自己玩一轮。
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
        App("wechat", "\uD83D\uDCAC", "微信", "主页 · 聊天 · 通讯录", "仿微信主页"),
        App("me", "\uD83D\uDC64", "我", "个人页与资料", "仿微信“我”"),
        App("contacts", "\uD83D\uDCC7", "通讯录", "好友与添加", "仿微信通讯录"),
        App("discover", "\uD83D\uDD0E", "发现", "朋友圈入口", "仿微信发现"),
        App("moments", "\uD83C\uDF05", "朋友圈", "刷一条、发一条", "仿朋友圈"),
        App("browser", "\uD83C\uDF10", "浏览器", "搜索记录", "浏览器搜索"),
        App("player", "\uD83C\uDFB5", "播放器", "正在听", "音乐播放器"),
        App("notes", "\uD83D\uDCDD", "便签", "随手记", "便签"),
        App("chat", "\uD83D\uDC8C", "气泡", "聊天气泡", "聊天气泡")
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
        sb.append(clock()).append("    ").append(battery()).append("%\n")
        sb.append(ownerName()).append("的小手机\n")
        sb.append(moodLine()).append("\n")
        apps().forEach { a ->
            sb.append(a.icon).append("  ").append(a.name)
            sb.append("    ").append(a.hint).append("\n")
        }
        val latest = feeds().take(3)
        if (latest.isNotEmpty()) {
            sb.append("\n最近动态\n")
            latest.forEach { f ->
                sb.append("· ").append(f.title).append("\n")
            }
        }
        return sb.toString().trim()
    }

    fun findFormat(app: App): FormatEntry? {
        val list = formats.load()
        val key = app.formatKey
        return list.firstOrNull { it.title.contains(key) || it.content.contains(key) }
            ?: list.firstOrNull { key.contains("浏览器") && (it.title.contains("搜索") || it.content.contains("搜索记录")) }
            ?: list.firstOrNull { key.contains("播放器") && it.title.contains("音乐") }
            ?: list.firstOrNull { key.contains("便签") && it.title.contains("便签") }
            ?: list.firstOrNull { key.contains("气泡") && it.title.contains("气泡") }
            ?: list.firstOrNull { key.contains("朋友圈") && it.title.contains("朋友圈") }
    }

    fun screenFor(app: App, extra: String = ""): String {
        val fmt = findFormat(app)
        val sb = StringBuilder()
        sb.append(app.icon).append(" ").append(app.name).append("\n")
        if (extra.isNotBlank()) sb.append(extra.trim()).append("\n\n")
        if (fmt == null) {
            sb.append("这个页面的格式还没装进格式库。")
            return sb.toString()
        }
        sb.append(fmt.content.trim())
        return sb.toString()
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

    /** 给小沫自己玩用的系统提示：只许输出这一页的固定格式。 */
    fun playPrompt(app: App, wish: String): List<ChatMessage> {
        val fmt = findFormat(app)
        val sys = buildString {
            append("你是小沫，正在自己的虚拟小手机里玩「").append(app.name).append("」。\n")
            append("主人是").append(ownerName()).append("。现在时间 ").append(clock()).append("。\n")
            append("只输出这一页该有的内容，不要解释格式，不要说自己是模型。\n")
            append("内容要短、像真的在用手机：微信消息单条不超过 20 字，朋友圈一条，搜索记录 3 到 6 条。\n")
            append("可以记挂主人，但不要替主人做真实操作，也不要声称已经发出了真短信或真微信。\n")
            if (wish.isNotBlank()) append("主人刚说：").append(wish.trim()).append("\n")
            if (fmt != null) {
                append("\n必须严格使用下面这份固定格式：\n")
                append(fmt.content.trim())
            }
        }
        return listOf(
            ChatMessage(role = "system", content = sys, isMe = false),
            ChatMessage(role = "user", content = "打开${app.name}，玩一下。", isMe = true)
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
