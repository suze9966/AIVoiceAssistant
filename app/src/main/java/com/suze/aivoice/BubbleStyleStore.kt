package com.suze.aivoice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat

/**
 * 气泡样式（BubbleStyleStore）
 *
 * 让主人用一句话就能改聊天气泡的**形状 / 颜色 / 渐变 / 字号 / 宽度 / 内边距 / 边框 / 阴影 / 头像大小 / 间距**：
 *   - 形状：圆角 / 方正 / 胶囊 / 斜角(带尾) / 云朵 / 斜切 / 大圆角
 *   - 颜色：小沫气泡色 + 我的气泡色（支持纯色与渐变）
 *   - 渐变：气泡从 A 色渐变到 B 色
 *   - 字号：随气泡一起调
 *   - 宽度：气泡最大宽度
 *   - 内边距：气泡松紧
 *   - 透明：半透明玻璃感
 *   - 边框：粗细 / 颜色（自动跟随主色）
 *   - 阴影：气泡浮起感
 *   - 头像大小 / 气泡间距
 *   - 预设：少女风 / 微信风 / 极简风 / 暗黑风 / 海洋风 / 糖果风 / 赛博风 / 森林风 一键切换
 *   - 随机换肤：让沫沫随便挑一套
 *
 * 所有 drawable 都是运行时用 GradientDrawable 现画，不新增图片资源，不涨包。
 */
object BubbleStyleStore {

    // ---- 形状 ----
    const val SHAPE_ROUND = "round"
    const val SHAPE_SQUARE = "square"
    const val SHAPE_PILL = "pill"
    const val SHAPE_TAIL = "tail"
    const val SHAPE_CLOUD = "cloud"     // 云朵：大圆角 + 略扁
    const val SHAPE_SLANT = "slant"     // 斜切：一侧对角切
    const val SHAPE_ROUND_LARGE = "round_large" // 大圆角

    // ---- 预设 ----
    const val PRESET_GIRL = "girl"
    const val PRESET_WECHAT = "wechat"
    const val PRESET_MINIMAL = "minimal"
    const val PRESET_DARK = "dark"
    const val PRESET_OCEAN = "ocean"
    const val PRESET_CANDY = "candy"
    const val PRESET_CYBER = "cyber"
    const val PRESET_FOREST = "forest"

    // ---- 字号 ----
    const val SIZE_TINY = 13f
    const val SIZE_SMALL = 14f
    const val SIZE_NORMAL = 16f
    const val SIZE_LARGE = 18f
    const val SIZE_HUGE = 21f

    // ---- 宽度 ----
    const val WIDTH_NORMAL = 78
    private const val WIDTH_MIN = 40
    private const val WIDTH_MAX = 100

    // ---- 内边距 ----
    data class Pad(val h: Int, val v: Int)
    val PAD_TIGHT = Pad(10, 6)
    val PAD_NORMAL = Pad(14, 10)
    val PAD_LOOSE = Pad(20, 14)

    // ---- 头像 / 间距 ----
    const val AVATAR_NORMAL = 36
    const val AVATAR_SMALL = 28
    const val AVATAR_LARGE = 44

    data class Gap(val h: Int, val v: Int)
    val GAP_NORMAL = Gap(12, 10)
    val GAP_TIGHT = Gap(6, 4)
    val GAP_LOOSE = Gap(20, 16)

    // ---- 尾巴方向 ----
    const val TAIL_AUTO = "auto"     // 我方朝右、对方朝左（默认）
    const val TAIL_LEFT = "left"
    const val TAIL_RIGHT = "right"
    const val TAIL_NONE = "none"

    // ---- 出现动效 ----
    const val ANIM_NONE = "none"
    const val ANIM_FADE = "fade"     // 淡入
    const val ANIM_POP = "pop"       // 弹入（缩放）
    const val ANIM_SLIDE = "slide"   // 侧滑入

    // ---- 圆角程度（dp） ----
    const val CORNER_PRESET_SHARP = 4
    const val CORNER_PRESET_NORMAL = 18
    const val CORNER_PRESET_ROUND = 26

    private val COLOR_WORDS = mapOf(
        "粉" to "#F8BBD0", "粉色" to "#F8BBD0", "少女粉" to "#F8BBD0", "樱花" to "#FFD1DC",
        "红" to "#FF8A80", "红色" to "#FF8A80", "西瓜" to "#FF8A80",
        "橙" to "#FFCC80", "橙色" to "#FFCC80", "橘" to "#FFCC80", "蜜桃" to "#FFCCBC",
        "黄" to "#FFF59D", "黄色" to "#FFF59D", "柠檬" to "#FFF59D",
        "绿" to "#A5D6A7", "绿色" to "#A5D6A7", "薄荷" to "#B2DFDB", "抹茶" to "#C5E1A5",
        "青" to "#80DEEA", "青色" to "#80DEEA",
        "蓝" to "#90CAF9", "蓝色" to "#90CAF9", "天蓝" to "#81D4FA", "海蓝" to "#64B5F6",
        "紫" to "#CE93D8", "紫色" to "#CE93D8", "紫罗兰" to "#B39DDB", "香芋" to "#D1C4E9",
        "灰" to "#CFD8DC", "灰色" to "#CFD8DC", "雾灰" to "#ECEFF1",
        "白" to "#F5F5F5", "白色" to "#F5F5F5", "米白" to "#FAF3E0", "奶白" to "#FFF8E1",
        "黑" to "#37474F", "黑色" to "#37474F", "墨色" to "#263238",
        "棕" to "#BCAAA4", "咖啡" to "#BCAAA4", "奶茶" to "#D7CCC8",
        "金" to "#FFE082", "金色" to "#FFE082", "香槟" to "#F5E6CA"
    )

