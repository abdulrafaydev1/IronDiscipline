package com.irondiscipline.app.util

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat
import com.irondiscipline.app.domain.model.PermissionStatusState
import com.irondiscipline.app.service.AppBlockerAccessibilityService

object PermissionHelper {

    fun checkAllPermissions(
        context: Context,
        usageTrackerHelper: UsageTrackerHelper
    ): PermissionStatusState {
        val isAdmin = isDeviceAdminActive(context)
        val isOwner = isDeviceOwner(context)
        val isBlocked = if (isOwner) isUninstallBlockedBySystem(context) else false
        return PermissionStatusState(
            hasUsageStatsPermission = usageTrackerHelper.hasUsageStatsPermission(),
            hasOverlayPermission = Settings.canDrawOverlays(context),
            isAccessibilityServiceEnabled = isAccessibilityServiceEnabled(context),
            isBatteryOptimizationExempt = isIgnoringBatteryOptimizations(context),
            hasNotificationPermission = hasNotificationPermission(context),
            isDeviceAdminActive = isAdmin,
            isDeviceOwnerActive = isOwner,
            isUninstallBlocked = isBlocked
        )
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedComponent = ComponentName(context, AppBlockerAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)
        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            val enabledComponent = ComponentName.unflattenFromString(componentNameString)
            if (enabledComponent != null && enabledComponent == expectedComponent) {
                return true
            }
        }
        return false
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun createUsageAccessIntent(context: Context): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createFallbackUsageAccessIntent(): Intent {
        return Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createOverlayPermissionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    @Suppress("BatteryLife")
    fun createBatteryOptimizationExemptionIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.canScheduleExactAlarms() ?: true
        } else {
            true
        }
    }

    fun createRequestExactAlarmIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val method = nm?.javaClass?.getMethod("canUseFullScreenIntent")
                (method?.invoke(nm) as? Boolean) ?: true
            } catch (_: Throwable) {
                true
            }
        } else {
            true
        }
    }

    fun createManageFullScreenIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT").apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun getDeviceAdminComponent(context: Context): ComponentName {
        return ComponentName(context, com.irondiscipline.app.FocusDeviceAdminReceiver::class.java)
    }

    fun isDeviceAdminActive(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val adminComponent = getDeviceAdminComponent(context)
        return try {
            dpm?.isAdminActive(adminComponent) == true
        } catch (_: Exception) {
            false
        }
    }

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        return try {
            dpm?.isDeviceOwnerApp(context.packageName) == true
        } catch (_: Exception) {
            false
        }
    }

    fun isUninstallBlockedBySystem(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val adminComponent = getDeviceAdminComponent(context)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm?.isUninstallBlocked(adminComponent, context.packageName) == true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun setUninstallBlocked(context: Context, blocked: Boolean): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager ?: return false
        val adminComponent = getDeviceAdminComponent(context)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.setUninstallBlocked(adminComponent, context.packageName, blocked)
                    android.util.Log.i("PermissionHelper", "dpm.setUninstallBlocked($blocked) succeeded as Device Owner")
                    true
                } else {
                    android.util.Log.w("PermissionHelper", "setUninstallBlocked called but app is not Device Owner (ordinary Device Admin cannot call this)")
                    false
                }
            } else {
                false
            }
        } catch (e: SecurityException) {
            android.util.Log.e("PermissionHelper", "SecurityException in setUninstallBlocked: ${e.message}")
            false
        } catch (e: Exception) {
            android.util.Log.e("PermissionHelper", "Exception in setUninstallBlocked: ${e.message}")
            false
        }
    }

    fun removeDeviceAdmin(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager ?: return false
        val adminComponent = getDeviceAdminComponent(context)
        return try {
            if (dpm.isAdminActive(adminComponent)) {
                dpm.removeActiveAdmin(adminComponent)
                true
            } else {
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("PermissionHelper", "Failed to remove active admin: ${e.message}")
            false
        }
    }

    fun createEnableDeviceAdminIntent(context: Context): Intent {
        val adminComponent = getDeviceAdminComponent(context)
        return Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Enable Device Admin to prevent accidental home-launcher uninstallation of IronDiscipline without entering your 4-digit PIN."
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    // --- Official Android Lock Task Mode & Global Actions (Power Menu) Restriction ---

    fun isLockTaskPermitted(context: Context): Boolean {
        val dpm = getDevicePolicyManager(context) ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.isLockTaskPermitted(context.packageName)
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    fun isLockTaskActive(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            am.isInLockTaskMode
        }
    }

    fun isLockTaskLockedByDeviceOwner(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_LOCKED
        } else {
            false
        }
    }

    /**
     * Activates official Android Lock Task Mode to restrict the Power-Off menu while the alarm is ringing.
     *
     * In accordance with official Android SDK device management:
     * - Only a Device Owner (or Profile Owner) can configure Lock Task features.
     * - By omitting LOCK_TASK_FEATURE_GLOBAL_ACTIONS, Android's PhoneWindowManager disables the
     *   Global Actions dialog (Power-Off, Restart, Lockdown) when the user presses the power button.
     * - Essential status information (LOCK_TASK_FEATURE_SYSTEM_INFO) is retained so the user can
     *   monitor battery, time, and signal.
     * - If Device Owner is not provisioned, this method safely stands down and logs diagnostic details
     *   to prevent launching an unmanaged screen pinning dialog.
     */
    fun enableLockTaskForAlarm(activity: Activity): Boolean {
        val context = activity.applicationContext
        if (!isDeviceOwner(context)) {
            Log.i(
                "PermissionHelper",
                "Device Owner is NOT active. Ordinary third-party Android apps and standard Device Admins " +
                "cannot suppress the system Global Actions (power-off) menu. " +
                "To enable power-off suppression, provision Device Owner via ADB: " +
                "adb shell dpm set-device-owner com.irondiscipline.app/.FocusDeviceAdminReceiver"
            )
            return false
        }

        val dpm = getDevicePolicyManager(context) ?: return false
        val admin = getDeviceAdminComponent(context)
        val pkg = activity.packageName

        return try {
            // 1. Whitelist our package for Lock Task mode if not already whitelisted
            val currentPackages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                dpm.getLockTaskPackages(admin)
            } else {
                emptyArray()
            }

            if (!currentPackages.contains(pkg)) {
                val updatedPackages = (currentPackages.toSet() + pkg).toTypedArray()
                dpm.setLockTaskPackages(admin, updatedPackages)
                Log.i("PermissionHelper", "Added $pkg to LockTask whitelisted packages")
            }

            // 2. Configure Lock Task features:
            // Explicitly set LOCK_TASK_FEATURE_SYSTEM_INFO while OMITTING LOCK_TASK_FEATURE_GLOBAL_ACTIONS.
            // This causes Android's PhoneWindowManager to disable the power-off / restart menu while in lock task mode.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO)
                Log.i("PermissionHelper", "Configured LockTask features: omitted LOCK_TASK_FEATURE_GLOBAL_ACTIONS to suppress power menu")
            }

            // 3. Silently enter Lock Task mode (no user confirmation prompt because package is whitelisted)
            activity.startLockTask()

            val isLocked = isLockTaskLockedByDeviceOwner(context)
            Log.i("PermissionHelper", "LockTaskMode active: $isLocked")
            isLocked
        } catch (e: SecurityException) {
            Log.w("PermissionHelper", "SecurityException while enabling LockTaskMode for alarm: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e("PermissionHelper", "Error enabling LockTaskMode for alarm: ${e.message}", e)
            false
        }
    }

    /**
     * Disables Lock Task Mode cleanly and restores standard system interaction.
     * Called when the alarm is dismissed or upon Activity destruction.
     */
    fun disableLockTaskForAlarm(activity: Activity) {
        val context = activity.applicationContext
        try {
            val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val isInLockTask = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            } else {
                @Suppress("DEPRECATION")
                am?.isInLockTaskMode == true
            }

            if (isInLockTask) {
                activity.stopLockTask()
                Log.i("PermissionHelper", "Stopped LockTaskMode successfully")
            }

            // Restore default LockTask features if Device Owner
            if (isDeviceOwner(context) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val dpm = getDevicePolicyManager(context)
                val admin = getDeviceAdminComponent(context)
                dpm?.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
        } catch (e: Exception) {
            Log.w("PermissionHelper", "Exception while stopping LockTaskMode: ${e.message}")
        }
    }
}
