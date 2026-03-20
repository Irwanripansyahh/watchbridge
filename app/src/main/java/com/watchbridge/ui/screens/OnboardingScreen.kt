package com.watchbridge.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                text = "WatchBridge",
                style = MaterialTheme.typography.title2,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp)
            )
        }

        item {
            Text(
                text = "iPhone notifications\non your Galaxy Watch",
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Setup Steps",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        val steps = listOf(
            "1" to "Grant Bluetooth permissions when prompted",
            "2" to "Tap 'Connect' and select your iPhone from the scan list",
            "3" to "Accept the pairing request on your iPhone",
            "4" to "Notifications will appear on your watch automatically"
        )

        for ((num, text) in steps) {
            item {
                Chip(
                    onClick = { },
                    label = {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.caption2,
                            maxLines = 3
                        )
                    },
                    icon = {
                        Text(
                            text = num,
                            style = MaterialTheme.typography.title3,
                            color = MaterialTheme.colors.primary
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Chip(
                onClick = { },
                label = {
                    Text(
                        text = "Keep iPhone Bluetooth on and nearby for a stable connection",
                        style = MaterialTheme.typography.caption3,
                        color = Color(0xFFFFB74D),
                        maxLines = 3
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onComplete) {
                Text("Get Started")
            }
        }
    }
}
