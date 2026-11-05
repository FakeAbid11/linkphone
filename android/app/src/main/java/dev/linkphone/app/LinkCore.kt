package dev.linkphone.app

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore

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
            CallHub.attach(app)
            UpdateScheduler.schedule(app)
            ContactsRepository.get(app).preload()
            sweepShareCache(app)
            sweepPendingMedia(app)
        }
    }

    private fun sweepPendingMedia(context: Context) {
        runCatching {
            val resolver = context.contentResolver
            val cutoff = (System.currentTimeMillis() / 1000L) - 48 * 60 * 60
            listOf(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).forEach { collection ->
                runCatching {
                    resolver.query(
                        collection,
                        arrayOf(MediaStore.MediaColumns._ID),
                        "${MediaStore.MediaColumns.IS_PENDING} = 1 AND " +
                            "${MediaStore.MediaColumns.DATE_ADDED} < ?",
                        arrayOf(cutoff.toString()),
                        null
                    )?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            resolver.delete(
                                ContentUris.withAppendedId(collection, id),
                                null,
                                null
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sweepShareCache(context: Context) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
            context.cacheDir.resolve("share").listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.deleteRecursively()
            }
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
