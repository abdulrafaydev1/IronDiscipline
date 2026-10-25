package com.irondiscipline.app.util

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class CoreTargetApp(
    val appKey: String,
    val displayName: String,
    val packageNames: List<String>
) {
    YOUTUBE(
        appKey = "youtube",
        displayName = "YouTube",
        packageNames = listOf("com.google.android.youtube")
    ),
    FACEBOOK(
        appKey = "facebook",
        displayName = "Facebook",
        packageNames = listOf("com.facebook.katana")
    ),
    INSTAGRAM(
        appKey = "instagram",
        displayName = "Instagram",
        packageNames = listOf("com.instagram.android")
    ),
    TIKTOK(
        appKey = "tiktok",
        displayName = "TikTok",
        packageNames = listOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go"
        )
    );

    val canonicalKey: String get() = appKey

    companion object {
        val ALL_TARGET_PACKAGES: Set<String> = entries
            .flatMap { it.packageNames }
            .map { it.lowercase() }
            .toSet()

        fun fromPackageName(packageName: String): CoreTargetApp? {
            return entries.firstOrNull { target ->
                target.packageNames.any { it.equals(packageName, ignoreCase = true) }
            }
        }

        fun fromAppKey(appKey: String): CoreTargetApp? {
            return entries.firstOrNull { it.appKey.equals(appKey, ignoreCase = true) }
        }

        fun isCoreTargetPackage(packageName: String): Boolean {
            return fromPackageName(packageName) != null
        }

        fun canonicalKeyForPackage(packageName: String): String {
            return fromPackageName(packageName)?.appKey ?: packageName
        }
    }
}

