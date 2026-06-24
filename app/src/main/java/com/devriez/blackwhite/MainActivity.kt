package com.devriez.blackwhite

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BlackWhiteTheme {
                BlackWhiteApp()
            }
        }
    }
}

data class InstalledApp(
    val label: String,
    val packageName: String
)

data class SystemUsageSnapshot(
    val last7Days: Map<String, Long> = emptyMap(),
    val last7DaysDaily: Map<Long, Map<String, Long>> = emptyMap(),
    val beforeInstallUsage: Map<String, Long> = emptyMap(),
    val beforeInstallDailyUsage: Map<Long, Map<String, Long>> = emptyMap(),
    val today: Map<String, Long> = emptyMap(),
    val appInstallEpochDay: Long = LocalDate.now().toEpochDay()
)

private val WeekdayScheduleDays = setOf(1, 2, 3, 4, 5)
private val WeekendScheduleDays = setOf(6, 7)
private const val BASELINE_LOOKBACK_DAYS = 30L
private const val TEST_FACEBOOK_PACKAGE = "com.facebook.katana"
private const val TEST_INSTAGRAM_PACKAGE = "com.instagram.android"
private const val TEST_YOUTUBE_PACKAGE = "com.google.android.youtube"
private const val TEST_DUOCARDS_PACKAGE = "com.duocards.app"
private const val TEST_TELEGRAM_PACKAGE = "org.telegram.messenger"
private const val TEST_TWITTER_PACKAGE = "com.twitter.android"

private enum class AppScreen(val title: String) {
    Home("ScrollLess"),
    Apps("Приложения"),
    Color("Цвет экрана"),
    Schedule("Расписание"),
    Pro("ScrollLess Pro")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BlackWhiteApp() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val settings by store.settings.collectAsState(initial = BlackWhiteSettings())
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var permissionSnapshot by remember { mutableStateOf(PermissionSnapshot.from(context)) }
    var systemUsage by remember { mutableStateOf(SystemUsageSnapshot()) }
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    var currentScreen by remember { mutableStateOf(AppScreen.Home) }
    var progressExpanded by remember { mutableStateOf(false) }
    var candidateNotice by remember { mutableStateOf("") }
    var heroNotice by remember { mutableStateOf("") }
    var appsScreenOrder by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var firstLaunchHistoryRequested by remember { mutableStateOf(false) }
    val appLabels = remember(apps) { apps.associate { it.packageName to it.label } }
    val effectiveSettings = remember(settings) {
        if (settings.testStatsEnabled) settings.withTestStatsSelection() else settings
    }
    val sortedApps = remember(apps, effectiveSettings.selectedPackages) {
        apps.sortedForSelection(effectiveSettings.selectedPackages)
    }
    val displayedApps = if (currentScreen == AppScreen.Apps && appsScreenOrder.isNotEmpty()) {
        appsScreenOrder
    } else {
        sortedApps
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { context.loadLaunchableApps() }
        while (true) {
            permissionSnapshot = PermissionSnapshot.from(context)
            if (settings.firstLaunchPreview) {
                systemUsage = if (permissionSnapshot.usageStatsGranted && firstLaunchHistoryRequested) {
                    withContext(Dispatchers.IO) { context.loadSystemUsageSnapshot() }
                } else {
                    SystemUsageSnapshot()
                }
            } else if (permissionSnapshot.usageStatsGranted) {
                systemUsage = withContext(Dispatchers.IO) { context.loadSystemUsageSnapshot() }
                scope.launch {
                    store.captureBaselineIfEmpty(
                        systemUsage.beforeInstallUsage,
                        systemUsage.beforeInstallDailyUsage
                    )
                }
            }
            delay(1_000L)
        }
    }

    LaunchedEffect(settings.pausedUntilMillis) {
        while (settings.isPaused(nowMillis)) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
        nowMillis = System.currentTimeMillis()
    }

    LaunchedEffect(settings.filterMode) {
        if (settings.filterMode == FilterMode.Full) {
            store.setFilterMode(FilterMode.Quick)
        }
    }

    LaunchedEffect(settings.selectedPackages) {
        if (settings.selectedPackages.isNotEmpty()) {
            heroNotice = ""
        }
    }

    LaunchedEffect(settings.firstLaunchPreview) {
        firstLaunchHistoryRequested = false
    }

