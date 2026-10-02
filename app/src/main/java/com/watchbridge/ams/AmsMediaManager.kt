package com.watchbridge.ams

import android.os.SystemClock
import android.util.Log
import com.watchbridge.ble.BleConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The iPhone's now-playing state and remote control, over Apple Media Service (AMS).
 *
 * Like ANCS, AMS is built into iOS: it covers any app that shows up in the iPhone's
 * lock screen media controls (Spotify, Apple Music, YouTube Music, podcasts, ...).
 */
class AmsMediaManager(private val connectionManager: BleConnectionManager) {

    companion object {
        private const val TAG = "AmsMediaManager"
    }

    data class MediaState(
        /** The iPhone exposes AMS and we're subscribed to it. */
        val available: Boolean = false,
        /** Name of the app playing media, empty when nothing is playing. */
        val playerName: String = "",
        val isPlaying: Boolean = false,
        val playbackRate: Float = 0f,
        val elapsedSeconds: Float = 0f,
        /** SystemClock.elapsedRealtime() when [elapsedSeconds] was reported. */
        val elapsedReportedAt: Long = 0L,
        /** 0..1, or null if unknown. */
        val volume: Float? = null,
        val title: String = "",
        val artist: String = "",
        val durationSeconds: Float = 0f,
        val supportedCommands: Set<Byte> = emptySet()
    ) {
        /** Some players report a track but no app name, so either one counts. */
        val hasPlayer: Boolean get() = playerName.isNotEmpty() || title.isNotEmpty()

        fun supports(command: Byte): Boolean = command in supportedCommands

        /** iOS only reports the position on changes, so extrapolate it from the playback rate. */
        fun elapsedAt(realtimeMillis: Long): Float {
            val elapsed = elapsedSeconds + (realtimeMillis - elapsedReportedAt) / 1000f * playbackRate
            return if (durationSeconds > 0f) elapsed.coerceIn(0f, durationSeconds) else elapsed.coerceAtLeast(0f)
        }
    }

    private val _state = MutableStateFlow(MediaState())
    val state: StateFlow<MediaState> = _state.asStateFlow()

    fun onSubscribed() {
        Log.i(TAG, "AMS subscribed")
        _state.update { it.copy(available = true) }
    }

    /** Disconnected from the iPhone: forget everything. */
    fun reset() {
        _state.value = MediaState()
    }

    /** Remote Command notification: the list of commands the current player supports. */
    fun onRemoteCommandsUpdate(data: ByteArray) {
        _state.update { it.copy(supportedCommands = data.toSet()) }
    }

    /**
     * Entity Update notification:
     *   Byte 0: EntityID, Byte 1: AttributeID, Byte 2: EntityUpdateFlags, Bytes 3+: UTF-8 value
     */
    fun onEntityUpdate(data: ByteArray) {
        if (data.size < 3) return
        val entityId = data[0]
        val attributeId = data[1]
        val flags = data[2].toInt()
        val value = String(data, 3, data.size - 3, Charsets.UTF_8)

        applyAttribute(entityId, attributeId, value)

        // The value didn't fit in one packet (e.g. a long song title on a small MTU)
        if (flags and AmsConstants.ENTITY_UPDATE_FLAG_TRUNCATED != 0) {
            connectionManager.readAmsAttribute(entityId, attributeId) { full ->
                applyAttribute(entityId, attributeId, full)
            }
        }
    }

    fun togglePlayPause() {
        val state = _state.value
        val command = when {
            state.supports(AmsConstants.COMMAND_TOGGLE_PLAY_PAUSE) -> AmsConstants.COMMAND_TOGGLE_PLAY_PAUSE
            state.isPlaying -> AmsConstants.COMMAND_PAUSE
            else -> AmsConstants.COMMAND_PLAY
        }
        sendCommand(command)
    }

    /** Fetch the current now playing from the phone again; see [BleConnectionManager.refreshAms]. */
    fun refresh() = connectionManager.refreshAms()

    fun play() = sendCommand(AmsConstants.COMMAND_PLAY)

    fun pause() = sendCommand(AmsConstants.COMMAND_PAUSE)

    fun nextTrack() = sendCommand(AmsConstants.COMMAND_NEXT_TRACK)

    fun previousTrack() = sendCommand(AmsConstants.COMMAND_PREVIOUS_TRACK)

    fun volumeUp() = sendCommand(AmsConstants.COMMAND_VOLUME_UP)

    fun volumeDown() = sendCommand(AmsConstants.COMMAND_VOLUME_DOWN)

    private fun sendCommand(command: Byte) {
        connectionManager.writeAmsRemoteCommand(command)
    }

    private fun applyAttribute(entityId: Byte, attributeId: Byte, value: String) {
        _state.update { state ->
            when (entityId) {
                AmsConstants.ENTITY_PLAYER -> when (attributeId) {
                    AmsConstants.PLAYER_ATTR_NAME -> state.copy(playerName = value)
                    AmsConstants.PLAYER_ATTR_PLAYBACK_INFO -> withPlaybackInfo(state, value)
                    AmsConstants.PLAYER_ATTR_VOLUME -> state.copy(volume = value.toFloatOrNull())
                    else -> state
                }
                AmsConstants.ENTITY_TRACK -> when (attributeId) {
                    AmsConstants.TRACK_ATTR_ARTIST -> state.copy(artist = value)
                    AmsConstants.TRACK_ATTR_TITLE -> state.copy(title = value)
                    AmsConstants.TRACK_ATTR_DURATION ->
                        state.copy(durationSeconds = value.toFloatOrNull() ?: 0f)
                    else -> state
                }
                else -> state
            }
        }
    }

    /** PlaybackInfo is "PlaybackState,PlaybackRate,ElapsedTime", e.g. "1,1.0,42.5". */
    private fun withPlaybackInfo(state: MediaState, value: String): MediaState {
        val parts = value.split(',')
        val playbackState = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return state
        return state.copy(
            isPlaying = playbackState != AmsConstants.PLAYBACK_STATE_PAUSED,
            playbackRate = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: 0f,
            elapsedSeconds = parts.getOrNull(2)?.trim()?.toFloatOrNull() ?: 0f,
            elapsedReportedAt = SystemClock.elapsedRealtime()
        )
    }
}
