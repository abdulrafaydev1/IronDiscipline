package com.irondiscipline.app.alarm

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Dual WakeLock Manager that guarantees CPU execution and forces display panel wake-up
 * from deep sleep when an IronDiscipline alarm fires.
 */
object AlarmWakeLockManager {

    private const val TAG = "AlarmWakeLockManager"

    @Volatile
    private var cpuWakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var screenWakeLock: PowerManager.WakeLock? = null

    /**
     * Acquires CPU partial wake lock and screen bright wake lock with CAUSES_WAKEUP.
     */
    @Synchronized
    fun acquireWakeLocks(context: Context, timeoutMs: Long = 15 * 60 * 1000L) {
        val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return

        // 1. CPU WakeLock (PARTIAL_WAKE_LOCK) - guarantees continuous AudioTrack PCM audio & vibration
        try {
            if (cpuWakeLock == null) {
                cpuWakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "IronDiscipline:AlarmCpuWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (cpuWakeLock?.isHeld != true) {
                cpuWakeLock?.acquire(timeoutMs)
                Log.d(TAG, "CPU WakeLock acquired ($timeoutMs ms timeout)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire CPU WakeLock", e)
        }

        // 2. Screen WakeLock - Physically forces the screen to turn ON from deep sleep
        try {
            @Suppress("DEPRECATION")
            val screenFlags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE

            if (screenWakeLock == null) {
                screenWakeLock = pm.newWakeLock(
                    screenFlags,
                    "IronDiscipline:AlarmScreenWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (screenWakeLock?.isHeld != true) {
                screenWakeLock?.acquire(60 * 1000L) // 60s is ample to wake screen and render activity
                Log.d(TAG, "Screen WakeLock acquired (forces display ON)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire Screen WakeLock", e)
        }
    }

    /**
     * Safely releases both screen and CPU WakeLocks.
     */
    @Synchronized
    fun releaseAll() {
        try {
            if (screenWakeLock?.isHeld == true) {
                screenWakeLock?.release()
                Log.d(TAG, "Screen WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing screen WakeLock", e)
        } finally {
            screenWakeLock = null
        }

        try {
            if (cpuWakeLock?.isHeld == true) {
                cpuWakeLock?.release()
                Log.d(TAG, "CPU WakeLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing CPU WakeLock", e)
        } finally {
            cpuWakeLock = null
        }
    }
}