    LaunchedEffect(currentScreen, apps) {
        if (currentScreen == AppScreen.Apps) {
            if (appsScreenOrder.isEmpty()) {
                appsScreenOrder = apps.sortedForSelection(settings.selectedPackages)
            }
        } else {
            appsScreenOrder = emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(currentScreen.title, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    if (currentScreen != AppScreen.Home) {
                        IconButton(onClick = { currentScreen = AppScreen.Home }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Назад")
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentScreen) {
                    var totalDragX = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDragX = 0f },
                        onHorizontalDrag = { _, dragAmount -> totalDragX += dragAmount },
                        onDragEnd = {
                            if (currentScreen != AppScreen.Home && totalDragX > 140f) {
                                currentScreen = AppScreen.Home
                            }
                        }
                    )
                }
                .background(Color(0xFFF7F7F2))
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (currentScreen) {
                AppScreen.Home -> {
                    if (!settings.introDismissed) {
                        item {
                            IntroCard(onDismiss = { scope.launch { store.dismissIntro() } })
                        }
                    }

                    item {
                        HeroCard(
                            settings = settings,
                            nowMillis = nowMillis,
                            accessibilityEnabled = permissionSnapshot.accessibilityEnabled,
                            notice = heroNotice,
                            onEnable = {
                                if (settings.selectedPackages.isEmpty()) {
                                    heroNotice = "Сначала выбери хотя бы одно приложение в настройках."
                                } else if (!settings.isWithinSchedule(LocalTime.now().hour, LocalDate.now().dayOfWeek.value)) {
                                    heroNotice = "Защита включена вручную до полуночи. Потом снова будет работать расписание."
                                    scope.launch {
                                        store.clearPause()
                                        store.enableProtectionNow()
                                    }
                                } else {
                                    heroNotice = ""
                                    scope.launch {
                                        store.clearPause()
                                        store.setAppEnabled(true)
                                    }
                                }
                            },
                            onDisable = {
                                heroNotice = ""
                                scope.launch {
                                    store.clearPause()
                                    store.setAppEnabled(false)
                                }
                            },
                            onPause = {
                                if (settings.selectedPackages.isEmpty()) {
                                    heroNotice = "Сначала выбери хотя бы одно приложение в настройках."
                                } else {
                                    heroNotice = ""
                                    scope.launch {
                                        store.setAppEnabled(true)
                                        store.pauseFor(15)
                                    }
                                }
                            },
                        )
                    }

                    if (permissionSnapshot.shouldShow()) {
                        item {
                            PermissionsCard(
                                snapshot = permissionSnapshot,
                                onOpenAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                            )
                        }
                    }

                    item {
                        SettingsNavCard(
                            settings = effectiveSettings,
                            onAppsClick = { currentScreen = AppScreen.Apps },
                            onScheduleClick = { currentScreen = AppScreen.Schedule },
                            onFilterClick = { currentScreen = AppScreen.Color }
                        )
                    }

                    item {
                        val previewUsageStatsGranted = if (settings.testStatsEnabled) {
                            true
                        } else if (settings.firstLaunchPreview) {
                            permissionSnapshot.usageStatsGranted && firstLaunchHistoryRequested
                        } else {
                            permissionSnapshot.usageStatsGranted
                        }
                        UsageCard(
                            settings = effectiveSettings,
                            appLabels = appLabels,
                            systemUsage = if (settings.testStatsEnabled) {
                                buildTestUsageSnapshot()
                            } else if (settings.firstLaunchPreview) {
                                SystemUsageSnapshot(
                                    last7Days = systemUsage.last7Days,
                                    last7DaysDaily = systemUsage.last7DaysDaily,
                                    beforeInstallUsage = systemUsage.beforeInstallUsage,
                                    beforeInstallDailyUsage = systemUsage.beforeInstallDailyUsage,
                                    appInstallEpochDay = LocalDate.now().toEpochDay()
                                )
                            } else {
                                systemUsage
                            },
                            usageStatsGranted = previewUsageStatsGranted,
                            firstLaunchPreview = settings.firstLaunchPreview,
                            expanded = progressExpanded,
                            highlighted = false,
                            onToggleExpanded = { progressExpanded = !progressExpanded },
                            candidateNotice = candidateNotice,
                            onOpenPro = { currentScreen = AppScreen.Pro },
                            onCandidateChange = { packageName, checked ->
                                if (!checked || settings.canSelectMore(packageName)) {
                                    candidateNotice = ""
                                    scope.launch { store.togglePackage(packageName, checked, settings) }
                                } else {
                                    candidateNotice = "В Free можно выбрать 2 приложения. Pro снимает лимит."
                                }
                            },
                            onOpenUsageAccess = {
                                firstLaunchHistoryRequested = true
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        )
                    }

                    item {
                        DeveloperProCard(
                            settings = settings,
                            onProChange = { enabled -> scope.launch { store.setPro(enabled) } },
                            onTestStatsChange = { enabled -> scope.launch { store.setTestStatsEnabled(enabled) } },
                            onFirstLaunchPreviewChange = { enabled ->
                                heroNotice = ""
                                candidateNotice = ""
                                progressExpanded = false
                                firstLaunchHistoryRequested = false
                                scope.launch { store.setFirstLaunchPreview(enabled) }
                            }
                        )
                    }
                }

                AppScreen.Apps -> {
                    stickyHeader {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF7F7F2))
                                .padding(bottom = 4.dp)
                        ) {
                            AppsHeader(
                                settings = effectiveSettings,
                                onOpenPro = { currentScreen = AppScreen.Pro }
                            )
                        }
                    }
                    items(displayedApps, key = { it.packageName }) { app ->
                        val checked = settings.selectedPackages.contains(app.packageName)
                        val locked = !checked && !effectiveSettings.canSelectMore(app.packageName)
                        AppRow(
                            app = app,
                            checked = checked,
                            locked = locked,
                            onCheckedChange = { next ->
                                scope.launch { store.togglePackage(app.packageName, next, settings) }
                            }
                        )
                    }
                }

                AppScreen.Color -> {
                    item {
                        ModeCard(
                            settings = settings,
                            expanded = true,
                            highlighted = false,
                            showHeader = false,
                            onToggleExpanded = {},
                            onQuickFilterStyleChange = { style -> scope.launch { store.setQuickFilterStyle(style) } }
                        )
                    }
                }

                AppScreen.Schedule -> {
                    item {
                        ScheduleCard(
                            settings = settings,
                            expanded = true,
                            highlighted = false,
                            showHeader = false,
                            onToggleExpanded = {},
                            onOpenPro = { currentScreen = AppScreen.Pro },
                            onScheduleChange = { enabled -> scope.launch { store.setScheduleEnabled(enabled) } },
                            onScheduleForDaysChange = { days, start, end ->
                                scope.launch { store.setScheduleForDays(days, start, end) }
                            }
                        )
                    }
                }

                AppScreen.Pro -> {
                    item {
                        ProUpgradeCard(
                            settings = settings,
                            onBack = { currentScreen = AppScreen.Home }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(
    settings: BlackWhiteSettings,
    nowMillis: Long,
    accessibilityEnabled: Boolean,
    notice: String,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onPause: () -> Unit
) {
    val isPaused = settings.isPaused(nowMillis)
    val hasSelectedApps = settings.selectedPackages.isNotEmpty()
    SectionCard {
        Text(
            when {
                !hasSelectedApps -> "Выберите приложения"
                isPaused -> "Пауза: ${settings.pauseRemainingLabel(nowMillis)}"
                settings.isAppEnabled -> "Защита включена"
                else -> "Выключено до завтра"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = when {
                !hasSelectedApps -> Color(0xFF444444)
                isPaused -> Color(0xFF8A6D00)
                settings.isAppEnabled -> Color(0xFF146C43)
                else -> Color(0xFFB3261E)
            }
        )
        if (!hasSelectedApps) {
            Text(
                if (accessibilityEnabled) {
                    "Защита заработает после выбора хотя бы одного приложения."
                } else {
                    "Чтобы фильтр заработал, выбери приложения и включи Accessibility Service."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF444444)
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                modifier = Modifier.weight(1f).height(58.dp),
                selected = hasSelectedApps && settings.isAppEnabled && !isPaused,
                onClick = onEnable,
                label = { HeroButtonContent(Icons.Default.PowerSettingsNew, "вкл") }
            )
            FilterChip(
                modifier = Modifier.weight(1f).height(58.dp),
                selected = !settings.isAppEnabled,
                onClick = onDisable,
                label = { HeroButtonContent(Icons.Default.PowerOff, "до завтра") }
            )
            FilterChip(
                modifier = Modifier.weight(1f).height(58.dp),
                selected = isPaused,
                onClick = onPause,
                label = { HeroButtonContent(Icons.Default.Pause, "15м") }
            )
        }
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                notice,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF8A6D00)
            )
        }
    }
}

@Composable
private fun SettingsNavCard(
    settings: BlackWhiteSettings,
    onAppsClick: () -> Unit,
    onScheduleClick: () -> Unit,
    onFilterClick: () -> Unit
) {
    val appsCountLabel = if (settings.isPro) {
        "${settings.selectedPackages.size}/∞"
    } else {
        "${settings.selectedPackages.size}/${BlackWhiteSettings.FREE_APP_LIMIT}"
    }
    SectionCard {
        Text("Настройки", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                modifier = Modifier.weight(1f).height(58.dp),
                selected = false,
                onClick = onAppsClick,
                label = { AppsButtonContent(appsCountLabel) }
            )
            FilterChip(
                modifier = Modifier.weight(1f).height(58.dp),
                selected = false,
                onClick = onScheduleClick,
                label = { HeroButtonContent(Icons.Default.Schedule, "расписание") }
            )
            FilterChip(
                modifier = Modifier.weight(1f).height(58.dp),
                selected = false,
                onClick = onFilterClick,
                label = { HeroButtonContent(Icons.Default.Visibility, "режим") }
            )
        }
    }
}

@Composable
private fun ProUpgradeCard(
    settings: BlackWhiteSettings,
    onBack: () -> Unit
) {
    SectionCard {
        Text(
            if (settings.isPro) "Pro включен" else "ScrollLess Pro",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (settings.isPro) {
                "Сейчас открыт тестовый Pro-режим: можно выбирать больше двух приложений и пользоваться расписанием."
            } else {
                "Pro нужен, когда хочется настроить ScrollLess под себя и не ограничиваться двумя приложениями."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF444444)
        )
        Spacer(Modifier.height(16.dp))
        ProBenefitRow(
            title = "Без лимита приложений",
            description = "Добавляй все соцсети, мессенджеры и видео-приложения, где хочется меньше залипать."
        )
        ProBenefitRow(
            title = "Расписание",
            description = "Включай фильтр только в нужные часы: например, в рабочее время или вечером."
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (settings.isPro) "Вернуться" else "Понятно")
        }
    }
}

@Composable
private fun ProBenefitRow(
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = Color(0xFF3A6652),
            modifier = Modifier.padding(top = 1.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF555555)
            )
        }
    }
}

