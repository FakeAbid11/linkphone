package dev.linkphone.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.ContactsContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves phone numbers to contact names. The whole phone book is loaded once
 * into a small digits-only map so resolving a thread list or call does not run
 * one query per number. Names never leave the phone except over the existing
 * encrypted LAN channel.
 */
data class NumberLabel(val number: String, val label: String)

data class ContactEntry(val id: String, val name: String, val numbers: List<NumberLabel>)

class ContactsRepository private constructor(private val context: Context) {

    private val resolver = context.contentResolver
    private val cache = ConcurrentHashMap<String, String>()

    @Volatile private var listCache: List<ContactEntry> = emptyList()

    @Volatile private var loaded = false
    @Volatile private var loadedAt = 0L

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            invalidate()
        }
    }

    init {
        runCatching {
            resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        }
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun nameFor(number: String): String? {
        val key = normalize(number) ?: return null
        ensureLoaded()
        return cache[key]
    }

    /** Full contact list for the PC hub, loaded with the same TTL as the name map. */
    fun list(): List<ContactEntry> {
        ensureLoaded()
        return listCache
    }

    /** Load in the background so the first call or SMS thread does not block the UI. */
    fun preload() {
        CoroutineScope(Dispatchers.IO).launch { ensureLoaded() }
    }

    fun invalidate() {
        loaded = false
        loadedAt = 0L
        cache.clear()
        listCache = emptyList()
    }

    @Synchronized
    private fun ensureLoaded() {
        val now = SystemClock.elapsedRealtime()
        if (loaded && now - loadedAt < TTL_MS) return
        cache.clear()
        listCache = emptyList()
        loaded = true
        loadedAt = now
        if (!hasPermission()) return

        runCatching {
            val contacts = LinkedHashMap<Long, ContactBuilder>()
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID
                )
                val nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val typeIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.TYPE
                )
                val labelIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.LABEL
                )
                if (nameIndex < 0 || numberIndex < 0) return@use
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)?.trim().orEmpty()
                    val number = cursor.getString(numberIndex)?.trim().orEmpty()
                    val key = normalize(number) ?: continue
                    if (name.isEmpty()) continue
                    cache.putIfAbsent(key, name)

                    val id = if (idIndex >= 0) cursor.getLong(idIndex) else 0L
                    if (id <= 0L) continue
                    val entry = contacts.getOrPut(id) { ContactBuilder(id, name) }
                    if (entry.numbers.size >= MAX_NUMBERS) continue
                    if (entry.numbers.any { normalize(it.number) == key }) continue
                    val type = if (typeIndex >= 0) cursor.getInt(typeIndex) else 0
                    val custom = if (labelIndex >= 0) cursor.getString(labelIndex) else null
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        context.resources,
                        type,
                        custom ?: ""
                    ).toString()
                    entry.numbers.add(NumberLabel(number, label.take(24)))
                }
            }
            listCache = contacts.values
                .filter { it.numbers.isNotEmpty() }
                .map { ContactEntry(it.id.toString(), it.name, it.numbers.toList()) }
                .sortedBy { it.name.lowercase() }
                .take(MAX_CONTACTS)
        }.onFailure { Log.w(TAG, "cannot read contacts", it) }
    }

    private class ContactBuilder(val id: Long, val name: String) {
        val numbers = mutableListOf<NumberLabel>()
    }

    companion object {
        private const val TAG = "LinkPhone"
        private const val TTL_MS = 5 * 60 * 1000L
        private const val MAX_CONTACTS = 800
        private const val MAX_NUMBERS = 5

        @Volatile private var instance: ContactsRepository? = null

        fun get(context: Context): ContactsRepository = instance ?: synchronized(this) {
            instance ?: ContactsRepository(context.applicationContext).also { instance = it }
        }

        /** Digits only, last 10 to drop country prefixes; short codes never match. */
        fun normalize(number: String): String? {
            val digits = number.filter { it.isDigit() }
            if (digits.length < 7) return null
            return if (digits.length > 10) digits.takeLast(10) else digits
        }
    }
}
