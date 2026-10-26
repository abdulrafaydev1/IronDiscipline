package com.irondiscipline.app.alarm

import android.app.KeyguardManager
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
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.irondiscipline.app.R
import com.irondiscipline.app.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground Service that plays the synthesized PCM hard alarm sound and drives heavy vibration.
 * Promotes to systemExempted foreground service and STARTS AUDIO/VIBRATION IMMEDIATELY,
 * fully decoupled from lock-screen Activity launch delays.
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
    private var isScreenStateReceiverRegistered = false

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

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if (isCurrentlyRinging) {
                if (action == Intent.ACTION_SCREEN_OFF) {
                    Log.i(TAG, "[IronAlarmTrace:SCREEN_OFF_INTERCEPT] User pressed Power button during alarm. Re-waking screen immediately!")
                    AlarmWakeLockManager.acquireRingingWakeLocks(applicationContext)
                    bringAlarmScreenToFront(applicationContext)
                    mainHandler.postDelayed({
                        if (isCurrentlyRinging) {
                            bringAlarmScreenToFront(applicationContext)
                        }
                    }, 80L)
                } else if (action == Intent.ACTION_CLOSE_SYSTEM_DIALOGS) {
                    Log.d(TAG, "[IronAlarmTrace:CLOSE_DIALOGS_INTERCEPT] Close system dialogs event detected while alarm is ringing")
                    bringAlarmScreenToFront(applicationContext)
                }
            }
        }
    }

    fun unregisterReceiversCleanly() {
        if (isUnlockReceiverRegistered) {
            try {
                unregisterReceiver(unlockReceiver)
            } catch (_: Exception) {}
            isUnlockReceiverRegistered = false
        }
        if (isScreenStateReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {}
            isScreenStateReceiverRegistered = false
        }
    }

    companion object {
        const val TAG = "IronAlarmTrace"
        const val CHANNEL_ID = "iron_alarm_lockscreen_channel_v2"
        const val NOTIFICATION_ID = 2026

        @Volatile
        var serviceInstance: AlarmRingingService? = null

        @Volatile
        var isCurrentlyRinging: Boolean = false

        @Volatile
        var currentRingingAlarmId: String? = null

        @Volatile
        var currentRingingAlarmLabel: String? = null

        fun stopAndCleanup(context: Context, alarmId: String? = null) {
            val targetAlarmId = alarmId ?: currentRingingAlarmId
            Log.i(TAG, "[IronAlarmTrace:STAGE7_DISMISS] stopAndCleanup invoked for alarmId=$targetAlarmId, previousRingingId=$currentRingingAlarmId")

            isCurrentlyRinging = false
            currentRingingAlarmId = null
            currentRingingAlarmLabel = null

            serviceInstance?.unregisterReceiversCleanly()

            ActiveAlarmSessionState.clearSession()
            HardAlarmSoundEngine.getInstance().stopSound()
            AlarmWakeLockManager.releaseAll()
            AlarmRingingActivity.releaseLockTaskIfActive()

            val prefs = context.getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("is_alarm_currently_ringing", false).commit()

            // Post-ringing storage sync:
            if (targetAlarmId != null) {
                try {
                    val repository = AlarmRepository.getInstance(context)
                    repository.handleAlarmDismissed(targetAlarmId)
                } catch (e: Exception) {
                    Log.e(TAG, "[IronAlarmTrace:STAGE7_DISMISS_ERROR] Error in repository handleAlarmDismissed: ${e.message}")
                }
            }

            val intent = Intent(context, AlarmRingingService::class.java)
            context.stopService(intent)
        }

        fun stopRinging(context: Context, alarmId: String? = null) {
            stopAndCleanup(context, alarmId)
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
                Log.d(TAG, "[IronAlarmTrace:STAGE6_ACTIVITY] bringAlarmScreenToFront dispatched successfully")
            } catch (e: Exception) {
                Log.e(TAG, "[IronAlarmTrace:STAGE6_ACTIVITY_ERROR] Exception launching AlarmRingingActivity: ${e.message}")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceInstance = this
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
            Log.e("AlarmDiag", "[SERVICE_START] Error registering unlockReceiver: ${e.message}")
        }

        // Register dynamic receiver for SCREEN_OFF and CLOSE_SYSTEM_DIALOGS to immediately re-wake screen if user presses power button
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        }
        try {
            ContextCompat.registerReceiver(this, screenStateReceiver, screenFilter, ContextCompat.RECEIVER_EXPORTED)
            isScreenStateReceiverRegistered = true
        } catch (e: Exception) {
            Log.e("AlarmDiag", "[SERVICE_START] Error registering screenStateReceiver: ${e.message}")
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

        // Step A: Acquire WakeLocks immediately
        AlarmWakeLockManager.acquireWakeLocks(this)

        // Step B: Promote to foreground (< 50ms)
        val notification = buildRingingNotification(alarmId, alarm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
                Log.i(TAG, "[IronAlarmTrace:STAGE4_FGS_START] ServiceCompat.startForeground with mediaPlayback SUCCESS for Alarm ID=$alarmId")
            } catch (e: Exception) {
                Log.w(TAG, "[IronAlarmTrace:STAGE4_FGS_WARN] mediaPlayback startForeground failed: ${e.message}, falling back to specialUse")
                try {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } catch (_: Exception) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Update active ringing state and metadata
        isCurrentlyRinging = true
        currentRingingAlarmId = alarmId
        currentRingingAlarmLabel = alarm?.label ?: alarmLabel
        currentlyRingingAlarmId = alarmId

        // Synchronously record ringing state to persistent preferences
        val prefs = getSharedPreferences("iron_discipline_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("is_alarm_currently_ringing", true).commit()

        if (alarmId != null) {
            ActiveAlarmSessionState.initSession(alarmId)
        }

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false
        val isInteractive = powerManager?.isInteractive ?: false
        val canFullScreen = PermissionHelper.canUseFullScreenIntent(this)
        Log.d(
            TAG,
            "[IronAlarmTrace:FGS_METRICS] canUseFullScreenIntent=$canFullScreen, isKeyguardLocked=$isKeyguardLocked, isInteractive=$isInteractive"
        )

        // Step C: START AUDIO & VIBRATION IMMEDIATELY (Decoupled from Activity lifecycle)
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Request Audio Focus with USAGE_ALARM to pause all external media
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* maintain alarm */ }
                .build()
            try {
                am?.requestAudioFocus(focusRequest)
            } catch (e: Exception) {
                Log.w(TAG, "[IronAlarmTrace:AUDIO_WARN] Audio focus request error: ${e.message}")
            }
        } else {
            @Suppress("DEPRECATION")
            try {
                am?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            } catch (_: Exception) {}
        }

        // Max Volume Override & 1-Second Periodic Volume Guard
        try {
            am?.let {
                val maxVol = it.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                it.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            }
        } catch (_: Exception) {}

        volumeGuardJob?.cancel()
        volumeGuardJob = serviceScope.launch {
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

        // Play Selected High-Decibel Hard Sound
        val soundIndex = alarm?.soundIndex ?: intent?.getIntExtra(AlarmScheduler.EXTRA_SOUND_INDEX, 0) ?: 0
        soundEngine.playAlarmSound(soundIndex)

        // Cancel any lingering vibration and start fresh Heavy Vibration Pulse
        try { vibrator?.cancel() } catch (_: Exception) {}
        startHeavyVibration()

        val receiverTriggerMs = intent?.getLongExtra(AlarmReceiver.EXTRA_RECEIVER_TRIGGER_MS, 0L) ?: 0L
        val audioStartNow = System.currentTimeMillis()
        val triggerToAudioDelayMs = if (receiverTriggerMs > 0L) audioStartNow - receiverTriggerMs else -1L

        val currentVol = am?.getStreamVolume(AudioManager.STREAM_ALARM) ?: -1
        val maxVol = am?.getStreamMaxVolume(AudioManager.STREAM_ALARM) ?: -1
        Log.i(
            TAG,
            "[IronAlarmTrace:STAGE5_AUDIO_START] AudioTrack.play() active for Alarm ID=$alarmId, soundIndex=$soundIndex, STREAM_ALARM vol=$currentVol/$maxVol, latencyFromReceiver=${triggerToAudioDelayMs}ms, Vibrator active=true, isKeyguardLocked=$isKeyguardLocked, isInteractive=$isInteractive"
        )

        // Step D: THEN bring AlarmRingingActivity to front
        bringAlarmScreenToFront(this)

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
            data = Uri.parse("irondiscipline://alarm_ring/${alarmId ?: "active"}")
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }
        val requestCode = if (alarmId != null) AlarmScheduler.getRequestCode(alarmId) else 101
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
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
        Log.d("AlarmDiag", "[DISMISS_CLEANUP] Stopping AlarmRingingService, audio stopped, vibrator cancelled, WakeLocks released")

        isCurrentlyRinging = false
        currentRingingAlarmId = null
        currentRingingAlarmLabel = null
        currentlyRingingAlarmId = null

        unregisterReceiversCleanly()
        if (serviceInstance === this) {
            serviceInstance = null
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }
}
