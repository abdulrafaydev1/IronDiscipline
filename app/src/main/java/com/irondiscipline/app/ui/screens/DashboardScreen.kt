package com.irondiscipline.app.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.irondiscipline.app.FocusDeviceAdminReceiver
import com.irondiscipline.app.domain.model.AppLockerItem
import com.irondiscipline.app.domain.model.PermissionStatusState
import com.irondiscipline.app.screenshare.ScreenViewerEngine
import com.irondiscipline.app.service.ScreenShareService
import com.irondiscipline.app.service.ScreenShareSessionManager
import com.irondiscipline.app.service.ScreenShareState
import com.irondiscipline.app.service.StreamCryptoEngine
import com.irondiscipline.app.ui.components.CreatePasswordDialog
import com.irondiscipline.app.ui.components.LockPhoneAppsDialog
import com.irondiscipline.app.ui.components.UninstallPinDialog
import com.irondiscipline.app.ui.components.jellyElastic
import com.irondiscipline.app.util.CoreAppQuotaStatus
import com.irondiscipline.app.util.CoreTargetApp
import com.irondiscipline.app.util.PermissionHelper
import com.irondiscipline.app.util.RealtimeQuotaSnapshot
import kotlinx.coroutines.launch

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
    isMasterPasswordEnabled: Boolean = false,
    lockedPackages: Set<String> = emptySet(),
    onSetMasterPassword: (String) -> Unit = {},
    onToggleCustomPackageLock: (String, Boolean) -> Unit = { _, _ -> },
    onLoadInstalledApps: suspend () -> List<AppLockerItem> = { emptyList() },
    onActivateOneMinuteTestMode: () -> Unit,
    onSwitchBackToNormalThirtyMinMode: () -> Unit,
    onRefreshPermissions: () -> Unit = {},
    onVerifyPin: (String) -> Boolean = { true },
    onConfirmUninstall: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val isTestMode = realtimeQuotaState.isTestModeActive
    var showUninstallPinDialog by remember { mutableStateOf(false) }
    var showCreatePasswordDialog by remember { mutableStateOf(false) }
    var showLockPhoneAppsDialog by remember { mutableStateOf(false) }
    var showScreenShareDialog by remember { mutableStateOf(false) }
    var installedAppsList by remember { mutableStateOf<List<AppLockerItem>>(emptyList()) }
    var isLoadingApps by remember { mutableStateOf(false) }

    if (showScreenShareDialog) {
        ScreenShareDialog(
            onDismiss = { showScreenShareDialog = false }
        )
    }

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

    if (showCreatePasswordDialog) {
        CreatePasswordDialog(
            isChangePassword = isMasterPasswordEnabled,
            onDismiss = { showCreatePasswordDialog = false },
            onConfirmPassword = { newPass ->
                showCreatePasswordDialog = false
                onSetMasterPassword(newPass)
            }
        )
    }

    if (showLockPhoneAppsDialog) {
        LockPhoneAppsDialog(
            installedApps = installedAppsList,
            lockedPackages = lockedPackages,
            onToggleLock = onToggleCustomPackageLock,
            onDismiss = { showLockPhoneAppsDialog = false },
            isLoading = isLoadingApps
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
        val dpm = remember { context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager }
        val adminComponent = remember { android.content.ComponentName(context, FocusDeviceAdminReceiver::class.java) }
        val isDeviceAdminActive = remember(permissionState) {
            try { dpm?.isAdminActive(adminComponent) == true } catch (_: Exception) { false }
        }

        // High-Visibility Banner: Prompt Device Admin if not enabled yet
        if (!isDeviceAdminActive) {
            DeviceAdminSetupBannerCard(
                onActivateClick = {
                    val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(
                            android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Prevents accidental uninstallation without your 4-digit PIN."
                        )
                    }
                    context.startActivity(intent)
                }
            )
        }

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

        // 4.5 Master App Password & Phone App Locker Section
        MasterSecurityVaultCard(
            isMasterPasswordEnabled = isMasterPasswordEnabled,
            lockedAppsCount = lockedPackages.size,
            onManagePasswordClick = {
                showCreatePasswordDialog = true
            },
            onLockPhoneAppsClick = {
                if (!isMasterPasswordEnabled) {
                    showCreatePasswordDialog = true
                } else {
                    showLockPhoneAppsDialog = true
                    coroutineScope.launch {
                        isLoadingApps = true
                        installedAppsList = onLoadInstalledApps()
                        isLoadingApps = false
                    }
                }
            },
            onLiveScreenShareClick = {
                showScreenShareDialog = true
            }
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

/**
 * High-Visibility Banner Card prompting the user to enable Device Admin Uninstall Protection.
 */
@Composable
fun DeviceAdminSetupBannerCard(
    onActivateClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(1.5.dp, Color(0xFF38BDF8)),
                RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "🛡️ Step Required: Enable Uninstall Protection",
                        color = ColorTextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Prevents accidental uninstallation without entering your 4-digit PIN.",
                        color = Color(0xFF38BDF8).copy(alpha = 0.85f),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onActivateClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .jellyElastic(baseCornerRadius = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF38BDF8),
                    contentColor = ColorObsidianDeep
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Activate Protection Shield",
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun MasterSecurityVaultCard(
    isMasterPasswordEnabled: Boolean,
    lockedAppsCount: Int,
    onManagePasswordClick: () -> Unit,
    onLockPhoneAppsClick: () -> Unit,
    onLiveScreenShareClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(Color(0xFF26334D), Color(0xFF141B29))
                ),
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ColorElevatedSlate)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "MASTER SECURITY & APP LOCKER",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 0.8.sp
                    )
                }

                // Status pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isMasterPasswordEnabled) Color(0x2600E676) else Color(0x26FFB300))
                        .border(
                            1.dp,
                            if (isMasterPasswordEnabled) Color(0xFF00E676) else Color(0xFFFFB300),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isMasterPasswordEnabled) "PROTECTED" else "PASSWORD NOT SET",
                        color = if (isMasterPasswordEnabled) Color(0xFF00E676) else Color(0xFFFFB300),
                        fontWeight = FontWeight.Black,
                        fontSize = 9.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Button 1: Create or Change Password & Lock Now
            Button(
                onClick = onManagePasswordClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .jellyElastic(baseCornerRadius = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isMasterPasswordEnabled) Color(0xFF38BDF8) else Color(0xFF1E293B),
                    contentColor = if (!isMasterPasswordEnabled) Color(0xFF06080E) else Color(0xFF38BDF8)
                ),
                border = if (isMasterPasswordEnabled) BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.6f)) else null
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (!isMasterPasswordEnabled) "🔐 Create App Password" else "🔐 Change Password / Lock App Now",
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Button 2: Lock Phone Apps
            Button(
                onClick = onLockPhoneAppsClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .jellyElastic(baseCornerRadius = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF161F30),
                    contentColor = Color(0xFFFFFFFF)
                ),
                border = BorderStroke(1.dp, Color(0xFF2E3E58))
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = Color(0xFF38BDF8),
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "🔒 Lock Phone Apps ($lockedAppsCount Locked)",
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Button 3: Live Screen Share (Share / Watch)
            val isSenderActive by ScreenShareSessionManager.isSenderActive.collectAsState()
            val senderRoomCode by ScreenShareSessionManager.senderRoomCode.collectAsState()
            val viewerSessions by ScreenShareSessionManager.viewerSessions.collectAsState()

            val isLiveActive = isSenderActive || viewerSessions.isNotEmpty()
            val shareButtonText = when {
                isSenderActive -> "🟢 Sharing Screen Live (#$senderRoomCode) — Tap to Open"
                viewerSessions.isNotEmpty() -> "🟢 Watching ${viewerSessions.size} Remote Screen(s) — Tap to Open"
                else -> "📡 Live Screen Share (Share / Watch)"
            }

            Button(
                onClick = onLiveScreenShareClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .jellyElastic(baseCornerRadius = 12.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLiveActive) Color(0xFF132B25) else Color(0xFF111927),
                    contentColor = Color(0xFFFFFFFF)
                ),
                border = BorderStroke(
                    1.dp,
                    if (isLiveActive) Color(0xFF00E676) else Color(0xFF38BDF8).copy(alpha = 0.6f)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = if (isLiveActive) Color(0xFF00E676) else Color(0xFF38BDF8),
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = shareButtonText,
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Interactive Dual-Mode Screen Share Dialog (Sender & Viewer).
 */
@Composable
fun ScreenShareDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val viewerSessions by ScreenShareSessionManager.viewerSessions.collectAsState()
    var selectedTab by rememberSaveable { mutableIntStateOf(if (ScreenShareSessionManager.viewerSessions.value.isNotEmpty()) 1 else 0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(listOf(Color(0xFF38BDF8), Color(0xFF141B29))),
                    shape = RoundedCornerShape(24.dp)
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A0F1A))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header Row
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
                                .background(Color(0x2638BDF8))
                                .border(1.dp, Color(0xFF38BDF8), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "LIVE SCREEN SHARE",
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "🔒 End-to-End Encrypted (AES-256-GCM) • Persistent Stream",
                                color = Color(0xFF00E676),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color(0xFF8A99AD)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tabs: Share (Sender) vs Watch (Viewer)
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color(0xFF141C2A),
                    contentColor = Color(0xFF38BDF8),
                    indicator = { tabPositions ->
                        TabRowDefaults.Indicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = Color(0xFF38BDF8),
                            height = 3.dp
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = "📤 Share Screen",
                                fontWeight = if (selectedTab == 0) FontWeight.Black else FontWeight.Normal,
                                color = if (selectedTab == 0) Color(0xFF38BDF8) else Color(0xFF8A99AD),
                                fontSize = 13.sp
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                text = if (viewerSessions.isNotEmpty()) "📺 Watch Screens (${viewerSessions.size})" else "📺 Watch Screen",
                                fontWeight = if (selectedTab == 1) FontWeight.Black else FontWeight.Normal,
                                color = if (selectedTab == 1) Color(0xFF38BDF8) else Color(0xFF8A99AD),
                                fontSize = 13.sp
                            )
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Content
                if (selectedTab == 0) {
                    SenderScreenTab(context = context)
                } else {
                    ViewerScreenTab()
                }
            }
        }
    }
}

