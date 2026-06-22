package com.devriez.blackwhite

import android.app.AppOpsManager
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
    val last7DaysDaily: Map<Long, Map<String, Long>> = emptyMap()
)

private val WeekdayScheduleDays = setOf(1, 2, 3, 4, 5)
private val WeekendScheduleDays = setOf(6, 7)

private enum class AppScreen(val title: String) {
    Home("ScrollLess"),
    Apps("Приложения"),
    Color("Цвет экрана"),
    Schedule("Расписание")
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val appLabels = remember(apps) { apps.associate { it.packageName to it.label } }
    val sortedApps = remember(apps, settings.selectedPackages) {
        apps.sortedForSelection(settings.selectedPackages)
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { context.loadLaunchableApps() }
        while (true) {
            permissionSnapshot = PermissionSnapshot.from(context)
            if (permissionSnapshot.usageStatsGranted) {
                systemUsage = withContext(Dispatchers.IO) { context.loadSystemUsageSnapshot() }
                scope.launch { store.captureBaselineIfEmpty(systemUsage.last7Days, systemUsage.last7DaysDaily) }
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
                .background(Color(0xFFF7F7F2))
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (currentScreen) {
                AppScreen.Home -> {
                    item {
                        HeroCard(
                            settings = settings,
                            nowMillis = nowMillis,
                            onEnable = {
                                scope.launch {
                                    store.clearPause()
                                    store.setAppEnabled(true)
                                }
                            },
                            onDisable = {
                                scope.launch {
                                    store.clearPause()
                                    store.setAppEnabled(false)
                                }
                            },
                            onPause = {
                                scope.launch {
                                    store.setAppEnabled(true)
                                    store.pauseFor(15)
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

                    if (!settings.introDismissed) {
                        item {
                            IntroCard(onDismiss = { scope.launch { store.dismissIntro() } })
                        }
                    }

                    item {
                        SettingsNavCard(
                            settings = settings,
                            onAppsClick = { currentScreen = AppScreen.Apps },
                            onScheduleClick = { currentScreen = AppScreen.Schedule },
                            onFilterClick = { currentScreen = AppScreen.Color }
                        )
                    }

                    item {
                        UsageCard(
                            settings = settings,
                            appLabels = appLabels,
                            systemUsage = systemUsage,
                            usageStatsGranted = permissionSnapshot.usageStatsGranted,
                            expanded = progressExpanded,
                            highlighted = false,
                            onToggleExpanded = { progressExpanded = !progressExpanded },
                            candidateNotice = candidateNotice,
                            onCandidateNoticeShown = { candidateNotice = "" },
                            onCandidateChange = { packageName, checked ->
                                if (settings.isPro) {
                                    scope.launch { store.togglePackage(packageName, checked, settings) }
                                } else {
                                    candidateNotice = "Добавление кандидатов доступно в Pro."
                                }
                            },
                            onOpenUsageAccess = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                        )
                    }

                    item {
                        DeveloperProCard(
                            settings = settings,
                            onProChange = { enabled -> scope.launch { store.setPro(enabled) } }
                        )
                    }
                }

                AppScreen.Apps -> {
                    item {
                        AppsHeader(settings = settings)
                    }
                    items(sortedApps, key = { it.packageName }) { app ->
                        val checked = settings.selectedPackages.contains(app.packageName)
                        val locked = !checked && !settings.canSelectMore(app.packageName)
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
                            onScheduleChange = { enabled -> scope.launch { store.setScheduleEnabled(enabled) } },
                            onScheduleForDaysChange = { days, start, end ->
                                scope.launch { store.setScheduleForDays(days, start, end) }
                            }
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
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onPause: () -> Unit
) {
    val isPaused = settings.isPaused(nowMillis)
    SectionCard {
        Text(
            when {
                isPaused -> "Пауза: ${settings.pauseRemainingLabel(nowMillis)}"
                settings.isAppEnabled -> "Защита включена"
                else -> "Выключено до завтра"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = when {
                isPaused -> Color(0xFF8A6D00)
                settings.isAppEnabled -> Color(0xFF146C43)
                else -> Color(0xFFB3261E)
            }
        )
        Text(
            "${settings.selectedPackages.size} прилож. под фильтром",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF444444)
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                modifier = Modifier.weight(1f).height(58.dp),
                selected = settings.isAppEnabled && !isPaused,
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
        Spacer(Modifier.height(10.dp))
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
                label = { HeroButtonContent(Icons.Default.Visibility, "цвет") }
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
        Spacer(Modifier.height(10.dp))
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
    expanded: Boolean,
    highlighted: Boolean,
    onToggleExpanded: () -> Unit,
    candidateNotice: String,
    onCandidateNoticeShown: () -> Unit,
    onCandidateChange: (String, Boolean) -> Unit,
    onOpenUsageAccess: () -> Unit
) {
    val selectedToday = settings.selectedPackages.sumOf { settings.usageToday[it] ?: 0L }
    val selectedYesterday = settings.selectedPackages.sumOf { settings.usageYesterday[it] ?: 0L }
    val baselineUsage = settings.baselineUsage.ifEmpty { systemUsage.last7Days }
    val baselineDailyUsage = settings.baselineDailyUsage.ifEmpty { systemUsage.last7DaysDaily }
    val selectedBaselineDaily = settings.selectedPackages.sumOf { (baselineUsage[it] ?: 0L) / 7L }
    val selectedAfterDaily = averageAfterUsage(settings.selectedPackages, settings.usageYesterday, settings.usageToday)
    val averageSaved = (selectedBaselineDaily - selectedAfterDaily).coerceAtLeast(0L)
    val weeklySaved = averageSaved * 7L
    val currentTopCandidates = settings.usageToday
        .filterKeys { it !in settings.selectedPackages }
        .filterValues { it >= 60_000L }
        .entries
        .sortedByDescending { it.value }
        .take(5)
    val historicalTopCandidates = baselineUsage
        .filterKeys { it !in settings.selectedPackages }
        .filterValues { it >= TimeUnit.MINUTES.toMillis(5) }
        .entries
        .sortedByDescending { it.value }
        .take(5)
    val topCandidates = if (historicalTopCandidates.isNotEmpty()) historicalTopCandidates else currentTopCandidates
    var pinnedCandidates by remember { mutableStateOf<List<Map.Entry<String, Long>>>(emptyList()) }
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
    } else if (usageStatsGranted && selectedBaselineDaily > 0L && selectedAfterDaily > 0L) {
        "С подключенным ScrollLess выбранные приложения используются в среднем на ${averageSaved.formatDuration()} меньше в день. Это примерно ${weeklySaved.formatDuration()} в неделю."
    } else if (usageStatsGranted && (selectedToday > 0L || selectedYesterday > 0L)) {
        "ScrollLess уже считает время в выбранных приложениях. Сравнение с прошлой привычкой появится после накопления первых данных."
    } else if (usageStatsGranted) {
        "История подключена. Когда появятся первые данные по выбранным приложениям, ScrollLess покажет, стали ли они забирать меньше времени."
    } else {
        "Подключи историю Android, чтобы увидеть прогресс."
    }

    SectionCard(highlighted = highlighted) {
        if (candidateNotice.isNotBlank()) {
            LaunchedEffect(candidateNotice) {
                delay(3_000L)
                onCandidateNoticeShown()
            }
        }
        SectionHeaderRow(
            title = "Прогресс",
            expanded = expanded,
            highlighted = false,
            onClick = onToggleExpanded
        )
        Spacer(Modifier.height(8.dp))
        Text(
            summaryText,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF444444)
        )
        if (!expanded) return@SectionCard
        Spacer(Modifier.height(10.dp))
        if (!usageStatsGranted) {
            OutlinedButton(onClick = onOpenUsageAccess) {
                Text("Подключить историю")
            }
        } else {
            UsageChart(
                settings = settings,
                baselineDailyUsage = baselineDailyUsage,
                baselineAverageDaily = selectedBaselineDaily
            )
            ProgressTable(
                settings = settings,
                appLabels = appLabels,
                baselineUsage = baselineUsage
            )
            if (displayedCandidates.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Похоже, здесь тоже уходит время", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                if (candidateNotice.isNotBlank()) {
                    Text(
                        candidateNotice,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8A6D00)
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    displayedCandidates.forEach { entry ->
                        val duration = if (historicalTopCandidates.isNotEmpty()) entry.value / 7L else entry.value
                        CandidateRow(
                            label = appLabels[entry.key] ?: entry.key,
                            packageName = entry.key,
                            duration = duration,
                            checked = settings.selectedPackages.contains(entry.key),
                            onCheckedChange = { checked -> onCandidateChange(entry.key, checked) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UsageChart(
    settings: BlackWhiteSettings,
    baselineDailyUsage: Map<Long, Map<String, Long>>,
    baselineAverageDaily: Long
) {
    if (settings.selectedPackages.isEmpty()) return
    val todayEpochDay = LocalDate.now().toEpochDay()
    val afterBars = listOf(
        UsageBar("Вчера", settings.selectedPackages.sumOf { settings.usageYesterday[it] ?: 0L }, false),
        UsageBar("Сегодня", settings.selectedPackages.sumOf { settings.usageToday[it] ?: 0L }, true)
    ).filter { it.duration > 0L || it.isToday }
    val beforeBars = (6L downTo 0L).map { daysAgo ->
        val day = todayEpochDay - daysAgo
        UsageBar(
            label = if (daysAgo == 0L) "До" else "",
            duration = settings.selectedPackages.sumOf { baselineDailyUsage[day]?.get(it) ?: 0L },
            isToday = false
        )
    }.filter { it.duration > 0L }
    val maxDuration = (beforeBars + afterBars).maxOfOrNull { it.duration }
        ?.coerceAtLeast(baselineAverageDaily)
        ?: baselineAverageDaily
    if (maxDuration <= 0L) return

    Spacer(Modifier.height(10.dp))
    Text("Динамика времени", fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Серая линия — среднее до ScrollLess. Зеленый — после, желтый — сегодняшний незавершенный день.",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF666666)
    )
    Spacer(Modifier.height(10.dp))
    UsageBarChart(
        beforeBars = beforeBars,
        afterBars = afterBars,
        baselineAverageDaily = baselineAverageDaily,
        maxDuration = maxDuration
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("До: ${baselineAverageDaily.formatDuration()}/день", style = MaterialTheme.typography.bodySmall)
        val afterAverage = averageAfterUsage(settings.selectedPackages, settings.usageYesterday, settings.usageToday)
        Text("После: ${afterAverage.formatDuration()}/день", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UsageBarChart(
    beforeBars: List<UsageBar>,
    afterBars: List<UsageBar>,
    baselineAverageDaily: Long,
    maxDuration: Long
) {
    val bars = beforeBars + afterBars
    val baselineColor = Color(0xFF777777)
    val beforeColor = Color(0xFFCACACA)
    val afterColor = Color(0xFF3A6652)
    val todayColor = Color(0xFFE4B33D)
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
        val baselineY = chartBottom - chartHeight * (baselineAverageDaily.toFloat() / maxDuration.toFloat())
        drawLine(
            color = baselineColor,
            start = androidx.compose.ui.geometry.Offset(0f, baselineY),
            end = androidx.compose.ui.geometry.Offset(size.width, baselineY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        if (bars.isEmpty()) return@Canvas
        val slot = size.width / bars.size
        val barWidth = (slot * 0.52f).coerceAtMost(22.dp.toPx())
        bars.forEachIndexed { index, bar ->
            val barHeight = chartHeight * (bar.duration.toFloat() / maxDuration.toFloat())
            val left = index * slot + (slot - barWidth) / 2f
            val top = chartBottom - barHeight
            drawRoundRect(
                color = when {
                    bar.isToday -> todayColor
                    index >= beforeBars.size -> afterColor
                    else -> beforeColor
                },
                topLeft = androidx.compose.ui.geometry.Offset(left, top),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight.coerceAtLeast(2.dp.toPx())),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx())
            )
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("7 дней до", style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
        Text("вчера / сегодня", style = MaterialTheme.typography.bodySmall, color = Color(0xFF666666))
    }
}

private data class UsageBar(
    val label: String,
    val duration: Long,
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
    baselineUsage: Map<String, Long>
) {
    if (settings.selectedPackages.isEmpty()) return
    Spacer(Modifier.height(12.dp))
    Text("Отслеживаемые приложения", fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Приложение", modifier = Modifier.weight(1.1f), style = MaterialTheme.typography.bodySmall)
        Text("До", modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
        Text("После", modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
        Text("Сегодня", modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
    }
    settings.selectedPackages
        .sortedBy { appLabels[it] ?: it }
        .forEach { packageName ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    appLabels[packageName] ?: packageName,
                    modifier = Modifier.weight(1.1f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    ((baselineUsage[packageName] ?: 0L) / 7L).formatDuration(),
                    modifier = Modifier.weight(0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    averageAfterUsage(setOf(packageName), settings.usageYesterday, settings.usageToday).formatDuration(),
                    modifier = Modifier.weight(0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    (settings.usageToday[packageName] ?: 0L).formatDuration(),
                    modifier = Modifier.weight(0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
}

@Composable
private fun AppsHeader(
    settings: BlackWhiteSettings
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
                "Сейчас выбран строгий темный режим."
            } else {
                "Сейчас выбран мягкий светлый режим."
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
                label = { Text("Мягкий") }
            )
            FilterChip(
                selected = settings.quickFilterStyle == QuickFilterStyle.Dark,
                onClick = { onQuickFilterStyleChange(QuickFilterStyle.Dark) },
                label = { Text("Строгий") }
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
        }
        if (expanded) {
            Spacer(Modifier.height(12.dp))
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
    onProChange: (Boolean) -> Unit
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
    if (!hasUsageStatsPermission()) return SystemUsageSnapshot()
    val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    val now = System.currentTimeMillis()
    val start = now - TimeUnit.DAYS.toMillis(7)
    val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now).orEmpty()
    val usage = stats
        .groupBy { it.packageName }
        .mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }
        .filterValues { it > 0L }
    val dailyUsage = stats
        .groupBy { usageStat ->
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
    return SystemUsageSnapshot(last7Days = usage, last7DaysDaily = dailyUsage)
}

private fun Int.asHour(): String {
    return "${toString().padStart(2, '0')}:00"
}

private fun DaySchedule.shortRange(): String {
    return "${startHour.asHour()}-${endHour.asHour()}"
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

private fun averageAfterUsage(
    selectedPackages: Set<String>,
    yesterdayUsage: Map<String, Long>,
    todayUsage: Map<String, Long>
): Long {
    if (selectedPackages.isEmpty()) return 0L
    val yesterdayTotal = selectedPackages.sumOf { yesterdayUsage[it] ?: 0L }
    val todayTotal = selectedPackages.sumOf { todayUsage[it] ?: 0L }
    val sampleDays = listOf(yesterdayTotal, todayTotal).count { it > 0L }.coerceAtLeast(1)
    return (yesterdayTotal + todayTotal) / sampleDays
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
        QuickFilterStyle.Dark -> Color(24, 24, 24, 235)
    }
}
