package com.suze.aivoice

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import java.util.Locale

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var tts: TtsHelper
    private lateinit var memory: MemoryEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 兜底：任何意外异常都不让设置页闪退
        try {
            setContentView(R.layout.activity_settings)
            initView()
        } catch (t: Throwable) {
            Toast.makeText(this, "设置页初始化失败：" + t.message, Toast.LENGTH_LONG).show()
        }
    }

    private fun initView() {
        prefs = Prefs(this)
        tts = TtsHelper(this)
        memory = MemoryEngine(this)

        val editBaseUrl = findViewById<EditText>(R.id.editBaseUrl)
        val editApiKey = findViewById<EditText>(R.id.editApiKey)
        val editModel = findViewById<EditText>(R.id.editModel)
        val editSystem = findViewById<EditText>(R.id.editSystem)
        val spinnerLocale = findViewById<Spinner>(R.id.spinnerLocale)
        val switchTaiwan = findViewById<SwitchCompat>(R.id.switchTaiwan)
        val editTaiwanPrompt = findViewById<EditText>(R.id.editTaiwanPrompt)
        val spinnerVoice = findViewById<Spinner>(R.id.spinnerVoice)
        val editWake = findViewById<EditText>(R.id.editWakeWord)
        val switchStream = findViewById<SwitchCompat>(R.id.switchStream)
        val switchEmotion = findViewById<SwitchCompat>(R.id.switchEmotion)
        val switchMind = findViewById<SwitchCompat>(R.id.switchMind)
        val switchGrow = findViewById<SwitchCompat>(R.id.switchGrow)
        val switchMemory = findViewById<SwitchCompat>(R.id.switchMemory)
        val btnMemorySummary = findViewById<Button>(R.id.btnMemorySummary)
        val btnTest = findViewById<Button>(R.id.btnTestVoice)
        val seekRate = findViewById<SeekBar>(R.id.seekRate)
        val seekPitch = findViewById<SeekBar>(R.id.seekPitch)
        val btnStopSpeak = findViewById<Button>(R.id.btnStopSpeak)
        val btnSave = findViewById<Button>(R.id.btnSave)

        editBaseUrl.setText(prefs.baseUrl)
        // 安全：绝不回显完整 API Key。已保存过就显示脱敏占位，仅在用户重新输入时才覆盖
        val hasKey = prefs.apiKey.isNotBlank()
        editApiKey.setText("")
        editApiKey.hint = if (hasKey) "已保存 · " + maskKey(prefs.apiKey) + "（留空则不修改）" else getString(R.string.label_apikey_hint)
        editModel.setText(prefs.model)
        editSystem.setText(prefs.systemPrompt)
        editWake.setText(prefs.wakeWord)
        switchStream.isChecked = prefs.streamEnabled
        switchEmotion.isChecked = prefs.emotionEnabled
        switchMind.isChecked = prefs.mindEnabled
        switchGrow.isChecked = prefs.growEnabled
        switchMemory.isChecked = prefs.memoryEnabled
        switchTaiwan.isChecked = prefs.taiwanVoice
        editTaiwanPrompt.setText(prefs.taiwanPrompt)
        seekRate.progress = (((prefs.ttsRate - 0.5f) / 1.1f) * 110f).toInt().coerceIn(0, 110)
        seekPitch.progress = (((prefs.ttsPitch - 0.5f) / 1.1f) * 110f).toInt().coerceIn(0, 110)

        val localeLabels = try { tts.localeOptions.map { it.first } } catch (t: Throwable) { listOf("普通话（大陆）") }
        spinnerLocale.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, localeLabels)
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
                Toast.makeText(this, if (name != null) "已切换台湾音色：$name" else "未找到台湾音色，已回退中文音色", Toast.LENGTH_SHORT).show()
            } else {
                tts.setLocale(Locale.CHINA)
                refreshVoices(spinnerVoice)
            }
        }

        spinnerVoice.postDelayed({ refreshVoices(spinnerVoice) }, 800)

        btnTest.setOnClickListener {
            val demo = if (switchTaiwan.isChecked)
                "欸，你好喔主人，我这样讲话有沒有比较台湾腔啦～"
            else
                "你好主人，这是当前的语音音色试听效果。"
            tts.setRate(0.5f + seekRate.progress / 100f)
            tts.setPitch(0.5f + seekPitch.progress / 100f)
            tts.speak(demo, switchTaiwan.isChecked)
        }

        btnStopSpeak.setOnClickListener {
            tts.stop()
            Toast.makeText(this, R.string.toast_stopped, Toast.LENGTH_SHORT).show()
        }

        btnMemorySummary.setOnClickListener {
            val txt = memory.exportText()
            android.app.AlertDialog.Builder(this)
                .setTitle("本地记忆库 · " + memory.summary())
                .setMessage(txt)
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空记忆") { _, _ ->
                    memory.clearAll()
                    Toast.makeText(this, "已清空本地记忆", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        btnSave.setOnClickListener {
            prefs.baseUrl = editBaseUrl.text.toString().trim()
            // 安全：只有用户真正输入了新 Key 才覆盖；留空则保留原有加密 Key（不读取、不回显、不落盘明文）
            val newKey = editApiKey.text.toString().trim()
            if (newKey.isNotEmpty()) {
                prefs.apiKey = newKey
                editApiKey.setText("") // 保存后立即清空输入框，避免遗留在界面/内存
                Toast.makeText(this, R.string.toast_key_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_key_on, Toast.LENGTH_SHORT).show()
            }
            prefs.model = editModel.text.toString().trim()
            prefs.systemPrompt = editSystem.text.toString().trim()
            prefs.wakeWord = editWake.text.toString().trim().ifBlank { "你好小沫" }
            prefs.streamEnabled = switchStream.isChecked
            prefs.emotionEnabled = switchEmotion.isChecked
            prefs.mindEnabled = switchMind.isChecked
            prefs.growEnabled = switchGrow.isChecked
            prefs.memoryEnabled = switchMemory.isChecked
            prefs.taiwanVoice = switchTaiwan.isChecked
            val tp = editTaiwanPrompt.text.toString().trim()
            if (tp.isNotBlank()) prefs.taiwanPrompt = tp
            val locIdx = spinnerLocale.selectedItemPosition
            prefs.voiceLocaleName = localeLabels.getOrNull(locIdx) ?: "普通话（大陆）"
            prefs.voiceIndex = spinnerVoice.selectedItemPosition
            prefs.ttsRate = 0.5f + seekRate.progress / 100f
            prefs.ttsPitch = 0.5f + seekPitch.progress / 100f
            val vName = spinnerVoice.selectedItem?.toString()
            if (vName != null && !vName.startsWith("（")) {
                tts.setVoiceByName(vName)
            }
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /**
     * 把 Key 脱敏成 sk-****abcd 形式，仅用于界面提示，绝不暴露完整内容。
     */
    private fun maskKey(k: String): String {
        if (k.isBlank()) return "—"
        if (k.length <= 7) return "****"
        val head = k.take(3)
        val tail = k.takeLast(4)
        return head + "-****" + tail
    }

    private fun refreshVoices(spinnerVoice: Spinner) {
        val voices = tts.availableVoices()
        val adapter = if (voices.isNotEmpty()) {
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voices)
        } else {
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("（系统未返回音色，使用默认）"))
        }
        spinnerVoice.adapter = adapter
        if (voices.isNotEmpty()) {
            spinnerVoice.setSelection(prefs.voiceIndex.coerceIn(0, voices.size - 1))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) tts.shutdown()
    }
}
