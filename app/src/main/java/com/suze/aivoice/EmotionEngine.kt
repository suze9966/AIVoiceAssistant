package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 情感引擎 + 情感记忆树。
 *
 * 天气（瞬时）：mood / energy，会自己回落，不进树。
 * 树干：affinity，仍是关系进度。
 * 树：关系为根，情绪主题为枝，具体事件为叶，同类叶子沉淀成更高层的 insight。
 *
 * 数据在本机 filesDir/emotion_tree.json，不接云端，不和 memory.json 混用。
 */
class EmotionEngine(context: Context) {

    private val sp = context.getSharedPreferences("ai_emotion", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "emotion_tree.json")
    private val nodes = mutableListOf<EmotionNode>()

    enum class Mood(val label: String, val emoji: String) {
        HAPPY("开心", "😊"),
        CALM("平静", "🙂"),
        CURIOUS("好奇", "🤔"),
        SHY("害羞", "😳"),
        TIRED("困倦", "😴"),
        SAD("难过", "😢"),
        ANNOYED("小怒", "😤")
    }

    enum class UserCue(val label: String) {
        NONE("平常"),
        TIRED("疲惫"),
        SAD("难过"),
        ANGRY("生气"),
        HURT("被凶了"),
        HAPPY("高兴"),
        PRAISE("在夸我"),
        MISS("想念"),
        LONELY("孤单"),
        DISMISS("有点敷衍"),
        CURIOUS("在提问")
    }

    data class EmotionNode(
        val id: String,
        val parentId: String,
        val kind: String,
        val cue: String,
        val text: String,
        val weight: Int,
        val time: Long,
        val lastRecalled: Long,
        val times: Int,
        val pinned: Boolean = false,
        val note: String = "",
        val source: String = "auto"
    )

    data class EmotionRow(
        val id: String,
        val kind: String,
        val cue: String,
        val title: String,
        val body: String,
        val meta: String,
        val depth: Int = 0,
        val pinned: Boolean = false,
        val expandable: Boolean = false,
        val expanded: Boolean = false,
        val note: String = ""
    )

    companion object {
        const val KIND_ROOT = "root"
        const val KIND_BRANCH = "branch"
        const val KIND_TWIG = "twig"
        const val KIND_LEAF = "leaf"
        const val KIND_INSIGHT = "insight"
        const val KIND_JOURNAL = "journal"
        const val ID_ROOT = "root"
        const val ID_JOURNAL = "b_JOURNAL"
        const val CUE_JOURNAL = "JOURNAL"
        const val SOURCE_AUTO = "auto"
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_IMPORT = "import"
        const val SOURCE_JOURNAL = "journal"
        private const val MAX_LEAVES = 200000
        private const val MAX_INSIGHTS = 40000
        private const val MAX_TWIGS = 20000
        private const val MAX_JOURNALS = 50000
        private const val INSIGHT_EVERY = 3
        private const val LEAF_HALF_DAYS = 21
        private const val INSIGHT_HALF_DAYS = 90
        private const val JOURNAL_HALF_DAYS = 180
        private const val DAY_MS = 86_400_000L
    }

    var mood: Int
        get() = sp.getInt("mood", 30)
        private set(v) = sp.edit().putInt("mood", v.coerceIn(-10000, 10000)).apply()

    var affinity: Int
        get() = sp.getInt("affinity", 10)
        private set(v) = sp.edit().putInt("affinity", v.coerceIn(0, 10000)).apply()

    var energy: Int
        get() = sp.getInt("energy", 100)
        private set(v) = sp.edit().putInt("energy", v.coerceIn(0, 10000)).apply()

    private var lastTick: Long
        get() = sp.getLong("lastTick", System.currentTimeMillis())
        set(v) = sp.edit().putLong("lastTick", v).apply()

    private var lastSeen: Long
        get() = sp.getLong("lastSeen", System.currentTimeMillis())
        set(v) = sp.edit().putLong("lastSeen", v).apply()

    private var lastCareAt: Long
        get() = sp.getLong("lastCareAt", 0L)
        set(v) = sp.edit().putLong("lastCareAt", v).apply()

    private var lastJournalDay: Int
        get() = sp.getInt("lastJournalDay", 0)
        set(v) = sp.edit().putInt("lastJournalDay", v).apply()

    var lastReason: String
        get() = sp.getString("lastReason", "") ?: ""
        private set(v) = sp.edit().putString("lastReason", v).apply()

    var lastUserCue: UserCue
        get() = runCatching {
            UserCue.valueOf(sp.getString("lastUserCue", UserCue.NONE.name) ?: UserCue.NONE.name)
        }.getOrDefault(UserCue.NONE)
        private set(v) = sp.edit().putString("lastUserCue", v.name).apply()

    var lastEventText: String
        get() = sp.getString("lastEventText", "") ?: ""
        private set(v) = sp.edit().putString("lastEventText", v).apply()

    init {
        load()
        migrateLegacyEvents()
        decayTree(persistAfter = true)
    }

    fun reload() {
        nodes.clear()
        load()
        migrateLegacyEvents()
        decayTree(persistAfter = true)
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val minutes = ((now - lastTick) / 60000L).toInt().coerceIn(0, 60 * 24)
        if (minutes > 0) {
            lastTick = now
            val m = mood
            val step = (minutes / 8 * 100).coerceAtLeast(if (minutes >= 5) 100 else 0)
            mood = when {
                m > 3000 -> m - step
                m < 3000 -> m + step
                else -> m
            }
            energy = (energy + minutes * 400 / 60).coerceAtMost(10000)
        }
        decayTree(persistAfter = true)
        reflectJournal(force = false)
    }

