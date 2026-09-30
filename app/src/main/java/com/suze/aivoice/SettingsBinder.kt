package com.suze.aivoice

import android.content.Intent
import androidx.core.content.FileProvider
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
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
        val spinnerCosyVoice = activity.findViewById<Spinner>(R.id.spinnerCosyVoice)
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
        val switchFreeChat = activity.findViewById<SwitchCompat>(R.id.switchFreeChat)
        val switchKeepListen = activity.findViewById<SwitchCompat>(R.id.switchKeepListen)
        val switchWebSearch = activity.findViewById<SwitchCompat>(R.id.switchWebSearch)
        val switchNotifySpeak = activity.findViewById<SwitchCompat>(R.id.switchNotifySpeak)
        val switchHeadsetWake = activity.findViewById<SwitchCompat>(R.id.switchHeadsetWake)
        val switchLocalKws = activity.findViewById<SwitchCompat>(R.id.switchLocalKws)
        val switchWelcome = activity.findViewById<SwitchCompat>(R.id.switchWelcome)
        val spinnerPortrait = activity.findViewById<Spinner>(R.id.spinnerPortrait)
        val btnEmotionTree = activity.findViewById<Button>(R.id.btnEmotionTree)
        val btnMemorySummary = activity.findViewById<Button>(R.id.btnMemorySummary)
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
        listOf(editApiKey, editCaiyunKey, editCaiyunSecret, editCaiyunToken, editSiliconflowKey).forEach {
            it.filterTouchesWhenObscured = true
        }
        editSiliconflowKey.setText("")
        editSiliconflowKey.hint = if (prefs.siliconflowKey.isNotBlank()) "已加密保存 · 留空不修改" else "硅基流动 API Key（cloud.siliconflow.cn）"
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
        switchFreeChat.isChecked = prefs.freeChatEnabled
        switchKeepListen.isChecked = prefs.keepListenInBackground
        switchWebSearch.isChecked = prefs.webSearchEnabled
        switchNotifySpeak.isChecked = prefs.notifySpeakEnabled
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
                val strongTwIndex = tts.availableVoices().indexOfFirst { it.contains("台湾腔·女·真人") }
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
        val cosyLabels = Prefs.COSY_VOICE_OPTIONS.map { it.second }
        spinnerCosyVoice.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, cosyLabels)
        val cosyIdx = Prefs.COSY_VOICE_OPTIONS.indexOfFirst { it.first == prefs.cosyVoice }
        if (cosyIdx >= 0) spinnerCosyVoice.setSelection(cosyIdx)
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
                spinnerCosyVoice,
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
        btnEmotionTree.setOnClickListener {
            activity.startActivity(Intent(activity, EmotionActivity::class.java))
        }
        btnMemorySummary.setOnClickListener {
            activity.startActivity(Intent(activity, MemoryActivity::class.java))
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
            prefs.freeChatEnabled = switchFreeChat.isChecked
            prefs.keepListenInBackground = switchKeepListen.isChecked
            prefs.webSearchEnabled = switchWebSearch.isChecked
            val turnOnNotify = switchNotifySpeak.isChecked && !prefs.notifySpeakEnabled
            prefs.notifySpeakEnabled = switchNotifySpeak.isChecked
            prefs.headsetWakeEnabled = switchHeadsetWake.isChecked
            prefs.localKwsEnabled = switchLocalKws.isChecked
            prefs.welcomeEnabled = switchWelcome.isChecked
            PortraitLibrary.all().getOrNull(spinnerPortrait.selectedItemPosition)?.id?.let { prefs.portraitId = it }
            if (turnOnNotify) {
                runCatching {
                    activity.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
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
            prefs.cosyVoice = Prefs.COSY_VOICE_OPTIONS.getOrNull(spinnerCosyVoice.selectedItemPosition)?.first ?: prefs.cosyVoice
            editSiliconflowKey.text.toString().trim().takeIf { it.isNotEmpty() }?.let { prefs.siliconflowKey = it }
            editSiliconflowKey.setText("")
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
        spinnerCosyVoice: Spinner,
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
        val prevCosy = prefs.cosyVoice
        prefs.ttsEngine = Prefs.TTS_ENGINE_OPTIONS.getOrNull(spinnerTtsEngine.selectedItemPosition)?.first ?: prevEngine
        prefs.cosyVoice = Prefs.COSY_VOICE_OPTIONS.getOrNull(spinnerCosyVoice.selectedItemPosition)?.first ?: prevCosy
        if (prefs.cloneVoiceEnabled) {
            Toast.makeText(activity, activity.getString(R.string.toast_clone_covers_cosy), Toast.LENGTH_LONG).show()
        }
        tts.speak(demo, taiwan)
        prefs.ttsEngine = prevEngine
        prefs.cosyVoice = prevCosy
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
        refreshAppearancePreview()
    }

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
