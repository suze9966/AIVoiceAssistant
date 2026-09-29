package com.suze.aivoice
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var tts: TtsHelper
    private lateinit var memory: MemoryEngine
    private var pickingAvatar = true
    private var binder: SettingsBinder? = null
    private val pickBackup = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = BackupStore(this).importZip(uri)
        Toast.makeText(this, if (ok) R.string.toast_backup_imported else R.string.toast_backup_fail, Toast.LENGTH_LONG).show()
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
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
        if (ok) binder?.refreshAppearancePreview()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        try {
            setContentView(R.layout.activity_settings)
            prefs = Prefs(this)
            tts = TtsHelper(this, prefs)
            memory = MemoryEngine(this)
            binder = SettingsBinder(
                activity = this,
                prefs = prefs,
                tts = tts,
                memory = memory,
                pickImage = { avatar ->
                    pickingAvatar = avatar
                    pickImage.launch("image/*")
                },
                pickBackup = { pickBackup.launch("application/zip") },
                onSaved = { finish() }
            )
            binder?.bind()
        } catch (t: Throwable) {
            Toast.makeText(this, "设置页初始化失败：" + t.message, Toast.LENGTH_LONG).show()
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        if (::tts.isInitialized) tts.shutdown()
    }
}
