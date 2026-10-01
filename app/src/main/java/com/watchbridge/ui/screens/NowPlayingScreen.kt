package com.watchbridge.ui.screens

import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactButton
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.watchbridge.R
import com.watchbridge.ams.AmsConstants
import com.watchbridge.ams.AmsMediaManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

/** Rotary scroll needed for one volume step; a bezel click or a short crown turn. */
private const val ROTARY_VOLUME_STEP_PX = 50f

/**
 * Remote control for whatever is playing on the iPhone (via Apple Media Service).
 * The rotating bezel / crown changes the volume.
 */
@Composable
fun NowPlayingScreen(media: AmsMediaManager?) {
    val fallback = remember { MutableStateFlow(AmsMediaManager.MediaState()) }
    val state by (media?.state ?: fallback).collectAsState()

    // iOS only reports the position on changes, so tick it locally while playing
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.isPlaying, state.elapsedReportedAt) {
        now = SystemClock.elapsedRealtime()
        while (state.isPlaying) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }

    val focusRequester = remember { FocusRequester() }
    var rotaryScroll by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        timeText = { TimeText() },
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                rotaryScroll += event.verticalScrollPixels
                if (rotaryScroll >= ROTARY_VOLUME_STEP_PX) {
                    media?.volumeUp()
                    rotaryScroll = 0f
                } else if (rotaryScroll <= -ROTARY_VOLUME_STEP_PX) {
                    media?.volumeDown()
                    rotaryScroll = 0f
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (state.hasPlayer && state.durationSeconds > 0f) {
                // Gap at the top leaves room for the time
                CircularProgressIndicator(
                    progress = (state.elapsedAt(now) / state.durationSeconds).coerceIn(0f, 1f),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(3.dp),
                    startAngle = 300f,
                    endAngle = 240f,
                    strokeWidth = 4.dp
                )
            }

            when {
                !state.available -> EmptyState(
                    title = "Media unavailable",
                    detail = "Connect to your iPhone first"
                )
                !state.hasPlayer -> EmptyState(
                    title = "Nothing playing",
                    detail = "Start music on your iPhone"
                )
                else -> PlayerControls(state = state, media = media)
            }
        }
    }
}

@Composable
private fun PlayerControls(state: AmsMediaManager.MediaState, media: AmsMediaManager?) {
    // Until iOS tells us which commands the player supports, allow everything
    fun supported(command: Byte) = state.supportedCommands.isEmpty() || state.supports(command)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 20.dp)
    ) {
        Text(
            text = state.playerName,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = state.title.ifEmpty { "Unknown title" },
            style = MaterialTheme.typography.body1,
            color = MaterialTheme.colors.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (state.artist.isNotEmpty()) {
            Text(
                text = state.artist,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MediaButton(
                icon = R.drawable.ic_skip_previous,
                description = "Previous",
                size = 40.dp,
                primary = false,
                enabled = supported(AmsConstants.COMMAND_PREVIOUS_TRACK),
                onClick = { media?.previousTrack() }
            )
            MediaButton(
                icon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                description = if (state.isPlaying) "Pause" else "Play",
                size = ButtonDefaults.DefaultButtonSize,
                primary = true,
                enabled = true,
                onClick = { media?.togglePlayPause() }
            )
            MediaButton(
                icon = R.drawable.ic_skip_next,
                description = "Next",
                size = 40.dp,
                primary = false,
                enabled = supported(AmsConstants.COMMAND_NEXT_TRACK),
                onClick = { media?.nextTrack() }
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            CompactButton(
                onClick = { media?.volumeDown() },
                enabled = supported(AmsConstants.COMMAND_VOLUME_DOWN),
                colors = ButtonDefaults.secondaryButtonColors(),
                backgroundPadding = 4.dp
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_volume_down),
                    contentDescription = "Volume down",
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = state.volume?.let { "${(it * 100).roundToInt()}%" } ?: "Vol",
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            CompactButton(
                onClick = { media?.volumeUp() },
                enabled = supported(AmsConstants.COMMAND_VOLUME_UP),
                colors = ButtonDefaults.secondaryButtonColors(),
                backgroundPadding = 4.dp
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_volume_up),
                    contentDescription = "Volume up",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun MediaButton(
    @DrawableRes icon: Int,
    description: String,
    size: Dp,
    primary: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (primary) ButtonDefaults.primaryButtonColors() else ButtonDefaults.secondaryButtonColors(),
        modifier = Modifier.size(size)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(if (primary) 26.dp else 20.dp)
        )
    }
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 24.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_music_note),
            contentDescription = null,
            tint = MaterialTheme.colors.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.title3,
            color = MaterialTheme.colors.onSurface,
            textAlign = TextAlign.Center
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
