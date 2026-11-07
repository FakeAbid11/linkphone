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
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

/** A call notification reduced to what CallHub needs; `id` is replaced by the call identity there. */
data class SystemCallSeed(val key: String, val packageName: String, val info: PhoneCallInfo)

/** A call control we can dispatch through the owning app's own notification action. */
enum class CallActionKind { ANSWER, DECLINE, HANGUP }

/**
 * Mirrors posted notifications to the PC and executes inline replies coming back.
 * Requires the user to grant notification access in system settings.
 */
class NotificationRelay : NotificationListenerService() {

    private val iconCache = ConcurrentHashMap<String, String>()

    private fun getAppIconBase64(pkg: String): String? {
        iconCache[pkg]?.let { return it }
        val base64 = runCatching {
            val drawable = packageManager.getApplicationIcon(pkg)
            val size = 48
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            val bytes = stream.toByteArray()
            bitmap.recycle()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/png;base64,$b64"
        }.getOrNull()
        if (base64 != null) {
            if (iconCache.size > 64) iconCache.clear()
            iconCache[pkg] = base64
        }
        return base64
    }

    private val lastSent = ConcurrentHashMap<String, String>()
    private val lastSentAt = ConcurrentHashMap<String, Long>()
    private val mirrored = ConcurrentHashMap.newKeySet<String>()
    private val groupChildren = ConcurrentHashMap<String, MutableSet<String>>()
    private val groupSummaries = ConcurrentHashMap<String, String>()
    private val main = Handler(Looper.getMainLooper())
    /** Cached so isSystemCall never performs a Telecom binder call per notification. */
    @Volatile private var cachedDialerPackage: String? = null

    override fun onListenerConnected() {
        LinkCore.init(this)
        instance = this
        isConnected = true
        Log.d(TAG, "notification listener connected")
        MediaWatcher.start(this)
        refreshDialerPackage()
        reconcileCalls()
        if (LinkCore.clientOrNull()?.isReady == true) backfill()
    }

    override fun onListenerDisconnected() {
        isConnected = false
        Log.d(TAG, "notification listener disconnected")
        if (instance === this) instance = null
        MediaWatcher.stop()
        CallHub.clearNotificationCalls()
    }

