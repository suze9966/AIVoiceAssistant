package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TodoItem(
    val id: String,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

class TodoStore(context: Context) {
    private val file = File(context.filesDir, "todos.json")
    private val maxKeep = 80

    fun load(): MutableList<TodoItem> {
        val list = mutableListOf<TodoItem>()
        try {
            if (!file.exists()) return list
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    TodoItem(
                        id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                        text = o.optString("text"),
                        done = o.optBoolean("done", false),
                        createdAt = o.optLong("createdAt")
                    )
                )
            }
        } catch (_: Exception) { }
        return list
    }

    fun save(items: List<TodoItem>) {
        try {
            val arr = JSONArray()
            items.takeLast(maxKeep).forEach { t ->
                arr.put(
                    JSONObject()
                        .put("id", t.id)
                        .put("text", t.text)
                        .put("done", t.done)
                        .put("createdAt", t.createdAt)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun add(text: String): TodoItem {
        val item = TodoItem(
            id = UUID.randomUUID().toString(),
            text = text.trim().ifBlank { "待办" }.take(80)
        )
        val list = load()
        list.add(item)
        save(list)
        return item
    }

    fun pending(): List<TodoItem> = load().filter { !it.done }

    fun markDone(query: String): TodoItem? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val list = load()
        val idx = list.indexOfLast { !it.done && (it.text == q || it.text.contains(q) || q.contains(it.text)) }
        if (idx < 0) return null
        val done = list[idx].copy(done = true)
        list[idx] = done
        save(list)
        return done
    }

    fun clearAll() {
        save(emptyList())
    }

    fun formatList(): String {
        val pending = pending()
        if (pending.isEmpty()) return "现在没有待办。"
        return pending.mapIndexed { i, t -> (i + 1).toString() + ". " + t.text }.joinToString("\n")
    }
}
