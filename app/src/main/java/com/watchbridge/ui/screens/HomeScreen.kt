package com.watchbridge.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchbridge.ble.ConnectionStateMachine
import com.watchbridge.service.WatchBridgeService

@Composable
fun HomeScreen(
    onNavigateToPairing: () -> Unit,
    onDisconnect: () -> Unit
) {
    val sm = WatchBridgeService.stateMachine
    val session = WatchBridgeService.sessionManager

    val smState by (sm?.state
        ?: kotlinx.coroutines.flow.MutableStateFlow(ConnectionStateMachine.State.IDLE))
        .collectAsState()

    val eventLog by (session?.eventLog
        ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList()))
        .collectAsState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        item {
            Text(
                text = "WatchBridge",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp)
            )
        }

        // Connection status
        item {
            val (statusText, statusColor) = when (smState) {
                ConnectionStateMachine.State.IDLE -> "Not Connected" to Color.Gray
                ConnectionStateMachine.State.CONNECTING -> "Connecting..." to Color.Yellow
                ConnectionStateMachine.State.CONNECTED -> "Discovering..." to Color.Yellow
                ConnectionStateMachine.State.READY -> "Connected" to Color(0xFF4FC3F7)
                ConnectionStateMachine.State.DISCONNECTED -> "Disconnected" to Color(0xFFEF5350)
                ConnectionStateMachine.State.WAITING_TO_RECONNECT -> "Reconnecting soon..." to Color(0xFFFFB74D)
                ConnectionStateMachine.State.RECONNECTING -> "Reconnecting..." to Color.Yellow
                ConnectionStateMachine.State.FAILED -> "Connection Failed" to Color(0xFFEF5350)
            }

            Text(
                text = statusText,
                style = MaterialTheme.typography.body1,
                color = statusColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // Active notifications count
        item {
            val activeCount = session?.getActiveUidCount() ?: 0
            if (activeCount > 0) {
                Text(
                    text = "$activeCount active notification${if (activeCount != 1) "s" else ""}",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Action buttons
        item {
            Spacer(Modifier.height(8.dp))
            when (smState) {
                ConnectionStateMachine.State.IDLE,
                ConnectionStateMachine.State.FAILED -> {
                    Button(onClick = onNavigateToPairing) {
                        Text("Connect")
                    }
                }
                ConnectionStateMachine.State.READY -> {
                    Button(onClick = onDisconnect) {
                        Text("Disconnect")
                    }
                }
                ConnectionStateMachine.State.DISCONNECTED -> {
                    // Auto-reconnect is active, but offer manual connect
                    Button(onClick = onNavigateToPairing) {
                        Text("New Device")
                    }
                }
                else -> {
                    Text(
                        text = "Please wait...",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
            }
        }

        // Event log
        if (eventLog.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Recent Events",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.primary
                )
            }

            items(eventLog.reversed()) { entry ->
                Chip(
                    onClick = { },
                    label = {
                        Text(
                            text = entry,
                            style = MaterialTheme.typography.caption3,
                            maxLines = 2
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
