package com.watchbridge.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.watchbridge.R
import com.watchbridge.camera.CameraRemote
import com.watchbridge.ui.theme.StatusConnected
import com.watchbridge.ui.theme.StatusConnecting
import com.watchbridge.ui.theme.StatusDisconnected
import com.watchbridge.ui.theme.WatchBridgeTheme
import kotlinx.coroutines.delay

/**
 * The "Camera Remote" launcher icon: a shutter button for the iPhone Camera app.
 * The remote is only active while this screen is open.
 */
class CameraRemoteActivity : ComponentActivity() {

    private lateinit var remote: CameraRemote

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) remote.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remote = CameraRemote(this)
        // Framing a shot takes a while; don't let the watch screen go dark meanwhile
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            WatchBridgeTheme {
                val status by remote.status.collectAsState()
                CameraRemoteScreen(
                    status = status,
                    onShutter = remote::shutter,
                    onRetry = remote::reconnect
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            == PackageManager.PERMISSION_GRANTED
        ) {
            remote.start()
        } else {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    override fun onStop() {
        // Don't stay connected to the iPhone as an input device in the background
        remote.stop()
        super.onStop()
    }
}

private const val TIMER_SECONDS = 3

@Composable
private fun CameraRemoteScreen(
    status: CameraRemote.Status,
    onShutter: () -> Boolean,
    onRetry: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var countdown by remember { mutableIntStateOf(0) }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            if (countdown == 1) {
                if (onShutter()) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            countdown -= 1
        }
    }

    val connected = status == CameraRemote.Status.CONNECTED
    val (statusText, statusColor) = when (status) {
        CameraRemote.Status.CONNECTED -> "Open Camera on your iPhone" to StatusConnected
        CameraRemote.Status.CONNECTING -> "Connecting to iPhone…" to StatusConnecting
        CameraRemote.Status.DISCONNECTED -> "Not connected · tap to retry" to StatusDisconnected
        CameraRemote.Status.NO_IPHONE -> "No paired iPhone" to StatusDisconnected
        CameraRemote.Status.UNSUPPORTED -> "Not supported on this watch" to StatusDisconnected
    }

    Scaffold(timeText = { TimeText() }) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.caption2,
                    color = statusColor,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        when {
                            connected && countdown == 0 -> {
                                if (onShutter()) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            !connected -> onRetry()
                        }
                    },
                    colors = ButtonDefaults.primaryButtonColors(),
                    modifier = Modifier.size(88.dp)
                ) {
                    if (countdown > 0) {
                        Text(text = "$countdown", style = MaterialTheme.typography.display1)
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_camera),
                            contentDescription = "Take photo",
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                CompactChip(
                    onClick = { if (countdown == 0) countdown = TIMER_SECONDS else countdown = 0 },
                    enabled = connected,
                    label = { Text(if (countdown > 0) "Cancel" else "Timer ${TIMER_SECONDS}s") },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}
