package com.irondiscipline.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import com.irondiscipline.app.data.local.IronDisciplineDatabase
import com.irondiscipline.app.data.repository.DisciplineRepository
import com.irondiscipline.app.domain.model.BlockEvaluationResult
import com.irondiscipline.app.ui.overlay.BlockOverlayView
import com.irondiscipline.app.ui.theme.IronDisciplineTheme
import com.irondiscipline.app.util.RealtimeQuotaManager
import com.irondiscipline.app.util.UsageTrackerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppBlockerAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var repository: DisciplineRepository
    private lateinit var usageTrackerHelper: UsageTrackerHelper
    private lateinit var realtimeQuotaManager: RealtimeQuotaManager
    private var windowManager: WindowManager? = null

    private var overlayView: ComposeView? = null
    private var overlayLifecycleOwner: OverlayLifecycleOwner? = null
    private var activeOverlayState by mutableStateOf<BlockEvaluationResult?>(null)

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var lastInterceptTimestampMs: Long = 0L

    private var oneSecondTickerJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val database = IronDisciplineDatabase.getInstance(applicationContext)
        usageTrackerHelper = UsageTrackerHelper.getInstance(applicationContext)
        realtimeQuotaManager = RealtimeQuotaManager.getInstance(applicationContext)
        repository = DisciplineRepository(
            dao = database.disciplineDao(),
            usageTrackerHelper = usageTrackerHelper,
            realtimeQuotaManager = realtimeQuotaManager
        )
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        DisciplineMonitorService.startServiceIfNeeded(applicationContext)
        startOneSecondForegroundTicker()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        DisciplineMonitorService.startServiceIfNeeded(applicationContext)
        startOneSecondForegroundTicker()
    }

    /**
     * Real-Time 1-Second (1,000ms) Foreground Countdown Engine.
     * Tracks currentForegroundPackage via TYPE_WINDOW_STATE_CHANGED and rootInActiveWindow?.packageName,
     * increments usedSecondsToday every 1,000ms, auto-resets at midnight (00:00), and immediately
     * triggers GLOBAL_ACTION_HOME + BlockOverlayView the exact second activeLimitSeconds is reached
     * (1800s in Normal 30-Min Mode, or 60s in 1-Min Test Mode).
     */
    private fun startOneSecondForegroundTicker() {
        if (oneSecondTickerJob?.isActive == true) return
        oneSecondTickerJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                realtimeQuotaManager.checkMidnightResetIfNeeded()

                if (overlayView != null) {
                    continue
                }

                // Resolve active foreground package using both rootInActiveWindow and event-tracked state
                val activeWindowPkg = try {
                    rootInActiveWindow?.packageName?.toString()
                } catch (_: Exception) {
                    null
                }
                if (!activeWindowPkg.isNullOrBlank() && !isIgnoredTransientPackage(activeWindowPkg)) {
                    currentForegroundPackage = activeWindowPkg
                }

                val fgPkg = currentForegroundPackage
                if (!fgPkg.isNullOrBlank() &&
                    fgPkg != packageName &&
                    !isSystemUIOrLauncher(fgPkg)
                ) {
                    val evaluation = withContext(Dispatchers.IO) {
                        repository.onOneSecondForegroundTick(fgPkg)
                    }
                    if (evaluation.shouldBlock) {
                        withContext(Dispatchers.IO) {
                            repository.recordBlockedAttempt(fgPkg)
                        }
                        triggerInterceptionAndOverlay(evaluation)
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val pkgName = safeEvent.packageName?.toString() ?: return

        if (pkgName == packageName) return

        if (safeEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (!isIgnoredTransientPackage(pkgName)) {
                currentForegroundPackage = pkgName
                usageTrackerHelper.onForegroundPackageChanged(pkgName)
            }
        } else if (safeEvent.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (!isIgnoredTransientPackage(pkgName) && !isSystemUIOrLauncher(pkgName)) {
                currentForegroundPackage = pkgName
            }
        }

        serviceScope.launch(Dispatchers.Default) {
            // 1. Strict Mode Settings & Anti-Tamper Protection
            if (SETTINGS_AND_INSTALLER_PACKAGES.contains(pkgName)) {
                if (repository.isStrictModeCurrentlyEnforced()) {
                    val tamperTarget = inspectSettingsForSelfSabotage(safeEvent)
                    if (tamperTarget != null) {
                        val blockResult = repository.buildStrictModeSettingsBlockResult(tamperTarget)
                        withContext(Dispatchers.Main) {
                            triggerInterceptionAndOverlay(blockResult)
                        }
                        return@launch
                    }
                }
            }

            // 2. Browser Social Media URL Interception
            if (SUPPORTED_BROWSER_PACKAGES.contains(pkgName)) {
                val detectedUrl = extractBrowserUrlFromWindow()
                if (!detectedUrl.isNullOrBlank()) {
                    val urlBlockResult = repository.evaluateBrowserUrl(detectedUrl)
                    if (urlBlockResult.shouldBlock) {
                        repository.recordBlockedAttempt(urlBlockResult.targetKey)
                        withContext(Dispatchers.Main) {
                            performGlobalAction(GLOBAL_ACTION_BACK)
                            triggerInterceptionAndOverlay(urlBlockResult)
                        }
                        return@launch
                    }
                }
            }

            // 3. Immediate Launch Check (if quota is already exhausted or schedule is active)
            if (!isSystemUIOrLauncher(pkgName) && !isIgnoredTransientPackage(pkgName)) {
                val appEvaluation = repository.evaluatePackageAccess(pkgName)
                if (appEvaluation.shouldBlock) {
                    repository.recordBlockedAttempt(pkgName)
                    withContext(Dispatchers.Main) {
                        triggerInterceptionAndOverlay(appEvaluation)
                    }
                }
            }
        }
    }

    private fun inspectSettingsForSelfSabotage(event: AccessibilityEvent): String? {
        val classNameStr = event.className?.toString()?.lowercase().orEmpty()
        val eventTexts = event.text.joinToString(" ") { it.toString() }.lowercase()
        val contentDesc = event.contentDescription?.toString()?.lowercase().orEmpty()
        val combinedEventText = "$classNameStr $eventTexts $contentDesc"

        if (combinedEventText.contains("irondiscipline") ||
            combinedEventText.contains("iron discipline") ||
            combinedEventText.contains("focus shield") ||
            combinedEventText.contains(packageName.lowercase())
        ) {
            return "IronDiscipline App Info / Permission Page"
        }

        if (classNameStr.contains("accessibility") ||
            classNameStr.contains("installedappdetails") ||
            classNameStr.contains("deviceadmin") ||
            classNameStr.contains("usageaccess") ||
            classNameStr.contains("drawoverlay")
        ) {
            return "System Permission / Security Settings"
        }

        val rootNode = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null

        val scrapedTexts = mutableListOf<String>()
        collectVisibleTexts(rootNode, scrapedTexts, maxNodes = 80)
        val fullWindowContent = scrapedTexts.joinToString(" | ").lowercase()

        if (fullWindowContent.contains("irondiscipline") ||
            fullWindowContent.contains("iron discipline") ||
            fullWindowContent.contains("focus shield")
        ) {
            if (fullWindowContent.contains("force stop") ||
                fullWindowContent.contains("uninstall") ||
                fullWindowContent.contains("use service") ||
                fullWindowContent.contains("accessibility") ||
                fullWindowContent.contains("display over other apps") ||
                fullWindowContent.contains("appear on top") ||
                fullWindowContent.contains("usage access") ||
                fullWindowContent.contains("permissions")
            ) {
                return "IronDiscipline Settings & Permissions"
            }
        }

        if (fullWindowContent.contains("downloaded apps") && fullWindowContent.contains("accessibility") ||
            fullWindowContent.contains("installed services") && fullWindowContent.contains("accessibility") ||
            fullWindowContent.contains("device admin apps")
        ) {
            return "Protected Accessibility / Admin Settings"
        }

        return null
    }

    private fun collectVisibleTexts(
        node: AccessibilityNodeInfo?,
        outList: MutableList<String>,
        maxNodes: Int
    ) {
        if (node == null || outList.size >= maxNodes) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { outList.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { outList.add(it) }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (outList.size >= maxNodes) break
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            if (child != null) {
                collectVisibleTexts(child, outList, maxNodes)
            }
        }
    }

    private fun extractBrowserUrlFromWindow(): String? {
        val rootNode = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return null

        for (viewId in BROWSER_URL_BAR_VIEW_IDS) {
            val matchingNodes = try {
                rootNode.findAccessibilityNodeInfosByViewId(viewId)
            } catch (_: Exception) {
                null
            }
            if (!matchingNodes.isNullOrEmpty()) {
                for (node in matchingNodes) {
                    val text = node.text?.toString()?.trim()
                    if (!text.isNullOrBlank()) {
                        return text
                    }
                }
            }
        }

        return findUrlNodeRecursively(rootNode, depth = 0)
    }

    private fun findUrlNodeRecursively(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 12) return null
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (viewId.contains("url_bar") ||
            viewId.contains("url_field") ||
            viewId.contains("location_bar") ||
            viewId.contains("omnibar") ||
            viewId.contains("mozac_browser_toolbar_url_view")
        ) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank()) return text
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Exception) {
                null
            }
            val found = findUrlNodeRecursively(child, depth + 1)
            if (!found.isNullOrBlank()) return found
        }
        return null
    }

    private fun triggerInterceptionAndOverlay(result: BlockEvaluationResult) {
        val now = System.currentTimeMillis()
        currentForegroundPackage = null
        performGlobalAction(GLOBAL_ACTION_HOME)
        usageTrackerHelper.onForegroundPackageChanged(null, now)

        if (now - lastInterceptTimestampMs > 600L) {
            lastInterceptTimestampMs = now
            vibrateWarningPulse()
        }

        activeOverlayState = result

        if (overlayView != null) {
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            return
        }

        val wm = windowManager ?: return
        val lifecycleOwner = OverlayLifecycleOwner().apply {
            performRestore(null)
            handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            handleLifecycleEvent(Lifecycle.Event.ON_START)
            handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        val composeView = ComposeView(this).apply {
            lifecycleOwner.attachToView(this)
            setContent {
                val currentResult = activeOverlayState ?: result
                IronDisciplineTheme {
                    BlockOverlayView(
                        evaluationResult = currentResult,
                        onBackToWorkClicked = {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            dismissOverlayView()
                        },
                        onEmergencyUnlockCompleted = { unlockedTargetKey ->
                            serviceScope.launch(Dispatchers.IO) {
                                repository.grantEmergencyUnlockFor5Minutes(unlockedTargetKey)
                                withContext(Dispatchers.Main) {
                                    dismissOverlayView()
                                }
                            }
                        }
                    )
                }
            }
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        try {
            wm.addView(composeView, layoutParams)
            overlayLifecycleOwner = lifecycleOwner
            overlayView = composeView
        } catch (_: Exception) {
            lifecycleOwner.onDestroy()
        }
    }

    private fun dismissOverlayView() {
        val view = overlayView ?: return
        val owner = overlayLifecycleOwner
        overlayView = null
        overlayLifecycleOwner = null
        activeOverlayState = null

        try {
            owner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            owner?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            windowManager?.removeViewImmediate(view)
        } catch (_: Exception) {
        } finally {
            owner?.onDestroy()
        }
    }

    private fun vibrateWarningPulse() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0L, 90L, 60L, 140L), -1)
            )
        } catch (_: Exception) {
        }
    }

    private fun isSystemUIOrLauncher(pkgName: String): Boolean {
        val lower = pkgName.lowercase()
        return lower == "com.android.systemui" ||
            lower.contains("launcher") ||
            lower.contains("trebuchet") ||
            lower.contains("nexuslauncher") ||
            lower.contains("pixel") && lower.contains("launcher") ||
            lower.contains("miui.home") ||
            lower.contains("sec.android.app.launcher") ||
            lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower == "com.google.android.inputmethod.latin"
    }

    private fun isIgnoredTransientPackage(pkgName: String): Boolean {
        val lower = pkgName.lowercase()
        return lower == "com.android.systemui" ||
            lower.contains("inputmethod") ||
            lower.contains("keyboard")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        oneSecondTickerJob?.cancel()
        dismissOverlayView()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private val SETTINGS_AND_INSTALLER_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.miui.securitycenter",
            "com.samsung.accessibility"
        )

        private val SUPPORTED_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.brave.browser",
            "org.mozilla.firefox",
            "org.mozilla.fenix",
            "org.mozilla.focus",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.duckduckgo.mobile.android",
            "com.vivaldi.browser"
        )

        private val BROWSER_URL_BAR_VIEW_IDS = listOf(
            "com.android.chrome:id/url_bar",
            "com.chrome.beta:id/url_bar",
            "com.brave.browser:id/url_bar",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
            "org.mozilla.fenix:id/mozac_browser_toolbar_url_view",
            "org.mozilla.focus:id/display_url",
            "com.microsoft.emmx:id/url_bar",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
            "com.opera.browser:id/url_field",
            "com.duckduckgo.mobile.android:id/omnibarTextInput",
            "com.vivaldi.browser:id/url_bar"
        )
    }
}
