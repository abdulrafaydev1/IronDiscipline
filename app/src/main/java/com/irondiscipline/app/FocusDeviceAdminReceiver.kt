package com.irondiscipline.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class FocusDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i("FocusDeviceAdmin", "IronDiscipline Device Admin Protection Enabled successfully")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i("FocusDeviceAdmin", "IronDiscipline Device Admin Protection Disabled")
    }
}
