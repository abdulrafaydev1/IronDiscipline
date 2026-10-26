package com.irondiscipline.app.service

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.irondiscipline.app.FocusDeviceAdminReceiver
import com.irondiscipline.app.IronDisciplineApp
import com.irondiscipline.app.alarm.ActiveAlarmSessionState
import com.irondiscipline.app.alarm.AlarmReceiver
import com.irondiscipline.app.alarm.AlarmRepository
import com.irondiscipline.app.alarm.AlarmRingingActivity
import com.irondiscipline.app.alarm.AlarmRingingService
import com.irondiscipline.app.alarm.AlarmScheduler
import com.irondiscipline.app.alarm.AlarmWakeLockManager
import com.irondiscipline.app.util.CoreTargetApp
import com.irondiscipline.app.util.RealtimeQuotaManager
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.tanh

class AppBlockerAccessibilityService : AccessibilityService() {

    private lateinit var realtimeQuotaManager: RealtimeQuotaManager
    private var windowManager: WindowManager? = null
    private var audioManager: AudioManager? = null

    // Audio focus & mute state
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isAudioMutedByUs: Boolean = false

    // Native Android Overlay Views & Strict Latch State
    private var isBlockOverlayShowing: Boolean = false
    private var latchedLockedAppKey: String? = null
    private var blockOverlayView: FrameLayout? = null
    private var overlayTitleTextView: TextView? = null
    private var overlayCountdownTextView: TextView? = null
    private var blockedAppSubtitleTextView: TextView? = null
    private var countdownLabelTextView: TextView? = null

    @Volatile
    private var exitToHomeGraceUntilMs: Long = 0L

    @Volatile
    private var lastDetectedPackage: String = ""

    @Volatile
    private var currentTargetAppKey: String? = null

    @Volatile
    private var lastTargetSeenTimestamp: Long = 0L

    @Volatile
    private var lastInterceptVibrationMs: Long = 0L

    @Volatile
    private var serviceConnectedTimestampMs: Long = 0L

    @Volatile
    private var lastProtectedToastMs: Long = 0L

    // Live On-Screen Floating Timer Pill
    private var floatingPillView: TextView? = null