    override fun onDestroy() {
        isConnected = false
        if (instance === this) instance = null
        MediaWatcher.stop()
        CallHub.clearNotificationCalls()
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

        // Content first, then plain buttons, then the reply action: the reply must survive the cap.
        // Ids are only stable ("reply"/"a<n>") for desktops that advertise notif.action.v2.
        val stableIds = client.supportsActionV2
        val actions = mutableListOf<OutgoingAction>()
        if (notification.contentIntent != null) {
            actions.add(OutgoingAction("content", "Open", false))
        }
        val replyAction = notification.actions?.withIndex()?.firstOrNull { (_, action) ->
            action.actionIntent != null && isFreeFormReply(action)
        }
        val buttons = notification.actions.orEmpty().mapIndexedNotNull { index, action ->
            if (action.actionIntent == null || isFreeFormReply(action)) return@mapIndexedNotNull null
            val label = actionLabel(action) ?: return@mapIndexedNotNull null
            OutgoingAction(if (stableIds) "a$index" else index.toString(), label, false)
        }
        val buttonSlots = (MAX_ACTIONS - actions.size - if (replyAction != null) 1 else 0)
            .coerceAtLeast(0)
        actions.addAll(buttons.take(buttonSlots))
        replyAction?.let { (index, action) ->
            actions.add(
                OutgoingAction(
                    if (stableIds) "reply" else index.toString(),
                    actionLabel(action) ?: "Reply",
                    true
                )
            )
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
                ongoing = ongoing,
                icon = getAppIconBase64(sbn.packageName)
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
    /**
     * Rebuilds the notification-derived call cards from the live shade. A null list means the
     * listener cannot answer, so the existing cards are kept; an empty list is authoritative.
     */
    private fun reconcileCallNotifications() {
        main.post {
            if (!isConnected) return@post
            val active = runCatching { activeNotifications }.getOrNull() ?: return@post
            val seeds = active.mapNotNull { sbn ->
                val notification = sbn.notification ?: return@mapNotNull null
                if (!isSystemCall(sbn, notification)) return@mapNotNull null
                buildSystemCall(sbn, notification)
            }
            Log.d(TAG, "call reconcile: ${seeds.size} live")
            CallHub.replaceNotificationCalls(seeds)
        }
    }

    private fun liveCallKeys(): Set<String>? =
        runCatching { activeNotifications }.getOrNull()
            ?.filter { isSystemCall(it, it.notification) }
            ?.map { it.key }
            ?.toSet()

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
        // The hub ignores keys it does not know, so ordinary notifications are unaffected.
        CallHub.clearNotificationCall(sbn.key)
        lastSent.remove(sbn.key)
        lastSentAt.remove(sbn.key)
        forgetGroupKey(sbn.key)
        if (mirrored.remove(sbn.key)) {
            LinkCore.clientOrNull()?.sendNotificationDismissed(sbn.key)
        }
    }

    private fun isSystemCall(sbn: StatusBarNotification, notification: Notification): Boolean {
        if (sbn.packageName == packageName) return false
        val categoryCall = notification.category == Notification.CATEGORY_CALL
        val callShaped = isCallShaped(notification, categoryCall)
        val dialer = isDialerPackage(sbn.packageName)
        // Ordinary ongoing notifications (VPN, system status, music, foreground services) are not
        // calls. Only a call-shaped action, the CALL category or a dialer may produce a card.
        if (!callShaped && !categoryCall && !dialer) return false
        val ongoing = sbn.isOngoing ||
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (!ongoing && !callShaped) return false

        if (!callShaped) {
            // Blank/dialer RemoteViews and localized dialers land here; a missed call must not.
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
        // No call UI offers an inline text reply; prompts with RemoteInput are not calls.
        if (!categoryCall && notification.actions.orEmpty().any { isFreeFormReply(it) }) return false
        return true
    }

    /**
     * A hang-up/answer action is the strongest signal; "decline"/"reject" alone is not, because
     * file-transfer and calendar prompts use it. Localized labels are covered when the notification
     * is already tagged as a call.
     */
    private fun isCallShaped(notification: Notification, categoryCall: Boolean): Boolean {
        val hangUpWords = listOf(
            "hang up", "hangup", "end call", "auflegen", "raccrocher", "colgar",
            "encerrar", "riaggancia", "挂断"
        )
        return notification.actions.orEmpty().any { action ->
            val label = action.title?.toString().orEmpty().lowercase()
            label.contains("answer") ||
                hangUpWords.any { label.contains(it) } ||
                (categoryCall && actionKind(action) != null)
        }
    }

    private fun isDialerPackage(pkg: String): Boolean {
        val cached = cachedDialerPackage
        if (!cached.isNullOrBlank() && cached == pkg) return true
        val lower = pkg.lowercase()
        return lower.contains("incallui") || lower.contains("dialer") || lower.contains("telecom")
    }

    private fun refreshDialerPackage() {
        cachedDialerPackage = runCatching {
            getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        }.getOrNull()
    }

    private fun handleSystemCall(sbn: StatusBarNotification, notification: Notification) {
        buildSystemCall(sbn, notification)?.let { seed ->
            CallHub.updateSystemCall(seed.key, seed.packageName, seed.info)
        }
    }

    /** Builds the card data; shared by live posts and by reconciliation after a reconnect. */
    private fun buildSystemCall(
        sbn: StatusBarNotification,
        notification: Notification
    ): SystemCallSeed? {
        val actions = notification.actions.orEmpty().mapNotNull { it.title?.toString() }
        val ringing = notification.actions.orEmpty().any {
            val kind = actionKind(it)
            kind == CallActionKind.ANSWER || kind == CallActionKind.DECLINE
        }
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras?.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
            )?.toString()?.trim().orEmpty()
        // Keep the number only when it really is a number; a contact name belongs in the title.
        val number = extractNumber("$title $text").orEmpty()
        val displayName = title.ifBlank { text }

        Log.d(
            "LinkPhone",
            "call notification pkg=${sbn.packageName} category=${notification.category} " +
                "ongoing=${sbn.isOngoing} actions=$actions title=$title text=$text"
        )

        // A dialer only starts its chronometer when the call connects, and `when` is then the
        // connect time; this is the most reliable answer signal available without the dialer role.
        val chronometer = extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) == true
        val countDown = extras?.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN) == true
        val connected = chronometer && !countDown
        val phase = when {
            ringing -> PhoneCallPhase.RINGING
            connected -> PhoneCallPhase.ACTIVE
            TIMER_PATTERN.containsMatchIn("$title $text") -> PhoneCallPhase.ACTIVE
            "$title $text".contains("calling", ignoreCase = true) ||
                "$title $text".contains("dialing", ignoreCase = true) ||
                "$title $text".contains("connecting", ignoreCase = true) -> PhoneCallPhase.DIALING
            CallHub.hasPendingOutgoing() -> PhoneCallPhase.DIALING
            else -> PhoneCallPhase.ACTIVE
        }

