package dev.linkphone.app

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Publishes the active media session and forwards transport controls from the PC. */
object MediaWatcher {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var appContext: Context? = null
    private var audio: AudioManager? = null
    private var lastArtKey = ""
    private var lastArtId = ""
    private val controllers = LinkedHashMap<MediaSession.Token, MediaController>()
    private val callbacks = HashMap<MediaSession.Token, MediaController.Callback>()

    private val ticker = object : Runnable {
        override fun run() = publish()
    }

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
        audio = app.getSystemService(AudioManager::class.java)
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
        handler.removeCallbacks(ticker)
        detach()
        manager = null
        component = null
        lastArtKey = ""
        lastArtId = ""
        publish()
    }

    @Synchronized
    fun action(msg: JSONObject) {
        val controller = playingController() ?: return
        runCatching {
            when (msg.optString("action")) {
                "play" -> controller.transportControls.play()
                "pause" -> controller.transportControls.pause()
                "next" -> controller.transportControls.skipToNext()
                "previous" -> controller.transportControls.skipToPrevious()
                "seek" -> {
                    val position = msg.optLong("positionMs", -1)
                    if (position >= 0) {
                        val duration = controller.metadata
                            ?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                        controller.transportControls.seekTo(
                            if (duration > 0) position.coerceIn(0, duration) else position
                        )
                    }
                }
                "volume" -> {
                    val manager = audio ?: return
                    val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    manager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        msg.optInt("level", manager.getStreamVolume(AudioManager.STREAM_MUSIC))
                            .coerceIn(0, max),
                        0
                    )
                }
            }
        }.onFailure { Log.w("LinkPhone", "media action failed", it) }
        publish()
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

    private fun playingController(): MediaController? =
        controllers.values.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.values.firstOrNull()

    @Synchronized
    private fun publish() {
        val controller = playingController()
        val client = LinkCore.clientOrNull() ?: return
        if (controller == null) {
            handler.removeCallbacks(ticker)
            if (lastArtId.isNotEmpty()) {
                lastArtId = ""
                client.sendMediaArt("", "", null)
            }
            client.sendMediaState("", "", false, "", 0, 0, 0, 0, "")
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
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            ?.takeIf { it > 0 } ?: 0L
        val position = currentPosition(controller, duration)
        val max = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
        val volume = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
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

        val trackKey = "$title|$artist|$duration"
        if (trackKey != lastArtKey) {
            lastArtKey = trackKey
            if (lastArtId.isNotEmpty()) {
                lastArtId = ""
                client.sendMediaArt("", "", null)
            }
            loadArt(metadata, trackKey)
        }

        client.sendMediaState(
            title,
            artist,
            playing,
            app,
            position,
            duration,
            volume,
            max,
            lastArtId
        )
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, if (playing) 1000L else 3000L)
    }

    private fun currentPosition(controller: MediaController, duration: Long): Long {
        val state = controller.playbackState ?: return 0
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0) {
            position += (
                (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) *
                    state.playbackSpeed
                ).toLong()
        }
        if (duration > 0) position = position.coerceIn(0, duration)
        return position.coerceAtLeast(0)
    }

    private fun loadArt(metadata: MediaMetadata?, key: String) {
        scope.launch {
            val bitmap = runCatching {
                metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            }.getOrNull() ?: return@launch
            val bytes = runCatching {
                val scale = minOf(1f, 256f / maxOf(bitmap.width, bitmap.height, 1))
                val scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    )
                } else {
                    bitmap
                }
                val output = ByteArrayOutputStream()
                try {
                    scaled.compress(Bitmap.CompressFormat.JPEG, 70, output)
                } finally {
                    // The source bitmap belongs to the session metadata; recycle only our copy.
                    if (scaled !== bitmap) scaled.recycle()
                }
                output.toByteArray()
            }.getOrNull()
            val valid = bytes != null && bytes.isNotEmpty() && bytes.size <= 64 * 1024
            val id = if (valid) "art-${bytes!!.contentHashCode()}" else ""
            val mime = if (valid) "image/jpeg" else ""
            handler.post {
                if (lastArtKey != key) return@post
                lastArtId = id
                LinkCore.clientOrNull()?.sendMediaArt(id, mime, if (valid) bytes else null)
            }
        }
    }
}
