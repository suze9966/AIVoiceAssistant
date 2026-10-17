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

    /** AI 可用的贴图标记正则：[sticker:seed]，中文状态名也要能放下。 */
    private val TAG = Regex("\\[\\s*sticker\\s*:\\s*([^\\]]{1,48})\\]", RegexOption.IGNORE_CASE)

    /** 状态表情在 assets 里的目录。 */
    const val PACK_DIR = "stickers"

    /**
     * 主人导入的状态表情。
     * 前 8 张按图上的字命名；最后一张多次裁切都没有读出字，不假装命名。
     */
    val statusPack: List<Pair<String, String>> = listOf(
        "已上吊" to "hang.jpg",
        "已石化" to "stone.jpg",
        "正在流泪" to "cry.jpg",
        "已下班" to "offwork.jpg",
        "在便秘" to "constipate.jpg",
        "正在痛经" to "cramp.jpg",
        "在滴眼药水" to "eyedrop.jpg",
        "已放学" to "schoolout.jpg",
        "状态图" to "status.jpg"
    )

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
        val raw = (m.groupValues.getOrNull(1) ?: "xiaomo").trim()
        // 状态表情优先，其次本地 emoji，最后才走旧的网络图。
        val url = packUrl(raw) ?: localStickerByEmoji(raw) ?: imageUrl(raw)
        val cleaned = text.replace(m.value, "").trim()
        return url to cleaned
    }

    /** 把本地表情地址还原成状态名。对不上时返回空。 */
    fun packLabel(url: String): String {
        val file = url.substringAfterLast('/').substringBefore('?')
        return statusPack.firstOrNull { it.second.equals(file, ignoreCase = true) }?.first.orEmpty()
    }

    /** 给模型看的文字。屏幕仍显示图片，模型只收到表情名。 */
    fun describeForModel(message: ChatMessage): String {
        if (message.type != ChatMessage.TYPE_IMAGE) return message.content
        val label = packLabel(message.content)
        val who = if (message.isMe) "主人" else "小沫"
        return if (label.isNotBlank()) {
            "（" + who + "发了一张状态表情：「" + label + "」。这是表情包，不是微信页面，要按这个名字接话。）"
        } else {
            "（" + who + "发了一张表情包。要承认看到了，再按语气接话。）"
        }
    }

    /** 按状态名或文件名取本地表情包地址。对不上返回 null。 */
    fun packUrl(raw: String): String? {
        val key = raw.trim()
            .removePrefix("该用户")
            .removePrefix("该用戶")
            .trim()
        if (key.isEmpty()) return null
        val hit = statusPack.firstOrNull { (name, file) ->
            key == name || key.equals(file, ignoreCase = true) ||
                key.equals(file.substringBeforeLast('.'), ignoreCase = true)
        } ?: return null
        return LOCAL_PREFIX + PACK_DIR + "/" + hit.second
    }

    fun packNames(): String = statusPack.joinToString("、") { it.first }

    /**
     * 把 [sticker:😊] 里的 emoji 解析成本地图片表情地址。
     * @return 不是 emoji（或面板里没有）时返回 null，交回原来的网络图源。
     */
    fun localStickerByEmoji(raw: String): String? {
        val e = raw.trim()
        if (e.isEmpty()) return null
        // 只认单个 emoji（避免把整句话当 seed）
        if (e.codePointCount(0, e.length) > 2) return null
        // 必须是纯 emoji（沿用 ChatMessage 的判断，口径一致）
        if (!isEmojiLike(e)) return null
        val pick = emojiPanel.indexOf(e)
        // 面板里没有这个 emoji、或它对应的是 Unicode 未定义码点（没有图）时，
        // 按情绪归类就近取一张，避免出现空白气泡。
        val path = if (pick >= 0 && pick !in MISSING_INDEX) {
            val cps = e.codePoints().toArray().joinToString("-") { String.format("%X", it) }
            LOCAL_EMOJI_DIR + "/" + String.format("%04d", pick) + "_" + cps + ".png"
        } else {
            val byMood = moodOfEmoji(e)
            val alt = localStickerFor(byMood).removePrefix(LOCAL_PREFIX)
            if (alt.isBlank()) return null
            alt
        }
        return LOCAL_PREFIX + path
    }

    /**
     * 面板里这些下标对应的是 Unicode 尚未定义的码点（OpenMoji 也没有图），
     * 挑图时跳过它们，改用同情绪的就近图片。
     */
    private val MISSING_INDEX = setOf<Int>()

    /** 粗略判断是不是 emoji（和 ChatMessage.isPureEmoji 口径一致，额外容忍单符号）。 */
    private fun isEmojiLike(t: String): Boolean {
        var i = 0
        var has = false
        while (i < t.length) {
            val cp = t.codePointAt(i)
            val cc = Character.charCount(cp)
            i += cc
            if (cp == 0x20 || cp == 0xFE0F || cp == 0x200D) continue
            if (!EmojiDetector.isEmoji(cp)) return false
            has = true
        }
        return has
    }

    /** 把一个 emoji 粗略归到 [EmotionEngine.Mood] 名上，便于就近取图。 */
    private fun moodOfEmoji(e: String): String = when {
        e in listOf("😢","😭","🥺","😔","😞","💧","💔","😿") -> "SAD"
        e in listOf("😤","😠","😡","😒","😾","💢","🙄","👿") -> "ANNOYED"
        e in listOf("😴","🥱","😪","💤","😑","😐") -> "TIRED"
        e in listOf("😳","🙈","😚","💗","🥰") -> "SHY"
        e in listOf("🤔","🧐","👀","💡","🤨","❓","❔") -> "CURIOUS"
        e in listOf("😊","😄","😆","😀","😁","😍","🥳","😂","🤣") -> "HAPPY"
        e in listOf("🙂","😌","😇","🌿","🌸","🍀","☺") -> "CALM"
        else -> "HAPPY"
    }

    /** 供 AI 提示词使用：告诉模型可用哪些贴图关键词 */
    fun stickerHint(): String = listOf(
        "happy", "love", "sad", "angry", "cry", "laugh",
        "shy", "sleepy", "surprise", "cool", "think", "wave"
    ).joinToString("、")

    // ---------------- 本地图片表情（打包进 assets，离线可用） ----------------

    /** 图片表情在 assets 里的目录 */
    const val LOCAL_EMOJI_DIR = "emoji"

    /** 图片表情在气泡里用的统一前缀，ImageLoader 见到它就按 assets 读 */
    const val LOCAL_PREFIX = "asset://"

    /**
     * 按心情挑一张**本地图片表情**。返回 `asset://emoji/xxxx_1F600.png` 形式的地址，
     * 交给 [ImageLoader] 从 APK 内置的 assets 里读，不联网。
     */
    /** 主人刚发来的状态表情，回一张同名或挨着的状态图。 */
    fun replyPackFor(label: String): String {
        val hit = statusPack.firstOrNull { it.first == label.trim() } ?: statusPack.first()
        return LOCAL_PREFIX + PACK_DIR + "/" + hit.second
    }

    fun localStickerFor(mood: String?): String {
        // 按情绪给一组「候选 emoji」，再从面板里定位它们的下标，
        // 不依赖面板顺序，改动面板也不会错位。
        val wanted = when (mood) {
            "HAPPY" -> listOf("😊", "😄", "😆", "🥰", "😍", "😀", "🥳", "😁")
            "CALM" -> listOf("🙂", "😌", "😇", "🌿", "🌸", "🍀")
            "CURIOUS" -> listOf("🤔", "🧐", "👀", "💡", "🤨")
            "SHY" -> listOf("😳", "🙈", "😊", "💗", "😚")
            "TIRED" -> listOf("😴", "🥱", "😪", "💤", "😑")
            "SAD" -> listOf("😢", "🥺", "😔", "😞", "💧")
            "ANNOYED" -> listOf("😤", "😠", "😒", "😾", "💢")
            else -> listOf("🙂", "😊", "🌸", "😀")
        }
        val found = wanted.filter { w -> emojiPanel.indexOf(w) >= 0 }
        val pool = found.ifEmpty { listOf(emojiPanel.firstOrNull() ?: return "") }
        val emoji = pool[(Math.random() * pool.size).toInt().coerceIn(0, pool.size - 1)]
        val pick = emojiPanel.indexOf(emoji)
        if (pick < 0) return ""
        val cps = emoji.codePoints().toArray().joinToString("-") { String.format("%X", it) }
        return LOCAL_PREFIX + LOCAL_EMOJI_DIR + "/" + String.format("%04d", pick) + "_" + cps + ".png"
    }

    /**
     * 给云端模型用的「图片表情」说明：告诉它可以用 [sticker:emoji] 语法发本地图，
     * 以及什么时候该发——这是「按情绪逻辑发」的规则来源。
     */
    fun localStickerRule(): String = listOf(
        "你还能发**图片表情**（比文字表情更大更可爱）。",
        "用法：在想发的时候，单独写一个标记 [sticker:😊]，把里面的 emoji 换成你想表达的情绪即可；",
        "程序会自动把它换成对应的图片表情发出去，你不需要解释这个标记。",
        "另外有一套状态表情包，名字是：",
        packNames(),
        "。想发时写 [sticker:已上吊] 这种，括号里必须是上面的名字之一。",
        "规矩：",
        "1. 闲聊、撒娇、安慰、接梗时，几乎每次都要发一张图片表情，标记单独放在回复最后一行；",
        "2. 一条回复最多发一个；状态名只能用上面那九个，情绪表情用单个 emoji；",
        "3. 主人发来状态表情，你要回一张对得上的状态表情，再补一句接话；",
        "4. 报时间、报天气、念工具结果、解答问题、说明事实时不要发图；",
        "5. 不要解释标记，也不要说自己不会发表情包。"
    ).joinToString("")

    // ---------------- 小沫自己的情绪表情（都取自 emojiPanel，保证不重复） ----------------

    /**
     * 按心情取表情。key 用 [EmotionEngine.Mood] 的名字（HAPPY/SHY/...），
     * 每类 4 个，随机挑一个，让同一心情也不会每次都一样。
     */
    fun moodsFor(mood: String?): String {
        val list = when (mood) {
            "HAPPY" -> listOf("😊", "😄", "🥰", "😆")
            "CALM" -> listOf("🙂", "😌", "🌿", "😇")
            "CURIOUS" -> listOf("🤔", "👀", "🧐", "💡")
            "SHY" -> listOf("😳", "😊", "🙈", "💗")
            "TIRED" -> listOf("😴", "🥱", "😪", "💤")
            "SAD" -> listOf("😢", "🥺", "💧", "😔")
            "ANNOYED" -> listOf("😤", "😠", "😾", "💢")
            else -> listOf("🙂", "😊", "🌸", "😀")
        }
        return list[(Math.random() * list.size).toInt().coerceIn(0, list.size - 1)]
    }

    /** 给云端模型的「表情使用规矩」：从这 1000 个里挑，别乱贴。 */
    fun emojiRule(): String = listOf(
        "你可以从这套表情里挑着用：",
        emojiPanel.take(500).joinToString(""),
        "。",
        "规矩：一条回复最多 1 到 2 个，贴在句尾或情绪转折处，不要塞进句子中间；",
        "只在真有情绪时才用（高兴、害羞、心疼、好奇、撒娇、委屈）；",
        "报时间、报天气、念工具结果、说明事实时不要贴；",
        "表情要和这句话的情绪一致，别开心配哭脸；",
        "不要解释你用了哪个表情。"
    ).joinToString("")

    /** 常用 emoji 面板（分组的扁平列表，供快速发送） */
    val emojiPanel: List<String> = listOf(
        "😀", "😁", "😂", "😃", "😄", "😅", "😆", "😇", "😈", "😉", "😊", "😋",
        "😌", "😍", "😎", "😏", "😐", "😑", "😒", "😓", "😔", "😕", "😖", "😗",
        "😘", "😙", "😚", "😛", "😜", "😝", "😞", "😟", "😠", "😡", "😢", "😣",
        "😤", "😥", "😦", "😧", "😨", "😩", "😪", "😫", "😬", "😭", "😮", "😯",
        "😰", "😱", "😲", "😳", "😴", "😵", "😶", "😷", "😸", "😹", "😺", "😻",
        "😼", "😽", "😾", "😿", "🙀", "🙁", "🙂", "🙃", "🙄", "🙅", "🙆", "🙇",
        "🙈", "🙉", "🙊", "🙋", "🙌", "🙍", "🙎", "🙏", "🫠", "🫡", "🫢", "🫣",
        "🫤", "🫥", "🫨", "🫰", "🫱", "🫲", "🫳", "🫴", "🤌", "🤍", "🤎", "🤏",
        "🤐", "🤑", "🤒", "🤓", "🤔", "🤕", "🤖", "🤗", "🤘", "🤙", "🤚", "🤛",
        "🤜", "🤝", "🤞", "🤟", "🤠", "🤡", "🤢", "🤣", "🤤", "🤥", "🤦", "🤧",
        "🤨", "🤩", "🤪", "🤫", "🤬", "🤭", "🤮", "🤯", "🤰", "🤱", "🤲", "🤳",
        "🤴", "🤵", "🤶", "🤷", "🤸", "🤹", "🤺", "🫵", "🤼", "🤽", "🤾", "🤿",
        "🥀", "🥁", "🥂", "🥃", "🥄", "🥅", "🫶", "🥇", "🥈", "🥉", "🥊", "🥋",
        "🥌", "🥍", "🥎", "🥏", "🥐", "🥑", "🥒", "🥓", "🥔", "🥕", "🥖", "🥗",
        "🥘", "🥙", "🥚", "🥛", "🥜", "🥝", "🥞", "🥟", "🥠", "🥡", "🥢", "🥣",
        "🥤", "🥥", "🥦", "🥧", "🥨", "🥩", "🥪", "🥫", "🥬", "🥭", "🥮", "🥯",
        "🥰", "🥱", "🥲", "🥳", "🥴", "🥵", "🥶", "🥷", "🥸", "🥹", "🥺", "🥻",
        "🥼", "🥽", "🥾", "🥿", "🦀", "🦁", "🦂", "🦃", "🦄", "🦅", "🦆", "🦇",
        "🦈", "🦉", "🦊", "🦋", "🦌", "🦍", "🦎", "🦏", "🦐", "🦑", "🦒", "🦓",
        "🦔", "🦕", "🦖", "🦗", "🦘", "🦙", "🦚", "🦛", "🦜", "🦝", "🦞", "🦟",
        "🦠", "🦡", "🦢", "🦣", "🦤", "🦥", "🦦", "🦧", "🦨", "🦩", "🦪", "🦫",
        "🦬", "🦭", "🦮", "🦯", "🦰", "🦱", "🦲", "🦳", "🦴", "🦵", "🦶", "🦷",
        "🦸", "🦹", "🦺", "🦻", "🦼", "🦽", "🦾", "🦿", "🧀", "🧁", "🧂", "🧃",
        "🧄", "🧅", "🧆", "🧇", "🧈", "🧉", "🧊", "🧋", "🧌", "🧍", "🧎", "🧏",
        "🧐", "🧑", "🧒", "🧓", "🧔", "🧕", "🧖", "🧗", "🧘", "🧙", "🧚", "🧛",
        "🧜", "🧝", "🧞", "🧟", "🧠", "🧡", "🧢", "🧣", "🧤", "🧥", "🧦", "🧧",
        "🧨", "🧩", "🧪", "🧫", "🧬", "🧭", "🧮", "🧯", "🧰", "🧱", "🧲", "🧳",
        "🧴", "🧵", "🧶", "🧷", "🧸", "🧹", "🧺", "🧻", "🧼", "🧽", "🧾", "🧿",
        "🚀", "🚁", "🚂", "🚃", "🚄", "🚅", "🚆", "🚇", "🚈", "🚉", "🚊", "🚋",
        "🚌", "🚍", "🚎", "🚏", "🚐", "🚑", "🚒", "🚓", "🚔", "🚕", "🚖", "🚗",
        "🚘", "🚙", "🚚", "🚛", "🚜", "🚝", "🚞", "🚟", "🚠", "🚡", "🚢", "🚣",
        "🚤", "🚥", "🚦", "🚧", "🚨", "🚩", "🚪", "🚫", "🚬", "🚭", "🚮", "🚯",
        "🚰", "🚱", "🚲", "🚳", "🚴", "🚵", "🚶", "🚷", "🚸", "🚹", "🚺", "🚻",
        "🚼", "🚽", "🚾", "🚿", "🛀", "🛁", "🛂", "🛃", "🛄", "🛅", "🫷", "🫸",
        "🩷", "🩸", "🩹", "🛋", "🛌", "🛍", "🛎", "🛏", "🛐", "🛑", "🛒", "🩺",
        "🩻", "🛕", "🛖", "🛗", "🛜", "🛝", "🛞", "🛟", "🛠", "🛡", "🛢", "🛣",
        "🛤", "🛥", "🩼", "🪀", "🪁", "🛩", "🪂", "🛫", "🛬", "🛰", "🪃", "🪄",
        "🛳", "🛴", "🛵", "🛶", "🛷", "🛸", "🛹", "🛺", "🛻", "🛼", "🌀", "🌁",
        "🌂", "🌃", "🌄", "🌅", "🌆", "🌇", "🌈", "🌉", "🌊", "🌋", "🌌", "🌍",
        "🌎", "🌏", "🌐", "🌑", "🌒", "🌓", "🌔", "🌕", "🌖", "🌗", "🌘", "🌙",
        "🌚", "🌛", "🌜", "🌝", "🌞", "🌟", "🌠", "🌡", "🪅", "🪆", "🌤", "🌥",
        "🌦", "🌧", "🌨", "🌩", "🌪", "🌫", "🌬", "🌭", "🌮", "🌯", "🌰", "🌱",
        "🌲", "🌳", "🌴", "🌵", "🌶", "🌷", "🌸", "🌹", "🌺", "🌻", "🌼", "🌽",
        "🌾", "🌿", "🍀", "🍁", "🍂", "🍃", "🍄", "🍅", "🍆", "🍇", "🍈", "🍉",
        "🍊", "🍋", "🍌", "🍍", "🍎", "🍏", "🍐", "🍑", "🍒", "🍓", "🍔", "🍕",
        "🍖", "🍗", "🍘", "🍙", "🍚", "🍛", "🍜", "🍝", "🍞", "🍟", "🍠", "🍡",
        "🍢", "🍣", "🍤", "🍥", "🍦", "🍧", "🍨", "🍩", "🍪", "🍫", "🍬", "🍭",
        "🍮", "🍯", "🍰", "🍱", "🍲", "🍳", "🍴", "🍵", "🍶", "🍷", "🍸", "🍹",
        "🍺", "🍻", "🍼", "🍽", "🍾", "🍿", "🎀", "🎁", "🎂", "🎃", "🎄", "🎅",
        "🎆", "🎇", "🎈", "🎉", "🎊", "🎋", "🎌", "🎍", "🎎", "🎏", "🎐", "🎑",
        "🎒", "🎓", "🪐", "🪑", "🎖", "🎗", "🪒", "🎙", "🎚", "🎛", "🪓", "🪔",
        "🎞", "🎟", "🎠", "🎡", "🎢", "🎣", "🎤", "🎥", "🎦", "🎧", "🎨", "🎩",
        "🎪", "🎫", "🎬", "🎭", "🎮", "🎯", "🎰", "🎱", "🎲", "🎳", "🎴", "🎵",
        "🎶", "🎷", "🎸", "🎹", "🎺", "🎻", "🎼", "🎽", "🎾", "🎿", "🏀", "🏁",
        "🏂", "🏃", "🏄", "🏅", "🏆", "🏇", "🏈", "🏉", "🏊", "🏋", "🏌", "🏍",
        "🏎", "🏏", "🏐", "🏑", "🏒", "🏓", "🏔", "🏕", "🏖", "🏗", "🏘", "🏙",
        "🏚", "🏛", "🏜", "🏝", "🏞", "🏟", "🏠", "🏡", "🏢", "🏣", "🏤", "🏥",
        "🏦", "🏧", "🏨", "🏩", "🏪", "🏫", "🏬", "🏭", "🏮", "🏯", "🏰", "🪕",
        "🪖", "🏳", "🏴", "🏵", "🪗", "🏷", "🏸", "🏹", "🏺", "🐀", "🐁", "🐂",
        "🐃", "🐄", "🐅", "🐆", "🐇", "🐈", "🐉", "🐊", "🐋", "🐌", "🐍", "🐎",
        "🐏", "🐐", "🐑", "🐒", "🐓", "🐔", "🐕", "🐖", "🐗", "🐘", "🐙", "🐚",
        "🐛", "🐜", "🐝", "🐞", "🐟", "🐠", "🐡", "🐢", "🐣", "🐤", "🐥", "🐦",
        "🐧", "🐨", "🐩", "🐪", "🐫", "🐬", "🐭", "🐮", "🐯", "🐰", "🐱", "🐲",
        "🐳", "🐴", "🐵", "🐶", "🐷", "🐸", "🐹", "🐺", "🐻", "🐼", "🐽", "🐾",
        "🐿", "👀", "👁", "👂", "👃", "👄", "👅", "👆", "👇", "👈", "👉", "👊",
        "👋", "👌", "👍", "👎", "👏", "👐", "👑", "👒", "👓", "👔", "👕", "👖",
        "👗", "👘", "👙", "👚", "👛", "👜", "👝", "👞", "👟", "👠", "👡", "👢",
        "👣", "👤", "👥", "👦", "👧", "👨", "👩", "👪", "👫", "👬", "👭", "👮",
        "👯", "👰", "👱", "👲", "👳", "👴", "👵", "👶", "👷", "👸", "👹", "👺",
        "👻", "👼", "👽", "👾", "👿", "💀", "💁", "💂", "💃", "💄", "💅", "💆",
        "💇", "💈", "💉", "💊", "💋", "💌", "💍", "💎", "💏", "💐", "💑", "💒",
        "💓", "💔", "💕", "💖", "💗", "💘", "💙", "💚", "💛", "💜", "💝", "💞",
        "💟", "💠", "💡", "💢", "💣", "💤", "💥", "💦", "💧", "💨", "💩", "💪",
        "💫", "💬", "💭", "💮", "💯", "💰", "💱", "💲", "💳", "💴", "💵", "💶",
        "💷", "💸", "💹", "💺", "💻", "💼", "💽", "💾", "💿", "📀", "📁", "📂",
        "📃", "📄", "📅", "📆", "📇", "📈", "📉", "📊", "📋", "📌", "📍", "📎",
        "📏", "📐", "📑", "📒", "📓", "📔", "📕", "📖", "📗", "📘", "📙", "📚",
        "📛", "📜", "📝", "📞", "📟", "📠", "📡", "📢", "📣", "📤", "📥", "📦",
        "📧", "📨", "📩", "📪", "📫", "📬", "📭", "📮", "📯", "📰", "📱", "📲",
        "📳", "📴", "📵", "📶", "📷", "📸", "📹", "📺", "📻", "📼", "📽", "🪘",
        "📿", "🔀", "🔁", "🔂", "🔃", "🔄", "🔅", "🔆", "🔇", "🔈", "🔉", "🔊",
        "🔋", "🔌", "🔍", "🔎", "🔏", "🔐", "🔑", "🔒", "🔓", "🔔", "🔕", "🔖",
        "🔗", "🔘", "🔙", "🔚", "🔛", "🔜", "🔝", "🔞", "🔟", "🔠", "🔡", "🔢",
        "🔣", "🔤", "🔥", "🔦"
    )
}