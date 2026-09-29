package com.suze.aivoice

/** 体积很小的内置立绘：6 张矢量图，不引入整包 WebP。 */
object PortraitLibrary {
    const val DEFAULT_ID = "xiaomo"

    data class Item(val id: String, val name: String, val resId: Int)

    fun all(): List<Item> = listOf(
        Item("xiaomo", "小沫", R.drawable.portrait_xiaomo),
        Item("wanqing", "晚晴", R.drawable.portrait_wanqing),
        Item("abei", "阿北", R.drawable.portrait_abei),
        Item("shenheng", "沈衡", R.drawable.portrait_shenheng),
        Item("linxi", "林溪", R.drawable.portrait_linxi),
        Item("jiuyu", "酒羽", R.drawable.portrait_jiuyu)
    )

    fun resFor(id: String): Int? = all().firstOrNull { it.id == id }?.resId
}
