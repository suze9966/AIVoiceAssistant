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

/** Ten small, offline-first tools that share the app's local storage. */
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
    }
    private fun showSaved(key: String, title: String) { val v = prefs.getString(key, "") ?: ""; if (v.isNotBlank()) AlertDialog.Builder(this).setTitle(title).setMessage(v.trim()).setPositiveButton("关闭", null).show() }
    private fun now() = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
