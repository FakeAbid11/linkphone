package dev.linkphone.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Rings the phone at the current ring volume; respects silent/DND where Android does. */
object PhoneRinger {
    private const val CHANNEL_ID = "linkphone_ringing"
    private const val NOTIFICATION_ID = 43

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var notifierContext: Context? = null
    private val stopTask = Runnable { stop() }

    private val _ringing = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = _ringing.asStateFlow()

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
        val app = context.applicationContext
        ringtone = tone
        notifierContext = app
        _ringing.value = true
        postNotification(app)
        handler.postDelayed(stopTask, durationMs.coerceIn(1_000L, 30_000L))
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(stopTask)
        runCatching { ringtone?.stop() }
        ringtone = null
        _ringing.value = false
        notifierContext?.let(::cancelNotification)
        notifierContext = null
    }

    private fun postNotification(context: Context) {
        if (!canNotify(context)) return
        createChannel(context)
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getBroadcast(
            context,
            NOTIFICATION_ID,
            Intent(context, RingActionReceiver::class.java)
                .setAction(RingActionReceiver.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle("Phone is ringing")
            .setContentText("Sent from LinkPhone on your PC")
            .setContentIntent(contentIntent)
            .addAction(0, "Stop ringing", stopIntent)
            .setDeleteIntent(stopIntent)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w("LinkPhone", "could not post ring notification", it) }
    }

    private fun cancelNotification(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
    }

    private fun canNotify(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Find my phone",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Lets you stop the phone ringing from your PC"
                setSound(null, null)
                setShowBadge(false)
            }
        )
    }
}

/** Stops the find-my-phone ringtone from the notification action or a swipe-away. */
class RingActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) PhoneRinger.stop()
    }

    companion object {
        const val ACTION_STOP = "dev.linkphone.app.ring.STOP"
    }
}
