package com.watchbridge.tile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
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
import com.watchbridge.R
import com.watchbridge.ble.BondManager
import com.watchbridge.ble.ConnectionStateMachine.State
import com.watchbridge.service.WatchBridgeService
import com.watchbridge.settings.SettingsManager

/**
 * Tile showing the iPhone connection status, with a one-tap reconnect from the watch face.
 * The top line shows the iPhone's name and battery level, or stays blank when unknown.
 */
class ConnectionTileService : TileService() {

    companion object {
        // Bump when the images change, so the system fetches them again
        private const val RESOURCES_VERSION = "2"
        private const val ICON_BATTERY = "battery"

        /** At or below this, the battery level turns red. */
        private const val LOW_BATTERY_PERCENT = 20

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
            .setPrimaryLabelTextContent(phoneLine())
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
        immediateFuture(
            ResourceBuilders.Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .addIdToImageMapping(
                    ICON_BATTERY,
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.ic_battery)
                                .build()
                        )
                        .build()
                )
                .build()
        )

    /**
     * "iPhone ▮ 82%": the phone's battery, labelled with its name. Blank when the battery
     * level isn't known. The name is, in order: the phone's name on the watch's Bluetooth,
     * the name the phone reports for itself, or "Your device battery".
     */
    @SuppressLint("MissingPermission")
    private fun phoneLine(): LayoutElementBuilders.LayoutElement {
        // Turned off in Settings: keep the line, empty, so the layout doesn't jump
        if (!SettingsManager(this).isPhoneInfoShown) return caption(" ", COLOR_PRIMARY)

        val connection = WatchBridgeService.connectionManager
        val battery = connection?.phoneBattery?.value ?: return caption(" ", COLOR_PRIMARY)

        // Paired devices can't be listed without the Bluetooth permission
        val bluetoothName = runCatching {
            BondManager(this).getBondedDevice()?.let { it.alias ?: it.name }
        }.getOrNull()?.trim()?.ifEmpty { null }
        val label = bluetoothName
            ?: connection.phoneDeviceName.value
            ?: "Your device battery"

        val color = if (battery <= LOW_BATTERY_PERCENT) COLOR_DISCONNECTED else COLOR_DETAIL
        val row = LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(caption(label, COLOR_PRIMARY))
            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
            .addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId(ICON_BATTERY)
                    .setWidth(dp(12f))
                    .setHeight(dp(12f))
                    .setColorFilter(
                        LayoutElementBuilders.ColorFilter.Builder().setTint(argb(color)).build()
                    )
                    .build()
            )
            .addContent(caption("$battery%", color))
        return row.build()
    }

    private fun caption(value: String, color: Int): Text =
        Text.Builder(this, value)
            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
            .setColor(argb(color))
            .setMaxLines(1)
            .build()

    private fun currentStatus(): Status {
        val state = WatchBridgeService.stateMachine?.state?.value
            ?: return Status(
                "Not running", "Tap Start to connect to your phone",
                COLOR_DISCONNECTED, "Start", ReconnectActivity::class.java
            )

        return when (state) {
            State.READY -> Status(
                "Connected", "Notifications from your phone are on",
                COLOR_CONNECTED, "Open", MainActivity::class.java
            )
            State.CONNECTING, State.CONNECTED, State.RECONNECTING -> Status(
                "Connecting…", "Talking to your phone",
                COLOR_WAITING, "Open", MainActivity::class.java
            )
            State.WAITING_TO_RECONNECT -> Status(
                "Reconnecting soon", "Lost the phone, retrying",
                COLOR_WAITING, "Reconnect", ReconnectActivity::class.java
            )
            State.BLUETOOTH_OFF -> Status(
                "Bluetooth off", "Turn on Bluetooth to reconnect",
                COLOR_DISCONNECTED, "Open", MainActivity::class.java
            )
            State.WAITING_FOR_PHONE -> Status(
                "Phone out of range", "Reconnects when it's nearby",
                COLOR_WAITING, "Reconnect", ReconnectActivity::class.java
            )
            State.ADVERTISING -> Status(
                "Pairing…", "Pick WatchBridge in your phone's Bluetooth settings",
                COLOR_WAITING, "Open", MainActivity::class.java
            )
            State.IDLE, State.DISCONNECTED -> Status(
                "Disconnected", "Not receiving notifications",
                COLOR_DISCONNECTED, "Reconnect", ReconnectActivity::class.java
            )
            State.FAILED -> Status(
                "Not paired", "Pair your phone in the app",
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
