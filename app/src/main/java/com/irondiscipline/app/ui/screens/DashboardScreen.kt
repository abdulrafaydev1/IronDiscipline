package com.irondiscipline.app.ui.screens

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.domain.model.PermissionStatusState
import com.irondiscipline.app.ui.components.UninstallPinDialog
import com.irondiscipline.app.ui.components.jellyElastic
import com.irondiscipline.app.util.CoreAppQuotaStatus
import com.irondiscipline.app.util.CoreTargetApp
import com.irondiscipline.app.util.PermissionHelper
import com.irondiscipline.app.util.RealtimeQuotaSnapshot

// Obsidian Cyber-Discipline Color Palette
private val ColorObsidianDeep = Color(0xFF06080E)
private val ColorObsidianSlate = Color(0xFF0D121D)
private val ColorElevatedSlate = Color(0xFF141B29)
private val ColorCardBorderStart = Color(0xFF26334D)
private val ColorCardBorderEnd = Color(0xFF141B29)

private val ColorElectricEmerald = Color(0xFF00E676)
private val ColorEmeraldDark = Color(0xFF10B981)
private val ColorCyberAmber = Color(0xFFFFB300)
private val ColorAmberDark = Color(0xFFF59E0B)
private val ColorCrimsonNeon = Color(0xFFFF2E54)
private val ColorCrimsonDark = Color(0xFFDC2626)

private val ColorTextPrimary = Color(0xFFFFFFFF)
private val ColorTextSecondary = Color(0xFF8A99AD)
private val ColorTextMuted = Color(0xFF5E6D82)

// App Brand Gradient Definitions
private val YouTubeGradient = listOf(Color(0xFFFF0033), Color(0xFFCC0000))
private val InstagramGradient = listOf(Color(0xFFF58529), Color(0xFFDD2A7B), Color(0xFF8134AF))
private val FacebookGradient = listOf(Color(0xFF1877F2), Color(0xFF0C5DC7))
private val TikTokGradient = listOf(Color(0xFF00F2FE), Color(0xFFFE0979))

@Composable
fun DashboardScreen(
    realtimeQuotaState: RealtimeQuotaSnapshot,
    permissionState: PermissionStatusState,
    onActivateOneMinuteTestMode: () -> Unit,
    onSwitchBackToNormalThirtyMinMode: () -> Unit,
    onRefreshPermissions: () -> Unit = {},
    onVerifyPin: (String) -> Boolean = { true },
    onConfirmUninstall: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val isTestMode = realtimeQuotaState.isTestModeActive
    var showUninstallPinDialog by remember { mutableStateOf(false) }

    if (showUninstallPinDialog) {
        UninstallPinDialog(
            onDismiss = { showUninstallPinDialog = false },
            onVerifyPin = onVerifyPin,
            onConfirmUninstall = {
                showUninstallPinDialog = false
                onConfirmUninstall()
            }
        )
    }

    // Calculate aggregated daily statistics for the Hero Gauge
    val totalLimitSeconds = realtimeQuotaState.coreStatuses.sumOf { it.limitSeconds }.coerceAtLeast(1)
    val totalUsedSeconds = realtimeQuotaState.coreStatuses.sumOf { it.usedSeconds }
    val totalRemainingSeconds = (totalLimitSeconds - totalUsedSeconds).coerceAtLeast(0)
    val remainingFraction = (totalRemainingSeconds.toFloat() / totalLimitSeconds.toFloat()).coerceIn(0f, 1f)
    val remainingPercent = (remainingFraction * 100).toInt()
    val unlockedCount = realtimeQuotaState.coreStatuses.count { !it.isLocked }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(ColorObsidianDeep, ColorObsidianSlate)
                )
            )
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Top "Command Header" & Live Animated Shield Status Pill
        CommandHeaderSection(
            permissionState = permissionState
        )

        // 2. Hero Motivational & Daily Focus Banner Card (with Circular Discipline Gauge)
        HeroDisciplineBannerCard(
            unlockedCount = unlockedCount,
            totalAppsCount = CoreTargetApp.entries.size,
            remainingFraction = remainingFraction,
            remainingPercent = remainingPercent,
            isTestMode = isTestMode
        )

        // 3. Sleek Permission Alert Card (Conditional: shown if critical permissions or Device Admin missing)
        if (!permissionState.allCriticalGranted || !permissionState.isDeviceAdminActive) {
            SleekPermissionAlertCard(
                context = context,
                permissionState = permissionState,
                onRefreshPermissions = onRefreshPermissions
            )
        }

        // 4. Tactical Control Capsule (Auto-Resets on Toggle + Emergency Delete App)
        TacticalControlCapsule(
            isTestMode = isTestMode,
            isAnyAppLocked = realtimeQuotaState.anyAppLocked,
            onActivateOneMinuteTestMode = onActivateOneMinuteTestMode,
            onSwitchBackToNormalThirtyMinMode = onSwitchBackToNormalThirtyMinMode,
            onDeleteAppClick = { showUninstallPinDialog = true }
        )

        // 5. Section Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FLAGSHIP TARGET APPLICATIONS (4)",
                color = ColorTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp
            )
            Text(
                text = if (isTestMode) "DIAGNOSTIC 1-MIN" else "STRICT 30-MIN",
                color = if (isTestMode) ColorCyberAmber else ColorElectricEmerald,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
        }

        // 6. Premium Per-App Cards (YouTube, Facebook, Instagram, TikTok)
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

            PremiumAppDisciplineCard(
                status = status,
                isTestMode = isTestMode
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

/**
 * 1. Top "Command Header" with pulsating Live Shield Status Pill.
 */
@Composable
private fun CommandHeaderSection(
    permissionState: PermissionStatusState
) {
    val isShieldArmed = permissionState.allCriticalGranted

    // Pulsing alpha animation for the status dot
    val infiniteTransition = rememberInfiniteTransition(label = "ShieldPulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "IRON DISCIPLINE",
                color = ColorTextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "PERSONAL FOCUS COMMAND CENTER",
                color = ColorTextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.0.sp
            )
        }

        // Animated Shield Status Pill
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isShieldArmed) Color(0x1A00E676) else Color(0x26FFB300)
                )
                .border(
                    width = 1.dp,
                    color = if (isShieldArmed) ColorElectricEmerald.copy(alpha = 0.5f) else ColorCyberAmber.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .alpha(dotAlpha)
                        .background(if (isShieldArmed) ColorElectricEmerald else ColorCyberAmber)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isShieldArmed) "SHIELD ACTIVE" else "SETUP REQUIRED",
                    color = if (isShieldArmed) ColorElectricEmerald else ColorCyberAmber,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

