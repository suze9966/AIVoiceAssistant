package com.suze.aivoice

import android.app.Activity
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 小手机提供给内嵌页面的宿主能力。
 *
 * 酒馆不再另开浮动窗口，而是小手机窗口里的页面层，
 * 所以页面跳转、出栈返回、文件选择都由小手机接管。
 */
interface PocketHost {
    val hostActivity: Activity

    /** 页面跳转：在同一个小窗里压入下一页。 */
    fun openPage(intent: Intent)

    /** 页面返回：出栈回上一页，栈空才回小手机桌面。 */
    fun closePage()

    /** 文件选择。open 为 true 走系统文档选择器，false 走任意内容。 */
    fun requestFile(mimeTypes: Array<String>, open: Boolean, onResult: (Uri?) -> Unit)
}

/**
 * 小手机内部的一页：酒馆角色列表、角色聊天、角色编辑、酒馆插件、插件编辑、
 * 群聊列表、群编辑、群聊、接入大模型。
 *
 * 页面不是 Activity，但它自己就是一个 Context，所以页面里原有的
 * findViewById、startActivity、Toast、AlertDialog、RoleStore(this) 都能照常写，
 * 只有类声明、onCreate 里的 setContentView 和生命周期方法名需要改。
 */
abstract class PocketPage(
    val host: PocketHost,
    pageIntent: Intent?
) : ContextWrapper(host.hostActivity) {

    private val frame = FrameLayout(host.hostActivity)

    /** 小手机承载这一页的容器。 */
    val root: View get() = frame

    /** 页面参数，页面里读 role_id、chat_id、group_id 照常用 getStringExtra。 */
    val pageArgs: Intent = pageIntent ?: Intent()

    /** 页面已被弹出。页面里 isFinishing、isDestroyed 的判断照常工作。 */
    var closed = false
        private set

    /** 宿主出栈时标记页面已关闭，页面里 isFinishing 随即为 true。 */
    fun markClosed() {
        closed = true
    }

    val isFinishing: Boolean get() = closed
    val isDestroyed: Boolean get() = closed
    val isTaskRoot: Boolean get() = closed

    /**
     * 页面自己的任务域，页面里 lifecycleScope.launch 照常写。
     *
     * 每页独立一个 scope，页面出栈时整体取消，
     * 不然关掉的酒馆页还会继续发请求、刷已经销毁的界面。
     */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    protected val lifecycleScope: CoroutineScope get() = ownScope

    /** 出栈时把这一页挂着的后台任务全部收掉。 */
    fun cancelPageJobs() {
        ownScope.cancel()
    }

    /** 页面 inflate 自己的布局进容器。 */
    protected fun inflate(layoutRes: Int) {
        LayoutInflater.from(host.hostActivity).inflate(layoutRes, frame, true)
    }

    /**
     * 页面自己的视图查找。找不到就抛，和 Activity 行为一致，
     * 这样页面里 findViewById<TextView>(x).text = y 这样的写法照常编译。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : View> findViewById(id: Int): T {
        if (id == android.R.id.content) return frame as T
        return frame.findViewById(id) as T
    }

    /**
     * 页面跳转。酒馆内的页面由小手机转成页面栈压栈，
     * 酒馆之外的页面照常另开，保证外部入口不被裁掉。
     */
    override fun startActivity(intent: Intent) {
        if (PocketPageMap.isInnerPage(intent)) host.openPage(intent) else host.hostActivity.startActivity(intent)
    }

    /** 页面返回：栈里还有上一页就回上一页，没有就回小手机桌面。 */
    fun finish() {
        if (closed) return
        closed = true
        host.closePage()
    }

    /** 主线程回调，页面里 runOnUiThread 照常写。 */
    fun runOnUiThread(action: () -> Unit) {
        val view = root
        if (view.isAttachedToWindow) view.post(action) else host.hostActivity.runOnUiThread(action)
    }

    /** 打开系统文件选择，结果回给页面。 */
    protected fun pickFile(onResult: (Uri?) -> Unit, open: Boolean, mimeTypes: Array<String>) {
        host.requestFile(mimeTypes, open, onResult)
    }

    /** 页面禁止截图，和原来的 Activity 一致。 */
    protected fun secureScreen() {
        runCatching { host.hostActivity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    /** 挂上页面自己的玻璃顶栏底栏。 */
    protected fun attachGlass(scroller: View) {
        GlassKit.attachPage(frame, scroller)
    }

    open fun onPageCreate() {}

    open fun onPageResume() {}

    open fun onPagePause() {}

    open fun onPageDestroy() {}
}

/** 酒馆内嵌页面登记表：这些页面在小手机里压栈，其余页面照常另开。 */
object PocketPageMap {
    private val inner = setOf(
        RoleLoungePage::class.java.name,
        RoleChatPage::class.java.name,
        RoleEditPage::class.java.name,
        TavernPluginPage::class.java.name,
        TavernPluginEditPage::class.java.name,
        GroupLoungePage::class.java.name,
        GroupEditPage::class.java.name,
        GroupChatPage::class.java.name,
        LlmConnectPage::class.java.name
    )

    fun isInnerPage(intent: Intent): Boolean {
        val name = intent.component?.className ?: return false
        return name in inner
    }

    /** 按目标页面类名建实例。 */
    fun create(name: String, host: PocketHost, args: Intent?): PocketPage? = when (name) {
        RoleLoungePage::class.java.name -> RoleLoungePage(host, args)
        RoleChatPage::class.java.name -> RoleChatPage(host, args)
        RoleEditPage::class.java.name -> RoleEditPage(host, args)
        TavernPluginPage::class.java.name -> TavernPluginPage(host, args)
        TavernPluginEditPage::class.java.name -> TavernPluginEditPage(host, args)
        GroupLoungePage::class.java.name -> GroupLoungePage(host, args)
        GroupEditPage::class.java.name -> GroupEditPage(host, args)
        GroupChatPage::class.java.name -> GroupChatPage(host, args)
        LlmConnectPage::class.java.name -> LlmConnectPage(host, args)
        else -> null
    }
}