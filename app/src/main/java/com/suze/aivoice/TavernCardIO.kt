package com.suze.aivoice

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.Inflater
import android.util.Base64

data class ImportedTavernCard(
    val character: RoleCharacter,
    val avatarBytes: ByteArray? = null
)

/** 导入 / 导出酒馆角色卡：JSON、Character Card V2，以及 PNG 内嵌的 chara 块。 */
object TavernCardIO {
    fun importUri(context: Context, uri: Uri): RoleCharacter? = importUriFull(context, uri)?.character

    /**
     * 把一批世界书条目导出为标准 World Info JSON（兼容 SillyTavern 的 world info 结构）。
     */
    fun worldInfoToJson(entries: List<WorldEntry>): String {
        val obj = JSONObject()
        val map = JSONObject()
        entries.forEachIndexed { index, e ->
            val keys = JSONArray()
            splitWorldKeys(e.keys).forEach { keys.put(it) }
            map.put(
                index.toString(),
                JSONObject()
                    .put("uid", index)
                    .put("key", keys)
                    .put("keysecondary", JSONArray())
                    .put("comment", e.comment)
                    .put("content", e.content)
                    .put("constant", e.constant)
                    .put("selective", !e.constant && splitWorldKeys(e.keys).isNotEmpty())
                    .put("disable", !e.enabled)
                    .put("order", e.order)
            )
        }
        obj.put("entries", map)
        return obj.toString(2)
    }

    /** 从 World Info JSON 读回世界书条目（兼容 entries 为对象/数组两种写法）。 */
    fun worldInfoFromJson(text: String): List<WorldEntry> {
        val root = runCatching { JSONObject(text.trim()) }.getOrNull() ?: return emptyList()
        return parseCharacterBook(root)
    }