@Composable
private fun EncryptionStatusBadge() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color(0xFF00E676).copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF081C15))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00E676))
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "🛡️ AES-256-GCM Encrypted (Video + Audio Protected)",
                    color = Color(0xFF00E676),
                    fontWeight = FontWeight.Black,
                    fontSize = 12.sp,
                    letterSpacing = 0.3.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "End-to-End Encrypted: Koi teesra phone ya server aapki screen ya aawaz nahi sun sakta.",
                    color = Color(0xFF86EFAC),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }
    }
}

@Composable
private fun SenderScreenTab(context: Context) {
    val isSharing by ScreenShareSessionManager.isSenderActive.collectAsState()
    val activeRoomCode by ScreenShareSessionManager.senderRoomCode.collectAsState()
    val framesSent by ScreenShareSessionManager.senderFramesSent.collectAsState()
    val statusText by ScreenShareSessionManager.senderStatusText.collectAsState()
    val selectedAudioMode by ScreenShareSessionManager.senderAudioMode.collectAsState()

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, ScreenShareService::class.java).apply {
                action = ScreenShareService.ACTION_START_SHARE
                putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenShareService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenShareService.EXTRA_ROOM_CODE, activeRoomCode)
                putExtra(ScreenShareService.EXTRA_AUDIO_MODE, selectedAudioMode)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager != null) {
            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EncryptionStatusBadge()
        Spacer(modifier = Modifier.height(14.dp))

        // Device Name Badge
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF26334D), RoundedCornerShape(14.dp)),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1522))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0x2638BDF8))
                        .border(1.dp, Color(0xFF38BDF8), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "YOUR PHONE'S BROADCAST NAME",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Black,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "📱 ${ScreenShareService.getLocalPhoneName()}",
                        color = Color(0xFFFFFFFF),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (!isSharing) {
            // Room Code Display Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF26334D), RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1522))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "YOUR 6-DIGIT ROOM CODE",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Black,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = activeRoomCode,
                            color = Color(0xFFFFFFFF),
                            fontWeight = FontWeight.Black,
                            fontSize = 34.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 4.sp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        IconButton(
                            onClick = { ScreenShareSessionManager.regenerateSenderRoomCode() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Regenerate Room Code",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Share this 6-digit code with the viewer. They can add multiple phones to watch simultaneously!",
                        color = Color(0xFF8A99AD),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Audio Mode Selector (4 options in a clean 2x2 grid)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111824))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "🎙️ AUDIO STREAMING MODE",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Select what sound is broadcasted (switchable live anytime):",
                        color = Color(0xFF8A99AD),
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    val row1Modes = listOf(
                        ScreenShareService.AUDIO_MODE_INTERNAL to "🎵 Internal Sound",
                        ScreenShareService.AUDIO_MODE_MIC to "🎙️ Mic Voice"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row1Modes.forEach { (mode, label) ->
                            val isSelected = selectedAudioMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF182232))
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF38BDF8) else Color(0xFF26334D),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .jellyElastic(baseCornerRadius = 10.dp)
                                    .clickable { ScreenShareSessionManager.setSenderAudioMode(mode) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color(0xFF06080E) else Color(0xFFBAC7D5),
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val row2Modes = listOf(
                        ScreenShareService.AUDIO_MODE_BOTH to "🔊 Both (Mix)",
                        ScreenShareService.AUDIO_MODE_NONE to "🔇 None (Silent)"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row2Modes.forEach { (mode, label) ->
                            val isSelected = selectedAudioMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF182232))
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF38BDF8) else Color(0xFF26334D),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .jellyElastic(baseCornerRadius = 10.dp)
                                    .clickable { ScreenShareSessionManager.setSenderAudioMode(mode) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color(0xFF06080E) else Color(0xFFBAC7D5),
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Instructions
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111824))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "🔒 PRIVATE & ENCRYPTED STREAM",
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "1. Click 'Start Screen Sharing' below and approve Android's screen capture prompt.\n" +
                               "2. Video and device name are encrypted with AES-256-GCM using your Room Code key.\n" +
                               "3. Audio mode can be switched dynamically between Internal, Mic, Both, or Silent anytime.\n" +
                               "4. Broadcast runs 24/7 persistently in the background until you explicitly tap 'Stop Screen Sharing'.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    val needsMic = selectedAudioMode == ScreenShareService.AUDIO_MODE_MIC || selectedAudioMode == ScreenShareService.AUDIO_MODE_BOTH
                    val hasMicPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (needsMic && !hasMicPermission) {
                        recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
                        if (mpManager != null) {
                            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .jellyElastic(baseCornerRadius = 14.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF38BDF8),
                    contentColor = Color(0xFF06080E)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "▶️ Start Screen Sharing",
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
            }
        } else {
            // Live Broadcasting Active State
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF00E676), RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1B16))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0x3300E676))
                            .border(1.dp, Color(0xFF00E676), RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "🟢 LIVE BROADCAST ACTIVE",
                            color = Color(0xFF00E676),
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "ROOM #$activeRoomCode",
                        color = Color(0xFFFFFFFF),
                        fontWeight = FontWeight.Black,
                        fontSize = 28.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 3.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Frames Sent: $framesSent  •  Status: $statusText",
                        color = Color(0xFF8A99AD),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Dynamic Live Audio Mode Switcher
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111824))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "🎙️ LIVE AUDIO CONTROL: ",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            text = selectedAudioMode,
                            color = Color(0xFF00E676),
                            fontWeight = FontWeight.Black,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    val allModes = listOf(
                        ScreenShareService.AUDIO_MODE_INTERNAL to "🎵 Internal",
                        ScreenShareService.AUDIO_MODE_MIC to "🎙️ Mic",
                        ScreenShareService.AUDIO_MODE_BOTH to "🔊 Both",
                        ScreenShareService.AUDIO_MODE_NONE to "🔇 Silent"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        allModes.forEach { (mode, label) ->
                            val isSelected = selectedAudioMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFF38BDF8) else Color(0xFF182232))
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF38BDF8) else Color(0xFF26334D),
                                        RoundedCornerShape(8.dp)
                                    )
                                    .jellyElastic(baseCornerRadius = 8.dp)
                                    .clickable { ScreenShareSessionManager.setSenderAudioMode(mode) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color(0xFF06080E) else Color(0xFFBAC7D5),
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    fontSize = 10.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111824))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "📱 BACKGROUND STREAMING ACTIVE",
                        color = Color(0xFF00E676),
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Broadcasting live as '${ScreenShareService.getLocalPhoneName()}'. You can minimize this app or lock the screen without interrupting the broadcast.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    ScreenShareService.stopSharing(context)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .jellyElastic(baseCornerRadius = 14.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF2E54),
                    contentColor = Color(0xFFFFFFFF)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "⏹️ Stop Screen Sharing",
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun ViewerScreenTab() {
    val context = LocalContext.current

    val viewerSessions by ScreenShareSessionManager.viewerSessions.collectAsState()
    val selectedTabRoomCode by ScreenShareSessionManager.selectedTabRoomCode.collectAsState()

    var inputRoomCode by rememberSaveable { mutableStateOf("") }
    val isInputValid = inputRoomCode.length == 6

    val activeSession = viewerSessions.find { it.roomCode == selectedTabRoomCode } ?: viewerSessions.firstOrNull()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        EncryptionStatusBadge()
        Spacer(modifier = Modifier.height(14.dp))

        // Top Connect Bar: Always visible so user can add multiple phones
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF26334D), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1522))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Text(
                    text = "CONNECT TO SENDER PHONE",
                    color = Color(0xFF38BDF8),
                    fontWeight = FontWeight.Black,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Enter a 6-digit Room Code to watch live. You can connect to multiple phones simultaneously:",
                    color = Color(0xFF8A99AD),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = inputRoomCode,
                        onValueChange = { newValue ->
                            if (newValue.length <= 6 && newValue.all { it.isDigit() }) {
                                inputRoomCode = newValue
                            }
                        },
                        placeholder = {
                            Text(
                                text = "e.g. 482910",
                                color = Color(0xFF5E6D82),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(
                            color = Color(0xFF38BDF8),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Black,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF26334D),
                            focusedContainerColor = Color(0xFF060910),
                            unfocusedContainerColor = Color(0xFF060910)
                        ),
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            ScreenShareSessionManager.addOrSelectViewerPhone(context, inputRoomCode)
                            inputRoomCode = ""
                        },
                        enabled = isInputValid,
                        modifier = Modifier
                            .height(52.dp)
                            .jellyElastic(baseCornerRadius = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF38BDF8),
                            disabledContainerColor = Color(0xFF1E2838),
                            contentColor = Color(0xFF06080E),
                            disabledContentColor = Color(0xFF5E6D82)
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "➕ Add Phone",
                            fontWeight = FontWeight.Black,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Multi-Phone Horizontal Device Tabs Bar
        if (viewerSessions.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                viewerSessions.forEach { session ->
                    val isSelected = session.roomCode == activeSession?.roomCode
                    Card(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF26334D),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { ScreenShareSessionManager.selectViewerTab(session.roomCode) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0xFF162235) else Color(0xFF0F1522)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (session.status.contains("LIVE", true)) Color(0xFF00E676) else Color(0xFF38BDF8))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "📱 ${session.phoneName} (#${session.roomCode})",
                                color = if (isSelected) Color(0xFFFFFFFF) else Color(0xFF8A99AD),
                                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x33FF2E54))
                                    .clickable {
                                        ScreenShareSessionManager.disconnectSinglePhoneTab(session.roomCode)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Disconnect tab",
                                    tint = Color(0xFFFF2E54),
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        // Active Session View or Empty State
        if (activeSession != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(16.dp)),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF070A10))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFFFF2E54))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "📱 ${activeSession.phoneName}",
                                    color = Color(0xFFFFFFFF),
                                    fontWeight = FontWeight.Black,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                            Text(
                                text = "AES-256-GCM Encrypted • ${activeSession.status}",
                                color = if (activeSession.status.contains("LIVE", true)) Color(0xFF00E676) else Color(0xFF38BDF8),
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { ScreenShareSessionManager.toggleMuteForPhone(activeSession.roomCode) },
                                modifier = Modifier
                                    .height(34.dp)
                                    .jellyElastic(baseCornerRadius = 10.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (activeSession.isAudioMuted) Color(0xFFFF2E54) else Color(0xFF38BDF8)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (activeSession.isAudioMuted) Color(0xFFFF2E54) else Color(0xFF38BDF8)
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = if (activeSession.isAudioMuted) "🔇 Audio: MUTED" else "🔊 Audio: ON",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }

                            OutlinedButton(
                                onClick = { ScreenShareSessionManager.disconnectSinglePhoneTab(activeSession.roomCode) },
                                modifier = Modifier
                                    .height(34.dp)
                                    .jellyElastic(baseCornerRadius = 10.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFFFF2E54)),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color(0xFFFF2E54)
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp)
                            ) {
                                Text(
                                    text = "❌ Disconnect",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Remote Video Canvas
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(440.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF030509))
                            .border(1.dp, Color(0xFF1B2433), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        val currentBmp = activeSession.latestFrame
                        if (currentBmp != null) {
                            Image(
                                bitmap = currentBmp.asImageBitmap(),
                                contentDescription = "Remote Screen Stream for ${activeSession.phoneName}",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = Color(0xFF38BDF8),
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "📡 Waiting for live frames...",
                                    color = Color(0xFFFFFFFF),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Make sure ${activeSession.phoneName} on Room #${activeSession.roomCode} is sharing.",
                                    color = Color(0xFF8A99AD),
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Empty State: No phones connected
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF111824))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "NO PHONES CONNECTED YET",
                        color = Color(0xFFFFFFFF),
                        fontWeight = FontWeight.Black,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter a 6-digit Room Code in the top bar and tap '➕ Add Phone' to watch. You can connect to multiple phones at the same time and switch between them using tabs!",
                        color = Color(0xFF8A99AD),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

