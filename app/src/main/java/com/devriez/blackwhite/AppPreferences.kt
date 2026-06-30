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

data class DaySchedule(
    val startHour: Int = BlackWhiteSettings.DEFAULT_SCHEDULE_START_HOUR,
    val endHour: Int = BlackWhiteSettings.DEFAULT_SCHEDULE_END_HOUR
)

data class DailyBreakStats(
    val pauseCount: Int = 0,
    val allowedAppCount: Int = 0
)

data class BlackWhiteSettings(
    val selectedPackages: Set<String> = emptySet(),
    val filterMode: FilterMode = FilterMode.Quick,
    val isPro: Boolean = false,
    val isAppEnabled: Boolean = true,
    val appDisabledDateEpochDay: Long = 0L,
    val scheduleOverrideDateEpochDay: Long = 0L,
    val quickOverlayAlpha: Int = DEFAULT_QUICK_OVERLAY_ALPHA,
    val quickFilterStyle: QuickFilterStyle = QuickFilterStyle.Dark,
    val pausedUntilMillis: Long = 0L,
    val pauseCountDateEpochDay: Long = 0L,
    val pauseCountToday: Int = 0,
    val pauseTimeTodayMillis: Long = 0L,
    val allowedPackageName: String = "",
    val allowedUntilMillis: Long = 0L,
    val allowedAppDateEpochDay: Long = 0L,
    val allowedAppCountToday: Int = 0,
    val activeSelectedPackageName: String = "",
    val breakHistory: Map<Long, DailyBreakStats> = emptyMap(),
    val scheduleEnabled: Boolean = false,
    val scheduleStartHour: Int = DEFAULT_SCHEDULE_START_HOUR,
    val scheduleEndHour: Int = DEFAULT_SCHEDULE_END_HOUR,
    val daySchedules: Map<Int, DaySchedule> = defaultDaySchedules(),
    val usageDateEpochDay: Long = LocalDate.now().toEpochDay(),
    val usageToday: Map<String, Long> = emptyMap(),
    val usageYesterday: Map<String, Long> = emptyMap(),
    val baselineUsage: Map<String, Long> = emptyMap(),
    val baselineDailyUsage: Map<Long, Map<String, Long>> = emptyMap(),
    val baselineVersion: Int = CURRENT_BASELINE_VERSION,
    val usageHistory: Map<Long, Map<String, Long>> = emptyMap(),
    val protectionDateEpochDay: Long = LocalDate.now().toEpochDay(),
    val protectionTodayMillis: Long = 0L,
    val introDismissed: Boolean = false,
    val firstLaunchPreview: Boolean = false,
    val testStatsEnabled: Boolean = false,
    val debugStatus: String = ""
) {
    fun canSelectMore(packageName: String): Boolean {
        return isPro || selectedPackages.contains(packageName) || selectedPackages.size < FREE_APP_LIMIT
    }

    fun isPaused(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return pausedUntilMillis > nowMillis
    }

    fun isPackageAllowed(packageName: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        return packageName != null &&
            packageName == allowedPackageName &&
            allowedUntilMillis > nowMillis
    }

    fun canAllowOneAppToday(): Boolean {
        return allowedAppCountToday < DAILY_ALLOWED_APP_LIMIT
    }

    fun canPauseToday(): Boolean {
        return pauseCountToday < DAILY_PAUSE_LIMIT
    }

    fun remainingPausesToday(): Int {
        return (DAILY_PAUSE_LIMIT - pauseCountToday).coerceAtLeast(0)
    }

    fun isWithinSchedule(hour: Int, dayOfWeek: Int = LocalDate.now().dayOfWeek.value): Boolean {
        if (!isPro || !scheduleEnabled) return true
        if (scheduleOverrideDateEpochDay == LocalDate.now().toEpochDay()) return true
        val daySchedule = daySchedules[dayOfWeek] ?: DaySchedule(scheduleStartHour, scheduleEndHour)
        return if (daySchedule.startHour <= daySchedule.endHour) {
            hour in daySchedule.startHour until daySchedule.endHour
        } else {
            hour >= daySchedule.startHour || hour < daySchedule.endHour
        }
    }

    companion object {
        const val FREE_APP_LIMIT = 2
        const val MIN_QUICK_OVERLAY_ALPHA = 204
        const val MAX_QUICK_OVERLAY_ALPHA = 255
        const val DEFAULT_QUICK_OVERLAY_ALPHA = 230
        const val DEFAULT_SCHEDULE_START_HOUR = 9
        const val DEFAULT_SCHEDULE_END_HOUR = 22
        const val CURRENT_BASELINE_VERSION = 2
        const val DAILY_PAUSE_LIMIT = 3
        const val DAILY_ALLOWED_APP_LIMIT = 1
        const val ONE_APP_ALLOW_MINUTES = 60L

        fun defaultDaySchedules(): Map<Int, DaySchedule> {
            return (1..7).associateWith { DaySchedule() }
        }
    }
}

