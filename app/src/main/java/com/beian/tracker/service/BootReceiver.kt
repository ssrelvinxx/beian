package com.beian.tracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.beian.tracker.util.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 开机后若之前处于记录状态，自动恢复。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = SettingsStore(context.applicationContext).trackingEnabled.first()
                if (enabled) TrackService.start(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}