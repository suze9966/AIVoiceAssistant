package com.suze.aivoice

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class GroupEditActivity : AppCompatActivity() {
    private lateinit var store: GroupStore
    private lateinit var roles: RoleStore
    private var groupId: String = ""
    private val checks = mutableListOf<Pair<String, CheckBox>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_edit)
        GlassKit.attachPage(this, findViewById(R.id.pageScroll))
        store = GroupStore(this)
        roles = RoleStore(this)
        groupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        val existing = groupId.takeIf { it.isNotBlank() }?.let { store.get(it) }
        if (groupId.isNotBlank() && existing == null) {
            Toast.makeText(this, R.string.toast_group_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<TextView>(R.id.tvGroupEditTitle).setText(
            if (existing == null) R.string.group_add else R.string.group_edit_title
        )
        findViewById<View>(R.id.btnGroupEditBack).setOnClickListener { finish() }

        val editName = findViewById<EditText>(R.id.editGroupName)
        val editScene = findViewById<EditText>(R.id.editGroupScene)
        val editGreeting = findViewById<EditText>(R.id.editGroupGreeting)
        if (existing != null) {
            editName.setText(existing.name)
            editScene.setText(existing.scene)
            editGreeting.setText(existing.greeting)
        }

        val host = findViewById<LinearLayout>(R.id.listGroupMembers)
        val selected = existing?.memberIds?.toSet().orEmpty()
        val characters = roles.loadCharacters()
        if (characters.isEmpty()) {
            Toast.makeText(this, R.string.group_need_roles, Toast.LENGTH_SHORT).show()
        }
        characters.forEach { c ->
            val row = LayoutInflater.from(this).inflate(R.layout.item_group_member, host, false)
            val box = row.findViewById<CheckBox>(R.id.checkGroupMember)
            box.text = (c.emoji.ifBlank { "\uD83C\uDFAD" } + "  " + c.name)
            box.isChecked = c.id in selected
            box.setOnCheckedChangeListener { _, checked ->
                if (checked && checks.count { it.second.isChecked } > 6) {
                    box.isChecked = false
                    Toast.makeText(this, R.string.group_member_limit, Toast.LENGTH_SHORT).show()
                }
            }
            host.addView(row)
            checks.add(c.id to box)
        }

        findViewById<Button>(R.id.btnGroupSave).setOnClickListener {
            val name = editName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.group_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val members = checks.filter { it.second.isChecked }.map { it.first }
            if (members.size < 2) {
                Toast.makeText(this, R.string.group_need_members, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (groupId.isBlank()) {
                if (store.loadRooms().size >= GroupStore.MAX_ROOMS) {
                    Toast.makeText(this, R.string.toast_group_limit, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                groupId = GroupStore.newId()
            }
            store.upsert(
                GroupRoom(
                    id = groupId,
                    name = name,
                    memberIds = members.take(6),
                    scene = editScene.text.toString().trim(),
                    greeting = editGreeting.text.toString().trim(),
                    updatedAt = System.currentTimeMillis(),
                    preview = existing?.preview.orEmpty()
                )
            )
            Toast.makeText(this, R.string.toast_group_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
    }
}