package com.suze.aivoice

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var llm: LlmClient
    private lateinit var tts: TtsHelper
    private lateinit var store: HistoryStore
    private lateinit var emotion: EmotionEngine
    private lateinit var mind: MindEngine
    private lateinit var memory: MemoryEngine
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var editInput: EditText
    private lateinit var tvStatus: TextView
    private lateinit var micHalo: View

    private var recognizer: SpeechRecognizer? = null
    private var wakeHelper: WakeWordHelper? = null
    private val history = mutableListOf<ChatMessage>()
    private var isSending = false

    private val REQ_AUDIO = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        llm = LlmClient(prefs)
        tts = TtsHelper(this)
        store = HistoryStore(this)
        emotion = EmotionEngine(this)
        mind = MindEngine(this)
        memory = MemoryEngine(this)

        tvStatus = findViewById(R.id.tvStatus)
        micHalo = findViewById(R.id.micHalo)
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        btnMenu.setOnClickListener { v ->
            val pop = PopupMenu(this, v)
            pop.menu.add(0, 1, 0, getString(R.string.btn_settings))
            pop.menu.add(0, 2, 1, getString(R.string.menu_clear))
            pop.menu.add(0, 4, 2, getString(R.string.menu_stop_speak))
            pop.menu.add(0, 3, 3, getString(R.string.menu_wake))
            pop.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                    2 -> {
                        history.clear()
                        adapter.notifyDataSetChanged()
                        store.clear()
                        Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
                        true
                    }
                    4 -> { tts.stop(); Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show(); true }
                    3 -> { toggleWake(true); true }
                    else -> false
                }
            }
            pop.show()
        }

        recycler = findViewById(R.id.recyclerChat)
        editInput = findViewById(R.id.editInput)
        adapter = ChatAdapter(history)
        recycler.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        recycler.adapter = adapter

        // 恢复本地历史
        history.addAll(store.load())
        adapter.notifyDataSetChanged()
        if (history.isNotEmpty()) scrollToBottom()
        // 欢迎语／引导（首次进入且无历史）
        setupChatInteractions()
        if (history.isEmpty() && prefs.welcomeEnabled) {
            adapter.add(ChatMessage(role = "assistant", content = getString(R.string.welcome_msg), isMe = false))
            store.save(history)
            scrollToBottom()
        }

        val btnSend = findViewById<ImageButton>(R.id.btnSend)
        val btnMic = findViewById<ImageButton>(R.id.btnMic)

        btnSend.setOnClickListener {
            val text = editInput.text.toString().trim()
            if (text.isNotEmpty() && !isSending) {
                editInput.setText("")
                sendToLlm(text)
            }
        }
        btnMic.setOnClickListener {
            // 语音打断：正在朗读时先停止
            tts.stop()
            startListening()
        }

        ensureAudioPermission()
        initRecognizer()
        refreshMoodSubtitle()
        // 注入用户设定的基准语速/音调
        tts.setRate(prefs.ttsRate)
        tts.setPitch(prefs.ttsPitch)
    }

    /** 把情绪状态显示在标题栏副标题上 */
    /** 刷新标题栏成长状态：等级 + 经验 + 记忆数 */
    private fun refreshGrowthSubtitle() {
        if (!::memory.isInitialized) return
        val base = emotion.statusLine()
        val g = memory.summary()
        if (::tvStatus.isInitialized) tvStatus.text = if (prefs.growEnabled) "$base · $g" else base
    }


    private fun refreshMoodSubtitle() {
        if (!::emotion.isInitialized) return
        if (::tvStatus.isInitialized) tvStatus.text = emotion.statusLine()
    }

    // ---------------- 对话长按：复制 / 删除 ----------------
    private fun setupChatInteractions() {
        adapter.onItemLongClick = { pos ->
            val first = (recycler.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition() ?: 0
            val anchorView = recycler.getChildAt(pos - first)
                ?: recycler
            val popup = PopupMenu(this, anchorView)
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
                        store.save(history)
                        Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                    }
                }
                true
            }
            popup.show()
        }
    }

    /** 语音状态光环：聆听时点亮 */
    private fun setHalo(on: Boolean) {
        if (!::micHalo.isInitialized) return
        val res = if (on) R.drawable.halo_ring_active else R.drawable.halo_ring
        micHalo.setBackgroundResource(res)
    }
    // ---------------- 语音唤醒 ----------------
    private fun toggleWake(enable: Boolean) {
        if (enable) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // 没录音权限时不能监听，先申请权限（否则会误报“不支持”）
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
                Toast.makeText(this, "请先授予麦克风权限，然后重新开启唤醒", Toast.LENGTH_LONG).show()
                return
            }
            val word = prefs.wakeWord.ifBlank { "你好小沫" }
            wakeHelper = WakeWordHelper(this, word) {
                runOnUiThread {
                    Toast.makeText(this, "唤醒成功，请说话", Toast.LENGTH_SHORT).show()
                    startListening()
                }
            }
            if (wakeHelper?.isSupported() == true) {
                wakeHelper?.start()
                Toast.makeText(this, "已开启唤醒，说：$word", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "当前设备不支持语音唤醒", Toast.LENGTH_SHORT).show()
            }
        } else {
            wakeHelper?.stop()
            wakeHelper = null
            Toast.makeText(this, "已关闭唤醒", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // 情感引擎：随时间自然回落/亲密度增长
        if (::emotion.isInitialized) { emotion.tick(); refreshMoodSubtitle() }
    }

    private fun ensureAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
        }
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setHalo(false) }
            override fun onError(error: Int) {
                Toast.makeText(this@MainActivity, "语音识别失败: $error", Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty() && !isSending) sendToLlm(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrEmpty()) editInput.setText(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (recognizer == null) {
            Toast.makeText(this, "当前设备不支持语音识别", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (prefs.taiwanVoice) Locale.TAIWAN else Locale.CHINA)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
        setHalo(true)
        Toast.makeText(this, "请说话…", Toast.LENGTH_SHORT).show()
    }

    // ---------------- 发送：流式 / 非流式 ----------------
    private fun sendToLlm(userText: String) {
        isSending = true

        // ① 情感引擎：先根据主人的话更新情绪（心情/好感度/精力）
        if (prefs.emotionEnabled) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            emotion.reactToUser(userText, isNight = hour >= 22 || hour < 6)
        if (prefs.growEnabled) memory.learnFromUser(userText)
            refreshMoodSubtitle()
        }
        // ② 思考引擎：记录经历（供反思使用）
        if (prefs.mindEnabled) mind.record("主人说：" + userText.take(50))

        // ③ 人设：台湾腔优先，其次默认系统提示
        llm.systemPromptOverride = if (prefs.taiwanVoice) prefs.taiwanPrompt else null

        // ④ 拼接“动态提示”：独立思考 + 情绪状态（注入 system prompt，是关键）
        val extra = StringBuilder()
        if (prefs.mindEnabled) {
            extra.append(mind.independencePrompt())
            extra.append("\n").append(memory.growthPrompt())
            extra.append("\n")
            // 独立思考（CoT）：要求模型先内心推演，再对外发言
            extra.append(mind.thinkPrompt(userText))
            extra.append("\n")
        }
        if (prefs.emotionEnabled) {
            extra.append(emotion.emotionPrompt())
        }
        llm.extraSystemPrompt = extra.toString().takeIf { it.isNotBlank() }

        history.add(ChatMessage("user", userText, isMe = true))
        adapter.add(ChatMessage("user", userText, isMe = true))

        history.add(ChatMessage("assistant", "", isMe = false))
        adapter.add(ChatMessage("assistant", "", isMe = false))
        scrollToBottom()

        lifecycleScope.launch {
            val requestHistory = history.dropLast(1).filter { it.content.isNotBlank() }
            val rawText: String

            if (prefs.streamEnabled) {
                // 流式：逐字显示（打字机）
                rawText = llm.chatStream(requestHistory) { delta ->
                    runOnUiThread {
                        val cur = history.last().content + delta
                        history[history.size - 1] = ChatMessage("assistant", cur, isMe = false)
                        adapter.updateLast(cur)
                        scrollToBottom()
                    }
                }.ifBlank { "（无回复）" }
            } else {
                val reply = llm.chat(requestHistory)
                runOnUiThread {
                    history[history.size - 1] = ChatMessage("assistant", reply, isMe = false)
                    adapter.updateLast(reply)
                    scrollToBottom()
                }
                rawText = reply
            }

            // 独立思考：从回复中拆出「内心想法 / 对外发言」
            var thought = ""
            var finalText = rawText
            if (prefs.mindEnabled) {
                val parsed = mind.parse(rawText)
                thought = parsed.first
                finalText = parsed.second.ifBlank { rawText }
            }

            history[history.size - 1] = ChatMessage("assistant", finalText, isMe = false)
            adapter.updateLast(finalText)
            scrollToBottom()
            store.save(history)
            // 内心独白：不展示正文，但记录到状态栏提示
            if (prefs.mindEnabled && thought.isNotBlank()) {
                runOnUiThread { tvStatus.text = "\uD83D\uDCAD $thought" }
            }
            if (prefs.growEnabled) refreshGrowthSubtitle()
            isSending = false

            // 情感化 TTS：语速/音调随情绪变化
            if (prefs.emotionEnabled) {
                tts.setEmotion(emotion.ttsRate(), emotion.ttsPitch())
            }
            // 应用用户设定的语速/音调（在情感引擎基础上叠加）
            tts.setRate(prefs.ttsRate)
            tts.setPitch(prefs.ttsPitch)
            tts.speak(finalText, prefs.taiwanVoice)

            // 思考引擎：到反射周期就发起一次反思（异步，不阻塞）
            if (prefs.mindEnabled && mind.shouldReflect()) {
                runCatching {
                    val rp = mind.reflectionPrompt()
                    val oldExtra = llm.extraSystemPrompt
                    llm.extraSystemPrompt = rp
                    val r = llm.chat(listOf(ChatMessage("user", rp, isMe = true)))
                    llm.extraSystemPrompt = oldExtra
                    mind.applyReflection(r)
                }
            }
        }
    }

    private fun scrollToBottom() {
        recycler.post { recycler.scrollToPosition(adapter.itemCount - 1) }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy()
        wakeHelper?.stop()
        tts.shutdown()
    }
}