    data class Style(
        val shape: String = SHAPE_ROUND,
        val aiColor: String = "#FFFFFF",
        val meColor: String = "#E9D5FF",
        val transparent: Boolean = false,
        val fontSp: Float = SIZE_NORMAL,
        val maxWidthPercent: Int = WIDTH_NORMAL,
        val padH: Int = PAD_NORMAL.h,
        val padV: Int = PAD_NORMAL.v,
        /** 渐变第二色（为空时是纯色） */
        val aiColor2: String = "",
        val meColor2: String = "",
        /** 边框粗细（dp），0 = 不加粗 */
        val borderDp: Int = 1,
        /** 阴影：气泡浮起 */
        val shadow: Boolean = false,
        /** 头像直径 dp */
        val avatarDp: Int = AVATAR_NORMAL,
        /** 气泡间距 dp */
        val gapH: Int = GAP_NORMAL.h,
        val gapV: Int = GAP_NORMAL.v,
        /** 尾巴方向：auto / left / right / none */
        val tailSide: String = TAIL_AUTO,
        /** 出现动效：none / fade / pop / slide */
        val anim: String = ANIM_NONE,
        /** 圆角程度 dp（仅 round/单圆角形状生效） */
        val cornerDp: Int = CORNER_PRESET_NORMAL,
        /** 去掉文本首尾引号 */
        val trimQuotes: Boolean = false
    ) {
        val isDefault: Boolean
            get() = shape == SHAPE_ROUND && aiColor == "#FFFFFF" && meColor == "#E9D5FF" &&
                    !transparent && fontSp == SIZE_NORMAL && maxWidthPercent == WIDTH_NORMAL &&
                    padH == PAD_NORMAL.h && padV == PAD_NORMAL.v &&
                    aiColor2.isBlank() && meColor2.isBlank() &&
                    borderDp == 1 && !shadow &&
                    avatarDp == AVATAR_NORMAL &&
                    gapH == GAP_NORMAL.h && gapV == GAP_NORMAL.v &&
                    tailSide == TAIL_AUTO && anim == ANIM_NONE &&
                    cornerDp == CORNER_PRESET_NORMAL && !trimQuotes
    }

    private const val KEY_SHAPE = "bubbleShape"
    private const val KEY_AI = "bubbleAiColor"
    private const val KEY_ME = "bubbleMeColor"
    private const val KEY_AI2 = "bubbleAiColor2"
    private const val KEY_ME2 = "bubbleMeColor2"
    private const val KEY_TRANSPARENT = "bubbleTransparent"
    private const val KEY_FONT = "bubbleFontSp"
    private const val KEY_WIDTH = "bubbleWidth"
    private const val KEY_PAD_H = "bubblePadH"
    private const val KEY_PAD_V = "bubblePadV"
    private const val KEY_BORDER = "bubbleBorderDp"
    private const val KEY_SHADOW = "bubbleShadow"
    private const val KEY_AVATAR = "bubbleAvatarDp"
    private const val KEY_GAP_H = "bubbleGapH"
    private const val KEY_GAP_V = "bubbleGapV"
    private const val KEY_TAIL = "bubbleTailSide"
    private const val KEY_ANIM = "bubbleAnim"
    private const val KEY_CORNER = "bubbleCornerDp"
    private const val KEY_TRIM_QUOTES = "bubbleTrimQuotes"

    private fun sp(context: Context) =
        context.getSharedPreferences("ai_voice_prefs", Context.MODE_PRIVATE)

