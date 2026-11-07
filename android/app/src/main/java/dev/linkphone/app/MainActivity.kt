package dev.linkphone.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkCore.init(this)
        val startStore = LinkCore.requireStore()
        if (startStore.hasPairing()) {
            startStore.onboardingDone = true
            runCatching { LinkCore.startService(this) }
        }

        setContent {
            val store = LinkCore.requireStore()
            var showOnboarding by remember {
                mutableStateOf(!store.onboardingDone && !store.hasPairing())
            }
            LinkPhoneTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
                        if (showOnboarding) {
                            OnboardingScreen(
                                store = store,
                                client = LinkCore.requireClient(),
                                modifier = Modifier.padding(padding),
                                onFinish = {
                                    store.onboardingDone = true
                                    showOnboarding = false
                                }
                            )
                        } else {
                            Screen(Modifier.padding(padding))
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ClipboardSync.beginForeground()
    }

    override fun onStop() {
        ClipboardSync.endForeground()
        super.onStop()
    }
}

internal fun hasNotificationAccess(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

internal fun isBatteryExempt(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .isIgnoringBatteryOptimizations(context.packageName)

@Composable
private fun Screen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val client = LinkCore.requireClient()
    val store = LinkCore.requireStore()
    val updater = LinkCore.requireUpdater()
    val status by client.status.collectAsStateWithLifecycle()
    val transfers by client.transfers.collectAsStateWithLifecycle()
    val ringing by PhoneRinger.ringing.collectAsStateWithLifecycle()
    val update by updater.state.collectAsStateWithLifecycle()

    var notifAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var listenerConnected by remember { mutableStateOf(NotificationRelay.isConnected) }
    var backgroundClipboard by remember { mutableStateOf(ClipboardAccessibilityService.isEnabled(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    var smsAccess by remember { mutableStateOf(hasSmsAccess(context)) }
    var photoAccess by remember { mutableStateOf(hasPhotoAccess(context)) }
    var videoAccess by remember { mutableStateOf(hasVideoAccess(context)) }
    var fullVideoAccess by remember { mutableStateOf(hasFullVideoAccess(context)) }
    var partialVideoAccess by remember { mutableStateOf(hasPartialVideoAccess(context)) }
    var contactsAccess by remember { mutableStateOf(hasContactsAccess(context)) }
    var callLogAccess by remember { mutableStateOf(hasCallLogAccess(context)) }
    var simAccess by remember { mutableStateOf(hasSimAccess(context)) }
    var callPermissionsGranted by remember { mutableStateOf(hasCallPermissions(context)) }
    var outgoingReady by remember { mutableStateOf(CallHub.isOutgoingReady(context)) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    val paired = store.hasPairing()
    var setupOpen by rememberSaveable { mutableStateOf(!paired) }
    var phoneNameDraft by remember { mutableStateOf(client.deviceName) }
    val stepsLeft = listOf(
        !notifAccess,
        !batteryExempt,
        !smsAccess,
        !photoAccess,
        photoAccess && !fullVideoAccess,
        !contactsAccess,
        !callLogAccess,
        smsAccess && !simAccess,
        !outgoingReady || !callPermissionsGranted
    ).count { it }

    LaunchedEffect(Unit) {
        updater.check(false)
    }

    LaunchedEffect(Unit) {
        if (hasNotificationAccess(context) && !NotificationRelay.isConnected) {
            NotificationRelay.ensureBound(context)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            val nextNotifAccess = hasNotificationAccess(context)
            notifAccess = nextNotifAccess
            listenerConnected = NotificationRelay.isConnected
            if (nextNotifAccess && !listenerConnected) {
                NotificationRelay.ensureBound(context)
            }
            backgroundClipboard = ClipboardAccessibilityService.isEnabled(context)
            batteryExempt = isBatteryExempt(context)
            smsAccess = hasSmsAccess(context)
            photoAccess = hasPhotoAccess(context)
            videoAccess = hasVideoAccess(context)
            fullVideoAccess = hasFullVideoAccess(context)
            partialVideoAccess = hasPartialVideoAccess(context)
            val nextContacts = hasContactsAccess(context)
            if (nextContacts != contactsAccess) {
                contactsAccess = nextContacts
                ContactsRepository.get(context).invalidate()
                if (nextContacts) {
                    ContactsRepository.get(context).preload()
                    LinkCore.clientOrNull()?.refreshContacts()
                }
            }
            callLogAccess = hasCallLogAccess(context)
            simAccess = hasSimAccess(context)
            val nextCallPermissions = hasCallPermissions(context)
            outgoingReady = CallHub.isOutgoingReady(context)
            if (nextCallPermissions != callPermissionsGranted) {
                callPermissionsGranted = nextCallPermissions
                if (!simAccess) SystemCallWatcher.stop()
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
        videoAccess = hasVideoAccess(context)
        fullVideoAccess = hasFullVideoAccess(context)
        partialVideoAccess = hasPartialVideoAccess(context)
        if (!photoAccess) {
            notice = "Photo access was denied. Allow it in Settings → Apps → LinkPhone → Permissions."
        }
    }

    val videoPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        videoAccess = hasVideoAccess(context)
        fullVideoAccess = hasFullVideoAccess(context)
        partialVideoAccess = hasPartialVideoAccess(context)
        if (!videoAccess) {
            notice = "Video access was denied. Allow it in Settings → Apps → LinkPhone → Permissions."
        }
    }

    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        contactsAccess = hasContactsAccess(context)
        ContactsRepository.get(context).invalidate()
        ContactsRepository.get(context).preload()
        if (contactsAccess) LinkCore.clientOrNull()?.refreshContacts()
        if (!contactsAccess) {
            notice = "Contact access was denied. Names will show as numbers."
        }
    }

    val callPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        callPermissionsGranted = hasCallPermissions(context)
        outgoingReady = CallHub.isOutgoingReady(context)
        SystemCallWatcher.start(context)
        CallHub.refreshDialerRole(context)
        if (!callPermissionsGranted) {
            notice = "Call permission was denied. Allow it in Settings → Apps → LinkPhone → Permissions."
        }
    }

    val callLogPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        callLogAccess = hasCallLogAccess(context)
        if (!callLogAccess) {
            notice = "Call-history access was denied. Allow Call logs in Settings → Apps → LinkPhone → Permissions."
        }
    }

    val simPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        simAccess = hasSimAccess(context)
        SimRepository.get(context).reload()
        if (!simAccess) {
            notice = "Phone access was denied. SIM selection stays on the default SIM."
        }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val batteryExemption = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        batteryExempt = isBatteryExempt(context)
        if (!batteryExempt) {
            notice = "Background stays limited. Find LinkPhone in battery optimization and choose " +
                "Don't optimize. On Xiaomi/HyperOS also enable Autostart and set Battery saver " +
                "to No restrictions."
        }
    }

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.logo_mark),
                contentDescription = null,
                modifier = Modifier.size(58.dp, 28.dp),
                contentScale = ContentScale.Fit
            )
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
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        StatusCard(
            status = status,
            notifAccess = notifAccess,
            paired = paired,
            onReconnect = {
                LinkCore.startService(context)
                client.connectSaved()
            }
        )

        if (ringing) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.padding(start = 16.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Symbol(Symbols.volume_up)
                    Spacer(Modifier.width(8.dp))
                    Text("Phone is ringing", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { PhoneRinger.stop() }) {
                        ButtonLabel(Symbols.stop, "Stop")
                    }
                }
            }
        }

        notice?.let { text ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.padding(start = 16.dp, top = 10.dp, end = 6.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Symbol(Symbols.info, 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { notice = null }) { ButtonLabel(Symbols.close, "Dismiss") }
                }
            }
        }

        if (photoAccess && !fullVideoAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.photo_library, "Videos need access")
                    Text(
                        if (partialVideoAccess) {
                            "Android is showing only the videos you selected. Allow all videos to " +
                                "see the full gallery on your PC."
                        } else {
                            "Photo access is on, but Android asks for video separately. Allow it " +
                                "to see videos on your PC."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        videoPermission.launch(Manifest.permission.READ_MEDIA_VIDEO)
                    }) {
                        ButtonLabel(
                            Symbols.photo_library,
                            if (partialVideoAccess) "Allow all videos" else "Allow video access"
                        )
                    }
                }
            }
        }

        UpdateCard(
            update = update,
            onCheck = { updater.check(true) },
            onDownload = { updater.download() },
            onInstall = { updater.install() }
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CardTitle(Symbols.folder, "Files")
                    Button(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = status.state == LinkState.CONNECTED
                    ) {
                        ButtonLabel(Symbols.upload, "Send file")
                    }
                }
                if (transfers.isEmpty()) {
                    Text(
                        if (status.state == LinkState.CONNECTED) {
                            "Files you send land in your PC's Downloads/LinkPhone folder."
                        } else {
                            "Connect your phone to send files."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
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
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        transferLabel(transfer),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (
                                        (transfer.status == "pending" ||
                                            transfer.status == "active") &&
                                        status.state == LinkState.CONNECTED
                                    ) {
                                        TextButton(
                                            onClick = { client.cancelTransfer(transfer.id) },
                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                        ) { Text("Cancel", style = MaterialTheme.typography.bodySmall) }
                                    }
                                    if (
                                        transfer.direction == "out" &&
                                        (transfer.status == "failed" ||
                                            transfer.status == "interrupted") &&
                                        status.state == LinkState.CONNECTED
                                    ) {
                                        TextButton(
                                            onClick = { client.retryTransfer(transfer.id) },
                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                        ) { Text("Retry", style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
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

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Symbol(Symbols.settings, 20.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Setup", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !paired -> "Pair your phone to get started"
                            stepsLeft > 0 ->
                                "$stepsLeft ${if (stepsLeft == 1) "step" else "steps"} left"
                            else -> "All set"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (paired) {
                    TextButton(onClick = { setupOpen = !setupOpen }) {
                        Text(if (setupOpen) "Hide" else "Show")
                    }
                }
            }
        }

        if (setupOpen) {

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle(Symbols.smartphone, "This phone")
                OutlinedTextField(
                    value = phoneNameDraft,
                    onValueChange = { phoneNameDraft = it.take(32) },
                    singleLine = true,
                    label = { Text("Phone name") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    enabled = phoneNameDraft.trim().isNotEmpty(),
                    onClick = {
                        if (client.renameDevice(phoneNameDraft)) {
                            phoneNameDraft = client.deviceName
                            notice = "Phone name updated."
                        }
                    }
                ) { ButtonLabel(Symbols.check_circle, "Save name") }
            }
        }

        if (store.hasPairing()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    CardTitle(Symbols.link, "Paired PC")
                    Text(
                        "${store.serverName ?: "PC"} · ${store.host ?: ""}:${store.port}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            confirmForget = true
                        }) { ButtonLabel(Symbols.close, "Forget") }
                    }
                }
            }
        }

        PairingSection(
            store = store,
            client = client,
            onNotice = { notice = it }
        )

        if (!notifAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.notifications, "Notification access")
                    Text(
                        "Required to mirror notifications. Android asks you to allow it in system settings.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { ButtonLabel(Symbols.settings, "Open settings") }
                }
            }
        } else if (!listenerConnected) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.notifications, "Notification listener")
                    Text(
                        "Notification access is on, but the listener is disconnected. " +
                            "Reconnect it to mirror notifications from your phone.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            NotificationRelay.ensureBound(context, true)
                        }) { ButtonLabel(Symbols.refresh, "Reconnect") }
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            )
                        }) { ButtonLabel(Symbols.settings, "Open settings") }
                    }
                }
            }
        }

        if (!batteryExempt) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.settings, "Background connection")
                    Text(
                        "Keep the connection alive while the screen is off. Android battery " +
                            "optimization can pause background networking; LinkPhone can ask to be " +
                            "exempt. You can undo this in system settings.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        val request = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            "package:${context.packageName}".toUri()
                        )
                        runCatching { batteryExemption.launch(request) }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            }
                            notice = "Choose LinkPhone in the battery-optimization list and set it " +
                                "to Don't optimize. On Xiaomi/HyperOS also enable Autostart."
                        }
                    }) { ButtonLabel(Symbols.settings, "Allow background") }
                }
            }
        }

        if (!smsAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.sms, "Messages")
                    Text(
                        "Allow LinkPhone to read and send SMS so your texts show up on the PC. " +
                            "It stays a normal app — it never becomes your default SMS app.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        smsPermission.launch(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.SEND_SMS,
                                Manifest.permission.RECEIVE_SMS
                            )
                        )
                    }) { ButtonLabel(Symbols.sms, "Allow SMS access") }
                }
            }
        }

        if (!photoAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.photo_library, "Photos and videos")
                    Text(
                        "Allow LinkPhone to show photo and video albums on your PC. You can grant " +
                            "all media or a selected set on newer Android versions.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        photoPermission.launch(photoPermissions())
                    }) { ButtonLabel(Symbols.photo_library, "Allow media access") }
                }
            }
        }

        if (!contactsAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.contacts, "Contact names")
                    Text(
                        "Allow contact access to show names instead of numbers and to browse your " +
                            "contact list from the PC.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    }) { ButtonLabel(Symbols.contacts, "Allow contact access") }
                }
            }
        }

        if (!callLogAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.call, "Call history")
                    Text(
                        "Allow call-log access to see recent calls and call back from the PC.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        callLogPermission.launch(Manifest.permission.READ_CALL_LOG)
                    }) { ButtonLabel(Symbols.call, "Allow call-history access") }
                }
            }
        }

        if (smsAccess && !simAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.smartphone, "Choose a SIM")
                    Text(
                        "Allow phone access to label texts and calls by SIM and pick a SIM from the PC.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        simPermission.launch(Manifest.permission.READ_PHONE_STATE)
                    }) { ButtonLabel(Symbols.smartphone, "Allow SIM access") }
                }
            }
        }

        if (!outgoingReady || !callPermissionsGranted) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.call, "Calls from your PC")
                    Text(
                        "Dial, answer, decline, hang up and switch the phone speaker from Windows. " +
                            "Audio always stays on this phone. No default-phone role is required.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!outgoingReady) {
                        OutlinedButton(onClick = {
                            callPermission.launch(
                                arrayOf(
                                    Manifest.permission.CALL_PHONE,
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.ANSWER_PHONE_CALLS
                                )
                            )
                        }) { ButtonLabel(Symbols.call, "Allow call access") }
                    } else {
                        OutlinedButton(onClick = {
                            callPermission.launch(
                                arrayOf(
                                    Manifest.permission.READ_PHONE_STATE,
                                    Manifest.permission.ANSWER_PHONE_CALLS
                                )
                            )
                        }) { ButtonLabel(Symbols.call, "Allow call control") }
                    }
                }
            }
        }

        }

        if (paired && store.clipboardEnabled && store.pcClipboardEnabled && !backgroundClipboard) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.settings, "Sync copies from other apps")
                    Text(
                        "Optional. Android only lets LinkPhone read the clipboard while it is on " +
                            "screen. Enable accessibility to sync text copied in other apps. " +
                            "LinkPhone reads clipboard text only, never screen content.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        ClipboardAccessibilityService.openSettings(context)
                    }) { ButtonLabel(Symbols.settings, "Enable background sync") }
                }
            }
        }

        Text(
            when {
                !paired -> "Pair your phone to sync the clipboard."
                !store.pcClipboardEnabled -> "Clipboard sync is turned off on your PC."
                backgroundClipboard ->
                    "Clipboard text syncs from any app while LinkPhone is in the background."
                else ->
                    "Clipboard text syncs while LinkPhone is open. Enable background sync above " +
                        "to copy from other apps."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this PC?") },
            text = { Text("You'll need to scan a new QR code to pair again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    client.forget()
                    context.stopService(Intent(context, LinkService::class.java))
                }) { Text("Forget") }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text("Cancel") }
            }
        )
    }
}

