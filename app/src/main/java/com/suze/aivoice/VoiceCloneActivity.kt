package com.suze.aivoice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * 声音克隆：录一段 8~10 秒参考音频，或从相册/文件选 mp3/wav，
 * 上传到硅基流动克隆接口，拿到 speech: URI 后给小沫说话。
 */
class VoiceCloneActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var client: VoiceCloneClient
    private lateinit var tts: TtsHelper
    private val main = Handler(Looper.getMainLooper())
    private var capture: WavCapture? = null
    private var sourceFile: File? = null
    private var recording = false
    private var startedAt = 0L
    private var player: MediaPlayer? = null
    private var busy = false

    private val pickAudio = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val copied = copyAudio(uri)
        if (copied == null) {
            toast(getString(R.string.toast_clone_audio_failed))
            return@registerForActivityResult
        }
        sourceFile = copied
        refreshSourceLabel()
        toast(getString(R.string.toast_clone_audio_ready))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_voice_clone)
        GlassKit.attachPage(this, findViewById(R.id.pageScroll))
        prefs = Prefs(this)
        client = VoiceCloneClient(prefs)
        tts = TtsHelper(this, prefs)

        findViewById<android.view.View>(R.id.btnCloneBack).setOnClickListener { finish() }
        val switchUse = findViewById<SwitchCompat>(R.id.switchUseClone)
        val editName = findViewById<EditText>(R.id.editCloneName)
        val editText = findViewById<EditText>(R.id.editCloneTranscript)
        val btnRecord = findViewById<Button>(R.id.btnCloneRecord)
        val btnPick = findViewById<Button>(R.id.btnClonePick)
        val btnPlay = findViewById<Button>(R.id.btnClonePlay)
        val btnUpload = findViewById<Button>(R.id.btnCloneUpload)
        val btnTest = findViewById<Button>(R.id.btnCloneTest)
        val btnDelete = findViewById<Button>(R.id.btnCloneDelete)

        editName.setText(prefs.cloneVoiceName.ifBlank { "xiaomo" })
        if (editText.text.isNullOrBlank()) {
            editText.setText(getString(R.string.voice_clone_default_transcript))
        }
        switchUse.isChecked = prefs.cloneVoiceEnabled
        refreshStatus()
        refreshSourceLabel()

        switchUse.setOnCheckedChangeListener { _, checked ->
            if (checked && prefs.cloneVoiceUri.isBlank()) {
                switchUse.isChecked = false
                toast(getString(R.string.toast_clone_need_upload))
                return@setOnCheckedChangeListener
            }
            prefs.cloneVoiceEnabled = checked
            refreshStatus()
        }
        btnRecord.setOnClickListener { toggleRecord(btnRecord) }
        btnPick.setOnClickListener { pickAudio.launch("audio/*") }
        btnPlay.setOnClickListener { playSource() }
        btnUpload.setOnClickListener { startUpload(editName, editText, switchUse) }
        btnTest.setOnClickListener {
            if (prefs.siliconflowKey.isBlank()) {
                toast(VoiceCloneClient.NEED_KEY)
                return@setOnClickListener
            }
            val demo = if (prefs.taiwanVoice) {
                getString(R.string.voice_clone_demo_tw)
            } else {
                getString(R.string.voice_clone_demo)
            }
            tts.setRate(prefs.ttsRate)
            tts.setPitch(prefs.ttsPitch)
            tts.speak(demo, prefs.taiwanVoice)
        }
        btnDelete.setOnClickListener { startDelete(switchUse) }
    }

    private fun toggleRecord(btn: Button) {
        if (recording) {
            stopRecord(btn)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            toast(getString(R.string.toast_clone_need_mic))
            return
        }
        val file = File(filesDir, RECORD_FILE)
        try { if (file.exists()) file.delete() } catch (_: Exception) { }
        val cap = WavCapture(file)
        if (!cap.start()) {
            toast(getString(R.string.toast_clone_record_failed))
            return
        }
        capture = cap
        recording = true
        startedAt = System.currentTimeMillis()
        btn.text = getString(R.string.btn_clone_stop_record)
        toast(getString(R.string.toast_clone_recording))
        main.postDelayed({ if (recording) stopRecord(btn) }, MAX_RECORD_MS)
    }

    private fun stopRecord(btn: Button) {
        if (!recording) return
        recording = false
        val elapsed = System.currentTimeMillis() - startedAt
        val ok = try { capture?.stop() == true } catch (_: Exception) { false }
        capture = null
        btn.text = getString(R.string.btn_clone_record)
        if (!ok || elapsed < MIN_RECORD_MS) {
            toast(getString(R.string.toast_clone_record_short))
            return
        }
        val file = File(filesDir, RECORD_FILE)
        if (!file.isFile || file.length() <= 44L) {
            toast(getString(R.string.toast_clone_record_failed))
            return
        }
        sourceFile = file
        refreshSourceLabel()
        toast(getString(R.string.toast_clone_audio_ready))
    }

    private fun startUpload(editName: EditText, editText: EditText, switchUse: SwitchCompat) {
        if (busy) return
        if (prefs.siliconflowKey.isBlank()) {
            toast(VoiceCloneClient.NEED_KEY)
            return
        }
        val file = sourceFile
        if (file == null || !file.isFile || file.length() <= 44L) {
            toast(getString(R.string.toast_clone_need_audio))
            return
        }
        val transcript = editText.text.toString().trim()
        if (transcript.isEmpty()) {
            toast(getString(R.string.toast_clone_need_text))
            return
        }
        val name = editName.text.toString().trim().ifBlank { "xiaomo" }
        busy = true
        findViewById<Button>(R.id.btnCloneUpload).isEnabled = false
        toast(getString(R.string.toast_clone_uploading))
        lifecycleScope.launch {
            val result = client.upload(file, name, transcript)
            if (isFinishing || isDestroyed) return@launch
            busy = false
            findViewById<Button>(R.id.btnCloneUpload).isEnabled = true
            result.fold(
                onSuccess = { uri ->
                    prefs.cloneVoiceUri = uri
                    prefs.cloneVoiceName = name
                    prefs.cloneVoiceEnabled = true
                    switchUse.isChecked = true
                    refreshStatus()
                    toast(getString(R.string.toast_clone_ok))
                },
                onFailure = { err ->
                    toast(err.message ?: getString(R.string.toast_clone_failed))
                }
            )
        }
    }

    private fun startDelete(switchUse: SwitchCompat) {
        if (busy) return
        val uri = prefs.cloneVoiceUri
        if (uri.isBlank()) {
            toast(getString(R.string.toast_clone_none))
            return
        }
        busy = true
        lifecycleScope.launch {
            val remote = client.delete(uri)
            if (isFinishing || isDestroyed) return@launch
            busy = false
            prefs.cloneVoiceUri = ""
            prefs.cloneVoiceName = ""
            prefs.cloneVoiceEnabled = false
            switchUse.isChecked = false
            refreshStatus()
            val extra = remote.exceptionOrNull()?.message
            toast(
                if (extra.isNullOrBlank()) getString(R.string.toast_clone_deleted)
                else getString(R.string.toast_clone_deleted_local)
            )
        }
    }

    private fun playSource() {
        val file = sourceFile
        if (file == null || !file.isFile) {
            toast(getString(R.string.toast_clone_need_audio))
            return
        }
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); if (player === it) player = null }
                prepare()
                start()
            }
        } catch (_: Exception) {
            toast(getString(R.string.toast_clone_play_failed))
        }
    }

    private fun copyAudio(uri: Uri): File? {
        return try {
            val cr = contentResolver
            val mime = cr.getType(uri).orEmpty().lowercase()
            val name = uri.lastPathSegment.orEmpty().lowercase()
            val ext = when {
                mime.contains("mpeg") || mime.contains("mp3") || name.endsWith(".mp3") -> "mp3"
                mime.contains("wav") || name.endsWith(".wav") -> "wav"
                mime.contains("opus") || name.endsWith(".opus") -> "opus"
                mime.contains("ogg") || name.endsWith(".ogg") -> "ogg"
                mime.contains("pcm") || name.endsWith(".pcm") -> "pcm"
                else -> "wav"
            }
            val dest = File(filesDir, "voice_clone_source." + ext)
            cr.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_FILE_BYTES) return null
                        out.write(buf, 0, n)
                    }
                }
            } ?: return null
            if (!dest.isFile || dest.length() <= 0L) null else dest
        } catch (_: Exception) {
            null
        }
    }

    private fun refreshStatus() {
        val tv = findViewById<TextView>(R.id.tvClonePageStatus)
        tv.text = when {
            prefs.siliconflowKey.isBlank() -> getString(R.string.voice_clone_need_key)
            prefs.cloneVoiceEnabled -> getString(
                R.string.voice_clone_status_on,
                prefs.cloneVoiceName.ifBlank { getString(R.string.voice_clone_unnamed) }
            )
            prefs.cloneVoiceUri.isNotBlank() -> getString(
                R.string.voice_clone_status_saved,
                prefs.cloneVoiceName.ifBlank { getString(R.string.voice_clone_unnamed) }
            )
            else -> getString(R.string.voice_clone_status_off)
        }
    }

    private fun refreshSourceLabel() {
        val tv = findViewById<TextView>(R.id.tvCloneSource)
        val file = sourceFile
        tv.text = if (file != null && file.isFile) {
            val kb = (file.length() / 1024L).coerceAtLeast(1L)
            getString(R.string.voice_clone_source_ready, file.name, kb.toString())
        } else {
            getString(R.string.voice_clone_source_empty)
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        recording = false
        try { capture?.stop() } catch (_: Exception) { }
        capture = null
        try { player?.release() } catch (_: Exception) { }
        player = null
        if (::tts.isInitialized) tts.shutdown()
        super.onDestroy()
    }

    private inner class WavCapture(private val file: File) {
        @Volatile private var running = false
        private var record: AudioRecord? = null
        private var worker: Thread? = null

        fun start(): Boolean {
            val buf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            if (buf <= 0) return false
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, buf * 2)
            } catch (_: Exception) {
                return false
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            record = rec
            running = true
            try { rec.startRecording() } catch (_: Exception) {
                running = false
                rec.release()
                record = null
                return false
            }
            worker = Thread {
                try {
                    RandomAccessFile(file, "rw").use { raf ->
                        writeHeader(raf, 0)
                        val data = ByteArray(buf)
                        var total = 0
                        while (running) {
                            val n = rec.read(data, 0, data.size)
                            if (n > 0) {
                                raf.write(data, 0, n)
                                total += n
                            }
                        }
                        writeHeader(raf, total)
                    }
                } catch (_: Exception) {
                } finally {
                    try { rec.stop() } catch (_: Exception) { }
                    try { rec.release() } catch (_: Exception) { }
                    if (record === rec) record = null
                }
            }.also { it.start() }
            return true
        }

        fun stop(): Boolean {
            running = false
            try { worker?.join(2000) } catch (_: Exception) { }
            worker = null
            return file.isFile && file.length() > 44L
        }

        private fun writeHeader(raf: RandomAccessFile, dataBytes: Int) {
            val byteRate = SAMPLE_RATE * 2
            raf.seek(0)
            raf.writeBytes("RIFF")
            writeIntLE(raf, 36 + dataBytes)
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            writeIntLE(raf, 16)
            writeShortLE(raf, 1)
            writeShortLE(raf, 1)
            writeIntLE(raf, SAMPLE_RATE)
            writeIntLE(raf, byteRate)
            writeShortLE(raf, 2)
            writeShortLE(raf, 16)
            raf.writeBytes("data")
            writeIntLE(raf, dataBytes)
        }

        private fun writeIntLE(raf: RandomAccessFile, value: Int) {
            raf.write(value and 0xff)
            raf.write((value shr 8) and 0xff)
            raf.write((value shr 16) and 0xff)
            raf.write((value shr 24) and 0xff)
        }

        private fun writeShortLE(raf: RandomAccessFile, value: Int) {
            raf.write(value and 0xff)
            raf.write((value shr 8) and 0xff)
        }
    }

    companion object {
        private const val REQ_MIC = 2101
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val MAX_RECORD_MS = 300000L
        private const val MIN_RECORD_MS = 2000L
        private const val MAX_FILE_BYTES = 64 * 1024 * 1024
        private const val RECORD_FILE = "voice_clone_record.wav"
    }
}
