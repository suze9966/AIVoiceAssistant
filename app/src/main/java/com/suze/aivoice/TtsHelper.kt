package com.suze.aivoice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 语音合成助手（小智 AI 同款方案）。
 *
 * 原实现基于系统 TextToSpeech（台湾腔效果差），现改为 **EdgeTTS**：
 * 即微软 Edge「大声朗读」所用的在线神经网络语音——正是小智 xiaozhi-server
 * 默认且免费的 TTS 引擎。音色自然、支持中英文与多地区（大陆/台湾/香港）。
 *
 * 实现方式（严格对齐 EdgeTTS 协议）：
 * 1. 通过 WebSocket 连接 wss://speech.platform.bing.com/.../edge/v1
 * 2. 发送 speech.config（指定输出格式 mp3）
 * 3. 发送 ssml 消息，服务端以二进制帧回传音频
 * 4. 收到 turn.end 后拼接为 MP3 文件，用 MediaPlayer 播放
 * 5. 播放结束通过 onSpeakDone 通知上层，供聆听模式续听
 *
 * 对外方法签名与旧版保持一致，MainActivity 基本无需改动。
 */
class TtsHelper(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var socket: WebSocket? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
            .build()

    /** EdgeTTS 固定 TrustedClientToken（公开常量，各客户端通用） */
    private val trustedToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    /** 是否正在朗读（避免“边读边听”造成自问自答） */
    var isSpeaking: Boolean = false
        private set

    /** 朗读结束回调（供聆听模式在播报完后自动续听） */
    var onSpeakDone: (() -> Unit)? = null

    /** 当前语言区域，默认普通话 */
    var currentLocale: Locale = Locale.CHINA
        private set

    /** 语言区域选项（EdgeTTS 全部支持） */
    val localeOptions: List<Pair<String, Locale>> = listOf(
        "普通话（大陆）" to Locale.CHINA,
        "台湾腔（台湾）" to Locale.TAIWAN,
        "粤语（香港）" to Locale("zh", "HK")
    )

    /** 用户显式选择的音色（为空时按区域自动选） */
    private var explicitVoice: String? = null

    /** EdgeTTS 音色映射：区域 -> 神经网络音色 */
    private fun voiceForLocale(locale: Locale): String {
        explicitVoice?.let { return it }
        return when {
            locale.country == "TW" -> "zh-TW-HsiaoChenNeural"
            locale.country == "HK" -> "zh-HK-HiuMaanNeural"
            else -> "zh-CN-XiaoxiaoNeural"
        }
    }

    /** 情感化语速/音调（默认 1.0） */
    var emotionRate: Float = 1.0f
        private set
    var emotionPitch: Float = 1.0f
        private set
    var userRate: Float = 1.0f
        private set
    var userPitch: Float = 1.0f
        private set

    /** 最终语速（映射为 SSML 的 +N%/-N%） */
    private fun edgeRate(): String {
        val r = (emotionRate * userRate).coerceIn(0.5f, 1.6f)
        val pct = ((r - 1.0f) * 100).toInt()
        return if (pct >= 0) "+" + pct + "%" else "" + pct + "%"
    }

    /** 最终音调 */
    private fun edgePitch(): String {
        val p = (emotionPitch * userPitch).coerceIn(0.5f, 1.6f)
        val pct = ((p - 1.0f) * 100).toInt()
        return if (pct >= 0) "+" + pct + "%" else "" + pct + "%"
    }

    // ===== 兼容旧接口：设置类 =====

    fun setLocale(locale: Locale): Boolean {
        currentLocale = locale
        return true
    }

    fun isTaiwan(): Boolean =
        currentLocale == Locale.TAIWAN || currentLocale.country == "TW"

    /** EdgeTTS 音色列表（内置常用中文音色） */
    fun availableVoices(): List<String> = listOf(
        "晓晓（大陆·女）",
        "云希（大陆·男）",
        "晓伊（大陆·童声）",
        "晓臻（台湾·女）",
        "曉曼（香港·女）"
    )

    fun setVoiceByName(name: String?) {
        if (name == null) { explicitVoice = null; return }
        when {
            name.contains("云希") -> {
                explicitVoice = "zh-CN-YunxiNeural"
                currentLocale = Locale.CHINA
            }
            name.contains("晓伊") || name.contains("曉伊") -> {
                explicitVoice = "zh-CN-XiaoyiNeural"
                currentLocale = Locale.CHINA
            }
            name.contains("晓臻") || name.contains("曉臻") || name.contains("台湾") -> {
                explicitVoice = "zh-TW-HsiaoChenNeural"
                currentLocale = Locale.TAIWAN
            }
            name.contains("曉曼") || name.contains("晓曼") || name.contains("香港") -> {
                explicitVoice = "zh-HK-HiuMaanNeural"
                currentLocale = Locale("zh", "HK")
            }
            else -> {
                explicitVoice = "zh-CN-XiaoxiaoNeural"
                currentLocale = Locale.CHINA
            }
        }
    }

    fun applyTaiwanVoice(): String? {
        currentLocale = Locale.TAIWAN
        explicitVoice = "zh-TW-HsiaoChenNeural"
        return "晓臻（台湾·女）"
    }

    fun setRate(rate: Float) { userRate = rate }
    fun setPitch(pitch: Float) { userPitch = pitch }

    fun setEmotion(rate: Float, pitch: Float) {
        emotionRate = rate.coerceIn(0.5f, 1.6f)
        emotionPitch = pitch.coerceIn(0.5f, 1.6f)
    }

    // ===== 朗读主流程 =====

    /**
     * 朗读文字（可传入是否使用台湾腔）。
     * 内部：WebSocket 合成 MP3 -> 拼接 -> 主线程播放。
     */
    fun speak(text: String, taiwan: Boolean = false) {
        if (text.isBlank()) return
        if (taiwan) {
            currentLocale = Locale.TAIWAN
            explicitVoice = "zh-TW-HsiaoChenNeural"
        }

        stopInternal()
        isSpeaking = true

        val voice = voiceForLocale(currentLocale)
        val rate = edgeRate()
        val pitch = edgePitch()

        val out = File(context.cacheDir, "tts_" + System.currentTimeMillis() + ".mp3")
        val audio = FileOutputStream(out)

        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/" +
            "readaloud/edge/v1?TrustedClientToken=" + trustedToken + "&" +
            "ConnectionId=" + uuidNoDash()
        val req = Request.Builder()
            .url(url)
            .header("Origin", "chrome-extension://jdiccldimpahogkjbkabmgfgpnmlfhd")
            .header("User-Agent", "Mozilla/5.0")
            .build()

        val ws = client.newWebSocket(req, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                // 1) 发送语音配置
                val configTime = edgeTimestamp()
                val config = "X-Timestamp:" + configTime + "\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":" +
                    "{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"}," +
                    "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                ws.send(config)

                // 2) 发送 SSML
                val id = uuidNoDash()
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                    "<voice name='" + voice + "'>" +
                    "<prosody rate='" + rate + "' pitch='" + pitch + "'>" + escapeXml(text) + "</prosody>" +
                    "</voice></speak>"
                val speech = "X-RequestId:" + id + "\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:" + edgeTimestamp() + "Z\r\n" +
                    "Path:ssml\r\n\r\n" + ssml
                ws.send(speech)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                // 二进制帧：前 2 字节为大端 header 长度，跳过 header 后是音频数据
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
                val start = 2 + headerLen
                if (start >= data.size) return
                try {
                    audio.write(data, start, data.size - start)
                } catch (_: Exception) { }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    try { audio.flush(); audio.close() } catch (_: Exception) { }
                    ws.close(1000, null)
                    main.post { playFile(out) }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                try { audio.close() } catch (_: Exception) { }
                out.delete()
                main.post {
                    isSpeaking = false
                    onSpeakDone?.invoke()
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) { }
        })

        socket = ws
    }

    /** 主线程用 MediaPlayer 播放缓存 MP3 */
    private fun playFile(file: File) {
        if (!file.exists() || file.length() <= 0) {
            isSpeaking = false
            onSpeakDone?.invoke()
            return
        }
        try {
            currentFile?.delete()
            currentFile = file

            player?.release()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    isSpeaking = false
                    onSpeakDone?.invoke()
                }
                setOnErrorListener { _, _, _ ->
                    isSpeaking = false
                    onSpeakDone?.invoke()
                    true
                }
                prepare()
                start()
            }
        } catch (_: Exception) {
            isSpeaking = false
            onSpeakDone?.invoke()
        }
    }

    fun stop() {
        stopInternal()
        isSpeaking = false
    }

    private fun stopInternal() {
        try { socket?.cancel() } catch (_: Exception) { }
        socket = null
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) { }
        player = null
    }

    fun shutdown() {
        stopInternal()
        currentFile?.delete()
        currentFile = null
        client.dispatcher.executorService.shutdown()
        isSpeaking = false
    }

    // ===== 工具方法 =====

    private fun uuidNoDash(): String = UUID.randomUUID().toString().replace("-", "")

    private fun edgeTimestamp(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date())
    }

    /** XML 转义：实体用分段拼接，避免源码中出现会被误吞的完整实体 */
    private fun escapeXml(s: String): String {
        val amp = "&" + "amp;"
        val lt = "&" + "lt;"
        val gt = "&" + "gt;"
        val quot = "&" + "quot;"
        val apos = "&" + "apos;"
        return s
            .replace("&", amp)
            .replace("<", lt)
            .replace(">", gt)
            .replace("\"", quot)
            .replace("'", apos)
    }
}
