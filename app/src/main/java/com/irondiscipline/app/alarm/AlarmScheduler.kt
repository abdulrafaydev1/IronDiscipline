package com.irondiscipline.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.irondiscipline.app.MainActivity
import java.util.Calendar

/**
 * High-Priority Exact Alarm Scheduler using Android's AlarmManager.setAlarmClock.
 * Bypasses system battery optimization and Doze mode to ensure 100% reliable wake-ups.
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    companion object {
        const val ACTION_TRIGGER_ALARM = "com.irondiscipline.app.ACTION_ALARM_TRIGGER"
        const val ACTION_SNOOZE_ALARM = "com.irondiscipline.app.alarm.SNOOZE_ALARM"
        const val ACTION_DISMISS_ALARM = "com.irondiscipline.app.alarm.DISMISS_ALARM"

        const val EXTRA_ALARM_ID = "extra_alarm_id"
        const val EXTRA_ALARM_LABEL = "extra_alarm_label"
        const val EXTRA_SOUND_INDEX = "extra_sound_index"
        const val EXTRA_DELETE_AFTER_RINGING = "extra_delete_after_ringing"

        @Volatile
        private var instance: AlarmScheduler? = null

        fun getInstance(context: Context): AlarmScheduler {
            return instance ?: synchronized(this) {
                instance ?: AlarmScheduler(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Schedules an alarm with setAlarmClock to wake the device out of Doze mode.
     */
    fun scheduleAlarm(alarm: AlarmItem) {
        if (!alarm.isEnabled) {
            cancelAlarm(alarm.id)
            return
        }

        val am = alarmManager ?: return
        val nextTriggerMs = calculateNextTriggerEpochMs(alarm)

        val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarm.id)
            putExtra("EXTRA_ALARM_ID", alarm.id)
            putExtra(EXTRA_ALARM_LABEL, alarm.label)
            putExtra("EXTRA_ALARM_LABEL", alarm.label)
            putExtra(EXTRA_SOUND_INDEX, alarm.soundIndex)
            putExtra("EXTRA_SOUND_INDEX", alarm.soundIndex)
            putExtra(EXTRA_DELETE_AFTER_RINGING, alarm.deleteAfterRinging)
            putExtra("EXTRA_DELETE_AFTER_RINGING", alarm.deleteAfterRinging)
            putExtra("EXTRA_IS_NEW_TRIGGER", true)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            alarm.id.hashCode(),
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        val deltaMs = nextTriggerMs - System.currentTimeMillis()
        Log.d(
            "AlarmDiag",
            "[SCHEDULE] Alarm ID=${alarm.id}, target=${alarm.formattedTime}, triggerAtMillis=$nextTriggerMs, deltaMs=$deltaMs, canScheduleExactAlarms=$canExact"
        )

        try {
            val clockInfo = AlarmManager.AlarmClockInfo(nextTriggerMs, showPendingIntent)
            am.setAlarmClock(clockInfo, pendingIntent)
        } catch (_: SecurityException) {
            // Fallback for older or restricted exact alarm environments
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
                }
            } catch (e: Exception) {
                Log.e("AlarmDiag", "[SCHEDULE] Failed to schedule exact alarm fallback: ${e.message}")
            }
        }

        // Maintain CPU partial wakelock whenever an alarm is scheduled
        AlarmWakeLockManager.maintainPendingAlarmCpuLock(context, hasEnabledAlarms = true)
    }

    /**
     * Cancels an alarm by its ID.
     */
    fun cancelAlarm(alarmId: String) {
        val am = alarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra("EXTRA_ALARM_ID", alarmId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            am.cancel(pendingIntent)
            pendingIntent.cancel()
        }

        // Update CPU partial wakelock state
        try {
            val hasEnabled = AlarmRepository.getInstance(context).alarmsFlow.value.any { it.isEnabled && it.id != alarmId }
            AlarmWakeLockManager.maintainPendingAlarmCpuLock(context, hasEnabled)
        } catch (_: Exception) {}
    }

    /**
     * Schedules a Snooze trigger after the configured snooze minutes.
     */
    fun scheduleSnooze(alarm: AlarmItem, snoozeMinutes: Int) {
        val am = alarmManager ?: return
        val triggerMs = System.currentTimeMillis() + (snoozeMinutes * 60 * 1000L)

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarm.id)
            putExtra("EXTRA_ALARM_ID", alarm.id)
            putExtra(EXTRA_ALARM_LABEL, alarm.label)
            putExtra("EXTRA_ALARM_LABEL", alarm.label)
            putExtra(EXTRA_SOUND_INDEX, alarm.soundIndex)
            putExtra("EXTRA_SOUND_INDEX", alarm.soundIndex)
            putExtra(EXTRA_DELETE_AFTER_RINGING, alarm.deleteAfterRinging)
            putExtra("EXTRA_DELETE_AFTER_RINGING", alarm.deleteAfterRinging)
            putExtra("EXTRA_IS_NEW_TRIGGER", true)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            alarm.id.hashCode(),
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val clockInfo = AlarmManager.AlarmClockInfo(triggerMs, showPendingIntent)
            am.setAlarmClock(clockInfo, pendingIntent)
        } catch (_: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
        }

        AlarmWakeLockManager.maintainPendingAlarmCpuLock(context, hasEnabledAlarms = true)
    }

    /**
     * Calculates the exact epoch millis for the next occurrence of this alarm.
     */
    fun calculateNextTriggerEpochMs(alarm: AlarmItem, nowCal: Calendar = Calendar.getInstance()): Long {
        val nowMs = nowCal.timeInMillis

        if (alarm.repeatDays.isEmpty()) {
            // One-time alarm
            val targetCal = (nowCal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (targetCal.timeInMillis <= nowMs) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return targetCal.timeInMillis
        } else {
            // Repeating alarm across selected days
            for (offset in 0..7) {
                val candidate = (nowCal.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, offset)
                    set(Calendar.HOUR_OF_DAY, alarm.hour)
                    set(Calendar.MINUTE, alarm.minute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayOfWeek = candidate.get(Calendar.DAY_OF_WEEK)
                if (alarm.repeatDays.contains(dayOfWeek)) {
                    if (candidate.timeInMillis > nowMs) {
                        return candidate.timeInMillis
                    }
                }
            }
            // Fallback: 7 days ahead
            val fallback = (nowCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 7)
                set(Calendar.HOUR_OF_DAY, alarm.hour)
                set(Calendar.MINUTE, alarm.minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            return fallback.timeInMillis
        }
    }
}
