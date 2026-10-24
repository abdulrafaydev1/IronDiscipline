package com.irondiscipline.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime
import java.util.Locale

@Entity(tableName = "schedule_blocks")
data class ScheduleBlock(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val startHour: Int,
    val startMinute: Int,
    val endHour: Int,
    val endMinute: Int,
    /**
     * Comma-separated ISO-8601 day numbers: 1 (Monday) through 7 (Sunday).
     * Example: "1,2,3,4,5" for Weekdays, "1,2,3,4,5,6,7" for Every Day.
     */
    val daysOfWeekCsv: String = "1,2,3,4,5,6,7",
    /**
     * "*" applies globally to all targeted apps (where isBlocked == true && blockDuringSchedules == true).
     * Or a comma-separated list of specific package names.
     */
    val targetPackagesCsv: String = "*",
    val isEnabled: Boolean = true,
    val enforcesStrictMode: Boolean = false
) {
    fun getActiveDaysSet(): Set<Int> {
        return daysOfWeekCsv
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .toSet()
    }

    fun appliesToPackage(packageName: String): Boolean {
        if (targetPackagesCsv.trim() == "*" || targetPackagesCsv.isBlank()) return true
        return targetPackagesCsv
            .split(",")
            .map { it.trim() }
            .contains(packageName)
    }

    fun isActiveNow(now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (!isEnabled) return false
        val activeDays = getActiveDaysSet()
        if (activeDays.isEmpty()) return false

        val currentMinutes = now.hour * 60 + now.minute
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute
        val todayIso = now.dayOfWeek.value // 1 (Mon) .. 7 (Sun)

        return if (startMinutes <= endMinutes) {
            todayIso in activeDays && currentMinutes in startMinutes until endMinutes
        } else {
            // Overnight schedule crossing midnight (e.g., 22:00 -> 07:00)
            if (currentMinutes >= startMinutes) {
                todayIso in activeDays
            } else if (currentMinutes < endMinutes) {
                val yesterdayIso = now.minusDays(1).dayOfWeek.value
                yesterdayIso in activeDays || todayIso in activeDays
            } else {
                false
            }
        }
    }

    fun formattedTimeRange(): String {
        return String.format(
            Locale.US,
            "%02d:%02d - %02d:%02d",
            startHour,
            startMinute,
            endHour,
            endMinute
        )
    }

    fun formattedDaysSummary(): String {
        val days = getActiveDaysSet()
        if (days.size == 7) return "Every Day"
        if (days == setOf(1, 2, 3, 4, 5)) return "Weekdays (Mon-Fri)"
        if (days == setOf(6, 7)) return "Weekends (Sat-Sun)"
        val labels = mapOf(
            1 to "Mon",
            2 to "Tue",
            3 to "Wed",
            4 to "Thu",
            5 to "Fri",
            6 to "Sat",
            7 to "Sun"
        )
        return days.sorted().mapNotNull { labels[it] }.joinToString(", ")
    }
}
