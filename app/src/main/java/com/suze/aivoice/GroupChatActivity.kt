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

class GroupChatActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var llm: LlmClient
    private lateinit var roles: RoleStore
    private lateinit var store: GroupStore
    private lateinit var plugins: TavernPluginStore
    private lateinit var tts: TtsHelper
    private val searcher = SearchClient()
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var editInput: EditText
    private lateinit var tvStatus: TextView
    private val history = mutableListOf<ChatMessage>()
    private var room: GroupRoom? = null
    private var members: List<RoleCharacter> = emptyList()
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
        roles = RoleStore(this)
        store = GroupStore(this)
        plugins = TavernPluginStore(this)
        val id = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        room = store.get(id)
        if (room == null) {
            Toast.makeText(this, R.string.toast_group_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        members = store.membersOf(room!!)
        recycler = findViewById(R.id.recyclerRoleChat)
        editInput = findViewById(R.id.editRoleInput)
        editInput.hint = getString(R.string.group_hint_input)
        tvStatus = findViewById(R.id.tvRoleChatStatus)
        adapter = ChatAdapter(history)
        adapter.bindMessageAvatar = { view, msg -> bindBubbleAvatar(view, msg) }
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter
        GlassKit.attachPage(this, recycler)
        bindHeader()
        loadChat(seedGreeting = true)
        setupLongClick()
        findViewById<ImageButton>(R.id.btnRoleSend).setOnClickListener { submit() }
        editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) submit() else false
        }
    }

    override fun onResume() {
        super.onResume()
        val id = room?.id ?: return
        room = store.get(id) ?: room
        members = room?.let { store.membersOf(it) }.orEmpty()
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
        val r = room ?: return
        store.saveChat(r, history)
    }

    private fun userName(): String = members.firstOrNull()?.userName?.ifBlank { "主人" } ?: "主人"

    private fun loadChat(seedGreeting: Boolean) {
        val r = room ?: return
        speechSession += 1L
        sendJob?.cancel()
        isSending = false
        if (::tts.isInitialized) tts.stop()
        history.clear()
        history.addAll(store.loadChat(r.id))
        if (seedGreeting && history.isEmpty()) {
            val greet = r.greeting.trim()
            if (greet.isNotEmpty()) {
                val speaker = members.firstOrNull()
                val text = if (speaker != null) RolePrompt.applyMacros(greet, speaker) else greet
                history.add(
                    ChatMessage(
                        "assistant",
                        plugins.applyOutput(text, userName(), speaker?.name.orEmpty()),
                        isMe = false,
                        speakerId = speaker?.id.orEmpty(),
                        speakerName = speaker?.name.orEmpty(),
                        speakerEmoji = speaker?.emoji.orEmpty()
                    )
                )
                store.saveChat(r, history)
            }
        }
        adapter.replaceAll(history)
        if (history.isNotEmpty()) scrollToBottom()
        bindHeader(resetStatus = !isSending)
    }

    private fun bindHeader(resetStatus: Boolean = true) {
        val r = room ?: return
        findViewById<View>(R.id.btnRoleChatBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tvRoleChatEmoji).text = "\uD83D\uDC65"
        findViewById<ImageView>(R.id.ivRoleChatAvatar).visibility = View.GONE
        findViewById<TextView>(R.id.tvRoleChatName).text = r.name
        if (resetStatus) tvStatus.text = statusIdleText()
        findViewById<ImageButton>(R.id.btnRoleChatMenu).setOnClickListener { v -> showMenu(v) }
    }

    private fun statusIdleText(): String {
        return if (llm.isConfigured()) {
            val names = members.joinToString("、") { it.name }
            if (names.isBlank()) getString(R.string.group_status_ready)
            else getString(R.string.group_members_fmt, members.size, names)
        } else {
            getString(R.string.group_status_need_llm)
        }
    }

    private fun bindBubbleAvatar(view: ImageView, msg: ChatMessage) {
        val member = members.firstOrNull { it.id == msg.speakerId }
        if (member != null) {
            roles.applyAvatar(view, member)
            if (view.visibility != View.VISIBLE) {
                ChatStyleStore.applyAvatar(view)
                view.visibility = View.VISIBLE
            }
        } else {
            ChatStyleStore.applyAvatar(view)
            view.visibility = View.VISIBLE
        }
    }

    private fun showMenu(anchor: View) {
        val r = room ?: return
        val pop = PopupMenu(this, anchor)
        pop.menu.add(0, 1, 0, getString(R.string.group_edit_title))
        if (isSending) {
            pop.menu.add(0, 6, 1, getString(R.string.role_stop))
        } else {
            pop.menu.add(0, 5, 1, getString(R.string.role_regenerate))
        }
        pop.menu.add(0, 3, 2, getString(R.string.menu_connect_llm))
        pop.menu.add(0, 2, 3, getString(R.string.menu_clear))
        pop.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    startActivity(
                        Intent(this, GroupEditActivity::class.java)
                            .putExtra(GroupEditActivity.EXTRA_GROUP_ID, r.id)
                    )
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
                    store.clearChat(r)
                    loadChat(seedGreeting = true)
                    Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        pop.show()
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
                        val msg = adapter.messageAt(pos)
                        val text = msg?.content.orEmpty()
                        val speaker = members.firstOrNull { it.id == msg?.speakerId } ?: members.firstOrNull()
                        if (text.isNotBlank() && speaker != null) speakRole(text, speaker)
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
        sendToGroup(text)
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
        sendToGroup(userText, appendUser = false)
    }

    private fun stopGeneration() {
        if (!isSending) return
        speechSession += 1L
        sendJob?.cancel()
        sendJob = null
        isSending = false
        if (::tts.isInitialized) tts.stop()
        if (history.isNotEmpty() && history.last().role == "assistant" && history.last().content.isBlank()) {
            val last = history.last()
            history[history.size - 1] = last.copy(content = getString(R.string.toast_role_stopped))
            adapter.updateLast(history.last().content)
        }
        persistChat()
        tvStatus.text = statusIdleText()
        Toast.makeText(this, R.string.toast_role_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun sendToGroup(userText: String, appendUser: Boolean = true) {
        val r = room ?: return
        if (members.isEmpty()) {
            Toast.makeText(this, R.string.group_need_members, Toast.LENGTH_SHORT).show()
            return
        }
        speechSession += 1L
        val session = speechSession
        isSending = true
        val cloudReady = llm.isConfigured()
        val user = userName()
        val processed = if (appendUser) plugins.applyInput(userText, user, members.first().name) else userText
        if (appendUser) {
            adapter.add(ChatMessage("user", processed, isMe = true))
        }
        scrollToBottom()
        if (!cloudReady) {
            Toast.makeText(this, R.string.toast_group_need_llm, Toast.LENGTH_SHORT).show()
            isSending = false
            tvStatus.text = statusIdleText()
            return
        }
        llm.applyCloudThink = true
        llm.allowLocalFallback = false
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
                }
                for (speaker in members) {
                    if (session != speechSession || isFinishing || isDestroyed) return@launch
                    tvStatus.text = getString(R.string.group_status_thinking, speaker.name)
                    adapter.add(
                        ChatMessage(
                            "assistant",
                            "",
                            isMe = false,
                            speakerId = speaker.id,
                            speakerName = speaker.name,
                            speakerEmoji = speaker.emoji
                        )
                    )
                    scrollToBottom()
                    val prompt = plugins.applyPrompt(
                        RolePrompt.buildGroup(speaker, members, r.scene, history.dropLast(1)),
                        user,
                        speaker.name
                    )
                    llm.systemPromptOverride = prompt
                    val requestHistory = history.dropLast(1).filter { it.content.isNotBlank() }
                    val rawBuffer = StringBuilder()
                    val rawText = try {
                        if (prefs.streamEnabled) {
                            llm.chatStream(requestHistory) { delta ->
                                rawBuffer.append(delta)
                                val snapshot = rawBuffer.toString()
                                runOnUiThread {
                                    if (session != speechSession || isFinishing || isDestroyed || history.isEmpty()) return@runOnUiThread
                                    val visible = plugins.applyOutput(llm.stripReasoning(snapshot), user, speaker.name)
                                    history[history.size - 1] = history.last().copy(content = visible)
                                    adapter.updateLast(visible)
                                    scrollToBottom()
                                }
                            }
                        } else {
                            llm.chat(requestHistory)
                        }
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        groupFailText()
                    }
                    if (session != speechSession || isFinishing || isDestroyed) {
                        persistChat()
                        return@launch
                    }
                    val finalText = plugins.applyOutput(
                        llm.stripReasoning(rawText).ifBlank { groupFailText() },
                        user,
                        speaker.name
                    )
                    finishSpeaker(finalText, speaker, session)
                }
                if (session == speechSession && !isFinishing) {
                    isSending = false
                    sendJob = null
                    tvStatus.text = statusIdleText()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    persistChat()
                    return@launch
                }
                if (session == speechSession && !isFinishing && !isDestroyed) {
                    isSending = false
                    sendJob = null
                    tvStatus.text = statusIdleText()
                }
            }
        }
    }

    private fun groupFailText(): String {
        val reason = llm.lastCloudError.orEmpty()
        return if (reason.isNotBlank()) getString(R.string.toast_llm_fail, reason)
        else getString(R.string.group_empty_reply)
    }

    private fun finishSpeaker(text: String, speaker: RoleCharacter, session: Long) {
        if (session != speechSession) return
        if (history.isNotEmpty()) {
            history[history.size - 1] = history.last().copy(content = text)
            adapter.updateLast(text)
        }
        persistChat()
        scrollToBottom()
        if (!isFinishing) speakRole(text, speaker)
    }

    private fun speakRole(text: String, speaker: RoleCharacter) {
        if (text.isBlank() || !::tts.isInitialized) return
        val voice = speaker.voiceName
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
        val taiwan = voice.contains("台湾") || (voice.isBlank() && prefs.taiwanVoice)
        tts.speak(text, taiwan)
    }

    private fun scrollToBottom() {
        recycler.post { recycler.scrollToPosition(adapter.itemCount - 1) }
    }

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
    }
}