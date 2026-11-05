package dev.linkphone.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.DisconnectCause
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

enum class PhoneCallPhase {
    NEW, CONNECTING, DIALING, RINGING, ACTIVE, HELD, DISCONNECTING, DISCONNECTED, UNKNOWN
}

data class PhoneCallInfo(
    val id: String,
    val number: String,
    val displayName: String,
    val phase: PhoneCallPhase,
    val incoming: Boolean,
    val startedAt: Long,
    val simLabel: String? = null
)

data class PhoneCallSnapshot(
    val calls: List<PhoneCallInfo> = emptyList(),
    val dialerReady: Boolean = false,
    val outgoingReady: Boolean = false,
    val controlReady: Boolean = false,
    val speaker: Boolean = false,
    val muted: Boolean = false
)

/** Process-wide call state shared by the phone UI, InCallService and LAN client. */
object CallHub {
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(PhoneCallSnapshot())
    val state: StateFlow<PhoneCallSnapshot> = _state.asStateFlow()

    private var appContext: Context? = null
    private var systemCall: PhoneCallInfo? = null
    private var telephonyPhase: PhoneCallPhase? = null
    private var systemSpeaker = false

    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    internal fun publish(snapshot: PhoneCallSnapshot) {
        _state.value = snapshot
        LinkCore.clientOrNull()?.sendCallState(snapshot)
    }

    fun refreshDialerRole(context: Context) {
        publish(
            _state.value.copy(
                dialerReady = isCallReady(context),
                outgoingReady = isOutgoingReady(context),
                controlReady = isRemoteControlReady(context)
            )
        )
    }

    fun isRemoteControlReady(context: Context): Boolean =
        PhoneCallService.instance != null ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAnswerPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
            PackageManager.PERMISSION_GRANTED

    @Synchronized
    fun updateSystemCall(call: PhoneCallInfo) {
        if (PhoneCallService.instance != null) return
        val startedAt = systemCall?.startedAt ?: call.startedAt
        val phase = telephonyPhase ?: call.phase
        systemCall = call.copy(
            phase = phase,
            incoming = phase == PhoneCallPhase.RINGING,
            startedAt = startedAt
        )
        publishSystemState()
    }

    @Synchronized
    fun updateTelephonyCall(phase: PhoneCallPhase) {
        if (PhoneCallService.instance != null) return
        if (phase != PhoneCallPhase.RINGING && phase != PhoneCallPhase.ACTIVE) return
        telephonyPhase = phase
        val existing = systemCall
        systemCall = if (existing != null) {
            existing.copy(
                phase = phase,
                incoming = phase == PhoneCallPhase.RINGING
            )
        } else {
            PhoneCallInfo(
                id = "system:telephony",
                number = "",
                displayName = if (phase == PhoneCallPhase.RINGING) {
                    "Incoming call"
                } else {
                    "Phone call"
                },
                phase = phase,
                incoming = phase == PhoneCallPhase.RINGING,
                startedAt = System.currentTimeMillis()
            )
        }
        publishSystemState()
    }

    @Synchronized
    fun clearTelephonyCall() {
        if (PhoneCallService.instance != null) return
        telephonyPhase = null
        systemCall = null
        systemSpeaker = false
        publishSystemState()
    }

    @Synchronized
    fun clearSystemCall() {
        if (PhoneCallService.instance != null) return
        // The dialer notification can disappear while telephony still reports a live call.
        if (telephonyPhase != null) return
        systemCall = null
        systemSpeaker = false
        publishSystemState()
    }

    private fun publishSystemState() {
        val context = appContext
        publish(
            PhoneCallSnapshot(
                calls = listOfNotNull(systemCall),
                dialerReady = context?.let { isCallReady(it) } ?: false,
                outgoingReady = context?.let { isOutgoingReady(it) } ?: false,
                controlReady = context?.let { isRemoteControlReady(it) } ?: false,
                speaker = systemSpeaker,
                muted = false
            )
        )
    }

    fun isDefaultDialer(context: Context): Boolean {
        val roles = context.getSystemService(RoleManager::class.java) ?: return false
        return roles.isRoleAvailable(RoleManager.ROLE_DIALER) && roles.isRoleHeld(RoleManager.ROLE_DIALER)
    }