@Composable
private fun AppsButtonContent(countLabel: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            countLabel,
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
            maxLines = 1
        )
        Text(
            "приложений",
            textAlign = TextAlign.Center,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun HeroButtonContent(icon: ImageVector, label: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null)
        Text(
            label,
            textAlign = TextAlign.Center,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun IntroCard(onDismiss: () -> Unit) {
    SectionCard {
        Text("Добро пожаловать", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Насыщенные цвета, контрастные элементы и быстрые визуальные награды помогают приложениям удерживать внимание. ScrollLess снижает визуальную привлекательность выбранных приложений, чтобы ими было легче пользоваться осознанно.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF444444)
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onDismiss) {
            Text("Понятно")
        }
    }
}

@Composable
private fun UsageCard(
    settings: BlackWhiteSettings,
    appLabels: Map<String, String>,
    systemUsage: SystemUsageSnapshot,
    usageStatsGranted: Boolean,
    firstLaunchPreview: Boolean,
    expanded: Boolean,
    highlighted: Boolean,
    onToggleExpanded: () -> Unit,
    candidateNotice: String,
    onOpenPro: () -> Unit,
    onCandidateChange: (String, Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit
) {
    val todayUsage = settings.usageToday.maxMergedWith(systemUsage.today)
    val selectedToday = settings.selectedPackages.sumOf { todayUsage[it] ?: 0L }
    val selectedYesterday = settings.selectedPackages.sumOf { settings.usageYesterday[it] ?: 0L }
    val baselineUsage = when {
        systemUsage.beforeInstallUsage.isNotEmpty() -> systemUsage.beforeInstallUsage
        settings.baselineUsage.isNotEmpty() -> settings.baselineUsage
        else -> emptyMap()
    }
    val baselineDailyUsage = when {
        systemUsage.beforeInstallDailyUsage.isNotEmpty() -> systemUsage.beforeInstallDailyUsage
        settings.baselineDailyUsage.isNotEmpty() -> settings.baselineDailyUsage
        else -> emptyMap()
    }
    val chartDailyUsage = systemUsage.last7DaysDaily.withDailyFallback(settings.usageHistory)
    val progressDays = buildCompletedProgressDays(
        selectedPackages = settings.selectedPackages,
        baselineDailyUsage = baselineDailyUsage,
        dailyUsage = chartDailyUsage,
        appInstallEpochDay = systemUsage.appInstallEpochDay
    )
    val progressDaysWithBaseline = progressDays.filter { it.baselineDuration > 0L }
    val selectedBaselineDaily = if (progressDays.isNotEmpty()) {
        if (progressDaysWithBaseline.isNotEmpty()) {
            progressDaysWithBaseline.sumOf { it.baselineDuration } / progressDaysWithBaseline.size
        } else {
            0L
        }
    } else {
        averageUsageOnMeasuredDays(settings.selectedPackages, baselineDailyUsage, baselineUsage)
    }
    val selectedAfterDaily = if (progressDays.isNotEmpty()) {
        progressDays.sumOf { it.duration } / progressDays.size
    } else {
        0L
    }
    val baselineMeasuredDays = measuredUsageDays(settings.selectedPackages, baselineDailyUsage)
    val displayedBaselineDaily = selectedBaselineDaily.toDisplayedMinuteMillis()
    val displayedAfterDaily = selectedAfterDaily.toDisplayedMinuteMillis()
    val averageDelta = displayedBaselineDaily - displayedAfterDaily
    val averageSaved = averageDelta.coerceAtLeast(0L)
    val currentTopCandidates = todayUsage
        .filterKeys { it !in settings.selectedPackages }
        .filterValues { it >= 60_000L }
        .entries
        .sortedByDescending { it.value }
        .take(5)
    val historicalTopCandidates = baselineUsage.keys
        .filter { it !in settings.selectedPackages }
        .mapNotNull { packageName ->
            val duration = averageUsageOnMeasuredDays(setOf(packageName), baselineDailyUsage, baselineUsage)
            if (duration >= TimeUnit.MINUTES.toMillis(5)) packageName to duration else null
        }
        .sortedByDescending { it.second }
        .take(5)
    val currentCandidatePairs = currentTopCandidates.map { it.key to it.value }
    val topCandidates = if (historicalTopCandidates.isNotEmpty()) historicalTopCandidates else currentCandidatePairs
    var pinnedCandidates by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    LaunchedEffect(expanded, topCandidates) {
        if (!expanded) {
            pinnedCandidates = emptyList()
        } else if (pinnedCandidates.isEmpty()) {
            pinnedCandidates = topCandidates
        }
    }
    val displayedCandidates = if (expanded && pinnedCandidates.isNotEmpty()) pinnedCandidates else topCandidates
    val summaryText = if (settings.selectedPackages.isEmpty()) {
        "Выбери приложения, и ScrollLess начнет считать время в них."
    } else if (firstLaunchPreview && usageStatsGranted && selectedBaselineDaily > 0L) {
        "История подключена. Ниже видно, сколько выбранные приложения использовались до ScrollLess."
    } else if (firstLaunchPreview && usageStatsGranted) {
        "История подключена. Выбери приложения, чтобы увидеть их использование до ScrollLess."
    } else if (firstLaunchPreview) {
        "Подключи историю Android, чтобы увидеть прогресс."
    } else if (usageStatsGranted && selectedBaselineDaily > 0L && selectedAfterDaily > 0L && averageDelta >= 0L) {
        "С подключенным ScrollLess выбранные приложения используются в среднем на ${averageSaved.formatDuration()} меньше в день. Это примерно ${(averageDelta * 7L).formatDuration()} в неделю."
    } else if (usageStatsGranted && selectedBaselineDaily > 0L && selectedAfterDaily > 0L) {
        "Сейчас выбранные приложения используются на ${(-averageDelta).formatDuration()} больше старой нормы."
    } else if (usageStatsGranted && (selectedToday > 0L || selectedYesterday > 0L)) {
        "ScrollLess уже считает время в выбранных приложениях. Сравнение с прошлой привычкой появится после накопления первых данных."
    } else if (usageStatsGranted) {
        "История подключена. Когда появятся первые данные по выбранным приложениям, ScrollLess покажет, стали ли они забирать меньше времени."
    } else {
        "Подключи историю Android, чтобы увидеть прогресс."
    }

    SectionCard(highlighted = highlighted) {
        SectionHeaderRow(
            title = "Прогресс",
            expanded = expanded,
            highlighted = false,
            onClick = onToggleExpanded
        )
        Spacer(Modifier.height(10.dp))
        if (!usageStatsGranted) {
            Text(
                summaryText,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF444444)
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenUsageAccess) {
                Text("Подключить историю")
            }
        } else {
            if (settings.selectedPackages.isEmpty() || selectedBaselineDaily <= 0L || selectedAfterDaily <= 0L) {
                Text(
                    summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF444444)
                )
            } else if (firstLaunchPreview && selectedAfterDaily <= 0L) {
                Text(
                    summaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF444444)
                )
            } else {
                UsageDashboard(
                    settings = settings,
                    todayUsage = todayUsage,
                    progressDays = progressDays,
                    baselineAverageDaily = displayedBaselineDaily,
                    selectedAfterDaily = displayedAfterDaily,
                    averageDelta = averageDelta,
                    baselineMeasuredDays = baselineMeasuredDays,
                    showTrend = expanded
                )
            }
            if (!expanded) return@SectionCard
            Spacer(Modifier.height(26.dp))
            ProgressTable(
                settings = settings,
                appLabels = appLabels,
                baselineUsage = baselineUsage,
                baselineDailyUsage = baselineDailyUsage,
                dailyUsage = chartDailyUsage,
                todayUsage = todayUsage,
                appInstallEpochDay = systemUsage.appInstallEpochDay
            )
            if (displayedCandidates.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                Text("Похоже, здесь тоже уходит время", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                if (candidateNotice.isNotBlank()) {
                    Text(
                        candidateNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A6D00)
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!settings.isPro && settings.selectedPackages.size >= BlackWhiteSettings.FREE_APP_LIMIT) {
                        OutlinedButton(onClick = onOpenPro) {
                            Text("Снять лимит")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    displayedCandidates.forEach { entry ->
                        CandidateRow(
                            label = appLabels[entry.first] ?: entry.first,
                            packageName = entry.first,
                            duration = entry.second,
                            checked = settings.selectedPackages.contains(entry.first),
                            onCheckedChange = { checked -> onCandidateChange(entry.first, checked) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageDashboard(
    settings: BlackWhiteSettings,
    todayUsage: Map<String, Long>,
    progressDays: List<UsageBar>,
    baselineAverageDaily: Long,
    selectedAfterDaily: Long,
    averageDelta: Long,
    baselineMeasuredDays: Int,
    showTrend: Boolean
) {
    if (settings.selectedPackages.isEmpty()) return
    if (baselineAverageDaily <= 0L) return
    val selectedToday = settings.selectedPackages.sumOf { todayUsage[it] ?: 0L }
    val bars = progressDays

    Spacer(Modifier.height(2.dp))
    val savedTitle = when {
        averageDelta > 0L -> "На ${averageDelta.formatDuration()} в день меньше"
        averageDelta < 0L -> "На ${(-averageDelta).formatDuration()} в день больше"
        else -> "Примерно столько же, как раньше"
    }
    val progressColor = when {
        averageDelta > 0L -> Color(0xFF146C43)
        averageDelta < 0L -> Color(0xFFB3261E)
        else -> Color(0xFF444444)
    }
    Text(
        savedTitle,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = progressColor,
        textAlign = TextAlign.Center
    )
    Text(
        "в выбранных приложениях со ScrollLess",
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF444444),
        textAlign = TextAlign.Center
    )
    if (!showTrend) return
    Spacer(Modifier.height(24.dp))
    ProgressComparisonCard(
        beforeDuration = baselineAverageDaily,
        afterDuration = selectedAfterDaily
    )
    if (baselineMeasuredDays > 0) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Старая норма рассчитана по $baselineMeasuredDays дням истории Android.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF666666)
        )
    }
    Spacer(Modifier.height(24.dp))
    TodayUsageCard(
        duration = selectedToday,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(26.dp))
    Text(
        "График по дням",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(8.dp))
    UsageBarChart(
        bars = bars,
        baselineAverageDaily = baselineAverageDaily
    )
    Spacer(Modifier.height(10.dp))
    UsageChartLegend()
    Spacer(Modifier.height(8.dp))
    Text(
        "Сравниваем завершенные дни с такими же днями недели до установки.",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF666666)
    )
}

@Composable
private fun ProgressComparisonCard(
    beforeDuration: Long,
    afterDuration: Long
) {
    val maxDuration = maxOf(beforeDuration, afterDuration).coerceAtLeast(1L)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F1EC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ProgressComparisonRow(
                label = "До ScrollLess",
                duration = beforeDuration,
                maxDuration = maxDuration,
                color = Color(0xFF9A9A94)
            )
            ProgressComparisonRow(
                label = "Со ScrollLess",
                duration = afterDuration,
                maxDuration = maxDuration,
                color = Color(0xFF3A6652)
            )
        }
    }
}

@Composable
private fun ProgressComparisonRow(
    label: String,
    duration: Long,
    maxDuration: Long,
    color: Color
) {
    val fillFraction = (duration.toFloat() / maxDuration.toFloat())
        .coerceIn(0f, 1f)
        .let { if (duration > 0L) it.coerceAtLeast(0.05f) else 0f }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF444444)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(16.dp)
                .background(Color(0xFFE0E0DA))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fillFraction)
                    .height(16.dp)
                    .background(color)
            )
        }
        Text(
            duration.formatDuration(),
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TodayUsageCard(
    duration: Long,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F1EC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Сегодня в выбранных приложениях",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF444444)
            )
            Text(
                duration.formatDuration(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun UsageBarChart(
    bars: List<UsageBar>,
    baselineAverageDaily: Long
) {
    val visibleBars = bars.ifEmpty { listOf(UsageBar("Пока", 0L, 0L, false)) }
    val maxDuration = visibleBars.maxOfOrNull { maxOf(it.duration, it.baselineDuration) }
        ?.coerceAtLeast(baselineAverageDaily)
        ?: baselineAverageDaily
    if (maxDuration <= 0L) return
    val baselineColor = Color(0xFF777777)
    val afterColor = Color(0xFF3A6652)
    val emptyColor = Color(0xFFD8D8D2)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(Color(0xFFF1F1EC))
            .padding(10.dp)
    ) {
        val chartTop = 8.dp.toPx()
        val chartBottom = size.height - 26.dp.toPx()
        val chartHeight = (chartBottom - chartTop).coerceAtLeast(1f)
        val slot = size.width / visibleBars.size
        val barWidth = (slot * 0.52f).coerceAtMost(22.dp.toPx())
        visibleBars.forEachIndexed { index, bar ->
            val barHeight = chartHeight * (bar.duration.toFloat() / maxDuration.toFloat())
            val left = index * slot + (slot - barWidth) / 2f
            val top = chartBottom - barHeight
            drawRoundRect(
                color = when {
                    bar.duration <= 0L -> emptyColor
                    else -> afterColor
                },
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
            if (bar.baselineDuration > 0L) {
                val baselineY = chartBottom - chartHeight * (bar.baselineDuration.toFloat() / maxDuration.toFloat())
                drawLine(
                    color = baselineColor,
                    start = androidx.compose.ui.geometry.Offset(left - 3.dp.toPx(), baselineY),
                    end = androidx.compose.ui.geometry.Offset(left + barWidth + 3.dp.toPx(), baselineY),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        visibleBars.forEach { bar ->
            Text(
                bar.label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF666666)
            )
        }
    }
}

@Composable
private fun UsageChartLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ChartLegendItem(
            label = "старая норма до ScrollLess",
            color = Color(0xFF777777),
            isLine = true
        )
        ChartLegendItem(
            label = "дни со ScrollLess",
            color = Color(0xFF3A6652),
            isLine = false
        )
    }
}

@Composable
private fun ChartLegendItem(
    label: String,
    color: Color,
    isLine: Boolean
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(if (isLine) 24.dp else 12.dp)
                    .height(if (isLine) 2.dp else 8.dp)
                    .background(color)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF555555)
        )
    }
}