    fun load(context: Context): Style {
        val p = sp(context)
        return Style(
            shape = p.getString(KEY_SHAPE, SHAPE_ROUND) ?: SHAPE_ROUND,
            aiColor = p.getString(KEY_AI, "#FFFFFF") ?: "#FFFFFF",
            meColor = p.getString(KEY_ME, "#E9D5FF") ?: "#E9D5FF",
            aiColor2 = p.getString(KEY_AI2, "") ?: "",
            meColor2 = p.getString(KEY_ME2, "") ?: "",
            transparent = p.getBoolean(KEY_TRANSPARENT, false),
            fontSp = p.getFloat(KEY_FONT, SIZE_NORMAL),
            maxWidthPercent = p.getInt(KEY_WIDTH, WIDTH_NORMAL).coerceIn(WIDTH_MIN, WIDTH_MAX),
            padH = p.getInt(KEY_PAD_H, PAD_NORMAL.h),
            padV = p.getInt(KEY_PAD_V, PAD_NORMAL.v),
            borderDp = p.getInt(KEY_BORDER, 1),
            shadow = p.getBoolean(KEY_SHADOW, false),
            avatarDp = p.getInt(KEY_AVATAR, AVATAR_NORMAL),
            gapH = p.getInt(KEY_GAP_H, GAP_NORMAL.h),
            gapV = p.getInt(KEY_GAP_V, GAP_NORMAL.v),
            tailSide = p.getString(KEY_TAIL, TAIL_AUTO) ?: TAIL_AUTO,
            anim = p.getString(KEY_ANIM, ANIM_NONE) ?: ANIM_NONE,
            cornerDp = p.getInt(KEY_CORNER, CORNER_PRESET_NORMAL),
            trimQuotes = p.getBoolean(KEY_TRIM_QUOTES, false)
        )
    }

    fun save(context: Context, style: Style) {
        sp(context).edit()
            .putString(KEY_SHAPE, style.shape)
            .putString(KEY_AI, style.aiColor)
            .putString(KEY_ME, style.meColor)
            .putString(KEY_AI2, style.aiColor2)
            .putString(KEY_ME2, style.meColor2)
            .putBoolean(KEY_TRANSPARENT, style.transparent)
            .putFloat(KEY_FONT, style.fontSp)
            .putInt(KEY_WIDTH, style.maxWidthPercent.coerceIn(WIDTH_MIN, WIDTH_MAX))
            .putInt(KEY_PAD_H, style.padH)
            .putInt(KEY_PAD_V, style.padV)
            .putInt(KEY_BORDER, style.borderDp)
            .putBoolean(KEY_SHADOW, style.shadow)
            .putInt(KEY_AVATAR, style.avatarDp)
            .putInt(KEY_GAP_H, style.gapH)
            .putInt(KEY_GAP_V, style.gapV)
            .putString(KEY_TAIL, style.tailSide)
            .putString(KEY_ANIM, style.anim)
            .putInt(KEY_CORNER, style.cornerDp)
            .putBoolean(KEY_TRIM_QUOTES, style.trimQuotes)
            .apply()
    }

    fun reset(context: Context) {
        sp(context).edit()
            .remove(KEY_SHAPE)
            .remove(KEY_AI)
            .remove(KEY_ME)
            .remove(KEY_AI2)
            .remove(KEY_ME2)
            .remove(KEY_TRANSPARENT)
            .remove(KEY_FONT)
            .remove(KEY_WIDTH)
            .remove(KEY_PAD_H)
            .remove(KEY_PAD_V)
            .remove(KEY_BORDER)
            .remove(KEY_SHADOW)
            .remove(KEY_AVATAR)
            .remove(KEY_GAP_H)
            .remove(KEY_GAP_V)
            .remove(KEY_TAIL)
            .remove(KEY_ANIM)
            .remove(KEY_CORNER)
            .remove(KEY_TRIM_QUOTES)
            .apply()
    }

    /** 一键套用预设主题。 */
    fun preset(context: Context, id: String): Style {
        val s = when (id) {
            PRESET_GIRL -> Style(
                shape = SHAPE_PILL, aiColor = "#FFF0F6", meColor = "#F8BBD0",
                aiColor2 = "#FCE4EC", meColor2 = "#F48FB1",
                padH = 16, padV = 10, shadow = true
            )
            PRESET_WECHAT -> Style(
                shape = SHAPE_TAIL, aiColor = "#FFFFFF", meColor = "#95EC69",
                maxWidthPercent = 75, padH = 14, padV = 10
            )
            PRESET_MINIMAL -> Style(
                shape = SHAPE_SQUARE, aiColor = "#F7F7F7", meColor = "#EDEDED",
                maxWidthPercent = 70, padH = 14, padV = 10, borderDp = 0
            )
            PRESET_DARK -> Style(
                shape = SHAPE_ROUND, aiColor = "#37474F", meColor = "#5C6BC0",
                maxWidthPercent = 80, padH = 16, padV = 11
            )
            PRESET_OCEAN -> Style(
                shape = SHAPE_CLOUD, aiColor = "#E1F5FE", meColor = "#64B5F6",
                aiColor2 = "#B3E5FC", meColor2 = "#2196F3",
                padH = 18, padV = 12, shadow = true, maxWidthPercent = 78
            )
            PRESET_CANDY -> Style(
                shape = SHAPE_PILL, aiColor = "#FFE0F0", meColor = "#B39DDB",
                aiColor2 = "#FFF59D", meColor2 = "#F48FB1",
                padH = 16, padV = 11, shadow = true, fontSp = SIZE_NORMAL
            )
            PRESET_CYBER -> Style(
                shape = SHAPE_SLANT, aiColor = "#1E1E2E", meColor = "#7C4DFF",
                aiColor2 = "#311B92", meColor2 = "#00E5FF",
                padH = 16, padV = 11, borderDp = 2, shadow = true, maxWidthPercent = 82
            )
            PRESET_FOREST -> Style(
                shape = SHAPE_ROUND_LARGE, aiColor = "#F1F8E9", meColor = "#A5D6A7",
                aiColor2 = "#DCEDC8", meColor2 = "#66BB6A",
                padH = 16, padV = 11, shadow = true
            )
            else -> Style()
        }
        save(context, s)
        return s
    }

