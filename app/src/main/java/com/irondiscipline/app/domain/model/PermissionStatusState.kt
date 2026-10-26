package com.irondiscipline.app.domain.model

data class PermissionStatusState(
    val hasUsageStatsPermission: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val isAccessibilityServiceEnabled: Boolean = false,
    val isBatteryOptimizationExempt: Boolean = false,
    val hasNotificationPermission: Boolean = true,
    val isDeviceAdminActive: Boolean = false
) {
    val allCriticalGranted: Boolean
        get() = hasUsageStatsPermission && hasOverlayPermission && isAccessibilityServiceEnabled

    val allPermissionsGranted: Boolean
        get() = allCriticalGranted && isBatteryOptimizationExempt && hasNotificationPermission && isDeviceAdminActive

    val grantedCount: Int
        get() = listOf(
            hasUsageStatsPermission,
            hasOverlayPermission,
            isAccessibilityServiceEnabled,
            isBatteryOptimizationExempt,
            isDeviceAdminActive
        ).count { it }
}
