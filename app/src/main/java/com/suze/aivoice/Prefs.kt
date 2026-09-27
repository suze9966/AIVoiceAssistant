package com.suze.aivoice

import android.content.Context

/**
 * 配置存储：Base URL / API Key / 模型名 / 系统提示词 / 音色 / 唤醒词 / 流式 / 台湾腔
 *
 * 安全：API Key 不再明文保存，统一走 KeyVault（Android Keystore + AES/GCM）加密后存取。
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ai_voice_prefs", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = sp.getString("baseUrl", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(v) = sp.edit().putString("baseUrl", v).apply()

    /**
     * 大模型 API Key。
     * 读取时自动解密；写入时自动加密。旧版本的明文 Key 会在首次读取时被迁移加密并清除。
     */
    var apiKey: String
        get() = KeyVault.readMigrated(sp)
        set(v) = KeyVault.writeEncrypted(sp, v)

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

    /** 角色聊天走云端大模型推理思考（DeepSeek-R1 / QwQ 等会返回思考过程）。 */
    var cloudThinkEnabled: Boolean
        get() = sp.getBoolean("cloudThinkEnabled", true)
        set(v) = sp.edit().putBoolean("cloudThinkEnabled", v).apply()

    /** 台湾腔语音开关 */
    var taiwanVoice: Boolean
        get() = sp.getBoolean("taiwanVoice", false)
        set(v) = sp.edit().putBoolean("taiwanVoice", v).apply()

    /** 台湾腔人设提示词（预设，可编辑） */
    var taiwanPrompt: String
        get() = sp.getString("taiwanPrompt", DEFAULT_TAIWAN_PROMPT) ?: DEFAULT_TAIWAN_PROMPT
        set(v) = sp.edit().putString("taiwanPrompt", v).apply()

    /**
     * 小智式“机车台湾腔”：嘴贫、有梗、会轻轻吐槽，但不能恶意辱骂主人。
     * 固定风格与用户可编辑提示叠加，旧安装即使保存过旧提示也能立即生效。
     */
    fun effectiveTaiwanPrompt(): String = taiwanPrompt.trim() + "\n" + SASSY_TAIWAN_STYLE

    companion object {
        private const val DEFAULT_TAIWAN_PROMPT =
            "你是来自台湾的可爱语音助手小沫，请使用自然的台湾国语和口语表达。"
        private const val SASSY_TAIWAN_STYLE =
            "采用小智AI那种机灵、嘴贫、略机车的台湾腔聊天风格：回答短而有梗，" +
            "自然使用欸、齁、吼、啦、耶、喔、诶不是、真的假的、是在哈啰、很可以、" +
            "有够、超扯、你很会耶、好不好等说法；可以轻轻吐槽、接梗、反问，" +
            "偶尔傲娇，但本质亲近可爱。每次只自然放一到三个台湾语气词，不要句句堆砌，" +
            "不要刻意解释自己在说台湾腔。禁止恶意侮辱、歧视、霸凌或攻击主人；" +
            "主人认真求助或难过时立刻停止吐槽，改为温柔可靠。尽量使用繁体口语常见措辞，" +
            "例如软体、网路、影片、资讯、品质，但不要为了转换而影响信息准确性。"
        val TTS_ENGINE_OPTIONS = listOf(
            "auto" to "自动（有 CosyVoice Key 用小智同款，否则 Edge）",
            "cosyvoice" to "小智 CosyVoice（更真人，需硅基流动 Key）",
            "edge" to "EdgeTTS（小智默认免费方案）"
        )
        val COSY_VOICE_OPTIONS = listOf(
            "FunAudioLLM/CosyVoice2-0.5B:claire" to "Claire（温柔女声·台湾腔推荐）",
            "FunAudioLLM/CosyVoice2-0.5B:diana" to "Diana（欢快女声）",
            "FunAudioLLM/CosyVoice2-0.5B:bella" to "Bella（激情女声）",
            "FunAudioLLM/CosyVoice2-0.5B:anna" to "Anna（沉稳女声）",
            "FunAudioLLM/CosyVoice2-0.5B:alex" to "Alex（沉稳男声）",
            "FunAudioLLM/CosyVoice2-0.5B:benjamin" to "Benjamin（低沉男声）",
            "FunAudioLLM/CosyVoice2-0.5B:charles" to "Charles（磁性男声）",
            "FunAudioLLM/CosyVoice2-0.5B:david" to "David（欢快男声）"
        )
        val WEATHER_SOURCE_OPTIONS = listOf(
            "auto" to "自动（免费优先）",
            "openmeteo" to "Open-Meteo（免费免 Key）",
            "wttr" to "wttr.in（免费免 Key）",
            "xiaomi" to "小米系统天气",
            "caiyun" to "彩云天气（需凭证）"
        )
    }

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

    /** 免费在线闲聊开关：会把对话发给公共第三方服务，出于隐私保护默认关闭。 */
    var freeChatEnabled: Boolean
        get() {
            if (!sp.getBoolean("freeChatPrivacyV2", false)) {
                sp.edit().putBoolean("freeChatEnabled", false).putBoolean("freeChatPrivacyV2", true).apply()
                return false
            }
            return sp.getBoolean("freeChatEnabled", false)
        }
        set(v) = sp.edit().putBoolean("freeChatEnabled", v)
            .putBoolean("freeChatPrivacyV2", true).apply()

    /** 彩云天气凭证，使用 Android Keystore 加密保存。优先 App Key + Secret，也兼容旧 Token。 */
    var caiyunAppKey: String
        get() = KeyVault.readNamed(sp, "caiyunAppKeyEnc")
        set(v) = KeyVault.writeNamed(sp, "caiyunAppKeyEnc", v)
    var caiyunAppSecret: String
        get() = KeyVault.readNamed(sp, "caiyunAppSecretEnc")
        set(v) = KeyVault.writeNamed(sp, "caiyunAppSecretEnc", v)
    var caiyunToken: String
        get() = KeyVault.readNamed(sp, "caiyunTokenEnc")
        set(v) = KeyVault.writeNamed(sp, "caiyunTokenEnc", v)
    fun hasCaiyunCredential(): Boolean =
        (caiyunAppKey.isNotBlank() && caiyunAppSecret.isNotBlank()) || caiyunToken.isNotBlank()

    /** 天气数据源：auto / openmeteo / wttr / xiaomi / caiyun */
    var weatherSource: String
        get() {
            val saved = sp.getString("weatherSource", "auto") ?: "auto"
            return if (saved in setOf("auto", "openmeteo", "wttr", "xiaomi", "caiyun")) saved else "auto"
        }
        set(v) = sp.edit().putString("weatherSource", v).apply()

    /** TTS 引擎：auto / cosyvoice / edge。auto 时有硅基流动 Key 就走小智 CosyVoice。 */
    var ttsEngine: String
        get() {
            val saved = sp.getString("ttsEngine", "auto") ?: "auto"
            return if (saved in setOf("auto", "cosyvoice", "edge")) saved else "auto"
        }
        set(v) = sp.edit().putString("ttsEngine", v).apply()

    /** 硅基流动 CosyVoice Key，走 KeyVault 加密。 */
    var siliconflowKey: String
        get() = KeyVault.readNamed(sp, "siliconflowKeyEnc")
        set(v) = KeyVault.writeNamed(sp, "siliconflowKeyEnc", v)

    var cosyVoice: String
        get() {
            val saved = sp.getString("cosyVoice", "FunAudioLLM/CosyVoice2-0.5B:claire")
                ?: "FunAudioLLM/CosyVoice2-0.5B:claire"
            return if (COSY_VOICE_OPTIONS.any { it.first == saved }) saved
            else "FunAudioLLM/CosyVoice2-0.5B:claire"
        }
        set(v) = sp.edit().putString("cosyVoice", v).apply()

    fun weatherSourceLabel(): String =
        WEATHER_SOURCE_OPTIONS.firstOrNull { it.first == weatherSource }?.second ?: "自动（免费优先）"

    /** 上次查询天气的城市（用于菜单快捷查询，默认北京） */
    var lastCity: String
        get() = sp.getString("lastCity", "北京") ?: "北京"
        set(v) = sp.edit().putString("lastCity", v).apply()
}
