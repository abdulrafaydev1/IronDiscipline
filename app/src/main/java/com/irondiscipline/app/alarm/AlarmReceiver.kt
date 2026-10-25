package com.irondiscipline.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * BroadcastReceiver triggered by AlarmManager.setAlarmClock.
 * Hands off execution immediately to AlarmRingingService and launches AlarmRingingActivity.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val safeIntent = intent ?: return
        val alarmId = safeIntent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return

        when (safeIntent.action) {
            AlarmScheduler.ACTION_DISMISS_ALARM -> {
                // Dismiss action from notification
                val repository = AlarmRepository.getInstance(context)
                repository.handleAlarmDismissed(alarmId)
                AlarmRingingService.stopRinging(context)
            }

            AlarmScheduler.ACTION_SNOOZE_ALARM -> {
                // Snooze action from notification
                val repository = AlarmRepository.getInstance(context)
                val alarm = repository.getAlarmById(alarmId)
                if (alarm != null) {
                    AlarmScheduler.getInstance(context).scheduleSnooze(alarm, alarm.snoozeMinutes)
                }
                AlarmRingingService.stopRinging(context)
            }

            AlarmScheduler.ACTION_TRIGGER_ALARM, null -> {
                // Primary Alarm Trigger: start foreground ringing service & full-screen activity
                val serviceIntent = Intent(context, AlarmRingingService::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }

                val activityIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                }
                context.startActivity(activityIntent)
            }
        }
    }
}
