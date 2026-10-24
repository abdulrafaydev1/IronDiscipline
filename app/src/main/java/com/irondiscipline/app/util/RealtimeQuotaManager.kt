package com.irondiscipline.app.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
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
        packageNames = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
    );

    companion object {
        fun fromPackageName(packageName: String): CoreTargetApp? {
            return entries.firstOrNull { target ->
                target.packageNames.any { it.equals(packageName, ignoreCase = true) }
            }
        }

        fun canonicalKeyForPackage(packageName: String): String {
            return fromPackageName(packageName)?.canonicalKey ?: packageName
        }

        fun isCoreTargetPackage(packageName: String): Boolean {
            return fromPackageName(packageName) != null
        }
    }
}

data class CoreAppQuotaStatus(
    val target: CoreTargetApp,
    val usedSeconds: Int,
    val limitSeconds: Int,
    val remainingSeconds: Int
) {
    val formattedRemainingMmSs: String
        get() = RealtimeQuotaManager.formatSecondsToMmSs(remainingSeconds)

    val formattedUsedMmSs: String
        get() = RealtimeQuotaManager.formatSecondsToMmSs(usedSeconds)

    val progressFraction: Float
        get() = if (limitSeconds > 0) {
            (usedSeconds.toFloat() / limitSeconds.toFloat()).coerceIn(0f, 1f)
        } else 1f
}

data class RealtimeQuotaSnapshot(
    val dateString: String = LocalDate.now().toString(),
    val isTestModeActive: Boolean = false,
    val activeLimitSeconds: Int = RealtimeQuotaManager.NORMAL_MODE_LIMIT_SECONDS,
    val coreStatuses: List<CoreAppQuotaStatus> = emptyList(),
    val headlineRemainingSeconds: Int = RealtimeQuotaManager.NORMAL_MODE_LIMIT_SECONDS
) {
    val formattedHeadlineRemainingMmSs: String
        get() = RealtimeQuotaManager.formatSecondsToMmSs(headlineRemainingSeconds)
}

/**
 * Zero-latency SharedPreferences + StateFlow quota engine that powers the 1-second
 * foreground countdown ticker in AppBlockerAccessibilityService and live MM:SS updates on DashboardScreen.
 */