private data class UsageBar(
    val label: String,
    val duration: Long,
    val baselineDuration: Long,
    val isToday: Boolean
)

@Composable
private fun SectionHeaderRow(
    title: String,
    expanded: Boolean,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

@Composable
private fun CandidateRow(
    label: String,
    packageName: String,
    duration: Long,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Apps, contentDescription = null, tint = Color(0xFF222222))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(label, fontWeight = FontWeight.Medium)
                Text(
                    "${duration.formatDuration()} в день",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666)
                )
                Text(packageName, style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
            }
            Box(
                modifier = Modifier.width(56.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange
                )
            }
        }
    }
}

@Composable
private fun ProgressTable(
    settings: BlackWhiteSettings,
    appLabels: Map<String, String>,
    baselineUsage: Map<String, Long>,
    baselineDailyUsage: Map<Long, Map<String, Long>>,
    dailyUsage: Map<Long, Map<String, Long>>,
    todayUsage: Map<String, Long>,
    appInstallEpochDay: Long
) {
    if (settings.selectedPackages.isEmpty()) return
    Text("Отслеживаемые приложения", fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(8.dp))
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F1EC)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Приложение",
                    modifier = Modifier.weight(1.25f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666)
                )
                Text(
                    "Было",
                    modifier = Modifier.weight(0.62f),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666)
                )
                Text(
                    "Стало",
                    modifier = Modifier.weight(0.72f),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF666666)
                )
            }
            settings.selectedPackages
                .sortedBy { appLabels[it] ?: it }
                .forEach { packageName ->
                    val beforeDuration = averageUsageOnMeasuredDays(
                        setOf(packageName),
                        baselineDailyUsage,
                        baselineUsage
                    )
                    val afterDuration = averageScrollLessUsage(
                        setOf(packageName),
                        dailyUsage,
                        appInstallEpochDay = appInstallEpochDay
                    )
                    ProgressTableRow(
                        appName = appLabels[packageName] ?: packageName,
                        before = beforeDuration.formatDuration(),
                        after = afterDuration.formatDuration()
                    )
                }
        }
    }
}

