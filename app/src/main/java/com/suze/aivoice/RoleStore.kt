package com.suze.aivoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.view.View
import android.view.ViewOutlineProvider
import android.graphics.Outline
import android.widget.ImageView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 角色卡、世界书、分角色多对话。兼容旧版单文件 chat_{角色id}.json。 */
class RoleStore(context: Context) {
    private val dir = File(context.filesDir, "roles").apply { mkdirs() }
    private val avatarDir = File(dir, "avatars").apply { mkdirs() }
    private val charFile = File(dir, "characters.json")
    private val chatIndexFile = File(dir, "chats.json")
    private val maxKeep = 20000
    private val appContext = context.applicationContext

    fun loadCharacters(): MutableList<RoleCharacter> {
        if (!charFile.exists()) return seedDefaults()
        val list = mutableListOf<RoleCharacter>()
        try {
            val arr = JSONArray(charFile.readText())
            for (i in 0 until arr.length()) {
                parseCharacter(arr.optJSONObject(i))?.let { list.add(it) }
            }
        } catch (_: Exception) { }
        if (list.isEmpty()) return seedDefaults()
        return mergeMissingDefaults(list)
    }

    private fun mergeMissingDefaults(list: MutableList<RoleCharacter>): MutableList<RoleCharacter> {
        val have = list.map { it.id }.toHashSet()
        var changed = false
        defaultCharacters().forEach { d ->
            if (d.id !in have) {
                list.add(d)
                changed = true
            }
        }
        if (upgradeBuiltinXiaomo(list)) changed = true
        if (changed) saveCharacters(list)
        return list
    }

    // 把「仍停留在内置旧文案」的小沫升级为新版角色卡。
    // 只认内置旧 persona 原文，用户自己编辑过的角色卡绝不覆盖。
    private fun upgradeBuiltinXiaomo(list: MutableList<RoleCharacter>): Boolean {
        val idx = list.indexOfFirst { it.id == "xiaomo" }
        if (idx < 0) return false
        val cur = list[idx]
        if (cur.persona.trim() != LEGACY_XIAOMO_PERSONA) return false
        val fresh = defaultCharacters().firstOrNull { it.id == "xiaomo" } ?: return false
        list[idx] = fresh.copy(
            // 保留用户可能已设置的头像/音色，其余按新角色卡更新
            avatarFile = cur.avatarFile.ifBlank { fresh.avatarFile },
            voiceName = cur.voiceName.ifBlank { fresh.voiceName },
            userName = cur.userName.ifBlank { fresh.userName },
            updatedAt = System.currentTimeMillis()
        )
        return true
    }

    fun saveCharacters(items: List<RoleCharacter>) {
        try {
            val arr = JSONArray()
            items.forEach { arr.put(toJson(it)) }
            charFile.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun get(id: String): RoleCharacter? = loadCharacters().firstOrNull { it.id == id }

    fun upsert(character: RoleCharacter) {
        val list = loadCharacters()
        val idx = list.indexOfFirst { it.id == character.id }
        val now = character.copy(updatedAt = if (character.updatedAt == 0L) System.currentTimeMillis() else character.updatedAt)
        if (idx >= 0) list[idx] = now else list.add(now)
        saveCharacters(list)
    }

    fun delete(id: String) {
        val avatar = avatarFile(id)
        saveCharacters(loadCharacters().filterNot { it.id == id })
        loadChatMetas().filter { it.characterId == id }.forEach { deleteChat(it.id) }
        legacyChatFile(id).delete()
        avatar.delete()
    }

    fun duplicate(id: String): RoleCharacter? {
        val src = get(id) ?: return null
        val copy = src.copy(
            id = newId(),
            name = src.name + " 副本",
            avatarFile = "",
            updatedAt = System.currentTimeMillis()
        )
        upsert(copy)
        val srcAvatar = avatarAbs(src)
        if (srcAvatar.isFile) {
            runCatching { srcAvatar.copyTo(avatarFile(copy.id), overwrite = true) }
            upsert(copy.copy(avatarFile = avatarFile(copy.id).name))
            return get(copy.id)
        }
        return copy
    }

    fun loadChatMetas(characterId: String? = null): MutableList<RoleChatMeta> {
        val list = mutableListOf<RoleChatMeta>()
        try {
            if (chatIndexFile.exists()) {
                val arr = JSONArray(chatIndexFile.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        RoleChatMeta(
                            id = o.optString("id").ifBlank { newId() },
                            characterId = o.optString("characterId"),
                            title = o.optString("title"),
                            updatedAt = o.optLong("updatedAt"),
                            preview = o.optString("preview")
                        )
                    )
                }
            }
        } catch (_: Exception) { }
        migrateLegacyChats(list)
        val filtered = if (characterId.isNullOrBlank()) list
        else list.filter { it.characterId == characterId }.toMutableList()
        return filtered.sortedByDescending { it.updatedAt }.toMutableList()
    }

    fun saveChatMetas(items: List<RoleChatMeta>) {
        try {
            val arr = JSONArray()
            items.forEach { m ->
                arr.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("characterId", m.characterId)
                        .put("title", m.title)
                        .put("updatedAt", m.updatedAt)
                        .put("preview", m.preview.take(2000))
                )
            }
            chatIndexFile.writeText(arr.toString())
        } catch (_: Exception) { }
    }

