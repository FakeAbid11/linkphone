package dev.linkphone.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Fallback call state for devices whose dialer notification is hidden from notification listeners.
 * Works without the default-dialer role; requires READ_PHONE_STATE.
 */
@Suppress("DEPRECATION")
object SystemCallWatcher {

    private var manager: TelephonyManager? = null
    private var listener: PhoneStateListener? = null

    @Synchronized
    fun start(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        if (listener != null) return

        val app = context.applicationContext
        val telephony = app.getSystemService(TelephonyManager::class.java) ?: return
        val stateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                Log.d("LinkPhone", "telephony call state=$state")
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING ->
                        CallHub.updateTelephonyCall(PhoneCallPhase.RINGING)
                    TelephonyManager.CALL_STATE_OFFHOOK ->
                        CallHub.updateTelephonyCall(PhoneCallPhase.ACTIVE)
                    TelephonyManager.CALL_STATE_IDLE ->
                        CallHub.clearTelephonyCall()
                }
            }
        }
        runCatching {
            telephony.listen(stateListener, PhoneStateListener.LISTEN_CALL_STATE)
        }.onFailure {
            Log.w("LinkPhone", "could not watch call state", it)
            return
        }
        manager = telephony
        listener = stateListener
    }

    @Synchronized
    fun stop() {
        val telephony = manager
        val active = listener
        manager = null
        listener = null
        if (telephony != null && active != null) {
            runCatching { telephony.listen(active, PhoneStateListener.LISTEN_NONE) }
        }
    }
}
