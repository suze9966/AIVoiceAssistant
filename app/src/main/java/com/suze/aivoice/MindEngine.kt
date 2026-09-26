package com.suze.aivoice

import android.content.Context

/**
 * 自主思考引擎（MindEngine）
 *
 * 参考经验：
 *  - Generative Agents（斯坦福小镇）：反思（reflection）—— 把零散经历总结成更高层的“想法”。
 *  - AutoGPT / BabyAGI：自主目标 —— AI 自己拆解、追踪想要完成的小目标。
 *  - 角色扮演类产品（星野/猫箱）：内心独白（monologue）—— 先在心里想，再开口。
 *
 * 实现“独立思考”的观感：每轮对话前，先用一条“内心想法（thought）”内部推演，
 * 再把“对外发言（say）”输出给主人。内心独白不直接展示，但会被塞进提示词里，
 * 让模型真的按自己的想法来说话；同时周期性做反思，形成长期“人格记忆”。
 */
class MindEngine(context: Context) {
    private val sp = context.getSharedPreferences("ai_mind", Context.MODE_PRIVATE)

    /** 自主目标（AI 自己想做的事，例如“想多了解主人”） */
    var goal: String
        get() = sp.getString("goal", "") ?: ""
        private set(v) = sp.edit().putString("goal", v).apply()

    /** 上一次的内心独白（可用于调试/展示） */
    var lastThought: String = ""
        private set

    /** 累计经历条数，每到阈值触发一次反思 */
    private var expCount: Int
        get() = sp.getInt("expCount", 0)
        set(v) = sp.edit().putInt("expCount", v).apply()

    private val expBuffer: MutableList<String> = mutableListOf()

    init {
        // 恢复历史经历（换行分隔，最多保留 40 条）
        val raw = sp.getString("exp", "") ?: ""
        raw.split("\n").filter { it.isNotBlank() }.takeLast(40).forEach { expBuffer.add(it) }
    }

    /** 记录一条经历（用户说了什么 / 发生了什么） */
    fun record(exp: String) {
        if (exp.isBlank()) return
        expBuffer.add(exp)
        while (expBuffer.size > 40) expBuffer.removeAt(0)
        sp.edit().putString("exp", expBuffer.joinToString("\n")).apply()
        expCount = expCount + 1
    }

    /** 是否到了该反思的时机（每 12 条经历反思一次） */
    fun shouldReflect(): Boolean = expCount > 0 && expCount % 12 == 0

    /**
     * 生成“内心独白”提示词，要求模型先思考再回答。
     * 这是一种思维链（CoT）式的角色扮演：模型先在内部推演立场/情绪/意图，
     * 再输出真正给主人的话，从而显得“有自己的想法”。
     */
    fun thinkPrompt(userText: String): String {
        val goalLine = if (goal.isNotBlank()) "你最近自己想做的一件事：$goal。" else ""
        return "【内心独白任务】在回答主人这句话之前，先用第一人称在心里想一小段（不超过 40 字），" +
            "内容包括：现在的心情、你怎么看这句话、你想怎么回应、有没有自己的小主意。" +
            "$goalLine\n" +
            "【主人的话】$userText\n" +
            "【输出格式】只输出两部分，先一行以 THOUGHT: 开头的内心想法，再一行以 SAY: 开头的对主人说的话。" +
            "SAY 部分要自然、有性格，不要暴露 THOUGHT 格式。"
    }

    /**
     * 从模型整段回复中拆出（想法, 发言）。
     * 兼容模型没按要求输出的情况：没有标记就整体当作发言。
     */
    fun parse(reply: String): Pair<String, String> {
        var thought = ""
        var say = ""
        reply.split("\n").forEach { line ->
            val l = line.trim()
            when {
                l.startsWith("THOUGHT:") -> thought = l.removePrefix("THOUGHT:").trim()
                l.startsWith("想法：") || l.startsWith("想法:") -> thought = l.substringAfter("：").substringAfter(":").trim()
                l.startsWith("SAY:") -> say += l.removePrefix("SAY:").trim() + " "
            }
        }
        if (say.isBlank()) {
            // 没按格式：剥离可能的 THOUGHT 行，余下当发言
            say = reply.lines()
                .filterNot { it.trim().startsWith("THOUGHT:") || it.trim().startsWith("想法") }
                .joinToString("\n").trim()
        }
        lastThought = thought
        if (thought.isNotBlank()) record("我心想：$thought")
        return Pair(thought, say.trim())
    }

    /** 周期性反思：把最近经历汇总成一句“总结性认知”，并可更新自主目标 */
    fun reflectionPrompt(): String {
        val recent = expBuffer.takeLast(12).joinToString("；")
        return "【反思任务】以下是最近的经历：$recent。" +
            "请用一句话总结你对主人/这段关系的认识（不超过 30 字），" +
            "并想一个你接下来想主动做的小目标。输出格式：REFLECT: <总结> | GOAL: <目标>。"
    }

    /** 解析反思结果，写入自主目标 */
    fun applyReflection(reply: String) {
        val r = Regex("REFLECT:\s*(.+?)\s*\|\s*GOAL:\s*(.+)", RegexOption.DOT_MATCHES_ALL)
        val m = r.find(reply)
        if (m != null) {
            goal = m.groupValues[2].trim().take(60)
            sp.edit().putString("selfView", m.groupValues[1].trim()).apply()
        }
    }

    /** 当前“自我认知”（长期反思沉淀） */
    fun selfView(): String = sp.getString("selfView", "") ?: ""

    /** 独立思考提示词：表达“我有自己的判断，不盲从，但会为你好” */
    fun independencePrompt(): String {
        val g = if (goal.isNotBlank()) "你现在自己想做的是：$goal。" else ""
        val s = if (selfView().isNotBlank()) "你对自己的认知：${selfView()}。" else ""
        return "【独立思考】你是有独立人格的伙伴，不是只会顺从的工具：" +
            "可以表达自己的看法、适当的反对意见与好奇心，会主动提问和分享想法，" +
            "但始终把主人的利益放在第一位。$s$g只有当涉及危险/不可逆操作时才提醒确认。"
    }
}
