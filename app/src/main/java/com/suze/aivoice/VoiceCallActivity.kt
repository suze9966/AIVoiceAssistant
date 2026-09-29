package com.suze.aivoice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 可打给小沫的语音电话页：听完一句 -> 短答 -> 播完再听，循环到挂断。
 * 复用系统 SpeechRecognizer、TtsHelper、LlmClient，不引入 WebRTC。
 */
class VoiceCallActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var llm: LlmClient
    private lateinit var tts: TtsHelper
    private lateinit var store: HistoryStore
    private lateinit var emotion: EmotionEngine
    private lateinit var tvDuration: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCaption: TextView
    private lateinit var callHalo: View
    private lateinit var ivAvatar: ImageView

    private var recognizer: SpeechRecognizer? = null
    private val history = mutableListOf<ChatMessage>()
    private var chatId: String = ""
    private var inCall = false
    private var isListening = false
    private var accepting = false
    private var isSending = false
    private var lastSentText = ""
    private var lastSentAt = 0L
    private var notBeforeListenAt = 0L
    private var startedAt = 0L
    private var hungUp = false
    private val main = Handler(Looper.getMainLooper())
    private val REQ_AUDIO = 2101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_call)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = Prefs(this)
        llm = LlmClient(prefs)
        tts = TtsHelper(this, prefs)
        store = HistoryStore(this)
        emotion = EmotionEngine(this)
        llm.bindEmotion(emotion)

        tvDuration = findViewById(R.id.tvCallDuration)
        tvStatus = findViewById(R.id.tvCallStatus)
        tvCaption = findViewById(R.id.tvCallCaption)
        callHalo = findViewById(R.id.callHalo)
        ivAvatar = findViewById(R.id.ivCallAvatar)
        ChatStyleStore.applyAvatar(ivAvatar)

        chatId = store.migrateAndActive(prefs)
        history.addAll(store.load(chatId))

        findViewById<View>(R.id.btnHangup).setOnClickListener { hangup(speakBye = false) }
        findViewById<View>(R.id.btnCallBack).setOnClickListener { hangup(speakBye = false) }

        applySavedVoice()
        if (hasMic()) startCall()
        else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)
            Toast.makeText(this, R.string.voice_call_need_mic, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUDIO) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCall()
        } else {
            Toast.makeText(this, R.string.voice_call_need_mic, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCall() {
        if (inCall || hungUp) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, R.string.voice_call_no_asr, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        inCall = true
        startedAt = System.currentTimeMillis()
        tickDuration()
        initRecognizer()
        setStatus(getString(R.string.voice_call_dialing), Halo.IDLE)
        val hello = getString(R.string.voice_call_greeting)
        tvCaption.text = hello
        speakThenListen(hello, persistAsAssistant = true)
    }

    private fun tickDuration() {
        if (hungUp) return
        val sec = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
        val mm = sec / 60L
        val ss = sec % 60L
        tvDuration.text = String.format(Locale.CHINA, "%02d:%02d", mm, ss)
        main.postDelayed({ tickDuration() }, 1000L)
    }

    private fun initRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {
                if (tts.isSpeaking || System.currentTimeMillis() < notBeforeListenAt) {
                    suspendListen()
                }
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) {
                if (!accepting || hungUp) return
                accepting = false
                isListening = false
                if (inCall && !isSending && !tts.isSpeaking) scheduleListen()
            }
            override fun onResults(results: Bundle?) {
                if (!accepting || hungUp) return
                accepting = false
                isListening = false
                if (tts.isSpeaking || isSending || System.currentTimeMillis() < notBeforeListenAt) {
                    scheduleListen()
                    return
                }
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim().orEmpty()
                if (text.isEmpty()) {
                    scheduleListen()
                    return
                }
                tvCaption.text = text
                if (isHangupPhrase(text)) {
                    hangup(speakBye = true)
                    return
                }
                answer(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!accepting || tts.isSpeaking || isSending) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()
                if (!text.isNullOrEmpty()) tvCaption.text = text
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun startListening() {
        if (hungUp || !inCall || recognizer == null) return
        if (isListening || isSending || tts.isSpeaking || isFinishing || isDestroyed) return
        if (System.currentTimeMillis() < notBeforeListenAt) {
            scheduleListen()
            return
        }
        accepting = true
        isListening = true
        setStatus(getString(R.string.voice_call_listening), Halo.LISTEN)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (prefs.taiwanVoice) Locale.TAIWAN else Locale.CHINA)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer?.startListening(intent) }
    }

    private fun scheduleListen() {
        if (!inCall || hungUp) return
        val wait = (notBeforeListenAt - System.currentTimeMillis()).coerceAtLeast(320L)
        main.postDelayed({
            if (inCall && !hungUp && !isListening && !isSending && !tts.isSpeaking) startListening()
        }, wait)
    }

    private fun suspendListen() {
        accepting = false
        isListening = false
        runCatching { recognizer?.cancel() }
    }

    private fun answer(userText: String) {
        val now = System.currentTimeMillis()
        if (userText == lastSentText && now - lastSentAt < 2000L) {
            scheduleListen()
            return
        }
        lastSentText = userText
        lastSentAt = now
        isSending = true
        suspendListen()
        setStatus(getString(R.string.voice_call_thinking), Halo.IDLE)
        history.add(ChatMessage("user", userText, isMe = true))
        persist()

        if (prefs.emotionEnabled) {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            emotion.reactToUser(userText, isNight = hour >= 22 || hour < 6)
        }

        llm.applyCloudThink = false
        llm.allowLocalFallback = true
        llm.systemPromptOverride = prefs.chattingPersona()
        val extra = StringBuilder(CALL_PROMPT)
        if (prefs.emotionEnabled) extra.append('\n').append(emotion.emotionPrompt())
        llm.extraSystemPrompt = extra.toString()

        lifecycleScope.launch {
            var reply = ""
            try {
                reply = llm.chat(history.takeLast(16)).ifBlank { getString(R.string.voice_call_fallback) }
                reply = llm.stripReasoning(reply)
                reply = shortenCallReply(reply)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                reply = getString(R.string.voice_call_fallback)
            }
            if (hungUp || isFinishing || isDestroyed) return@launch
            history.add(ChatMessage("assistant", reply, isMe = false))
            persist()
            isSending = false
            tvCaption.text = reply
            speakThenListen(reply, persistAsAssistant = false)
        }
    }

    private fun speakThenListen(text: String, persistAsAssistant: Boolean) {
        if (hungUp) return
        if (persistAsAssistant) {
            history.add(ChatMessage("assistant", text, isMe = false))
            persist()
        }
        applySavedVoice()
        suspendListen()
        setStatus(getString(R.string.voice_call_speaking), Halo.SPEAK)
        tts.onSpeakDone = {
            runOnUiThread {
                notBeforeListenAt = System.currentTimeMillis() + 700L
                if (!hungUp && inCall && !isSending) {
                    setStatus(getString(R.string.voice_call_listening), Halo.LISTEN)
                    scheduleListen()
                }
            }
        }
        tts.speak(text, prefs.taiwanVoice)
        main.postDelayed({
            if (!hungUp && inCall && !isSending && !isListening && !tts.isSpeaking && !tts.hasQueuedSpeech()) {
                scheduleListen()
            }
        }, 22000L)
    }

    private fun hangup(speakBye: Boolean) {
        if (hungUp) return
        hungUp = true
        inCall = false
        isSending = false
        accepting = false
        isListening = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        tts.onSpeakDone = null
        tts.stop()
        persist()
        Toast.makeText(this, R.string.toast_voice_call_ended, Toast.LENGTH_SHORT).show()
        if (speakBye) {
            val bye = getString(R.string.voice_call_bye)
            tvCaption.text = bye
            setStatus(getString(R.string.voice_call_speaking), Halo.SPEAK)
            history.add(ChatMessage("assistant", bye, isMe = false))
            persist()
            tts.onSpeakDone = { runOnUiThread { finish() } }
            tts.speak(bye, prefs.taiwanVoice)
            main.postDelayed({ if (!isFinishing) finish() }, 6000L)
        } else {
            finish()
        }
    }

    private fun persist() {
        if (chatId.isBlank()) return
        store.save(chatId, history)
    }

    private fun applySavedVoice() {
        if (prefs.taiwanVoice) tts.applyTaiwanVoice()
        tts.setRate(prefs.ttsRate)
        tts.setPitch(prefs.ttsPitch)
        if (prefs.emotionEnabled) tts.setEmotion(emotion.ttsRate(), emotion.ttsPitch())
    }

    private fun setStatus(text: String, halo: Halo) {
        tvStatus.text = text
        callHalo.setBackgroundResource(
            when (halo) {
                Halo.LISTEN -> R.drawable.halo_ring_active
                Halo.SPEAK -> R.drawable.halo_ring_speaking
                Halo.IDLE -> R.drawable.halo_ring
            }
        )
    }

    private fun isHangupPhrase(text: String): Boolean {
        val t = text.replace(" ", "")
        return t.contains("挂了") || t.contains("挂断") || t.contains("结束通话") ||
            t.contains("不聊了") || t == "拜拜" || t == "再见" || t.contains("先挂")
    }

    private fun shortenCallReply(raw: String): String {
        var t = raw.replace("[sticker:", " ").replace('\n', ' ').trim()
        if (t.length <= 80) return t
        val cut = t.indexOfFirst { it == '。' || it == '！' || it == '？' || it == '!' || it == '?' }
        if (cut in 8..80) return t.substring(0, cut + 1)
        return t.take(80)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        hangup(speakBye = false)
    }

    override fun onDestroy() {
        hungUp = true
        inCall = false
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        recognizer = null
        if (::tts.isInitialized) {
            tts.onSpeakDone = null
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }

    private enum class Halo { IDLE, LISTEN, SPEAK }

    companion object {
        private const val CALL_PROMPT =
            "你正在和主人进行语音电话。回复必须短，像打电话：一两句口语，接上刚才的话往下聊。" +
                "可以附和、吐槽、关心，偶尔才追问一句。不要把每句话都当成新问题，不要每句都以提问结尾。" +
                "不要列表、不要长文、不要表情包标记，也不要提及模型或提示词。"
    }
}
