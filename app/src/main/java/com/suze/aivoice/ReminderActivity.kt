package com.suze.aivoice

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Calendar

class ReminderActivity : AppCompatActivity() {
    private lateinit var store: ReminderStore
    private lateinit var adapter: HubAdapter
    private val items = mutableListOf<ReminderItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub)
        store = ReminderStore(this)
        findViewById<TextView>(R.id.tvHubTitle).setText(R.string.remind_title)
        findViewById<TextView>(R.id.tvHubHelp).setText(R.string.remind_help)
        findViewById<View>(R.id.btnHubBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnHubAdd).setOnClickListener { showEditor(null) }
        findViewById<android.widget.Button>(R.id.btnHubAction1).apply {
            setText(R.string.remind_reschedule)
            setOnClickListener {
                store.rescheduleAll()
                reload()
                Toast.makeText(this@ReminderActivity, R.string.toast_remind_rescheduled, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.widget.Button>(R.id.btnHubAction2).apply {
            visibility = View.GONE
        }
        findViewById<android.widget.Button>(R.id.btnHubAction3).apply {
            setText(R.string.memory_clear)
            setOnClickListener {
                AlertDialog.Builder(this@ReminderActivity)
                    .setTitle(R.string.memory_clear)
                    .setMessage(R.string.remind_confirm_clear)
                    .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                        store.cancelAll()
                        reload()
                        Toast.makeText(this@ReminderActivity, R.string.toast_remind_cleared, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(R.string.role_cancel, null)
                    .show()
            }
        }
        adapter = HubAdapter(
            items,
            label = { if (it.repeatDaily) getString(R.string.remind_repeat) else getString(R.string.remind_once) },
            body = { it.text },
            meta = { store.formatItem(it) },
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
        items.addAll(store.upcoming())
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.tvHubSubtitle).text = store.summary()
        findViewById<TextView>(R.id.tvHubEmpty).apply {
            visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            setText(R.string.remind_empty)
        }
    }

    private fun showEditor(item: ReminderItem?) {
        val cal = Calendar.getInstance()
        if (item != null) cal.timeInMillis = item.atMillis
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.remind_hint)
            setText(item?.text.orEmpty())
        }
        val timeBtn = android.widget.Button(this).apply {
            text = store.formatWhen(cal.timeInMillis)
            setOnClickListener {
                DatePickerDialog(this@ReminderActivity, { _, y, m, d ->
                    cal.set(Calendar.YEAR, y)
                    cal.set(Calendar.MONTH, m)
                    cal.set(Calendar.DAY_OF_MONTH, d)
                    TimePickerDialog(this@ReminderActivity, { _, h, min ->
                        cal.set(Calendar.HOUR_OF_DAY, h)
                        cal.set(Calendar.MINUTE, min)
                        cal.set(Calendar.SECOND, 0)
                        text = store.formatWhen(cal.timeInMillis)
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        val repeat = CheckBox(this).apply {
            setText(R.string.remind_repeat)
            isChecked = item?.repeatDaily == true
        }
        val advance = CheckBox(this).apply {
            setText(R.string.remind_advance_title)
            isChecked = (item?.advanceMin ?: 0) > 0
        }
        box.addView(input)
        box.addView(timeBtn)
        box.addView(repeat)
        box.addView(advance)
        AlertDialog.Builder(this)
            .setTitle(if (item == null) R.string.remind_add else R.string.remind_edit)
            .setView(box)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    Toast.makeText(this, R.string.remind_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val mins = if (advance.isChecked) 5 else 0
                if (item == null) store.add(text, cal.timeInMillis, repeat.isChecked, mins)
                else store.update(item.id, text, cal.timeInMillis, repeat.isChecked, mins)
                reload()
                Toast.makeText(this, R.string.toast_remind_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun confirmDelete(item: ReminderItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remind_delete)
            .setMessage(item.text)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                store.cancel(item.id)
                reload()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private class HubAdapter(
        private val items: List<ReminderItem>,
        private val label: (ReminderItem) -> String,
        private val body: (ReminderItem) -> String,
        private val meta: (ReminderItem) -> String,
        private val onClick: (ReminderItem) -> Unit,
        private val onLongClick: (ReminderItem) -> Unit
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