package com.irondiscipline.app.domain.model

import android.graphics.drawable.Drawable

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isHighRisk: Boolean,
    val isInstalledOnDevice: Boolean,
    val icon: Drawable? = null
)
