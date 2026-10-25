package com.irondiscipline.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver triggered by AlarmManager.setAlarmClock.
 * Instantly acquires WakeLocks to prevent CPU sleep, starts AlarmRingingService in foreground,
 * and launches AlarmRingingActivity directly over the lock screen.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val safeIntent = intent ?: return

        // 1. Boot recovery
        if (safeIntent.action == Intent.ACTION_BOOT_COMPLETED ||
            safeIntent.action == "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) {
            try {
                AlarmRepository.getInstance(context).rescheduleAllActiveAlarms()
            } catch (_: Exception) {}
            return
        }

        // 2. First line for alarm triggers: Acquire CPU & Screen WakeLocks immediately
        AlarmWakeLockManager.acquireWakeLocks(context)

        val alarmId = safeIntent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
        val alarmLabel = safeIntent.getStringExtra(AlarmScheduler.EXTRA_ALARM_LABEL)
        val soundIndex = safeIntent.getIntExtra(AlarmScheduler.EXTRA_SOUND_INDEX, 0)
        val deleteAfter = safeIntent.getBooleanExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING, false)

        when (safeIntent.action) {
            AlarmScheduler.ACTION_DISMISS_ALARM -> {
                val repository = AlarmRepository.getInstance(context)
                repository.handleAlarmDismissed(alarmId)
                AlarmRingingService.stopRinging(context, alarmId)
            }

            AlarmScheduler.ACTION_SNOOZE_ALARM -> {
                // Snooze is permanently disabled under IronDiscipline protocols
                AlarmRingingService.stopRinging(context, alarmId)
            }

            AlarmScheduler.ACTION_TRIGGER_ALARM, null -> {
                // Initialize persistent Math Hard questions session state for this alarm
                ActiveAlarmSessionState.initSession(alarmId)

                // Start Foreground Ringing Service (High Priority Audio & 100% Volume Guard)
                val serviceIntent = Intent(context, AlarmRingingService::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, alarmLabel)
                    putExtra(AlarmScheduler.EXTRA_SOUND_INDEX, soundIndex)
                    putExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING, deleteAfter)
                }
                ContextCompat.startForegroundService(context, serviceIntent)

                // Direct Activity Launch in addition to fullScreenIntent
                try {
                    val activityIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                        putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                        putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, alarmLabel)
                        putExtra(AlarmScheduler.EXTRA_SOUND_INDEX, soundIndex)
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                Intent.FLAG_ACTIVITY_NO_USER_ACTION
                        )
                    }
                    context.startActivity(activityIntent)
                } catch (_: Exception) {}
            }
        }
    }
}
