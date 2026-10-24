package com.irondiscipline.app.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.irondiscipline.app.data.local.entities.AppBlockRule
import com.irondiscipline.app.data.local.entities.DailyUsageLog
import com.irondiscipline.app.data.local.entities.DeepWorkSession
import com.irondiscipline.app.data.local.entities.DisciplineStateEntity
import com.irondiscipline.app.data.local.entities.ScheduleBlock
import kotlinx.coroutines.flow.Flow

@Dao
interface DisciplineDao {

    // --- App Block Rules ---
    @Query("SELECT * FROM app_block_rules ORDER BY isHighRisk DESC, isBlocked DESC, appName ASC")
    fun observeAllAppRules(): Flow<List<AppBlockRule>>

    @Query("SELECT * FROM app_block_rules")
    suspend fun getAllAppRulesOnce(): List<AppBlockRule>

    @Query("SELECT * FROM app_block_rules WHERE packageName = :packageName LIMIT 1")
    suspend fun getAppRule(packageName: String): AppBlockRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppRule(rule: AppBlockRule)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAppRulesIgnoreExisting(rules: List<AppBlockRule>)

    @Delete
    suspend fun deleteAppRule(rule: AppBlockRule)

    // --- Schedule Blocks ---
    @Query("SELECT * FROM schedule_blocks ORDER BY startHour ASC, startMinute ASC")
    fun observeAllSchedules(): Flow<List<ScheduleBlock>>

    @Query("SELECT * FROM schedule_blocks")
    suspend fun getAllSchedulesOnce(): List<ScheduleBlock>

    @Query("SELECT * FROM schedule_blocks WHERE isEnabled = 1")
    suspend fun getEnabledSchedulesOnce(): List<ScheduleBlock>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSchedule(schedule: ScheduleBlock): Long

    @Update
    suspend fun updateSchedule(schedule: ScheduleBlock)

    @Delete
    suspend fun deleteSchedule(schedule: ScheduleBlock)

    // --- Deep Work Sessions ---
    @Query("SELECT * FROM deep_work_sessions ORDER BY completedAtEpochMs DESC")
    fun observeAllDeepWorkSessions(): Flow<List<DeepWorkSession>>

    @Query("SELECT * FROM deep_work_sessions WHERE dateString = :dateString ORDER BY completedAtEpochMs DESC")
    fun observeDeepWorkSessionsForDate(dateString: String): Flow<List<DeepWorkSession>>

    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM deep_work_sessions WHERE dateString = :dateString")
    fun observeTotalDeepWorkMinutesForDate(dateString: String): Flow<Int>

    @Query("SELECT COALESCE(SUM(durationMinutes), 0) FROM deep_work_sessions WHERE dateString = :dateString")
    suspend fun getTotalDeepWorkMinutesForDateOnce(dateString: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDeepWorkSession(session: DeepWorkSession): Long

    // --- Daily Usage Logs ---
    @Query("SELECT * FROM daily_usage_logs WHERE dateString = :dateString")
    fun observeUsageLogsForDate(dateString: String): Flow<List<DailyUsageLog>>

    @Query("SELECT * FROM daily_usage_logs WHERE dateString = :dateString")
    suspend fun getUsageLogsForDateOnce(dateString: String): List<DailyUsageLog>

    @Query("SELECT * FROM daily_usage_logs WHERE dateString = :dateString AND packageName = :packageName LIMIT 1")
    suspend fun getUsageLogForPackage(dateString: String, packageName: String): DailyUsageLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUsageLog(log: DailyUsageLog)

    @Query("UPDATE daily_usage_logs SET usedSeconds = 0 WHERE dateString = :dateString")
    suspend fun resetUsedSecondsForDate(dateString: String)

    // --- Discipline State (Streak, Strict Mode, Emergency Unlock, Custom Quote) ---
    @Query("SELECT * FROM discipline_state WHERE id = 1 LIMIT 1")
    fun observeDisciplineState(): Flow<DisciplineStateEntity?>

    @Query("SELECT * FROM discipline_state WHERE id = 1 LIMIT 1")
    suspend fun getDisciplineStateOnce(): DisciplineStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDisciplineState(state: DisciplineStateEntity)
}
