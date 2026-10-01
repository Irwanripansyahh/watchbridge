package com.watchbridge.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchbridge.R
import com.watchbridge.ams.AmsMediaManager
import com.watchbridge.ble.ConnectionStateMachine
import com.watchbridge.service.WatchBridgeService
import com.watchbridge.ui.components.WatchBridgeScaffold
import com.watchbridge.ui.theme.StatusConnected
import com.watchbridge.ui.theme.StatusConnecting
import com.watchbridge.ui.theme.StatusDisconnected
import com.watchbridge.ui.theme.StatusIdle
import com.watchbridge.ui.theme.SurfaceCard
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun HomeScreen(
    onNavigateToPairing: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToMedia: () -> Unit,
    onReconnect: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onDisconnect: () -> Unit
) {
    val sm = WatchBridgeService.stateMachine
    val session = WatchBridgeService.sessionManager
    val noMedia = remember { MutableStateFlow(AmsMediaManager.MediaState()) }
    val media by (WatchBridgeService.mediaManager?.state ?: noMedia).collectAsState()

    val smState by (sm?.state
        ?: kotlinx.coroutines.flow.MutableStateFlow(ConnectionStateMachine.State.IDLE))
        .collectAsState()

    val listState = rememberScalingLazyListState()

    WatchBridgeScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 48.dp),
            autoCentering = AutoCenteringParams()
        ) {
            // Title
            item {
                Text(
                    text = "WatchBridge",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center
                )
            }

            // Status card
            item {
                Spacer(Modifier.height(8.dp))
                StatusCard(smState = smState, activeCount = session?.getActiveUidCount() ?: 0)
            }

            // Action button
            item {
                Spacer(Modifier.height(8.dp))
                ActionChip(
                    smState = smState,
                    onNavigateToPairing = onNavigateToPairing,
                    onReconnect = onReconnect,
                    onTurnOnBluetooth = onTurnOnBluetooth,
                    onDisconnect = onDisconnect
                )
            }

            // iPhone media controls
            item {
                Spacer(Modifier.height(4.dp))
                Chip(
                    onClick = onNavigateToMedia,
                    label = { Text("Now Playing") },
                    secondaryLabel = {
                        Text(
                            text = when {
                                media.title.isNotEmpty() -> media.title
                                media.hasPlayer -> media.playerName
                                else -> "Control phone music"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Settings chip
            item {
                Spacer(Modifier.height(4.dp))
                Chip(
                    onClick = onNavigateToSettings,
                    label = { Text("Settings") },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun StatusCard(smState: ConnectionStateMachine.State, activeCount: Int) {
    val (statusText, statusColor, isPulsing) = when (smState) {
        ConnectionStateMachine.State.IDLE -> Triple("Not Connected", StatusIdle, false)
        ConnectionStateMachine.State.ADVERTISING -> Triple("Advertising...", StatusConnecting, true)
        ConnectionStateMachine.State.CONNECTING -> Triple("Connecting...", StatusConnecting, true)
        ConnectionStateMachine.State.CONNECTED -> Triple("Discovering...", StatusConnecting, true)
        ConnectionStateMachine.State.READY -> Triple("Connected", StatusConnected, false)
        ConnectionStateMachine.State.DISCONNECTED -> Triple("Disconnected", StatusDisconnected, false)
        ConnectionStateMachine.State.WAITING_TO_RECONNECT -> Triple("Reconnecting soon...", Color(0xFFFFB74D), true)
        ConnectionStateMachine.State.RECONNECTING -> Triple("Reconnecting...", StatusConnecting, true)
        ConnectionStateMachine.State.WAITING_FOR_PHONE -> Triple("Waiting for phone", Color(0xFFFFB74D), true)
        ConnectionStateMachine.State.BLUETOOTH_OFF -> Triple("Bluetooth Off", StatusDisconnected, false)
        ConnectionStateMachine.State.FAILED -> Triple("Not Paired", StatusDisconnected, false)
    }

    val dotAlpha = if (isPulsing) {
        val transition = rememberInfiniteTransition(label = "pulse")
        val alpha by transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )
        alpha
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceCard, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dotAlpha)
                        .background(statusColor, CircleShape)
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.body1,
                    color = statusColor,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (activeCount > 0)
                    "$activeCount active notification${if (activeCount != 1) "s" else ""}"
                else
                    "No notifications",
                style = MaterialTheme.typography.caption3,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActionChip(
    smState: ConnectionStateMachine.State,
    onNavigateToPairing: () -> Unit,
    onReconnect: () -> Unit,
    onTurnOnBluetooth: () -> Unit,
    onDisconnect: () -> Unit
) {
    when (smState) {
        ConnectionStateMachine.State.BLUETOOTH_OFF -> {
            Chip(
                onClick = onTurnOnBluetooth,
                label = { Text("Turn on Bluetooth") },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_bluetooth),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        ConnectionStateMachine.State.IDLE,
        ConnectionStateMachine.State.FAILED -> {
            Chip(
                onClick = onNavigateToPairing,
                label = { Text("Connect") },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_bluetooth),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        ConnectionStateMachine.State.READY -> {
            Chip(
                onClick = onDisconnect,
                label = { Text("Disconnect") },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_bluetooth_disabled),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        ConnectionStateMachine.State.DISCONNECTED,
        ConnectionStateMachine.State.WAITING_TO_RECONNECT,
        ConnectionStateMachine.State.WAITING_FOR_PHONE -> {
            Chip(
                onClick = onReconnect,
                label = { Text("Reconnect now") },
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_bluetooth),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        else -> {
            Text(
                text = "Please wait...",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
