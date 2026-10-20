package com.suze.aivoice

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 界面主题管理器：三版配色方案的运行时切换中枢。
 *
 * 三套方案的 drawable / color 全部打包在 APK 里，用独立前缀互不覆盖：
 *   A 深海蓝夜      abyss_  前缀 + colors_abyss.xml
 *   B 晨雾浅蓝      mist_   前缀 + colors_mist.xml
 *   C 蓝调液态玻璃  liquid_ 前缀 + colors_liquid.xml
 *
 * 为什么不用 Theme 属性覆盖：
 * 主聊天页和气泡背景是把 @drawable/xxx 写死在 XML 里的，换 Theme 属性并不会换掉这些引用。
 * 所以这里走「显式重刷」——拿到 View 按当前主题直接设背景、设文字色、设图标。
 *
 * 用法：
 *   UiThemeManager.apply(this, findViewById(R.id.chatScene))   // Activity onCreate 里调
 *   UiThemeManager.applyBubble(ctx, tvMsg, isMe)               // ChatAdapter 绑定气泡时调
 */
object UiThemeManager {

    /** 全部合法主题 id。别处判断合法性一律用这个集合，别硬编码字符串。 */
    val THEMES = setOf("A", "B", "C")

    /** 设置面板 Spinner 的展示顺序，必须与 [specs] 的项一一对应。 */
    val THEME_ORDER = listOf("A", "B", "C")

    /**
     * 一套主题的全部视觉映射。
     *
     * @param label 设置面板里给主人看的名字
     * @param dark 深底主题为 true，浅底为 false。决定系统栏图标明暗。
     */
    data class Spec(
        val label: String,
        val pageBg: Int,
        val bubbleMe: Int,
        val bubbleAi: Int,
        val card: Int,
        val bottomBar: Int,
        val inputBg: Int,
        val sendBtn: Int,
        val header: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textTertiary: Int,
        val textOnAccent: Int,
        val accent: Int,
        val statusBar: Int,
        val dark: Boolean
    )

    private val specs: Map<String, Spec> = mapOf(
        // A 深海蓝夜：夜间高对比，青蓝辉光，气泡内发光
        "A" to Spec(
            label = "深海蓝夜",
            pageBg = R.drawable.abyss_page_bg,
            bubbleMe = R.drawable.abyss_bubble_me,
            bubbleAi = R.drawable.abyss_bubble_ai,
            card = R.drawable.abyss_card,
            bottomBar = R.drawable.abyss_bottom_bar,
            inputBg = R.drawable.abyss_input_bg,
            sendBtn = R.drawable.abyss_send_btn,
            header = R.drawable.abyss_header,
            textPrimary = R.color.abyss_text_primary,
            textSecondary = R.color.abyss_text_secondary,
            textTertiary = R.color.abyss_text_tertiary,
            textOnAccent = R.color.abyss_text_on_accent,
            accent = R.color.abyss_accent,
            statusBar = R.color.abyss_status_bar,
            dark = true
        ),
        // B 晨雾浅蓝：清透玻璃、大量留白、柔和蓝白
        "B" to Spec(
            label = "晨雾浅蓝",
            pageBg = R.drawable.mist_page_bg,
            bubbleMe = R.drawable.mist_bubble_me,
            bubbleAi = R.drawable.mist_bubble_ai,
            card = R.drawable.mist_card,
            bottomBar = R.drawable.mist_bottom_bar,
            inputBg = R.drawable.mist_input_bg,
            sendBtn = R.drawable.mist_send_btn,
            header = R.drawable.mist_header,
            textPrimary = R.color.mist_text_primary,
            textSecondary = R.color.mist_text_secondary,
            textTertiary = R.color.mist_text_tertiary,
            textOnAccent = R.color.mist_text_on_accent,
            accent = R.color.mist_accent,
            statusBar = R.color.mist_status_bar,
            dark = false
        ),
        // C 蓝调液态玻璃：极光泛光、虹彩折射、半透明玻璃边缘
        "C" to Spec(
            label = "蓝调液态玻璃",
            pageBg = R.drawable.liquid_page_bg,
            bubbleMe = R.drawable.liquid_bubble_me,
            bubbleAi = R.drawable.liquid_bubble_ai,
            card = R.drawable.liquid_card,
            bottomBar = R.drawable.liquid_bottom_bar,
            inputBg = R.drawable.liquid_input_bg,
            sendBtn = R.drawable.liquid_send_btn,
            header = R.drawable.liquid_header,
            textPrimary = R.color.liquid_text_primary,
            textSecondary = R.color.liquid_text_secondary,
            textTertiary = R.color.liquid_text_tertiary,
            textOnAccent = R.color.liquid_text_on_accent,
            accent = R.color.liquid_accent,
            statusBar = R.color.liquid_status_bar,
            dark = true
        )
    )