internal fun hasSmsAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
        PackageManager.PERMISSION_GRANTED &&
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) ==
        PackageManager.PERMISSION_GRANTED

internal fun hasPhotoAccess(context: Context): Boolean = when {
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

internal fun hasFullVideoAccess(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) ==
            PackageManager.PERMISSION_GRANTED
    else -> ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED
}

internal fun hasPartialVideoAccess(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) !=
            PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        ) == PackageManager.PERMISSION_GRANTED

internal fun hasVideoAccess(context: Context): Boolean =
    hasFullVideoAccess(context) || hasPartialVideoAccess(context)

internal fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO
    )
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

internal fun hasContactsAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

internal fun hasCallLogAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) ==
        PackageManager.PERMISSION_GRANTED

internal fun hasSimAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED

internal fun hasCallPermissions(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
        PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED &&
        (
            CallHub.isDefaultDialer(context) ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
                PackageManager.PERMISSION_GRANTED
            )

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
                    CardTitle(Symbols.system_update, "App update")
                    Text(
                        "Current version ${update.currentVersion}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                when (update.phase) {
                    AppUpdatePhase.AVAILABLE ->
                        Button(onClick = onDownload) { ButtonLabel(Symbols.download, "Download") }
                    AppUpdatePhase.READY ->
                        Button(onClick = onInstall) { ButtonLabel(Symbols.install_desktop, "Install") }
                    AppUpdatePhase.CHECKING, AppUpdatePhase.DOWNLOADING ->
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> OutlinedButton(onClick = onCheck) {
                        ButtonLabel(Symbols.refresh, "Check")
                    }
                }
            }
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
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