    fun presetFromText(text: String): String? = when {
        containsAny(text, "少女风", "少女心", "可爱风", "甜甜的", "粉色主题", "公主风") -> PRESET_GIRL
        containsAny(text, "微信风", "微信气泡", "微信那样", "微信同款") -> PRESET_WECHAT
        containsAny(text, "极简风", "简约风", "性冷淡", "简单点", "朴素") -> PRESET_MINIMAL
        containsAny(text, "暗黑风", "深色气泡", "夜间气泡", "黑暗风", "暗色气泡") -> PRESET_DARK
        containsAny(text, "海洋风", "深海", "大海", "海洋") -> PRESET_OCEAN
        containsAny(text, "糖果风", "糖果色", "马卡龙") -> PRESET_CANDY
        containsAny(text, "赛博", "赛博朋克", "科技风", "霓虹") -> PRESET_CYBER
        containsAny(text, "森林风", "森林", "自然风", "清新风") -> PRESET_FOREST
        else -> null
    }

    fun presetLabel(id: String): String = when (id) {
        PRESET_GIRL -> "少女风"
        PRESET_WECHAT -> "微信风"
        PRESET_MINIMAL -> "极简风"
        PRESET_DARK -> "暗黑风"
        PRESET_OCEAN -> "海洋风"
        PRESET_CANDY -> "糖果风"
        PRESET_CYBER -> "赛博风"
        PRESET_FOREST -> "森林风"
        else -> "默认"
    }

    val allPresets: List<String> = listOf(
        PRESET_GIRL, PRESET_WECHAT, PRESET_MINIMAL, PRESET_DARK,
        PRESET_OCEAN, PRESET_CANDY, PRESET_CYBER, PRESET_FOREST
    )

    /** 让沫沫随便挑一套，返回挑中的 id（排除当前这套，避免原地踏步）。 */
    fun randomPreset(context: Context, current: String? = null): String {
        val pool = allPresets.filter { it != current }
        val pick = if (pool.isEmpty()) allPresets.random() else pool.random()
        preset(context, pick)
        return pick
    }

    /** 反查当前样式最接近哪个预设（用于随机时避免重复）。 */
    fun detectCurrentPreset(context: Context): String? {
        val now = load(context)
        return allPresets.firstOrNull { id ->
            val p = presetValue(id)
            p.shape == now.shape && p.aiColor == now.aiColor && p.meColor == now.meColor
        }
    }

    // ---- 样式收藏夹（命名样式，存成 JSON） ----

    data class Saved(val name: String, val style: Style)

    private const val KEY_FAVORITES = "bubbleFavorites"
    private const val KEY_FAV_PREFIX = "bubbleFav_"

    /** 收藏列表（按加入顺序）。 */
    fun favorites(context: Context): List<Saved> {
        val names = sp(context).getString(KEY_FAVORITES, "")?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
        return names.mapNotNull { name ->
            val json = sp(context).getString(KEY_FAV_PREFIX + name, null) ?: return@mapNotNull null
            parseStyle(json)?.let { Saved(name, it) }
        }
    }

    /** 把当前样式存成命名收藏（同名覆盖）。 */
    fun saveFavorite(context: Context, name: String): Boolean {
        val key = name.trim().take(12)
        if (key.isBlank()) return false
        val names = sp(context).getString(KEY_FAVORITES, "")?.split("|")?.filter { it.isNotBlank() }
            ?.toMutableList() ?: mutableListOf()
        if (!names.contains(key)) names.add(key)
        sp(context).edit()
            .putString(KEY_FAVORITES, names.joinToString("|"))
            .putString(KEY_FAV_PREFIX + key, encodeStyle(load(context)))
            .apply()
        return true
    }

    /** 套用命名收藏。 */
    fun applyFavorite(context: Context, name: String): Boolean {
        val key = name.trim().take(12)
        val json = sp(context).getString(KEY_FAV_PREFIX + key, null) ?: return false
        val style = parseStyle(json) ?: return false
        save(context, style)
        return true
    }

    /** 删除命名收藏。 */
    fun deleteFavorite(context: Context, name: String): Boolean {
        val key = name.trim().take(12)
        val names = sp(context).getString(KEY_FAVORITES, "")?.split("|")?.filter { it.isNotBlank() }
            ?.toMutableList() ?: return false
        if (!names.remove(key)) return false
        sp(context).edit()
            .putString(KEY_FAVORITES, names.joinToString("|"))
            .remove(KEY_FAV_PREFIX + key)
            .apply()
        return true
    }

