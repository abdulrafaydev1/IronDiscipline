package com.irondiscipline.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.irondiscipline.app.data.local.IronDisciplineDatabase
import com.irondiscipline.app.data.local.entities.AppBlockRule
import com.irondiscipline.app.data.local.entities.DailyUsageLog
import com.irondiscipline.app.data.local.entities.DeepWorkSession
import com.irondiscipline.app.data.local.entities.DisciplineStateEntity
import com.irondiscipline.app.data.local.entities.ScheduleBlock
import com.irondiscipline.app.data.repository.DisciplineRepository
import com.irondiscipline.app.domain.model.InstalledAppInfo
import com.irondiscipline.app.domain.model.PermissionStatusState
import com.irondiscipline.app.service.AppBlockerAccessibilityService
import com.irondiscipline.app.service.DisciplineMonitorService
import com.irondiscipline.app.util.PermissionHelper
import com.irondiscipline.app.util.RealtimeQuotaManager
import com.irondiscipline.app.util.RealtimeQuotaSnapshot
import com.irondiscipline.app.util.UsageTrackerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class DeepWorkTimerUiState(
    val selectedMinutes: Int = 25,
    val totalSeconds: Int = 25 * 60,
    val remainingSeconds: Int = 25 * 60,
    val isRunning: Boolean = false,
    val focusTitle: String = "Deep Work Architecture",
    val startedAtEpochMs: Long = 0L
)