data class CoreAppQuotaStatus(
    val target: CoreTargetApp,
    val usedSeconds: Int,
    val limitSeconds: Int,
    val remainingSeconds: Int,
    val isLocked: Boolean,
    val lockedUntilEpochMs: Long,
    val lockRemainingMillis: Long,
    val remainingLockSeconds: Int = 0
) {
    val formattedRemainingMmSs: String
        get() = RealtimeQuotaManager.formatSecondsToMmSs(remainingSeconds)

    val formattedLimitMinutes: String
        get() = "${limitSeconds / 60}m"

    val formattedLockRemainingHhMmSs: String
        get() = if (remainingLockSeconds > 0) {
            RealtimeQuotaManager.formatSecondsToHhMmSs(remainingLockSeconds)
        } else {
            RealtimeQuotaManager.formatMillisToHhMmSs(lockRemainingMillis)
        }

    val progressFraction: Float
        get() = if (limitSeconds > 0) {
            (usedSeconds.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
        } else 1f
}

data class RealtimeQuotaSnapshot(
    val dateString: String = LocalDate.now().toString(),
    val isTestModeActive: Boolean = false,
    val coreStatuses: List<CoreAppQuotaStatus> = emptyList()
) {
    val anyAppLocked: Boolean
        get() = coreStatuses.any { it.isLocked || it.remainingLockSeconds > 0 }

    fun statusFor(appKey: String): CoreAppQuotaStatus? {
        return coreStatuses.firstOrNull { it.target.appKey == appKey }
    }
}

/**
 * Synchronous SharedPreferences ("iron_discipline_prefs") + StateFlow state engine.
 * Stores strictly per appKey:
 * - used_seconds_<appKey>
 * - limit_seconds_<appKey>
 * - locked_until_epoch_ms_<appKey>
 * - remaining_lock_seconds_<appKey> (Strict 5-Hour Countdown: 18,000s)
 * - lock_started_elapsed_ms_<appKey> (Monotonic Clock-Tamper-Proof Base)
 * - is_one_minute_test_mode
 * - tracked_date_yyyy_mm_dd
 * Ensures independent timers and unbreakable 5-hour independent locks for YouTube, Facebook, Instagram, and TikTok.
 */
class RealtimeQuotaManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val usedSecondsCache = ConcurrentHashMap<String, Int>()
    private val limitSecondsCache = ConcurrentHashMap<String, Int>()
    private val lockedUntilCache = ConcurrentHashMap<String, Long>()
    private val remainingLockSecondsCache = ConcurrentHashMap<String, Int>()
    private val lockStartedElapsedCache = ConcurrentHashMap<String, Long>()
    private val lastElapsedCheckCache = ConcurrentHashMap<String, Long>()

    @Volatile
    private var currentTrackedDate: String = LocalDate.now().toString()

    @Volatile
    private var isTestMode: Boolean = false

    private val _quotaSnapshotFlow = MutableStateFlow(RealtimeQuotaSnapshot())
    val quotaSnapshotFlow: StateFlow<RealtimeQuotaSnapshot> = _quotaSnapshotFlow.asStateFlow()

    init {
        loadFromPrefsSynchronously()
        checkExpiryAndMidnightRollover()
        publishSnapshot()
    }

    @Synchronized
    private fun loadFromPrefsSynchronously() {
        val today = LocalDate.now().toString()
        currentTrackedDate = prefs.getString(KEY_TRACKED_DATE, today) ?: today
        isTestMode = prefs.getBoolean(KEY_IS_TEST_MODE, false)

        usedSecondsCache.clear()
        limitSecondsCache.clear()
        lockedUntilCache.clear()
        remainingLockSecondsCache.clear()
        lockStartedElapsedCache.clear()
        lastElapsedCheckCache.clear()

        val activeLimit = if (isTestMode) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS
        val nowElapsed = SystemClock.elapsedRealtime()

        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            val used = prefs.getInt(PREFIX_USED_SEC + key, 0)
            val lockedUntil = prefs.getLong(PREFIX_LOCKED_UNTIL + key, 0L)
            val remainingLockSec = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + key, 0)

            usedSecondsCache[key] = used
            limitSecondsCache[key] = activeLimit
            lockedUntilCache[key] = lockedUntil
            remainingLockSecondsCache[key] = remainingLockSec
            lockStartedElapsedCache[key] = nowElapsed
            lastElapsedCheckCache[key] = nowElapsed
        }
    }

    /**
     * Decrements the 5-hour countdown by genuine elapsed monotonic time.
     * Defeats all attempts to bypass the lock by advancing the phone date/time.
     */
    @Synchronized
    fun tickLockCountdowns(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        var changed = false
        val editor = prefs.edit()

        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            val remainingSec = remainingLockSecondsCache[key] ?: 0
            if (remainingSec > 0) {
                val lastCheck = lastElapsedCheckCache[key] ?: nowElapsedMs
                val deltaMs = nowElapsedMs - lastCheck
                if (deltaMs >= 950L) {
                    val secondsToDeduct = (deltaMs / 1000L).toInt().coerceAtLeast(1)
                    val newRemaining = (remainingSec - secondsToDeduct).coerceAtLeast(0)
                    remainingLockSecondsCache[key] = newRemaining
                    lastElapsedCheckCache[key] = nowElapsedMs
                    changed = true

                    if (newRemaining <= 0) {
                        // 5 Full genuine hours have elapsed!
                        remainingLockSecondsCache[key] = 0
                        lockedUntilCache[key] = 0L
                        usedSecondsCache[key] = 0 // Grant fresh quota
                        editor.putInt(PREFIX_REMAINING_LOCK_SEC + key, 0)
                        editor.putLong(PREFIX_LOCKED_UNTIL + key, 0L)
                        editor.putInt(PREFIX_USED_SEC + key, 0)
                    } else {
                        editor.putInt(PREFIX_REMAINING_LOCK_SEC + key, newRemaining)
                    }
                }
            }
        }

        if (changed) {
            editor.commit()
            publishSnapshot()
        }
        return changed
    }

    /**
     * Checks midnight rollover for UNLOCKED apps only.
     * If an app has remaining_lock_seconds > 0, IT CANNOT BE UNLOCKED by date changes.
     */
    @Synchronized
    fun checkExpiryAndMidnightRollover(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        val today = LocalDate.now().toString()
        val isNewDay = today != currentTrackedDate

        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            val remainingSec = remainingLockSecondsCache[key] ?: 0

            // If still serving 5-hour penalty lock, DO NOT reset on midnight rollover or clock change!
            if (remainingSec > 0) {
                continue
            }

            // Midnight rollover for unlocked apps
            if (isNewDay) {
                usedSecondsCache[key] = 0
                lockedUntilCache[key] = 0L
                changed = true
            }
        }

        if (isNewDay) {
            currentTrackedDate = today
            changed = true
        }

        if (changed) {
            saveAllToPrefsSynchronously()
            publishSnapshot(nowEpochMs)
        }
        return changed
    }

    fun isTestModeActive(): Boolean = isTestMode

    fun checkMidnightResetIfNeeded(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        return checkExpiryAndMidnightRollover(nowEpochMs)
    }

    fun getUsedSecondsToday(appKeyOrPackage: String): Int {
        val appKey = CoreTargetApp.fromPackageName(appKeyOrPackage)?.appKey ?: appKeyOrPackage
        return getUsedSeconds(appKey)
    }

    fun getAppLimitSeconds(appKey: String): Int {
        return if (isTestMode) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS
    }

    fun getUsedSeconds(appKey: String): Int {
        checkExpiryAndMidnightRollover()
        return usedSecondsCache[appKey] ?: 0
    }

    fun getLockedUntilEpochMs(appKey: String): Long {
        return lockedUntilCache[appKey] ?: 0L
    }

    fun getRemainingLockSeconds(appKey: String): Int {
        return (remainingLockSecondsCache[appKey] ?: 0).coerceAtLeast(0)
    }

    /**
     * Returns true if ANY target app is locked for 5 hours or reached its limit.
     */
    fun isAnyAppLocked(): Boolean {
        return CoreTargetApp.entries.any { target ->
            (remainingLockSecondsCache[target.appKey] ?: 0) > 0 ||
                (usedSecondsCache[target.appKey] ?: 0) >= getAppLimitSeconds(target.appKey)
        }
    }

    /**
     * Returns true if the specific target app is currently locked.
     * Controlled strictly by remaining_lock_seconds > 0.
     */
    @Synchronized
    fun isAppLocked(appKey: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        val remainingSec = remainingLockSecondsCache[appKey] ?: 0
        if (remainingSec > 0) {
            return true
        }

        val used = usedSecondsCache[appKey] ?: 0
        val limit = getAppLimitSeconds(appKey)
        if (used >= limit) {
            engageLockForAppInternal(appKey, nowEpochMs)
            return true
        }
        return false
    }

    /**
     * Increments used_seconds_<appKey> by 1 second for that specific app only.
     * If limit is reached, locks ONLY this specific app for strictly 5 full hours.
     */
    @Synchronized
    fun incrementUsedSecond(appKey: String, nowEpochMs: Long = System.currentTimeMillis()): Int {
        val remainingSec = remainingLockSecondsCache[appKey] ?: 0
        if (remainingSec > 0) {
            publishSnapshot(nowEpochMs)
            return usedSecondsCache[appKey] ?: 0
        }

        val currentUsed = usedSecondsCache[appKey] ?: 0
        val nextUsed = currentUsed + 1
        usedSecondsCache[appKey] = nextUsed

        val limit = getAppLimitSeconds(appKey)
        if (nextUsed >= limit) {
            engageLockForAppInternal(appKey, nowEpochMs)
        } else {
            prefs.edit()
                .putString(KEY_TRACKED_DATE, currentTrackedDate)
                .putInt(PREFIX_USED_SEC + appKey, nextUsed)
                .commit()
        }

        publishSnapshot(nowEpochMs)
        return nextUsed
    }

    /**
     * Locks ONLY the specified appKey for STRICTLY 5 HOURS (18,000 Seconds).
     */
    @Synchronized
    fun engageLockForApp(appKey: String, nowEpochMs: Long = System.currentTimeMillis()) {
        engageLockForAppInternal(appKey, nowEpochMs)
        publishSnapshot(nowEpochMs)
    }

    private fun engageLockForAppInternal(appKey: String, nowEpochMs: Long) {
        val currentRemainingSec = remainingLockSecondsCache[appKey] ?: 0
        if (currentRemainingSec > 0) return

        val lockEndMs = nowEpochMs + FIVE_HOURS_MS
        val nowElapsed = SystemClock.elapsedRealtime()

        remainingLockSecondsCache[appKey] = FIVE_HOURS_SECONDS
        lockedUntilCache[appKey] = lockEndMs
        lockStartedElapsedCache[appKey] = nowElapsed
        lastElapsedCheckCache[appKey] = nowElapsed

        prefs.edit()
            .putInt(PREFIX_REMAINING_LOCK_SEC + appKey, FIVE_HOURS_SECONDS)
            .putLong(PREFIX_LOCKED_UNTIL + appKey, lockEndMs)
            .putLong(PREFIX_LOCK_STARTED_ELAPSED_MS + appKey, nowElapsed)
            .putInt(PREFIX_USED_SEC + appKey, usedSecondsCache[appKey] ?: getAppLimitSeconds(appKey))
            .commit()
    }

    /**
     * Configures custom limit minutes for a specific target app.
     */
    @Synchronized
    fun setAppLimitMinutes(appKey: String, limitMinutes: Int) {
        // If app is currently locked for 5 hours, limit cannot be changed to unlock it
        if ((remainingLockSecondsCache[appKey] ?: 0) > 0) return

        val limitSec = (limitMinutes.coerceAtLeast(1)) * 60
        limitSecondsCache[appKey] = limitSec
        prefs.edit().putInt(PREFIX_LIMIT_SEC + appKey, limitSec).commit()

        val nowMs = System.currentTimeMillis()
        val used = usedSecondsCache[appKey] ?: 0
        if (used >= limitSec) {
            engageLockForAppInternal(appKey, nowMs)
        }

        publishSnapshot()
    }

    /**
     * Resets used seconds and active lock for a SINGLE specific app (Only allowed when not in 5-hour lock).
     */
    @Synchronized
    fun resetAppTimerAndLock(appKey: String) {
        if ((remainingLockSecondsCache[appKey] ?: 0) > 0) return

        usedSecondsCache[appKey] = 0
        lockedUntilCache[appKey] = 0L
        remainingLockSecondsCache[appKey] = 0
        val activeLimit = if (isTestMode) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS
        limitSecondsCache[appKey] = activeLimit
        prefs.edit()
            .putInt(PREFIX_USED_SEC + appKey, 0)
            .putInt(PREFIX_LIMIT_SEC + appKey, activeLimit)
            .putLong(PREFIX_LOCKED_UNTIL + appKey, 0L)
            .putInt(PREFIX_REMAINING_LOCK_SEC + appKey, 0)
            .commit()
        publishSnapshot()
    }

    /**
     * Global 1-Min Test Mode: switches limit for all 4 apps to 60s.
     * BLOCKED if ANY app currently has an active 5-hour lock!
     */
    @Synchronized
    fun activateGlobalOneMinuteTestMode() {
        if (isAnyAppLocked()) return

        currentTrackedDate = LocalDate.now().toString()
        isTestMode = true
        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            limitSecondsCache[key] = TEST_MODE_LIMIT_SECONDS
            usedSecondsCache[key] = 0
            lockedUntilCache[key] = 0L
            remainingLockSecondsCache[key] = 0
        }
        saveAllToPrefsSynchronously()
        publishSnapshot()
    }

    /**
     * Global 30-Min Normal Mode: switches limit for all 4 apps to 1800s.
     * BLOCKED if ANY app currently has an active 5-hour lock!
     */
    @Synchronized
    fun switchBackToNormalThirtyMinModeAndReset() {
        if (isAnyAppLocked()) return

        currentTrackedDate = LocalDate.now().toString()
        isTestMode = false
        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            limitSecondsCache[key] = NORMAL_MODE_LIMIT_SECONDS
            usedSecondsCache[key] = 0
            lockedUntilCache[key] = 0L
            remainingLockSecondsCache[key] = 0
        }
        saveAllToPrefsSynchronously()
        publishSnapshot()
    }

    /**
     * Reset All Timers & Locks.
     * BLOCKED if ANY app currently has an active 5-hour lock!
     */
    @Synchronized
    fun resetAllTimersAndLocks() {
        if (isAnyAppLocked()) return

        currentTrackedDate = LocalDate.now().toString()
        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            usedSecondsCache[key] = 0
            lockedUntilCache[key] = 0L
            remainingLockSecondsCache[key] = 0
        }
        saveAllToPrefsSynchronously()
        publishSnapshot()
    }

    private fun saveAllToPrefsSynchronously() {
        val editor = prefs.edit()
            .putString(KEY_TRACKED_DATE, currentTrackedDate)
            .putBoolean(KEY_IS_TEST_MODE, isTestMode)

        val activeLimit = if (isTestMode) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS
        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            editor.putInt(PREFIX_USED_SEC + key, usedSecondsCache[key] ?: 0)
            editor.putInt(PREFIX_LIMIT_SEC + key, activeLimit)
            editor.putLong(PREFIX_LOCKED_UNTIL + key, lockedUntilCache[key] ?: 0L)
            editor.putInt(PREFIX_REMAINING_LOCK_SEC + key, remainingLockSecondsCache[key] ?: 0)
            editor.putLong(PREFIX_LOCK_STARTED_ELAPSED_MS + key, lockStartedElapsedCache[key] ?: 0L)
        }
        editor.commit()
    }

    fun refreshSnapshotNow(nowEpochMs: Long = System.currentTimeMillis()) {
        tickLockCountdowns()
        checkExpiryAndMidnightRollover(nowEpochMs)
        publishSnapshot(nowEpochMs)
    }

    private fun publishSnapshot(nowEpochMs: Long = System.currentTimeMillis()) {
        val statuses = CoreTargetApp.entries.map { target ->
            val key = target.appKey
            val used = (usedSecondsCache[key] ?: 0).coerceAtLeast(0)
            val limit = getAppLimitSeconds(key)
            val lockedUntil = lockedUntilCache[key] ?: 0L
            val remainingLockSec = (remainingLockSecondsCache[key] ?: 0).coerceAtLeast(0)
            val isLocked = remainingLockSec > 0 || used >= limit
            val remainingSec = if (isLocked) 0 else (limit - used).coerceAtLeast(0)
            val lockRemainingMs = remainingLockSec * 1000L

            CoreAppQuotaStatus(
                target = target,
                usedSeconds = used,
                limitSeconds = limit,
                remainingSeconds = remainingSec,
                isLocked = isLocked,
                lockedUntilEpochMs = lockedUntil,
                lockRemainingMillis = lockRemainingMs,
                remainingLockSeconds = remainingLockSec
            )
        }

        _quotaSnapshotFlow.value = RealtimeQuotaSnapshot(
            dateString = currentTrackedDate,
            isTestModeActive = isTestMode,
            coreStatuses = statuses
        )
    }

    companion object {
        const val PREFS_NAME = "iron_discipline_prefs"
        const val KEY_TRACKED_DATE = "tracked_date_yyyy_mm_dd"
        const val KEY_IS_TEST_MODE = "is_one_minute_test_mode"

        const val PREFIX_USED_SEC = "used_seconds_"
        const val PREFIX_LIMIT_SEC = "limit_seconds_"
        const val PREFIX_LOCKED_UNTIL = "locked_until_epoch_ms_"
        const val PREFIX_REMAINING_LOCK_SEC = "remaining_lock_seconds_"
        const val PREFIX_LOCK_STARTED_ELAPSED_MS = "lock_started_elapsed_ms_"

        const val FIVE_HOURS_SECONDS = 5 * 60 * 60 // 18,000 seconds
        const val FIVE_HOURS_MS = 18_000_000L      // 18,000,000 ms

        const val NORMAL_MODE_LIMIT_SECONDS = 1800 // 30 minutes
        const val TEST_MODE_LIMIT_SECONDS = 60     // 1 minute
        const val TEST_MODE_LOCK_DURATION_MS = FIVE_HOURS_MS // strictly 5 hours in test mode too

        @Volatile
        private var INSTANCE: RealtimeQuotaManager? = null

        fun getInstance(context: Context): RealtimeQuotaManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RealtimeQuotaManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun formatSecondsToMmSs(totalSeconds: Int): String {
            val clamped = totalSeconds.coerceAtLeast(0)
            val mins = clamped / 60
            val secs = clamped % 60
            return String.format(Locale.US, "%02d:%02d", mins, secs)
        }

        fun formatMillisToHhMmSs(remainingMillis: Long): String {
            val totalSeconds = (remainingMillis.coerceAtLeast(0L) + 999L) / 1000L
            val hours = totalSeconds / 3600L
            val minutes = (totalSeconds % 3600L) / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }

        fun formatSecondsToHhMmSs(totalSeconds: Int): String {
            val clamped = totalSeconds.coerceAtLeast(0)
            val hours = clamped / 3600
            val minutes = (clamped % 3600) / 60
            val seconds = clamped % 60
            return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }
}
