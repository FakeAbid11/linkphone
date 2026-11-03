package dev.linkphone.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Live SMS arrival. Multipart/long messages arrive as several PDUs that must be
 * concatenated per sender — Android exposes them via
 * [Telephony.Sms.Intents.getMessagesFromIntent].
 *
 * Nothing is written to the SMS provider here: only the user's default SMS app
 * may do that. We forward the text straight to the desktop; the provider row
 * (written by that default app) shows up the next time a thread is refreshed.
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()
            ?: return
        if (parts.isEmpty()) return

        val bodies = LinkedHashMap<String, StringBuilder>()
        var receivedAt = System.currentTimeMillis()
        for (part in parts) {
            val address = part.originatingAddress ?: continue
            if (part.timestampMillis > 0) receivedAt = part.timestampMillis
            bodies.getOrPut(address) { StringBuilder() }.append(part.messageBody.orEmpty())
        }
        if (bodies.isEmpty()) return

        val pendingResult = goAsync()
        val application = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                LinkCore.init(application)
                val client = LinkCore.clientOrNull() ?: return@launch
                if (!client.isReady) return@launch

                val repository = SmsRepository(application)
                for ((address, body) in bodies) {
                    val text = body.toString()
                    if (text.isBlank()) continue

                    val threadId = runCatching { repository.threadIdFor(address) }.getOrNull()
                        ?: (SmsRepository.ADDRESS_PREFIX + address)

                    Log.d(TAG, "incoming SMS from $address (thread $threadId)")
                    client.sendSmsIncoming(
                        SmsRepository.Message(
                            id = "in-$receivedAt-$address",
                            threadId = threadId,
                            address = address,
                            name = repository.contactName(address),
                            body = text,
                            date = receivedAt,
                            outgoing = false
                        )
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "LinkPhone"
    }
}
