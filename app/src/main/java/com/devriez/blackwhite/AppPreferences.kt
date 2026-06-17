package com.devriez.blackwhite

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "blackwhite_settings")

enum class FilterMode {
    Quick,
    Full
}

data class BlackWhiteSettings(
    val selectedPackages: Set<String> = emptySet(),
    val filterMode: FilterMode = FilterMode.Quick,
    val isPro: Boolean = false,
    val isQuickToggleEnabled: Boolean = true,
    val quickOverlayAlpha: Int = DEFAULT_QUICK_OVERLAY_ALPHA,
    val pausedUntilMillis: Long = 0L,
    val activeProfileName: String = "Default",
    val scheduleEnabled: Boolean = false,
    val debugStatus: String = ""
) {
    fun canSelectMore(packageName: String): Boolean {
        return isPro || selectedPackages.contains(packageName) || selectedPackages.size < FREE_APP_LIMIT
    }

    fun isPaused(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return pausedUntilMillis > nowMillis
    }

    companion object {
        const val FREE_APP_LIMIT = 2
        const val MIN_QUICK_OVERLAY_ALPHA = 80
        const val MAX_QUICK_OVERLAY_ALPHA = 235
        const val DEFAULT_QUICK_OVERLAY_ALPHA = 205
    }
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val selectedPackages = stringSetPreferencesKey("selected_packages")
        val filterMode = stringPreferencesKey("filter_mode")
        val isPro = booleanPreferencesKey("is_pro")
        val quickToggle = booleanPreferencesKey("quick_toggle")
        val quickOverlayAlpha = intPreferencesKey("quick_overlay_alpha")
        val pausedUntil = longPreferencesKey("paused_until")
        val activeProfile = stringPreferencesKey("active_profile")
        val scheduleEnabled = booleanPreferencesKey("schedule_enabled")
        val debugStatus = stringPreferencesKey("debug_status")
    }

    val settings: Flow<BlackWhiteSettings> = context.dataStore.data.map { prefs ->
        BlackWhiteSettings(
            selectedPackages = prefs[Keys.selectedPackages].orEmpty(),
            filterMode = prefs[Keys.filterMode].toFilterMode(),
            isPro = prefs[Keys.isPro] ?: false,
            isQuickToggleEnabled = prefs[Keys.quickToggle] ?: true,
            quickOverlayAlpha = (prefs[Keys.quickOverlayAlpha] ?: BlackWhiteSettings.DEFAULT_QUICK_OVERLAY_ALPHA)
                .coerceIn(
                    BlackWhiteSettings.MIN_QUICK_OVERLAY_ALPHA,
                    BlackWhiteSettings.MAX_QUICK_OVERLAY_ALPHA
                ),
            pausedUntilMillis = prefs[Keys.pausedUntil] ?: 0L,
            activeProfileName = prefs[Keys.activeProfile] ?: "Default",
            scheduleEnabled = prefs[Keys.scheduleEnabled] ?: false,
            debugStatus = prefs[Keys.debugStatus].orEmpty()
        )
    }

    suspend fun setFilterMode(mode: FilterMode) {
        context.dataStore.edit { it[Keys.filterMode] = mode.name }
    }

    suspend fun setPro(enabled: Boolean) {
        context.dataStore.edit { it[Keys.isPro] = enabled }
    }

    suspend fun setQuickToggle(enabled: Boolean) {
        context.dataStore.edit { it[Keys.quickToggle] = enabled }
    }

    suspend fun setQuickOverlayAlpha(alpha: Int) {
        val safeAlpha = alpha.coerceIn(
            BlackWhiteSettings.MIN_QUICK_OVERLAY_ALPHA,
            BlackWhiteSettings.MAX_QUICK_OVERLAY_ALPHA
        )
        context.dataStore.edit { it[Keys.quickOverlayAlpha] = safeAlpha }
    }

    suspend fun setScheduleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.scheduleEnabled] = enabled }
    }

    suspend fun pauseFor(minutes: Long) {
        val until = if (minutes <= 0L) 0L else System.currentTimeMillis() + minutes * 60_000L
        context.dataStore.edit { it[Keys.pausedUntil] = until }
    }

    suspend fun togglePackage(packageName: String, checked: Boolean, current: BlackWhiteSettings) {
        if (checked && !current.canSelectMore(packageName)) return
        val next = current.selectedPackages.toMutableSet()
        if (checked) {
            next += packageName
        } else {
            next -= packageName
        }
        context.dataStore.edit { it[Keys.selectedPackages] = next }
    }

    suspend fun setDebugStatus(status: String) {
        context.dataStore.edit { it[Keys.debugStatus] = status.take(800) }
    }

    private fun String?.toFilterMode(): FilterMode {
        return runCatching { FilterMode.valueOf(this ?: FilterMode.Quick.name) }
            .getOrDefault(FilterMode.Quick)
    }
}
