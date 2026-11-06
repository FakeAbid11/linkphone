package dev.linkphone.app

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Shared clipboard text sync. Automatic reads happen only while the app is foreground or
 * the optional accessibility service is bound; remote writes work in the background too.
 * The platform clipboard listener is the trigger, so there is no polling and no toast storm.
 */
object ClipboardSync {

    private const val MAX_TEXT = 256 * 1024
    private const val REMOTE_SUPPRESS_MS = 2_000L
    private const val PENDING_TTL_MS = 60_000L
    private const val READ_DEBOUNCE_MS = 250L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var clipboard: ClipboardManager? = null
    private var listener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var foreground = false
    private var background = false

    private var lastText = ""
    private var suppressUntil = 0L
    private var lastReadAt = 0L
    private var pendingText: String? = null
    private var pendingAt = 0L

    private val readRunnable = Runnable { readAndSend(manual = false) }

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    fun beginForeground() {
        foreground = true
        syncListener()
    }

    fun endForeground() {
        foreground = false
        syncListener()
    }

    fun beginBackground() {
        background = true
        syncListener()
    }

    fun endBackground() {
        background = false
        syncListener()
    }

    @Synchronized
    private fun syncListener() {
        val manager = clipboard ?: return
        val shouldListen = foreground || background
        if (shouldListen && listener == null) {
            val callback = ClipboardManager.OnPrimaryClipChangedListener {
                handler.removeCallbacks(readRunnable)
                handler.postDelayed(readRunnable, READ_DEBOUNCE_MS)
            }
            listener = callback
            manager.addPrimaryClipChangedListener(callback)
            Log.d("LinkPhone", "clipboard listener on")
        } else if (!shouldListen) {
            listener?.let { manager.removePrimaryClipChangedListener(it) }
            listener = null
            handler.removeCallbacks(readRunnable)
            Log.d("LinkPhone", "clipboard listener off")
        }
    }

    /** Called from LinkClient when the PC sends a clip. Safe from any thread. */
    fun applyRemote(text: String) {
        if (text.isEmpty()) return
        handler.post { applyRemoteOnMain(text) }
    }

    private fun applyRemoteOnMain(text: String) {
        val app = appContext ?: return
        suppressUntil = System.currentTimeMillis() + REMOTE_SUPPRESS_MS
        lastText = text
        pendingText = null
        pendingAt = 0
        runCatching {
            val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("LinkPhone", text))
        }
    }

    /** Manual reads bypass the PC toggle; automatic reads require both settings. */
    fun requestRead(manual: Boolean = false, delayMs: Long = 0) {
        if (delayMs > 0) handler.postDelayed({ readAndSend(manual) }, delayMs)
        else handler.post { readAndSend(manual) }
    }

    fun flushPending() {
        handler.post { flushPendingOnMain() }
    }

    private fun readAndSend(manual: Boolean) {
        val app = appContext ?: return
        val client = LinkCore.clientOrNull() ?: return
        val store = runCatching { LinkCore.requireStore() }.getOrNull() ?: return
        if (!store.hasPairing()) return
        if (!manual && (!store.clipboardEnabled || !store.pcClipboardEnabled)) return
        val now = System.currentTimeMillis()
        if (!manual && now < suppressUntil) return
        // The clipboard listener and the accessibility fallback can both fire for one copy.
        if (!manual && now - lastReadAt < 400) return
        lastReadAt = now

        val manager = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: run {
            if (manual) client.sendClipboardError("unavailable")
            return
        }
        val description = runCatching { manager.primaryClipDescription }.getOrNull()
        if (description == null ||
            !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
        ) {
            Log.d("LinkPhone", "clipboard read skipped: not text")
            if (manual) client.sendClipboardError("unavailable")
            return
        }

        val text = runCatching {
            manager.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(app)
                ?.toString()
                .orEmpty()
        }.getOrNull().orEmpty()

        if (text.isBlank()) {
            if (manual) client.sendClipboardError("empty")
            return
        }
        if (text.length > MAX_TEXT) {
            if (manual) client.sendClipboardError("too-large")
            return
        }
        if (!manual && text == lastText) return

        if (!client.isReady) {
            if (manual) {
                client.sendClipboardError("offline")
            } else {
                pendingText = text
                pendingAt = now
            }
            return
        }

        if (client.sendClipboard(text, manual)) {
            lastText = text
            pendingText = null
            pendingAt = 0
            Log.d("LinkPhone", "clipboard sent (${text.length} chars)")
        } else {
            pendingText = text
            pendingAt = now
            if (manual) client.sendClipboardError("offline")
        }
    }

    private fun flushPendingOnMain() {
        val text = pendingText ?: return
        if (System.currentTimeMillis() - pendingAt > PENDING_TTL_MS) {
            pendingText = null
            pendingAt = 0
            return
        }
        val client = LinkCore.clientOrNull() ?: return
        if (!client.isReady) return
        val store = runCatching { LinkCore.requireStore() }.getOrNull() ?: return
        if (!store.clipboardEnabled || !store.pcClipboardEnabled) return
        if (client.sendClipboard(text, manual = false)) {
            lastText = text
            pendingText = null
            pendingAt = 0
        }
    }
}
