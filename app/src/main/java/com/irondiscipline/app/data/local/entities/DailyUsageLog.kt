package com.irondiscipline.app.data.local.entities

import androidx.room.Entity

@Entity(
    tableName = "daily_usage_logs",
    primaryKeys = ["dateString", "packageName"]
)
data class DailyUsageLog(
    val dateString: String, // Format: YYYY-MM-DD
    val packageName: String,
    val usedSeconds: Long = 0L,
    val blockedAttemptsCount: Int = 0,
    val lastUpdatedEpochMs: Long = System.currentTimeMillis()
)
