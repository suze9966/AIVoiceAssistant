package com.suze.aivoice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * 语音合成助手：
 * - 支持切换语言区域（普通话 zh-CN / 台湾腔 zh-TW / 粤语 zh-HK）
 * - 支持在选定区域内切换具体音色
 * 台湾腔效果 = zh-TW 语言区域 + 该区域内的中文音色（Android 系统 TTS 引擎自带 zh-TW 支持）
 */
class TtsHelper(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String? = null

    /** 当前语言区域，默认普通话 */
    var currentLocale: Locale = Locale.CHINA
        private set

    /** 语言区域选项：显示名 -> Locale */
    val localeOptions: List<Pair<String, Locale>> = listOf(
        "普通话（大陆）" to Locale.CHINA,
        "台湾腔（台湾）" to Locale.TAIWAN,
        "粤语（香港）" to Locale("zh", "HK")
    )

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ready = true
            try {
                tts?.language = currentLocale
            } catch (_: Exception) { }
            applyRatePitch()
            pendingText?.let {
                speak(it)
                pendingText = null
            }
        }
    }

    /** 切换语言区域（如设台湾腔） */
    fun setLocale(locale: Locale): Boolean {
        currentLocale = locale
        return try {
            val r = tts?.setLanguage(locale)
            r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
        } catch (_: Exception) {
            false
        }
    }

    /** 当前语言区域是否为台湾腔 */
    fun isTaiwan(): Boolean =
        currentLocale == Locale.TAIWAN || currentLocale.country == "TW"

    /**
     * 返回当前区域下的可用音色。
     * 台湾腔时优先返回 locale.country==TW 的中文音色。
     */
    fun availableVoices(): List<String> {
        val all = tts?.voices ?: return emptyList()
        val zhTw = all.filter {
            it.locale.language == "zh" &&
                (it.locale.country == "TW" || it.locale.country == "HK" || it.locale.country == "CN")
        }
        // 优先台湾音色，其次是其它中文音色
        val preferred = if (isTaiwan()) {
            zhTw.sortedBy { if (it.locale.country == "TW") 0 else 1 }
        } else {
            zhTw
        }
        val names = preferred.map { "${it.name}（${it.locale.country}）" }
        return if (names.isEmpty()) {
            all.filter { it.locale.language == "zh" || it.locale.language == "en" }
                .map { it.name }.sorted()
        } else names
    }

    /** 按“音色名（区域）”或纯音色名设置音色 */
    fun setVoiceByName(name: String?) {
        if (name == null) return
        val pure = name.substringBefore("（").trim()
        val target = (tts?.voices ?: return).firstOrNull {
            it.name == name || it.name == pure
        } ?: return
        try {
            tts?.voice = target
            currentLocale = target.locale
        } catch (_: Exception) { }
    }

    /** 找到并设置当前区域的第一个台湾/中文音色，用于一键切台湾腔 */
    fun applyTaiwanVoice(): String? {
        setLocale(Locale.TAIWAN)
        val list = tts?.voices ?: return null
        val tw = list.filter { it.locale.language == "zh" && it.locale.country == "TW" }
            .sortedBy { it.name }
        val pick = tw.firstOrNull()
            ?: list.filter { it.locale.language == "zh" }.sortedBy { it.name }.firstOrNull()
        pick?.let {
            try { tts?.voice = it } catch (_: Exception) { }
            return "${it.name}（${it.locale.country}）"
        }
        return null
    }

    /** 情感化语速（默认 1.0，开心快一点、疲倦慢一点） */
    var emotionRate: Float = 1.0f
        set(v) { field = v.coerceIn(0.5f, 1.6f); applyRatePitch() }

    /** 情感化音调（默认 1.0） */
    var emotionPitch: Float = 1.0f
        set(v) { field = v.coerceIn(0.5f, 1.6f); applyRatePitch() }

    /** 用户基准语速（设置页手动设定，与情感叠加） */
    var userRate: Float = 1.0f
        set(v) { field = v.coerceIn(0.5f, 1.6f); applyRatePitch() }
    /** 用户基准音调（设置页手动设定，与情感叠加） */
    var userPitch: Float = 1.0f
        set(v) { field = v.coerceIn(0.5f, 1.6f); applyRatePitch() }
    /** 应用语速/音调 = 情感值 × 用户基准 */
    private fun applyRatePitch() {
        try { tts?.setSpeechRate((emotionRate * userRate).coerceIn(0.5f, 1.6f)) } catch (_: Exception) { }
        try { tts?.setPitch((emotionPitch * userPitch).coerceIn(0.5f, 1.6f)) } catch (_: Exception) { }
    }
    /** 供设置页直接设定基准语速/音调 */
    fun setRate(rate: Float) { userRate = rate }
    fun setPitch(pitch: Float) { userPitch = pitch }

    /** 直接设置情感参数（供情感引擎调用） */
    fun setEmotion(rate: Float, pitch: Float) {
        try { tts?.setSpeechRate(rate.coerceIn(0.5f, 1.6f)) } catch (_: Exception) { }
        try { tts?.setPitch(pitch.coerceIn(0.5f, 1.6f)) } catch (_: Exception) { }
    }

    /** 读取文字（可传入是否用台湾腔） */
    fun speak(text: String, taiwan: Boolean = false) {
        if (!ready) {
            pendingText = text
            return
        }
        applyRatePitch()
        if (taiwan && !isTaiwan()) {
            // 语音念台湾腔：切 zh-TW + 台湾音色
            applyTaiwanVoice()
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "utt_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
