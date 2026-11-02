package dev.linkphone.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Mirrors posted notifications to the PC and executes inline replies coming back.
 * Requires the user to grant notification access in system settings.
 */
class NotificationRelay : NotificationListenerService() {

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
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
            )?.toString().orEmpty()

        if (title.isBlank() && text.isBlank()) return

        val appLabel = runCatching {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        }.getOrDefault(sbn.packageName)

        val actions = mutableListOf<Pair<String, Boolean>>()
        var canReply = false
        notification.actions?.forEach { action ->
            val label = action.title?.toString() ?: return@forEach
            val isReply = action.remoteInputs?.any { it.allowFreeFormInput } == true
            if (isReply) canReply = true
            actions.add(label to isReply)
        }

        client.sendNotification(
            OutgoingNotification(
                key = sbn.key,
                app = appLabel,
                pkg = sbn.packageName,
                title = title,
                text = text,
                whenTs = sbn.postTime,
                canReply = canReply,
                actions = actions.take(4)
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        LinkCore.clientOrNull()?.sendNotificationDismissed(sbn.key)
    }

    private fun handleAction(key: String, actionId: String, replyText: String) {
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: return
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
