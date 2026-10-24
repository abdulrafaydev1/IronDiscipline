package com.irondiscipline.app.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.domain.model.PermissionStatusState
import com.irondiscipline.app.ui.theme.DarkBg
import com.irondiscipline.app.ui.theme.DarkBorder
import com.irondiscipline.app.ui.theme.DarkCard
import com.irondiscipline.app.ui.theme.DarkSurface
import com.irondiscipline.app.ui.theme.NeonAmber
import com.irondiscipline.app.ui.theme.NeonCrimson
import com.irondiscipline.app.ui.theme.NeonCyan
import com.irondiscipline.app.ui.theme.NeonLime
import com.irondiscipline.app.ui.theme.TextMuted
import com.irondiscipline.app.ui.theme.TextPrimary
import com.irondiscipline.app.ui.theme.TextSecondary
import com.irondiscipline.app.util.CoreAppQuotaStatus
import com.irondiscipline.app.util.CoreTargetApp
import com.irondiscipline.app.util.PermissionHelper
import com.irondiscipline.app.util.RealtimeQuotaSnapshot

@Composable
fun DashboardScreen(
    realtimeQuotaState: RealtimeQuotaSnapshot,
    permissionState: PermissionStatusState,
    onActivateOneMinuteTestMode: () -> Unit,
    onSwitchBackToNormalThirtyMinMode: () -> Unit,
    onResetAllTimers: () -> Unit,
    onResetSingleApp: (appKey: String) -> Unit,
    onRefreshPermissions: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val isTestMode = realtimeQuotaState.isTestModeActive

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Permission Check Card (Appears if Accessibility or Overlay is missing)
        if (!permissionState.allCriticalGranted) {
            PermissionSetupCard(
                context = context,
                permissionState = permissionState,
                onRefreshPermissions = onRefreshPermissions
            )
        } else {
            ShieldActiveStatusBanner()
        }

        // 2. Mode & Test Control Bar
        ModeAndTestControlCard(
            isTestMode = isTestMode,
            onActivateOneMinuteTestMode = onActivateOneMinuteTestMode,
            onSwitchBackToNormalThirtyMinMode = onSwitchBackToNormalThirtyMinMode,
            onResetAllTimers = onResetAllTimers
        )

        // 3. Section Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TARGET APPLICATIONS (4)",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.1.sp
            )
            Text(
                text = if (isTestMode) "1-MIN TEST LIMIT" else "FIXED 30-MIN LIMIT",
                color = if (isTestMode) NeonAmber else NeonCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black
            )
        }

        // 4. Four Individual App Status Cards (YouTube, Facebook, Instagram, TikTok)
        for (target in CoreTargetApp.entries) {
            val status = realtimeQuotaState.statusFor(target.appKey) ?: CoreAppQuotaStatus(
                target = target,
                usedSeconds = 0,
                limitSeconds = if (isTestMode) 60 else 1800,
                remainingSeconds = if (isTestMode) 60 else 1800,
                isLocked = false,
                lockedUntilEpochMs = 0L,
                lockRemainingMillis = 0L
            )

            FixedAppQuotaCard(
                status = status,
                isTestMode = isTestMode,
                onResetApp = { onResetSingleApp(target.appKey) }
            )
        }

        Spacer(modifier = Modifier.height(18.dp))
    }
}

/**
 * Clean permission setup card shown prominently when critical permissions are missing.
 */