class SettingsStore(private val context: Context) {
    private object Keys {
        val selectedPackages = stringSetPreferencesKey("selected_packages")
        val filterMode = stringPreferencesKey("filter_mode")
        val isPro = booleanPreferencesKey("is_pro")
        val appEnabled = booleanPreferencesKey("app_enabled")
        val appDisabledDate = longPreferencesKey("app_disabled_date")
        val scheduleOverrideDate = longPreferencesKey("schedule_override_date")
        val quickOverlayAlpha = intPreferencesKey("quick_overlay_alpha")
        val quickFilterStyle = stringPreferencesKey("quick_filter_style")
        val pausedUntil = longPreferencesKey("paused_until")
        val pauseCountDate = longPreferencesKey("pause_count_date")
        val pauseCount = intPreferencesKey("pause_count")
        val pauseTimeToday = longPreferencesKey("pause_time_today")
        val allowedPackage = stringPreferencesKey("allowed_package")
        val allowedUntil = longPreferencesKey("allowed_until")
        val allowedAppDate = longPreferencesKey("allowed_app_date")
        val allowedAppCount = intPreferencesKey("allowed_app_count")
        val activeSelectedPackage = stringPreferencesKey("active_selected_package")
        val breakHistory = stringPreferencesKey("break_history")
        val scheduleEnabled = booleanPreferencesKey("schedule_enabled")
        val scheduleStartHour = intPreferencesKey("schedule_start_hour")
        val scheduleEndHour = intPreferencesKey("schedule_end_hour")
        val daySchedules = stringPreferencesKey("day_schedules")
        val usageDate = longPreferencesKey("usage_date")
        val usageToday = stringPreferencesKey("usage_today")
        val usageYesterday = stringPreferencesKey("usage_yesterday")
        val baselineUsage = stringPreferencesKey("baseline_usage")
        val baselineDailyUsage = stringPreferencesKey("baseline_daily_usage")
        val baselineVersion = intPreferencesKey("baseline_version")
        val usageHistory = stringPreferencesKey("usage_history")
        val protectionDate = longPreferencesKey("protection_date")
        val protectionToday = longPreferencesKey("protection_today")
        val introDismissed = booleanPreferencesKey("intro_dismissed")
        val firstLaunchPreview = booleanPreferencesKey("first_launch_preview")
        val testStatsEnabled = booleanPreferencesKey("test_stats_enabled")
        val debugStatus = stringPreferencesKey("debug_status")
    }

