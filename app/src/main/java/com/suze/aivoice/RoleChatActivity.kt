package com.suze.aivoice

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.content.FileProvider
import java.io.File
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RoleChatActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var llm: LlmClient
    private lateinit var store: RoleStore
    private lateinit var plugins: TavernPluginStore
    private lateinit var tts: TtsHelper
    private val searcher = SearchClient()
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var editInput: EditText
    private lateinit var tvStatus: TextView
    private val history = mutableListOf<ChatMessage>()
    private var character: RoleCharacter? = null
    private var chatId: String = ""
    private var isSending = false
    private var sendJob: Job? = null
    private var speechSession = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_chat)
        prefs = Prefs(this)
        llm = LlmClient(prefs)
        llm.allowLocalFallback = false
        tts = TtsHelper(this, prefs)
        store = RoleStore(this)
        plugins = TavernPluginStore(this)
        val id = intent.getStringExtra(EXTRA_ROLE_ID).orEmpty()
        character = store.get(id)
        if (character == null) {
            Toast.makeText(this, R.string.toast_role_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        chatId = intent.getStringExtra(EXTRA_CHAT_ID).orEmpty().ifBlank {
            store.activeChatId(id)
        }
        recycler = findViewById(R.id.recyclerRoleChat)
        editInput = findViewById(R.id.editRoleInput)
        tvStatus = findViewById(R.id.tvRoleChatStatus)
        adapter = ChatAdapter(history)
        adapter.bindAiAvatar = { view -> bindBubbleAvatar(view) }
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter
        GlassKit.attachPage(this, recycler)
        bindHeader()
        loadCurrentChat(seedGreeting = true)
        setupLongClick()
        if (intent.getBooleanExtra(EXTRA_OPEN_CHATS, false)) {
            recycler.post { showChatPicker() }
        }
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
        adapter.notifyDataSetChanged()
    }

    override fun onPause() {
        super.onPause()
        persistChat()
    }

    override fun onDestroy() {
        sendJob?.cancel()
        if (::tts.isInitialized) {
            tts.onSpeakDone = null
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    private fun persistChat() {
        val c = character ?: return
        if (chatId.isBlank()) return
        store.saveChat(chatId, history, c.id)
    }

    private fun applyRoleVoice() {
        if (!::tts.isInitialized || !::prefs.isInitialized) return
        val voice = character?.voiceName.orEmpty()
        if (voice.isNotBlank()) {
            tts.setVoiceByName(voice)
        } else if (prefs.taiwanVoice) {
            tts.applyTaiwanVoice()
        } else {
            val savedVoice = tts.availableVoices().getOrNull(prefs.voiceIndex)
            if (savedVoice != null) tts.setVoiceByName(savedVoice)
        }
        tts.setRate(prefs.ttsRate)
        tts.setPitch(prefs.ttsPitch)
    }

    private fun speakRole(text: String) {
        if (text.isBlank() || !::tts.isInitialized) return
        applyRoleVoice()
        val taiwan = character?.voiceName.orEmpty().contains("台湾") ||
            (character?.voiceName.isNullOrBlank() && prefs.taiwanVoice)
        tts.speak(text, taiwan)
    }

    private fun loadCurrentChat(seedGreeting: Boolean) {
        val c = character ?: return
        speechSession += 1L
        sendJob?.cancel()
        isSending = false
        if (::tts.isInitialized) tts.stop()
        history.clear()
        history.addAll(store.loadChat(chatId))
        if (seedGreeting && history.isEmpty()) {
            pickGreeting(c)?.let { greet ->
                val text = plugins.applyOutput(RolePrompt.applyMacros(greet, c), c.userName.ifBlank { "主人" }, c.name)
                history.add(ChatMessage("assistant", text, isMe = false))
                store.saveChat(chatId, history, c.id)
            }
        }
        adapter.replaceAll(history)
        if (history.isNotEmpty()) scrollToBottom()
        bindHeader(resetStatus = !isSending)
    }

    private fun pickGreeting(c: RoleCharacter): String? {
        val all = c.allGreetings()
        if (all.isEmpty()) return null
        val metas = store.loadChatMetas(c.id)
        val index = (metas.size - 1).coerceAtLeast(0) % all.size
        return all[index]
    }

    private fun bindHeader(resetStatus: Boolean = true) {
        val c = character ?: return
        findViewById<View>(R.id.btnRoleChatBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvRoleChatEmoji).text = c.emoji.ifBlank { "\uD83C\uDFAD" }
        val title = findViewById<TextView>(R.id.tvRoleChatName)
        val meta = store.loadChatMetas(c.id).firstOrNull { it.id == chatId }
        val chatTitle = meta?.title.orEmpty()
        title.text = if (chatTitle.isBlank()) c.name else getString(R.string.role_chat_title_fmt, c.name, chatTitle)
        store.applyAvatar(findViewById(R.id.ivRoleChatAvatar), c)
        if (resetStatus) {
            tvStatus.text = statusIdleText(c)
        }
        findViewById<ImageButton>(R.id.btnRoleChatMenu).setOnClickListener { v -> showMenu(v) }
    }

    private fun statusIdleText(c: RoleCharacter): String {
        return if (llm.isConfigured()) {
            c.displayIntro().ifBlank { getString(R.string.role_chat_ready) }
        } else {
            getString(R.string.role_status_need_llm)
        }
    }

    private fun bindBubbleAvatar(view: ImageView) {
        val c = character ?: return
        store.applyAvatar(view, c)
        if (view.visibility != View.VISIBLE) {
            ChatStyleStore.applyAvatar(view)
            view.visibility = View.VISIBLE
        }
    }

    private fun showMenu(anchor: View) {
        val c = character ?: return
        val pop = PopupMenu(this, anchor)
        pop.menu.add(0, 1, 0, getString(R.string.role_edit))
        pop.menu.add(0, 8, 1, getString(R.string.role_chats))
        pop.menu.add(0, 4, 2, getString(R.string.role_new_chat))
        pop.menu.add(0, 9, 3, getString(R.string.role_pick_greeting))
        if (isSending) {
            pop.menu.add(0, 6, 4, getString(R.string.role_stop))
        } else {
            pop.menu.add(0, 5, 4, getString(R.string.role_regenerate))
        }
        pop.menu.add(0, 7, 5, getString(R.string.role_export_chat))
        pop.menu.add(0, 3, 6, getString(R.string.menu_connect_llm))
        pop.menu.add(0, 2, 7, getString(R.string.menu_clear))
        pop.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    startActivity(
                        Intent(this, RoleEditActivity::class.java)
                            .putExtra(RoleEditActivity.EXTRA_ROLE_ID, c.id)
                    )
                    true
                }
                8 -> {
                    showChatPicker()
                    true
                }
                4 -> {
                    startNewChat()
                    true
                }
                9 -> {
                    promptGreeting()
                    true
                }
                7 -> {
                    exportCurrentChat()
                    true
                }
                5 -> {
                    regenerateLast()
                    true
                }
                6 -> {
                    stopGeneration()
                    true
                }
                3 -> {
                    startActivity(Intent(this, LlmConnectActivity::class.java))
                    true
                }
                2 -> {
                    history.clear()
                    store.clearChat(chatId)
                    pickGreeting(c)?.let {
                        val text = plugins.applyOutput(RolePrompt.applyMacros(it, c), c.userName.ifBlank { "主人" }, c.name)
                        history.add(ChatMessage("assistant", text, isMe = false))
                    }
                    adapter.replaceAll(history)
                    persistChat()
                    Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        pop.show()
    }

    private fun showChatPicker() {
        val c = character ?: return
        val metas = store.loadChatMetas(c.id)
        if (metas.isEmpty()) {
            startNewChat()
            return
        }
        val labels = metas.map { m ->
            val preview = m.preview.ifBlank { getString(R.string.role_preview_empty) }
            val labelTitle = m.title.ifBlank { getString(R.string.role_new_chat) }
            val mark = if (m.id == chatId) getString(R.string.role_current_chat) + " · " else ""
            mark + labelTitle + "\n" + preview
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.role_chats)
            .setItems(labels) { _, which ->
                val selected = metas.getOrNull(which) ?: return@setItems
                persistChat()
                chatId = selected.id
                loadCurrentChat(seedGreeting = true)
            }
            .setNeutralButton(R.string.role_rename_chat) { _, _ -> promptRename() }
            .setNegativeButton(R.string.role_delete_chat) { _, _ -> confirmDeleteChat() }
            .setPositiveButton(R.string.role_cancel, null)
            .show()
    }

    private fun promptRename() {
        val input = EditText(this)
        input.hint = getString(R.string.role_hint_rename)
        input.setText(store.loadChatMetas(character?.id).firstOrNull { it.id == chatId }?.title.orEmpty())
        AlertDialog.Builder(this)
            .setTitle(R.string.role_rename_chat)
            .setView(input)
            .setPositiveButton(R.string.role_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    store.renameChat(chatId, name)
                    bindHeader(resetStatus = !isSending)
                    Toast.makeText(this, R.string.toast_role_renamed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun confirmDeleteChat() {
        val c = character ?: return
        val metas = store.loadChatMetas(c.id)
        if (metas.size <= 1) {
            Toast.makeText(this, R.string.role_keep_one_chat, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.role_confirm_delete_chat)
            .setPositiveButton(R.string.role_confirm_ok) { _, _ ->
                store.deleteChat(chatId)
                chatId = store.activeChatId(c.id)
                loadCurrentChat(seedGreeting = true)
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun startNewChat() {
        val c = character ?: return
        persistChat()
        val created = store.createChat(c.id)
        chatId = created.id
        loadCurrentChat(seedGreeting = true)
    }

    private fun promptGreeting() {
        val c = character ?: return
        val greets = c.allGreetings()
        if (greets.isEmpty()) {
            Toast.makeText(this, R.string.toast_role_no_regenerate, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = greets.mapIndexed { i, text ->
            getString(R.string.role_greeting_index, i + 1) + "\n" + RolePrompt.applyMacros(text, c).take(60)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.role_pick_greeting)
            .setItems(labels) { _, which ->
                val picked = greets.getOrNull(which) ?: return@setItems
                persistChat()
                val created = store.createChat(c.id)
                chatId = created.id
                history.clear()
                val text = plugins.applyOutput(RolePrompt.applyMacros(picked, c), c.userName.ifBlank { "主人" }, c.name)
                history.add(ChatMessage("assistant", text, isMe = false))
                adapter.replaceAll(history)
                persistChat()
                bindHeader(resetStatus = true)
                scrollToBottom()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun exportCurrentChat() {
        val c = character ?: return
        if (history.isEmpty()) {
            Toast.makeText(this, R.string.toast_role_chat_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val dir = File(cacheDir, "role_export")
        dir.mkdirs()
        val title = store.loadChatMetas(c.id).firstOrNull { it.id == chatId }?.title.orEmpty()
            .ifBlank { "对话" }
        val safe = RoleStore.sanitize(c.name + "_" + title)
        val file = File(dir, "$safe.md")
        val sb = StringBuilder()
        sb.append("# ").append(c.name)
        if (title.isNotBlank()) sb.append(" · ").append(title)
        sb.append("\n\n")
        history.filter { it.content.isNotBlank() }.forEach { m ->
            val who = if (m.isMe || m.role == "user") c.userName.ifBlank { "主人" } else m.speakerName.ifBlank { c.name }
            sb.append("**").append(who).append("：** ").append(m.content).append("\n\n")
        }
        try {
            file.writeText(sb.toString())
        } catch (_: Exception) {
            Toast.makeText(this, R.string.toast_role_chat_export_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.role_export_chat)
            )
        )
        Toast.makeText(this, R.string.toast_role_chat_exported, Toast.LENGTH_SHORT).show()
    }

    private fun setupLongClick() {
        adapter.onItemLongClick = { pos ->
            val first = (recycler.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition() ?: 0
            val anchor = recycler.getChildAt(pos - first) ?: recycler
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, 1, 0, getString(R.string.menu_copy))
            popup.menu.add(0, 2, 1, getString(R.string.menu_delete))
            popup.menu.add(0, 3, 2, getString(R.string.menu_respeak))
            if (!adapter.isMeAt(pos)) popup.menu.add(0, 4, 3, getString(R.string.menu_regenerate))
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("chat", adapter.contentAt(pos)))
                        Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        adapter.removeAt(pos)
                        persistChat()
                        Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        val text = adapter.contentAt(pos)
                        if (text.isNotBlank()) speakRole(text)
                    }
                    4 -> regenerateLast()
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

    private fun regenerateLast() {
        if (isSending) return
        val lastUserIndex = history.indexOfLast { it.role == "user" && it.content.isNotBlank() }
        if (lastUserIndex < 0) {
            Toast.makeText(this, R.string.toast_role_no_regenerate, Toast.LENGTH_SHORT).show()
            return
        }
        val userText = history[lastUserIndex].content
        while (history.size > lastUserIndex + 1) {
            history.removeAt(history.lastIndex)
        }
        adapter.replaceAll(history)
        sendToRole(userText, appendUser = false)
    }

    private fun stopGeneration() {
        if (!isSending) return
        speechSession += 1L
        sendJob?.cancel()
        sendJob = null
        isSending = false
        if (::tts.isInitialized) tts.stop()
        val c = character
        if (history.isNotEmpty() && history.last().role == "assistant" && history.last().content.isBlank()) {
            history[history.size - 1] = ChatMessage("assistant", getString(R.string.toast_role_stopped), isMe = false)
            adapter.updateLast(history.last().content)
        }
        persistChat()
        if (c != null) tvStatus.text = statusIdleText(c)
        Toast.makeText(this, R.string.toast_role_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun sendToRole(userText: String, appendUser: Boolean = true) {
        val c = character ?: return
        speechSession += 1L
        val session = speechSession
        isSending = true
        val cloudReady = llm.isConfigured()
        tvStatus.text = if (cloudReady && prefs.cloudThinkEnabled) {
            getString(R.string.role_status_thinking)
        } else if (cloudReady) {
            getString(R.string.status_speaking)
        } else {
            getString(R.string.role_status_need_llm)
        }
        val user = c.userName.ifBlank { "主人" }
        val processed = if (appendUser) plugins.applyInput(userText, user, c.name) else userText
        if (appendUser) {
            adapter.add(ChatMessage("user", processed, isMe = true))
        }
        adapter.add(ChatMessage("assistant", "", isMe = false))
        scrollToBottom()
        if (!cloudReady) {
            Toast.makeText(this, R.string.toast_role_need_llm, Toast.LENGTH_SHORT).show()
            val fallback = plugins.applyOutput(RolePrompt.fallbackLine(c), user, c.name)
            finishAssistant(fallback, c)
            return
        }
        llm.applyCloudThink = true
        llm.allowLocalFallback = false
        llm.systemPromptOverride = plugins.applyPrompt(RolePrompt.build(c, history.dropLast(1)), user, c.name)
        llm.extraSystemPrompt = null
        sendJob?.cancel()
        sendJob = lifecycleScope.launch {
            try {
                if (prefs.webSearchEnabled && KnowledgeAssist.needsWeb(processed)) {
                    tvStatus.text = getString(R.string.status_searching)
                    val notes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        searcher.gatherNotes(KnowledgeAssist.queryOf(processed))
                    }
                    if (session != speechSession || isFinishing || isDestroyed) {
                        persistChat()
                        return@launch
                    }
                    llm.extraSystemPrompt = KnowledgeAssist.notesPrompt(notes, "role").takeIf { it.isNotBlank() }
                    tvStatus.text = if (prefs.cloudThinkEnabled) {
                        getString(R.string.role_status_thinking)
                    } else {
                        getString(R.string.status_speaking)
                    }
                }
                val requestHistory = history.dropLast(1).filter { it.content.isNotBlank() }
                val rawBuffer = StringBuilder()
                val rawText = try {
                    if (prefs.streamEnabled) {
                        llm.chatStream(requestHistory) { delta ->
                            rawBuffer.append(delta)
                            val snapshot = rawBuffer.toString()
                            runOnUiThread {
                                if (session != speechSession || isFinishing || isDestroyed || history.isEmpty()) return@runOnUiThread
                                val visible = plugins.applyOutput(llm.stripReasoning(snapshot), user, c.name)
                                history[history.size - 1] = ChatMessage("assistant", visible, isMe = false)
                                adapter.updateLast(visible)
                                scrollToBottom()
                            }
                        }
                    } else {
                        llm.chat(requestHistory)
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    roleFailText()
                }
                if (session != speechSession || isFinishing || isDestroyed) {
                    persistChat()
                    return@launch
                }
                val finalText = plugins.applyOutput(
                    llm.stripReasoning(rawText).ifBlank { roleFailText() },
                    user,
                    c.name
                )
                finishAssistant(finalText, c, session)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    persistChat()
                    return@launch
                }
                if (session == speechSession && !isFinishing && !isDestroyed) {
                    finishAssistant(roleFailText(), c, session)
                }
            }
        }
    }

    private fun roleFailText(): String {
        val reason = llm.lastCloudError.orEmpty()
        return if (reason.isNotBlank()) getString(R.string.toast_llm_fail, reason)
        else getString(R.string.role_empty_reply)
    }

    private fun finishAssistant(text: String, c: RoleCharacter, session: Long = speechSession) {
        if (session != speechSession) return
        if (history.isNotEmpty()) {
            history[history.size - 1] = ChatMessage("assistant", text, isMe = false)
            adapter.updateLast(text)
        }
        persistChat()
        scrollToBottom()
        isSending = false
        sendJob = null
        if (!isFinishing) {
            tvStatus.text = statusIdleText(c)
            speakRole(text)
        }
    }

    private fun scrollToBottom() {
        recycler.post { recycler.scrollToPosition(adapter.itemCount - 1) }
    }

    companion object {
        const val EXTRA_ROLE_ID = "role_id"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_OPEN_CHATS = "open_chats"
    }
}
