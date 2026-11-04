package dev.linkphone.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val Primary = Color.White
private val Secondary = Color(0xFFB3B3B3)
private val MaterialSymbols = FontFamily(Font(R.font.material_symbols_rounded))

internal object Symbols {
    const val smartphone = "\ue7ba"
    const val system_update = "\uf2cd"
    const val search = "\uef7a"
    const val notifications = "\ue7f5"
    const val sms = "\ue625"
    const val photo_library = "\ue413"
    const val contacts = "\ue0ba"
    const val call = "\uf0d4"
    const val folder = "\ue2c7"
    const val link = "\ue250"
    const val qr_code_scanner = "\uf206"
    const val check_circle = "\uf0be"
    const val download = "\uf090"
    const val install_desktop = "\ueb71"
    const val refresh = "\ue5d5"
    const val close = "\ue5cd"
    const val settings = "\ue8b8"
    const val upload = "\uf09b"
}

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF1C1C1C),
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1C1C1C),
    onSecondaryContainer = Color.White,
    tertiary = Secondary,
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color(0xFF121212),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Secondary,
    outline = Color(0xFF3A3A3A),
    error = Color.White,
    onError = Color.Black,
    errorContainer = Color(0xFF1C1C1C),
    onErrorContainer = Color.White
)

@Composable
internal fun Symbol(
    glyph: String,
    size: TextUnit = 20.sp,
    tint: Color = Color.Unspecified
) {
    Text(
        text = glyph,
        fontFamily = MaterialSymbols,
        fontSize = size,
        lineHeight = size,
        color = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint
    )
}

@Composable
internal fun ButtonLabel(glyph: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Symbol(glyph, 18.sp, LocalContentColor.current)
        Text(label)
    }
}

@Composable
internal fun CardTitle(glyph: String, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Symbol(glyph)
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

class MainActivity : ComponentActivity() {

    private var suppressUntil = 0L
    private var lastClip = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LinkCore.init(this)
        val startStore = LinkCore.requireStore()
        if (startStore.hasPairing()) {
            startStore.onboardingDone = true
            runCatching { LinkCore.startService(this) }
        }

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
            val store = LinkCore.requireStore()
            var showOnboarding by remember {
                mutableStateOf(!store.onboardingDone && !store.hasPairing())
            }
            MaterialTheme(colorScheme = DarkColors) {
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

    private fun applyRemoteClip(text: String) {
        suppressUntil = System.currentTimeMillis() + 2000
        lastClip = text
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LinkPhone", text))
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
    val update by updater.state.collectAsStateWithLifecycle()

    var notifAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }
    var smsAccess by remember { mutableStateOf(hasSmsAccess(context)) }
    var photoAccess by remember { mutableStateOf(hasPhotoAccess(context)) }
    var contactsAccess by remember { mutableStateOf(hasContactsAccess(context)) }
    var callLogAccess by remember { mutableStateOf(hasCallLogAccess(context)) }
    var simAccess by remember { mutableStateOf(hasSimAccess(context)) }
    var callPermissionsGranted by remember { mutableStateOf(hasCallPermissions(context)) }
    var outgoingReady by remember { mutableStateOf(CallHub.isOutgoingReady(context)) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        updater.check(false)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            notifAccess = hasNotificationAccess(context)
            batteryExempt = isBatteryExempt(context)
            smsAccess = hasSmsAccess(context)
            photoAccess = hasPhotoAccess(context)
            val nextContacts = hasContactsAccess(context)
            if (nextContacts != contactsAccess) {
                contactsAccess = nextContacts
                ContactsRepository.get(context).invalidate()
                if (nextContacts) ContactsRepository.get(context).preload()
            }
            callLogAccess = hasCallLogAccess(context)
            simAccess = hasSimAccess(context)
            val nextCallPermissions = hasCallPermissions(context)
            outgoingReady = CallHub.isOutgoingReady(context)
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

    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        contactsAccess = hasContactsAccess(context)
        ContactsRepository.get(context).invalidate()
        ContactsRepository.get(context).preload()
        if (!contactsAccess) {
            notice = "Contact access was denied. Names will show as numbers."
        }
    }

    val callPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        callPermissionsGranted = hasCallPermissions(context)
        outgoingReady = CallHub.isOutgoingReady(context)
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
                    fontSize = 13.sp
                )
            }
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
                    CardTitle(Symbols.link, "Paired PC")
                    Text(
                        "${store.serverName ?: "PC"} · ${store.host ?: ""}:${store.port}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            LinkCore.startService(context)
                            client.connectSaved()
                        }) { ButtonLabel(Symbols.refresh, "Reconnect") }
                        OutlinedButton(onClick = {
                            client.forget()
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
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }) { ButtonLabel(Symbols.settings, "Open settings") }
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
                        fontSize = 12.sp
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
                    }) { ButtonLabel(Symbols.sms, "Allow SMS access") }
                }
            }
        }

        if (!photoAccess) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardTitle(Symbols.photo_library, "Photos")
                    Text(
                        "Allow LinkPhone to show photo albums on your PC. You can grant all photos " +
                            "or a selected set on newer Android versions.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = {
                        photoPermission.launch(photoPermissions())
                    }) { ButtonLabel(Symbols.photo_library, "Allow photo access") }
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
                        fontSize = 12.sp
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
                        fontSize = 12.sp
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
                        fontSize = 12.sp
                    )
                    OutlinedButton(onClick = {
                        simPermission.launch(Manifest.permission.READ_PHONE_STATE)
                    }) { ButtonLabel(Symbols.smartphone, "Allow phone access") }
                }
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle(Symbols.call, "Calls from your PC")
                Text(
                    "Dial, answer, decline, hang up and switch the phone speaker from Windows. " +
                        "Audio always stays on this phone. No default-phone role is required.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                when {
                    !outgoingReady -> OutlinedButton(onClick = {
                        callPermission.launch(
                            arrayOf(
                                Manifest.permission.CALL_PHONE,
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.ANSWER_PHONE_CALLS
                            )
                        )
                    }) { ButtonLabel(Symbols.call, "Allow call access") }

                    !callPermissionsGranted -> OutlinedButton(onClick = {
                        callPermission.launch(
                            arrayOf(
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.ANSWER_PHONE_CALLS
                            )
                        )
                    }) { ButtonLabel(Symbols.call, "Allow phone access") }

                    else -> Text(
                        "Call control is ready",
                        color = Primary,
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
                    TextButton(onClick = { notice = null }) { ButtonLabel(Symbols.close, "Dismiss") }
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
                    CardTitle(Symbols.folder, "Files")
                    Button(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        ButtonLabel(Symbols.upload, "Send file")
                    }
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

internal fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
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
        ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) ==
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
                    CardTitle(Symbols.system_update, "App update")
                    Text(
                        "Current version ${update.currentVersion}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
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
            Primary
        )
        LinkState.PAIRING -> Triple("Pairing…", status.detail, Secondary)
        LinkState.CONNECTING -> Triple("Connecting…", status.detail, Secondary)
        LinkState.ERROR -> Triple("Offline", status.detail.ifBlank { "Not connected" }, Secondary)
        LinkState.IDLE -> Triple(
            if (paired) "Paired, not connected" else "Not connected",
            if (paired) "Tap Reconnect or scan again" else "Scan for your PC below",
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
