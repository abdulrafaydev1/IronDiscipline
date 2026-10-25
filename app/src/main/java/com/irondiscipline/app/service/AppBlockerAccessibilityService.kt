package com.irondiscipline.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.irondiscipline.app.IronDisciplineApp
import com.irondiscipline.app.util.CoreTargetApp
import com.irondiscipline.app.util.RealtimeQuotaManager
import java.time.LocalDate

class AppBlockerAccessibilityService : AccessibilityService() {

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
    private var currentActivePackage: String = ""

    @Volatile
    private var currentLockedAppKey: String? = null

    @Volatile
    private var lastInterceptVibrationMs: Long = 0L

    // Single Deterministic 1-Second Handler Loop (Zero Race Conditions)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            try {
                tickOneSecond()
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        realtimeQuotaManager = RealtimeQuotaManager.getInstance(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        DisciplineMonitorService.startServiceIfNeeded(applicationContext)

        mainHandler.removeCallbacks(tickerRunnable)
        mainHandler.post(tickerRunnable)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        DisciplineMonitorService.startServiceIfNeeded(applicationContext)

        mainHandler.removeCallbacks(tickerRunnable)
        mainHandler.post(tickerRunnable)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(tickerRunnable)
        hideNativeBlockOverlay()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Sticky Foreground App Detection:
     * Only reacts to TYPE_WINDOW_STATE_CHANGED and ignores transient overlays, keyboards, and volume panels.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return

        // 1. Only process TYPE_WINDOW_STATE_CHANGED for full-screen app transitions
        if (safeEvent.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val rawPkg = safeEvent.packageName?.toString()
            ?: try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
            ?: return

        // 2. Ignore transient system/keyboard/volume packages
        if (isIgnoredTransientPackage(rawPkg)) {
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 3. Anti-Cheat: Block Calculator if wake-up alarm is ringing
        if (prefs.getBoolean("is_alarm_currently_ringing", false)) {
            if (CALCULATOR_PACKAGES.contains(rawPkg) || rawPkg.contains("calc", ignoreCase = true)) {
                showOrUpdateNonCoreBlockOverlay("Calculator (Math Mission Active)")
                return
            }
        }

        // 4. Anti-Tamper: Block Settings if ANY target app has an active 5-hour lock
        if (isAnyCoreAppLocked(prefs)) {
            if (SETTINGS_AND_INSTALLER_PACKAGES.contains(rawPkg) || rawPkg == "com.android.settings") {
                if (inspectSettingsForFiveHourTamper(safeEvent)) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    showOrUpdateNonCoreBlockOverlay(
                        "🛡️ STRICT 5-HOUR PROTECTION ACTIVE: Settings cannot be modified while an app is locked."
                    )
                    stopVideoAndSilenceAudio()
                    return
                }
            }
        }

        // 5. If user opened IronDiscipline MainActivity (Dashboard), hide overlay
        if (rawPkg == packageName) {
            val className = safeEvent.className?.toString().orEmpty()
            if (className.contains("MainActivity") || IronDisciplineApp.isMainActivityVisible) {
                currentActivePackage = packageName
                hideNativeBlockOverlay()
            }
            return
        }

        // 6. If user navigated to Home Launcher, hide overlay cleanly
        if (isHomeLauncherPackage(rawPkg)) {
            currentActivePackage = rawPkg
            hideNativeBlockOverlay()
            return
        }

        // 7. Update sticky active package
        currentActivePackage = rawPkg

        // Instant Check for Target Apps (< 100ms response)
        val activeAppKey = mapPackageToCoreAppKey(rawPkg)
        if (activeAppKey != null) {
            val remainingLockSec = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + activeAppKey, 0)
            val usedSec = prefs.getInt(PREFIX_USED_SEC + activeAppKey, 0)
            val isTestMode = prefs.getBoolean("is_test_mode", false) || prefs.getBoolean("is_one_minute_test_mode", false)
            val limitSec = if (isTestMode) 60 else 1800

            if (remainingLockSec > 0 || usedSec >= limitSec) {
                showBlockOverlay(activeAppKey)
                stopVideoAndSilenceAudio()
            }
        }
    }

    /**
     * Single Deterministic 1-Second Ticker (Runs strictly on Main Thread).
     * Guarantees monotonic time progression: time only ever increases by +1s during usage.
     */
    private fun tickOneSecond() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Date rollover check for unlocked apps
        checkMidnightDateRollover(prefs)