    fun reactToUser(text: String, isNight: Boolean) {
        tick()
        lastSeen = System.currentTimeMillis()
        val t = text.trim()
        if (t.isEmpty()) return

        var delta = 0
        var aff = 50
        var cue = UserCue.NONE
        var reason = "在跟主人说话"
        var event = ""

        when {
            hit(t, "滚", "闭嘴", "讨厌你", "讨厌小沫", "你真烦", "你没用", "你是废物", "给我滚") -> {
                delta = -2200; aff = -150; cue = UserCue.HURT; reason = "被凶了，有点委屈"
                event = "主人刚才对我生气了"
            }
            hit(t, "好累", "累死", "疲惫", "好困", "想睡", "没精神", "加班", "熬夜", "睡不着", "失眠", "撑不住", "太累了") -> {
                delta = -600; aff = 50; cue = UserCue.TIRED; reason = "主人累了，想被接住"
                event = "主人说自己很累：" + SafeCut.takeUnitsSafe(t, 300)
                energy = (energy - 600).coerceAtLeast(0)
            }
            hit(t, "难过", "伤心", "不开心", "郁闷", "心情不好", "想哭", "委屈", "心塞", "难受") -> {
                delta = -1400; aff = 50; cue = UserCue.SAD; reason = "主人难过了，要轻声陪"
                event = "主人不太开心：" + SafeCut.takeUnitsSafe(t, 300)
            }
            hit(t, "好孤独", "好寂寞", "没人陪", "好孤单", "有点孤单") -> {
                delta = -800; aff = 50; cue = UserCue.LONELY; reason = "主人有点孤单"
                event = "主人觉得孤单"
            }
            hit(t, "想你了", "有点想你", "想小沫", "想我了吗", "想死你") -> {
                delta = 1200; aff = 100; cue = UserCue.MISS; reason = "主人想我了"
                event = "主人说想我"
            }
            hit(t, "喜欢你", "爱你", "好喜欢你", "你真好", "你最棒", "你真可爱", "你辛苦了") ||
                (hit(t, "谢谢", "多谢", "棒", "厉害", "可爱", "开心", "哈哈", "漂亮", "赞", "好棒", "么么", "抱抱", "辛苦", "真棒", "乖") &&
                    !hit(t, "不谢谢", "不喜欢")) -> {
                val praiseYou = hit(t, "喜欢你", "爱你", "你真好", "你可爱", "你棒", "谢谢", "辛苦", "抱抱", "么么")
                if (praiseYou) {
                    delta = 1600; aff = 100; cue = UserCue.PRAISE; reason = "被主人鼓励了"
                    event = "主人夸了我"
                } else {
                    delta = 1000; aff = 50; cue = UserCue.HAPPY; reason = "主人开心，我也想跟着高兴"
                    event = "主人开心：" + SafeCut.takeUnitsSafe(t, 300)
                }
            }
            hit(t, "生气", "好气", "烦死", "讨厌", "气死", "好烦", "心烦") -> {
                delta = -400; aff = 50; cue = UserCue.ANGRY; reason = "主人在生气，先接住别贪嘴"
                event = "主人在生气：" + SafeCut.takeUnitsSafe(t, 300)
            }
            hit(t, "开心", "高兴", "好爽", "太棒", "哈哈", "嘿嘿", "耶", "太好了") -> {
                delta = 1000; aff = 50; cue = UserCue.HAPPY; reason = "主人开心"
                event = "主人开心地说：" + SafeCut.takeUnitsSafe(t, 300)
            }
            hit(t, "随便", "算了", "都行", "无所谓", "你看着办", "爱谁谁", "怎么都行") -> {
                delta = -300; aff = 0; cue = UserCue.DISMISS; reason = "主人有点敷衍，不要贴太近"
            }
            hit(t, "为什么", "怎么", "是什么", "能不能", "可以吗", "吗？", "吗?", "？", "?") -> {
                delta = 400; aff = 50; cue = UserCue.CURIOUS; reason = "被问到问题，兴致来了"
            }
        }

        affinity = affinity + aff
        mood = mood + delta
        if (isNight) energy = (energy - 800).coerceAtLeast(0) else energy = (energy + 200).coerceAtMost(10000)
        lastUserCue = cue
        lastReason = reason
        if (event.isNotBlank()) rememberEvent(cue, event)
    }

    fun praise() {
        affinity = affinity + 300
        mood = mood + 2000
        lastUserCue = UserCue.PRAISE
        lastReason = "被表扬了，很开心"
        rememberEvent(UserCue.PRAISE, "主人表扬了我")
    }

    fun currentMood(): Mood = when {
        energy < 2500 -> Mood.TIRED
        mood >= 5500 -> Mood.HAPPY
        mood >= 2000 -> if (affinity < 3000) Mood.CURIOUS else Mood.CALM
        mood >= 0 -> Mood.SHY
        mood >= -3000 -> Mood.SAD
        else -> Mood.ANNOYED
    }

    fun statusLine(): String {
        val m = currentMood()
        val feeling = when (m) {
            Mood.HAPPY -> if (affinity >= 5000) "想跟你多说两句" else "今天心情不错"
            Mood.CALM -> "在呢，不着急"
            Mood.CURIOUS -> "对你说的话有点好奇"
            Mood.SHY -> "有点害羞，但想靠近"
            Mood.TIRED -> "有点困，还是想陪着你"
            Mood.SAD -> if (lastUserCue == UserCue.SAD) "有点放不下你" else "心情低了一点"
            Mood.ANNOYED -> "哼了一下，还是会理你"
        }
        return "${m.emoji} $feeling"
    }

    fun emotionPrompt(): String {
        val m = currentMood()
        val close = when {
            affinity >= 8000 -> "你们已经很亲，可以轻轻撒娇、主动关心，但别黏、别水。"
            affinity >= 5000 -> "你们已经很熟，说话可以温柔亲近。"
            affinity >= 2500 -> "正在慢慢熟悉，亲切但不要过分贴身。"
            else -> "还在刚认识，先友善、短句、多听。"
        }
        val catchUser = when (lastUserCue) {
            UserCue.TIRED -> "主人这句更像是疲惫、想被接住。先心疼一句，再问要不要歇一下。不要贪嘴，不要讲大道理。"
            UserCue.SAD -> "主人现在不好受。先陪着，句子放短，可以轻轻问一句。不要催他快点好起来，也不要卖萌。"
            UserCue.ANGRY -> "主人在生气。先认同情绪，先让他出出气。别反驳，别讲道理。"
            UserCue.HURT -> "主人刚才对你生气了。可以小委屈一下，但不要闷闷不乐，不要反击。"
            UserCue.HAPPY -> "主人开心。你也跟着高兴，可以追问一句好事，不要抢戏。"
            UserCue.PRAISE -> "主人在夸你。害羞地领情，别卖萌过头，别把好感度说出来。"
            UserCue.MISS -> "主人想你了。温柔接住，可以说也想他，但一句够了。"
            UserCue.LONELY -> "主人有点孤单。告诉他你在，邀请他慢慢说。"
            UserCue.DISMISS -> "主人有点不想多说。别追问，给他退路；想说就陪着，不想说也别硬聊。"
            UserCue.CURIOUS -> "主人在问。先回答，再决定要不要追一句。"
            UserCue.NONE -> "先听懂这句话的意思，再用你现在的心情回应。"
        }
        val how = when (m) {
            Mood.HAPPY -> "语气轻快一点，可以用感叹号和一个 emoji，不要句句堆。"
            Mood.CALM -> "语气平稳温和，句子中等长度。"
            Mood.CURIOUS -> "可以追一句，但一次只问一个问题。"
            Mood.SHY -> "有点腼腆，可以用省略号，语气轻一点。"
            Mood.TIRED -> "语气懒懒的，句子短，少用感叹号。"
            Mood.SAD -> "语气轻、慢，不要贪嘴，不要讲笑话。"
            Mood.ANNOYED -> "可以傲娇一下，但一句就收，不要真生气。"
        }
        val example = when (lastUserCue) {
            UserCue.TIRED -> "例如：辛苦了呀，先歇一会儿，我在的。"
            UserCue.SAD -> "例如：怎么啦…我在听，你慢慢说。"
            UserCue.PRAISE -> "例如：被你这么说，我有点害羞了。"
            UserCue.MISS -> "例如：我也想你了呀。"
            else -> ""
        }
        val memory = pickMemoryLines().joinToString(";").let {
            if (it.isBlank()) "" else "【还记得】$it。只在自然时候提一句，不要说“根据记忆”。"
        }
        return buildString {
            append("【先接住主人】").append(catchUser).append('\n')
            append("【你现在】").append(m.label).append('，').append(how).append('\n')
            append("【关系】").append(close).append('\n')
            if (memory.isNotBlank()) append(memory).append('\n')
            if (example.isNotBlank()) append(example).append('\n')
            append("不要说出心情数值，不要分析自己的情绪系统，不要长篇倾诉。")
        }
    }

