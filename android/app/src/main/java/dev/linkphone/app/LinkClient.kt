package dev.linkphone.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
    val status: String
)

data class OutgoingNotification(
    val key: String,
    val app: String,
    val pkg: String,
    val title: String,
    val text: String,
    val whenTs: Long,
    val canReply: Boolean,
    val actions: List<Pair<String, Boolean>>
)

/**
 * One WebSocket session with the desktop. Owns the pairing handshake, the
 * encrypted frame codec and both directions of file transfer.
 */
class LinkClient(context: Context, private val store: PairStore) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val sms = SmsRepository(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _status = MutableStateFlow(LinkStatus(LinkState.IDLE))
    val status: StateFlow<LinkStatus> = _status.asStateFlow()

    private val _transfers = MutableStateFlow<List<TransferUi>>(emptyList())
    val transfers: StateFlow<List<TransferUi>> = _transfers.asStateFlow()

    val clipboardIn = MutableSharedFlow<String>(extraBufferCapacity = 16)

    @Volatile private var ws: WebSocket? = null
    @Volatile private var key: ByteArray? = null
    @Volatile private var ready = false
    @Volatile private var wantConnection = false
    private var sendSeq = 0L
    private var recvSeq = 0L

    private var pendingCode: String? = null
    private var pendingServerId: String = ""
    private var serverName: String = ""
    private var host = ""
    private var port = 8765
    private var reconnectJob: Job? = null

    /** Extra addresses from a QR payload, tried in order when the first one fails. */
    private val hostQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val pendingSends = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    val isReady: Boolean get() = ready

    val deviceName: String
        get() = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .ifBlank { "Android phone" }
            .replaceFirstChar { it.uppercase() }

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
        wantConnection = true
        open()
    }

    fun connectSaved() {
        val savedHost = store.host ?: return
        hostQueue.clear()
        host = savedHost
        port = store.port
        pendingCode = null
        wantConnection = true
        open()
    }

    @Synchronized
    private fun open() {
        val previous = ws
        ws = null
        runCatching { previous?.cancel() }
        key = null
        ready = false
        sendSeq = 0
        recvSeq = 0
        _status.value = LinkStatus(LinkState.CONNECTING)
        val request = Request.Builder().url("ws://$host:$port/").build()
        ws = http.newWebSocket(request, listener)
    }

    @Synchronized
    fun disconnect() {
        wantConnection = false
        reconnectJob?.cancel()
        hostQueue.clear()
        val current = ws
        ws = null
        runCatching { current?.close(1000, "user") }
        key = null
        ready = false
        _status.value = LinkStatus(LinkState.IDLE)
    }

    fun forget() {
        disconnect()
        store.clearPair()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) return
            sendPlain(
                JSONObject().apply {
                    put("t", "hello")
                    put("proto", Proto.VERSION)
                    put("deviceId", store.deviceId)
                    put("deviceName", deviceName)
                    put("platform", "android")
                }
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            runCatching { handleText(text) }
                .onFailure { Log.w("LinkPhone", "text frame", it) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            if (webSocket !== ws) return
            runCatching { handleBinary(bytes.toByteArray()) }
                .onFailure { Log.w("LinkPhone", "binary frame", it) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            Log.w("LinkPhone", "socket failure: ${t.message}")
            fail(t.message ?: "Connection lost")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            if (wantConnection) fail(reason.ifBlank { "Disconnected" })
        }
    }

    private fun fail(detail: String) {
        ready = false
        key = null
        val next = hostQueue.poll()
        if (wantConnection && next != null) {
            Log.d("LinkPhone", "first address failed, trying $next")
            host = next
            open()
            return
        }
        _status.value = LinkStatus(LinkState.ERROR, serverName, detail)
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (!wantConnection || store.keyB64 == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2500)
            if (wantConnection && !ready) open()
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

        if (!msg.optBoolean("needPair")) {
            _status.value = LinkStatus(LinkState.CONNECTING, serverName, "Authenticating")
            return
        }

        val code = pendingCode
        if (code.isNullOrBlank()) {
            wantConnection = false
            _status.value = LinkStatus(LinkState.ERROR, serverName, "Pairing code required")
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
            _status.value = LinkStatus(LinkState.ERROR, serverName, "Not paired")
            return
        }
        key = stored
        sendSeq = 0
        recvSeq = 0
        sendPlain(
            JSONObject().apply {
                put("t", "auth.response")
                put("proof", b64(Crypto.hmac(stored, "auth:$nonce")))
            }
        )
    }

    private fun onPaired() {
        val current = key ?: return
        store.savePair(pendingServerId, serverName, host, port, b64(current))
        markConnected()
    }

    private fun markConnected() {
        ready = true
        wantConnection = true
        hostQueue.clear()
        _status.value = LinkStatus(LinkState.CONNECTED, serverName)
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
    private fun send(msg: JSONObject) {
        val socket = ws ?: return
        val currentKey = key
        if (currentKey == null) {
            socket.send(msg.toString())
            return
        }
        val body = msg.toString().toByteArray(Charsets.UTF_8)
        val plain = ByteArray(body.size + 1)
        plain[0] = Proto.KIND_CONTROL.toByte()
        System.arraycopy(body, 0, plain, 1, body.size)
        sendSeq += 1
        socket.send(Crypto.seal(currentKey, "c2s", sendSeq, plain).toByteString())
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
            "file.accept" -> pendingSends.remove(msg.optString("id"))?.complete(true)
            "file.decline" -> pendingSends.remove(msg.optString("id"))?.complete(false)
            "file.done" -> finishIncoming(msg.optString("id"))
            "file.cancel" -> abortIncoming(msg.optString("id"))
            "notif.action" -> NotificationRelay.deliver(
                msg.optString("key"),
                msg.optString("actionId"),
                msg.optString("replyText")
            )
            "sms.threads.request" -> scope.launch { sendSmsThreads() }
            "sms.thread.request" -> {
                val threadId = msg.optString("threadId")
                if (threadId.isNotEmpty()) scope.launch { sendSmsThread(threadId) }
            }
            "sms.send" -> scope.launch { handleSmsSend(msg) }
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

    private fun onIncomingOffer(msg: JSONObject) {
        val id = msg.optString("id")
        val name = msg.optString("name", "file")
        val size = msg.optLong("size", 0L)
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/LinkPhone")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("no media store entry")
            val stream = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("no output stream")
            incoming[id] = Incoming(id, uri, stream, name, size)
            upsertTransfer(TransferUi(id, name, size, 0, "in", "active"))
            send(JSONObject().apply { put("t", "file.accept"); put("id", id) })
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
            if (sink.received - sink.lastAck >= 512 * 1024) {
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
            abortIncoming(id)
        }
    }

    private fun finishIncoming(id: String) {
        val sink = incoming.remove(id) ?: return
        try {
            sink.stream.flush()
            sink.stream.close()
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(sink.uri, values, null, null)
            upsertTransfer(TransferUi(id, sink.name, sink.size, sink.received, "in", "done"))
        } catch (e: Exception) {
            upsertTransfer(TransferUi(id, sink.name, sink.size, sink.received, "in", "failed"))
        }
    }

    private fun abortIncoming(id: String) {
        val sink = incoming.remove(id) ?: return
        runCatching { sink.stream.close() }
        runCatching { resolver.delete(sink.uri, null, null) }
        upsertTransfer(TransferUi(id, sink.name, sink.size, sink.received, "in", "failed"))
    }

    /* ------------------------------------------------------------ */
    /* outgoing files                                                */
    /* ------------------------------------------------------------ */

    fun sendFile(uri: Uri) {
        scope.launch {
            try {
                val (name, size) = queryMeta(uri)
                val id = UUID.randomUUID().toString()
                val accepted = CompletableDeferred<Boolean>()
                pendingSends[id] = accepted
                upsertTransfer(TransferUi(id, name, size, 0, "out", "pending"))

                send(
                    JSONObject().apply {
                        put("t", "file.offer")
                        put("id", id)
                        put("name", name)
                        put("size", size)
                        put("mime", "application/octet-stream")
                    }
                )

                val ok = withTimeoutOrNull(15_000) { accepted.await() } ?: false
                if (!ok) {
                    pendingSends.remove(id)
                    upsertTransfer(TransferUi(id, name, size, 0, "out", "failed"))
                    return@launch
                }

                upsertTransfer(TransferUi(id, name, size, 0, "out", "active"))
                var offset = 0L
                var lastReport = 0L
                resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(Proto.CHUNK)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sendChunk(id, offset, buffer, read)
                        offset += read
                        while ((ws?.queueSize() ?: 0L) > 2L * 1024 * 1024) delay(8)
                        if (offset - lastReport >= 512 * 1024) {
                            lastReport = offset
                            upsertTransfer(TransferUi(id, name, size, offset, "out", "active"))
                        }
                    }
                }
                send(JSONObject().apply { put("t", "file.done"); put("id", id) })
                upsertTransfer(TransferUi(id, name, size, offset, "out", "done"))
            } catch (e: Exception) {
                Log.w("LinkPhone", "send file", e)
            }
        }
    }

    @Synchronized
    private fun sendChunk(id: String, offset: Long, buffer: ByteArray, length: Int) {
        val currentKey = key ?: return
        val socket = ws ?: return
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
        socket.send(Crypto.seal(currentKey, "c2s", sendSeq, plain).toByteString())
    }

    private fun queryMeta(uri: Uri): Pair<String, Long> {
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
        notification.actions.forEachIndexed { index, entry ->
            actions.put(
                JSONObject().apply {
                    put("id", index.toString())
                    put("label", entry.first)
                    put("reply", entry.second)
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
                put("snippet", thread.snippet)
                put("date", thread.date)
                put("count", thread.count)
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
        val result = sms.send(address, body)
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
        put("body", body)
        put("date", date)
        put("outgoing", outgoing)
        put("failed", failed)
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
