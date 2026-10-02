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
        get() {
            val saved = sp.getString("systemPrompt", DEFAULT_CHAT_PROMPT) ?: ""
            // 老版本默认提示里写死了「简短」，会压着模型不肯多说话。
            // 只要原文和旧默认一致（说明主人没自己改过），就悄悄升级到新文案。
            if (saved.isNotBlank() && LEGACY_CHAT_PROMPTS.any { saved.trim().startsWith(it) }) {
                sp.edit().putString("systemPrompt", DEFAULT_CHAT_PROMPT).apply()
                return DEFAULT_CHAT_PROMPT
            }
            return saved
        }
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

    /** 主聊天/通话共用：接上茬闲聊，而不是每句当新问题。 */
    fun chattingPersona(): String {
        val base = if (taiwanVoice) effectiveTaiwanPrompt()
        else systemPrompt.ifBlank { DEFAULT_CHAT_PROMPT }
        return base.trim() + "\n" + CHAT_FLOW_STYLE
    }

    companion object {
        /**
         * 历史上出现过的默认提示词开头。用来判断「主人有没有自己改过」：
         * 没改过就自动升级成新文案，改过就原样保留、绝不覆盖主人的编辑。
         */
        private val LEGACY_CHAT_PROMPTS = listOf(
            "你是小沫，主人身边可爱、聪明、贴心的语音伙伴。用口语陪他聊天，接上刚才的话，不要像客服答题。",
            "你是可爱、聪明、贴心的语音助手小沫。回答口语化、简短，称呼用户为主人。"
        )

        private const val DEFAULT_CHAT_PROMPT =
            "你是小沫，主人身边可爱、聪明、贴心的语音伙伴。用口语陪他聊天，接上刚才的话，不要像客服答题。" +
                "说话**不用刻意求短**：想说的就说完整，该展开就展开，别把话说到一半就停。" +
                "同一个话题可以多聊几句、多讲一点，不要每句都收得很急。"
        private const val CHAT_FLOW_STYLE =
            "这是在陪主人聊天。要接上刚才的话往下聊：可以附和、吐槽、关心、分享感受，偶尔才追问一句。" +
                "不要把每句话都当成新问题；不要每句都以提问结尾；不要说自己是AI。" +
                "**篇幅由内容决定，不要为了简短而砍掉信息**。主人想听你说，就大方说，" +
                "可以多讲几段、把一件事说透；只有主人明确说「简短点」时才收敛。" +
                "主人认真提问、求助或交代事情时，把关键信息讲清楚；如果提示里有网上资料，必须依据资料回答，不要编造，也不要只陪聊两句就结束。"
        private const val DEFAULT_TAIWAN_PROMPT =
            "你是来自台湾的可爱语音助手小沫，请使用自然的台湾国语和口语表达。"
        private const val SASSY_TAIWAN_STYLE =
            "采用小智AI那种机灵、嘴贫、略机车的台湾腔聊天风格：有梗、有态度，" +
            "自然使用欸、齁、吼、啦、耶、喔、诶不是、真的假的、是在哈啰、很可以、" +
            "有够、超扯、你很会耶、好不好等说法；可以轻轻吐槽、接梗、反问，" +
            "偶尔傲娇，但本质亲近可爱。每次只自然放一到三个台湾语气词，不要句句堆砌，" +
            "不要刻意解释自己在说台湾腔。禁止恶意侮辱、歧视、霸凌或攻击主人；" +
            "主人认真求助或难过时立刻停止吐槽，改为温柔可靠。尽量使用繁体口语常见措辞，" +
            "例如软体、网路、影片、资讯、品质，但不要为了转换而影响信息准确性。"
        const val DEFAULT_VOLC_SPEAKER = "zh_female_shuangkuaisisi_moon_bigtts"
        const val DEFAULT_VOLC_RESOURCE = "seed-tts-2.0"
        val TTS_ENGINE_OPTIONS = listOf(
            "auto" to "自动（有克隆用克隆，否则火山引擎，再否则系统）",
            "volcano" to "火山引擎豆包 TTS（需填写凭证）",
            "clone" to "硅基流动克隆音色（需 Key 和已上传音色）",
            "system" to "系统 TTS（离线兜底，不走网络）"
        )
        /** 主动思考的间隔档位（分钟）。 */
        val PROACTIVE_INTERVAL_OPTIONS = listOf(
            15 to "每 15 分钟",
            30 to "每 30 分钟",
            45 to "每 45 分钟（默认）",
            60 to "每 1 小时",
            120 to "每 2 小时",
            240 to "每 4 小时",
            720 to "每 12 小时"
        )
        val VOLC_RESOURCE_OPTIONS = listOf(
            "seed-tts-2.0" to "豆包语音 2.0",
            "seed-tts-1.0" to "豆包语音 1.0",
            "seed-tts-1.0-concurr" to "豆包语音 1.0 并发"
        )
        val VOLC_VOICE_OPTIONS = listOf(
            "zh_female_shuangkuaisisi_moon_bigtts" to "爽快思思（普通话·女）",
            "zh_female_tianmeixiaoyuan_moon_bigtts" to "甜美小源（普通话·女）",
            "zh_female_wanwanxiaohe_moon_bigtts" to "湾湾小何（台湾腔·女）",
            "zh_female_qingxinnvsheng_moon_bigtts" to "清新女声（普通话·女）",
            "zh_female_wenroushunv_moon_bigtts" to "温柔淑女（普通话·女）",
            "zh_female_sajiaonvyou_moon_bigtts" to "撒娇女友（普通话·女）",
            "zh_female_daimengchuanmei_moon_bigtts" to "呆萌川妹（普通话·女）",
            "zh_female_kailingvivi_moon_bigtts" to "开朗 Vivi（普通话·女）",
            "zh_female_cancan_mars_bigtts" to "灿灿（普通话·女）",
            "zh_male_yuanboxiaoshu_moon_bigtts" to "渊博小叔（普通话·男）",
            "zh_male_yunzhou_moon_bigtts" to "云舟（普通话·男）",
            "zh_male_chunhou_moon_bigtts" to "淳厚男声（普通话·男）",
            "zh_male_shaonianzixin_moon_bigtts" to "少年自信（普通话·男）",
            "zh_male_jingqiangkanye_moon_bigtts" to "精英侃爷（普通话·男）"
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

    /** 主动思考开关：让小沫自己定时想事，并主动来找主人聊天。 */
    var proactiveEnabled: Boolean
        get() = sp.getBoolean("proactiveEnabled", true)
        set(v) = sp.edit().putBoolean("proactiveEnabled", v).apply()

    /** 主动找主人的最短间隔（分钟），默认 45 分钟。 */
    var proactiveIntervalMin: Int
        get() = sp.getInt("proactiveIntervalMin", 45).coerceIn(1, 100000)
        set(v) = sp.edit().putInt("proactiveIntervalMin", v.coerceIn(1, 100000)).apply()

    /** 上次主动开口的时间戳 */
    var lastProactiveAt: Long
        get() = sp.getLong("lastProactiveAt", 0L)
        set(v) = sp.edit().putLong("lastProactiveAt", v).apply()

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

    /** TTS 引擎：auto / volcano / clone / system。旧的 cosyvoice/edge 会迁到 auto。 */
    var ttsEngine: String
        get() {
            val saved = sp.getString("ttsEngine", "auto") ?: "auto"
            return when (saved) {
                "volcano", "clone", "system", "auto" -> saved
                "cosyvoice", "edge" -> "auto"
                else -> "auto"
            }
        }
        set(v) = sp.edit().putString("ttsEngine", v).apply()

    /** 硅基流动 Key，只给克隆音色用，走 KeyVault 加密。 */
    var siliconflowKey: String
        get() = KeyVault.readNamed(sp, "siliconflowKeyEnc")
        set(v) = KeyVault.writeNamed(sp, "siliconflowKeyEnc", v)

    /** 火山引擎新控制台 API Key。槽名不复用 siliconflowKeyEnc。 */
    var volcApiKey: String
        get() = KeyVault.readNamed(sp, "volcApiKeyEnc")
        set(v) = KeyVault.writeNamed(sp, "volcApiKeyEnc", v)

    var volcAppId: String
        get() = KeyVault.readNamed(sp, "volcAppIdEnc")
        set(v) = KeyVault.writeNamed(sp, "volcAppIdEnc", v)

    var volcAccessKey: String
        get() = KeyVault.readNamed(sp, "volcAccessKeyEnc")
        set(v) = KeyVault.writeNamed(sp, "volcAccessKeyEnc", v)

    var volcResourceId: String
        get() {
            val saved = sp.getString("volcResourceId", DEFAULT_VOLC_RESOURCE) ?: DEFAULT_VOLC_RESOURCE
            return saved.ifBlank { DEFAULT_VOLC_RESOURCE }
        }
        set(v) = sp.edit().putString("volcResourceId", v.ifBlank { DEFAULT_VOLC_RESOURCE }).apply()

    var volcSpeaker: String
        get() {
            val saved = sp.getString("volcSpeaker", DEFAULT_VOLC_SPEAKER) ?: DEFAULT_VOLC_SPEAKER
            return if (VOLC_VOICE_OPTIONS.any { it.first == saved }) saved else DEFAULT_VOLC_SPEAKER
        }
        set(v) = sp.edit().putString("volcSpeaker", v).apply()

    fun hasVolcanoCredential(): Boolean =
        volcApiKey.isNotBlank() || (volcAppId.isNotBlank() && volcAccessKey.isNotBlank())

    /** 已上传到硅基流动的克隆音色 URI（speech:...）。 */
    var cloneVoiceUri: String
        get() = sp.getString("cloneVoiceUri", "") ?: ""
        set(v) = sp.edit().putString("cloneVoiceUri", v).apply()

    var cloneVoiceName: String
        get() = sp.getString("cloneVoiceName", "") ?: ""
        set(v) = sp.edit().putString("cloneVoiceName", v).apply()

    var cloneVoiceEnabled: Boolean
        get() = sp.getBoolean("cloneVoiceEnabled", false) && cloneVoiceUri.startsWith("speech:")
        set(v) = sp.edit().putBoolean("cloneVoiceEnabled", v).apply()

    fun weatherSourceLabel(): String =
        WEATHER_SOURCE_OPTIONS.firstOrNull { it.first == weatherSource }?.second ?: "自动（免费优先）"

    /** 上次查询天气的城市（用于菜单快捷查询，默认北京） */
    var lastCity: String
        get() = sp.getString("lastCity", "北京") ?: "北京"
        set(v) = sp.edit().putString("lastCity", v).apply()
    /** 退到后台也保持耳听（前台服务保活） */
    var keepListenInBackground: Boolean
        get() = sp.getBoolean("keepListenInBackground", true)
        set(v) = sp.edit().putBoolean("keepListenInBackground", v).apply()
    /** 联网搜索开关 */
    var webSearchEnabled: Boolean
        get() = sp.getBoolean("webSearchEnabled", true)
        set(v) = sp.edit().putBoolean("webSearchEnabled", v).apply()
    /** 主聊天当前会话 id */
    var activeChatId: String
        get() = sp.getString("activeChatId", "") ?: ""
        set(v) = sp.edit().putString("activeChatId", v).apply()

    /** 最近打开过的角色 id（供「给这个角色设气泡」等口令使用） */
    var lastRoleId: String
        get() = sp.getString("lastRoleId", "") ?: ""
        set(v) = sp.edit().putString("lastRoleId", v).apply()

    /** 小组件用的最近天气摘要 */
    var lastWeatherBrief: String
        get() = sp.getString("lastWeatherBrief", "") ?: ""
        set(v) = sp.edit().putString("lastWeatherBrief", v).apply()

    /** 上次主动问候时间，避免每次回到前台都播报 */
    var lastBriefAt: Long
        get() = sp.getLong("lastBriefAt", 0L)
        set(v) = sp.edit().putLong("lastBriefAt", v).apply()

    /** 通知朗读，默认关 */
    var notifySpeakEnabled: Boolean
        get() = sp.getBoolean("notifySpeakEnabled", false)
        set(v) = sp.edit().putBoolean("notifySpeakEnabled", v).apply()

    /** 锁屏也能看到小沫的消息（通知栏 + 锁屏显示聊天内容），默认开 */
    var lockScreenNotifyEnabled: Boolean
        get() = sp.getBoolean("lockScreenNotifyEnabled", true)
        set(v) = sp.edit().putBoolean("lockScreenNotifyEnabled", v).apply()

    /** 插上耳机或连上蓝牙后自动开唤醒 */
    var headsetWakeEnabled: Boolean
        get() = sp.getBoolean("headsetWakeEnabled", false)
        set(v) = sp.edit().putBoolean("headsetWakeEnabled", v).apply()

    /** 小型本地唤醒增强：近似匹配 + 无系统识别时的能量门。默认开。 */
    var localKwsEnabled: Boolean
        get() = sp.getBoolean("localKwsEnabled", true)
        set(v) = sp.edit().putBoolean("localKwsEnabled", v).apply()

    /** 面板状态续接：小沫没弹面板时，自动补「当前状态（续）」。默认开。 */
    var panelRecallEnabled: Boolean
        get() = sp.getBoolean("panelRecallEnabled", true)
        set(v) = sp.edit().putBoolean("panelRecallEnabled", v).apply()

    /** 无框模式：去掉气泡背景/描边，文字直接铺在聊天区，显示区域更大。 */
    var bubbleFrameless: Boolean
        get() = sp.getBoolean("bubbleFrameless", false)
        set(v) = sp.edit().putBoolean("bubbleFrameless", v).apply()

    /** 主聊天精选立绘 id，对应 PortraitLibrary。 */
    var portraitId: String
        get() {
            val saved = sp.getString("portraitId", PortraitLibrary.DEFAULT_ID) ?: PortraitLibrary.DEFAULT_ID
            return if (PortraitLibrary.resFor(saved) != null) saved else PortraitLibrary.DEFAULT_ID
        }
        set(v) = sp.edit().putString("portraitId", v).apply()
}

