package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.regex.Pattern

data class TavernPlugin(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val trigger: String = "",
    val replace: String = "",
    val target: String = TARGET_OUTPUT,
    val notes: String = ""
) {
    companion object {
        const val TARGET_INPUT = "input"
        const val TARGET_OUTPUT = "output"
        const val TARGET_PROMPT = "prompt"
    }
}

/** 本地酒馆插件：正则替换输入、输出和系统提示。不做浏览器扩展或第三方脚本。 */
class TavernPluginStore(context: Context) {
    private val file = File(File(context.filesDir, "roles").apply { mkdirs() }, "plugins.json")

    fun load(): MutableList<TavernPlugin> {
        val list = mutableListOf<TavernPlugin>()
        try {
            if (!file.exists()) return seedDefaults()
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    TavernPlugin(
                        id = o.optString("id").ifBlank { newId() },
                        name = o.optString("name"),
                        enabled = o.optBoolean("enabled", true),
                        trigger = o.optString("trigger"),
                        replace = o.optString("replace"),
                        target = o.optString("target", TavernPlugin.TARGET_OUTPUT),
                        notes = o.optString("notes")
                    )
                )
            }
        } catch (_: Exception) { }
        if (list.isEmpty()) return seedDefaults()
        return list
    }

    fun save(items: List<TavernPlugin>) {
        try {
            val arr = JSONArray()
            items.take(32).forEach { p ->
                arr.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("name", p.name)
                        .put("enabled", p.enabled)
                        .put("trigger", p.trigger)
                        .put("replace", p.replace)
                        .put("target", p.target)
                        .put("notes", p.notes)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun upsert(plugin: TavernPlugin) {
        val list = load()
        val idx = list.indexOfFirst { it.id == plugin.id }
        if (idx >= 0) list[idx] = plugin else list.add(plugin)
        save(list)
    }

    fun delete(id: String) {
        save(load().filterNot { it.id == id })
    }

    fun applyInput(text: String, user: String, charName: String): String =
        apply(text, TavernPlugin.TARGET_INPUT, user, charName)

    fun applyOutput(text: String, user: String, charName: String): String =
        apply(text, TavernPlugin.TARGET_OUTPUT, user, charName)

    fun applyPrompt(text: String, user: String, charName: String): String =
        apply(text, TavernPlugin.TARGET_PROMPT, user, charName)

    private fun apply(text: String, target: String, user: String, charName: String): String {
        if (text.isEmpty()) return text
        var out = text
        load().filter { it.enabled && it.target == target && it.trigger.isNotBlank() }.forEach { plugin ->
            out = replaceOne(out, plugin, user, charName)
        }
        return out
    }

    private fun replaceOne(text: String, plugin: TavernPlugin, user: String, charName: String): String {
        val pattern = runCatching { Pattern.compile(plugin.trigger, Pattern.DOTALL) }.getOrNull() ?: return text
        val repl = plugin.replace
            .replace("{{user}}", user, ignoreCase = true)
            .replace("{{char}}", charName, ignoreCase = true)
            .replace("{{match}}", "\$0")
        return runCatching { pattern.matcher(text).replaceAll(repl) }.getOrDefault(text)
    }

    private fun seedDefaults(): MutableList<TavernPlugin> {
        val list = mutableListOf(
            TavernPlugin(
                id = "trim-stage",
                name = "去掉舞台指令",
                enabled = false,
                trigger = "\\*[^*]{0,80}\\*",
                replace = "",
                target = TavernPlugin.TARGET_OUTPUT,
                notes = "删掉角色回复里成对的 *动作*。"
            ),
            TavernPlugin(
                id = "user-alias",
                name = "把你换成主人",
                enabled = false,
                trigger = """(?<![\p{L}\p{N}])你(?![\p{L}\p{N}])""",
                replace = "{{user}}",
                target = TavernPlugin.TARGET_OUTPUT,
                notes = "把单独的「你」换成当前用户称呼。"
            )
        )
        save(list)
        return list
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
