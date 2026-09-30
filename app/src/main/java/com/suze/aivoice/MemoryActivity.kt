package com.suze.aivoice

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryActivity : AppCompatActivity() {
    private lateinit var memory: MemoryEngine
    private lateinit var adapter: MemoryAdapter
    private lateinit var emptyView: TextView
    private lateinit var subtitle: TextView
    private val items = mutableListOf<MemoryEngine.Mem>()

    private val pickJson = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        askImportMode(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory)
        memory = MemoryEngine(this)
        emptyView = findViewById(R.id.tvMemoryEmpty)
        subtitle = findViewById(R.id.tvMemorySubtitle)
        findViewById<View>(R.id.btnMemoryBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMemoryAdd).setOnClickListener { showEditor(-1) }
        findViewById<View>(R.id.btnMemoryImport).setOnClickListener {
            pickJson.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        findViewById<View>(R.id.btnMemoryExport).setOnClickListener { exportJson() }
        findViewById<View>(R.id.btnMemoryClear).setOnClickListener { confirmClear() }
        adapter = MemoryAdapter(
            items,
            onClick = { showEditor(it) },
            onLongClick = { confirmDelete(it) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recyclerMemory)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        GlassKit.attachPage(this, recycler)
        reload()
    }

    private fun reload() {
        memory.reload()
        items.clear()
        items.addAll(memory.listItems().asReversed())
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        subtitle.text = memory.summary()
    }

    private fun showEditor(displayIndex: Int) {
        val editing = displayIndex in items.indices
        val current = if (editing) items[displayIndex] else null
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val spinner = Spinner(this)
        val typeKeys = listOf(
            MemoryEngine.TYPE_PREF,
            MemoryEngine.TYPE_FACT,
            MemoryEngine.TYPE_LEARN
        )
        val typeLabels = listOf(
            getString(R.string.memory_type_pref),
            getString(R.string.memory_type_fact),
            getString(R.string.memory_type_learn)
        )
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        val typeIdx = typeKeys.indexOf(current?.type ?: MemoryEngine.TYPE_FACT).coerceAtLeast(0)
        spinner.setSelection(typeIdx)
        val input = EditText(this).apply {
            hint = getString(R.string.memory_hint_content)
            setText(current?.content.orEmpty())
            minLines = 3
            maxLines = 6
            setPadding(paddingLeft, (8 * resources.displayMetrics.density).toInt(), paddingRight, paddingBottom)
        }
        box.addView(spinner)
        box.addView(input)
        AlertDialog.Builder(this)
            .setTitle(if (editing) R.string.memory_edit else R.string.memory_add)
            .setView(box)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val type = typeKeys.getOrNull(spinner.selectedItemPosition) ?: MemoryEngine.TYPE_LEARN
                val text = input.text.toString()
                val ok = if (editing) {
                    val realIndex = memory.listItems().size - 1 - displayIndex
                    memory.updateAt(realIndex, type, text)
                } else {
                    memory.addManual(type, text)
                }
                if (ok) {
                    reload()
                    Toast.makeText(
                        this,
                        if (editing) R.string.toast_memory_updated else R.string.toast_memory_added,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, R.string.memory_content_required, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun confirmDelete(displayIndex: Int) {
        if (displayIndex !in items.indices) return
        AlertDialog.Builder(this)
            .setTitle(R.string.memory_delete)
            .setMessage(R.string.memory_confirm_delete)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                val realIndex = memory.listItems().size - 1 - displayIndex
                if (memory.deleteAt(realIndex)) {
                    reload()
                    Toast.makeText(this, R.string.toast_memory_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.memory_clear)
            .setMessage(R.string.memory_confirm_clear)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                memory.clearAll()
                reload()
                Toast.makeText(this, R.string.toast_memory_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun askImportMode(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.memory_import)
            .setMessage(R.string.memory_import_mode)
            .setPositiveButton(R.string.memory_import_replace) { _, _ -> importFrom(uri, true) }
            .setNeutralButton(R.string.memory_import_merge) { _, _ -> importFrom(uri, false) }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun importFrom(uri: Uri, replace: Boolean) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.toast_memory_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val n = memory.importJson(text, replace)
        if (n < 0) {
            Toast.makeText(this, R.string.toast_memory_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        reload()
        Toast.makeText(this, getString(R.string.toast_memory_imported, n), Toast.LENGTH_SHORT).show()
    }

    private fun exportJson() {
        val dir = File(cacheDir, "memory_export").apply { mkdirs() }
        val name = "xiaomo-memory-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date()) + ".json"
        val file = File(dir, name)
        val ok = runCatching { file.writeText(memory.exportJson()) }.isSuccess
        if (!ok || !file.isFile || file.length() <= 0L) {
            Toast.makeText(this, R.string.toast_memory_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.memory_export)
            )
        )
        Toast.makeText(this, getString(R.string.toast_memory_exported, file.name), Toast.LENGTH_SHORT).show()
    }

    private class MemoryAdapter(
        private val items: List<MemoryEngine.Mem>,
        private val onClick: (Int) -> Unit,
        private val onLongClick: (Int) -> Unit
    ) : RecyclerView.Adapter<MemoryAdapter.VH>() {
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
            val ctx = holder.itemView.context
            holder.type.text = when (item.type) {
                MemoryEngine.TYPE_PREF -> ctx.getString(R.string.memory_type_pref)
                MemoryEngine.TYPE_FACT -> ctx.getString(R.string.memory_type_fact)
                else -> ctx.getString(R.string.memory_type_learn)
            }
            holder.content.text = item.content
            val time = if (item.time > 0L) {
                DateFormat.format("MM-dd HH:mm", Date(item.time)).toString()
            } else {
                ""
            }
            holder.meta.text = if (time.isBlank()) {
                ctx.getString(R.string.memory_weight, item.weight)
            } else {
                ctx.getString(R.string.memory_weight, item.weight) + " · " + time
            }
            holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
            holder.itemView.setOnLongClickListener {
                onLongClick(holder.bindingAdapterPosition)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}