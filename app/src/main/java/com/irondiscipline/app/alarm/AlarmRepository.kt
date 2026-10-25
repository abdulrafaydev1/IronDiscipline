package com.irondiscipline.app.alarm

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.Calendar

/**
 * Thread-Safe Persistent Alarm Repository.
 * Stores Hard Alarms in SharedPreferences and coordinates exact AlarmManager scheduling.
 */
class AlarmRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val scheduler = AlarmScheduler.getInstance(context)

    private val _alarmsFlow = MutableStateFlow<List<AlarmItem>>(emptyList())
    val alarmsFlow: StateFlow<List<AlarmItem>> = _alarmsFlow.asStateFlow()

    init {
        loadAlarmsFromPrefs()
    }

    companion object {
        private const val PREFS_NAME = "iron_discipline_alarms_prefs"
        private const val KEY_ALARMS_JSON = "key_alarms_json"

        @Volatile
        private var instance: AlarmRepository? = null

        fun getInstance(context: Context): AlarmRepository {
            return instance ?: synchronized(this) {
                instance ?: AlarmRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    @Synchronized
    private fun loadAlarmsFromPrefs() {
        val jsonString = prefs.getString(KEY_ALARMS_JSON, null)
        val loadedList = mutableListOf<AlarmItem>()

        if (!jsonString.isNullOrBlank()) {
            try {
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    loadedList.add(AlarmItem.fromJsonObject(obj))
                }
            } catch (_: Exception) {}
        }

        // Sort by time (hour, minute)
        loadedList.sortWith(compareBy({ it.hour }, { it.minute }))
        _alarmsFlow.value = loadedList
    }

    @Synchronized
    private fun saveAlarmsToPrefs(list: List<AlarmItem>) {
        val sorted = list.sortedWith(compareBy({ it.hour }, { it.minute }))
        val array = JSONArray()
        for (item in sorted) {
            array.put(item.toJsonObject())
        }
        prefs.edit().putString(KEY_ALARMS_JSON, array.toString()).apply()
        _alarmsFlow.value = sorted
    }

    @Synchronized
    fun saveAlarm(alarm: AlarmItem) {
        val nextTriggerMs = if (alarm.isEnabled) scheduler.calculateNextTriggerEpochMs(alarm) else 0L
        val alarmWithTrigger = alarm.copy(nextTriggerAtMillis = nextTriggerMs)

        val current = _alarmsFlow.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == alarm.id }
        if (existingIndex >= 0) {
            current[existingIndex] = alarmWithTrigger
        } else {
            current.add(alarmWithTrigger)
        }
        saveAlarmsToPrefs(current)

        if (alarmWithTrigger.isEnabled) {
            scheduler.scheduleAlarm(alarmWithTrigger)
        } else {
            scheduler.cancelAlarm(alarmWithTrigger.id)
        }
    }

    @Synchronized
    fun deleteAlarm(alarmId: String) {
        scheduler.cancelAlarm(alarmId)
        val current = _alarmsFlow.value.toMutableList()
        current.removeAll { it.id == alarmId }
        saveAlarmsToPrefs(current)
    }

    @Synchronized
    fun toggleAlarm(alarmId: String, isEnabled: Boolean) {
        val current = _alarmsFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == alarmId }
        if (index >= 0) {
            val existing = current[index]
            val nextTriggerMs = if (isEnabled) scheduler.calculateNextTriggerEpochMs(existing) else 0L
            val updated = existing.copy(isEnabled = isEnabled, nextTriggerAtMillis = nextTriggerMs)
            current[index] = updated
            saveAlarmsToPrefs(current)

            if (isEnabled) {
                scheduler.scheduleAlarm(updated)
            } else {
                scheduler.cancelAlarm(updated.id)
            }
        }
    }

    @Synchronized
    fun getAlarmById(alarmId: String): AlarmItem? {
        return _alarmsFlow.value.firstOrNull { it.id == alarmId }
    }

    /**
     * Handles post-dismiss logic:
     * - If deleteAfterRinging is true -> deletes alarm permanently.
     * - Else if repeatDays is non-empty -> keeps enabled and schedules next occurrence.
     * - Else -> sets isEnabled = false.
     */
    @Synchronized
    fun handleAlarmDismissed(alarmId: String) {
        val alarm = getAlarmById(alarmId) ?: return

        if (alarm.deleteAfterRinging) {
            deleteAlarm(alarm.id)
        } else if (alarm.isRepeating) {
            val nextTriggerMs = scheduler.calculateNextTriggerEpochMs(alarm)
            val updated = alarm.copy(isEnabled = true, nextTriggerAtMillis = nextTriggerMs)
            val current = _alarmsFlow.value.toMutableList()
            val index = current.indexOfFirst { it.id == alarm.id }
            if (index >= 0) {
                current[index] = updated
                saveAlarmsToPrefs(current)
            }
            scheduler.scheduleAlarm(updated)
        } else {
            toggleAlarm(alarm.id, false)
        }
    }

    /**
     * Finds the next enabled alarm and returns the time remaining in milliseconds.
     */
    fun getNextUpcomingAlarm(): Pair<AlarmItem, Long>? {
        val nowCal = Calendar.getInstance()
        val enabledAlarms = _alarmsFlow.value.filter { it.isEnabled }
        if (enabledAlarms.isEmpty()) return null

        var earliestAlarm: AlarmItem? = null
        var minRemainingMs = Long.MAX_VALUE

        for (alarm in enabledAlarms) {
            val triggerMs = scheduler.calculateNextTriggerEpochMs(alarm, nowCal)
            val remainingMs = triggerMs - nowCal.timeInMillis
            if (remainingMs > 0 && remainingMs < minRemainingMs) {
                minRemainingMs = remainingMs
                earliestAlarm = alarm
            }
        }

        return if (earliestAlarm != null) Pair(earliestAlarm, minRemainingMs) else null
    }

    /**
     * Reschedules all enabled alarms (e.g. after phone reboot or app update).
     */
    @Synchronized
    fun rescheduleAllActiveAlarms() {
        val current = _alarmsFlow.value.toMutableList()
        var changed = false
        for (i in current.indices) {
            val alarm = current[i]
            if (alarm.isEnabled) {
                val nextTriggerMs = scheduler.calculateNextTriggerEpochMs(alarm)
                val updated = alarm.copy(nextTriggerAtMillis = nextTriggerMs)
                current[i] = updated
                changed = true
                scheduler.scheduleAlarm(updated)
            }
        }
        if (changed) {
            saveAlarmsToPrefs(current)
        }
    }
}
