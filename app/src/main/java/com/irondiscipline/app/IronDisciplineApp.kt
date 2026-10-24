package com.irondiscipline.app

import android.app.Application
import com.irondiscipline.app.data.local.IronDisciplineDatabase
import com.irondiscipline.app.service.DisciplineMonitorService

class IronDisciplineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize Room DB & seed default High-Risk Rules & Schedules
        IronDisciplineDatabase.getInstance(this)
        DisciplineMonitorService.startServiceIfNeeded(this)
    }
}
