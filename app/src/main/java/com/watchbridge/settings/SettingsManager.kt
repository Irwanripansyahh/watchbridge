package com.watchbridge.settings

import android.content.Context
import android.content.SharedPreferences
import com.watchbridge.ancs.AncsConstants
import com.watchbridge.notification.NotificationChannels
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persisted app settings for notification filtering, vibration, and behavior.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "watchbridge_settings"

        // Keys
        private const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_RESPECT_DND = "respect_dnd"
        private const val KEY_SHOW_PRE_EXISTING = "show_pre_existing"
        private const val KEY_SHOW_SILENT = "show_silent"
        private const val KEY_CATEGORY_PREFIX = "category_enabled_"
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Observable state
    private val _vibrationEnabled = MutableStateFlow(prefs.getBoolean(KEY_VIBRATION_ENABLED, true))
    val vibrationEnabled: StateFlow<Boolean> = _vibrationEnabled.asStateFlow()

    private val _respectDnd = MutableStateFlow(prefs.getBoolean(KEY_RESPECT_DND, true))
    val respectDnd: StateFlow<Boolean> = _respectDnd.asStateFlow()

    private val _showPreExisting = MutableStateFlow(prefs.getBoolean(KEY_SHOW_PRE_EXISTING, false))
    val showPreExisting: StateFlow<Boolean> = _showPreExisting.asStateFlow()

    private val _showSilent = MutableStateFlow(prefs.getBoolean(KEY_SHOW_SILENT, false))
    val showSilent: StateFlow<Boolean> = _showSilent.asStateFlow()

    val isOnboardingComplete: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun setOnboardingComplete() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    /**
     * Read straight from SharedPreferences (shared by every SettingsManager instance), so a
     * change made from another instance is never missed when picking a notification channel.
     */
    val isVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)

    fun setVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, enabled).apply()
        _vibrationEnabled.value = enabled
        NotificationChannels.createAll(appContext, vibrate = enabled)
    }

    fun setRespectDnd(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RESPECT_DND, enabled).apply()
        _respectDnd.value = enabled
    }

    fun setShowPreExisting(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_PRE_EXISTING, enabled).apply()
        _showPreExisting.value = enabled
    }

    fun setShowSilent(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_SILENT, enabled).apply()
        _showSilent.value = enabled
    }

    /**
     * Check if a given ANCS category is enabled for forwarding.
     * All categories are enabled by default.
     */
    fun isCategoryEnabled(categoryId: Byte): Boolean {
        return prefs.getBoolean(KEY_CATEGORY_PREFIX + categoryId, true)
    }

    fun setCategoryEnabled(categoryId: Byte, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CATEGORY_PREFIX + categoryId, enabled).apply()
    }

    /**
     * Get all category filter states as a map.
     */
    fun getAllCategoryFilters(): Map<Byte, Boolean> {
        val categories = listOf(
            AncsConstants.CATEGORY_OTHER,
            AncsConstants.CATEGORY_INCOMING_CALL,
            AncsConstants.CATEGORY_MISSED_CALL,
            AncsConstants.CATEGORY_VOICEMAIL,
            AncsConstants.CATEGORY_SOCIAL,
            AncsConstants.CATEGORY_SCHEDULE,
            AncsConstants.CATEGORY_EMAIL,
            AncsConstants.CATEGORY_NEWS,
            AncsConstants.CATEGORY_HEALTH_AND_FITNESS,
            AncsConstants.CATEGORY_BUSINESS_AND_FINANCE,
            AncsConstants.CATEGORY_LOCATION,
            AncsConstants.CATEGORY_ENTERTAINMENT
        )
        return categories.associateWith { isCategoryEnabled(it) }
    }
}
