package com.irondiscipline.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
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
    private var audioManager: AudioManager? = null

    // Audio focus & mute state
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isAudioMutedByUs: Boolean = false

    // Native Android Overlay Views
    private var overlayRootView: FrameLayout? = null
    private var blockedAppHeaderTextView: TextView? = null
    private var blockedAppSubtitleTextView: TextView? = null
    private var countdownLabelTextView: TextView? = null
    private var countdownTimerTextView: TextView? = null

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var currentLockedAppKey: String? = null

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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
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
     * Continuous 500ms monitoring & 1-second independent usage countdown loop.
     * - Checks strictly per-app lock state (YouTube, Facebook, Instagram, TikTok are completely independent).
     * - When time expires or app is re-opened while locked, pauses/mutes video and shows native overlay.
     * - Increments used seconds only for the active app.
     */
    private fun startContinuous500msLoop() {
        if (continuousMonitorJob?.isActive == true) return
        continuousMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(500L)
                val nowMs = System.currentTimeMillis()
                realtimeQuotaManager.checkExpiryAndMidnightRollover(nowMs)

                // If user is inside IronDiscipline MainActivity (Dashboard), hide overlay
                if (IronDisciplineApp.isMainActivityVisible) {
                    if (overlayRootView != null) {
                        hideNativeBlockOverlay()
                    }
                    continue
                }

                // Resolve active foreground application package
                val detectedPkg = detectForegroundApplicationPackage()
                if (!detectedPkg.isNullOrBlank() && !isTransientSystemOrKeyboardPackage(detectedPkg)) {
                    if (detectedPkg != packageName) {
                        currentForegroundPackage = detectedPkg
                    }
                }

                val fgPkg = currentForegroundPackage

                // If user navigated to Home Launcher, hide overlay and restore audio
                if (!fgPkg.isNullOrBlank() && isHomeLauncherPackage(fgPkg)) {
                    if (overlayRootView != null) {
                        hideNativeBlockOverlay()
                    }
                    continue
                }

                val coreTarget = if (fgPkg != null) CoreTargetApp.fromPackageName(fgPkg) else null

                // 1. Core Target App (YouTube, Facebook, Instagram, TikTok)
                if (coreTarget != null) {
                    val isThisAppLocked = realtimeQuotaManager.isAppLocked(coreTarget.appKey, nowMs)

                    if (isThisAppLocked) {
                        showOrUpdateNativeBlockOverlay(coreTarget, nowMs)
                        stopVideoAndSilenceAudio()
                        continue
                    } else {
                        // This core app is NOT locked. If an overlay was showing from another app, dismiss it!
                        if (overlayRootView != null && currentLockedAppKey != null && currentLockedAppKey != coreTarget.appKey) {
                            hideNativeBlockOverlay()
                        }

                        // Increment active foreground seconds for this specific app only
                        if (nowMs - lastSecondIncrementEpochMs >= 980L) {
                            lastSecondIncrementEpochMs = nowMs
                            val newUsed = realtimeQuotaManager.incrementUsedSecond(coreTarget.appKey, nowMs)
                            val limit = realtimeQuotaManager.getAppLimitSeconds(coreTarget.appKey)

                            serviceScope.launch(Dispatchers.IO) {
                                repository.syncRealtimeTickToRoom(fgPkg!!, newUsed)
                            }

                            if (newUsed >= limit || realtimeQuotaManager.isAppLocked(coreTarget.appKey, nowMs)) {
                                serviceScope.launch(Dispatchers.IO) {
                                    repository.recordBlockedAttempt(fgPkg!!)
                                }
                                showOrUpdateNativeBlockOverlay(coreTarget, nowMs)
                                stopVideoAndSilenceAudio()
                            }
                        }
                    }
                } else if (!fgPkg.isNullOrBlank() && fgPkg != packageName && !isHomeLauncherPackage(fgPkg)) {
                    // Non-core app: check custom rules
                    val customEval = withContext(Dispatchers.IO) {
                        repository.evaluatePackageAccess(fgPkg)
                    }
                    if (customEval.shouldBlock) {
                        showOrUpdateNonCoreBlockOverlay(customEval.displayTitle, nowMs)
                        stopVideoAndSilenceAudio()
                    } else if (overlayRootView != null) {
                        // User switched to an unblocked app (e.g., WhatsApp, Maps, Notes)
                        hideNativeBlockOverlay()
                    }
                }

                // Update live countdown if overlay is visible
                if (overlayRootView != null) {
                    val lockedKey = currentLockedAppKey
                    if (lockedKey != null) {
                        if (!realtimeQuotaManager.isAppLocked(lockedKey, nowMs)) {
                            hideNativeBlockOverlay()
                        } else {
                            updateOverlayLiveCountdownText(lockedKey, nowMs)
                        }
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return
        val pkgName = safeEvent.packageName?.toString() ?: return

        if (isTransientSystemOrKeyboardPackage(pkgName)) {
            return
        }

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

                if (isHomeLauncherPackage(pkgName)) {
                    hideNativeBlockOverlay()
                    return
                }
            } else if (!isHomeLauncherPackage(pkgName)) {
                currentForegroundPackage = pkgName
            }

            val nowMs = System.currentTimeMillis()

            // Instant Re-Open Interception (< 200ms) for independent core apps
            val coreTarget = CoreTargetApp.fromPackageName(pkgName)
            if (coreTarget != null) {
                if (realtimeQuotaManager.isAppLocked(coreTarget.appKey, nowMs)) {
                    showOrUpdateNativeBlockOverlay(coreTarget, nowMs)
                    stopVideoAndSilenceAudio()
                    return
                }
            }
        }

        serviceScope.launch(Dispatchers.Default) {
            // Strict Mode Settings Protection
            if (SETTINGS_AND_INSTALLER_PACKAGES.contains(pkgName)) {
                if (repository.isStrictModeCurrentlyEnforced()) {
                    val tamperTarget = inspectSettingsForSelfSabotage(safeEvent)
                    if (tamperTarget != null) {
                        withContext(Dispatchers.Main) {
                            showOrUpdateNonCoreBlockOverlay(tamperTarget, System.currentTimeMillis())
                            stopVideoAndSilenceAudio()
                        }
                        return@launch
                    }
                }
            }

            // Browser Social Media URL Interception
            if (SUPPORTED_BROWSER_PACKAGES.contains(pkgName)) {
                val detectedUrl = extractBrowserUrlFromWindow()
                if (!detectedUrl.isNullOrBlank()) {
                    val urlBlockResult = repository.evaluateBrowserUrl(detectedUrl)
                    if (urlBlockResult.shouldBlock) {
                        repository.recordBlockedAttempt(urlBlockResult.targetKey)
                        withContext(Dispatchers.Main) {
                            showOrUpdateNonCoreBlockOverlay(urlBlockResult.displayTitle, System.currentTimeMillis())
                            stopVideoAndSilenceAudio()
                        }
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * TRIPLE-LAYER VIDEO & AUDIO STOPPER (Zero Background Sound Leak)
     * 1. Requests Exclusive Audio Focus (Forces video players to pause).
     * 2. Dispatches Hardware Media Pause & Stop Key Events.
     * 3. Mutes STREAM_MUSIC fail-safe while overlay is on screen.
     */
    private fun stopVideoAndSilenceAudio() {
        val am = audioManager ?: return
        try {
            // 1. Exclusive Audio Focus
            if (audioFocusRequest == null) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(audioAttributes)
                    .setOnAudioFocusChangeListener { /* Handled */ }
                    .build()
            }
            audioFocusRequest?.let { am.requestAudioFocus(it) }
        } catch (_: Exception) {}

        try {
            // 2. Hardware Media Pause & Stop Key Events
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))
        } catch (_: Exception) {}

        try {
            // 3. Mute STREAM_MUSIC while overlay is active
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            isAudioMutedByUs = true
        } catch (_: Exception) {}
    }

    /**
     * Restores audio playback capabilities cleanly when leaving the blocked app.
     */
    private fun restoreAudioCleanly() {
        val am = audioManager ?: return
        try {
            if (isAudioMutedByUs) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                isAudioMutedByUs = false
            }
        } catch (_: Exception) {}

        try {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } catch (_: Exception) {}
    }

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
        } catch (_: Exception) {}

        return try {
            val activePkg = rootInActiveWindow?.packageName?.toString()
            if (!activePkg.isNullOrBlank() &&
                activePkg != packageName &&
                !isTransientSystemOrKeyboardPackage(activePkg)
            ) {
                activePkg
            } else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Displays native full-screen Block Overlay specific to the locked app.
     */
    private fun showOrUpdateNativeBlockOverlay(
        coreTarget: CoreTargetApp,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val appName = coreTarget.displayName
        currentLockedAppKey = coreTarget.appKey

        if (nowMs - lastInterceptVibrationMs > 1500L) {
            lastInterceptVibrationMs = nowMs
            vibrateWarningPulse()
        }

        if (overlayRootView != null) {
            blockedAppHeaderTextView?.text = "$appName Block Ho Gaya Hai"
            countdownLabelTextView?.text = "UNLOCK HONE MEIN BAQI WAQT"
            updateOverlayLiveCountdownText(coreTarget.appKey, nowMs)
            return
        }

        val wm = windowManager ?: return
        val view = buildNativeBlockOverlayView(appName, coreTarget.appKey, nowMs)

        val primaryLayoutParams = createOverlayLayoutParams(
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        )

        try {
            wm.addView(view, primaryLayoutParams)
            overlayRootView = view
        } catch (_: Exception) {
            if (Settings.canDrawOverlays(this)) {
                try {
                    val fallbackParams = createOverlayLayoutParams(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    )
                    wm.addView(view, fallbackParams)
                    overlayRootView = view
                } catch (_: Exception) {}
            }
        }
    }

    private fun showOrUpdateNonCoreBlockOverlay(title: String, nowMs: Long) {
        currentLockedAppKey = null
        if (overlayRootView != null) {
            blockedAppHeaderTextView?.text = "🚫 $title Block Ho Gaya Hai!"
            blockedAppSubtitleTextView?.text = "Yeh focus session ke dauran block hai."
            countdownLabelTextView?.text = "Unlock hone mein baqi waqt:"
            countdownTimerTextView?.text = "--:--:--"
            return
        }

        val wm = windowManager ?: return
        val view = buildNativeBlockOverlayView(title, null, nowMs)
        val params = createOverlayLayoutParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        try {
            wm.addView(view, params)
            overlayRootView = view
        } catch (_: Exception) {
            if (Settings.canDrawOverlays(this)) {
                try {
                    wm.addView(view, createOverlayLayoutParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
                    overlayRootView = view
                } catch (_: Exception) {}
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
     * Builds the required App-Specific Full-Screen Native Vault Overlay (Obsidian Cyber-Discipline theme).
     */
    private fun buildNativeBlockOverlayView(
        appName: String,
        appKey: String?,
        nowMs: Long
    ): FrameLayout {
        val rootFrame = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
                if (event?.keyCode == KeyEvent.KEYCODE_BACK) {
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#05070B"), Color.parseColor("#12080E"))
            )
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(32), dp(20), dp(32))
        }

        val cardContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#161B26"))
                setStroke(dp(2), Color.parseColor("#FF2E54"))
            }
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // 1. Top Red Shield / Lock Badge (Circular Glowing Badge)
        val badgeFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#2A121E"))
                setStroke(dp(1), Color.parseColor("#FF2E54"))
            }
        }
        val badgeTv = TextView(this).apply {
            text = "🔒"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
        }
        val badgeFrameParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply {
            bottomMargin = dp(14)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        badgeFrame.addView(
            badgeTv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
        )
        cardContainer.addView(badgeFrame, badgeFrameParams)

        // 2. Focus Lock Engaged Tracking Badge
        val trackingBadgeTv = TextView(this).apply {
            text = "FOCUS LOCK ENGAGED"
            setTextColor(Color.parseColor("#FF2E54"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
        }
        cardContainer.addView(trackingBadgeTv)

        // 3. Main Title: "[AppName] Block Ho Gaya Hai"
        val headerTv = TextView(this).apply {
            text = "$appName Block Ho Gaya Hai"
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        }
        blockedAppHeaderTextView = headerTv
        cardContainer.addView(headerTv)

        // 4. Motivational Quote Capsule Box
        val quoteBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#0D1118"))
                setStroke(dp(1), Color.parseColor("#232B3B"))
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val quoteBoxParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(20)
        }
        val quoteTv = TextView(this).apply {
            text = "“Apne maqsad ko yaad rakho. Yeh waqt dobara laut kar nahi aayega.”"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
            gravity = Gravity.CENTER
        }
        blockedAppSubtitleTextView = quoteTv
        quoteBox.addView(quoteTv)
        cardContainer.addView(quoteBox, quoteBoxParams)

        // 5. Digital Vault Countdown Box
        val countdownBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#0B0E14"))
                setStroke(dp(1), Color.parseColor("#263042"))
            }
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val countdownBoxParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(24)
        }

        val labelTv = TextView(this).apply {
            text = "UNLOCK HONE MEIN BAQI WAQT"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        countdownLabelTextView = labelTv
        countdownBox.addView(labelTv)

        val lockedUntilMs = if (appKey != null) realtimeQuotaManager.getLockedUntilEpochMs(appKey) else 0L
        val initialRemainingMs = if (lockedUntilMs > nowMs) lockedUntilMs - nowMs else RealtimeQuotaManager.TEST_MODE_LOCK_DURATION_MS

        val timerTv = TextView(this).apply {
            text = RealtimeQuotaManager.formatMillisToHhMmSs(initialRemainingMs)
            setTextColor(Color.parseColor("#FF4D6D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        countdownTimerTextView = timerTv
        countdownBox.addView(timerTv)

        val footerNoteTv = TextView(this).apply {
            text = "Meharbani farma kar unlock time pura hone ka wait karein."
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        countdownBox.addView(footerNoteTv)

        cardContainer.addView(countdownBox, countdownBoxParams)

        // 6. Premium Action Button: "WAPAS APNE KAAM PAR JAYEIN"
        val exitHomeButton = Button(this).apply {
            text = "WAPAS APNE KAAM PAR JAYEIN"
            isAllCaps = false
            setTextColor(Color.parseColor("#090D16"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.05f
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#FFFFFF"))
            }
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setOnClickListener {
                currentForegroundPackage = null
                performGlobalAction(GLOBAL_ACTION_HOME)
                hideNativeBlockOverlay()
            }
        }
        cardContainer.addView(exitHomeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        rootFrame.addView(cardContainer, cardParams)
        return rootFrame
    }

    private fun updateOverlayLiveCountdownText(appKey: String, nowMs: Long = System.currentTimeMillis()) {
        val tv = countdownTimerTextView ?: return
        val lockedUntilMs = realtimeQuotaManager.getLockedUntilEpochMs(appKey)
        val remainingMs = (lockedUntilMs - nowMs).coerceAtLeast(0L)
        tv.text = RealtimeQuotaManager.formatMillisToHhMmSs(remainingMs)
    }

    fun hideNativeBlockOverlay() {
        val view = overlayRootView ?: return
        overlayRootView = null
        currentLockedAppKey = null
        blockedAppHeaderTextView = null
        blockedAppSubtitleTextView = null
        countdownLabelTextView = null
        countdownTimerTextView = null

        restoreAudioCleanly()

        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: Exception) {}
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

        val rootNode = try { rootInActiveWindow } catch (_: Exception) { null } ?: return null
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

    private fun collectVisibleTexts(node: AccessibilityNodeInfo?, outList: MutableList<String>, maxNodes: Int) {
        if (node == null || outList.size >= maxNodes) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { outList.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { outList.add(it) }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            if (outList.size >= maxNodes) break
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                collectVisibleTexts(child, outList, maxNodes)
            }
        }
    }

    private fun extractBrowserUrlFromWindow(): String? {
        val rootNode = try { rootInActiveWindow } catch (_: Exception) { null } ?: return null
        for (viewId in BROWSER_URL_BAR_VIEW_IDS) {
            val matchingNodes = try { rootNode.findAccessibilityNodeInfosByViewId(viewId) } catch (_: Exception) { null }
            if (!matchingNodes.isNullOrEmpty()) {
                for (node in matchingNodes) {
                    val text = node.text?.toString()?.trim()
                    if (!text.isNullOrBlank()) return text
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
            val child = try { node.getChild(i) } catch (_: Exception) { null }
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
        } catch (_: Exception) {}
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