    /** 从「收藏成XX样式」口令里取名字。 */
    fun favoriteNameFromText(text: String): String? {
        val m = Regex("收藏(?:成|为|叫)?[「\\\"']?([^「\\\"'，,。\\s]{1,12})[」\\\"']?").find(text)
        return m?.groupValues?.getOrNull(1)
    }

    /** 从「套用XX样式」口令里取名字。 */
    fun applyNameFromText(text: String): String? {
        val m = Regex("(?:套用|用回|切换?到|换成)[「\\\"']?([^「\\\"'，,。\\s]{1,12})(?:样式|气泡)").find(text)
        return m?.groupValues?.getOrNull(1)
    }

    /** 从「删除XX样式」口令里取名字。 */
    fun deleteNameFromText(text: String): String? {
        val m = Regex("(?:删除|去掉|删掉)[「\\\"']?([^「\\\"'，,。\\s]{1,12})(?:样式|气泡)").find(text)
        return m?.groupValues?.getOrNull(1)
    }

    private fun encodeStyle(s: Style): String = listOf(
        s.shape, s.aiColor, s.meColor, s.aiColor2, s.meColor2,
        s.transparent.toString(), s.fontSp.toString(), s.maxWidthPercent.toString(),
        s.padH.toString(), s.padV.toString(), s.borderDp.toString(), s.shadow.toString(),
        s.avatarDp.toString(), s.gapH.toString(), s.gapV.toString(),
        s.tailSide, s.anim, s.cornerDp.toString(), s.trimQuotes.toString()
    ).joinToString(",")

    private fun parseStyle(raw: String): Style? {
        val p = raw.split(",")
        if (p.size < 19) return null
        return runCatching {
            Style(
                shape = p[0], aiColor = p[1], meColor = p[2], aiColor2 = p[3], meColor2 = p[4],
                transparent = p[5].toBoolean(), fontSp = p[6].toFloat(),
                maxWidthPercent = p[7].toInt(), padH = p[8].toInt(), padV = p[9].toInt(),
                borderDp = p[10].toInt(), shadow = p[11].toBoolean(), avatarDp = p[12].toInt(),
                gapH = p[13].toInt(), gapV = p[14].toInt(), tailSide = p[15], anim = p[16],
                cornerDp = p[17].toInt(), trimQuotes = p[18].toBoolean()
            )
        }.getOrNull()
    }

    // ---- 按角色区分样式 ----

    private const val KEY_ROLE_PREFIX = "bubbleRole_"

    /** 某角色的专属样式；没设过返回 null（表示跟随全局）。 */
    fun roleStyle(context: Context, characterId: String): Style? {
        if (characterId.isBlank()) return null
        val raw = sp(context).getString(KEY_ROLE_PREFIX + characterId, null) ?: return null
        return parseStyle(raw)
    }

    /** 把当前全局样式绑定为某角色的专属样式。 */
    fun bindRole(context: Context, characterId: String) {
        if (characterId.isBlank()) return
        sp(context).edit()
            .putString(KEY_ROLE_PREFIX + characterId, encodeStyle(load(context)))
            .apply()
    }

    /** 清掉某角色的专属样式（改回跟随全局）。 */
    fun unbindRole(context: Context, characterId: String) {
        if (characterId.isBlank()) return
        sp(context).edit().remove(KEY_ROLE_PREFIX + characterId).apply()
    }

    /** 只算出预设对应的 Style，不落库（供比对用）。 */
    private fun presetValue(id: String): Style = when (id) {
        PRESET_GIRL -> Style(SHAPE_PILL, "#FFF0F6", "#F8BBD0")
        PRESET_WECHAT -> Style(SHAPE_TAIL, "#FFFFFF", "#95EC69")
        PRESET_MINIMAL -> Style(SHAPE_SQUARE, "#F7F7F7", "#EDEDED")
        PRESET_DARK -> Style(SHAPE_ROUND, "#37474F", "#5C6BC0")
        PRESET_OCEAN -> Style(SHAPE_CLOUD, "#E1F5FE", "#64B5F6")
        PRESET_CANDY -> Style(SHAPE_PILL, "#FFE0F0", "#B39DDB")
        PRESET_CYBER -> Style(SHAPE_SLANT, "#1E1E2E", "#7C4DFF")
        PRESET_FOREST -> Style(SHAPE_ROUND_LARGE, "#F1F8E9", "#A5D6A7")
        else -> Style()
    }

    // ---- 口令解析 ----

    fun colorFromText(text: String): String? {
        return COLOR_WORDS.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { text.contains(it.key) }
            ?.value
    }

    /** 从「A到B渐变」「A到B」这类口令里抽两个颜色。 */
    fun gradientFromText(text: String): Pair<String, String>? {
        if (!containsAny(text, "渐变", "渐变色", "晕染", "到")) return null
        if (!containsAny(text, "渐变", "晕染")) return null
        val c1 = colorFromText(text) ?: return null
        // 找第二个颜色词（排除第一个的键）
        val firstKey = COLOR_WORDS.entries
            .sortedByDescending { it.key.length }
            .firstOrNull { text.contains(it.key) }?.key ?: return null
        val second = COLOR_WORDS.entries
            .sortedByDescending { it.key.length }
            .filter { !it.key.contains(firstKey) && !firstKey.contains(it.key) }
            .firstOrNull { text.contains(it.key) }?.value ?: return null
        return c1 to second
    }