    fun isOutgoingReady(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED

    fun isCallReady(context: Context): Boolean =
        isDefaultDialer(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    fun placeCall(context: Context, rawNumber: String, accountId: String? = null): Result<Unit> = runCatching {
        if (!isOutgoingReady(context)) error("Phone permission is not granted")

        val number = rawNumber.filter { it.isDigit() || it in "+*#,;" }
        if (number.length < 2) error("Enter a valid phone number")
        if (number.length > 64) error("Phone number is too long")
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: error("Calling is unavailable on this device")
        val uri = Uri.fromParts("tel", number, null)
        val extras = Bundle()
        if (!accountId.isNullOrBlank()) {
            val sims = SimRepository.get(context)
            if (!sims.hasPermission()) error("Phone access is needed to choose a SIM")
            val handle = sims.accountHandle(accountId)
                ?: error("That SIM is no longer available")
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
        }
        try {
            telecom.placeCall(uri, extras)
        } catch (_: SecurityException) {
            startCallActivity(context, uri, extras)
        } catch (_: IllegalArgumentException) {
            startCallActivity(context, uri, extras)
        }
    }

    @Suppress("DEPRECATION")
    fun answer(context: Context, callId: String): Result<Unit> = runCatching {
        val service = PhoneCallService.instance
        if (service != null) {
            if (!service.hasCall(callId)) error("Call is no longer available")
            main.post { runCatching { service.answer(callId) } }
            return@runCatching
        }
        if (!hasAnswerPermission(context)) error("Allow call access on your phone")
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: error("Calling is unavailable on this device")
        telecom.acceptRingingCall()
        systemCall = systemCall?.copy(phase = PhoneCallPhase.ACTIVE, incoming = false)
        publishSystemState()
    }

    @Suppress("DEPRECATION")
    fun reject(context: Context, callId: String): Result<Unit> = runCatching {
        val service = PhoneCallService.instance
        if (service != null) {
            if (!service.hasCall(callId)) error("Call is no longer available")
            main.post { runCatching { service.reject(callId) } }
            return@runCatching
        }
        if (!hasAnswerPermission(context)) error("Allow call access on your phone")
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: error("Calling is unavailable on this device")
        telecom.endCall()
        systemCall = null
        systemSpeaker = false
        publishSystemState()
    }

    @Suppress("DEPRECATION")
    fun disconnect(context: Context, callId: String): Result<Unit> = runCatching {
        val service = PhoneCallService.instance
        if (service != null) {
            if (!service.hasCall(callId)) error("Call is no longer available")
            main.post { runCatching { service.disconnect(callId) } }
            return@runCatching
        }
        if (!hasAnswerPermission(context)) error("Allow call access on your phone")
        val telecom = context.getSystemService(TelecomManager::class.java)
            ?: error("Calling is unavailable on this device")
        telecom.endCall()
        systemCall = null
        systemSpeaker = false
        publishSystemState()
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        val service = PhoneCallService.instance
        if (service != null) {
            main.post { runCatching { service.setSpeaker(enabled) } }
            return@runCatching
        }
        if (!hasAnswerPermission(context)) error("Allow call access on your phone")
        val audio = context.getSystemService(AudioManager::class.java)
            ?: error("Audio routing is unavailable on this device")
        audio.isSpeakerphoneOn = enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val speaker = audio.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } ?: error("Speaker routing is unavailable")
                if (!audio.setCommunicationDevice(speaker)) {
                    error("Speaker routing was blocked by the phone")
                }
            } else {
                audio.clearCommunicationDevice()
            }
        }
        systemSpeaker = enabled
        publishSystemState()
    }

    fun answer(callId: String) = main.post { runCatching { PhoneCallService.instance?.answer(callId) } }

    fun reject(callId: String) = main.post { runCatching { PhoneCallService.instance?.reject(callId) } }

    fun disconnect(callId: String) = main.post { runCatching { PhoneCallService.instance?.disconnect(callId) } }

    fun setSpeaker(enabled: Boolean) = main.post {
        runCatching { PhoneCallService.instance?.setSpeaker(enabled) }
    }

    /**
     * Muting has no reliable non-dialer fallback: AudioManager.setMicrophoneMute is device-global and
     * often ignored on modern OEMs, so require the InCallService instead of claiming success.
     */
    fun setMute(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        val service = PhoneCallService.instance
            ?: error("Allow call access on your phone")
        main.post { runCatching { service.setMute(enabled) } }
    }

    fun setMute(enabled: Boolean) = main.post {
        runCatching { PhoneCallService.instance?.setMute(enabled) }
    }
}

/**
 * Restrictive ROMs can refuse Telecom placement for a non-default dialer. Hand the same call to the
 * system dialer; this needs LinkPhone to be visible on Android 10+ background-start rules.
 */
@Suppress("DEPRECATION")
private fun startCallActivity(context: Context, uri: Uri, extras: Bundle) {
    val intent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    (extras.getParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE) as? PhoneAccountHandle)
        ?.let { intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
    context.startActivity(intent)
}

/** Default-dialer InCallService. No audio is captured or transported by LinkPhone. */
class PhoneCallService : InCallService() {
    private val calls = ConcurrentHashMap<String, Call>()
    private val callbacks = ConcurrentHashMap<String, Call.Callback>()
    private val started = ConcurrentHashMap<String, Long>()
    private lateinit var notifier: CallNotifier

