package dev.linkphone.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** QR, network scan and typed-code pairing, shared by the main screen and onboarding. */
@Composable
fun PairingSection(
    store: PairStore,
    client: LinkClient,
    modifier: Modifier = Modifier,
    onNotice: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val discovery = remember { Discovery(context) }
    val devices = remember { mutableStateListOf<Discovery.Target>() }
    var scanning by remember { mutableStateOf(false) }
    var scanningQr by remember { mutableStateOf(false) }
    var pendingDevice by remember { mutableStateOf<Discovery.Target?>(null) }
    var code by remember { mutableStateOf("") }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var scanFinished by remember { mutableStateOf(false) }
    val status by client.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pairing = status.state == LinkState.CONNECTING || status.state == LinkState.PAIRING

    LaunchedEffect(status.state) {
        if (status.state == LinkState.CONNECTED) {
            pendingDevice = null
            code = ""
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scanJob?.cancel()
            discovery.stop()
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        discovery.stop()
        scanning = false
    }

    fun scan() {
        stopScan()
        devices.clear()
        scanFinished = false
        scanning = true
        discovery.start(
            onFound = { target ->
                if (devices.none { it.host == target.host && it.port == target.port }) {
                    devices.add(target)
                }
            },
            onError = {
                scanning = false
                scanFinished = true
            }
        )
        scanJob = scope.launch {
            delay(15_000)
            if (scanning) {
                discovery.stop()
                scanning = false
                scanFinished = true
            }
        }
    }

    fun connect(target: Discovery.Target, pairingCode: String?) {
        stopScan()
        LinkCore.startService(context)
        client.connect(target.host, target.port, pairingCode)
    }

    fun connectScanned(payload: PairPayload) {
        stopScan()
        val preferred = preferredAddress(payload.addresses)
        val addresses = buildList {
            if (preferred != null) add(preferred)
            addAll(payload.addresses.filter { it != preferred })
            addAll(payload.ipv6Addresses)
        }
        LinkCore.startService(context)
        client.connect(addresses, payload.port, payload.code)
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitle(Symbols.search, "Find your PC")
            Text(
                "Scan the QR code on your PC for one-tap pairing, or look for it on your network.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = { scanningQr = true }
                ) {
                    ButtonLabel(Symbols.qr_code_scanner, "Scan QR")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = { scan() },
                    enabled = !scanning
                ) {
                    ButtonLabel(Symbols.search, if (scanning) "Scanning…" else "Scan")
                }
            }
            if (scanning) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        "Looking for your PC…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { stopScan(); scanFinished = true }) { Text("Stop") }
                }
            } else if (scanFinished && devices.isEmpty()) {
                Text(
                    "No PCs found on this network — try Scan again.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (devices.isNotEmpty()) {
                devices.forEach { target ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                if (store.hasPairing()) connect(target, null)
                                else pendingDevice = target
                            }
                            .padding(Spacing.m),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.m)
                    ) {
                        Symbol(Symbols.smartphone, 18.sp)
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(target.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${target.host}:${target.port}",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Symbol(Symbols.chevron_right, 18.sp)
                    }
                }
            }
        }
    }

    val target = pendingDevice
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDevice = null },
            title = { Text("Pair with ${target.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Type the pairing code shown on your PC.")
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.uppercase().take(12) },
                        singleLine = true,
                        enabled = !pairing,
                        label = { Text("Pairing code") },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            letterSpacing = 4.sp
                        )
                    )
                    if (pairing) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text(
                                "Connecting…",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (status.state == LinkState.ERROR && status.detail.isNotBlank()) {
                        Text(
                            status.detail,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { connect(target, code) },
                    enabled = code.length >= 6 && !pairing
                ) { ButtonLabel(Symbols.link, "Pair") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDevice = null
                    code = ""
                }) { Text("Cancel") }
            }
        )
    }

    if (scanningQr) {
        QrScannerScreen(
            onResult = { raw ->
                scanningQr = false
                val payload = parsePairPayload(raw)
                when {
                    payload == null ->
                        onNotice("That QR code is not a LinkPhone pairing code.")
                    payload.addresses.isEmpty() && payload.ipv6Addresses.isEmpty() ->
                        onNotice("The QR code had no network address. Use Scan instead.")
                    else -> connectScanned(payload)
                }
            },
            onClose = { scanningQr = false }
        )
    }
}
