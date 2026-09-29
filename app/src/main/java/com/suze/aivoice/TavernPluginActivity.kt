package com.suze.aivoice

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class TavernPluginActivity : AppCompatActivity() {
    private lateinit var store: TavernPluginStore
    private lateinit var adapter: PluginAdapter
    private lateinit var emptyView: TextView
    private val items = mutableListOf<TavernPlugin>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tavern_plugin)
        store = TavernPluginStore(this)
        emptyView = findViewById(R.id.tvPluginEmpty)
        findViewById<View>(R.id.btnPluginBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnPluginAdd).setOnClickListener {
            if (store.load().size >= 32) {
                Toast.makeText(this, R.string.toast_plugin_limit, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, TavernPluginEditActivity::class.java))
        }
        adapter = PluginAdapter(
            items,
            onClick = { openEdit(it.id) },
            onToggle = { plugin, enabled ->
                store.upsert(plugin.copy(enabled = enabled))
                reload()
            },
            onLongClick = { confirmDelete(it) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recyclerPlugins)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        GlassKit.attachPage(this, recycler)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items.clear()
        items.addAll(store.load())
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openEdit(id: String) {
        startActivity(
            Intent(this, TavernPluginEditActivity::class.java)
                .putExtra(TavernPluginEditActivity.EXTRA_PLUGIN_ID, id)
        )
    }

    private fun confirmDelete(plugin: TavernPlugin) {
        AlertDialog.Builder(this)
            .setTitle(R.string.plugin_delete)
            .setMessage(R.string.plugin_confirm_delete)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                store.delete(plugin.id)
                reload()
                Toast.makeText(this, R.string.toast_plugin_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private class PluginAdapter(
        private val items: List<TavernPlugin>,
        private val onClick: (TavernPlugin) -> Unit,
        private val onToggle: (TavernPlugin, Boolean) -> Unit,
        private val onLongClick: (TavernPlugin) -> Unit
    ) : RecyclerView.Adapter<PluginAdapter.VH>() {
        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tvPluginName)
            val meta: TextView = view.findViewById(R.id.tvPluginMeta)
            val enabled: CheckBox = view.findViewById(R.id.checkPluginEnabled)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_tavern_plugin, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.name.text = item.name.ifBlank { ctx.getString(R.string.plugin_edit_title) }
            val target = when (item.target) {
                TavernPlugin.TARGET_INPUT -> ctx.getString(R.string.plugin_target_input)
                TavernPlugin.TARGET_PROMPT -> ctx.getString(R.string.plugin_target_prompt)
                else -> ctx.getString(R.string.plugin_target_output)
            }
            val notes = item.notes.trim().ifBlank { item.trigger }
            val meta = if (item.enabled) {
                ctx.getString(R.string.plugin_meta_on, target)
            } else {
                ctx.getString(R.string.plugin_meta_off, target)
            }
            holder.meta.text = if (notes.isBlank()) meta else meta + " · " + notes
            holder.enabled.setOnCheckedChangeListener(null)
            holder.enabled.isChecked = item.enabled
            holder.enabled.setOnCheckedChangeListener { _, checked -> onToggle(item, checked) }
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
