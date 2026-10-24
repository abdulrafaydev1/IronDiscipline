package com.irondiscipline.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalTime
import java.util.Locale

@Entity(tableName = "app_block_rules")
data class AppBlockRule(
    @PrimaryKey
    val packageName: String,
    val appName: String,
    val isHighRisk: Boolean = false,
    val isBlocked: Boolean = false,
    val dailyQuotaMinutes: Int = 15,
    val blockDuringSchedules: Boolean = true,
    val hasCustomSchedule: Boolean = false,
    val customStartHour: Int = 9,
    val customStartMinute: Int = 0,
    val customEndHour: Int = 18,
    val customEndMinute: Int = 0,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun isWithinCustomSchedule(now: LocalTime = LocalTime.now()): Boolean {
        if (!hasCustomSchedule) return false
        val currentMinutes = now.hour * 60 + now.minute
        val startMinutes = customStartHour * 60 + customStartMinute
        val endMinutes = customEndHour * 60 + customEndMinute

        return if (startMinutes <= endMinutes) {
            currentMinutes in startMinutes until endMinutes
        } else {
            // Overnight schedule (e.g., 22:00 to 07:00)
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    fun formattedCustomSchedule(): String {
        return String.format(
            Locale.US,
            "%02d:%02d - %02d:%02d",
            customStartHour,
            customStartMinute,
            customEndHour,
            customEndMinute
        )
    }

    companion object {
        val HIGH_RISK_TARGETS: Map<String, String> = linkedMapOf(
            "com.instagram.android" to "Instagram",
            "com.google.android.youtube" to "YouTube",
            "com.zhiliaoapp.musically" to "TikTok",
            "com.ss.android.ugc.trill" to "TikTok (Regional)",
            "com.facebook.katana" to "Facebook"
        )

        val SOCIAL_DOMAIN_TO_PACKAGES: Map<String, List<String>> = mapOf(
            "instagram.com" to listOf("com.instagram.android"),
            "youtube.com" to listOf("com.google.android.youtube"),
            "youtu.be" to listOf("com.google.android.youtube"),
            "tiktok.com" to listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"),
            "facebook.com" to listOf("com.facebook.katana"),
            "fb.com" to listOf("com.facebook.katana")
        )
    }
}
