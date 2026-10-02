package com.watchbridge.ams

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.VolumeProviderCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.watchbridge.R
import com.watchbridge.WatchBridgeApp
import com.watchbridge.service.WatchBridgeService
import com.watchbridge.ui.MediaActivity
import kotlin.math.roundToInt

/**
 * Mirrors the phone's now playing (from AMS) into an Android MediaSession on the watch:
 * - the watch's own media controls (and anything else that reads media sessions) show the
 *   track and control it — play/pause, skip and volume all go back to the phone;
 * - a media notification with an Ongoing Activity puts a music icon at the bottom of the
 *   watch face while something plays, which opens Music Control.
 */
class MediaSessionBridge(private val context: Context, private val media: AmsMediaManager) {

    companion object {
        private const val TAG = "WatchBridgeMedia"
        private const val NOTIFICATION_ID = 3

        /** iOS changes the volume in 16 steps. */
        private const val VOLUME_STEPS = 16

        const val ACTION_PLAY_PAUSE = "com.watchbridge.media.PLAY_PAUSE"
        const val ACTION_NEXT = "com.watchbridge.media.NEXT"
        const val ACTION_PREVIOUS = "com.watchbridge.media.PREVIOUS"
    }

    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    private val session = MediaSessionCompat(context, TAG).apply {
        setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() = media.play()
            override fun onPause() = media.pause()
            override fun onStop() = media.pause()
            override fun onSkipToNext() = media.nextTrack()
            override fun onSkipToPrevious() = media.previousTrack()
        })
        setSessionActivity(openPlayer())
    }

    /** The watch's volume controls (e.g. the crown in media controls) change the phone's volume. */
    private val volumeProvider = object : VolumeProviderCompat(
        VOLUME_CONTROL_RELATIVE, VOLUME_STEPS, VOLUME_STEPS / 2
    ) {
        override fun onAdjustVolume(direction: Int) {
            when {
                direction > 0 -> media.volumeUp()
                direction < 0 -> media.volumeDown()
            }
        }
    }

    /** What the notification last showed, so it's only re-posted when that changes. */
    private var notifiedKey: List<Any?>? = null

    init {
        session.setPlaybackToRemote(volumeProvider)
    }

    fun update(state: AmsMediaManager.MediaState) {
        // Nothing to show until a track is loaded on the phone
        if (!state.available || !state.hasPlayer || state.title.isEmpty()) {
            hide()
            return
        }

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, state.playerName)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (state.durationSeconds * 1000).toLong())
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (state.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    (state.elapsedSeconds * 1000).toLong(),
                    if (state.isPlaying) state.playbackRate else 0f,
                    state.elapsedReportedAt.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
                )
                .build()
        )
        state.volume?.let { volumeProvider.currentVolume = (it * VOLUME_STEPS).roundToInt() }
        if (!session.isActive) session.isActive = true

        val key = listOf(state.title, state.artist, state.playerName, state.isPlaying)
        if (key != notifiedKey) {
            notifiedKey = key
            postNotification(state)
        }
    }

    fun release() {
        hide()
        session.release()
    }

    private fun hide() {
        if (session.isActive) session.isActive = false
        if (notifiedKey != null) {
            notifiedKey = null
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    private fun postNotification(state: AmsMediaManager.MediaState) {
        val open = openPlayer()
        val builder = NotificationCompat.Builder(context, WatchBridgeApp.MEDIA_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(state.title)
            .setContentText(state.artist.ifEmpty { state.playerName })
            .setSubText(state.playerName)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            // Ongoing (and on the watch face) while playing; can be swiped away when paused
            .setOngoing(state.isPlaying)
            .addAction(R.drawable.ic_skip_previous, "Previous", action(ACTION_PREVIOUS))
            .addAction(
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (state.isPlaying) "Pause" else "Play",
                action(ACTION_PLAY_PAUSE)
            )
            .addAction(R.drawable.ic_skip_next, "Next", action(ACTION_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        if (state.isPlaying) {
            // The music icon at the bottom of the watch face
            OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_music_note)
                .setTouchIntent(open)
                .setStatus(Status.forPart(Status.TextPart(state.title)))
                .build()
                .apply(context)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun openPlayer(): PendingIntent =
        PendingIntent.getActivity(
            context, 0, Intent(context, MediaActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun action(action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, MediaActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}

/** Buttons on the media notification. */
class MediaActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val media = WatchBridgeService.mediaManager ?: return
        when (intent.action) {
            MediaSessionBridge.ACTION_PLAY_PAUSE -> media.togglePlayPause()
            MediaSessionBridge.ACTION_NEXT -> media.nextTrack()
            MediaSessionBridge.ACTION_PREVIOUS -> media.previousTrack()
        }
    }
}
