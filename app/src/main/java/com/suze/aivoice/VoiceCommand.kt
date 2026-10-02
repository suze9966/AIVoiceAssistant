package com.suze.aivoice

import java.util.Calendar

sealed class VoiceCommand {
    data class RemindAt(
        val atMillis: Long,
        val text: String,
        val repeatDaily: Boolean = false,
        val advanceMin: Int = 0
    ) : VoiceCommand()
    data class RemindIn(val atMillis: Long, val text: String) : VoiceCommand()
    data object ListReminders : VoiceCommand()
    data object CancelReminders : VoiceCommand()
    data class Search(val query: String) : VoiceCommand()
    data class AddTodo(val text: String) : VoiceCommand()
    data object ListTodos : VoiceCommand()
    data class DoneTodo(val text: String) : VoiceCommand()
    data object ClearTodos : VoiceCommand()
    data class Translate(val text: String, val target: String) : VoiceCommand()
    data class Convert(val amount: Double, val from: String, val to: String) : VoiceCommand()
    data class WorldClock(val place: String) : VoiceCommand()
    data object News : VoiceCommand()
    data object CalendarList : VoiceCommand()
    data class CalendarAdd(val atMillis: Long, val text: String) : VoiceCommand()
    data class FindChat(val query: String) : VoiceCommand()
    data object DailyBrief : VoiceCommand()
    /** 面板模板：保存 / 套用 / 删除 / 列表 / 建字段模板。 */
    data class PanelTemplateCmd(
        val saveName: String? = null,
        val applyName: String? = null,
        val deleteName: String? = null,
        val listAll: Boolean = false,
        /** 新建纯字段模板（preset）。 */
        val newPresetName: String? = null,
        /** 新建时附带的字段（逗号/空格分隔）。 */
        val fields: List<String> = emptyList()
    ) : VoiceCommand()
    /** 改气泡样式：null 表示不改这一项。 */
    data class BubbleStyle(
        val shape: String? = null,
        val aiColor: String? = null,
        val meColor: String? = null,
        val aiColor2: String? = null,
        val meColor2: String? = null,
        val transparent: Boolean? = null,
        val fontSp: Float? = null,
        val maxWidthPercent: Int? = null,
        val padH: Int? = null,
        val padV: Int? = null,
        val borderDp: Int? = null,
        val shadow: Boolean? = null,
        val avatarDp: Int? = null,
        val gapH: Int? = null,
        val gapV: Int? = null,
        val tailSide: String? = null,
        val anim: String? = null,
        val cornerDp: Int? = null,
        val trimQuotes: Boolean? = null,
        val favoriteName: String? = null,
        val applyName: String? = null,
        val deleteName: String? = null,
        val listFavorites: Boolean = false,
        val bindRole: Boolean = false,
        val followWallpaper: Boolean = false,
        val exportJson: Boolean = false,
        val importJson: Boolean = false,
        val preset: String? = null,
        val random: Boolean = false,
        val reset: Boolean = false,
        val query: Boolean = false
    ) : VoiceCommand()
}