    override fun onCreate() {
        super.onCreate()
        LinkCore.init(this)
        instance = this
        notifier = CallNotifier(this)
        CallHub.refreshDialerRole(this)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val id = callId(call)
        calls[id] = call
        started[id] = System.currentTimeMillis()
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                publish()
            }
            override fun onDetailsChanged(call: Call, details: Call.Details) = publish()
        }
        callbacks[id] = callback
        call.registerCallback(callback)
        publish()
        if (call.state == Call.STATE_RINGING ||
            !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            runCatching {
                startActivity(
                    Intent(this, CallActivity::class.java)
                        .putExtra(CallActionReceiver.EXTRA_CALL_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        val id = callId(call)
        val finalInfo = call.toInfo(id, started[id] ?: System.currentTimeMillis())
        val wasMissed = call.details.disconnectCause.code == DisconnectCause.MISSED
        callbacks.remove(id)?.let { call.unregisterCallback(it) }
        calls.remove(id)
        started.remove(id)
        publish()
        if (finalInfo.incoming && wasMissed) notifier.showMissed(finalInfo)
        super.onCallRemoved(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        publish()
    }

    override fun onDestroy() {
        for ((id, call) in calls) callbacks[id]?.let { call.unregisterCallback(it) }
        callbacks.clear()
        calls.clear()
        started.clear()
        notifier.cancel()
        instance = null
        CallHub.publish(
            PhoneCallSnapshot(
                dialerReady = CallHub.isCallReady(this),
                outgoingReady = CallHub.isOutgoingReady(this),
                controlReady = CallHub.isRemoteControlReady(this)
            )
        )
        super.onDestroy()
    }

    fun hasCall(callId: String): Boolean = calls.containsKey(callId)

    fun answer(callId: String) {
        calls[callId]?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun reject(callId: String) {
        calls[callId]?.reject(false, null)
    }

    fun disconnect(callId: String) {
        calls[callId]?.disconnect()
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(enabled: Boolean) {
        setAudioRoute(if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
        publish()
    }

    fun setMute(enabled: Boolean) {
        if (calls.values.none { it.state == Call.STATE_ACTIVE || it.state == Call.STATE_HOLDING }) return
        setMuted(enabled)
        publish()
    }

    private fun publish() {
        val audio = callAudioState
        val snapshot = PhoneCallSnapshot(
            calls = calls.map { (id, call) -> call.toInfo(id, started[id] ?: System.currentTimeMillis()) }
                .sortedWith(compareBy<PhoneCallInfo> { it.phase != PhoneCallPhase.RINGING }.thenBy { it.startedAt }),
            dialerReady = CallHub.isCallReady(this),
            outgoingReady = CallHub.isOutgoingReady(this),
            controlReady = CallHub.isRemoteControlReady(this),
            speaker = audio?.route == CallAudioState.ROUTE_SPEAKER,
            muted = audio?.isMuted == true
        )
        CallHub.publish(snapshot)
        notifier.update(snapshot)
    }

    private fun Call.toInfo(id: String, fallbackStartedAt: Long): PhoneCallInfo {
        val info = details
        val incoming = info.callDirection == Call.Details.DIRECTION_INCOMING || state == Call.STATE_RINGING
        val connectedAt = info.connectTimeMillis.takeIf { it > 0 } ?: fallbackStartedAt
        val number = info.handle?.schemeSpecificPart.orEmpty().ifBlank { "Unknown" }
        val resolved = info.callerDisplayName?.toString().orEmpty()
            .ifBlank { ContactsRepository.get(this@PhoneCallService).nameFor(number).orEmpty() }
        val simLabel = info.accountHandle?.let {
            SimRepository.get(this@PhoneCallService).labelForAccount(it)
        }
        return PhoneCallInfo(
            id = id,
            number = number,
            displayName = resolved,
            phase = state.toPhase(),
            incoming = incoming,
            startedAt = connectedAt,
            simLabel = simLabel
        )
    }

    private fun Int.toPhase(): PhoneCallPhase = when (this) {
        Call.STATE_NEW -> PhoneCallPhase.NEW
        Call.STATE_CONNECTING, Call.STATE_SELECT_PHONE_ACCOUNT -> PhoneCallPhase.CONNECTING
        Call.STATE_DIALING, Call.STATE_PULLING_CALL -> PhoneCallPhase.DIALING
        Call.STATE_RINGING -> PhoneCallPhase.RINGING
        Call.STATE_ACTIVE -> PhoneCallPhase.ACTIVE
        Call.STATE_HOLDING -> PhoneCallPhase.HELD
        Call.STATE_DISCONNECTING -> PhoneCallPhase.DISCONNECTING
        Call.STATE_DISCONNECTED -> PhoneCallPhase.DISCONNECTED
        else -> PhoneCallPhase.UNKNOWN
    }

    private fun callId(call: Call): String = Integer.toHexString(System.identityHashCode(call))

    companion object {
        @Volatile internal var instance: PhoneCallService? = null
    }
}

/** Explicit notification actions work even while the phone UI is not open. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LinkCore.init(context)
        val callId = intent.getStringExtra(EXTRA_CALL_ID).orEmpty()
        when (intent.action) {
            ACTION_ANSWER -> CallHub.answer(callId)
            ACTION_REJECT -> CallHub.reject(callId)
            ACTION_DISCONNECT -> CallHub.disconnect(callId)
            ACTION_SPEAKER -> CallHub.setSpeaker(intent.getBooleanExtra(EXTRA_ENABLED, false))
            ACTION_MUTE -> CallHub.setMute(intent.getBooleanExtra(EXTRA_ENABLED, false))
        }
    }

    companion object {
        const val ACTION_ANSWER = "dev.linkphone.app.call.ANSWER"
        const val ACTION_REJECT = "dev.linkphone.app.call.REJECT"
        const val ACTION_DISCONNECT = "dev.linkphone.app.call.DISCONNECT"
        const val ACTION_SPEAKER = "dev.linkphone.app.call.SPEAKER"
        const val ACTION_MUTE = "dev.linkphone.app.call.MUTE"
        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_ENABLED = "enabled"
    }
}

private class CallNotifier(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phone calls",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Incoming and active LinkPhone calls"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val missed = NotificationChannel(
            MISSED_CHANNEL_ID,
            "Missed calls",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Missed calls handled by LinkPhone"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(missed)
    }

    fun update(snapshot: PhoneCallSnapshot) {
        val call = snapshot.calls.firstOrNull() ?: run {
            cancel()
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return

        val contentIntent = PendingIntent.getActivity(
            context,
            90,
            Intent(context, CallActivity::class.java)
                .putExtra(CallActionReceiver.EXTRA_CALL_ID, call.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle(call.displayName.ifBlank { call.number })
            .setContentText(call.phase.notificationText())
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, call.phase == PhoneCallPhase.RINGING)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(call.phase != PhoneCallPhase.DISCONNECTED)

        if (call.phase == PhoneCallPhase.RINGING) {
            builder.addAction(
                0,
                "Decline",
                action(CallActionReceiver.ACTION_REJECT, call.id, 91)
            )
            builder.addAction(
                0,
                "Answer",
                action(CallActionReceiver.ACTION_ANSWER, call.id, 92)
            )
        } else {
            builder.addAction(
                0,
                if (snapshot.speaker) "Speaker off" else "Speaker on",
                action(
                    CallActionReceiver.ACTION_SPEAKER,
                    call.id,
                    93,
                    !snapshot.speaker
                )
            )
            builder.addAction(
                0,
                "Hang up",
                action(CallActionReceiver.ACTION_DISCONNECT, call.id, 94)
            )
        }
        manager.notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel() {
        manager.cancel(NOTIFICATION_ID)
    }

    fun showMissed(call: PhoneCallInfo) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", call.number, null), context, CallActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            95 + call.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, MISSED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_logo)
            .setColor(ContextCompat.getColor(context, R.color.notification_accent))
            .setContentTitle("Missed call")
            .setContentText(call.displayName.ifBlank { call.number })
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MISSED_CALL)
            .build()
        manager.notify(MISSED_NOTIFICATION_BASE + call.id.hashCode(), notification)
    }

    private fun action(
        action: String,
        callId: String,
        requestCode: Int,
        enabled: Boolean? = null
    ): PendingIntent {
        val intent = Intent(context, CallActionReceiver::class.java)
            .setAction(action)
            .putExtra(CallActionReceiver.EXTRA_CALL_ID, callId)
        if (enabled != null) intent.putExtra(CallActionReceiver.EXTRA_ENABLED, enabled)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun PhoneCallPhase.notificationText(): String = when (this) {
        PhoneCallPhase.RINGING -> "Incoming call"
        PhoneCallPhase.DIALING -> "Dialing"
        PhoneCallPhase.CONNECTING -> "Connecting"
        PhoneCallPhase.ACTIVE -> "Call in progress"
        PhoneCallPhase.HELD -> "Call on hold"
        PhoneCallPhase.DISCONNECTING -> "Ending call"
        PhoneCallPhase.DISCONNECTED -> "Call ended"
        else -> "Phone call"
    }

    companion object {
        private const val CHANNEL_ID = "linkphone_calls"
        private const val MISSED_CHANNEL_ID = "linkphone_missed_calls"
        private const val NOTIFICATION_ID = 42
        private const val MISSED_NOTIFICATION_BASE = 4300
    }
}
