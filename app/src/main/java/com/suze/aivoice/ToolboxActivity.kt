package com.suze.aivoice

import android.app.AlertDialog
import android.os.Bundle
import android.os.CountDownTimer
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random

/** Forty offline-first tools that share the app's local storage. */
class ToolboxActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("xiaomo_toolbox", MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private var focusTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 18, 20, 18)
        }
        UiThemeManager.applyPage(this, root)
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(4, 4, 4, 14) }
        bar.addView(TextView(this).apply {
            text = "生活工具箱"
            textSize = 23f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(15, 53, 87))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(this).apply {
            text = "‹"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(30, 111, 184))
            background = getDrawable(R.drawable.icon_touch_bg)
            contentDescription = "返回"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(48, 48))
        root.addView(bar)
        root.addView(TextView(this).apply {
            text = "日常安排 · 记录 · 专注"
            textSize = 13f
            setTextColor(Color.rgb(60, 110, 155))
            setPadding(4, 0, 4, 14)
        })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { clipToPadding = false; isVerticalScrollBarEnabled = false; addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        addTool("习惯打卡", "记录今天完成了什么习惯") { habit() }
        addTool("收支账本", "快速记录收入和支出") { ledger() }
        addTool("私人日记", "按日期保存一段只属于你的文字") { diary() }
        addTool("纪念日倒数", "保存日期并计算距离今天") { anniversary() }
        addTool("阅读进度", "记录书名、页数和阅读笔记") { reading() }
        addTool("闪卡复习", "添加问答卡片并随机抽取复习") { flashcards() }
        addTool("常用短语", "保存可一键复制的常用文字") { phrases() }
        addTool("选择转盘", "输入候选项，随机帮你做决定") { wheel() }
        addTool("专注计时", "25 分钟专注计时器") { focus() }
        addTool("密码生成器", "离线生成随机密码") { password() }
        addTool("饮水提醒", "记录每天喝水次数和目标") { water() }
        addTool("运动记录", "记录今日运动类型和时长") { exercise() }
        addTool("体重追踪", "按日期记录体重变化") { weight() }
        addTool("睡眠日志", "记录入睡和起床时间") { sleep() }
        addTool("情绪日记", "用简短文字记录当下心情") { mood() }
        addTool("感恩清单", "每天写下值得感恩的事") { gratitude() }
        addTool("愿望清单", "列出想做的事和想去的地方") { wishlist() }
        addTool("目标看板", "设定目标并追踪完成进度") { goals() }
        addTool("时间胶囊", "保存未来某天才能打开的消息") { capsule() }
        addTool("灵感收集", "随手记录脑海中闪现的想法") { ideas() }
        addTool("电影清单", "记录想看和已看的电影") { movies() }
        addTool("美食记录", "保存喜欢的餐厅和菜品") { food() }
        addTool("旅行计划", "规划目的地、预算和行程") { travel() }
        addTool("购物清单", "列出要买的东西避免遗漏") { shopping() }
        addTool("礼物备忘", "记录朋友喜好和送礼灵感") { gifts() }
        addTool("生日管家", "保存亲友生日避免忘记") { birthdays() }
        addTool("习语学习", "收藏单词、短语和例句") { vocabulary() }
        addTool("每日一句", "保存喜欢的名言和金句") { quotes() }
        addTool("梦境记录", "醒来后立即记下梦的内容") { dreams() }
        addTool("冥想计时", "10 分钟冥想呼吸计时") { meditation() }
        addTool("快速笔记", "临时记事本随时保存文字") { notes() }
        addTool("倒计时器", "自定义时长的倒计时提醒") { countdown() }
        addTool("纪念相册", "用文字记录珍贵时刻") { moments() }
        addTool("书摘收藏", "摘录书中打动你的段落") { bookmarks() }
        addTool("小确幸", "记录生活中微小的快乐瞬间") { smalljoys() }
        addTool("待办分类", "按项目分类管理任务清单") { projects() }
        addTool("每月总结", "回顾这个月的收获与不足") { monthly() }
        addTool("消费分析", "统计本月各类支出占比") { analysis() }
        addTool("戒断打卡", "记录戒除某习惯的天数") { quit() }
        addTool("学习打卡", "记录每日学习时长和内容") { study() }
    }

    private fun addTool(title: String, subtitle: String, action: () -> Unit) {
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18, 15, 18, 15)
            background = getDrawable(R.drawable.glass_card)
            isClickable = true
            isFocusable = true
            elevation = 2f
            setOnClickListener { action() }
            addView(TextView(this@ToolboxActivity).apply {
                text = title
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.rgb(15, 53, 87))
            })
            addView(TextView(this@ToolboxActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(Color.rgb(60, 110, 155))
                setPadding(0, 4, 0, 0)
            })
        }
        list.addView(b, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
    }

    private fun input(title: String, hint: String, initial: String = "", multiline: Boolean = false, done: (String) -> Unit) {
        val e = EditText(this).apply { this.hint = hint; setText(initial) }
        if (multiline) e.minLines = 4
        AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton("保存") { _, _ -> done(e.text.toString().trim()) }
            .setNegativeButton("取消", null).show()
    }

    private fun habit() {
        val key = "habit_" + SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val old = prefs.getString(key, "") ?: ""
        input("今日习惯", "例如：运动 20 分钟", old, true) { v -> prefs.edit().putString(key, v).apply(); toast("已保存今日打卡") }
    }
    private fun ledger() {
        input("新增收支", "例如：支出 25 午餐 / 收入 300 工资") { v ->
            if (v.isNotBlank()) { val old = prefs.getString("ledger", "") ?: ""; prefs.edit().putString("ledger", "$old\n${now()}  $v").apply(); toast("账目已保存") }
        }
        showSaved("ledger", "收支记录")
    }
    private fun diary() {
        val key = "diary_" + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        input("今日私密日记", "写下今天的心情", prefs.getString(key, "") ?: "", true) { v -> prefs.edit().putString(key, v).apply(); toast("日记已保存") }
    }
    private fun anniversary() {
        input("纪念日", "例如：小沫生日|2026-12-01", prefs.getString("anniversary", "") ?: "") { v -> prefs.edit().putString("anniversary", v).apply(); toast("纪念日已保存") }
        val saved = prefs.getString("anniversary", "") ?: ""
        if (saved.isNotBlank()) toast("已保存：$saved")
    }
    private fun reading() {
        input("阅读进度", "例如：书名|第 120 页|感想", prefs.getString("reading", "") ?: "", true) { v -> prefs.edit().putString("reading", v).apply(); toast("阅读进度已保存") }
    }
    private fun flashcards() {
        input("添加闪卡", "问题|答案") { v -> if (v.contains("|")) { val old = prefs.getString("cards", "") ?: ""; prefs.edit().putString("cards", "$old\n$v").apply(); toast("闪卡已添加") } else toast("请用 | 分隔问题和答案") }
        val cards = (prefs.getString("cards", "") ?: "").lines().filter { it.contains("|") }
        if (cards.isNotEmpty()) AlertDialog.Builder(this).setTitle("随机复习").setMessage(cards.random().replace("|", "\n\n答案："))
            .setPositiveButton("知道了", null).show()
    }
    private fun phrases() {
        input("常用短语", "输入要保存的文字", prefs.getString("phrases", "") ?: "", true) { v -> prefs.edit().putString("phrases", v).apply(); toast("短语已保存") }
        val text = prefs.getString("phrases", "") ?: ""
        if (text.isNotBlank()) AlertDialog.Builder(this).setTitle("已保存短语").setMessage(text).setPositiveButton("复制") { _, _ ->
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("短语", text)); toast("已复制")
        }.setNegativeButton("关闭", null).show()
    }
    private fun wheel() {
        input("选择转盘", "候选项用逗号分隔") { v -> val a = v.split(',', '，').map { it.trim() }.filter { it.isNotBlank() }; if (a.isNotEmpty()) AlertDialog.Builder(this).setTitle("结果").setMessage(a.random()).setPositiveButton("确定", null).show() }
    }
    private fun focus() {
        focusTimer?.cancel()
        val label = TextView(this).apply { textSize = 28f; gravity = Gravity.CENTER; setPadding(20, 30, 20, 30) }
        val dlg = AlertDialog.Builder(this).setTitle("专注计时").setView(label).setNegativeButton("停止") { _, _ -> focusTimer?.cancel() }.create()
        dlg.show()
        focusTimer = object : CountDownTimer(25 * 60 * 1000L, 1000) {
            override fun onTick(ms: Long) { label.text = "%02d:%02d".format(ms / 60000, (ms / 1000) % 60) }
            override fun onFinish() { label.text = "完成！休息一下吧"; toast("专注时间结束") }
        }.start()
    }
    private fun password() {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%"
        val value = (1..20).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        AlertDialog.Builder(this).setTitle("随机密码").setMessage(value).setPositiveButton("复制") { _, _ ->
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("密码", value)); toast("已复制到剪贴板")
        }.setNegativeButton("关闭", null).show()
    private fun water() {
        val key = "water_" + SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val count = prefs.getInt(key, 0)
        prefs.edit().putInt(key, count + 1).apply()
        toast("今日已喝水 ${count + 1} 次")
    }
    private fun exercise() {
        val key = "exercise_" + SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        input("今日运动", "例如：跑步 30 分钟", prefs.getString(key, "") ?: "", true) { v -> prefs.edit().putString(key, v).apply(); toast("运动已记录") }
    }
    private fun weight() {
        val key = "weight_" + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        input("今日体重", "例如：65.5", prefs.getString(key, "") ?: "") { v -> prefs.edit().putString(key, v).apply(); toast("体重已记录") }
    }
    private fun sleep() {
        val key = "sleep_" + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        input("睡眠时间", "例如：23:30-07:00", prefs.getString(key, "") ?: "") { v -> prefs.edit().putString(key, v).apply(); toast("睡眠已记录") }
    }
    private fun mood() {
        val key = "mood_" + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        input("此刻心情", "用一句话描述", "", true) { v -> if (v.isNotBlank()) { val old = prefs.getString("moods", "") ?: ""; prefs.edit().putString("moods", "$old\n$key  $v").apply(); toast("心情已保存") } }
    }
    private fun gratitude() {
        val key = "gratitude_" + SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        input("今日感恩", "感谢的人或事", prefs.getString(key, "") ?: "", true) { v -> prefs.edit().putString(key, v).apply(); toast("感恩清单已保存") }
    }
    private fun wishlist() {
        input("新增愿望", "想做的事或想去的地方") { v -> if (v.isNotBlank()) { val old = prefs.getString("wishlist", "") ?: ""; prefs.edit().putString("wishlist", "$old\n• $v").apply(); toast("愿望已添加") } }
        showSaved("wishlist", "愿望清单")
    }
    private fun goals() {
        input("设定目标", "例如：读完 10 本书") { v -> if (v.isNotBlank()) { val old = prefs.getString("goals", "") ?: ""; prefs.edit().putString("goals", "$old\n[ ] $v").apply(); toast("目标已添加") } }
        showSaved("goals", "目标看板")
    }
    private fun capsule() {
        input("时间胶囊", "日期|消息 (例如：2027-01-01|新年快乐)", prefs.getString("capsule", "") ?: "", true) { v -> prefs.edit().putString("capsule", v).apply(); toast("时间胶囊已封存") }
    }
    private fun ideas() {
        input("灵感记录", "写下你的想法") { v -> if (v.isNotBlank()) { val old = prefs.getString("ideas", "") ?: ""; prefs.edit().putString("ideas", "$old\n${now()}  $v").apply(); toast("灵感已保存") } }
        showSaved("ideas", "灵感收集")
    }
    private fun movies() {
        input("电影清单", "电影名|状态 (想看/已看)") { v -> if (v.isNotBlank()) { val old = prefs.getString("movies", "") ?: ""; prefs.edit().putString("movies", "$old\n$v").apply(); toast("电影已添加") } }
        showSaved("movies", "电影清单")
    }
    private fun food() {
        input("美食记录", "餐厅名|菜品|评价") { v -> if (v.isNotBlank()) { val old = prefs.getString("food", "") ?: ""; prefs.edit().putString("food", "$old\n$v").apply(); toast("美食已记录") } }
        showSaved("food", "美食记录")
    }
    private fun travel() {
        input("旅行计划", "目的地|预算|日期", prefs.getString("travel", "") ?: "", true) { v -> prefs.edit().putString("travel", v).apply(); toast("旅行计划已保存") }
    }
    private fun shopping() {
        input("购物清单", "要买的东西") { v -> if (v.isNotBlank()) { val old = prefs.getString("shopping", "") ?: ""; prefs.edit().putString("shopping", "$old\n☐ $v").apply(); toast("清单已添加") } }
        showSaved("shopping", "购物清单")
    }
    private fun gifts() {
        input("礼物备忘", "对象|喜好|送礼想法") { v -> if (v.isNotBlank()) { val old = prefs.getString("gifts", "") ?: ""; prefs.edit().putString("gifts", "$old\n$v").apply(); toast("礼物想法已保存") } }
        showSaved("gifts", "礼物备忘")
    }
    private fun birthdays() {
        input("生日管家", "姓名|日期 (例如：小明|05-20)") { v -> if (v.isNotBlank()) { val old = prefs.getString("birthdays", "") ?: ""; prefs.edit().putString("birthdays", "$old\n$v").apply(); toast("生日已添加") } }
        showSaved("birthdays", "生日管家")
    }
    private fun vocabulary() {
        input("习语学习", "单词|释义|例句") { v -> if (v.contains("|")) { val old = prefs.getString("vocabulary", "") ?: ""; prefs.edit().putString("vocabulary", "$old\n$v").apply(); toast("词汇已添加") } else toast("请用 | 分隔") }
        showSaved("vocabulary", "习语学习")
    }
    private fun quotes() {
        input("每日一句", "喜欢的名言或金句") { v -> if (v.isNotBlank()) { val old = prefs.getString("quotes", "") ?: ""; prefs.edit().putString("quotes", "$old\n— $v").apply(); toast("金句已收藏") } }
        val quotes = (prefs.getString("quotes", "") ?: "").lines().filter { it.isNotBlank() }
        if (quotes.isNotEmpty()) AlertDialog.Builder(this).setTitle("随机一句").setMessage(quotes.random())
            .setPositiveButton("知道了", null).show()
    }
    private fun dreams() {
        val key = "dream_" + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        input("梦境记录", "醒来立即记下", prefs.getString(key, "") ?: "", true) { v -> prefs.edit().putString(key, v).apply(); toast("梦境已记录") }
    }
    private fun meditation() {
        focusTimer?.cancel()
        val label = TextView(this).apply { textSize = 28f; gravity = Gravity.CENTER; setPadding(20, 30, 20, 30) }
        val dlg = AlertDialog.Builder(this).setTitle("冥想呼吸").setView(label).setNegativeButton("停止") { _, _ -> focusTimer?.cancel() }.create()
        dlg.show()
        focusTimer = object : CountDownTimer(10 * 60 * 1000L, 1000) {
            override fun onTick(ms: Long) { label.text = "%02d:%02d".format(ms / 60000, (ms / 1000) % 60) }
            override fun onFinish() { label.text = "冥想结束"; toast("感受平静的力量") }
        }.start()
    }
    private fun notes() {
        input("快速笔记", "临时记事", prefs.getString("notes", "") ?: "", true) { v -> prefs.edit().putString("notes", v).apply(); toast("笔记已保存") }
    }
    private fun countdown() {
        input("倒计时", "分钟数 (例如：5)") { v ->
            val mins = v.toIntOrNull()
            if (mins != null && mins > 0) {
                focusTimer?.cancel()
                val label = TextView(this).apply { textSize = 28f; gravity = Gravity.CENTER; setPadding(20, 30, 20, 30) }
                val dlg = AlertDialog.Builder(this).setTitle("倒计时").setView(label).setNegativeButton("停止") { _, _ -> focusTimer?.cancel() }.create()
                dlg.show()
                focusTimer = object : CountDownTimer(mins * 60 * 1000L, 1000) {
                    override fun onTick(ms: Long) { label.text = "%02d:%02d".format(ms / 60000, (ms / 1000) % 60) }
                    override fun onFinish() { label.text = "时间到！"; toast("倒计时结束") }
                }.start()
            } else toast("请输入有效分钟数")
        }
    }
    private fun moments() {
        input("纪念时刻", "日期|事件描述") { v -> if (v.isNotBlank()) { val old = prefs.getString("moments", "") ?: ""; prefs.edit().putString("moments", "$old\n$v").apply(); toast("时刻已记录") } }
        showSaved("moments", "纪念相册")
    }
    private fun bookmarks() {
        input("书摘收藏", "书名|摘录段落") { v -> if (v.isNotBlank()) { val old = prefs.getString("bookmarks", "") ?: ""; prefs.edit().putString("bookmarks", "$old\n\n$v").apply(); toast("书摘已收藏") } }
        showSaved("bookmarks", "书摘收藏")
    }
    private fun smalljoys() {
        input("今日小确幸", "微小的快乐瞬间") { v -> if (v.isNotBlank()) { val old = prefs.getString("smalljoys", "") ?: ""; prefs.edit().putString("smalljoys", "$old\n${now()}  $v").apply(); toast("小确幸已保存") } }
        showSaved("smalljoys", "小确幸记录")
    }
    private fun projects() {
        input("项目任务", "项目名|任务内容") { v -> if (v.isNotBlank()) { val old = prefs.getString("projects", "") ?: ""; prefs.edit().putString("projects", "$old\n$v").apply(); toast("任务已添加") } }
        showSaved("projects", "待办分类")
    }
    private fun monthly() {
        val key = "monthly_" + SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(Date())
        input("本月总结", "收获与不足", prefs.getString(key, "") ?: "", true) { v -> prefs.edit().putString(key, v).apply(); toast("总结已保存") }
    }
    private fun analysis() {
        val ledger = prefs.getString("ledger", "") ?: ""
        val lines = ledger.lines().filter { it.contains("支出") }
        if (lines.isEmpty()) { toast("暂无支出记录"); return }
        val summary = "本月共 ${lines.size} 笔支出\n\n${lines.takeLast(10).joinToString("\n")}"
        AlertDialog.Builder(this).setTitle("消费分析").setMessage(summary).setPositiveButton("关闭", null).show()
    }
    private fun quit() {
        val start = prefs.getString("quit_start", "") ?: ""
        if (start.isBlank()) {
            input("戒断打卡", "开始日期 (YYYY-MM-DD)") { v ->
                prefs.edit().putString("quit_start", v).apply()
                toast("开始日期已设定")
            }
        } else {
            toast("戒断开始于 $start")
        }
    }
    private fun study() {
        val key = "study_" + SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        input("今日学习", "时长和内容", prefs.getString(key, "") ?: "", true) { v -> prefs.edit().putString(key, v).apply(); toast("学习已打卡") }
    }
    private fun showSaved(key: String, title: String) { val v = prefs.getString(key, "") ?: ""; if (v.isNotBlank()) AlertDialog.Builder(this).setTitle(title).setMessage(v.trim()).setPositiveButton("关闭", null).show() }
    private fun now() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
}
