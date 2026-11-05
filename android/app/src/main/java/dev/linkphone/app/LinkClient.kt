package dev.linkphone.app

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val CAP_NOTIF_PROGRESS = "notif.progress"
private const val RECONNECT_MIN_MS = 2500L
private const val RECONNECT_MAX_MS = 15_000L
private const val DISCOVERY_INTERVAL_MS = 20_000L
private const val DISCOVERY_TIMEOUT_MS = 5_000L

enum class LinkState { IDLE, CONNECTING, PAIRING, CONNECTED, ERROR }

data class LinkStatus(
    val state: LinkState,
    val serverName: String = "",
    val detail: String = ""
)

data class TransferUi(
    val id: String,
    val name: String,
    val size: Long,
    val received: Long,
    val direction: String,
    val status: String,
    val target: String = "downloads"
)

data class OutgoingAction(val id: String, val label: String, val reply: Boolean)

data class OutgoingNotification(
    val key: String,
    val app: String,
    val pkg: String,
    val title: String,
    val text: String,
    val whenTs: Long,
    val canReply: Boolean,
    val actions: List<OutgoingAction>,
    val progress: Int? = null,
    val progressMax: Int? = null,
    val indeterminate: Boolean = false,
    val ongoing: Boolean = false
)

/**
 * One WebSocket session with the desktop. Owns the pairing handshake, the
 * encrypted frame codec and both directions of file transfer.
 */
