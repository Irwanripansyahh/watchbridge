package com.watchbridge.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.notification.NotificationIcons
import com.watchbridge.settings.SettingsManager
import com.watchbridge.ui.components.WatchBridgeScaffold

@Composable
fun SettingsScreen(settings: SettingsManager) {
    val vibration by settings.vibrationEnabled.collectAsState()
    val respectDnd by settings.respectDnd.collectAsState()
    val showPreExisting by settings.showPreExisting.collectAsState()
    val showSilent by settings.showSilent.collectAsState()

    val categoryFilters = settings.getAllCategoryFilters()
    val listState = rememberScalingLazyListState()

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

            // General settings header
            item { SectionHeader("GENERAL") }

            item {
                SettingsToggle(
                    label = "Vibration",
                    secondaryLabel = "Vibrate for notifications",
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
