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
    private val maxKeep = 200

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

    fun update(id: String, text: String): Boolean {
        val next = text.trim().take(80)
        if (next.isEmpty()) return false
        val list = load()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list[idx] = list[idx].copy(text = next)
        save(list)
        return true
    }

    fun toggle(id: String): TodoItem? {
        val list = load()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val item = list[idx].copy(done = !list[idx].done)
        list[idx] = item
        save(list)
        return item
    }

    fun delete(id: String): Boolean {
        val list = load()
        val next = list.filterNot { it.id == id }
        if (next.size == list.size) return false
        save(next)
        return true
    }

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

    fun summary(): String {
        val list = load()
        val pending = list.count { !it.done }
        return "待办 ${list.size} 条，未完成 $pending 条"
    }

    fun formatList(): String {
        val pending = pending()
        if (pending.isEmpty()) return "现在没有待办。"
        return pending.mapIndexed { i, t -> (i + 1).toString() + ". " + t.text }.joinToString("\n")
    }
}
