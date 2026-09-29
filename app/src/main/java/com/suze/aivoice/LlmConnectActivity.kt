package com.suze.aivoice

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class LlmConnectActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var llm: LlmClient
    private var applyingPreset = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_llm_connect)
        GlassKit.attachPage(this, findViewById(R.id.pageScroll))
        prefs = Prefs(this)
        llm = LlmClient(prefs)

        findViewById<View>(R.id.btnLlmBack).setOnClickListener { finish() }
        val spinner = findViewById<Spinner>(R.id.spinnerLlmPreset)
        val editBaseUrl = findViewById<EditText>(R.id.editLlmBaseUrl)
        val editApiKey = findViewById<EditText>(R.id.editLlmApiKey)
        val editModel = findViewById<EditText>(R.id.editLlmModel)
        val switchThink = findViewById<SwitchCompat>(R.id.switchCloudThink)
        val btnTest = findViewById<Button>(R.id.btnLlmTest)
        val btnSave = findViewById<Button>(R.id.btnLlmSave)

        editApiKey.filterTouchesWhenObscured = true
        editBaseUrl.setText(prefs.baseUrl)
        editModel.setText(prefs.model)
        switchThink.isChecked = prefs.cloudThinkEnabled
        editApiKey.setText("")
        editApiKey.hint = if (prefs.apiKey.isNotBlank()) {
            "已加密保存（留空则不修改）"
        } else {
            getString(R.string.label_apikey_hint)
        }

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            PRESETS.map { it.label }
        )
        val matched = PRESETS.indexOfFirst { it.baseUrl == prefs.baseUrl && it.model == prefs.model }
        spinner.setSelection(if (matched > 0) matched else 0)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingPreset) return
                val preset = PRESETS.getOrNull(position) ?: return
                if (preset.baseUrl.isBlank()) return
                applyingPreset = true
                editBaseUrl.setText(preset.baseUrl)
                editModel.setText(preset.model)
                if (preset.think) switchThink.isChecked = true
                applyingPreset = false
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        btnTest.setOnClickListener {
            if (!applyDraft(editBaseUrl, editApiKey, editModel, switchThink)) return@setOnClickListener
            btnTest.isEnabled = false
            Toast.makeText(this, R.string.llm_testing, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val result = try {
                    llm.probe()
                } catch (_: Exception) {
                    "连接失败：网络异常或接口不可达"
                }
                if (isFinishing || isDestroyed) return@launch
                btnTest.isEnabled = true
                Toast.makeText(this@LlmConnectActivity, result, Toast.LENGTH_LONG).show()
            }
        }
        btnSave.setOnClickListener {
            if (!applyDraft(editBaseUrl, editApiKey, editModel, switchThink)) return@setOnClickListener
            Toast.makeText(this, R.string.toast_llm_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun applyDraft(
        editBaseUrl: EditText,
        editApiKey: EditText,
        editModel: EditText,
        switchThink: SwitchCompat
    ): Boolean {
        val baseUrl = editBaseUrl.text.toString().trim()
        if (!isSafeHttpsBaseUrl(baseUrl)) {
            editBaseUrl.error = getString(R.string.error_https_base_url)
            editBaseUrl.requestFocus()
            Toast.makeText(this, R.string.error_https_base_url, Toast.LENGTH_LONG).show()
            return false
        }
        val model = editModel.text.toString().trim()
        if (model.isEmpty()) {
            editModel.error = getString(R.string.label_model)
            editModel.requestFocus()
            return false
        }
        prefs.baseUrl = baseUrl
        prefs.model = model
        prefs.cloudThinkEnabled = switchThink.isChecked
        val newKey = editApiKey.text.toString().trim()
        if (newKey.isNotEmpty()) {
            prefs.apiKey = newKey
            editApiKey.setText("")
        }
        return true
    }

    private fun isSafeHttpsBaseUrl(value: String): Boolean = runCatching {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)

    private data class Preset(
        val label: String,
        val baseUrl: String,
        val model: String,
        val think: Boolean
    )

    companion object {
        private val PRESETS = listOf(
            Preset("自定义", "", "", false),
            Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini", false),
            Preset("DeepSeek 对话", "https://api.deepseek.com", "deepseek-chat", false),
            Preset("DeepSeek 推理", "https://api.deepseek.com", "deepseek-reasoner", true),
            Preset("硅基流动 DeepSeek-R1", "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-R1", true),
            Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", false),
            Preset("Moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k", false),
            Preset("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-r1", true)
        )
    }
}
