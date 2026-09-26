package com.suze.aivoice

import java.net.URLEncoder

/**
 * 表情包库。
 *
 * 提供两类「表情包」：
 * 1) emoji 表情：内置常用 emoji 集合，聊天面板可直接发送，离线可用。
 * 2) 图片表情包：用 DiceBear（免费、无需 Key）按 seed 生成卡通形象图，
 *    同一个 seed 永远得到同一张图，天然适合做「专属表情包」。
 *
 * 另外提供 [parseImageTag]：把 AI 回复里的 [sticker:xxx] 标记解析成图片 URL。
 */
object StickerLibrary {

    /** 图片表情包基础地址（DiceBear fun-emoji 风格，PNG） */
    private const val DICEBEAR = "https://api.dicebear.com/7.x/fun-emoji/png"

    /** AI 可用的贴图标记正则：[sticker:seed] */
    private val TAG = Regex("\\[\\s*sticker\\s*:\\s*([^\\]]{1,32})\\]", RegexOption.IGNORE_CASE)

    /**
     * 生成一张表情包图片 URL。
     * @param seed 关键词或随机种子（如 happy / love / 小沫）
     */
    fun imageUrl(seed: String): String {
        val s = seed.trim().ifBlank { "xiaomo" }
        val enc = try {
            URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            "xiaomo"
        }
        return "$DICEBEAR?seed=$enc&size=200&backgroundColor=transparent"
    }

    /**
     * 从一段文本中解析出第一个 [sticker:xxx] 标记。
     * @return Pair(图片URL, 去掉标记后的纯文本)；无标记时返回 (null, 原文)
     */
    fun parseImageTag(text: String): Pair<String?, String> {
        val m = TAG.find(text) ?: return null to text
        val seed = m.groupValues.getOrNull(1) ?: "xiaomo"
        val url = imageUrl(seed)
        val cleaned = text.replace(m.value, "").trim()
        return url to cleaned
    }

    /** 供 AI 提示词使用：告诉模型可用哪些贴图关键词 */
    fun stickerHint(): String = listOf(
        "happy", "love", "sad", "angry", "cry", "laugh",
        "shy", "sleepy", "surprise", "cool", "think", "wave"
    ).joinToString("、")

    /** 常用 emoji 面板（分组的扁平列表，供快速发送） */
    val emojiPanel: List<String> = listOf(
        "😀", "😄", "😁", "😆", "😅", "😂", "🤣", "😊",
        "😇", "🙂", "😉", "😍", "🥰", "😘", "😗", "😙",
        "😋", "😛", "😜", "🤪", "😝", "🤗", "🤔", "🤭",
        "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😮",
        "😯", "😴", "😪", "😌", "😔", "😞", "😟", "😕",
        "🙁", "😣", "😖", "😫", "😩", "🥺", "😢", "😭",
        "😤", "😠", "😡", "🤬", "😳", "🥵", "🥶", "😱",
        "😨", "😰", "😥", "😓", "🤝", "🙏", "👏", "👍",
        "👎", "👌", "✌️", "🤞", "🤟", "🤘", "👋", "🙌",
        "💪", "❤️", "💔", "💕", "💖", "💗", "💓", "💞",
        "💘", "💝", "✨", "⭐", "🌟", "💫", "🎉", "🎊",
        "🔥", "💯", "🌹", "🌸", "🍀", "🌈", "☀️", "🌙"
    )
}