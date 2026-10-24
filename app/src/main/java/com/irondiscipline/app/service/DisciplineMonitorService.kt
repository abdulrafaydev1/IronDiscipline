package com.irondiscipline.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.irondiscipline.app.MainActivity
import com.irondiscipline.app.R
import com.irondiscipline.app.data.local.IronDisciplineDatabase
import com.irondiscipline.app.data.repository.DisciplineRepository
import com.irondiscipline.app.util.PermissionHelper
import com.irondiscipline.app.util.UsageTrackerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Persistent ForegroundService that ensures the IronDiscipline quota engine,
 * daily usage synchronization, and streak progression are never killed by the OS.
 */
class DisciplineMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    private lateinit var repository: DisciplineRepository
    private lateinit var usageTrackerHelper: UsageTrackerHelper

    override fun onCreate() {
        super.onCreate()
        val database = IronDisciplineDatabase.getInstance(applicationContext)
        usageTrackerHelper = UsageTrackerHelper.getInstance(applicationContext)
        repository = DisciplineRepository(database.disciplineDao(), usageTrackerHelper)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = buildOngoingNotification(
            streakDays = 1,
            isStrictActive = false,
            isShieldArmed = PermissionHelper.isAccessibilityServiceEnabled(this)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        startBackgroundSyncLoop()
        return START_STICKY
    }

    private fun startBackgroundSyncLoop() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            while (isActive) {
                try {
                    val state = repository.getOrCreateDisciplineState()
                    val strictActive = repository.isStrictModeCurrentlyEnforced()
                    repository.syncTodayUsageLogs()

                    val updatedNotification = buildOngoingNotification(
                        streakDays = state.currentStreakDays,
                        isStrictActive = strictActive,
                        isShieldArmed = PermissionHelper.isAccessibilityServiceEnabled(applicationContext)
                    )
                    val notificationManager =
                        getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    notificationManager?.notify(NOTIFICATION_ID, updatedNotification)
                } catch (_: Exception) {
                    // Keep monitoring loop alive regardless of transient I/O errors
                }
                delay(30_000L)
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "IronDiscipline Active Shield",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the IronDiscipline app blocker and quota tracker active 24/7"
            setShowBadge(false)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.createNotificationChannel(channel)
    }

    private fun buildOngoingNotification(
        streakDays: Int,
        isStrictActive: Boolean,
        isShieldArmed: Boolean
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusTitle = when {
            isStrictActive -> "IRON STRICT MODE LOCKED • Streak: ${streakDays}d"
            isShieldArmed -> "Focus Shield Active • Streak: ${streakDays}d"
            else -> "Action Needed: Enable Accessibility Shield"
        }

        val statusBody = if (isStrictActive) {
            "Anti-bypass protection & distraction quotas actively enforced."
        } else {
            "Monitoring daily social media quotas and scheduled focus windows."
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_lock)
            .setContentTitle(statusTitle)
            .setContentText(statusBody)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "iron_discipline_monitor_channel"
        private const val NOTIFICATION_ID = 1001

        fun startServiceIfNeeded(context: Context) {
            try {
                val intent = Intent(context.applicationContext, DisciplineMonitorService::class.java)
                ContextCompatStarter.startForeground(context.applicationContext, intent)
            } catch (_: Exception) {
                // On Android 12+ background start restrictions may defer until next foreground / accessibility bind
            }
        }
    }
}

private object ContextCompatStarter {
    fun startForeground(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}
