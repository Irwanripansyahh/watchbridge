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

    private companion object {
        const val PREFS_NAME = "camera_remote"
        const val KEY_TIMER_SECONDS = "timer_seconds"
    }

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

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setContent {
            WatchBridgeTheme {
                val status by remote.status.collectAsState()
                CameraRemoteScreen(
                    status = status,
                    initialTimerSeconds = prefs.getInt(KEY_TIMER_SECONDS, 0),
                    onTimerChange = { prefs.edit().putInt(KEY_TIMER_SECONDS, it).apply() },
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

/** Self-timer choices, cycled by the chip: 0 = take the photo right away. */
private val TIMER_OPTIONS = listOf(0, 3, 5)

@Composable
private fun CameraRemoteScreen(
    status: CameraRemote.Status,
    initialTimerSeconds: Int,
    onTimerChange: (Int) -> Unit,
    onShutter: () -> Boolean,
    onRetry: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var timerSeconds by remember {
        mutableIntStateOf(initialTimerSeconds.takeIf { it in TIMER_OPTIONS } ?: 0)
    }
    var countdown by remember { mutableIntStateOf(0) }

    fun takePhoto() {
        if (onShutter()) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            if (countdown == 1) {
                takePhoto()
            } else {
                // A light tick each second, so the countdown can be felt without looking
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
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
                            !connected -> onRetry()
                            // Tapping again during the countdown cancels it
                            countdown > 0 -> countdown = 0
                            timerSeconds == 0 -> takePhoto()
                            else -> countdown = timerSeconds
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
                    onClick = {
                        val next = TIMER_OPTIONS[(TIMER_OPTIONS.indexOf(timerSeconds) + 1) % TIMER_OPTIONS.size]
                        timerSeconds = next
                        onTimerChange(next)
                    },
                    enabled = countdown == 0,
                    label = { Text(if (timerSeconds == 0) "Instant" else "Timer ${timerSeconds}s") },
                    colors = ChipDefaults.secondaryChipColors()
                )
            }
        }
    }
}
