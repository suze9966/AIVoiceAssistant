package com.suze.aivoice

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 小沫的虚拟小手机。默认就是可拖、可缩放的小窗，也能钉到其他应用上层。 */
class PocketPhoneActivity : AppCompatActivity(), PocketHost {

    private val pageStack = mutableListOf<PocketPage>()
    private var pendingFile: ((Uri?) -> Unit)? = null

    override val hostActivity: Activity get() = this

    private val pickDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingFile?.invoke(uri)
        pendingFile = null
    }

    private val pickContent = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingFile?.invoke(uri)
        pendingFile = null
    }

    override fun requestFile(mimeTypes: Array<String>, open: Boolean, onResult: (Uri?) -> Unit) {
        pendingFile = onResult
        runCatching {
            if (open) pickDocument.launch(mimeTypes) else pickContent.launch(mimeTypes.first())
        }.onFailure {
            pendingFile = null
            onResult(null)
        }
    }

    /** 酒馆页面压栈：视图直接进小手机容器，不再另开浮动窗。 */
    override fun openPage(intent: Intent) {
        val name = intent.component?.className ?: return
        val page = PocketPageMap.create(name, this, intent) ?: run {
            startActivity(intent)
            return
        }
        pageStack.lastOrNull()?.onPagePause()
        page.onPageCreate()
        pageStack.add(page)
        val host = findViewById<ViewGroup>(R.id.pocketPageHost) ?: return
        host.removeAllViews()
        host.addView(page.root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        host.visibility = View.VISIBLE
        hidePhoneBody()
        page.onPageResume()
        page.root.alpha = 0.85f
        page.root.animate().alpha(1f).setDuration(140).start()
    }

    /** 酒馆页面出栈：栈空才回小手机桌面。 */
    override fun closePage() {
        if (pageStack.isEmpty()) return
        val page = pageStack.removeAt(pageStack.lastIndex)
        page.markClosed()
        page.onPagePause()
        page.cancelPageJobs()
        page.onPageDestroy()
        val prev = pageStack.lastOrNull()
        val host = findViewById<ViewGroup>(R.id.pocketPageHost)
        if (prev != null) {
            host?.removeAllViews()
            host?.addView(prev.root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            host?.visibility = View.VISIBLE
            prev.onPageResume()
        } else {
            host?.removeAllViews()
            host?.visibility = View.GONE
            showPhoneBody()
            showHome()
        }
    }

    /**
     * 酒馆页面盖住小手机桌面时，把桌面正文、应用栏和输入区收起来，
     * 只留顶栏和缩放把手，小窗仍可拖动和放大缩小。
     */
    private fun hidePhoneBody() {
        findViewById<View>(R.id.pocketScroll)?.visibility = View.GONE
        findViewById<View>(R.id.appDock)?.visibility = View.GONE
        findViewById<View>(R.id.bottomBar)?.visibility = View.GONE
        ops.visibility = View.GONE
    }

    /** 退出酒馆后把小手机桌面放回来，播放条按当前应用恢复。 */
    private fun showPhoneBody() {
        findViewById<View>(R.id.pocketScroll)?.visibility = View.VISIBLE
        findViewById<View>(R.id.appDock)?.visibility = View.VISIBLE
        findViewById<View>(R.id.bottomBar)?.visibility = View.VISIBLE
        playerBar.visibility = if (current?.id == "player") View.VISIBLE else View.GONE
    }

    override fun onBackPressed() {
        if (pageStack.isNotEmpty()) {
            closePage()
            return
        }
        super.onBackPressed()
    }

    private lateinit var phone: PocketPhone
    private lateinit var llm: LlmClient
    private lateinit var screen: TextView
    private lateinit var scene: SceneCardView
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var input: EditText
    private lateinit var dock: LinearLayout
    private lateinit var ops: TextView
    private lateinit var pinBtn: TextView
    private lateinit var playerBar: View
    private lateinit var pauseBtn: TextView
    private lateinit var card: View
    private var current: PocketPhone.App? = null
    private var playing = false
    private var overlay = false
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var dragX = 0
    private var dragY = 0
    private var startX = 0f
    private var startY = 0f
    private var baseW = 0
    private var baseH = 0
    private var pinchScale = 1f
    private var resizing = false
    private var closing = false
    private var music: MediaPlayer? = null
    private var tracks = listOf<PocketMusic.Track>()
    private var trackIndex = 0
    private var musicJob: kotlinx.coroutines.Job? = null
    private var moveAnimator: ValueAnimator? = null
    private var sizeAnimator: ValueAnimator? = null
    private var contentScale = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pocket)
        phone = PocketPhone(this)
        llm = LlmClient(Prefs(this))
        card = findViewById(R.id.pocketRoot)
        screen = findViewById(R.id.tvPocketScreen)
        scene = findViewById(R.id.pocketScene)
        status = findViewById(R.id.tvPocketStatus)
        title = findViewById(R.id.tvPocketTitle)
        input = findViewById(R.id.editPocket)
        dock = findViewById(R.id.appDockRow)
        ops = findViewById(R.id.tvPocketOps)
        pinBtn = findViewById(R.id.btnPocketPin)
        playerBar = findViewById(R.id.pocketPlayerBar)
        pauseBtn = findViewById(R.id.btnPocketPause)
        findViewById<TextView>(R.id.btnPocketPrev).setOnClickListener { stepTrack(-1) }
        pauseBtn.setOnClickListener { toggleMusic() }
        findViewById<TextView>(R.id.btnPocketNext).setOnClickListener { stepTrack(1) }
        findViewById<TextView>(R.id.btnPocketBack).setOnClickListener {
            if (overlay) {
                closeOverlay()
            } else if (current != null) {
                showHome()
            } else {
                closeWithAnim()
            }
        }
        findViewById<TextView>(R.id.btnPocketPlay).setOnClickListener {
            play(input.text?.toString().orEmpty())
        }
        pinBtn.setOnClickListener { pinOverlay() }
        findViewById<TextView>(R.id.btnPocketBigger).setOnClickListener { scaleBy(1.12f) }
        findViewById<TextView>(R.id.btnPocketSmaller).setOnClickListener { scaleBy(0.88f) }
        findViewById<View>(R.id.headerBar).setOnTouchListener { _, ev -> dragWindow(ev) }
        findViewById<View>(R.id.pocketResize).setOnTouchListener { _, ev -> resizeWindow(ev) }
        placeWindow()
        findViewById<Button>(R.id.btnPocketSend).setOnClickListener {
            val text = input.text?.toString().orEmpty()
            input.setText("")
            if (current == null) openByWish(text) else play(text)
        }
        scene.onIconClick = { index ->
            phone.apps().getOrNull(index)?.let { showApp(it) }
        }
        buildDock()
        showHome()
        adaptContent(window.attributes.width, window.attributes.height, force = true)
        playEnter()
    }

    private fun buildDock() {
        dock.removeAllViews()
        dock.addView(chip("\u2302", getString(R.string.pocket_home)) { showHome() })
        phone.apps().forEach { app ->
            dock.addView(chip(app.icon, app.name) { showApp(app) })
        }
    }

    private fun chip(icon: String, name: String, click: () -> Unit): TextView {
        return TextView(this).apply {
            text = "$icon $name"
            gravity = Gravity.CENTER
            textSize = 12f * contentScale
            setTextColor(getColor(R.color.purple_700))
            val pad = (8f * resources.displayMetrics.density * contentScale).roundToInt()
            setPadding(pad, pad / 4, pad, pad / 4)
            setOnClickListener { click() }
        }
    }

    override fun onDestroy() {
        moveAnimator?.cancel()
        sizeAnimator?.cancel()
        musicJob?.cancel()
        stopMusic()
        removeOverlay()
        // 内嵌页面栈一起收掉，别让酒馆页面的定时器和流式任务挂在宿主上
        while (pageStack.isNotEmpty()) {
            val page = pageStack.removeAt(pageStack.lastIndex)
            page.markClosed()
            page.onPagePause()
            page.cancelPageJobs()
            page.onPageDestroy()
        }
        pendingFile = null
        super.onDestroy()
    }

    private fun wm(): WindowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    private fun placeWindow() {
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.86f).toInt()
        val h = (dm.heightPixels * 0.62f).toInt()
        val lp = window.attributes
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = ((dm.widthPixels - w) / 2).coerceAtLeast(0)
        lp.y = (dm.heightPixels * 0.12f).toInt()
        window.attributes = lp
    }

    private fun currentLp(): WindowManager.LayoutParams? {
        return if (overlay) overlayParams else window.attributes
    }

    private fun dragWindow(ev: MotionEvent): Boolean {
        val lp = currentLp() ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                moveAnimator?.cancel()
                dragX = lp.x
                dragY = lp.y
                startX = ev.rawX
                startY = ev.rawY
                card.animate().cancel()
                card.scaleX = 0.985f
                card.scaleY = 0.985f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x = dragX + (ev.rawX - startX).toInt()
                lp.y = dragY + (ev.rawY - startY).toInt()
                applyLayout(lp)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                card.animate().scaleX(1f).scaleY(1f).setDuration(180)
                    .setInterpolator(OvershootInterpolator(1.4f)).start()
                snapToEdge()
                return true
            }
        }
        return false
    }

    private fun resizeWindow(ev: MotionEvent): Boolean {
        val lp = currentLp() ?: return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sizeAnimator?.cancel()
                card.animate().cancel()
                baseW = if (lp.width > 0) lp.width else card.width
                baseH = if (lp.height > 0) lp.height else card.height
                startX = ev.rawX
                startY = ev.rawY
                pinchScale = 1f
                resizing = true
                card.pivotX = card.width / 2f
                card.pivotY = card.height * 0.42f
            }
            MotionEvent.ACTION_MOVE -> {
                if (!resizing || baseW <= 0 || baseH <= 0) return true
                val dm = resources.displayMetrics
                val wantW = (baseW + (ev.rawX - startX)).roundToInt().coerceIn(minW(), dm.widthPixels)
                val wantH = (baseH + (ev.rawY - startY)).roundToInt().coerceIn(minH(), dm.heightPixels)
                lp.width = wantW
                lp.height = wantH
                pinchScale = 1f
                card.scaleX = 1f
                card.scaleY = 1f
                applyLayout(lp)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finishResize(lp)
        }
        return true
    }

    private fun finishResize(lp: WindowManager.LayoutParams) {
        if (!resizing) return
        resizing = false
        card.animate().cancel()
        card.scaleX = 1f
        card.scaleY = 1f
        pinchScale = 1f
        applyLayout(lp)
        adaptContent(lp.width, lp.height, force = true)
    }

    private fun scaleBy(factor: Float) {
        if (resizing) return
        val lp = currentLp() ?: return
        val dm = resources.displayMetrics
        val w = if (lp.width > 0) lp.width else card.width
        val h = if (lp.height > 0) lp.height else card.height
        if (w <= 0 || h <= 0) return
        val targetW = (w * factor).roundToInt().coerceIn(minW(), dm.widthPixels)
        val targetH = (h * factor).roundToInt().coerceIn(minH(), dm.heightPixels)
        card.animate().cancel()
        card.scaleX = 1f
        card.scaleY = 1f
        animateSize(w, h, targetW, targetH)
    }

    private fun animateSize(fromW: Int, fromH: Int, toW: Int, toH: Int) {
        val lp = currentLp() ?: return
        sizeAnimator?.cancel()
        if (fromW == toW && fromH == toH) {
            lp.width = toW
            lp.height = toH
            applyLayout(lp)
            return
        }
        sizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val cur = currentLp() ?: return@addUpdateListener
                cur.width = (fromW + (toW - fromW) * t).roundToInt()
                cur.height = (fromH + (toH - fromH) * t).roundToInt()
                applyLayout(cur)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    val cur = currentLp() ?: return
                    adaptContent(cur.width, cur.height, force = true)
                }
            })
            start()
        }
    }

    private fun snapToEdge() {
        val lp = currentLp() ?: return
        val dm = resources.displayMetrics
        val w = if (lp.width > 0) lp.width else card.width
        val h = if (lp.height > 0) lp.height else card.height
        val maxX = (dm.widthPixels - w).coerceAtLeast(0)
        val maxY = (dm.heightPixels - h - statusReserve()).coerceAtLeast(0)
        val targetX = lp.x.coerceIn(0, maxX)
        val targetY = lp.y.coerceIn(0, maxY)
        if (targetX == lp.x && targetY == lp.y) return
        val fromX = lp.x
        val fromY = lp.y
        moveAnimator?.cancel()
        moveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator(1.8f)
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val cur = currentLp() ?: return@addUpdateListener
                cur.x = (fromX + (targetX - fromX) * t).roundToInt()
                cur.y = (fromY + (targetY - fromY) * t).roundToInt()
                applyLayout(cur)
            }
            start()
        }
    }

    private fun statusReserve(): Int = (28 * resources.displayMetrics.density).roundToInt()

    private fun minW(): Int = (resources.displayMetrics.widthPixels * 0.42f).toInt()

    private fun minH(): Int = (resources.displayMetrics.heightPixels * 0.32f).toInt()

    private fun applyLayout(lp: WindowManager.LayoutParams) {
        if (overlay) {
            overlayView?.let { runCatching { wm().updateViewLayout(it, lp) } }
        } else {
            window.attributes = lp
        }
        adaptContent(lp.width, lp.height)
    }

    /** 窗口变大变小后，屏幕里的字、图标和场景一起变，不再停在原来的尺寸。 */
    private fun adaptContent(width: Int, height: Int, force: Boolean = false) {
        if (!::screen.isInitialized) return
        val w = when {
            width > 0 -> width
            card.width > 0 -> card.width
            else -> return
        }
        val h = when {
            height > 0 -> height
            card.height > 0 -> card.height
            else -> return
        }
        val dm = resources.displayMetrics
        val baseW = dm.widthPixels * 0.86f
        val baseH = dm.heightPixels * 0.62f
        if (baseW <= 0f || baseH <= 0f) return
        val scale = (((w / baseW) + (h / baseH)) / 2f).coerceIn(0.52f, 1.75f)
        if (!force && kotlin.math.abs(scale - contentScale) < 0.04f) return
        contentScale = scale
        val d = dm.density
        fun px(dp: Float) = (dp * d * scale).roundToInt().coerceAtLeast(1)
        fun box(view: View, dpW: Float, dpH: Float) {
            val lp = view.layoutParams ?: return
            lp.width = px(dpW)
            lp.height = px(dpH)
            view.layoutParams = lp
        }
        title.textSize = 16f * scale
        status.textSize = 11f * scale
        screen.textSize = 14f * scale
        screen.setLineSpacing(3f * d * scale, 1f)
        ops.textSize = 11f * scale
        input.textSize = 13f * scale
        input.minHeight = px(40f)
        findViewById<TextView>(R.id.btnPocketBack)?.let {
            it.textSize = 24f * scale
            box(it, 40f, 40f)
        }
        findViewById<TextView>(R.id.btnPocketSmaller)?.let {
            it.textSize = 20f * scale
            box(it, 40f, 40f)
        }
        findViewById<TextView>(R.id.btnPocketBigger)?.let {
            it.textSize = 20f * scale
            box(it, 40f, 40f)
        }
        findViewById<TextView>(R.id.btnPocketPin)?.let {
            it.textSize = 12f * scale
            it.minHeight = px(40f)
            it.setPadding(px(8f), 0, px(8f), 0)
        }
        findViewById<TextView>(R.id.btnPocketPlay)?.let {
            it.textSize = 12f * scale
            it.minHeight = px(40f)
            it.setPadding(px(8f), 0, px(8f), 0)
        }
        findViewById<Button>(R.id.btnPocketSend)?.let {
            it.textSize = 13f * scale
            box(it, 52f, 40f)
        }
        listOf(R.id.btnPocketPrev, R.id.btnPocketPause, R.id.btnPocketNext).forEach { id ->
            findViewById<TextView>(id)?.let {
                it.textSize = 12f * scale
                box(it, 64f, 36f)
            }
        }
        findViewById<View>(R.id.headerBar)?.setPadding(px(10f), px(8f), px(8f), px(8f))
        findViewById<View>(R.id.pocketScroll)?.let { scroll ->
            (scroll.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.topMargin = px(58f)
                it.bottomMargin = px(132f)
                scroll.layoutParams = it
            }
        }
        (screen.parent as? View)?.setPadding(px(10f), px(6f), px(10f), px(8f))
        (ops.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.marginStart = px(8f)
            it.marginEnd = px(44f)
            it.bottomMargin = px(132f)
            ops.layoutParams = it
        }
        findViewById<View>(R.id.appDock)?.let { dockBar ->
            (dockBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                it.height = px(44f)
                it.bottomMargin = px(84f)
                dockBar.layoutParams = it
            }
        }
        findViewById<View>(R.id.bottomBar)?.let { bar ->
            bar.setPadding(px(8f), px(4f), px(36f), px(8f))
        }
        findViewById<View>(R.id.pocketResize)?.let { handle ->
            handle.setPadding(0, 0, 0, 0)
            box(handle, 44f, 44f)
            (handle as? TextView)?.textSize = 16f * scale
        }
        val chipPad = px(8f)
        for (i in 0 until dock.childCount) {
            val chip = dock.getChildAt(i) as? TextView ?: continue
            chip.textSize = 12f * scale
            chip.setPadding(chipPad, chipPad / 4, chipPad, chipPad / 4)
        }
        scene.setUiScale(scale)
    }

    private fun playEnter() {
        card.pivotX = resources.displayMetrics.widthPixels * 0.43f
        card.pivotY = resources.displayMetrics.heightPixels * 0.26f
        card.scaleX = 0.86f
        card.scaleY = 0.86f
        card.alpha = 0.55f
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(280)
            .setInterpolator(OvershootInterpolator(1.05f)).start()
    }

    private fun closeWithAnim() {
        if (closing) return
        closing = true
        card.animate().cancel()
        card.pivotX = card.width / 2f
        card.pivotY = card.height * 0.42f
        card.animate().scaleX(0.82f).scaleY(0.82f).alpha(0f).setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finish()
                }
            }).start()
    }

    private fun pinOverlay() {
        if (overlay) {
            closeOverlay()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.pocket_need_overlay, Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        val root = findViewById<View>(R.id.pocketRoot)
        (root.parent as? ViewGroup)?.removeView(root)
        val dm = resources.displayMetrics
        val lp = WindowManager.LayoutParams(
            window.attributes.width.takeIf { it > 0 } ?: (dm.widthPixels * 0.86f).toInt(),
            window.attributes.height.takeIf { it > 0 } ?: (dm.heightPixels * 0.62f).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = window.attributes.x
        lp.y = window.attributes.y
        root.scaleX = 0.92f
        root.scaleY = 0.92f
        root.alpha = 0.7f
        runCatching { wm().addView(root, lp) }.onFailure {
            root.scaleX = 1f
            root.scaleY = 1f
            root.alpha = 1f
            (findViewById<View>(android.R.id.content) as? ViewGroup)?.addView(root)
            Toast.makeText(this, R.string.pocket_need_overlay, Toast.LENGTH_LONG).show()
            return
        }
        overlayView = root
        overlayParams = lp
        overlay = true
        pinBtn.text = getString(R.string.pocket_unpin)
        root.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220)
            .setInterpolator(OvershootInterpolator(1.1f)).start()
        moveTaskToBack(true)
        Toast.makeText(this, R.string.pocket_pinned, Toast.LENGTH_SHORT).show()
    }

    private fun closeOverlay() {
        val view = overlayView ?: return
        view.animate().cancel()
        view.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.4f).setDuration(140).withEndAction {
            runCatching { wm().removeView(view) }
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
            overlayView = null
            overlayParams = null
            overlay = false
            pinBtn.text = getString(R.string.pocket_pin)
            val content = findViewById<ViewGroup>(android.R.id.content)
            if (view.parent == null) content.addView(view)
            placeWindow()
            playEnter()
        }.start()
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        view.animate().cancel()
        runCatching { wm().removeView(view) }
        overlayView = null
        overlayParams = null
        overlay = false
    }

    private fun showOps(lines: List<String>) {
        ops.visibility = View.VISIBLE
        ops.text = lines.joinToString("\n") { "· $it" }
    }

    /**
     * 酒馆不再另开浮动窗，改成小手机内部的应用页面。
     * 内嵌页面走页面栈，其余仍按小窗坐标另开。
     */
    private fun openInside(intent: Intent) {
        if (PocketPageMap.isInnerPage(intent)) {
            openPage(intent)
            return
        }
        val lp = window.attributes
        intent.putExtra("pocket_x", lp.x)
        intent.putExtra("pocket_y", lp.y)
        intent.putExtra("pocket_w", lp.width)
        intent.putExtra("pocket_h", lp.height)
        startActivity(intent)
    }

    /** 酒馆页面开着时，桌面和底栏收起来，返回键先退页面。 */
    private fun showHome() {
        if (pageStack.isNotEmpty()) return
        current = null
        title.text = getString(R.string.pocket_title)
        status.text = phone.clock() + "  ·  " + phone.battery() + "%"
        screen.typeface = Typeface.DEFAULT
        screen.text = ""
        scene.bind(phone.homeScene())
        playerBar.visibility = View.GONE
        stopMusic()
    }

    private fun showApp(app: PocketPhone.App, extra: String = "", remember: Boolean = true) {
        if (app.id == "tavern") {
            openInside(Intent(this, RoleLoungePage::class.java))
            return
        }
        current = app
        title.text = app.icon + " " + app.name
        status.text = phone.clock()
        screen.typeface = Typeface.DEFAULT
        scene.bind(phone.sceneFor(app, extra))
        screen.text = phone.screenFor(app, extra)
        phone.noteOpen(app)
        if (app.id == "player") {
            playerBar.visibility = View.VISIBLE
            if (tracks.isEmpty()) startMusic(extra)
        } else {
            playerBar.visibility = View.GONE
            stopMusic()
        }
        if (!remember) return
        val opened = "我在虚拟小手机里打开了「" + app.name + "」"
        MemoryEngine(this).rememberPocket(opened)
        if (Prefs(this).mindEnabled) MindEngine(this).record(opened)
    }

    private fun startMusic(wish: String) {
        musicJob?.cancel()
        status.text = getString(R.string.pocket_music_loading)
        musicJob = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { PocketMusic.search(wish) }.getOrDefault(emptyList()) }
            if (isDestroyed) return@launch
            if (current?.id != "player") return@launch
            tracks = found
            trackIndex = 0
            if (found.isEmpty()) {
                status.text = getString(R.string.pocket_music_empty)
                return@launch
            }
            playTrack(0)
        }
    }

    private fun playTrack(index: Int) {
        val list = tracks
        if (list.isEmpty()) return
        val i = ((index % list.size) + list.size) % list.size
        trackIndex = i
        val track = list[i]
        stopMusic()
        status.text = getString(R.string.pocket_music_loading)
        val player = MediaPlayer()
        music = player
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        player.setOnPreparedListener {
            if (music !== player || isDestroyed) return@setOnPreparedListener
            it.start()
            pauseBtn.text = "暂停"
            showNowPlaying(track)
        }
        player.setOnCompletionListener {
            if (music !== player) return@setOnCompletionListener
            if (track.live) showNowPlaying(track) else stepTrack(1)
        }
        player.setOnErrorListener { mp, _, _ ->
            if (music === mp) {
                music = null
                runCatching { mp.release() }
                status.text = getString(R.string.pocket_music_fail)
                val next = trackIndex + 1
                if (next < tracks.size) playTrack(next)
            }
            true
        }
        runCatching { player.setDataSource(track.url); player.prepareAsync() }.onFailure {
            status.text = getString(R.string.pocket_music_fail)
        }
    }

    private fun showNowPlaying(track: PocketMusic.Track) {
        val kind = if (track.live) "电台" else "试听"
        val line = track.title + "\n" + track.artist + "  ·  " + kind
        scene.bind(SceneCardView.Model("player", "正在听", listOf(track.title, track.artist, kind)))
        screen.text = "正在听\n" + line + "\n" + getString(R.string.pocket_music_playing)
        status.text = track.title
        val app = current
        if (app != null) {
            MemoryEngine(this).rememberPocket("我在虚拟小手机的播放器里正在听「" + track.title + "」")
        }
    }

    private fun stepTrack(delta: Int) {
        if (tracks.isEmpty()) {
            startMusic("")
            return
        }
        playTrack(trackIndex + delta)
    }

    private fun toggleMusic() {
        val player = music
        if (player == null) {
            if (tracks.isEmpty()) startMusic("") else playTrack(trackIndex)
            return
        }
        if (player.isPlaying) {
            player.pause()
            pauseBtn.text = "播放"
            status.text = getString(R.string.pocket_music_paused)
        } else {
            player.start()
            pauseBtn.text = "暂停"
            status.text = tracks.getOrNull(trackIndex)?.title ?: getString(R.string.pocket_music_playing)
        }
    }

    private fun stopMusic() {
        val player = music ?: return
        music = null
        runCatching { player.stop() }
        runCatching { player.release() }
    }

    private fun openByWish(wish: String) {
        val hit = phone.apps().firstOrNull { wish.contains(it.name) || (it.id == "tavern" && (wish.contains("角色酒馆") || wish.contains("角色聊天"))) }
        if (hit != null) showApp(hit, wish) else play(wish)
    }

    private fun play(wish: String) {
        if (current?.id == "player") {
            tracks = emptyList()
            startMusic(wish)
            return
        }
        if (playing) {
            Toast.makeText(this, R.string.pocket_busy, Toast.LENGTH_SHORT).show()
            return
        }
        val app = current
            ?: phone.apps().firstOrNull { wish.contains(it.name) }
            ?: phone.apps().first()
        playing = true
        status.text = getString(R.string.pocket_playing)
        current = app
        title.text = app.icon + " " + app.name
        val steps = phone.operationSteps(app, wish)
        lifecycleScope.launch {
            val shown = mutableListOf<String>()
            for (step in steps.dropLast(1)) {
                if (isDestroyed) return@launch
                shown.add(step)
                showOps(shown.takeLast(4))
                status.text = step
                delay(650)
            }
            val reply = try {
                llm.chat(phone.playPrompt(app, wish))
            } catch (_: Exception) {
                ""
            }
            playing = false
            if (isDestroyed) return@launch
            val text = reply.trim().ifBlank { getString(R.string.pocket_empty) }
            phone.addFeed(app.id, app.name, text)
            val glimpse = phone.readable(text).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            val played = "我在虚拟小手机的「" + app.name + "」里玩了一下" +
                if (glimpse.isBlank()) "。" else "，看到：" + glimpse.take(40)
            MemoryEngine(this@PocketPhoneActivity).rememberPocket(played)
            if (Prefs(this@PocketPhoneActivity).mindEnabled) {
                MindEngine(this@PocketPhoneActivity).record(played)
            }
            showApp(app, text, remember = false)
            shown.add(steps.last())
            showOps(shown.takeLast(4))
            status.text = phone.clock()
        }
    }
}