@Composable
private fun ProgressTableRow(
    appName: String,
    before: String,
    after: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                appName,
                modifier = Modifier.weight(1.25f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            ProgressTableValue(before, Modifier.weight(0.62f), Color(0xFF666666))
            ProgressTableValue(after, Modifier.weight(0.72f), Color(0xFF3A6652))
        }
    }
}

@Composable
private fun ProgressTableValue(
    value: String,
    modifier: Modifier,
    color: Color
) {
    Text(
        value,
        modifier = modifier,
        textAlign = TextAlign.End,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1
    )
}

@Composable
private fun AppsHeader(
    settings: BlackWhiteSettings,
    onOpenPro: () -> Unit
) {
    SectionCard {
        Row(
            modifier = Modifier
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Приложения (${settings.selectedPackages.size}/${if (settings.isPro) "∞" else BlackWhiteSettings.FREE_APP_LIMIT})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    when {
                        !settings.isPro -> "В Free можно выбрать 2 приложения. Pro снимает лимит."
                        else -> "Выбранные приложения будут отображаться сверху."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (settings.isPro) Color(0xFF444444) else Color(0xFF8A6D00)
                )
                if (!settings.isPro && settings.selectedPackages.size >= BlackWhiteSettings.FREE_APP_LIMIT) {
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenPro) {
                        Text("Снять лимит")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(
    settings: BlackWhiteSettings,
    expanded: Boolean,
    highlighted: Boolean,
    showHeader: Boolean = true,
    onToggleExpanded: () -> Unit,
    onQuickFilterStyleChange: (QuickFilterStyle) -> Unit
) {
    SectionCard(highlighted = highlighted) {
        if (showHeader) {
            SectionHeaderRow(
                title = "Цвет экрана",
                expanded = expanded,
                highlighted = false,
                onClick = onToggleExpanded
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = if (settings.quickFilterStyle == QuickFilterStyle.Dark) {
                "Сейчас выбран темный режим."
            } else {
                "Сейчас выбран светлый режим."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF444444)
        )
        if (!expanded) return@SectionCard
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.quickFilterStyle == QuickFilterStyle.Light,
                onClick = { onQuickFilterStyleChange(QuickFilterStyle.Light) },
                label = { Text("Светлый") }
            )
            FilterChip(
                selected = settings.quickFilterStyle == QuickFilterStyle.Dark,
                onClick = { onQuickFilterStyleChange(QuickFilterStyle.Dark) },
                label = { Text("Темный") }
            )
        }
        Spacer(Modifier.height(12.dp))
        FilterPreview(style = settings.quickFilterStyle)
    }
}

@Composable
private fun FilterPreview(style: QuickFilterStyle) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(Color(0xFFEDEDE7))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ColorPreviewBlock(Color(0xFFE53935), Modifier.weight(1f))
            ColorPreviewBlock(Color(0xFF43A047), Modifier.weight(1f))
            ColorPreviewBlock(Color(0xFF1E88E5), Modifier.weight(1f))
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(style.overlayColor())
        )
    }
}

@Composable
private fun ColorPreviewBlock(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(48.dp)
            .background(color)
    )
}

@Composable
private fun PermissionsCard(
    snapshot: PermissionSnapshot,
    onOpenAccessibility: () -> Unit
) {
    SectionCard {
        Text("Готовность", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (!snapshot.accessibilityEnabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFF8A6D00))
                Text(
                    "Нужно включить Accessibility Service",
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                    color = Color(0xFF8A6D00)
                )
                Button(onClick = onOpenAccessibility) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Text("Включить")
                }
            }
        }
    }
}

@Composable
private fun ScheduleCard(
    settings: BlackWhiteSettings,
    expanded: Boolean,
    highlighted: Boolean,
    showHeader: Boolean = true,
    onToggleExpanded: () -> Unit,
    onOpenPro: () -> Unit,
    onScheduleChange: (Boolean) -> Unit,
    onScheduleForDaysChange: (Set<Int>, Int, Int) -> Unit
) {
    val weekdaySchedule = settings.daySchedules[1] ?: DaySchedule()
    val weekendSchedule = settings.daySchedules[6] ?: DaySchedule()
    SectionCard(highlighted = highlighted) {
        if (showHeader) {
            ScheduleHeaderRow(
                settings = settings,
                expanded = expanded,
                weekdaySchedule = weekdaySchedule,
                weekendSchedule = weekendSchedule,
                onClick = onToggleExpanded
            )
        } else {
            Text(
                when {
                    !settings.isPro -> "Pro добавляет расписание: фильтр будет включаться только в выбранные часы."
                    settings.scheduleEnabled -> "Включено: будни ${weekdaySchedule.shortRange()}, выходные ${weekendSchedule.shortRange()}."
                    else -> "Если выключено, фильтр работает всегда."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (settings.isPro) Color(0xFF444444) else Color(0xFF8A6D00)
            )
            if (!settings.isPro) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = onOpenPro) {
                    Text("Открыть Pro")
                }
            }
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
            if (!settings.isPro && showHeader) {
                Button(onClick = onOpenPro) {
                    Text("Открыть Pro")
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Включить расписание", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Switch(
                    checked = settings.isPro && settings.scheduleEnabled,
                    onCheckedChange = onScheduleChange,
                    enabled = settings.isPro
                )
            }
            if (settings.isPro && settings.scheduleEnabled) {
                Spacer(Modifier.height(12.dp))
                ScheduleGroupRow(
                    label = "Будни",
                    schedule = weekdaySchedule,
                    onScheduleChange = { start, end -> onScheduleForDaysChange(WeekdayScheduleDays, start, end) }
                )
                Spacer(Modifier.height(8.dp))
                ScheduleGroupRow(
                    label = "Выходные",
                    schedule = weekendSchedule,
                    onScheduleChange = { start, end -> onScheduleForDaysChange(WeekendScheduleDays, start, end) }
                )
            }
        }
    }
}

