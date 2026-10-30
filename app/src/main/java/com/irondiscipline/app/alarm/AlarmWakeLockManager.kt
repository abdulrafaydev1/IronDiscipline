package com.irondiscipline.app.alarm

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * High-Reliability WakeLock Manager.
 * 1. maintainPendingAlarmCpuLock: Holds PARTIAL_WAKE_LOCK whenever an alarm is enabled,
 *    guaranteeing CPU & Handler loops never freeze during screen-off sleep.
 * 2. acquireRingingWakeLocks: Turns the physical screen ON and holds CPU during ringing.
 */
object AlarmWakeLockManager {
    private const val TAG = "AlarmWakeLockManager"

    @Volatile
    private var pendingCpuWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var ringingCpuWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var ringingScreenWakeLock: PowerManager.WakeLock? = null

    /**
     * Holds CPU PARTIAL_WAKE_LOCK during pending alarms and the 5-minute follow-up countdown,
     * ensuring background loops, watchdog timers, and AlarmManager events never freeze during screen-off sleep.
     */
    @Synchronized
    fun maintainPendingAlarmCpuLock(context: Context, hasEnabledAlarms: Boolean) {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        try {
            if (hasEnabledAlarms) {
                if (pendingCpuWakeLock == null) {
                    pendingCpuWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "IronDiscipline:PendingCpuWakeLock"
                    ).apply { setReferenceCounted(false) }
                }
                if (pendingCpuWakeLock?.isHeld == false) {
                    pendingCpuWakeLock?.acquire(10 * 60 * 1000L) // 10-minute safety timeout
                    Log.d(TAG, "Pending Alarm CPU WakeLock acquired (10m timeout for 5-min follow-up)")
                }
            } else {
                if (pendingCpuWakeLock?.isHeld == true) {
                    pendingCpuWakeLock?.release()
                    Log.d(TAG, "Pending Alarm CPU WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error managing pendingCpuWakeLock: ${e.message}")
        }
    }

    /**
     * Call this the exact millisecond an alarm triggers to wake both the CPU and the physical screen!
     */
    @Suppress("DEPRECATION")
    @Synchronized
    fun acquireRingingWakeLocks(context: Context) {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

        if (ringingCpuWakeLock == null) {
            ringingCpuWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "IronDiscipline:RingingCpuWakeLock"
            ).apply { setReferenceCounted(false) }
        }
        if (ringingCpuWakeLock?.isHeld == false) {
            try {
                ringingCpuWakeLock?.acquire(30 * 60 * 1000L)
                Log.d(TAG, "Ringing CPU WakeLock acquired (30m timeout)")
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring ringing CPU WakeLock", e)
            }
        }

        if (ringingScreenWakeLock == null) {
            ringingScreenWakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "IronDiscipline:RingingScreenWakeLock"
            ).apply { setReferenceCounted(false) }
        }
        if (ringingScreenWakeLock?.isHeld == false) {
            try {
                ringingScreenWakeLock?.acquire(10 * 60 * 1000L)
                Log.d(TAG, "Ringing Screen WakeLock acquired (forces display ON)")
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring ringing Screen WakeLock", e)
            }
        }
    }

    /**
     * Backwards-compatible alias for acquireRingingWakeLocks.
     */
    fun acquireWakeLocks(context: Context) {
        acquireRingingWakeLocks(context)
    }

    @Synchronized
    fun releaseRingingWakeLocks() {
        try {
            if (ringingCpuWakeLock?.isHeld == true) {
                ringingCpuWakeLock?.release()
                Log.d(TAG, "Ringing CPU WakeLock released")
            }
        } catch (_: Exception) {}

        try {
            if (ringingScreenWakeLock?.isHeld == true) {
                ringingScreenWakeLock?.release()
                Log.d(TAG, "Ringing Screen WakeLock released")
            }
        } catch (_: Exception) {}
    }

    /**
     * Releases ringing wake locks (alias).
     */
    fun releaseAll() {
        releaseRingingWakeLocks()
    }
}