    fun shapeFromText(text: String): String? = when {
        containsAny(text, "云朵", "云朵形", "云雾", "棉花") -> SHAPE_CLOUD
        containsAny(text, "斜切", "切角", "斜边", "对角切") -> SHAPE_SLANT
        containsAny(text, "大圆角", "超圆", "很圆", "特别圆") -> SHAPE_ROUND_LARGE
        containsAny(text, "胶囊", "药丸", "椭圆", "全圆", "圆形气泡") -> SHAPE_PILL
        containsAny(text, "方正", "直角", "方块", "方形", "方一点", "方的", "方些") -> SHAPE_SQUARE
        containsAny(text, "斜角", "带尖", "小尾巴", "尾巴", "尖角", "带个角") -> SHAPE_TAIL
        containsAny(text, "圆角", "圆一点", "圆弧", "圆润", "圆滑", "圆些") -> SHAPE_ROUND
        else -> null
    }

    fun sizeFromText(text: String): Float? = when {
        containsAny(text, "超大", "巨大", "最大字号", "特别大") -> SIZE_HUGE
        containsAny(text, "大一点", "字大", "字号大", "大点字", "字大点", "大字") -> SIZE_LARGE
        containsAny(text, "小一点", "字小", "字号小", "小点字", "字小点", "小字") -> SIZE_SMALL
        containsAny(text, "字再小", "字很小", "极小", "特小") -> SIZE_TINY
        containsAny(text, "正常字号", "标准字号", "默认字号", "字号正常") -> SIZE_NORMAL
        else -> null
    }

    fun widthFromText(text: String): Int? = when {
        containsAny(text, "拉满", "铺满", "占满", "全宽", "宽到边") -> WIDTH_MAX
        containsAny(text, "宽一点", "宽点", "宽一些") -> 92
        containsAny(text, "窄一点", "窄点", "窄一些", "短一点", "短点") -> 60
        else -> null
    }

    fun paddingFromText(text: String): Pad? = when {
        containsAny(text, "松一点", "松点", "宽松", "松些") -> PAD_LOOSE
        containsAny(text, "紧一点", "紧点", "紧凑", "紧些", "挤一点") -> PAD_TIGHT
        else -> null
    }

    /** 边框口令：粗一点 / 细一点 / 去掉边框 */
    fun borderFromText(text: String): Int? = when {
        containsAny(text, "去掉边框", "不要边框", "没边框", "无边框") -> 0
        containsAny(text, "边框粗", "粗边框", "边框厚", "框粗") -> 3
        containsAny(text, "边框细", "细边框", "边框薄", "框细") -> 1
        else -> null
    }

    /** 阴影口令 */
    fun shadowFromText(text: String): Boolean? = when {
        containsAny(text, "不要阴影", "去掉阴影", "无阴影", "去掉影子") -> false
        containsAny(text, "加阴影", "要阴影", "浮起来", "立体", "有影子", "阴影") -> true
        else -> null
    }

    /** 头像大小口令 */
    fun avatarFromText(text: String): Int? = when {
        containsAny(text, "头像大", "大一点的头像", "大头像") -> AVATAR_LARGE
        containsAny(text, "头像小", "小一点的头像", "小头像") -> AVATAR_SMALL
        containsAny(text, "头像正常", "头像标准", "默认头像大小") -> AVATAR_NORMAL
        else -> null
    }

    /** 间距口令 */
    fun gapFromText(text: String): Gap? = when {
        containsAny(text, "间距大", "空隙大", "分开点", "间距宽") -> GAP_LOOSE
        containsAny(text, "间距小", "空隙小", "挨紧", "间距紧") -> GAP_TIGHT
        else -> null
    }

    /** 随机换肤口令 */
    fun isRandomRequest(text: String): Boolean =
        containsAny(text, "随机气泡", "随机换肤", "随便换个气泡", "换个随机", "随便来个气泡", "帮我换个主题") &&
            (text.contains("气泡") || text.contains("换肤"))

    /** 尾巴方向口令 */
    fun tailFromText(text: String): String? = when {
        containsAny(text, "不要尾巴", "去掉尾巴", "没尾巴", "无尾巴", "不要尖角", "去掉尖角") -> TAIL_NONE
        containsAny(text, "尾巴朝左", "尖角朝左", "尾巴在左", "往左指") -> TAIL_LEFT
        containsAny(text, "尾巴朝右", "尖角朝右", "尾巴在右", "往右指") -> TAIL_RIGHT
        containsAny(text, "尾巴朝两边", "尾巴自动", "尾巴默认") -> TAIL_AUTO
        else -> null
    }

