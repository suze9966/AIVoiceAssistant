package com.suze.aivoice

import android.content.Intent
import android.graphics.PixelFormat
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 小沫的虚拟小手机。默认就是可拖、可缩放的小窗，也能钉到其他应用上层。 */
class PocketPhoneActivity : AppCompatActivity() {

    private lateinit var phone: PocketPhone
    private lateinit var llm: LlmClient
    private lateinit var screen: TextView
    private lateinit var status: TextView
    private lateinit var title: TextView
    private lateinit var input: EditText
    private lateinit var dock: LinearLayout
    private lateinit var ops: TextView
    private lateinit var pinBtn: TextView
    private var current: PocketPhone.App? = null
    private var playing = false
    private var overlay = false
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var dragX = 0
    private var dragY = 0
    private var startX = 0f
    private var startY = 0f
    private var sizeW = 0
    private var sizeH = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pocket)
        phone = PocketPhone(this)
        llm = LlmClient(Prefs(this))
        screen = findViewById(R.id.tvPocketScreen)
        status = findViewById(R.id.tvPocketStatus)
        title = findViewById(R.id.tvPocketTitle)
        input = findViewById(R.id.editPocket)
        dock = findViewById(R.id.appDockRow)
        ops = findViewById(R.id.tvPocketOps)
        pinBtn = findViewById(R.id.btnPocketPin)
        findViewById<TextView>(R.id.btnPocketBack).setOnClickListener {
            if (overlay) {
                closeOverlay()
            } else if (current != null) {
                showHome()
            } else {
                finish()
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
        buildDock()
        showHome()
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
            textSize = 12f
            setTextColor(getColor(R.color.purple_700))
            setPadding(16, 4, 16, 4)
            setOnClickListener { click() }
        }
    }

    override fun onDestroy() {
        removeOverlay()
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
                dragX = lp.x
                dragY = lp.y
                startX = ev.rawX
                startY = ev.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                lp.x = dragX + (ev.rawX - startX).toInt()
                lp.y = dragY + (ev.rawY - startY).toInt()
                applyLayout(lp)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return false
        }
        return false
    }

    private fun resizeWindow(ev: MotionEvent): Boolean {
        val lp = currentLp() ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sizeW = if (lp.width > 0) lp.width else window.decorView.width
                sizeH = if (lp.height > 0) lp.height else window.decorView.height
                startX = ev.rawX
                startY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dm = resources.displayMetrics
                lp.width = (sizeW + (ev.rawX - startX).toInt()).coerceIn(minW(), dm.widthPixels)
                lp.height = (sizeH + (ev.rawY - startY).toInt()).coerceIn(minH(), dm.heightPixels)
                applyLayout(lp)
            }
        }
        return true
    }

    private fun scaleBy(factor: Float) {
        val lp = currentLp() ?: return
        val dm = resources.displayMetrics
        val w = if (lp.width > 0) lp.width else window.decorView.width
        val h = if (lp.height > 0) lp.height else window.decorView.height
        lp.width = (w * factor).toInt().coerceIn(minW(), dm.widthPixels)
        lp.height = (h * factor).toInt().coerceIn(minH(), dm.heightPixels)
        applyLayout(lp)
    }

    private fun minW(): Int = (resources.displayMetrics.widthPixels * 0.42f).toInt()

    private fun minH(): Int = (resources.displayMetrics.heightPixels * 0.32f).toInt()

    private fun applyLayout(lp: WindowManager.LayoutParams) {
        if (overlay) {
            overlayView?.let { runCatching { wm().updateViewLayout(it, lp) } }
        } else {
            window.attributes = lp
        }
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
        runCatching { wm().addView(root, lp) }.onFailure {
            (findViewById<View>(android.R.id.content) as? ViewGroup)?.addView(root)
            Toast.makeText(this, R.string.pocket_need_overlay, Toast.LENGTH_LONG).show()
            return
        }
        overlayView = root
        overlayParams = lp
        overlay = true
        pinBtn.text = getString(R.string.pocket_unpin)
        moveTaskToBack(true)
        Toast.makeText(this, R.string.pocket_pinned, Toast.LENGTH_SHORT).show()
    }

    private fun closeOverlay() {
        val view = overlayView ?: return
        runCatching { wm().removeView(view) }
        overlayView = null
        overlayParams = null
        overlay = false
        pinBtn.text = getString(R.string.pocket_pin)
        val content = findViewById<ViewGroup>(android.R.id.content)
        if (view.parent == null) content.addView(view)
        placeWindow()
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { wm().removeView(view) }
        overlayView = null
        overlayParams = null
        overlay = false
    }

    private fun showOps(lines: List<String>) {
        ops.visibility = View.VISIBLE
        ops.text = lines.joinToString("\n") { "· $it" }
    }

    private fun showHome() {
        current = null
        title.text = getString(R.string.pocket_title)
        status.text = phone.clock() + "  ·  " + phone.battery() + "%"
        screen.typeface = Typeface.MONOSPACE
        screen.text = phone.homeCard()
    }

    private fun showApp(app: PocketPhone.App, extra: String = "") {
        current = app
        title.text = app.icon + " " + app.name
        status.text = phone.clock()
        screen.typeface = Typeface.MONOSPACE
        screen.text = phone.screenFor(app, extra)
    }

    private fun openByWish(wish: String) {
        val hit = phone.apps().firstOrNull { wish.contains(it.name) }
        if (hit != null) showApp(hit, wish) else play(wish)
    }

    private fun play(wish: String) {
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
            showApp(app, text)
            shown.add(steps.last())
            showOps(shown.takeLast(4))
            status.text = phone.clock()
        }
    }
}