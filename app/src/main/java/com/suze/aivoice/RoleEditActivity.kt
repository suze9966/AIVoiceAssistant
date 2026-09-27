package com.suze.aivoice

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class RoleEditActivity : AppCompatActivity() {
    private lateinit var store: RoleStore
    private var roleId: String = ""

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
        val editGreeting = findViewById<EditText>(R.id.editRoleGreeting)
        val editPersona = findViewById<EditText>(R.id.editRolePersona)
        if (existing != null) {
            editName.setText(existing.name)
            editEmoji.setText(existing.emoji)
            editIntro.setText(existing.intro)
            editGreeting.setText(existing.greeting)
            editPersona.setText(existing.persona)
        } else {
            editEmoji.setText("\uD83C\uDFAD")
        }
        findViewById<Button>(R.id.btnRoleSave).setOnClickListener {
            val name = editName.text.toString().trim()
            if (name.isEmpty()) {
                editName.error = getString(R.string.role_name_required)
                editName.requestFocus()
                return@setOnClickListener
            }
            val saved = RoleCharacter(
                id = if (roleId.isBlank()) RoleStore.newId() else roleId,
                name = name.take(24),
                emoji = editEmoji.text.toString().trim().ifBlank { "\uD83C\uDFAD" }.take(8),
                intro = editIntro.text.toString().trim().take(80),
                greeting = editGreeting.text.toString().trim().take(200),
                persona = editPersona.text.toString().trim().take(4000)
            )
            store.upsert(saved)
            Toast.makeText(this, R.string.toast_role_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_ROLE_ID = "role_id"
    }
}