/**
 * 2. Hero Motivational & Daily Focus Banner Card with Circular Discipline Gauge.
 */
@Composable
private fun HeroDisciplineBannerCard(
    unlockedCount: Int,
    totalAppsCount: Int,
    remainingFraction: Float,
    remainingPercent: Int,
    isTestMode: Boolean
) {
    val animatedProgress by animateFloatAsState(
        targetValue = remainingFraction,
        animationSpec = tween(durationMillis = 700),
        label = "HeroGaugeProgress"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF2E3E60), Color(0xFF182233))
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF182236), Color(0xFF0F1524))
                    )
                )
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left Column: Motivational Copy & Stats
                Column(modifier = Modifier.weight(1f)) {
                    // Tagline Chip
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x2600E676))
                            .border(0.8.dp, Color(0x6600E676), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "MINDSET OVER IMPULSE",
                            color = ColorElectricEmerald,
                            fontWeight = FontWeight.Black,
                            fontSize = 9.sp,
                            letterSpacing = 1.1.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Guard Your Time.\nBuild Your Empire.",
                        color = ColorTextPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 19.sp,
                        lineHeight = 25.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "$unlockedCount OF $totalAppsCount APPS UNLOCKED • FOCUS STRONG",
                        color = ColorTextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Right Column: Circular Discipline Gauge
                Box(
                    modifier = Modifier.size(76.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(76.dp)) {
                        val strokeWidth = 7.dp.toPx()

                        // Background ring track
                        drawArc(
                            color = Color(0xFF1F2B40),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )

                        // Glowing sweep progress
                        val gaugeGradient = Brush.sweepGradient(
                            listOf(
                                ColorElectricEmerald,
                                Color(0xFF00F5D4),
                                ColorElectricEmerald
                            )
                        )

                        drawArc(
                            brush = gaugeGradient,
                            startAngle = -90f,
                            sweepAngle = 360f * animatedProgress,
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "$remainingPercent%",
                            color = ColorTextPrimary,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "LEFT",
                            color = ColorTextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * 3. Sleek Permission Alert Card (Conditional).
 */
@Composable
private fun SleekPermissionAlertCard(
    context: Context,
    permissionState: PermissionStatusState,
    onRefreshPermissions: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(ColorCyberAmber, ColorAmberDark)
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF22170A), Color(0xFF160E06))
                    )
                )
                .padding(18.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = ColorCyberAmber,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Shield Permissions Required",
                            color = ColorTextPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Enable required permissions to detect & lock distracting apps.",
                            color = ColorCyberAmber.copy(alpha = 0.9f),
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (!permissionState.isAccessibilityServiceEnabled) {
                    Button(
                        onClick = {
                            context.startActivity(PermissionHelper.createAccessibilitySettingsIntent())
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .jellyElastic(baseCornerRadius = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ColorCyberAmber,
                            contentColor = ColorObsidianDeep
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
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!permissionState.hasOverlayPermission) {
                    Button(
                        onClick = {
                            context.startActivity(PermissionHelper.createOverlayPermissionIntent(context))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .jellyElastic(baseCornerRadius = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00F5D4),
                            contentColor = ColorObsidianDeep
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
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (!permissionState.isDeviceAdminActive) {
                    Button(
                        onClick = {
                            context.startActivity(PermissionHelper.createEnableDeviceAdminIntent(context))
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .jellyElastic(baseCornerRadius = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF38BDF8),
                            contentColor = ColorObsidianDeep
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "🛡️ Enable Device Admin (Uninstall Protection)",
                            fontWeight = FontWeight.Black,
                            fontSize = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedButton(
                    onClick = onRefreshPermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .jellyElastic(baseCornerRadius = 12.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF3B4860))
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = ColorTextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Verify Permission Status",
                        color = ColorTextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * 4. Tactical Control Capsule (Disabled when any app is locked for 5 hours).
 */
@Composable
private fun TacticalControlCapsule(
    isTestMode: Boolean,
    isAnyAppLocked: Boolean,
    onActivateOneMinuteTestMode: () -> Unit,
    onSwitchBackToNormalThirtyMinMode: () -> Unit,
    onDeleteAppClick: () -> Unit
) {
    val modeAccentColor by animateColorAsState(
        targetValue = when {
            isAnyAppLocked -> ColorCrimsonNeon
            isTestMode -> ColorCyberAmber
            else -> ColorElectricEmerald
        },
        animationSpec = tween(400),
        label = "ModeAccentColor"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = if (isAnyAppLocked) {
                        listOf(ColorCrimsonNeon.copy(alpha = 0.6f), ColorCrimsonDark.copy(alpha = 0.3f))
                    } else {
                        listOf(ColorCardBorderStart, ColorCardBorderEnd)
                    }
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAnyAppLocked) Color(0xFF160B10) else ColorElevatedSlate
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Mode Header Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when {
                            isAnyAppLocked -> Icons.Default.Lock
                            isTestMode -> Icons.Default.Bolt
                            else -> Icons.Default.Shield
                        },
                        contentDescription = null,
                        tint = modeAccentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            isAnyAppLocked -> "🔒 UNBREAKABLE 5-HOUR LOCK ACTIVE"
                            isTestMode -> "⚡ DIAGNOSTIC TEST: 1 MIN / APP"
                            else -> "🛡️ STRICT PROTOCOL: 30 MINS / APP"
                        },
                        color = modeAccentColor,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Single Full-Width Mode Toggle & Auto-Reset Button
            Button(
                onClick = {
                    if (!isAnyAppLocked) {
                        if (isTestMode) onSwitchBackToNormalThirtyMinMode() else onActivateOneMinuteTestMode()
                    }
                },
                enabled = !isAnyAppLocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .jellyElastic(enabled = !isAnyAppLocked, baseCornerRadius = 13.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        isAnyAppLocked -> Color(0xFF261017)
                        isTestMode -> Color(0xFF1E283A)
                        else -> Color(0xFF261D0E)
                    },
                    contentColor = when {
                        isAnyAppLocked -> ColorCrimsonNeon
                        isTestMode -> ColorElectricEmerald
                        else -> ColorCyberAmber
                    },
                    disabledContainerColor = Color(0xFF261017),
                    disabledContentColor = ColorCrimsonNeon
                ),
                border = BorderStroke(
                    width = 1.2.dp,
                    color = when {
                        isAnyAppLocked -> ColorCrimsonNeon
                        isTestMode -> ColorElectricEmerald
                        else -> ColorCyberAmber
                    }
                )
            ) {
                Icon(
                    imageVector = when {
                        isAnyAppLocked -> Icons.Default.Lock
                        isTestMode -> Icons.Default.Shield
                        else -> Icons.Default.Bolt
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when {
                        isAnyAppLocked -> "🔒 LOCKED: Test Mode Disabled Until 5-Hr Lock Expires"
                        isTestMode -> "🛡️ Switch to 30-Min Normal Mode (Auto-Resets)"
                        else -> "⚡ Activate 1-Min Test Mode (Auto-Resets)"
                    },
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sleek Crimson-Outlined Dark Metallic Uninstall Button (Always 100% Active & Clickable)
            Button(
                onClick = onDeleteAppClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .jellyElastic(enabled = true, baseCornerRadius = 13.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF200F15),
                    contentColor = ColorCrimsonNeon
                ),
                border = BorderStroke(
                    width = 1.2.dp,
                    color = ColorCrimsonNeon.copy(alpha = 0.85f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "🗑️ Delete App (Enter PIN)",
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 0.4.sp
                )
            }
        }
    }
}

/**
 * 5. Premium Per-App Flagship Cards (YouTube, Facebook, Instagram, TikTok).
 */
@Composable
private fun PremiumAppDisciplineCard(
    status: CoreAppQuotaStatus,
    isTestMode: Boolean
) {
    val isLocked = status.isLocked

    // Resolve Brand Gradient and Monogram
    val brandGradient = when (status.target) {
        CoreTargetApp.YOUTUBE -> YouTubeGradient
        CoreTargetApp.INSTAGRAM -> InstagramGradient
        CoreTargetApp.FACEBOOK -> FacebookGradient
        CoreTargetApp.TIKTOK -> TikTokGradient
    }

    val monogramText = when (status.target) {
        CoreTargetApp.YOUTUBE -> "YT"
        CoreTargetApp.INSTAGRAM -> "IG"
        CoreTargetApp.FACEBOOK -> "FB"
        CoreTargetApp.TIKTOK -> "TT"
    }

    // Card border: Crimson glow when locked, otherwise subtle obsidian gradient
    val cardBorder = if (isLocked) {
        BorderStroke(1.5.dp, ColorCrimsonNeon)
    } else {
        BorderStroke(
            1.dp,
            Brush.linearGradient(listOf(ColorCardBorderStart, ColorCardBorderEnd))
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(border = cardBorder, shape = RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ColorElevatedSlate)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Row 1: Brand Monogram Badge + Title & Allowance + Status Pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Custom Brand Monogram Badge
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(brandGradient)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = monogramText,
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = status.target.displayName,
                            color = ColorTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Text(
                            text = if (isTestMode) "Test Allowance • 1 Min" else "Daily Allowance • 30 Mins",
                            color = ColorTextSecondary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 11.sp
                        )
                    }
                }

                // Glowing Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isLocked) Color(0x26FF2E54) else Color(0x1A00E676)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isLocked) ColorCrimsonNeon else ColorElectricEmerald,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isLocked) "LOCKED FOR 5 HOURS" else "UNLOCKED",
                        color = if (isLocked) ColorCrimsonNeon else ColorElectricEmerald,
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Row 2: Big Digital Readout & Countdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                // Left: Time Remaining Today
                Column {
                    Text(
                        text = "TIME REMAINING TODAY",
                        color = ColorTextSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.0.sp
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (isLocked) "00:00 left" else "${status.formattedRemainingMmSs} left",
                        color = if (isLocked) ColorCrimsonNeon else ColorElectricEmerald,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.0.sp
                    )
                }

                // Right: Countdown or Usage Stats
                Column(horizontalAlignment = Alignment.End) {
                    if (isLocked) {
                        Text(
                            text = "5-HR PENALTY",
                            color = ColorCrimsonNeon,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.0.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = status.formattedLockRemainingHhMmSs,
                            color = ColorCrimsonNeon,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    } else {
                        Text(
                            text = "TIME USED TODAY",
                            color = ColorTextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.0.sp
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "${status.formattedUsedMmSs} used",
                            color = ColorCyberAmber,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 3: Bottom Animated Progress Bar
            val animatedFraction by animateFloatAsState(
                targetValue = status.progressFraction,
                animationSpec = tween(500),
                label = "AppProgress"
            )

            // Select fill brush: Crimson when locked, Amber when >80% used, else brand/emerald
            val fillBrush = when {
                isLocked -> Brush.linearGradient(listOf(ColorCrimsonNeon, ColorCrimsonDark))
                status.progressFraction >= 0.8f -> Brush.linearGradient(listOf(ColorCyberAmber, ColorAmberDark))
                else -> Brush.linearGradient(listOf(ColorElectricEmerald, ColorEmeraldDark))
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF0B0F17))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedFraction.coerceIn(0.01f, 1f))
                        .clip(RoundedCornerShape(4.dp))
                        .background(fillBrush)
                )
            }
        }
    }
}
