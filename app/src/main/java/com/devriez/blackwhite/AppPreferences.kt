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
import org.json.JSONObject
import java.time.LocalDate

private val Context.dataStore by preferencesDataStore(name = "blackwhite_settings")

enum class FilterMode {
    Quick,
    Full
}

enum class QuickFilterStyle {
    Light,
    Dark
}

data class BlackWhiteSettings(
    val selectedPackages: Set<String> = emptySet(),
    val filterMode: FilterMode = FilterMode.Quick,
    val isPro: Boolean = false,
    val isAppEnabled: Boolean = true,
    val quickOverlayAlpha: Int = DEFAULT_QUICK_OVERLAY_ALPHA,
    val quickFilterStyle: QuickFilterStyle = QuickFilterStyle.Light,
    val pausedUntilMillis: Long = 0L,
    val pauseCountDateEpochDay: Long = 0L,
    val pauseCountToday: Int = 0,
    val scheduleEnabled: Boolean = false,
    val scheduleStartHour: Int = DEFAULT_SCHEDULE_START_HOUR,
    val scheduleEndHour: Int = DEFAULT_SCHEDULE_END_HOUR,
    val usageDateEpochDay: Long = LocalDate.now().toEpochDay(),
    val usageToday: Map<String, Long> = emptyMap(),
    val usageYesterday: Map<String, Long> = emptyMap(),
    val baselineUsage: Map<String, Long> = emptyMap(),
    val debugStatus: String = ""
) {
    fun canSelectMore(packageName: String): Boolean {
        return isPro || selectedPackages.contains(packageName) || selectedPackages.size < FREE_APP_LIMIT
    }

    fun isPaused(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return pausedUntilMillis > nowMillis
    }

    fun pausesLeftToday(todayEpochDay: Long = LocalDate.now().toEpochDay()): Int {
        val usedToday = if (pauseCountDateEpochDay == todayEpochDay) pauseCountToday else 0
        return (MAX_DAILY_PAUSES - usedToday).coerceAtLeast(0)
    }

    fun canPauseToday(todayEpochDay: Long = LocalDate.now().toEpochDay()): Boolean {
        return pausesLeftToday(todayEpochDay) > 0
    }

    fun isWithinSchedule(hour: Int): Boolean {
        if (!isPro || !scheduleEnabled) return true
        return if (scheduleStartHour <= scheduleEndHour) {
            hour in scheduleStartHour until scheduleEndHour
        } else {
            hour >= scheduleStartHour || hour < scheduleEndHour
        }
    }

    companion object {
        const val FREE_APP_LIMIT = 2
        const val MIN_QUICK_OVERLAY_ALPHA = 204
        const val MAX_QUICK_OVERLAY_ALPHA = 255
        const val DEFAULT_QUICK_OVERLAY_ALPHA = 230
        const val DEFAULT_SCHEDULE_START_HOUR = 9
        const val DEFAULT_SCHEDULE_END_HOUR = 22
        const val MAX_DAILY_PAUSES = 2
    }
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val selectedPackages = stringSetPreferencesKey("selected_packages")
        val filterMode = stringPreferencesKey("filter_mode")
        val isPro = booleanPreferencesKey("is_pro")
        val appEnabled = booleanPreferencesKey("app_enabled")
        val quickOverlayAlpha = intPreferencesKey("quick_overlay_alpha")
        val quickFilterStyle = stringPreferencesKey("quick_filter_style")
        val pausedUntil = longPreferencesKey("paused_until")
        val pauseCountDate = longPreferencesKey("pause_count_date")
        val pauseCount = intPreferencesKey("pause_count")
        val scheduleEnabled = booleanPreferencesKey("schedule_enabled")
        val scheduleStartHour = intPreferencesKey("schedule_start_hour")
        val scheduleEndHour = intPreferencesKey("schedule_end_hour")
        val usageDate = longPreferencesKey("usage_date")
        val usageToday = stringPreferencesKey("usage_today")
        val usageYesterday = stringPreferencesKey("usage_yesterday")
        val baselineUsage = stringPreferencesKey("baseline_usage")
        val debugStatus = stringPreferencesKey("debug_status")
    }

    val settings: Flow<BlackWhiteSettings> = context.dataStore.data.map { prefs ->
        BlackWhiteSettings(
            selectedPackages = prefs[Keys.selectedPackages].orEmpty(),
            filterMode = prefs[Keys.filterMode].toFilterMode(),
            isPro = prefs[Keys.isPro] ?: false,
            isAppEnabled = prefs[Keys.appEnabled] ?: true,
            quickOverlayAlpha = (prefs[Keys.quickOverlayAlpha] ?: BlackWhiteSettings.DEFAULT_QUICK_OVERLAY_ALPHA)
                .coerceIn(
                    BlackWhiteSettings.MIN_QUICK_OVERLAY_ALPHA,
                    BlackWhiteSettings.MAX_QUICK_OVERLAY_ALPHA
                ),
            quickFilterStyle = prefs[Keys.quickFilterStyle].toQuickFilterStyle(),
            pausedUntilMillis = prefs[Keys.pausedUntil] ?: 0L,
            pauseCountDateEpochDay = prefs[Keys.pauseCountDate] ?: 0L,
            pauseCountToday = prefs[Keys.pauseCount] ?: 0,
            scheduleEnabled = prefs[Keys.scheduleEnabled] ?: false,
            scheduleStartHour = (prefs[Keys.scheduleStartHour] ?: BlackWhiteSettings.DEFAULT_SCHEDULE_START_HOUR)
                .coerceIn(0, 23),
            scheduleEndHour = (prefs[Keys.scheduleEndHour] ?: BlackWhiteSettings.DEFAULT_SCHEDULE_END_HOUR)
                .coerceIn(0, 23),
            usageDateEpochDay = prefs[Keys.usageDate] ?: LocalDate.now().toEpochDay(),
            usageToday = decodeUsageMap(prefs[Keys.usageToday].orEmpty()),
            usageYesterday = decodeUsageMap(prefs[Keys.usageYesterday].orEmpty()),
            baselineUsage = decodeUsageMap(prefs[Keys.baselineUsage].orEmpty()),
            debugStatus = prefs[Keys.debugStatus].orEmpty()
        )
    }

    suspend fun setFilterMode(mode: FilterMode) {
        context.dataStore.edit { it[Keys.filterMode] = mode.name }
    }

    suspend fun setPro(enabled: Boolean) {
        context.dataStore.edit { it[Keys.isPro] = enabled }
    }

    suspend fun setAppEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.appEnabled] = enabled }
    }

    suspend fun setQuickOverlayAlpha(alpha: Int) {
        val safeAlpha = alpha.coerceIn(
            BlackWhiteSettings.MIN_QUICK_OVERLAY_ALPHA,
            BlackWhiteSettings.MAX_QUICK_OVERLAY_ALPHA
        )
        context.dataStore.edit { it[Keys.quickOverlayAlpha] = safeAlpha }
    }

    suspend fun setQuickFilterStyle(style: QuickFilterStyle) {
        context.dataStore.edit { it[Keys.quickFilterStyle] = style.name }
    }

    suspend fun setScheduleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.scheduleEnabled] = enabled }
    }

    suspend fun setScheduleStartHour(hour: Int) {
        context.dataStore.edit { it[Keys.scheduleStartHour] = hour.coerceIn(0, 23) }
    }

    suspend fun setScheduleEndHour(hour: Int) {
        context.dataStore.edit { it[Keys.scheduleEndHour] = hour.coerceIn(0, 23) }
    }

    suspend fun pauseFor(minutes: Long) {
        if (minutes <= 0L) {
            clearPause()
            return
        }
        val today = LocalDate.now().toEpochDay()
        context.dataStore.edit { prefs ->
            val storedDate = prefs[Keys.pauseCountDate] ?: 0L
            val usedToday = if (storedDate == today) prefs[Keys.pauseCount] ?: 0 else 0
            if (usedToday >= BlackWhiteSettings.MAX_DAILY_PAUSES) return@edit

            prefs[Keys.pauseCountDate] = today
            prefs[Keys.pauseCount] = usedToday + 1
            prefs[Keys.pausedUntil] = System.currentTimeMillis() + minutes * 60_000L
        }
    }

    suspend fun clearPause() {
        context.dataStore.edit { it[Keys.pausedUntil] = 0L }
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

    suspend fun addUsage(packageName: String, durationMillis: Long) {
        if (durationMillis < MIN_USAGE_SAMPLE_MS) return
        val today = LocalDate.now().toEpochDay()
        context.dataStore.edit { prefs ->
            val storedDate = prefs[Keys.usageDate] ?: today
            val todayUsage = decodeUsageMap(prefs[Keys.usageToday].orEmpty()).toMutableMap()

            if (storedDate != today) {
                prefs[Keys.usageDate] = today
                prefs[Keys.usageYesterday] = encodeUsageMap(todayUsage)
                prefs[Keys.usageToday] = encodeUsageMap(mapOf(packageName to durationMillis))
            } else {
                todayUsage[packageName] = (todayUsage[packageName] ?: 0L) + durationMillis
                prefs[Keys.usageDate] = today
                prefs[Keys.usageToday] = encodeUsageMap(todayUsage)
            }
        }
    }

    suspend fun captureBaselineIfEmpty(usage: Map<String, Long>) {
        if (usage.isEmpty()) return
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.baselineUsage].orEmpty()
            if (current.isBlank() || decodeUsageMap(current).isEmpty()) {
                prefs[Keys.baselineUsage] = encodeUsageMap(usage)
            }
        }
    }

    private fun String?.toFilterMode(): FilterMode {
        return runCatching { FilterMode.valueOf(this ?: FilterMode.Quick.name) }
            .getOrDefault(FilterMode.Quick)
    }

    private fun String?.toQuickFilterStyle(): QuickFilterStyle {
        return runCatching { QuickFilterStyle.valueOf(this ?: QuickFilterStyle.Light.name) }
            .getOrDefault(QuickFilterStyle.Light)
    }

    companion object {
        private const val MIN_USAGE_SAMPLE_MS = 1_000L
    }
}

private fun decodeUsageMap(raw: String): Map<String, Long> {
    if (raw.isBlank()) return emptyMap()
    return runCatching {
        val json = JSONObject(raw)
        json.keys().asSequence().associateWith { key -> json.optLong(key, 0L) }
    }.getOrDefault(emptyMap())
}

private fun encodeUsageMap(values: Map<String, Long>): String {
    val json = JSONObject()
    values.forEach { (key, value) ->
        if (value > 0L) json.put(key, value)
    }
    return json.toString()
}