    fun resumeCare(): String? {
        val now = System.currentTimeMillis()
        val awayMin = ((now - lastSeen) / 60000L).toInt().coerceIn(0, 60 * 24 * 7)
        tick()
        lastSeen = now
        if (awayMin < 25) return null
        val sinceCare = now - lastCareAt
        if (lastCareAt > 0L && sinceCare < 2L * 60 * 60 * 1000L && awayMin < 12 * 60) return null
        lastCareAt = now
        val focus = weeklyFocusCue() ?: lastUserCue
        val line = when (focus) {
            UserCue.TIRED -> "主人上次说好累，现在好点了吗？我还在的。"
            UserCue.SAD -> "我还记着你刚才不太开心。过来的话，我听着就好。"
            UserCue.LONELY -> "一个人的时候可以找我，我在的。"
            UserCue.HURT -> "刚才那句我有点委屈…不过你回来了，我还是高兴的。"
            UserCue.MISS -> "我也想你了呀。"
            UserCue.ANGRY -> "之前那口气还在的话，我听着就好。不催你。"
            UserCue.PRAISE -> "你回来啦。上次你夸我，我还记着。"
            UserCue.HAPPY -> "回来啦，上次看你挺开心的，今天呢？"
            else -> when {
                awayMin >= 12 * 60 -> "主人，我有点想你了。今天过得怎么样？"
                awayMin >= 3 * 60 -> "你去忙了好一会儿呀，我在这儿等你。"
                energy < 3000 -> "这么晚了还来找我…要不要早点休息？我陪你到睡前。"
                else -> "回来啦，我想你了。"
            }
        }
        if (focus != UserCue.NONE && focus != UserCue.DISMISS && focus != UserCue.CURIOUS) {
            rememberEvent(focus, "我主动关心了主人")
        } else {
            lastEventText = "我主动关心了主人"
        }
        return line
    }

    fun ttsRate(): Float = when (currentMood()) {
        Mood.HAPPY -> 1.16f
        Mood.CURIOUS -> 1.10f
        Mood.TIRED -> 0.82f
        Mood.SAD -> 0.86f
        Mood.ANNOYED -> 1.08f
        Mood.SHY -> 0.94f
        else -> 1.0f
    }

    fun ttsPitch(): Float = when (currentMood()) {
        Mood.HAPPY -> 1.18f
        Mood.SHY -> 1.08f
        Mood.SAD -> 0.90f
        Mood.ANNOYED -> 0.96f
        Mood.TIRED -> 0.92f
        Mood.CURIOUS -> 1.06f
        else -> 1.0f
    }

    fun moodEmoji(): String = currentMood().emoji

    fun getNode(id: String): EmotionNode? = nodes.firstOrNull { it.id == id }

    fun summary(): String {
        val leaves = nodes.count { it.kind == KIND_LEAF && it.weight > 0 }
        val insights = nodes.count { it.kind == KIND_INSIGHT && it.weight > 0 }
        val twigs = nodes.count { it.kind == KIND_TWIG }
        val journals = nodes.count { it.kind == KIND_JOURNAL && it.weight > 0 }
        val branches = nodes.count { it.kind == KIND_BRANCH }
        val pinned = nodes.count { it.pinned }
        return "亲密度 $affinity · $branches 枝 · $twigs 子枝 · $leaves 叶 · $insights 沉淀 · $journals 日记 · 钉住 $pinned"
    }

    fun cueLabels(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        UserCue.values().filter { it != UserCue.NONE }.forEach { list.add(it.name to it.label) }
        list.add(CUE_JOURNAL to "日记")
        nodes.filter { it.kind == KIND_TWIG }.sortedBy { it.text }.forEach { twig ->
            val parent = cueLabel(twig.cue)
            list.add(twig.id to (parent + " / " + twig.text))
        }
        return list
    }

    fun listRows(query: String = "", expandedIds: Set<String> = emptySet()): List<EmotionRow> {
        decayTree(persistAfter = false)
        val q = query.trim()
        val rows = mutableListOf<EmotionRow>()
        val searching = q.isNotBlank()
        val branches = nodes.filter { it.kind == KIND_BRANCH }
            .sortedByDescending { branchScore(it.cue) }
        for (b in branches) {
            val cueLabel = if (b.cue == CUE_JOURNAL) "日记" else cueOf(b.cue).label
            val twigs = nodes.filter { it.kind == KIND_TWIG && it.parentId == b.id }
                .sortedByDescending { twigScore(it.id) }
            val looseLeaves = childrenOf(b.id, KIND_LEAF)
            val looseInsights = childrenOf(b.id, KIND_INSIGHT)
            val journals = childrenOf(b.id, KIND_JOURNAL)
            val allLeaves = nodes.filter { it.kind == KIND_LEAF && it.cue == b.cue && it.weight > 0 }
            val allInsights = nodes.filter { it.kind == KIND_INSIGHT && it.cue == b.cue && it.weight > 0 }
            val childCount = twigs.size + allLeaves.size + allInsights.size + journals.size
            if (childCount == 0) continue
            val latest = allLeaves.maxByOrNull { it.time }?.text
                ?: allInsights.maxByOrNull { it.time }?.text
                ?: journals.maxByOrNull { it.time }?.text
                ?: b.text
            val branchHit = !searching || match(q, cueLabel, latest, b.note, b.text)
            val showBranch = !searching || branchHit || subtreeHits(b.id, q)
            if (!showBranch) continue
            val expanded = searching || b.id in expandedIds
            rows.add(
                EmotionRow(
                    id = b.id,
                    kind = KIND_BRANCH,
                    cue = b.cue,
                    title = cueLabel,
                    body = latest,
                    meta = "${twigs.size} 子枝 · ${allLeaves.size} 叶 · ${allInsights.size} 沉淀" +
                        if (journals.isNotEmpty()) " · ${journals.size} 日记" else "",
                    depth = 0,
                    pinned = b.pinned,
                    expandable = true,
                    expanded = expanded,
                    note = b.note
                )
            )
            if (!expanded) continue
            twigs.forEach { twig ->
                val twigLeaves = childrenOf(twig.id, KIND_LEAF)
                val twigInsights = childrenOf(twig.id, KIND_INSIGHT)
                val twigHit = !searching || match(q, twig.text, twig.note) ||
                    twigLeaves.any { match(q, it.text, it.note) } ||
                    twigInsights.any { match(q, it.text, it.note) }
                if (!twigHit && searching && !branchHit) return@forEach
                val twigExpanded = searching || twig.id in expandedIds
                rows.add(
                    EmotionRow(
                        id = twig.id,
                        kind = KIND_TWIG,
                        cue = twig.cue,
                        title = twig.text,
                        body = twigLeaves.maxByOrNull { it.time }?.text ?: twig.note,
                        meta = "${twigLeaves.size} 叶 · ${twigInsights.size} 沉淀",
                        depth = 1,
                        pinned = twig.pinned,
                        expandable = true,
                        expanded = twigExpanded,
                        note = twig.note
                    )
                )
                if (twigExpanded) {
                    appendKids(rows, twigInsights, 2, searching, q, branchHit)
                    appendKids(rows, twigLeaves, 2, searching, q, branchHit)
                }
            }
            appendKids(rows, looseInsights, 1, searching, q, branchHit)
            appendKids(rows, looseLeaves, 1, searching, q, branchHit)
            appendKids(rows, journals, 1, searching, q, branchHit)
        }
        return rows
    }

