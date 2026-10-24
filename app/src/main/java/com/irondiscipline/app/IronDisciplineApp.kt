package com.irondiscipline.app

import android.app.Application
import com.irondiscipline.app.data.local.IronDisciplineDatabase
import com.irondiscipline.app.service.DisciplineMonitorService
import com.irondiscipline.app.util.RealtimeQuotaManager

class IronDisciplineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        IronDisciplineDatabase.getInstance(this)
        RealtimeQuotaManager.getInstance(this)
        DisciplineMonitorService.startServiceIfNeeded(this)
    }

    companion object {
        @Volatile
        var isMainActivityVisible: Boolean = false
    }
}
