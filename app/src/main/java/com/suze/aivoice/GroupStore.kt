package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class GroupRoom(
    val id: String,
    val name: String,
    val memberIds: List<String> = emptyList(),
    val scene: String = "",
    val greeting: String = "",
    val updatedAt: Long = 0L,
    val preview: String = ""
)

/** 酒馆群聊：多个角色同桌，各自用人设轮流回。 */
class GroupStore(context: Context) {
    private val dir = File(context.filesDir, "roles").apply { mkdirs() }
    private val file = File(dir, "groups.json")
    private val roles = RoleStore(context)

    fun loadRooms(): MutableList<GroupRoom> {
        val list = mutableListOf<GroupRoom>()
        try {
            if (!file.exists()) return list
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                parse(arr.optJSONObject(i))?.let { list.add(it) }
            }
        } catch (_: Exception) { }
        return list.sortedByDescending { it.updatedAt }.toMutableList()
    }

    fun get(id: String): GroupRoom? = loadRooms().firstOrNull { it.id == id }

    fun upsert(room: GroupRoom) {
        val list = loadRooms()
        val now = room.copy(updatedAt = if (room.updatedAt == 0L) System.currentTimeMillis() else room.updatedAt)
        val idx = list.indexOfFirst { it.id == room.id }
        if (idx >= 0) list[idx] = now else list.add(now)
        saveRooms(list.sortedByDescending { it.updatedAt }.take(MAX_ROOMS))
    }

    fun delete(id: String) {
        saveRooms(loadRooms().filterNot { it.id == id })
        roles.deleteChat(id)
    }

    fun membersOf(room: GroupRoom): List<RoleCharacter> {
        val all = roles.loadCharacters()
        return room.memberIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
    }

    fun loadChat(id: String): MutableList<ChatMessage> = roles.loadChat(id)

    fun saveChat(room: GroupRoom, messages: List<ChatMessage>) {
        roles.saveChat(room.id, messages, "group:" + room.id)
        val preview = messages.lastOrNull { it.content.isNotBlank() }?.content.orEmpty()
            .replace("\n", " ").take(2000)
        upsert(room.copy(preview = preview, updatedAt = System.currentTimeMillis()))
    }

    fun clearChat(room: GroupRoom) {
        roles.clearChat(room.id)
        upsert(room.copy(preview = "", updatedAt = System.currentTimeMillis()))
    }

    private fun saveRooms(items: List<GroupRoom>) {
        try {
            val arr = JSONArray()
            items.sortedByDescending { it.updatedAt }.take(MAX_ROOMS).forEach { g ->
                val ids = JSONArray()
                g.memberIds.forEach { ids.put(it) }
                arr.put(
                    JSONObject()
                        .put("id", g.id)
                        .put("name", g.name)
                        .put("memberIds", ids)
                        .put("scene", g.scene)
                        .put("greeting", g.greeting)
                        .put("updatedAt", g.updatedAt)
                        .put("preview", g.preview)
                )
            }
            file.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    private fun parse(o: JSONObject?): GroupRoom? {
        if (o == null) return null
        val ids = mutableListOf<String>()
        o.optJSONArray("memberIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let { ids.add(it) }
            }
        }
        return GroupRoom(
            id = o.optString("id").ifBlank { newId() },
            name = o.optString("name").ifBlank { "群聊" },
            memberIds = ids.distinct().take(MAX_MEMBERS),
            scene = o.optString("scene"),
            greeting = o.optString("greeting"),
            updatedAt = o.optLong("updatedAt"),
            preview = o.optString("preview")
        )
    }

    companion object {
        const val MAX_ROOMS = 3000
        const val MAX_MEMBERS = 200
        fun newId(): String = UUID.randomUUID().toString()
    }
}