package com.suze.aivoice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 语音合成助手。
 *
 * 新方案（按主人要求删除重构）：
 * - 主引擎：火山引擎豆包 TTS（HTTP Chunked V3）
 * - 克隆音色：硅基流动 CosyVoice2（speech: URI）
 * - 离线兜底：系统 TTS，仅在显式选择或云端尚未出声时使用
 *
 * 不再使用 EdgeTTS，也不再把 CosyVoice 预设当主引擎。
 * 整段回复锁死同一种引擎，中途失败不换人声。
 * 对外方法签名保持不变，MainActivity / 角色窗 / 通话页不用改调用。
 */
class TtsHelper(private val context: Context, prefs: Prefs? = null) {
    private val prefs: Prefs = prefs ?: Prefs(context)

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var currentFile: File? = null
    private var fallbackTts: TextToSpeech? = null
    private var fallbackReady = false
    private var pendingFallback: String? = null
    private var fallbackStarted = false
    private var currentText: String = ""
    private var speechGeneration: Long = 0L
    private val speakQueue: ArrayDeque<String> = ArrayDeque()
    private var speakingTaiwan: Boolean = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(50, TimeUnit.SECONDS)
        .build()
    private var httpCall: Call? = null
    private var engineSettled: Boolean = false
    /** 整段回复锁死的引擎：volcano / clone / system。 */
    private var sessionEngine: String = ""
    private var sessionHasAudio: Boolean = false

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
                    val generation = speechGeneration
                    main.post { speakFallback(text, generation) }
                }
            }
        }
    }

    var isSpeaking: Boolean = false
        private set

    var onSpeakDone: (() -> Unit)? = null

    var currentLocale: Locale = Locale.CHINA
        private set

    val localeOptions: List<Pair<String, Locale>> = listOf(
        "普通话（大陆）" to Locale.CHINA,
        "台湾腔（台湾）" to Locale.TAIWAN,
        "粤语（香港）" to Locale("zh", "HK")
    )

    private var explicitVoice: String? = null

    var emotionRate: Float = 1.0f
        private set
    var emotionPitch: Float = 1.0f
        private set
    var userRate: Float = 1.0f
        private set
    var userPitch: Float = 1.0f
        private set

    fun setLocale(locale: Locale): Boolean {
        if (isSpeaking) return true
        currentLocale = locale
        return true
    }

    fun isTaiwan(): Boolean =
        currentLocale == Locale.TAIWAN || currentLocale.country == "TW"

    fun availableVoices(): List<String> = Prefs.VOLC_VOICE_OPTIONS.map { it.second }

    fun setVoiceByName(name: String?) {
        if (isSpeaking) return
        if (name.isNullOrBlank()) {
            explicitVoice = null
            return
        }
        val mapped = Prefs.VOLC_VOICE_OPTIONS.firstOrNull { it.second == name || name.contains(it.second.substringBefore("（")) }?.first
        if (mapped != null) {
            explicitVoice = mapped
            currentLocale = when {
                name.contains("台湾") -> Locale.TAIWAN
                name.contains("粤") || name.contains("香港") -> Locale("zh", "HK")
                else -> Locale.CHINA
            }
            return
        }
        when {
            name.contains("云舟") || name.contains("雲哲") || name.contains("云哲") -> {
                explicitVoice = "zh_male_yunzhou_moon_bigtts"
                currentLocale = Locale.CHINA
            }
            name.contains("男") || name.contains("小叔") || name.contains("云希") || name.contains("侃爷") -> {
                explicitVoice = "zh_male_yuanboxiaoshu_moon_bigtts"
                currentLocale = Locale.CHINA
            }
            name.contains("湾湾") || name.contains("台湾") || name.contains("曉臻") || name.contains("晓臻") -> {
                explicitVoice = "zh_female_wanwanxiaohe_moon_bigtts"
                currentLocale = Locale.TAIWAN
            }
            name.contains("粤") || name.contains("香港") || name.contains("晓曼") || name.contains("曉曼") -> {
                explicitVoice = "zh_female_wanwanxiaohe_moon_bigtts"
                currentLocale = Locale("zh", "HK")
            }
            name.contains("晓伊") || name.contains("曉伊") -> {
                explicitVoice = "zh_female_wenroushunv_moon_bigtts"
                currentLocale = Locale.CHINA
            }
            name.contains("曉雨") || name.contains("晓雨") -> {
                explicitVoice = "zh_female_cancan_mars_bigtts"
                currentLocale = Locale.CHINA
            }
            else -> {
                explicitVoice = Prefs.DEFAULT_VOLC_SPEAKER
                currentLocale = Locale.CHINA
            }
        }
    }

    fun applyTaiwanVoice(): String? {
        if (!isSpeaking) {
            currentLocale = Locale.TAIWAN
            explicitVoice = "zh_female_wanwanxiaohe_moon_bigtts"
        }
        return Prefs.VOLC_VOICE_OPTIONS.firstOrNull { it.first == "zh_female_wanwanxiaohe_moon_bigtts" }?.second
            ?: "湾湾小何（台湾腔·女）"
    }

    fun setRate(rate: Float) {
        if (isSpeaking) return
        userRate = rate
    }

    fun setPitch(pitch: Float) {
        if (isSpeaking) return
        userPitch = pitch
    }

    fun setEmotion(rate: Float, pitch: Float) {
        if (isSpeaking) return
        emotionRate = rate.coerceIn(0.5f, 1.6f)
        emotionPitch = pitch.coerceIn(0.5f, 1.6f)
    }

    /**
     * 剥离 emoji 后再朗读：屏幕上保留表情，嘴里不念「微笑的表情」这种废话。
     * 同时去掉 [sticker:xxx] 标记与变体选择符（U+FE0F）。
     */
    fun stripForSpeech(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val cc = Character.charCount(cp)
            // 精确判定 emoji：`→ ← ✓ ★ ⚡` 等常用符号会被保留，朗读时不会消失
            val skip = EmojiDetector.isEmoji(cp)
            if (!skip) out.appendCodePoint(cp)
            i += cc
        }
        val cleaned = out.toString()
            .replace(Regex("\\[\\s*sticker\\s*:[^\\]]{1,32}\\]", RegexOption.IGNORE_CASE), "")
        // 剥掉 Markdown 标记：标题井号、加粗星号、行内代码反引号、表格竖线、分隔线、列表符号等
        // 否则 TTS 会一字一句念出「星号星号」「反引号」这种噪音
        return MiniMarkdown.stripForSpeech(cleaned)
            .replace(Regex("[ \\t]{2,}"), " ")
            .trim()
    }

    fun speak(text: String, taiwan: Boolean = false) {
        if (text.isBlank()) return
        val trimmed = stripForSpeech(text)
        if (trimmed.isBlank()) return
        if (isSpeaking && currentText == trimmed && speakQueue.isEmpty()) return
        speakQueue.clear()
        sessionEngine = ""
        sessionHasAudio = false
        val chunks = splitSpeakChunks(trimmed)
        if (chunks.isEmpty()) return
        if (chunks.size > 1) {
            for (i in 1 until chunks.size) speakQueue.addLast(chunks[i])
        }
        startSpeakChunk(chunks[0], taiwan)
    }

    fun enqueueSpeak(text: String, taiwan: Boolean = false) {
        if (text.isBlank()) return
        val chunks = splitSpeakChunks(stripForSpeech(text))
        if (chunks.isEmpty()) return
        if (!isSpeaking && speakQueue.isEmpty()) {
            speak(text, taiwan)
            return
        }
        speakingTaiwan = taiwan
        for (chunk in chunks) {
            if (chunk.isBlank()) continue
            if (chunk == currentText && speakQueue.isEmpty()) continue
            if (speakQueue.peekLast() == chunk) continue
            speakQueue.addLast(chunk)
        }
    }

    fun hasQueuedSpeech(): Boolean = speakQueue.isNotEmpty()

    fun speakingText(): String = currentText

    private fun startSpeakChunk(text: String, taiwan: Boolean) {
        if (taiwan) {
            currentLocale = Locale.TAIWAN
            val current = explicitVoice.orEmpty()
            if (!current.contains("wanwan") && !current.contains("taiwan")) {
                explicitVoice = "zh_female_wanwanxiaohe_moon_bigtts"
            }
        }
        speakingTaiwan = taiwan
        stopInternal()
        val generation = speechGeneration
        currentText = text
        fallbackStarted = false
        engineSettled = false
        isSpeaking = true
        val out = File(context.cacheDir, "tts_" + System.currentTimeMillis() + ".mp3")
        val engine = resolveEngine()
        sessionEngine = engine
        when (engine) {
            "system" -> speakFallback(text, generation)
            "clone" -> speakClone(text, taiwan, generation, out)
            else -> speakVolcano(text, taiwan, generation, out)
        }
    }

    private fun resolveEngine(): String {
        if (sessionEngine.isNotBlank()) return sessionEngine
        return when (prefs.ttsEngine) {
            "system" -> "system"
            "clone" -> if (canUseClone()) "clone" else if (canUseVolcano()) "volcano" else "system"
            "volcano" -> if (canUseVolcano()) "volcano" else if (canUseClone()) "clone" else "system"
            else -> when {
                canUseClone() -> "clone"
                canUseVolcano() -> "volcano"
                else -> "system"
            }
        }
    }

    private fun canUseVolcano(): Boolean = prefs.hasVolcanoCredential()

    private fun canUseClone(): Boolean =
        prefs.siliconflowKey.isNotBlank() && prefs.cloneVoiceEnabled && prefs.cloneVoiceUri.startsWith("speech:")

    /**
     * 把长回复切成适合合成的片段。
     *
     * 主人要求「不限制小沫说话的字数」，所以这里的阈值放得很宽：
     * - 一整段短于 600 字**完全不切**，一口气合成，避免句号处断掉；
     * - 只有真的很长时才切，而且优先在换行/句末切，尽量少切。
     */
    private fun splitSpeakChunks(text: String): List<String> {
        val t = text.trim()
        if (t.length <= 600) return listOf(t)
        val out = ArrayList<String>()
        val buf = StringBuilder()
        fun flush() {
            val s = buf.toString().trim()
            if (s.isNotEmpty()) out.add(s)
            buf.setLength(0)
        }
        // 按码点遍历：emoji / 生僻字（代理对）必须整体进入 buf，
        // 否则分段点落在代理对中间会把半个字符交给 TTS，读出来就是乱码音。
        var ci = 0
        while (ci < t.length) {
            val cp = t.codePointAt(ci)
            ci += Character.charCount(cp)
            buf.appendCodePoint(cp)
            val hitBreak = cp == 10
            val hitEnd = cp == '。'.code || cp == '！'.code || cp == '？'.code ||
                cp == '!'.code || cp == '?'.code || cp == '；'.code
            val hitComma = cp == '，'.code || cp == ','.code || cp == '、'.code
            when {
                hitBreak && buf.toString().trim().length >= 400 -> flush()
                hitEnd && buf.length >= 500 -> flush()
                hitComma && buf.length >= 800 -> flush()
                buf.length >= 1000 -> flush()
            }
        }
        flush()
        if (out.isEmpty()) return listOf(t)
        if (out.size >= 2 && out[0].length < 120) {
            out[1] = out[0] + out[1]
            out.removeAt(0)
        }
        return out
    }

    private fun playNextOrFinish(generation: Long) {
        if (generation != speechGeneration) return
        stopInternal()
        val next = if (speakQueue.isEmpty()) null else speakQueue.removeFirst()
        if (next != null) {
            startSpeakChunk(next, speakingTaiwan)
        } else {
            isSpeaking = false
            onSpeakDone?.invoke()
        }
    }

    private fun speechRateValue(): Int {
        val taiwanCadence = if (isTaiwan()) 0.98f else 1.0f
        val r = (emotionRate * userRate * taiwanCadence).coerceIn(0.5f, 1.6f)
        return ((r - 1.0f) * 100f).toInt().coerceIn(-50, 100)
    }

    private fun pitchValue(): Int {
        val p = (emotionPitch * userPitch).coerceIn(0.5f, 1.6f)
        return ((p - 1.0f) * 20f).toInt().coerceIn(-12, 12)
    }

    private fun cloneSpeed(): Double {
        val taiwanCadence = if (isTaiwan()) 0.98f else 1.0f
        return (emotionRate * userRate * taiwanCadence).coerceIn(0.5f, 1.6f).toDouble()
    }

    private fun volcanoSpeaker(): String {
        explicitVoice?.takeIf { it.isNotBlank() }?.let { return it }
        return when {
            currentLocale.country == "TW" -> "zh_female_wanwanxiaohe_moon_bigtts"
            else -> prefs.volcSpeaker
        }
    }

    private fun speakVolcano(text: String, taiwan: Boolean, generation: Long, out: File) {
        if (!canUseVolcano()) {
            fallbackFromCloud(text, taiwan, generation, from = "volcano")
            return
        }
        val speaker = volcanoSpeaker()
        val audioParams = JSONObject()
            .put("format", "mp3")
            .put("sample_rate", 24000)
            .put("speech_rate", speechRateValue())
        val additions = JSONObject()
            .put("disable_markdown_filter", true)
        val pitch = pitchValue()
        if (pitch != 0) {
            additions.put("post_process", JSONObject().put("pitch", pitch))
        }
        if (taiwan || isTaiwan()) {
            additions.put("explicit_language", "zh-cn")
        }
        val reqParams = JSONObject()
            .put("text", text)
            .put("speaker", speaker)
            .put("audio_params", audioParams)
            .put("additions", additions.toString())
        val json = JSONObject()
            .put("user", JSONObject().put("uid", "xiaomo"))
            .put("req_params", reqParams)
            .toString()
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder()
            .url("https://openspeech.bytedance.com/api/v3/tts/unidirectional")
            .header("Content-Type", "application/json")
            .header("X-Api-Resource-Id", prefs.volcResourceId)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(body)
        val apiKey = prefs.volcApiKey
        if (apiKey.isNotBlank()) {
            builder.header("X-Api-Key", apiKey)
        } else {
            builder.header("X-Api-App-Id", prefs.volcAppId)
            builder.header("X-Api-Access-Key", prefs.volcAccessKey)
        }
        val call = client.newCall(builder.build())
        httpCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post {
                    if (generation != speechGeneration) return@post
                    if (httpCall === call) httpCall = null
                    fallbackFromCloud(text, taiwan, generation, from = "volcano")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val bytes = try { collectVolcanoAudio(res) } catch (_: Exception) { null }
                    if (!res.isSuccessful || bytes == null || bytes.isEmpty()) {
                        main.post {
                            if (generation != speechGeneration) return@post
                            if (httpCall === call) httpCall = null
                            fallbackFromCloud(text, taiwan, generation, from = "volcano")
                        }
                        return
                    }
                    try {
                        FileOutputStream(out).use { it.write(bytes) }
                    } catch (_: Exception) {
                        main.post {
                            if (generation != speechGeneration) return@post
                            if (httpCall === call) httpCall = null
                            fallbackFromCloud(text, taiwan, generation, from = "volcano")
                        }
                        return
                    }
                    main.post {
                        if (generation != speechGeneration) return@post
                        if (engineSettled || fallbackStarted) { out.delete(); return@post }
                        if (httpCall === call) httpCall = null
                        playFile(out, text, taiwan, generation)
                    }
                }
            }
        })
        main.postDelayed({
            if (generation == speechGeneration && isSpeaking && httpCall === call && player?.isPlaying != true && !fallbackStarted) {
                try { call.cancel() } catch (_: Exception) { }
                httpCall = null
                fallbackFromCloud(text, taiwan, generation, from = "volcano")
            }
        }, 22_000L)
    }

    private fun collectVolcanoAudio(res: Response): ByteArray? {
        val body = res.body ?: return null
        val source = body.source()
        val audio = ByteArrayOutputStream()
        var sawAudio = false
        var sawEnd = false
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val payload = if (trimmed.startsWith("data:")) trimmed.substring(5).trim() else trimmed
            if (payload.isEmpty() || payload == "[DONE]") continue
            val obj = try { JSONObject(payload) } catch (_: Exception) { continue }
            val code = obj.optInt("code", 0)
            if (code == 20000000) {
                sawEnd = true
                break
            }
            if (code != 0 && code != 20000000) {
                if (!sawAudio) return null
                break
            }
            val data = obj.optString("data")
            if (data.isNotBlank() && data != "null") {
                val decoded = try {
                    Base64.decode(data, Base64.DEFAULT)
                } catch (_: Exception) {
                    null
                }
                if (decoded != null && decoded.isNotEmpty()) {
                    audio.write(decoded)
                    sawAudio = true
                }
            }
        }
        if (!sawAudio && !sawEnd) {
            val raw = try { audio.toByteArray() } catch (_: Exception) { ByteArray(0) }
            if (raw.isEmpty()) {
                val leftover = try { body.bytes() } catch (_: Exception) { null }
                if (leftover != null && leftover.isNotEmpty() && leftover[0] == '{'.code.toByte()) {
                    val obj = try { JSONObject(String(leftover, Charsets.UTF_8)) } catch (_: Exception) { null }
                    val data = obj?.optString("data").orEmpty()
                    if (data.isNotBlank() && data != "null") {
                        return try { Base64.decode(data, Base64.DEFAULT) } catch (_: Exception) { null }
                    }
                } else if (leftover != null && leftover.size > 64) {
                    return leftover
                }
            }
        }
        val bytes = audio.toByteArray()
        return if (bytes.isNotEmpty()) bytes else null
    }

    private fun speakClone(text: String, taiwan: Boolean, generation: Long, out: File) {
        val key = prefs.siliconflowKey
        val voice = prefs.cloneVoiceUri
        if (key.isBlank() || !voice.startsWith("speech:")) {
            fallbackFromCloud(text, taiwan, generation, from = "clone")
            return
        }
        val spoken = if (taiwan || isTaiwan()) {
            "请用自然的台湾国语口音来说。<|endofprompt|>" + text
        } else {
            text
        }
        val json = JSONObject()
            .put("model", "FunAudioLLM/CosyVoice2-0.5B")
            .put("input", spoken)
            .put("voice", voice)
            .put("response_format", "mp3")
            .put("speed", cloneSpeed())
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
                    fallbackFromCloud(text, taiwan, generation, from = "clone")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val bytes = try { res.body?.bytes() } catch (_: Exception) { null }
                    if (!res.isSuccessful || bytes == null || bytes.isEmpty()) {
                        main.post {
                            if (generation != speechGeneration) return@post
                            if (httpCall === call) httpCall = null
                            fallbackFromCloud(text, taiwan, generation, from = "clone")
                        }
                        return
                    }
                    try {
                        FileOutputStream(out).use { it.write(bytes) }
                    } catch (_: Exception) {
                        main.post {
                            if (generation != speechGeneration) return@post
                            if (httpCall === call) httpCall = null
                            fallbackFromCloud(text, taiwan, generation, from = "clone")
                        }
                        return
                    }
                    main.post {
                        if (generation != speechGeneration) return@post
                        if (engineSettled || fallbackStarted) { out.delete(); return@post }
                        if (httpCall === call) httpCall = null
                        playFile(out, text, taiwan, generation)
                    }
                }
            }
        })
        main.postDelayed({
            if (generation == speechGeneration && isSpeaking && httpCall === call && player?.isPlaying != true && !fallbackStarted) {
                try { call.cancel() } catch (_: Exception) { }
                httpCall = null
                fallbackFromCloud(text, taiwan, generation, from = "clone")
            }
        }, 18_000L)
    }

    private fun fallbackFromCloud(text: String, taiwan: Boolean, generation: Long, from: String) {
        if (generation != speechGeneration || fallbackStarted || engineSettled) return
        if (sessionHasAudio) {
            playNextOrFinish(generation)
            return
        }
        sessionEngine = "system"
        speakFallback(text, generation)
    }

    private fun playFile(file: File, text: String, taiwan: Boolean, generation: Long) {
        if (generation != speechGeneration) { file.delete(); return }
        if (!file.exists() || file.length() <= 0) {
            fallbackFromCloud(text, taiwan, generation, from = sessionEngine)
            return
        }
        try {
            currentFile?.delete()
            currentFile = file
            engineSettled = true
            if (sessionEngine.isEmpty()) sessionEngine = "volcano"
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
                    main.post {
                        if (generation == speechGeneration) playNextOrFinish(generation)
                    }
                }
                setOnErrorListener { _, _, _ ->
                    main.post {
                        if (generation == speechGeneration) playNextOrFinish(generation)
                    }
                    true
                }
                prepare()
                start()
            }
            sessionHasAudio = true
        } catch (_: Exception) {
            if (sessionHasAudio) playNextOrFinish(generation)
            else fallbackFromCloud(text, taiwan, generation, from = sessionEngine)
        }
    }

    private fun speakFallback(text: String, generation: Long = speechGeneration) {
        if (generation != speechGeneration || text.isBlank() || fallbackStarted) return
        fallbackStarted = true
        try { player?.stop(); player?.release() } catch (_: Exception) { }
        player = null
        try { httpCall?.cancel() } catch (_: Exception) { }
        httpCall = null
        val engine = fallbackTts
        if (!fallbackReady || engine == null) {
            fallbackStarted = false
            pendingFallback = text
            main.postDelayed({
                if (generation == speechGeneration && pendingFallback == text) {
                    pendingFallback = null
                    playNextOrFinish(generation)
                }
            }, 5_000L)
            return
        }
        engine.language = currentLocale
        val taiwanCadence = if (isTaiwan()) 0.98f else 1.0f
        engine.setSpeechRate((emotionRate * userRate * taiwanCadence).coerceIn(0.5f, 1.6f))
        engine.setPitch((emotionPitch * userPitch).coerceIn(0.5f, 1.6f))
        isSpeaking = true
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "fallback_" + generation)
        if (result == TextToSpeech.ERROR) {
            main.post { playNextOrFinish(generation) }
        }
    }

    private fun finishFallback(utteranceId: String?) {
        main.post {
            if (utteranceId == "fallback_" + speechGeneration) {
                playNextOrFinish(speechGeneration)
            }
        }
    }

    fun stop() {
        speakQueue.clear()
        sessionEngine = ""
        sessionHasAudio = false
        stopInternal()
        isSpeaking = false
    }

    private fun stopInternal() {
        speechGeneration++
        pendingFallback = null
        try { fallbackTts?.stop() } catch (_: Exception) { }
        try { httpCall?.cancel() } catch (_: Exception) { }
        httpCall = null
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) { }
        player = null
    }

    fun shutdown() {
        speakQueue.clear()
        stopInternal()
        currentFile?.delete()
        currentFile = null
        try { fallbackTts?.shutdown() } catch (_: Exception) { }
        fallbackTts = null
        client.dispatcher.executorService.shutdown()
        isSpeaking = false
    }
}
