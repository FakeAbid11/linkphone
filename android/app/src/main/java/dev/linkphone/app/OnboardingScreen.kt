package dev.linkphone.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

private const val STEPS = 3

@Composable
fun OnboardingScreen(
    store: PairStore,
    client: LinkClient,
    modifier: Modifier = Modifier,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    val status by client.status.collectAsStateWithLifecycle()

    var notifAccess by remember { mutableStateOf(hasNotificationAccess(context)) }
    var smsAccess by remember { mutableStateOf(hasSmsAccess(context)) }
    var contactsAccess by remember { mutableStateOf(hasContactsAccess(context)) }
    var photoAccess by remember { mutableStateOf(hasPhotoAccess(context)) }
    var videoAccess by remember { mutableStateOf(hasFullVideoAccess(context)) }
    var callLogAccess by remember { mutableStateOf(hasCallLogAccess(context)) }
    var callAccess by remember { mutableStateOf(hasCallPermissions(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryExempt(context)) }

    fun refreshPermissions() {
        notifAccess = hasNotificationAccess(context)
        smsAccess = hasSmsAccess(context)
        contactsAccess = hasContactsAccess(context)
        photoAccess = hasPhotoAccess(context)
        videoAccess = hasFullVideoAccess(context)
        callLogAccess = hasCallLogAccess(context)
        callAccess = hasCallPermissions(context)
        batteryExempt = isBatteryExempt(context)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            refreshPermissions()
        }
    }

    LaunchedEffect(step, status.state) {
        if (step == 2 && status.state == LinkState.CONNECTED) onFinish()
    }

    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    val contactsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        refreshPermissions()
        ContactsRepository.get(context).invalidate()
        ContactsRepository.get(context).preload()
        if (hasContactsAccess(context)) LinkCore.clientOrNull()?.refreshContacts()
    }

    val photoPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissions() }

    val callLogPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissions() }

    val callPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermissions()
        SystemCallWatcher.start(context)
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissions() }

    val batteryPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshPermissions() }

    LaunchedEffect(step) {
        if (step == 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(modifier.fillMaxSize().padding(24.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onFinish) { Text("Skip", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                0 -> WelcomeStep()
                1 -> PermissionsStep(
                    notifAccess = notifAccess,
                    smsAccess = smsAccess,
                    contactsAccess = contactsAccess,
                    photoAccess = photoAccess,
                    videoAccess = videoAccess,
                    callAccess = callAccess,
                    callLogAccess = callLogAccess,
                    batteryExempt = batteryExempt,
                    onOpenNotificationSettings = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    onRequestSms = {
                        smsPermission.launch(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.SEND_SMS,
                                Manifest.permission.RECEIVE_SMS
                            )
                        )
                    },
                    onRequestContacts = {
                        contactsPermission.launch(Manifest.permission.READ_CONTACTS)
                    },
                    onRequestPhotos = { photoPermission.launch(photoPermissions()) },
                    onRequestCallLog = {
                        callLogPermission.launch(Manifest.permission.READ_CALL_LOG)
                    },
                    onRequestCalls = {
                        callPermission.launch(
                            arrayOf(
                                Manifest.permission.CALL_PHONE,
                                Manifest.permission.READ_PHONE_STATE,
                                Manifest.permission.ANSWER_PHONE_CALLS
                            )
                        )
                    },
                    onRequestBattery = {
                        val request = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            "package:${context.packageName}".toUri()
                        )
                        runCatching { batteryPermission.launch(request) }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                )
                            }
                        }
                    }
                )
                else -> PairStep(
                    store = store,
                    client = client,
                    notice = notice,
                    onNotice = { notice = it }
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(STEPS) { index ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (index == step) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == step) {
                                MaterialTheme.colorScheme.onBackground
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (step > 0) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        notice = null
                        step -= 1
                    }
                ) { Text("Back") }
            }
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    if (step < STEPS - 1) {
                        step += 1
                    } else {
                        store.onboardingDone = true
                        onFinish()
                    }
                }
            ) {
                Text(
                    when {
                        step < STEPS - 1 -> "Next"
                        store.hasPairing() -> "Finish"
                        else -> "Pair later"
                    }
                )
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.logo_mark),
            contentDescription = null,
            modifier = Modifier.size(150.dp, 72.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(12.dp))
        Text("LinkPhone", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Your phone, on your PC — over your own Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        FeatureRow(
            Symbols.smartphone,
            "No cloud, no account",
            "Your phone and PC talk directly on the local network. Nothing is relayed."
        )
        FeatureRow(
            Symbols.link,
            "One encrypted link",
            "Pair once and LinkPhone keeps notifications, texts, calls, photos and files in sync."
        )
        FeatureRow(
            Symbols.call,
            "Calls stay on your phone",
            "Windows controls calls, but the audio and SIM stay on the phone."
        )
    }
}

