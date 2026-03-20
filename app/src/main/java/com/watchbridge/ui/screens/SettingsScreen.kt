package com.watchbridge.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.settings.SettingsManager

@Composable
fun SettingsScreen(settings: SettingsManager) {
    val vibration by settings.vibrationEnabled.collectAsState()
    val respectDnd by settings.respectDnd.collectAsState()
    val showPreExisting by settings.showPreExisting.collectAsState()
    val showSilent by settings.showSilent.collectAsState()

    val categoryFilters = settings.getAllCategoryFilters()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
            )
        }

        // General settings
        item {
            Text(
                text = "General",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        }

        item {
            SettingsToggle(
                label = "Vibration",
                checked = vibration,
                onCheckedChange = { settings.setVibrationEnabled(it) }
            )
        }

        item {
            SettingsToggle(
                label = "Respect DND",
                checked = respectDnd,
                onCheckedChange = { settings.setRespectDnd(it) }
            )
        }

        item {
            SettingsToggle(
                label = "Show pre-existing",
                checked = showPreExisting,
                onCheckedChange = { settings.setShowPreExisting(it) }
            )
        }

        item {
            SettingsToggle(
                label = "Show silent",
                checked = showSilent,
                onCheckedChange = { settings.setShowSilent(it) }
            )
        }

        // Category filters
        item {
            Text(
                text = "Categories",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
            )
        }

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
                SettingsToggle(
                    label = catName,
                    checked = categoryFilters[catId] ?: true,
                    onCheckedChange = { settings.setCategoryEnabled(catId, it) }
                )
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    label: String,
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
        toggleControl = {
            Icon(
                imageVector = ToggleChipDefaults.switchIcon(checked = checked),
                contentDescription = if (checked) "On" else "Off"
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}
