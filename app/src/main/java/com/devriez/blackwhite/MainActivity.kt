package com.devriez.blackwhite

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val last7Days: Map<String, Long> = emptyMap()
)

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
    var appsExpanded by remember { mutableStateOf(false) }
    val appLabels = remember(apps) { apps.associate { it.packageName to it.label } }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { context.loadLaunchableApps() }
        while (true) {
            permissionSnapshot = PermissionSnapshot.from(context)
            if (permissionSnapshot.usageStatsGranted) {
                systemUsage = withContext(Dispatchers.IO) { context.loadSystemUsageSnapshot() }
                scope.launch { store.captureBaselineIfEmpty(systemUsage.last7Days) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("ScrollLess", fontWeight = FontWeight.SemiBold)
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
            item {
                ModeCard(
                    settings = settings,
                    onModeChange = { mode -> scope.launch { store.setFilterMode(mode) } },
                    onQuickOverlayAlphaChange = { alpha -> scope.launch { store.setQuickOverlayAlpha(alpha) } }
                )
            }

            if (permissionSnapshot.shouldShowFor(settings.filterMode)) {
                item {
                    PermissionsCard(
                        mode = settings.filterMode,
                        snapshot = permissionSnapshot,
                        onOpenAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )
                }
            }

            if (settings.isPro && settings.filterMode == FilterMode.Full && !permissionSnapshot.secureSettingsGranted) {
                item {
                    FullModeSetupCard()
                }
            }

            item {
                PauseCard(
                    settings = settings,
                    onPause = { minutes -> scope.launch { store.pauseFor(minutes) } },
                    onClearPause = { scope.launch { store.clearPause() } },
                    nowMillis = nowMillis
                )
            }

            item {
                ScheduleCard(
                    settings = settings,
                    onScheduleChange = { enabled -> scope.launch { store.setScheduleEnabled(enabled) } },
                    onScheduleStartChange = { hour -> scope.launch { store.setScheduleStartHour(hour) } },
                    onScheduleEndChange = { hour -> scope.launch { store.setScheduleEndHour(hour) } }
                )
            }

            item {
                UsageCard(
                    settings = settings,
                    appLabels = appLabels,
                    systemUsage = systemUsage,
                    usageStatsGranted = permissionSnapshot.usageStatsGranted,
                    onOpenUsageAccess = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                )
            }

            item {
                AppsHeader(
                    settings = settings,
                    expanded = appsExpanded,
                    onToggleExpanded = { appsExpanded = !appsExpanded }
                )
            }

            if (appsExpanded) {
                items(apps, key = { it.packageName }) { app ->
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

            item {
                DeveloperProCard(
                    settings = settings,
                    onProChange = { enabled -> scope.launch { store.setPro(enabled) } }
                )
            }
        }
    }
}

@Composable
private fun UsageCard(
    settings: BlackWhiteSettings,
    appLabels: Map<String, String>,
    systemUsage: SystemUsageSnapshot,
    usageStatsGranted: Boolean,
    onOpenUsageAccess: () -> Unit
) {
    val selectedToday = settings.selectedPackages.sumOf { settings.usageToday[it] ?: 0L }
    val selectedYesterday = settings.selectedPackages.sumOf { settings.usageYesterday[it] ?: 0L }
    val baselineUsage = settings.baselineUsage.ifEmpty { systemUsage.last7Days }
    val currentTopCandidates = settings.usageToday
        .filterKeys { it !in settings.selectedPackages }
        .filterValues { it >= 60_000L }
        .entries
        .sortedByDescending { it.value }
        .take(3)
    val historicalTopCandidates = baselineUsage
        .filterKeys { it !in settings.selectedPackages }
        .filterValues { it >= TimeUnit.MINUTES.toMillis(5) }
        .entries
        .sortedByDescending { it.value }
        .take(3)

    SectionCard {
        Text("Прогресс", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (!usageStatsGranted) {
            Text(
                "Можно импортировать историю Android за последние дни и сразу увидеть, где уходит время.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF444444)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onOpenUsageAccess) {
                Text("Подключить историю")
            }
        } else {
            Text(
                if (settings.selectedPackages.isEmpty()) {
                    "Выбери приложения, и ScrollLess начнет считать время в них."
                } else {
                    "Сегодня в выбранных приложениях: ${selectedToday.formatDuration()}."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF444444)
            )
            if (settings.selectedPackages.isNotEmpty() && selectedYesterday > 0L) {
                Spacer(Modifier.height(4.dp))
                Text(
                    selectedToday.progressComparedTo(selectedYesterday),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selectedToday <= selectedYesterday) Color(0xFF146C43) else Color(0xFF8A6D00)
                )
            }
            ProgressTable(
                settings = settings,
                appLabels = appLabels,
                baselineUsage = baselineUsage
            )
            val topCandidates = if (historicalTopCandidates.isNotEmpty()) historicalTopCandidates else currentTopCandidates
            if (topCandidates.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Кандидаты для фильтра", fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                topCandidates.forEach { entry ->
                    val duration = if (historicalTopCandidates.isNotEmpty()) entry.value / 7L else entry.value
                    Text(
                        "${appLabels[entry.key] ?: entry.key}: ${duration.formatDuration()} в день",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF444444)
                    )
                }
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
    Text(
        "До: среднее в день за 7 дней до подключения истории.",
        style = MaterialTheme.typography.bodySmall,
        color = Color(0xFF666666)
    )
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Приложение", modifier = Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
        Text("До", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
        Text("Сейчас", modifier = Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall)
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
                    modifier = Modifier.weight(1.2f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    ((baselineUsage[packageName] ?: 0L) / 7L).formatDuration(),
                    modifier = Modifier.weight(0.8f),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    (settings.usageToday[packageName] ?: 0L).formatDuration(),
                    modifier = Modifier.weight(0.8f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
}

@Composable
private fun FullModeSetupCard() {
    SectionCard {
        Text("Полный режим Pro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Чтобы включать настоящий черно-белый режим, один раз подключи телефон к компьютеру и выполни команду:",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF444444)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "adb shell pm grant com.devriez.blackwhite android.permission.WRITE_SECURE_SETTINGS",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF222222)
        )
    }
}

@Composable
private fun AppsHeader(
    settings: BlackWhiteSettings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.clickable(onClick = onToggleExpanded)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
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
                        !settings.isPro -> "Free: 2 приложения. Больше приложений доступно в Pro."
                        expanded -> "Список открыт"
                        else -> "Нажми, чтобы выбрать приложения"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (settings.isPro) Color(0xFF444444) else Color(0xFF8A6D00)
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = null
            )
        }
    }
}

@Composable
private fun ModeCard(
    settings: BlackWhiteSettings,
    onModeChange: (FilterMode) -> Unit,
    onQuickOverlayAlphaChange: (Int) -> Unit
) {
    SectionCard {
        Text("Режим фильтра", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.filterMode == FilterMode.Quick,
                onClick = { onModeChange(FilterMode.Quick) },
                label = { Text("Быстрый") },
                leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null) }
            )
            FilterChip(
                selected = settings.filterMode == FilterMode.Full,
                onClick = { onModeChange(FilterMode.Full) },
                label = { Text("Полный Pro") },
                leadingIcon = { Icon(Icons.Default.Computer, contentDescription = null) }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (settings.filterMode == FilterMode.Quick) {
                "Работает без компьютера: приложение накладывает нейтральный фильтр поверх выбранных приложений."
            } else {
                "Настоящий системный grayscale. Нужна разовая команда ADB с компьютера."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF444444)
        )
        if (settings.filterMode == FilterMode.Quick) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Сила быстрого фильтра", modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Text("${settings.quickOverlayAlphaPercent()}%")
            }
            Slider(
                value = settings.quickOverlayAlpha.toFloat(),
                onValueChange = { onQuickOverlayAlphaChange(it.toInt()) },
                valueRange = BlackWhiteSettings.MIN_QUICK_OVERLAY_ALPHA.toFloat()..
                    BlackWhiteSettings.MAX_QUICK_OVERLAY_ALPHA.toFloat()
            )
            Spacer(Modifier.height(8.dp))
            FilterPreview(alpha = settings.quickOverlayAlpha)
        }
    }
}