    private fun splitWorldKeys(raw: String): List<String> =
        raw.split(',', '，', ';', '；', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun importUriFull(context: Context, uri: Uri): ImportedTavernCard? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val name = displayName(context, uri)
        val character = importBytes(bytes, name) ?: return null
        val isPng = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIG)
        val avatar = if (isPng && bytes.size > 256) bytes else null
        return ImportedTavernCard(character, avatar)
    }

    fun importBytes(bytes: ByteArray, fileName: String = ""): RoleCharacter? {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIG)) {
            extractPngText(bytes, "chara")?.let { decodeChara(it) }?.let { return it }
            extractPngText(bytes, "ccv3")?.let { decodeChara(it) }?.let { return it }
        }
        val text = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrNull().orEmpty()
        if (text.startsWith("{") || text.startsWith("[")) {
            return parseJsonCard(text)
        }
        if (fileName.endsWith(".json", true) || fileName.endsWith(".png", true)) {
            return parseJsonCard(text)
        }
        return null
    }

    fun toXiaomoJson(character: RoleCharacter): String {
        return RoleStore.toJson(character)
            .put("spec", "xiaomo_role_v1")
            .toString(2)
    }

    fun toCharaV2Json(character: RoleCharacter): String {
        val tags = JSONArray()
        character.tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }.forEach { tags.put(it) }
        val alts = JSONArray()
        character.alternateGreetings.forEach { alts.put(it) }
        val entries = JSONArray()
        character.worldEntries.forEach { e ->
            val keys = JSONArray()
            e.keys.split(',', '，', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }.forEach { keys.put(it) }
            entries.put(
                JSONObject()
                    .put("keys", keys)
                    .put("content", e.content)
                    .put("enabled", e.enabled)
                    .put("constant", e.constant)
                    .put("comment", e.comment)
                    .put("insertion_order", e.order)
            )
        }
        val book = JSONObject().put("entries", entries)
        val data = JSONObject()
            .put("name", character.name)
            .put("description", character.description.ifBlank { character.intro })
            .put("personality", character.personality.ifBlank { character.persona })
            .put("scenario", character.scenario)
            .put("first_mes", character.greeting)
            .put("mes_example", character.mesExample.ifBlank { examplesAsMes(character) })
            .put("creator_notes", character.intro)
            .put("system_prompt", character.systemPrompt.ifBlank { character.persona })
            .put("post_history_instructions", character.postHistory)
            .put("alternate_greetings", alts)
            .put("tags", tags)
            .put("creator", character.creator.ifBlank { "小沫" })
            .put("character_book", book)
        return JSONObject()
            .put("spec", "chara_card_v2")
            .put("spec_version", "2.0")
            .put("data", data)
            .toString()
    }

    fun writeExportJson(dir: File, character: RoleCharacter): File {
        dir.mkdirs()
        val safe = RoleStore.sanitize(character.name.ifBlank { character.id })
        val file = File(dir, "$safe.json")
        file.writeText(toXiaomoJson(character))
        return file
    }

    fun writeExportPng(dir: File, character: RoleCharacter, avatar: File?): File? {
        dir.mkdirs()
        val safe = RoleStore.sanitize(character.name.ifBlank { character.id })
        val dest = File(dir, "$safe.png")
        val payload = Base64.encodeToString(toCharaV2Json(character).toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val source = if (avatar != null && avatar.isFile) avatar.readBytes() else minimalPng()
        val png = if (source.size >= 8 && source.copyOfRange(0, 8).contentEquals(PNG_SIG)) source else minimalPng()
        dest.writeBytes(injectTextChunk(png, "chara", payload))
        return dest
    }

    private fun decodeChara(raw: String): RoleCharacter? {
        val json = runCatching {
            val decoded = Base64.decode(raw.trim(), Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        }.getOrElse { raw }
        return parseJsonCard(json)
    }

    private fun parseJsonCard(text: String): RoleCharacter? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        if (root.has("name") && (root.has("persona") || root.has("greeting") || root.has("spec"))) {
            if (root.optString("spec") == "xiaomo_role_v1" || root.has("persona") || root.has("worldEntries")) {
                val parsed = RoleStore.parseCharacter(root) ?: return null
                return parsed.copy(id = RoleStore.newId(), avatarFile = "")
            }
        }
        val data = when {
            root.has("data") && root.optJSONObject("data") != null -> root.optJSONObject("data")!!
            root.has("name") -> root
            else -> return null
        }
        val alts = jsonStringList(data.optJSONArray("alternate_greetings"))
        val tags = jsonStringList(data.optJSONArray("tags")).joinToString(",")
        val examples = parseMesExample(data.optString("mes_example"))
        val world = parseCharacterBook(data.optJSONObject("character_book") ?: data.optJSONObject("worldbook"))
        val name = data.optString("name").ifBlank { "导入角色" }
        return RoleCharacter(
            id = RoleStore.newId(),
            name = name.take(48),
            emoji = "\uD83C\uDFAD",
            intro = data.optString("creator_notes").ifBlank { data.optString("description").replace("\n", " ").take(80) },
            greeting = data.optString("first_mes"),
            persona = data.optString("system_prompt").ifBlank { data.optString("personality") },
            description = data.optString("description"),
            personality = data.optString("personality"),
            scenario = data.optString("scenario"),
            mesExample = data.optString("mes_example"),
            systemPrompt = data.optString("system_prompt"),
            postHistory = data.optString("post_history_instructions"),
            userName = "主人",
            tags = tags,
            alternateGreetings = alts,
            examples = examples,
            worldEntries = world,
            creator = data.optString("creator"),
            updatedAt = System.currentTimeMillis()
        )
    }

    private fun parseCharacterBook(book: JSONObject?): List<WorldEntry> {
        if (book == null) return emptyList()
        val items = mutableListOf<JSONObject>()
        book.optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { items.add(it) }
        }
        if (items.isEmpty()) {
            book.optJSONObject("entries")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) obj.optJSONObject(keys.next())?.let { items.add(it) }
            }
        }
        val list = mutableListOf<WorldEntry>()
        items.forEach { item ->
            val keys = mutableListOf<String>()
            keys.addAll(jsonStringList(item.optJSONArray("keys")))
            keys.addAll(jsonStringList(item.optJSONArray("secondary_keys")))
            if (keys.isEmpty()) {
                item.optString("keys").split(',', '，', ';', '；')
                    .map { it.trim() }.filter { it.isNotEmpty() }.forEach { keys.add(it) }
            }
            val enabled = when {
                item.has("enabled") -> item.optBoolean("enabled", true)
                item.has("disable") -> !item.optBoolean("disable", false)
                else -> true
            }
            val content = item.optString("content")
            if (content.isBlank() && keys.isEmpty()) return@forEach
            list.add(
                WorldEntry(
                    id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                    keys = keys.joinToString(","),
                    content = content,
                    enabled = enabled,
                    constant = item.optBoolean("constant", false) || keys.isEmpty(),
                    comment = item.optString("comment"),
                    order = item.optInt("insertion_order", item.optInt("order", 100))
                )
            )
        }
        return list.sortedBy { it.order }
    }

    private fun parseMesExample(raw: String): List<RoleExample> {
        if (raw.isBlank()) return emptyList()
        val list = mutableListOf<RoleExample>()
        val blocks = raw.split("<START>", ignoreCase = true)
        fun consume(block: String) {
            var user = ""
            var assistant = ""
            block.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                val lower = line.lowercase()
                when {
                    lower.startsWith("{{user}}:") || lower.startsWith("user:") -> {
                        if (user.isNotEmpty() || assistant.isNotEmpty()) {
                            list.add(RoleExample(user, assistant)); user = ""; assistant = ""
                        }
                        user = line.substringAfter(":").trim()
                    }
                    lower.startsWith("{{char}}:") || lower.startsWith("assistant:") || lower.startsWith("char:") -> {
                        assistant = line.substringAfter(":").trim()
                    }
                }
            }
            if (user.isNotEmpty() || assistant.isNotEmpty()) list.add(RoleExample(user, assistant))
        }
        if (blocks.size > 1) blocks.drop(1).forEach { consume(it) } else consume(raw)
        return list.filter { it.user.isNotBlank() || it.assistant.isNotBlank() }.take(16)
    }

    private fun examplesAsMes(character: RoleCharacter): String {
        if (character.mesExample.isNotBlank()) return character.mesExample
        if (character.examples.isEmpty()) return ""
        val sb = StringBuilder()
        character.examples.forEach { e ->
            sb.append("<START>\n{{user}}: ").append(e.user).append("\n{{char}}: ").append(e.assistant).append("\n")
        }
        return sb.toString()
    }

    private fun jsonStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { list.add(it) }
        return list
    }

    private fun displayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else "" }
        }.getOrNull().orEmpty()
    }

    private fun extractPngText(bytes: ByteArray, key: String): String? {
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0 || offset + 12 + length > bytes.size) break
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val dataStart = offset + 8
            val data = bytes.copyOfRange(dataStart, dataStart + length)
            when (type) {
                "tEXt" -> parseLatinText(data)?.let { if (it.first == key) return it.second }
                "zTXt" -> parseZtxt(data)?.let { if (it.first == key) return it.second }
                "iTXt" -> parseItxt(data)?.let { if (it.first == key) return it.second }
                "IEND" -> return null
            }
            offset += 12 + length
        }
        return null
    }

    private fun parseLatinText(data: ByteArray): Pair<String, String>? {
        val zero = data.indexOf(0.toByte())
        if (zero <= 0) return null
        val keyword = String(data, 0, zero, Charsets.ISO_8859_1)
        val text = String(data, zero + 1, data.size - zero - 1, Charsets.ISO_8859_1)
        return keyword to text
    }

    private fun parseZtxt(data: ByteArray): Pair<String, String>? {
        val zero = data.indexOf(0.toByte())
        if (zero <= 0 || zero + 2 > data.size) return null
        val keyword = String(data, 0, zero, Charsets.ISO_8859_1)
        val compressed = data.copyOfRange(zero + 2, data.size)
        val text = inflate(compressed) ?: return null
        return keyword to text
    }

    private fun parseItxt(data: ByteArray): Pair<String, String>? {
        val zero = data.indexOf(0.toByte())
        if (zero <= 0 || zero + 3 >= data.size) return null
        val keyword = String(data, 0, zero, Charsets.ISO_8859_1)
        val compressed = data[zero + 1].toInt()
        var pos = zero + 3
        fun skip() {
            while (pos < data.size && data[pos] != 0.toByte()) pos++
            pos++
        }
        skip(); skip()
        if (pos > data.size) return null
        val payload = data.copyOfRange(pos, data.size)
        val text = if (compressed == 1) inflate(payload) else String(payload, Charsets.UTF_8)
        return keyword to (text ?: return null)
    }

    private fun inflate(data: ByteArray): String? {
        return try {
            val inflater = Inflater()
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(2048)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (out.size() > 2 * 1024 * 1024) break
            }
            inflater.end()
            String(out.toByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun injectTextChunk(png: ByteArray, key: String, value: String): ByteArray {
        val chunkData = (key + '\u0000' + value).toByteArray(Charsets.ISO_8859_1)
        val chunk = buildChunk("tEXt", chunkData)
        val iend = findIend(png)
        val cut = if (iend > 0) iend else png.size
        val out = ByteArrayOutputStream(cut + chunk.size + 12)
        out.write(png, 0, cut)
        out.write(chunk)
        if (iend > 0) out.write(png, iend, png.size - iend)
        else out.write(buildChunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun findIend(png: ByteArray): Int {
        var offset = 8
        while (offset + 12 <= png.size) {
            val length = readInt(png, offset)
            if (length < 0 || offset + 12 + length > png.size) return -1
            val type = String(png, offset + 4, 4, Charsets.US_ASCII)
            if (type == "IEND") return offset
            offset += 12 + length
        }
        return -1
    }

    private fun buildChunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val buf = ByteBuffer.allocate(12 + data.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(data.size)
        buf.put(typeBytes)
        buf.put(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        buf.putInt(crc.value.toInt())
        return buf.array()
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun minimalPng(): ByteArray {
        return Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=",
            Base64.DEFAULT
        )
    }

    private val PNG_SIG = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
}