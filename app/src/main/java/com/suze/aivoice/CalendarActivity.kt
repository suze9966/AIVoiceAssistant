package com.suze.aivoice

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class CalendarActivity : AppCompatActivity() {
    private lateinit var helper: CalendarHelper
    private lateinit var adapter: HubAdapter
    private val items = mutableListOf<CalendarEvent>()
    private val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hub)
        helper = CalendarHelper(this)
        findViewById<TextView>(R.id.tvHubTitle).setText(R.string.calendar_title)
        findViewById<TextView>(R.id.tvHubHelp).setText(R.string.calendar_help)
        findViewById<View>(R.id.btnHubBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnHubAdd).setOnClickListener { showEditor() }
        findViewById<android.widget.Button>(R.id.btnHubAction1).apply {
            setText(R.string.calendar_refresh)
            setOnClickListener { reload() }
        }
        findViewById<android.widget.Button>(R.id.btnHubAction2).visibility = View.GONE
        findViewById<android.widget.Button>(R.id.btnHubAction3).visibility = View.GONE
        adapter = HubAdapter(
            items,
            body = { it.title },
            meta = {
                fmt.format(Date(it.start)) + if (it.location.isBlank()) "" else " · " + it.location
            },
            onLongClick = { confirmDelete(it) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recyclerHub)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        GlassKit.attachPage(this, recycler)
        if (!hasPerm()) requestPerm() else reload()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) reload()
        else Toast.makeText(this, R.string.toast_calendar_need_perm, Toast.LENGTH_SHORT).show()
    }

    private fun hasPerm(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestPerm() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
            REQ
        )
    }

    private fun reload() {
        if (!hasPerm()) {
            findViewById<TextView>(R.id.tvHubSubtitle).setText(R.string.toast_calendar_need_perm)
            return
        }
        items.clear()
        items.addAll(helper.upcomingEvents(40))
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.tvHubSubtitle).text =
            if (helper.lastDenied) getString(R.string.toast_calendar_need_perm)
            else getString(R.string.calendar_summary, items.size)
        findViewById<TextView>(R.id.tvHubEmpty).apply {
            visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            setText(R.string.calendar_empty)
        }
    }

    private fun showEditor() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPerm()
            return
        }
        val cal = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply { hint = getString(R.string.calendar_hint) }
        val timeBtn = android.widget.Button(this).apply {
            text = fmt.format(Date(cal.timeInMillis))
            setOnClickListener {
                DatePickerDialog(this@CalendarActivity, { _, y, m, d ->
                    cal.set(Calendar.YEAR, y)
                    cal.set(Calendar.MONTH, m)
                    cal.set(Calendar.DAY_OF_MONTH, d)
                    TimePickerDialog(this@CalendarActivity, { _, h, min ->
                        cal.set(Calendar.HOUR_OF_DAY, h)
                        cal.set(Calendar.MINUTE, min)
                        cal.set(Calendar.SECOND, 0)
                        text = fmt.format(Date(cal.timeInMillis))
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
        }
        box.addView(input)
        box.addView(timeBtn)
        AlertDialog.Builder(this)
            .setTitle(R.string.calendar_add)
            .setView(box)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val title = input.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(this, R.string.calendar_hint, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ok = helper.add(title, cal.timeInMillis)
                Toast.makeText(
                    this,
                    if (ok) R.string.toast_calendar_added else R.string.toast_calendar_fail,
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) reload()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun confirmDelete(item: CalendarEvent) {
        AlertDialog.Builder(this)
            .setTitle(R.string.calendar_delete)
            .setMessage(item.title)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                val ok = helper.delete(item.id)
                Toast.makeText(
                    this,
                    if (ok) R.string.toast_calendar_deleted else R.string.toast_calendar_fail,
                    Toast.LENGTH_SHORT
                ).show()
                if (ok) reload()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private class HubAdapter(
        private val items: List<CalendarEvent>,
        private val body: (CalendarEvent) -> String,
        private val meta: (CalendarEvent) -> String,
        private val onLongClick: (CalendarEvent) -> Unit
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
            holder.type.setText(R.string.calendar_event)
            holder.content.text = body(item)
            holder.meta.text = meta(item)
            holder.itemView.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }

    companion object {
        private const val REQ = 41
    }
}