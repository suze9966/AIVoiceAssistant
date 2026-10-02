package com.suze.aivoice

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar

/**
 * 主动思考引擎（ProactiveEngine）
 *
 * 让 AI 从「被动应答」变成「主动想事、主动找人」。
 *
 * 核心是「三明治结构」——自己决定要不要开口时，按三层一起推演：
 *   ① 底层·情感（EmotionEngine）：我现在的心情、好感度、精力够不够。
 *   ② 中层·记忆（MemoryEngine）：关于主人的相关记忆、最近经历。
 *   ③ 顶层·思考（MindEngine）：我自己的自主目标、反思沉淀、独立判断。
 *
 * 三层夹好后，交给大模型产出一条「我想主动对主人说的话」。
 * 是否真的开口，由时机门槛决定（间隔够长、精力够、不是打扰时段）。
 *
 * 全部判断在本机完成，只把最小必要上下文发给已配置的大模型。
 */
class ProactiveEngine(
    private val context: Context,
    private val prefs: Prefs,
    private val emotion: EmotionEngine,
    private val memory: MemoryEngine,
    private val mind: MindEngine
) {

    /** 是否已到可以主动开口的时机（不含内容生成）。 */
    fun shouldReachOut(): Boolean {
        if (!prefs.proactiveEnabled) return false
        if (!prefs.emotionEnabled && !prefs.mindEnabled && !prefs.growEnabled) return false
        val now = System.currentTimeMillis()
        val gap = now - prefs.lastProactiveAt
        val minGap = prefs.proactiveIntervalMin * 60_000L
        if (gap < minGap) return false
        // 深夜不打扰（主人休息时段）
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour >= 23 || hour < 7) return false
        // 精力太低就先不主动，避免显得黏人或敷衍
        if (prefs.emotionEnabled && emotion.energy < 3000) return false
        return true
    }

    /**
     * 组装三明治提示词：情感 + 记忆 + 思考。
     * 这三层共同决定「为什么现在想找主人」以及「想说什么」。
     */
    fun sandwichPrompt(): String {
        val sb = StringBuilder()
        sb.append("【主动开口任务】你现在不是被动等主人说话，而是自己想着想着，决定主动来找主人聊两句。\n")

        // ① 底层·情感
        if (prefs.emotionEnabled) {
            sb.append("① 你此刻的情绪：").append(emotion.statusLine()).append("。")
            sb.append("好感度 ").append(emotion.affinity).append("/10000，精力 ").append(emotion.energy).append("/10000。")
            sb.append("让语气符合你现在的心情，不要硬装开心。\n")
        }

        // ② 中层·记忆
        if (prefs.growEnabled) {
            val facts = memory.search("", 5).joinToString("；") { it.content }
            if (facts.isNotBlank()) {
                sb.append("② 你记得关于主人的事：").append(facts).append("。")
                sb.append("可以自然提到，但不要说“根据我的记忆”。\n")
            }
        }

        // ③ 顶层·思考
        if (prefs.mindEnabled) {
            val g = mind.goal
            if (g.isNotBlank()) sb.append("③ 你自己最近想做的一件事：").append(g).append("。")
            val sv = mind.selfView()
            if (sv.isNotBlank()) sb.append("你对自己的认知：").append(sv).append("。")
            val recent = mind.experiences().takeLast(300).joinToString("；")
            if (recent.isNotBlank()) sb.append("最近的事：").append(recent).append("。")
            sb.append("\n")
        }

        sb.append("【要求】只说一到两句，像朋友忽然想起对方一样自然，")
        sb.append("可以问一句近况、分享一个小想法、或轻轻关心，")
        sb.append("不要报时、不要念天气、不要像客服、不要每句都提问。")
        return sb.toString()
    }

    /** 主动开口与主聊天共用同一个 LlmClient，加锁保证串行，避免临时提示词互相干扰。 */
    private val llmLock = Mutex()

    /**
     * 生成一条主动消息。
     * @param llm 已配置好的大模型客户端（复用主聊天实例）
     * @return 主动消息文本；不适合开口时返回空串
     */
    suspend fun compose(llm: LlmClient, history: List<ChatMessage>): String {
        if (!shouldReachOut()) return ""
        return llmLock.withLock {
            val prompt = sandwichPrompt()
            val turn = ArrayList<ChatMessage>(history.takeLast(60))
            turn.add(ChatMessage("user", "[系统触发] 现在轮到你主动开口，请对主人说一到两句话。", isMe = true))
            val prevOverride = llm.systemPromptOverride
            val prevExtra = llm.extraSystemPrompt
            val prevThink = llm.applyCloudThink
            val prevTools = llm.toolsEnabled
            try {
                llm.systemPromptOverride = prefs.chattingPersona()
                llm.extraSystemPrompt = prompt + "\n" + emotion.emotionPrompt()
                llm.applyCloudThink = false
                llm.toolsEnabled = false
                val raw = llm.chat(turn)
                if (raw.isBlank()) ""
                else {
                    val text = if (prefs.mindEnabled) mind.parse(raw).second.ifBlank { raw } else raw
                    text.trim().take(3000)
                }
            } catch (e: Exception) {
                ""
            } finally {
                llm.systemPromptOverride = prevOverride
                llm.extraSystemPrompt = prevExtra
                llm.applyCloudThink = prevThink
                llm.toolsEnabled = prevTools
            }
        }
    }

    fun markReachedOut() {
        prefs.lastProactiveAt = System.currentTimeMillis()
    }
}