    /** 把任意输入规整成合法主题 id：认不出来的兜回默认，绝不返回垃圾值。 */
    fun normalize(ctx: Context, theme: String?): String =
        if (theme != null && theme in THEMES) theme else Prefs(ctx).uiTheme

    /** 取指定主题的映射；主题 id 不认识就退回默认主题，绝不崩。 */
    fun spec(ctx: Context, theme: String? = null): Spec =
        specs[normalize(ctx, theme)] ?: specs[Prefs.DEFAULT_UI_THEME]!!

    /** 当前主题 id。 */
    fun current(ctx: Context): String = Prefs(ctx).uiTheme

    /** 设置面板下拉要显示的名字，按 [THEME_ORDER] 顺序。 */
    fun labels(): List<String> = THEME_ORDER.map { specs[it]!!.label }

    /** Spinner 选中位置 -> 主题 id。越界位置兜回当前主题。 */
    fun themeOfPosition(ctx: Context, position: Int): String =
        if (position in THEME_ORDER.indices) THEME_ORDER[position] else current(ctx)

    /** 主题 id -> Spinner 应显示的位置。 */
    fun positionOfTheme(ctx: Context, theme: String? = null): Int {
        val idx = THEME_ORDER.indexOf(normalize(ctx, theme))
        return if (idx >= 0) idx else THEME_ORDER.indexOf(Prefs.DEFAULT_UI_THEME)
    }

    /**
     * 给一个 View 树刷主题。主聊天页在 onCreate 里调一次即可。
     *
     * 刻意不做「递归遍历所有 View 乱改背景」——那会把 RecyclerView item、
     * 表情面板、小手机屏幕这些自带专属背景的控件一起刷坏。
     * 这里只处理认得出来、且确实该跟主题走的控件，其余原样保留。
     */
    fun apply(ctx: Context, root: View?) {
        val r = root ?: return
        val s = spec(ctx)
        // 页面底层
        r.setBackgroundResource(s.pageBg)
        // chatContent 是 chatScene 里的实际内容容器，布局里写死了 mist_page_bg。
        // 不刷这一层，深色主题下聊天区会一直露出 B 版的浅蓝底。
        r.findViewById<View>(R.id.chatContent)?.setBackgroundResource(s.pageBg)

        // 文字：按 id 语义刷，不动内容
        tintPrimary(ctx, r.findViewById(R.id.tvStatus))
        tintPrimary(ctx, r.findViewById(R.id.editInput))
        r.findViewById<TextView>(R.id.editInput)
            ?.setHintTextColor(alpha(color(ctx, s.textTertiary), 0.6f))
        tintTertiary(ctx, r.findViewById(R.id.tvTime))
        tintAccent(ctx, r.findViewById(R.id.tvSpeaker))
        tintPrimary(ctx, r.findViewById(R.id.tvMsg))

        // 底部输入栏 / 输入框 / 发送键
        r.findViewById<View>(R.id.bottomGlass)?.setBackgroundResource(s.bottomBar)
        r.findViewById<View>(R.id.bottomBar)?.setBackgroundResource(s.bottomBar)
        r.findViewById<View>(R.id.editInput)?.setBackgroundResource(s.inputBg)
        r.findViewById<View>(R.id.btnSend)?.setBackgroundResource(s.sendBtn)
        // 状态条卡片布局里写死了 mist_card，跟着主题走
        r.findViewById<View>(R.id.tvStatus)?.setBackgroundResource(s.card)


        // 状态栏跟随主题
        (ctx as? Activity)?.let { paintSystemBars(it, s) }
    }

    /**
     * 聊天气泡专用。ChatAdapter 绑定每条消息时调。
     * 按 isMe 取对应背景，避免整屏被刷成同一个。
     *
     * 我的气泡是实心青蓝，文字必须反白；小沫气泡跟页面底一致，用主色。
     */
    fun applyBubble(ctx: Context, view: TextView?, isMe: Boolean) {
        val v = view ?: return
        val s = spec(ctx)
        v.setBackgroundResource(if (isMe) s.bubbleMe else s.bubbleAi)
        v.setTextColor(color(ctx, if (isMe) s.textOnAccent else s.textPrimary))
    }