private fun transferLabel(transfer: TransferUi): String = when (transfer.status) {
    "pending" -> "Waiting"
    "active" -> if (transfer.direction == "in") "Receiving…" else "Sending…"
    "done" -> "Done"
    "failed" -> "Failed"
    "interrupted" -> "Interrupted"
    "declined" -> "Declined"
    "canceled" -> "Canceled"
    else -> transfer.status
}

@Composable
private fun StatusCard(
    status: LinkStatus,
    notifAccess: Boolean,
    paired: Boolean,
    onReconnect: () -> Unit
) {
    val (label, detail, color) = when (status.state) {
        LinkState.CONNECTED -> Triple(
            "Connected to ${status.serverName.ifBlank { "PC" }}",
            if (notifAccess) "Notifications and messages mirror here" else "Messages mirror here",
            Primary
        )
        LinkState.PAIRING -> Triple("Pairing…", status.detail, Secondary)
        LinkState.CONNECTING -> Triple("Connecting…", status.detail, Secondary)
        LinkState.ERROR -> Triple("Offline", status.detail.ifBlank { "Not connected" }, Secondary)
        LinkState.IDLE -> Triple(
            if (paired) "Paired, not connected" else "Not connected",
            if (paired) "Open LinkPhone on your PC or try again" else "Scan for your PC below",
            Secondary
        )
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Symbol(Symbols.smartphone, 22.sp, color)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (paired && (status.state == LinkState.IDLE || status.state == LinkState.ERROR)) {
                TextButton(onClick = onReconnect) { ButtonLabel(Symbols.refresh, "Reconnect") }
            } else if (status.state == LinkState.CONNECTING || status.state == LinkState.PAIRING) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(2.dp))
        }
    }
}
