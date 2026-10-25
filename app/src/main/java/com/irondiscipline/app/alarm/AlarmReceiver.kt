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
        AlarmWakeLockManager.acquireRingingWakeLocks(context)

        val alarmId = safeIntent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
            ?: safeIntent.getStringExtra("EXTRA_ALARM_ID") ?: return
        val alarmLabel = safeIntent.getStringExtra(AlarmScheduler.EXTRA_ALARM_LABEL)
            ?: safeIntent.getStringExtra("EXTRA_ALARM_LABEL")
        val soundIndex = if (safeIntent.hasExtra(AlarmScheduler.EXTRA_SOUND_INDEX)) {
            safeIntent.getIntExtra(AlarmScheduler.EXTRA_SOUND_INDEX, 0)
        } else {
            safeIntent.getIntExtra("EXTRA_SOUND_INDEX", 0)
        }
        val deleteAfter = if (safeIntent.hasExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING)) {
            safeIntent.getBooleanExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING, false)
        } else {
            safeIntent.getBooleanExtra("EXTRA_DELETE_AFTER_RINGING", false)
        }

        Log.i(
            "AlarmDiag",
            "[RECEIVER] Triggered at ${System.currentTimeMillis()} action=$action alarmId=$alarmId label=$alarmLabel"
        )

        when (action) {
            AlarmScheduler.ACTION_DISMISS_ALARM -> {
                Log.d("AlarmDiag", "[RECEIVER] Dismiss action for alarmId=$alarmId")
                AlarmRingingService.stopAndCleanup(context, alarmId)
            }

            AlarmScheduler.ACTION_SNOOZE_ALARM -> {
                // Snooze is permanently disabled under IronDiscipline protocols
                Log.d("AlarmDiag", "[RECEIVER] Snooze action (disabled) for alarmId=$alarmId")
                AlarmRingingService.stopAndCleanup(context, alarmId)
            }

            else -> {
                // Record timestamp to avoid duplicate triggers within 60s from watchdog
                val prefs = context.getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("last_triggered_timestamp_$alarmId", System.currentTimeMillis()).commit()

                // Initialize persistent Math Hard questions session state for this alarm
                ActiveAlarmSessionState.initSession(alarmId)

                // Start Foreground Ringing Service (High Priority Audio & 100% Volume Guard)
                val serviceIntent = Intent(context, AlarmRingingService::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, alarmLabel)
                    putExtra(AlarmScheduler.EXTRA_SOUND_INDEX, soundIndex)
                    putExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING, deleteAfter)
                    putExtra("EXTRA_IS_NEW_TRIGGER", true)
                }
                ContextCompat.startForegroundService(context, serviceIntent)

                // Also launch AlarmRingingActivity directly over lock screen
                val activityIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, alarmLabel)
                    putExtra(AlarmScheduler.EXTRA_SOUND_INDEX, soundIndex)
                    putExtra(AlarmScheduler.EXTRA_DELETE_AFTER_RINGING, deleteAfter)
                    putExtra("EXTRA_IS_NEW_TRIGGER", true)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
                try {
                    context.startActivity(activityIntent)
                } catch (e: Exception) {
                    Log.e("AlarmDiag", "[RECEIVER] Failed to launch AlarmRingingActivity directly: ${e.message}")
                }
            }
        }
    }
}