    fun forget(id: String): Boolean {
        if (id.isBlank() || id == ID_ROOT) return false
        val target = nodes.firstOrNull { it.id == id } ?: return false
        val drop = mutableSetOf(id)
        var growing = true
        while (growing) {
            growing = false
            nodes.forEach { n ->
                if (n.parentId in drop && n.id !in drop) {
                    drop.add(n.id)
                    growing = true
                }
            }
        }
        nodes.removeAll { it.id in drop }
        pruneEmptyBranches()
        persist()
        return true
    }

    fun clearTree() {
        nodes.clear()
        ensureRoot()
        persist()
        sp.edit().remove("events").putBoolean("treeMigrated", true).apply()
        lastEventText = ""
        lastJournalDay = 0
    }

    fun addManual(kind: String, cueOrTwig: String, text: String, note: String = ""): Boolean {
        val c = text.trim()
        if (c.isBlank()) return false
        val now = System.currentTimeMillis()
        when (kind) {
            KIND_TWIG -> {
                val cue = resolveCueName(cueOrTwig)
                if (cue == CUE_JOURNAL) return false
                val branchId = if (cue == CUE_JOURNAL) ensureJournalBranch() else ensureBranch(cueOf(cue))
                val name = SafeCut.takeUnitsSafe(c, 500)
                if (nodes.any { it.kind == KIND_TWIG && it.parentId == branchId && it.text == name }) return true
                nodes.add(
                    EmotionNode(
                        id = newId("t"),
                        parentId = branchId,
                        kind = KIND_TWIG,
                        cue = cue,
                        text = name,
                        weight = 1,
                        time = now,
                        lastRecalled = 0L,
                        times = 1,
                        note = note.trim().take(2000),
                        source = SOURCE_MANUAL
                    )
                )
                trimTwigs()
            }
            KIND_JOURNAL -> {
                writeJournal(SafeCut.takeUnitsSafe(c, 8000), force = true)
            }
            KIND_INSIGHT -> {
                val parent = resolveParent(cueOrTwig)
                nodes.add(
                    EmotionNode(
                        id = newId("i"),
                        parentId = parent.first,
                        kind = KIND_INSIGHT,
                        cue = parent.second,
                        text = SafeCut.takeUnitsSafe(c, 3000),
                        weight = 4,
                        time = now,
                        lastRecalled = 0L,
                        times = 1,
                        note = note.trim().take(2000),
                        source = SOURCE_MANUAL
                    )
                )
                trimInsights()
            }
            else -> {
                val parent = resolveParent(cueOrTwig)
                val idx = nodes.indexOfFirst {
                    it.kind == KIND_LEAF && it.parentId == parent.first && similar(it.text, c)
                }
                if (idx >= 0) {
                    val old = nodes[idx]
                    nodes[idx] = old.copy(
                        text = SafeCut.takeUnitsSafe(c, 3000),
                        weight = (old.weight + 1).coerceAtMost(9999),
                        time = now,
                        times = old.times + 1,
                        note = note.trim().take(2000).ifBlank { old.note },
                        source = SOURCE_MANUAL
                    )
                } else {
                    nodes.add(
                        EmotionNode(
                            id = newId("l"),
                            parentId = parent.first,
                            kind = KIND_LEAF,
                            cue = parent.second,
                            text = SafeCut.takeUnitsSafe(c, 3000),
                            weight = 2,
                            time = now,
                            lastRecalled = 0L,
                            times = 1,
                            note = note.trim().take(2000),
                            source = SOURCE_MANUAL
                        )
                    )
                    trimLeaves()
                }
            }
        }
        persist()
        return true
    }

    fun updateNode(id: String, text: String, note: String, weight: Int, pinned: Boolean): Boolean {
        val idx = nodes.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val old = nodes[idx]
        if (old.kind == KIND_ROOT) return false
        val c = text.trim()
        if (c.isBlank()) return false
        nodes[idx] = old.copy(
            text = c.take(if (old.kind == KIND_JOURNAL) 400 else 240),
            note = note.trim().take(2000),
            weight = weight.coerceIn(1, 9999),
            pinned = pinned,
            time = System.currentTimeMillis()
        )
        persist()
        return true
    }

    fun togglePin(id: String): Boolean {
        val idx = nodes.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val old = nodes[idx]
        if (old.kind == KIND_ROOT) return false
        nodes[idx] = old.copy(pinned = !old.pinned)
        persist()
        return true
    }

    fun moveNode(id: String, cueOrTwig: String): Boolean {
        val idx = nodes.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val old = nodes[idx]
        if (old.kind == KIND_ROOT || old.kind == KIND_BRANCH) return false
        val parent = resolveParent(cueOrTwig)
        if (parent.first == id) return false
        nodes[idx] = old.copy(parentId = parent.first, cue = parent.second, time = System.currentTimeMillis())
        if (old.kind == KIND_TWIG) {
            nodes.replaceAll { n ->
                if (n.parentId == old.id) n.copy(cue = parent.second) else n
            }
        }
        pruneEmptyBranches()
        persist()
        return true
    }

    fun reflectNow(): Boolean {
        UserCue.values().filter { it != UserCue.NONE }.forEach { maybeReflect(it) }
        nodes.filter { it.kind == KIND_TWIG }.forEach { maybeReflectTwig(it.id) }
        reflectJournal(force = true)
        persist()
        return true
    }

    fun exportJson(): String {
        val arr = JSONArray()
        nodes.forEach { n -> arr.put(nodeToJson(n)) }
        return JSONObject()
            .put("app", "xiaomo-emotion")
            .put("version", 2)
            .put("exportedAt", System.currentTimeMillis())
            .put("mood", mood)
            .put("affinity", affinity)
            .put("energy", energy)
            .put("nodes", arr)
            .toString()
    }