    // Single Deterministic 1-Second Handler Loop (Zero Race Conditions)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tickerRunnable = object : Runnable {
        override fun run() {
            try {
                guardActiveAlarmFromPowerDialog(null)
                tickOneSecond()
                checkBackupAlarmWatchdog()
                checkProtectedNavigation(null)
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        serviceConnectedTimestampMs = System.currentTimeMillis()
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
        serviceConnectedTimestampMs = System.currentTimeMillis()
        DisciplineMonitorService.startServiceIfNeeded(applicationContext)

        mainHandler.removeCallbacks(tickerRunnable)
        mainHandler.post(tickerRunnable)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(tickerRunnable)
        hideFloatingTimerPill()
        hideBlockOverlay()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Flexible Package Matcher: Catches all regional, full, and lite variants.
     */
    private fun resolveTargetAppKey(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        val p = pkg.lowercase().trim()
        return when {
            p == "com.google.android.youtube" || p.contains("youtube") -> "youtube"
            p == "com.facebook.katana" || p == "com.facebook.lite" || p == "com.facebook.wakizashi" || (p.startsWith("com.facebook.") && !p.contains("orca")) -> "facebook"
            p == "com.instagram.android" || p == "com.instagram.lite" || p.contains("instagram") -> "instagram"
            p.contains("musically") || p.contains("ugc.trill") || p.contains("tiktok") -> "tiktok"
            else -> null
        }
    }

    /**
     * Filters out transient system UI, volume panels, and virtual keyboards so focus is never lost.
     * When our own app emits an event (from overlay or floating pill), ignore it unless MainActivity is visible!
     */
    private fun isIgnoredSystemPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return true
        val p = pkg.lowercase().trim()
        if (p == packageName.lowercase()) {
            return !IronDisciplineApp.isMainActivityVisible
        }
        return p == "android" ||
            p.contains("systemui") ||
            p.contains("inputmethod") ||
            p.contains("keyboard") ||
            p.contains("honeyboard") ||
            p.contains("gboard") ||
            p.contains("swiftkey") ||
            p.contains("volume") ||
            p.contains("miui.securitycenter") ||
            p.contains("coloros.safecenter") ||
            p.contains("samsung.android.app.cocktailbarservice") ||
            p.contains("permissioncontroller")
    }

    /**
     * Instant Event Interception:
     * Captures TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED, TYPE_VIEW_SCROLLED, etc.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        guardActiveAlarmFromPowerDialog(event)

        // 1. Grace cooldown after clicking "WAPAS APNE KAAM PAR JAYEIN"
        if (System.currentTimeMillis() < exitToHomeGraceUntilMs) {
            return
        }

        val safeEvent = event ?: return
        checkProtectedNavigation(safeEvent)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 0. Authorized App Uninstall session check
        if (com.irondiscipline.app.util.SecurityPinManager.isUninstallSessionActive(this)) {
            hideFloatingTimerPill()
            hideBlockOverlay()
            return
        }

        // CRITICAL LATCH RULE: While isBlockOverlayShowing == true, NEVER auto-hide on window events!
        if (isBlockOverlayShowing && latchedLockedAppKey != null) {
            return
        }

        val eventPkg = safeEvent.packageName?.toString()
            ?: try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }

        if (!isIgnoredSystemPackage(eventPkg)) {
            val validPkg = eventPkg!!
            lastDetectedPackage = validPkg

            // Anti-Cheat: Block Calculator if wake-up alarm is ringing
            if (prefs.getBoolean("is_alarm_currently_ringing", false)) {
                if (CALCULATOR_PACKAGES.contains(validPkg) || validPkg.contains("calc", ignoreCase = true)) {
                    showOrUpdateNonCoreBlockOverlay("Calculator (Math Mission Active)")
                    return
                }
            }

            // If user opened IronDiscipline MainActivity or Home Launcher, clear current target
            if ((validPkg == packageName && IronDisciplineApp.isMainActivityVisible) || isHomeLauncherPackage(validPkg)) {
                currentTargetAppKey = null
                hideFloatingTimerPill()
                if (isBlockOverlayShowing) {
                    hideBlockOverlay()
                }
                realtimeQuotaManager.publishSnapshot()
                return
            }

            val matchedKey = resolveTargetAppKey(validPkg)
            if (matchedKey != null) {
                currentTargetAppKey = matchedKey
                lastTargetSeenTimestamp = System.currentTimeMillis()

                val lockRem = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + matchedKey, 0)
                val isTestMode = prefs.getBoolean("is_test_mode", false) || prefs.getBoolean("is_one_minute_test_mode", false)
                val limitSec = if (isTestMode) 60 else 1800
                val usedSec = prefs.getInt(PREFIX_USED_SEC + matchedKey, 0)

                if (lockRem > 0 || usedSec >= limitSec) {
                    val remSec = if (lockRem > 0) lockRem else FIVE_HOURS_SECONDS
                    hideFloatingTimerPill()
                    latchedLockedAppKey = matchedKey
                    showOrUpdateBlockOverlay(matchedKey, remSec)
                    stopVideoAndSilenceAudio()
                }
            } else if (safeEvent.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                currentTargetAppKey = null
                hideFloatingTimerPill()
                if (isBlockOverlayShowing && latchedLockedAppKey == null) {
                    hideBlockOverlay()
                }
            }
        }
    }

    /**
     * Active Window Polling (Works even when watching videos with screen untouched).
     */
    private fun detectCurrentTargetAppKey(): String? {
        if (System.currentTimeMillis() < exitToHomeGraceUntilMs) {
            return null
        }

        if (IronDisciplineApp.isMainActivityVisible) {
            currentTargetAppKey = null
            return null
        }

        // 1. Check interactive application windows on screen first
        try {
            val windowList = try { getWindows() } catch (_: Exception) { null }
            if (!windowList.isNullOrEmpty()) {
                for (window in windowList) {
                    if (window != null && window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val rootNode = try { window.root } catch (_: Exception) { null }
                        val winPkg = rootNode?.packageName?.toString()
                        if (!isIgnoredSystemPackage(winPkg)) {
                            if ((winPkg == packageName && IronDisciplineApp.isMainActivityVisible) || isHomeLauncherPackage(winPkg.orEmpty())) {
                                currentTargetAppKey = null
                                return null
                            }
                            val key = resolveTargetAppKey(winPkg)
                            if (key != null) {
                                currentTargetAppKey = key
                                lastTargetSeenTimestamp = System.currentTimeMillis()
                                return key
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Check rootInActiveWindow
        try {
            val rootPkg = rootInActiveWindow?.packageName?.toString()
            if (!isIgnoredSystemPackage(rootPkg)) {
                if ((rootPkg == packageName && IronDisciplineApp.isMainActivityVisible) || isHomeLauncherPackage(rootPkg.orEmpty())) {
                    currentTargetAppKey = null
                    return null
                }
                val key = resolveTargetAppKey(rootPkg)
                if (key != null) {
                    currentTargetAppKey = key
                    lastTargetSeenTimestamp = System.currentTimeMillis()
                    return key
                } else {
                    currentTargetAppKey = null
                    return null
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback to sticky currentTargetAppKey if recently seen within last 2.5s
        val now = System.currentTimeMillis()
        if (currentTargetAppKey != null && now - lastTargetSeenTimestamp < 2500L) {
            return currentTargetAppKey
        }

        return currentTargetAppKey
    }

    /**
     * Live On-Screen Floating Timer Pill:
     * Non-intrusive floating indicator in top-right corner.
     * Shows visual proof of live countdown inside target apps.
     */
    fun showOrUpdateFloatingTimerPill(appKey: String, remainingSec: Int) {
        val wm = windowManager ?: return

        val appTag = when (appKey) {
            "youtube" -> "YT"
            "facebook" -> "FB"
            "instagram" -> "IG"
            "tiktok" -> "TT"
            else -> appKey.take(2).uppercase()
        }
        val mmSs = RealtimeQuotaManager.formatSecondsToMmSs(remainingSec)
        val pillText = "⏱️ $appTag • $mmSs"

        if (floatingPillView != null) {
            floatingPillView?.text = pillText
            return
        }

        val pillTv = TextView(this).apply {
            text = pillText
            setTextColor(Color.parseColor("#00E676"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#E60D121D")) // 90% obsidian slate
                setStroke(dp(1), Color.parseColor("#00E676"))
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(52)
        }

        try {
            wm.addView(pillTv, params)
            floatingPillView = pillTv
        } catch (_: Exception) {
            if (Settings.canDrawOverlays(this)) {
                try {
                    params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    wm.addView(pillTv, params)
                    floatingPillView = pillTv
                } catch (_: Exception) {}
            }
        }
    }

    fun hideFloatingTimerPill() {
        val pill = floatingPillView ?: return
        floatingPillView = null
        try {
            windowManager?.removeViewImmediate(pill)
        } catch (_: Exception) {}
    }

    /**
     * Fail-Safe Backup Alarm Watchdog (Dual-Engine Protection):
     * Runs every second in the persistent Accessibility Service.
     * Keeps CPU ticking via maintainPendingAlarmCpuLock so 1s timer never freezes during screen-off sleep.
     * If trigger time arrives, instantly wakes screen, starts foreground ringing service, and launches UI.
     */
    private fun checkBackupAlarmWatchdog() {
        try {
            val alarms = AlarmRepository.getInstance(applicationContext).alarmsFlow.value
            val hasEnabledAlarms = alarms.any { it.isEnabled }

            // Allow natural sleep when idle to comply with Android power management
            AlarmWakeLockManager.maintainPendingAlarmCpuLock(this, hasEnabledAlarms)

            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (AlarmRingingService.isCurrentlyRinging || prefs.getBoolean("is_alarm_currently_ringing", false)) {
                return
            }

            val now = System.currentTimeMillis()

            for (alarm in alarms) {
                if (!alarm.isEnabled) continue
                val triggerAt = alarm.nextTriggerAtMillis
                if (triggerAt <= 0L) continue

                // Check if trigger time has arrived (allow up to 15-minute catch-up window)
                if (now >= triggerAt && (now - triggerAt) <= 15 * 60 * 1000L) {
                    val lastTriggeredKey = "last_triggered_timestamp_${alarm.id}"
                    val lastTriggered = prefs.getLong(lastTriggeredKey, 0L)

                    // Ensure this alarm hasn't fired in the last 60 seconds
                    if (now - lastTriggered > 60_000L) {
                        prefs.edit().putLong(lastTriggeredKey, now).commit()
                        Log.i("IronAlarmTrace", "[IronAlarmTrace:WATCHDOG_TRIGGER] Backup watchdog triggering Alarm ID=${alarm.id}, drift=${now - triggerAt}ms")

                        // 1. Physically turn the screen ON from sleep!
                        AlarmWakeLockManager.acquireRingingWakeLocks(this)

                        // 2. Initialize persistent session state
                        ActiveAlarmSessionState.initSession(alarm.id)

                        // 3. Immediately start AlarmRingingService in foreground
                        val serviceIntent = Intent(applicationContext, AlarmRingingService::class.java).apply {
                            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
                            putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, alarm.label)
                            putExtra(AlarmScheduler.EXTRA_SOUND_INDEX, alarm.soundIndex)
                            putExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING, alarm.deleteAfterRinging)
                            putExtra(AlarmReceiver.EXTRA_RECEIVER_TRIGGER_MS, now)
                            putExtra("EXTRA_IS_NEW_TRIGGER", true)
                        }
                        ContextCompat.startForegroundService(applicationContext, serviceIntent)

                        // 4. Launch AlarmRingingActivity directly (AccessibilityService has unrestricted background launch permission)
                        val activityIntent = Intent(applicationContext, AlarmRingingActivity::class.java).apply {
                            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
                            putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, alarm.label)
                            putExtra(AlarmScheduler.EXTRA_SOUND_INDEX, alarm.soundIndex)
                            putExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING, alarm.deleteAfterRinging)
                            putExtra(AlarmReceiver.EXTRA_RECEIVER_TRIGGER_MS, now)
                            putExtra("EXTRA_IS_NEW_TRIGGER", true)
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                            )
                        }
                        try {
                            startActivity(activityIntent)
                        } catch (e: Exception) {
                            Log.e("IronAlarmTrace", "[IronAlarmTrace:WATCHDOG_ACTIVITY_ERROR] Error starting AlarmRingingActivity: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("IronAlarmTrace", "[IronAlarmTrace:WATCHDOG_ERROR] Error in watchdog tick: ${e.message}")
        }
    }

    /**
     * Single Deterministic 1-Second Ticker (Runs strictly on Main Thread).
     * Guarantees monotonic time progression: time only ever increases by +1s during usage.
     */
    private fun tickOneSecond() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Authorized App Uninstall: stand down overlay and allow unhindered removal
        if (com.irondiscipline.app.util.SecurityPinManager.isUninstallSessionActive(this)) {
            hideFloatingTimerPill()
            hideBlockOverlay()
            return
        }

        // Midnight rollover check for unlocked apps
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
                        .commit()
                } else {
                    prefs.edit()
                        .putInt(PREFIX_REMAINING_LOCK_SEC + appKey, nextLockRem)
                        .commit()
                }
            }
        }

        // CRITICAL LATCH CHECK: If block overlay is currently showing, keep it strictly latched!
        if (isBlockOverlayShowing && latchedLockedAppKey != null) {
            val key = latchedLockedAppKey!!
            val remLock = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + key, 0)
            if (remLock > 0) {
                hideFloatingTimerPill()
                showOrUpdateBlockOverlay(key, remLock)
                stopVideoAndSilenceAudio() // Keep underlying video paused/muted
                realtimeQuotaManager.publishSnapshot()
                return // Stay latched! Do NOT run normal detection or hide overlay!
            } else {
                // 5-hour lock has completely expired
                hideBlockOverlay()
            }
        }

        // Grace period check: Wait for Home Launcher transition
        if (System.currentTimeMillis() < exitToHomeGraceUntilMs) {
            return
        }

        // Calculator Anti-Cheat check while alarm is ringing
        if (prefs.getBoolean("is_alarm_currently_ringing", false) && lastDetectedPackage.isNotBlank()) {
            if (CALCULATOR_PACKAGES.contains(lastDetectedPackage) || lastDetectedPackage.contains("calc", ignoreCase = true)) {
                showOrUpdateNonCoreBlockOverlay("Calculator (Math Mission Active)")
                return
            }
        }

        // If inside Dashboard or Home Launcher, ensure overlay and pill are hidden
        if (IronDisciplineApp.isMainActivityVisible || isHomeLauncherPackage(lastDetectedPackage) || (lastDetectedPackage == packageName && IronDisciplineApp.isMainActivityVisible)) {
            hideFloatingTimerPill()
            if (isBlockOverlayShowing) {
                hideBlockOverlay()
            }
            realtimeQuotaManager.publishSnapshot()
            return
        }

        // Step B — Active App Detection & 1-Second Synchronous Ticking:
        val activeKey = detectCurrentTargetAppKey()
        if (activeKey != null) {
            val lockRem = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + activeKey, 0)
            val isTestMode = prefs.getBoolean("is_test_mode", false) || prefs.getBoolean("is_one_minute_test_mode", false)
            val limitSeconds = if (isTestMode) 60 else 1800

            if (lockRem > 0) {
                // App is locked for 5 hours: latch, hide mini pill, show block overlay, silence audio
                hideFloatingTimerPill()
                latchedLockedAppKey = activeKey
                showOrUpdateBlockOverlay(activeKey, lockRem)
                stopVideoAndSilenceAudio()
            } else {
                // App is unlocked: synchronously increment used seconds by +1
                val used = prefs.getInt(PREFIX_USED_SEC + activeKey, 0) + 1
                prefs.edit().putInt(PREFIX_USED_SEC + activeKey, used).commit()

                val remainingSec = (limitSeconds - used).coerceAtLeast(0)

                if (used >= limitSeconds) {
                    // Reached limit: trigger 5-hour lock immediately
                    val lockEndMs = System.currentTimeMillis() + FIVE_HOURS_MS
                    prefs.edit()
                        .putInt(PREFIX_REMAINING_LOCK_SEC + activeKey, FIVE_HOURS_SECONDS)
                        .putLong(PREFIX_LOCKED_UNTIL + activeKey, lockEndMs)
                        .commit()
                    hideFloatingTimerPill()
                    latchedLockedAppKey = activeKey
                    showOrUpdateBlockOverlay(activeKey, FIVE_HOURS_SECONDS)
                    stopVideoAndSilenceAudio()
                } else {
                    // Show or update top-right Live Floating Timer Pill
                    showOrUpdateFloatingTimerPill(activeKey, remainingSec)

                    if (isBlockOverlayShowing && latchedLockedAppKey == activeKey) {
                        hideBlockOverlay()
                    }
                }
            }
        } else {
            // User switched to an unblocked application (e.g. WhatsApp, Maps)
            hideFloatingTimerPill()
            if (isBlockOverlayShowing && latchedLockedAppKey == null) {
                hideBlockOverlay()
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
            editor.commit()
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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val remSec = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + appKey, FIVE_HOURS_SECONDS)
        showOrUpdateBlockOverlay(appKey, if (remSec > 0) remSec else FIVE_HOURS_SECONDS)
    }

    fun showOrUpdateBlockOverlay(appKey: String, remainingLockSeconds: Int) {
        val displayAppName = when (appKey) {
            "youtube" -> "YouTube"
            "facebook" -> "Facebook"
            "instagram" -> "Instagram"
            "tiktok" -> "TikTok"
            else -> appKey.replaceFirstChar { it.uppercase() }
        }
        val formattedTime = RealtimeQuotaManager.formatSecondsToHhMmSs(remainingLockSeconds)

        // 1. If overlay is ALREADY on screen, ONLY update the TextViews in-place! (ZERO FLICKER)
        if (isBlockOverlayShowing && blockOverlayView != null) {
            overlayTitleTextView?.text = "$displayAppName 5-Hour Lock Active!"
            overlayCountdownTextView?.text = formattedTime
            return
        }

        // 2. Otherwise, create and attach the overlay view ONCE
        val wm = windowManager ?: return
        hideFloatingTimerPill()
        stopVideoAndSilenceAudio()

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastInterceptVibrationMs > 1500L) {
            lastInterceptVibrationMs = nowMs
            vibrateWarningPulse()
        }

        try {
            val view = buildNativeBlockOverlayView(displayAppName, appKey, remainingLockSeconds)
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            try {
                wm.addView(view, params)
                blockOverlayView = view
                isBlockOverlayShowing = true
                latchedLockedAppKey = appKey
            } catch (_: Exception) {
                if (Settings.canDrawOverlays(this)) {
                    try {
                        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        wm.addView(view, params)
                        blockOverlayView = view
                        isBlockOverlayShowing = true
                        latchedLockedAppKey = appKey
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun showOrUpdateNonCoreBlockOverlay(title: String) {
        hideFloatingTimerPill()
        stopVideoAndSilenceAudio()
        if (isBlockOverlayShowing && blockOverlayView != null) {
            overlayTitleTextView?.text = "🛡️ $title"
            blockedAppSubtitleTextView?.text = "Yeh feature 5-hour active lock ke dauran protected hai."
            countdownLabelTextView?.text = "DISCIPLINE PROTECTION ENGAGED"
            overlayCountdownTextView?.text = "--:--:--"
            return
        }

        val wm = windowManager ?: return
        val view = buildNativeBlockOverlayView(title, null, FIVE_HOURS_SECONDS)
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
        try {
            wm.addView(view, params)
            blockOverlayView = view
            isBlockOverlayShowing = true
            latchedLockedAppKey = null
        } catch (_: Exception) {
            if (Settings.canDrawOverlays(this)) {
                try {
                    params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    wm.addView(view, params)
                    blockOverlayView = view
                    isBlockOverlayShowing = true
                    latchedLockedAppKey = null
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Builds the Full-Screen Native Vault Overlay (Obsidian Cyber-Discipline theme).
     */
    private fun buildNativeBlockOverlayView(
        appName: String,
        appKey: String?,
        remainingLockSeconds: Int
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
            text = if (appKey != null) "$appName 5-Hour Lock Active!" else "🛡️ $appName"
            setTextColor(Color.parseColor("#FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(12))
        }
        overlayTitleTextView = headerTv
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
            text = if (appKey != null) {
                "“Yeh app 5 ghante ke liye strict lock ho chuki hai. 5 ghante se pehle yeh kisi surat unlock nahi hogi.”"
            } else {
                "“Yeh feature 5-hour active lock ke dauran protected hai.”"
            }
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
            text = if (appKey != null) "UNLOCK HONE MEIN BAQI WAQT (5 HOURS STRICT)" else "DISCIPLINE PROTECTION ENGAGED"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        }
        countdownLabelTextView = labelTv
        countdownBox.addView(labelTv)

        val timerTv = TextView(this).apply {
            text = if (appKey != null) RealtimeQuotaManager.formatSecondsToHhMmSs(remainingLockSeconds) else "--:--:--"
            setTextColor(Color.parseColor("#FF4D6D"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 36f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        }
        overlayCountdownTextView = timerTv
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
                // 1. Set a 600ms grace cooldown so the service ignores the closing target app while returning Home
                exitToHomeGraceUntilMs = System.currentTimeMillis() + 600L
                currentTargetAppKey = null
                latchedLockedAppKey = null
                lastDetectedPackage = ""

                // 2. Navigate to Home Screen immediately
                performGlobalAction(GLOBAL_ACTION_HOME)

                // 3. Remove the overlay and restore audio cleanly
                hideBlockOverlay()
            }

            // Rubber Jelly elastic stretch physics on hold & drag
            var downX = 0f
            var downY = 0f
            var isDragging = false
            val touchSlop = dp(8).toFloat()
            val maxDragPx = dp(32).toFloat()
            val dragResistance = dp(95).toFloat()

            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        isDragging = false
                        v.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120).start()
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - downX
                        val dy = event.rawY - downY
                        if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            val normX = tanh(dx / dragResistance)
                            val normY = tanh(dy / dragResistance)
                            val transX = normX * maxDragPx
                            val transY = normY * maxDragPx
                            val scaleX = (1f + (abs(normX) * 0.35f) - (abs(normY) * 0.08f)).coerceIn(0.88f, 1.4f)
                            val scaleY = (1f + (abs(normY) * 0.35f) - (abs(normX) * 0.08f)).coerceIn(0.88f, 1.4f)
                            v.translationX = transX
                            v.translationY = transY
                            v.scaleX = scaleX
                            v.scaleY = scaleY
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            v.animate()
                                .translationX(0f)
                                .translationY(0f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(380)
                                .setInterpolator(OvershootInterpolator(2.0f))
                                .start()
                            isDragging = false
                            true
                        } else {
                            v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                            v.performClick()
                            true
                        }
                    }
                    else -> false
                }
            }
        }
        cardContainer.addView(exitHomeButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        rootFrame.addView(cardContainer, cardParams)
        return rootFrame
    }

    fun hideBlockOverlay() {
        val view = blockOverlayView ?: return
        blockOverlayView = null
        isBlockOverlayShowing = false
        latchedLockedAppKey = null
        overlayTitleTextView = null
        overlayCountdownTextView = null
        blockedAppSubtitleTextView = null
        countdownLabelTextView = null

        restoreAudioCleanly()

        try {
            windowManager?.removeViewImmediate(view)
        } catch (_: Exception) {}
    }

    fun hideNativeBlockOverlay() {
        hideBlockOverlay()
    }

    /**
     * Emergency / Authorized Disarm:
     * Cleans up all overlays, audio muting, ticker callbacks, and shuts down the AccessibilityService.
     */
    fun disarmAndDisable() {
        hideFloatingTimerPill()
        hideBlockOverlay()
        restoreAudioCleanly()
        mainHandler.removeCallbacks(tickerRunnable)
        disableSelf()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
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

    private fun collectVisibleText(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        try {
            node.text?.let { sb.append(it).append(" ") }
            node.contentDescription?.let { sb.append(it).append(" ") }
            for (i in 0 until node.childCount) {
                collectVisibleText(node.getChild(i), sb)
            }
        } catch (_: Exception) {}
    }

    /**
     * Dismisses the system Power Off / Restart / Shutdown menu if invoked while the alarm is actively ringing.
     * Prevents bypassing the 3 Math Hard questions via the Android Global Actions dialog.
     */
    private fun guardActiveAlarmFromPowerDialog(event: AccessibilityEvent?) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isAlarmRinging = AlarmRingingService.isCurrentlyRinging ||
                prefs.getBoolean("is_alarm_currently_ringing", false)

        if (!isAlarmRinging) return

        val activePkg = (event?.packageName?.toString()
            ?: try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
            ?: "").lowercase()

        // Check if SystemUI or Android system dialog (Power Menu / Shutdown Menu) popped up over the alarm
        if (activePkg == "android" || activePkg.contains("systemui")) {
            val sb = StringBuilder()
            event?.text?.forEach { sb.append(it).append(" ") }
            try {
                collectVisibleText(rootInActiveWindow, sb)
            } catch (_: Exception) {}
            try {
                windows?.forEach { win ->
                    if (win.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                        collectVisibleText(win.root, sb)
                    }
                }
            } catch (_: Exception) {}

            val text = sb.toString().lowercase()
            val isPowerMenuVisible = text.contains("power off") ||
                    text.contains("restart") ||
                    text.contains("reboot") ||
                    text.contains("shut down") ||
                    text.contains("emergency") ||
                    text.contains("lockdown") ||
                    text.contains("turn off")

            if (isPowerMenuVisible) {
                // 1. Immediately dismiss the Power Off / Restart menu
                performGlobalAction(GLOBAL_ACTION_BACK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                }

                // 2. Wake screen and bring AlarmRingingActivity back to the front immediately
                AlarmWakeLockManager.acquireRingingWakeLocks(applicationContext)
                AlarmRingingService.bringAlarmScreenToFront(applicationContext)
            }
        }
    }

    private fun checkProtectedNavigation(event: AccessibilityEvent?) {
        // 1. If an authorized uninstall session is active, allow unhindered uninstallation
        if (com.irondiscipline.app.util.SecurityPinManager.isUninstallSessionActive(this)) {
            hideBlockOverlay()
            return
        }

        // 2. Give a 3-second grace window right after enabling the Accessibility Service
        if (System.currentTimeMillis() - serviceConnectedTimestampMs < 3000L) {
            return
        }

        val activePkg = (event?.packageName?.toString()
            ?: try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
            ?: "").lowercase()

        // Ignore our own app package
        if (activePkg.isEmpty() || activePkg == packageName.lowercase()) return

        val isSystemManagementScreen = activePkg.contains("packageinstaller") ||
                activePkg.contains("settings") ||
                activePkg.contains("miui.securitycenter") ||
                activePkg.contains("coloros.safecenter") ||
                activePkg.contains("oplus.safecenter") ||
                activePkg.contains("vivo.permissionmanager")

        if (!isSystemManagementScreen) return

        val sb = StringBuilder()
        event?.text?.forEach { sb.append(it).append(" ") }
        try {
            collectVisibleText(rootInActiveWindow, sb)
        } catch (_: Exception) {}

        val screenText = sb.toString().lowercase()
        val isAboutOurApp = screenText.contains("irondiscipline") || screenText.contains("iron discipline")

        if (!isAboutOurApp) return

        // Allow activating Device Admin when it is currently OFF
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        val adminComponent = android.content.ComponentName(this, FocusDeviceAdminReceiver::class.java)
        val isAdminActive = try { dpm?.isAdminActive(adminComponent) ?: false } catch (_: Exception) { false }

        if (!isAdminActive && (screenText.contains("activate this device admin") || screenText.contains("activate"))) {
            if (!screenText.contains("uninstall") && !screenText.contains("force stop")) {
                return // Let the user click "Activate" to turn ON Device Admin
            }
        }

        // Check if the screen is an Uninstall dialog, App Info page, Device Admin deactivate page, or Service turn-off dialog
        val isRestrictedScreen = screenText.contains("uninstall") ||
                screenText.contains("delete") ||
                screenText.contains("do you want to uninstall") ||
                screenText.contains("force stop") ||
                screenText.contains("clear data") ||
                screenText.contains("clear storage") ||
                screenText.contains("app info") ||
                screenText.contains("deactivate") ||
                screenText.contains("use service") ||
                screenText.contains("turn off") ||
                screenText.contains("disable") ||
                screenText.contains("stop?")

        if (isRestrictedScreen) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)
            val now = System.currentTimeMillis()
            if (now - lastProtectedToastMs > 1500L) {
                lastProtectedToastMs = now
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        applicationContext,
                        "🔒 Protected Mode Active: Enter 4-digit PIN inside IronDiscipline to delete.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
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
            Handler(Looper.getMainLooper()).post {
                service.hideFloatingTimerPill()
                service.hideBlockOverlay()
            }
        }

        /**
         * Disarms 5-hour locks for target apps and cleans up overlays so Android's
         * package uninstaller dialog is completely unobstructed.
         */
        fun disarmAndDisableServiceForUninstall(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            val coreKeys = listOf("youtube", "facebook", "instagram", "tiktok")
            for (key in coreKeys) {
                editor.putInt(PREFIX_REMAINING_LOCK_SEC + key, 0)
                editor.putLong(PREFIX_LOCKED_UNTIL + key, 0L)
            }
            editor.commit()

            val service = instance
            if (service != null) {
                Handler(Looper.getMainLooper()).post {
                    service.hideFloatingTimerPill()
                    service.hideBlockOverlay()
                    service.restoreAudioCleanly()
                }
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
    }
}
