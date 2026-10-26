package com.irondiscipline.app

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.irondiscipline.app.util.PermissionHelper
import com.irondiscipline.app.util.SecurityPinManager

class FocusDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "IronDiscipline Device Admin Protection Enabled successfully")

        // If app has Device Owner privileges, actively enforce OS-level uninstall lock and whitelist Lock Task mode
        if (PermissionHelper.isDeviceOwner(context)) {
            val success = PermissionHelper.setUninstallBlocked(context, true)
            Log.i(TAG, "Enforced Device Owner setUninstallBlocked(true): $success")
            PermissionHelper.whitelistLockTaskPackage(context)
        }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.w(TAG, "User requested deactivation of Device Admin in System Settings")
        return "⚠️ IronDiscipline App Protection is ACTIVE.\n\nTo safely remove this application without breaking focus habits, open IronDiscipline and enter your 4-digit PIN in the App Protection dialog."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "IronDiscipline Device Admin Protection Disabled")
        // Reset authorization state cleanly
        SecurityPinManager.resetAuthorizationSession(context)
    }

    companion object {
        private const val TAG = "FocusDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context, FocusDeviceAdminReceiver::class.java)
        }
    }
}
