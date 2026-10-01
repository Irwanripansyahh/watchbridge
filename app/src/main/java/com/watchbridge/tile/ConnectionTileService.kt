package com.watchbridge.tile

import android.app.Activity
import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.ChipDefaults
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.watchbridge.MainActivity
import com.watchbridge.ble.ConnectionStateMachine.State
import com.watchbridge.service.WatchBridgeService

/**
 * Tile showing the iPhone connection status, with a one-tap reconnect from the watch face.
 */
class ConnectionTileService : TileService() {

    companion object {
        private const val RESOURCES_VERSION = "1"

        private const val COLOR_PRIMARY = 0xFF4FC3F7.toInt()
        private const val COLOR_CONNECTED = 0xFF4FC3F7.toInt()
        private const val COLOR_WAITING = 0xFFFFD54F.toInt()
        private const val COLOR_DISCONNECTED = 0xFFEF5350.toInt()
        private const val COLOR_DETAIL = 0xFFB0B0B0.toInt()

        /** Ask the system to re-render the tile, e.g. when the connection state changes. */
        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(ConnectionTileService::class.java)
        }
    }

    private class Status(
        val label: String,
        val detail: String,
        val color: Int,
        val chipLabel: String,
        val chipTarget: Class<out Activity>
    )

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> {
        val deviceParameters = requestParams.deviceConfiguration
        val status = currentStatus()

        val content = LayoutElementBuilders.Column.Builder()
            .addContent(
                Text.Builder(this, status.label)
                    .setTypography(Typography.TYPOGRAPHY_TITLE2)
                    .setColor(argb(status.color))
                    .setMaxLines(2)
                    .build()
            )
            .addContent(
                Text.Builder(this, status.detail)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setColor(argb(COLOR_DETAIL))
                    .setMaxLines(2)
                    .build()
            )
            .build()

        val layout = PrimaryLayout.Builder(deviceParameters)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, "WatchBridge")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(COLOR_PRIMARY))
                    .build()
            )
            .setContent(content)
            .setPrimaryChipContent(
                CompactChip.Builder(
                    this,
                    status.chipLabel,
                    launchClickable(status.chipTarget),
                    deviceParameters
                )
                    .setChipColors(ChipDefaults.COMPACT_PRIMARY_COLORS)
                    .build()
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
        immediateFuture(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun currentStatus(): Status {
        val state = WatchBridgeService.stateMachine?.state?.value
            ?: return Status(
                "Not running", "Tap Start to connect to your iPhone",
                COLOR_DISCONNECTED, "Start", ReconnectActivity::class.java
            )

        return when (state) {
            State.READY -> Status(
                "Connected", "Notifications from your iPhone are on",
                COLOR_CONNECTED, "Open", MainActivity::class.java
            )
            State.CONNECTING, State.CONNECTED, State.RECONNECTING -> Status(
                "Connecting…", "Talking to your iPhone",
                COLOR_WAITING, "Open", MainActivity::class.java
            )
            State.WAITING_TO_RECONNECT -> Status(
                "Reconnecting soon", "Lost the iPhone, retrying",
                COLOR_WAITING, "Reconnect", ReconnectActivity::class.java
            )
            State.WAITING_FOR_PHONE -> Status(
                "iPhone out of range", "Reconnects when it's nearby",
                COLOR_WAITING, "Reconnect", ReconnectActivity::class.java
            )
            State.ADVERTISING -> Status(
                "Pairing…", "Pick WatchBridge in iPhone Bluetooth settings",
                COLOR_WAITING, "Open", MainActivity::class.java
            )
            State.IDLE, State.DISCONNECTED -> Status(
                "Disconnected", "Not receiving notifications",
                COLOR_DISCONNECTED, "Reconnect", ReconnectActivity::class.java
            )
            State.FAILED -> Status(
                "Not paired", "Pair your iPhone in the app",
                COLOR_DISCONNECTED, "Open", MainActivity::class.java
            )
        }
    }

    private fun launchClickable(activity: Class<out Activity>): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(activity.simpleName)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(activity.name)
                            .build()
                    )
                    .build()
            )
            .build()

    private fun <T> immediateFuture(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "immediateFuture"
        }
}
