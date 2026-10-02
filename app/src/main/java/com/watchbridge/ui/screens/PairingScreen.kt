package com.watchbridge.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchbridge.ble.BleScanner
import com.watchbridge.ble.ConnectionStateMachine
import com.watchbridge.call.WatchCalls
import com.watchbridge.service.WatchBridgeService
import com.watchbridge.ui.components.WatchBridgeScaffold
import com.watchbridge.ui.theme.SurfaceCard
import kotlinx.coroutines.delay

@SuppressLint("MissingPermission")
@Composable
fun PairingScreen(
    onStartAdvertising: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnected: () -> Unit
) {
    val context = LocalContext.current
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter = bluetoothManager.adapter

    val smState by (WatchBridgeService.stateMachine?.state
        ?: kotlinx.coroutines.flow.MutableStateFlow(ConnectionStateMachine.State.IDLE))
        .collectAsState()

    val bondedDevices = adapter?.bondedDevices?.map { dev ->
        BleScanner.ScannedDevice(
            name = dev.name ?: "Bonded Device",
            address = dev.address,
            rssi = 0
        )
    } ?: emptyList()

    // With "Answer calls on watch" on, pairing ends with one more step: making the watch the
    // phone's call audio. Often that link comes up with the pairing; this covers when it doesn't.
    var showCallStep by remember { mutableStateOf(false) }
    val watchName = adapter?.name ?: "this watch"

    LaunchedEffect(smState) {
        if (smState == ConnectionStateMachine.State.READY) {
            delay(500)
            if (WatchCalls.isEnabled(context)) showCallStep = true else onConnected()
        }
    }

    val listState = rememberScalingLazyListState()

    WatchBridgeScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 48.dp),
            autoCentering = AutoCenteringParams()
        ) {
            item {
                Text(
                    text = "Pair with phone",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center
                )
            }

            if (showCallStep) {
                item { CallAudioStep(watchName = watchName, onDone = onConnected) }
                return@ScalingLazyColumn
            }

            // Previously paired devices
            if (bondedDevices.isNotEmpty()) {
                item {
                    Text(
                        text = "Previously Paired",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                items(bondedDevices) { device ->
                    Chip(
                        onClick = {
                            val btDevice = adapter.getRemoteDevice(device.address)
                            onDeviceSelected(btDevice)
                        },
                        label = {
                            Text(
                                text = device.name ?: "Bonded Device",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = device.address,
                                style = MaterialTheme.typography.caption3
                            )
                        },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            when (smState) {
                ConnectionStateMachine.State.ADVERTISING -> {
                    item {
                        AdvertisingContent()
                    }
                    item {
                        Spacer(Modifier.height(12.dp))
                        Chip(
                            onClick = {
                                val adv = WatchBridgeService.advertiser
                                val sm = WatchBridgeService.stateMachine
                                if (adv != null && sm != null) {
                                    sm.stopAdvertising(adv)
                                }
                            },
                            label = {
                                Text(
                                    text = "Cancel",
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                ConnectionStateMachine.State.CONNECTING,
                ConnectionStateMachine.State.CONNECTED -> {
                    item {
                        Spacer(Modifier.height(16.dp))
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Connecting...\nAccept pairing on phone\nif prompted",
                            style = MaterialTheme.typography.body2,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colors.onSurfaceVariant
                        )
                    }
                }

                else -> {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Chip(
                            onClick = onStartAdvertising,
                            label = {
                                Text(
                                    text = "Start Pairing",
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            },
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Makes this watch visible\nto your phone",
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallAudioStep(watchName: String, onDone: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Paired!",
            style = MaterialTheme.typography.title3,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "One more step for calls",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    // The phone lists the watch once, under the name it was paired with
                    // ("WatchBridge"); that one entry also carries the call audio
                    text = "On your phone: Settings → Bluetooth.\n" +
                        "The watch you just paired (\"WatchBridge\" or \"$watchName\") " +
                        "should say Connected. If it says Not Connected, tap it.",
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Chip(
            onClick = onDone,
            label = {
                Text(
                    text = "Done",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Already Connected? Just tap Done.",
            style = MaterialTheme.typography.caption2,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onSurfaceVariant
        )
    }
}

@Composable
private fun AdvertisingContent() {
    val transition = rememberInfiniteTransition(label = "advPulse")
    val pulseAlpha by transition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Spacer(Modifier.height(8.dp))
        Box(modifier = Modifier.alpha(pulseAlpha)) {
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Ready to Pair",
            style = MaterialTheme.typography.body1,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.primary
        )
        Spacer(Modifier.height(8.dp))

        // Consolidated instruction card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceCard, RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "On your phone:",
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "1. Open Settings\n2. Tap Bluetooth\n3. Tap this watch to pair",
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colors.onSurfaceVariant
                )
            }
        }
    }
}