@Composable
private fun FeatureRow(glyph: String, title: String, detail: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Symbol(glyph, 22.sp, MaterialTheme.colorScheme.onBackground)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionsStep(
    notifAccess: Boolean,
    smsAccess: Boolean,
    contactsAccess: Boolean,
    photoAccess: Boolean,
    videoAccess: Boolean,
    callAccess: Boolean,
    callLogAccess: Boolean,
    batteryExempt: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onRequestSms: () -> Unit,
    onRequestContacts: () -> Unit,
    onRequestPhotos: () -> Unit,
    onRequestCallLog: () -> Unit,
    onRequestCalls: () -> Unit,
    onRequestBattery: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Permissions", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "All optional. Each one is explained below and can be changed later on the main screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        PermissionRow(
            glyph = Symbols.notifications,
            title = "Notifications",
            detail = "Mirror notifications and reply from the PC.",
            granted = notifAccess,
            action = "Open settings",
            onAction = onOpenNotificationSettings,
            settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        )
        PermissionRow(
            glyph = Symbols.sms,
            title = "Messages",
            detail = "Read and send SMS. LinkPhone never becomes your default SMS app.",
            granted = smsAccess,
            action = "Allow",
            onAction = onRequestSms
        )
        PermissionRow(
            glyph = Symbols.contacts,
            title = "Contacts",
            detail = "Show names instead of numbers and browse contacts on the PC.",
            granted = contactsAccess,
            action = "Allow",
            onAction = onRequestContacts
        )
        PermissionRow(
            glyph = Symbols.photo_library,
            title = "Photos and videos",
            detail = "Show photo and video albums and download originals on the PC.",
            granted = photoAccess && videoAccess,
            action = "Allow",
            onAction = onRequestPhotos
        )
        PermissionRow(
            glyph = Symbols.call,
            title = "Calls",
            detail = "Dial and control calls from the PC (best effort).",
            granted = callAccess,
            action = "Allow",
            onAction = onRequestCalls
        )
        PermissionRow(
            glyph = Symbols.call,
            title = "Call history",
            detail = "Show recent calls with redial from the PC.",
            granted = callLogAccess,
            action = "Allow",
            onAction = onRequestCallLog
        )
        PermissionRow(
            glyph = Symbols.settings,
            title = "Background",
            detail = "Keep the link alive while the screen is off.",
            granted = batteryExempt,
            action = "Allow",
            onAction = onRequestBattery
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Notification access lets the PC show the live call card. Call access enables answer, " +
                "decline and hang up; speaker routing varies by phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionRow(
    glyph: String,
    title: String,
    detail: String,
    granted: Boolean,
    action: String,
    onAction: () -> Unit,
    settingsAction: String = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
) {
    val context = LocalContext.current
    if (granted) {
        LinkCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                Symbol(Symbols.check_circle, 18.sp, LocalContentColor.current)
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    "Allowed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        PermissionCard(
            glyph = glyph,
            title = title,
            body = detail,
            actionLabel = action,
            onAction = onAction,
            secondaryLabel = "Open settings",
            onSecondary = {
                val intent = if (settingsAction == Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) {
                    Intent(settingsAction)
                } else {
                    Intent(settingsAction, Uri.fromParts("package", context.packageName, null))
                }
                context.startActivity(intent)
            }
        )
    }
}

@Composable
private fun PairStep(
    store: PairStore,
    client: LinkClient,
    notice: String?,
    onNotice: (String) -> Unit
) {
    val status by client.status.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Pair with your PC", style = MaterialTheme.typography.headlineSmall)
        listOf(
            "Open LinkPhone on Windows.",
            "In the sidebar, choose “Scan QR code instead”.",
            "Scan the code here, or find the PC on your network."
        ).forEach { step ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.s)
            ) {
                LinkDot(active = false)
                Text(
                    step,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(Spacing.s))
        PairingSection(store = store, client = client, onNotice = onNotice)
        if (status.state == LinkState.CONNECTING || status.state == LinkState.PAIRING) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    if (status.state == LinkState.PAIRING) "Pairing…" else "Connecting…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (status.state == LinkState.ERROR && status.detail.isNotBlank()) {
            Text(
                status.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
