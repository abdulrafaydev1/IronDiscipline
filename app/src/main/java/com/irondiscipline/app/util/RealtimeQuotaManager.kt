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
    val lockRemainingMillis: Long
) {
    val formattedRemainingMmSs: String
        get() = RealtimeQuotaManager.formatSecondsToMmSs(remainingSeconds)

    val formattedLimitMinutes: String
        get() = "${limitSeconds / 60}m"

    val formattedLockRemainingHhMmSs: String
        get() = RealtimeQuotaManager.formatMillisToHhMmSs(lockRemainingMillis)

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
        get() = coreStatuses.any { it.isLocked }

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
 * - is_one_minute_test_mode
 * - tracked_date_yyyy_mm_dd
 * Ensures independent timers and independent locks for YouTube, Facebook, Instagram, and TikTok.
 */
class RealtimeQuotaManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val usedSecondsCache = ConcurrentHashMap<String, Int>()
    private val limitSecondsCache = ConcurrentHashMap<String, Int>()
    private val lockedUntilCache = ConcurrentHashMap<String, Long>()

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

        val defaultLimit = if (isTestMode) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS

        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            val used = prefs.getInt(PREFIX_USED_SEC + key, 0)
            val limit = prefs.getInt(PREFIX_LIMIT_SEC + key, defaultLimit)
            val lockedUntil = prefs.getLong(PREFIX_LOCKED_UNTIL + key, 0L)

            usedSecondsCache[key] = used
            limitSecondsCache[key] = limit
            lockedUntilCache[key] = lockedUntil
        }
    }

    /**
     * Checks if any app's lock timer has expired or if midnight rollover has occurred.
     */
    @Synchronized
    fun checkExpiryAndMidnightRollover(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        var changed = false
        val today = LocalDate.now().toString()
        val isNewDay = today != currentTrackedDate

        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            val lockedUntil = lockedUntilCache[key] ?: 0L

            // Lock has expired
            if (lockedUntil > 0L && nowEpochMs >= lockedUntil) {
                lockedUntilCache[key] = 0L
                usedSecondsCache[key] = 0
                changed = true
            }

            // Midnight rollover (and app not locked into the next day)
            if (isNewDay && (lockedUntilCache[key] ?: 0L) <= nowEpochMs) {
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
        val defaultLimit = if (isTestMode) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS
        return limitSecondsCache[appKey] ?: defaultLimit
    }

    fun getUsedSeconds(appKey: String): Int {
        checkExpiryAndMidnightRollover()
        return usedSecondsCache[appKey] ?: 0
    }

    fun getLockedUntilEpochMs(appKey: String): Long {
        checkExpiryAndMidnightRollover()
        return lockedUntilCache[appKey] ?: 0L
    }

    /**
     * Returns true if the specific target app is currently locked.
     * Checks strictly System.currentTimeMillis() < locked_until_epoch_ms_<appKey>.
     */
    @Synchronized
    fun isAppLocked(appKey: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        checkExpiryAndMidnightRollover(nowEpochMs)
        val lockedUntil = lockedUntilCache[appKey] ?: 0L
        if (lockedUntil > nowEpochMs) {
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
     * If limit is reached, locks ONLY this specific app.
     */
    @Synchronized
    fun incrementUsedSecond(appKey: String, nowEpochMs: Long = System.currentTimeMillis()): Int {
        checkExpiryAndMidnightRollover(nowEpochMs)

        val lockedUntil = lockedUntilCache[appKey] ?: 0L
        if (lockedUntil > nowEpochMs) {
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
     * Locks ONLY the specified appKey.
     */
    @Synchronized
    fun engageLockForApp(appKey: String, nowEpochMs: Long = System.currentTimeMillis()) {
        engageLockForAppInternal(appKey, nowEpochMs)
        publishSnapshot(nowEpochMs)
    }

    private fun engageLockForAppInternal(appKey: String, nowEpochMs: Long) {
        val currentLocked = lockedUntilCache[appKey] ?: 0L
        if (currentLocked > nowEpochMs) return

        val lockEndMs = if (isTestMode) {
            nowEpochMs + TEST_MODE_LOCK_DURATION_MS
        } else {
            val zoneId = ZoneId.systemDefault()
            val nextMidnightMs = LocalDate.now(zoneId)
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant()
                .toEpochMilli()
            if (nextMidnightMs - nowEpochMs > 60_000L) nextMidnightMs else nowEpochMs + 24 * 3600_000L
        }

        lockedUntilCache[appKey] = lockEndMs
        prefs.edit()
            .putLong(PREFIX_LOCKED_UNTIL + appKey, lockEndMs)
            .putInt(PREFIX_USED_SEC + appKey, usedSecondsCache[appKey] ?: getAppLimitSeconds(appKey))
            .commit()
    }

    /**
     * Configures custom limit minutes for a specific target app.
     */
    @Synchronized
    fun setAppLimitMinutes(appKey: String, limitMinutes: Int) {
        val limitSec = (limitMinutes.coerceAtLeast(1)) * 60
        limitSecondsCache[appKey] = limitSec
        prefs.edit().putInt(PREFIX_LIMIT_SEC + appKey, limitSec).commit()

        val nowMs = System.currentTimeMillis()
        val used = usedSecondsCache[appKey] ?: 0
        if (used < limitSec && (lockedUntilCache[appKey] ?: 0L) > 0L) {
            // If new limit is higher than used, unlock app
            lockedUntilCache[appKey] = 0L
            prefs.edit().putLong(PREFIX_LOCKED_UNTIL + appKey, 0L).commit()
        } else if (used >= limitSec && (lockedUntilCache[appKey] ?: 0L) <= nowMs) {
            engageLockForAppInternal(appKey, nowMs)
        }

        publishSnapshot()
    }

    /**
     * Resets used seconds and active lock for a SINGLE specific app.
     */
    @Synchronized
    fun resetAppTimerAndLock(appKey: String) {
        usedSecondsCache[appKey] = 0
        lockedUntilCache[appKey] = 0L
        prefs.edit()
            .putInt(PREFIX_USED_SEC + appKey, 0)
            .putLong(PREFIX_LOCKED_UNTIL + appKey, 0L)
            .commit()
        publishSnapshot()
    }

    /**
     * Global 1-Min Test Mode: switches limit for all 4 apps to 60s and resets all timers and locks.
     */
    @Synchronized
    fun activateGlobalOneMinuteTestMode() {
        currentTrackedDate = LocalDate.now().toString()
        isTestMode = true
        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            limitSecondsCache[key] = TEST_MODE_LIMIT_SECONDS
            usedSecondsCache[key] = 0
            lockedUntilCache[key] = 0L
        }
        saveAllToPrefsSynchronously()
        publishSnapshot()
    }

    /**
     * Global 30-Min Normal Mode: switches limit for all 4 apps to 1800s and resets all timers and locks.
     */
    @Synchronized
    fun switchBackToNormalThirtyMinModeAndReset() {
        currentTrackedDate = LocalDate.now().toString()
        isTestMode = false
        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            limitSecondsCache[key] = NORMAL_MODE_LIMIT_SECONDS
            usedSecondsCache[key] = 0
            lockedUntilCache[key] = 0L
        }
        saveAllToPrefsSynchronously()
        publishSnapshot()
    }

    /**
     * Reset All Timers & Locks: resets all apps' used seconds to 0 and unlocks them.
     */
    @Synchronized
    fun resetAllTimersAndLocks() {
        currentTrackedDate = LocalDate.now().toString()
        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            usedSecondsCache[key] = 0
            lockedUntilCache[key] = 0L
        }
        saveAllToPrefsSynchronously()
        publishSnapshot()
    }

    private fun saveAllToPrefsSynchronously() {
        val editor = prefs.edit()
            .putString(KEY_TRACKED_DATE, currentTrackedDate)
            .putBoolean(KEY_IS_TEST_MODE, isTestMode)

        for (target in CoreTargetApp.entries) {
            val key = target.appKey
            editor.putInt(PREFIX_USED_SEC + key, usedSecondsCache[key] ?: 0)
            editor.putInt(PREFIX_LIMIT_SEC + key, limitSecondsCache[key] ?: NORMAL_MODE_LIMIT_SECONDS)
            editor.putLong(PREFIX_LOCKED_UNTIL + key, lockedUntilCache[key] ?: 0L)
        }
        editor.commit()
    }

    fun refreshSnapshotNow(nowEpochMs: Long = System.currentTimeMillis()) {
        checkExpiryAndMidnightRollover(nowEpochMs)
        publishSnapshot(nowEpochMs)
    }

    private fun publishSnapshot(nowEpochMs: Long = System.currentTimeMillis()) {
        val statuses = CoreTargetApp.entries.map { target ->
            val key = target.appKey
            val used = (usedSecondsCache[key] ?: 0).coerceAtLeast(0)
            val limit = getAppLimitSeconds(key)
            val lockedUntil = lockedUntilCache[key] ?: 0L
            val isLocked = lockedUntil > nowEpochMs || used >= limit
            val remainingSec = if (isLocked) 0 else (limit - used).coerceAtLeast(0)
            val lockRemainingMs = if (isLocked && lockedUntil > nowEpochMs) {
                lockedUntil - nowEpochMs
            } else if (isLocked) {
                TEST_MODE_LOCK_DURATION_MS
            } else 0L

            CoreAppQuotaStatus(
                target = target,
                usedSeconds = used,
                limitSeconds = limit,
                remainingSeconds = remainingSec,
                isLocked = isLocked,
                lockedUntilEpochMs = lockedUntil,
                lockRemainingMillis = lockRemainingMs
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
