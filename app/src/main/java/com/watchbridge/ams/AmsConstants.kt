package com.watchbridge.ams

import java.util.UUID

/**
 * Apple Media Service (AMS) protocol constants.
 * Reference: Apple Media Service Specification
 */
object AmsConstants {

    val AMS_SERVICE_UUID: UUID =
        UUID.fromString("89D3502B-0F36-433A-8EF4-C502AD55F8DC")

    /** Writeable, notifiable. Watch -> iPhone: commands. iPhone -> Watch: supported commands. */
    val REMOTE_COMMAND_UUID: UUID =
        UUID.fromString("9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2")

    /** Writeable with response, notifiable. Subscribe to attributes / receive their values. */
    val ENTITY_UPDATE_UUID: UUID =
        UUID.fromString("2F7CABCE-808D-411F-9A0C-BB92BA96C102")

    /** Readable, writeable. Fetches the full value of an attribute that was truncated. */
    val ENTITY_ATTRIBUTE_UUID: UUID =
        UUID.fromString("C6B2F38C-23AB-46D8-A6AB-A3A870BBD5D7")

    // --- RemoteCommandID ---
    const val COMMAND_PLAY: Byte = 0
    const val COMMAND_PAUSE: Byte = 1
    const val COMMAND_TOGGLE_PLAY_PAUSE: Byte = 2
    const val COMMAND_NEXT_TRACK: Byte = 3
    const val COMMAND_PREVIOUS_TRACK: Byte = 4
    const val COMMAND_VOLUME_UP: Byte = 5
    const val COMMAND_VOLUME_DOWN: Byte = 6

    // --- EntityID ---
    const val ENTITY_PLAYER: Byte = 0
    const val ENTITY_QUEUE: Byte = 1
    const val ENTITY_TRACK: Byte = 2

    // --- Player AttributeID ---
    const val PLAYER_ATTR_NAME: Byte = 0
    const val PLAYER_ATTR_PLAYBACK_INFO: Byte = 1
    const val PLAYER_ATTR_VOLUME: Byte = 2

    // --- Track AttributeID ---
    const val TRACK_ATTR_ARTIST: Byte = 0
    const val TRACK_ATTR_ALBUM: Byte = 1
    const val TRACK_ATTR_TITLE: Byte = 2
    const val TRACK_ATTR_DURATION: Byte = 3

    // --- EntityUpdateFlags ---
    const val ENTITY_UPDATE_FLAG_TRUNCATED: Int = 1 shl 0

    // --- PlaybackState (first field of PlaybackInfo) ---
    const val PLAYBACK_STATE_PAUSED = 0
    const val PLAYBACK_STATE_PLAYING = 1
    const val PLAYBACK_STATE_REWINDING = 2
    const val PLAYBACK_STATE_FAST_FORWARDING = 3
}
