package com.suze.aivoice

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class WallpaperPreviewActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var scene: XiaomoSceneView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallpaper)
        prefs = Prefs(this)
        scene = findViewById(R.id.pageContent)
        findViewById<android.view.View>(R.id.btnWallpaperBack).setOnClickListener { finish() }
        val mood = findViewById<SwitchCompat>(R.id.switchWallpaperMood)
        val weather = findViewById<SwitchCompat>(R.id.switchWallpaperWeather)
        val touch = findViewById<SwitchCompat>(R.id.switchWallpaperTouch)
        val cards = findViewById<SwitchCompat>(R.id.switchWallpaperCards)
        mood.isChecked = prefs.wallpaperMoodEnabled
        weather.isChecked = prefs.wallpaperWeatherEnabled
        touch.isChecked = prefs.wallpaperTouchEnabled
        cards.isChecked = prefs.wallpaperCardsEnabled
        mood.setOnCheckedChangeListener { _, checked ->
            prefs.wallpaperMoodEnabled = checked
            scene.invalidate()
            Toast.makeText(this, R.string.toast_wallpaper_saved, Toast.LENGTH_SHORT).show()
        }
        weather.setOnCheckedChangeListener { _, checked ->
            prefs.wallpaperWeatherEnabled = checked
            scene.invalidate()
            Toast.makeText(this, R.string.toast_wallpaper_saved, Toast.LENGTH_SHORT).show()
        }
        touch.setOnCheckedChangeListener { _, checked ->
            prefs.wallpaperTouchEnabled = checked
            scene.invalidate()
            Toast.makeText(this, R.string.toast_wallpaper_saved, Toast.LENGTH_SHORT).show()
        }
        cards.setOnCheckedChangeListener { _, checked ->
            prefs.wallpaperCardsEnabled = checked
            scene.invalidate()
            Toast.makeText(this, R.string.toast_wallpaper_saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnWallpaperChat).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
        }
        findViewById<Button>(R.id.btnWallpaperApply).setOnClickListener { applyWallpaper() }
        GlassKit.attachPage(this, scene)
    }

    private fun applyWallpaper() {
        val component = ComponentName(this, XiaomoWallpaperService::class.java)
        val live = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
        val picked = runCatching { startActivity(live); true }.getOrDefault(false)
        if (picked) {
            Toast.makeText(this, R.string.toast_wallpaper_pick, Toast.LENGTH_LONG).show()
            return
        }
        val chooser = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
        val opened = runCatching { startActivity(chooser); true }.getOrDefault(false)
        Toast.makeText(
            this,
            if (opened) R.string.toast_wallpaper_pick else R.string.toast_wallpaper_no_picker,
            Toast.LENGTH_LONG
        ).show()
    }
}
