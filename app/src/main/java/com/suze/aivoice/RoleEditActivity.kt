package com.suze.aivoice

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_edit)
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
        val editExampleUser = findViewById<EditText>(R.id.editRoleExampleUser)
        val editExampleChar = findViewById<EditText>(R.id.editRoleExampleChar)
        val editWorldKeys = findViewById<EditText>(R.id.editRoleWorldKeys)
        val editWorldContent = findViewById<EditText>(R.id.editRoleWorldContent)
        val editPostHistory = findViewById<EditText>(R.id.editRolePostHistory)
        val editTags = findViewById<EditText>(R.id.editRoleTags)

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
            val example = existing.examples.firstOrNull()
            editExampleUser.setText(example?.user.orEmpty())
            editExampleChar.setText(example?.assistant.orEmpty())
            val world = existing.worldEntries.firstOrNull()
            editWorldKeys.setText(world?.keys.orEmpty())
            editWorldContent.setText(world?.content.orEmpty())
            editPostHistory.setText(existing.postHistory)
            editTags.setText(existing.tags)
        } else {
            editEmoji.setText("\uD83C\uDFAD")
            editUserName.setText("主人")
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
            val exampleUser = editExampleUser.text.toString().trim()
            val exampleChar = editExampleChar.text.toString().trim()
            val examples = if (exampleUser.isEmpty() && exampleChar.isEmpty()) {
                previous?.examples.orEmpty()
            } else {
                listOf(RoleExample(exampleUser, exampleChar))
            }
            val worldKeys = editWorldKeys.text.toString().trim()
            val worldContent = editWorldContent.text.toString().trim()
            val world = if (worldKeys.isEmpty() && worldContent.isEmpty()) {
                previous?.worldEntries.orEmpty()
            } else {
                listOf(
                    WorldEntry(
                        id = previous?.worldEntries?.firstOrNull()?.id ?: RoleStore.newId(),
                        keys = worldKeys,
                        content = worldContent,
                        enabled = true
                    )
                )
            }
            val avatarName = store.avatarFile(roleId).takeIf { it.isFile }?.name.orEmpty()
            val saved = RoleCharacter(
                id = roleId,
                name = name.take(24),
                emoji = editEmoji.text.toString().trim().ifBlank { "\uD83C\uDFAD" }.take(8),
                intro = editIntro.text.toString().trim().take(80),
                greeting = editGreeting.text.toString().trim().take(400),
                persona = editPersona.text.toString().trim().take(4000),
                description = editDescription.text.toString().trim().take(4000),
                personality = editPersonality.text.toString().trim().take(2000),
                scenario = editScenario.text.toString().trim().take(2000),
                mesExample = previous?.mesExample.orEmpty(),
                systemPrompt = previous?.systemPrompt.orEmpty(),
                postHistory = editPostHistory.text.toString().trim().take(1000),
                userName = editUserName.text.toString().trim().ifBlank { "主人" }.take(24),
                tags = editTags.text.toString().trim().take(80),
                alternateGreetings = alts,
                examples = examples,
                worldEntries = world,
                avatarFile = avatarName,
                creator = previous?.creator.orEmpty(),
                updatedAt = System.currentTimeMillis()
            )
            store.upsert(saved)
            Toast.makeText(this, R.string.toast_role_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
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
        val has = store.avatarFile(roleId).isFile
        view.visibility = if (has) View.VISIBLE else View.GONE
        emoji.visibility = if (has) View.GONE else View.VISIBLE
        emoji.text = findViewById<EditText>(R.id.editRoleEmoji).text.toString().ifBlank { "\uD83C\uDFAD" }
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
        return store.get(roleId)?.copy(name = name) ?: RoleCharacter(id = roleId, name = name)
    }

    companion object {
        const val EXTRA_ROLE_ID = "role_id"
    }
}