class RealtimeQuotaManager private constructor(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val usedSecondsCache = ConcurrentHashMap<String, Int>()

    @Volatile
    private var currentTrackedDate: String = LocalDate.now().toString()

    @Volatile
    private var isTestMode: Boolean = false

    private val _quotaSnapshotFlow = MutableStateFlow(RealtimeQuotaSnapshot())
    val quotaSnapshotFlow: StateFlow<RealtimeQuotaSnapshot> = _quotaSnapshotFlow.asStateFlow()

    init {
        loadFromPrefs()
        checkMidnightResetIfNeeded()
        publishSnapshot()
    }

    @Synchronized
    private fun loadFromPrefs() {
        val savedDate = prefs.getString(KEY_TRACKED_DATE, null)
        val today = LocalDate.now().toString()
        isTestMode = prefs.getBoolean(KEY_IS_TEST_MODE, false)

        if (savedDate == null || savedDate != today) {
            currentTrackedDate = today
            resetAllCountersInternal(keepTestMode = isTestMode)
        } else {
            currentTrackedDate = savedDate
            usedSecondsCache.clear()
            for ((key, value) in prefs.all) {
                if (key.startsWith(PREFIX_USED_SEC) && value is Int) {
                    val canonicalPkg = key.removePrefix(PREFIX_USED_SEC)
                    usedSecondsCache[canonicalPkg] = value
                }
            }
        }
    }

    /**
     * Automatically resets usedSecondsToday to 0 at midnight (00:00) each day.
     */
    @Synchronized
    fun checkMidnightResetIfNeeded(): Boolean {
        val today = LocalDate.now().toString()
        if (today != currentTrackedDate) {
            currentTrackedDate = today
            resetAllCountersInternal(keepTestMode = isTestMode)
            publishSnapshot()
            return true
        }
        return false
    }

    fun isTestModeActive(): Boolean {
        checkMidnightResetIfNeeded()
        return isTestMode
    }

    fun getActiveLimitSecondsForCoreTargets(): Int {
        return if (isTestModeActive()) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS
    }

    fun getActiveLimitSecondsForPackage(packageName: String, ruleQuotaMinutes: Int): Int {
        if (isTestModeActive() && CoreTargetApp.isCoreTargetPackage(packageName)) {
            return TEST_MODE_LIMIT_SECONDS
        }
        if (CoreTargetApp.isCoreTargetPackage(packageName) && ruleQuotaMinutes == 30) {
            return NORMAL_MODE_LIMIT_SECONDS
        }
        return (ruleQuotaMinutes.coerceAtLeast(0)) * 60
    }

    fun getUsedSecondsToday(packageName: String): Int {
        checkMidnightResetIfNeeded()
        val canonicalKey = CoreTargetApp.canonicalKeyForPackage(packageName)
        return usedSecondsCache[canonicalKey] ?: 0
    }

    /**
     * Called every 1,000ms (1 second) by AppBlockerAccessibilityService when a monitored app is in the foreground.
     */
    @Synchronized
    fun incrementUsedSecond(packageName: String): Int {
        checkMidnightResetIfNeeded()
        val canonicalKey = CoreTargetApp.canonicalKeyForPackage(packageName)
        val nextValue = (usedSecondsCache[canonicalKey] ?: 0) + 1
        usedSecondsCache[canonicalKey] = nextValue

        prefs.edit()
            .putString(KEY_TRACKED_DATE, currentTrackedDate)
            .putInt(PREFIX_USED_SEC + canonicalKey, nextValue)
            .apply()

        publishSnapshot()
        return nextValue
    }

    /**
     * Switches the limit for YouTube, Facebook, Instagram, and TikTok to 1 minute (60s)
     * and resets the usage counters to 0 seconds for immediate live testing.
     */
    @Synchronized
    fun activateOneMinuteTestMode() {
        currentTrackedDate = LocalDate.now().toString()
        isTestMode = true
        resetAllCountersInternal(keepTestMode = true)
        publishSnapshot()
    }

    /**
     * Switches back to the 30-Minute (1,800s) Normal Mode limit and resets counters to 0 seconds.
     */
    @Synchronized
    fun switchBackToNormalThirtyMinModeAndReset() {
        currentTrackedDate = LocalDate.now().toString()
        isTestMode = false
        resetAllCountersInternal(keepTestMode = false)
        publishSnapshot()
    }

    @Synchronized
    private fun resetAllCountersInternal(keepTestMode: Boolean) {
        usedSecondsCache.clear()
        val editor = prefs.edit()
        editor.clear()
        editor.putString(KEY_TRACKED_DATE, currentTrackedDate)
        editor.putBoolean(KEY_IS_TEST_MODE, keepTestMode)
        for (target in CoreTargetApp.entries) {
            usedSecondsCache[target.canonicalKey] = 0
            editor.putInt(PREFIX_USED_SEC + target.canonicalKey, 0)
        }
        editor.apply()
    }

    private fun publishSnapshot() {
        val limitSec = if (isTestMode) TEST_MODE_LIMIT_SECONDS else NORMAL_MODE_LIMIT_SECONDS
        val statuses = CoreTargetApp.entries.map { target ->
            val used = (usedSecondsCache[target.canonicalKey] ?: 0).coerceAtLeast(0)
            val remaining = (limitSec - used).coerceAtLeast(0)
            CoreAppQuotaStatus(
                target = target,
                usedSeconds = used,
                limitSeconds = limitSec,
                remainingSeconds = remaining
            )
        }
        // Show the most-used core target's remaining time as the headline countdown
        val minRemaining = statuses.minOfOrNull { it.remainingSeconds } ?: limitSec

        _quotaSnapshotFlow.value = RealtimeQuotaSnapshot(
            dateString = currentTrackedDate,
            isTestModeActive = isTestMode,
            activeLimitSeconds = limitSec,
            coreStatuses = statuses,
            headlineRemainingSeconds = minRemaining
        )
    }

    companion object {
        private const val PREFS_NAME = "iron_discipline_realtime_quota_prefs"
        private const val KEY_TRACKED_DATE = "tracked_date_yyyy_mm_dd"
        private const val KEY_IS_TEST_MODE = "is_one_minute_test_mode"
        private const val PREFIX_USED_SEC = "used_sec_"

        const val NORMAL_MODE_LIMIT_SECONDS = 1800 // 30 minutes
        const val TEST_MODE_LIMIT_SECONDS = 60     // 1 minute

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
    }
}
