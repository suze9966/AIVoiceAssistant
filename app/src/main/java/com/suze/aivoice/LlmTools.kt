package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 大模型可调用的本地工具。闲聊不必用；认真提问、天气、记忆、翻译等按需调用。
 * 不把整段聊天当记忆，也不把工具结果写进可见对话。
 */
interface LlmToolHost {
    fun webSearch(query: String): String
    suspend fun weather(city: String): String
    fun recallMemory(query: String): String
    fun translate(text: String, target: String): String
    fun convert(amount: Double, from: String, to: String): String
    fun worldClock(place: String): String
    fun news(): String
    fun listTodos(): String
    fun addTodo(text: String): String
    fun listReminders(): String
}

class DefaultLlmToolHost(
    private val context: Context,
    private val prefs: Prefs,
    private val searcher: SearchClient,
    private val weather: WeatherClient? = null,
    private val memory: MemoryEngine? = null,
    private val utilities: UtilityClient? = null,
    private val todos: TodoStore? = null,
    private val reminders: ReminderStore? = null
) : LlmToolHost {

    override fun webSearch(query: String): String = searcher.gatherNotes(query)

    override suspend fun weather(city: String): String {
        val client = weather ?: return "天气工具还没接上"
        val q = city.trim().ifBlank { prefs.lastCity }
        val info = client.query(q) ?: return "没查到${q.ifBlank { "本地" }}的天气"
        if (info.place.isNotBlank()) prefs.lastCity = info.place
        prefs.lastWeatherBrief = info.toSpeakText().take(48)
        runCatching { XiaomoWidgetProvider.refresh(context) }
        return info.toSpeakText().take(800)
    }

    override fun recallMemory(query: String): String {
        val engine = memory ?: return "记忆工具还没接上"
        val hits = engine.search(query)
        if (hits.isEmpty()) return "记忆里没有找到相关内容"
        return hits.joinToString("\n") { "[${it.type}] ${it.content}" }
    }

    override fun translate(text: String, target: String): String {
        val client = utilities ?: return "翻译工具还没接上"
        return client.translate(text, target).ifBlank { "这次没译出来" }
    }

    override fun convert(amount: Double, from: String, to: String): String {
        val client = utilities ?: return "换算工具还没接上"
        return client.convert(amount, from, to).ifBlank { "这次没换算出来" }
    }

    override fun worldClock(place: String): String {
        val client = utilities ?: return "世界时钟还没接上"
        return client.worldClock(place)
    }

    override fun news(): String {
        val client = utilities ?: return "新闻工具还没接上"
        return client.news().ifBlank { "这次没拉到新闻" }
    }

    override fun listTodos(): String = todos?.formatList() ?: "待办工具还没接上"

    override fun addTodo(text: String): String {
        val store = todos ?: return "待办工具还没接上"
        val t = text.trim()
        if (t.isEmpty()) return "没有待办内容"
        val item = store.add(t)
        return "已记下待办：${item.text}"
    }

    override fun listReminders(): String {
        val store = reminders ?: return "提醒工具还没接上"
        val items = store.upcoming()
        if (items.isEmpty()) return "现在没有提醒"
        return items.joinToString("\n") { store.formatItem(it) }
    }
}

object LlmTools {
    const val MAX_RESULT_CHARS = 2400