object VoiceCommandParser {
    fun parse(raw: String): VoiceCommand? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        parseBubbleStyle(text)?.let { return it }
        parsePanelTemplate(text)?.let { return it }
        parseNews(text)?.let { return it }
        parseBrief(text)?.let { return it }
        parseWorldClock(text)?.let { return it }
        parseTranslate(text)?.let { return it }
        parseConvert(text)?.let { return it }
        parseFindChat(text)?.let { return it }
        parseTodo(text)?.let { return it }
        parseCalendar(text)?.let { return it }
        parseListOrCancel(text)?.let { return it }
        parseCountdown(text)?.let { return it }
        parseClock(text)?.let { return it }
        return null
    }

    /**
     * 「给沫沫提要求改气泡」——识别成 [VoiceCommand.BubbleStyle]。
     *
     * 支持的口令举例：
     *   - 形状：气泡方一点 / 改成胶囊 / 加个小尾巴 / 圆润一点
     *   - 颜色：气泡换成粉色 / 我的气泡改成蓝色 / 小沫的气泡弄成薄荷绿
     *   - 字号：气泡字大一点 / 字小一点 / 超大字号
     *   - 宽度：气泡宽一点 / 窄一点 / 拉满
     *   - 内边距：气泡松一点 / 紧凑一点
     *   - 透明：气泡透明一点 / 不透明
     *   - 预设：换个少女风气泡 / 微信风 / 极简风 / 暗黑风
     *   - 查询：我的气泡什么样 / 现在气泡是什么样
     *   - 重置：恢复默认气泡 / 重置气泡样式
     */
    /**
     * 面板模板口令（对应 GitHub stquickstatusbar 的配置管理）。
     *
     * 支持：
     *   - 保存：存面板 龙鳞冒险 / 保存面板模板 龙鳞冒险
     *   - 套用：套用面板 龙鳞冒险 / 应用面板 龙鳞冒险
     *   - 删除：删除面板模板 龙鳞冒险
     *   - 列表：面板模板列表 / 有哪些面板模板
     *   - 建字段模板：新建字段模板 修仙，字段：气血 灵力 境界
     */
    private fun parsePanelTemplate(text: String): VoiceCommand.PanelTemplateCmd? {
        if (!text.contains("面板") && !text.contains("字段模板")) return null
        // 「面板」二字命中就继续；下面各分支再精确把关，避免误伤普通闲聊
        // 面板相关的游戏闲聊别误判
        if (hit(text, "面板是什么", "什么是面板", "面板怎么用")) return null

        // 列表
        if (hit(text, "模板列表", "有哪些面板模板", "面板模板有哪些", "面板模板列表",
                "我存了哪些面板", "我的面板模板", "列出面板模板", "看看面板模板")) {
            return VoiceCommand.PanelTemplateCmd(listAll = true)
        }
        // 删除
        if (hit(text, "删除面板模板", "删掉面板模板", "删除面板", "移除面板模板")) {
            PanelStore.nameFromText(text)?.let {
                return VoiceCommand.PanelTemplateCmd(deleteName = it)
            }
        }
        // 新建字段模板：新建字段模板 修仙，字段：气血 灵力 境界
        if (hit(text, "新建字段模板", "新建面板模板", "自定义字段模板")) {
            val name = PanelStore.nameFromText(text) ?: return null
            var fields = emptyList<String>()
            val fIdx = maxOf(text.indexOf("字段："), text.indexOf("字段:"))
            if (fIdx >= 0) {
                val raw = text.substring(fIdx + 3)
                fields = raw.split(Regex("[,\\s，、；;]+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length <= 12 }
            }
            return VoiceCommand.PanelTemplateCmd(newPresetName = name, fields = fields)
        }
        // 套用
        if (hit(text, "套用面板", "应用面板", "套用面板模板", "应用面板模板", "载入面板")) {
            PanelStore.nameFromText(text)?.let {
                return VoiceCommand.PanelTemplateCmd(applyName = it)
            }
        }
        // 保存（放最后，避免「保存」被套用抢走）
        if (hit(text, "保存面板", "存面板", "收藏面板", "记住面板", "保存面板模板") ||
            Regex("存(?:个|一下|下)?面板").containsMatchIn(text)) {
            PanelStore.nameFromText(text)?.let {
                return VoiceCommand.PanelTemplateCmd(saveName = it)
            }
        }
        return null
    }

    private fun parseBubbleStyle(text: String): VoiceCommand.BubbleStyle? {
        // 必须提到「气泡/泡泡」，或命中壁纸取色口令（跟随壁纸等不含「气泡」二字）
        val bubbleWord = text.contains("气泡") || text.contains("泡泡")
        val wallpaperWord = hit(text, "跟随壁纸", "壁纸取色", "跟壁纸颜色", "配壁纸", "和壁纸搭", "按壁纸配")
        if (!bubbleWord && !wallpaperWord) return null
        // 别把「气泡」相关的普通闲聊（问句）当命令
        if (hit(text, "气泡是什么", "什么是气泡")) return null

        // 查询当前样式
        if (hit(text, "气泡什么样", "气泡是什么样", "现在气泡", "当前气泡", "气泡设置是什么",
                "看看气泡", "气泡长啥样", "气泡样式是什么")) {
            return VoiceCommand.BubbleStyle(query = true)
        }

        // 重置
        if (hit(text, "恢复默认气泡", "重置气泡", "默认气泡", "气泡恢复", "还原气泡", "气泡变回来",
                "气泡改回来")) {
            return VoiceCommand.BubbleStyle(reset = true)
        }

        // 随机换肤
        if (BubbleStyleStore.isRandomRequest(text)) {
            return VoiceCommand.BubbleStyle(random = true)
        }

        // 收藏夹：收藏 / 套用 / 删除 / 列表
        if (hit(text, "收藏了哪些气泡", "气泡收藏列表", "我收藏的气泡", "有哪些气泡样式",
                "气泡收藏有哪些", "收藏的气泡有哪些")) {
            return VoiceCommand.BubbleStyle(listFavorites = true)
        }
        // 删除收藏：优先判断，避免「删除XX」被误判成收藏
        if (hit(text, "删除", "删掉", "去掉", "移除")) {
            BubbleStyleStore.deleteNameFromText(text)?.let { name ->
                return VoiceCommand.BubbleStyle(deleteName = name)
            }
        }
        if (hit(text, "收藏")) {
            BubbleStyleStore.favoriteNameFromText(text)?.let { name ->
                return VoiceCommand.BubbleStyle(favoriteName = name)
            }
        }
        if (hit(text, "套用", "用回", "切换", "用到")) {
            BubbleStyleStore.applyNameFromText(text)?.let { name ->
                return VoiceCommand.BubbleStyle(applyName = name)
            }
        }

        // 按角色绑定：给这个角色单独设样式 / 恢复跟随全局
        if (hit(text, "恢复跟随全局", "取消角色气泡", "角色气泡恢复")) {
            return VoiceCommand.BubbleStyle(bindRole = false, reset = true)
        }
        if (hit(text, "给这个角色", "这个角色单独", "角色专属气泡", "绑定到角色")) {
            return VoiceCommand.BubbleStyle(bindRole = true)
        }

        // 跟随壁纸取色
        if (hit(text, "跟随壁纸", "壁纸取色", "跟壁纸颜色", "配壁纸", "和壁纸搭", "按壁纸配")) {
            return VoiceCommand.BubbleStyle(followWallpaper = true)
        }

        // 导入导出
        if (hit(text, "导出气泡", "气泡导出", "备份气泡样式")) {
            return VoiceCommand.BubbleStyle(exportJson = true)
        }
        if (hit(text, "导入气泡", "气泡导入", "恢复气泡样式文件")) {
            return VoiceCommand.BubbleStyle(importJson = true)
        }

        // 预设主题（优先级高：命中就整包套用，不再逐项解析）
        BubbleStyleStore.presetFromText(text)?.let { preset ->
            return VoiceCommand.BubbleStyle(preset = preset)
        }

        val shape = BubbleStyleStore.shapeFromText(text)
        val color = BubbleStyleStore.colorFromText(text)
        val gradient = BubbleStyleStore.gradientFromText(text)
        val fontSp = BubbleStyleStore.sizeFromText(text)
        val width = BubbleStyleStore.widthFromText(text)
        val pad = BubbleStyleStore.paddingFromText(text)
        val border = BubbleStyleStore.borderFromText(text)
        val shadow = BubbleStyleStore.shadowFromText(text)
        val avatar = BubbleStyleStore.avatarFromText(text)
        val gap = BubbleStyleStore.gapFromText(text)
        val tailSide = BubbleStyleStore.tailFromText(text)
        val anim = BubbleStyleStore.animFromText(text)
        val cornerDp = BubbleStyleStore.cornerFromText(text)
        val trimQuotes = BubbleStyleStore.trimQuotesFromText(text)
        val transparent = when {
            hit(text, "不透明", "别透明", "恢复不透明") -> false
            hit(text, "透明一点", "半透明", "气泡透明", "透一点", "透明") -> true
            else -> null
        }

        if (shape == null && color == null && gradient == null && fontSp == null && width == null &&
            pad == null && transparent == null && border == null && shadow == null &&
            avatar == null && gap == null && tailSide == null && anim == null &&
            cornerDp == null && trimQuotes == null) return null

        // 区分「我的气泡」和「小沫的气泡」
        val mine = hit(text, "我的气泡", "我这边", "自己气泡", "右侧气泡", "我发的")
        val hers = hit(text, "小沫的气泡", "你的气泡", "左侧气泡", "沫沫的气泡", "你发的")

        var aiColor: String? = null
        var meColor: String? = null
        var aiColor2: String? = null
        var meColor2: String? = null
        if (gradient != null) {
            when {
                mine && !hers -> { meColor = gradient.first; meColor2 = gradient.second }
                hers && !mine -> { aiColor = gradient.first; aiColor2 = gradient.second }
                else -> {
                    aiColor = gradient.first; aiColor2 = gradient.second
                    meColor = gradient.first; meColor2 = gradient.second
                }
            }
        } else if (color != null) {
            when {
                mine && !hers -> meColor = color
                hers && !mine -> aiColor = color
                else -> { aiColor = color; meColor = color }
            }
        }
        return VoiceCommand.BubbleStyle(
            shape = shape,
            aiColor = aiColor,
            meColor = meColor,
            aiColor2 = aiColor2,
            meColor2 = meColor2,
            transparent = transparent,
            fontSp = fontSp,
            maxWidthPercent = width,
            padH = pad?.h,
            padV = pad?.v,
            borderDp = border,
            shadow = shadow,
            avatarDp = avatar,
            gapH = gap?.h,
            gapV = gap?.v,
            tailSide = tailSide,
            anim = anim,
            cornerDp = cornerDp,
            trimQuotes = trimQuotes,
            favoriteName = BubbleStyleStore.favoriteNameFromText(text),
            applyName = BubbleStyleStore.applyNameFromText(text),
            deleteName = BubbleStyleStore.deleteNameFromText(text)
        )
    }

    private fun parseNews(text: String): VoiceCommand? {
        if (hit(text, "今天有什么新闻", "今日新闻", "新闻快讯", "最近新闻", "热点新闻", "播报新闻")) {
            return VoiceCommand.News
        }
        return null
    }

    private fun parseBrief(text: String): VoiceCommand? {
        if (hit(text, "今日摘要", "今天摘要", "今日概览", "今天安排", "早上好小沫")) {
            return VoiceCommand.DailyBrief
        }
        return null
    }

    private fun parseWorldClock(text: String): VoiceCommand.WorldClock? {
        if (!hit(text, "几点", "现在几点", "当地时间", "现在是几点")) return null
        if (hit(text, "提醒", "闹钟", "倒计时")) return null
        val place = listOf(
            "纽约", "洛杉矶", "旧金山", "伦敦", "巴黎", "东京", "首尔", "悉尼",
            "新加坡", "香港", "台北", "莫斯科", "迪拜", "北京", "上海"
        ).firstOrNull { text.contains(it) }
        if (place != null) return VoiceCommand.WorldClock(place)
        if (text.length <= 10 && hit(text, "现在几点", "几点了")) return VoiceCommand.WorldClock("北京")
        return null
    }

    private fun parseTranslate(text: String): VoiceCommand.Translate? {
        val m1 = Regex("(?:把|将)?(.+?)翻译成(.+)").find(text)
        if (m1 != null) {
            val src = m1.groupValues[1].trim()
            val dst = m1.groupValues[2].trim()
            if (src.isNotEmpty() && dst.isNotEmpty()) return VoiceCommand.Translate(src.take(5000), dst)
        }
        val prefixes = listOf("翻译成英语", "翻译成英文", "翻译成日语", "翻译成韩语", "翻译一下", "翻译")
        for (p in prefixes) {
            if (text.startsWith(p)) {
                val rest = text.removePrefix(p).trim().trimStart('：', ':', '，', ',', ' ')
                if (rest.isNotEmpty()) {
                    val target = when {
                        p.contains("英") -> "英语"
                        p.contains("日") -> "日语"
                        p.contains("韩") -> "韩语"
                        else -> "英语"
                    }
                    return VoiceCommand.Translate(rest.take(5000), target)
                }
            }
        }
        return null
    }

    private fun parseConvert(text: String): VoiceCommand.Convert? {
        if (!hit(text, "美元", "欧元", "日元", "英镑", "港币", "台币", "韩元", "人民币", "美金")) return null
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(美元|美金|欧元|日元|英镑|港币|港元|台币|韩元|人民币|块钱|块)").find(text)
            ?: return null
        val amount = m.groupValues[1].toDoubleOrNull() ?: return null
        val from = m.groupValues[2]
        val to = when {
            hit(text, "人民币") && from != "人民币" && from != "块" && from != "块钱" -> "人民币"
            hit(text, "美元", "美金") && !from.contains("美") -> "美元"
            else -> if (from.contains("人民") || from == "块" || from == "块钱") "美元" else "人民币"
        }
        return VoiceCommand.Convert(amount, from, to)
    }

    private fun parseFindChat(text: String): VoiceCommand.FindChat? {
        val prefixes = listOf("对话里找", "聊天记录找", "找聊天", "搜索对话", "在对话里搜")
        for (p in prefixes) {
            if (text.contains(p)) {
                val q = text.replace(p, " ").replace(Regex("[：:,，]"), " ").trim()
                if (q.isNotEmpty()) return VoiceCommand.FindChat(q.take(1000))
            }
        }
        return null
    }

    private fun parseTodo(text: String): VoiceCommand? {
        if (hit(text, "清空待办", "待办清空", "清空备忘")) return VoiceCommand.ClearTodos
        if (hit(text, "我的待办", "待办有哪些", "有哪些待办", "待办列表", "我待办有哪些", "备忘录")) {
            return VoiceCommand.ListTodos
        }
        val donePrefixes = listOf("完成待办", "待办完成", "勾掉待办", "待办勾掉")
        for (p in donePrefixes) {
            if (text.startsWith(p)) {
                val q = text.removePrefix(p).trim().trimStart('：', ':', ' ')
                if (q.isNotEmpty()) return VoiceCommand.DoneTodo(q.take(1000))
            }
        }
        val addPrefixes = listOf("记住", "记一下", "记着", "待办", "备忘")
        for (p in addPrefixes) {
            if (text.startsWith(p)) {
                var q = text.removePrefix(p).trim().trimStart('：', ':', '，', ',', ' ')
                q = q.replace(Regex("^(帮我|给我|一下)"), "").trim()
                if (q.length in 1..80 && !q.contains("天气") && !looksLikeRemind(q) && !q.startsWith("密码")) {
                    return VoiceCommand.AddTodo(q)
                }
            }
        }
        return null
    }

    private fun parseCalendar(text: String): VoiceCommand? {
        if (hit(text, "我的日程", "有哪些日程", "日程列表", "日历安排", "今天有什么安排")) {
            return VoiceCommand.CalendarList
        }
        if (hit(text, "加到日历", "写入日历", "添加到日历", "记到日历")) {
            val clock = parseClock(
                text.replace("加到日历", "提醒我")
                    .replace("写入日历", "提醒我")
                    .replace("添加到日历", "提醒我")
                    .replace("记到日历", "提醒我")
            )
            if (clock != null) return VoiceCommand.CalendarAdd(clock.atMillis, clock.text)
        }
        return null
    }

    private fun parseSearch(text: String): VoiceCommand.Search? {
        val prefixes = listOf("搜一下", "搜一搜", "搜索", "查一下", "查一查", "查查", "百度一下")
        for (p in prefixes) {
            if (text.startsWith(p)) {
                val q = text.removePrefix(p).trim().trimStart('：', ':', '，', ',', ' ')
                if (q.isNotEmpty() && !q.contains("天气") && !q.contains("待办") && !q.contains("日程")) {
                    return VoiceCommand.Search(q.take(1000))
                }
            }
        }
        if (text.startsWith("什么是") || text.startsWith("谁是")) {
            val q = text.removePrefix("什么是").removePrefix("谁是").trim()
            if (q.length in 1..40) return VoiceCommand.Search(q)
        }
        return null
    }

    private fun parseListOrCancel(text: String): VoiceCommand? {
        if (hit(text, "取消全部提醒", "取消所有提醒", "清空提醒", "提醒全取消")) {
            return VoiceCommand.CancelReminders
        }
        if (hit(text, "取消提醒", "删掉提醒", "关掉提醒") && !text.contains("分钟") && !text.contains("小时")) {
            return VoiceCommand.CancelReminders
        }
        if (hit(text, "我的提醒", "有哪些提醒", "提醒列表", "还有什么提醒", "查看提醒")) {
            return VoiceCommand.ListReminders
        }
        return null
    }

    private fun parseCountdown(text: String): VoiceCommand.RemindIn? {
        if (!looksLikeRemind(text)) return null
        val hourMin = Regex("(\\d{1,2})\\s*小时(?:零|又)?(\\d{1,2})\\s*分钟").find(text)
        if (hourMin != null) {
            val h = hourMin.groupValues[1].toInt()
            val m = hourMin.groupValues[2].toInt()
            val ms = (h * 60 + m) * 60_000L
            if (ms in 30_000L..24 * 3600_000L) {
                return VoiceCommand.RemindIn(System.currentTimeMillis() + ms, topic(text))
            }
        }
        val hour = Regex("(\\d{1,2})\\s*小时").find(text)
        if (hour != null && !text.contains("点")) {
            val h = hour.groupValues[1].toInt()
            val ms = h * 3600_000L
            if (ms in 30_000L..24 * 3600_000L) {
                return VoiceCommand.RemindIn(System.currentTimeMillis() + ms, topic(text))
            }
        }
        val min = Regex("(\\d{1,3})\\s*分钟").find(text)
        if (min != null) {
            val m = min.groupValues[1].toInt()
            val ms = m * 60_000L
            if (ms in 30_000L..12 * 3600_000L) {
                return VoiceCommand.RemindIn(System.currentTimeMillis() + ms, topic(text))
            }
        }
        val sec = Regex("(\\d{1,3})\\s*秒").find(text)
        if (sec != null && hit(text, "倒计时", "提醒", "叫我")) {
            val s = sec.groupValues[1].toInt().coerceAtLeast(10)
            return VoiceCommand.RemindIn(System.currentTimeMillis() + s * 1000L, topic(text))
        }
        return null
    }

    private fun parseClock(text: String): VoiceCommand.RemindAt? {
        if (!looksLikeRemind(text) && !hit(text, "每天", "重复")) return null
        val m = Regex("(?:早上|上午|中午|下午|晚上|傍晚)?\\s*(\\d{1,2})\\s*(?:点|:|：)\\s*(\\d{1,2})?").find(text)
            ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        if ((text.contains("下午") || text.contains("晚上") || text.contains("傍晚")) && hour in 1..11) hour += 12
        if (text.contains("中午") && hour in 1..2) hour = 12
        val cal = Calendar.getInstance()
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        if (cal.timeInMillis <= System.currentTimeMillis() + 20_000L) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        if (text.contains("明天")) {
            val today = Calendar.getInstance()
            if (cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
        val repeat = hit(text, "每天", "每日", "重复闹钟", "天天")
        val advance = when {
            text.contains("提前10分钟") || text.contains("提前十分钟") -> 10
            text.contains("提前5分钟") || text.contains("提前五分钟") -> 5
            else -> 0
        }
        return VoiceCommand.RemindAt(cal.timeInMillis, topic(text), repeat, advance)
    }

    private fun looksLikeRemind(text: String): Boolean {
        return hit(
            text,
            "提醒我", "叫我", "喊我", "闹钟", "倒计时",
            "分钟后", "小时后", "秒后", "点提醒", "点叫我"
        )
    }

    private fun topic(text: String): String {
        var t = text
        listOf(
            "提醒我", "叫我", "喊我", "设个闹钟", "定个闹钟", "设闹钟", "倒计时",
            "一下", "一下下", "每天", "每日", "重复", "提前5分钟", "提前五分钟",
            "提前10分钟", "提前十分钟", "加到日历", "写入日历", "添加到日历"
        ).forEach { t = t.replace(it, " ") }
        t = t.replace(Regex("\\d+\\s*(小时|分钟|秒|点|：|:)\\s*\\d*"), " ")
        t = t.replace(Regex("(早上|上午|中午|下午|晚上|傍晚|明天|今天|后天)"), " ")
        t = t.replace(Regex("[，。！？,\\.!\\?]+"), " ").trim()
        t = t.replace(Regex("\\s+"), " ").trim()
        return t.take(2000).ifBlank { "到时间啦" }
    }

    private fun hit(text: String, vararg keys: String): Boolean =
        keys.any { text.contains(it) }
}