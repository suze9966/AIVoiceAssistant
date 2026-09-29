package com.suze.aivoice

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 插上有线耳机或连上蓝牙后，可选自动打开主界面并开启唤醒。 */
class HeadsetWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.headsetWakeEnabled) return
        val ok = when (intent.action) {
            Intent.ACTION_HEADSET_PLUG -> intent.getIntExtra("state", 0) == 1
            BluetoothDevice.ACTION_ACL_CONNECTED -> true
            else -> false
        }
        if (!ok) return
        val now = System.currentTimeMillis()
        if (now - lastAt < 8_000L) return
        lastAt = now
        val open = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_HEADSET_WAKE, true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        runCatching { context.startActivity(open) }
    }

    companion object {
        private var lastAt: Long = 0L
    }
}
