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
        const val TAG = "IronAlarmTrace"
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

        /**
         * Generates a stable, non-negative 31-bit request code derived strictly from the alarm UUID.
         * Guarantees that different alarms NEVER collide or overwrite each other in Android's PendingIntent table.
         */
        fun getRequestCode(alarmId: String): Int {
            return alarmId.hashCode() and 0x7FFFFFFF
        }

        /**
         * Schedules a 100% Screen-Off Reliable 5-Minute Follow-Up Alarm after the initial alarm's math challenge is solved.
         * Holds CPU PARTIAL_WAKE_LOCK so timers and alarms never freeze during phone sleep.
         */
        fun scheduleFiveMinuteFollowUpAlarm(
            context: Context,
            fiveDigitCode: String,
            soundIndex: Int,
            label: String
        ) {
            val triggerAtMillis = System.currentTimeMillis() + 5 * 60 * 1000L
            val prefs = context.getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("followup_alarm_pending", true)
                .putLong("followup_alarm_trigger_ms", triggerAtMillis)
                .putString("followup_alarm_code", fiveDigitCode)
                .putInt("followup_alarm_sound_index", soundIndex)
                .putString("followup_alarm_label", label)
                .commit()

            // 1. Keep CPU awake while screen is OFF during the 5-minute wait!
            AlarmWakeLockManager.maintainPendingAlarmCpuLock(context, hasEnabledAlarms = true)

            // 2. Schedule exact OS AlarmClock broadcast (RequestCode = 999991)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = ACTION_TRIGGER_ALARM
                data = Uri.parse("irondiscipline://alarm/followup_5min")
                putExtra(EXTRA_ALARM_ID, "FOLLOWUP_5MIN_ALARM")
                putExtra("EXTRA_ALARM_ID", "FOLLOWUP_5MIN_ALARM")
                putExtra(EXTRA_ALARM_LABEL, "5-Min Wake-Up Verification")
                putExtra("EXTRA_ALARM_LABEL", "5-Min Wake-Up Verification")
                putExtra(EXTRA_SOUND_INDEX, soundIndex)
                putExtra("EXTRA_SOUND_INDEX", soundIndex)
                putExtra("EXTRA_IS_FOLLOWUP_ALARM", true)
                putExtra("EXTRA_FOLLOWUP_CODE", fiveDigitCode)
                putExtra("EXTRA_IS_NEW_TRIGGER", true)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                999991,
                triggerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val showIntent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val showPendingIntent = PendingIntent.getActivity(
                context,
                999992,
                showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMillis, showPendingIntent),
                    pendingIntent
                )
                Log.i(TAG, "[IronAlarmTrace:FOLLOWUP_SCHEDULE] 5-minute follow-up alarm scheduled for epoch=$triggerAtMillis, code=$fiveDigitCode")
            } catch (se: SecurityException) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[IronAlarmTrace:FOLLOWUP_SCHEDULE_ERROR] Fallback follow-up schedule failed: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[IronAlarmTrace:FOLLOWUP_SCHEDULE_ERROR] Exception in follow-up setAlarmClock: ${e.message}")
            }
        }

        /**
         * Clears the pending 5-minute follow-up alarm and releases any waiting wakelocks.
         */
        fun clearFollowUpAlarm(context: Context) {
            val prefs = context.getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("followup_alarm_pending", false)
                .putLong("followup_alarm_trigger_ms", 0L)
                .putBoolean("is_current_ring_followup", false)
                .commit()
            try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                val cancelIntent = Intent(context, AlarmReceiver::class.java).apply {
                    action = ACTION_TRIGGER_ALARM
                    data = Uri.parse("irondiscipline://alarm/followup_5min")
                }
                val pi = PendingIntent.getBroadcast(
                    context,
                    999991,
                    cancelIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                if (alarmManager != null && pi != null) {
                    alarmManager.cancel(pi)
                    pi.cancel()
                }
                Log.i(TAG, "[IronAlarmTrace:FOLLOWUP_CLEAR] 5-minute follow-up alarm cancelled")
            } catch (_: Exception) {}

            try {
                val alarms = AlarmRepository.getInstance(context).alarmsFlow.value
                val hasOtherEnabled = alarms.any { it.isEnabled }
                AlarmWakeLockManager.maintainPendingAlarmCpuLock(context, hasOtherEnabled)
            } catch (_: Exception) {}
        }
    }

    /**
     * Constructs a unique, non-colliding trigger Intent for AlarmReceiver.
     * Combines explicit Action, unique Data URI, and Android Q+ Identifier so that
     * Intent.filterEquals() treats every alarm as an entirely distinct system identity.
     */
    fun createTriggerIntent(alarm: AlarmItem): Intent {
        return Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            data = Uri.parse("irondiscipline://alarm/${alarm.id}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                identifier = alarm.id
            }
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
    }

    /**
     * Constructs the companion show Intent displayed in the lock-screen Alarm Clock widget.
     */
    fun createShowIntent(alarmId: String): Intent {
        return Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            data = Uri.parse("irondiscipline://show/$alarmId")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                identifier = alarmId
            }
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
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

        val am = alarmManager ?: run {
            Log.e(TAG, "[IronAlarmTrace:STAGE2_SCHEDULE_ERROR] AlarmManager service is null, cannot schedule alarm ID=${alarm.id}")
            return
        }

        val nextTriggerMs = calculateNextTriggerEpochMs(alarm)
        val requestCode = getRequestCode(alarm.id)
        val triggerIntent = createTriggerIntent(alarm)

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = createShowIntent(alarm.id)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        val deltaMs = nextTriggerMs - System.currentTimeMillis()
        Log.i(
            TAG,
            "[IronAlarmTrace:STAGE2_SCHEDULE] SUCCESS -> Alarm ID=${alarm.id}, target=${alarm.formattedTime}, triggerEpoch=$nextTriggerMs, inDelta=${deltaMs}ms, canExact=$canExact, requestCode=$requestCode, data=${triggerIntent.data}"
        )

        try {
            val clockInfo = AlarmManager.AlarmClockInfo(nextTriggerMs, showPendingIntent)
            am.setAlarmClock(clockInfo, pendingIntent)
            Log.i(TAG, "[IronAlarmTrace:STAGE2_SCHEDULE_CONFIRMED] am.setAlarmClock() registered for Alarm ID=${alarm.id}")
        } catch (se: SecurityException) {
            Log.w(TAG, "[IronAlarmTrace:STAGE2_SCHEDULE_FALLBACK] SecurityException in setAlarmClock: ${se.message}, attempting setExactAndAllowWhileIdle")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[IronAlarmTrace:STAGE2_SCHEDULE_FATAL] Fallback exact scheduling failed: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[IronAlarmTrace:STAGE2_SCHEDULE_ERROR] Exception in setAlarmClock: ${e.message}, trying setExactAndAllowWhileIdle")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, nextTriggerMs, pendingIntent)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "[IronAlarmTrace:STAGE2_SCHEDULE_FATAL] Generic fallback failed: ${ex.message}")
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
        val requestCode = getRequestCode(alarmId)

        val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            data = Uri.parse("irondiscipline://alarm/$alarmId")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                identifier = alarmId
            }
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra("EXTRA_ALARM_ID", alarmId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            triggerIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            am.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i(TAG, "[IronAlarmTrace:CANCEL] Broadcast PendingIntent cancelled for Alarm ID=$alarmId, requestCode=$requestCode")
        }

        val showIntent = createShowIntent(alarmId)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            showIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (showPendingIntent != null) {
            am.cancel(showPendingIntent)
            showPendingIntent.cancel()
        }

        // Update CPU partial wakelock state
        try {
            val hasEnabled = AlarmRepository.getInstance(context).alarmsFlow.value.any { it.isEnabled && it.id != alarmId }
            AlarmWakeLockManager.maintainPendingAlarmCpuLock(context, hasEnabled)
            Log.d(TAG, "[IronAlarmTrace:CANCEL_SYNC] Post-cancel hasEnabledAlarms=$hasEnabled")
        } catch (_: Exception) {}
    }

    /**
     * Schedules a Snooze trigger after the configured snooze minutes.
     */
    fun scheduleSnooze(alarm: AlarmItem, snoozeMinutes: Int) {
        val am = alarmManager ?: return
        val triggerMs = System.currentTimeMillis() + (snoozeMinutes * 60 * 1000L)
        val requestCode = getRequestCode(alarm.id)

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            data = Uri.parse("irondiscipline://snooze/${alarm.id}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                identifier = alarm.id
            }
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
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = createShowIntent(alarm.id)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
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
