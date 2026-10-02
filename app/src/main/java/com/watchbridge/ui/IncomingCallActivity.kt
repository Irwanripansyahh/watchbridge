package com.watchbridge.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchbridge.R
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.call.WatchCalls
import com.watchbridge.ui.theme.WatchBridgeTheme

class IncomingCallActivity : ComponentActivity() {

    private var callUid: Int = -1
    private var callerName: String = "Unknown Caller"

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
                IncomingCallScreen(
                    callerName = callerName,
                    onAccept = {
                        // Talk on the watch when it's the phone's hands-free device,
                        // otherwise answer on the phone
                        if (WatchCalls.answer(this)) finish() else performAction(AncsConstants.ACTION_POSITIVE)
                    },
                    onDecline = { performAction(AncsConstants.ACTION_NEGATIVE) }
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
        callerName = intent.getStringExtra("caller_name") ?: "Unknown Caller"
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
private fun IncomingCallScreen(
    callerName: String,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "ring")
    val ringAlpha by transition.animateFloat(
        initialValue = 0.0f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Incoming Call",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant
            )
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .border(
                        width = 2.dp,
                        color = MaterialTheme.colors.primary.copy(alpha = ringAlpha),
                        shape = CircleShape
                    )
                    .padding(12.dp)
            ) {
                Text(
                    text = callerName,
                    style = MaterialTheme.typography.title2,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Decline button (red)
                Button(
                    onClick = onDecline,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFFD32F2F)
                    ),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_call_decline),
                        contentDescription = "Decline",
                        modifier = Modifier.size(24.dp)
                    )
                }
                // Accept button (green)
                Button(
                    onClick = onAccept,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = Color(0xFF388E3C)
                    ),
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_call_accept),
                        contentDescription = "Accept",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
