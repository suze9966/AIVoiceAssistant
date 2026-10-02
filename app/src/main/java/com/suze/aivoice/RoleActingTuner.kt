package com.suze.aivoice

/**
 * 入戏度自适应：让角色自动跟用户的「玩法」合拍。
 *
 * 有人喜欢纯对白（像微信聊天），有人喜欢动作旁白（小说式）。原版酒馆一律
 * 靠用户自己写系统提示去调，很麻烦。这里分析用户最近几条消息的风格——
 * 动作描写密度、对白引号使用、句长、书面程度——推断出用户想要的**叙事浓度**，
 * 自动生成一段风格指令附在系统提示末尾。用户聊得越"小说"，角色就越像小说；
 * 用户越日常，角色就越像发微信。
 */
object RoleActingTuner {

    /** 叙事浓度档位 */
    enum class Mode(val label: String) {
        CHAT("日常对白"),      // 几乎不用动作
        BALANCED("自然混合"),  // 对白为主，适度动作
        NOVEL("小说叙事")      // 动作/环境/心理描写丰富
    }

    data class Result(
        val mode: Mode,
        val actionRate: Double,    // 动作描写占比
        val quoteRate: Double,     // 含引号对白占比
        val avgLen: Double,
        val confidence: Int        // 0-100
    ) {
        fun describe(): String {
            val conf = when {
                confidence >= 70 -> "很明确"
                confidence >= 40 -> "较明显"
                else -> "暂不确定"
            }
            return "${mode.label}（动作 ${(actionRate * 100).toInt()}% · 对白 ${(quoteRate * 100).toInt()}% · 平均 ${avgLen.toInt()} 字，$conf）"
        }
    }

    /**
     * 分析历史里用户的发言（只取 user 消息，最多最近 30 条）。
     */
    fun analyze(history: List<ChatMessage>): Result {
        val userMsgs = history.filter { it.role == "user" && it.type == ChatMessage.TYPE_TEXT }
            .takeLast(200)
            .map { it.content.trim() }
            .filter { it.isNotEmpty() }
        if (userMsgs.isEmpty()) return Result(Mode.BALANCED, 0.0, 0.0, 0.0, 0)
        var action = 0
        var quote = 0
        var totalLen = 0
        userMsgs.forEach { text ->
            totalLen += text.length
            // 动作描写常见标记：星号包裹、括号动作、*、（）内动作
            if (text.contains("*") || text.contains("（") || text.contains("(") ||
                text.contains("指了指") || text.contains("走") || text.contains("看") ||
                text.contains("伸手") || text.contains("转身") || text.contains("笑"))
                action++
            // 对白引号
            if (text.contains("「") || text.contains("」") || text.contains("\"") ||
                text.contains("“") || text.contains("”") || text.contains(":"))
                quote++
        }
        val n = userMsgs.size.toDouble()
        val actionRate = action / n
        val quoteRate = quote / n
        val avgLen = totalLen / n
        val mode = when {
            actionRate >= 0.5 || avgLen >= 60 -> Mode.NOVEL
            actionRate <= 0.2 && avgLen <= 25 -> Mode.CHAT
            else -> Mode.BALANCED
        }
        // 置信度：样本越多、特征越极端越自信
        val sampleConf = (n / 8.0 * 60).toInt()
        val featureConf = (kotlin.math.abs(actionRate - 0.35) * 80).toInt()
        val confidence = (sampleConf + featureConf).coerceIn(0, 100)
        return Result(mode, actionRate, quoteRate, avgLen, confidence)
    }

    /** 生成风格指令，进系统提示 */
    fun promptBlock(history: List<ChatMessage>): String {
        val r = analyze(history)
        val style = when (r.mode) {
            Mode.CHAT -> "多用短句直接说话，动作/神态描写克制在一两句以内，像熟人发微信。"
            Mode.BALANCED -> "以对白推进，适度穿插动作与神态描写，保持节奏自然。"
            Mode.NOVEL -> "营造沉浸式小说感：丰富使用动作、神态、环境与心理描写，对白与叙述交错。"
        }
        return "【叙事风格】当前用户的风格偏向：${r.mode.label}。$style" +
            (if (r.confidence < 40) "（提示：用户风格尚不明确，可保持中性）" else "")
    }
}