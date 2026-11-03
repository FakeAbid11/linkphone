package dev.linkphone.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors posted notifications to the PC and executes inline replies coming back.
 * Requires the user to grant notification access in system settings.
 */
class NotificationRelay : NotificationListenerService() {

    private val lastSent = ConcurrentHashMap<String, String>()
    private val mirrored = ConcurrentHashMap.newKeySet<String>()

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val client = LinkCore.clientOrNull() ?: return
        if (!client.isReady) return

        val key = sbn.key
        if (sbn.packageName == packageName) {
            drop(key, client)
            return
        }

        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            drop(key, client)
            return
        }

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
            )?.toString().orEmpty()

        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val rawProgress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val hasProgress = indeterminate || progressMax > 0
        val ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0

        if (ongoing && !hasProgress) {
            drop(key, client)
            return
        }
        if (hasProgress && !client.supportsLiveProgress) {
            drop(key, client)
            return
        }
        if (title.isBlank() && text.isBlank() && !hasProgress) {
            drop(key, client)
            return
        }

        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)

        val actions = mutableListOf<OutgoingAction>()
        if (notification.contentIntent != null) {
            actions.add(OutgoingAction("content", "Open", false))
        }
        notification.actions?.forEachIndexed { index, action ->
            if (actions.size >= MAX_ACTIONS) return@forEachIndexed
            if (action.actionIntent == null) return@forEachIndexed
            val label = action.title?.toString()
                ?.replace(Regex("[\\r\\n\\t]+"), " ")
                ?.trim()
                ?.take(24)
                ?.takeIf { it.isNotEmpty() }
                ?: return@forEachIndexed
            val isReply = action.remoteInputs?.any { it.allowFreeFormInput } == true
            actions.add(OutgoingAction(index.toString(), label, isReply))
        }
        val canReply = actions.any { it.reply }

        val progress = if (!indeterminate && progressMax > 0) {
            rawProgress.coerceIn(0, progressMax)
        } else {
            null
        }
        val signature = buildString {
            append(title).append('|').append(text).append('|')
            append(progress ?: "").append('|')
            append(progressMax).append('|')
            append(indeterminate).append('|').append(ongoing).append('|')
            actions.forEach { append(it.id).append(':').append(it.label).append(':').append(it.reply).append(',') }
        }
        if (lastSent.put(key, signature) == signature) return

        mirrored.add(key)
        client.sendNotification(
            OutgoingNotification(
                key = key,
                app = appLabel,
                pkg = sbn.packageName,
                title = title,
                text = text,
                whenTs = sbn.postTime,
                canReply = canReply,
                actions = actions,
                progress = progress,
                progressMax = if (!indeterminate && progressMax > 0) progressMax else null,
                indeterminate = indeterminate,
                ongoing = ongoing
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        lastSent.remove(sbn.key)
        mirrored.remove(sbn.key)
        LinkCore.clientOrNull()?.sendNotificationDismissed(sbn.key)
    }

    private fun drop(key: String, client: LinkClient) {
        lastSent.remove(key)
        if (mirrored.remove(key)) client.sendNotificationDismissed(key)
    }

    private fun handleAction(key: String, actionId: String, replyText: String) {
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: return
        if (actionId == "content") {
            val content = sbn.notification.contentIntent ?: return
            try {
                content.send()
            } catch (e: PendingIntent.CanceledException) {
                Log.w("LinkPhone", "notification content intent canceled", e)
            } catch (e: SecurityException) {
                Log.w("LinkPhone", "notification content intent denied", e)
            }
            return
        }
        val index = actionId.toIntOrNull() ?: return
        val action = sbn.notification.actions?.getOrNull(index) ?: return

        val intent = Intent()
        val remoteInputs = action.remoteInputs
        if (!remoteInputs.isNullOrEmpty() && replyText.isNotEmpty()) {
            val results = Bundle().apply {
                remoteInputs.forEach { putCharSequence(it.resultKey, replyText) }
            }
            RemoteInputCompat.addResults(remoteInputs, intent, results)
        }

        try {
            action.actionIntent.send(this, 0, intent)
        } catch (e: PendingIntent.CanceledException) {
            Log.w("LinkPhone", "notification action canceled", e)
        }
    }

    companion object {
        private const val MAX_ACTIONS = 4

        @Volatile private var instance: NotificationRelay? = null

        fun deliver(key: String, actionId: String, replyText: String) {
            instance?.handleAction(key, actionId, replyText)
        }
    }
}

/** Tiny wrapper so the import of android.app.RemoteInput stays in one place. */
private object RemoteInputCompat {
    fun addResults(
        inputs: Array<android.app.RemoteInput>,
        intent: Intent,
        results: Bundle
    ) {
        android.app.RemoteInput.addResultsToIntent(inputs, intent, results)
    }
}
