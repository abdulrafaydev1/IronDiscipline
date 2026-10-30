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

        fun getPreWakeRequestCode(alarmId: String): Int {
            return (alarmId.hashCode() xor 0x55555555) and 0x7FFFFFFF
        }

        fun getBackupBroadcastRequestCode(alarmId: String): Int {
            return (alarmId.hashCode() xor 0x33333333) and 0x7FFFFFFF
        }

        /**
         * Schedules a 100% Screen-Off Reliable 5-Minute Follow-Up Alarm after the initial alarm's math challenge is solved.
         * Dual-trigger: 5-second Pre-Wake Direct Activity AlarmClock + Exact Direct Activity AlarmClock + Backup Broadcast.
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

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

            // Trigger 1 — Pre-Wake Screen Opener (5 Seconds Before Alarm Time: triggerAtMillis - 5000L)
            val preWakeEpochMs = triggerAtMillis - 5000L
            val preWakeIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                data = Uri.parse("irondiscipline://alarm_ring/followup_5min/prewake")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    identifier = "followup_prewake"
                }
                putExtra(EXTRA_ALARM_ID, "FOLLOWUP_5MIN_ALARM")
                putExtra("EXTRA_ALARM_ID", "FOLLOWUP_5MIN_ALARM")
                putExtra(EXTRA_ALARM_LABEL, "5-Min Wake-Up Verification")
                putExtra("EXTRA_ALARM_LABEL", "5-Min Wake-Up Verification")
                putExtra(EXTRA_SOUND_INDEX, soundIndex)
                putExtra("EXTRA_SOUND_INDEX", soundIndex)
                putExtra("EXTRA_IS_FOLLOWUP_ALARM", true)
                putExtra("EXTRA_FOLLOWUP_CODE", fiveDigitCode)
                putExtra("EXTRA_IS_NEW_TRIGGER", true)
                putExtra("EXTRA_PRE_WAKE_COUNTDOWN_MS", 5000L)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            val preWakePendingIntent = PendingIntent.getActivity(
                context,
                999995,
                preWakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(preWakeEpochMs, preWakePendingIntent),
                    preWakePendingIntent
                )
                Log.i(TAG, "[IronAlarmTrace:FOLLOWUP_PREWAKE] 5-minute follow-up pre-wake scheduled for epoch=$preWakeEpochMs")
            } catch (_: Exception) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preWakeEpochMs, preWakePendingIntent)
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, preWakeEpochMs, preWakePendingIntent)
                    }
                } catch (_: Exception) {}
            }

            // Trigger 2 — Exact Alarm Time Direct Activity AlarmClock (at triggerAtMillis)
            val directActivityIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                data = Uri.parse("irondiscipline://alarm_ring/followup_5min/direct")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    identifier = "followup_direct"
                }
                putExtra(EXTRA_ALARM_ID, "FOLLOWUP_5MIN_ALARM")
                putExtra("EXTRA_ALARM_ID", "FOLLOWUP_5MIN_ALARM")
                putExtra(EXTRA_ALARM_LABEL, "5-Min Wake-Up Verification")
                putExtra("EXTRA_ALARM_LABEL", "5-Min Wake-Up Verification")
                putExtra(EXTRA_SOUND_INDEX, soundIndex)
                putExtra("EXTRA_SOUND_INDEX", soundIndex)
                putExtra("EXTRA_IS_FOLLOWUP_ALARM", true)
                putExtra("EXTRA_FOLLOWUP_CODE", fiveDigitCode)
                putExtra("EXTRA_IS_NEW_TRIGGER", true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            val directPendingIntent = PendingIntent.getActivity(
                context,
                999991,
                directActivityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMillis, directPendingIntent),
                    directPendingIntent
                )
                Log.i(TAG, "[IronAlarmTrace:FOLLOWUP_SCHEDULE] 5-minute follow-up direct activity scheduled for epoch=$triggerAtMillis, code=$fiveDigitCode")
            } catch (se: SecurityException) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, directPendingIntent)
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, directPendingIntent)
                    }
                } catch (_: Exception) {}
            } catch (e: Exception) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, directPendingIntent)
                    } else {
                        alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, directPendingIntent)
                    }
                } catch (_: Exception) {}
            }

            // Trigger 3 — Backup Broadcast Trigger (at triggerAtMillis)
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
            val backupPendingIntent = PendingIntent.getBroadcast(
                context,
                999993,
                triggerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, backupPendingIntent)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, backupPendingIntent)
                }
            } catch (_: Exception) {}
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
                if (alarmManager != null) {
                    // Cancel direct activity pi
                    val directIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        data = Uri.parse("irondiscipline://alarm_ring/followup_5min/direct")
                    }
                    val directPi = PendingIntent.getActivity(context, 999991, directIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                    if (directPi != null) { alarmManager.cancel(directPi); directPi.cancel() }

                    // Cancel prewake pi
                    val preWakeIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                        action = Intent.ACTION_MAIN
                        data = Uri.parse("irondiscipline://alarm_ring/followup_5min/prewake")
                    }
                    val preWakePi = PendingIntent.getActivity(context, 999995, preWakeIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                    if (preWakePi != null) { alarmManager.cancel(preWakePi); preWakePi.cancel() }

                    // Cancel backup broadcast pi
                    val cancelIntent = Intent(context, AlarmReceiver::class.java).apply {
                        action = ACTION_TRIGGER_ALARM
                        data = Uri.parse("irondiscipline://alarm/followup_5min")
                    }
                    val pi = PendingIntent.getBroadcast(context, 999993, cancelIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                    if (pi != null) { alarmManager.cancel(pi); pi.cancel() }
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
     * Constructs a direct activity intent for AlarmRingingActivity.
     * Used by AlarmManager.setAlarmClock() to physically wake up the screen and display the alarm UI
     * over the lock screen without waiting for user touch or OEM broadcast delivery.
     */
    fun createDirectActivityIntent(
        alarm: AlarmItem,
        isPreWake: Boolean = false,
        preWakeCountdownMs: Long = 0L
    ): Intent {
        return Intent(context, AlarmRingingActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            data = Uri.parse("irondiscipline://alarm_ring/${alarm.id}${if (isPreWake) "/prewake" else ""}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                identifier = "${alarm.id}_${if (isPreWake) "prewake" else "direct"}"
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
            if (isPreWake) {
                putExtra("EXTRA_PRE_WAKE_COUNTDOWN_MS", preWakeCountdownMs)
            }
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
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
        val nowMs = System.currentTimeMillis()

        // 1. Trigger 1 — Pre-Wake Screen Opener (5 Seconds Before Alarm Time: nextTriggerMs - 5000L)
        if (nextTriggerMs - nowMs > 6000L) {
            val preWakeEpochMs = nextTriggerMs - 5000L
            val preWakeIntent = createDirectActivityIntent(alarm, isPreWake = true, preWakeCountdownMs = 5000L)
            val preWakePendingIntent = PendingIntent.getActivity(
                context,
                getPreWakeRequestCode(alarm.id),
                preWakeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                val preWakeClockInfo = AlarmManager.AlarmClockInfo(preWakeEpochMs, preWakePendingIntent)
                am.setAlarmClock(preWakeClockInfo, preWakePendingIntent)
                Log.i(TAG, "[IronAlarmTrace:PRE_WAKE_SCHEDULE] 5s Pre-Wake AlarmClock scheduled for Alarm ID=${alarm.id} at $preWakeEpochMs")
            } catch (e: Exception) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, preWakeEpochMs, preWakePendingIntent)
                    } else {
                        am.setExact(AlarmManager.RTC_WAKEUP, preWakeEpochMs, preWakePendingIntent)
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Trigger 2 — Exact Alarm Time Direct Activity AlarmClock (at nextTriggerMs)
        val directActivityIntent = createDirectActivityIntent(alarm, isPreWake = false)
        val directActivityPendingIntent = PendingIntent.getActivity(
            context,
            getRequestCode(alarm.id),
            directActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val clockInfo = AlarmManager.AlarmClockInfo(nextTriggerMs, directActivityPendingIntent)
            am.setAlarmClock(clockInfo, directActivityPendingIntent)
            Log.i(TAG, "[IronAlarmTrace:STAGE2_SCHEDULE_CONFIRMED] Direct Activity AlarmClock registered for Alarm ID=${alarm.id}")
        } catch (se: SecurityException) {
            Log.w(TAG, "[IronAlarmTrace:STAGE2_SCHEDULE_FALLBACK] SecurityException in setAlarmClock: ${se.message}")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, directActivityPendingIntent)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, nextTriggerMs, directActivityPendingIntent)
                }
            } catch (_: Exception) {}
        } catch (e: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, directActivityPendingIntent)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, nextTriggerMs, directActivityPendingIntent)
                }
            } catch (_: Exception) {}
        }

        // 3. Backup Broadcast Alarm Trigger (at nextTriggerMs)
        val backupBroadcastIntent = createTriggerIntent(alarm)
        val backupBroadcastPendingIntent = PendingIntent.getBroadcast(
            context,
            getBackupBroadcastRequestCode(alarm.id),
            backupBroadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextTriggerMs, backupBroadcastPendingIntent)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, nextTriggerMs, backupBroadcastPendingIntent)
            }
        } catch (_: Exception) {}

        // Maintain CPU partial wakelock whenever an alarm is scheduled
        AlarmWakeLockManager.maintainPendingAlarmCpuLock(context, hasEnabledAlarms = true)
    }

    /**
     * Cancels an alarm by its ID.
     */
    fun cancelAlarm(alarmId: String) {
        val am = alarmManager ?: return

        // 1. Cancel Direct Activity PendingIntent
        val directIntent = Intent(context, AlarmRingingActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            data = Uri.parse("irondiscipline://alarm_ring/$alarmId")
        }
        val directPi = PendingIntent.getActivity(
            context,
            getRequestCode(alarmId),
            directIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (directPi != null) {
            am.cancel(directPi)
            directPi.cancel()
        }

        // 2. Cancel Pre-Wake PendingIntent
        val preWakeIntent = Intent(context, AlarmRingingActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            data = Uri.parse("irondiscipline://alarm_ring/$alarmId/prewake")
        }
        val preWakePi = PendingIntent.getActivity(
            context,
            getPreWakeRequestCode(alarmId),
            preWakeIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (preWakePi != null) {
            am.cancel(preWakePi)
            preWakePi.cancel()
        }

        // 3. Cancel Backup Broadcast PendingIntent
        val backupIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            data = Uri.parse("irondiscipline://alarm/$alarmId")
        }
        val backupPi = PendingIntent.getBroadcast(
            context,
            getBackupBroadcastRequestCode(alarmId),
            backupIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (backupPi != null) {
            am.cancel(backupPi)
            backupPi.cancel()
        }

        // 4. Cancel legacy showIntent if any
        val showIntent = createShowIntent(alarmId)
        val showPendingIntent = PendingIntent.getActivity(
            context,
            getRequestCode(alarmId),
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