@Composable
private fun FilterPreview(alpha: Int) {
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
                .background(Color(166, 166, 166, alpha))
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
    mode: FilterMode,
    snapshot: PermissionSnapshot,
    onOpenAccessibility: () -> Unit
) {
    SectionCard {
        Text("Готовность", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        if (!snapshot.accessibilityEnabled) {
            StatusLine(Icons.Default.Warning, "Включить Accessibility Service", false)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenAccessibility) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Text("Accessibility")
            }
        }
        if (mode == FilterMode.Full && !snapshot.secureSettingsGranted) {
            StatusLine(Icons.Default.Computer, "Разрешение для полного режима через компьютер", false)
        }
    }
}

@Composable
private fun PauseCard(
    settings: BlackWhiteSettings,
    onPause: (Long) -> Unit,
    onClearPause: () -> Unit,
    nowMillis: Long
) {
    SectionCard {
        Text("Пауза", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        PauseControls(
            settings = settings,
            nowMillis = nowMillis,
            onPause = onPause,
            onClearPause = onClearPause
        )
    }
}

@Composable
private fun ScheduleCard(
    settings: BlackWhiteSettings,
    onScheduleChange: (Boolean) -> Unit,
    onScheduleStartChange: (Int) -> Unit,
    onScheduleEndChange: (Int) -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Расписание", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !settings.isPro -> "Доступно в Pro: фильтр будет включаться только в выбранные часы."
                        settings.scheduleEnabled -> "Фильтр работает с ${settings.scheduleStartHour.asHour()} до ${settings.scheduleEndHour.asHour()}."
                        else -> "Если выключено, фильтр работает всегда."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (settings.isPro) Color(0xFF444444) else Color(0xFF8A6D00)
                )
            }
            Switch(
                checked = settings.isPro && settings.scheduleEnabled,
                onCheckedChange = onScheduleChange,
                enabled = settings.isPro
            )
        }
        if (settings.isPro && settings.scheduleEnabled) {
            Spacer(Modifier.height(8.dp))
            ScheduleHourRow(
                label = "Начало",
                hour = settings.scheduleStartHour,
                onHourChange = onScheduleStartChange
            )
            Spacer(Modifier.height(6.dp))
            ScheduleHourRow(
                label = "Конец",
                hour = settings.scheduleEndHour,
                onHourChange = onScheduleEndChange
            )
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
private fun PauseControls(
    settings: BlackWhiteSettings,
    nowMillis: Long,
    onPause: (Long) -> Unit,
    onClearPause: () -> Unit
) {
    val isPaused = settings.isPaused(nowMillis)
    val pausesLeft = settings.pausesLeftToday()
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AssistChip(
            onClick = { if (!isPaused && pausesLeft > 0) onPause(15) },
            enabled = !isPaused && pausesLeft > 0,
            label = { Text(if (pausesLeft > 0) "Пауза 15 мин ($pausesLeft)" else "Пауза недоступна") },
            leadingIcon = { Icon(Icons.Default.Pause, contentDescription = null) }
        )
        if (isPaused) {
            Text(
                settings.pauseRemainingLabel(nowMillis),
                color = Color(0xFF6B4F00),
                fontWeight = FontWeight.Medium
            )
            OutlinedButton(onClick = onClearPause) {
                Text("Снять")
            }
        }
    }
}