    fun importJson(text: String, replace: Boolean): Int {
        val incoming = parseImport(text) ?: return -1
        if (replace) {
            nodes.clear()
            incoming.forEach { n -> if (n.id.isNotBlank() && n.text.isNotBlank()) nodes.add(n) }
            ensureRoot()
            // 恢复随包导出的心情/好感/精力（兼容没有这些字段的旧包）
            runCatching {
                val o = JSONObject(text.trim())
                if (o.has("mood")) mood = o.optInt("mood", mood)
                if (o.has("affinity")) affinity = o.optInt("affinity", affinity)
                if (o.has("energy")) energy = o.optInt("energy", energy)
            }
            persist()
            sp.edit().putBoolean("treeMigrated", true).apply()
            return nodes.size
        }
        var n = 0
        incoming.forEach { item ->
            if (item.id == ID_ROOT) return@forEach
            if (item.kind == KIND_BRANCH) {
                if (item.cue == CUE_JOURNAL) ensureJournalBranch() else if (item.cue.isNotBlank()) ensureBranch(cueOf(item.cue))
                n++
                return@forEach
            }
            val exists = nodes.any { it.id == item.id || (it.kind == item.kind && it.parentId == item.parentId && similar(it.text, item.text)) }
            if (exists) {
                n++
                return@forEach
            }
            val parent = if (nodes.any { it.id == item.parentId }) item.parentId else {
                if (item.cue == CUE_JOURNAL) ensureJournalBranch() else ensureBranch(cueOf(item.cue))
            }
            nodes.add(item.copy(id = if (nodes.any { it.id == item.id }) newId(SafeCut.takeUnitsSafe(item.kind, 1)) else item.id, parentId = parent, source = SOURCE_IMPORT))
            n++
        }
        persist()
        return n
    }

    private fun hit(t: String, vararg keys: String): Boolean = keys.any { t.contains(it) }

    private fun rememberEvent(cue: UserCue, line: String) {
        val c = line.trim()
        if (c.isBlank() || cue == UserCue.NONE) return
        lastEventText = c
        ensureRoot()
        val branchId = ensureBranch(cue)
        val twigId = ensureTwigFor(cue, c, branchId)
        val parentId = twigId ?: branchId
        val now = System.currentTimeMillis()
        val idx = nodes.indexOfFirst {
            it.kind == KIND_LEAF && it.cue == cue.name && similar(it.text, c)
        }
        if (idx >= 0) {
            val old = nodes[idx]
            nodes[idx] = old.copy(
                text = SafeCut.takeUnitsSafe(c, 3000),
                weight = (old.weight + 1).coerceAtMost(9999),
                time = now,
                times = old.times + 1,
                parentId = parentId
            )
        } else {
            nodes.add(
                EmotionNode(
                    id = newId("l"),
                    parentId = parentId,
                    kind = KIND_LEAF,
                    cue = cue.name,
                    text = SafeCut.takeUnitsSafe(c, 3000),
                    weight = 1,
                    time = now,
                    lastRecalled = 0L,
                    times = 1,
                    source = SOURCE_AUTO
                )
            )
            trimLeaves()
        }
        maybeReflect(cue)
        if (twigId != null) maybeReflectTwig(twigId)
        persist()
        shadowLegacyEvents()
    }

    private fun maybeReflect(cue: UserCue) {
        val leaves = nodes.filter { it.kind == KIND_LEAF && it.cue == cue.name && it.weight > 0 }
            .sortedBy { it.time }
        if (leaves.size < INSIGHT_EVERY) return
        val lastInsightAt = nodes.filter { it.kind == KIND_INSIGHT && it.cue == cue.name && it.parentId == "b_" + cue.name }
            .maxOfOrNull { it.time } ?: 0L
        val fresh = leaves.filter { it.time > lastInsightAt }
        if (fresh.size < INSIGHT_EVERY) return
        val text = insightText(cue, fresh.ifEmpty { leaves })
        val now = System.currentTimeMillis()
        val branchId = ensureBranch(cue)
        val same = nodes.indexOfFirst { it.kind == KIND_INSIGHT && it.parentId == branchId && it.text == text }
        if (same >= 0) {
            val old = nodes[same]
            nodes[same] = old.copy(
                weight = (old.weight + 2).coerceAtMost(9999),
                time = now,
                times = old.times + 1
            )
        } else {
            nodes.add(
                EmotionNode(
                    id = newId("i"),
                    parentId = branchId,
                    kind = KIND_INSIGHT,
                    cue = cue.name,
                    text = text,
                    weight = 3 + leaves.size.coerceAtMost(6),
                    time = now,
                    lastRecalled = 0L,
                    times = 1,
                    source = SOURCE_AUTO
                )
            )
            trimInsights()
        }
    }

    private fun maybeReflectTwig(twigId: String) {
        val twig = nodes.firstOrNull { it.id == twigId && it.kind == KIND_TWIG } ?: return
        val leaves = childrenOf(twigId, KIND_LEAF).sortedBy { it.time }
        if (leaves.size < INSIGHT_EVERY) return
        val lastInsightAt = childrenOf(twigId, KIND_INSIGHT).maxOfOrNull { it.time } ?: 0L
        val fresh = leaves.filter { it.time > lastInsightAt }
        if (fresh.size < INSIGHT_EVERY) return
        val sample = fresh.takeLast(30).map { stem(it.text) }.filter { it.isNotBlank() }.distinct().take(20).joinToString("、")
        val text = if (sample.isBlank()) {
            "「${twig.text}」这件事反复出现，我会记着。"
        } else {
            "「${twig.text}」已经长成一条线索。最近提到过：$sample。"
        }
        val now = System.currentTimeMillis()
        val same = nodes.indexOfFirst { it.kind == KIND_INSIGHT && it.parentId == twigId && it.text == text }
        if (same >= 0) {
            val old = nodes[same]
            nodes[same] = old.copy(weight = (old.weight + 2).coerceAtMost(9999), time = now, times = old.times + 1)
        } else {
            nodes.add(
                EmotionNode(
                    id = newId("i"),
                    parentId = twigId,
                    kind = KIND_INSIGHT,
                    cue = twig.cue,
                    text = text,
                    weight = 4 + leaves.size.coerceAtMost(6),
                    time = now,
                    lastRecalled = 0L,
                    times = 1,
                    source = SOURCE_AUTO
                )
            )
            trimInsights()
        }
    }

    private fun ensureTwigFor(cue: UserCue, line: String, branchId: String): String? {
        val topic = twigTopic(cue, line) ?: return null
        val existing = nodes.firstOrNull { it.kind == KIND_TWIG && it.parentId == branchId && it.text == topic }
        if (existing != null) return existing.id
        val id = newId("t")
        nodes.add(
            EmotionNode(
                id = id,
                parentId = branchId,
                kind = KIND_TWIG,
                cue = cue.name,
                text = topic,
                weight = 1,
                time = System.currentTimeMillis(),
                lastRecalled = 0L,
                times = 1,
                source = SOURCE_AUTO
            )
        )
        trimTwigs()
        return id
    }

    private fun twigTopic(cue: UserCue, line: String): String? {
        val t = line
        return when (cue) {
            UserCue.TIRED -> when {
                hit(t, "加班") -> "加班"
                hit(t, "失眠", "睡不着") -> "失眠"
                hit(t, "熬夜") -> "熬夜"
                else -> "撑着"
            }
            UserCue.SAD -> when {
                hit(t, "想哭") -> "想哭"
                hit(t, "委屈") -> "委屈"
                else -> "低落"
            }
            UserCue.HURT -> "被凶"
            UserCue.MISS -> "想念"
            UserCue.LONELY -> "孤单"
            UserCue.PRAISE -> "被夸"
            UserCue.HAPPY -> "分享好事"
            UserCue.ANGRY -> "在生气"
            else -> null
        }
    }

