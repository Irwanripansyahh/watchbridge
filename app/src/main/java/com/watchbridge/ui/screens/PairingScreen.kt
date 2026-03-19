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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.watchbridge.ble.BleScanner
import com.watchbridge.ble.ConnectionStateMachine
import com.watchbridge.service.WatchBridgeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onCompletion

@SuppressLint("MissingPermission")
@Composable
fun PairingScreen(
    bleScanner: BleScanner,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onConnected: () -> Unit
) {
    val context = LocalContext.current
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter = bluetoothManager.adapter

    val smState by (WatchBridgeService.stateMachine?.state
        ?: kotlinx.coroutines.flow.MutableStateFlow(ConnectionStateMachine.State.IDLE))
        .collectAsState()

    val devices = remember { mutableStateListOf<BleScanner.ScannedDevice>() }
    var isScanning by remember { mutableStateOf(false) }

    // Navigate back when connection is ready
    LaunchedEffect(smState) {
        if (smState == ConnectionStateMachine.State.READY) {
            delay(500)
            onConnected()
        }
    }

    // Auto-start scan
    LaunchedEffect(Unit) {
        isScanning = true
        devices.clear()
        bleScanner.scan()
            .catch { isScanning = false }
            .onCompletion { isScanning = false }
            .collect { device ->
                if (device.name != null && devices.none { it.address == device.address }) {
                    devices.add(device)
                }
            }
    }

    // Stop scan after 15 seconds
    LaunchedEffect(isScanning) {
        if (isScanning) {
            delay(15_000)
            isScanning = false
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                text = "Find iPhone",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp)
            )
        }

        when {
            smState == ConnectionStateMachine.State.CONNECTING ||
            smState == ConnectionStateMachine.State.CONNECTED -> {
                item {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Connecting...\nAccept pairing on iPhone",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
            }

            isScanning && devices.isEmpty() -> {
                item {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Scanning...",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
            }

            else -> {
                if (devices.isEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "No devices found",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = {
                            devices.clear()
                            isScanning = true
                        }) {
                            Text("Retry")
                        }
                    }
                }

                items(devices) { device ->
                    Chip(
                        onClick = {
                            val btDevice = adapter.getRemoteDevice(device.address)
                            onDeviceSelected(btDevice)
                        },
                        label = {
                            Text(
                                text = device.name ?: "Unknown",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = "${device.address} (${device.rssi} dBm)",
                                style = MaterialTheme.typography.caption3
                            )
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
