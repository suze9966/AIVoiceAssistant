package com.suze.aivoice

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.regex.Pattern

class TavernPluginEditActivity : AppCompatActivity() {
    private lateinit var store: TavernPluginStore
    private var pluginId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tavern_plugin_edit)
        GlassKit.attachPage(this, findViewById(R.id.pageScroll))
        store = TavernPluginStore(this)
        pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID).orEmpty()
        val existing = pluginId.takeIf { it.isNotBlank() }?.let { id -> store.load().firstOrNull { it.id == id } }
        if (pluginId.isNotBlank() && existing == null) {
            Toast.makeText(this, R.string.toast_plugin_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.tvPluginEditTitle).setText(
            if (existing == null) R.string.plugin_add else R.string.plugin_edit_title
        )
        findViewById<View>(R.id.btnPluginEditBack).setOnClickListener { finish() }

        val targets = listOf(
            TavernPlugin.TARGET_OUTPUT to getString(R.string.plugin_target_output),
            TavernPlugin.TARGET_INPUT to getString(R.string.plugin_target_input),
            TavernPlugin.TARGET_PROMPT to getString(R.string.plugin_target_prompt)
        )
        val spinner = findViewById<Spinner>(R.id.spinnerPluginTarget)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, targets.map { it.second })

        val editName = findViewById<EditText>(R.id.editPluginName)
        val editTrigger = findViewById<EditText>(R.id.editPluginTrigger)
        val editReplace = findViewById<EditText>(R.id.editPluginReplace)
        val editNotes = findViewById<EditText>(R.id.editPluginNotes)
        val checkEnabled = findViewById<CheckBox>(R.id.checkPluginEditEnabled)

        if (existing != null) {
            editName.setText(existing.name)
            editTrigger.setText(existing.trigger)
            editReplace.setText(existing.replace)
            editNotes.setText(existing.notes)
            checkEnabled.isChecked = existing.enabled
            val idx = targets.indexOfFirst { it.first == existing.target }.coerceAtLeast(0)
            spinner.setSelection(idx)
        }

        findViewById<Button>(R.id.btnPluginSave).setOnClickListener {
            val name = editName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.plugin_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val trigger = editTrigger.text.toString()
            if (trigger.isBlank()) {
                Toast.makeText(this, R.string.plugin_trigger_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (runCatching { Pattern.compile(trigger) }.isFailure) {
                Toast.makeText(this, R.string.toast_plugin_bad_regex, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pluginId.isBlank() && store.load().size >= 32) {
                Toast.makeText(this, R.string.toast_plugin_limit, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pluginId.isBlank()) pluginId = TavernPluginStore.newId()
            val target = targets.getOrNull(spinner.selectedItemPosition)?.first ?: TavernPlugin.TARGET_OUTPUT
            store.upsert(
                TavernPlugin(
                    id = pluginId,
                    name = name,
                    enabled = checkEnabled.isChecked,
                    trigger = trigger,
                    replace = editReplace.text.toString(),
                    target = target,
                    notes = editNotes.text.toString().trim()
                )
            )
            Toast.makeText(this, R.string.toast_plugin_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_PLUGIN_ID = "plugin_id"
    }
}
