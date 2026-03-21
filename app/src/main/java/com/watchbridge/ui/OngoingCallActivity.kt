package com.watchbridge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.ui.theme.WatchBridgeTheme
import kotlinx.coroutines.delay

class OngoingCallActivity : ComponentActivity() {

    private var callUid: Int = -1
    private var callerName: String = "Active Call"

    private val callDismissedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)
        setContent {
            WatchBridgeTheme {
                OngoingCallScreen(
                    callerName = callerName,
                    onHangUp = { performAction(AncsConstants.ACTION_NEGATIVE) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readExtras(intent)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            callDismissedReceiver,
            IntentFilter("com.watchbridge.CALL_DISMISSED"),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(callDismissedReceiver)
    }

    private fun readExtras(intent: Intent) {
        callUid = intent.getIntExtra("call_uid", -1)
        callerName = intent.getStringExtra("caller_name") ?: "Active Call"
    }

    private fun performAction(actionId: Byte) {
        val intent = Intent("com.watchbridge.ACTION_PERFORM").apply {
            setPackage(packageName)
            putExtra("notification_uid", callUid)
            putExtra("action_id", actionId)
            putExtra("is_call", true)
        }
        sendBroadcast(intent)
        finish()
    }
}

@Composable
private fun OngoingCallScreen(
    callerName: String,
    onHangUp: () -> Unit
) {
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSeconds++
        }
    }

    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val timerText = "%d:%02d".format(minutes, seconds)

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = callerName,
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center
            )
            Text(
                text = timerText,
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            // Hang up button (red)
            Button(
                onClick = onHangUp,
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFFD32F2F)
                ),
                modifier = Modifier.size(48.dp)
            ) {
                Text("✕", style = MaterialTheme.typography.title3)
            }
        }
    }
}
