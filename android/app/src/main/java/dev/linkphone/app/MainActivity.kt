package dev.linkphone.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.role.RoleManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Teal = Color(0xFF2DD4BF)
private val DarkColors = darkColorScheme(
    primary = Teal,
    onPrimary = Color(0xFF04231F),
    secondary = Color(0xFF7DD3FC),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE6EEFC),
    surface = Color(0xFF101A2C),
    onSurface = Color(0xFFE6EEFC),
    surfaceVariant = Color(0xFF16233A),
    onSurfaceVariant = Color(0xFF8BA0BF),
    outline = Color(0xFF1F2F4A)
)

class MainActivity : ComponentActivity() {

    private lateinit var discovery: Discovery
    private var suppressUntil = 0L
    private var lastClip = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkCore.init(this)
        discovery = Discovery(this)

        lifecycleScope.launch {
            LinkCore.requireClient().clipboardIn.collect { text -> applyRemoteClip(text) }
        }

        lifecycleScope.launch {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            while (isActive) {
                delay(1500)
                if (!LinkCore.requireClient().isReady) continue
                if (!LinkCore.requireStore().clipboardEnabled) continue
                if (System.currentTimeMillis() < suppressUntil) continue
                val text = clipboard.primaryClip
                    ?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)
                    ?.coerceToText(this@MainActivity)
                    ?.toString()
                    .orEmpty()
                if (text.isBlank() || text == lastClip) continue
                lastClip = text
                LinkCore.requireClient().sendClipboard(text)
            }
        }

        setContent {
            MaterialTheme(colorScheme = DarkColors) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                        Screen(discovery, Modifier.padding(padding))
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        discovery.stop()
        super.onDestroy()
    }

    private fun applyRemoteClip(text: String) {
        suppressUntil = System.currentTimeMillis() + 2000
        lastClip = text
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LinkPhone", text))
    }
}

private fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

@Composable
private fun Screen(discovery: Discovery, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val client = LinkCore.requireClient()
    val store = LinkCore.requireStore()
    val updater = LinkCore.requireUpdater()
    val status by client.status.collectAsStateWithLifecycle()
    val transfers by client.transfers.collectAsStateWithLifecycle()
    val update by updater.state.collectAsStateWithLifecycle()

    val devices = remember { mutableStateListOf<Discovery.Target>() }
    var scanning by remember { mutableStateOf(false) }
    var scanningQr by remember { mutableStateOf(false) }
    var pendingDevice by remember { mutableStateOf<Discovery.Target?>(null) }
    var code by remember { mutableStateOf("") }
    var notifAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var smsAccess by remember { mutableStateOf(hasSmsAccess(context)) }
    var photoAccess by remember { mutableStateOf(hasPhotoAccess(context)) }
    var dialerRoleHeld by remember { mutableStateOf(CallHub.isDefaultDialer(context)) }
    var callPermissionsGranted by remember { mutableStateOf(hasCallPermissions(context)) }
    var notice by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose { discovery.stop() }
    }

    LaunchedEffect(Unit) {
        updater.check(false)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            notifAccess = hasNotificationAccess(context)
            smsAccess = hasSmsAccess(context)
            photoAccess = hasPhotoAccess(context)
            val nextDialer = CallHub.isDefaultDialer(context)
            if (nextDialer != dialerRoleHeld) {
                dialerRoleHeld = nextDialer
                CallHub.refreshDialerRole(context)
            }
            val nextCallPermissions = hasCallPermissions(context)
            if (nextCallPermissions != callPermissionsGranted) {
                callPermissionsGranted = nextCallPermissions
                CallHub.refreshDialerRole(context)
            }
        }
    }

    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        smsAccess = hasSmsAccess(context)
        if (!smsAccess) {
            notice = "SMS permission was denied. Allow it in Settings → Apps → LinkPhone → Permissions."
        }
    }

    val photoPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        photoAccess = hasPhotoAccess(context)
        if (!photoAccess) {
            notice = "Photo access was denied. Allow it in Settings → Apps → LinkPhone → Permissions."
        }
    }

    val callPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        callPermissionsGranted = hasCallPermissions(context)
        CallHub.refreshDialerRole(context)
        if (!callPermissionsGranted) {
            notice = "Call permission was denied. Allow it in Settings → Apps → LinkPhone → Permissions."
        }
    }

    val dialerRole = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        dialerRoleHeld = CallHub.isDefaultDialer(context)
        CallHub.refreshDialerRole(context)
        if (dialerRoleHeld && !hasCallPermissions(context)) {
            callPermission.launch(
                arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
            )
        } else if (!dialerRoleHeld) {
            notice = "LinkPhone needs to be the default phone app for reliable call control."
        }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            client.sendFile(uri)
        }
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
        val addresses = preferredAddress(payload.addresses)?.let { first ->
            listOf(first) + payload.addresses.filter { it != first }
        } ?: payload.addresses
        LinkCore.startService(context)
        client.connect(addresses, payload.port, payload.code)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                "LinkPhone",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Your PC, on your phone",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp
            )
        }

        StatusCard(status, notifAccess, paired = store.hasPairing())

        UpdateCard(
            update = update,
            onCheck = { updater.check(true) },
            onDownload = { updater.download() },
            onInstall = { updater.install() }
        )

        if (store.hasPairing()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Paired PC", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${store.serverName ?: "PC"} · ${store.host ?: ""}:${store.port}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            LinkCore.startService(context)
                            client.connectSaved()
                        }) { Text("Reconnect") }
                        OutlinedButton(onClick = {
                            client.forget()
                        }) { Text("Forget") }
                    }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Find your PC", fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { scanningQr = true }) {
                            Text("Scan QR")
                        }
                        Button(onClick = { scan() }, enabled = !scanning) {
                            Text(if (scanning) "Scanning…" else "Scan")
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
                                Text(target.name, fontWeight = FontWeight.Medium)
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

        if (!notifAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Notification access", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Required to mirror notifications. Android asks you to allow it in system settings.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { Text("Open settings") }
                }
            }
        }

        if (!smsAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Messages", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Allow LinkPhone to read and send SMS so your texts show up on the PC. " +
                            "It stays a normal app — it never becomes your default SMS app.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = {
                        smsPermission.launch(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.SEND_SMS,
                                Manifest.permission.RECEIVE_SMS
                            )
                        )
                    }) { Text("Allow SMS access") }
                }
            }
        }

        if (!photoAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Photos", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Allow LinkPhone to show photo albums on your PC. You can grant all photos " +
                            "or a selected set on newer Android versions.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = {
                        photoPermission.launch(photoPermissions())
                    }) { Text("Allow photo access") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Calls from your PC", fontWeight = FontWeight.SemiBold)
                Text(
                    "Answer, decline, dial and switch the phone speaker from Windows. Call audio " +
                        "always stays on this phone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                when {
                    !dialerRoleHeld -> OutlinedButton(onClick = {
                        val roles = context.getSystemService(RoleManager::class.java)
                        if (roles?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                            dialerRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                        } else {
                            notice = "Default phone-app control is unavailable on this device."
                        }
                    }) { Text("Set as default phone app") }

                    !callPermissionsGranted -> OutlinedButton(onClick = {
                        callPermission.launch(
                            arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
                        )
                    }) { Text("Allow call access") }

                    else -> Text(
                        "Call control is ready",
                        color = Teal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        notice?.let { text ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.padding(start = 16.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { notice = null }) { Text("Dismiss") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Files", fontWeight = FontWeight.SemiBold)
                    Button(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Send file") }
                }
                if (transfers.isEmpty()) {
                    Text(
                        "Files you send land in your PC's Downloads/LinkPhone folder.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                } else {
                    transfers.forEach { transfer ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    (if (transfer.direction == "in") "↓ " else "↑ ") + transfer.name,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    transfer.status,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            val fraction = if (transfer.size > 0) {
                                (transfer.received.toFloat() / transfer.size).coerceIn(0f, 1f)
                            } else 0f
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth().height(4.dp)
                            )
                        }
                    }
                }
            }
        }

        Text(
            "Clipboard sync is on while this screen is open (Android only allows clipboard reads to the app in the foreground).",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                ) { Text("Pair") }
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
                        notice = "That QR code is not a LinkPhone pairing code."
                    payload.addresses.isEmpty() ->
                        notice = "The QR code had no network address. Use Scan instead."
                    else -> connectScanned(payload)
                }
            },
            onClose = { scanningQr = false }
        )
    }
}

