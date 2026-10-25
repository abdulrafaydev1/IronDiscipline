package com.irondiscipline.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.irondiscipline.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service that plays the synthesized PCM hard alarm sound and drives heavy vibration.
 * Maintains full-screen notification intent and sets the anti-cheat flag in SharedPreferences.
 */
class AlarmRingingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private var volumeGuardJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private val soundEngine = HardAlarmSoundEngine.getInstance()

    companion object {
        const val CHANNEL_ID = "iron_discipline_hard_alarm_channel"
        const val NOTIFICATION_ID = 2026

        fun stopRinging(context: Context, alarmId: String? = null) {
            val intent = Intent(context, AlarmRingingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IronDiscipline:AlarmRingingLock")?.apply {
            setReferenceCounted(false)
            acquire(15 * 60 * 1000L) // 15 mins timeout safety
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        // Anti-Cheat: block calculator apps while alarm is actively ringing
        val prefs = getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_alarm_currently_ringing", true).commit()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        val repository = AlarmRepository.getInstance(applicationContext)
        val alarm = if (alarmId != null) repository.getAlarmById(alarmId) else null

        val notification = buildRingingNotification(alarmId, alarm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // 1. Max Volume Override & 1-Second Periodic Volume Guard
        volumeGuardJob?.cancel()
        volumeGuardJob = serviceScope.launch {
            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            while (isActive) {
                try {
                    am?.let {
                        val maxVol = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                        if (it.getStreamVolume(AudioManager.STREAM_ALARM) < maxVol) {
                            it.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
                        }
                    }
                } catch (_: Exception) {}
                delay(1000L)
            }
        }

        // 2. Play Selected High-Decibel Hard Sound
        val soundIndex = alarm?.soundIndex ?: 0
        soundEngine.startLoopingSound(soundIndex)

        // 3. Heavy Vibration Pulse (if enabled)
        if (alarm?.vibrateWhileRinging != false) {
            startHeavyVibration()
        }

        return START_STICKY
    }

    private fun startHeavyVibration() {
        val v = vibrator ?: return
        val pattern = longArrayOf(0, 600, 300, 600)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 0)
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                v.vibrate(effect, audioAttributes)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, 0)
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hard Discipline Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority wake up alerts for IronDiscipline"
                setSound(null, null) // AudioTrack handles sound
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildRingingNotification(alarmId: String?, alarm: AlarmItem?): Notification {
        val title = if (alarm != null) "🚨 ALARM: ${alarm.formattedTime}" else "🚨 DISCIPLINE ALARM RINGING"
        val label = alarm?.label ?: "Wake up and conquer the day."

        val fullScreenIntent = Intent(this, AlarmRingingActivity::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            alarmId?.hashCode() ?: 101,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Math Hard is mandatory: Notification only opens AlarmRingingActivity.
        // No direct dismiss or snooze action buttons allowed.
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()
    }

    override fun onDestroy() {
        volumeGuardJob?.cancel()
        soundEngine.stopSound()

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

        // Release Anti-Cheat flag
        val prefs = getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_alarm_currently_ringing", false).commit()

        super.onDestroy()
    }
}
