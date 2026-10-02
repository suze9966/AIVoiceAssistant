package com.suze.aivoice

import android.content.Intent
import androidx.core.content.FileProvider
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.util.Locale

class SettingsBinder(
    private val activity: AppCompatActivity,
    private val prefs: Prefs,
    private val tts: TtsHelper,
    private val memory: MemoryEngine,
    private val pickImage: (avatar: Boolean) -> Unit,
    private val pickBackup: () -> Unit = {},
    private val onSaved: () -> Unit
) {
    fun bind() {
        val editBaseUrl = activity.findViewById<EditText>(R.id.editBaseUrl)
        val editApiKey = activity.findViewById<EditText>(R.id.editApiKey)
        val editModel = activity.findViewById<EditText>(R.id.editModel)
        val editSystem = activity.findViewById<EditText>(R.id.editSystem)
        val spinnerLocale = activity.findViewById<Spinner>(R.id.spinnerLocale)
        val switchTaiwan = activity.findViewById<SwitchCompat>(R.id.switchTaiwan)
        val editTaiwanPrompt = activity.findViewById<EditText>(R.id.editTaiwanPrompt)
        val spinnerVoice = activity.findViewById<Spinner>(R.id.spinnerVoice)
        val spinnerTtsEngine = activity.findViewById<Spinner>(R.id.spinnerTtsEngine)
        val spinnerVolcResource = activity.findViewById<Spinner>(R.id.spinnerVolcResource)
        val editVolcApiKey = activity.findViewById<EditText>(R.id.editVolcApiKey)
        val editVolcAppId = activity.findViewById<EditText>(R.id.editVolcAppId)
        val editVolcAccessKey = activity.findViewById<EditText>(R.id.editVolcAccessKey)
        val editSiliconflowKey = activity.findViewById<EditText>(R.id.editSiliconflowKey)
        val editCaiyunKey = activity.findViewById<EditText>(R.id.editCaiyunKey)
        val editCaiyunSecret = activity.findViewById<EditText>(R.id.editCaiyunSecret)
        val editCaiyunToken = activity.findViewById<EditText>(R.id.editCaiyunToken)
        val spinnerWeatherSource = activity.findViewById<Spinner>(R.id.spinnerWeatherSource)
        val editWake = activity.findViewById<EditText>(R.id.editWakeWord)
        val switchStream = activity.findViewById<SwitchCompat>(R.id.switchStream)
        val switchEmotion = activity.findViewById<SwitchCompat>(R.id.switchEmotion)
        val switchMind = activity.findViewById<SwitchCompat>(R.id.switchMind)
        val switchGrow = activity.findViewById<SwitchCompat>(R.id.switchGrow)
        val switchMemory = activity.findViewById<SwitchCompat>(R.id.switchMemory)
        val switchPanelRecall = activity.findViewById<SwitchCompat>(R.id.switchPanelRecall)
        val switchFreeChat = activity.findViewById<SwitchCompat>(R.id.switchFreeChat)
        val switchProactive = activity.findViewById<SwitchCompat>(R.id.switchProactive)
        val spinnerProactiveInterval = activity.findViewById<Spinner>(R.id.spinnerProactiveInterval)
        val switchKeepListen = activity.findViewById<SwitchCompat>(R.id.switchKeepListen)
        val switchWebSearch = activity.findViewById<SwitchCompat>(R.id.switchWebSearch)
        val switchNotifySpeak = activity.findViewById<SwitchCompat>(R.id.switchNotifySpeak)
        val switchLockScreenMsg = activity.findViewById<SwitchCompat>(R.id.switchLockScreenMsg)
        val switchHeadsetWake = activity.findViewById<SwitchCompat>(R.id.switchHeadsetWake)
        val switchLocalKws = activity.findViewById<SwitchCompat>(R.id.switchLocalKws)
        val switchWelcome = activity.findViewById<SwitchCompat>(R.id.switchWelcome)
        val spinnerPortrait = activity.findViewById<Spinner>(R.id.spinnerPortrait)
        val btnTest = activity.findViewById<Button>(R.id.btnTestVoice)
        val seekRate = activity.findViewById<SeekBar>(R.id.seekRate)
        val seekPitch = activity.findViewById<SeekBar>(R.id.seekPitch)
        val btnStopSpeak = activity.findViewById<Button>(R.id.btnStopSpeak)
        val btnSave = activity.findViewById<Button>(R.id.btnSave)
        bindAppearance()
        activity.findViewById<Button>(R.id.btnConnectLlm).setOnClickListener {
            activity.startActivity(Intent(activity, LlmConnectActivity::class.java))
        }
        activity.findViewById<Button>(R.id.btnVoiceClone).setOnClickListener {
            activity.startActivity(Intent(activity, VoiceCloneActivity::class.java))
        }
        editBaseUrl.setText(prefs.baseUrl)
        val hasKey = prefs.apiKey.isNotBlank()
        editApiKey.setText("")
        editApiKey.hint = if (hasKey) "已加密保存（留空则不修改）" else activity.getString(R.string.label_apikey_hint)
        listOf(editApiKey, editCaiyunKey, editCaiyunSecret, editCaiyunToken, editSiliconflowKey, editVolcApiKey, editVolcAppId, editVolcAccessKey).forEach {
            it.filterTouchesWhenObscured = true
        }
        editSiliconflowKey.setText("")
        editSiliconflowKey.hint = if (prefs.siliconflowKey.isNotBlank()) "已加密保存 · 留空不修改" else "硅基流动 API Key（cloud.siliconflow.cn）"
        editVolcApiKey.setText("")
        editVolcApiKey.hint = if (prefs.volcApiKey.isNotBlank()) "已加密保存 · 留空不修改" else activity.getString(R.string.label_volc_api_key_hint)
        editVolcAppId.setText("")
        editVolcAppId.hint = if (prefs.volcAppId.isNotBlank()) "已加密保存 · 留空不修改" else activity.getString(R.string.label_volc_app_id_hint)
        editVolcAccessKey.setText("")
        editVolcAccessKey.hint = if (prefs.volcAccessKey.isNotBlank()) "已加密保存 · 留空不修改" else activity.getString(R.string.label_volc_access_key_hint)
        editModel.setText(prefs.model)
        editSystem.setText(prefs.systemPrompt)
        editCaiyunKey.hint = if (prefs.caiyunAppKey.isNotBlank()) "已加密保存 · 留空不修改" else "请输入彩云 App Key"
        editCaiyunSecret.hint = if (prefs.caiyunAppSecret.isNotBlank()) "已加密保存 · 留空不修改" else "请输入彩云 App Secret"
        editCaiyunToken.hint = if (prefs.caiyunToken.isNotBlank()) "旧 Token 已加密保存 · 留空不修改" else "可选：旧版 Token"
        editWake.setText(prefs.wakeWord)
        switchStream.isChecked = prefs.streamEnabled
        switchEmotion.isChecked = prefs.emotionEnabled
        switchMind.isChecked = prefs.mindEnabled
        switchGrow.isChecked = prefs.growEnabled
        switchMemory.isChecked = prefs.memoryEnabled
        switchPanelRecall.isChecked = prefs.panelRecallEnabled
        switchFreeChat.isChecked = prefs.freeChatEnabled
        switchProactive.isChecked = prefs.proactiveEnabled
        val proactiveLabels = Prefs.PROACTIVE_INTERVAL_OPTIONS.map { it.second }
        spinnerProactiveInterval.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, proactiveLabels)
        val proactiveIdx = Prefs.PROACTIVE_INTERVAL_OPTIONS.indexOfFirst { it.first == prefs.proactiveIntervalMin }
        if (proactiveIdx >= 0) spinnerProactiveInterval.setSelection(proactiveIdx)
        switchKeepListen.isChecked = prefs.keepListenInBackground
        switchWebSearch.isChecked = prefs.webSearchEnabled
        switchNotifySpeak.isChecked = prefs.notifySpeakEnabled
        switchLockScreenMsg.isChecked = prefs.lockScreenNotifyEnabled
        switchHeadsetWake.isChecked = prefs.headsetWakeEnabled
        switchLocalKws.isChecked = prefs.localKwsEnabled
        switchWelcome.isChecked = prefs.welcomeEnabled
        val portraitLabels = PortraitLibrary.all().map { it.name }
        spinnerPortrait.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, portraitLabels)
        val portraitIdx = PortraitLibrary.all().indexOfFirst { it.id == prefs.portraitId }
        if (portraitIdx >= 0) spinnerPortrait.setSelection(portraitIdx)
        activity.findViewById<Button>(R.id.btnBackupExport).setOnClickListener {
            val file = BackupStore(activity).exportZip()
            if (file == null) {
                Toast.makeText(activity, R.string.toast_backup_fail, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".fileprovider", file)
            val share = Intent(Intent.ACTION_SEND)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivity(Intent.createChooser(share, activity.getString(R.string.btn_backup_export)))
            Toast.makeText(activity, R.string.toast_backup_ok, Toast.LENGTH_SHORT).show()
        }
        activity.findViewById<Button>(R.id.btnBackupImport).setOnClickListener { pickBackup() }
        switchTaiwan.isChecked = prefs.taiwanVoice
        editTaiwanPrompt.setText(prefs.taiwanPrompt)
        seekRate.progress = (((prefs.ttsRate - 0.5f) / 1.1f) * 110f).toInt().coerceIn(0, 110)
        seekPitch.progress = (((prefs.ttsPitch - 0.5f) / 1.1f) * 110f).toInt().coerceIn(0, 110)
        val localeLabels = try { tts.localeOptions.map { it.first } } catch (t: Throwable) { listOf("普通话（大陆）") }
        spinnerLocale.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, localeLabels)
        val savedIdx = localeLabels.indexOfFirst { it == prefs.voiceLocaleName }
        if (savedIdx >= 0) spinnerLocale.setSelection(savedIdx)
        spinnerLocale.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val loc = tts.localeOptions.getOrNull(pos)?.second ?: Locale.CHINA
                tts.setLocale(loc)
                refreshVoices(spinnerVoice)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        switchTaiwan.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val name = tts.applyTaiwanVoice()
                val twIdx = localeLabels.indexOfFirst { it.contains("台湾") }
                if (twIdx >= 0) spinnerLocale.setSelection(twIdx)
                refreshVoices(spinnerVoice)
                val strongTwIndex = tts.availableVoices().indexOfFirst { it.contains("湾湾小何") || it.contains("台湾腔") }
                if (strongTwIndex >= 0) spinnerVoice.setSelection(strongTwIndex)
                Toast.makeText(activity, if (name != null) "已切换台湾音色：$name" else "未找到台湾音色，已回退中文音色", Toast.LENGTH_SHORT).show()
            } else {
                tts.setLocale(Locale.CHINA)
                refreshVoices(spinnerVoice)
            }
        }
        val engineLabels = Prefs.TTS_ENGINE_OPTIONS.map { it.second }
        spinnerTtsEngine.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, engineLabels)
        val engineIdx = Prefs.TTS_ENGINE_OPTIONS.indexOfFirst { it.first == prefs.ttsEngine }
        if (engineIdx >= 0) spinnerTtsEngine.setSelection(engineIdx)
        val resourceLabels = Prefs.VOLC_RESOURCE_OPTIONS.map { it.second }
        spinnerVolcResource.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, resourceLabels)
        val resourceIdx = Prefs.VOLC_RESOURCE_OPTIONS.indexOfFirst { it.first == prefs.volcResourceId }
        if (resourceIdx >= 0) spinnerVolcResource.setSelection(resourceIdx)
        val weatherLabels = Prefs.WEATHER_SOURCE_OPTIONS.map { it.second }
        spinnerWeatherSource.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, weatherLabels)
        val weatherIdx = Prefs.WEATHER_SOURCE_OPTIONS.indexOfFirst { it.first == prefs.weatherSource }
        if (weatherIdx >= 0) spinnerWeatherSource.setSelection(weatherIdx)
        spinnerVoice.postDelayed({ refreshVoices(spinnerVoice) }, 800)

        btnTest.setOnClickListener {
            val demo = if (switchTaiwan.isChecked)
                "欸，主人你好喔！今天过得还好吗？这个真的很可以耶，我陪你一起聊，好不好嘛～"
            else
                "你好主人，这是当前的语音音色试听效果。"
            previewCurrentVoice(
                spinnerVoice,
                spinnerTtsEngine,
                switchTaiwan.isChecked,
                0.5f + seekRate.progress / 100f,
                0.5f + seekPitch.progress / 100f,
                demo
            )
        }
        btnStopSpeak.setOnClickListener {
            tts.stop()
            Toast.makeText(activity, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }
        btnSave.setOnClickListener {
            val baseUrl = editBaseUrl.text.toString().trim()
            if (!isSafeHttpsBaseUrl(baseUrl)) {
                editBaseUrl.error = activity.getString(R.string.error_https_base_url)
                editBaseUrl.requestFocus()
                Toast.makeText(activity, R.string.error_https_base_url, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.baseUrl = baseUrl
            val newKey = editApiKey.text.toString().trim()
            if (newKey.isNotEmpty()) {
                prefs.apiKey = newKey
                editApiKey.setText("")
                Toast.makeText(activity, R.string.toast_key_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, R.string.toast_key_on, Toast.LENGTH_SHORT).show()
            }
            editCaiyunKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.caiyunAppKey = it }
            editCaiyunSecret.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.caiyunAppSecret = it }
            editCaiyunToken.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.caiyunToken = it }
            editCaiyunKey.setText("")
            editCaiyunSecret.setText("")
            editCaiyunToken.setText("")
            prefs.model = editModel.text.toString().trim()
            prefs.systemPrompt = editSystem.text.toString().trim()
            prefs.wakeWord = editWake.text.toString().trim().ifBlank { "你好小沫" }
            prefs.streamEnabled = switchStream.isChecked
            prefs.emotionEnabled = switchEmotion.isChecked
            prefs.mindEnabled = switchMind.isChecked
            prefs.growEnabled = switchGrow.isChecked
            prefs.memoryEnabled = switchMemory.isChecked
            prefs.panelRecallEnabled = switchPanelRecall.isChecked
            prefs.freeChatEnabled = switchFreeChat.isChecked
            prefs.proactiveEnabled = switchProactive.isChecked
            Prefs.PROACTIVE_INTERVAL_OPTIONS.getOrNull(spinnerProactiveInterval.selectedItemPosition)?.first?.let { prefs.proactiveIntervalMin = it }
            prefs.keepListenInBackground = switchKeepListen.isChecked
            prefs.webSearchEnabled = switchWebSearch.isChecked
            val turnOnNotify = switchNotifySpeak.isChecked && !prefs.notifySpeakEnabled
            prefs.notifySpeakEnabled = switchNotifySpeak.isChecked
            val turnOnLockMsg = switchLockScreenMsg.isChecked && !prefs.lockScreenNotifyEnabled
            prefs.lockScreenNotifyEnabled = switchLockScreenMsg.isChecked
            prefs.headsetWakeEnabled = switchHeadsetWake.isChecked
            prefs.localKwsEnabled = switchLocalKws.isChecked
            prefs.welcomeEnabled = switchWelcome.isChecked
            PortraitLibrary.all().getOrNull(spinnerPortrait.selectedItemPosition)?.id?.let { prefs.portraitId = it }
            if (turnOnNotify) {
                runCatching {
                    activity.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
                }
            }
            if (turnOnLockMsg) {
                // 提前建好渠道，并提示：若系统没给通知权限，锁屏就看不到
                MessageNotifier.ensureChannel(activity)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                        activity, android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        Toast.makeText(activity, R.string.toast_lock_msg_need_notify, Toast.LENGTH_LONG).show()
                    }
                }
            }
            prefs.taiwanVoice = switchTaiwan.isChecked
            val tp = editTaiwanPrompt.text.toString().trim()
            if (tp.isNotBlank()) prefs.taiwanPrompt = tp
            val locIdx = spinnerLocale.selectedItemPosition
            prefs.voiceLocaleName = localeLabels.getOrNull(locIdx) ?: "普通话（大陆）"
            prefs.voiceIndex = spinnerVoice.selectedItemPosition
            prefs.weatherSource = Prefs.WEATHER_SOURCE_OPTIONS.getOrNull(spinnerWeatherSource.selectedItemPosition)?.first ?: "auto"
            prefs.ttsEngine = Prefs.TTS_ENGINE_OPTIONS.getOrNull(spinnerTtsEngine.selectedItemPosition)?.first ?: "auto"
            prefs.volcResourceId = Prefs.VOLC_RESOURCE_OPTIONS.getOrNull(spinnerVolcResource.selectedItemPosition)?.first ?: prefs.volcResourceId
            val voiceName = spinnerVoice.selectedItem?.toString()
            Prefs.VOLC_VOICE_OPTIONS.firstOrNull { it.second == voiceName }?.first?.let { prefs.volcSpeaker = it }
            editSiliconflowKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.siliconflowKey = it }
            editSiliconflowKey.setText("")
            editVolcApiKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.volcApiKey = it }
            editVolcAppId.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.volcAppId = it }
            editVolcAccessKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.volcAccessKey = it }
            editVolcApiKey.setText("")
            editVolcAppId.setText("")
            editVolcAccessKey.setText("")
            prefs.ttsRate = 0.5f + seekRate.progress / 100f
            prefs.ttsPitch = 0.5f + seekPitch.progress / 100f
            val vName = spinnerVoice.selectedItem?.toString()
            if (vName != null && !vName.startsWith("（")) {
                tts.setVoiceByName(vName)
            }
            if (prefs.cloneVoiceEnabled) {
                Toast.makeText(activity, activity.getString(R.string.toast_clone_covers_cosy), Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(activity, "已保存", Toast.LENGTH_SHORT).show()
            }
            onSaved()
        }
    }

    private fun previewCurrentVoice(
        spinnerVoice: Spinner,
        spinnerTtsEngine: Spinner,
        taiwan: Boolean,
        rate: Float,
        pitch: Float,
        demo: String
    ) {
        tts.setRate(rate)
        tts.setPitch(pitch)
        val vName = spinnerVoice.selectedItem?.toString()
        if (vName != null && !vName.startsWith("（")) {
            tts.setVoiceByName(vName)
        }
        val prevEngine = prefs.ttsEngine
        val prevSpeaker = prefs.volcSpeaker
        prefs.ttsEngine = Prefs.TTS_ENGINE_OPTIONS.getOrNull(spinnerTtsEngine.selectedItemPosition)?.first ?: prevEngine
        Prefs.VOLC_VOICE_OPTIONS.firstOrNull { it.second == vName }?.first?.let { prefs.volcSpeaker = it }
        if (prefs.cloneVoiceEnabled) {
            Toast.makeText(activity, activity.getString(R.string.toast_clone_covers_cosy), Toast.LENGTH_LONG).show()
        }
        tts.speak(demo, taiwan)
        prefs.ttsEngine = prevEngine
        prefs.volcSpeaker = prevSpeaker
    }

    private fun bindAppearance() {
        activity.findViewById<Button>(R.id.btnChangeAvatar).setOnClickListener {
            pickImage(true)
        }
        activity.findViewById<Button>(R.id.btnResetAvatar).setOnClickListener {
            ChatStyleStore.clearAvatar(activity)
            refreshAppearancePreview()
            Toast.makeText(activity, R.string.toast_avatar_reset, Toast.LENGTH_SHORT).show()
        }
        activity.findViewById<Spinner>(R.id.spinnerPortrait).onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    refreshAppearancePreview()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        activity.findViewById<Button>(R.id.btnChangeBackground).setOnClickListener {
            pickImage(false)
        }
        activity.findViewById<Button>(R.id.btnResetBackground).setOnClickListener {
            ChatStyleStore.clearBackground(activity)
            refreshAppearancePreview()
            Toast.makeText(activity, R.string.toast_background_reset, Toast.LENGTH_SHORT).show()
        }
        // ---- 无框模式 + 字体设置 ----------------
        bindFramelessAndFont()
        // 气泡样式：预设 Spinner + 重置
        val spinnerBubble = activity.findViewById<Spinner>(R.id.spinnerBubblePreset)
        val bubbleIds = listOf("", BubbleStyleStore.PRESET_GIRL, BubbleStyleStore.PRESET_WECHAT,
            BubbleStyleStore.PRESET_MINIMAL, BubbleStyleStore.PRESET_DARK,
            BubbleStyleStore.PRESET_OCEAN, BubbleStyleStore.PRESET_CANDY,
            BubbleStyleStore.PRESET_CYBER, BubbleStyleStore.PRESET_FOREST)
        val bubbleLabels = listOf(
            activity.getString(R.string.bubble_preset_default),
            activity.getString(R.string.bubble_preset_girl),
            activity.getString(R.string.bubble_preset_wechat),
            activity.getString(R.string.bubble_preset_minimal),
            activity.getString(R.string.bubble_preset_dark),
            activity.getString(R.string.bubble_preset_ocean),
            activity.getString(R.string.bubble_preset_candy),
            activity.getString(R.string.bubble_preset_cyber),
            activity.getString(R.string.bubble_preset_forest)
        )
        spinnerBubble.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, bubbleLabels)
        spinnerBubble.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val presetId = bubbleIds.getOrNull(pos) ?: ""
                if (presetId.isEmpty()) {
                    BubbleStyleStore.reset(activity)
                } else {
                    BubbleStyleStore.preset(activity, presetId)
                }
                refreshBubblePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        activity.findViewById<Button>(R.id.btnBubbleReset).setOnClickListener {
            BubbleStyleStore.reset(activity)
            spinnerBubble.setSelection(0, false)
            refreshBubblePreview()
            Toast.makeText(activity, R.string.toast_bubble_reset, Toast.LENGTH_SHORT).show()
        }
        activity.findViewById<Button>(R.id.btnBubbleRandom).setOnClickListener {
            val current = BubbleStyleStore.detectCurrentPreset(activity)
            val picked = BubbleStyleStore.randomPreset(activity, current)
            val idx = bubbleIds.indexOf(picked)
            if (idx >= 0) spinnerBubble.setSelection(idx, false)
            refreshBubblePreview()
            Toast.makeText(
                activity,
                activity.getString(R.string.toast_bubble_random, BubbleStyleStore.presetLabel(picked), ""),
                Toast.LENGTH_SHORT
            ).show()
        }
        bindBubbleExtras()
        refreshAppearancePreview()
    }

    /** 无框模式开关 + 字体（字号 / 字形 / 行距）绑定。 */
    private fun bindFramelessAndFont() {
        val switchFrameless = activity.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.switchBubbleFrameless)
        switchFrameless.isChecked = prefs.bubbleFrameless
        switchFrameless.setOnCheckedChangeListener { _, on ->
            prefs.bubbleFrameless = on
        }

        val seekSize = activity.findViewById<SeekBar>(R.id.seekFontSize)
        val seekLine = activity.findViewById<SeekBar>(R.id.seekFontLine)
        val tvSizeLabel = activity.findViewById<TextView>(R.id.tvFontSizeLabel)
        val tvLineLabel = activity.findViewById<TextView>(R.id.tvFontLineLabel)
        val tvPreview = activity.findViewById<TextView>(R.id.tvFontPreview)
        val spinnerFamily = activity.findViewById<Spinner>(R.id.spinnerFontFamily)
        val checkBold = activity.findViewById<CheckBox>(R.id.checkFontBold)
        val checkItalic = activity.findViewById<CheckBox>(R.id.checkFontItalic)

        // 字形选择器
        val familyIds = listOf(
            ChatFontStore.FONT_DEFAULT, ChatFontStore.FONT_SERIF,
            ChatFontStore.FONT_SANS, ChatFontStore.FONT_MONO, ChatFontStore.FONT_CURSIVE
        )
        val familyLabels = familyIds.map { ChatFontStore.familyLabel(it) }
        spinnerFamily.adapter = ArrayAdapter(
            activity, android.R.layout.simple_spinner_dropdown_item, familyLabels
        )

        fun applyPreview() {
            val f = ChatFontStore.load(activity)
            val size = ChatFontStore.SIZE_MIN + seekSize.progress
            val line = ChatFontStore.LINE_MIN + seekLine.progress * 0.1f
            tvPreview.textSize = size
            tvPreview.typeface = ChatFontStore.typeface(f.copy(sizeSp = size, lineMul = line))
            tvPreview.setLineSpacing(ChatFontStore.lineSpacingExtra(f.copy(sizeSp = size, lineMul = line)), 1f)
            tvSizeLabel.text = "字号 ${size}sp"
            tvLineLabel.text = "行距 " + String.format(java.util.Locale.CHINA, "%.1f", line) + " 倍"
        }

        fun saveFromControls() {
            val size = ChatFontStore.SIZE_MIN + seekSize.progress
            val line = ChatFontStore.LINE_MIN + seekLine.progress * 0.1f
            val family = familyIds.getOrNull(spinnerFamily.selectedItemPosition)
                ?: ChatFontStore.FONT_DEFAULT
            ChatFontStore.save(
                activity,
                ChatFontStore.Font(
                    sizeSp = size,
                    lineMul = line,
                    family = family,
                    bold = checkBold.isChecked,
                    italic = checkItalic.isChecked
                )
            )
            applyPreview()
        }

        // 初始值
        val cur = ChatFontStore.load(activity)
        seekSize.progress = (cur.sizeSp - ChatFontStore.SIZE_MIN).toInt().coerceIn(0, 29)
        seekLine.progress = ((cur.lineMul - ChatFontStore.LINE_MIN) / 0.1f).toInt().coerceIn(0, 13)
        spinnerFamily.setSelection(familyIds.indexOf(cur.family).coerceAtLeast(0), false)
        checkBold.isChecked = cur.bold
        checkItalic.isChecked = cur.italic
        applyPreview()

        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { applyPreview() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saveFromControls() }
        })
        seekLine.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { applyPreview() }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { saveFromControls() }
        })
        spinnerFamily.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                saveFromControls()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        checkBold.setOnCheckedChangeListener { _, _ -> saveFromControls() }
        checkItalic.setOnCheckedChangeListener { _, _ -> saveFromControls() }
    }

    /** 尾巴方向 / 出现动效 / 去引号 三个维度的绑定。 */
    private fun bindBubbleExtras() {
        val spinnerTail = activity.findViewById<Spinner>(R.id.spinnerBubbleTail)
        val tailIds = listOf(BubbleStyleStore.TAIL_AUTO, BubbleStyleStore.TAIL_LEFT,
            BubbleStyleStore.TAIL_RIGHT, BubbleStyleStore.TAIL_NONE)
        val tailLabels = listOf(
            activity.getString(R.string.bubble_tail_auto),
            activity.getString(R.string.bubble_tail_left),
            activity.getString(R.string.bubble_tail_right),
            activity.getString(R.string.bubble_tail_none)
        )
        spinnerTail.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, tailLabels)
        spinnerTail.setSelection(tailIds.indexOf(BubbleStyleStore.load(activity).tailSide).coerceAtLeast(0), false)
        spinnerTail.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val tail = tailIds.getOrNull(pos) ?: return
                val now = BubbleStyleStore.load(activity)
                BubbleStyleStore.save(activity, now.copy(tailSide = tail))
                refreshBubblePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val spinnerAnim = activity.findViewById<Spinner>(R.id.spinnerBubbleAnim)
        val animIds = listOf(BubbleStyleStore.ANIM_NONE, BubbleStyleStore.ANIM_FADE,
            BubbleStyleStore.ANIM_POP, BubbleStyleStore.ANIM_SLIDE)
        val animLabels = listOf(
            activity.getString(R.string.bubble_anim_none),
            activity.getString(R.string.bubble_anim_fade),
            activity.getString(R.string.bubble_anim_pop),
            activity.getString(R.string.bubble_anim_slide)
        )
        spinnerAnim.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, animLabels)
        spinnerAnim.setSelection(animIds.indexOf(BubbleStyleStore.load(activity).anim).coerceAtLeast(0), false)
        spinnerAnim.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val anim = animIds.getOrNull(pos) ?: return
                val now = BubbleStyleStore.load(activity)
                BubbleStyleStore.save(activity, now.copy(anim = anim))
                refreshBubblePreview()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val cbTrim = activity.findViewById<android.widget.CheckBox>(R.id.switchBubbleTrimQuotes)
        cbTrim.isChecked = BubbleStyleStore.load(activity).trimQuotes
        cbTrim.setOnCheckedChangeListener { _, checked ->
            val now = BubbleStyleStore.load(activity)
            BubbleStyleStore.save(activity, now.copy(trimQuotes = checked))
            refreshBubblePreview()
        }

        // 跟随壁纸取色
        activity.findViewById<Button>(R.id.btnBubbleWallpaper).setOnClickListener {
            val s = BubbleStyleStore.applyFromWallpaper(activity)
            if (s == null) {
                Toast.makeText(activity, R.string.toast_bubble_no_wallpaper, Toast.LENGTH_LONG).show()
            } else {
                refreshBubblePreview()
                Toast.makeText(
                    activity,
                    activity.getString(R.string.toast_bubble_wallpaper, BubbleStyleStore.describe(s)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        // 导出 / 导入
        activity.findViewById<Button>(R.id.btnBubbleExport).setOnClickListener {
            onBubbleExport?.invoke()
        }
        activity.findViewById<Button>(R.id.btnBubbleImport).setOnClickListener {
            onBubbleImport?.invoke()
        }
    }

    /** 气泡导出回调（由 Activity 负责写文件与分享）。 */
    var onBubbleExport: (() -> Unit)? = null
    /** 气泡导入回调（由 Activity 负责选文件与读取）。 */
    var onBubbleImport: (() -> Unit)? = null

    /** 刷新设置页里的气泡预览（两个假气泡按当前样式现画）。 */
    fun refreshBubblePreview() {
        val style = BubbleStyleStore.load(activity)
        val ai = activity.findViewById<android.widget.TextView>(R.id.tvBubblePreviewAi)
        val me = activity.findViewById<android.widget.TextView>(R.id.tvBubblePreviewMe)
        if (style.isDefault) {
            ai.background = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bubble_ai_bg)
            me.background = androidx.core.content.ContextCompat.getDrawable(activity, R.drawable.bubble_me_bg)
            // 默认 AI 气泡是浅底、我的气泡是紫底，各自配深色字
            ai.setTextColor(0xFF1F1F1F.toInt())
            me.setTextColor(0xFF1F1F1F.toInt())
            ai.textSize = BubbleStyleStore.SIZE_NORMAL
            me.textSize = BubbleStyleStore.SIZE_NORMAL
            ai.setPadding(dp(14), dp(10), dp(14), dp(10))
            me.setPadding(dp(14), dp(10), dp(14), dp(10))
        } else {
            ai.background = BubbleStyleStore.drawableFor(activity, false, style)
            me.background = BubbleStyleStore.drawableFor(activity, true, style)
            ai.setTextColor(BubbleStyleStore.textColorFor(false, style))
            me.setTextColor(BubbleStyleStore.textColorFor(true, style))
            ai.textSize = style.fontSp
            me.textSize = style.fontSp
            val el = if (style.shadow) activity.resources.displayMetrics.density * 4f else 0f
            ai.elevation = el
            me.elevation = el
            ai.setPadding(BubbleStyleStore.padHpx(activity, style), BubbleStyleStore.padVpx(activity, style),
                BubbleStyleStore.padHpx(activity, style), BubbleStyleStore.padVpx(activity, style))
            me.setPadding(BubbleStyleStore.padHpx(activity, style), BubbleStyleStore.padVpx(activity, style),
                BubbleStyleStore.padHpx(activity, style), BubbleStyleStore.padVpx(activity, style))
        }
    }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun refreshAppearancePreview() {
        val avatar = activity.findViewById<android.widget.ImageView>(R.id.ivAvatarPreview)
        ChatStyleStore.applyAvatar(avatar)
        if (!ChatStyleStore.hasAvatar(activity)) {
            val spinner = activity.findViewById<Spinner>(R.id.spinnerPortrait)
            val item = PortraitLibrary.all().getOrNull(spinner.selectedItemPosition)
            val res = item?.resId ?: PortraitLibrary.resFor(prefs.portraitId)
            if (res != null) {
                avatar.setPadding(0, 0, 0, 0)
                avatar.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                avatar.setImageResource(res)
            }
        }
        ChatStyleStore.applyBackgroundPreview(activity.findViewById(R.id.ivBackgroundPreview))
        runCatching { refreshBubblePreview() }
    }

    private fun isSafeHttpsBaseUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)

    private fun refreshVoices(spinnerVoice: Spinner) {
        val voices = tts.availableVoices()
        val adapter = if (voices.isNotEmpty()) {
            ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, voices)
        } else {
            ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, listOf("（系统未返回音色，使用默认）"))
        }
        spinnerVoice.adapter = adapter
        if (voices.isNotEmpty()) {
            spinnerVoice.setSelection(prefs.voiceIndex.coerceIn(0, voices.size - 1))
        }
    }
}