    val settings: Flow<BlackWhiteSettings> = context.dataStore.data.map { prefs ->
        val today = LocalDate.now().toEpochDay()
        val storedAppEnabled = prefs[Keys.appEnabled] ?: true
        val disabledDate = prefs[Keys.appDisabledDate] ?: 0L
        val autoEnabledForNewDay = !storedAppEnabled && disabledDate > 0L && disabledDate < today
        val pauseCountDate = prefs[Keys.pauseCountDate] ?: 0L
        val allowedAppDate = prefs[Keys.allowedAppDate] ?: 0L
        val protectionDate = prefs[Keys.protectionDate] ?: today
        val scheduleStartHour = (prefs[Keys.scheduleStartHour] ?: BlackWhiteSettings.DEFAULT_SCHEDULE_START_HOUR)
            .coerceIn(0, 23)
        val scheduleEndHour = (prefs[Keys.scheduleEndHour] ?: BlackWhiteSettings.DEFAULT_SCHEDULE_END_HOUR)
            .coerceIn(0, 23)
        BlackWhiteSettings(
            selectedPackages = prefs[Keys.selectedPackages].orEmpty(),
            filterMode = prefs[Keys.filterMode].toFilterMode(),
            isPro = prefs[Keys.isPro] ?: false,
            isAppEnabled = storedAppEnabled || autoEnabledForNewDay,
            appDisabledDateEpochDay = disabledDate,
            scheduleOverrideDateEpochDay = prefs[Keys.scheduleOverrideDate] ?: 0L,
            quickOverlayAlpha = (prefs[Keys.quickOverlayAlpha] ?: BlackWhiteSettings.DEFAULT_QUICK_OVERLAY_ALPHA)
                .coerceIn(
                    BlackWhiteSettings.MIN_QUICK_OVERLAY_ALPHA,
                    BlackWhiteSettings.MAX_QUICK_OVERLAY_ALPHA
                ),
            quickFilterStyle = prefs[Keys.quickFilterStyle].toQuickFilterStyle(),
            pausedUntilMillis = prefs[Keys.pausedUntil] ?: 0L,
            pauseCountDateEpochDay = pauseCountDate,
            pauseCountToday = if (pauseCountDate == today) prefs[Keys.pauseCount] ?: 0 else 0,
            pauseTimeTodayMillis = if (pauseCountDate == today) prefs[Keys.pauseTimeToday] ?: 0L else 0L,
            allowedPackageName = prefs[Keys.allowedPackage].orEmpty(),
            allowedUntilMillis = prefs[Keys.allowedUntil] ?: 0L,
            allowedAppDateEpochDay = allowedAppDate,
            allowedAppCountToday = if (allowedAppDate == today) prefs[Keys.allowedAppCount] ?: 0 else 0,
            activeSelectedPackageName = prefs[Keys.activeSelectedPackage].orEmpty(),
            breakHistory = decodeBreakHistory(prefs[Keys.breakHistory].orEmpty()),
            scheduleEnabled = prefs[Keys.scheduleEnabled] ?: false,
            scheduleStartHour = scheduleStartHour,
            scheduleEndHour = scheduleEndHour,
            daySchedules = decodeDaySchedules(prefs[Keys.daySchedules].orEmpty(), scheduleStartHour, scheduleEndHour),
            usageDateEpochDay = prefs[Keys.usageDate] ?: LocalDate.now().toEpochDay(),
            usageToday = decodeUsageMap(prefs[Keys.usageToday].orEmpty()),
            usageYesterday = decodeUsageMap(prefs[Keys.usageYesterday].orEmpty()),
            baselineUsage = decodeUsageMap(prefs[Keys.baselineUsage].orEmpty()),
            baselineDailyUsage = decodeDailyUsageMap(prefs[Keys.baselineDailyUsage].orEmpty()),
            baselineVersion = prefs[Keys.baselineVersion] ?: 0,
            usageHistory = decodeDailyUsageMap(prefs[Keys.usageHistory].orEmpty()),
            protectionDateEpochDay = protectionDate,
            protectionTodayMillis = if (protectionDate == today) prefs[Keys.protectionToday] ?: 0L else 0L,
            introDismissed = prefs[Keys.introDismissed] ?: false,
            firstLaunchPreview = prefs[Keys.firstLaunchPreview] ?: false,
            testStatsEnabled = prefs[Keys.testStatsEnabled] ?: false,
            debugStatus = prefs[Keys.debugStatus].orEmpty()
        )
    }

    suspend fun setFilterMode(mode: FilterMode) {
        context.dataStore.edit { it[Keys.filterMode] = mode.name }
    }

    suspend fun setPro(enabled: Boolean) {
        context.dataStore.edit { it[Keys.isPro] = enabled }
    }