        return SystemCallSeed(
            key = sbn.key,
            packageName = sbn.packageName,
            info = PhoneCallInfo(
                id = "system:${sbn.key}",
                number = number,
                displayName = displayName,
                phase = phase,
                incoming = phase == PhoneCallPhase.RINGING,
                startedAt = if (connected && notification.`when` > 0) {
                    notification.`when`
                } else {
                    System.currentTimeMillis()
                }
            )
        )
    }

    private fun extractNumber(value: String): String? =
        Regex("""\+?\d[\d\s()\-]{4,}\d""").find(value)?.value?.trim()

    private fun isFreeFormReply(action: Notification.Action): Boolean =
        action.remoteInputs?.any { it.allowFreeFormInput } == true

    private fun actionLabel(action: Notification.Action): String? =
        action.title?.toString()
            ?.replace(Regex("[\\r\\n\\t]+"), " ")
            ?.trim()
            ?.take(24)
            ?.takeIf { it.isNotEmpty() }

    /**
     * Labels only: the call-specific `semanticAction` values (answer/reject/hang up) are hidden
     * framework constants, not public API, so they cannot be referenced here.
     */
    private fun actionKind(action: Notification.Action): CallActionKind? {
        if (isFreeFormReply(action)) return null
        val label = action.title?.toString().orEmpty().lowercase().replace(Regex("\\s+"), " ")
        if (label.isBlank()) return null
        val answerWords = listOf(
            "answer", "accept", "pick up", "annehmen", "répondre", "repondre",
            "responder", "atender", "rispondi", "接听"
        )
        val declineWords = listOf(
            "decline", "reject", "ignore", "ablehnen", "refuser", "rechazar",
            "recusar", "rifiuta", "拒绝"
        )
        val hangUpWords = listOf(
            "hang up", "hangup", "end call", "auflegen", "raccrocher", "colgar",
            "encerrar", "riaggancia", "挂断"
        )
        return when {
            answerWords.any { label.contains(it) } -> CallActionKind.ANSWER
            declineWords.any { label.contains(it) } -> CallActionKind.DECLINE
            label == "end" || hangUpWords.any { label.contains(it) } -> CallActionKind.HANGUP
            else -> null
        }
    }

    private fun findCallAction(notification: Notification, kind: CallActionKind): Notification.Action? {
        val actions = notification.actions.orEmpty().filter { it.actionIntent != null }
        actions.firstOrNull { actionKind(it) == kind }?.let { return it }
        // A lone unfilled action on a connected call is its hang-up button.
        if (kind == CallActionKind.HANGUP && actions.size == 1 && !isFreeFormReply(actions[0])) {
            return actions.first()
        }
        return null
    }

    /** Fires a call notification's own action. Returns null on success, otherwise a reason. */
    private fun handleCallAction(key: String, kind: CallActionKind): String? {
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: return "gone"
        val action = findCallAction(sbn.notification, kind) ?: return "no-action"
        return sendIntent(action.actionIntent, Intent())
    }

    private fun drop(key: String, client: LinkClient) {
        lastSent.remove(key)
        lastSentAt.remove(key)
        forgetGroupKey(key)
        if (mirrored.remove(key)) client.sendNotificationDismissed(key)
    }

    /** Returns null on success, or a short reason the PC can show. */
    private fun handleAction(key: String, actionId: String, replyText: String, label: String): String? {
        val sbn = activeNotifications?.firstOrNull { it.key == key } ?: return "gone"
        if (actionId == "content") {
            val content = sbn.notification.contentIntent ?: return "gone"
            return sendIntent(content, Intent())
        }
        val actions = sbn.notification.actions.orEmpty()
        // "reply" is resolved by meaning, never by position: the app may reorder its actions.
        val action = if (actionId == "reply") {
            actions.firstOrNull { it.actionIntent != null && isFreeFormReply(it) }
        } else {
            val index = actionId.removePrefix("a").toIntOrNull() ?: return "no-action"
            actions.getOrNull(index)
        } ?: return "no-action"

        val remoteInputs = action.remoteInputs
        if (actionId == "reply" || isFreeFormReply(action)) {
            val text = replyText.trim()
            if (text.isEmpty()) return "blank"
            if (text.length > MAX_REPLY_CHARS) return "too-long"
            if (remoteInputs.isNullOrEmpty()) return "no-reply"
            val intent = Intent()
            val results = Bundle().apply {
                remoteInputs.forEach { putCharSequence(it.resultKey, text) }
            }
            RemoteInputCompat.addResults(remoteInputs, intent, results)
            return sendIntent(action.actionIntent, intent)
        }

        // Plain buttons keep their index; a stale index is detected by the echoed label.
        if (label.isNotBlank() && actionLabel(action) != label) return "action-changed"
        return sendIntent(action.actionIntent, Intent())
    }

    private fun sendIntent(pending: PendingIntent?, intent: Intent): String? {
        if (pending == null) return "no-action"
        return try {
            pending.send(this, 0, intent)
            null
        } catch (e: PendingIntent.CanceledException) {
            Log.w("LinkPhone", "notification action canceled", e)
            "blocked"
        } catch (e: SecurityException) {
            Log.w("LinkPhone", "notification action denied", e)
            "blocked"
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
        private const val MAX_REPLY_CHARS = 2000
        private const val MAX_BACKFILL = 30
        private const val THROTTLE_MS = 2_000L
        private const val REBIND_INTERVAL_MS = 10_000L
        private const val MAX_REBIND_ATTEMPTS = 3

        /** A dialer chronometer such as "00:42" means the call is connected. */
        private val TIMER_PATTERN = Regex("""\b\d{1,2}:\d{2}\b""")

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
            instance?.let {
                it.refreshDialerPackage()
                it.reconcileCallNotifications()
                it.backfill()
            }
        }

        /** Rebuild the call cards from the live shade; called after a reconnect or a role change. */
        fun reconcileCalls() {
            instance?.reconcileCallNotifications()
        }

        /** Null when the listener cannot answer; otherwise the keys of live call notifications. */
        fun activeCallKeys(): Set<String>? = instance?.liveCallKeys()

        /** Null means the action ran; otherwise a short reason for the PC. */
        fun deliver(key: String, actionId: String, replyText: String, label: String = ""): String? =
            instance?.handleAction(key, actionId, replyText, label) ?: "unavailable"

        /** Answer/Decline/Hang up through the owning app's own notification action. */
        fun deliverCallAction(key: String, kind: CallActionKind): String? =
            instance?.handleCallAction(key, kind) ?: "unavailable"

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
