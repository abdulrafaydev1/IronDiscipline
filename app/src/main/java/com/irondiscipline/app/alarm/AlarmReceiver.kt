package com.irondiscipline.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * BroadcastReceiver triggered by AlarmManager.setAlarmClock.
 * Instantly acquires WakeLocks to prevent CPU sleep and starts AlarmRingingService
 * in the foreground immediately without gating on lock-screen Activity launch.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val safeIntent = intent ?: return
        val action = safeIntent.action

        // 1. Boot recovery and system time/timezone/permission change events
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED" ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == "android.intent.action.TIME_SET" ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        ) {
            Log.i("AlarmDiag", "[RECEIVER] System event received: action=$action -> rescheduling all active alarms")
            try {
                AlarmRepository.getInstance(context).rescheduleAllActiveAlarms()
            } catch (e: Exception) {
                Log.e("AlarmDiag", "[RECEIVER] Error rescheduling active alarms: ${e.message}")
            }
            return
        }

        // 2. First line for alarm triggers: Acquire CPU & Screen WakeLocks immediately
        AlarmWakeLockManager.acquireWakeLocks(context)

        val alarmId = safeIntent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
        val alarmLabel = safeIntent.getStringExtra(AlarmScheduler.EXTRA_ALARM_LABEL)
        val soundIndex = safeIntent.getIntExtra(AlarmScheduler.EXTRA_SOUND_INDEX, 0)
        val deleteAfter = safeIntent.getBooleanExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING, false)

        Log.i(
            "AlarmDiag",
            "[RECEIVER] Triggered at ${System.currentTimeMillis()} action=$action alarmId=$alarmId label=$alarmLabel"
        )

        when (action) {
            AlarmScheduler.ACTION_DISMISS_ALARM -> {
                Log.d("AlarmDiag", "[RECEIVER] Dismiss action for alarmId=$alarmId")
                val repository = AlarmRepository.getInstance(context)
                repository.handleAlarmDismissed(alarmId)
                AlarmRingingService.stopRinging(context, alarmId)
            }

            AlarmScheduler.ACTION_SNOOZE_ALARM -> {
                // Snooze is permanently disabled under IronDiscipline protocols
                Log.d("AlarmDiag", "[RECEIVER] Snooze action (disabled) for alarmId=$alarmId")
                AlarmRingingService.stopRinging(context, alarmId)
            }

            AlarmScheduler.ACTION_TRIGGER_ALARM, "com.irondiscipline.app.alarm.TRIGGER_ALARM", null -> {
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

                // Note: Background startActivity is deliberately omitted here.
                // On Android 10+ background activity starts from BroadcastReceiver are restricted
                // or delayed when screen is locked/OFF. AlarmRingingService promotes to foreground
                // within milliseconds, immediately starts sound and vibration, and brings the activity to front!
            }
        }
    }
}
