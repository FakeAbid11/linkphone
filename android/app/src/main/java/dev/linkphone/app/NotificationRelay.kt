package dev.linkphone.app

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors posted notifications to the PC and executes inline replies coming back.
 * Requires the user to grant notification access in system settings.
 */
class NotificationRelay : NotificationListenerService() {

    private val lastSent = ConcurrentHashMap<String, String>()
    private val lastSentAt = ConcurrentHashMap<String, Long>()
    private val mirrored = ConcurrentHashMap.newKeySet<String>()
    private val groupChildren = ConcurrentHashMap<String, MutableSet<String>>()
    private val groupSummaries = ConcurrentHashMap<String, String>()
    private val main = Handler(Looper.getMainLooper())
    private var callKey: String? = null

    override fun onListenerConnected() {
        LinkCore.init(this)
        instance = this
        isConnected = true
        Log.d(TAG, "notification listener connected")
        MediaWatcher.start(this)
        runCatching {
            activeNotifications
                ?.firstOrNull { isSystemCall(it, it.notification) }
                ?.let { handleSystemCall(it, it.notification) }
        }
        if (LinkCore.clientOrNull()?.isReady == true) backfill()
    }

    override fun onListenerDisconnected() {
        isConnected = false
        Log.d(TAG, "notification listener disconnected")
        if (instance === this) instance = null
        MediaWatcher.stop()
        if (callKey != null) {
            callKey = null
            CallHub.clearSystemCall()
        }
    }

    override fun onDestroy() {
        isConnected = false
        if (instance === this) instance = null
        MediaWatcher.stop()
        if (callKey != null) {
            callKey = null
            CallHub.clearSystemCall()
        }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        val notification = sbn.notification ?: return

        if (isSystemCall(sbn, notification)) {
            handleSystemCall(sbn, notification)
            return
        }

        val client = LinkCore.clientOrNull() ?: return
        if (!client.isReady) {
            logDrop(sbn, "not-ready")
            return
        }
        relay(sbn, client, false)
    }

