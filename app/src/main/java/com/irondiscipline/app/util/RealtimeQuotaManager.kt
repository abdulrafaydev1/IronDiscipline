package com.irondiscipline.app.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class CoreTargetApp(
    val canonicalKey: String,
    val displayName: String,
    val packageNames: List<String>
) {
    YOUTUBE(
        canonicalKey = "com.google.android.youtube",
        displayName = "YouTube",
        packageNames = listOf("com.google.android.youtube")
    ),
    FACEBOOK(
        canonicalKey = "com.facebook.katana",
        displayName = "Facebook",
        packageNames = listOf("com.facebook.katana")
    ),
    INSTAGRAM(
        canonicalKey = "com.instagram.android",
        displayName = "Instagram",
        packageNames = listOf("com.instagram.android")
    ),
    TIKTOK(
        canonicalKey = "com.zhiliaoapp.musically",
        displayName = "TikTok",
        packageNames = listOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.zhiliaoapp.musically.go"
        )
    );

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

        fun canonicalKeyForPackage(packageName: String): String {
            return fromPackageName(packageName)?.canonicalKey ?: packageName
        }

        fun isCoreTargetPackage(packageName: String): Boolean {
            return ALL_TARGET_PACKAGES.contains(packageName.lowercase())
        }
    }
}

