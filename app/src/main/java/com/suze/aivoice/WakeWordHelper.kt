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
 * 听写结果用小型本地 KWS 近似匹配；系统识别不可用时走能量门兜底。
 */
class WakeWordHelper(
    private val context: Context,
    private val wakeWord: String,
    private val onWake: () -> Unit
) {

    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var energyWake: EnergyWakeHelper? = null
    private val localKws = Prefs(context).localKwsEnabled

    fun isSupported(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context) || localKws

    fun start() {
        if (running || !isSupported()) return
        running = true
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            val helper = EnergyWakeHelper {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!running) return@post
                    running = false
                    stopInternal()
                    onWake()
                }
            }
            energyWake = helper
            helper.start()
            return
        }
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
                if (hitWake(text)) {
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
                if (hitWake(text)) {
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

    private fun hitWake(text: String): Boolean {
        if (text.contains(wakeWord) || text.replace(" ", "").contains(wakeWord)) return true
        return localKws && LocalKws.matches(text, wakeWord)
    }

    private fun stopInternal() {
        try { energyWake?.stop() } catch (_: Exception) { }
        energyWake = null
        try { recognizer?.cancel() } catch (_: Exception) { }
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
    }

    fun stop() {
        running = false
        stopInternal()
    }
}
