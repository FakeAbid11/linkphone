package dev.linkphone.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class ShareState(
    val total: Int = 0,
    val done: Int = 0,
    val names: List<String> = emptyList(),
    val running: Boolean = false,
    val waiting: Boolean = false,
    val message: String = ""
)

/** Copies shared URIs to cache and sends them through the normal file pipeline. */
object ShareSender {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()

    fun enqueue(context: Context, uris: List<Uri>) {
        val app = context.applicationContext
        scope.launch {
            val files = mutableListOf<File>()
            try {
                LinkCore.init(app)
                if (!LinkCore.requireStore().hasPairing()) {
                    _state.value = ShareState(message = "Pair with your PC in LinkPhone first.")
                    return@launch
                }

                LinkCore.startService(app)
                val client = LinkCore.requireClient()
                if (client.status.value.state != LinkState.CONNECTED) client.connectSaved()

                _state.value = ShareState(total = uris.size, running = true, message = "Preparing files…")
                var totalBytes = 0L
                val names = mutableListOf<String>()
                val cache = File(app.cacheDir, "share").apply { mkdirs() }
                for ((index, uri) in uris.distinct().take(20).withIndex()) {
                    val (name, size) = meta(app, uri)
                    totalBytes += size
                    if (totalBytes > 2L * 1024 * 1024 * 1024) {
                        _state.value = ShareState(
                            total = files.size,
                            names = names.toList(),
                            message = "Shared files are too large (2 GiB maximum)."
                        )
                        return@launch
                    }
                    val safe = name.replace(Regex("[^A-Za-z0-9._ -]"), "_")
                        .take(120)
                        .ifBlank { "file" }
                    val target = File(cache, "${UUID.randomUUID()}-$safe")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                    } ?: throw IllegalStateException("Could not read $name")
                    files.add(target)
                    names.add(name)
                    _state.value = ShareState(
                        total = uris.size,
                        done = index,
                        names = names.toList(),
                        running = true,
                        message = "Preparing files…"
                    )
                }

                _state.value = ShareState(
                    total = files.size,
                    names = names.toList(),
                    running = true,
                    waiting = true,
                    message = "Waiting for your PC…"
                )
                val deadline = System.currentTimeMillis() + 20_000
                while (client.status.value.state != LinkState.CONNECTED &&
                    System.currentTimeMillis() < deadline
                ) {
                    delay(250)
                }
                if (client.status.value.state != LinkState.CONNECTED) {
                    _state.value = ShareState(
                        total = files.size,
                        names = names.toList(),
                        message = "Could not reach your PC. Open LinkPhone on the PC and try again."
                    )
                    return@launch
                }

                var failures = 0
                files.forEachIndexed { index, file ->
                    _state.value = ShareState(
                        total = files.size,
                        done = index,
                        names = names.toList(),
                        running = true,
                        message = "Sending ${index + 1} of ${files.size}…"
                    )
                    if (!client.sendFileAndWait(Uri.fromFile(file), "downloads")) failures += 1
                }
                _state.value = ShareState(
                    total = files.size,
                    done = files.size - failures,
                    names = names.toList(),
                    message = if (failures == 0) {
                        "Sent to your PC."
                    } else {
                        "Sent ${files.size - failures} of ${files.size}; $failures failed."
                    }
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    running = false,
                    waiting = false,
                    message = e.message ?: "Could not share those files."
                )
            } finally {
                files.forEach { runCatching { it.delete() } }
            }
        }
    }

    private fun meta(context: Context, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment ?: "file"
        var size = 0L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) cursor.getString(nameIndex)?.let { name = it }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return name to size
    }
}

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = collectUris(intent)
        if (uris.isEmpty()) {
            finish()
            return
        }
        LinkCore.init(this)
        ShareSender.enqueue(this, uris)
        setContent {
            MaterialTheme(colorScheme = DarkColors) {
                ShareScreen(onClose = { finish() })
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun collectUris(intent: Intent?): List<Uri> {
        val out = mutableListOf<Uri>()
        val clip = intent?.clipData
        if (clip != null) {
            for (index in 0 until clip.itemCount) {
                clip.getItemAt(index).uri?.let { out.add(it) }
            }
        }
        intent?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
        return out.distinct()
    }
}

@Composable
private fun ShareScreen(onClose: () -> Unit) {
    val state by ShareSender.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Send to LinkPhone", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (state.names.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.names.take(20).forEach { name ->
                        Text(
                            name,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                state.message.ifBlank { "Preparing…" },
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (state.running) {
                val fraction = if (state.total > 0) {
                    (state.done.toFloat() / state.total).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.running) "Continue in background" else "Close")
            }
        }
    }
}