    suspend fun setTestStatsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.testStatsEnabled] = enabled }
    }

    suspend fun setFirstLaunchPreview(enabled: Boolean) {
        val today = LocalDate.now().toEpochDay()
        context.dataStore.edit { prefs ->
            prefs[Keys.firstLaunchPreview] = enabled
            if (enabled) {
                prefs[Keys.selectedPackages] = emptySet()
                prefs[Keys.filterMode] = FilterMode.Quick.name
                prefs[Keys.isPro] = false
                prefs[Keys.appEnabled] = true
                prefs[Keys.appDisabledDate] = 0L
                prefs[Keys.scheduleOverrideDate] = 0L
                prefs[Keys.quickFilterStyle] = QuickFilterStyle.Dark.name
                prefs[Keys.pausedUntil] = 0L
                prefs[Keys.pauseCountDate] = today
                prefs[Keys.pauseCount] = 0
                prefs[Keys.pauseTimeToday] = 0L
                prefs[Keys.allowedPackage] = ""
                prefs[Keys.allowedUntil] = 0L
                prefs[Keys.allowedAppDate] = today
                prefs[Keys.allowedAppCount] = 0
                prefs[Keys.activeSelectedPackage] = ""
                prefs[Keys.breakHistory] = ""
                prefs[Keys.scheduleEnabled] = false
                prefs[Keys.usageDate] = today
                prefs[Keys.usageToday] = ""
                prefs[Keys.usageYesterday] = ""
                prefs[Keys.baselineUsage] = ""
                prefs[Keys.baselineDailyUsage] = ""
                prefs[Keys.baselineVersion] = 0
                prefs[Keys.usageHistory] = ""
                prefs[Keys.protectionDate] = today
                prefs[Keys.protectionToday] = 0L
                prefs[Keys.introDismissed] = false
                prefs[Keys.testStatsEnabled] = false
                prefs[Keys.debugStatus] = ""
            }
        }
    }

    suspend fun setAppEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.appEnabled] = enabled
            it[Keys.appDisabledDate] = if (enabled) 0L else LocalDate.now().toEpochDay()
            it[Keys.pausedUntil] = 0L
            it[Keys.allowedPackage] = ""
            it[Keys.allowedUntil] = 0L
            it[Keys.scheduleOverrideDate] = 0L
        }
    }

    suspend fun enableProtectionNow() {
        context.dataStore.edit {
            it[Keys.appEnabled] = true
            it[Keys.appDisabledDate] = 0L
            it[Keys.scheduleOverrideDate] = LocalDate.now().toEpochDay()
            it[Keys.pausedUntil] = 0L
            it[Keys.allowedPackage] = ""
            it[Keys.allowedUntil] = 0L
        }
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

    suspend fun setScheduleForDay(dayOfWeek: Int, startHour: Int, endHour: Int) {
        val safeDay = dayOfWeek.coerceIn(1, 7)
        context.dataStore.edit { prefs ->
            val schedules = decodeDaySchedules(
                prefs[Keys.daySchedules].orEmpty(),
                prefs[Keys.scheduleStartHour] ?: BlackWhiteSettings.DEFAULT_SCHEDULE_START_HOUR,
                prefs[Keys.scheduleEndHour] ?: BlackWhiteSettings.DEFAULT_SCHEDULE_END_HOUR
            ).toMutableMap()
            schedules[safeDay] = DaySchedule(startHour.coerceIn(0, 23), endHour.coerceIn(0, 23))
            prefs[Keys.daySchedules] = encodeDaySchedules(schedules)
        }
    }

    suspend fun setScheduleForDays(days: Set<Int>, startHour: Int, endHour: Int) {
        if (days.isEmpty()) return
        context.dataStore.edit { prefs ->
            val schedules = decodeDaySchedules(
                prefs[Keys.daySchedules].orEmpty(),
                prefs[Keys.scheduleStartHour] ?: BlackWhiteSettings.DEFAULT_SCHEDULE_START_HOUR,
                prefs[Keys.scheduleEndHour] ?: BlackWhiteSettings.DEFAULT_SCHEDULE_END_HOUR
            ).toMutableMap()
            val schedule = DaySchedule(startHour.coerceIn(0, 23), endHour.coerceIn(0, 23))
            days.forEach { day -> schedules[day.coerceIn(1, 7)] = schedule }
            prefs[Keys.daySchedules] = encodeDaySchedules(schedules)
        }
    }

    suspend fun copySchedule(sourceDayOfWeek: Int, targetDays: Set<Int>) {
        if (targetDays.isEmpty()) return
        val safeSource = sourceDayOfWeek.coerceIn(1, 7)
        context.dataStore.edit { prefs ->
            val schedules = decodeDaySchedules(
                prefs[Keys.daySchedules].orEmpty(),
                prefs[Keys.scheduleStartHour] ?: BlackWhiteSettings.DEFAULT_SCHEDULE_START_HOUR,
                prefs[Keys.scheduleEndHour] ?: BlackWhiteSettings.DEFAULT_SCHEDULE_END_HOUR
            ).toMutableMap()
            val source = schedules[safeSource] ?: DaySchedule()
            targetDays.forEach { day ->
                schedules[day.coerceIn(1, 7)] = source
            }
            prefs[Keys.daySchedules] = encodeDaySchedules(schedules)
        }
    }

    suspend fun pauseFor(minutes: Long) {
        if (minutes <= 0L) {
            clearPause()
            return
        }
        context.dataStore.edit { prefs ->
            val today = LocalDate.now().toEpochDay()
            val pauseMillis = minutes * 60_000L
            val pauseCountDate = prefs[Keys.pauseCountDate] ?: 0L
            val count = if (pauseCountDate == today) prefs[Keys.pauseCount] ?: 0 else 0
            if (count >= BlackWhiteSettings.DAILY_PAUSE_LIMIT) return@edit
            val pauseTime = if (pauseCountDate == today) prefs[Keys.pauseTimeToday] ?: 0L else 0L
            prefs[Keys.pausedUntil] = System.currentTimeMillis() + pauseMillis
            prefs[Keys.pauseCountDate] = today
            prefs[Keys.pauseCount] = count + 1
            prefs[Keys.pauseTimeToday] = pauseTime + pauseMillis
            prefs[Keys.breakHistory] = encodeBreakHistory(
                decodeBreakHistory(prefs[Keys.breakHistory].orEmpty())
                    .incrementPause(today)
            )
        }
    }

    suspend fun clearPause() {
        context.dataStore.edit { it[Keys.pausedUntil] = 0L }
    }

    suspend fun allowOneApp(packageName: String, minutes: Long = BlackWhiteSettings.ONE_APP_ALLOW_MINUTES) {
        if (packageName.isBlank() || minutes <= 0L) return
        val today = LocalDate.now().toEpochDay()
        context.dataStore.edit { prefs ->
            val allowedDate = prefs[Keys.allowedAppDate] ?: 0L
            val count = if (allowedDate == today) prefs[Keys.allowedAppCount] ?: 0 else 0
            if (count >= BlackWhiteSettings.DAILY_ALLOWED_APP_LIMIT) return@edit
            prefs[Keys.allowedPackage] = packageName
            prefs[Keys.allowedUntil] = System.currentTimeMillis() + minutes * 60_000L
            prefs[Keys.allowedAppDate] = today
            prefs[Keys.allowedAppCount] = count + 1
            prefs[Keys.breakHistory] = encodeBreakHistory(
                decodeBreakHistory(prefs[Keys.breakHistory].orEmpty())
                    .incrementAllowedApp(today)
            )
        }
    }

    suspend fun clearAllowedApp() {
        context.dataStore.edit {
            it[Keys.allowedPackage] = ""
            it[Keys.allowedUntil] = 0L
        }
    }

    suspend fun resetBreakCounters() {
        val today = LocalDate.now().toEpochDay()
        context.dataStore.edit {
            it[Keys.pausedUntil] = 0L
            it[Keys.pauseCountDate] = today
            it[Keys.pauseCount] = 0
            it[Keys.pauseTimeToday] = 0L
            it[Keys.allowedPackage] = ""
            it[Keys.allowedUntil] = 0L
            it[Keys.allowedAppDate] = today
            it[Keys.allowedAppCount] = 0
            it[Keys.breakHistory] = ""
        }
    }

    suspend fun setActiveSelectedPackage(packageName: String?) {
        context.dataStore.edit { prefs ->
            val next = packageName.orEmpty()
            if (prefs[Keys.activeSelectedPackage].orEmpty() != next) {
                prefs[Keys.activeSelectedPackage] = next
            }
        }
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
            val yesterdayUsage = decodeUsageMap(prefs[Keys.usageYesterday].orEmpty())
            val history = decodeDailyUsageMap(prefs[Keys.usageHistory].orEmpty()).toMutableMap()
            if (yesterdayUsage.isNotEmpty()) {
                history.putIfAbsent(today - 1L, yesterdayUsage)
            }

            if (storedDate != today) {
                if (todayUsage.isNotEmpty()) {
                    history[storedDate] = todayUsage.toMap()
                }
                val newTodayUsage = mapOf(packageName to durationMillis)
                prefs[Keys.usageDate] = today
                prefs[Keys.usageYesterday] = encodeUsageMap(todayUsage)
                prefs[Keys.usageToday] = encodeUsageMap(newTodayUsage)
                history[today] = newTodayUsage
            } else {
                todayUsage[packageName] = (todayUsage[packageName] ?: 0L) + durationMillis
                prefs[Keys.usageDate] = today
                prefs[Keys.usageToday] = encodeUsageMap(todayUsage)
                history[today] = todayUsage.toMap()
            }
            prefs[Keys.usageHistory] = encodeDailyUsageMap(history.filterKeys { it >= today - 30L })
        }
    }

    suspend fun addProtectionTime(durationMillis: Long) {
        if (durationMillis < MIN_USAGE_SAMPLE_MS) return
        val today = LocalDate.now().toEpochDay()
        context.dataStore.edit { prefs ->
            val storedDate = prefs[Keys.protectionDate] ?: today
            val current = if (storedDate == today) prefs[Keys.protectionToday] ?: 0L else 0L
            prefs[Keys.protectionDate] = today
            prefs[Keys.protectionToday] = current + durationMillis
        }
    }

    suspend fun captureBaselineIfEmpty(usage: Map<String, Long>, dailyUsage: Map<Long, Map<String, Long>>) {
        if (usage.isEmpty() && dailyUsage.isEmpty()) return
        context.dataStore.edit { prefs ->
            val version = prefs[Keys.baselineVersion] ?: 0
            if (version < BlackWhiteSettings.CURRENT_BASELINE_VERSION) {
                prefs[Keys.baselineUsage] = ""
                prefs[Keys.baselineDailyUsage] = ""
            }
            val currentUsage = if (version < BlackWhiteSettings.CURRENT_BASELINE_VERSION) {
                ""
            } else {
                prefs[Keys.baselineUsage].orEmpty()
            }
            val currentDailyText = if (version < BlackWhiteSettings.CURRENT_BASELINE_VERSION) {
                ""
            } else {
                prefs[Keys.baselineDailyUsage].orEmpty()
            }
            val currentDaily = decodeDailyUsageMap(currentDailyText)
            val shouldReplaceDaily = dailyUsage.isNotEmpty() && (
                currentDaily.isEmpty() || dailyUsage.size > currentDaily.size
            )
            if (shouldReplaceDaily) {
                prefs[Keys.baselineDailyUsage] = encodeDailyUsageMap(dailyUsage)
                prefs[Keys.baselineUsage] = encodeUsageMap(dailyUsage.totalUsageByPackage())
            } else if (usage.isNotEmpty() && (currentUsage.isBlank() || decodeUsageMap(currentUsage).isEmpty())) {
                prefs[Keys.baselineUsage] = encodeUsageMap(usage)
            }
            prefs[Keys.baselineVersion] = BlackWhiteSettings.CURRENT_BASELINE_VERSION
        }
    }

    suspend fun dismissIntro() {
        context.dataStore.edit { it[Keys.introDismissed] = true }
    }

    private fun String?.toFilterMode(): FilterMode {
        return runCatching { FilterMode.valueOf(this ?: FilterMode.Quick.name) }
            .getOrDefault(FilterMode.Quick)
    }

    private fun String?.toQuickFilterStyle(): QuickFilterStyle {
        return runCatching { QuickFilterStyle.valueOf(this ?: QuickFilterStyle.Dark.name) }
            .getOrDefault(QuickFilterStyle.Dark)
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

private fun decodeDailyUsageMap(raw: String): Map<Long, Map<String, Long>> {
    if (raw.isBlank()) return emptyMap()
    return runCatching {
        val json = JSONObject(raw)
        json.keys().asSequence().associate { dayKey ->
            val apps = json.optJSONObject(dayKey) ?: JSONObject()
            dayKey.toLong() to apps.keys().asSequence().associateWith { packageName ->
                apps.optLong(packageName, 0L)
            }.filterValues { it > 0L }
        }.filterValues { it.isNotEmpty() }
    }.getOrDefault(emptyMap())
}

private fun encodeDailyUsageMap(values: Map<Long, Map<String, Long>>): String {
    val json = JSONObject()
    values.forEach { (day, apps) ->
        val appsJson = JSONObject()
        apps.forEach { (packageName, duration) ->
            if (duration > 0L) appsJson.put(packageName, duration)
        }
        if (appsJson.length() > 0) json.put(day.toString(), appsJson)
    }
    return json.toString()
}

private fun decodeBreakHistory(raw: String): Map<Long, DailyBreakStats> {
    if (raw.isBlank()) return emptyMap()
    return runCatching {
        val json = JSONObject(raw)
        json.keys().asSequence().associate { dayKey ->
            val dayJson = json.optJSONObject(dayKey) ?: JSONObject()
            dayKey.toLong() to DailyBreakStats(
                pauseCount = dayJson.optInt("pause", 0),
                allowedAppCount = dayJson.optInt("allow", 0)
            )
        }.filterValues { it.pauseCount > 0 || it.allowedAppCount > 0 }
    }.getOrDefault(emptyMap())
}

private fun encodeBreakHistory(values: Map<Long, DailyBreakStats>): String {
    val json = JSONObject()
    values.forEach { (day, stats) ->
        if (stats.pauseCount > 0 || stats.allowedAppCount > 0) {
            json.put(
                day.toString(),
                JSONObject()
                    .put("pause", stats.pauseCount)
                    .put("allow", stats.allowedAppCount)
            )
        }
    }
    return json.toString()
}

private fun Map<Long, DailyBreakStats>.incrementPause(day: Long): Map<Long, DailyBreakStats> {
    val next = toMutableMap()
    val current = next[day] ?: DailyBreakStats()
    next[day] = current.copy(pauseCount = current.pauseCount + 1)
    return next.filterKeys { it >= day - 30L }
}

private fun Map<Long, DailyBreakStats>.incrementAllowedApp(day: Long): Map<Long, DailyBreakStats> {
    val next = toMutableMap()
    val current = next[day] ?: DailyBreakStats()
    next[day] = current.copy(allowedAppCount = current.allowedAppCount + 1)
    return next.filterKeys { it >= day - 30L }
}

private fun Map<Long, Map<String, Long>>.totalUsageByPackage(): Map<String, Long> {
    return values
        .flatMap { it.entries }
        .groupBy { it.key }
        .mapValues { entry -> entry.value.sumOf { it.value } }
        .filterValues { it > 0L }
}

private fun decodeDaySchedules(raw: String, fallbackStartHour: Int, fallbackEndHour: Int): Map<Int, DaySchedule> {
    val fallback = (1..7).associateWith {
        DaySchedule(fallbackStartHour.coerceIn(0, 23), fallbackEndHour.coerceIn(0, 23))
    }
    if (raw.isBlank()) return fallback
    return runCatching {
        val json = JSONObject(raw)
        (1..7).associateWith { day ->
            val dayJson = json.optJSONObject(day.toString())
            DaySchedule(
                startHour = dayJson?.optInt("start", fallbackStartHour)?.coerceIn(0, 23)
                    ?: fallbackStartHour.coerceIn(0, 23),
                endHour = dayJson?.optInt("end", fallbackEndHour)?.coerceIn(0, 23)
                    ?: fallbackEndHour.coerceIn(0, 23)
            )
        }
    }.getOrDefault(fallback)
}

private fun encodeDaySchedules(values: Map<Int, DaySchedule>): String {
    val json = JSONObject()
    (1..7).forEach { day ->
        val schedule = values[day] ?: DaySchedule()
        json.put(
            day.toString(),
            JSONObject()
                .put("start", schedule.startHour.coerceIn(0, 23))
                .put("end", schedule.endHour.coerceIn(0, 23))
        )
    }
    return json.toString()
}