data class DashboardMetricsUiState(
    val disciplineState: DisciplineStateEntity = DisciplineStateEntity(),
    val isStrictModeEffective: Boolean = false,
    val strictRemainingMillis: Long = 0L,
    val todayDeepWorkMinutes: Int = 0,
    val todayDistractionMinutes: Int = 0,
    val todaySavedMinutes: Int = 180,
    val todayBlockedAttempts: Int = 0,
    val recentSessions: List<DeepWorkSession> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val database = IronDisciplineDatabase.getInstance(context)
    private val usageTrackerHelper = UsageTrackerHelper.getInstance(context)
    private val realtimeQuotaManager = RealtimeQuotaManager.getInstance(context)
    private val repository = DisciplineRepository(
        dao = database.disciplineDao(),
        usageTrackerHelper = usageTrackerHelper,
        realtimeQuotaManager = realtimeQuotaManager
    )

    private val todayDateString: String
        get() = LocalDate.now().toString()

    // --- Real-Time 1-Second Quota Snapshot Flow (30-Min Normal Mode & 1-Min Test Mode) ---
    val realtimeQuotaState: StateFlow<RealtimeQuotaSnapshot> = realtimeQuotaManager.quotaSnapshotFlow

    // --- Real-Time Permission State ---
    private val _permissionState = MutableStateFlow(
        PermissionHelper.checkAllPermissions(context, usageTrackerHelper)
    )
    val permissionState: StateFlow<PermissionStatusState> = _permissionState.asStateFlow()

    // --- Installed + Target Apps ---
    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    // --- Room Database Flows ---
    val appRules: StateFlow<List<AppBlockRule>> = repository.appRulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val schedules: StateFlow<List<ScheduleBlock>> = repository.schedulesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val usageLogsToday: StateFlow<List<DailyUsageLog>> = repository
        .observeTodayUsageLogs(todayDateString)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- Deep Work Focus Timer State ---
    private val _timerState = MutableStateFlow(DeepWorkTimerUiState())
    val timerState: StateFlow<DeepWorkTimerUiState> = _timerState.asStateFlow()
    private var timerJob: Job? = null

    // --- Ticker for Strict Mode Countdown ---
    private val _clockTickMs = MutableStateFlow(System.currentTimeMillis())

    // --- Status Toast / Snackbar Message ---
    private val _statusBannerMessage = MutableStateFlow<String?>(null)
    val statusBannerMessage: StateFlow<String?> = _statusBannerMessage.asStateFlow()

    val dashboardMetrics: StateFlow<DashboardMetricsUiState> = combine(
        repository.disciplineStateFlow,
        repository.schedulesFlow,
        repository.observeTodayDeepWorkMinutes(todayDateString),
        repository.observeTodayUsageLogs(todayDateString),
        repository.allDeepWorkSessionsFlow,
        _clockTickMs
    ) { values ->
        val state = (values[0] as? DisciplineStateEntity) ?: DisciplineStateEntity()
        @Suppress("UNCHECKED_CAST")
        val allSchedules = values[1] as List<ScheduleBlock>
        val deepWorkMins = values[2] as Int
        @Suppress("UNCHECKED_CAST")
        val usageLogs = values[3] as List<DailyUsageLog>
        @Suppress("UNCHECKED_CAST")
        val sessions = values[4] as List<DeepWorkSession>
        val nowMs = values[5] as Long

        val nowDateTime = LocalDateTime.now()
        val scheduleStrict = allSchedules.any { it.isActiveNow(nowDateTime) && it.enforcesStrictMode }
        val manualStrict = state.isStrictLockedNow(nowMs)
        val effectiveStrict = manualStrict || scheduleStrict
        val remainingStrictMs = state.remainingStrictModeMillis(nowMs)

        val totalDistractionSeconds = usageLogs.sumOf { it.usedSeconds }
        val distractionMins = (totalDistractionSeconds / 60L).toInt()
        val baseline = state.baselineDailyDistractionMinutes.coerceAtLeast(60)
        val savedMins = (baseline - distractionMins).coerceAtLeast(0) + deepWorkMins
        val blockedAttempts = usageLogs.sumOf { it.blockedAttemptsCount }

        DashboardMetricsUiState(
            disciplineState = state,
            isStrictModeEffective = effectiveStrict,
            strictRemainingMillis = remainingStrictMs,
            todayDeepWorkMinutes = deepWorkMins,
            todayDistractionMinutes = distractionMins,
            todaySavedMinutes = savedMins,
            todayBlockedAttempts = blockedAttempts,
            recentSessions = sessions.take(8)
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DashboardMetricsUiState()
    )

    init {
        refreshPermissionsAndApps()
        startClockAndUsageSyncLoop()
    }

    fun refreshPermissionsAndApps() {
        _permissionState.value = PermissionHelper.checkAllPermissions(context, usageTrackerHelper)
        DisciplineMonitorService.startServiceIfNeeded(context)
        realtimeQuotaManager.publishSnapshot()
        viewModelScope.launch(Dispatchers.IO) {
            repository.getOrCreateDisciplineState()
            val discovered = usageTrackerHelper.getInstalledAndTargetApps()
            _installedApps.value = discovered
        }
    }

    private fun startClockAndUsageSyncLoop() {
        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(500L)
                val nowMs = System.currentTimeMillis()
                _clockTickMs.value = nowMs
                realtimeQuotaManager.publishSnapshot()
            }
        }
    }

    fun clearBannerMessage() {
        _statusBannerMessage.value = null
    }

    private fun postBannerMessage(msg: String) {
        _statusBannerMessage.value = msg
    }

    // --- 1-Minute Test Mode vs 30-Minute Normal Mode & Reset Actions ---
    fun activateOneMinuteTestMode() {
        if (realtimeQuotaManager.isAnyAppLocked()) {
            postBannerMessage("Action Blocked: Test mode cannot be changed while a 5-hour lock is active.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.activateOneMinuteTestMode()
            AppBlockerAccessibilityService.dismissOverlayIfShowing()
            postBannerMessage("1-Min Test Mode ACTIVE (60s limit). Open YouTube/IG/TikTok/FB to test live blocking!")
        }
    }

    fun switchBackToNormalThirtyMinModeAndReset() {
        if (realtimeQuotaManager.isAnyAppLocked()) {
            postBannerMessage("Action Blocked: Mode cannot be changed while a 5-hour lock is active.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.switchBackToNormalThirtyMinModeAndReset()
            AppBlockerAccessibilityService.dismissOverlayIfShowing()
            postBannerMessage("Switched to 30-Min Normal Mode (1800s limit).")
        }
    }

    fun unlockAndResetTimerForTesting() {
        if (realtimeQuotaManager.isAnyAppLocked()) {
            postBannerMessage("Action Blocked: 5-Hour lock is strictly unbreakable.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.unlockAndResetTimerForTesting()
            AppBlockerAccessibilityService.dismissOverlayIfShowing()
            postBannerMessage("Unlocked & Reset All Timers (YouTube, FB, IG, TikTok).")
        }
    }

    fun setAppLimitMinutes(appKey: String, limitMinutes: Int) {
        if (realtimeQuotaManager.getRemainingLockSeconds(appKey) > 0) {
            postBannerMessage("Action Blocked: Cannot modify limit for $appKey while 5-hour lock is active.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.setAppLimitMinutes(appKey, limitMinutes)
            AppBlockerAccessibilityService.dismissOverlayIfShowing()
            postBannerMessage("Updated limit for $appKey to ${limitMinutes}m.")
        }
    }

    fun resetSingleApp(appKey: String) {
        if (realtimeQuotaManager.getRemainingLockSeconds(appKey) > 0) {
            postBannerMessage("Action Blocked: $appKey is locked for 5 hours and cannot be reset.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.resetSingleApp(appKey)
            AppBlockerAccessibilityService.dismissOverlayIfShowing()
            postBannerMessage("Reset timer and unlocked $appKey.")
        }
    }

    // --- Strict Mode Actions ---
    fun activateStrictMode(durationHours: Int, allowEmergencyOverride: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.activateStrictMode(durationHours, allowEmergencyOverride)
            postBannerMessage("Strict Mode locked for $durationHours hours. Stay relentless.")
        }
    }

    fun updateMotivationalQuote(newQuote: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateMotivationalQuote(newQuote)
            postBannerMessage("Motivational overlay quote updated.")
        }
    }

    // --- App Block Rules Actions ---
    fun toggleAppBlocking(appInfo: InstalledAppInfo, existingRule: AppBlockRule?, targetBlocked: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val strictActive = repository.isStrictModeCurrentlyEnforced()
            if (strictActive && existingRule?.isBlocked == true && !targetBlocked) {
                postBannerMessage("Strict Mode Active: Cannot unblock ${appInfo.appName} until timer expires.")
                return@launch
            }
            val updatedRule = (existingRule ?: AppBlockRule(
                packageName = appInfo.packageName,
                appName = appInfo.appName,
                isHighRisk = appInfo.isHighRisk,
                dailyQuotaMinutes = 30,
                blockDuringSchedules = true
            )).copy(
                appName = appInfo.appName,
                isHighRisk = appInfo.isHighRisk,
                isBlocked = targetBlocked
            )
            repository.upsertAppRule(updatedRule)
            repository.syncTodayUsageLogs()
        }
    }

    fun updateAppRuleConfig(
        appInfo: InstalledAppInfo,
        existingRule: AppBlockRule?,
        newQuotaMinutes: Int,
        blockDuringSchedules: Boolean,
        hasCustomSchedule: Boolean,
        customStartHour: Int,
        customStartMinute: Int,
        customEndHour: Int,
        customEndMinute: Int
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val strictActive = repository.isStrictModeCurrentlyEnforced()
            if (strictActive && existingRule != null && existingRule.isBlocked) {
                if (newQuotaMinutes > existingRule.dailyQuotaMinutes) {
                    postBannerMessage("Strict Mode Active: You can only lower daily quotas during an active lock.")
                    return@launch
                }
                if (existingRule.blockDuringSchedules && !blockDuringSchedules) {
                    postBannerMessage("Strict Mode Active: Cannot disable schedule enforcement.")
                    return@launch
                }
            }
            val updated = (existingRule ?: AppBlockRule(
                packageName = appInfo.packageName,
                appName = appInfo.appName,
                isHighRisk = appInfo.isHighRisk
            )).copy(
                appName = appInfo.appName,
                isBlocked = true,
                dailyQuotaMinutes = newQuotaMinutes.coerceIn(0, 360),
                blockDuringSchedules = blockDuringSchedules,
                hasCustomSchedule = hasCustomSchedule,
                customStartHour = customStartHour.coerceIn(0, 23),
                customStartMinute = customStartMinute.coerceIn(0, 59),
                customEndHour = customEndHour.coerceIn(0, 23),
                customEndMinute = customEndMinute.coerceIn(0, 59)
            )
            repository.upsertAppRule(updated)
            postBannerMessage("Updated rule for ${appInfo.appName} (${newQuotaMinutes}m/day quota).")
        }
    }

    // --- Schedule Actions ---
    fun createOrUpdateSchedule(schedule: ScheduleBlock) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.upsertSchedule(schedule)
            postBannerMessage("Saved schedule '${schedule.title}'.")
        }
    }

    fun toggleScheduleEnabled(schedule: ScheduleBlock, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val strictActive = repository.isStrictModeCurrentlyEnforced()
            if (strictActive && schedule.isEnabled && !enabled) {
                postBannerMessage("Strict Mode Active: Cannot disable schedules during an active lock.")
                return@launch
            }
            repository.upsertSchedule(schedule.copy(isEnabled = enabled))
        }
    }

    fun deleteSchedule(schedule: ScheduleBlock) {
        viewModelScope.launch(Dispatchers.IO) {
            val strictActive = repository.isStrictModeCurrentlyEnforced()
            if (strictActive) {
                postBannerMessage("Strict Mode Active: Cannot delete schedules while locked.")
                return@launch
            }
            repository.deleteSchedule(schedule)
            postBannerMessage("Deleted schedule '${schedule.title}'.")
        }
    }

    // --- Deep Work Timer Controls ---
    fun setDeepWorkPresetMinutes(minutes: Int) {
        val current = _timerState.value
        if (current.isRunning) return
        val clamped = minutes.coerceIn(5, 240)
        _timerState.value = current.copy(
            selectedMinutes = clamped,
            totalSeconds = clamped * 60,
            remainingSeconds = clamped * 60
        )
    }

    fun updateDeepWorkTitle(newTitle: String) {
        _timerState.value = _timerState.value.copy(focusTitle = newTitle)
    }

    fun startOrPauseDeepWorkTimer() {
        val current = _timerState.value
        if (current.isRunning) {
            timerJob?.cancel()
            _timerState.value = current.copy(isRunning = false)
        } else {
            val startEpoch = if (current.startedAtEpochMs == 0L) {
                System.currentTimeMillis()
            } else {
                current.startedAtEpochMs
            }
            _timerState.value = current.copy(
                isRunning = true,
                startedAtEpochMs = startEpoch
            )
            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                while (isActive && _timerState.value.remainingSeconds > 0) {
                    delay(1000L)
                    val latest = _timerState.value
                    val nextRemaining = (latest.remainingSeconds - 1).coerceAtLeast(0)
                    _timerState.value = latest.copy(remainingSeconds = nextRemaining)
                    if (nextRemaining == 0) {
                        completeDeepWorkSessionInternal(wasFullDuration = true)
                        break
                    }
                }
            }
        }
    }

    fun finishAndLogDeepWorkEarly() {
        viewModelScope.launch {
            completeDeepWorkSessionInternal(wasFullDuration = false)
        }
    }

    fun resetDeepWorkTimer() {
        timerJob?.cancel()
        val current = _timerState.value
        _timerState.value = current.copy(
            isRunning = false,
            remainingSeconds = current.selectedMinutes * 60,
            startedAtEpochMs = 0L
        )
    }

    private suspend fun completeDeepWorkSessionInternal(wasFullDuration: Boolean) {
        timerJob?.cancel()
        val snapshot = _timerState.value
        val elapsedSeconds = snapshot.totalSeconds - snapshot.remainingSeconds
        val completedMinutes = if (wasFullDuration) {
            snapshot.selectedMinutes
        } else {
            (elapsedSeconds / 60).coerceAtLeast(1)
        }
        val startMs = if (snapshot.startedAtEpochMs > 0L) {
            snapshot.startedAtEpochMs
        } else {
            System.currentTimeMillis() - completedMinutes * 60_000L
        }

        repository.logDeepWorkSession(
            title = snapshot.focusTitle,
            durationMinutes = completedMinutes,
            startedAtEpochMs = startMs
        )

        _timerState.value = snapshot.copy(
            isRunning = false,
            remainingSeconds = snapshot.selectedMinutes * 60,
            startedAtEpochMs = 0L
        )
        postBannerMessage("Logged $completedMinutes mins of Deep Work! Discipline grows stronger.")
    }

    // --- PIN Management & Emergency App Deletion ---
    fun getUninstallPin(): String {
        val prefs = context.getSharedPreferences(AppBlockerAccessibilityService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        return prefs.getString("uninstall_pin", "1234") ?: "1234"
    }

    fun verifyUninstallPin(enteredPin: String): Boolean {
        return enteredPin.trim() == getUninstallPin().trim()
    }

    fun updateUninstallPin(currentPin: String, newPin: String): Boolean {
        if (!verifyUninstallPin(currentPin)) {
            postBannerMessage("Incorrect Current PIN.")
            return false
        }
        val cleanNewPin = newPin.trim()
        if (cleanNewPin.length < 4) {
            postBannerMessage("New PIN must be at least 4 digits.")
            return false
        }
        val prefs = context.getSharedPreferences(AppBlockerAccessibilityService.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("uninstall_pin", cleanNewPin).apply()
        postBannerMessage("Security PIN updated successfully.")
        return true
    }

    fun executeAuthorizedUninstall() {
        AppBlockerAccessibilityService.disarmAndDisableServiceForUninstall(context)
        realtimeQuotaManager.publishSnapshot()

        try {
            val packageUri = android.net.Uri.parse("package:${context.packageName}")
            val uninstallIntent = android.content.Intent(android.content.Intent.ACTION_DELETE, packageUri).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(uninstallIntent)
        } catch (e: Exception) {
            postBannerMessage("Failed to launch uninstaller: ${e.message}")
        }
    }
}
