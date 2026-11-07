package dev.linkphone.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** Minimal default-dialer screen so calls remain usable without the PC. */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        LinkCore.init(this)

        val initialNumber = intent?.data?.schemeSpecificPart.orEmpty()
        val launchedForCall = intent?.action != Intent.ACTION_DIAL && initialNumber.isEmpty()
        setContent {
            LinkPhoneTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PhoneCallScreen(
                        initialNumber = initialNumber,
                        closeAfterCall = launchedForCall,
                        onClose = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PhoneCallScreen(
    initialNumber: String,
    closeAfterCall: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val snapshot by CallHub.state.collectAsStateWithLifecycle()
    var number by remember { mutableStateOf(initialNumber) }
    var error by remember { mutableStateOf("") }
    var sawCall by remember { mutableStateOf(snapshot.calls.isNotEmpty()) }
    val call = snapshot.calls.firstOrNull()

    LaunchedEffect(snapshot.calls.size) {
        if (snapshot.calls.isNotEmpty()) sawCall = true
        if (closeAfterCall && sawCall && snapshot.calls.isEmpty()) {
            delay(500)
            onClose()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("LinkPhone call", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)

        if (call == null) {
            Text(
                "Make a call",
                modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
                style = MaterialTheme.typography.titleLarge
            )
            OutlinedTextField(
                value = number,
                onValueChange = { number = it.take(64) },
                label = { Text("Phone number") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            error.takeIf { it.isNotBlank() }?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Button(
                onClick = {
                    error = ""
                    CallHub.placeCall(context, number)
                        .onFailure { error = it.message ?: "Could not place call" }
                },
                enabled = number.isNotBlank(),
                modifier = Modifier.padding(top = 12.dp)
            ) { Text("Call") }
            return@Column
        }

        Box(
            Modifier
                .padding(top = 18.dp, bottom = 16.dp)
                .size(84.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val label = (call.displayName.ifBlank { call.number }).take(2).uppercase()
            Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            call.displayName.ifBlank { call.number },
            style = MaterialTheme.typography.titleLarge
        )
        if (call.displayName.isNotBlank() && call.number.isNotBlank()) {
            Text(call.number, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        call.simLabel?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Text(
            call.phase.label(),
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )

        if (call.phase == PhoneCallPhase.RINGING) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { CallHub.reject(call.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = Color.White
                    )
                ) { Text("Decline") }
                Button(
                    onClick = { CallHub.answer(call.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    )
                ) { Text("Answer") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { CallHub.setSpeaker(!snapshot.speaker) }) {
                    Text(if (snapshot.speaker) "Speaker on" else "Speaker off")
                }
                Button(
                    onClick = { CallHub.disconnect(call.id) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = Color.White
                    )
                ) { Text("Hang up") }
            }
        }

        Text(
            "Audio stays on this phone.",
            modifier = Modifier.padding(top = 24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp
        )
    }
}

private fun PhoneCallPhase.label(): String = when (this) {
    PhoneCallPhase.RINGING -> "Incoming call"
    PhoneCallPhase.DIALING -> "Dialing…"
    PhoneCallPhase.CONNECTING -> "Connecting…"
    PhoneCallPhase.ACTIVE -> "Call in progress"
    PhoneCallPhase.HELD -> "On hold"
    PhoneCallPhase.DISCONNECTING -> "Ending…"
    PhoneCallPhase.DISCONNECTED -> "Call ended"
    else -> "Phone call"
}