@Composable
private fun DeveloperProCard(
    settings: BlackWhiteSettings,
    onProChange: (Boolean) -> Unit,
    onTestStatsChange: (Boolean) -> Unit,
    onFirstLaunchPreviewChange: (Boolean) -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Developer Pro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (settings.isPro) {
                        "Платная версия для теста: без лимита приложений и с расписанием."
                    } else {
                        "Бесплатная версия: можно выбрать максимум 2 приложения."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF444444)
                )
            }
            Switch(checked = settings.isPro, onCheckedChange = onProChange)
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Тестовая статистика", fontWeight = FontWeight.Medium)
                Text(
                    if (settings.testStatsEnabled) {
                        "Подставляет демо-данные для проверки прогресса, графика и рекомендаций."
                    } else {
                        "Позволяет проверить экран прогресса без реальной истории Android."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF444444)
                )
            }
            Switch(
                checked = settings.testStatsEnabled,
                onCheckedChange = onTestStatsChange
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Режим первого запуска", fontWeight = FontWeight.Medium)
                Text(
                    if (settings.firstLaunchPreview) {
                        "Системная история временно скрыта, данные ScrollLess сброшены."
                    } else {
                        "Показывает приложение как после чистой установки."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF444444)
                )
            }
            Switch(
                checked = settings.firstLaunchPreview,
                onCheckedChange = onFirstLaunchPreviewChange
            )
        }
    }
}

@Composable
private fun ScheduleHeaderRow(
    settings: BlackWhiteSettings,
    expanded: Boolean,
    weekdaySchedule: DaySchedule,
    weekendSchedule: DaySchedule,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Text("Расписание", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    !settings.isPro -> "Pro добавляет расписание: фильтр будет включаться только в выбранные часы."
                    settings.scheduleEnabled -> "Включено: будни ${weekdaySchedule.shortRange()}, выходные ${weekendSchedule.shortRange()}."
                    else -> "Если выключено, фильтр работает всегда."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (settings.isPro) Color(0xFF444444) else Color(0xFF8A6D00)
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp)
        )
    }
}

@Composable
private fun ScheduleGroupRow(
    label: String,
    schedule: DaySchedule,
    onScheduleChange: (Int, Int) -> Unit
) {
    Column {
        Text(
            "$label: ${schedule.startHour.asHour()} - ${schedule.endHour.asHour()}",
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(4.dp))
        ScheduleTimeRow(
            label = "Начало",
            hour = schedule.startHour,
            onHourChange = { next -> onScheduleChange(next, schedule.endHour) }
        )
        Spacer(Modifier.height(4.dp))
        ScheduleTimeRow(
            label = "Конец",
            hour = schedule.endHour,
            onHourChange = { next -> onScheduleChange(schedule.startHour, next) }
        )
    }
}

@Composable
private fun ScheduleTimeRow(
    label: String,
    hour: Int,
    onHourChange: (Int) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { onHourChange((hour + 23) % 24) }) {
            Text("-")
        }
        Text(hour.asHour(), modifier = Modifier.padding(horizontal = 12.dp))
        OutlinedButton(onClick = { onHourChange((hour + 1) % 24) }) {
            Text("+")
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    locked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (locked) Icons.Default.Lock else Icons.Default.Apps,
                contentDescription = null,
                tint = if (locked) Color(0xFF8A6D00) else Color(0xFF222222)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(app.label, fontWeight = FontWeight.Medium)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
            }
            Box(
                modifier = Modifier.width(56.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Switch(
                    checked = checked,
                    onCheckedChange = { if (!locked) onCheckedChange(it) },
                    enabled = !locked || checked
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    highlighted: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) ExpandedSectionColor else Color.White
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}

private val ExpandedSectionColor = Color(0xFFDADDD9)

@Composable
private fun BlackWhiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF222222),
            onPrimary = Color.White,
            secondary = Color(0xFF3A6652),
            background = Color(0xFFF7F7F2),
            surface = Color.White
        ),
        content = {
            Surface(color = Color(0xFFF7F7F2)) {
                content()
            }
        }
    )
}

private fun Context.loadLaunchableApps(): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return packageManager.queryIntentActivities(intent, 0)
        .map { info ->
            InstalledApp(
                label = info.loadLabel(packageManager).toString(),
                packageName = info.activityInfo.packageName
            )
        }
        .distinctBy { it.packageName }
        .filterNot { it.packageName == packageName }
        .sortedBy { it.label.lowercase() }
}

private fun List<InstalledApp>.sortedForSelection(selectedPackages: Set<String>): List<InstalledApp> {
    return sortedWith(
        compareByDescending<InstalledApp> { selectedPackages.contains(it.packageName) }
            .thenBy { it.label.lowercase() }
    )
}

data class PermissionSnapshot(
    val accessibilityEnabled: Boolean,
    val usageStatsGranted: Boolean
) {
    companion object {
        fun from(context: Context): PermissionSnapshot {
            return PermissionSnapshot(
                accessibilityEnabled = context.isAccessibilityServiceEnabled(),
                usageStatsGranted = context.hasUsageStatsPermission()
            )
        }
    }
}

private fun PermissionSnapshot.shouldShow(): Boolean {
    val needsAccessibility = !accessibilityEnabled
    return needsAccessibility
}