@Composable
private fun PermissionSetupCard(
    context: Context,
    permissionState: PermissionStatusState,
    onRefreshPermissions: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, NeonAmber, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = NeonAmber,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Permissions Setup Required",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "Enable required permissions to detect & block target apps.",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Accessibility Service Action Button
            if (!permissionState.isAccessibilityServiceEnabled) {
                Button(
                    onClick = {
                        context.startActivity(PermissionHelper.createAccessibilitySettingsIntent())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonAmber,
                        contentColor = DarkBg
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessibilityNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Enable Accessibility Service",
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Display Over Other Apps Action Button
            if (!permissionState.hasOverlayPermission) {
                Button(
                    onClick = {
                        context.startActivity(PermissionHelper.createOverlayPermissionIntent(context))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = DarkBg
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Enable Overlay Permission",
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Refresh Status Button
            OutlinedButton(
                onClick = onRefreshPermissions,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Check Permission Status",
                    color = TextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
 * Compact banner shown when all permissions are granted and the shield is active.
 */
@Composable
private fun ShieldActiveStatusBanner() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, NeonLime.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(NeonLime)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = null,
                tint = NeonLime,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = "Focus Shield Armed & Monitoring",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Auto-blocks each app after 30 mins with zero sound leak.",
                    color = TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Mode & Test Control Bar.
 * - Displays active rule: "Fixed Limit: 30 Mins Per App" or "Test Mode: 1 Min Per App"
 * - Button 1: Toggle between 1-Min Test Mode and Fixed 30-Min Normal Mode
 * - Button 2: Reset All App Timers
 */
@Composable
private fun ModeAndTestControlCard(
    isTestMode: Boolean,
    onActivateOneMinuteTestMode: () -> Unit,
    onSwitchBackToNormalThirtyMinMode: () -> Unit,
    onResetAllTimers: () -> Unit
) {
    val borderColor = if (isTestMode) NeonAmber else NeonCyan

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Active Rule display + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isTestMode) Icons.Default.Bolt else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isTestMode) NeonAmber else NeonCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = if (isTestMode) "Test Mode: 1 Min Per App" else "Fixed Limit: 30 Mins Per App",
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp
                        )
                        Text(
                            text = if (isTestMode) "Testing active: apps lock after 60 seconds." else "Strict daily quota enforced per app.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isTestMode) NeonAmber.copy(alpha = 0.2f) else NeonCyan.copy(alpha = 0.2f))
                        .border(1.dp, if (isTestMode) NeonAmber else NeonCyan, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isTestMode) "1-MIN TEST" else "30-MIN FIXED",
                        color = if (isTestMode) NeonAmber else NeonCyan,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Button 1: Toggle Mode
                if (isTestMode) {
                    Button(
                        onClick = onSwitchBackToNormalThirtyMinMode,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonCyan,
                            contentColor = DarkBg
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Set 30-Min Mode",
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Button(
                        onClick = onActivateOneMinuteTestMode,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NeonAmber,
                            contentColor = DarkBg
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "⚡ 1-Min Test Mode",
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    }
                }

                // Button 2: Reset All App Timers
                Button(
                    onClick = onResetAllTimers,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonLime,
                        contentColor = DarkBg
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Reset All Timers",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * 4 Individual App Status Cards (YouTube, Facebook, Instagram, TikTok).
 * Cleanly displays:
 * - App Name
 * - Fixed Allowance Badge: "Fixed Time: 30 Mins" (or "Test Time: 1 Min")
 * - Live Remaining Time Today: "MM:SS remaining" (updates every second)
 * - Current Status: "ACTIVE / UNLOCKED" (Green) or "BLOCKED (Unlocks in HH:MM:SS)" (Red)
 */
@Composable
private fun FixedAppQuotaCard(
    status: CoreAppQuotaStatus,
    isTestMode: Boolean,
    onResetApp: () -> Unit
) {
    val isLocked = status.isLocked
    val cardBorderColor = if (isLocked) NeonCrimson else DarkBorder

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, cardBorderColor, RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: App Name + Fixed Allowance Badge + Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (isLocked) NeonCrimson.copy(alpha = 0.2f) else NeonCyan.copy(alpha = 0.2f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (isLocked) NeonCrimson else NeonCyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = status.target.displayName,
                            color = TextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 17.sp
                        )
                        // Fixed Allowance Badge
                        Text(
                            text = if (isTestMode) "Test Time: 1 Min" else "Fixed Time: 30 Mins",
                            color = if (isTestMode) NeonAmber else NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Current Status Badge: ACTIVE / UNLOCKED (Green) or BLOCKED (Red)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isLocked) NeonCrimson.copy(alpha = 0.18f) else NeonLime.copy(alpha = 0.18f)
                        )
                        .border(
                            1.dp,
                            if (isLocked) NeonCrimson else NeonLime,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isLocked) {
                            "BLOCKED (${status.formattedLockRemainingHhMmSs})"
                        } else {
                            "ACTIVE / UNLOCKED"
                        },
                        color = if (isLocked) NeonCrimson else NeonLime,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = if (isLocked) FontFamily.Monospace else FontFamily.Default
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Live Remaining Time Today
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLocked) {
                        "Usage time exhausted (Limit: ${status.formattedLimitMinutes})"
                    } else {
                        "${status.formattedRemainingMmSs} remaining"
                    },
                    color = if (isLocked) NeonCrimson else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "${status.usedSeconds}s used / ${status.limitSeconds / 60}m limit",
                    color = TextMuted,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Progress Bar
            LinearProgressIndicator(
                progress = { status.progressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (isLocked) NeonCrimson else NeonCyan,
                trackColor = DarkSurface
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Footer Row: Reset single app
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLocked) {
                        "🚫 Locked until midnight (or reset)"
                    } else {
                        "Independent 30-min allowance"
                    },
                    color = if (isLocked) NeonCrimson.copy(alpha = 0.8f) else TextMuted,
                    fontSize = 11.sp
                )

                OutlinedButton(
                    onClick = onResetApp,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset",
                        tint = NeonLime,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Reset App",
                        color = NeonLime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
