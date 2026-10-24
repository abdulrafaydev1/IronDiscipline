package com.irondiscipline.app.data.repository

import com.irondiscipline.app.data.local.DisciplineDao
import com.irondiscipline.app.data.local.entities.AppBlockRule
import com.irondiscipline.app.data.local.entities.DailyUsageLog
import com.irondiscipline.app.data.local.entities.DeepWorkSession
import com.irondiscipline.app.data.local.entities.DisciplineStateEntity
import com.irondiscipline.app.data.local.entities.ScheduleBlock
import com.irondiscipline.app.domain.model.BlockEvaluationResult
import com.irondiscipline.app.domain.model.BlockReasonType
import com.irondiscipline.app.util.UsageTrackerHelper
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

class DisciplineRepository(
    private val dao: DisciplineDao,
    private val usageTrackerHelper: UsageTrackerHelper
) {

    val appRulesFlow: Flow<List<AppBlockRule>> = dao.observeAllAppRules()
    val schedulesFlow: Flow<List<ScheduleBlock>> = dao.observeAllSchedules()
    val disciplineStateFlow: Flow<DisciplineStateEntity?> = dao.observeDisciplineState()
    val allDeepWorkSessionsFlow: Flow<List<DeepWorkSession>> = dao.observeAllDeepWorkSessions()

    fun observeTodayDeepWorkMinutes(dateString: String): Flow<Int> =
        dao.observeTotalDeepWorkMinutesForDate(dateString)

    fun observeTodayUsageLogs(dateString: String): Flow<List<DailyUsageLog>> =
        dao.observeUsageLogsForDate(dateString)

    suspend fun getOrCreateDisciplineState(): DisciplineStateEntity {
        val existing = dao.getDisciplineStateOnce()
        if (existing != null) {
            return refreshStreakAndStrictState(existing)
        }
        val initial = DisciplineStateEntity(
            id = 1,
            lastActiveDate = LocalDate.now().toString()
        )
        dao.upsertDisciplineState(initial)
        return initial
    }

    private suspend fun refreshStreakAndStrictState(state: DisciplineStateEntity): DisciplineStateEntity {
        val nowMs = System.currentTimeMillis()
        val today = LocalDate.now()
        val todayStr = today.toString()

        var updated = state
        var changed = false

        // Auto-expire Strict Mode once countdown reaches 0
        if (updated.isStrictModeActive && updated.strictModeEndTimeEpochMs <= nowMs) {
            updated = updated.copy(
                isStrictModeActive = false,
                strictModeEndTimeEpochMs = 0L
            )
            changed = true
        }

        // Advance daily discipline streak on date rollover
        if (updated.lastActiveDate.isNotBlank() && updated.lastActiveDate != todayStr) {
            try {
                val lastDate = LocalDate.parse(updated.lastActiveDate)
                val daysBetween = ChronoUnit.DAYS.between(lastDate, today).toInt()
                if (daysBetween >= 1) {
                    val newStreak = updated.currentStreakDays + daysBetween
                    val newLongest = maxOf(updated.longestStreakDays, newStreak)
                    updated = updated.copy(
                        currentStreakDays = newStreak,
                        longestStreakDays = newLongest,
                        lastActiveDate = todayStr
                    )
                    changed = true
                }
            } catch (_: Exception) {
                updated = updated.copy(lastActiveDate = todayStr)
                changed = true
            }
        } else if (updated.lastActiveDate.isBlank()) {
            updated = updated.copy(lastActiveDate = todayStr)
            changed = true
        }

        if (changed) {
            dao.upsertDisciplineState(updated)
        }
        return updated
    }

    suspend fun isStrictModeCurrentlyEnforced(): Boolean {
        val state = getOrCreateDisciplineState()
        if (state.isStrictLockedNow()) return true
        val schedules = dao.getEnabledSchedulesOnce()
        val now = LocalDateTime.now()
        return schedules.any { it.isActiveNow(now) && it.enforcesStrictMode }
    }

    suspend fun activateStrictMode(
        durationHours: Int,
        allowEmergencyOverride: Boolean
    ): DisciplineStateEntity {
        val current = getOrCreateDisciplineState()
        val nowMs = System.currentTimeMillis()
        val requestedEndMs = nowMs + durationHours * 3_600_000L
        // Cannot shorten an already active Strict Mode timer
        val finalEndMs = maxOf(current.strictModeEndTimeEpochMs, requestedEndMs)
        val finalAllowEmergency = if (current.isStrictLockedNow(nowMs)) {
            current.allowEmergencyUnlockInStrictMode && allowEmergencyOverride
        } else {
            allowEmergencyOverride
        }
        val updated = current.copy(
            isStrictModeActive = true,
            strictModeEndTimeEpochMs = finalEndMs,
            allowEmergencyUnlockInStrictMode = finalAllowEmergency
        )
        dao.upsertDisciplineState(updated)
        return updated
    }

    suspend fun updateMotivationalQuote(newQuote: String) {
        val trimmed = newQuote.trim()
        if (trimmed.isEmpty()) return
        val current = getOrCreateDisciplineState()
        dao.upsertDisciplineState(current.copy(customQuote = trimmed))
    }

    suspend fun upsertAppRule(rule: AppBlockRule) {
        dao.upsertAppRule(rule.copy(updatedAtEpochMs = System.currentTimeMillis()))
    }

    suspend fun upsertSchedule(schedule: ScheduleBlock) {
        dao.upsertSchedule(schedule)
    }

    suspend fun deleteSchedule(schedule: ScheduleBlock) {
        dao.deleteSchedule(schedule)
    }

    suspend fun logDeepWorkSession(
        title: String,
        durationMinutes: Int,
        startedAtEpochMs: Long,
        completedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        if (durationMinutes <= 0) return
        val todayStr = LocalDate.now().toString()
        dao.insertDeepWorkSession(
            DeepWorkSession(
                dateString = todayStr,
                focusTitle = title.ifBlank { "Deep Work Session" },
                durationMinutes = durationMinutes,
                startedAtEpochMs = startedAtEpochMs,
                completedAtEpochMs = completedAtEpochMs,
                wasCompletedFully = true
            )
        )
    }

    suspend fun grantEmergencyUnlockFor5Minutes(targetKey: String) {
        val todayStr = LocalDate.now().toString()
        val nowMs = System.currentTimeMillis()
        val current = getOrCreateDisciplineState()
        val updated = current.copy(
            lastEmergencyUnlockDate = todayStr,
            emergencyUnlockTarget = targetKey,
            emergencyUnlockUntilEpochMs = nowMs + 5 * 60 * 1000L
        )
        dao.upsertDisciplineState(updated)
    }

    suspend fun recordBlockedAttempt(packageName: String) {
        val todayStr = LocalDate.now().toString()
        val existing = dao.getUsageLogForPackage(todayStr, packageName)
        val persistedSec = existing?.usedSeconds ?: 0L
        val latestSec = usageTrackerHelper.getTodayUsageSecondsForPackage(packageName, persistedSec)
        val updated = DailyUsageLog(
            dateString = todayStr,
            packageName = packageName,
            usedSeconds = latestSec,
            blockedAttemptsCount = (existing?.blockedAttemptsCount ?: 0) + 1,
            lastUpdatedEpochMs = System.currentTimeMillis()
        )
        dao.upsertUsageLog(updated)
    }

    suspend fun syncTodayUsageLogs(): Map<String, Long> {
        val todayStr = LocalDate.now().toString()
        val rules = dao.getAllAppRulesOnce()
        val existingLogs = dao.getUsageLogsForDateOnce(todayStr).associateBy { it.packageName }
        val systemMap = usageTrackerHelper.queryTodayUsageSecondsMap()
        val result = HashMap<String, Long>()

        val allRelevantPackages = (rules.map { it.packageName } + AppBlockRule.HIGH_RISK_TARGETS.keys).toSet()
        for (pkg in allRelevantPackages) {
            val existing = existingLogs[pkg]
            val systemSec = systemMap[pkg] ?: 0L
            val mergedSec = maxOf(systemSec, existing?.usedSeconds ?: 0L)
            result[pkg] = mergedSec
            if (mergedSec > 0L || existing != null) {
                dao.upsertUsageLog(
                    DailyUsageLog(
                        dateString = todayStr,
                        packageName = pkg,
                        usedSeconds = mergedSec,
                        blockedAttemptsCount = existing?.blockedAttemptsCount ?: 0,
                        lastUpdatedEpochMs = System.currentTimeMillis()
                    )
                )
            }
        }
        return result
    }

    /**
     * Core Dual Blocking Engine evaluation for a foreground app package.
     * Checks:
     * 1. Active 5-minute Emergency Unlock override
     * 2. Active ScheduleBlock windows (global or per-package)
     * 3. Per-App Custom Schedule window
     * 4. Daily Time Quota via UsageStatsManager + internal session tracker
     */
    suspend fun evaluatePackageAccess(packageName: String): BlockEvaluationResult {
        val todayStr = LocalDate.now().toString()
        val nowDateTime = LocalDateTime.now()
        val nowTime = LocalTime.now()
        val nowMs = System.currentTimeMillis()

        val state = getOrCreateDisciplineState()
        if (state.isTargetInEmergencyUnlock(packageName, nowMs)) {
            return BlockEvaluationResult(shouldBlock = false)
        }

        val rule = dao.getAppRule(packageName) ?: return BlockEvaluationResult(shouldBlock = false)
        if (!rule.isBlocked) {
            return BlockEvaluationResult(shouldBlock = false)
        }

        val enabledSchedules = dao.getEnabledSchedulesOnce()
        val activeSchedules = enabledSchedules.filter {
            it.isActiveNow(nowDateTime) &&
                ((it.targetPackagesCsv.trim() == "*" && rule.blockDuringSchedules) ||
                    (it.targetPackagesCsv.trim() != "*" && it.appliesToPackage(packageName)))
        }
        val scheduleEnforcesStrict = activeSchedules.any { it.enforcesStrictMode }
        val canEmergencyOverride = state.canUseEmergencyUnlockToday(todayStr, scheduleEnforcesStrict)
        val todayDeepWorkMinutes = dao.getTotalDeepWorkMinutesForDateOnce(todayStr)

        // 1. Check Global / Targeted Schedule Block Windows
        if (activeSchedules.isNotEmpty()) {
            val primarySchedule = activeSchedules.first()
            return BlockEvaluationResult(
                shouldBlock = true,
                targetKey = packageName,
                displayTitle = rule.appName,
                reasonType = BlockReasonType.SCHEDULE_WINDOW,
                reasonDetail = "Scheduled Lock: ${primarySchedule.title} (${primarySchedule.formattedTimeRange()})",
                canUseEmergencyOverride = canEmergencyOverride,
                currentStreakDays = state.currentStreakDays,
                todayDeepWorkMinutes = todayDeepWorkMinutes,
                motivationalQuote = state.customQuote
            )
        }

        // 2. Check Per-App Custom Schedule Window
        if (rule.hasCustomSchedule && rule.isWithinCustomSchedule(nowTime)) {
            return BlockEvaluationResult(
                shouldBlock = true,
                targetKey = packageName,
                displayTitle = rule.appName,
                reasonType = BlockReasonType.CUSTOM_APP_SCHEDULE,
                reasonDetail = "App Focus Schedule (${rule.formattedCustomSchedule()})",
                canUseEmergencyOverride = canEmergencyOverride,
                currentStreakDays = state.currentStreakDays,
                todayDeepWorkMinutes = todayDeepWorkMinutes,
                motivationalQuote = state.customQuote
            )
        }

        // 3. Check Daily Time Quota
        val existingLog = dao.getUsageLogForPackage(todayStr, packageName)
        val usedSeconds = usageTrackerHelper.getTodayUsageSecondsForPackage(
            packageName = packageName,
            persistedSeconds = existingLog?.usedSeconds ?: 0L
        )

        if (usedSeconds > (existingLog?.usedSeconds ?: 0L)) {
            dao.upsertUsageLog(
                DailyUsageLog(
                    dateString = todayStr,
                    packageName = packageName,
                    usedSeconds = usedSeconds,
                    blockedAttemptsCount = existingLog?.blockedAttemptsCount ?: 0,
                    lastUpdatedEpochMs = nowMs
                )
            )
        }

        val quotaSeconds = rule.dailyQuotaMinutes * 60L
        if (rule.dailyQuotaMinutes <= 0 || usedSeconds >= quotaSeconds) {
            val usedMinutesDisplay = usedSeconds / 60L
            val detail = if (rule.dailyQuotaMinutes <= 0) {
                "Strict Zero-Quota Block (0 mins/day allowed)"
            } else {
                "Daily Quota Exhausted: ${usedMinutesDisplay}m / ${rule.dailyQuotaMinutes}m used today"
            }
            return BlockEvaluationResult(
                shouldBlock = true,
                targetKey = packageName,
                displayTitle = rule.appName,
                reasonType = BlockReasonType.DAILY_QUOTA_EXHAUSTED,
                reasonDetail = detail,
                canUseEmergencyOverride = canEmergencyOverride,
                currentStreakDays = state.currentStreakDays,
                todayDeepWorkMinutes = todayDeepWorkMinutes,
                motivationalQuote = state.customQuote
            )
        }

        return BlockEvaluationResult(shouldBlock = false)
    }

    /**
     * Evaluates browser URL bar content for restricted social media domains
     * (instagram.com, facebook.com, tiktok.com, youtube.com).
     */
    suspend fun evaluateBrowserUrl(rawUrlText: String): BlockEvaluationResult {
        val cleaned = rawUrlText.trim().lowercase()
        if (cleaned.isEmpty() || cleaned.contains(" ")) {
            // Ignore search queries containing spaces unless they explicitly match a domain token
            val hasDomainToken = AppBlockRule.SOCIAL_DOMAIN_TO_PACKAGES.keys.any { domain ->
                cleaned.contains(domain)
            }
            if (!hasDomainToken) return BlockEvaluationResult(shouldBlock = false)
        }

        val matchedEntry = AppBlockRule.SOCIAL_DOMAIN_TO_PACKAGES.entries.firstOrNull { (domain, _) ->
            cleaned.contains(domain)
        } ?: return BlockEvaluationResult(shouldBlock = false)

        val matchedDomain = matchedEntry.key
        val mappedPackages = matchedEntry.value

        val todayStr = LocalDate.now().toString()
        val nowMs = System.currentTimeMillis()
        val nowDateTime = LocalDateTime.now()
        val state = getOrCreateDisciplineState()

        if (state.isTargetInEmergencyUnlock(matchedDomain, nowMs) ||
            mappedPackages.any { state.isTargetInEmergencyUnlock(it, nowMs) }
        ) {
            return BlockEvaluationResult(shouldBlock = false)
        }

        val allRules = dao.getAllAppRulesOnce().associateBy { it.packageName }
        val enabledSchedules = dao.getEnabledSchedulesOnce()
        val anyActiveSchedule = enabledSchedules.any { it.isActiveNow(nowDateTime) }
        val scheduleEnforcesStrict = enabledSchedules.any { it.isActiveNow(nowDateTime) && it.enforcesStrictMode }
        val isCorrespondingRuleBlocked = mappedPackages.any { pkg -> allRules[pkg]?.isBlocked == true }

        if (isCorrespondingRuleBlocked || anyActiveSchedule || state.isStrictLockedNow(nowMs)) {
            val todayDeepWorkMinutes = dao.getTotalDeepWorkMinutesForDateOnce(todayStr)
            val canEmergencyOverride = state.canUseEmergencyUnlockToday(todayStr, scheduleEnforcesStrict)
            return BlockEvaluationResult(
                shouldBlock = true,
                targetKey = matchedDomain,
                displayTitle = "Browser: $matchedDomain",
                reasonType = BlockReasonType.BROWSER_SOCIAL_DOMAIN,
                reasonDetail = "Web Bypass Intercepted ($matchedDomain is restricted during focus sessions)",
                canUseEmergencyOverride = canEmergencyOverride,
                currentStreakDays = state.currentStreakDays,
                todayDeepWorkMinutes = todayDeepWorkMinutes,
                motivationalQuote = state.customQuote
            )
        }

        return BlockEvaluationResult(shouldBlock = false)
    }

    /**
     * Evaluates Settings self-sabotage attempt when Strict Mode is active.
     */
    suspend fun buildStrictModeSettingsBlockResult(detectedPageLabel: String): BlockEvaluationResult {
        val todayStr = LocalDate.now().toString()
        val state = getOrCreateDisciplineState()
        val todayDeepWorkMinutes = dao.getTotalDeepWorkMinutesForDateOnce(todayStr)

        return BlockEvaluationResult(
            shouldBlock = true,
            targetKey = "com.android.settings",
            displayTitle = "Strict Mode Anti-Bypass Shield",
            reasonType = BlockReasonType.STRICT_MODE_SETTINGS_GUARD,
            reasonDetail = "Protected Settings Page Blocked ($detectedPageLabel). Strict Mode cannot be bypassed until the timer expires.",
            canUseEmergencyOverride = false, // Never allow bypassing Settings protection
            currentStreakDays = state.currentStreakDays,
            todayDeepWorkMinutes = todayDeepWorkMinutes,
            motivationalQuote = state.customQuote
        )
    }
}