    /**
 * 整页主题入口：给那些走 GlassKit 玻璃层的独立页面（天气 / 记忆 / 情感 / 思维 /
 * 待办 / 提醒 / 日历 / 语音克隆 / 大模型接入 / 语音通话 / 虚拟小手机）用。
 *
 * 这些页面没有主聊天页那套固定 id，所以按「职责」刷而不是按 id 硬找：
 * 页面滚动容器换卡片底、顶栏换 header、深底主题下正文统一主色。
 * 找不到对应 id 就跳过，绝不因此崩。
 *
 * @param root 布局根 View（setContentView 的最外层）
 */
fun applyPage(ctx: Context, root: View?) {
    val r = root ?: return
    val s = spec(ctx)

    // 页面底：只在根容器自己没有背景时铺，避免盖掉 GlassKit 自己画的那层玻璃
    if (r.background == null) {
        r.setBackgroundResource(s.pageBg)
    }

    // 页面滚动容器 / 顶栏：换当前主题的卡片与顶栏
    r.findViewById<View>(R.id.pageScroll)?.setBackgroundResource(s.card)
    r.findViewById<View>(R.id.pageContent)?.setBackgroundResource(s.card)
    r.findViewById<View>(R.id.headerBar)?.setBackgroundResource(s.header)

    // 深底主题下，原布局配的深色文字会糊在深蓝底里看不见，统一刷成亮色主色。
    // 浅色主题不刷：那里布局自己的主色/次色/三级色层次是正确的，抹平反而难看。
    if (s.dark) {
        tintAllText(r, color(ctx, s.textPrimary))
    }
}

/**
 * 递归把 TextView 刷成主色（深底主题专用）。
 *
 * EditText 特殊处理：只换文字色，背景和光标保持原样，
 * 否则主人正在输入的内容框会突然变成一整块卡片色。
 */
private fun tintAllText(view: View, colorInt: Int) {
    if (view is TextView) {
        try {
            view.setTextColor(colorInt)
        } catch (_: Throwable) {
        }
        // 输入框另外刷提示色，否则深底上原来的深灰 hint 看不见
        if (view is android.widget.EditText) {
            try {
                view.setHintTextColor(colorInt and 0x60FFFFFF)
            } catch (_: Throwable) {
            }
        }
    }
    if (view is android.view.ViewGroup) {
        val n = view.childCount
        for (i in 0 until n) {
            try {
                tintAllText(view.getChildAt(i), colorInt)
            } catch (_: Throwable) {
            }
        }
    }
}

/** 卡片类控件（设置项、酒馆条目、群聊条目）套当前主题。 */
fun applyCard(ctx: Context, view: View?) {
    view?.setBackgroundResource(spec(ctx).card)
}

    /** 主色文字。 */
    fun tintPrimary(ctx: Context, view: TextView?) {
        view?.setTextColor(color(ctx, spec(ctx).textPrimary))
    }

    /** 三级文字（时间戳等）。 */
    fun tintTertiary(ctx: Context, view: TextView?) {
        view?.setTextColor(color(ctx, spec(ctx).textTertiary))
    }

    /** 强调色文字（发言人名、选中态标签）。 */
    fun tintAccent(ctx: Context, view: TextView?) {
        view?.setTextColor(color(ctx, spec(ctx).accent))
    }

    /** 次级文字。 */
    fun tintSecondary(ctx: Context, view: TextView?) {
        view?.setTextColor(color(ctx, spec(ctx).textSecondary))
    }

    /**
     * 图标染色：深底主题要把深色图标换成亮色，否则看不清。
     * 少数自定义 View 不支持着色，吞掉异常——不能因为换主题把界面搞崩。
     */
    fun tintIcon(ctx: Context, iv: ImageView?) {
        val v = iv ?: return
        val s = spec(ctx)
        try {
            v.setColorFilter(color(ctx, if (s.dark) s.textPrimary else s.textSecondary))
        } catch (_: Throwable) {
        }
    }

    /**
     * 系统栏：状态栏与导航栏底色跟随主题；
     * 深底主题用浅色图标，浅底主题用深色图标，否则文字压在底色上看不清。
     */
    fun paintSystemBars(activity: Activity, s: Spec = spec(activity)) {
        val win = activity.window
        val bar = color(activity, s.statusBar)
        val wantLightBars = !s.dark
        val flags = win.decorView.systemUiVisibility
        var next = flags
        next = if (wantLightBars) {
            next or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            next and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        next = if (wantLightBars) {
            next or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } else {
            next and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        }
        if (next != flags) win.decorView.systemUiVisibility = next
        try {
            win.statusBarColor = bar
            win.navigationBarColor = bar
        } catch (_: Throwable) {
            // Android 15+ 部分系统禁止改状态栏色，忽略即可
        }
    }

    /** 取色：走 ContextCompat，保证 night 限定符正确生效。取不到就给灰色兜底。 */
    private fun color(ctx: Context, id: Int): Int = try {
        ContextCompat.getColor(ctx, id)
    } catch (_: Throwable) {
        Color.GRAY
    }

    private fun alpha(color: Int, factor: Float): Int {
        val a = (Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}