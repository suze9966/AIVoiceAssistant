package com.suze.aivoice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.security.MessageDigest
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 语音合成助手（小智 AI 同款方案）。
 *
 * 对齐开源小智 xiaozhi-esp32-server 的 TTS 方案：
 * - 默认免费引擎仍是 EdgeTTS（小智 selected_module.TTS=EdgeTTS）
 * - 更真人的方案是小智同款 CosyVoiceSiliconflow（硅基流动 CosyVoice2）
 * 有 Key 时优先 CosyVoice，失败自动回退 Edge，再失败才用系统 TTS。
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
class TtsHelper(private val context: Context, prefs: Prefs? = null) {
    private val prefs: Prefs = prefs ?: Prefs(context)

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var socket: WebSocket? = null
    private var fallbackTts: TextToSpeech? = null
    private var fallbackReady = false
    private var pendingFallback: String? = null
    private var fallbackStarted = false
    private var currentText: String = ""
    /** 每次开始/停止朗读都会递增；旧网络回调不得影响新朗读。 */
    private var speechGeneration: Long = 0L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()
    private var httpCall: Call? = null
    /** CosyVoice 本轮已经成功播放或已经回退，防止超时与失败回调重复切到 Edge。 */
    private var engineSettled: Boolean = false

    init {
        fallbackTts = TextToSpeech(context.applicationContext) { status ->
            fallbackReady = status == TextToSpeech.SUCCESS
            val engine = fallbackTts
            if (fallbackReady && engine != null) {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        main.post {
                            if (utteranceId == "fallback_" + speechGeneration) isSpeaking = true
                        }
                    }
                    override fun onDone(utteranceId: String?) = finishFallback(utteranceId)
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) = finishFallback(utteranceId)
                    override fun onError(utteranceId: String?, errorCode: Int) = finishFallback(utteranceId)
                })
                pendingFallback?.let { text ->
                    pendingFallback = null
                    main.post { speakFallback(text, speechGeneration) }
                }
            }
        }
    }

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
        val taiwanCadence = if (isTaiwan()) 0.98f else 1.0f
        val r = (emotionRate * userRate * taiwanCadence).coerceIn(0.5f, 1.6f)
        val pct = ((r - 1.0f) * 100).toInt()
        return if (pct >= 0) "+" + pct + "%" else "" + pct + "%"
    }

    /** 最终音调 */
    private fun edgePitch(): String {
        val taiwanLift = if (isTaiwan()) 1.0f else 1.0f
        val p = (emotionPitch * userPitch * taiwanLift).coerceIn(0.5f, 1.6f)
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
        "曉臻（台湾腔·女·真人）",
        "曉臻 HD（台湾腔·女·超拟真）",
        "曉雨（台湾腔·女·浓）",
        "雲哲（台湾腔·男）",
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
            name.contains("HD") && (name.contains("曉臻") || name.contains("晓臻") || name.contains("超拟真")) -> {
                explicitVoice = "zh-TW-HsiaoChen:DragonHDLatestNeural"
                currentLocale = Locale.TAIWAN
            }
            name.contains("曉臻") || name.contains("晓臻") || name.contains("台湾腔·女·真人") || name.contains("台湾腔·女·自然") -> {
                explicitVoice = "zh-TW-HsiaoChenNeural"
                currentLocale = Locale.TAIWAN
            }
            name.contains("曉雨") || name.contains("晓雨") || name.contains("台湾腔·女·浓") -> {
                explicitVoice = "zh-TW-HsiaoYuNeural"
                currentLocale = Locale.TAIWAN
            }
            name.contains("雲哲") || name.contains("云哲") || name.contains("台湾腔·男") -> {
                explicitVoice = "zh-TW-YunJheNeural"
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
        return "曉臻（台湾腔·女·真人）"
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
            val current = explicitVoice.orEmpty()
            if (!current.startsWith("zh-TW-")) {
                explicitVoice = "zh-TW-HsiaoChenNeural"
            }
        }

        stopInternal()
        val generation = speechGeneration
        currentText = text
        fallbackStarted = false
        engineSettled = false
        isSpeaking = true

        val voice = voiceForLocale(currentLocale)
        val rate = edgeRate()
        val pitch = edgePitch()

        val out = File(context.cacheDir, "tts_" + System.currentTimeMillis() + ".mp3")
        if (shouldUseCosyVoice()) {
            speakCosyVoice(text, taiwan, generation, out)
            return
        }
        speakEdge(text, taiwan, generation, out, voice, rate, pitch)
    }

    private fun shouldUseCosyVoice(): Boolean {
        val key = prefs.siliconflowKey
        if (key.isBlank()) return false
        return prefs.ttsEngine != "edge"
    }

    /** 小智 CosyVoiceSiliconflow：POST https://api.siliconflow.cn/v1/audio/speech */
    private fun speakCosyVoice(text: String, taiwan: Boolean, generation: Long, out: File) {
        val key = prefs.siliconflowKey
        if (key.isBlank()) {
            speakEdge(text, taiwan, generation, out, voiceForLocale(currentLocale), edgeRate(), edgePitch())
            return
        }
        val speed = (emotionRate * userRate * (if (isTaiwan()) 0.98f else 1.0f)).coerceIn(0.5f, 1.6f)
        val spoken = if (taiwan || isTaiwan()) {
            "请用自然的台湾国语口音来说。<|endofprompt|>" + text
        } else {
            text
        }
        val json = JSONObject()
            .put("model", "FunAudioLLM/CosyVoice2-0.5B")
            .put("input", spoken)
            .put("voice", prefs.cosyVoice)
            .put("response_format", "mp3")
            .put("speed", speed.toDouble())
            .toString()
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("https://api.siliconflow.cn/v1/audio/speech")
            .header("Authorization", "Bearer " + key)
            .header("Content-Type", "application/json")
            .post(body)
            .build()
        val call = client.newCall(req)
        httpCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post {
                    if (generation != speechGeneration) return@post
                    if (httpCall === call) httpCall = null
                    fallbackFromCosy(text, taiwan, generation)
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val bytes = try { res.body?.bytes() } catch (_: Exception) { null }
                    if (!res.isSuccessful || bytes == null || bytes.isEmpty()) {
                        main.post {
                            if (generation != speechGeneration) return@post
                            if (httpCall === call) httpCall = null
                            fallbackFromCosy(text, taiwan, generation)
                        }
                        return
                    }
                    try {
                        FileOutputStream(out).use { it.write(bytes) }
                    } catch (_: Exception) {
                        main.post {
                            if (generation != speechGeneration) return@post
                            if (httpCall === call) httpCall = null
                            fallbackFromCosy(text, taiwan, generation)
                        }
                        return
                    }
                    main.post {
                        if (generation != speechGeneration) return@post
                        if (httpCall === call) httpCall = null
                        playFile(out, text, generation)
                    }
                }
            }
        })
        main.postDelayed({
            // 成功播放后必须先清空 httpCall，避免 18 秒超时把正在播的 CosyVoice 误切到 Edge。
            if (generation == speechGeneration && isSpeaking && httpCall === call && player?.isPlaying != true && !fallbackStarted) {
                try { call.cancel() } catch (_: Exception) { }
                httpCall = null
                fallbackFromCosy(text, taiwan, generation)
            }
        }, 18_000L)
    }

    private fun fallbackFromCosy(text: String, taiwan: Boolean, generation: Long) {
        if (generation != speechGeneration || fallbackStarted || engineSettled) return
        engineSettled = true
        speakEdge(
            text,
            taiwan,
            generation,
            File(context.cacheDir, "tts_" + System.currentTimeMillis() + ".mp3"),
            voiceForLocale(currentLocale),
            edgeRate(),
            edgePitch()
        )
    }

    private fun speakEdge(
        text: String,
        taiwan: Boolean,
        generation: Long,
        out: File,
        voice: String,
        rate: String,
        pitch: String
    ) {
        val audio = FileOutputStream(out)

        // Edge 现行协议要求 Sec-MS-GEC 签名、版本参数及新版扩展 Origin；
        // 缺少这些字段时握手会返回 HTTP 403，不能只靠旧 TrustedClientToken。
        val chromiumVersion = "143.0.3650.75"
        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/" +
            "readaloud/edge/v1?TrustedClientToken=" + trustedToken + "&" +
            "ConnectionId=" + uuidNoDash() + "&" +
            "Sec-MS-GEC=" + edgeSecMsGec() + "&" +
            "Sec-MS-GEC-Version=1-" + chromiumVersion
        val req = Request.Builder()
            .url(url)
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0"
            )
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Cookie", "muid=" + uuidNoDash().uppercase(Locale.US) + ";")
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
                val speechLocale = when {
                    voice.startsWith("zh-TW-") -> "zh-TW"
                    voice.startsWith("zh-HK-") -> "zh-HK"
                    else -> "zh-CN"
                }
                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" +
                    speechLocale + "'>" +
                    "<voice name='" + voice + "'>" +
                    "<prosody rate='" + rate + "' pitch='" + pitch + "'>" + escapeXml(text) + "</prosody>" +
                    "</voice></speak>"
                val speech = "X-RequestId:" + id + "\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:" + edgeTimestamp() + "\r\n" +
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

            override fun onMessage(ws: WebSocket, frameText: String) {
                if (frameText.contains("Path:turn.end")) {
                    try { audio.flush(); audio.close() } catch (_: Exception) { }
                    if (generation != speechGeneration) { out.delete(); return }
                    socket = null
                    ws.close(1000, null)
                    main.post { if (generation == speechGeneration) playFile(out, text, generation) }
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                try { audio.close() } catch (_: Exception) { }
                out.delete()
                main.post {
                    if (generation != speechGeneration) return@post
                    if (voice.contains("DragonHD")) {
                        explicitVoice = "zh-TW-HsiaoChenNeural"
                        speak(text, taiwan = false)
                    } else {
                        speakFallback(text, generation)
                    }
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) { }
        })

        socket = ws
        // Edge 服务偶尔可能连接成功但不返回 turn.end；超时后自动切到系统 TTS。
        main.postDelayed({
            if (generation == speechGeneration && isSpeaking && socket === ws && !fallbackStarted) {
                try { ws.cancel() } catch (_: Exception) { }
                socket = null
                try { audio.close() } catch (_: Exception) { }
                out.delete()
                if (voice.contains("DragonHD")) {
                    explicitVoice = "zh-TW-HsiaoChenNeural"
                    speak(text, taiwan = false)
                } else {
                    speakFallback(text, generation)
                }
            }
        }, 18_000L)
    }

    /** 主线程用 MediaPlayer 播放缓存 MP3 */
    private fun playFile(file: File, text: String, generation: Long) {
        if (generation != speechGeneration) { file.delete(); return }
        if (!file.exists() || file.length() <= 0) {
            speakFallback(text, generation)
            return
        }
        try {
            currentFile?.delete()
            currentFile = file
            engineSettled = true

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
                    if (generation == speechGeneration) {
                        isSpeaking = false
                        onSpeakDone?.invoke()
                    }
                }
                setOnErrorListener { _, _, _ ->
                    speakFallback(text, generation)
                    true
                }
                prepare()
                start()
            }
        } catch (_: Exception) {
            speakFallback(text, generation)
        }
    }

    /** EdgeTTS 不可用时使用手机系统 TTS，保证最差情况下仍能说话。 */
    private fun speakFallback(text: String, generation: Long = speechGeneration) {
        if (generation != speechGeneration || text.isBlank() || fallbackStarted) return
        fallbackStarted = true
        try { socket?.cancel() } catch (_: Exception) { }
        socket = null
        val engine = fallbackTts
        if (!fallbackReady || engine == null) {
            // 等初始化完成后需要允许再次进入 speakFallback。
            fallbackStarted = false
            pendingFallback = text
            // 初始化很慢或设备没有 TTS 引擎时，避免永久卡在“正在说话”。
            main.postDelayed({
                if (generation == speechGeneration && pendingFallback == text) {
                    pendingFallback = null
                    isSpeaking = false
                    onSpeakDone?.invoke()
                }
            }, 5_000L)
            return
        }
        engine.language = currentLocale
        val taiwanCadence = if (isTaiwan()) 0.98f else 1.0f
        val taiwanLift = if (isTaiwan()) 1.0f else 1.0f
        engine.setSpeechRate((emotionRate * userRate * taiwanCadence).coerceIn(0.5f, 1.6f))
        engine.setPitch((emotionPitch * userPitch * taiwanLift).coerceIn(0.5f, 1.6f))
        isSpeaking = true
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fallback_" + generation)
        if (result == TextToSpeech.ERROR) {
            isSpeaking = false
            onSpeakDone?.invoke()
        }
    }

    private fun finishFallback(utteranceId: String?) {
        main.post {
            if (utteranceId == "fallback_" + speechGeneration) {
                isSpeaking = false
                onSpeakDone?.invoke()
            }
        }
    }

    fun stop() {
        stopInternal()
        isSpeaking = false
    }

    private fun stopInternal() {
        speechGeneration++
        pendingFallback = null
        try { fallbackTts?.stop() } catch (_: Exception) { }
        try { socket?.cancel() } catch (_: Exception) { }
        socket = null
        try { httpCall?.cancel() } catch (_: Exception) { }
        httpCall = null
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
        try { fallbackTts?.shutdown() } catch (_: Exception) { }
        fallbackTts = null
        client.dispatcher.executorService.shutdown()
        isSpeaking = false
    }

    // ===== 工具方法 =====

    private fun uuidNoDash(): String = UUID.randomUUID().toString().replace("-", "")

    /** 生成 Edge 当前协议要求的五分钟窗口 SHA-256 签名。 */
    private fun edgeSecMsGec(): String {
        val windowsEpochSeconds = 11_644_473_600L
        val unixSeconds = System.currentTimeMillis() / 1000L
        val roundedSeconds = ((unixSeconds + windowsEpochSeconds) / 300L) * 300L
        val ticks = roundedSeconds * 10_000_000L
        val source = ticks.toString() + trustedToken
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02X".format(Locale.US, it.toInt() and 0xff) }
    }

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
