package com.suze.aivoice

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

class TodoActivity : AppCompatActivity() {
    private lateinit var store: TodoStore
    private lateinit var adapter: HubAdapter
    private val items = mutableListOf<TodoItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub)
        store = TodoStore(this)
        findViewById<TextView>(R.id.tvHubTitle).setText(R.string.todo_title)
        findViewById<TextView>(R.id.tvHubHelp).setText(R.string.todo_help)
        findViewById<View>(R.id.btnHubBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnHubAdd).setOnClickListener { showEditor(null) }
        findViewById<android.widget.Button>(R.id.btnHubAction1).apply {
            setText(R.string.todo_show_all)
            setOnClickListener { reload() }
        }
        findViewById<android.widget.Button>(R.id.btnHubAction2).apply {
            setText(R.string.todo_clear_done)
            setOnClickListener {
                store.load().filter { it.done }.forEach { store.delete(it.id) }
                reload()
                Toast.makeText(this@TodoActivity, R.string.toast_todo_cleared_done, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.widget.Button>(R.id.btnHubAction3).apply {
            setText(R.string.memory_clear)
            setOnClickListener {
                AlertDialog.Builder(this@TodoActivity)
                    .setTitle(R.string.memory_clear)
                    .setMessage(R.string.todo_confirm_clear)
                    .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                        store.clearAll()
                        reload()
                        Toast.makeText(this@TodoActivity, R.string.toast_todo_cleared, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.role_cancel, null)
                    .show()
            }
        }
        adapter = HubAdapter(
            items,
            label = { if (it.done) getString(R.string.todo_done) else getString(R.string.todo_pending) },
            body = { it.text },
            meta = {
                DateFormat.format("MM-dd HH:mm", Date(it.createdAt)).toString()
            },
            onClick = { showEditor(it) },
            onLongClick = { confirmDelete(it) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recyclerHub)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        GlassKit.attachPage(this, recycler)
        reload()
    }

    private fun reload() {
        items.clear()
        items.addAll(store.load().asReversed())
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.tvHubSubtitle).text = store.summary()
        findViewById<View>(R.id.tvHubEmpty).apply {
            visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (this is TextView) setText(R.string.todo_empty)
        }
    }

    private fun showEditor(item: TodoItem?) {
        val input = EditText(this).apply {
            hint = getString(R.string.todo_hint)
            setText(item?.text.orEmpty())
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle(if (item == null) R.string.todo_add else R.string.todo_edit)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val text = input.text.toString()
                val ok = if (item == null) {
                    store.add(text)
                    true
                } else {
                    store.update(item.id, text)
                }
                if (ok) {
                    reload()
                    Toast.makeText(this, R.string.toast_todo_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.todo_hint, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(if (item?.done == true) R.string.todo_undone else R.string.todo_mark_done) { _, _ ->
                if (item != null) {
                    store.toggle(item.id)
                    reload()
                }
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun confirmDelete(item: TodoItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.todo_delete)
            .setMessage(item.text)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                store.delete(item.id)
                reload()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private class HubAdapter(
        private val items: List<TodoItem>,
        private val label: (TodoItem) -> String,
        private val body: (TodoItem) -> String,
        private val meta: (TodoItem) -> String,
        private val onClick: (TodoItem) -> Unit,
        private val onLongClick: (TodoItem) -> Unit
    ) : RecyclerView.Adapter<HubAdapter.VH>() {
        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val type: TextView = view.findViewById(R.id.tvMemoryType)
            val content: TextView = view.findViewById(R.id.tvMemoryContent)
            val meta: TextView = view.findViewById(R.id.tvMemoryMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_memory, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.type.text = label(item)
            holder.content.text = body(item)
            holder.meta.text = meta(item)
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
