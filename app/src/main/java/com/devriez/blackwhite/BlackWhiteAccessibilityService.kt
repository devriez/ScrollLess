package com.devriez.blackwhite

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BlackWhiteAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var settingsStore: SettingsStore
    private var currentSettings = BlackWhiteSettings()
    private var currentPackageName: String? = null
    private var lastSelectedPackageName: String? = null
    private var lastSelectedAtMillis: Long = 0L
    private var overlayView: View? = null
    private var fullModeApplied = false
    private var pendingClearJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsStore = SettingsStore(applicationContext)
        scope.launch {
            settingsStore.settings.collectLatest { settings ->
                currentSettings = settings
                updateFilterForCurrentPackage()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        currentPackageName = packageName
        updateFilterForCurrentPackage()
    }

    override fun onInterrupt() {
        clearFilters()
    }

    override fun onDestroy() {
        pendingClearJob?.cancel()
        clearFilters()
        scope.cancel()
        super.onDestroy()
    }

    private fun updateFilterForCurrentPackage() {
        val foregroundPackageName = selectedForegroundPackageName(allowStickyFallback = true)
        val shouldEnable = foregroundPackageName != null &&
            currentSettings.isQuickToggleEnabled &&
            !currentSettings.isPaused()
        writeDebugStatus(
            decision = if (shouldEnable) "enable/keep" else "schedule clear",
            foregroundPackageName = foregroundPackageName
        )

        if (!shouldEnable) {
            scheduleFilterClear()
            return
        }

        lastSelectedPackageName = foregroundPackageName
        lastSelectedAtMillis = System.currentTimeMillis()
        pendingClearJob?.cancel()
        when (currentSettings.filterMode) {
            FilterMode.Quick -> {
                disableFullModeIfNeeded()
                showQuickOverlay()
            }

            FilterMode.Full -> {
                hideQuickOverlay()
                enableFullModeIfAllowed()
            }
        }
    }

    private fun scheduleFilterClear() {
        pendingClearJob?.cancel()
        pendingClearJob = scope.launch {
            delay(FILTER_CLEAR_DELAY_MS)
            val selectedForegroundPackage = selectedActiveWindowPackageName()
            val shouldStillClear = !currentSettings.isQuickToggleEnabled ||
                currentSettings.isPaused() ||
                selectedForegroundPackage == null
            writeDebugStatus(
                decision = if (shouldStillClear) "clear" else "keep after delay",
                foregroundPackageName = selectedForegroundPackage
            )

            if (shouldStillClear) {
                lastSelectedPackageName = null
                clearFilters()
            } else {
                lastSelectedPackageName = selectedForegroundPackage
            }
        }
    }

    private fun selectedForegroundPackageName(allowStickyFallback: Boolean): String? {
        selectedActiveWindowPackageName()?.let { return it }

        val eventPackage = currentPackageName
        if (eventPackage in currentSettings.selectedPackages) {
            return eventPackage
        }

        val stickyPackage = lastSelectedPackageName
        if (
            allowStickyFallback &&
            overlayView != null &&
            stickyPackage in currentSettings.selectedPackages &&
            eventPackage.isShortSystemInterruption() &&
            System.currentTimeMillis() - lastSelectedAtMillis < STICKY_FALLBACK_MS
        ) {
            return stickyPackage
        }

        if (
            allowStickyFallback &&
            overlayView != null &&
            stickyPackage in currentSettings.selectedPackages &&
            eventPackage.isLauncherPackage() &&
            visibleSelectedWindowPackageName() == stickyPackage
        ) {
            return stickyPackage
        }

        return null
    }

    private fun selectedActiveWindowPackageName(): String? {
        val activeRootPackage = rootInActiveWindow?.packageName?.toString()
        if (activeRootPackage in currentSettings.selectedPackages) {
            return activeRootPackage
        }

        return windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull { it in currentSettings.selectedPackages }
    }

    private fun activeRootPackageName(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    private fun visibleSelectedWindowPackageName(): String? {
        return windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull { it in currentSettings.selectedPackages }
    }

    private fun writeDebugStatus(decision: String, foregroundPackageName: String?) {
        val status = buildString {
            appendLine("decision=$decision")
            appendLine("event=$currentPackageName")
            appendLine("activeRoot=${activeRootPackageName()}")
            appendLine("foreground=$foregroundPackageName")
            appendLine("visibleSelected=${visibleSelectedWindowPackageName()}")
            appendLine("lastSelected=$lastSelectedPackageName")
            appendLine("selected=${currentSettings.selectedPackages.joinToString()}")
        }
        scope.launch(Dispatchers.IO) {
            settingsStore.setDebugStatus(status)
        }
    }

    private fun showQuickOverlay() {
        overlayView?.let {
            it.setBackgroundColor(quickOverlayColor())
            return
        }
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = View(this).apply {
            setBackgroundColor(quickOverlayColor())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        runCatching {
            windowManager.addView(view, params)
            overlayView = view
        }
    }

    private fun quickOverlayColor(): Int {
        return Color.argb(currentSettings.quickOverlayAlpha, 166, 166, 166)
    }

    private fun hideQuickOverlay() {
        val view = overlayView ?: return
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { windowManager.removeView(view) }
        overlayView = null
    }

    private fun enableFullModeIfAllowed() {
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        runCatching {
            Settings.Secure.putInt(contentResolver, SETTING_DALTONIZER, DALTONIZER_MONOCHROMACY)
            Settings.Secure.putInt(contentResolver, SETTING_DALTONIZER_ENABLED, 1)
            fullModeApplied = true
        }
    }

    private fun disableFullModeIfNeeded() {
        if (!fullModeApplied) return
        if (checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            fullModeApplied = false
            return
        }
        runCatching {
            Settings.Secure.putInt(contentResolver, SETTING_DALTONIZER_ENABLED, 0)
            fullModeApplied = false
        }
    }

    private fun clearFilters() {
        hideQuickOverlay()
        disableFullModeIfNeeded()
    }

    companion object {
        private const val SETTING_DALTONIZER_ENABLED = "accessibility_display_daltonizer_enabled"
        private const val SETTING_DALTONIZER = "accessibility_display_daltonizer"
        private const val DALTONIZER_MONOCHROMACY = 0
        private const val FILTER_CLEAR_DELAY_MS = 100L
        private const val STICKY_FALLBACK_MS = 5_000L
    }
}

private fun String?.isShortSystemInterruption(): Boolean {
    return this == null ||
        this == "android" ||
        this == "com.android.systemui" ||
        this.contains("inputmethod", ignoreCase = true)
}

private fun String?.isLauncherPackage(): Boolean {
    return this?.contains("launcher", ignoreCase = true) == true
}
