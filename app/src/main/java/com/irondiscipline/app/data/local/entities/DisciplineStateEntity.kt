package com.irondiscipline.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "discipline_state")
data class DisciplineStateEntity(
    @PrimaryKey
    val id: Int = 1,
    val isStrictModeActive: Boolean = false,
    val strictModeEndTimeEpochMs: Long = 0L,
    val allowEmergencyUnlockInStrictMode: Boolean = true,
    val lastEmergencyUnlockDate: String = "",
    val emergencyUnlockTarget: String = "",
    val emergencyUnlockUntilEpochMs: Long = 0L,
    val currentStreakDays: Int = 1,
    val longestStreakDays: Int = 1,
    val lastActiveDate: String = "",
    val lastRuleBrokenDate: String = "",
    val customQuote: String = DEFAULT_MOTIVATIONAL_QUOTE,
    val baselineDailyDistractionMinutes: Int = 180
) {
    fun isStrictLockedNow(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        return isStrictModeActive && strictModeEndTimeEpochMs > nowEpochMs
    }

    fun remainingStrictModeMillis(nowEpochMs: Long = System.currentTimeMillis()): Long {
        if (!isStrictModeActive) return 0L
        return (strictModeEndTimeEpochMs - nowEpochMs).coerceAtLeast(0L)
    }

    fun isTargetInEmergencyUnlock(
        targetKey: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (emergencyUnlockUntilEpochMs <= nowEpochMs) return false
        return emergencyUnlockTarget == "*" ||
            emergencyUnlockTarget.equals(targetKey, ignoreCase = true)
    }

    fun canUseEmergencyUnlockToday(
        todayDateString: String,
        scheduleEnforcesStrict: Boolean
    ): Boolean {
        val strictNow = isStrictLockedNow() || scheduleEnforcesStrict
        if (strictNow && !allowEmergencyUnlockInStrictMode) {
            return false
        }
        return lastEmergencyUnlockDate != todayDateString
    }

    companion object {
        const val DEFAULT_MOTIVATIONAL_QUOTE =
            "Remember Why You Started: Time is non-refundable. Get back to building your future."
    }
}