@Composable
private fun ScheduleHourRow(label: String, hour: Int, onHourChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
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
            Switch(
                checked = checked,
                onCheckedChange = { if (!locked) onCheckedChange(it) },
                enabled = !locked || checked
            )
        }
    }
}

@Composable
private fun StatusLine(icon: ImageVector, label: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (ok) Color(0xFF146C43) else Color(0xFF8A6D00))
        Text(label, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text(if (ok) "OK" else "Нужно включить", color = if (ok) Color(0xFF146C43) else Color(0xFF8A6D00))
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), content = content)
    }
}

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

data class PermissionSnapshot(
    val accessibilityEnabled: Boolean,
    val secureSettingsGranted: Boolean,
    val usageStatsGranted: Boolean
) {
    companion object {
        fun from(context: Context): PermissionSnapshot {
            return PermissionSnapshot(
                accessibilityEnabled = context.isAccessibilityServiceEnabled(),
                secureSettingsGranted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED,
                usageStatsGranted = context.hasUsageStatsPermission()
            )
        }
    }
}

private fun PermissionSnapshot.shouldShowFor(mode: FilterMode): Boolean {
    val needsAccessibility = !accessibilityEnabled
    val needsSecureSettings = mode == FilterMode.Full && !secureSettingsGranted
    return needsAccessibility || needsSecureSettings
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
    val usage = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
        .orEmpty()
        .groupBy { it.packageName }
        .mapValues { entry -> entry.value.sumOf { it.totalTimeInForeground } }
        .filterValues { it > 0L }
    return SystemUsageSnapshot(last7Days = usage)
}

private fun BlackWhiteSettings.quickOverlayAlphaPercent(): Int {
    return (quickOverlayAlpha * 100 / 255).coerceIn(80, 100)
}

private fun Int.asHour(): String {
    return "${toString().padStart(2, '0')}:00"
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