    private fun relay(sbn: StatusBarNotification, client: LinkClient, force: Boolean) {
        val notification = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        val key = sbn.key
        if (notification.visibility == Notification.VISIBILITY_SECRET) {
            logDrop(sbn, "secret")
            return
        }

        val extras = notification.extras ?: run {
            logDrop(sbn, "no-extras")
            return
        }
        val category = notification.category
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val rawProgress = extras.getInt(Notification.EXTRA_PROGRESS, -1)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val hasProgress = indeterminate || progressMax > 0
        val ongoing = notification.flags and Notification.FLAG_ONGOING_EVENT != 0

        // Keep background-service chatter out, but mirror media, navigation, alarm and progress.
        if (ongoing && !hasProgress &&
            (category == Notification.CATEGORY_SERVICE || category == Notification.CATEGORY_SYSTEM)
        ) {
            logDrop(sbn, "service-ongoing")
            return
        }
        if (hasProgress && !client.supportsLiveProgress) {
            logDrop(sbn, "no-progress-cap")
            return
        }

        val title = (
            extras.getCharSequence(Notification.EXTRA_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            )?.toString().orEmpty()
        val text = notificationText(notification, extras)
        if (title.isBlank() && text.isBlank() && !hasProgress) {
            logDrop(sbn, "no-text")
            return
        }

        val groupKey = sbn.groupKey
        val isSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (isSummary && groupKey != null && !groupChildren[groupKey].isNullOrEmpty()) {
            logDrop(sbn, "summary-with-children")
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
        if (!force) {
            if (lastSent[key] == signature) return
            // Throttle progress churn only; rapid chat updates must all get through.
            if (hasProgress) {
                val now = System.currentTimeMillis()
                if (now - (lastSentAt[key] ?: 0L) < THROTTLE_MS) return
            }
        }

        val sent = client.sendNotification(
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
        if (!sent) {
            logDrop(sbn, "send-failed")
            return
        }

        lastSent[key] = signature
        lastSentAt[key] = System.currentTimeMillis()
        mirrored.add(key)
        if (groupKey != null) {
            if (isSummary) {
                groupSummaries[groupKey] = key
            } else {
                groupChildren.getOrPut(groupKey) { ConcurrentHashMap.newKeySet() }.add(key)
                groupSummaries.remove(groupKey)?.let { drop(it, client) }
            }
        }
        Log.d(TAG, "notif ${sbn.packageName} mirror")
    }

    /**
     * Re-publishes the phone's live notifications after the socket connects. Listener callbacks and
     * activeNotifications are read on the main thread.
     */
    private fun backfill() {
        main.post {
            val client = LinkCore.clientOrNull() ?: return@post
            if (!client.isReady) return@post
            lastSent.clear()
            lastSentAt.clear()
            groupChildren.clear()
            groupSummaries.clear()
            val active = runCatching { activeNotifications }.getOrNull().orEmpty()
                .filter { it.packageName != packageName }
                .sortedByDescending { it.postTime }
                .take(MAX_BACKFILL)
            active.forEach { sbn ->
                val notification = sbn.notification ?: return@forEach
                if (isSystemCall(sbn, notification)) return@forEach
                relay(sbn, client, true)
            }
        }
    }

    private fun notificationText(notification: Notification, extras: Bundle): String {
        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString(" ") { it.toString() }
            ?.takeIf { it.isNotBlank() }?.let { return it }
        runCatching {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        }.getOrNull()?.messages?.lastOrNull()?.text?.toString()
            ?.takeIf { it.isNotBlank() }?.let { return it }
        return extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
    }

    private fun forgetGroupKey(key: String) {
        groupSummaries.entries.removeAll { it.value == key }
        val iterator = groupChildren.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            entry.value.remove(key)
            if (entry.value.isEmpty()) iterator.remove()
        }
    }

    private fun logDrop(sbn: StatusBarNotification, reason: String) {
        Log.d(TAG, "notif ${sbn.packageName} drop:$reason")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val sbn = sbn ?: return
        if (sbn.key == callKey) {
            callKey = null
            CallHub.clearSystemCall()
        }
        lastSent.remove(sbn.key)
        lastSentAt.remove(sbn.key)
        forgetGroupKey(sbn.key)
        if (mirrored.remove(sbn.key)) {
            LinkCore.clientOrNull()?.sendNotificationDismissed(sbn.key)
        }
    }

    private fun isSystemCall(sbn: StatusBarNotification, notification: Notification): Boolean {
        if (sbn.packageName == packageName) return false
        val labels = notification.actions.orEmpty().mapNotNull { it.title?.toString()?.lowercase() }
        val callAction = labels.any { label ->
            label.contains("answer") || label.contains("decline") ||
                label.contains("reject") || label.contains("hang up") ||
                label.contains("end call")
        }
        val categoryCall = notification.category == Notification.CATEGORY_CALL
        val ongoing = sbn.isOngoing ||
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        // A call needs the call category or an ongoing call-like notification. A plain
        // calendar/email "Decline" action must stay a normal notification.
        if (!ongoing && !callAction) return false
        if (!categoryCall && !ongoing) return false

        if (!callAction) {
            val extras = notification.extras
            val text = buildString {
                append(extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
                append(' ')
                append(
                    extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                        ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
                )
            }.lowercase()
            if (text.contains("missed")) return false
        }
        return true
    }

    private fun handleSystemCall(sbn: StatusBarNotification, notification: Notification) {
        val actions = notification.actions.orEmpty().mapNotNull { it.title?.toString() }
        val ringing = actions.any { label ->
            val lower = label.lowercase()
            lower.contains("answer") || lower.contains("decline") || lower.contains("reject")
        }
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            )?.toString()?.trim().orEmpty()
        val number = extractNumber("$title $text") ?: title.ifBlank { text }
        val displayName = title.ifBlank { text }.ifBlank { number }.ifBlank { "Unknown" }

        Log.d(
            "LinkPhone",
            "call notification pkg=${sbn.packageName} category=${notification.category} " +
                "ongoing=${sbn.isOngoing} actions=$actions title=$title text=$text"
        )

        callKey = sbn.key
        CallHub.updateSystemCall(
            PhoneCallInfo(
                id = "system:${sbn.key}",
                number = number,
                displayName = displayName,
                phase = if (ringing) PhoneCallPhase.RINGING else PhoneCallPhase.ACTIVE,
                incoming = ringing,
                startedAt = System.currentTimeMillis()
            )
        )
    }

    private fun extractNumber(value: String): String? =
        Regex("""\+?\d[\d\s()\-]{4,}\d""").find(value)?.value?.trim()

    private fun drop(key: String, client: LinkClient) {
        lastSent.remove(key)
        lastSentAt.remove(key)
        forgetGroupKey(key)
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

    fun cancel(keys: List<String>) {
        keys.forEach { key ->
            runCatching { cancelNotification(key) }
                .onFailure { Log.w("LinkPhone", "cannot cancel notification", it) }
            lastSent.remove(key)
            lastSentAt.remove(key)
            forgetGroupKey(key)
            mirrored.remove(key)
        }
    }

    companion object {
        private const val TAG = "LinkPhone"
        private const val MAX_ACTIONS = 4
        private const val MAX_BACKFILL = 30
        private const val THROTTLE_MS = 2_000L
        private const val REBIND_INTERVAL_MS = 10_000L
        private const val MAX_REBIND_ATTEMPTS = 3

        @Volatile private var instance: NotificationRelay? = null

        @Volatile var isConnected = false

        @Volatile private var lastRebindAt = 0L
        @Volatile private var rebindAttempts = 0

        /**
         * A rebind request, not a re-grant. It recovers the common post-update/reboot state where
         * access is listed but the listener is unbound; a revoked grant still needs system settings.
         */
        fun ensureBound(context: Context, force: Boolean = false) {
            if (isConnected) return
            val app = context.applicationContext
            if (!NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)) {
                return
            }
            val now = System.currentTimeMillis()
            synchronized(this) {
                if (force) {
                    rebindAttempts = 0
                } else {
                    if (now - lastRebindAt < REBIND_INTERVAL_MS) return
                    if (rebindAttempts >= MAX_REBIND_ATTEMPTS) return
                }
                lastRebindAt = now
                rebindAttempts += 1
            }
            runCatching {
                NotificationListenerService.requestRebind(
                    ComponentName(app, NotificationRelay::class.java)
                )
            }.onFailure { Log.w(TAG, "notification rebind failed", it) }
        }

        fun onClientReady() {
            instance?.backfill()
        }

        fun deliver(key: String, actionId: String, replyText: String) {
            instance?.handleAction(key, actionId, replyText)
        }

        fun deliverCancel(keys: List<String>) {
            instance?.cancel(keys)
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
