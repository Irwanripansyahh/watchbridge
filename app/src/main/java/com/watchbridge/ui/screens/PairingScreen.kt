package com.watchbridge.ui.screens

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchbridge.ble.BleScanner
import com.watchbridge.ble.ConnectionStateMachine
import com.watchbridge.service.WatchBridgeService
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

    // Show previously bonded devices
    val bondedDevices = adapter?.bondedDevices?.map { dev ->
        BleScanner.ScannedDevice(
            name = dev.name ?: "Bonded Device",
            address = dev.address,
            rssi = 0
        )
    } ?: emptyList()

    // Navigate back when connection is ready
    LaunchedEffect(smState) {
        if (smState == ConnectionStateMachine.State.READY) {
            delay(500)
            onConnected()
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                text = "Pair with iPhone",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp)
            )
        }

        // Previously paired devices — use existing outbound connectTo() path
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
                // Advertising — show instructions for pairing from iPhone
                item {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Ready to Pair",
                        style = MaterialTheme.typography.body1,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.primary
                    )
                }
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "On your iPhone:",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurface
                    )
                }
                item {
                    Text(
                        text = "1. Open Settings",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
                item {
                    Text(
                        text = "2. Tap Bluetooth",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
                item {
                    Text(
                        text = "3. Tap this watch to pair",
                        style = MaterialTheme.typography.caption1,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val adv = WatchBridgeService.advertiser
                            val sm = WatchBridgeService.stateMachine
                            if (adv != null && sm != null) {
                                sm.stopAdvertising(adv)
                            }
                        },
                        colors = ButtonDefaults.secondaryButtonColors()
                    ) {
                        Text("Cancel")
                    }
                }
            }

            ConnectionStateMachine.State.CONNECTING,
            ConnectionStateMachine.State.CONNECTED -> {
                item {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Connecting...\nAccept pairing on iPhone\nif prompted",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
            }

            else -> {
                // Initial state — show "Start Pairing" button
                item {
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onStartAdvertising) {
                        Text("Start Pairing")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Makes this watch visible\nto your iPhone",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}
