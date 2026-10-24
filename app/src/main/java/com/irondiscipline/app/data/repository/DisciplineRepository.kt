package com.irondiscipline.app.data.repository

import com.irondiscipline.app.data.local.DisciplineDao
import com.irondiscipline.app.data.local.entities.AppBlockRule
import com.irondiscipline.app.data.local.entities.DailyUsageLog
import com.irondiscipline.app.data.local.entities.DeepWorkSession
import com.irondiscipline.app.data.local.entities.DisciplineStateEntity
import com.irondiscipline.app.data.local.entities.ScheduleBlock
import com.irondiscipline.app.domain.model.BlockEvaluationResult
import com.irondiscipline.app.domain.model.BlockReasonType
import com.irondiscipline.app.util.CoreTargetApp
import com.irondiscipline.app.util.RealtimeQuotaManager
import com.irondiscipline.app.util.UsageTrackerHelper
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

class DisciplineRepository(
    private val dao: DisciplineDao,
    private val usageTrackerHelper: UsageTrackerHelper,
    private val realtimeQuotaManager: RealtimeQuotaManager? = null
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

        if (updated.isStrictModeActive && updated.strictModeEndTimeEpochMs <= nowMs) {
            updated = updated.copy(
                isStrictModeActive = false,
                strictModeEndTimeEpochMs = 0L
            )
            changed = true
        }

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
        // Keep unified TikTok regional packages synchronized
        if (rule.packageName == "com.zhiliaoapp.musically") {
            val regional = dao.getAppRule("com.ss.android.ugc.trill")
            if (regional != null) {
                dao.upsertAppRule(
                    regional.copy(
                        isBlocked = rule.isBlocked,
                        dailyQuotaMinutes = rule.dailyQuotaMinutes,
                        blockDuringSchedules = rule.blockDuringSchedules,
                        hasCustomSchedule = rule.hasCustomSchedule,
                        customStartHour = rule.customStartHour,
                        customStartMinute = rule.customStartMinute,
                        customEndHour = rule.customEndHour,
                        customEndMinute = rule.customEndMinute,
                        updatedAtEpochMs = System.currentTimeMillis()
                    )
                )
            }
        }
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

    /**
     * Switches the limit for YouTube, Facebook, Instagram, and TikTok to 1 Minute (60s)
     * and resets today's test usage counters to 0 seconds.
     */
    suspend fun activateOneMinuteTestMode() {
        val todayStr = LocalDate.now().toString()
        realtimeQuotaManager?.activateOneMinuteTestMode()
        dao.resetUsedSecondsForDate(todayStr)
        usageTrackerHelper.resetInternalCounters()

        for ((pkg, name) in AppBlockRule.HIGH_RISK_TARGETS) {
            val existing = dao.getAppRule(pkg)
            dao.upsertAppRule(
                (existing ?: AppBlockRule(
                    packageName = pkg,
                    appName = name,
                    isHighRisk = true,
                    dailyQuotaMinutes = 30
                )).copy(
                    isBlocked = true,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Switches back to the 30-Minute (1,800s) Normal Mode limit for YouTube, Facebook,
     * Instagram, and TikTok and resets the current counters to 0 seconds.
     */
    suspend fun switchBackToNormalThirtyMinModeAndReset() {
        val todayStr = LocalDate.now().toString()
        realtimeQuotaManager?.switchBackToNormalThirtyMinModeAndReset()
        dao.resetUsedSecondsForDate(todayStr)
        usageTrackerHelper.resetInternalCounters()

        for ((pkg, name) in AppBlockRule.HIGH_RISK_TARGETS) {
            val existing = dao.getAppRule(pkg)
            dao.upsertAppRule(
                (existing ?: AppBlockRule(
                    packageName = pkg,
                    appName = name,
                    isHighRisk = true
                )).copy(
                    isBlocked = true,
                    dailyQuotaMinutes = 30,
                    updatedAtEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Explicit "Unlock & Reset Timer (For Testing)" action from Dashboard.
     * Sets used_seconds_today = 0 and is_locked_until_epoch_ms = 0.
     */
    suspend fun unlockAndResetTimerForTesting() {
        val todayStr = LocalDate.now().toString()
        realtimeQuotaManager?.unlockAndResetTimerForTesting()
        dao.resetUsedSecondsForDate(todayStr)
        usageTrackerHelper.resetInternalCounters()
    }

    suspend fun syncRealtimeTickToRoom(packageName: String, usedSecondsToday: Int) {
        val todayStr = LocalDate.now().toString()
        val canonicalPkg = CoreTargetApp.canonicalKeyForPackage(packageName)
        val existingLog = dao.getUsageLogForPackage(todayStr, canonicalPkg)
        dao.upsertUsageLog(
            DailyUsageLog(
                dateString = todayStr,
                packageName = canonicalPkg,
                usedSeconds = usedSecondsToday.toLong(),
                blockedAttemptsCount = existingLog?.blockedAttemptsCount ?: 0,
                lastUpdatedEpochMs = System.currentTimeMillis()
            )
        )
    }

    /**
     * Called every 1,000ms (1 second) by AppBlockerAccessibilityService when a foreground package is open.
     * Increments the real-time used seconds if the package is a monitored target app and returns
     * the immediate block evaluation.
     */
    suspend fun onOneSecondForegroundTick(packageName: String): BlockEvaluationResult {
        realtimeQuotaManager?.checkMidnightResetIfNeeded()
        val nowMs = System.currentTimeMillis()
        val state = getOrCreateDisciplineState()

        val canonicalPkg = CoreTargetApp.canonicalKeyForPackage(packageName)
        if (state.isTargetInEmergencyUnlock(packageName, nowMs) ||
            state.isTargetInEmergencyUnlock(canonicalPkg, nowMs)
        ) {
            return BlockEvaluationResult(shouldBlock = false)
        }

        val rule = resolveRuleForPackage(packageName) ?: return BlockEvaluationResult(shouldBlock = false)
        if (!rule.isBlocked) {
            return BlockEvaluationResult(shouldBlock = false)
        }

        val usedSecondsToday = realtimeQuotaManager?.incrementUsedSecond(packageName) ?: 0
        val todayStr = LocalDate.now().toString()
        val existingLog = dao.getUsageLogForPackage(todayStr, canonicalPkg)
        dao.upsertUsageLog(
            DailyUsageLog(
                dateString = todayStr,
                packageName = canonicalPkg,
                usedSeconds = usedSecondsToday.toLong(),
                blockedAttemptsCount = existingLog?.blockedAttemptsCount ?: 0,
                lastUpdatedEpochMs = nowMs
            )
        )

        return evaluatePackageAccess(packageName)
    }

    private suspend fun resolveRuleForPackage(packageName: String): AppBlockRule? {
        val direct = dao.getAppRule(packageName)
        if (direct != null) return direct
        val coreTarget = CoreTargetApp.fromPackageName(packageName)
        if (coreTarget != null) {
            return dao.getAppRule(coreTarget.canonicalKey)
        }
        return null
    }

    suspend fun recordBlockedAttempt(packageName: String) {
        val todayStr = LocalDate.now().toString()
        val canonicalPkg = CoreTargetApp.canonicalKeyForPackage(packageName)
        val existing = dao.getUsageLogForPackage(todayStr, canonicalPkg)
        val realtimeSec = realtimeQuotaManager?.getUsedSecondsToday(canonicalPkg)?.toLong() ?: 0L
        val latestSec = maxOf(realtimeSec, existing?.usedSeconds ?: 0L)
        val updated = DailyUsageLog(
            dateString = todayStr,
            packageName = canonicalPkg,
            usedSeconds = latestSec,
            blockedAttemptsCount = (existing?.blockedAttemptsCount ?: 0) + 1,
            lastUpdatedEpochMs = System.currentTimeMillis()
        )
        dao.upsertUsageLog(updated)
    }

    suspend fun syncTodayUsageLogs(): Map<String, Long> {
        realtimeQuotaManager?.checkMidnightResetIfNeeded()
        val todayStr = LocalDate.now().toString()
        val rules = dao.getAllAppRulesOnce()
        val existingLogs = dao.getUsageLogsForDateOnce(todayStr).associateBy { it.packageName }
        val result = HashMap<String, Long>()

        val allRelevantPackages = (rules.map { it.packageName } + AppBlockRule.HIGH_RISK_TARGETS.keys).toSet()
        for (pkg in allRelevantPackages) {
            val canonicalPkg = CoreTargetApp.canonicalKeyForPackage(pkg)
            val existing = existingLogs[canonicalPkg]
            val realtimeSec = realtimeQuotaManager?.getUsedSecondsToday(canonicalPkg)?.toLong() ?: 0L
            val mergedSec = maxOf(realtimeSec, existing?.usedSeconds ?: 0L)
            result[pkg] = mergedSec
            if (mergedSec > 0L || existing != null) {
                dao.upsertUsageLog(
                    DailyUsageLog(
                        dateString = todayStr,
                        packageName = canonicalPkg,
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
     * Supports both Normal Mode (30-Minute / 1,800s limit) and 1-Minute Test Mode (60s limit).
     */
    suspend fun evaluatePackageAccess(packageName: String): BlockEvaluationResult {
        realtimeQuotaManager?.checkMidnightResetIfNeeded()
        val todayStr = LocalDate.now().toString()
        val nowDateTime = LocalDateTime.now()
        val nowTime = LocalTime.now()
        val nowMs = System.currentTimeMillis()

        val canonicalPkg = CoreTargetApp.canonicalKeyForPackage(packageName)
        val state = getOrCreateDisciplineState()
        if (state.isTargetInEmergencyUnlock(packageName, nowMs) ||
            state.isTargetInEmergencyUnlock(canonicalPkg, nowMs)
        ) {
            return BlockEvaluationResult(shouldBlock = false)
        }

        val rule = resolveRuleForPackage(packageName) ?: return BlockEvaluationResult(shouldBlock = false)
        if (!rule.isBlocked) {
            return BlockEvaluationResult(shouldBlock = false)
        }

        val isTestMode = realtimeQuotaManager?.isTestModeActive() == true
        val isCoreTarget = CoreTargetApp.isCoreTargetPackage(packageName)

        val enabledSchedules = dao.getEnabledSchedulesOnce()
        val activeSchedules = enabledSchedules.filter {
            it.isActiveNow(nowDateTime) &&
                ((it.targetPackagesCsv.trim() == "*" && rule.blockDuringSchedules) ||
                    (it.targetPackagesCsv.trim() != "*" && it.appliesToPackage(packageName)))
        }
        val scheduleEnforcesStrict = activeSchedules.any { it.enforcesStrictMode }
        val canEmergencyOverride = state.canUseEmergencyUnlockToday(todayStr, scheduleEnforcesStrict)
        val todayDeepWorkMinutes = dao.getTotalDeepWorkMinutesForDateOnce(todayStr)

        // When 1-Minute Test Mode is active on the 4 core targets, prioritize the 60s countdown evaluation
        if (!isTestMode) {
            // 1. Check Global / Targeted Schedule Block Windows
            if (activeSchedules.isNotEmpty()) {
                val primarySchedule = activeSchedules.first()
                return BlockEvaluationResult(
                    shouldBlock = true,
                    targetKey = canonicalPkg,
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
                    targetKey = canonicalPkg,
                    displayTitle = rule.appName,
                    reasonType = BlockReasonType.CUSTOM_APP_SCHEDULE,
                    reasonDetail = "App Focus Schedule (${rule.formattedCustomSchedule()})",
                    canUseEmergencyOverride = canEmergencyOverride,
                    currentStreakDays = state.currentStreakDays,
                    todayDeepWorkMinutes = todayDeepWorkMinutes,
                    motivationalQuote = state.customQuote
                )
            }
        }

        // 3. Check Real-Time Daily Quota (60s in Test Mode, 1800s / 30m in Normal Mode)
        val activeLimitSeconds: Long = when {
            isTestMode && isCoreTarget -> RealtimeQuotaManager.TEST_MODE_LIMIT_SECONDS.toLong()
            isCoreTarget && rule.dailyQuotaMinutes == 30 -> RealtimeQuotaManager.NORMAL_MODE_LIMIT_SECONDS.toLong()
            else -> rule.dailyQuotaMinutes.coerceAtLeast(0) * 60L
        }

        val realtimeSeconds = realtimeQuotaManager?.getUsedSecondsToday(canonicalPkg)?.toLong() ?: 0L
        val existingLog = dao.getUsageLogForPackage(todayStr, canonicalPkg)
        val usedSeconds = if (isTestMode && isCoreTarget) {
            realtimeSeconds
        } else {
            maxOf(realtimeSeconds, existingLog?.usedSeconds ?: 0L)
        }

        if (activeLimitSeconds <= 0L || usedSeconds >= activeLimitSeconds) {
            val detail = when {
                isTestMode && isCoreTarget ->
                    "1-Minute Test Mode Limit Reached: ${usedSeconds}s / 60s elapsed!"
                activeLimitSeconds <= 0L ->
                    "Strict Zero-Quota Block (0 mins/day allowed)"
                else -> {
                    val usedMmSs = RealtimeQuotaManager.formatSecondsToMmSs(usedSeconds.toInt())
                    val limitMmSs = RealtimeQuotaManager.formatSecondsToMmSs(activeLimitSeconds.toInt())
                    "Daily Quota Exhausted: $usedMmSs / $limitMmSs used today"
                }
            }
            return BlockEvaluationResult(
                shouldBlock = true,
                targetKey = canonicalPkg,
                displayTitle = CoreTargetApp.fromPackageName(packageName)?.displayName ?: rule.appName,
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
            canUseEmergencyOverride = false,
            currentStreakDays = state.currentStreakDays,
            todayDeepWorkMinutes = todayDeepWorkMinutes,
            motivationalQuote = state.customQuote
        )
    }
}
