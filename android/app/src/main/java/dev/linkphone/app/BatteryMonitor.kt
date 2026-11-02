package dev.linkphone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

data class PhoneBatteryState(val level: Int, val charging: Boolean)

/** Sends battery changes while the foreground link service is alive. */
class BatteryMonitor(
    context: Context,
    private val onChange: (PhoneBatteryState) -> Unit
) {
    private val appContext = context.applicationContext
    private var registered = false
    private var last: PhoneBatteryState? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            emit(parse(intent))
        }
    }

    fun start() {
        if (registered) return
        registered = true
        val sticky = ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        sticky?.let { emit(parse(it)) }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    private fun emit(state: PhoneBatteryState?) {
        if (state == null || state == last) return
        last = state
        onChange(state)
    }

    companion object {
        fun current(context: Context): PhoneBatteryState? {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            ) ?: return null
            return parse(intent)
        }

        private fun parse(intent: Intent): PhoneBatteryState? {
            val rawLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (rawLevel < 0 || scale <= 0) return null
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            return PhoneBatteryState(
                level = ((rawLevel * 100f) / scale).toInt().coerceIn(0, 100),
                charging = plugged != 0 &&
                    (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL)
            )
        }
    }
}
