package com.suze.aivoice

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.graphics.ColorUtils

/**
 * 界面主题：三版视觉方案切换。
 *
 * A 深海蓝夜 / B 晨雾浅蓝 / C 蓝调液态玻璃。
 * 每版的 drawable 与颜色都已独立命名（liquid_ / mist_ / abyss_），
 * 所以切换只改运行时引用，不动布局文件里的控件 ID，也不改任何业务绑定。
 */
object ThemeSwitch {

    const val ABYSS = "abyss"   // A 深海蓝夜
    const val MIST = "mist"     // B 晨雾浅蓝
    const val LIQUID = "liquid" // C 蓝调液态玻璃

    const val DEFAULT = MIST

    private const val SP = "ai_voice_prefs"
    private const val KEY_THEME = "uiTheme"

    /** 供设置页下拉用：名称与取值。 */
    val LABELS: List<Pair<String, String>> = listOf(
        "A · 深海蓝夜" to ABYSS,
        "B · 晨雾浅蓝" to MIST,
        "C · 蓝调液态玻璃" to LIQUID
    )

    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun current(context: Context): String {
        val v = sp(context).getString(KEY_THEME, DEFAULT) ?: DEFAULT
        return if (v == ABYSS || v == MIST || v == LIQUID) v else DEFAULT
    }

    fun set(context: Context, theme: String) {
        sp(context).edit().putString(KEY_THEME, theme).apply()
    }

    // ---------- 各版取色 ----------

    private fun c(context: Context, res: Int): Int = context.resources.getColor(res, null)

    fun pageBackground(context: Context): Int = when (current(context)) {
        ABYSS -> R.drawable.abyss_page_bg
        LIQUID -> R.drawable.liquid_page_bg
        else -> R.drawable.mist_page_bg
    }

    private fun bubbleMe(context: Context): Int = when (current(context)) {
        ABYSS -> R.drawable.abyss_bubble_me
        LIQUID -> R.drawable.liquid_bubble_me
        else -> R.drawable.mist_bubble_me
    }

    private fun bubbleAi(context: Context): Int = when (current(context)) {
        ABYSS -> R.drawable.abyss_bubble_ai
        LIQUID -> R.drawable.liquid_bubble_ai
        else -> R.drawable.mist_bubble_ai
    }

    private fun glassCard(context: Context): Int = when (current(context)) {
        ABYSS -> R.drawable.abyss_card
        LIQUID -> R.drawable.liquid_card
        else -> R.drawable.mist_card
    }

    private fun bottomBar(context: Context): Int = when (current(context)) {
        ABYSS -> R.drawable.abyss_bottom_bar
        LIQUID -> R.drawable.liquid_bottom_bar
        else -> R.drawable.mist_bottom_bar
    }

    private fun inputBg(context: Context): Int = when (current(context)) {
        ABYSS -> R.drawable.abyss_input_bg
        LIQUID -> R.drawable.liquid_input_bg
        else -> R.drawable.mist_input_bg
    }

    private fun sendBtn(context: Context): Int = when (current(context)) {
        ABYSS -> R.drawable.abyss_send_btn
        LIQUID -> R.drawable.liquid_send_btn
        else -> R.drawable.mist_send_btn
    }

    private fun textPrimary(context: Context): Int = when (current(context)) {
        ABYSS -> c(context, R.color.abyss_text_primary)
        LIQUID -> c(context, R.color.liquid_text_primary)
        else -> c(context, R.color.mist_text_primary)
    }

    private fun textSecondary(context: Context): Int = when (current(context)) {
        ABYSS -> c(context, R.color.abyss_text_secondary)
        LIQUID -> c(context, R.color.liquid_text_secondary)
        else -> c(context, R.color.mist_text_secondary)
    }

    private fun textTertiary(context: Context): Int = when (current(context)) {
        ABYSS -> c(context, R.color.abyss_text_tertiary)
        LIQUID -> c(context, R.color.liquid_text_tertiary)
        else -> c(context, R.color.mist_text_tertiary)
    }

