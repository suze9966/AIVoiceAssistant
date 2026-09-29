package com.suze.aivoice

import android.view.View
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView

/** 把顶栏 / 底栏接到真模糊玻璃上，并让列表从玻璃下面滚过。 */
object GlassKit {
    fun attach(
        scene: View,
        scroller: View,
        header: View,
        bottom: View?,
        headerGlass: LiquidGlassView,
        bottomGlass: LiquidGlassView?
    ) {
        headerGlass.headerStyle()
        headerGlass.bind(scene)
        bottomGlass?.footerStyle()
        bottomGlass?.bind(scene)
        val extra = (8f * scroller.resources.displayMetrics.density).toInt()
        val applyPad = Runnable {
            val top = header.height
            val bot = bottom?.height ?: 0
            matchHeight(headerGlass, top)
            if (bottomGlass != null) matchHeight(bottomGlass, bot)
            val wantTop = top + extra
            val wantBot = bot + extra
            if (scroller.paddingTop != wantTop || scroller.paddingBottom != wantBot) {
                scroller.setPadding(scroller.paddingLeft, wantTop, scroller.paddingRight, wantBot)
            }
            headerGlass.invalidate()
            bottomGlass?.invalidate()
        }
        header.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPad.run() }
        bottom?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyPad.run() }
        scroller.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            headerGlass.invalidate()
            bottomGlass?.invalidate()
        }
        val redraw = ViewTreeObserver.OnScrollChangedListener {
            headerGlass.invalidate()
            bottomGlass?.invalidate()
        }
        scroller.viewTreeObserver.addOnScrollChangedListener(redraw)
        if (scroller is RecyclerView) {
            scroller.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    headerGlass.invalidate()
                    bottomGlass?.invalidate()
                }
            })
        }
        if (scroller is ScrollView || scroller is NestedScrollView) {
            scroller.viewTreeObserver.addOnScrollChangedListener(redraw)
        }
        scroller.post(applyPad)
    }

    fun attachPage(host: android.app.Activity, scroller: View) {
        val content = host.findViewById<View>(R.id.pageContent) ?: return
        val header = host.findViewById<View>(R.id.headerBar) ?: return
        val headerGlass = host.findViewById<LiquidGlassView>(R.id.headerGlass) ?: return
        attach(
            content,
            scroller,
            header,
            host.findViewById(R.id.bottomBar),
            headerGlass,
            host.findViewById(R.id.bottomGlass)
        )
    }

    fun bindPanel(scene: View, glass: LiquidGlassView) {
        glass.panelStyle()
        glass.bind(scene)
        glass.invalidate()
    }

    private fun matchHeight(view: View, height: Int) {
        if (height <= 0) return
        val lp = view.layoutParams ?: return
        if (lp.height != height) {
            lp.height = height
            view.layoutParams = lp
        }
    }
}
