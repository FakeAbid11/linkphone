package dev.linkphone.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    DisposableEffect(Unit) {
        onDispose { discovery.stop() }
    }

    fun scan() {
        devices.clear()
        scanning = true
        discovery.start(
            onFound = { target ->
                if (devices.none { it.host == target.host && it.port == target.port }) {
                    devices.add(target)
                }
            },
            onError = { scanning = false }
        )
    }

    fun connect(target: Discovery.Target, pairingCode: String?) {
        scanning = false
        discovery.stop()
        LinkCore.startService(context)
        client.connect(target.host, target.port, pairingCode)
    }

    fun connectScanned(payload: PairPayload) {
        scanning = false
        discovery.stop()
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CardTitle(Symbols.search, "Find your PC")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scanningQr = true }) {
                        ButtonLabel(Symbols.qr_code_scanner, "Scan QR")
                    }
                    Button(onClick = { scan() }, enabled = !scanning) {
                        ButtonLabel(Symbols.search, if (scanning) "Scanning…" else "Scan")
                    }
                }
            }
            Text(
                "Scan the QR code on your PC for one-tap pairing, or look for it on your network.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            if (devices.isNotEmpty()) {
                devices.forEach { target ->
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            if (store.hasPairing()) connect(target, null)
                            else pendingDevice = target
                        }
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(target.name, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            Text(
                                "${target.host}:${target.port}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                        label = { Text("Pairing code") },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 20.sp,
                            letterSpacing = 4.sp
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val value = code
                        pendingDevice = null
                        code = ""
                        connect(target, value)
                    },
                    enabled = code.length >= 6
                ) { ButtonLabel(Symbols.link, "Pair") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDevice = null }) { Text("Cancel") }
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