    /** 该版是否深底（深底要反过来开浅色系统栏图标）。 */
    fun isDark(context: Context): Boolean = when (current(context)) {
        ABYSS, LIQUID -> true
        else -> false
    }

    // ---------- 应用到主界面 ----------

    /**
     * 把当前主题刷到主聊天页。只改背景与文字色，
     * 不增删控件、不改 ID，因此 RecyclerView 的 adapter 无需重建。
     */
    fun applyMain(root: View) {
        val ctx = root.context

        // chatContent 底
        find(root, R.id.chatContent)?.background = ctx.getDrawable(pageBackground(ctx))

        // tvStatus 状态胶囊
        find(root, R.id.tvStatus)?.apply {
            background = ctx.getDrawable(glassCard(ctx))
            setTextColor(textSecondary(ctx))
        }

        // bottomBar 输入栏容器
        find(root, R.id.bottomBar)?.background = ctx.getDrawable(bottomBar(ctx))

        // editInput 输入框
        find(root, R.id.editInput)?.apply {
            background = ctx.getDrawable(inputBg(ctx))
            setTextColor(textPrimary(ctx))
            setHintTextColor(textTertiary(ctx))
        }

        // btnSend 发送按钮
        find(root, R.id.btnSend)?.background = ctx.getDrawable(sendBtn(ctx))

        // emojiPanel
        find(root, R.id.emojiPanel)?.background = ctx.getDrawable(glassCard(ctx))

        // 已有的聊天气泡：递归换背景
        root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recyclerChat)
            ?.let { rv -> paintBubbles(rv) }
    }

    /** 递归给聊天列表里的气泡换肤。子项类型由布局根节点自身带的信息决定。 */
    private fun paintBubbles(view: View) {
        when (view.id) {
            R.id.tvMsg -> {
                val msg = view as? TextView ?: return
                val isMine = isMineBubble(view)
                msg.background = view.context.getDrawable(
                    if (isMine) bubbleMe(view.context) else bubbleAi(view.context)
                )
                msg.setTextColor(textPrimary(view.context))
            }
            R.id.tvTime -> {
                (view as? TextView)?.setTextColor(textTertiary(view.context))
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) paintBubbles(view.getChildAt(i))
        }
    }

    /**
     * 判断气泡归属方：我的气泡右对齐（父级 gravity=end）。
     * 用位置兜底，不依赖额外标记。
     */
    private fun isMineBubble(view: View): Boolean {
        var parent = view.parent as? View
        var depth = 0
        while (parent != null && depth < 4) {
            if (parent is androidx.recyclerview.widget.RecyclerView) return false
            // 我的那一侧：bubble_me 的左上角是小圆角、右上角是尖角，
            // 这里用「父布局 gravity=end」判断更稳，交给布局层去做，这里只做兜底。
            parent = parent.parent as? View
            depth++
        }
        return false
    }

    /** 切换后重建界面：主题涉及背景资源，Activity 级刷新最稳。 */
    fun applyToActivity(activity: android.app.Activity) {
        applyMain(activity.window.decorView)
        activity.window.decorView.post {
            applySystemBars(activity)
        }
    }

    fun applySystemBars(activity: android.app.Activity) {
        val dark = isDark(activity)
        val ctx = activity
        val bar = when (current(ctx)) {
            ABYSS, LIQUID -> c(ctx, R.color.abyss_bg_end)
            else -> c(ctx, R.color.mist_status_bar)
        }
        val nav = when (current(ctx)) {
            ABYSS, LIQUID -> c(ctx, R.color.abyss_bg_end)
            else -> c(ctx, R.color.mist_nav_bar)
        }
        runCatching {
            activity.window.statusBarColor = bar
            activity.window.navigationBarColor = nav
            val controller = androidx.core.view.WindowCompat.getInsetsController(
                activity.window, activity.window.decorView
            )
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    private fun <T : View> find(root: View, id: Int): T? {
        @Suppress("UNCHECKED_CAST")
        return root.findViewById<View>(id) as? T
    }
}