    fun schema(webSearchEnabled: Boolean): JSONArray {
        val arr = JSONArray()
        if (webSearchEnabled) {
            arr.put(
                function(
                    "web_search",
                    "查找百科、时事、资料或需要核对的事实。闲聊、情绪陪伴不要调用。",
                    required("query" to "搜索关键词或完整问题")
                )
            )
        }
        arr.put(
            function(
                "get_weather",
                "查询某地实时天气、体感和未来几天概况。没说城市就查主人常用或上次的城市。",
                optional("city" to "城市名，可空")
            )
        )
        arr.put(
            function(
                "recall_memory",
                "检索主人本机记忆里的偏好、事实或约定。不要把整段聊天当记忆。",
                required("query" to "要找的关键词，如名字、喜好、约定")
            )
        )
        arr.put(
            function(
                "translate",
                "把文本翻译成目标语言。",
                JSONObject()
                    .put("text", str("要翻译的原文"))
                    .put("target", str("目标语言，如英语、日语、韩语"))
                    .put("required", JSONArray().put("text").put("target"))
            )
        )
        arr.put(
            function(
                "convert_currency",
                "货币换算，如美元兑人民币。",
                JSONObject()
                    .put("amount", JSONObject().put("type", "number").put("description", "金额"))
                    .put("from", str("源货币，如美元、人民币"))
                    .put("to", str("目标货币"))
                    .put("required", JSONArray().put("amount").put("from").put("to"))
            )
        )
        arr.put(
            function(
                "world_clock",
                "查询某地当前时间。",
                required("place" to "城市或地区，如纽约、东京、伦敦")
            )
        )
        arr.put(
            function(
                "get_news",
                "获取最近新闻快讯。只有主人想听新闻时才调用。",
                JSONObject()
            )
        )
        arr.put(
            function(
                "list_todos",
                "查看主人本机待办/备忘。",
                JSONObject()
            )
        )
        arr.put(
            function(
                "add_todo",
                "给主人本机待办加一条。只有明确要记住、待办、备忘时才调用。",
                required("text" to "待办内容")
            )
        )
        arr.put(
            function(
                "list_reminders",
                "查看主人即将到来的提醒。",
                JSONObject()
            )
        )
        arr.put(
            function(
                "current_time",
                "获取主人手机当前日期和时间。",
                JSONObject()
            )
        )
        return arr
    }

    suspend fun execute(name: String, arguments: String, host: LlmToolHost): String {
        val args = try {
            JSONObject(arguments.ifBlank { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }
        val raw = when (name) {
            "web_search" -> {
                val q = args.optString("query").trim()
                if (q.isEmpty()) "没有搜索词" else host.webSearch(q).ifBlank { "网上没查到可用资料" }
            }
            "get_weather" -> host.weather(args.optString("city"))
            "recall_memory" -> host.recallMemory(args.optString("query"))
            "translate" -> {
                val text = args.optString("text").trim()
                if (text.isEmpty()) "没有要翻译的文本"
                else host.translate(text, args.optString("target").ifBlank { "英语" })
            }
            "convert_currency" -> {
                val amount = args.optDouble("amount", Double.NaN)
                if (amount.isNaN()) "没有金额"
                else host.convert(amount, args.optString("from"), args.optString("to"))
            }
            "world_clock" -> host.worldClock(args.optString("place"))
            "get_news" -> host.news()
            "list_todos" -> host.listTodos()
            "add_todo" -> host.addTodo(args.optString("text"))
            "list_reminders" -> host.listReminders()
            "current_time" -> currentTime()
            else -> "没有这个工具：$name"
        }
        return raw.trim().take(MAX_RESULT_CHARS)
    }

    fun statusLabel(name: String): String = when (name) {
        "web_search" -> "正在查网上资料…"
        "get_weather" -> "正在查天气…"
        "recall_memory" -> "正在翻记忆…"
        "translate" -> "正在翻译…"
        "convert_currency" -> "正在换算…"
        "world_clock" -> "正在看世界时钟…"
        "get_news" -> "正在看新闻…"
        "list_todos", "add_todo" -> "正在看待办…"
        "list_reminders" -> "正在看提醒…"
        "current_time" -> "正在看时间…"
        else -> "正在调用工具…"
    }

    fun currentTime(): String {
        val fmt = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)
        return "现在是 " + fmt.format(Date())
    }

    private fun function(name: String, description: String, fields: JSONObject): JSONObject {
        val params = JSONObject()
            .put("type", "object")
            .put("properties", fields.optJSONObject("properties") ?: propertiesOf(fields))
        val required = fields.optJSONArray("required")
        if (required != null && required.length() > 0) params.put("required", required)
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", description)
                    .put("parameters", params)
            )
    }

    private fun propertiesOf(fields: JSONObject): JSONObject {
        val props = JSONObject()
        val keys = fields.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "required") continue
            props.put(key, fields.get(key))
        }
        return props
    }

    private fun required(vararg pairs: Pair<String, String>): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        pairs.forEach { (name, desc) ->
            props.put(name, str(desc))
            req.put(name)
        }
        return JSONObject().put("properties", props).put("required", req)
    }

    private fun optional(vararg pairs: Pair<String, String>): JSONObject {
        val props = JSONObject()
        pairs.forEach { (name, desc) -> props.put(name, str(desc)) }
        return JSONObject().put("properties", props)
    }

    private fun str(description: String): JSONObject =
        JSONObject().put("type", "string").put("description", description)
}