class LinkClient(context: Context, private val store: PairStore) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val sms = SmsRepository(appContext)
    private val sims = SimRepository.get(appContext)
    private val media = MediaRepository(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ipv6Hosts = ConcurrentHashMap<String, InetAddress>()

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            ipv6Hosts[hostname]?.let { listOf(it) } ?: Dns.SYSTEM.lookup(hostname)
    }

    private val http = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(6, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _status = MutableStateFlow(LinkStatus(LinkState.IDLE))
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferUi>>(emptyList())
    val transfers: StateFlow<List<TransferUi>> = _transfers.asStateFlow()

    init {
        scope.launch {
            PhoneRinger.ringing.collect { sendRingState(it) }
        }
    }

    val clipboardIn = MutableSharedFlow<String>(extraBufferCapacity = 16)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var key: ByteArray? = null
    @Volatile private var ready = false

    @Volatile
    var supportsLiveProgress = false
        private set
    @Volatile private var wantConnection = false
    private var sendSeq = 0L
    private var recvSeq = 0L

    private var pendingCode: String? = null
    private var pendingServerId: String = ""
    private var pendingAddresses: List<String> = emptyList()
    private var serverName: String = ""
    private var host = ""
    private var port = 8765
    private var reconnectJob: Job? = null
    private var reconnectAttempts = 0
    private var reconnectDelayMs = RECONNECT_MIN_MS
    private var lastDiscoveryAt = 0L
    private var rediscovering = false
    private val autoDiscovery = Discovery(appContext)

    /** Extra addresses from a QR payload, tried in order when the first one fails. */
    private val hostQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val outgoing = ConcurrentHashMap<String, Outgoing>()

    val isReady: Boolean get() = ready

    private val _deviceName = MutableStateFlow(
        store.deviceName?.takeIf { it.isNotBlank() } ?: derivedDeviceName()
    )
    val deviceNameFlow: StateFlow<String> = _deviceName.asStateFlow()
    val deviceName: String
        get() = _deviceName.value

    private fun derivedDeviceName(): String =
        listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .ifBlank { "Android phone" }
            .replaceFirstChar { it.uppercase() }

    fun renameDevice(raw: String): Boolean {
        val name = sanitizeDeviceName(raw)
        if (name.isEmpty()) return false
        store.deviceName = name
        _deviceName.value = name
        if (ready) {
            send(
                JSONObject().apply {
                    put("t", "device.rename.state")
                    put("name", name)
                }
            )
        }
        return true
    }

    private fun sanitizeDeviceName(raw: String): String =
        raw.replace(Regex("[\\u0000-\\u001F\\u007F\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(32)

    /* ------------------------------------------------------------ */
    /* connection                                                    */
    /* ------------------------------------------------------------ */

    fun connect(address: String, portNumber: Int, code: String?) =
        connect(listOf(address), portNumber, code)

    /**
     * Connects using every address from a QR payload until one answers. A PC with
     * a VPN, Docker or Hyper-V adapter advertises several IPv4 addresses and only
     * one of them is reachable from the phone.
     */
    fun connect(addresses: List<String>, portNumber: Int, code: String?) {
        val candidates = addresses.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (candidates.isEmpty()) {
            _status.value = LinkStatus(LinkState.ERROR, serverName, "No address in that QR code")
            return
        }
        hostQueue.clear()
        candidates.drop(1).forEach { hostQueue.add(it) }
        host = candidates.first()
        port = portNumber
        pendingCode = code
        pendingAddresses = candidates
        wantConnection = true
        reconnectJob?.cancel()
        reconnectAttempts = 0
        reconnectDelayMs = RECONNECT_MIN_MS
        open()
    }

    fun connectSaved() {
        val saved = store.hostsInOrder()
        if (saved.isEmpty()) return
        hostQueue.clear()
        saved.drop(1).forEach { hostQueue.add(it) }
        host = saved.first()
        port = store.port
        pendingCode = null
        pendingAddresses = saved
        wantConnection = true
        reconnectJob?.cancel()
        reconnectAttempts = 0
        reconnectDelayMs = RECONNECT_MIN_MS
        open()
    }

    @Synchronized
    private fun open() {
        // Keep the v0.1 socket lifecycle: OkHttp may dispatch callbacks as the
        // newWebSocket call returns, so clearing ws and identity-filtering those
        // callbacks can discard the real connection attempt.
        runCatching { ws?.cancel() }
        key = null
        ready = false
        supportsLiveProgress = false
        sendSeq = 0
        recvSeq = 0
        _status.value = LinkStatus(LinkState.CONNECTING)
        val urlHost = urlHostFor(host)
        Log.d(
            "LinkPhone",
            "connecting ws://$host:$port as $urlHost pair=${!pendingCode.isNullOrBlank()}"
        )
        val request = Request.Builder().url("ws://$urlHost:$port/").build()
        ws = http.newWebSocket(request, listener)
    }

    /**
     * OkHttp rejects zone IDs in IPv6 URLs. Map the PC's link-local address to
     * a synthetic hostname and resolve it to a scoped Inet6Address, so the
     * socket leaves through the phone's Wi-Fi interface.
     */
    private fun urlHostFor(address: String): String {
        if (!address.contains(':')) return address
        val plain = address.substringBefore('%')
        val parsed = runCatching { InetAddress.getByName(plain) }.getOrNull()
        if (parsed !is Inet6Address || !parsed.isLinkLocalAddress) return address
        val index = wifiInterfaceIndex() ?: return address
        val scoped = runCatching {
            Inet6Address.getByAddress(null, parsed.address, index)
        }.getOrNull() ?: return address
        val synthetic = "pc6-${Integer.toHexString(plain.hashCode())}.linkphone"
        if (ipv6Hosts.size > 16) ipv6Hosts.clear()
        ipv6Hosts[synthetic] = scoped
        Log.d("LinkPhone", "IPv6 $plain via interface index $index as $synthetic")
        return synthetic
    }

    private fun wifiInterfaceIndex(): Int? {
        return try {
            var fallback: Int? = null
            for (iface in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = Collections.list(iface.inetAddresses)
                if (addresses.none { it is Inet6Address && it.isLinkLocalAddress }) continue
                if (iface.name.startsWith("wlan") || iface.name.startsWith("ap")) {
                    return iface.index
                }
                if (fallback == null) fallback = iface.index
            }
            fallback
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun disconnect() {
        wantConnection = false
        reconnectJob?.cancel()
        hostQueue.clear()
        autoDiscovery.stop()
        runCatching { ws?.close(1000, "user") }
        ws = null
        key = null
        ready = false
        supportsLiveProgress = false
        PhoneRinger.stop()
        incoming.values.forEach { sink ->
            runCatching { sink.stream.close() }
            runCatching { resolver.delete(sink.uri, null, null) }
        }
        incoming.clear()
        outgoing.values.forEach { it.attempt?.complete(-1L) }
        outgoing.clear()
        _status.value = LinkStatus(LinkState.IDLE)
    }

    fun forget() {
        disconnect()
        store.clearPair()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val wantsPair = !pendingCode.isNullOrBlank()
            sendPlain(
                JSONObject().apply {
                    put("t", "hello")
                    put("proto", Proto.VERSION)
                    put("deviceId", store.deviceId)
                    put("deviceName", deviceName)
                    put("platform", "android")
                    if (wantsPair) put("pair", true)
                }
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { handleText(text) }
                .onFailure { Log.w("LinkPhone", "text frame", it) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            runCatching { handleBinary(bytes.toByteArray()) }
                .onFailure { Log.w("LinkPhone", "binary frame", it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("LinkPhone", "socket failure: ${t.message}")
            fail(t.message ?: "Connection lost")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // A close during the handshake used to be ignored, leaving the UI on
            // "Connecting…" forever. Only suppress it when we initiated it.
            if (wantConnection) fail(reason.ifBlank { "Disconnected ($code)" })
        }
    }

    private fun fail(detail: String) {
        ready = false
        key = null
        supportsLiveProgress = false
        PhoneRinger.stop()
        val interrupted = _transfers.value.map { transfer ->
            if (transfer.status == "active" || transfer.status == "pending") {
                transfer.copy(status = "interrupted")
            } else {
                transfer
            }
        }
        if (interrupted != _transfers.value) _transfers.value = interrupted
        val next = hostQueue.poll()
        if (wantConnection && next != null) {
            Log.d("LinkPhone", "first address failed, trying $next")
            host = next
            open()
            return
        }
        val help = if (detail.contains("Failed to connect", ignoreCase = true)) {
            "$detail. On Windows, set this Wi-Fi to Private and allow LinkPhone through the firewall."
        } else {
            detail
        }
        val paired = store.keyB64 != null
        _status.value = LinkStatus(
            LinkState.ERROR,
            serverName,
            if (paired) "$help Searching again automatically…" else help
        )
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        // Keep retrying a QR/code pairing while its code is still in memory as
        // well as normal saved-key reconnects. This lets a first connection
        // recover automatically after the user fixes Windows Firewall/Wi-Fi.
        if (!wantConnection || (store.keyB64 == null && pendingCode.isNullOrBlank())) return
        reconnectJob?.cancel()
        val waitMs = reconnectDelayMs
        reconnectAttempts += 1
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        reconnectJob = scope.launch {
            delay(waitMs)
            if (!wantConnection || ready) return@launch
            val paired = store.keyB64 != null
            if (paired && reconnectAttempts >= 2 &&
                System.currentTimeMillis() - lastDiscoveryAt >= DISCOVERY_INTERVAL_MS
            ) {
                rediscoverPaired()
            }
            if (wantConnection && !ready) open()
        }
    }

    /**
     * Finds the paired PC again after its address changed. Only a target whose
     * advertised server UUID matches the saved pairing is accepted; the usual
     * key handshake still authenticates the socket.
     */
    private suspend fun rediscoverPaired(): Discovery.Target? {
        val savedId = store.serverId
        if (savedId.isNullOrBlank() || rediscovering) return null
        rediscovering = true
        lastDiscoveryAt = System.currentTimeMillis()
        _status.value = LinkStatus(
            LinkState.CONNECTING,
            serverName.ifBlank { store.serverName.orEmpty() },
            "Looking for ${store.serverName ?: "your PC"}…"
        )
        return try {
            val found = CompletableDeferred<Discovery.Target?>()
            withContext(Dispatchers.Main) {
                autoDiscovery.start(
                    onFound = { target ->
                        if (target.serverId == savedId) found.complete(target)
                    },
                    onError = { found.complete(null) }
                )
            }
            val target = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) { found.await() }
            if (target != null) {
                host = target.host
                port = target.port
                store.updateEndpoint(target.host, target.port)
                Log.d("LinkPhone", "rediscovered ${target.name} at ${target.host}:${target.port}")
            }
            target
        } finally {
            autoDiscovery.stop()
            rediscovering = false
        }
    }

    /* ------------------------------------------------------------ */
    /* handshake                                                     */
    /* ------------------------------------------------------------ */

    private fun handleText(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "hello.result" -> onHelloResult(msg)
            "auth.challenge" -> onAuthChallenge(msg)
            "pair.ok" -> onPaired()
            "auth.ok" -> markConnected()
            "auth.reject" -> onAuthRejected(msg)
            "pair.reject" -> {
                wantConnection = false
                store.clearPair()
                _status.value = LinkStatus(
                    LinkState.ERROR,
                    serverName,
                    if (msg.optString("reason") == "code") "Wrong pairing code" else "Pairing rejected"
                )
            }
        }
    }

    private fun onHelloResult(msg: JSONObject) {
        serverName = msg.optString("serverName", "PC")
        pendingServerId = msg.optString("serverId")
        supportsLiveProgress = msg.optJSONArray("caps")?.let { caps ->
            (0 until caps.length()).any { caps.optString(it) == CAP_NOTIF_PROGRESS }
        } ?: false

        if (!msg.optBoolean("needPair")) {
            _status.value = LinkStatus(LinkState.CONNECTING, serverName, "Authenticating")
            return
        }

        val code = pendingCode
        if (code.isNullOrBlank()) {
            wantConnection = false
            _status.value = LinkStatus(
                LinkState.ERROR,
                serverName,
                "This PC no longer knows this phone. Scan the QR to pair again."
            )
            return
        }

        val salt = Base64.decode(msg.optString("salt"), Base64.NO_WRAP)
        val derived = Crypto.deriveKey(code, salt)
        key = derived
        sendSeq = 0
        recvSeq = 0
        _status.value = LinkStatus(LinkState.PAIRING, serverName, "Pairing")
        sendPlain(
            JSONObject().apply {
                put("t", "pair.request")
                put("deviceName", deviceName)
                put("platform", "android")
                put("proof", b64(Crypto.hmac(derived, "pair:${store.deviceId}")))
            }
        )
    }

    private fun onAuthChallenge(msg: JSONObject) {
        val nonce = msg.optString("nonce")
        val stored = store.key()
        if (stored == null) {
            wantConnection = false
            _status.value = LinkStatus(
                LinkState.ERROR,
                serverName,
                "Pairing is required. Scan the QR to pair again."
            )
            return
        }
        key = stored
        sendSeq = 0
        recvSeq = 0
        sendPlain(
            JSONObject().apply {
                put("t", "auth.response")
                put("proof", b64(Crypto.hmac(stored, "auth:$nonce")))
                put("deviceName", deviceName)
            }
        )
    }

    private fun onAuthRejected(msg: JSONObject) {
        val reason = msg.optString("reason")
        Log.w("LinkPhone", "auth rejected: ${reason.ifBlank { "unknown" }}")
        wantConnection = false
        pendingCode = null
        store.clearPair()
        _status.value = LinkStatus(
            LinkState.ERROR,
            serverName,
            if (reason == "bad-proof") {
                "The saved pairing is out of date. Scan the QR to pair again."
            } else {
                "This PC no longer knows this phone. Scan the QR to pair again."
            }
        )
    }

    private fun onPaired() {
        val current = key ?: return
        store.savePair(
            pendingServerId,
            serverName,
            host,
            port,
            b64(current),
            pendingAddresses
        )
        pendingCode = null
        markConnected()
    }

    private fun markConnected() {
        ready = true
        wantConnection = true
        hostQueue.clear()
        reconnectAttempts = 0
        reconnectDelayMs = RECONNECT_MIN_MS
        store.updateEndpoint(host, port)
        _status.value = LinkStatus(LinkState.CONNECTED, serverName)
        BatteryMonitor.current(appContext)?.let { battery ->
            sendBatteryStatus(battery.level, battery.charging)
        }
        CallHub.refreshDialerRole(appContext)
    }

    /* ------------------------------------------------------------ */
    /* frame codec                                                   */
    /* ------------------------------------------------------------ */

    /**
     * Frame emission is serialised: the clipboard poller, the notification listener
     * and the file pump all call in from different threads, and an out-of-order
     * sequence number would make the desktop drop the connection.
     */
    @Synchronized
    private fun sendPlain(msg: JSONObject) {
        ws?.send(msg.toString())
    }

    @Synchronized
    private fun send(msg: JSONObject): Boolean {
        val socket = ws ?: return false
        val currentKey = key
        if (currentKey == null) {
            return socket.send(msg.toString())
        }
        val body = msg.toString().toByteArray(Charsets.UTF_8)
        val plain = ByteArray(body.size + 1)
        plain[0] = Proto.KIND_CONTROL.toByte()
        System.arraycopy(body, 0, plain, 1, body.size)
        sendSeq += 1
        return socket.send(Crypto.seal(currentKey, "c2s", sendSeq, plain).toByteString())
    }

    private fun handleBinary(bytes: ByteArray) {
        val currentKey = key ?: return
        recvSeq += 1
        val plain = try {
            Crypto.open(currentKey, "s2c", recvSeq, bytes)
        } catch (e: Exception) {
            Log.w("LinkPhone", "bad frame", e)
            ws?.close(1002, "bad frame")
            return
        }
        if (plain.isEmpty()) return
        when (plain[0].toInt()) {
            Proto.KIND_CONTROL -> {
                val json = JSONObject(String(plain, 1, plain.size - 1, Charsets.UTF_8))
                handleControl(json)
            }
            Proto.KIND_FILE -> handleChunk(plain)
        }
    }

    private fun handleControl(msg: JSONObject) {
        when (msg.optString("t")) {
            "pair.ok" -> onPaired()
            "auth.ok" -> markConnected()
            "ping" -> send(JSONObject().apply { put("t", "pong"); put("ts", msg.optLong("ts")) })
            "clip.set" -> {
                val text = msg.optString("text")
                if (text.isNotEmpty()) clipboardIn.tryEmit(text)
            }
            "file.offer" -> onIncomingOffer(msg)
            "file.accept" -> {
                val id = msg.optString("id")
                outgoing[id]?.attempt?.complete(msg.optLong("offset", 0L))
            }
            "file.decline" -> outgoing[msg.optString("id")]?.attempt?.complete(-1L)
            "file.done" -> finishIncoming(msg.optString("id"))
            "file.cancel" -> {
                val id = msg.optString("id")
                outgoing[id]?.let {
                    it.cancelled = true
                    it.attempt?.complete(-1L)
                }
                abortIncoming(id, msg.optString("reason", "canceled by PC"))
            }
            "file.ack" -> {
                val out = outgoing[msg.optString("id")]
                if (out != null) {
                    val offset = msg.optLong("offset", 0L)
                    if (offset > out.acked) out.acked = offset
                }
            }
            "notif.action" -> NotificationRelay.deliver(
                msg.optString("key"),
                msg.optString("actionId"),
                msg.optString("replyText")
            )
            "notif.cancel" -> {
                val raw = msg.optJSONArray("keys")
                val keys = buildList {
                    if (raw != null) {
                        for (index in 0 until raw.length()) {
                            raw.optString(index)
                                .takeIf { it.isNotEmpty() }
                                ?.let { add(it) }
                        }
                    }
                }
                if (keys.isNotEmpty()) NotificationRelay.deliverCancel(keys)
            }
            "sms.threads.request" -> scope.launch { sendSmsThreads() }
            "sms.thread.request" -> {
                val threadId = msg.optString("threadId")
                if (threadId.isNotEmpty()) scope.launch { sendSmsThread(threadId) }
            }
            "sms.send" -> scope.launch { handleSmsSend(msg) }
            "gallery.albums.request" -> scope.launch { sendGalleryAlbums(msg.optString("kind", "image")) }
            "gallery.photos.request" -> scope.launch { sendGalleryPhotos(msg) }
            "gallery.thumbnails.request" -> scope.launch { sendGalleryThumbnails(msg) }
            "gallery.preview.request" -> scope.launch {
                sendGalleryPreview(
                    msg.optString("kind", "image"),
                    msg.optString("photoId")
                )
            }
            "gallery.download" -> scope.launch { downloadGalleryPhoto(msg) }
            "storage.list.request" -> scope.launch { sendStorageList(msg.optString("reqId")) }
            "storage.pull" -> scope.launch { sendStoredFile(msg.optString("id")) }
            "call.place" -> {
                val number = msg.optString("number")
                CallHub.placeCall(
                    appContext,
                    number,
                    msg.optString("accountId").ifBlank { null }
                ).onSuccess { sendCallPlaced(number) }
                    .onFailure { sendCallError(it.message ?: "Could not place call") }
            }
            "call.answer" -> CallHub.answer(appContext, msg.optString("callId"))
                .onFailure { sendCallError(it.message ?: "Could not answer the call") }
            "call.reject" -> CallHub.reject(appContext, msg.optString("callId"))
                .onFailure { sendCallError(it.message ?: "Could not decline the call") }
            "call.disconnect" -> CallHub.disconnect(appContext, msg.optString("callId"))
                .onFailure { sendCallError(it.message ?: "Could not hang up the call") }
            "call.speaker" -> CallHub.setSpeaker(appContext, msg.optBoolean("enabled"))
                .onFailure { sendCallError(it.message ?: "Could not switch the speaker") }
            "contacts.request" -> scope.launch { sendContacts() }
            "calllog.request" -> scope.launch { sendCallLog(msg.optInt("limit", 200)) }
            "sims.request" -> scope.launch { sendSims() }
            "phone.ring" -> PhoneRinger.ring(
                appContext,
                msg.optLong("durationMs", 15_000L)
            )
            "phone.ring.stop" -> PhoneRinger.stop()
            "device.rename" -> renameDevice(msg.optString("name"))
            "media.action" -> MediaWatcher.action(msg.optString("action"))
        }
    }

    /* ------------------------------------------------------------ */
    /* incoming files                                                */
    /* ------------------------------------------------------------ */

    private class Incoming(
        val id: String,
        val uri: Uri,
        val stream: OutputStream,
        val name: String,
        val size: Long
    ) {
        var received = 0L
        var lastAck = 0L
    }

    private class Outgoing(
        val id: String,
        val uri: Uri,
        val name: String,
        val size: Long,
        val mime: String,
        val target: String,
        val photoId: String?,
        val mediaKind: String?
    ) {
        @Volatile var acked = 0L
        @Volatile var attempt: CompletableDeferred<Long>? = null
        @Volatile var cancelled = false
    }

    private fun onIncomingOffer(msg: JSONObject) {
        val id = msg.optString("id")
        if (id.isEmpty()) return
        val name = msg.optString("name", "file")
        val size = msg.optLong("size", 0L)
        val mime = msg.optString("mime", "application/octet-stream")
        val target = msg.optString("target", "downloads")
        val resumeFrom = if (msg.has("resumeFrom")) msg.optLong("resumeFrom", 0L) else null
        val existing = incoming[id]
        if (existing != null) {
            if (resumeFrom != null && existing.name == name && existing.size == size) {
                upsertTransfer(TransferUi(id, name, size, existing.received, "in", "active"))
                send(
                    JSONObject().apply {
                        put("t", "file.accept")
                        put("id", id)
                        put("offset", existing.received)
                    }
                )
                return
            }
            abortIncoming(id, "restarted")
        }
        try {
            val pictures = target == "pictures" && mime.startsWith("image/")
            val videos = target == "videos" && mime.startsWith("video/")
            val collection = when {
                pictures -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                videos -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val relativePath = when {
                pictures -> "Pictures/LinkPhone"
                videos -> "Movies/LinkPhone"
                else -> "Download/LinkPhone"
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("no media store entry")
            val stream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("no output stream")
            incoming[id] = Incoming(id, uri, stream, name, size)
            upsertTransfer(TransferUi(id, name, size, 0, "in", "active", target))
            send(JSONObject().apply { put("t", "file.accept"); put("id", id); put("offset", 0) })
        } catch (e: Exception) {
            Log.w("LinkPhone", "cannot accept file", e)
            send(
                JSONObject().apply {
                    put("t", "file.decline")
                    put("id", id)
                    put("reason", e.message ?: "io")
                }
            )
        }
    }

    private fun handleChunk(payload: ByteArray) {
        if (payload.size < 2 + 8) return
        val idLength = payload[1].toInt() and 0xff
        if (payload.size < 2 + idLength + 8) return
        val id = String(payload, 2, idLength, Charsets.UTF_8)
        val offset = ByteBuffer.wrap(payload, 2 + idLength, 8).long
        val data = payload.copyOfRange(2 + idLength + 8, payload.size)
        val sink = incoming[id] ?: return
        if (offset != sink.received) return
        try {
            sink.stream.write(data)
            sink.received += data.size
            if (sink.received - sink.lastAck >= 512 * 1024 || sink.received == sink.size) {
                sink.lastAck = sink.received
                send(
                    JSONObject().apply {
                        put("t", "file.ack")
                        put("id", id)
                        put("offset", sink.received)
                    }
                )
                upsertTransfer(TransferUi(id, sink.name, sink.size, sink.received, "in", "active"))
            }
        } catch (e: Exception) {
            Log.w("LinkPhone", "write chunk", e)
            abortIncoming(id, "write failed")
        }
    }

    private fun finishIncoming(id: String) {
        val sink = incoming[id] ?: return
        if (sink.received != sink.size) {
            abortIncoming(id, "incomplete")
            return
        }
        incoming.remove(id)
        try {
            sink.stream.flush()
            sink.stream.close()
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(sink.uri, values, null, null)
            upsertTransfer(TransferUi(id, sink.name, sink.size, sink.received, "in", "done"))
            send(
                JSONObject().apply {
                    put("t", "file.ack")
                    put("id", id)
                    put("offset", sink.received)
                }
            )
        } catch (e: Exception) {
            upsertTransfer(TransferUi(id, sink.name, sink.size, sink.received, "in", "failed"))
        }
    }

    private fun abortIncoming(id: String, reason: String = "canceled") {
        val sink = incoming.remove(id) ?: return
        runCatching { sink.stream.close() }
        runCatching { resolver.delete(sink.uri, null, null) }
        upsertTransfer(TransferUi(id, sink.name, sink.size, sink.received, "in", "failed"))
        send(
            JSONObject().apply {
                put("t", "file.cancel")
                put("id", id)
                put("reason", reason)
            }
        )
    }

    /* ------------------------------------------------------------ */
    /* outgoing files                                                */
    /* ------------------------------------------------------------ */

    fun sendFile(
        uri: Uri,
        target: String = "downloads",
        photoId: String? = null,
        mediaKind: String? = null
    ) {
        scope.launch {
            val out = prepareSend(uri, target, photoId, mediaKind) ?: return@launch
            startSend(out, 0L)
        }
    }

    suspend fun sendFileAndWait(
        uri: Uri,
        target: String = "downloads",
        photoId: String? = null,
        mediaKind: String? = null
    ): Boolean {
        val out = prepareSend(uri, target, photoId, mediaKind) ?: return false
        return startSend(out, 0L)
    }

    fun retryTransfer(id: String) {
        val out = outgoing[id] ?: return
        if (!ready || out.attempt?.isActive == true) return
        scope.launch { startSend(out, out.acked) }
    }

    private fun prepareSend(
        uri: Uri,
        target: String,
        photoId: String?,
        mediaKind: String?
    ): Outgoing? = try {
        val (name, size) = queryMeta(uri)
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val out = Outgoing(
            UUID.randomUUID().toString(),
            uri,
            name,
            size,
            mime,
            target,
            photoId,
            mediaKind
        )
        outgoing[out.id] = out
        out
    } catch (e: Exception) {
        Log.w("LinkPhone", "prepare file", e)
        null
    }

    private suspend fun startSend(out: Outgoing, resumeFrom: Long): Boolean {
        val attempt = CompletableDeferred<Long>()
        out.attempt = attempt
        out.cancelled = false
        upsertTransfer(
            TransferUi(out.id, out.name, out.size, resumeFrom, "out", "pending", out.target)
        )
        val offer = JSONObject().apply {
            put("t", "file.offer")
            put("id", out.id)
            put("name", out.name)
            put("size", out.size)
            put("mime", out.mime)
            put("target", out.target)
            if (resumeFrom > 0) put("resumeFrom", resumeFrom)
            if (!out.photoId.isNullOrEmpty()) put("photoId", out.photoId)
            if (!out.mediaKind.isNullOrEmpty()) put("mediaKind", out.mediaKind)
        }
        if (!send(offer)) {
            upsertTransfer(
                TransferUi(out.id, out.name, out.size, out.acked, "out", "interrupted", out.target)
            )
            return false
        }

        val accepted = withTimeoutOrNull(15_000) { attempt.await() } ?: -2L
        if (accepted < 0) {
            val declined = accepted == -1L
            upsertTransfer(
                TransferUi(
                    out.id,
                    out.name,
                    out.size,
                    out.acked,
                    "out",
                    if (declined) "failed" else "interrupted",
                    out.target
                )
            )
            if (declined) outgoing.remove(out.id)
            return false
        }

        val start = accepted.coerceIn(0L, out.size)
        out.acked = start
        upsertTransfer(
            TransferUi(out.id, out.name, out.size, start, "out", "active", out.target)
        )
        try {
            val input = resolver.openInputStream(out.uri)
                ?: throw IllegalStateException("cannot read file")
            input.use { stream ->
                var skipped = 0L
                while (skipped < start) {
                    val step = stream.skip(start - skipped)
                    if (step <= 0) {
                        if (stream.read() < 0) throw IllegalStateException("file changed")
                        skipped += 1
                    } else {
                        skipped += step
                    }
                }
                var offset = start
                var lastReport = start
                val buffer = ByteArray(Proto.CHUNK)
                while (true) {
                    if (out.cancelled) throw IllegalStateException("canceled")
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    if (!sendChunk(out.id, offset, buffer, read)) {
                        throw IllegalStateException("connection lost")
                    }
                    offset += read
                    while ((ws?.queueSize() ?: 0L) > 2L * 1024 * 1024) {
                        if (!ready) throw IllegalStateException("connection lost")
                        delay(8)
                    }
                    while (offset - out.acked >= 512L * 1024) {
                        if (!ready) throw IllegalStateException("connection lost")
                        delay(50)
                    }
                    if (offset - lastReport >= 512 * 1024 || offset == out.size) {
                        lastReport = offset
                        upsertTransfer(
                            TransferUi(out.id, out.name, out.size, offset, "out", "active", out.target)
                        )
                    }
                }

                val deadline = System.currentTimeMillis() + 15_000
                while (out.acked < out.size && System.currentTimeMillis() < deadline) {
                    if (!ready) throw IllegalStateException("connection lost")
                    delay(100)
                }
                if (out.acked < out.size) throw IllegalStateException("phone did not confirm")
                send(JSONObject().apply { put("t", "file.done"); put("id", out.id) })
                upsertTransfer(
                    TransferUi(out.id, out.name, out.size, out.size, "out", "done", out.target)
                )
                outgoing.remove(out.id)
                return true
            }
        } catch (e: Exception) {
            val message = e.message ?: "send failed"
            val interrupted = message == "connection lost" || message == "canceled"
            upsertTransfer(
                TransferUi(
                    out.id,
                    out.name,
                    out.size,
                    out.acked,
                    "out",
                    if (interrupted) "interrupted" else "failed",
                    out.target
                )
            )
            if (!interrupted) {
                send(
                    JSONObject().apply {
                        put("t", "file.cancel")
                        put("id", out.id)
                        put("reason", message)
                    }
                )
            }
            return false
        }
    }

    private fun sendStorageList(reqId: String) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
        val entries = JSONArray()
        if (granted) {
            runCatching {
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.SIZE,
                    MediaStore.Downloads.MIME_TYPE,
                    MediaStore.Downloads.DATE_MODIFIED
                )
                resolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Downloads.IS_PENDING} = 0",
                    null,
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                    val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                    val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_MODIFIED)
                    while (cursor.moveToNext() && entries.length() < 100) {
                        entries.put(
                            JSONObject().apply {
                                put("id", cursor.getLong(idIndex).toString())
                                put("name", cursor.getString(nameIndex) ?: "file")
                                put("size", cursor.getLong(sizeIndex))
                                put(
                                    "mime",
                                    cursor.getString(mimeIndex) ?: "application/octet-stream"
                                )
                                put("modified", cursor.getLong(dateIndex) * 1000L)
                            }
                        )
                    }
                }
            }.onFailure { Log.w("LinkPhone", "storage list", it) }
        }
        send(
            JSONObject().apply {
                put("t", "storage.list")
                put("reqId", reqId)
                put("entries", entries)
                put("scanLimited", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                put("granted", granted)
            }
        )
    }

    private fun sendStoredFile(id: String) {
        val numeric = id.toLongOrNull() ?: return
        val uri = ContentUris.withAppendedId(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            numeric
        )
        sendFile(uri, "downloads")
    }

    @Synchronized
    private fun sendChunk(id: String, offset: Long, buffer: ByteArray, length: Int): Boolean {
        val currentKey = key ?: return false
        val socket = ws ?: return false
        if (!ready) return false
        val idBytes = id.toByteArray(Charsets.UTF_8)
        val plain = ByteArray(2 + idBytes.size + 8 + length)
        plain[0] = Proto.KIND_FILE.toByte()
        plain[1] = idBytes.size.toByte()
        System.arraycopy(idBytes, 0, plain, 2, idBytes.size)
        System.arraycopy(
            ByteBuffer.allocate(8).putLong(offset).array(),
            0,
            plain,
            2 + idBytes.size,
            8
        )
        System.arraycopy(buffer, 0, plain, 2 + idBytes.size + 8, length)
        sendSeq += 1
        return socket.send(Crypto.seal(currentKey, "c2s", sendSeq, plain).toByteString())
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null && file.exists()) {
                return (file.name.ifBlank { "file" }) to file.length()
            }
        }
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        return name to size
    }

    private fun upsertTransfer(item: TransferUi) {
        val current = _transfers.value
        val index = current.indexOfFirst { it.id == item.id }
        _transfers.value = if (index >= 0) {
            current.toMutableList().also { it[index] = item }
        } else {
            (listOf(item) + current).take(40)
        }
    }

    /* ------------------------------------------------------------ */
    /* outbound messages                                             */
    /* ------------------------------------------------------------ */

    fun sendNotification(notification: OutgoingNotification) {
        if (!ready) return
        val actions = JSONArray()
        notification.actions.forEach { entry ->
            actions.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("label", entry.label)
                    put("reply", entry.reply)
                }
            )
        }
        send(
            JSONObject().apply {
                put("t", "notif.post")
                put(
                    "notif",
                    JSONObject().apply {
                        put("key", notification.key)
                        put("app", notification.app)
                        put("pkg", notification.pkg)
                        put("title", notification.title)
                        put("text", notification.text)
                        put("when", notification.whenTs)
                        put("canReply", notification.canReply)
                        put("actions", actions)
                        notification.progress?.let { put("progress", it) }
                        notification.progressMax?.let { put("progressMax", it) }
                        put("indeterminate", notification.indeterminate)
                        put("ongoing", notification.ongoing)
                    }
                )
            }
        )
    }

    fun sendNotificationDismissed(key: String) {
        if (!ready) return
        send(JSONObject().apply { put("t", "notif.dismiss"); put("key", key) })
    }

    fun sendClipboard(text: String) {
        if (!ready) return
        send(
            JSONObject().apply {
                put("t", "clip.set")
                put("text", text)
                put("ts", System.currentTimeMillis())
            }
        )
    }

    fun sendBatteryStatus(level: Int, charging: Boolean) {
        if (!ready) return
        send(
            JSONObject().apply {
                put("t", "device.status")
                put("batteryLevel", level.coerceIn(0, 100))
                put("charging", charging)
            }
        )
    }

    fun sendCallState(snapshot: PhoneCallSnapshot) {
        if (!ready) return
        val calls = JSONArray(snapshot.calls.map { call ->
            JSONObject().apply {
                put("id", call.id)
                put("number", call.number)
                put("displayName", call.displayName)
                put("phase", call.phase.name.lowercase())
                put("incoming", call.incoming)
                put("startedAt", call.startedAt)
                call.simLabel?.takeIf { it.isNotBlank() }?.let { put("simLabel", it) }
            }
        })
        send(
            JSONObject().apply {
                put("t", "call.state")
                put("calls", calls)
                put("dialerReady", snapshot.dialerReady)
                put("outgoingReady", snapshot.outgoingReady)
                put("controlReady", snapshot.controlReady)
                put("speaker", snapshot.speaker)
                put("muted", snapshot.muted)
            }
        )
    }

    private fun sendCallError(detail: String) {
        send(JSONObject().apply { put("t", "call.error"); put("error", detail) })
    }

    fun sendCallPlaced(number: String) {
        if (!ready) return
        send(JSONObject().apply { put("t", "call.placed"); put("number", number) })
    }

    fun sendMediaState(title: String, artist: String, playing: Boolean, app: String) {
        if (!ready) return
        send(
            JSONObject().apply {
                put("t", "media.state")
                put("title", title)
                put("artist", artist)
                put("playing", playing)
                put("app", app)
            }
        )
    }

    private fun sendRingState(ringing: Boolean) {
        if (!ready) return
        send(JSONObject().apply { put("t", "phone.ring.state"); put("ringing", ringing) })
    }

    /* ------------------------------------------------------------ */
    /* Contacts and call history                                     */
    /* ------------------------------------------------------------ */

    fun refreshContacts() {
        scope.launch { sendContacts() }
    }

    private fun sendContacts() {
        val repo = ContactsRepository.get(appContext)
        val granted = repo.hasPermission()
        val entries = if (granted) repo.list() else emptyList()
        val contacts = JSONArray(entries.map { contact ->
            JSONObject().apply {
                put("id", contact.id)
                put("name", contact.name)
                put("numbers", JSONArray(contact.numbers.map { number ->
                    JSONObject().apply {
                        put("number", number.number)
                        put("label", number.label)
                    }
                }))
            }
        })
        send(
            JSONObject().apply {
                put("t", "contacts.sync")
                put("contacts", contacts)
                put("granted", granted)
            }
        )
    }

    private fun sendCallLog(limit: Int) {
        val repo = CallLogRepository.get(appContext)
        val granted = repo.hasPermission()
        val entries = if (granted) repo.list(limit.coerceIn(1, 200)) else emptyList()
        val callLog = JSONArray(entries.map { entry ->
            JSONObject().apply {
                put("id", entry.id)
                put("number", entry.number)
                put("name", entry.name)
                put("type", entry.type)
                put("date", entry.date)
                put("duration", entry.duration)
                entry.simLabel.takeIf { it.isNotBlank() }?.let { put("simLabel", it) }
            }
        })
        send(
            JSONObject().apply {
                put("t", "calllog.sync")
                put("entries", callLog)
                put("granted", granted)
            }
        )
    }

    /* ------------------------------------------------------------ */
    /* SIMs                                                          */
    /* ------------------------------------------------------------ */

    private fun sendSims() {
        sims.reload()
        val smsList = JSONArray(sims.smsSims().map { sim ->
            JSONObject().apply {
                put("id", sim.id)
                put("label", sim.label)
                put("carrier", sim.carrier)
                put("slot", sim.slot)
                put("isDefault", sim.isDefault)
            }
        })
        val callList = JSONArray(sims.callAccounts().map { account ->
            JSONObject().apply {
                put("id", account.id)
                put("label", account.label)
                put("isDefault", account.isDefault)
            }
        })
        send(
            JSONObject().apply {
                put("t", "sims.sync")
                put("sms", smsList)
                put("call", callList)
                put("granted", sims.hasPermission())
            }
        )
    }

    /* ------------------------------------------------------------ */
    /* SMS                                                           */
    /* ------------------------------------------------------------ */

    fun sendSmsIncoming(message: SmsRepository.Message) {
        if (!ready) return
        send(
            JSONObject().apply {
                put("t", "sms.new")
                put("message", message.toJson())
            }
        )
    }

    private fun sendSmsThreads() {
        if (!sms.hasPermission()) {
            sendSmsError("threads", "SMS permission not granted on the phone")
            return
        }
        val threads = sms.threads().map { thread ->
            JSONObject().apply {
                put("threadId", thread.threadId)
                put("address", thread.address)
                put("name", thread.name)
                put("snippet", thread.snippet)
                put("date", thread.date)
                put("count", thread.count)
                thread.subscriptionId?.let { subId ->
                    put("subscriptionId", subId)
                    sims.labelForSubscription(subId)?.let { put("simLabel", it) }
                }
            }
        }
        send(
            JSONObject().apply {
                put("t", "sms.threads")
                put("threads", JSONArray(threads))
            }
        )
    }

    private fun sendSmsThread(threadId: String) {
        if (!sms.hasPermission()) {
            sendSmsError("thread", "SMS permission not granted on the phone")
            return
        }
        val messages = sms.messages(threadId)
        val address = messages.lastOrNull()?.address
            ?: if (threadId.startsWith(SmsRepository.ADDRESS_PREFIX)) {
                threadId.removePrefix(SmsRepository.ADDRESS_PREFIX)
            } else {
                ""
            }
        send(
            JSONObject().apply {
                put("t", "sms.thread")
                put("threadId", threadId)
                put("address", address)
                put("name", sms.contactName(address))
                put(
                    "messages",
                    JSONArray(messages.map { it.toJson() })
                )
            }
        )
    }

    private fun handleSmsSend(msg: JSONObject) {
        val tempId = msg.optString("tempId")
        val address = msg.optString("address")
        val body = msg.optString("body")
        if (address.isEmpty() || body.isEmpty()) {
            sendSmsResult(tempId, false, "Nothing to send")
            return
        }
        if (!sms.hasPermission()) {
            sendSmsResult(tempId, false, "SMS permission not granted on the phone")
            return
        }
        val subscriptionId = msg.optInt("subscriptionId", -1).takeIf { it >= 0 }
        if (subscriptionId != null) {
            if (!sims.hasPermission() ||
                sims.smsSims().none { it.id == subscriptionId.toString() }
            ) {
                sendSmsResult(tempId, false, "That SIM is no longer available")
                return
            }
        }
        val result = sms.send(address, body, subscriptionId)
        if (result.isFailure) Log.w("LinkPhone", "sms send failed", result.exceptionOrNull())
        sendSmsResult(tempId, result.isSuccess, result.exceptionOrNull()?.message)
    }

    private fun sendSmsResult(tempId: String, ok: Boolean, error: String?) {
        send(
            JSONObject().apply {
                put("t", "sms.sent")
                put("tempId", tempId)
                put("ok", ok)
                error?.let { put("error", it) }
            }
        )
    }

    private fun sendSmsError(op: String, detail: String) {
        send(
            JSONObject().apply {
                put("t", "sms.error")
                put("op", op)
                put("error", detail)
            }
        )
    }

    private fun SmsRepository.Message.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("threadId", threadId)
        put("address", address)
        put("name", name)
        put("body", body)
        put("date", date)
        put("outgoing", outgoing)
        put("failed", failed)
        subscriptionId?.let { subId ->
            put("subscriptionId", subId)
            sims.labelForSubscription(subId)?.let { put("simLabel", it) }
        }
    }

    /* ------------------------------------------------------------ */
    /* Gallery                                                       */
    /* ------------------------------------------------------------ */

    private fun kindOf(value: String): MediaRepository.Kind =
        if (value == "video") MediaRepository.Kind.VIDEO else MediaRepository.Kind.IMAGE

    private fun sendGalleryAlbums(kindName: String) {
        val kind = kindOf(kindName)
        val kinds = JSONArray(listOf("image", "video"))
        if (!media.hasPermission(kind)) {
            send(
                JSONObject().apply {
                    put("t", "gallery.albums")
                    put("albums", JSONArray())
                    put("kinds", kinds)
                    put("granted", false)
                    put("partial", false)
                }
            )
            return
        }
        runCatching {
            JSONArray(media.albums(kind).map { album ->
                JSONObject().apply {
                    put("id", album.id)
                    put("name", album.name)
                    put("count", album.count)
                    put("coverPhotoId", album.coverPhotoId)
                    put("kind", kindName)
                }
            })
        }.onSuccess { albums ->
            send(
                JSONObject().apply {
                    put("t", "gallery.albums")
                    put("albums", albums)
                    put("kinds", kinds)
                    put("granted", media.hasFullAccess(kind))
                    put("partial", media.isPartial(kind))
                }
            )
        }.onFailure { error ->
            Log.w("LinkPhone", "gallery albums", error)
            sendGalleryError("albums", error.message ?: "Could not read albums")
        }
    }

    private fun sendGalleryPhotos(msg: JSONObject) {
        val kindName = msg.optString("kind", "image")
        val kind = kindOf(kindName)
        if (!media.hasPermission(kind)) {
            sendGalleryError("photos", "Photo/video permission not granted on the phone")
            return
        }
        val albumId = msg.optString("albumId")
        val offset = msg.optInt("offset", 0).coerceAtLeast(0)
        val limit = msg.optInt("limit", 48).coerceIn(1, 60)
        runCatching { media.photos(kind, albumId, offset, limit) }
            .onSuccess { page ->
                val photos = JSONArray(page.photos.map { it.toJson() })
                send(
                    JSONObject().apply {
                        put("t", "gallery.photos")
                        put("albumId", albumId)
                        put("offset", offset)
                        put("photos", photos)
                        put("hasMore", page.hasMore)
                        put("kind", kindName)
                    }
                )
            }
            .onFailure { error ->
                Log.w("LinkPhone", "gallery photos", error)
                sendGalleryError("photos", error.message ?: "Could not read media")
            }
    }

    private fun sendGalleryThumbnails(msg: JSONObject) {
        val kindName = msg.optString("kind", "image")
        val kind = kindOf(kindName)
        if (!media.hasPermission(kind)) {
            sendGalleryError("thumbnails", "Photo/video permission not granted on the phone")
            return
        }
        val ids = msg.optJSONArray("photoIds") ?: JSONArray()
        val items = JSONArray()
        for (index in 0 until minOf(ids.length(), 24)) {
            val photoId = ids.optString(index)
            if (photoId.isEmpty()) continue
            runCatching { media.thumbnail(kind, photoId) }.getOrNull()?.let { image ->
                items.put(image.toJson(photoId))
            }
        }
        send(JSONObject().apply { put("t", "gallery.thumbnails"); put("items", items); put("kind", kindName) })
    }

    private fun sendGalleryPreview(kindName: String, photoId: String) {
        val kind = kindOf(kindName)
        if (!media.hasPermission(kind)) {
            sendGalleryError("preview", "Photo/video permission not granted on the phone")
            return
        }
        val image = runCatching { media.preview(kind, photoId) }
            .onFailure { Log.w("LinkPhone", "gallery preview", it) }
            .getOrNull()
        if (image == null) {
            sendGalleryError("preview", "Could not load that media")
            return
        }
        send(
            JSONObject().apply {
                put("t", "gallery.preview")
                put("photoId", photoId)
                put("mime", image.mime)
                put("data", b64(image.bytes))
            }
        )
    }

    private fun downloadGalleryPhoto(msg: JSONObject) {
        val kindName = msg.optString("kind", "image")
        val kind = kindOf(kindName)
        val photoId = msg.optString("photoId")
        if (!media.hasPermission(kind)) {
            sendGalleryError("download", "Photo/video permission not granted on the phone")
            return
        }
        val uri = media.uri(kind, photoId)
        if (uri == null) {
            sendGalleryError("download", "That item no longer exists")
            return
        }
        sendFile(uri, "downloads", photoId, kindName)
    }

    private fun sendGalleryError(op: String, detail: String) {
        send(
            JSONObject().apply {
                put("t", "gallery.error")
                put("op", op)
                put("error", detail)
            }
        )
    }

    private fun MediaRepository.Photo.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("albumId", albumId)
        put("name", name)
        put("mime", mime)
        put("size", size)
        put("dateTaken", dateTaken)
        put("width", width)
        put("height", height)
        put("kind", if (kind == MediaRepository.Kind.VIDEO) "video" else "image")
        if (kind == MediaRepository.Kind.VIDEO) put("durationMs", durationMs)
    }

    private fun MediaRepository.EncodedImage.toJson(photoId: String): JSONObject =
        JSONObject().apply {
            put("photoId", photoId)
            put("mime", mime)
            put("data", b64(bytes))
        }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
