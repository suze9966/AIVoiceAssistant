package com.suze.aivoice

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
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

class EmotionActivity : AppCompatActivity() {
    private lateinit var emotion: EmotionEngine
    private lateinit var adapter: EmotionAdapter
    private lateinit var emptyView: TextView
    private lateinit var subtitle: TextView
    private lateinit var search: EditText
    private val items = mutableListOf<EmotionEngine.EmotionRow>()
    private val expanded = mutableSetOf<String>()

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
        setContentView(R.layout.activity_emotion)
        emotion = EmotionEngine(this)
        emptyView = findViewById(R.id.tvEmotionEmpty)
        subtitle = findViewById(R.id.tvEmotionSubtitle)
        search = findViewById(R.id.editEmotionSearch)
        findViewById<View>(R.id.btnEmotionBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnEmotionAdd).setOnClickListener { showEditor(null) }
        findViewById<View>(R.id.btnEmotionImport).setOnClickListener {
            pickJson.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        findViewById<View>(R.id.btnEmotionExport).setOnClickListener { exportJson() }
        findViewById<View>(R.id.btnEmotionReflect).setOnClickListener {
            emotion.reflectNow()
            reload()
            Toast.makeText(this, R.string.toast_emotion_reflected, Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.btnEmotionClear).setOnClickListener { confirmClear() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { reload(keepSearch = true) }
        })
        adapter = EmotionAdapter(
            items,
            onClick = { onRowClick(it) },
            onLongClick = { confirmForget(it) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recyclerEmotion)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        GlassKit.attachPage(this, recycler)
        reload()
    }

    private fun query(): String = if (::search.isInitialized) search.text.toString() else ""

    private fun reload(keepSearch: Boolean = false) {
        emotion.reload()
        items.clear()
        items.addAll(emotion.listRows(query(), expanded))
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        subtitle.text = emotion.summary()
        if (!keepSearch) return
    }

    private fun onRowClick(index: Int) {
        if (index !in items.indices) return
        val row = items[index]
        if (row.expandable) {
            if (row.id in expanded) expanded.remove(row.id) else expanded.add(row.id)
            reload(keepSearch = true)
            return
        }
        showEditor(row)
    }

    private fun showEditor(row: EmotionEngine.EmotionRow?) {
        val editing = row != null && row.kind != EmotionEngine.KIND_BRANCH
        val node = row?.let { emotion.getNode(it.id) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        val kindKeys = listOf(
            EmotionEngine.KIND_LEAF,
            EmotionEngine.KIND_TWIG,
            EmotionEngine.KIND_INSIGHT,
            EmotionEngine.KIND_JOURNAL
        )
        val kindLabels = listOf(
            getString(R.string.emotion_kind_leaf),
            getString(R.string.emotion_kind_twig),
            getString(R.string.emotion_kind_insight),
            getString(R.string.emotion_kind_journal)
        )
        val kindSpinner = Spinner(this)
        kindSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, kindLabels)
        val kindIdx = kindKeys.indexOf(row?.kind ?: EmotionEngine.KIND_LEAF).coerceAtLeast(0)
        kindSpinner.setSelection(kindIdx)
        kindSpinner.isEnabled = !editing
        val parents = emotion.cueLabels()
        val parentLabels = parents.map { it.second }.ifEmpty { listOf("高兴") }
        val parentKeys = parents.map { it.first }.ifEmpty { listOf(EmotionEngine.UserCue.HAPPY.name) }
        val parentSpinner = Spinner(this)
        parentSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, parentLabels)
        val wantParent = node?.parentId ?: row?.cue ?: EmotionEngine.UserCue.HAPPY.name
        val parentIdx = parentKeys.indexOfFirst { it == wantParent || it == row?.cue }.coerceAtLeast(0)
        parentSpinner.setSelection(parentIdx)
        val input = EditText(this).apply {
            hint = getString(R.string.emotion_hint_content)
            setText(if (editing) row?.body.orEmpty() else "")
            minLines = 3
            maxLines = 8
        }
        val note = EditText(this).apply {
            hint = getString(R.string.emotion_hint_note)
            setText(row?.note.orEmpty())
            minLines = 2
            maxLines = 4
        }
        val weight = EditText(this).apply {
            hint = getString(R.string.emotion_weight)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((node?.weight ?: 2).toString())
        }
        val pin = CheckBox(this).apply {
            text = getString(R.string.emotion_pin)
            isChecked = row?.pinned == true
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
        }
        if (!editing) {
            box.addView(label(getString(R.string.emotion_label_kind)))
            box.addView(kindSpinner)
        }
        box.addView(label(getString(R.string.emotion_label_parent)))
        box.addView(parentSpinner)
        box.addView(input)
        box.addView(note)
        if (editing) box.addView(weight)
        box.addView(pin)
        AlertDialog.Builder(this)
            .setTitle(if (editing) R.string.emotion_edit else R.string.emotion_add)
            .setView(box)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val text = input.text.toString()
                val noteText = note.text.toString()
                val parentKey = parentKeys.getOrNull(parentSpinner.selectedItemPosition) ?: EmotionEngine.UserCue.HAPPY.name
                val ok = if (editing) {
                    val w = weight.text.toString().toIntOrNull() ?: (node?.weight ?: 2)
                    val updated = emotion.updateNode(row!!.id, text, noteText, w, pin.isChecked)
                    if (updated && parentKey != row.cue && parentKey != node?.parentId) {
                        emotion.moveNode(row.id, parentKey)
                    }
                    updated
                } else {
                    val kind = kindKeys.getOrNull(kindSpinner.selectedItemPosition) ?: EmotionEngine.KIND_LEAF
                    emotion.addManual(kind, parentKey, text, noteText)
                }
                if (ok) {
                    reload(keepSearch = true)
                    Toast.makeText(
                        this,
                        if (editing) R.string.toast_emotion_updated else R.string.toast_emotion_added,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, R.string.emotion_content_required, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun confirmForget(index: Int) {
        if (index !in items.indices) return
        val row = items[index]
        val msg = if (row.kind == EmotionEngine.KIND_BRANCH || row.kind == EmotionEngine.KIND_TWIG) {
            getString(R.string.emotion_confirm_forget_branch, row.title)
        } else {
            getString(R.string.emotion_confirm_forget)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.emotion_forget)
            .setMessage(msg)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                if (emotion.forget(row.id)) {
                    expanded.remove(row.id)
                    reload(keepSearch = true)
                    Toast.makeText(this, R.string.toast_emotion_forgot, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.emotion_clear)
            .setMessage(R.string.emotion_confirm_clear)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                emotion.clearTree()
                expanded.clear()
                reload()
                Toast.makeText(this, R.string.toast_emotion_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun askImportMode(uri: Uri) {
        AlertDialog.Builder(this)
            .setTitle(R.string.emotion_import)
            .setMessage(R.string.emotion_import_mode)
            .setPositiveButton(R.string.emotion_import_replace) { _, _ -> importFrom(uri, true) }
            .setNeutralButton(R.string.emotion_import_merge) { _, _ -> importFrom(uri, false) }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun importFrom(uri: Uri, replace: Boolean) {
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.toast_emotion_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val n = emotion.importJson(text, replace)
        if (n < 0) {
            Toast.makeText(this, R.string.toast_emotion_import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        reload()
        Toast.makeText(this, getString(R.string.toast_emotion_imported, n), Toast.LENGTH_SHORT).show()
    }

    private fun exportJson() {
        val dir = File(cacheDir, "emotion_export").apply { mkdirs() }
        val name = "xiaomo-emotion-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.CHINA).format(Date()) + ".json"
        val file = File(dir, name)
        val ok = runCatching { file.writeText(emotion.exportJson()) }.isSuccess
        if (!ok || !file.isFile || file.length() <= 0L) {
            Toast.makeText(this, R.string.toast_emotion_export_failed, Toast.LENGTH_SHORT).show()
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
                getString(R.string.emotion_export)
            )
        )
        Toast.makeText(this, getString(R.string.toast_emotion_exported, file.name), Toast.LENGTH_SHORT).show()
    }

    private class EmotionAdapter(
        private val items: List<EmotionEngine.EmotionRow>,
        private val onClick: (Int) -> Unit,
        private val onLongClick: (Int) -> Unit
    ) : RecyclerView.Adapter<EmotionAdapter.VH>() {
        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val kind: TextView = view.findViewById(R.id.tvEmotionKind)
            val body: TextView = view.findViewById(R.id.tvEmotionBody)
            val meta: TextView = view.findViewById(R.id.tvEmotionMeta)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_emotion, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val mark = when {
                item.expandable && item.expanded -> "▾ "
                item.expandable -> "▸ "
                else -> ""
            }
            holder.kind.text = mark + item.title
            holder.body.text = item.body
            holder.meta.text = item.meta
            val pad = (16 + item.depth * 18) * holder.itemView.resources.displayMetrics.density
            holder.itemView.setPadding(
                pad.toInt(),
                holder.itemView.paddingTop,
                holder.itemView.paddingRight,
                holder.itemView.paddingBottom
            )
            holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
            holder.itemView.setOnLongClickListener {
                onLongClick(holder.bindingAdapterPosition)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}