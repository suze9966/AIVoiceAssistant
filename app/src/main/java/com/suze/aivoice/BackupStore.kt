package com.suze.aivoice

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 会话 / 角色卡 / 待办 / 提醒 / 部分设置 的本地备份。不含 API Key。 */
class BackupStore(private val context: Context) {
    private val exportDir = File(context.cacheDir, "backup_export").apply { mkdirs() }

    fun exportZip(): File? {
        return try {
            val name = "xiaomo-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date()) + ".zip"
            val outFile = File(exportDir, name)
            ZipOutputStream(outFile.outputStream()).use { zip ->
                putFile(zip, File(context.filesDir, "chats"), "chats")
                putFile(zip, File(context.filesDir, "roles"), "roles")
                putOne(zip, File(context.filesDir, "todos.json"), "todos.json")
                putOne(zip, File(context.filesDir, "reminders.json"), "reminders.json")
                putOne(zip, File(context.filesDir, "memory.json"), "memory.json")
                putOne(zip, File(context.filesDir, "emotion_tree.json"), "emotion_tree.json")
                zip.putNextEntry(ZipEntry("prefs-safe.json"))
                zip.write(safePrefs().toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            outFile.takeIf { it.isFile && it.length() > 0L }
        } catch (_: Exception) { null }
    }

    fun importZip(uri: Uri): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = entry.name.trimStart('/').replace("\\", "/")
                        if (!entry.isDirectory && name.isNotBlank() && !name.contains("..")) {
                            val dest = File(context.filesDir, name)
                            dest.parentFile?.mkdirs()
                            dest.outputStream().use { out -> zip.copyTo(out) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return false
            true
        } catch (_: Exception) { false }
    }

    fun exportChatMarkdown(title: String, messages: List<ChatMessage>): File? {
        return try {
            val safe = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(24).ifBlank { "对话" }
            val file = File(exportDir, safe + "-" + SimpleDateFormat("MMdd-HHmm", Locale.CHINA).format(Date()) + ".md")
            val sb = StringBuilder()
            sb.append("# ").append(title.ifBlank { "小沫对话" }).append("\n\n")
            messages.forEach { m ->
                val who = if (m.isMe) "主人" else "小沫"
                if (m.type == ChatMessage.TYPE_IMAGE) {
                    sb.append("**").append(who).append("：** ").append(m.content).append("\n\n")
                } else {
                    sb.append("**").append(who).append("：** ").append(m.content).append("\n\n")
                }
            }
            file.writeText(sb.toString())
            file
        } catch (_: Exception) { null }
    }

    private fun safePrefs(): JSONObject {
        val sp = context.getSharedPreferences("ai_voice_prefs", Context.MODE_PRIVATE)
        val o = JSONObject()
        listOf(
            "baseUrl", "model", "systemPrompt", "wakeWord", "voiceLocaleName",
            "lastCity", "weatherSource", "ttsEngine", "cosyVoice", "activeChatId", "portraitId"
        ).forEach { k ->
            sp.getString(k, null)?.let { o.put(k, it) }
        }
        listOf(
            "streamEnabled", "taiwanVoice", "emotionEnabled", "mindEnabled",
            "growEnabled", "memoryEnabled", "keepListenInBackground", "webSearchEnabled",
            "welcomeEnabled", "notifySpeakEnabled", "headsetWakeEnabled", "localKwsEnabled"
        ).forEach { k ->
            if (sp.contains(k)) o.put(k, sp.getBoolean(k, false))
        }
        return o
    }

    private fun putFile(zip: ZipOutputStream, file: File, prefix: String) {
        if (!file.exists()) return
        if (file.isFile) {
            putOne(zip, file, prefix)
            return
        }
        file.walkTopDown().filter { it.isFile }.forEach { child ->
            val rel = prefix.trimEnd('/') + "/" + child.relativeTo(file).path.replace("\\", "/")
            putOne(zip, child, rel)
        }
    }

    private fun putOne(zip: ZipOutputStream, file: File, name: String) {
        if (!file.isFile) return
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
