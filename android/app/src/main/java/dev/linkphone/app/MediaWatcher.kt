package dev.linkphone.app

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Publishes the active media session and forwards transport controls from the PC. */
object MediaWatcher {

    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var appContext: Context? = null
    private val controllers = LinkedHashMap<MediaSession.Token, MediaController>()
    private val callbacks = HashMap<MediaSession.Token, MediaController.Callback>()

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> update(list) }

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext
        val listener = ComponentName(app, NotificationRelay::class.java)
        val service = app.getSystemService(MediaSessionManager::class.java) ?: return
        if (manager != null && component == listener) {
            updateSessions()
            return
        }
        stop()
        manager = service
        component = listener
        appContext = app
        val added = runCatching {
            service.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
        }
        if (added.isFailure) {
            Log.w("LinkPhone", "could not watch media sessions", added.exceptionOrNull())
            manager = null
            component = null
            return
        }
        updateSessions()
    }

    @Synchronized
    fun stop() {
        val service = manager
        val listener = component
        if (service != null && listener != null) {
            runCatching {
                service.removeOnActiveSessionsChangedListener(sessionsListener)
            }
        }
        detach()
        manager = null
        component = null
        publish()
    }

    @Synchronized
    fun action(action: String) {
        val controller = controllers.values.firstOrNull() ?: return
        runCatching {
            when (action) {
                "play" -> controller.transportControls.play()
                "pause" -> controller.transportControls.pause()
                "next" -> controller.transportControls.skipToNext()
                "previous" -> controller.transportControls.skipToPrevious()
            }
        }.onFailure { Log.w("LinkPhone", "media action failed", it) }
    }

    private fun updateSessions() {
        val service = manager ?: return
        val listener = component ?: return
        val sessions = runCatching { service.getActiveSessions(listener) }
            .getOrElse {
                Log.w("LinkPhone", "could not read media sessions", it)
                return
            }
        update(sessions)
    }

    @Synchronized
    private fun update(sessions: List<MediaController>?) {
        detach()
        sessions.orEmpty().forEach { controller ->
            val token = controller.sessionToken
            controllers[token] = controller
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
                override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
                override fun onSessionDestroyed() {
                    controllers.remove(token)
                    callbacks.remove(token)
                    publish()
                }
            }
            callbacks[token] = callback
            runCatching {
                controller.registerCallback(callback, handler)
            }.onFailure {
                controllers.remove(token)
                callbacks.remove(token)
            }
        }
        publish()
    }

    private fun detach() {
        callbacks.forEach { (token, callback) ->
            controllers[token]?.let { controller ->
                runCatching { controller.unregisterCallback(callback) }
            }
        }
        callbacks.clear()
        controllers.clear()
    }

    @Synchronized
    private fun publish() {
        val controller = controllers.values.firstOrNull()
        val client = LinkCore.clientOrNull() ?: return
        if (controller == null) {
            client.sendMediaState("", "", false, "")
            return
        }
        val metadata = controller.metadata
        val title = (
            metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            )?.toString().orEmpty()
        val artist = (
            metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            )?.toString().orEmpty()
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val pkg = controller.packageName.orEmpty()
        val app = if (pkg.isEmpty()) {
            ""
        } else {
            runCatching {
                val info = appContext?.packageManager?.getApplicationInfo(pkg, 0)
                if (info != null) {
                    appContext?.packageManager?.getApplicationLabel(info)?.toString().orEmpty()
                } else {
                    pkg
                }
            }.getOrDefault(pkg)
        }
        client.sendMediaState(title, artist, playing, app)
    }
}
