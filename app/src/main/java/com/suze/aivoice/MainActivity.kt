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
    private lateinit var weather: WeatherClient
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var editInput: EditText
    private lateinit var tvStatus: TextView
    private lateinit var micHalo: View

    private var recognizer: SpeechRecognizer? = null
    private var wakeHelper: WakeWordHelper? = null
    private val history = mutableListOf<ChatMessage>()
    private var isSending = false
    // 防重复发送：记录上一次发送的原文与时间戳（部分语音引擎会把同一条结果回调两次）
    private var lastSentText: String = ""
    private var lastSentAt: Long = 0L
    // 是否正在识别中（防止重复启动语音识别）
    private var isListening = false
    // 语音聆听开关：开启后持续识别主人的话并自动发送
    private var listeningEnabled = false

    private val REQ_AUDIO = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        llm = LlmClient(prefs)
        tts = TtsHelper(this)
        store = HistoryStore(this)
        emotion = EmotionEngine(this)
        llm.bindEmotion(emotion)
        mind = MindEngine(this)
        memory = MemoryEngine(this)
        weather = WeatherClient()

        tvStatus = findViewById(R.id.tvStatus)
        micHalo = findViewById(R.id.micHalo)
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        btnMenu.setOnClickListener { v ->
            val pop = PopupMenu(this, v)
            pop.menu.add(0, 1, 0, getString(R.string.btn_settings))
            pop.menu.add(0, 5, 1, getString(R.string.menu_weather))
            pop.menu.add(0, 2, 2, getString(R.string.menu_clear))
            pop.menu.add(0, 4, 3, getString(R.string.menu_stop_speak))
            pop.menu.add(0, 3, 4, getString(R.string.menu_wake))
            pop.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                    5 -> { askCityAndShowWeather(); true }
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

        // 恢复本地历史，并清理旧版本因 history/adapter 共用列表产生的相邻重复项。
        val loadedHistory = collapseLegacyDuplicates(store.load())
        history.addAll(loadedHistory)
        if (loadedHistory.isNotEmpty()) store.save(history)
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
            // 语音聆听开关：开 → 关闭；关 → 开启（自动申请权限并持续识别）
            if (listeningEnabled) stopListeningMode() else startListeningMode()
        }

        // ===== 表情：emoji 面板 + 图片表情包 =====
        setupEmojiPanel()

        ensureAudioPermission()
        initRecognizer()
        refreshMoodSubtitle()
        // 恢复用户保存的音色、区域、语速和音调。
        if (prefs.taiwanVoice) {
            tts.applyTaiwanVoice()
        } else {
            val savedVoice = tts.availableVoices().getOrNull(prefs.voiceIndex)
            if (savedVoice != null) tts.setVoiceByName(savedVoice)
            else when {
                prefs.voiceLocaleName.contains("台湾") -> tts.setLocale(Locale.TAIWAN)
                prefs.voiceLocaleName.contains("香港") -> tts.setLocale(Locale("zh", "HK"))
                else -> tts.setLocale(Locale.CHINA)
            }
        }
        tts.setRate(prefs.ttsRate)
        tts.setPitch(prefs.ttsPitch)
    }

    /**
     * 旧版本把同一消息同时加入 history 和 adapter；两者实际共用一个列表，
     * 因而持久化出了成对的相邻重复项。这里只折叠完全相同且相邻的记录，
     * 不会删除主人隔一段时间主动重复发送的正常消息。
     */
    private fun collapseLegacyDuplicates(source: List<ChatMessage>): List<ChatMessage> {
        if (source.size < 2) return source
        val result = ArrayList<ChatMessage>(source.size)
        var index = 0
        while (index < source.size) {
            val current = source[index]
            result.add(current)
            if (index + 1 < source.size && source[index + 1] == current) index += 2
            else index += 1
        }
        return result
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

    /** 权限回调：授予麦克风权限后，如果用户刚才是点“开启聆听”，就自动开始 */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_AUDIO) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                // 授权成功：自动开启聆听
                startListeningMode()
            } else {
                Toast.makeText(this, "未授予麦克风权限，无法聆听", Toast.LENGTH_SHORT).show()
            }
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
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                isListening = false
                // 聆听模式：出错后稍等重试（如未检测到语音、超时等，属正常情况不打扰用户）
                if (listeningEnabled) {
                    if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(this@MainActivity, "语音识别失败: $error", Toast.LENGTH_SHORT).show()
                    }
                    scheduleRestartListening()
                } else {
                    setHalo(false)
                }
            }
            override fun onResults(results: Bundle?) {
                isListening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty() && !isSending) {
                    // 立刻上锁，防止同一轮识别被回调两次时两条都穿透
                    isSending = true
                    editInput.setText("")
                    sendToLlm(text)
                } else if (listeningEnabled) {
                    // 没识别到有效内容：继续听下一句
                    scheduleRestartListening()
                }
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
        // 已在识别/发送中就不重复启动，避免一次说话产生两份结果
        if (isListening || isSending) return
        isListening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (prefs.taiwanVoice) Locale.TAIWAN else Locale.CHINA)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
        setHalo(true)
    }

    /** 聆听模式的“续听”：识别一轮结束后，稍等片刻自动开始听下一句 */
    private fun scheduleRestartListening() {
        if (!listeningEnabled) return
        recycler.postDelayed({
            if (listeningEnabled && !isListening && !isSending) {
                startListening()
            }
        }, 500L)
    }

    /** 开启语音聆听：申请麦克风权限 → 持续识别主人的话并自动发送 */
    private fun startListeningMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            // 自动向系统申请麦克风权限，授权后会回调 onRequestPermissionsResult 继续开启
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            Toast.makeText(this, "请授予麦克风权限，之后会自动开始聆听", Toast.LENGTH_LONG).show()
            return
        }
        if (recognizer == null) {
            Toast.makeText(this, "当前设备不支持语音识别", Toast.LENGTH_SHORT).show()
            return
        }
        listeningEnabled = true
        tts.onSpeakDone = null   // 清掉旧回调，避免 stop() 触发意外续听
        tts.stop()
        setHalo(true)
        startListening()
        Toast.makeText(this, "已开启聆听，直接说话即可；再点一次麦克风可关闭", Toast.LENGTH_LONG).show()
    }

    /** 关闭语音聆听 */
    private fun stopListeningMode() {
        listeningEnabled = false
        isListening = false
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
        setHalo(false)
        Toast.makeText(this, "已关闭聆听", Toast.LENGTH_SHORT).show()
    }

    // ---------------- 发送：流式 / 非流式 ----------------
    private fun sendToLlm(userText: String) {
        // 防重复：2 秒内完全相同的文本只处理一次（语音引擎双回调 / 双击发送按钮）
        val now = System.currentTimeMillis()
        if (userText == lastSentText && now - lastSentAt < 2000L) return
        lastSentText = userText
        lastSentAt = now

        isSending = true

        // ⚡ 智能天气：主人说“北京天气”之类，直接走天气查询（不耗大模型）
        val wxCity = detectWeatherQuery(userText)
        if (wxCity != null) {
            isSending = false
            requestWeather(wxCity)
            return
        }

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
        // 表情包能力：告诉模型可发 emoji 与图片表情包
        extra.append("\n")
        extra.append("你可以自然地在聊天里使用 emoji 表情（如 😊👍😂）增添温度；")
        extra.append("当情绪强烈、想逗主人开心时，你可以发一张图片表情包，")
        extra.append("使用方式是在回复里写一个标记：[sticker:关键词]，")
        extra.append("可用关键词有：" + StickerLibrary.stickerHint() + "。")
        extra.append("注意：一条回复最多发一个表情包标记，不要解释这个标记。")
        llm.extraSystemPrompt = extra.toString().takeIf { it.isNotBlank() }
        adapter.add(ChatMessage("user", userText, isMe = true))
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

            // 表情包：解析 AI 回复里的 [sticker:xxx] 标记
            val (stickerUrl, textNoSticker) = StickerLibrary.parseImageTag(finalText)
            finalText = if (stickerUrl != null) {
                if (textNoSticker.isNotBlank()) textNoSticker else "（发了一张表情包）"
            } else finalText
            history[history.size - 1] = ChatMessage("assistant", finalText, isMe = false)
            adapter.updateLast(finalText)
            // 有贴图时，额外追加一条图片消息
            if (stickerUrl != null) {
                val imgMsg = ChatMessage("assistant", stickerUrl, isMe = false, type = ChatMessage.TYPE_IMAGE)
                adapter.add(imgMsg)
            }
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
            // 聆听模式：等 TTS 播报完再续听（避免把朗读声当输入造成自问自答）
            if (listeningEnabled) {
                tts.onSpeakDone = {
                    runOnUiThread {
                        if (listeningEnabled && !isSending) scheduleRestartListening()
                    }
                }
            } else {
                tts.onSpeakDone = null
            }
            // 用 EdgeTTS 普通话音色朗读（台湾腔已改为可选，默认关闭）
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

    /**
     * 初始化表情面板：动态生成 emoji 按钮，点击发送。
     * 面板默认隐藏，点左侧 emoji 按钮切换显示。
     */
    private fun setupEmojiPanel() {
        val btnEmoji = findViewById<ImageButton>(R.id.btnEmoji)
        val panel = findViewById<android.widget.HorizontalScrollView>(R.id.emojiPanel)
        val row = findViewById<android.widget.LinearLayout>(R.id.emojiRow)
        if (row.childCount == 0) {
            val size = (44 * resources.displayMetrics.density).toInt()
            StickerLibrary.emojiPanel.forEach { emo ->
                val tv = TextView(this)
                tv.text = emo
                tv.textSize = 26f
                tv.gravity = android.view.Gravity.CENTER
                val lp = android.widget.LinearLayout.LayoutParams(size, size)
                tv.layoutParams = lp
                tv.setOnClickListener { sendEmoji(emo) }
                row.addView(tv)
            }
        }
        btnEmoji.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    /** 发送一个 emoji 表情（以大字号气泡显示） */
    private fun sendEmoji(emoji: String) {
        if (isSending) return
        // 统一交给 sendToLlm 添加消息，避免同一个 emoji 被加入两次。
        findViewById<android.widget.HorizontalScrollView>(R.id.emojiPanel).visibility = View.GONE
        sendToLlm(emoji)
    }

    /** 菜单「查天气」：弹窗让主人输入城市（默认上次城市） */
    private fun askCityAndShowWeather() {
        val input = EditText(this)
        input.hint = getString(R.string.weather_city_hint)
        input.setText(prefs.lastCity)
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_weather))
            .setView(input)
            .setPositiveButton(getString(R.string.btn_send)) { _, _ ->
                val city = input.text.toString().trim().ifBlank { prefs.lastCity }
                requestWeather(city)
            }
            .setNegativeButton(getString(R.string.menu_weather_cancel), null)
            .show()
    }

    /** 发起天气查询，以聊天气泡形式展示并语音播报 */
    private fun requestWeather(city: String) {
        if (isSending) return
        isSending = true
        val c = city.trim().ifBlank { prefs.lastCity }
        prefs.lastCity = c
        tvStatus.text = "🌤 正在查询【" + c + "】天气…"
        lifecycleScope.launch {
            val info = weather.query(c)
            if (info == null) {
                runOnUiThread {
                    isSending = false
                    tvStatus.text = getString(R.string.status_idle)
                    Toast.makeText(this@MainActivity, getString(R.string.weather_fail), Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val text = info.toSpeakText()
            runOnUiThread {
                adapter.add(ChatMessage("user", c + "\u5929\u6c14", isMe = true))
                adapter.add(ChatMessage("assistant", text, isMe = false))
                scrollToBottom()
                store.save(history)
                isSending = false
                tvStatus.text = getString(R.string.status_idle)
                if (prefs.emotionEnabled) {
                    tts.setEmotion(emotion.ttsRate(), emotion.ttsPitch())
                }
                tts.setRate(prefs.ttsRate)
                tts.setPitch(prefs.ttsPitch)
                tts.speak(text, prefs.taiwanVoice)
            }
        }
    }

    /**
     * 智能识别：从主人的话里提取「查天气」意图与城市名。
     * 匹配到则返回城市名，否则返回 null。
     */
    private fun detectWeatherQuery(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        // 含“天气”关键词才触发
        if (!t.contains("\u5929\u6c14") && !t.contains("\u6c14\u6e29")) return null
        // 提取城市：去掉常见问法词
        var city = t
        val noise = listOf(
            "\u4eca\u5929", "\u660e\u5929", "\u540e\u5929", "\u73b0\u5728", "\u8bf7\u95ee", "\u5e2e\u6211",
            "\u67e5\u4e00\u4e0b", "\u67e5\u67e5", "\u67e5\u8be2", "\u7684", "\u5929\u6c14", "\u600e\u4e48\u6837",
            "\u5982\u4f55", "\u6c14\u6e29", "\u591a\u5c11\u5ea6", "\u5462", "\u5417", "\uff1f", "?", "\u3002", "\uff0c", ",", "\u4e00\u4e0b", "\u544a\u8bc9\u6211"
        )
        noise.forEach { city = city.replace(it, "") }
        city = city.trim()
        return city.ifBlank { prefs.lastCity }
    }

    private fun scrollToBottom() {
        recycler.post { recycler.scrollToPosition(adapter.itemCount - 1) }
    }

    override fun onPause() {
        super.onPause()
        // 退到后台时关闭聆听，避免持续占用麦克风
        if (listeningEnabled) stopListeningMode()
    }

    override fun onDestroy() {
        super.onDestroy()
        listeningEnabled = false
        tts.onSpeakDone = null
        recognizer?.destroy()
        wakeHelper?.stop()
        tts.shutdown()
    }
}
