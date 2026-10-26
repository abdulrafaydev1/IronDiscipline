package com.irondiscipline.app

import android.app.Application
import com.irondiscipline.app.data.local.IronDisciplineDatabase
import com.irondiscipline.app.service.DisciplineMonitorService
import com.irondiscipline.app.util.PermissionHelper
import com.irondiscipline.app.util.RealtimeQuotaManager
import com.irondiscipline.app.util.SecurityPinManager

class IronDisciplineApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // 1. Reset any stale authorization sessions immediately to prevent security bypasses
        SecurityPinManager.resetAuthorizationSession(this)
        SecurityPinManager.ensureInitialized(this)

        IronDisciplineDatabase.getInstance(this)
        RealtimeQuotaManager.getInstance(this)
        DisciplineMonitorService.startServiceIfNeeded(this)

        // 2. If Device Owner privileges exist, ensure OS-level uninstall restriction & Lock Task whitelist are active
        if (PermissionHelper.isDeviceOwner(this)) {
            PermissionHelper.setUninstallBlocked(this, true)
            PermissionHelper.whitelistLockTaskPackage(this)
        }
    }

    companion object {
        @Volatile
        var isMainActivityVisible: Boolean = false
    }
}
