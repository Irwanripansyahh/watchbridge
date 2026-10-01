package com.watchbridge.tile

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.MultiButtonLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.watchbridge.R
import com.watchbridge.ams.AmsConstants
import com.watchbridge.ams.AmsMediaManager
import com.watchbridge.service.WatchBridgeService
import com.watchbridge.ui.MediaActivity

/**
 * Tile with the iPhone's now playing track and previous / play-pause / next buttons.
 *
 * Tapping a button reloads the tile with that button's id, which is when the command is
 * sent to the iPhone (tiles can't run code on click otherwise).
 */
class MediaTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"

        private const val ID_PREVIOUS = "previous"
        private const val ID_PLAY_PAUSE = "play_pause"
        private const val ID_NEXT = "next"

        private const val ICON_PLAY = "play"
        private const val ICON_PAUSE = "pause"
        private const val ICON_PREVIOUS = "previous"
        private const val ICON_NEXT = "next"

        private const val COLOR_PRIMARY = 0xFF4FC3F7.toInt()
        private const val COLOR_TEXT = 0xFFFFFFFF.toInt()
        private const val COLOR_DETAIL = 0xFFB0B0B0.toInt()

        /** Ask the system to re-render the tile, e.g. when the track or play state changes. */
        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(MediaTileService::class.java)
        }
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val media = WatchBridgeService.mediaManager
        var state = media?.state?.value ?: AmsMediaManager.MediaState()

        // A button on the tile was tapped
        when (requestParams.currentState.lastClickableId) {
            ID_PREVIOUS -> media?.previousTrack()
            ID_NEXT -> media?.nextTrack()
            ID_PLAY_PAUSE -> {
                media?.togglePlayPause()
                // Show the new state right away; iOS confirms it a moment later
                state = state.copy(isPlaying = !state.isPlaying)
            }
        }

        val deviceParameters = requestParams.deviceConfiguration
        val openApp = launchMediaApp()

        val layout = PrimaryLayout.Builder(deviceParameters)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                text(
                    state.playerName.ifEmpty { "Music Control" },
                    Typography.TYPOGRAPHY_CAPTION1,
                    COLOR_PRIMARY
                )
            )
            .setContent(
                if (state.hasPlayer) playerContent(state, openApp) else emptyContent(state, openApp)
            )
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return immediateFuture(tile)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(ICON_PLAY, image(R.drawable.ic_play))
                .addIdToImageMapping(ICON_PAUSE, image(R.drawable.ic_pause))
                .addIdToImageMapping(ICON_PREVIOUS, image(R.drawable.ic_skip_previous))
                .addIdToImageMapping(ICON_NEXT, image(R.drawable.ic_skip_next))
                .build()
        )

    private fun playerContent(
        state: AmsMediaManager.MediaState,
        openApp: ModifiersBuilders.Clickable
    ): LayoutElementBuilders.LayoutElement {
        // Until iOS says which commands the player supports, offer them all
        fun supported(command: Byte) = state.supportedCommands.isEmpty() || state.supports(command)

        val buttons = MultiButtonLayout.Builder()
        if (supported(AmsConstants.COMMAND_PREVIOUS_TRACK)) {
            buttons.addButtonContent(button(ID_PREVIOUS, ICON_PREVIOUS, "Previous", primary = false))
        }
        buttons.addButtonContent(
            button(
                ID_PLAY_PAUSE,
                if (state.isPlaying) ICON_PAUSE else ICON_PLAY,
                if (state.isPlaying) "Pause" else "Play",
                primary = true
            )
        )
        if (supported(AmsConstants.COMMAND_NEXT_TRACK)) {
            buttons.addButtonContent(button(ID_NEXT, ICON_NEXT, "Next", primary = false))
        }

        return LayoutElementBuilders.Column.Builder()
            .addContent(
                // Tapping the track opens the full player (volume, progress)
                LayoutElementBuilders.Column.Builder()
                    .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
                    .addContent(text(state.title.ifEmpty { "Unknown title" }, Typography.TYPOGRAPHY_TITLE3, COLOR_TEXT))
                    .apply {
                        if (state.artist.isNotEmpty()) {
                            addContent(text(state.artist, Typography.TYPOGRAPHY_CAPTION2, COLOR_DETAIL))
                        }
                    }
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(6f)).build())
            .addContent(buttons.build())
            .build()
    }

    private fun emptyContent(
        state: AmsMediaManager.MediaState,
        openApp: ModifiersBuilders.Clickable
    ): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Column.Builder()
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
            .addContent(
                text(
                    if (state.available) "Nothing playing" else "Not connected",
                    Typography.TYPOGRAPHY_TITLE3,
                    COLOR_TEXT
                )
            )
            .addContent(
                text(
                    if (state.available) "Start music on your iPhone" else "Open WatchBridge to connect",
                    Typography.TYPOGRAPHY_CAPTION2,
                    COLOR_DETAIL
                )
            )
            .build()

    private fun text(value: String, typography: Int, color: Int): Text =
        Text.Builder(this, value)
            .setTypography(typography)
            .setColor(argb(color))
            .setMaxLines(1)
            .build()

    private fun button(id: String, icon: String, description: String, primary: Boolean): Button =
        Button.Builder(
            this,
            ModifiersBuilders.Clickable.Builder()
                .setId(id)
                .setOnClick(ActionBuilders.LoadAction.Builder().build())
                .build()
        )
            .setIconContent(icon)
            .setContentDescription(description)
            .setButtonColors(if (primary) ButtonDefaults.PRIMARY_COLORS else ButtonDefaults.SECONDARY_COLORS)
            .build()

    private fun launchMediaApp(): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId("open")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MediaActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

    private fun image(resId: Int): ResourceBuilders.ImageResource =
        ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build()
            )
            .build()

    private fun <T> immediateFuture(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "immediateFuture"
        }
}
