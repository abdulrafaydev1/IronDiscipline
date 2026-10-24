package com.irondiscipline.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.irondiscipline.app.data.local.entities.AppBlockRule
import com.irondiscipline.app.data.local.entities.DailyUsageLog
import com.irondiscipline.app.data.local.entities.DeepWorkSession
import com.irondiscipline.app.data.local.entities.DisciplineStateEntity
import com.irondiscipline.app.data.local.entities.ScheduleBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

@Database(
    entities = [
        AppBlockRule::class,
        ScheduleBlock::class,
        DeepWorkSession::class,
        DailyUsageLog::class,
        DisciplineStateEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class IronDisciplineDatabase : RoomDatabase() {

    abstract fun disciplineDao(): DisciplineDao

    companion object {
        @Volatile
        private var INSTANCE: IronDisciplineDatabase? = null
        private val dbScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun getInstance(context: Context): IronDisciplineDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    IronDisciplineDatabase::class.java,
                    "iron_discipline.db"
                )
                    .fallbackToDestructiveMigration()
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            dbScope.launch {
                                INSTANCE?.let { database ->
                                    seedInitialData(database.disciplineDao())
                                }
                            }
                        }
                    })
                    .build()
                INSTANCE = instance
                dbScope.launch {
                    ensureSeeded(instance.disciplineDao())
                }
                instance
            }
        }

        private suspend fun ensureSeeded(dao: DisciplineDao) {
            seedInitialData(dao)
        }

        private suspend fun seedInitialData(dao: DisciplineDao) {
            val today = LocalDate.now().toString()

            // 1. Seed default singleton DisciplineStateEntity
            if (dao.getDisciplineStateOnce() == null) {
                dao.upsertDisciplineState(
                    DisciplineStateEntity(
                        id = 1,
                        isStrictModeActive = false,
                        strictModeEndTimeEpochMs = 0L,
                        allowEmergencyUnlockInStrictMode = true,
                        currentStreakDays = 1,
                        longestStreakDays = 1,
                        lastActiveDate = today
                    )
                )
            }

            // 2. Ensure the 4 Pre-Configured Core Targets (YouTube, Facebook, Instagram, TikTok)
            // exist and are enabled by default with a 30-Minute (1800s) daily time allowance
            val defaultRules = AppBlockRule.HIGH_RISK_TARGETS.map { (pkg, name) ->
                AppBlockRule(
                    packageName = pkg,
                    appName = name,
                    isHighRisk = true,
                    isBlocked = true,
                    dailyQuotaMinutes = 30,
                    blockDuringSchedules = true,
                    hasCustomSchedule = false,
                    customStartHour = 9,
                    customStartMinute = 0,
                    customEndHour = 18,
                    customEndMinute = 0
                )
            }
            dao.insertAppRulesIgnoreExisting(defaultRules)

            // 3. Seed optional Focus Schedules (disabled by default so the 30-min / 1-min countdown works immediately)
            if (dao.getAllSchedulesOnce().isEmpty()) {
                dao.upsertSchedule(
                    ScheduleBlock(
                        title = "Deep Work Core Hours",
                        startHour = 9,
                        startMinute = 0,
                        endHour = 18,
                        endMinute = 0,
                        daysOfWeekCsv = "1,2,3,4,5",
                        targetPackagesCsv = "*",
                        isEnabled = false,
                        enforcesStrictMode = false
                    )
                )
                dao.upsertSchedule(
                    ScheduleBlock(
                        title = "Night Digital Sunset",
                        startHour = 22,
                        startMinute = 0,
                        endHour = 7,
                        endMinute = 0,
                        daysOfWeekCsv = "1,2,3,4,5,6,7",
                        targetPackagesCsv = "*",
                        isEnabled = false,
                        enforcesStrictMode = true
                    )
                )
            }
        }
    }
}