    /** 出现动效口令 */
    fun animFromText(text: String): String? = when {
        containsAny(text, "不要动效", "去掉动画", "关闭动效", "取消动画", "别动") -> ANIM_NONE
        containsAny(text, "淡入", "渐显", "慢慢出现") -> ANIM_FADE
        containsAny(text, "弹入", "弹出", "跳出来", "Q弹", "弹一下") -> ANIM_POP
        containsAny(text, "滑入", "侧滑", "滑进来", "从旁边出来") -> ANIM_SLIDE
        else -> null
    }

    /** 圆角程度口令 */
    fun cornerFromText(text: String): Int? = when {
        containsAny(text, "直角一点", "尖一点", "棱角", "硬一点", "方棱") -> CORNER_PRESET_SHARP
        containsAny(text, "圆角大", "圆一点", "很圆", "圆弧大", "圆润") -> CORNER_PRESET_ROUND
        containsAny(text, "圆角正常", "圆角适中", "默认圆角") -> CORNER_PRESET_NORMAL
        else -> null
    }

    /** 去引号口令 */
    fun trimQuotesFromText(text: String): Boolean? = when {
        containsAny(text, "去掉引号", "不要引号", "去掉双引号", "去掉台词引号") -> true
        containsAny(text, "保留引号", "留着引号", "要引号") -> false
        else -> null
    }

    /** 尾巴/动效/圆角文案 */
    fun tailLabel(id: String): String = when (id) {
        TAIL_LEFT -> "朝左"
        TAIL_RIGHT -> "朝右"
        TAIL_NONE -> "无尾巴"
        else -> "跟随方向"
    }

    fun animLabel(id: String): String = when (id) {
        ANIM_FADE -> "淡入"
        ANIM_POP -> "弹入"
        ANIM_SLIDE -> "侧滑入"
        else -> "无动效"
    }

    fun cornerLabel(dp: Int): String = when {
        dp <= CORNER_PRESET_SHARP -> "尖角"
        dp >= CORNER_PRESET_ROUND -> "很圆"
        else -> "适中"
    }

    private fun containsAny(text: String, vararg keys: String) = keys.any { text.contains(it) }

    // ---- 绘制 ----

    fun drawableFor(context: Context, isMe: Boolean, style: Style = load(context)): GradientDrawable {
        val colorHex = if (isMe) style.meColor else style.aiColor
        val color2Hex = if (isMe) style.meColor2 else style.aiColor2
        val base = runCatching { Color.parseColor(colorHex) }.getOrDefault(Color.WHITE)
        val base2 = if (color2Hex.isNotBlank())
            runCatching { Color.parseColor(color2Hex) }.getOrDefault(base) else base

        val d = GradientDrawable()
        if (style.transparent) {
            d.setColor(withAlpha(base, 0x66))
        } else if (hasGradient(style)) {
            // 渐变：沿对角方向，A → B
            d.colors = intArrayOf(base, base2)
            d.gradientType = GradientDrawable.LINEAR_GRADIENT
            d.orientation = GradientDrawable.Orientation.TL_BR
        } else {
            d.setColor(base)
        }

        // 边框
        if (style.borderDp > 0) {
            val strokeColor = when {
                style.transparent -> withAlpha(Color.WHITE, 0x55)
                style.borderDp >= 3 -> darken(base, 0.72f)
                else -> runCatching { ContextCompat.getColor(context, R.color.glass_stroke) }
                    .getOrDefault(0x1A000000)
            }
            d.setStroke(dp(context, style.borderDp.toFloat()), strokeColor)
        }

        applyCorners(context, d, style, isMe)
        return d
    }

    private fun applyCorners(context: Context, d: GradientDrawable, style: Style, isMe: Boolean) {
        val r = dp(context, style.cornerDp.toFloat()).toFloat()
        val small = dp(context, 4f).toFloat()
        val mid = dp(context, (style.cornerDp / 1.5f)).toFloat()
        // 尾巴方向：auto 时我方朝右、对方朝左
        val tailRight = when (style.tailSide) {
            TAIL_LEFT -> false
            TAIL_RIGHT -> true
            TAIL_NONE -> false
            else -> isMe
        }
        when (style.shape) {
            SHAPE_SQUARE -> d.cornerRadius = small
            SHAPE_PILL -> d.cornerRadius = dp(context, 60f).toFloat()
            SHAPE_CLOUD -> d.cornerRadius = r
            SHAPE_ROUND_LARGE -> d.cornerRadius = r
            SHAPE_SLANT -> if (tailRight) {
                d.cornerRadii = floatArrayOf(r, r, r, r, small, small, r, r)
            } else {
                d.cornerRadii = floatArrayOf(small, small, r, r, r, r, r, r)
            }
            SHAPE_TAIL -> if (style.tailSide == TAIL_NONE) {
                d.cornerRadius = r
            } else if (tailRight) {
                d.cornerRadii = floatArrayOf(r, r, small, small, r, r, r, r)
            } else {
                d.cornerRadii = floatArrayOf(small, small, r, r, r, r, r, r)
            }
            else -> if (style.tailSide == TAIL_NONE) {
                d.cornerRadius = r
            } else if (tailRight) {
                d.cornerRadii = floatArrayOf(r, r, r, r, r, r, r, small)
            } else {
                d.cornerRadii = floatArrayOf(small, r, r, r, r, r, r, r)
            }
        }
    }

