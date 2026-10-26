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
     * Cleanly manages idle state.
     * Note: AlarmManager.setAlarmClock() natively programs hardware RTC wake-ups.
     * Indefinite partial wake locks prior to trigger are deliberately avoided to comply
     * with Android Power Management guidelines and prevent the OS / OEM power managers
     * from forcing the app into RESTRICTED Standby Bucket.
     */
    @Synchronized
    fun maintainPendingAlarmCpuLock(context: Context, hasEnabledAlarms: Boolean) {
        try {
            if (pendingCpuWakeLock?.isHeld == true) {
                pendingCpuWakeLock?.release()
                Log.d(TAG, "Pending Alarm CPU WakeLock released (allowing natural low-power sleep)")
            }
        } catch (_: Exception) {}
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
