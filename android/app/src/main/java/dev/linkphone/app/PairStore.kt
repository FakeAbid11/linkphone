package dev.linkphone.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/** Small persisted state holder. The session key lives in EncryptedSharedPreferences. */
class PairStore(context: Context) {

    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "linkphone_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as SharedPreferences
    }.getOrElse {
        context.getSharedPreferences("linkphone_plain", Context.MODE_PRIVATE)
    }

    val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    var deviceName: String?
        get() = prefs.getString("deviceName", null)
        set(value) = prefs.edit().putString("deviceName", value).apply()

    var serverId: String?
        get() = prefs.getString("serverId", null)
        set(value) = prefs.edit().putString("serverId", value).apply()

    var serverName: String?
        get() = prefs.getString("serverName", null)
        set(value) = prefs.edit().putString("serverName", value).apply()

    var host: String?
        get() = prefs.getString("host", null)
        set(value) = prefs.edit().putString("host", value).apply()

    var port: Int
        get() = prefs.getInt("port", 8765)
        set(value) = prefs.edit().putInt("port", value).apply()

    var keyB64: String?
        get() = prefs.getString("key", null)
        set(value) = prefs.edit().putString("key", value).apply()

    private var alternateHosts: List<String>
        get() = prefs.getString("altHosts", null)
            ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        set(value) {
            prefs.edit()
                .putString("altHosts", value.distinct().take(8).joinToString("\n"))
                .apply()
        }

    var clipboardEnabled: Boolean
        get() = prefs.getBoolean("clipboard", true)
        set(value) = prefs.edit().putBoolean("clipboard", value).apply()

    var onboardingDone: Boolean
        get() = prefs.getBoolean("onboardingDone", false)
        set(value) = prefs.edit().putBoolean("onboardingDone", value).apply()

    fun hasPairing(): Boolean = keyB64 != null && host != null

    fun key(): ByteArray? = keyB64?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun hostsInOrder(): List<String> =
        (listOfNotNull(host) + alternateHosts)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    fun savePair(
        serverId: String,
        serverName: String,
        host: String,
        port: Int,
        key: String,
        hosts: List<String> = emptyList()
    ) {
        prefs.edit()
            .putString("serverId", serverId)
            .putString("serverName", serverName)
            .putString("host", host)
            .putInt("port", port)
            .putString("key", key)
            .apply()
        alternateHosts = hosts.filter { it != host }
    }

    fun updateEndpoint(host: String, port: Int) {
        val previous = this.host
        this.host = host
        this.port = port
        if (!previous.isNullOrBlank() && previous != host) {
            alternateHosts = listOf(previous) + alternateHosts
        }
    }

    fun clearPair() {
        prefs.edit()
            .remove("key").remove("host").remove("serverId").remove("serverName")
            .remove("altHosts")
            .apply()
    }
}
