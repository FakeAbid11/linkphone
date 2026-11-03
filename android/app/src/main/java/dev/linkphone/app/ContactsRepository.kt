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
class ContactsRepository private constructor(private val context: Context) {

    private val resolver = context.contentResolver
    private val cache = ConcurrentHashMap<String, String>()

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

    /** Load in the background so the first call or SMS thread does not block the UI. */
    fun preload() {
        CoroutineScope(Dispatchers.IO).launch { ensureLoaded() }
    }

    fun invalidate() {
        loaded = false
        loadedAt = 0L
        cache.clear()
    }

    @Synchronized
    private fun ensureLoaded() {
        val now = SystemClock.elapsedRealtime()
        if (loaded && now - loadedAt < TTL_MS) return
        cache.clear()
        loaded = true
        loadedAt = now
        if (!hasPermission()) return

        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numberIndex = cursor.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                if (nameIndex < 0 || numberIndex < 0) return@use
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)?.trim().orEmpty()
                    val key = normalize(cursor.getString(numberIndex).orEmpty()) ?: continue
                    if (name.isNotEmpty()) cache.putIfAbsent(key, name)
                }
            }
        }.onFailure { Log.w(TAG, "cannot read contacts", it) }
    }

    companion object {
        private const val TAG = "LinkPhone"
        private const val TTL_MS = 5 * 60 * 1000L

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
