package com.suze.aivoice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 语音唤醒词助手：持续监听麦克风，识别到唤醒词（默认「你好小沫」）后触发回调。
 * 采用「识别结果包含唤醒词」的匹配方式，纯离线判断，无需额外服务。
 */
class WakeWordHelper(
    private val context: Context,
    private val wakeWord: String,
    private val onWake: () -> Unit
) {

    private var recognizer: SpeechRecognizer? = null
    private var running = false

    fun isSupported(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (running || !isSupported()) return
        running = true
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { restartIfRunning() }
            override fun onResults(results: Bundle?) {
                if (!running) return
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.joinToString(" ") ?: ""
                if (text.contains(wakeWord) || text.replace(" ", "").contains(wakeWord)) {
                    running = false
                    stopInternal()
                    onWake()
                } else {
                    restartIfRunning()
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!running) return
                val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = list?.joinToString(" ") ?: ""
                if (text.contains(wakeWord) || text.replace(" ", "").contains(wakeWord)) {
                    running = false
                    stopInternal()
                    onWake()
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        listenOnce()
    }

    private fun listenOnce() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) { }
    }

    private fun restartIfRunning() {
        if (!running) return
        try { recognizer?.cancel() } catch (_: Exception) { }
        listenOnce()
    }

    private fun stopInternal() {
        try { recognizer?.cancel() } catch (_: Exception) { }
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
    }

    fun stop() {
        running = false
        stopInternal()
    }
}
