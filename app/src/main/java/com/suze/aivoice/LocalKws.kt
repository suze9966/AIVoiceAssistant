package com.suze.aivoice

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 小型本地唤醒增强：不引入 Whisper / 离线大模型。
 * 1) 听写结果做拼音近似与常见误识别匹配
 * 2) 系统识别不可用时，用能量门做弱离线兜底
 */
object LocalKws {
    fun matches(heard: String, wakeWord: String): Boolean {
        val compact = compact(heard)
        if (compact.isEmpty()) return false
        aliases(wakeWord).forEach { alias ->
            if (compact.contains(alias) || compact.contains(compact(alias))) return true
        }
        return false
    }

    fun aliases(wakeWord: String): List<String> {
        val word = compact(wakeWord).ifBlank { "你好小沫" }
        val out = linkedSetOf(word, word.replace(" ", ""))
        listOf("沫", "莫", "末", "墨", "摸", "膜", "魔").forEach { ch ->
            if (word.contains("沫") || word.contains("莫") || word.contains("末")) {
                out.add(word.replace("沫", ch).replace("莫", ch).replace("末", ch))
            }
        }
        if (word.contains("小沫") || word.contains("小莫")) {
            out.add("你好小助手")
            out.add("小沫")
            out.add("小莫")
        }
        out.add(word.takeLast(2))
        return out.filter { it.length >= 2 }
    }

    fun compact(text: String): String =
        text.replace(" ", "")
            .replace("　", "")
            .replace("，", "")
            .replace("。", "")
            .replace(",", "")
            .replace(".", "")
            .replace("!", "")
            .replace("！", "")
            .lowercase()
}

class EnergyWakeHelper(
    private val onWake: () -> Unit
) {
    @Volatile private var running = false
    private var rec: AudioRecord? = null
    private var worker: Thread? = null

    fun start() {
        if (running) return
        val min = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) return
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                min * 2
            )
        } catch (_: Exception) {
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            try { record.release() } catch (_: Exception) { }
            return
        }
        rec = record
        running = true
        try { record.startRecording() } catch (_: Exception) {
            running = false
            try { record.release() } catch (_: Exception) { }
            rec = null
            return
        }
        worker = thread(name = "xiaomo-kws", isDaemon = true) {
            val buf = ShortArray(min)
            var voiced = 0
            var silence = 0
            while (running) {
                val n = try { record.read(buf, 0, buf.size) } catch (_: Exception) { -1 }
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val s = buf[i].toDouble()
                    sum += s * s
                }
                val rms = sqrt(sum / n)
                if (rms > 1400) {
                    voiced++
                    silence = 0
                } else {
                    silence++
                    if (silence > 4 && voiced in 3..18) {
                        running = false
                        releaseRecord(record)
                        onWake()
                        return@thread
                    }
                    if (silence > 8) voiced = 0
                }
            }
            releaseRecord(record)
        }
    }

    fun stop() {
        running = false
        rec?.let { releaseRecord(it) }
        worker = null
    }

    private fun releaseRecord(record: AudioRecord) {
        try { record.stop() } catch (_: Exception) { }
        try { record.release() } catch (_: Exception) { }
        if (rec === record) rec = null
    }
}