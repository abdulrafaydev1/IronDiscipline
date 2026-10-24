package com.irondiscipline.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.irondiscipline.app.IronDisciplineApp
import com.irondiscipline.app.data.local.IronDisciplineDatabase
import com.irondiscipline.app.data.repository.DisciplineRepository
import com.irondiscipline.app.util.CoreTargetApp
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

    // Native Android Overlay Views (Crash-proof, zero ComposeView/ViewTreeLifecycleOwner overhead)
    private var overlayRootView: FrameLayout? = null
    private var countdownTimerTextView: TextView? = null
    private var blockedAppSubtitleTextView: TextView? = null

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var lastSecondIncrementEpochMs: Long = 0L

    @Volatile
    private var lastInterceptVibrationMs: Long = 0L

    private var continuousMonitorJob: Job? = null

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
        instance = this

        DisciplineMonitorService.startServiceIfNeeded(applicationContext)
        startContinuous500msLoop()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DisciplineMonitorService.startServiceIfNeeded(applicationContext)
        startContinuous500msLoop()
    }

    /**
     * Continuous 500ms monitoring & 1-second usage countdown loop.
     * - Detects foreground app accurately (ignoring keyboard & SystemUI false positives).
     * - Immediately displays the Native Full-Screen Block Overlay if is_locked_until_epoch_ms > now
     *   (under 200ms re-open interception).
     * - Increments used_seconds_today every 1,000ms while a target app is open on screen.
     * - Updates the live HH:MM:SS unlock countdown timer on the Block Overlay every tick.
     */
    private fun startContinuous500msLoop() {
        if (continuousMonitorJob?.isActive == true) return
        continuousMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(500L)
                val nowMs = System.currentTimeMillis()
                realtimeQuotaManager.checkExpiryAndMidnightRollover(nowMs)

                // If user is inside IronDiscipline MainActivity (e.g., resetting timer), hide overlay
                if (IronDisciplineApp.isMainActivityVisible) {
                    if (overlayRootView != null) {
                        hideNativeBlockOverlay()
                    }
                    continue
                }

                // Resolve active foreground application package (ignoring keyboard, SystemUI, and our own overlay)
                val detectedPkg = detectForegroundApplicationPackage()
                if (!detectedPkg.isNullOrBlank() && !isTransientSystemOrKeyboardPackage(detectedPkg)) {
                    if (detectedPkg != packageName) {
                        currentForegroundPackage = detectedPkg
                    }
                }

                val fgPkg = currentForegroundPackage

                // If user navigated to Home Launcher or a non-target app while overlay is showing
                if (!fgPkg.isNullOrBlank() && isHomeLauncherPackage(fgPkg)) {
                    if (overlayRootView != null) {
                        hideNativeBlockOverlay()
                    }
                    continue
                }

                // Update live HH:MM:SS countdown if overlay is currently visible
                if (overlayRootView != null) {
                    if (!realtimeQuotaManager.isCurrentlyLocked(nowMs) &&
                        (fgPkg == null || !CoreTargetApp.isCoreTargetPackage(fgPkg))
                    ) {
                        hideNativeBlockOverlay()
                        continue
                    }
                    updateOverlayLiveCountdownText(nowMs)
                }

                if (fgPkg.isNullOrBlank() || fgPkg == packageName || isHomeLauncherPackage(fgPkg)) {
                    continue
                }

                // 1. Check if foreground package is one of the 4 core target apps (YouTube, Facebook, Instagram, TikTok)
                if (CoreTargetApp.isCoreTargetPackage(fgPkg)) {
                    // Check if already in persistent LOCKED STATE (System.currentTimeMillis() < is_locked_until_epoch_ms)
                    if (realtimeQuotaManager.isCurrentlyLocked(nowMs)) {
                        showOrUpdateNativeBlockOverlay(fgPkg, nowMs)
                        continue
                    }

                    // Otherwise, increment used_seconds_today every 1,000ms (1 second)
                    if (nowMs - lastSecondIncrementEpochMs >= 980L) {
                        lastSecondIncrementEpochMs = nowMs
                        val newUsedSeconds = realtimeQuotaManager.incrementUsedSecond(fgPkg, nowMs)
                        val limitSeconds = realtimeQuotaManager.getActiveLimitSeconds()

                        // Sync to Room DB asynchronously
                        serviceScope.launch(Dispatchers.IO) {
                            repository.syncRealtimeTickToRoom(fgPkg, newUsedSeconds)
                        }

                        // Immediately when used_seconds_today >= limitSeconds, lock and show overlay ON TOP of the app
                        // (Do NOT call GLOBAL_ACTION_HOME automatically)
                        if (newUsedSeconds >= limitSeconds || realtimeQuotaManager.isCurrentlyLocked(nowMs)) {
                            serviceScope.launch(Dispatchers.IO) {
                                repository.recordBlockedAttempt(fgPkg)
                            }
                            showOrUpdateNativeBlockOverlay(fgPkg, nowMs)
                        }
                    }
                } else {
                    // Non-core app: check if it has a custom rule in Room DB
                    val customEval = withContext(Dispatchers.IO) {
                        repository.evaluatePackageAccess(fgPkg)
                    }
                    if (customEval.shouldBlock) {
                        showOrUpdateNativeBlockOverlay(fgPkg, nowMs)
                    } else if (overlayRootView != null) {
                        // User switched to a non-blocked app (e.g. Calculator, Notes, WhatsApp)
                        hideNativeBlockOverlay()
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val pkgName = safeEvent.packageName?.toString() ?: return

        // Ignore transient keyboard / SystemUI events so they never clear currentForegroundPackage
        if (isTransientSystemOrKeyboardPackage(pkgName)) {
            return
        }

        // If event is from our own package, check if MainActivity was opened vs just our overlay
        if (pkgName == packageName) {
            val className = safeEvent.className?.toString().orEmpty()
            if (className.contains("MainActivity") || IronDisciplineApp.isMainActivityVisible) {
                currentForegroundPackage = packageName
                hideNativeBlockOverlay()
            }
            return
        }

        if (safeEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            safeEvent.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            if (safeEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                currentForegroundPackage = pkgName
                usageTrackerHelper.onForegroundPackageChanged(pkgName)

                // If user navigated to Home Launcher, hide overlay
                if (isHomeLauncherPackage(pkgName)) {
                    hideNativeBlockOverlay()
                    return
                }
            } else if (!isHomeLauncherPackage(pkgName)) {
                currentForegroundPackage = pkgName
            }

            val nowMs = System.currentTimeMillis()

            // INSTANT RE-OPEN INTERCEPTION (< 200ms):
            // If user opens or interacts with YouTube, Facebook, Instagram, or TikTok while locked,
            // show the Full-Screen Native Block Overlay immediately on top of the app!
            if (CoreTargetApp.isCoreTargetPackage(pkgName)) {
                if (realtimeQuotaManager.isCurrentlyLocked(nowMs)) {
                    showOrUpdateNativeBlockOverlay(pkgName, nowMs)
                    return
                }
            }
        }

        serviceScope.launch(Dispatchers.Default) {
            // 1. Strict Mode Settings Protection
            if (SETTINGS_AND_INSTALLER_PACKAGES.contains(pkgName)) {
                if (repository.isStrictModeCurrentlyEnforced()) {
                    val tamperTarget = inspectSettingsForSelfSabotage(safeEvent)
                    if (tamperTarget != null) {
                        withContext(Dispatchers.Main) {
                            showOrUpdateNativeBlockOverlay(pkgName, System.currentTimeMillis())
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
                            showOrUpdateNativeBlockOverlay(urlBlockResult.displayTitle, System.currentTimeMillis())
                        }
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Inspects interactive windows to find the top-most TYPE_APPLICATION window,
     * ignoring TYPE_ACCESSIBILITY_OVERLAY (our own overlay), TYPE_INPUT_METHOD (keyboard),
     * and TYPE_SYSTEM (notification shade / volume dialog).
     */
    private fun detectForegroundApplicationPackage(): String? {
        try {
            val windowList = windows
            if (!windowList.isNullOrEmpty()) {
                for (window in windowList) {
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val rootPkg = window.root?.packageName?.toString()
                        if (!rootPkg.isNullOrBlank() &&
                            rootPkg != packageName &&
                            !isTransientSystemOrKeyboardPackage(rootPkg)
                        ) {
                            return rootPkg
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to rootInActiveWindow below
        }

        return try {
            val activePkg = rootInActiveWindow?.packageName?.toString()
            if (!activePkg.isNullOrBlank() &&
                activePkg != packageName &&
                !isTransientSystemOrKeyboardPackage(activePkg)
            ) {
                activePkg
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Displays the crash-proof Native Full-Screen Block Overlay directly over the target app
     * using TYPE_ACCESSIBILITY_OVERLAY (with fallback to TYPE_APPLICATION_OVERLAY).
     * Does NOT close the app automatically; only exits to Home when the user clicks the button.
     */
    private fun showOrUpdateNativeBlockOverlay(
        blockedPackageOrLabel: String,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (nowMs - lastInterceptVibrationMs > 1500L) {
            lastInterceptVibrationMs = nowMs
            vibrateWarningPulse()
        }

        val appTitle = CoreTargetApp.fromPackageName(blockedPackageOrLabel)?.displayName
            ?: usageTrackerHelper.resolveAppName(blockedPackageOrLabel)

        if (overlayRootView != null) {
            blockedAppSubtitleTextView?.text = "Locked App: $appTitle"
            updateOverlayLiveCountdownText(nowMs)
            return
        }

        val wm = windowManager ?: return
        val view = buildNativeBlockOverlayView(appTitle, nowMs)

        val primaryLayoutParams = createOverlayLayoutParams(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        )

        try {
            wm.addView(view, primaryLayoutParams)
            overlayRootView = view
        } catch (_: Exception) {
            // Fallback to TYPE_APPLICATION_OVERLAY if granted
            if (Settings.canDrawOverlays(this)) {
                try {
                    val fallbackParams = createOverlayLayoutParams(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    )
                    wm.addView(view, fallbackParams)
                    overlayRootView = view
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun createOverlayLayoutParams(windowType: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.CENTER
        }
    }

    /**
     * Builds the exact required Full-Screen Native Overlay (#0D1117 background, #161B22 red warning card):
     * - Header Icon & Title: "🚫 App Block Ho Gayi Hai!"
     * - Message: "Aap ki app ka usage time pura ho chuka hai. Ab yeh app lock hai."
     * - Live Unlock Countdown Box:
     *   - Label: "Unlock hone mein baqi waqt:"
     *   - Live Timer Text: HH:MM:SS
     *   - Footer Note: "Meharbani farma kar unlock time pura hone ka wait karein."
     * - Action Button: "Wapas Home Screen Par Jayein"
     */
    private fun buildNativeBlockOverlayView(
        blockedAppTitle: String,
        nowMs: Long
    ): FrameLayout {
        val rootFrame = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.keyCode == KeyEvent.KEYCODE_BACK) {
                    // Consume hardware back press so user cannot dismiss the block screen via Back button
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#0D1117"))
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }

        // Warning Card (#161B22 with Neon Red border)
        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#161B22"))
                setStroke(dp(2), Color.parseColor("#FF2A54"))
            }
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // 1. Header Icon & Title: "🚫 App Block Ho Gayi Hai!"
        val headerTextView = TextView(this).apply {
            text = "🚫 App Block Ho Gayi Hai!"
            setTextColor(Color.parseColor("#FF2A54"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 23f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        cardContainer.addView(headerTextView)

        // Target App Subtitle Pill
        val subtitleTv = TextView(this).apply {
            text = "Locked App: $blockedAppTitle"
            setTextColor(Color.parseColor("#00F5D4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        }
        blockedAppSubtitleTextView = subtitleTv
        cardContainer.addView(subtitleTv)

        // 2. Message: "Aap ki app ka usage time pura ho chuka hai. Ab yeh app lock hai."
        val messageTextView = TextView(this).apply {
            text = "Aap ki app ka usage time pura ho chuka hai. Ab yeh app lock hai."
            setTextColor(Color.parseColor("#F0F6FC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1.0f)
            setPadding(dp(8), 0, dp(8), dp(22))
        }
        cardContainer.addView(messageTextView)

        // 3. Live Unlock Countdown Box
        val countdownBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#0D1117"))
                setStroke(dp(1), Color.parseColor("#FFBE0B"))
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }
        val countdownBoxParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(24)
        }

        val countdownLabelTv = TextView(this).apply {
            text = "Unlock hone mein baqi waqt:"
            setTextColor(Color.parseColor("#8B949E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        countdownBox.addView(countdownLabelTv)

        val lockedUntilMs = realtimeQuotaManager.getLockedUntilEpochMs()
        val initialRemainingMs = if (lockedUntilMs > nowMs) {
            lockedUntilMs - nowMs
        } else {
            RealtimeQuotaManager.TEST_MODE_LOCK_DURATION_MS
        }

        val timerTv = TextView(this).apply {
            text = RealtimeQuotaManager.formatMillisToHhMmSs(initialRemainingMs)
            setTextColor(Color.parseColor("#FFBE0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
        }
        countdownTimerTextView = timerTv
        countdownBox.addView(timerTv)

        val footerNoteTv = TextView(this).apply {
            text = "Meharbani farma kar unlock time pura hone ka wait karein."
            setTextColor(Color.parseColor("#C9D1D9"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
        }
        countdownBox.addView(footerNoteTv)

        cardContainer.addView(countdownBox, countdownBoxParams)

        // 4. Action Button: "Wapas Home Screen Par Jayein"
        val exitHomeButton = Button(this).apply {
            text = "Wapas Home Screen Par Jayein"
            isAllCaps = false
            setTextColor(Color.parseColor("#0D1117"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#00F5D4"))
            }
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setOnClickListener {
                currentForegroundPackage = null
                performGlobalAction(GLOBAL_ACTION_HOME)
                hideNativeBlockOverlay()
            }
        }
        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        cardContainer.addView(exitHomeButton, buttonParams)

        rootFrame.addView(cardContainer, cardParams)
        return rootFrame
    }

    private fun updateOverlayLiveCountdownText(nowMs: Long = System.currentTimeMillis()) {
        val tv = countdownTimerTextView ?: return
        val lockedUntilMs = realtimeQuotaManager.getLockedUntilEpochMs()
        val remainingMs = (lockedUntilMs - nowMs).coerceAtLeast(0L)
        tv.text = RealtimeQuotaManager.formatMillisToHhMmSs(remainingMs)
    }

    fun hideNativeBlockOverlay() {
        val view = overlayRootView ?: return
        overlayRootView = null
        countdownTimerTextView = null
        blockedAppSubtitleTextView = null
        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: Exception) {
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
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

    private fun isHomeLauncherPackage(pkgName: String): Boolean {
        val lower = pkgName.lowercase()
        return lower.contains("launcher") ||
            lower.contains("trebuchet") ||
            lower.contains("nexuslauncher") ||
            lower.contains("miui.home") ||
            lower.contains("sec.android.app.launcher") ||
            lower.contains("oneplus.launcher") ||
            lower.contains("oppo.launcher") ||
            lower.contains("vivo.launcher") ||
            lower.contains("huawei.android.launcher")
    }

    private fun isTransientSystemOrKeyboardPackage(pkgName: String): Boolean {
        val lower = pkgName.lowercase()
        return lower == "com.android.systemui" ||
            lower == "com.google.android.inputmethod.latin" ||
            lower == "com.samsung.android.honeyboard" ||
            lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.contains("gboard") ||
            lower.contains("swiftkey") ||
            lower == "android"
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        continuousMonitorJob?.cancel()
        hideNativeBlockOverlay()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: AppBlockerAccessibilityService? = null

        fun dismissOverlayIfShowing() {
            val service = instance ?: return
            service.serviceScope.launch(Dispatchers.Main) {
                service.hideNativeBlockOverlay()
            }
        }

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
