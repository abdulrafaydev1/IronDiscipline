package com.irondiscipline.app.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.irondiscipline.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service that plays the synthesized PCM hard alarm sound and drives heavy vibration.
 * Maintains full-screen notification intent, holds WakeLocks, launches AlarmRingingActivity
 * directly over the lock screen, and guards maximum alarm volume.
 */
class AlarmRingingService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var volumeGuardJob: Job? = null
    private var vibrator: Vibrator? = null
    private val soundEngine = HardAlarmSoundEngine.getInstance()

    @Volatile
    private var currentlyRingingAlarmId: String? = null

    private var isUnlockReceiverRegistered = false

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if ((action == Intent.ACTION_USER_PRESENT || action == Intent.ACTION_SCREEN_ON) && isCurrentlyRinging) {
                bringAlarmScreenToFront(context)
                mainHandler.postDelayed({
                    if (isCurrentlyRinging) {
                        bringAlarmScreenToFront(context)
                    }
                }, 150L)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "iron_alarm_lockscreen_channel_v2"
        const val NOTIFICATION_ID = 2026

        @Volatile
        var isCurrentlyRinging: Boolean = false

        @Volatile
        var currentRingingAlarmId: String? = null

        @Volatile
        var currentRingingAlarmLabel: String? = null

        fun stopRinging(context: Context, alarmId: String? = null) {
            isCurrentlyRinging = false
            currentRingingAlarmId = null
            currentRingingAlarmLabel = null
            val intent = Intent(context, AlarmRingingService::class.java)
            context.stopService(intent)
        }

        fun bringAlarmScreenToFront(context: Context) {
            if (!isCurrentlyRinging) return
            val intent = Intent(context, AlarmRingingActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, currentRingingAlarmId)
                putExtra(AlarmScheduler.EXTRA_ALARM_LABEL, currentRingingAlarmLabel)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Acquire CPU and Screen WakeLocks immediately
        AlarmWakeLockManager.acquireWakeLocks(this)

        // Register dynamic receiver for phone unlock (ACTION_USER_PRESENT) and screen wake (ACTION_SCREEN_ON)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        try {
            ContextCompat.registerReceiver(this, unlockReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            isUnlockReceiverRegistered = true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        // Anti-Cheat: block distracting apps while alarm is actively ringing
        val prefs = getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_alarm_currently_ringing", true).commit()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        val alarmLabel = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_LABEL)
        val repository = AlarmRepository.getInstance(applicationContext)
        val alarm = if (alarmId != null) repository.getAlarmById(alarmId) else null

        // Re-ensure WakeLocks are held
        AlarmWakeLockManager.acquireWakeLocks(this)

        // 1. Build and post high-priority lock-screen notification
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

        // Update active ringing state and metadata
        isCurrentlyRinging = true
        currentRingingAlarmId = alarmId
        currentRingingAlarmLabel = alarm?.label ?: alarmLabel

        // 2. Bring existing or new AlarmRingingActivity to front
        bringAlarmScreenToFront(this)

        // 3. Prevent duplicate playback threads if already ringing for this alarm
        if (currentlyRingingAlarmId != null && currentlyRingingAlarmId == alarmId) {
            return START_STICKY
        }
        currentlyRingingAlarmId = alarmId

        // 4. Max Volume Override & 1-Second Periodic Volume Guard
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

        // 5. Play Selected High-Decibel Hard Sound
        val soundIndex = alarm?.soundIndex ?: intent?.getIntExtra(AlarmScheduler.EXTRA_SOUND_INDEX, 0) ?: 0
        soundEngine.startLoopingSound(soundIndex)

        // 6. Heavy Vibration Pulse
        startHeavyVibration()

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
                "Iron Discipline Hard Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority wake up alerts for IronDiscipline"
                setSound(null, null) // Handled by HardAlarmSoundEngine AudioTrack
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
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
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            alarmId?.hashCode() ?: 101,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()
    }

    override fun onDestroy() {
        isCurrentlyRinging = false
        currentRingingAlarmId = null
        currentRingingAlarmLabel = null
        currentlyRingingAlarmId = null

        if (isUnlockReceiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
            } catch (_: Exception) {}
            isUnlockReceiverRegistered = false
        }
        mainHandler.removeCallbacksAndMessages(null)

        volumeGuardJob?.cancel()
        soundEngine.stopSound()

        try {
            vibrator?.cancel()
        } catch (_: Exception) {}

        // Release WakeLocks and Active Alarm State
        AlarmWakeLockManager.releaseAll()
        ActiveAlarmSessionState.clearSession()

        // Release Anti-Cheat flag
        val prefs = getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_alarm_currently_ringing", false).commit()

        super.onDestroy()
    }
}
