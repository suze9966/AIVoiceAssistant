package com.suze.aivoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 面板模板 / 收藏夹（参考 GitHub stquickstatusbar 的「配置管理」+ ST-StatusTracking 的「存储至角色卡」）。
 *
 * 主人可以把喜欢的面板存下来，下次一句话套用；也可以自建字段模板（preset）。
 */
data class PanelTemplate(
    /** 模板名（唯一，作为套用口令）。 */
    val name: String,
    /** 字段行，形如 `生命值 = 80/100`。 */
    val fields: List<String> = emptyList(),
    /** 默认样式名（可空）。 */
    val styleName: String = "",
    /** 标题。 */
    val title: String = "",
    /** 是否为纯字段模板（preset：只存字段名，值留空）。 */
    val isPreset: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

class PanelStore(context: Context) {

    private val file = File(context.filesDir, "panel_templates.json")
    private val maxKeep = 5000

    @Volatile
    private var cache: MutableList<PanelTemplate>? = null

    /** 对外只读副本，避免调用方意外改到内部缓存。 */
    fun load(): MutableList<PanelTemplate> = loadRef().toMutableList()

    /** 内部拿缓存引用（写操作用）。 */
    private fun loadRef(): MutableList<PanelTemplate> {
        cache?.let { return it }
        val list = mutableListOf<PanelTemplate>()
        try {
            if (!file.exists()) { cache = list; return list }
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                val fieldsArr = o.optJSONArray("fields") ?: JSONArray()
                val fields = mutableListOf<String>()
                for (j in 0 until fieldsArr.length()) {
                    val v = fieldsArr.optString(j)
                    if (v.isNotBlank()) fields.add(v)
                }
                list.add(
                    PanelTemplate(
                        name = name,
                        fields = fields,
                        styleName = o.optString("styleName"),
                        title = o.optString("title"),
                        isPreset = o.optBoolean("isPreset", false),
                        createdAt = o.optLong("createdAt")
                    )
                )
            }
        } catch (_: Exception) { }
        cache = list
        return list
    }

    fun save(items: List<PanelTemplate>) {
        try {
            val arr = JSONArray()
            items.takeLast(maxKeep).forEach { t ->
                val fa = JSONArray()
                t.fields.forEach { fa.put(it) }
                arr.put(
                    JSONObject()
                        .put("name", t.name)
                        .put("fields", fa)
                        .put("styleName", t.styleName)
                        .put("title", t.title)
                        .put("isPreset", t.isPreset)
                        .put("createdAt", t.createdAt)
                )
            }
            file.writeText(arr.toString())
            cache = items.toMutableList()
        } catch (_: Exception) { }
    }

    /** 新增或覆盖同名模板。 */
    fun put(t: PanelTemplate): Boolean {
        val list = loadRef()
        val idx = list.indexOfFirst { it.name == t.name }
        if (idx >= 0) list[idx] = t else list.add(t)
        save(list)
        return true
    }

    /** 删除同名模板，返回是否存在。 */
    fun remove(name: String): Boolean {
        val list = loadRef()
        val n = list.size
        list.removeAll { it.name == name }
        if (list.size == n) return false
        save(list)
        return true
    }

    /** 精确 → 包含 匹配模板。 */
    fun find(name: String): PanelTemplate? {
        val k = name.trim()
        if (k.isEmpty()) return null
        val list = loadRef()
        list.firstOrNull { it.name == k }?.let { return it }
        return list.firstOrNull { it.name.contains(k) || k.contains(it.name) }
    }

    /** 把模板转成可直接渲染的字段行（preset 型补 0/100）。 */
    fun toPanelBody(t: PanelTemplate): List<String> {
        val head = t.title.ifBlank { "${t.name} 状态" }
        return if (t.isPreset) {
            listOf(head) + t.fields.map { "$it = 0/100" }
        } else {
            listOf(head) + t.fields
        }
    }

    /** 导出为 JSON 字符串（给备份用）。 */
    fun exportJson(): String = try {
        file.takeIf { it.exists() }?.readText() ?: "[]"
    } catch (_: Exception) { "[]" }

    /** 从 JSON 字符串导入（replace=true 覆盖，false 合并）。 */
    fun importJson(text: String, replace: Boolean): Int {
        return try {
            val arr = JSONArray(text)
            val incoming = mutableListOf<PanelTemplate>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isBlank()) continue
                val fieldsArr = o.optJSONArray("fields") ?: JSONArray()
                val fields = mutableListOf<String>()
                for (j in 0 until fieldsArr.length()) fields.add(fieldsArr.optString(j))
                incoming.add(
                    PanelTemplate(
                        name, fields, o.optString("styleName"),
                        o.optString("title"), o.optBoolean("isPreset", false),
                        o.optLong("createdAt")
                    )
                )
            }
            if (replace) {
                save(incoming)
            } else {
                val list = loadRef()
                incoming.forEach { t ->
                    val idx = list.indexOfFirst { it.name == t.name }
                    if (idx >= 0) list[idx] = t else list.add(t)
                }
                save(list)
            }
            incoming.size
        } catch (_: Exception) { 0 }
    }

    /** 清空缓存（外部改了文件后调用）。 */
    fun invalidate() { cache = null }

    companion object {

        /**
         * 从口令里抽「模板名」。
         *
         * 支持：`存面板 龙鳞冒险`、`保存面板 龙鳞冒险`、`收藏面板 龙鳞冒险`、
         * `新建字段模板 修仙`、`删除面板模板 龙鳞冒险`
         */
        fun nameFromText(text: String): String? {
            val keys = listOf(
                // 长 key 优先，避免「应用面板模板 X」被「应用面板」截成「模板 X」
                "保存面板模板", "保存字段模板", "新建字段模板", "新建面板模板", "自定义字段模板",
                "收藏面板模板", "存面板模板", "删除面板模板", "删掉面板模板", "移除面板模板",
                "套用面板模板", "应用面板模板", "载入面板模板", "套用字段模板",
                "保存面板", "存面板", "收藏面板", "记住面板",
                "删除面板", "套用面板", "应用面板", "载入面板"
            ).sortedByDescending { it.length }
            for (k in keys) {
                val idx = text.indexOf(k)
                if (idx < 0) continue
                val rest = text.substring(idx + k.length)
                    .trim()
                    .trimStart('：', ':', ' ', '，', ',')
                    .trim()
                // 去掉尾部语气词
                var name = rest
                for (tail in listOf("吧", "呀", "哦", "啊", "。", "！", "!", ".", "的模板", "模板")) {
                    if (name.endsWith(tail)) name = name.dropLast(tail.length).trim()
                }
                if (name.isNotBlank() && name.length <= 20) return name
            }
            return null
        }
    }
}
