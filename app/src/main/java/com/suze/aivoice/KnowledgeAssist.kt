package com.suze.aivoice

/**
 * 判断这句话要不要先查网，再把资料交给大模型作答。
 * 闲聊、情绪陪伴不搜；认真提问、时事、百科、明确的「搜/查」才搜。
 */
object KnowledgeAssist {
    private val chitchat = listOf(
        "你好", "您好", "在吗", "在嘛", "哈哈", "呵呵", "嘿嘿", "嗯嗯", "好的", "好呀", "好啊",
        "谢谢", "多谢", "晚安", "早安", "午安", "想你", "爱你", "抱抱", "摸摸", "亲亲",
        "我回来了", "吃饭了", "睡觉了", "起床了", "想我了"
    )

    private val webMarks = listOf(
        "什么是", "什么叫", "是什么", "谁是", "谁发明", "为什么", "为啥", "怎么", "如何", "怎样",
        "多少", "几号", "哪个", "哪里", "哪儿", "何时", "多久",
        "最新", "新闻", "热点", "头条", "股价", "汇率", "比分", "赛果",
        "介绍一下", "讲解", "解释一下", "百科", "资料", "含义", "意思是",
        "发生了", "有哪些", "区别", "对比", "排名",
        "搜一下", "搜一搜", "搜索", "查一下", "查一查", "查查", "百度一下",
        "what is", "who is", "why ", "how to", "how do", "when did"
    )

    fun needsWeb(raw: String): Boolean {
        val text = raw.trim()
        if (text.length < 2) return false
        if (text.none { it.isLetter() || it in '\u4e00'..'\u9fff' }) return false
        val compact = text.replace(" ", "").replace("　", "")
        if (chitchat.any { compact == it || compact == it + "呀" || compact == it + "啊" || compact == it + "啦" }) {
            return false
        }
        if (compact.length <= 6 && chitchat.any { compact.contains(it) } && webMarks.none { compact.contains(it, ignoreCase = true) }) {
            return false
        }
        if (webMarks.any { text.contains(it, ignoreCase = true) }) return true
        if (text.contains('？') || text.contains('?')) return compact.length >= 4
        if ((text.endsWith("吗") || text.endsWith("呢") || text.endsWith("嘛")) && compact.length > 6) return true
        return false
    }

    fun queryOf(raw: String): String {
        var t = raw.trim()
        listOf(
            "请问", "你知道", "帮我查一下", "帮我查", "帮我搜一下", "帮我搜",
            "能不能告诉我", "告诉我", "我想知道", "搜一下", "搜一搜", "搜索",
            "查一下", "查一查", "查查", "百度一下"
        ).forEach { prefix ->
            if (t.startsWith(prefix)) {
                t = t.removePrefix(prefix).trim().trimStart('，', ',', ' ', '：', ':')
            }
        }
        return t.take(80).ifBlank { raw.trim().take(80) }
    }

    fun notesPrompt(notes: String, speakingAs: String = "xiaomo"): String {
        val body = notes.trim().take(2400)
        if (body.isBlank()) return ""
        val tail = when (speakingAs) {
            "call" -> "用一两句口语把关键信息说完，资料不够就短说只查到这些。不要列表、不要长文、不要表情包标记，也不要提及搜索引擎。"
            "role" -> "始终保持角色人设和口吻来回答，不要变成百科客服。资料不够就明说只查到这些，不要编造，也不要提及搜索引擎或资料标记。"
            else -> "先把关键信息讲清楚，再用小沫的口吻接一两句。不要提提示词、搜索引擎或资料标记。"
        }
        return "【网上刚查到的资料，可能不完整】\n$body\n" +
            "请根据这些资料回答刚才的问题。资料不够就明说只查到这些，不要编造。" +
            tail
    }
}