private fun Context.isAccessibilityServiceEnabled(): Boolean {
    val expected = ComponentName(this, BlackWhiteAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabled?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
}

private fun Context.hasUsageStatsPermission(): Boolean {
    val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

private fun Context.loadSystemUsageSnapshot(): SystemUsageSnapshot {
    val installEpochDay = appInstallEpochDay()
    if (!hasUsageStatsPermission()) {
        return SystemUsageSnapshot(appInstallEpochDay = installEpochDay)
    }
    val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val start = now - TimeUnit.DAYS.toMillis(7)
    val installDayStart = LocalDate.ofEpochDay(installEpochDay)
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    val beforeInstallStart = installDayStart - TimeUnit.DAYS.toMillis(BASELINE_LOOKBACK_DAYS)
    val todayStart = LocalDate.now()
        .atStartOfDay(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
    val dailyUsage = usageStatsManager.queryForegroundUsageByEventsDaily(start, now)
    val beforeInstallDailyUsage = usageStatsManager.queryDailyUsageStats(beforeInstallStart, installDayStart)
    val usage = dailyUsage.totalUsageByPackage()
    val beforeInstallUsage = beforeInstallDailyUsage.totalUsageByPackage()
    val todayUsage = usageStatsManager.queryForegroundUsageByEvents(todayStart, now)
    return SystemUsageSnapshot(
        last7Days = usage,
        last7DaysDaily = dailyUsage,
        beforeInstallUsage = beforeInstallUsage,
        beforeInstallDailyUsage = beforeInstallDailyUsage,
        today = todayUsage,
        appInstallEpochDay = installEpochDay
    )
}

private fun Map<Long, Map<String, Long>>.totalUsageByPackage(): Map<String, Long> {
    return values
        .flatMap { it.entries }
        .groupBy { it.key }
        .mapValues { entry -> entry.value.sumOf { it.value } }
        .filterValues { it > 0L }
}

private fun UsageStatsManager.queryDailyUsageStats(
    startMillis: Long,
    endMillis: Long
): Map<Long, Map<String, Long>> {
    val startDay = Instant.ofEpochMilli(startMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toEpochDay()
    val endDayExclusive = Instant.ofEpochMilli(endMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toEpochDay()
    return queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startMillis, endMillis)
        .orEmpty()
        .toDailyUsageByPackage()
        .filterKeys { it in startDay until endDayExclusive }
}

private fun List<UsageStats>.toDailyUsageByPackage(): Map<Long, Map<String, Long>> {
    return groupBy { usageStat ->
        Instant.ofEpochMilli(usageStat.firstTimeStamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toEpochDay()
    }
        .mapValues { dayEntry ->
            dayEntry.value
                .groupBy { it.packageName }
                .mapValues { packageEntry -> packageEntry.value.sumOf { it.totalTimeInForeground } }
                .filterValues { it > 0L }
        }
        .filterValues { it.isNotEmpty() }
}

private fun Context.appInstallEpochDay(): Long {
    val firstInstallTime = runCatching {
        packageManager.getPackageInfo(packageName, 0).firstInstallTime
    }.getOrDefault(System.currentTimeMillis())
    return Instant.ofEpochMilli(firstInstallTime)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .toEpochDay()
}

private fun UsageStatsManager.queryForegroundUsageByEvents(startMillis: Long, endMillis: Long): Map<String, Long> {
    val events = queryEvents(startMillis, endMillis)
    val event = UsageEvents.Event()
    val totals = mutableMapOf<String, Long>()
    var foregroundPackageName: String? = null
    var foregroundStartedAt = 0L

    fun closeForeground(atMillis: Long) {
        val packageName = foregroundPackageName ?: return
        val duration = (atMillis.coerceAtMost(endMillis) - foregroundStartedAt).coerceAtLeast(0L)
        if (duration > 0L) {
            totals[packageName] = (totals[packageName] ?: 0L) + duration
        }
        foregroundPackageName = null
        foregroundStartedAt = 0L
    }

    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val packageName = event.packageName ?: continue
        val eventTime = event.timeStamp.coerceIn(startMillis, endMillis)
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                if (foregroundPackageName != packageName) {
                    closeForeground(eventTime)
                    foregroundPackageName = packageName
                    foregroundStartedAt = eventTime
                }
            }

            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                if (foregroundPackageName == packageName) {
                    closeForeground(eventTime)
                }
            }
        }
    }

    closeForeground(endMillis)
    return totals.filterValues { it > 0L }
}

private fun UsageStatsManager.queryForegroundUsageByEventsDaily(
    startMillis: Long,
    endMillis: Long
): Map<Long, Map<String, Long>> {
    val events = queryEvents(startMillis, endMillis)
    val event = UsageEvents.Event()
    val totals = mutableMapOf<Long, MutableMap<String, Long>>()
    var foregroundPackageName: String? = null
    var foregroundStartedAt = 0L

    fun addDuration(packageName: String, fromMillis: Long, toMillis: Long) {
        var from = fromMillis.coerceAtLeast(startMillis)
        val to = toMillis.coerceAtMost(endMillis)
        while (from < to) {
            val fromDate = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).toLocalDate()
            val nextDayStart = fromDate.plusDays(1)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
            val chunkEnd = minOf(to, nextDayStart)
            val duration = (chunkEnd - from).coerceAtLeast(0L)
            if (duration > 0L) {
                val day = fromDate.toEpochDay()
                val dayTotals = totals.getOrPut(day) { mutableMapOf() }
                dayTotals[packageName] = (dayTotals[packageName] ?: 0L) + duration
            }
            from = chunkEnd
        }
    }

    fun closeForeground(atMillis: Long) {
        val packageName = foregroundPackageName ?: return
        addDuration(packageName, foregroundStartedAt, atMillis)
        foregroundPackageName = null
        foregroundStartedAt = 0L
    }

    while (events.hasNextEvent()) {
        events.getNextEvent(event)
        val packageName = event.packageName ?: continue
        val eventTime = event.timeStamp.coerceIn(startMillis, endMillis)
        when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                if (foregroundPackageName != packageName) {
                    closeForeground(eventTime)
                    foregroundPackageName = packageName
                    foregroundStartedAt = eventTime
                }
            }

            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                if (foregroundPackageName == packageName) {
                    closeForeground(eventTime)
                }
            }
        }
    }

    closeForeground(endMillis)
    return totals
        .mapValues { dayEntry -> dayEntry.value.filterValues { it > 0L } }
        .filterValues { it.isNotEmpty() }
}

private fun Int.asHour(): String {
    return "${toString().padStart(2, '0')}:00"
}

private fun DaySchedule.shortRange(): String {
    return "${startHour.asHour()}-${endHour.asHour()}"
}

private fun BlackWhiteSettings.scheduleBlockedMessage(): String {
    val todaySchedule = daySchedules[LocalDate.now().dayOfWeek.value]
        ?: DaySchedule(scheduleStartHour, scheduleEndHour)
    return "По расписанию защита сейчас выключена. Сегодня: ${todaySchedule.shortRange()}."
}

private fun BlackWhiteSettings.withTestStatsSelection(): BlackWhiteSettings {
    return copy(
        isPro = true,
        selectedPackages = setOf(
            TEST_FACEBOOK_PACKAGE,
            TEST_INSTAGRAM_PACKAGE,
            TEST_YOUTUBE_PACKAGE
        )
    )
}

private fun buildTestUsageSnapshot(): SystemUsageSnapshot {
    val today = LocalDate.now().toEpochDay()
    val installDay = today - 6L
    val beforeInstallDaily = ((BASELINE_LOOKBACK_DAYS + 6L) downTo 7L).associate { daysAgo ->
        val day = today - daysAgo
        day to mapOf(
            TEST_FACEBOOK_PACKAGE to testMinutes(day, weekday = 50, weekend = 95),
            TEST_INSTAGRAM_PACKAGE to testMinutes(day, weekday = 105, weekend = 155),
            TEST_YOUTUBE_PACKAGE to testMinutes(day, weekday = 85, weekend = 145),
            TEST_DUOCARDS_PACKAGE to testMinutes(day, weekday = 42, weekend = 55),
            TEST_TELEGRAM_PACKAGE to testMinutes(day, weekday = 38, weekend = 50),
            TEST_TWITTER_PACKAGE to testMinutes(day, weekday = 28, weekend = 65)
        )
    }
    val scrollLessDaily = (6L downTo 1L).associate { daysAgo ->
        val day = today - daysAgo
        day to mapOf(
            TEST_FACEBOOK_PACKAGE to testMinutes(day, weekday = 28, weekend = 58),
            TEST_INSTAGRAM_PACKAGE to testMinutes(day, weekday = 62, weekend = 100),
            TEST_YOUTUBE_PACKAGE to testMinutes(day, weekday = 55, weekend = 92),
            TEST_DUOCARDS_PACKAGE to testMinutes(day, weekday = 40, weekend = 52),
            TEST_TELEGRAM_PACKAGE to testMinutes(day, weekday = 34, weekend = 44),
            TEST_TWITTER_PACKAGE to testMinutes(day, weekday = 15, weekend = 32)
        )
    }
    val todayUsage = mapOf(
        TEST_FACEBOOK_PACKAGE to TimeUnit.MINUTES.toMillis(18),
        TEST_INSTAGRAM_PACKAGE to TimeUnit.MINUTES.toMillis(41),
        TEST_YOUTUBE_PACKAGE to TimeUnit.MINUTES.toMillis(36),
        TEST_DUOCARDS_PACKAGE to TimeUnit.MINUTES.toMillis(31),
        TEST_TELEGRAM_PACKAGE to TimeUnit.MINUTES.toMillis(22),
        TEST_TWITTER_PACKAGE to TimeUnit.MINUTES.toMillis(9)
    )
    return SystemUsageSnapshot(
        last7Days = scrollLessDaily.totalUsageByPackage(),
        last7DaysDaily = scrollLessDaily,
        beforeInstallUsage = beforeInstallDaily.totalUsageByPackage(),
        beforeInstallDailyUsage = beforeInstallDaily,
        today = todayUsage,
        appInstallEpochDay = installDay
    )
}