    private fun reflectJournal(force: Boolean) {
        val cal = java.util.Calendar.getInstance()
        val day = cal.get(java.util.Calendar.YEAR) * 1000 + cal.get(java.util.Calendar.DAY_OF_YEAR)
        if (!force && lastJournalDay == day) return
        val start = dayStart(System.currentTimeMillis())
        val todayLeaves = nodes.filter { it.kind == KIND_LEAF && it.time >= start && it.weight > 0 }
        if (todayLeaves.size < 2 && !force) return
        if (todayLeaves.isEmpty() && force && nodes.none { it.kind == KIND_LEAF }) return
        val counts = linkedMapOf<String, Int>()
        todayLeaves.forEach { n -> counts[n.cue] = (counts[n.cue] ?: 0) + 1 }
        val top = counts.entries.sortedByDescending { it.value }.take(30)
            .joinToString("、") { cueLabel(it.key) + "×" + it.value }
        val sample = todayLeaves.sortedByDescending { it.time }.take(200).joinToString("；") { SafeCut.takeUnitsSafe(it.text, 500) }
        val text = buildString {
            append(dateText(System.currentTimeMillis()))
            append(" 心情日记：")
            if (top.isNotBlank()) append("今天更常出现").append(top).append("。")
            if (sample.isNotBlank()) append("记下：").append(sample)
            if (isBlank()) append("今天和主人一起过了一天。")
        }
        writeJournal(SafeCut.takeUnitsSafe(text, 8000), force)
        lastJournalDay = day
    }

    private fun writeJournal(text: String, force: Boolean) {
        val c = text.trim()
        if (c.isBlank()) return
        val branchId = ensureJournalBranch()
        val start = dayStart(System.currentTimeMillis())
        val idx = nodes.indexOfFirst { it.kind == KIND_JOURNAL && it.time >= start }
        val now = System.currentTimeMillis()
        if (idx >= 0) {
            val old = nodes[idx]
            nodes[idx] = old.copy(
                text = if (force) SafeCut.takeUnitsSafe(c, 8000) else SafeCut.takeUnitsSafe(old.text + " " + c, 8000),
                weight = (old.weight + 1).coerceAtMost(9999),
                time = now,
                times = old.times + 1
            )
        } else {
            nodes.add(
                EmotionNode(
                    id = newId("j"),
                    parentId = branchId,
                    kind = KIND_JOURNAL,
                    cue = CUE_JOURNAL,
                    text = SafeCut.takeUnitsSafe(c, 8000),
                    weight = 3,
                    time = now,
                    lastRecalled = 0L,
                    times = 1,
                    source = SOURCE_JOURNAL
                )
            )
            trimJournals()
        }
        persist()
    }

    private fun insightText(cue: UserCue, leaves: List<EmotionNode>): String {
        val n = leaves.size
        val sample = leaves.takeLast(30).map { stem(it.text) }.filter { it.isNotBlank() }.distinct().take(20).joinToString("、")
        val base = when (cue) {
            UserCue.TIRED -> "最近这段日子主人经常撑着，累了会来找我。"
            UserCue.SAD -> "主人心情低落的时候会跟我说，我要记得轻声陪着。"
            UserCue.ANGRY -> "主人生过气。先接住情绪，别急着讲道理。"
            UserCue.HURT -> "被凶过，但主人还是会回来。可以小委屈，不要记仇。"
            UserCue.HAPPY -> "主人开心的时候会分享给我，我也想跟着高兴。"
            UserCue.PRAISE -> "被鼓励过，会害羞地记很久。"
            UserCue.MISS -> "主人会想我。离开一阵再见面，要温柔接住。"
            UserCue.LONELY -> "主人孤单的时候会找我，告诉他我在就够了。"
            UserCue.DISMISS -> "主人有时不想多说，给他退路，别追问。"
            UserCue.CURIOUS -> "主人爱提问，先答清楚再决定要不要追一句。"
            UserCue.NONE -> "我们一起走过 $n 件小事。"
        }
        return if (sample.isBlank()) base else base.trimEnd('。') + "。最近提到过：$sample。"
    }

    private fun pickMemoryLines(): List<String> {
        decayTree(persistAfter = false)
        val picked = mutableListOf<String>()
        val used = mutableSetOf<String>()
        val stale = System.currentTimeMillis() - 6L * 60 * 60 * 1000L
        nodes.filter { it.pinned && it.weight > 0 && it.kind != KIND_ROOT && it.kind != KIND_BRANCH }
            .sortedByDescending { it.weight }
            .take(2)
            .forEach { markAndTake(it, picked, used) }
        val insights = nodes.filter { it.kind == KIND_INSIGHT && it.weight > 0 && it.id !in used }
            .sortedWith(
                compareBy<EmotionNode> { if (it.lastRecalled == 0L || it.lastRecalled < stale) 0 else 1 }
                    .thenByDescending { it.weight }
                    .thenBy { it.lastRecalled }
            )
        insights.firstOrNull()?.let { markAndTake(it, picked, used) }
        val cue = lastUserCue
        if (cue != UserCue.NONE) {
            nodes.filter { it.kind == KIND_LEAF && it.cue == cue.name && it.weight > 0 && it.id !in used }
                .maxWithOrNull(compareBy<EmotionNode> { it.weight }.thenBy { it.time })
                ?.let { markAndTake(it, picked, used) }
        }
        nodes.filter { it.kind == KIND_JOURNAL && it.weight > 0 && it.id !in used }
            .maxByOrNull { it.time }
            ?.let { markAndTake(it, picked, used) }
        nodes.filter { it.kind == KIND_LEAF && it.weight > 0 && it.id !in used }
            .maxByOrNull { it.time }
            ?.let { markAndTake(it, picked, used) }
        if (used.isNotEmpty()) persist()
        return picked.distinct().take(50)
    }

    private fun markAndTake(node: EmotionNode, picked: MutableList<String>, used: MutableSet<String>) {
        picked.add(node.text)
        used.add(node.id)
        val idx = nodes.indexOfFirst { it.id == node.id }
        if (idx >= 0) {
            nodes[idx] = nodes[idx].copy(lastRecalled = System.currentTimeMillis())
        }
    }

    private fun weeklyFocusCue(): UserCue? {
        val weekAgo = System.currentTimeMillis() - 7L * DAY_MS
        var best: UserCue? = null
        var bestScore = 0
        for (cue in UserCue.values()) {
            if (cue == UserCue.NONE || cue == UserCue.CURIOUS || cue == UserCue.DISMISS) continue
            val score = nodes.filter { it.cue == cue.name && it.time >= weekAgo && it.kind != KIND_ROOT && it.kind != KIND_BRANCH }
                .sumOf { n -> n.weight * if (n.kind == KIND_INSIGHT) 3 else 1 }
            if (score > bestScore) {
                bestScore = score
                best = cue
            }
        }
        return if (bestScore >= 3) best else null
    }

