package com.watchbridge.notification

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Caches iOS bundle identifier → human-readable display name mappings.
 * Persists to SharedPreferences so we don't re-query known apps.
 *
 * When a notification arrives with an unknown bundle ID, the caller should
 * request app attributes via ANCS Control Point, then call [cacheAppName].
 */
class AppNameResolver(context: Context) {

    companion object {
        private const val TAG = "AppNameResolver"
        private const val PREFS_NAME = "watchbridge_app_names"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** In-memory cache layered on top of SharedPreferences. */
    private val cache = mutableMapOf<String, String>()

    /** Bundle IDs we've already requested but haven't received a response for yet. */
    private val pendingRequests = mutableSetOf<String>()

    init {
        // Load persisted names into memory
        prefs.all.forEach { (key, value) ->
            if (value is String) {
                cache[key] = value
            }
        }
        Log.d(TAG, "Loaded ${cache.size} cached app names")
    }

    /**
     * Get display name for a bundle identifier.
     * Returns the cached name, or a cleaned-up version of the bundle ID if unknown.
     */
    fun getDisplayName(bundleId: String): String {
        return cache[bundleId] ?: formatBundleId(bundleId)
    }

    /**
     * Check if we have a cached name for this bundle ID.
     */
    fun isKnown(bundleId: String): Boolean = bundleId in cache

    /**
     * Check if we need to request the app name for this bundle ID.
     * Returns true if the name is unknown and not already pending.
     */
    fun needsRequest(bundleId: String): Boolean {
        return bundleId !in cache && bundleId !in pendingRequests
    }

    /**
     * Mark a bundle ID as having a pending request.
     */
    fun markPending(bundleId: String) {
        pendingRequests.add(bundleId)
    }

    /**
     * Cache a resolved app name. Persists to disk.
     */
    fun cacheAppName(bundleId: String, displayName: String) {
        cache[bundleId] = displayName
        pendingRequests.remove(bundleId)
        prefs.edit().putString(bundleId, displayName).apply()
        Log.d(TAG, "Cached: $bundleId -> $displayName")
    }

    /**
     * Format a bundle ID as a fallback display name.
     * "com.apple.MobileSMS" -> "MobileSMS"
     */
    private fun formatBundleId(bundleId: String): String {
        return bundleId.substringAfterLast('.')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    }
}
