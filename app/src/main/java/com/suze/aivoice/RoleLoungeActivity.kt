package com.suze.aivoice

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

class RoleLoungeActivity : AppCompatActivity() {
    private lateinit var store: RoleStore
    private lateinit var prefs: Prefs
    private lateinit var llm: LlmClient
    private lateinit var adapter: RoleCardAdapter
    private lateinit var emptyView: TextView
    private val allItems = mutableListOf<RoleCharacter>()
    private val items = mutableListOf<RoleCharacter>()
    private var query: String = ""

    private val importCard = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val imported = TavernCardIO.importUriFull(this, uri)
        if (imported == null) {
            Toast.makeText(this, R.string.toast_role_import_failed, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val character = imported.character.copy(updatedAt = System.currentTimeMillis())
        store.upsert(character)
        imported.avatarBytes?.let { bytes -> store.saveAvatarFromBytes(character.id, bytes) }
        reload()
        Toast.makeText(this, R.string.toast_role_imported, Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_lounge)
        store = RoleStore(this)
        prefs = Prefs(this)
        llm = LlmClient(prefs)
        emptyView = findViewById(R.id.tvRoleEmpty)
        findViewById<View>(R.id.btnRoleBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRoleAdd).setOnClickListener {
            startActivity(Intent(this, RoleEditActivity::class.java))
        }
        findViewById<View>(R.id.btnRoleImport).setOnClickListener {
            importCard.launch(arrayOf("application/json", "image/png", "image/*", "*/*"))
        }
        findViewById<Button>(R.id.btnConnectLlm).setOnClickListener {
            startActivity(Intent(this, LlmConnectActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenPlugins).setOnClickListener {
            startActivity(Intent(this, TavernPluginActivity::class.java))
        }
        findViewById<Button>(R.id.btnOpenGroups).setOnClickListener {
            startActivity(Intent(this, GroupLoungeActivity::class.java))
        }
        adapter = RoleCardAdapter(
            items,
            store,
            onClick = { openChat(it) },
            onLongClick = { view, character -> showCardMenu(view, character) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recyclerRoles)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        findViewById<EditText>(R.id.editRoleSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty().trim()
                applyFilter()
            }
        })
        GlassKit.attachPage(this, recycler)
    }

    override fun onResume() {
        super.onResume()
        reload()
        refreshLlmStatus()
    }

    private fun reload() {
        allItems.clear()
        allItems.addAll(store.loadCharacters())
        applyFilter()
    }

    private fun applyFilter() {
        val q = query.lowercase()
        items.clear()
        if (q.isEmpty()) {
            items.addAll(allItems)
        } else {
            items.addAll(allItems.filter { c ->
                c.name.contains(query, ignoreCase = true) ||
                    c.intro.contains(query, ignoreCase = true) ||
                    c.description.contains(query, ignoreCase = true) ||
                    c.tags.contains(query, ignoreCase = true) ||
                    c.tagList().any { it.contains(query, ignoreCase = true) }
            })
        }
        adapter.notifyDataSetChanged()
        emptyView.text = when {
            allItems.isEmpty() -> getString(R.string.role_lounge_empty)
            items.isEmpty() -> getString(R.string.role_search_empty)
            else -> getString(R.string.role_lounge_empty)
        }
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshLlmStatus() {
        val tv = findViewById<TextView>(R.id.tvLlmStatus)
        if (!llm.isConfigured()) {
            tv.text = getString(R.string.llm_status_off)
            return
        }
        val model = prefs.model
        tv.text = if (prefs.cloudThinkEnabled) {
            getString(R.string.llm_status_on_think, model)
        } else {
            getString(R.string.llm_status_on, model)
        }
    }

    private fun openChat(character: RoleCharacter) {
        startActivity(
            Intent(this, RoleChatActivity::class.java)
                .putExtra(RoleChatActivity.EXTRA_ROLE_ID, character.id)
        )
    }

    private fun showCardMenu(anchor: View, character: RoleCharacter) {
        val pop = PopupMenu(this, anchor)
        pop.menu.add(0, 1, 0, getString(R.string.role_edit))
        pop.menu.add(0, 4, 1, getString(R.string.role_chats))
        pop.menu.add(0, 3, 2, getString(R.string.role_duplicate))
        pop.menu.add(0, 2, 3, getString(R.string.role_delete))
        pop.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    startActivity(
                        Intent(this, RoleEditActivity::class.java)
                            .putExtra(RoleEditActivity.EXTRA_ROLE_ID, character.id)
                    )
                    true
                }
                4 -> {
                    startActivity(
                        Intent(this, RoleChatActivity::class.java)
                            .putExtra(RoleChatActivity.EXTRA_ROLE_ID, character.id)
                            .putExtra(RoleChatActivity.EXTRA_OPEN_CHATS, true)
                    )
                    true
                }
                3 -> {
                    val copy = store.duplicate(character.id)
                    if (copy != null) {
                        reload()
                        Toast.makeText(this, R.string.toast_role_duplicated, Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                2 -> {
                    confirmDelete(character)
                    true
                }
                else -> false
            }
        }
        pop.show()
    }

    private fun confirmDelete(character: RoleCharacter) {
        AlertDialog.Builder(this)
            .setMessage(R.string.role_confirm_delete)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                store.delete(character.id)
                reload()
                Toast.makeText(this, R.string.toast_role_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private class RoleCardAdapter(
        private val items: List<RoleCharacter>,
        private val store: RoleStore,
        private val onClick: (RoleCharacter) -> Unit,
        private val onLongClick: (View, RoleCharacter) -> Unit
    ) : RecyclerView.Adapter<RoleCardAdapter.VH>() {
        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val emoji: TextView = view.findViewById(R.id.tvRoleEmoji)
            val avatar: ImageView = view.findViewById(R.id.ivRoleAvatar)
            val name: TextView = view.findViewById(R.id.tvRoleName)
            val intro: TextView = view.findViewById(R.id.tvRoleIntro)
            val tags: TextView = view.findViewById(R.id.tvRoleTags)
            val preview: TextView = view.findViewById(R.id.tvRolePreview)
            val time: TextView = view.findViewById(R.id.tvRoleTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_role_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val ctx = holder.itemView.context
            holder.emoji.text = item.emoji.ifBlank { "\uD83C\uDFAD" }
            holder.name.text = item.name
            holder.intro.text = item.displayIntro().ifBlank {
                ctx.getString(R.string.role_intro_empty)
            }
            val tags = item.tagList()
            if (tags.isEmpty()) {
                holder.tags.visibility = View.GONE
            } else {
                holder.tags.visibility = View.VISIBLE
                holder.tags.text = tags.take(4).joinToString(" · ")
            }
            val (preview, updatedAt) = store.lastPreview(item.id)
            holder.preview.text = preview.ifBlank { ctx.getString(R.string.role_preview_empty) }
            holder.time.text = if (updatedAt > 0L) formatTime(updatedAt) else ""
            store.applyAvatar(holder.avatar, item)
            val hasArt = store.avatarAbs(item).isFile || PortraitLibrary.resFor(item.id) != null
            holder.avatar.visibility = if (hasArt) View.VISIBLE else View.GONE
            holder.emoji.visibility = if (hasArt) View.GONE else View.VISIBLE
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                onLongClick(holder.itemView, item)
                true
            }
        }

        override fun getItemCount(): Int = items.size

        private fun formatTime(ms: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - ms
            return when {
                diff < 60_000L -> "刚刚"
                diff < 3_600_000L -> "${diff / 60_000L}分钟前"
                else -> DateFormat.format("MM-dd HH:mm", Date(ms)).toString()
            }
        }
    }
}