data class CoreAppQuotaStatus(
    val target: CoreTargetApp,
    val usedSeconds: Int,
    val limitSeconds: Int,
    val remainingSeconds: Int,
    val isLocked: Boolean
) {
    val formattedRemainingMmSs: String
        get() = RealtimeQuotaManager.formatSecondsToMmSs(remainingSeconds)

    val progressFraction: Float
        get() = if (limitSeconds > 0) {
            (usedSeconds.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
        } else 1f
}

data class RealtimeQuotaSnapshot(
    val dateString: String = LocalDate.now().toString(),
    val isTestModeActive: Boolean = false,
    val activeLimitSeconds: Int = RealtimeQuotaManager.NORMAL_MODE_LIMIT_SECONDS,
    val usedSecondsToday: Int = 0,
    val remainingUsageSeconds: Int = RealtimeQuotaManager.NORMAL_MODE_LIMIT_SECONDS,
    val isLockedNow: Boolean = false,
    val lockedUntilEpochMs: Long = 0L,
    val lockRemainingMillis: Long = 0L,
    val coreStatuses: List<CoreAppQuotaStatus> = emptyList(),
    val headlineRemainingSeconds: Int = RealtimeQuotaManager.NORMAL_MODE_LIMIT_SECONDS
) {
    val formattedHeadlineRemainingMmSs: String
        get() = RealtimeQuotaManager.formatSecondsToMmSs(headlineRemainingSeconds)

    val formattedLockRemainingHhMmSs: String
        get() = RealtimeQuotaManager.formatMillisToHhMmSs(lockRemainingMillis)
}

/**
 * Synchronous SharedPreferences ("iron_discipline_prefs") + StateFlow state engine.
 * Stores:
 * - used_seconds_today
 * - is_locked_until_epoch_ms
 * - is_one_minute_test_mode
 * Ensures zero-latency lock enforcement that cannot be bypassed by closing and re-opening an app.
 */
class RealtimeQuotaManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val perAppUsedSecondsCache = ConcurrentHashMap<String, Int>()

    @Volatile
    private var currentTrackedDate: String = LocalDate.now().toString()

    @Volatile
    private var isTestMode: Boolean = false

    @Volatile
    private var usedSecondsTodayState: Int = 0

    @Volatile
    private var isLockedUntilEpochMsState: Long = 0L

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
        usedSecondsTodayState = prefs.getInt(KEY_USED_SECONDS_TODAY, 0)
        isLockedUntilEpochMsState = prefs.getLong(KEY_IS_LOCKED_UNTIL_EPOCH_MS, 0L)

        perAppUsedSecondsCache.clear()
        for (target in CoreTargetApp.entries) {
            val savedAppSec = prefs.getInt(PREFIX_USED_SEC + target.canonicalKey, 0)
            perAppUsedSecondsCache[target.canonicalKey] = savedAppSec
        }
    }

    /**
     * Checks if an active lock timer has expired or if midnight (00:00) has passed.
     * Note: While a lock is actively enforced (now < is_locked_until_epoch_ms), it is NEVER cleared early.
     */
    @Synchronized
    fun checkExpiryAndMidnightRollover(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        var changed = false

        // 1. If a lock was set and has now expired (now >= is_locked_until_epoch_ms), unlock and reset counter
        if (isLockedUntilEpochMsState > 0L && nowEpochMs >= isLockedUntilEpochMsState) {
            isLockedUntilEpochMsState = 0L
            usedSecondsTodayState = 0
            perAppUsedSecondsCache.clear()
            currentTrackedDate = LocalDate.now().toString()
            saveStateToPrefsSynchronously()
            changed = true
        }

        // 2. If not currently locked and date rolled over past midnight, reset daily usage
        val today = LocalDate.now().toString()
        if (isLockedUntilEpochMsState <= nowEpochMs && today != currentTrackedDate) {
            currentTrackedDate = today
            usedSecondsTodayState = 0
            isLockedUntilEpochMsState = 0L
            perAppUsedSecondsCache.clear()
            saveStateToPrefsSynchronously()
            changed = true
        }

        if (changed) {
            publishSnapshot(nowEpochMs)
        }
        return changed
    }

    fun checkMidnightResetIfNeeded(): Boolean {
        return checkExpiryAndMidnightRollover(System.currentTimeMillis())
    }

    fun isTestModeActive(): Boolean {
        return isTestMode
    }

    fun getActiveLimitSeconds(): Int {
        return if (isTestMode) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS
    }

    fun getUsedSecondsToday(): Int {
        checkExpiryAndMidnightRollover()
        return usedSecondsTodayState
    }

    fun getUsedSecondsToday(packageName: String): Int {
        checkExpiryAndMidnightRollover()
        val canonicalKey = CoreTargetApp.canonicalKeyForPackage(packageName)
        val perApp = perAppUsedSecondsCache[canonicalKey] ?: 0
        return maxOf(usedSecondsTodayState, perApp)
    }

    fun getLockedUntilEpochMs(): Long {
        checkExpiryAndMidnightRollover()
        return isLockedUntilEpochMsState
    }

    /**
     * Returns true if the target apps are currently in a persistent LOCKED STATE.
     */
    @Synchronized
    fun isCurrentlyLocked(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        checkExpiryAndMidnightRollover(nowEpochMs)
        if (isLockedUntilEpochMsState > nowEpochMs) {
            return true
        }
        val limitSeconds = getActiveLimitSeconds()
        if (usedSecondsTodayState >= limitSeconds) {
            // Lock threshold reached: persist lock end timestamp immediately
            engagePersistentLockInternal(nowEpochMs)
            return true
        }
        return false
    }

    /**
     * Increments used_seconds_today by 1 second when a target app is in the foreground.
     * Immediately engages and persists is_locked_until_epoch_ms the moment used_seconds_today >= limitSeconds.
     */
    @Synchronized
    fun incrementUsedSecond(packageName: String, nowEpochMs: Long = System.currentTimeMillis()): Int {
        checkExpiryAndMidnightRollover(nowEpochMs)

        // If already locked, keep locked without resetting
        if (isLockedUntilEpochMsState > nowEpochMs) {
            publishSnapshot(nowEpochMs)
            return usedSecondsTodayState
        }

        val canonicalKey = CoreTargetApp.canonicalKeyForPackage(packageName)
        val nextAppSec = (perAppUsedSecondsCache[canonicalKey] ?: 0) + 1
        perAppUsedSecondsCache[canonicalKey] = nextAppSec

        usedSecondsTodayState += 1
        val limitSeconds = getActiveLimitSeconds()

        if (usedSecondsTodayState >= limitSeconds) {
            engagePersistentLockInternal(nowEpochMs)
        } else {
            saveStateToPrefsSynchronously()
        }

        publishSnapshot(nowEpochMs)
        return usedSecondsTodayState
    }

    @Synchronized
    private fun engagePersistentLockInternal(nowEpochMs: Long) {
        if (isLockedUntilEpochMsState > nowEpochMs) return

        isLockedUntilEpochMsState = if (isTestMode) {
            // In 1-Min Test Mode: Lock for 30 Minutes so user sees live HH:MM:SS countdown & re-open lock
            nowEpochMs + TEST_MODE_LOCK_DURATION_MS
        } else {
            // In Normal Mode (30m limit): Lock until next Midnight (00:00:00)
            val zoneId = ZoneId.systemDefault()
            val nextMidnightMs = LocalDate.now(zoneId)
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            if (nextMidnightMs - nowEpochMs > 60_000L) {
                nextMidnightMs
            } else {
                nowEpochMs + 24 * 3600_000L
            }
        }
        saveStateToPrefsSynchronously()
    }

    @Synchronized
    private fun saveStateToPrefsSynchronously() {
        val editor = prefs.edit()
            .putString(KEY_TRACKED_DATE, currentTrackedDate)
            .putBoolean(KEY_IS_TEST_MODE, isTestMode)
            .putInt(KEY_USED_SECONDS_TODAY, usedSecondsTodayState)
            .putLong(KEY_IS_LOCKED_UNTIL_EPOCH_MS, isLockedUntilEpochMsState)

        for ((pkgKey, sec) in perAppUsedSecondsCache) {
            editor.putInt(PREFIX_USED_SEC + pkgKey, sec)
        }
        // Use commit() so state is guaranteed persisted synchronously on disk
        editor.commit()
    }

    /**
     * Switches the limit for YouTube, Facebook, Instagram, and TikTok to 1 minute (60s)
     * and resets used_seconds_today = 0 and is_locked_until_epoch_ms = 0.
     */
    @Synchronized
    fun activateOneMinuteTestMode() {
        currentTrackedDate = LocalDate.now().toString()
        isTestMode = true
        usedSecondsTodayState = 0
        isLockedUntilEpochMsState = 0L
        perAppUsedSecondsCache.clear()
        saveStateToPrefsSynchronously()
        publishSnapshot()
    }

    /**
     * Switches back to the 30-Minute (1,800s) Normal Mode limit and resets counters.
     */
    @Synchronized
    fun switchBackToNormalThirtyMinModeAndReset() {
        currentTrackedDate = LocalDate.now().toString()
        isTestMode = false
        usedSecondsTodayState = 0
        isLockedUntilEpochMsState = 0L
        perAppUsedSecondsCache.clear()
        saveStateToPrefsSynchronously()
        publishSnapshot()
    }

    /**
     * Explicit "Unlock & Reset Timer (For Testing)" action from Dashboard.
     * Sets used_seconds_today = 0 and is_locked_until_epoch_ms = 0 while preserving current mode.
     */
    @Synchronized
    fun unlockAndResetTimerForTesting() {
        currentTrackedDate = LocalDate.now().toString()
        usedSecondsTodayState = 0
        isLockedUntilEpochMsState = 0L
        perAppUsedSecondsCache.clear()
        saveStateToPrefsSynchronously()
        publishSnapshot()
    }

    fun refreshSnapshotNow(nowEpochMs: Long = System.currentTimeMillis()) {
        checkExpiryAndMidnightRollover(nowEpochMs)
        publishSnapshot(nowEpochMs)
    }

    private fun publishSnapshot(nowEpochMs: Long = System.currentTimeMillis()) {
        val limitSec = getActiveLimitSeconds()
        val lockedNow = isLockedUntilEpochMsState > nowEpochMs
        val lockRemainingMs = if (lockedNow) {
            (isLockedUntilEpochMsState - nowEpochMs).coerceAtLeast(0L)
        } else 0L

        val remainingUsageSec = if (lockedNow) {
            0
        } else {
            (limitSec - usedSecondsTodayState).coerceAtLeast(0)
        }

        val statuses = CoreTargetApp.entries.map { target ->
            val appUsed = usedSecondsTodayState.coerceAtLeast(
                perAppUsedSecondsCache[target.canonicalKey] ?: 0
            )
            val appRemaining = if (lockedNow) 0 else (limitSec - appUsed).coerceAtLeast(0)
            CoreAppQuotaStatus(
                target = target,
                usedSeconds = appUsed,
                limitSeconds = limitSec,
                remainingSeconds = appRemaining,
                isLocked = lockedNow || appRemaining <= 0
            )
        }

        _quotaSnapshotFlow.value = RealtimeQuotaSnapshot(
            dateString = currentTrackedDate,
            isTestModeActive = isTestMode,
            activeLimitSeconds = limitSec,
            usedSecondsToday = usedSecondsTodayState,
            remainingUsageSeconds = remainingUsageSec,
            isLockedNow = lockedNow,
            lockedUntilEpochMs = isLockedUntilEpochMsState,
            lockRemainingMillis = lockRemainingMs,
            coreStatuses = statuses,
            headlineRemainingSeconds = remainingUsageSec
        )
    }

    companion object {
        const val PREFS_NAME = "iron_discipline_prefs"
        const val KEY_TRACKED_DATE = "tracked_date_yyyy_mm_dd"
        const val KEY_IS_TEST_MODE = "is_one_minute_test_mode"
        const val KEY_USED_SECONDS_TODAY = "used_seconds_today"
        const val KEY_IS_LOCKED_UNTIL_EPOCH_MS = "is_locked_until_epoch_ms"
        private const val PREFIX_USED_SEC = "used_sec_"

        const val NORMAL_MODE_LIMIT_SECONDS = 1800 // 30 minutes
        const val TEST_MODE_LIMIT_SECONDS = 60     // 1 minute
        const val TEST_MODE_LOCK_DURATION_MS = 30 * 60 * 1000L // 30 minutes lock in Test Mode

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
    }
}
