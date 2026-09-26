package com.suze.aivoice

import android.content.Context

/**
 * 配置存储：Base URL / API Key / 模型名 / 系统提示词 / 音色 / 唤醒词 / 流式 / 台湾腔
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ai_voice_prefs", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("baseUrl", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(v) = sp.edit().putString("baseUrl", v).apply()

    var apiKey: String
        get() = sp.getString("apiKey", "") ?: ""
        set(v) = sp.edit().putString("apiKey", v).apply()

    var model: String
        get() = sp.getString("model", "gpt-4o-mini") ?: "gpt-4o-mini"
        set(v) = sp.edit().putString("model", v).apply()

    var systemPrompt: String
        get() = sp.getString("systemPrompt", "你是一个可爱、聪明、贴心的语音助手，回答尽量口语化、简短。") ?: ""
        set(v) = sp.edit().putString("systemPrompt", v).apply()

    var voiceIndex: Int
        get() = sp.getInt("voiceIndex", 0)
        set(v) = sp.edit().putInt("voiceIndex", v).apply()

    var wakeWord: String
        get() = sp.getString("wakeWord", "你好小沫") ?: "你好小沫"
        set(v) = sp.edit().putString("wakeWord", v).apply()

    var streamEnabled: Boolean
        get() = sp.getBoolean("streamEnabled", true)
        set(v) = sp.edit().putBoolean("streamEnabled", v).apply()

    /** 台湾腔语音开关 */
    var taiwanVoice: Boolean
        get() = sp.getBoolean("taiwanVoice", false)
        set(v) = sp.edit().putBoolean("taiwanVoice", v).apply()

    /** 台湾腔人设提示词（预设，可编辑） */
    var taiwanPrompt: String
        get() = sp.getString("taiwanPrompt",
            "你是来自台湾的可爱语音助手，请用台湾腔口语习惯回答，例如会使用「超好看的啦」「好可爱喔」「真的假的」「很可以」「好哦」「欸」等语气词，语气轻松俏皮。") ?: ""
        set(v) = sp.edit().putString("taiwanPrompt", v).apply()

    /** 已选语音区域名称（用于设置页回显） */
    var voiceLocaleName: String
        get() = sp.getString("voiceLocaleName", "普通话（大陆）") ?: "普通话（大陆）"
        set(v) = sp.edit().putString("voiceLocaleName", v).apply()

    /** 情感化开关：让 AI 拥有心情/好感度并影响语气 */
    var emotionEnabled: Boolean
        get() = sp.getBoolean("emotionEnabled", true)
        set(v) = sp.edit().putBoolean("emotionEnabled", v).apply()

    /** 独立思考开关：内心独白 + 反思 + 自主目标 */
    var mindEnabled: Boolean
        get() = sp.getBoolean("mindEnabled", true)
        set(v) = sp.edit().putBoolean("mindEnabled", v).apply()

    /** 学习成长开关：等级/经验/记忆沉淀 */
    var growEnabled: Boolean
        get() = sp.getBoolean("growEnabled", true)
        set(v) = sp.edit().putBoolean("growEnabled", v).apply()
    /** 本地记忆库开关：把对话中的偏好/事实存到本机 */
    var memoryEnabled: Boolean
        get() = sp.getBoolean("memoryEnabled", true)
        set(v) = sp.edit().putBoolean("memoryEnabled", v).apply()

    /** TTS 语速 0.5-1.6 */
    var ttsRate: Float
        get() = sp.getFloat("ttsRate", 1.0f)
        set(v) = sp.edit().putFloat("ttsRate", v).apply()

    /** TTS 音调 0.5-1.6 */
    var ttsPitch: Float
        get() = sp.getFloat("ttsPitch", 1.0f)
        set(v) = sp.edit().putFloat("ttsPitch", v).apply()

    /** 欢迎语开关 */
    var welcomeEnabled: Boolean
        get() = sp.getBoolean("welcomeEnabled", true)
        set(v) = sp.edit().putBoolean("welcomeEnabled", v).apply()
}
