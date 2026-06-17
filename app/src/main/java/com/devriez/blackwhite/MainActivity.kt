package com.devriez.blackwhite

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Workspaces
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlackWhiteApp() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext) }
    val settings by store.settings.collectAsState(initial = BlackWhiteSettings())
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var permissionSnapshot by remember { mutableStateOf(PermissionSnapshot.from(context)) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { context.loadLaunchableApps() }
        permissionSnapshot = PermissionSnapshot.from(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Black & White", fontWeight = FontWeight.SemiBold)
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

            item {
                PermissionsCard(
                    snapshot = permissionSnapshot,
                    onRefresh = { permissionSnapshot = PermissionSnapshot.from(context) },
                    onOpenAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenOverlay = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                )
            }

            item {
                ProCard(
                    settings = settings,
                    onProChange = { enabled -> scope.launch { store.setPro(enabled) } },
                    onQuickToggleChange = { enabled -> scope.launch { store.setQuickToggle(enabled) } },
                    onScheduleChange = { enabled -> scope.launch { store.setScheduleEnabled(enabled) } },
                    onPause = { minutes -> scope.launch { store.pauseFor(minutes) } }
                )
            }

            if (settings.debugStatus.isNotBlank()) {
                item {
                    DebugCard(settings.debugStatus)
                }
            }

            item {
                Text(
                    text = "Приложения (${settings.selectedPackages.size}/${if (settings.isPro) "∞" else BlackWhiteSettings.FREE_APP_LIMIT})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

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
    }
}

@Composable
private fun DebugCard(debugStatus: String) {
    SectionCard {
        Text("Debug", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(debugStatus, style = MaterialTheme.typography.bodySmall, color = Color(0xFF444444))
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
                label = { Text("Полный") },
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
        }
    }
}

@Composable
private fun PermissionsCard(
    snapshot: PermissionSnapshot,
    onRefresh: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenOverlay: () -> Unit
) {
    SectionCard {
        Text("Готовность", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        StatusLine(Icons.Default.CheckCircle, "Accessibility Service", snapshot.accessibilityEnabled)
        StatusLine(Icons.Default.Apps, "Overlay для быстрого режима", snapshot.overlayEnabled)
        StatusLine(Icons.Default.Computer, "WRITE_SECURE_SETTINGS для полного режима", snapshot.secureSettingsGranted)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenAccessibility) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Text("Accessibility")
            }
            OutlinedButton(onClick = onOpenOverlay) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Text("Overlay")
            }
            OutlinedButton(onClick = onRefresh) {
                Text("Обновить")
            }
        }
    }
}

@Composable
private fun ProCard(
    settings: BlackWhiteSettings,
    onProChange: (Boolean) -> Unit,
    onQuickToggleChange: (Boolean) -> Unit,
    onScheduleChange: (Boolean) -> Unit,
    onPause: (Long) -> Unit
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Free / Pro", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Free: 2 приложения. Pro: без лимита, расписания, пауза, профили и быстрый toggle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF444444)
                )
            }
            Switch(checked = settings.isPro, onCheckedChange = onProChange)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onQuickToggleChange(!settings.isQuickToggleEnabled) },
                label = { Text(if (settings.isQuickToggleEnabled) "Toggle включен" else "Toggle выключен") },
                leadingIcon = { Icon(Icons.Default.Bolt, contentDescription = null) }
            )
            AssistChip(
                onClick = { onPause(15) },
                label = { Text("Пауза 15 мин") },
                leadingIcon = { Icon(Icons.Default.Pause, contentDescription = null) }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = { onScheduleChange(!settings.scheduleEnabled) },
                label = { Text(if (settings.scheduleEnabled) "Расписание активно" else "Расписание выкл.") },
                leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) }
            )
            AssistChip(
                onClick = {},
                label = { Text("Профиль: ${settings.activeProfileName}") },
                leadingIcon = { Icon(Icons.Default.Workspaces, contentDescription = null) }
            )
        }
        if (settings.isPaused()) {
            Spacer(Modifier.height(8.dp))
            Text("Фильтр временно на паузе.", color = Color(0xFF6B4F00))
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
    val overlayEnabled: Boolean,
    val secureSettingsGranted: Boolean
) {
    companion object {
        fun from(context: Context): PermissionSnapshot {
            return PermissionSnapshot(
                accessibilityEnabled = context.isAccessibilityServiceEnabled(),
                overlayEnabled = Settings.canDrawOverlays(context),
                secureSettingsGranted = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    PackageManager.PERMISSION_GRANTED
            )
        }
    }
}

private fun Context.isAccessibilityServiceEnabled(): Boolean {
    val expected = ComponentName(this, BlackWhiteAccessibilityService::class.java).flattenToString()
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    return enabled?.split(':')?.any { it.equals(expected, ignoreCase = true) } == true
}

private fun BlackWhiteSettings.quickOverlayAlphaPercent(): Int {
    val range = BlackWhiteSettings.MAX_QUICK_OVERLAY_ALPHA - BlackWhiteSettings.MIN_QUICK_OVERLAY_ALPHA
    return ((quickOverlayAlpha - BlackWhiteSettings.MIN_QUICK_OVERLAY_ALPHA) * 100 / range).coerceIn(0, 100)
}
