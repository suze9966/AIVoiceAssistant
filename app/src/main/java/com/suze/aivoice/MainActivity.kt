package com.suze.aivoice

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var llm: LlmClient
    private lateinit var tts: TtsHelper
    private lateinit var store: HistoryStore
    private lateinit var reminders: ReminderStore
    private lateinit var todos: TodoStore
    private lateinit var utilities: UtilityClient
    private lateinit var calendarHelper: CalendarHelper
    private lateinit var backups: BackupStore
    private lateinit var searcher: SearchClient
    private lateinit var emotion: EmotionEngine
    private lateinit var mind: MindEngine
    private lateinit var memory: MemoryEngine
    private lateinit var weather: WeatherClient
    private lateinit var adapter: ChatAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var editInput: EditText
    private lateinit var tvStatus: TextView
    private lateinit var micHalo: View
    private lateinit var ivChatBackground: android.widget.ImageView
    private lateinit var chatBgScrim: View
    private lateinit var ivHeaderAvatar: android.widget.ImageView
    private lateinit var drawerLayout: DrawerLayout
    private var pickingAvatar = true
    private var settingsBinder: SettingsBinder? = null
    private val pickSettingsImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = if (pickingAvatar) ChatStyleStore.saveAvatar(this, uri)
            else ChatStyleStore.saveBackground(this, uri)
        Toast.makeText(
            this,
            if (!ok) getString(R.string.toast_image_failed)
            else if (pickingAvatar) getString(R.string.toast_avatar_updated)
            else getString(R.string.toast_background_updated),
            Toast.LENGTH_SHORT
        ).show()
        if (ok) {
            settingsBinder?.refreshAppearancePreview()
            applyChatStyle()
        }
    }
    private val pickBackupZip = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = backups.importZip(uri)
        Toast.makeText(this, if (ok) R.string.toast_backup_imported else R.string.toast_backup_fail, Toast.LENGTH_LONG).show()
    }
    private val pickVisionImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        askAboutImage(uri)
    }
    private val takeVisionPhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        pendingCameraUri = null
        if (ok && uri != null) askAboutImage(uri)
    }
    private var pendingCameraUri: Uri? = null
    private var pendingVisionPrompt: String = "请用中文简短说明这张图。"
    private var greetingBusy = false
    private var pendingCalendarUser: String? = null
    private var pendingCalendarAddMillis: Long = 0L
    private var pendingCalendarAddTitle: String? = null

    private var recognizer: SpeechRecognizer? = null
    private var wakeHelper: WakeWordHelper? = null
    private val history = mutableListOf<ChatMessage>()
    private var isSending = false
    // 防重复发送：记录上一次发送的原文与时间戳（部分语音引擎会把同一条结果回调两次）
    private var lastSentText: String = ""
    private var lastSentAt: Long = 0L
    // 是否正在识别中（防止重复启动语音识别）
    private var isListening = false
    private var acceptingRecognition = false
    private var streamSpeechCancelled = false
    // 语音聆听开关：开启后持续识别主人的话并自动发送
    private var listeningEnabled = false
    // 只有点了麦克风才在授权后自动开启聆听；启动时申请权限不能顺带开麦。
    private var waitingMicForListen = false
    /** 流式回复里已经开口朗读到的位置，避免整段合成完才出声。 */
    private var streamSpokenUntil = 0
    private var streamSpeakStarted = false
    private var speechSession = 0L
    private var notBeforeListenAt = 0L
    private var activeChatId: String = ""
    private val REQ_AUDIO = 1001
    private val REQ_NOTIFY = 1002
    private val REQ_CALENDAR = 1003
    private val REQ_LOCATION = 1004
    private val REQ_CAMERA = 1005

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        llm = LlmClient(prefs)
        tts = TtsHelper(this, prefs)
        store = HistoryStore(this)
        reminders = ReminderStore(this)
        todos = TodoStore(this)
        utilities = UtilityClient()
        calendarHelper = CalendarHelper(this)
        backups = BackupStore(this)
        searcher = SearchClient()
        emotion = EmotionEngine(this)
        llm.bindEmotion(emotion)
        mind = MindEngine(this)
        memory = MemoryEngine(this)
        weather = WeatherClient(this, prefs)

        tvStatus = findViewById(R.id.tvStatus)
        micHalo = findViewById(R.id.micHalo)
        ivChatBackground = findViewById(R.id.ivChatBackground)
        chatBgScrim = findViewById(R.id.chatBgScrim)
        ivHeaderAvatar = findViewById(R.id.ivHeaderAvatar)
        applyChatStyle()
        setupSettingsDrawer()
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)
        btnMenu.setOnClickListener { v ->
            val pop = PopupMenu(this, v)
            pop.menu.add(0, 1, 0, getString(R.string.btn_settings))
            pop.menu.add(0, 8, 1, getString(R.string.menu_sessions))
            pop.menu.add(0, 9, 2, getString(R.string.menu_new_session))
            pop.menu.add(0, 10, 3, getString(R.string.menu_find_chat))
            pop.menu.add(0, 11, 4, getString(R.string.menu_export_chat))
            pop.menu.add(0, 12, 5, getString(R.string.menu_ask_image))
            pop.menu.add(0, 5, 6, getString(R.string.menu_weather))
            pop.menu.add(0, 6, 7, getString(R.string.menu_role_lounge))
            pop.menu.add(0, 7, 8, getString(R.string.menu_connect_llm))
            pop.menu.add(0, 2, 9, getString(R.string.menu_clear))
            pop.menu.add(0, 4, 10, getString(R.string.menu_stop_speak))
            pop.menu.add(0, 3, 11, getString(R.string.menu_wake))
            pop.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { openSettingsDrawer(); true }
                    8 -> { showSessionPicker(); true }
                    9 -> { startNewSession(); true }
                    10 -> { promptFindChat(); true }
                    11 -> { exportCurrentChat(); true }
                    12 -> { promptAskImage(); true }
                    5 -> { askCityAndShowWeather(); true }
                    6 -> { startActivity(Intent(this, RoleLoungeActivity::class.java)); true }
                    7 -> { startActivity(Intent(this, LlmConnectActivity::class.java)); true }
                    2 -> {
                        history.clear()
                        adapter.notifyDataSetChanged()
                        if (activeChatId.isNotBlank()) store.clear(activeChatId)
                        Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
                        true
                    }
                    4 -> { abortSpeech(); Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show(); true }
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
        activeChatId = store.migrateAndActive(prefs)
        val loadedHistory = collapseLegacyDuplicates(store.load(activeChatId))
        history.addAll(loadedHistory)
        if (loadedHistory.isNotEmpty()) store.save(activeChatId, history)
        adapter.notifyDataSetChanged()
        if (history.isNotEmpty()) scrollToBottom()
        // 欢迎语／引导（首次进入且无历史）
        setupChatInteractions()
        if (history.isEmpty() && prefs.welcomeEnabled) {
            adapter.add(ChatMessage(role = "assistant", content = getString(R.string.welcome_msg), isMe = false))
            persistHistory()
            scrollToBottom()
            prefs.lastBriefAt = System.currentTimeMillis()
        }
        handleRemindIntent(intent)

        val btnSend = findViewById<ImageButton>(R.id.btnSend)
        val btnMic = findViewById<ImageButton>(R.id.btnMic)

        fun submitInput(): Boolean {
            val text = editInput.text.toString().trim()
            if (text.isEmpty() || isSending) return false
            editInput.setText("")
            sendToLlm(text)
            return true
        }
        btnSend.setOnClickListener { submitInput() }
        editInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) submitInput() else false
        }
        btnMic.setOnClickListener {
            // 小智式打断：说话中点麦克风先停播，再继续听；没在说话才切换聆听开关。
            if (tts.isSpeaking || tts.hasQueuedSpeech()) {
                abortSpeech()
                if (listeningEnabled) startListening() else startListeningMode()
            } else if (listeningEnabled) {
                stopListeningMode()
            } else {
                startListeningMode()
            }
        }

        // ===== 表情：emoji 面板 + 图片表情包 =====
        setupEmojiPanel()

        ensureAudioPermission()
        initRecognizer()
        refreshMoodSubtitle()
        // 恢复用户保存的音色、区域、语速和音调。
        applySavedVoice()
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
                        persistHistory()
                        Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        val text = adapter.contentAt(pos)
                        if (text.isNotBlank()) speakLocal(text)
                    }
                    4 -> regenerateLast()
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
                    if (tts.isSpeaking || isSending) return@runOnUiThread
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
        if (listeningEnabled && !isListening && !isSending && !tts.isSpeaking) {
            scheduleRestartListening()
        }
        applySavedVoice()
        if (::emotion.isInitialized) {
            val care = if (prefs.emotionEnabled) emotion.resumeCare() else {
                emotion.tick()
                null
            }
            refreshMoodSubtitle()
            if (!care.isNullOrBlank() && ::adapter.isInitialized && ::store.isInitialized) {
                adapter.add(ChatMessage("assistant", care, isMe = false))
                persistHistory()
                scrollToBottom()
                if (::tts.isInitialized) {
                    tts.setEmotion(emotion.ttsRate(), emotion.ttsPitch())
                    tts.setRate(prefs.ttsRate)
                    tts.setPitch(prefs.ttsPitch)
                    if (!isSending && !tts.isSpeaking) {
                        suspendRecognitionForSpeech()
                        bindListenAfterSpeak()
                        tts.speak(care, prefs.taiwanVoice)
                    }
                }
            }
        }
        applyChatStyle()
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
        maybeGreetOnResume()
    }

    /** 应用可更换的小沫头像和聊天背景 */

    /** 从设置页回来时重载 Edge 音色/语速/音调；CosyVoice 读 Prefs，不走这里。 */
    private fun applySavedVoice() {
        if (!::tts.isInitialized || !::prefs.isInitialized) return
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
    private fun applyChatStyle() {
        if (!::ivChatBackground.isInitialized) return
        ChatStyleStore.applyBackground(ivChatBackground)
        chatBgScrim.visibility = if (ChatStyleStore.hasBackground(this)) View.VISIBLE else View.GONE
        ChatStyleStore.applyAvatar(ivHeaderAvatar)
    }

    private fun setupSettingsDrawer() {
        drawerLayout = findViewById(R.id.drawerLayout)
        findViewById<ImageButton>(R.id.btnDrawer).setOnClickListener {
            toggleSettingsDrawer()
        }
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            override fun onDrawerClosed(drawerView: View) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        })
        try {
            settingsBinder = SettingsBinder(
                activity = this,
                prefs = prefs,
                tts = tts,
                memory = memory,
                pickImage = { avatar ->
                    pickingAvatar = avatar
                    pickSettingsImage.launch("image/*")
                },
                pickBackup = { pickBackupZip.launch("application/zip") },
                onSaved = {
                    applySavedVoice()
                    applyChatStyle()
                    refreshMoodSubtitle()
                    if (::adapter.isInitialized) adapter.notifyDataSetChanged()
                    closeSettingsDrawer()
                }
            )
            settingsBinder?.bind()
        } catch (t: Throwable) {
            Toast.makeText(this, "设置栏初始化失败：" + t.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleSettingsDrawer() {
        if (!::drawerLayout.isInitialized) return
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) closeSettingsDrawer()
        else openSettingsDrawer()
    }

    private fun openSettingsDrawer() {
        if (::drawerLayout.isInitialized) drawerLayout.openDrawer(GravityCompat.START)
    }

    private fun closeSettingsDrawer() {
        if (::drawerLayout.isInitialized) drawerLayout.closeDrawer(GravityCompat.START)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::drawerLayout.isInitialized && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            closeSettingsDrawer()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
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
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_AUDIO -> {
                if (granted) {
                    if (waitingMicForListen || listeningEnabled) {
                        waitingMicForListen = false
                        startListeningMode()
                    }
                } else {
                    waitingMicForListen = false
                    listeningEnabled = false
                    Toast.makeText(this, "未授予麦克风权限，无法聆听", Toast.LENGTH_SHORT).show()
                }
            }
            REQ_CAMERA -> {
                if (granted) launchCameraForVision()
                else Toast.makeText(this, R.string.toast_vision_fail, Toast.LENGTH_SHORT).show()
            }
            REQ_CALENDAR -> {
                val userText = pendingCalendarUser
                val addTitle = pendingCalendarAddTitle
                val addMillis = pendingCalendarAddMillis
                pendingCalendarUser = null
                pendingCalendarAddTitle = null
                pendingCalendarAddMillis = 0L
                if (!granted || userText.isNullOrBlank()) {
                    if (!granted) Toast.makeText(this, R.string.toast_calendar_need_perm, Toast.LENGTH_SHORT).show()
                    return
                }
                if (addTitle != null) handleCalendarAdd(userText, addMillis, addTitle)
                else handleCalendarList(userText)
            }
            REQ_LOCATION -> {
                if (granted) {
                    locationCity(requestIfMissing = false)?.let { prefs.lastCity = it }
                }
            }
        }
    }

    private fun initRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                // 主人开口就打断朗读，但不能边播边听：手机没有小智那种 AEC，会把自己的声音当输入。
                if (tts.isSpeaking || System.currentTimeMillis() < notBeforeListenAt) {
                    suspendRecognitionForSpeech()
                }
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                if (!acceptingRecognition) return
                acceptingRecognition = false
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
                if (!acceptingRecognition) return
                acceptingRecognition = false
                isListening = false
                if (tts.isSpeaking || isSending || System.currentTimeMillis() < notBeforeListenAt) {
                    if (listeningEnabled) scheduleRestartListening()
                    return
                }
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) {
                    editInput.setText("")
                    sendToLlm(text)
                } else if (listeningEnabled) {
                    scheduleRestartListening()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!acceptingRecognition || tts.isSpeaking || isSending) return
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
        if (isListening || isSending || tts.isSpeaking || isFinishing || isDestroyed) return
        if (System.currentTimeMillis() < notBeforeListenAt) {
            scheduleRestartListening()
            return
        }
        acceptingRecognition = true
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
        val wait = (notBeforeListenAt - System.currentTimeMillis()).coerceAtLeast(280L)
        recycler.postDelayed({
            if (listeningEnabled && !isListening && !isSending && !tts.isSpeaking && System.currentTimeMillis() >= notBeforeListenAt) {
                startListening()
            }
        }, wait)
    }

    /** 开启语音聆听：申请麦克风权限 → 持续识别主人的话并自动发送 */
    private fun startListeningMode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            waitingMicForListen = true
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            Toast.makeText(this, "请授予麦克风权限，之后会自动开始聆听", Toast.LENGTH_LONG).show()
            return
        }
        if (recognizer == null) {
            Toast.makeText(this, "当前设备不支持语音识别", Toast.LENGTH_SHORT).show()
            return
        }
        listeningEnabled = true
        abortSpeech()
        setHalo(true)
        if (prefs.keepListenInBackground) {
            ensureNotifyPermission()
            ListenKeepAliveService.start(this)
        }
        startListening()
        Toast.makeText(this, "已开启聆听，直接说话即可；再点一次麦克风可关闭", Toast.LENGTH_LONG).show()
    }

    /** 关闭语音聆听 */
    private fun stopListeningMode() {
        listeningEnabled = false
        acceptingRecognition = false
        isListening = false
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.cancel() }
        ListenKeepAliveService.stop(this)
        setHalo(false)
        Toast.makeText(this, "已关闭聆听", Toast.LENGTH_SHORT).show()
    }

    // ---------------- 发送：流式 / 非流式 ----------------
    private fun sendToLlm(userText: String) {
        if (isSending) return
        // 防重复：2 秒内完全相同的文本只处理一次（语音引擎双回调 / 双击发送按钮）
        val now = System.currentTimeMillis()
        if (userText == lastSentText && now - lastSentAt < 2000L) {
            scheduleRestartListening()
            return
        }
        lastSentText = userText
        lastSentAt = now

        isSending = true
        speechSession += 1L
        val session = speechSession
        suspendRecognitionForSpeech()
        abortSpeech()
        streamSpeechCancelled = false
        streamSpokenUntil = 0
        streamSpeakStarted = false

        // ⚡ 本地口令：天气 / 提醒 / 搜索，先拦截再走大模型
        val wxCity = detectWeatherQuery(userText)
        if (wxCity != null) {
            isSending = false
            requestWeather(wxCity)
            return
        }
        val command = VoiceCommandParser.parse(userText)
        if (command != null) {
            isSending = false
            handleVoiceCommand(userText, command)
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
        llm.applyCloudThink = false
        llm.systemPromptOverride = if (prefs.taiwanVoice) prefs.effectiveTaiwanPrompt() else null

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
          try {
            val requestHistory = history.dropLast(1).filter { it.content.isNotBlank() }
            val rawText: String

            if (prefs.streamEnabled) {
                // 流式：逐字显示（打字机）
                rawText = llm.chatStream(requestHistory) { delta ->
                    runOnUiThread {
                        if (session != speechSession || isFinishing || isDestroyed || history.isEmpty()) return@runOnUiThread
                        val last = history.last()
                        val cur = last.content + delta
                        history[history.size - 1] = last.copy(content = cur)
                        adapter.updateLast(cur)
                        scrollToBottom()
                        while (true) {
                            val piece = takeCompletedSpeech(cur) ?: break
                            startOrQueueSpeak(piece)
                        }
                    }
                }.ifBlank { "（无回复）" }
            } else {
                val reply = llm.chat(requestHistory)
                runOnUiThread {
                    if (session == speechSession && history.isNotEmpty()) {
                        history[history.size - 1] = history.last().copy(content = reply)
                        adapter.updateLast(reply)
                        scrollToBottom()
                    }
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
            if (history.isNotEmpty()) {
                history[history.size - 1] = history.last().copy(content = finalText)
                adapter.updateLast(finalText)
            }
            // 有贴图时，额外追加一条图片消息
            if (stickerUrl != null) {
                val imgMsg = ChatMessage("assistant", stickerUrl, isMe = false, type = ChatMessage.TYPE_IMAGE)
                adapter.add(imgMsg)
            }
            scrollToBottom()
            persistHistory()
            // 内心独白：不展示正文，但记录到状态栏提示
            if (prefs.mindEnabled && thought.isNotBlank()) {
                runOnUiThread { tvStatus.text = "\uD83D\uDCAD $thought" }
            }
            if (prefs.growEnabled) refreshGrowthSubtitle()
            isSending = false

            // 小智式：第一句已经开口就只补后面；否则整段分句后先说第一句。
            if (session == speechSession && !streamSpeechCancelled) {
            val speakable = visibleSpeakable(if (streamSpeakStarted) rawText else finalText)
            val remain = if (streamSpeakStarted) {
                if (streamSpokenUntil in 0 until speakable.length) speakable.substring(streamSpokenUntil).trim() else ""
            } else {
                speakable
            }
            if (remain.isNotBlank()) startOrQueueSpeak(remain)
            }

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
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!isFinishing && !isDestroyed && history.isNotEmpty() && history.last().role == "assistant" && history.last().content.isBlank()) {
                val fallback = getString(R.string.role_empty_reply)
                history[history.size - 1] = history.last().copy(content = fallback)
                adapter.updateLast(fallback)
                persistHistory()
            }
          } finally {
            isSending = false
            persistHistory()
            if (!tts.isSpeaking) scheduleRestartListening()
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
        val c = city.trim().ifBlank { prefs.lastCity }
        prefs.lastCity = c
        isSending = true
        abortSpeech()
        tvStatus.text = "🌤 正在查询【" + c + "】天气…"
        lifecycleScope.launch {
          try {
            val info = weather.query(c)
            if (isFinishing || isDestroyed) return@launch
            if (info == null) {
                tvStatus.text = getString(R.string.status_idle)
                val message = getString(R.string.weather_fail)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                // 打开天气页展示完整处理建议；页面会再尝试一次，便于系统天气刚完成刷新时恢复。
                startActivity(Intent(this@MainActivity, WeatherActivity::class.java)
                    .putExtra(WeatherActivity.EXTRA_CITY, c))
                return@launch
            }
            val text = info.toSpeakText()
            prefs.lastWeatherBrief = text.take(48)
            XiaomoWidgetProvider.refresh(this@MainActivity)
            WeatherActivity.pendingInfo = info
            startActivity(Intent(this@MainActivity, WeatherActivity::class.java).putExtra(WeatherActivity.EXTRA_CITY, c))
            adapter.add(ChatMessage("user", c + "天气", isMe = true))
            adapter.add(ChatMessage("assistant", text, isMe = false))
            scrollToBottom()
            persistHistory()
            tvStatus.text = getString(R.string.status_idle)
            if (prefs.emotionEnabled) {
                tts.setEmotion(emotion.ttsRate(), emotion.ttsPitch())
            }
            tts.setRate(prefs.ttsRate)
            tts.setPitch(prefs.ttsPitch)
            suspendRecognitionForSpeech()
            bindListenAfterSpeak()
            tts.speak(text, prefs.taiwanVoice)
          } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!(isFinishing || isDestroyed)) {
                tvStatus.text = getString(R.string.status_idle)
                Toast.makeText(this@MainActivity, getString(R.string.weather_fail), Toast.LENGTH_LONG).show()
            }
          } finally {
            isSending = false
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


    private fun suspendRecognitionForSpeech() {
        acceptingRecognition = false
        isListening = false
        runCatching { recognizer?.cancel() }
    }

    private fun abortSpeech() {
        streamSpeechCancelled = true
        if (!::tts.isInitialized) return
        tts.onSpeakDone = null
        tts.stop()
        // Keep the spoken cursor: stopping must not cause a later full replay.
    }

    private fun bindListenAfterSpeak() {
        if (listeningEnabled) {
            tts.onSpeakDone = {
                runOnUiThread {
                    notBeforeListenAt = System.currentTimeMillis() + 700L
                    if (listeningEnabled && !isSending) scheduleRestartListening()
                }
            }
        } else {
            tts.onSpeakDone = null
        }
    }

    private fun applySpeakVoice() {
        if (prefs.emotionEnabled && ::emotion.isInitialized) {
            tts.setEmotion(emotion.ttsRate(), emotion.ttsPitch())
        }
        tts.setRate(prefs.ttsRate)
        tts.setPitch(prefs.ttsPitch)
    }

    private fun startOrQueueSpeak(text: String) {
        if (text.isBlank() || !::tts.isInitialized || streamSpeechCancelled) return
        suspendRecognitionForSpeech()
        applySpeakVoice()
        bindListenAfterSpeak()
        if (streamSpeakStarted || tts.isSpeaking) {
            tts.enqueueSpeak(text, prefs.taiwanVoice)
        } else {
            tts.speak(text, prefs.taiwanVoice)
        }
        streamSpeakStarted = true
    }

    /** 去掉内心独白和表情包标记，只留真正要读出口的字。 */
    private fun visibleSpeakable(full: String): String {
        var t = full
        val sayIdx = t.indexOf("SAY:")
        if (sayIdx >= 0) {
            t = t.substring(sayIdx + 4)
        } else if (prefs.mindEnabled && (t.contains("THOUGHT:") || t.contains("想法：") || t.contains("想法:"))) {
            return ""
        }
        val sticker = t.indexOf("[sticker:")
        if (sticker >= 0) t = t.substring(0, sticker)
        return t.trimStart()
    }

    /** 流式文本里已经成句的部分先送去合成，缩短首句等待。 */
    private fun takeCompletedSpeech(full: String): String? {
        val speakable = visibleSpeakable(full)
        if (streamSpokenUntil >= speakable.length) return null
        val rest = speakable.substring(streamSpokenUntil)
        val cut = rest.indexOfFirst { ch ->
            ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch.code == 10 || ch == '；'
        }
        if (cut < 0) return null
        val chunk = rest.substring(0, cut + 1).trim()
        if (chunk.length < 6) return null
        streamSpokenUntil += cut + 1
        return chunk
    }
    private fun scrollToBottom() {
        recycler.post { recycler.scrollToPosition(adapter.itemCount - 1) }
    }

    override fun onPause() {
        super.onPause()
        persistHistory()
        if (listeningEnabled && prefs.keepListenInBackground) {
            ListenKeepAliveService.start(this)
        } else {
            ListenKeepAliveService.stop(this)
            if (listeningEnabled) stopListeningMode()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRemindIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        listeningEnabled = false
        ListenKeepAliveService.stop(this)
        tts.onSpeakDone = null
        recognizer?.destroy()
        wakeHelper?.stop()
        tts.shutdown()
    }

    private fun persistHistory() {
        if (!::store.isInitialized || activeChatId.isBlank()) return
        store.save(activeChatId, history)
    }

    private fun switchToSession(id: String) {
        if (id.isBlank() || id == activeChatId) return
        persistHistory()
        activeChatId = id
        prefs.activeChatId = id
        history.clear()
        history.addAll(collapseLegacyDuplicates(store.load(id)))
        adapter.notifyDataSetChanged()
        if (history.isNotEmpty()) scrollToBottom()
    }

    private fun showSessionPicker() {
        val sessions = store.loadSessions()
        if (sessions.isEmpty()) {
            startNewSession()
            return
        }
        val labels = sessions.map { s ->
            val mark = if (s.id == activeChatId) "● " else ""
            val preview = s.preview.ifBlank { getString(R.string.role_preview_empty) }
            mark + s.title + "\n" + preview
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sessions)
            .setItems(labels) { _, which ->
                val selected = sessions.getOrNull(which) ?: return@setItems
                switchToSession(selected.id)
            }
            .setNeutralButton(R.string.menu_rename_session) { _, _ -> promptRenameSession() }
            .setNegativeButton(R.string.menu_delete_session) { _, _ -> confirmDeleteSession() }
            .setPositiveButton(R.string.role_cancel, null)
            .show()
    }

    private fun startNewSession() {
        persistHistory()
        val created = store.create()
        switchToSession(created.id)
        if (prefs.welcomeEnabled && history.isEmpty()) {
            adapter.add(ChatMessage(role = "assistant", content = getString(R.string.welcome_msg), isMe = false))
            persistHistory()
            scrollToBottom()
        }
        Toast.makeText(this, R.string.menu_new_session, Toast.LENGTH_SHORT).show()
    }

    private fun promptRenameSession() {
        val input = EditText(this)
        input.hint = getString(R.string.hint_rename_session)
        input.setText(store.loadSessions().firstOrNull { it.id == activeChatId }?.title.orEmpty())
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_rename_session)
            .setView(input)
            .setPositiveButton(R.string.role_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && activeChatId.isNotBlank()) {
                    store.rename(activeChatId, name)
                    Toast.makeText(this, R.string.toast_session_renamed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun confirmDeleteSession() {
        val remain = store.delete(activeChatId) ?: run {
            Toast.makeText(this, R.string.role_keep_one_chat, Toast.LENGTH_SHORT).show()
            return
        }
        activeChatId = remain
        prefs.activeChatId = remain
        history.clear()
        history.addAll(collapseLegacyDuplicates(store.load(remain)))
        adapter.notifyDataSetChanged()
        if (history.isNotEmpty()) scrollToBottom()
        Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
    }

    private fun handleVoiceCommand(userText: String, command: VoiceCommand) {
        when (command) {
            is VoiceCommand.RemindAt -> addReminder(userText, command.atMillis, command.text, command.repeatDaily, command.advanceMin)
            is VoiceCommand.RemindIn -> addReminder(userText, command.atMillis, command.text)
            VoiceCommand.ListReminders -> {
                val items = reminders.upcoming()
                val reply = if (items.isEmpty()) {
                    getString(R.string.toast_remind_none)
                } else {
                    items.joinToString("\n") { reminders.formatItem(it) }
                }
                replyLocal(userText, reply)
            }
            VoiceCommand.CancelReminders -> {
                reminders.cancelAll()
                replyLocal(userText, getString(R.string.toast_remind_cleared))
            }
            is VoiceCommand.Search -> runWebSearch(userText, command.query)
            is VoiceCommand.AddTodo -> {
                val item = todos.add(command.text)
                replyLocal(userText, getString(R.string.toast_todo_added, item.text))
            }
            VoiceCommand.ListTodos -> replyLocal(userText, todos.formatList())
            is VoiceCommand.DoneTodo -> {
                val done = todos.markDone(command.text)
                replyLocal(
                    userText,
                    if (done == null) getString(R.string.toast_todo_missing)
                    else getString(R.string.toast_todo_done, done.text)
                )
            }
            VoiceCommand.ClearTodos -> {
                todos.clearAll()
                replyLocal(userText, getString(R.string.toast_todo_cleared))
            }
            is VoiceCommand.Translate -> runUtility(userText) { utilities.translate(command.text, command.target) }
            is VoiceCommand.Convert -> runUtility(userText) { utilities.convert(command.amount, command.from, command.to) }
            is VoiceCommand.WorldClock -> replyLocal(userText, utilities.worldClock(command.place))
            VoiceCommand.News -> runUtility(userText) { utilities.news() }
            VoiceCommand.CalendarList -> handleCalendarList(userText)
            is VoiceCommand.CalendarAdd -> handleCalendarAdd(userText, command.atMillis, command.text)
            is VoiceCommand.FindChat -> replyLocal(userText, findInHistory(command.query))
            VoiceCommand.DailyBrief -> speakDailyBrief(userText)
        }
    }

    private fun addReminder(
        userText: String,
        atMillis: Long,
        topic: String,
        repeatDaily: Boolean = false,
        advanceMin: Int = 0
    ) {
        ensureNotifyPermission()
        val item = reminders.add(topic, atMillis, repeatDaily, advanceMin)
        replyLocal(userText, getString(R.string.toast_remind_set, reminders.formatItem(item), item.text))
    }

    private fun runWebSearch(userText: String, query: String) {
        if (!prefs.webSearchEnabled) {
            replyLocal(userText, getString(R.string.search_fail))
            return
        }
        isSending = true
        adapter.add(ChatMessage("user", userText, isMe = true))
        adapter.add(ChatMessage("assistant", "", isMe = false))
        scrollToBottom()
        tvStatus.text = "🔎 正在搜：$query"
        lifecycleScope.launch {
            try {
                val found = searcher.search(query)
                val reply = found.ifBlank { getString(R.string.search_fail) }
                if (isFinishing || isDestroyed) return@launch
                if (history.isNotEmpty()) {
                    history[history.size - 1] = history.last().copy(content = reply)
                    adapter.updateLast(reply)
                }
                persistHistory()
                scrollToBottom()
                speakLocal(reply)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (!isFinishing && !isDestroyed) {
                    val fail = getString(R.string.search_fail)
                    if (history.isNotEmpty() && history.last().role == "assistant") {
                        history[history.size - 1] = history.last().copy(content = fail)
                        adapter.updateLast(fail)
                    }
                    persistHistory()
                    speakLocal(fail)
                }
            } finally {
                isSending = false
                tvStatus.text = getString(R.string.status_idle)
                if (!tts.isSpeaking) scheduleRestartListening()
            }
        }
    }

    private fun replyLocal(userText: String, reply: String) {
        adapter.add(ChatMessage("user", userText, isMe = true))
        adapter.add(ChatMessage("assistant", reply, isMe = false))
        persistHistory()
        scrollToBottom()
        speakLocal(reply)
        if (!tts.isSpeaking) scheduleRestartListening()
    }

    private fun speakLocal(text: String) {
        if (text.isBlank() || !::tts.isInitialized) return
        applySpeakVoice()
        suspendRecognitionForSpeech()
        bindListenAfterSpeak()
        tts.speak(text, prefs.taiwanVoice)
    }

    private fun handleRemindIntent(intent: Intent?) {
        if (intent == null) return
        val notify = intent.getStringExtra(EXTRA_NOTIFY_SPEAK).orEmpty()
        if (notify.isNotBlank()) {
            intent.removeExtra(EXTRA_NOTIFY_SPEAK)
            val line = "通知：" + notify
            adapter.add(ChatMessage("assistant", line, isMe = false))
            persistHistory()
            scrollToBottom()
            speakLocal(line)
        }
        if (intent.getBooleanExtra(EXTRA_HEADSET_WAKE, false)) {
            intent.removeExtra(EXTRA_HEADSET_WAKE)
            toggleWake(true)
        }
        val text = intent.getStringExtra(EXTRA_REMIND_SPEAK).orEmpty()
        if (text.isBlank()) return
        intent.removeExtra(EXTRA_REMIND_SPEAK)
        val line = getString(R.string.remind_notif_title) + "：" + text
        adapter.add(ChatMessage("assistant", line, isMe = false))
        persistHistory()
        scrollToBottom()
        speakLocal(line)
    }

    private fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQ_NOTIFY
        )
    }

    private fun regenerateLast() {
        if (isSending) return
        val lastUserIndex = history.indexOfLast { it.role == "user" && it.content.isNotBlank() }
        if (lastUserIndex < 0) return
        val userText = history[lastUserIndex].content
        if (VoiceCommandParser.parse(userText) != null || detectWeatherQuery(userText) != null) {
            val lastReply = history.lastOrNull { !it.isMe && it.content.isNotBlank() }?.content.orEmpty()
            if (lastReply.isNotBlank()) speakLocal(lastReply)
            else speakLocal(getString(R.string.toast_regenerate_command))
            return
        }
        while (history.size > lastUserIndex + 1) history.removeAt(history.lastIndex)
        adapter.notifyDataSetChanged()
        persistHistory()
        sendToLlm(userText)
    }

    private fun promptFindChat() {
        val input = EditText(this)
        input.hint = getString(R.string.hint_find_chat)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_find_chat)
            .setView(input)
            .setPositiveButton(R.string.btn_send) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) replyLocal("搜索对话：" + q, findInHistory(q))
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun findInHistory(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "要找哪个字？"
        val hits = history.filter { it.content.contains(q) }.takeLast(6)
        if (hits.isEmpty()) return "对话里没找到「$q」。"
        return hits.joinToString("\n") { m ->
            val who = if (m.isMe) "主人" else "小沫"
            who + "：" + m.content.take(40)
        }
    }

    private fun exportCurrentChat() {
        val title = store.loadSessions().firstOrNull { it.id == activeChatId }?.title ?: "对话"
        val file = backups.exportChatMarkdown(title, history)
        if (file == null) {
            Toast.makeText(this, R.string.toast_backup_fail, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        val share = Intent(Intent.ACTION_SEND)
            .setType("text/markdown")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, getString(R.string.menu_export_chat)))
        Toast.makeText(this, R.string.toast_export_ok, Toast.LENGTH_SHORT).show()
    }

    private fun promptAskImage() {
        if (!llm.isConfigured()) {
            Toast.makeText(this, R.string.toast_vision_need_model, Toast.LENGTH_LONG).show()
            return
        }
        val input = EditText(this)
        input.hint = getString(R.string.hint_ask_image)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_ask_image)
            .setView(input)
            .setPositiveButton(R.string.ask_image_gallery) { _, _ ->
                pendingVisionPrompt = input.text.toString().trim().ifBlank { "请用中文简短说明这张图。" }
                pickVisionImage.launch("image/*")
            }
            .setNeutralButton(R.string.ask_image_camera) { _, _ ->
                pendingVisionPrompt = input.text.toString().trim().ifBlank { "请用中文简短说明这张图。" }
                launchCameraForVision()
            }
            .setNegativeButton(R.string.role_cancel, null)
            .show()
    }

    private fun launchCameraForVision() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            return
        }
        val dir = File(cacheDir, "vision_capture").apply { mkdirs() }
        val file = File(dir, "ask.jpg")
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        pendingCameraUri = uri
        takeVisionPhoto.launch(uri)
    }

    private fun askAboutImage(uri: Uri) {
        if (isSending) return
        isSending = true
        val prompt = pendingVisionPrompt
        adapter.add(ChatMessage("user", "问图：" + prompt, isMe = true))
        adapter.add(ChatMessage("assistant", "", isMe = false))
        scrollToBottom()
        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                val mime = contentResolver.getType(uri) ?: "image/jpeg"
                val reply = if (bytes.isEmpty()) "" else llm.chatVision(prompt, bytes.take(900_000).toByteArray(), mime)
                val text = reply.ifBlank { getString(R.string.toast_vision_fail) }
                if (!isFinishing && !isDestroyed && history.isNotEmpty()) {
                    history[history.size - 1] = history.last().copy(content = text)
                    adapter.updateLast(text)
                    persistHistory()
                    scrollToBottom()
                    speakLocal(text)
                }
            } catch (_: Exception) {
                if (!isFinishing && !isDestroyed) {
                    val fail = getString(R.string.toast_vision_fail)
                    if (history.isNotEmpty()) {
                        history[history.size - 1] = history.last().copy(content = fail)
                        adapter.updateLast(fail)
                    }
                    persistHistory()
                    speakLocal(fail)
                }
            } finally {
                isSending = false
                if (!tts.isSpeaking) scheduleRestartListening()
            }
        }
    }

    private fun runUtility(userText: String, block: () -> String) {
        isSending = true
        adapter.add(ChatMessage("user", userText, isMe = true))
        adapter.add(ChatMessage("assistant", "", isMe = false))
        scrollToBottom()
        lifecycleScope.launch {
            try {
                val reply = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
                    .ifBlank { getString(R.string.search_fail) }
                if (isFinishing || isDestroyed) return@launch
                if (history.isNotEmpty()) {
                    history[history.size - 1] = history.last().copy(content = reply)
                    adapter.updateLast(reply)
                }
                persistHistory()
                scrollToBottom()
                speakLocal(reply)
            } finally {
                isSending = false
                if (!tts.isSpeaking) scheduleRestartListening()
            }
        }
    }

    private fun handleCalendarList(userText: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCalendarUser = userText
            pendingCalendarAddTitle = null
            pendingCalendarAddMillis = 0L
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                REQ_CALENDAR
            )
            Toast.makeText(this, R.string.toast_calendar_need_perm, Toast.LENGTH_SHORT).show()
            return
        }
        val text = calendarHelper.upcoming()
        replyLocal(userText, if (text == "NO_PERM") getString(R.string.toast_calendar_need_perm) else text)
    }

    private fun handleCalendarAdd(userText: String, atMillis: Long, title: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingCalendarUser = userText
            pendingCalendarAddTitle = title
            pendingCalendarAddMillis = atMillis
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                REQ_CALENDAR
            )
            Toast.makeText(this, R.string.toast_calendar_need_perm, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = calendarHelper.add(title, atMillis)
        replyLocal(userText, getString(if (ok) R.string.toast_calendar_added else R.string.toast_calendar_fail))
    }

    private fun maybeGreetOnResume() {
        if (!prefs.welcomeEnabled || greetingBusy || isSending || tts.isSpeaking) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastBriefAt < 6 * 3600_000L) return
        prefs.lastBriefAt = now
        speakDailyBrief(null)
    }

    private fun speakDailyBrief(userText: String?) {
        greetingBusy = true
        lifecycleScope.launch {
            try {
                val city = locationCity() ?: prefs.lastCity
                if (city.isNotBlank()) prefs.lastCity = city
                val weatherText = runCatching { weather.query(city)?.toSpeakText().orEmpty() }.getOrDefault("")
                if (weatherText.isNotBlank()) {
                    prefs.lastWeatherBrief = weatherText.take(48)
                    XiaomoWidgetProvider.refresh(this@MainActivity)
                }
                val next = reminders.upcoming().firstOrNull()?.let { reminders.formatItem(it) } ?: "暂无提醒"
                val mood = if (::emotion.isInitialized) emotion.statusLine() else ""
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                val hello = when (hour) {
                    in 5..10 -> "早上好"
                    in 11..13 -> "中午好"
                    in 14..18 -> "下午好"
                    else -> "晚上好"
                }
                val reply = listOf(
                    hello + "，主人。",
                    weatherText.ifBlank { city + "天气等会儿再查。" },
                    "下一件提醒：" + next,
                    mood
                ).filter { it.isNotBlank() }.joinToString("\n")
                if (userText != null) replyLocal(userText, reply)
                else {
                    adapter.add(ChatMessage("assistant", reply, isMe = false))
                    persistHistory()
                    scrollToBottom()
                    speakLocal(reply)
                }
            } finally {
                greetingBusy = false
            }
        }
    }

    private fun locationCity(requestIfMissing: Boolean = true): String? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            if (requestIfMissing) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQ_LOCATION
                )
            }
            return null
        }
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (loc == null) null
            else android.location.Geocoder(this, Locale.CHINA)
                .getFromLocation(loc.latitude, loc.longitude, 1)
                ?.firstOrNull()?.locality
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val EXTRA_REMIND_SPEAK = "remind_speak"
        const val EXTRA_NOTIFY_SPEAK = "notify_speak"
        const val EXTRA_HEADSET_WAKE = "headset_wake"
    }
}
