package dev.linkphone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        runCatching { LinkCore.init(app) }.onFailure { return }
        val paired = runCatching { LinkCore.requireStore().hasPairing() }.getOrDefault(false)
        if (!paired) return
        runCatching { LinkCore.startService(app) }.onFailure {
            Log.w("LinkPhone", "could not start background service at boot", it)
        }
    }
}