private fun hasSmsAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
        PackageManager.PERMISSION_GRANTED &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED

private fun hasPhotoAccess(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) ==
            PackageManager.PERMISSION_GRANTED
    else -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
}

private fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun hasCallPermissions(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED

@Composable
private fun UpdateCard(
    update: AppUpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit
) {
    val text = when (update.phase) {
        AppUpdatePhase.IDLE -> "Updates are checked through GitHub Releases."
        AppUpdatePhase.CHECKING -> "Checking GitHub Releases…"
        AppUpdatePhase.AVAILABLE -> "LinkPhone ${update.availableVersion} is available."
        AppUpdatePhase.DOWNLOADING -> "Downloading update… ${update.progress}%"
        AppUpdatePhase.READY -> update.message.ifBlank { "Update is ready to install." }
        AppUpdatePhase.UP_TO_DATE -> "LinkPhone is up to date."
        AppUpdatePhase.ERROR -> update.message.ifBlank { "Update check failed." }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("App update", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Current version ${update.currentVersion}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                when (update.phase) {
                    AppUpdatePhase.AVAILABLE ->
                        Button(onClick = onDownload) { Text("Download") }
                    AppUpdatePhase.READY ->
                        Button(onClick = onInstall) { Text("Install") }
                    AppUpdatePhase.CHECKING, AppUpdatePhase.DOWNLOADING ->
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> OutlinedButton(onClick = onCheck) { Text("Check") }
                }
            }
            Text(
                text,
                color = if (update.phase == AppUpdatePhase.ERROR) Color(0xFFF87171)
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            if (update.phase == AppUpdatePhase.DOWNLOADING) {
                LinearProgressIndicator(
                    progress = { (update.progress / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
            }
        }
    }
}

@Composable
private fun StatusCard(status: LinkStatus, notifAccess: Boolean, paired: Boolean) {
    val (label, detail, color) = when (status.state) {
        LinkState.CONNECTED -> Triple(
            "Connected to ${status.serverName.ifBlank { "PC" }}",
            if (notifAccess) "Notifications and messages mirror here" else "Messages mirror here",
            Teal
        )
        LinkState.PAIRING -> Triple("Pairing…", status.detail, Color(0xFFFBBF24))
        LinkState.CONNECTING -> Triple("Connecting…", status.detail, Color(0xFF7DD3FC))
        LinkState.ERROR -> Triple("Offline", status.detail.ifBlank { "Not connected" }, Color(0xFFF87171))
        LinkState.IDLE -> Triple(
            if (paired) "Paired, not connected" else "Not connected",
            if (paired) "Tap Reconnect or scan again" else "Scan for your PC below",
            Color(0xFF8BA0BF)
        )
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(color, CircleShape)
            )
            Column(Modifier.weight(1f)) {
                Text(label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status.state == LinkState.CONNECTING || status.state == LinkState.PAIRING) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(2.dp))
        }
    }
}
