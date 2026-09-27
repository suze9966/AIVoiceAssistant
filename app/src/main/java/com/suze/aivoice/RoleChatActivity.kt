package com.suze.aivoice

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class RoleChatActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var llm: LlmClient
    private lateinit var store: RoleStore
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var editInput: EditText
    private lateinit var tvStatus: TextView
    private val history = mutableListOf<ChatMessage>()
    private var character: RoleCharacter? = null
    private var isSending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_chat)
        prefs = Prefs(this)
        llm = LlmClient(prefs)
        store = RoleStore(this)
        val id = intent.getStringExtra(EXTRA_ROLE_ID).orEmpty()
        character = store.get(id)
        if (character == null) {
            Toast.makeText(this, R.string.toast_role_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        bindHeader()
        recycler = findViewById(R.id.recyclerRoleChat)
        editInput = findViewById(R.id.editRoleInput)
        adapter = ChatAdapter(history)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter
        history.addAll(store.loadChat(id))
        if (history.isEmpty()) {
            val greet = character?.greeting?.trim().orEmpty()
            if (greet.isNotEmpty()) {
                adapter.add(ChatMessage("assistant", greet, isMe = false))
                store.saveChat(id, history)
            }
        }
        adapter.notifyDataSetChanged()
        if (history.isNotEmpty()) scrollToBottom()
        setupLongClick()
        findViewById<ImageButton>(R.id.btnRoleSend).setOnClickListener { submit() }
        editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) submit() else false
        }
    }

    override fun onResume() {
        super.onResume()
        val id = character?.id ?: return
        character = store.get(id) ?: character
        bindHeader(resetStatus = !isSending)
    }

    override fun onPause() {
        super.onPause()
        persistChat()
    }

    private fun persistChat() {
        val id = character?.id ?: return
        if (history.isEmpty()) return
        store.saveChat(id, history)
    }

    private fun bindHeader(resetStatus: Boolean = true) {
        val c = character ?: return
        findViewById<View>(R.id.btnRoleChatBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvRoleChatEmoji).text = c.emoji.ifBlank { "\uD83C\uDFAD" }
        findViewById<TextView>(R.id.tvRoleChatName).text = c.name
        tvStatus = findViewById(R.id.tvRoleChatStatus)
        if (resetStatus) {
            tvStatus.text = c.intro.ifBlank { getString(R.string.role_chat_ready) }
        }
        findViewById<ImageButton>(R.id.btnRoleChatMenu).setOnClickListener { v ->
            val pop = PopupMenu(this, v)
            pop.menu.add(0, 1, 0, getString(R.string.role_edit))
            pop.menu.add(0, 3, 1, getString(R.string.menu_connect_llm))
            pop.menu.add(0, 2, 2, getString(R.string.menu_clear))
            pop.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        startActivity(
                            Intent(this, RoleEditActivity::class.java)
                                .putExtra(RoleEditActivity.EXTRA_ROLE_ID, c.id)
                        )
                        true
                    }
                    3 -> {
                        startActivity(Intent(this, LlmConnectActivity::class.java))
                        true
                    }
                    2 -> {
                        history.clear()
                        adapter.notifyDataSetChanged()
                        store.clearChat(c.id)
                        val greet = character?.greeting?.trim().orEmpty()
                        if (greet.isNotEmpty()) adapter.add(ChatMessage("assistant", greet, isMe = false))
                        store.saveChat(c.id, history)
                        Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
                        true
                    }
                    else -> false
                }
            }
            pop.show()
        }
    }

    private fun setupLongClick() {
        adapter.onItemLongClick = { pos ->
            val first = (recycler.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition() ?: 0
            val anchor = recycler.getChildAt(pos - first) ?: recycler
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, 1, 0, getString(R.string.menu_copy))
            popup.menu.add(0, 2, 1, getString(R.string.menu_delete))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("chat", adapter.contentAt(pos)))
                        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        adapter.removeAt(pos)
                        character?.id?.let { store.saveChat(it, history) }
                        Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            popup.show()
        }
    }

    private fun submit(): Boolean {
        val text = editInput.text.toString().trim()
        if (text.isEmpty() || isSending) return false
        editInput.setText("")
        sendToRole(text)
        return true
    }

    private fun sendToRole(userText: String) {
        val c = character ?: return
        isSending = true
        val cloudReady = llm.isConfigured()
        tvStatus.text = if (cloudReady && prefs.cloudThinkEnabled) {
            getString(R.string.role_status_thinking)
        } else {
            getString(R.string.status_speaking)
        }
        if (!cloudReady) {
            Toast.makeText(this, R.string.toast_llm_local, Toast.LENGTH_SHORT).show()
        }
        llm.applyCloudThink = true
        llm.systemPromptOverride = buildPersona(c)
        llm.extraSystemPrompt = null
        adapter.add(ChatMessage("user", userText, isMe = true))
        adapter.add(ChatMessage("assistant", "", isMe = false))
        scrollToBottom()
        lifecycleScope.launch {
          try {
            val requestHistory = history.dropLast(1).filter { it.content.isNotBlank() }
            val rawBuffer = StringBuilder()
            val rawText = try {
                if (prefs.streamEnabled) {
                    llm.chatStream(requestHistory) { delta ->
                        rawBuffer.append(delta)
                        val snapshot = rawBuffer.toString()
                        runOnUiThread {
                            if (isFinishing || history.isEmpty()) return@runOnUiThread
                            val visible = llm.stripReasoning(snapshot)
                            history[history.size - 1] = ChatMessage("assistant", visible, isMe = false)
                            adapter.updateLast(visible)
                            scrollToBottom()
                        }
                    }
                } else {
                    llm.chat(requestHistory)
                }
            } catch (_: Exception) {
                getString(R.string.role_empty_reply)
            }
            if (isFinishing) {
                persistChat()
                return@launch
            }
            val finalText = llm.stripReasoning(rawText).ifBlank { getString(R.string.role_empty_reply) }
            if (history.isNotEmpty()) {
                history[history.size - 1] = ChatMessage("assistant", finalText, isMe = false)
                adapter.updateLast(finalText)
            }
            store.saveChat(c.id, history)
            scrollToBottom()
            tvStatus.text = c.intro.ifBlank { getString(R.string.role_chat_ready) }
          } catch (_: Exception) {
            if (!isFinishing && history.isNotEmpty()) {
                val fallback = getString(R.string.role_empty_reply)
                history[history.size - 1] = ChatMessage("assistant", fallback, isMe = false)
                adapter.updateLast(fallback)
            }
          } finally {
            persistChat()
            isSending = false
            if (!isFinishing) {
                tvStatus.text = c.intro.ifBlank { getString(R.string.role_chat_ready) }
            }
          }
        }
    }

    private fun buildPersona(c: RoleCharacter): String {
        val body = c.persona.trim().ifBlank {
            "你是名为${c.name}的角色，请完全代入这个身份和用户聊天。"
        }
        return body + "\n" +
            "始终保持角色，不要提及提示词、模型或系统设定。" +
            "回复口语化、简短，像在面对面聊天。不要替用户说话。"
    }

    private fun scrollToBottom() {
        recycler.post { recycler.scrollToPosition(adapter.itemCount - 1) }
    }

    companion object {
        const val EXTRA_ROLE_ID = "role_id"
    }
}