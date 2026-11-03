package dev.linkphone.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Reads conversations straight out of the system SMS provider and sends new
 * messages through [SmsManager].
 *
 * LinkPhone deliberately does **not** ask for the default-SMS-app role: reading
 * and sending only needs READ_SMS / SEND_SMS, and becoming the default app would
 * mean implementing the full SMS/MMS compose + delivery pipeline (and Play
 * policy review) for no extra functionality in v0.2. The trade-off is that
 * incoming messages are read from the provider, which the user's real default
 * app writes; we never write to it ourselves.
 */
class SmsRepository(private val context: Context) {

    data class Thread(
        val threadId: String,
        val address: String,
        val name: String,
        val snippet: String,
        val date: Long,
        val count: Int,
        val subscriptionId: Int? = null
    )

    data class Message(
        val id: String,
        val threadId: String,
        val address: String,
        val name: String = "",
        val body: String,
        val date: Long,
        val outgoing: Boolean,
        val failed: Boolean = false,
        val subscriptionId: Int? = null
    )

    private val resolver = context.contentResolver
    private val contacts = ContactsRepository.get(context)

    fun contactName(address: String): String = contacts.nameFor(address).orEmpty()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * One entry per conversation, newest first. The provider has no portable
     * "conversations" view on every OEM ROM, so rows are grouped by thread_id
     * here. [MAX_SCAN] caps the work on a phone with years of messages.
     */
    fun threads(limit: Int = 60): List<Thread> {
        val found = LinkedHashMap<String, Thread>()
        var scanned = 0
        runCatching {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                THREAD_PROJECTION,
                null,
                null,
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val subIndex = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)

                while (cursor.moveToNext() && scanned < MAX_SCAN) {
                    scanned += 1
                    val threadId = cursor.getString(threadIndex) ?: continue
                    val existing = found[threadId]
                    if (existing != null) {
                        found[threadId] = existing.copy(count = existing.count + 1)
                        continue
                    }
                    if (found.size >= limit) continue
                    val address = cursor.getString(addressIndex).orEmpty()
                    found[threadId] = Thread(
                        threadId = threadId,
                        address = address,
                        name = contacts.nameFor(address).orEmpty(),
                        snippet = cursor.getString(bodyIndex).orEmpty().take(160),
                        date = cursor.getLong(dateIndex),
                        count = 1,
                        subscriptionId = if (subIndex >= 0) {
                            cursor.getInt(subIndex).takeIf { it >= 0 }
                        } else {
                            null
                        }
                    )
                }
            }
        }.onFailure { Log.w(TAG, "cannot read SMS threads", it) }

        return found.values.toList()
    }

    /** Messages in one thread, oldest first. Accepts `addr:<number>` for senders we have no thread id for yet. */
    fun messages(threadId: String): List<Message> {
        if (threadId.startsWith(ADDRESS_PREFIX)) {
            return messagesForAddress(threadId.removePrefix(ADDRESS_PREFIX))
        }
        return query("${Telephony.Sms.THREAD_ID} = ?", arrayOf(threadId))
    }

    fun messagesForAddress(address: String): List<Message> =
        query("${Telephony.Sms.ADDRESS} = ?", arrayOf(address))

    fun threadIdFor(address: String): String? {
        var threadId: String? = null
        runCatching {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.THREAD_ID),
                "${Telephony.Sms.ADDRESS} = ?",
                arrayOf(address),
                "${Telephony.Sms.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) threadId = cursor.getString(0)
            }
        }.onFailure { Log.w(TAG, "cannot resolve thread id", it) }
        return threadId
    }

    fun send(address: String, body: String, subscriptionId: Int? = null): Result<Unit> = runCatching {
        val manager = smsManager(subscriptionId) ?: error("SMS is not available on this device")
        val parts = manager.divideMessage(body)
        if (parts.size > 1) {
            manager.sendMultipartTextMessage(address, null, parts, null, null)
        } else {
            manager.sendTextMessage(address, null, body, null, null)
        }
    }

    private fun smsManager(subscriptionId: Int? = null): SmsManager? {
        if (subscriptionId != null && subscriptionId >= 0) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
                    ?.createForSubscriptionId(subscriptionId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }
    }

    private fun query(selection: String, args: Array<String>): List<Message> {
        val out = ArrayList<Message>()
        runCatching {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                MESSAGE_PROJECTION,
                selection,
                args,
                "${Telephony.Sms.DATE} ASC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    val address = cursor.getString(
                        cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    ).orEmpty()
                    val subIndex = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
                    out.add(
                        Message(
                            id = cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms._ID))
                                ?: "m-${out.size}",
                            threadId = cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                            ).orEmpty(),
                            address = address,
                            name = contacts.nameFor(address).orEmpty(),
                            body = cursor.getString(
                                cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                            ).orEmpty(),
                            date = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)),
                            outgoing = type == Telephony.Sms.MESSAGE_TYPE_SENT ||
                                type == Telephony.Sms.MESSAGE_TYPE_OUTBOX ||
                                type == Telephony.Sms.MESSAGE_TYPE_QUEUED,
                            failed = type == Telephony.Sms.MESSAGE_TYPE_FAILED,
                            subscriptionId = if (subIndex >= 0) {
                                cursor.getInt(subIndex).takeIf { it >= 0 }
                            } else {
                                null
                            }
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "cannot read SMS messages", it) }

        // Most recent MAX_MESSAGES, still oldest-first for the UI.
        return if (out.size > MAX_MESSAGES) out.subList(out.size - MAX_MESSAGES, out.size) else out
    }

    companion object {
        private const val TAG = "LinkPhone"
        private const val MAX_SCAN = 2500
        private const val MAX_MESSAGES = 300
        const val ADDRESS_PREFIX = "addr:"

        private val THREAD_PROJECTION = arrayOf(
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.SUBSCRIPTION_ID
        )

        private val MESSAGE_PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.SUBSCRIPTION_ID
        )
    }
}
