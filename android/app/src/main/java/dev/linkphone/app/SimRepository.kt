package dev.linkphone.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat

data class SimInfo(
    val id: String,
    val label: String,
    val carrier: String,
    val slot: Int,
    val isDefault: Boolean
)

data class CallAccountInfo(
    val id: String,
    val label: String,
    val isDefault: Boolean
)

/** Lists SMS subscriptions and Telecom SIM accounts for the PC's SIM pickers. */
class SimRepository private constructor(private val context: Context) {

    @Volatile private var smsCache: List<SimInfo> = emptyList()
    @Volatile private var callCache: List<CallAccountInfo> = emptyList()
    @Volatile private var handleCache: Map<String, PhoneAccountHandle> = emptyMap()
    @Volatile private var defaultSmsId: String? = null
    @Volatile private var defaultCallId: String? = null
    @Volatile private var loadedAt = 0L

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    fun smsSims(): List<SimInfo> {
        ensureLoaded()
        return smsCache
    }

    fun callAccounts(): List<CallAccountInfo> {
        ensureLoaded()
        return callCache
    }

    fun defaultSmsId(): String? {
        ensureLoaded()
        return defaultSmsId
    }

    fun defaultCallId(): String? {
        ensureLoaded()
        return defaultCallId
    }

    fun labelForSubscription(subId: Int): String? {
        if (subId < 0) return null
        ensureLoaded()
        return smsCache.firstOrNull { it.id == subId.toString() }?.label
    }

    fun labelForAccount(handle: PhoneAccountHandle?): String? {
        if (handle == null) return null
        ensureLoaded()
        val id = accountId(handle)
        return callCache.firstOrNull { it.id == id }?.label
    }

    fun accountHandle(id: String): PhoneAccountHandle? {
        ensureLoaded()
        return handleCache[id]
    }

    fun accountId(handle: PhoneAccountHandle): String =
        handle.componentName.flattenToString() + "|" + handle.id

    fun reload() {
        loadedAt = 0L
        ensureLoaded()
    }

    @Synchronized
    private fun ensureLoaded() {
        val now = SystemClock.elapsedRealtime()
        if (loadedAt != 0L && now - loadedAt < TTL_MS) return
        loadedAt = now
        if (!hasPermission()) {
            smsCache = emptyList()
            callCache = emptyList()
            handleCache = emptyMap()
            defaultSmsId = null
            defaultCallId = null
            return
        }
        loadSms()
        loadCalls()
    }

    private fun loadSms() {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val infos = runCatching { manager?.activeSubscriptionInfoList }.getOrNull().orEmpty()
        val defaultId = runCatching { SmsManager.getDefaultSmsSubscriptionId() }.getOrDefault(-1)
        smsCache = infos.mapIndexedNotNull { index, info ->
            val subId = info.subscriptionId
            if (subId < 0) return@mapIndexedNotNull null
            val slot = info.simSlotIndex
            val carrier = info.carrierName?.toString()?.trim().orEmpty()
            val display = info.displayName?.toString()?.trim().orEmpty()
            val name = display.ifEmpty { carrier }
            val position = if (slot >= 0) slot + 1 else index + 1
            val label = buildString {
                append("SIM ").append(position)
                if (name.isNotEmpty()) append(" · ").append(name)
            }.take(24)
            SimInfo(
                id = subId.toString(),
                label = label,
                carrier = carrier.take(24),
                slot = slot,
                isDefault = subId == defaultId
            )
        }.sortedBy { it.slot }
        defaultSmsId = smsCache.firstOrNull { it.isDefault }?.id
    }

    private fun loadCalls() {
        val telecom = context.getSystemService(TelecomManager::class.java)
        val handles = runCatching { telecom?.callCapablePhoneAccounts }.getOrNull().orEmpty()
        val defaultId = runCatching { telecom?.getDefaultOutgoingPhoneAccount("tel") }
            .getOrNull()
            ?.let { accountId(it) }

        val accounts = handles.mapNotNull { handle ->
            val account = runCatching { telecom?.getPhoneAccount(handle) }.getOrNull()
                ?: return@mapNotNull null
            val label = account.label?.toString()?.trim().orEmpty()
                .ifEmpty { account.shortDescription?.toString()?.trim().orEmpty() }
            val isSim = (account.capabilities and PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION) != 0
            Triple(handle, label, isSim)
        }
        val simOnly = accounts.filter { it.third }
        val kept = (simOnly.ifEmpty { accounts }).sortedBy { accountId(it.first) }

        val bases = kept.mapIndexed { index, item ->
            item.second.ifEmpty { "SIM ${index + 1}" }.take(24)
        }
        val baseCounts = bases.groupingBy { it }.eachCount()
        val seen = HashMap<String, Int>()
        callCache = kept.mapIndexed { index, item ->
            val base = bases[index]
            val occurrence = (seen[base] ?: 0) + 1
            seen[base] = occurrence
            val label = if ((baseCounts[base] ?: 0) > 1) "$base $occurrence".take(24) else base
            CallAccountInfo(accountId(item.first), label, accountId(item.first) == defaultId)
        }
        handleCache = kept.associate { accountId(it.first) to it.first }
        defaultCallId = callCache.firstOrNull { it.isDefault }?.id
    }

    companion object {
        private const val TTL_MS = 15_000L

        @Volatile private var instance: SimRepository? = null

        fun get(context: Context): SimRepository = instance ?: synchronized(this) {
            instance ?: SimRepository(context.applicationContext).also { instance = it }
        }
    }
}
