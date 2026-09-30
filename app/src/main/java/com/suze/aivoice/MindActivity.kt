package com.suze.aivoice

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MindActivity : AppCompatActivity() {
    private lateinit var mind: MindEngine
    private lateinit var adapter: HubAdapter
    private val items = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub)
        mind = MindEngine(this)
        findViewById<TextView>(R.id.tvHubTitle).setText(R.string.mind_title)
        findViewById<TextView>(R.id.tvHubHelp).setText(R.string.mind_help)
        findViewById<View>(R.id.btnHubBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnHubAdd).setOnClickListener { showExperience(-1) }
        findViewById<android.widget.Button>(R.id.btnHubAction1).apply {
            setText(R.string.mind_edit_goal)
            setOnClickListener { editGoal() }
        }
        findViewById<android.widget.Button>(R.id.btnHubAction2).apply {
            setText(R.string.mind_edit_view)
            setOnClickListener { editSelfView() }
        }
        findViewById<android.widget.Button>(R.id.btnHubAction3).apply {
            setText(R.string.memory_clear)
            setOnClickListener {
                AlertDialog.Builder(this@MindActivity)
                    .setTitle(R.string.memory_clear)
                    .setMessage(R.string.mind_confirm_clear)
                    .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                        mind.clearExperiences()
                        reload()
                        Toast.makeText(this@MindActivity, R.string.toast_mind_cleared, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.role_cancel, null)
                    .show()
            }
        }
        adapter = HubAdapter(
            items,
            onClick = { showExperience(it) },
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
        items.addAll(mind.experiences().asReversed())
        adapter.notifyDataSetChanged()
        val thought = mind.lastThought.ifBlank { getString(R.string.mind_no_thought) }
        findViewById<TextView>(R.id.tvHubSubtitle).text = mind.summary() + " · " + thought
        findViewById<TextView>(R.id.tvHubEmpty).apply {
            visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            setText(R.string.mind_empty)
        }
    }

    private fun editGoal() {
        val input = EditText(this).apply {
            hint = getString(R.string.mind_goal_hint)
            setText(mind.goal)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.mind_edit_goal)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                mind.updateGoal(input.text.toString())
                reload()
                Toast.makeText(this, R.string.toast_mind_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun editSelfView() {
        val input = EditText(this).apply {
            hint = getString(R.string.mind_view_hint)
            setText(mind.selfView())
            minLines = 3
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.mind_edit_view)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                mind.setSelfView(input.text.toString())
                reload()
                Toast.makeText(this, R.string.toast_mind_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun showExperience(displayIndex: Int) {
        val editing = displayIndex in items.indices
        val current = if (editing) items[displayIndex] else ""
        val input = EditText(this).apply {
            hint = getString(R.string.mind_exp_hint)
            setText(current)
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle(if (editing) R.string.mind_edit_exp else R.string.mind_add_exp)
            .setView(input)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val text = input.text.toString()
                val ok = if (editing) {
                    val real = mind.experiences().size - 1 - displayIndex
                    mind.updateExperience(real, text)
                } else {
                    mind.addExperience(text)
                }
                if (ok) {
                    reload()
                    Toast.makeText(this, R.string.toast_mind_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.mind_exp_hint, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun confirmDelete(displayIndex: Int) {
        if (displayIndex !in items.indices) return
        AlertDialog.Builder(this)
            .setTitle(R.string.mind_delete_exp)
            .setMessage(items[displayIndex])
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                val real = mind.experiences().size - 1 - displayIndex
                mind.deleteExperience(real)
                reload()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private class HubAdapter(
        private val items: List<String>,
        private val onClick: (Int) -> Unit,
        private val onLongClick: (Int) -> Unit
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
            holder.type.setText(R.string.mind_exp)
            holder.content.text = items[position]
            holder.meta.text = ""
            holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
            holder.itemView.setOnLongClickListener {
                onLongClick(holder.bindingAdapterPosition)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}