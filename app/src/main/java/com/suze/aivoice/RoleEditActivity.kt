package com.suze.aivoice

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

class RoleEditActivity : AppCompatActivity() {
    private lateinit var store: RoleStore
    private var roleId: String = ""
    private var pendingAvatar = false
    private val worldIds = mutableListOf<String>()
    private val exampleViews = mutableListOf<View>()
    private val worldViews = mutableListOf<View>()

    private val pickAvatar = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        ensureRoleId()
        if (store.saveAvatar(roleId, uri)) {
            pendingAvatar = true
            refreshAvatarPreview()
        } else {
            Toast.makeText(this, R.string.toast_image_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private val importWorld = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val text = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
        val entries = text?.let { TavernCardIO.worldInfoFromJson(it) }.orEmpty()
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.toast_world_import_empty, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        entries.forEach { addWorldRow(it) }
        Toast.makeText(this, getString(R.string.toast_world_imported, entries.size), Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_edit)
        GlassKit.attachPage(this, findViewById(R.id.pageScroll))
        store = RoleStore(this)
        roleId = intent.getStringExtra(EXTRA_ROLE_ID).orEmpty()
        val existing = roleId.takeIf { it.isNotBlank() }?.let { store.get(it) }
        findViewById<TextView>(R.id.tvRoleEditTitle).setText(
            if (existing == null) R.string.role_add_title else R.string.role_edit_title
        )
        findViewById<View>(R.id.btnRoleEditBack).setOnClickListener { finish() }

        val editName = findViewById<EditText>(R.id.editRoleName)
        val editEmoji = findViewById<EditText>(R.id.editRoleEmoji)
        val editIntro = findViewById<EditText>(R.id.editRoleIntro)
        val editUserName = findViewById<EditText>(R.id.editRoleUserName)
        val editGreeting = findViewById<EditText>(R.id.editRoleGreeting)
        val editAltGreetings = findViewById<EditText>(R.id.editRoleAltGreetings)
        val editPersona = findViewById<EditText>(R.id.editRolePersona)
        val editDescription = findViewById<EditText>(R.id.editRoleDescription)
        val editPersonality = findViewById<EditText>(R.id.editRolePersonality)
        val editScenario = findViewById<EditText>(R.id.editRoleScenario)
        val editMesExample = findViewById<EditText>(R.id.editRoleMesExample)
        val editPostHistory = findViewById<EditText>(R.id.editRolePostHistory)
        val editTags = findViewById<EditText>(R.id.editRoleTags)
        val editCreator = findViewById<EditText>(R.id.editRoleCreator)
        val spinnerVoice = findViewById<Spinner>(R.id.spinnerRoleVoice)
        val voiceOptions = listOf(getString(R.string.role_voice_default)) + TtsHelper(this, Prefs(this)).availableVoices()
        spinnerVoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceOptions)

        if (existing != null) {
            editName.setText(existing.name)
            editEmoji.setText(existing.emoji)
            editIntro.setText(existing.intro)
            editUserName.setText(existing.userName)
            editGreeting.setText(existing.greeting)
            editAltGreetings.setText(existing.alternateGreetings.joinToString("\n"))
            editPersona.setText(existing.persona.ifBlank { existing.systemPrompt })
            editDescription.setText(existing.description)
            editPersonality.setText(existing.personality)
            editScenario.setText(existing.scenario)
            editMesExample.setText(existing.mesExample)
            editPostHistory.setText(existing.postHistory)
            editTags.setText(existing.tags)
            editCreator.setText(existing.creator)
            val vIdx = voiceOptions.indexOf(existing.voiceName).takeIf { it >= 0 } ?: 0
            spinnerVoice.setSelection(vIdx)
            if (existing.examples.isEmpty()) addExampleRow()
            else existing.examples.forEach { addExampleRow(it) }
            if (existing.worldEntries.isEmpty()) addWorldRow()
            else existing.worldEntries.forEach { addWorldRow(it) }
        } else {
            editEmoji.setText("\uD83C\uDFAD")
            editUserName.setText("主人")
            addExampleRow()
            addWorldRow()
        }
        refreshAvatarPreview()

        findViewById<Button>(R.id.btnRolePickAvatar).setOnClickListener {
            pickAvatar.launch("image/*")
        }
        findViewById<Button>(R.id.btnRoleClearAvatar).setOnClickListener {
            if (roleId.isNotBlank()) store.clearAvatar(roleId)
            pendingAvatar = false
            refreshAvatarPreview()
        }
        findViewById<Button>(R.id.btnRoleExportJson).setOnClickListener { exportCard(png = false) }
        findViewById<Button>(R.id.btnRoleExportPng).setOnClickListener { exportCard(png = true) }
        findViewById<Button>(R.id.btnRoleAddExample).setOnClickListener { addExampleRow() }
        findViewById<Button>(R.id.btnRoleAddWorld).setOnClickListener { addWorldRow() }
        findViewById<Button>(R.id.btnRoleImportWorld).setOnClickListener {
            importWorld.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        findViewById<Button>(R.id.btnRoleExportWorld).setOnClickListener { exportWorld() }

        findViewById<Button>(R.id.btnRoleSave).setOnClickListener {
            val name = editName.text.toString().trim()
            if (name.isEmpty()) {
                editName.error = getString(R.string.role_name_required)
                editName.requestFocus()
                return@setOnClickListener
            }
            ensureRoleId()
            val previous = store.get(roleId)
            val alts = editAltGreetings.text.toString().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val examples = collectExamples()
            val world = collectWorld()
            val avatarName = store.avatarFile(roleId).takeIf { it.isFile }?.name.orEmpty()
            val persona = editPersona.text.toString().trim().take(8000)
            val pickedVoice = spinnerVoice.selectedItem?.toString().orEmpty()
            val voiceName = if (pickedVoice == getString(R.string.role_voice_default)) "" else pickedVoice
            val saved = RoleCharacter(
                id = roleId,
                name = name.take(48),
                emoji = editEmoji.text.toString().trim().ifBlank { "\uD83C\uDFAD" }.take(8),
                intro = editIntro.text.toString().trim().take(80),
                greeting = editGreeting.text.toString().trim().take(800),
                persona = persona,
                description = editDescription.text.toString().trim().take(8000),
                personality = editPersonality.text.toString().trim().take(4000),
                scenario = editScenario.text.toString().trim().take(4000),
                mesExample = editMesExample.text.toString().trim().take(8000),
                systemPrompt = persona,
                postHistory = editPostHistory.text.toString().trim().take(2000),
                userName = editUserName.text.toString().trim().ifBlank { "主人" }.take(24),
                tags = editTags.text.toString().trim().take(120),
                alternateGreetings = alts,
                examples = examples,
                worldEntries = world,
                avatarFile = avatarName,
                voiceName = voiceName,
                creator = editCreator.text.toString().trim().ifBlank { previous?.creator.orEmpty() }.take(40),
                updatedAt = System.currentTimeMillis()
            )
            store.upsert(saved)
            Toast.makeText(this, R.string.toast_role_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun addExampleRow(example: RoleExample? = null) {
        if (exampleViews.size >= 16) return
        val host = findViewById<LinearLayout>(R.id.listRoleExamples)
        val row = LayoutInflater.from(this).inflate(R.layout.item_role_example, host, false)
        row.findViewById<EditText>(R.id.editExampleUser).setText(example?.user.orEmpty())
        row.findViewById<EditText>(R.id.editExampleChar).setText(example?.assistant.orEmpty())
        row.findViewById<View>(R.id.btnExampleDelete).setOnClickListener {
            if (exampleViews.size <= 1) {
                row.findViewById<EditText>(R.id.editExampleUser).setText("")
                row.findViewById<EditText>(R.id.editExampleChar).setText("")
                return@setOnClickListener
            }
            host.removeView(row)
            exampleViews.remove(row)
            refreshExampleLabels()
        }
        host.addView(row)
        exampleViews.add(row)
        refreshExampleLabels()
    }

    private fun addWorldRow(entry: WorldEntry? = null) {
        if (worldViews.size >= 32) return
        val host = findViewById<LinearLayout>(R.id.listRoleWorld)
        val row = LayoutInflater.from(this).inflate(R.layout.item_role_world, host, false)
        val id = entry?.id ?: RoleStore.newId()
        row.tag = id
        row.findViewById<EditText>(R.id.editWorldKeys).setText(entry?.keys.orEmpty())
        row.findViewById<EditText>(R.id.editWorldContent).setText(entry?.content.orEmpty())
        row.findViewById<CheckBox>(R.id.checkWorldEnabled).isChecked = entry?.enabled ?: true
        row.findViewById<CheckBox>(R.id.checkWorldConstant).isChecked =
            entry?.constant ?: entry?.keys.isNullOrBlank()
        row.findViewById<View>(R.id.btnWorldDelete).setOnClickListener {
            if (worldViews.size <= 1) {
                row.findViewById<EditText>(R.id.editWorldKeys).setText("")
                row.findViewById<EditText>(R.id.editWorldContent).setText("")
                row.findViewById<CheckBox>(R.id.checkWorldEnabled).isChecked = true
                row.findViewById<CheckBox>(R.id.checkWorldConstant).isChecked = false
                return@setOnClickListener
            }
            host.removeView(row)
            worldViews.remove(row)
            worldIds.remove(id)
            refreshWorldLabels()
        }
        host.addView(row)
        worldViews.add(row)
        worldIds.add(id)
        refreshWorldLabels()
    }

    private fun refreshExampleLabels() {
        exampleViews.forEachIndexed { index, view ->
            view.findViewById<TextView>(R.id.tvExampleIndex).text =
                getString(R.string.role_example_index, index + 1)
        }
    }

    private fun refreshWorldLabels() {
        worldViews.forEachIndexed { index, view ->
            view.findViewById<TextView>(R.id.tvWorldIndex).text =
                getString(R.string.role_world_index, index + 1)
        }
    }

    private fun collectExamples(): List<RoleExample> {
        return exampleViews.mapNotNull { view ->
            val user = view.findViewById<EditText>(R.id.editExampleUser).text.toString().trim()
            val assistant = view.findViewById<EditText>(R.id.editExampleChar).text.toString().trim()
            if (user.isEmpty() && assistant.isEmpty()) null else RoleExample(user, assistant)
        }.take(16)
    }

    private fun collectWorld(): List<WorldEntry> {
        return worldViews.mapIndexedNotNull { index, view ->
            val keys = view.findViewById<EditText>(R.id.editWorldKeys).text.toString().trim()
            val content = view.findViewById<EditText>(R.id.editWorldContent).text.toString().trim()
            if (keys.isEmpty() && content.isEmpty()) return@mapIndexedNotNull null
            val enabled = view.findViewById<CheckBox>(R.id.checkWorldEnabled).isChecked
            val constant = view.findViewById<CheckBox>(R.id.checkWorldConstant).isChecked || keys.isEmpty()
            WorldEntry(
                id = (view.tag as? String).orEmpty().ifBlank { RoleStore.newId() },
                keys = keys,
                content = content,
                enabled = enabled,
                constant = constant,
                order = (index + 1) * 100
            )
        }.take(32)
    }

    private fun ensureRoleId() {
        if (roleId.isBlank()) roleId = RoleStore.newId()
    }

    private fun refreshAvatarPreview() {
        val view = findViewById<ImageView>(R.id.ivRoleEditAvatar)
        val emoji = findViewById<TextView>(R.id.tvRoleEditEmojiPreview)
        if (roleId.isBlank()) {
            view.visibility = View.GONE
            emoji.visibility = View.VISIBLE
            return
        }
        val current = store.get(roleId) ?: RoleCharacter(id = roleId, name = "")
        store.applyAvatar(view, current.copy(avatarFile = store.avatarFile(roleId).name))
        val has = store.avatarFile(roleId).isFile || PortraitLibrary.resFor(roleId) != null
        view.visibility = if (has) View.VISIBLE else View.GONE
        emoji.visibility = if (has) View.GONE else View.VISIBLE
        emoji.text = findViewById<EditText>(R.id.editRoleEmoji).text.toString().ifBlank { "\uD83C\uDFAD" }
    }

    /** 导出当前编辑中的世界书为标准 World Info JSON，可分享给别的酒馆。 */
    private fun exportWorld() {
        val entries = collectWorld()
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.toast_world_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(cacheDir, "role_export")
        dir.mkdirs()
        val base = findViewById<EditText>(R.id.editRoleName).text.toString().trim()
            .ifBlank { getString(R.string.role_label_world) }
        val file = File(dir, RoleStore.sanitize(base) + "_world.json")
        try {
            file.writeText(TavernCardIO.worldInfoToJson(entries))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_role_export_failed, Toast.LENGTH_SHORT).show()
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
                getString(R.string.role_export_world)
            )
        )
    }

    private fun exportCard(png: Boolean) {
        val current = collectOrExisting() ?: run {
            Toast.makeText(this, R.string.role_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(cacheDir, "role_export")
        val file = try {
            if (png) TavernCardIO.writeExportPng(dir, current, store.avatarAbs(current).takeIf { it.isFile })
            else TavernCardIO.writeExportJson(dir, current)
        } catch (_: Exception) {
            null
        }
        if (file == null || !file.isFile) {
            Toast.makeText(this, R.string.toast_role_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = if (png) "image/png" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(if (png) R.string.role_export_png else R.string.role_export_json)))
        Toast.makeText(this, getString(R.string.toast_role_exported, file.name), Toast.LENGTH_SHORT).show()
    }

    private fun collectOrExisting(): RoleCharacter? {
        val name = findViewById<EditText>(R.id.editRoleName).text.toString().trim()
        if (name.isEmpty()) return roleId.takeIf { it.isNotBlank() }?.let { store.get(it) }
        ensureRoleId()
        val previous = store.get(roleId)
        val persona = findViewById<EditText>(R.id.editRolePersona).text.toString().trim()
        return (previous ?: RoleCharacter(id = roleId, name = name)).copy(
            name = name.take(48),
            emoji = findViewById<EditText>(R.id.editRoleEmoji).text.toString().trim().ifBlank { "\uD83C\uDFAD" },
            intro = findViewById<EditText>(R.id.editRoleIntro).text.toString().trim(),
            greeting = findViewById<EditText>(R.id.editRoleGreeting).text.toString().trim(),
            persona = persona,
            description = findViewById<EditText>(R.id.editRoleDescription).text.toString().trim(),
            personality = findViewById<EditText>(R.id.editRolePersonality).text.toString().trim(),
            scenario = findViewById<EditText>(R.id.editRoleScenario).text.toString().trim(),
            mesExample = findViewById<EditText>(R.id.editRoleMesExample).text.toString().trim(),
            systemPrompt = persona,
            postHistory = findViewById<EditText>(R.id.editRolePostHistory).text.toString().trim(),
            userName = findViewById<EditText>(R.id.editRoleUserName).text.toString().trim().ifBlank { "主人" },
            tags = findViewById<EditText>(R.id.editRoleTags).text.toString().trim(),
            alternateGreetings = findViewById<EditText>(R.id.editRoleAltGreetings).text.toString()
                .split("\n").map { it.trim() }.filter { it.isNotEmpty() },
            examples = collectExamples(),
            worldEntries = collectWorld(),
            creator = findViewById<EditText>(R.id.editRoleCreator).text.toString().trim(),
            updatedAt = System.currentTimeMillis()
        )
    }

    companion object {
        const val EXTRA_ROLE_ID = "role_id"
    }
}