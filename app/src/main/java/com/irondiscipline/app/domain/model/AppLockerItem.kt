package com.irondiscipline.app.domain.model

import androidx.compose.ui.graphics.ImageBitmap

data class AppLockerItem(
    val packageName: String,
    val appName: String,
    val iconBitmap: ImageBitmap? = null,
    val isLocked: Boolean = false
)
