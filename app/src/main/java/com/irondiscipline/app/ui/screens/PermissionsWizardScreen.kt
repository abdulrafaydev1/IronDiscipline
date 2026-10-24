package com.irondiscipline.app.ui.screens

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.domain.model.PermissionStatusState
import com.irondiscipline.app.ui.theme.DarkBg
import com.irondiscipline.app.ui.theme.DarkBorder
import com.irondiscipline.app.ui.theme.DarkCard
import com.irondiscipline.app.ui.theme.NeonAmber
import com.irondiscipline.app.ui.theme.NeonCyan
import com.irondiscipline.app.ui.theme.NeonLime
import com.irondiscipline.app.ui.theme.TextPrimary
import com.irondiscipline.app.ui.theme.TextSecondary
import com.irondiscipline.app.util.PermissionHelper

@Composable
fun PermissionsWizardScreen(
    permissionState: PermissionStatusState,
    onRefreshPermissions: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefreshPermissions()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header Status Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    1.5.dp,
                    if (permissionState.allCriticalGranted) NeonLime else NeonCyan,
                    RoundedCornerShape(18.dp)
                ),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard)
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (permissionState.allPermissionsGranted) {
                            "ALL SYSTEMS ARMED (100%)"
                        } else {
                            "PERMISSIONS SETUP WIZARD (${permissionState.grantedCount}/4)"
                        },
                        color = if (permissionState.allCriticalGranted) NeonLime else NeonCyan,
                        fontWeight = FontWeight.Black,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Grant each system permission below so IronDiscipline can monitor quotas, intercept browsers, and display the full-screen Block Overlay.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onRefreshPermissions) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Status",
                        tint = NeonCyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // 1. UsageStatsManager Permission
        PermissionStepCard(
            stepNumber = 1,
            title = "Usage Access (UsageStatsManager)",
            description = "Required for accurate daily screen-time calculation and enforcing daily usage quotas to the exact minute.",
            icon = Icons.Default.QueryStats,
            isGranted = permissionState.hasUsageStatsPermission,
            actionButtonLabel = "Grant Usage Access",
            onGrantClick = {
                launchSafely(context) {
                    try {
                        context.startActivity(PermissionHelper.createUsageAccessIntent(context))
                    } catch (_: Exception) {
                        context.startActivity(PermissionHelper.createFallbackUsageAccessIntent())
                    }
                }
            }
        )

        // 2. SYSTEM_ALERT_WINDOW Overlay Permission
        PermissionStepCard(
            stepNumber = 2,
            title = "Display Over Other Apps (Overlay)",
            description = "Required to display the instant, un-dismissible full-screen Motivational Block Overlay when a restricted app is opened.",
            icon = Icons.Default.Layers,
            isGranted = permissionState.hasOverlayPermission,
            actionButtonLabel = "Grant Overlay Permission",
            onGrantClick = {
                launchSafely(context) {
                    context.startActivity(PermissionHelper.createOverlayPermissionIntent(context))
                }
            }
        )

        // 3. AccessibilityService
        PermissionStepCard(
            stepNumber = 3,
            title = "Accessibility Service (Focus Shield)",
            description = "Required for real-time foreground app detection, browser URL bar inspection (Chrome/Brave/Firefox), and Strict Mode Settings protection.",
            icon = Icons.Default.AccessibilityNew,
            isGranted = permissionState.isAccessibilityServiceEnabled,
            actionButtonLabel = "Enable in Accessibility",
            onGrantClick = {
                launchSafely(context) {
                    context.startActivity(PermissionHelper.createAccessibilitySettingsIntent())
                }
            }
        )

        // 4. Battery Optimization Exemption
        PermissionStepCard(
            stepNumber = 4,
            title = "Battery Optimization Exemption",
            description = "Prevents Android from putting IronDiscipline's background monitoring engine to sleep during long focus sessions.",
            icon = Icons.Default.BatteryChargingFull,
            isGranted = permissionState.isBatteryOptimizationExempt,
            actionButtonLabel = "Disable Battery Restrictions",
            onGrantClick = {
                launchSafely(context) {
                    context.startActivity(
                        PermissionHelper.createBatteryOptimizationExemptionIntent(context)
                    )
                }
            }
        )

        // 5. Foreground Notification Permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionStepCard(
                stepNumber = 5,
                title = "Persistent Shield Notification",
                description = "Displays your active Discipline Streak and Strict Mode lock status in the status bar.",
                icon = Icons.Default.NotificationsActive,
                isGranted = permissionState.hasNotificationPermission,
                actionButtonLabel = "Allow Notifications",
                onGrantClick = {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun PermissionStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    actionButtonLabel: String,
    onGrantClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (isGranted) NeonLime.copy(alpha = 0.5f) else DarkBorder,
                RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (isGranted) NeonLime.copy(alpha = 0.15f)
                            else NeonAmber.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isGranted) Icons.Default.CheckCircle else icon,
                        contentDescription = null,
                        tint = if (isGranted) NeonLime else NeonAmber,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Step $stepNumber • $title",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = if (isGranted) "GRANTED & ACTIVE" else "ACTION REQUIRED",
                        color = if (isGranted) NeonLime else NeonAmber,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = description,
                color = TextSecondary,
                fontSize = 13.sp
            )

            if (!isGranted) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onGrantClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = DarkBg
                    )
                ) {
                    Text(
                        text = actionButtonLabel,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
    }
}

private fun launchSafely(context: Context, block: () -> Unit) {
    try {
        block()
    } catch (_: Exception) {
        // Fallback if specific OEM settings intent is unavailable
    }
}
