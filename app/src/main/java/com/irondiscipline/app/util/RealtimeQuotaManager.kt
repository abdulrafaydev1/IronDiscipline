package com.irondiscipline.app.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.util.Locale

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
            val lower = packageName.lowercase().trim()
            return entries.firstOrNull { target ->
                target.packageNames.any { it.equals(lower, ignoreCase = true) }
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
 * Clean, thread-safe Reader & Snapshot publisher for "iron_discipline_prefs".
 * AppBlockerAccessibilityService is the ONLY writer of used_seconds_<appKey> during active usage,
 * guaranteeing zero race conditions, zero fluctuations, and strictly monotonic countdowns.
 */
class RealtimeQuotaManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _quotaSnapshotFlow = MutableStateFlow(RealtimeQuotaSnapshot())
    val quotaSnapshotFlow: StateFlow<RealtimeQuotaSnapshot> = _quotaSnapshotFlow.asStateFlow()

    init {
        publishSnapshot()
    }

    fun isTestModeActive(): Boolean {
        return prefs.getBoolean(KEY_IS_TEST_MODE, false) || prefs.getBoolean("is_test_mode", false)
    }

    fun getAppLimitSeconds(appKey: String): Int {
        return if (isTestModeActive()) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS
    }

    fun getUsedSeconds(appKey: String): Int {
        return prefs.getInt(PREFIX_USED_SEC + appKey, 0).coerceAtLeast(0)
    }

    fun getUsedSecondsToday(appKeyOrPackage: String): Int {
        val appKey = CoreTargetApp.fromPackageName(appKeyOrPackage)?.appKey ?: appKeyOrPackage
        return getUsedSeconds(appKey)
    }

    fun getLockedUntilEpochMs(appKey: String): Long {
        return prefs.getLong(PREFIX_LOCKED_UNTIL + appKey, 0L)
    }

    fun getRemainingLockSeconds(appKey: String): Int {
        return prefs.getInt(PREFIX_REMAINING_LOCK_SEC + appKey, 0).coerceAtLeast(0)
    }

    fun isAppLocked(appKey: String): Boolean {
        val remainingLockSec = getRemainingLockSeconds(appKey)
        val used = getUsedSeconds(appKey)
        val limit = getAppLimitSeconds(appKey)
        return remainingLockSec > 0 || used >= limit
    }

    fun isAppLocked(appKey: String, nowEpochMs: Long): Boolean {
        return isAppLocked(appKey)
    }

    fun isAnyAppLocked(): Boolean {
        return CoreTargetApp.entries.any { isAppLocked(it.appKey) }
    }

    fun incrementUsedSecond(appKey: String, nowEpochMs: Long = System.currentTimeMillis()): Int {
        return getUsedSecondsToday(appKey)
    }

    /**
     * Publishes latest SharedPreferences state to the UI StateFlow.
     * Guaranteed read-only; never mutates or fluctuates used_seconds.
     */
    @Synchronized
    fun publishSnapshot() {
        val isTestMode = isTestModeActive()
        val limit = if (isTestMode) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS

        val statuses = CoreTargetApp.entries.map { target ->
            val key = target.appKey
            val used = prefs.getInt(PREFIX_USED_SEC + key, 0).coerceAtLeast(0)
            val remainingLockSec = prefs.getInt(PREFIX_REMAINING_LOCK_SEC + key, 0).coerceAtLeast(0)
            val lockedUntil = prefs.getLong(PREFIX_LOCKED_UNTIL + key, 0L)
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
            dateString = LocalDate.now().toString(),
            isTestModeActive = isTestMode,
            coreStatuses = statuses
        )
    }

    fun refreshSnapshotNow(nowEpochMs: Long = System.currentTimeMillis()) {
        publishSnapshot()
    }

    fun checkMidnightResetIfNeeded(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        publishSnapshot()
        return false
    }

    /**
     * Activated when the user toggles 1-Minute Test Mode on the Dashboard.
     * Blocked if ANY app is locked for 5 hours.
     */
    @Synchronized
    fun activateGlobalOneMinuteTestMode() {
        if (isAnyAppLocked()) return

        val editor = prefs.edit()
            .putBoolean(KEY_IS_TEST_MODE, true)
            .putBoolean("is_test_mode", true)

        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            editor.putInt(PREFIX_LIMIT_SEC + key, TEST_MODE_LIMIT_SECONDS)
            editor.putInt(PREFIX_USED_SEC + key, 0)
            editor.putLong(PREFIX_LOCKED_UNTIL + key, 0L)
            editor.putInt(PREFIX_REMAINING_LOCK_SEC + key, 0)
        }
        editor.commit()
        publishSnapshot()
    }

    /**
     * Activated when the user switches to 30-Minute Normal Mode on the Dashboard.
     * Blocked if ANY app is locked for 5 hours.
     */
    @Synchronized
    fun switchBackToNormalThirtyMinModeAndReset() {
        if (isAnyAppLocked()) return

        val editor = prefs.edit()
            .putBoolean(KEY_IS_TEST_MODE, false)
            .putBoolean("is_test_mode", false)

        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            editor.putInt(PREFIX_LIMIT_SEC + key, NORMAL_MODE_LIMIT_SECONDS)
            editor.putInt(PREFIX_USED_SEC + key, 0)
            editor.putLong(PREFIX_LOCKED_UNTIL + key, 0L)
            editor.putInt(PREFIX_REMAINING_LOCK_SEC + key, 0)
        }
        editor.commit()
        publishSnapshot()
    }

    /**
     * Reset All Timers & Locks (Blocked while any 5-hour lock is active).
     */
    @Synchronized
    fun resetAllTimersAndLocks() {
        if (isAnyAppLocked()) return

        val editor = prefs.edit()
        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            editor.putInt(PREFIX_USED_SEC + key, 0)
            editor.putLong(PREFIX_LOCKED_UNTIL + key, 0L)
            editor.putInt(PREFIX_REMAINING_LOCK_SEC + key, 0)
        }
        editor.commit()
        publishSnapshot()
    }

    @Synchronized
    fun resetAppTimerAndLock(appKey: String) {
        if (getRemainingLockSeconds(appKey) > 0) return

        val isTest = isTestModeActive()
        val limit = if (isTest) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS
        prefs.edit()
            .putInt(PREFIX_USED_SEC + appKey, 0)
            .putInt(PREFIX_LIMIT_SEC + appKey, limit)
            .putLong(PREFIX_LOCKED_UNTIL + appKey, 0L)
            .putInt(PREFIX_REMAINING_LOCK_SEC + appKey, 0)
            .commit()
        publishSnapshot()
    }

    @Synchronized
    fun setAppLimitMinutes(appKey: String, limitMinutes: Int) {
        if (getRemainingLockSeconds(appKey) > 0) return
        val limitSec = (limitMinutes.coerceAtLeast(1)) * 60
        prefs.edit().putInt(PREFIX_LIMIT_SEC + appKey, limitSec).commit()
        publishSnapshot()
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
        const val TEST_MODE_LOCK_DURATION_MS = FIVE_HOURS_MS

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