    fun hasGradient(style: Style): Boolean =
        style.aiColor2.isNotBlank() || style.meColor2.isNotBlank()

    /** 颜色文字用：深色气泡配浅字、浅色气泡配深字。 */
    fun textColorFor(isMe: Boolean, style: Style): Int {
        if (style.transparent) return 0xFF1F1F1F.toInt()
        val hex = if (isMe) style.meColor else style.aiColor
        val hex2 = if (isMe) style.meColor2 else style.aiColor2
        val c = runCatching { Color.parseColor(hex) }.getOrDefault(Color.WHITE)
        val lum = if (hex2.isNotBlank()) {
            val c2 = runCatching { Color.parseColor(hex2) }.getOrDefault(c)
            ((0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) +
                (0.299 * Color.red(c2) + 0.587 * Color.green(c2) + 0.114 * Color.blue(c2))) / 2.0 / 255.0
        } else {
            (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
        }
        return if (lum < 0.55) 0xFFF5F5F5.toInt() else 0xFF1F1F1F.toInt()
    }

    fun padHpx(context: Context, style: Style): Int = dp(context, style.padH.toFloat())
    fun padVpx(context: Context, style: Style): Int = dp(context, style.padV.toFloat())
    fun avatarPx(context: Context, style: Style): Int = dp(context, style.avatarDp.toFloat())
    fun gapHpx(context: Context, style: Style): Int = dp(context, style.gapH.toFloat())
    fun gapVpx(context: Context, style: Style): Int = dp(context, style.gapV.toFloat())

    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    // ---- 文案 ----

    fun shapeLabel(shape: String): String = when (shape) {
        SHAPE_SQUARE -> "方正"
        SHAPE_PILL -> "胶囊"
        SHAPE_TAIL -> "斜角"
        SHAPE_CLOUD -> "云朵"
        SHAPE_SLANT -> "斜切"
        SHAPE_ROUND_LARGE -> "大圆角"
        else -> "圆角"
    }

    fun colorLabel(hex: String): String =
        COLOR_WORDS.entries.firstOrNull { it.value.equals(hex, ignoreCase = true) }?.key ?: hex

    fun sizeLabel(sp: Float): String = when (sp) {
        SIZE_TINY -> "极小"
        SIZE_SMALL -> "小"
        SIZE_LARGE -> "大"
        SIZE_HUGE -> "超大"
        else -> "标准"
    }

    /** 给主人回话用的一句话描述（只描述非默认项）。 */
    fun describe(style: Style): String {
        if (style.isDefault) return "当前是默认气泡"
        val parts = mutableListOf<String>()
        parts.add("形状「${shapeLabel(style.shape)}」")
        if (hasGradient(style)) {
            parts.add("小沫色「${colorLabel(style.aiColor)}→${colorLabel(style.aiColor2)}渐变」")
        } else {
            parts.add("小沫色「${colorLabel(style.aiColor)}」")
        }
        if (hasGradient(style)) {
            parts.add("我的色「${colorLabel(style.meColor)}→${colorLabel(style.meColor2)}渐变」")
        } else {
            parts.add("我的色「${colorLabel(style.meColor)}」")
        }
        if (style.transparent) parts.add("半透明")
        if (style.fontSp != SIZE_NORMAL) parts.add("字号${sizeLabel(style.fontSp)}")
        if (style.maxWidthPercent != WIDTH_NORMAL) parts.add("宽度${style.maxWidthPercent}%")
        if (style.padH != PAD_NORMAL.h || style.padV != PAD_NORMAL.v) {
            val l = when {
                style.padH <= PAD_TIGHT.h -> "紧凑"
                style.padH >= PAD_LOOSE.h -> "宽松"
                else -> "适中"
            }
            parts.add("内边距$l")
        }
        if (style.borderDp != 1) parts.add(if (style.borderDp == 0) "无边框" else "粗边框")
        if (style.shadow) parts.add("带阴影")
        if (style.avatarDp != AVATAR_NORMAL) {
            parts.add("头像${if (style.avatarDp > AVATAR_NORMAL) "大" else "小"}")
        }
        if (style.gapH != GAP_NORMAL.h || style.gapV != GAP_NORMAL.v) {
            parts.add("间距${if (style.gapH > GAP_NORMAL.h) "宽" else "紧"}")
        }
        if (style.tailSide != TAIL_AUTO) parts.add("尾巴${tailLabel(style.tailSide)}")
        if (style.anim != ANIM_NONE) parts.add("${animLabel(style.anim)}动效")
        if (style.cornerDp != CORNER_PRESET_NORMAL) parts.add("圆角${cornerLabel(style.cornerDp)}")
        if (style.trimQuotes) parts.add("去引号")
        return parts.joinToString("、")
    }
}
