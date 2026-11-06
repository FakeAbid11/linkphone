package dev.linkphone.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Optional background clipboard mode. The service itself does not inspect windows or event
 * content; it keeps the process bound so ClipboardSync can read text while another app is in
 * front. Users enable it explicitly in system accessibility settings.
 */
class ClipboardAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        LinkCore.init(this)
        if (runCatching { LinkCore.requireStore().hasPairing() }.getOrDefault(false)) {
            runCatching { LinkCore.startService(this) }
                .onFailure { Log.w("LinkPhone", "could not start link service", it) }
        }
        ClipboardSync.beginBackground()
        Log.d("LinkPhone", "clipboard accessibility connected")
    }

    private var lastCopyClickAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Fallback for ROMs that do not deliver clipboard-listener callbacks in the background:
        // a "Copy" menu click is a strong signal that a text copy just happened.
        if (event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val label = event.text.joinToString(" ").lowercase()
        if (!label.contains("copy")) return
        val now = System.currentTimeMillis()
        if (now - lastCopyClickAt < 750) return
        lastCopyClickAt = now
        ClipboardSync.requestRead(manual = false, delayMs = 400)
    }

    override fun onInterrupt() {
        ClipboardSync.endBackground()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ClipboardSync.endBackground()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ClipboardSync.endBackground()
        super.onDestroy()
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ClipboardAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == expected
            }
        }

        fun openSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Log.w("LinkPhone", "cannot open accessibility settings", it) }
        }
    }
}
