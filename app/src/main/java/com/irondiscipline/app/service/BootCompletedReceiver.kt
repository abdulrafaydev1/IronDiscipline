package com.irondiscipline.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.irondiscipline.app.util.PermissionHelper
import com.irondiscipline.app.util.SecurityPinManager

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            // 1. Invalidate any temporary uninstall authorization session on reboot/update
            SecurityPinManager.resetAuthorizationSession(context)
            SecurityPinManager.ensureInitialized(context)

            // 2. Re-assert Device Owner uninstall block at the system level if provisioned
            if (PermissionHelper.isDeviceOwner(context)) {
                PermissionHelper.setUninstallBlocked(context, true)
            }

            DisciplineMonitorService.startServiceIfNeeded(context)
            try {
                com.irondiscipline.app.util.RealtimeQuotaManager.getInstance(context).refreshSnapshotNow()
            } catch (_: Exception) {}
            try {
                com.irondiscipline.app.alarm.AlarmRepository.getInstance(context).rescheduleAllActiveAlarms()
            } catch (_: Exception) {}
        }
    }
}
