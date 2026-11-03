package dev.linkphone.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telecom.PhoneAccountHandle
import android.util.Log
import androidx.core.content.ContextCompat

data class CallLogEntry(
    val id: String,
    val number: String,
    val name: String,
    val type: String,
    val date: Long,
    val duration: Long,
    val simLabel: String = ""
)

/** Reads the phone's recent call log for the PC hub; requires READ_CALL_LOG. */
class CallLogRepository private constructor(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
            PackageManager.PERMISSION_GRANTED

    fun list(limit: Int): List<CallLogEntry> {
        if (!hasPermission()) return emptyList()
        val capped = limit.coerceIn(1, MAX_ENTRIES)
        val contacts = ContactsRepository.get(context)
        val entries = ArrayList<CallLogEntry>(capped)

        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                    CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME
                ),
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndex(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val typeIndex = cursor.getColumnIndex(CallLog.Calls.TYPE)
                val dateIndex = cursor.getColumnIndex(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndex(CallLog.Calls.DURATION)
                val accountIndex = cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                val componentIndex = cursor.getColumnIndex(
                    CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME
                )
                if (idIndex < 0 || numberIndex < 0 || typeIndex < 0 || dateIndex < 0) return@use

                while (cursor.moveToNext() && entries.size < capped) {
                    val number = cursor.getString(numberIndex)?.trim().orEmpty()
                    val cached = if (nameIndex >= 0) {
                        cursor.getString(nameIndex)?.trim().orEmpty()
                    } else {
                        ""
                    }
                    val accountId = if (accountIndex >= 0) {
                        cursor.getString(accountIndex)
                    } else {
                        null
                    }
                    val component = if (componentIndex >= 0) {
                        cursor.getString(componentIndex)
                    } else {
                        null
                    }
                    val simLabel = if (!accountId.isNullOrBlank() && !component.isNullOrBlank()) {
                        val name = ComponentName.unflattenFromString(component)
                        val handle = name?.let { PhoneAccountHandle(it, accountId) }
                        handle?.let { SimRepository.get(context).labelForAccount(it) }.orEmpty()
                    } else {
                        ""
                    }
                    entries.add(
                        CallLogEntry(
                            id = if (idIndex >= 0) cursor.getLong(idIndex).toString() else "",
                            number = number,
                            name = cached.ifEmpty { contacts.nameFor(number).orEmpty() },
                            type = typeName(cursor.getInt(typeIndex)),
                            date = cursor.getLong(dateIndex),
                            duration = if (durationIndex >= 0) {
                                cursor.getLong(durationIndex)
                            } else {
                                0L
                            },
                            simLabel = simLabel
                        )
                    )
                }
            }
        }.onFailure { Log.w(TAG, "cannot read call log", it) }

        return entries
    }

    companion object {
        private const val TAG = "LinkPhone"
        private const val MAX_ENTRIES = 200

        @Volatile private var instance: CallLogRepository? = null

        fun get(context: Context): CallLogRepository = instance ?: synchronized(this) {
            instance ?: CallLogRepository(context.applicationContext).also { instance = it }
        }

        private fun typeName(type: Int): String = when (type) {
            CallLog.Calls.INCOMING_TYPE -> "incoming"
            CallLog.Calls.OUTGOING_TYPE -> "outgoing"
            CallLog.Calls.MISSED_TYPE -> "missed"
            CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
            CallLog.Calls.REJECTED_TYPE -> "rejected"
            CallLog.Calls.BLOCKED_TYPE -> "blocked"
            else -> "unknown"
        }
    }
}
