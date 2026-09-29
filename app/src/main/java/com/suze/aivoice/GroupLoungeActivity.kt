package com.suze.aivoice

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Date

class GroupLoungeActivity : AppCompatActivity() {
    private lateinit var store: GroupStore
    private lateinit var adapter: GroupCardAdapter
    private lateinit var emptyView: TextView
    private val items = mutableListOf<GroupRoom>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_lounge)
        store = GroupStore(this)
        emptyView = findViewById(R.id.tvGroupEmpty)
        findViewById<View>(R.id.btnGroupBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnGroupAdd).setOnClickListener {
            if (RoleStore(this).loadCharacters().size < 2) {
                Toast.makeText(this, R.string.group_need_roles, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (store.loadRooms().size >= GroupStore.MAX_ROOMS) {
                Toast.makeText(this, R.string.toast_group_limit, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startActivity(Intent(this, GroupEditActivity::class.java))
        }
        adapter = GroupCardAdapter(
            items,
            store,
            onClick = { openChat(it) },
            onLongClick = { view, room -> showCardMenu(view, room) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recyclerGroups)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        GlassKit.attachPage(this, recycler)
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        items.clear()
        items.addAll(store.loadRooms())
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openChat(room: GroupRoom) {
        startActivity(
            Intent(this, GroupChatActivity::class.java)
                .putExtra(GroupChatActivity.EXTRA_GROUP_ID, room.id)
        )
    }

    private fun showCardMenu(anchor: View, room: GroupRoom) {
        val pop = PopupMenu(this, anchor)
        pop.menu.add(0, 1, 0, getString(R.string.group_edit_title))
        pop.menu.add(0, 2, 1, getString(R.string.group_delete))
        pop.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    startActivity(
                        Intent(this, GroupEditActivity::class.java)
                            .putExtra(GroupEditActivity.EXTRA_GROUP_ID, room.id)
                    )
                    true
                }
                2 -> {
                    confirmDelete(room)
                    true
                }
                else -> false
            }
        }
        pop.show()
    }

    private fun confirmDelete(room: GroupRoom) {
        AlertDialog.Builder(this)
            .setMessage(R.string.group_confirm_delete)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                store.delete(room.id)
                reload()
                Toast.makeText(this, R.string.toast_group_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private class GroupCardAdapter(
        private val items: List<GroupRoom>,
        private val store: GroupStore,
        private val onClick: (GroupRoom) -> Unit,
        private val onLongClick: (View, GroupRoom) -> Unit
    ) : RecyclerView.Adapter<GroupCardAdapter.VH>() {
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
            holder.emoji.text = "\uD83D\uDC65"
            holder.emoji.visibility = View.VISIBLE
            holder.avatar.visibility = View.GONE
            holder.name.text = item.name
            val members = store.membersOf(item)
            val names = members.joinToString("、") { it.name }
            holder.intro.text = if (names.isBlank()) {
                ctx.getString(R.string.group_preview_empty)
            } else {
                ctx.getString(R.string.group_members_fmt, members.size, names)
            }
            holder.tags.visibility = View.GONE
            holder.preview.text = item.preview.ifBlank { ctx.getString(R.string.group_preview_empty) }
            holder.time.text = if (item.updatedAt > 0L) formatTime(item.updatedAt) else ""
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
