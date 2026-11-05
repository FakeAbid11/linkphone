package dev.linkphone.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class LinkService : Service() {

    private var batteryMonitor: BatteryMonitor? = null

    override fun onCreate() {
        super.onCreate()
        LinkCore.init(this)
        NotificationRelay.ensureBound(this)
        SystemCallWatcher.start(this)
        createChannel()

        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_text))
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(getColor(R.color.notification_accent))
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } catch (error: RuntimeException) {
            Log.w("LinkPhone", "could not start foreground service", error)
            stopSelf()
            return
        }

        val store = LinkCore.requireStore()
        val client = LinkCore.requireClient()
        batteryMonitor = BatteryMonitor(this) { battery ->
            client.sendBatteryStatus(battery.level, battery.charging)
        }.also { it.start() }
        if (store.hasPairing() && client.status.value.state == LinkState.IDLE) {
            client.connectSaved()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = LinkCore.requireStore()
        val client = LinkCore.requireClient()
        if (store.hasPairing() && client.status.value.state == LinkState.IDLE) {
            client.connectSaved()
        }
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w("LinkPhone", "foreground service timed out (type $fgsType)")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        SystemCallWatcher.stop()
        batteryMonitor?.stop()
        batteryMonitor = null
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_text)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "linkphone_connection"
        private const val NOTIFICATION_ID = 41
    }
}
