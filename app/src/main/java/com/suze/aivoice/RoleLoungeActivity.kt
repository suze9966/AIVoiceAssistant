package com.suze.aivoice

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class RoleLoungeActivity : AppCompatActivity() {
    private lateinit var store: RoleStore
    private lateinit var prefs: Prefs
    private lateinit var llm: LlmClient
    private lateinit var adapter: RoleCardAdapter
    private val items = mutableListOf<RoleCharacter>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_lounge)
        store = RoleStore(this)
        prefs = Prefs(this)
        llm = LlmClient(prefs)
        findViewById<View>(R.id.btnRoleBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnRoleAdd).setOnClickListener {
            startActivity(Intent(this, RoleEditActivity::class.java))
        }
        findViewById<Button>(R.id.btnConnectLlm).setOnClickListener {
            startActivity(Intent(this, LlmConnectActivity::class.java))
        }
        adapter = RoleCardAdapter(
            items,
            onClick = { openChat(it) },
            onLongClick = { view, character -> showCardMenu(view, character) }
        )
        val recycler = findViewById<RecyclerView>(R.id.recyclerRoles)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        reload()
        refreshLlmStatus()
    }

    private fun reload() {
        items.clear()
        items.addAll(store.loadCharacters())
        adapter.notifyDataSetChanged()
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
        pop.menu.add(0, 2, 1, getString(R.string.role_delete))
        pop.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    startActivity(
                        Intent(this, RoleEditActivity::class.java)
                            .putExtra(RoleEditActivity.EXTRA_ROLE_ID, character.id)
                    )
                    true
                }
                2 -> {
                    store.delete(character.id)
                    reload()
                    Toast.makeText(this, R.string.toast_role_deleted, Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        pop.show()
    }

    private class RoleCardAdapter(
        private val items: List<RoleCharacter>,
        private val onClick: (RoleCharacter) -> Unit,
        private val onLongClick: (View, RoleCharacter) -> Unit
    ) : RecyclerView.Adapter<RoleCardAdapter.VH>() {
        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val emoji: TextView = view.findViewById(R.id.tvRoleEmoji)
            val name: TextView = view.findViewById(R.id.tvRoleName)
            val intro: TextView = view.findViewById(R.id.tvRoleIntro)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_role_card, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.emoji.text = item.emoji.ifBlank { "\uD83C\uDFAD" }
            holder.name.text = item.name
            holder.intro.text = item.intro.ifBlank {
                holder.itemView.context.getString(R.string.role_intro_empty)
            }
            holder.itemView.setOnClickListener { onClick(item) }
            holder.itemView.setOnLongClickListener {
                onLongClick(holder.itemView, item)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }
}