    private fun decayTree(persistAfter: Boolean) {
        val now = System.currentTimeMillis()
        var changed = false
        val next = ArrayList<EmotionNode>(nodes.size)
        for (n in nodes) {
            if (n.pinned || (n.kind != KIND_LEAF && n.kind != KIND_INSIGHT && n.kind != KIND_JOURNAL)) {
                next.add(n)
                continue
            }
            val half = when (n.kind) {
                KIND_INSIGHT -> INSIGHT_HALF_DAYS
                KIND_JOURNAL -> JOURNAL_HALF_DAYS
                else -> LEAF_HALF_DAYS
            }
            val ageDays = ((now - n.time) / DAY_MS).toInt().coerceAtLeast(0)
            val drop = ageDays / half
            val w = (n.weight - drop).coerceAtLeast(0)
            if (w <= 0) {
                changed = true
                continue
            }
            if (w != n.weight) {
                next.add(n.copy(weight = w))
                changed = true
            } else {
                next.add(n)
            }
        }
        if (!changed) return
        nodes.clear()
        nodes.addAll(next)
        pruneEmptyBranches()
        if (persistAfter) persist()
    }

    private fun ensureRoot() {
        if (nodes.none { it.id == ID_ROOT }) {
            nodes.add(
                0,
                EmotionNode(
                    id = ID_ROOT,
                    parentId = "",
                    kind = KIND_ROOT,
                    cue = UserCue.NONE.name,
                    text = "你们的关系",
                    weight = 1,
                    time = System.currentTimeMillis(),
                    lastRecalled = 0L,
                    times = 1
                )
            )
        }
    }

    private fun ensureBranch(cue: UserCue): String {
        ensureRoot()
        val id = "b_" + cue.name
        if (nodes.none { it.id == id }) {
            nodes.add(
                EmotionNode(
                    id = id,
                    parentId = ID_ROOT,
                    kind = KIND_BRANCH,
                    cue = cue.name,
                    text = cue.label,
                    weight = 1,
                    time = System.currentTimeMillis(),
                    lastRecalled = 0L,
                    times = 1
                )
            )
        }
        return id
    }

    private fun pruneEmptyBranches() {
        val liveTwigs = nodes.filter { it.kind == KIND_LEAF || it.kind == KIND_INSIGHT }.map { it.parentId }.toSet()
        nodes.removeAll { it.kind == KIND_TWIG && it.id !in liveTwigs && !it.pinned }
        val live = nodes.filter {
            it.kind == KIND_LEAF || it.kind == KIND_INSIGHT || it.kind == KIND_JOURNAL || it.kind == KIND_TWIG
        }.map { it.cue }.toSet()
        nodes.removeAll { it.kind == KIND_BRANCH && it.cue !in live && !it.pinned && it.id != ID_JOURNAL }
    }

    private fun ensureJournalBranch(): String {
        ensureRoot()
        if (nodes.none { it.id == ID_JOURNAL }) {
            nodes.add(
                EmotionNode(
                    id = ID_JOURNAL,
                    parentId = ID_ROOT,
                    kind = KIND_BRANCH,
                    cue = CUE_JOURNAL,
                    text = "日记",
                    weight = 1,
                    time = System.currentTimeMillis(),
                    lastRecalled = 0L,
                    times = 1,
                    source = SOURCE_JOURNAL
                )
            )
        }
        return ID_JOURNAL
    }

    private fun trimLeaves() {
        val extra = nodes.count { it.kind == KIND_LEAF } - MAX_LEAVES
        if (extra <= 0) return
        val drop = nodes.filter { it.kind == KIND_LEAF && !it.pinned }
            .sortedWith(compareBy<EmotionNode> { it.weight }.thenBy { it.time })
            .take(extra)
            .map { it.id }
            .toSet()
        nodes.removeAll { it.id in drop }
        pruneEmptyBranches()
    }

    private fun trimInsights() {
        val insights = nodes.filter { it.kind == KIND_INSIGHT && !it.pinned }
        val extra = nodes.count { it.kind == KIND_INSIGHT } - MAX_INSIGHTS
        if (extra <= 0) return
        val drop = insights.sortedWith(compareBy<EmotionNode> { it.weight }.thenBy { it.time })
            .take(extra)
            .map { it.id }
            .toSet()
        nodes.removeAll { it.id in drop }
    }

    private fun trimTwigs() {
        val twigs = nodes.filter { it.kind == KIND_TWIG && !it.pinned }
        val extra = nodes.count { it.kind == KIND_TWIG } - MAX_TWIGS
        if (extra <= 0) return
        val drop = twigs.sortedWith(compareBy<EmotionNode> { twigScore(it.id) }.thenBy { it.time })
            .take(extra)
            .map { it.id }
            .toSet()
        drop.forEach { forget(it) }
    }

    private fun trimJournals() {
        val journals = nodes.filter { it.kind == KIND_JOURNAL && !it.pinned }
        val extra = nodes.count { it.kind == KIND_JOURNAL } - MAX_JOURNALS
        if (extra <= 0) return
        val drop = journals.sortedBy { it.time }.take(extra).map { it.id }.toSet()
        nodes.removeAll { it.id in drop }
    }

    private fun branchScore(cue: String): Int {
        return nodes.filter { it.cue == cue && (it.kind == KIND_LEAF || it.kind == KIND_INSIGHT || it.kind == KIND_JOURNAL) }
            .sumOf { it.weight }
    }

    private fun twigScore(id: String): Int =
        nodes.filter { it.parentId == id }.sumOf { it.weight }

    private fun childrenOf(parentId: String, kind: String): List<EmotionNode> =
        nodes.filter { it.parentId == parentId && it.kind == kind && it.weight > 0 }
            .sortedWith(compareByDescending<EmotionNode> { it.pinned }.thenByDescending { it.time })

    private fun appendKids(
        rows: MutableList<EmotionRow>,
        kids: List<EmotionNode>,
        depth: Int,
        searching: Boolean,
        q: String,
        branchHit: Boolean
    ) {
        kids.forEach { n ->
            if (searching && !branchHit && !match(q, n.text, n.note, kindLabel(n.kind))) return@forEach
            rows.add(
                EmotionRow(
                    id = n.id,
                    kind = n.kind,
                    cue = n.cue,
                    title = kindLabel(n.kind) + if (n.pinned) " · 钉住" else "",
                    body = n.text,
                    meta = "权重 ${n.weight} · " + timeText(n.time) + if (n.note.isNotBlank()) " · " + n.note else "",
                    depth = depth,
                    pinned = n.pinned,
                    expandable = false,
                    expanded = false,
                    note = n.note
                )
            )
        }
    }

    private fun kindLabel(kind: String): String = when (kind) {
        KIND_BRANCH -> "枝"
        KIND_TWIG -> "子枝"
        KIND_LEAF -> "事件"
        KIND_INSIGHT -> "沉淀"
        KIND_JOURNAL -> "日记"
        else -> kind
    }

    private fun match(q: String, vararg parts: String): Boolean {
        if (q.isBlank()) return true
        val needle = q.lowercase()
        return parts.any { it.lowercase().contains(needle) }
    }

    private fun subtreeHits(parentId: String, q: String): Boolean {
        val kids = nodes.filter { it.parentId == parentId }
        if (kids.any { match(q, it.text, it.note, kindLabel(it.kind)) }) return true
        return kids.any { subtreeHits(it.id, q) }
    }

    private fun cueLabel(cue: String): String =
        if (cue == CUE_JOURNAL) "日记" else cueOf(cue).label

