package dev.linkphone.app

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Rings the phone at the current ring volume; respects silent/DND where Android does. */
object PhoneRinger {
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private val stopTask = Runnable { stop() }

    @Synchronized
    fun ring(context: Context, durationMs: Long = 15_000) {
        stop()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: return
        val tone = runCatching {
            RingtoneManager.getRingtone(context.applicationContext, uri)
        }.getOrNull() ?: return
        tone.audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        runCatching { tone.play() }.onFailure {
            Log.w("LinkPhone", "could not ring the phone", it)
            return
        }
        ringtone = tone
        handler.postDelayed(stopTask, durationMs.coerceIn(1_000L, 30_000L))
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(stopTask)
        runCatching { ringtone?.stop() }
        ringtone = null
    }
}
