package com.watchbridge.ui.components

import androidx.compose.runtime.Composable
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition

@Composable
fun WatchBridgeScaffold(
    listState: ScalingLazyListState,
    showTimeText: Boolean = true,
    content: @Composable () -> Unit
) {
    Scaffold(
        timeText = {
            if (showTimeText) {
                TimeText()
            }
        },
        vignette = {
            Vignette(vignettePosition = VignettePosition.TopAndBottom)
        },
        positionIndicator = {
            PositionIndicator(scalingLazyListState = listState)
        },
        content = content
    )
}