        // Step A — Decrement Active 5-Hour Locks First:
        val coreKeys = listOf("youtube", "facebook", "instagram", "tiktok")
        for (appKey in coreKeys) {
            val lockRem = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + appKey, 0)
            if (lockRem > 0) {
                val nextLockRem = (lockRem - 1).coerceAtLeast(0)
                if (nextLockRem == 0) {
                    prefs.edit()
                        .putInt(PREFIX_REMAINING_LOCK_SEC + appKey, 0)
                        .putLong(PREFIX_LOCKED_UNTIL + appKey, 0L)
                        .putInt(PREFIX_USED_SEC + appKey, 0) // fresh quota granted
                        .apply()
                } else {
                    prefs.edit()
                        .putInt(PREFIX_REMAINING_LOCK_SEC + appKey, nextLockRem)
                        .apply()
                }
            }
        }

        // Anti-Tamper check if Settings is open while ANY app is locked
        if (isAnyCoreAppLocked(prefs) && currentActivePackage.isNotBlank()) {
            if (SETTINGS_AND_INSTALLER_PACKAGES.contains(currentActivePackage) || currentActivePackage == "com.android.settings") {
                if (inspectSettingsForFiveHourTamper(null)) {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    showOrUpdateNonCoreBlockOverlay(
                        "🛡️ STRICT 5-HOUR PROTECTION ACTIVE: Settings cannot be modified while an app is locked."
                    )
                    stopVideoAndSilenceAudio()
                    return
                }
            }
        }

        // Calculator Anti-Cheat check while alarm is ringing
        if (prefs.getBoolean("is_alarm_currently_ringing", false) && currentActivePackage.isNotBlank()) {
            if (CALCULATOR_PACKAGES.contains(currentActivePackage) || currentActivePackage.contains("calc", ignoreCase = true)) {
                showOrUpdateNonCoreBlockOverlay("Calculator (Math Mission Active)")
                return
            }
        }

        // If inside Dashboard or Home Launcher, ensure overlay is hidden
        if (currentActivePackage == packageName || isHomeLauncherPackage(currentActivePackage) || IronDisciplineApp.isMainActivityVisible) {
            if (overlayRootView != null) {
                hideNativeBlockOverlay()
            }
            realtimeQuotaManager.publishSnapshot()
            return
        }

        // Step B — Increment Active Foreground App Usage Strictly by +1 Second:
        val activeAppKey = mapPackageToCoreAppKey(currentActivePackage)
        if (activeAppKey != null) {
            val lockRem = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + activeAppKey, 0)
            val isTestMode = prefs.getBoolean("is_test_mode", false) || prefs.getBoolean("is_one_minute_test_mode", false)
            val limitSeconds = if (isTestMode) 60 else 1800

            if (lockRem > 0) {
                // App is locked for 5 hours: show block overlay and mute media
                showBlockOverlay(activeAppKey)
                stopVideoAndSilenceAudio()
            } else {
                // App is unlocked: strictly monotonic increment by +1 second
                val currentUsed = prefs.getInt(PREFIX_USED_SEC + activeAppKey, 0)
                val newUsed = currentUsed + 1
                prefs.edit().putInt(PREFIX_USED_SEC + activeAppKey, newUsed).apply()

                if (newUsed >= limitSeconds) {
                    // Reached limit: trigger 5-hour lock immediately
                    val lockEndMs = System.currentTimeMillis() + FIVE_HOURS_MS
                    prefs.edit()
                        .putInt(PREFIX_REMAINING_LOCK_SEC + activeAppKey, FIVE_HOURS_SECONDS)
                        .putLong(PREFIX_LOCKED_UNTIL + activeAppKey, lockEndMs)
                        .apply()
                    showBlockOverlay(activeAppKey)
                    stopVideoAndSilenceAudio()
                } else if (overlayRootView != null && currentLockedAppKey == activeAppKey) {
                    hideNativeBlockOverlay()
                }
            }
        } else {
            // User switched to an unblocked application (e.g. WhatsApp, Maps)
            if (overlayRootView != null && currentLockedAppKey != null) {
                hideNativeBlockOverlay()
            }
        }

        // If overlay is currently showing, update live countdown text
        if (overlayRootView != null && currentLockedAppKey != null) {
            val lockedKey = currentLockedAppKey!!
            val lockRem = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + lockedKey, 0)
            if (lockRem <= 0) {
                hideNativeBlockOverlay()
            } else {
                updateOverlayLiveCountdownText(lockedKey)
            }
        }

        // Smooth real-time UI notification
        realtimeQuotaManager.publishSnapshot()
    }

    private fun checkMidnightDateRollover(prefs: SharedPreferences) {
        val today = LocalDate.now().toString()
        val trackedDate = prefs.getString("tracked_date_yyyy_mm_dd", today) ?: today
        if (today != trackedDate) {
            val editor = prefs.edit().putString("tracked_date_yyyy_mm_dd", today)
            val coreKeys = listOf("youtube", "facebook", "instagram", "tiktok")
            for (key in coreKeys) {
                val lockRem = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + key, 0)
                // Only reset apps that are NOT currently serving a 5-hour penalty!
                if (lockRem <= 0) {
                    editor.putInt(PREFIX_USED_SEC + key, 0)
                    editor.putLong(PREFIX_LOCKED_UNTIL + key, 0L)
                }
            }
            editor.apply()
        }
    }

    private fun isAnyCoreAppLocked(prefs: SharedPreferences): Boolean {
        val isTestMode = prefs.getBoolean("is_test_mode", false) || prefs.getBoolean("is_one_minute_test_mode", false)
        val limitSec = if (isTestMode) 60 else 1800
        val coreKeys = listOf("youtube", "facebook", "instagram", "tiktok")
        return coreKeys.any { key ->
            val lockRem = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + key, 0)
            val usedSec = prefs.getInt(PREFIX_USED_SEC + key, 0)
            lockRem > 0 || usedSec >= limitSec
        }
    }

    private fun mapPackageToCoreAppKey(packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        val lower = packageName.lowercase().trim()
        return when {
            lower == "com.google.android.youtube" -> "youtube"
            lower == "com.facebook.katana" -> "facebook"
            lower == "com.instagram.android" -> "instagram"
            lower == "com.zhiliaoapp.musically" ||
                lower == "com.ss.android.ugc.trill" ||
                lower == "com.zhiliaoapp.musically.go" -> "tiktok"
            else -> null
        }
    }

    private fun isIgnoredTransientPackage(pkg: String): Boolean {
        val lower = pkg.lowercase().trim()
        return lower == "com.android.systemui" ||
            lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.contains("honeyboard") ||
            lower.contains("gboard") ||
            lower.contains("permissioncontroller") ||
            lower.contains("volume") ||
            (overlayRootView != null && lower == packageName.lowercase())
    }

    /**
     * TRIPLE-LAYER VIDEO & AUDIO STOPPER
     * 1. Requests Exclusive Audio Focus (Forces video players to pause).
     * 2. Dispatches Hardware Media Pause & Stop Key Events.
     * 3. Mutes STREAM_MUSIC fail-safe while overlay is on screen.
     */
    private fun stopVideoAndSilenceAudio() {
        val am = audioManager ?: return
        try {
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
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))
        } catch (_: Exception) {}

        try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            isAudioMutedByUs = true
        } catch (_: Exception) {}
    }

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

    fun showBlockOverlay(appKey: String) {
        val appName = when (appKey) {
            "youtube" -> "YouTube"
            "facebook" -> "Facebook"
            "instagram" -> "Instagram"
            "tiktok" -> "TikTok"
            else -> appKey.replaceFirstChar { it.uppercase() }
        }
        showOrUpdateNativeBlockOverlay(appName, appKey)
    }

    private fun showOrUpdateNativeBlockOverlay(appName: String, appKey: String) {
        currentLockedAppKey = appKey
        val nowMs = System.currentTimeMillis()

        if (nowMs - lastInterceptVibrationMs > 1500L) {
            lastInterceptVibrationMs = nowMs
            vibrateWarningPulse()
        }

        if (overlayRootView != null) {
            blockedAppHeaderTextView?.text = "$appName 5-Hour Lock Active!"
            countdownLabelTextView?.text = "UNLOCK HONE MEIN BAQI WAQT (5 HOURS STRICT)"
            updateOverlayLiveCountdownText(appKey)
            return
        }

        val wm = windowManager ?: return
        val view = buildNativeBlockOverlayView(appName, appKey, nowMs)
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

    private fun showOrUpdateNonCoreBlockOverlay(title: String) {
        currentLockedAppKey = null
        val nowMs = System.currentTimeMillis()
        if (overlayRootView != null) {
            blockedAppHeaderTextView?.text = "🛡️ $title"
            blockedAppSubtitleTextView?.text = "Yeh feature 5-hour active lock ke dauran protected hai."
            countdownLabelTextView?.text = "DISCIPLINE PROTECTION ENGAGED"
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
     * Builds the Full-Screen Native Vault Overlay (Obsidian Cyber-Discipline theme).
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

        // 1. Top Red Shield / Lock Badge
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

        // 3. Main Title
        val headerTv = TextView(this).apply {
            text = "$appName 5-Hour Lock Active!"
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
            text = "“Yeh app 5 ghante ke liye strict lock ho chuki hai. 5 ghante se pehle yeh kisi surat unlock nahi hogi.”"
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
            text = "UNLOCK HONE MEIN BAQI WAQT (5 HOURS STRICT)"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        countdownLabelTextView = labelTv
        countdownBox.addView(labelTv)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remainingSec = if (appKey != null) prefs.getInt(PREFIX_REMAINING_LOCK_SEC + appKey, 0) else FIVE_HOURS_SECONDS
        val initialRemainingSec = if (remainingSec > 0) remainingSec else FIVE_HOURS_SECONDS

        val timerTv = TextView(this).apply {
            text = RealtimeQuotaManager.formatSecondsToHhMmSs(initialRemainingSec)
            setTextColor(Color.parseColor("#FF4D6D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        countdownTimerTextView = timerTv
        countdownBox.addView(timerTv)

        val footerNoteTv = TextView(this).apply {
            text = "Discipline is absolute. Changing phone date/time will not unlock this app."
            setTextColor(Color.parseColor("#64748B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        countdownBox.addView(footerNoteTv)

        cardContainer.addView(countdownBox, countdownBoxParams)

        // 6. Action Button: Return to Home
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
                currentActivePackage = ""
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

    private fun updateOverlayLiveCountdownText(appKey: String) {
        val tv = countdownTimerTextView ?: return
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remainingSec = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + appKey, 0)
        tv.text = RealtimeQuotaManager.formatSecondsToHhMmSs(remainingSec)
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

    private fun inspectSettingsForFiveHourTamper(safeEvent: AccessibilityEvent?): Boolean {
        val eventTexts = safeEvent?.text?.joinToString(" ") { it.toString() }?.lowercase().orEmpty()
        val eventDesc = safeEvent?.contentDescription?.toString()?.lowercase().orEmpty()
        val className = safeEvent?.className?.toString()?.lowercase().orEmpty()
        val combinedEvent = "$eventTexts $eventDesc $className"

        val tamperKeywords = listOf(
            "irondiscipline",
            "iron discipline",
            "accessibility",
            "app info",
            "force stop",
            "clear data",
            "clear storage",
            "date & time",
            "date and time",
            "time zone",
            "automatic date",
            "set time",
            "set date"
        )

        if (tamperKeywords.any { combinedEvent.contains(it) }) {
            return true
        }

        val rootNode = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        val scrapedTexts = mutableListOf<String>()
        collectVisibleTexts(rootNode, scrapedTexts, maxNodes = 100)
        val fullWindowContent = scrapedTexts.joinToString(" | ").lowercase()

        return tamperKeywords.any { fullWindowContent.contains(it) }
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

    override fun onInterrupt() {}

    companion object {
        const val PREFS_NAME = "iron_discipline_prefs"
        const val PREFIX_USED_SEC = "used_seconds_"
        const val PREFIX_LOCKED_UNTIL = "locked_until_epoch_ms_"
        const val PREFIX_REMAINING_LOCK_SEC = "remaining_lock_seconds_"
        const val FIVE_HOURS_SECONDS = 5 * 60 * 60 // 18,000s
        const val FIVE_HOURS_MS = 18_000_000L

        @Volatile
        var instance: AppBlockerAccessibilityService? = null
            private set

        fun dismissOverlayIfShowing() {
            val service = instance ?: return
            service.mainHandler.post {
                service.hideNativeBlockOverlay()
            }
        }

        val CALCULATOR_PACKAGES = setOf(
            "com.google.android.calculator",
            "com.sec.android.app.popupcalculator",
            "com.miui.calculator",
            "com.coloros.calculator",
            "com.heytap.openid",
            "com.oneplus.calculator",
            "com.asus.calculator",
            "com.huawei.calculator",
            "com.android.calculator2",
            "com.transsion.calculator",
            "com.vivo.calculator"
        )

        private val SETTINGS_AND_INSTALLER_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.miui.securitycenter",
            "com.samsung.accessibility"
        )
    }
}
