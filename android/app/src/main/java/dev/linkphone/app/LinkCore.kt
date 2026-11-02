package dev.linkphone.app

import android.content.Context
import android.content.Intent
import android.os.Build

/** Process-wide wiring so the UI, the service and the notification listener share one session. */
object LinkCore {

    @Volatile private var clientRef: LinkClient? = null
    @Volatile private var storeRef: PairStore? = null
    @Volatile private var updaterRef: AppUpdateManager? = null

    fun init(context: Context) {
        if (clientRef != null) return
        synchronized(this) {
            if (clientRef != null) return
            val app = context.applicationContext
            val store = PairStore(app)
            storeRef = store
            clientRef = LinkClient(app, store)
            updaterRef = AppUpdateManager(app)
        }
    }

    fun clientOrNull(): LinkClient? = clientRef

    fun requireClient(): LinkClient = clientRef ?: error("LinkCore not initialised")

    fun requireStore(): PairStore = storeRef ?: error("LinkCore not initialised")

    fun requireUpdater(): AppUpdateManager = updaterRef ?: error("LinkCore not initialised")

    fun startService(context: Context) {
        val intent = Intent(context, LinkService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