private fun testMinutes(epochDay: Long, weekday: Long, weekend: Long): Long {
    val isWeekend = LocalDate.ofEpochDay(epochDay).dayOfWeek.value >= 6
    val minutes = if (isWeekend) weekend else weekday
    return TimeUnit.MINUTES.toMillis(minutes)
}

private fun Long.shortDayLabel(): String {
    return when (LocalDate.ofEpochDay(this).dayOfWeek.value) {
        1 -> "Пн"
        2 -> "Вт"
        3 -> "Ср"
        4 -> "Чт"
        5 -> "Пт"
        6 -> "Сб"
        else -> "Вс"
    }
}

private fun Map<String, Long>.maxMergedWith(other: Map<String, Long>): Map<String, Long> {
    if (isEmpty()) return other
    if (other.isEmpty()) return this
    return (keys + other.keys).associateWith { key ->
        maxOf(this[key] ?: 0L, other[key] ?: 0L)
    }.filterValues { it > 0L }
}

private fun Map<String, Long>.withFallback(fallback: Map<String, Long>): Map<String, Long> {
    if (isEmpty()) return fallback
    if (fallback.isEmpty()) return this
    return (keys + fallback.keys).associateWith { key ->
        val storedValue = this[key] ?: 0L
        if (storedValue > 0L) storedValue else fallback[key] ?: 0L
    }.filterValues { it > 0L }
}

private fun Map<Long, Map<String, Long>>.withDailyFallback(
    fallback: Map<Long, Map<String, Long>>
): Map<Long, Map<String, Long>> {
    if (isEmpty()) return fallback
    if (fallback.isEmpty()) return this
    return (keys + fallback.keys).associateWith { day ->
        (this[day].orEmpty()).withFallback(fallback[day].orEmpty())
    }.filterValues { it.isNotEmpty() }
}

private fun BlackWhiteSettings.pauseRemainingLabel(nowMillis: Long): String {
    val remainingSeconds = ((pausedUntilMillis - nowMillis).coerceAtLeast(0L) + 999L) / 1_000L
    val minutes = remainingSeconds / 60L
    val seconds = remainingSeconds % 60L
    return "${minutes}:${seconds.toString().padStart(2, '0')}"
}

private fun Long.formatDuration(): String {
    val totalMinutes = (this / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}ч ${minutes}м"
        hours > 0L -> "${hours}ч"
        else -> "${minutes}м"
    }
}

private fun Long.toDisplayedMinuteMillis(): Long {
    return (this / 60_000L).coerceAtLeast(0L) * 60_000L
}

private fun averageUsageOnMeasuredDays(
    selectedPackages: Set<String>,
    dailyUsage: Map<Long, Map<String, Long>>,
    totalUsage: Map<String, Long>
): Long {
    if (selectedPackages.isEmpty()) return 0L
    val dailyTotals = dailyUsage.values.map { dayUsage ->
        selectedPackages.sumOf { dayUsage[it] ?: 0L }
    }
    val measuredDays = dailyTotals.filter { it > 0L }
    if (measuredDays.isNotEmpty()) {
        return measuredDays.sum() / measuredDays.size
    }
    return selectedPackages.sumOf { totalUsage[it] ?: 0L } / BASELINE_LOOKBACK_DAYS
}

private fun measuredUsageDays(
    selectedPackages: Set<String>,
    dailyUsage: Map<Long, Map<String, Long>>
): Int {
    if (selectedPackages.isEmpty()) return 0
    return dailyUsage.values.count { dayUsage ->
        selectedPackages.sumOf { dayUsage[it] ?: 0L } > 0L
    }
}

private fun buildCompletedProgressDays(
    selectedPackages: Set<String>,
    baselineDailyUsage: Map<Long, Map<String, Long>>,
    dailyUsage: Map<Long, Map<String, Long>>,
    appInstallEpochDay: Long
): List<UsageBar> {
    if (selectedPackages.isEmpty()) return emptyList()
    val todayEpochDay = LocalDate.now().toEpochDay()
    val firstScrollLessDay = (appInstallEpochDay + 1L).coerceAtMost(todayEpochDay)
    val firstChartDay = maxOf(firstScrollLessDay, todayEpochDay - 6L)
    return (firstChartDay until todayEpochDay).map { day ->
        val duration = selectedPackages.sumOf { dailyUsage[day]?.get(it) ?: 0L }
        UsageBar(
            label = day.shortDayLabel(),
            duration = duration,
            baselineDuration = baselineForWeekday(
                selectedPackages = selectedPackages,
                baselineDailyUsage = baselineDailyUsage,
                targetEpochDay = day
            ),
            isToday = false
        )
    }
}

private fun baselineForWeekday(
    selectedPackages: Set<String>,
    baselineDailyUsage: Map<Long, Map<String, Long>>,
    targetEpochDay: Long
): Long {
    val targetDayOfWeek = LocalDate.ofEpochDay(targetEpochDay).dayOfWeek
    val matchingDays = baselineDailyUsage
        .filterKeys { LocalDate.ofEpochDay(it).dayOfWeek == targetDayOfWeek }
        .values
        .map { dayUsage -> selectedPackages.sumOf { dayUsage[it] ?: 0L } }
        .filter { it > 0L }
    return if (matchingDays.isNotEmpty()) matchingDays.sum() / matchingDays.size else 0L
}

private fun averageScrollLessUsage(
    selectedPackages: Set<String>,
    dailyUsage: Map<Long, Map<String, Long>>,
    appInstallEpochDay: Long
): Long {
    if (selectedPackages.isEmpty()) return 0L
    val todayEpochDay = LocalDate.now().toEpochDay()
    val firstScrollLessDay = (appInstallEpochDay + 1L).coerceAtMost(todayEpochDay)
    val firstChartDay = maxOf(firstScrollLessDay, todayEpochDay - 6L)
    if (firstChartDay >= todayEpochDay) return 0L
    val dailyTotals = (firstChartDay until todayEpochDay).map { day ->
        selectedPackages.sumOf { dailyUsage[day]?.get(it) ?: 0L }
    }
    return dailyTotals.sum() / dailyTotals.size
}

private fun Long.progressComparedTo(previousMillis: Long): String {
    val delta = this - previousMillis
    if (delta == 0L) return "Столько же, сколько вчера."
    val percent = ((kotlin.math.abs(delta).toDouble() / previousMillis) * 100).toInt()
    return if (delta < 0L) {
        "На ${(-delta).formatDuration()} меньше, чем вчера ($percent%)."
    } else {
        "На ${delta.formatDuration()} больше, чем вчера ($percent%)."
    }
}

private fun QuickFilterStyle.overlayColor(): Color {
    return when (this) {
        QuickFilterStyle.Light -> Color(166, 166, 166, 220)
        QuickFilterStyle.Dark -> Color(24, 24, 24, 205)
    }
}