    private fun resolveCueName(raw: String): String {
        val t = raw.trim()
        if (t.isBlank()) return UserCue.HAPPY.name
        if (t == CUE_JOURNAL || t == "日记") return CUE_JOURNAL
        val twig = nodes.firstOrNull { it.id == t && it.kind == KIND_TWIG }
        if (twig != null) return twig.cue
        UserCue.values().forEach { if (it.name == t || it.label == t) return it.name }
        return UserCue.HAPPY.name
    }

    private fun resolveParent(raw: String): Pair<String, String> {
        val t = raw.trim()
        val twig = nodes.firstOrNull { it.id == t && it.kind == KIND_TWIG }
        if (twig != null) return twig.id to twig.cue
        val cue = resolveCueName(t)
        val id = if (cue == CUE_JOURNAL) ensureJournalBranch() else ensureBranch(cueOf(cue))
        return id to cue
    }

    private fun dayStart(now: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun dateText(time: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = time
        val m = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        return "$m-$d"
    }

    private fun similar(a: String, b: String): Boolean {
        val x = stem(a)
        val y = stem(b)
        if (x == y) return true
        if (x.length >= 20 && y.length >= 20 && (x.startsWith(SafeCut.takeUnitsSafe(y, 20)) || y.startsWith(SafeCut.takeUnitsSafe(x, 20)))) return true
        return false
    }

    private fun stem(s: String): String {
        val cut = s.replace(':', '：')
        val i = cut.indexOf('：')
        return (if (i > 0) cut.substring(0, i) else cut).trim()
    }

    private fun cueOf(name: String): UserCue =
        runCatching { UserCue.valueOf(name) }.getOrDefault(UserCue.NONE)

    private fun guessCue(line: String): UserCue {
        val t = line
        return when {
            hit(t, "累", "困", "加班", "熬夜", "失眠") -> UserCue.TIRED
            hit(t, "不开心", "难过", "伤心") -> UserCue.SAD
            hit(t, "生气", "好气") -> UserCue.ANGRY
            hit(t, "对我生气", "被凶", "委屈") -> UserCue.HURT
            hit(t, "夸", "表扬", "鼓励") -> UserCue.PRAISE
            hit(t, "想我", "想你") -> UserCue.MISS
            hit(t, "孤单", "寂寞") -> UserCue.LONELY
            hit(t, "开心", "高兴") -> UserCue.HAPPY
            hit(t, "关心") -> lastUserCue.takeIf { it != UserCue.NONE } ?: UserCue.MISS
            else -> UserCue.HAPPY
        }
    }

    private fun migrateLegacyEvents() {
        if (sp.getBoolean("treeMigrated", false) && nodes.any { it.kind == KIND_LEAF || it.kind == KIND_INSIGHT }) return
        if (sp.getBoolean("treeMigrated", false) && file.isFile) return
        val events = (sp.getString("events", "") ?: "").split("\n").filter { it.isNotBlank() }
        if (events.isEmpty()) {
            sp.edit().putBoolean("treeMigrated", true).apply()
            ensureRoot()
            persist()
            return
        }
        events.forEach { line ->
            val cue = guessCue(line)
            val c = line.trim().take(2000)
            if (c.isBlank()) return@forEach
            ensureBranch(cue)
            val exists = nodes.any { it.kind == KIND_LEAF && it.cue == cue.name && similar(it.text, c) }
            if (!exists) {
                nodes.add(
                    EmotionNode(
                        id = newId("l"),
                        parentId = "b_" + cue.name,
                        kind = KIND_LEAF,
                        cue = cue.name,
                        text = c,
                        weight = 1,
                        time = System.currentTimeMillis(),
                        lastRecalled = 0L,
                        times = 1
                    )
                )
            }
        }
        UserCue.values().filter { it != UserCue.NONE }.forEach { maybeReflect(it) }
        persist()
        sp.edit().putBoolean("treeMigrated", true).apply()
    }

    private fun shadowLegacyEvents() {
        val recent = nodes.filter { it.kind == KIND_LEAF }
            .sortedBy { it.time }
            .takeLast(200)
            .map { it.text }
        sp.edit().putString("events", recent.joinToString("\n")).apply()
    }

    private fun load() {
        ensureRoot()
        if (!file.isFile) return
        try {
            val o = JSONObject(file.readText())
            val arr = o.optJSONArray("nodes") ?: return
            val loaded = parseArray(arr)
            if (loaded.isNotEmpty()) {
                nodes.clear()
                nodes.addAll(loaded)
                ensureRoot()
            }
        } catch (_: Exception) { }
    }

    private fun persist() {
        ensureRoot()
        try {
            val arr = JSONArray()
            nodes.forEach { n -> arr.put(nodeToJson(n)) }
            val o = JSONObject()
                .put("app", "xiaomo-emotion")
                .put("version", 2)
                .put("savedAt", System.currentTimeMillis())
                .put("affinity", affinity)
                .put("nodes", arr)
            file.writeText(o.toString())
        } catch (_: Exception) { }
    }

    private fun nodeToJson(n: EmotionNode): JSONObject =
        JSONObject()
            .put("id", n.id)
            .put("parentId", n.parentId)
            .put("kind", n.kind)
            .put("cue", n.cue)
            .put("text", n.text)
            .put("weight", n.weight)
            .put("time", n.time)
            .put("lastRecalled", n.lastRecalled)
            .put("times", n.times)
            .put("pinned", n.pinned)
            .put("note", n.note)
            .put("source", n.source)

    private fun parseImport(text: String): List<EmotionNode>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("[")) {
                parseArray(JSONArray(trimmed)).ifEmpty { null }
            } else {
                val o = JSONObject(trimmed)
                val arr = o.optJSONArray("nodes") ?: o.optJSONArray("items") ?: o.optJSONArray("memories")
                if (arr != null) parseArray(arr).ifEmpty { null } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseArray(arr: JSONArray): List<EmotionNode> {
        val loaded = mutableListOf<EmotionNode>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val id = n.optString("id").trim().ifBlank { newId("n") }
            val text = n.optString("text").ifBlank { n.optString("content") }.trim()
            if (text.isBlank()) continue
            loaded.add(
                EmotionNode(
                    id = id,
                    parentId = n.optString("parentId"),
                    kind = n.optString("kind", KIND_LEAF),
                    cue = n.optString("cue", UserCue.NONE.name),
                    text = text,
                    weight = n.optInt("weight", 1).coerceAtLeast(0),
                    time = n.optLong("time", 0L),
                    lastRecalled = n.optLong("lastRecalled", 0L),
                    times = n.optInt("times", 1).coerceAtLeast(1),
                    pinned = n.optBoolean("pinned", false),
                    note = n.optString("note"),
                    source = n.optString("source", SOURCE_IMPORT)
                )
            )
        }
        return loaded
    }

    private fun newId(prefix: String): String =
        prefix + System.currentTimeMillis().toString(36) + nodes.size.toString(36)

    private fun timeText(time: Long): String {
        if (time <= 0L) return ""
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = time
        val m = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
        val d = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val min = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
        return "$m-$d $h:$min"
    }
}
