package com.suze.aivoice

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

/** 酒馆各页跟小手机用同一个浮动窗，打开、返回都留在这个窗口里。 */
open class PocketHostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        placePhoneWindow()
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        val root = findViewById<View>(android.R.id.content) ?: return
        val header = root.findViewById<View>(R.id.headerBar) ?: return
        if (header.findViewWithTag<View>("pocket_size") != null) return
        header.setOnTouchListener { v, ev -> dragPhone(v, ev) }
        val smaller = phoneButton("−") { scalePhone(0.88f) }
        val bigger = phoneButton("+") { scalePhone(1.12f) }
        smaller.tag = "pocket_size"
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            addView(smaller)
            addView(bigger)
        }
        if (header is android.view.ViewGroup) header.addView(row)
    }

    private fun phoneButton(label: String, click: () -> Unit): android.widget.TextView {
        val d = resources.displayMetrics.density
        return android.widget.TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(getColor(R.color.purple_700))
            layoutParams = android.widget.LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt())
            setOnClickListener { click() }
        }
    }

    private fun scalePhone(factor: Float) {
        val dm = resources.displayMetrics
        val lp = window.attributes
        val minW = (dm.widthPixels * 0.42f).toInt()
        val minH = (dm.heightPixels * 0.32f).toInt()
        val maxW = (dm.widthPixels * 0.96f).toInt()
        val maxH = (dm.heightPixels * 0.92f).toInt()
        lp.width = (lp.width * factor).toInt().coerceIn(minW, maxW)
        lp.height = (lp.height * factor).toInt().coerceIn(minH, maxH)
        window.attributes = lp
    }

    override fun startActivity(intent: Intent?) {
        if (intent != null && intent.component?.packageName == packageName) {
            val lp = window.attributes
            if (!intent.hasExtra("pocket_w")) {
                intent.putExtra("pocket_x", lp.x)
                intent.putExtra("pocket_y", lp.y)
                intent.putExtra("pocket_w", lp.width)
                intent.putExtra("pocket_h", lp.height)
            }
        }
        super.startActivity(intent)
    }

    private fun placePhoneWindow() {
        val dm = resources.displayMetrics
        val lp = window.attributes
        val w = intent.getIntExtra("pocket_w", 0).takeIf { it > 0 } ?: (dm.widthPixels * 0.86f).toInt()
        val h = intent.getIntExtra("pocket_h", 0).takeIf { it > 0 } ?: (dm.heightPixels * 0.62f).toInt()
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = intent.getIntExtra("pocket_x", ((dm.widthPixels - w) / 2f).roundToInt())
        lp.y = intent.getIntExtra("pocket_y", (dm.heightPixels * 0.08f).roundToInt())
        window.attributes = lp
    }

    private var dragX = 0f
    private var dragY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false


    private fun touchedControl(host: View, ev: MotionEvent): Boolean {
        if (host !is android.view.ViewGroup) return false
        val x = ev.x
        val y = ev.y
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (!child.isClickable && child !is android.view.ViewGroup) continue
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                if (child.isClickable || child is android.widget.LinearLayout) return true
            }
        }
        return false
    }
    private fun dragPhone(host: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (touchedControl(host, ev)) return false
                dragging = false
                dragX = ev.rawX
                dragY = ev.rawY
                startX = window.attributes.x
                startY = window.attributes.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - dragX
                val dy = ev.rawY - dragY
                if (!dragging && dx * dx + dy * dy < 36f) return true
                dragging = true
                val lp = window.attributes
                lp.x = startX + dx.roundToInt()
                lp.y = startY + dy.roundToInt()
                window.attributes = lp
                return true
            }
            MotionEvent.ACTION_UP -> {
                val was = dragging
                dragging = false
                return was
            }
        }
        return false
    }
}
