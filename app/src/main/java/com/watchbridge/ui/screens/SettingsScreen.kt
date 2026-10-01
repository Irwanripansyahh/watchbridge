package com.watchbridge.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.watchbridge.R
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.notification.NotificationIcons
import com.watchbridge.settings.SettingsManager
import com.watchbridge.ui.components.WatchBridgeScaffold
import com.watchbridge.update.AppUpdater
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(settings: SettingsManager) {
    val vibration by settings.vibrationEnabled.collectAsState()
    val respectDnd by settings.respectDnd.collectAsState()
    val showPreExisting by settings.showPreExisting.collectAsState()
    val showSilent by settings.showSilent.collectAsState()

    val categoryFilters = settings.getAllCategoryFilters()
    val listState = rememberScalingLazyListState()

    val context = LocalContext.current
    val updateState by AppUpdater.state.collectAsState()
    var canInstall by remember { mutableStateOf(AppUpdater.canInstallUpdates(context)) }
    var showAdbHint by remember { mutableStateOf(false) }
    val openInstallSettings = {
        if (!AppUpdater.openInstallPermissionSettings(context)) showAdbHint = true
    }

    // Check once when Settings opens, so an available update shows up right away
    LaunchedEffect(Unit) {
        if (AppUpdater.state.value == AppUpdater.State.Idle) AppUpdater.checkForUpdate(context)
    }
    // Back from the system "Install unknown apps" screen: refresh, and continue a waiting update
    LifecycleResumeEffect(Unit) {
        canInstall = AppUpdater.canInstallUpdates(context)
        val waiting = AppUpdater.state.value as? AppUpdater.State.NeedsInstallPermission
        if (waiting != null && canInstall) AppUpdater.downloadAndInstall(context, waiting.release)
        onPauseOrDispose { }
    }

    WatchBridgeScaffold(listState = listState) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(top = 28.dp, start = 10.dp, end = 10.dp, bottom = 48.dp),
            autoCentering = AutoCenteringParams()
        ) {
            item {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.title3,
                    color = MaterialTheme.colors.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item { SectionHeader("UPDATES") }

            item {
                UpdateChip(
                    state = updateState,
                    installedVersion = remember { AppUpdater.installedVersion(context) },
                    onCheck = { AppUpdater.checkForUpdate(context) },
                    onInstall = { AppUpdater.downloadAndInstall(context, it) },
                    onAllowInstalls = openInstallSettings
                )
            }

            (updateState as? AppUpdater.State.Failed)?.let { failed ->
                item { HintText(failed.message) }
            }

            item {
                Chip(
                    onClick = openInstallSettings,
                    label = { Text("Install unknown apps", maxLines = 1) },
                    secondaryLabel = {
                        Text(
                            text = if (canInstall) "Allowed" else "Not allowed · tap to open",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_install_apps),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (showAdbHint && !canInstall) {
                item {
                    HintText(
                        "This watch has no screen for it. Allow it once from a computer:\n" +
                            AppUpdater.installPermissionAdbCommand(context)
                    )
                }
            }

            // General settings header
            item { SectionHeader("GENERAL") }

            item {
                SettingsToggle(
                    label = "Vibration",
                    secondaryLabel = "Vibrate for notifications (calls always vibrate)",
                    checked = vibration,
                    onCheckedChange = { settings.setVibrationEnabled(it) }
                )
            }

            item {
                SettingsToggle(
                    label = "Respect DND",
                    secondaryLabel = "Silence when phone is on DND",
                    checked = respectDnd,
                    onCheckedChange = { settings.setRespectDnd(it) }
                )
            }

            item {
                SettingsToggle(
                    label = "Show pre-existing",
                    secondaryLabel = "Show notifications from before connecting",
                    checked = showPreExisting,
                    onCheckedChange = { settings.setShowPreExisting(it) }
                )
            }

            item {
                SettingsToggle(
                    label = "Show silent",
                    secondaryLabel = "Show silenced notifications",
                    checked = showSilent,
                    onCheckedChange = { settings.setShowSilent(it) }
                )
            }

            // Category filters header
            item { SectionHeader("CATEGORIES") }

            val categories = listOf(
                AncsConstants.CATEGORY_INCOMING_CALL to "Incoming Calls",
                AncsConstants.CATEGORY_MISSED_CALL to "Missed Calls",
                AncsConstants.CATEGORY_VOICEMAIL to "Voicemail",
                AncsConstants.CATEGORY_SOCIAL to "Social",
                AncsConstants.CATEGORY_SCHEDULE to "Schedule",
                AncsConstants.CATEGORY_EMAIL to "Email",
                AncsConstants.CATEGORY_NEWS to "News",
                AncsConstants.CATEGORY_HEALTH_AND_FITNESS to "Health & Fitness",
                AncsConstants.CATEGORY_BUSINESS_AND_FINANCE to "Business & Finance",
                AncsConstants.CATEGORY_LOCATION to "Location",
                AncsConstants.CATEGORY_ENTERTAINMENT to "Entertainment",
                AncsConstants.CATEGORY_OTHER to "Other"
            )

            for ((catId, catName) in categories) {
                item {
                    CategoryToggle(
                        label = catName,
                        categoryId = catId,
                        checked = categoryFilters[catId] ?: true,
                        onCheckedChange = { settings.setCategoryEnabled(catId, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateChip(
    state: AppUpdater.State,
    installedVersion: String,
    onCheck: () -> Unit,
    onInstall: (AppUpdater.Release) -> Unit,
    onAllowInstalls: () -> Unit
) {
    // label, detail, and what tapping does (null = busy, not tappable)
    val (label, detail, onClick) = when (state) {
        AppUpdater.State.Idle ->
            Triple("Check for updates", "Version $installedVersion", onCheck)
        AppUpdater.State.Checking ->
            Triple("Checking…", "Version $installedVersion", null)
        is AppUpdater.State.UpToDate ->
            Triple("Up to date", "Version $installedVersion", onCheck)
        is AppUpdater.State.Available -> Triple(
            "Update to ${state.release.version}",
            "${formatMegabytes(state.release.apkSizeBytes)} · tap to install",
            { onInstall(state.release) }
        )
        is AppUpdater.State.Downloading -> Triple(
            "Downloading ${(state.progress * 100).roundToInt()}%",
            "Version ${state.release.version}",
            null
        )
        AppUpdater.State.Installing ->
            Triple("Installing…", "Confirm on the next screen", null)
        is AppUpdater.State.NeedsInstallPermission ->
            Triple("Allow installing updates", "Then the update continues", onAllowInstalls)
        is AppUpdater.State.Failed -> Triple(
            "Update failed",
            "Tap to try again",
            state.release?.let { release -> { onInstall(release) } } ?: onCheck
        )
    }

    Chip(
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = { Text(detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_system_update),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        colors = if (state is AppUpdater.State.Available) {
            ChipDefaults.primaryChipColors()
        } else {
            ChipDefaults.secondaryChipColors()
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.caption3,
        color = MaterialTheme.colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

private fun formatMegabytes(bytes: Long): String =
    if (bytes > 0) "%.1f MB".format(bytes / 1_048_576f) else "Unknown size"

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.caption1.copy(letterSpacing = 1.5.sp),
        color = MaterialTheme.colors.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsToggle(
    label: String,
    secondaryLabel: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ToggleChip(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.body2,
                maxLines = 1
            )
        },
        secondaryLabel = if (secondaryLabel != null) {
            {
                Text(
                    text = secondaryLabel,
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    maxLines = 2
                )
            }
        } else null,
        toggleControl = {
            Icon(
                imageVector = ToggleChipDefaults.switchIcon(checked = checked),
                contentDescription = if (checked) "On" else "Off"
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun CategoryToggle(
    label: String,
    categoryId: Byte,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ToggleChip(
        checked = checked,
        onCheckedChange = onCheckedChange,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.body2,
                maxLines = 1
            )
        },
        appIcon = {
            Icon(
                painter = painterResource(NotificationIcons.iconForCategory(categoryId)),
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        toggleControl = {
            Icon(
                imageVector = ToggleChipDefaults.switchIcon(checked = checked),
                contentDescription = if (checked) "On" else "Off"
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}