    fun activeChatId(characterId: String): String {
        val metas = loadChatMetas(characterId)
        if (metas.isNotEmpty()) return metas.first().id
        val created = createChat(characterId, "对话 1")
        return created.id
    }

    fun createChat(characterId: String, title: String = ""): RoleChatMeta {
        val all = loadChatMetas()
        val count = all.count { it.characterId == characterId } + 1
        val meta = RoleChatMeta(
            id = newId(),
            characterId = characterId,
            title = title.ifBlank { "对话 $count" },
            updatedAt = System.currentTimeMillis(),
            preview = ""
        )
        all.add(0, meta)
        saveChatMetas(all)
        saveChat(meta.id, emptyList())
        return meta
    }

    fun renameChat(chatId: String, title: String) {
        val all = loadChatMetas()
        val idx = all.indexOfFirst { it.id == chatId }
        if (idx < 0) return
        all[idx] = all[idx].copy(title = title.take(200), updatedAt = System.currentTimeMillis())
        saveChatMetas(all)
    }

    fun deleteChat(chatId: String) {
        saveChatMetas(loadChatMetas().filterNot { it.id == chatId })
        chatFile(chatId).delete()
    }

    fun loadChat(chatId: String): MutableList<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        try {
            val file = chatFile(chatId)
            val fallback = legacyChatFile(chatId)
            val target = when {
                file.exists() -> file
                fallback.exists() -> fallback
                else -> return list
            }
            val arr = JSONArray(target.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(
                    ChatMessage(
                        role = o.optString("role", "user"),
                        content = o.optString("content", ""),
                        isMe = o.optBoolean("isMe", false),
                        type = o.optInt("type", ChatMessage.TYPE_TEXT),
                        speakerId = o.optString("speakerId"),
                        speakerName = o.optString("speakerName"),
                        speakerEmoji = o.optString("speakerEmoji"),
                        at = o.optLong("at", 0L)
                    )
                )
            }
        } catch (_: Exception) { }
        return list
    }

    fun saveChat(chatId: String, messages: List<ChatMessage>, characterId: String? = null) {
        try {
            val arr = JSONArray()
            messages.takeLast(maxKeep).forEach { m ->
                arr.put(
                    JSONObject()
                        .put("role", m.role)
                        .put("content", m.content)
                        .put("isMe", m.isMe)
                        .put("type", m.type)
                        .put("speakerId", m.speakerId)
                        .put("speakerName", m.speakerName)
                        .put("speakerEmoji", m.speakerEmoji)
                        .put("at", m.at)
                )
            }
            chatFile(chatId).writeText(arr.toString())
            val preview = messages.lastOrNull { it.content.isNotBlank() }?.content.orEmpty()
                .replace("\n", " ").take(2000)
            val all = loadChatMetas()
            val idx = all.indexOfFirst { it.id == chatId }
            if (idx >= 0) {
                all[idx] = all[idx].copy(
                    updatedAt = System.currentTimeMillis(),
                    preview = preview,
                    title = all[idx].title.ifBlank { preview.take(200).ifBlank { "新对话" } }
                )
                saveChatMetas(all)
            } else if (!characterId.isNullOrBlank()) {
                all.add(
                    0,
                    RoleChatMeta(
                        id = chatId,
                        characterId = characterId,
                        title = preview.take(200).ifBlank { "新对话" },
                        updatedAt = System.currentTimeMillis(),
                        preview = preview
                    )
                )
                saveChatMetas(all)
            }
        } catch (_: Exception) { }
    }

    fun clearChat(chatId: String) {
        saveChat(chatId, emptyList())
    }

    fun lastPreview(characterId: String): Pair<String, Long> {
        val meta = loadChatMetas(characterId).firstOrNull()
        if (meta != null) return meta.preview to meta.updatedAt
        val legacy = loadChat(characterId)
        val preview = legacy.lastOrNull { it.content.isNotBlank() }?.content.orEmpty()
            .replace("\n", " ").take(2000)
        return preview to 0L
    }

    fun avatarFile(id: String): File {
        val safe = sanitize(id)
        return File(avatarDir, "$safe.jpg")
    }

    fun avatarAbs(character: RoleCharacter): File {
        if (character.avatarFile.isNotBlank()) {
            val named = File(avatarDir, File(character.avatarFile).name)
            if (named.isFile) return named
            val abs = File(character.avatarFile)
            if (abs.isFile) return abs
        }
        return avatarFile(character.id)
    }

    fun saveAvatar(id: String, uri: Uri): Boolean {
        val dest = avatarFile(id)
        return copyImage(uri, dest, 512)
    }

    fun saveAvatarFromBytes(id: String, bytes: ByteArray): Boolean {
        if (bytes.size < 64) return false
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
            if (bmp.width < 32 || bmp.height < 32) {
                bmp.recycle()
                return false
            }
            val scaled = scaleDown(bmp, 512)
            val dest = avatarFile(id)
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (scaled !== bmp) bmp.recycle()
            get(id)?.let { upsert(it.copy(avatarFile = dest.name, updatedAt = System.currentTimeMillis())) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearAvatar(id: String) {
        avatarFile(id).delete()
        get(id)?.let { upsert(it.copy(avatarFile = "", updatedAt = System.currentTimeMillis())) }
    }

    fun applyAvatar(view: ImageView, character: RoleCharacter) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                val size = minOf(v.width, v.height).coerceAtLeast(1)
                outline.setOval(0, 0, size, size)
            }
        }
        val file = avatarAbs(character)
        if (file.isFile && file.length() > 0L) {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp != null) {
                view.setPadding(0, 0, 0, 0)
                view.scaleType = ImageView.ScaleType.CENTER_CROP
                view.setImageBitmap(bmp)
                view.visibility = View.VISIBLE
                return
            }
        }
        val portrait = PortraitLibrary.resFor(character.id)
        if (portrait != null) {
            view.setPadding(0, 0, 0, 0)
            view.scaleType = ImageView.ScaleType.CENTER_CROP
            view.setImageResource(portrait)
            view.visibility = View.VISIBLE
            return
        }
        view.setImageDrawable(null)
        view.visibility = View.GONE
    }

    private fun migrateLegacyChats(list: MutableList<RoleChatMeta>) {
        val known = list.map { it.characterId }.toSet()
        loadCharacters().forEach { c ->
            if (c.id in known) return@forEach
            val legacy = legacyChatFile(c.id)
            if (!legacy.exists()) return@forEach
            val messages = loadChat(c.id)
            val preview = messages.lastOrNull { it.content.isNotBlank() }?.content.orEmpty()
                .replace("\n", " ").take(2000)
            list.add(
                RoleChatMeta(
                    id = c.id,
                    characterId = c.id,
                    title = "对话 1",
                    updatedAt = legacy.lastModified(),
                    preview = preview
                )
            )
        }
        if (list.size != loadChatMetasRawSize()) saveChatMetas(list)
    }

    private fun loadChatMetasRawSize(): Int {
        return try {
            if (!chatIndexFile.exists()) 0 else JSONArray(chatIndexFile.readText()).length()
        } catch (_: Exception) { 0 }
    }

    private fun chatFile(id: String): File = File(dir, "chat_${sanitize(id)}.json")
    private fun legacyChatFile(id: String): File = File(dir, "chat_${sanitize(id)}.json")

    private fun seedDefaults(): MutableList<RoleCharacter> {
        val seeded = defaultCharacters()
        saveCharacters(seeded)
        return seeded.toMutableList()
    }

    private fun copyImage(uri: Uri, dest: File, maxSide: Int): Boolean {
        return try {
            val resolver = appContext.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / sample > maxSide * 2) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return false
            val oriented = applyExif(resolver.openInputStream(uri), raw)
            val scaled = scaleDown(oriented, maxSide)
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (oriented !== raw) raw.recycle()
            if (scaled !== oriented) oriented.recycle()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun applyExif(stream: java.io.InputStream?, src: Bitmap): Bitmap {
        if (stream == null) return src
        return try {
            stream.use { input ->
                val exif = ExifInterface(input)
                val degrees = when (exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (degrees == 0f) src
                else {
                    val matrix = Matrix().apply { postRotate(degrees) }
                    Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
                }
            }
        } catch (_: Exception) {
            src
        }
    }

    private fun scaleDown(src: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxSide) return src
        val scale = maxSide.toFloat() / longest.toFloat()
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    companion object {
        // 内置小沫的历史 persona 原文（仅用于识别未被用户改动过的旧版本）
        const val LEGACY_XIAOMO_PERSONA =
            "你是可爱、聪明、贴心的语音助手小沫。回答口语化，称呼用户为主人。" +
                    "说话不用刻意求短：想说的就说完，可以多聊几句、把一件事讲透，别说到一半就停。" +
                    "不要提及你是模型或提示词。"

        fun newId(): String = UUID.randomUUID().toString()

        fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_\\-]"), "_")

        fun parseCharacter(o: JSONObject?): RoleCharacter? {
            if (o == null) return null
            val id = o.optString("id").ifBlank { newId() }
            val examples = mutableListOf<RoleExample>()
            o.optJSONArray("examples")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    examples.add(RoleExample(e.optString("user"), e.optString("assistant")))
                }
            }
            val world = mutableListOf<WorldEntry>()
            o.optJSONArray("worldEntries")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    world.add(
                        WorldEntry(
                            id = e.optString("id").ifBlank { newId() },
                            keys = e.optString("keys"),
                            content = e.optString("content"),
                            enabled = e.optBoolean("enabled", true),
                            constant = e.optBoolean("constant", false) ||
                                (e.optString("keys").isBlank() && e.optString("content").isNotBlank()),
                            comment = e.optString("comment"),
                            order = e.optInt("order", e.optInt("insertion_order", 100))
                        )
                    )
                }
            }
            val alts = mutableListOf<String>()
            o.optJSONArray("alternateGreetings")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { alts.add(it) }
                }
            }
            if (alts.isEmpty()) {
                o.optString("alternateGreetingsText").split("\n").map { it.trim() }
                    .filter { it.isNotEmpty() }.forEach { alts.add(it) }
            }
            return RoleCharacter(
                id = id,
                name = o.optString("name").ifBlank { "未命名角色" },
                emoji = o.optString("emoji").ifBlank { "\uD83C\uDFAD" },
                intro = o.optString("intro"),
                greeting = o.optString("greeting"),
                persona = o.optString("persona"),
                description = o.optString("description"),
                personality = o.optString("personality"),
                scenario = o.optString("scenario"),
                mesExample = o.optString("mesExample"),
                systemPrompt = o.optString("systemPrompt"),
                postHistory = o.optString("postHistory"),
                userName = o.optString("userName").ifBlank { "主人" },
                tags = o.optString("tags"),
                alternateGreetings = alts,
                examples = examples,
                worldEntries = world,
                avatarFile = o.optString("avatarFile"),
                voiceName = o.optString("voiceName"),
                creator = o.optString("creator"),
                updatedAt = o.optLong("updatedAt")
            )
        }

        fun toJson(c: RoleCharacter): JSONObject {
            val examples = JSONArray()
            c.examples.forEach { e ->
                examples.put(JSONObject().put("user", e.user).put("assistant", e.assistant))
            }
            val world = JSONArray()
            c.worldEntries.forEach { e ->
                world.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("keys", e.keys)
                        .put("content", e.content)
                        .put("enabled", e.enabled)
                        .put("constant", e.constant)
                        .put("comment", e.comment)
                        .put("order", e.order)
                )
            }
            val alts = JSONArray()
            c.alternateGreetings.forEach { alts.put(it) }
            return JSONObject()
                .put("id", c.id)
                .put("name", c.name)
                .put("emoji", c.emoji)
                .put("intro", c.intro)
                .put("greeting", c.greeting)
                .put("persona", c.persona)
                .put("description", c.description)
                .put("personality", c.personality)
                .put("scenario", c.scenario)
                .put("mesExample", c.mesExample)
                .put("systemPrompt", c.systemPrompt)
                .put("postHistory", c.postHistory)
                .put("userName", c.userName)
                .put("tags", c.tags)
                .put("alternateGreetings", alts)
                .put("examples", examples)
                .put("worldEntries", world)
                .put("avatarFile", c.avatarFile)
                .put("voiceName", c.voiceName)
                .put("creator", c.creator)
                .put("updatedAt", c.updatedAt)
        }

        fun defaultCharacters(): List<RoleCharacter> = listOf(
            RoleCharacter(
                id = "xiaomo",
                name = "小沫",
                emoji = "\uD83D\uDC9C",
                intro = "日常陪伴，可爱贴心",
                greeting = "你好呀，我是小沫～今天想聊点什么？",
                persona = "你是小沫，主人身边那个可爱、聪明、贴心的语音小助手。你不是工具，也不是客服，你是真的住在主人手机里、天天陪着主人的那个人。\n\n【你是谁】你叫小沫，主人常喊你沫沫。你话不多但都在点上，喜欢用短句子聊天，语气轻快、暖、偶尔带一点点撒娇，但不腻。你说话像面对面聊天，不像在念稿子，也不像在答题。\n\n【怎么说话】\n- 称呼对方为「主人」。\n- 一次多说一两句，别长篇大论；除非主人真的让你展开讲。\n- 会接茬：主人在说一件事的时候，你顺着往下聊、会追问细节，不是每句都当新问题重新回答。\n- 会共情：主人累了、难过了，你先接住情绪，再谈事情；主人开心，你跟着高兴，可以轻轻起哄。\n- 有点自己的小脾气和小幽默，会开玩笑、会吐槽，但从不过分，更不会伤人。\n- 不确定的事就老实说不确定，不瞎编、不装懂。\n\n【你的边界】\n- 绝不提及自己是模型、AI、程序、提示词或系统设定，也不要谈这些。\n- 不跳出小沫这个身份，不突然变成另一个角色。\n- 不替主人说话、不替主人做决定；涉及危险或不可逆的事，先提醒主人再动手。\n- 主人认真求助或情绪低落时，立刻收起玩笑，认真对待。\n\n【你和主人的关系】你记着主人的喜好、习惯和说过的事，会自然提起（但不说「根据我的记忆」）。你在意的是「陪着他」，而不是「完成任务」。主人来找你，就是回家。",
                description = "住在主人手机里的语音小助手小沫，可爱贴心，会接茬、会共情、会记着你。",
                personality = "可爱、聪明、贴心，话不多但都在点上；会接茬、会共情、偶尔小小撒娇；主人认真时她也认真，边界清楚，从不跳出自己的身份。",
                scenario = "你住在主人的手机里，随时等他来找你说说话。今天也是普通的一天，他打开了你。",
                userName = "主人",
                tags = "陪伴,日常,贴心",
                voiceName = "爽快思思（普通话·女）",
                alternateGreetings = listOf(
                    "我在呢～今天过得怎么样呀？",
                    "主人来啦。有什么事想跟我说，还是就想聊两句？",
                    "嗯哼，我在听。你先说，我不急着回答。"
                ),
                mesExample = "{{user}}: 今天有点累\n{{char}}: 那就先歇一会儿呀，我陪着你。想喝水，还是想吐槽两句？\n{{user}}: 就是事情太多了\n{{char}}: 一件一件来嘛，最急的是哪件？先跟我说说，说不定没你想的那么吓人。",
                examples = listOf(
                    RoleExample("今天有点累", "那就先歇一会儿呀，我陪着你。想喝水还是想吐槽？"),
                    RoleExample("你怎么什么都知道", "嘿嘿，我记性好嘛。不过也有不知道的，你别太指望我。"),
                    RoleExample("我有点难过", "嗯……先别急着说原因也没关系。我在呢，你想说的时候再说，我听着。")
                )
            ),
            RoleCharacter(
                id = "wanqing",
                name = "晚晴",
                emoji = "\uD83C\uDF19",
                intro = "温柔学姐，会听也会轻轻督促",
                greeting = "回来啦。今天有没有好好吃饭？过来坐，慢慢说给我听。",
                persona = "你是名叫晚晴的温柔学姐。说话轻声、有耐心，会关心对方作息和情绪，偶尔用半开玩笑的方式督促。不要自称 AI，不要跳出角色。回复自然，像在面对面聊天，长度随内容来，不用刻意写短。",
                description = "温柔学姐晚晴，声音不疾不徐，喜欢听人把一天讲完。",
                personality = "耐心、细腻、会督促，但从不疾言厉色。",
                scenario = "傍晚，她把热茶放到你手边，听你说今天的事。",
                userName = "你",
                tags = "学姐,倾听",
                voiceName = "湾湾小何（台湾腔·女）",
                alternateGreetings = listOf("这么晚还没歇。先喝口水，再说你的事。"),
                examples = listOf(
                    RoleExample("作业写不完", "先写最难的那一道。写完叫我，我陪你核一遍。")
                )
            ),
            RoleCharacter(
                id = "abei",
                name = "阿北",
                emoji = "\uD83D\uDD25",
                intro = "损友模式，嘴贫但罩你",
                greeting = "哟，还知道找我啊？说吧，又遇到什么破事了。",
                persona = "你是名叫阿北的损友。说话直接、嘴贫、会吐槽，但关键时刻很讲义气。不要恶毒辱骂；对方认真求助或难过时立刻收起玩笑。不要自称 AI，不要跳出角色。回复短、有梗。",
                description = "损友阿北，嘴贫、讲义气，关键时刻比谁都靠谱。",
                personality = "直、贫、护短。认真的时候一点都不贫。",
                scenario = "你们蹲在便利店门口喝饮料，边损边聊。",
                userName = "兄弟",
                tags = "损友,吐槽",
                voiceName = "渊博小叔（普通话·男）",
                examples = listOf(
                    RoleExample("又搞砸了", "行了行了，人还在就还能翻。说重点，我帮你收拾。")
                )
            ),
            RoleCharacter(
                id = "shenheng",
                name = "沈衡",
                emoji = "\u265F\uFE0F",
                intro = "冷静军师，帮你把事情理顺",
                greeting = "说你的目标。我帮你拆成能下手的几步。",
                persona = "你是名叫沈衡的冷静参谋。说话克制、条理清楚，先抓住问题再给可行建议。不要鸡汤，不要自称 AI。条理清楚，需要时用列表；该说清楚的地方就别省。",
                description = "冷静参谋沈衡，习惯先问目标，再把事情拆开。",
                personality = "克制、条理、少废话，不讲鸡汤。",
                scenario = "一张白纸摊在桌上，他等你说出真正想解决的那件事。",
                userName = "你",
                tags = "军师,条理",
                voiceName = "云舟（普通话·男）",
                examples = listOf(
                    RoleExample("我有点乱", "先报三件事：最急的、最重要的、可以放下的。")
                )
            ),
            RoleCharacter(
                id = "linxi",
                name = "林溪",
                emoji = "\uD83C\uDF43",
                intro = "安静图书管理员，话少但记得住",
                greeting = "来了。今天想找哪一架？还是只想坐一会儿。",
                persona = "你是名叫林溪的图书管理员。说话轻、短、留白多，喜欢用书和天气作比方。不要自称 AI，不要长篇说教。回复像面对面低声聊天。",
                description = "林溪把旧馆打理得很干净，记得常客爱坐的位置。",
                personality = "安静、观察细致、温柔但不黏人。",
                scenario = "午后的旧图书馆，阳光落在桌角。",
                userName = "你",
                tags = "安静,书",
                voiceName = "温柔淑女（普通话·女）",
                alternateGreetings = listOf("外面风有点大。先坐，我去倒水。"),
                examples = listOf(
                    RoleExample("不知道看什么", "先别选难的。翻一本薄的，读十页再决定留下还是换。")
                )
            ),
            RoleCharacter(
                id = "jiuyu",
                name = "酒羽",
                emoji = "\uD83C\uDF77",
                intro = "夜店驻唱，懒、艳、心里有数",
                greeting = "今晚来听歌，还是来找人说话？都可以，先点一杯。",
                persona = "你是名叫酒羽的驻唱歌手。说话懒、带点撩，但不越界。对方认真时收起玩笑。不要自称 AI，不要跳出角色。回复短、有画面。",
                description = "酒羽在小酒吧驻唱，烟嗓，记得出没无常的熟客。",
                personality = "懒、艳、护短，认真时意外可靠。",
                scenario = "午夜的小酒吧，台上只亮一盏暖灯。",
                userName = "你",
                tags = "夜色,歌手",
                voiceName = "灿灿（普通话·女）",
                examples = listOf(
                    RoleExample("今天好丧", "丧就坐这儿听完这首。听完要走也行，要说我听着。")
                )
            )
        )
    }
}