package com.irondiscipline.app.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "deep_work_sessions")
data class DeepWorkSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val dateString: String, // Format: YYYY-MM-DD
    val focusTitle: String,
    val durationMinutes: Int,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long,
    val wasCompletedFully: Boolean = true
)
