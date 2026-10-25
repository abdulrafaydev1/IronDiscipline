package com.irondiscipline.app.ui.screens

import android.content.Context
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VolumeOff
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.domain.model.PermissionStatusState
import com.irondiscipline.app.ui.components.ChangeSecurityPinDialog
import com.irondiscipline.app.ui.components.UninstallPinDialog
import com.irondiscipline.app.util.PermissionHelper

// Obsidian Palette
private val ColorObsidianDeep = Color(0xFF06080E)
private val ColorObsidianSlate = Color(0xFF0D121D)
private val ColorElevatedSlate = Color(0xFF141B29)
private val ColorCardBorderStart = Color(0xFF26334D)
private val ColorCardBorderEnd = Color(0xFF141B29)

private val ColorElectricEmerald = Color(0xFF00E676)
private val ColorCyberAmber = Color(0xFFFFB300)
private val ColorAmberDark = Color(0xFFF59E0B)
private val ColorSkyBlue = Color(0xFF38BDF8)

private val ColorTextPrimary = Color(0xFFFFFFFF)
private val ColorTextSecondary = Color(0xFF8A99AD)
private val ColorTextMuted = Color(0xFF5E6D82)

@Composable
fun SettingsScreen(
    permissionState: PermissionStatusState,
    onRefreshPermissions: () -> Unit,
    onVerifyPin: (String) -> Boolean = { true },
    onConfirmUninstall: () -> Unit = {},
    onChangePin: (String, String) -> Boolean = { _, _ -> true }
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var showUninstallPinDialog by remember { mutableStateOf(false) }
    var showChangePinDialog by remember { mutableStateOf(false) }

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

    if (showChangePinDialog) {
        ChangeSecurityPinDialog(
            onDismiss = { showChangePinDialog = false },
            onVerifyCurrentPin = onVerifyPin,
            onSaveNewPin = onChangePin
        )
    }

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
        // 1. Settings Header
        Column(modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp)) {
            Text(
                text = "SYSTEM SETTINGS",
                color = ColorTextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 22.sp,
                letterSpacing = 1.5.sp
            )
            Text(
                text = "PERMISSIONS & PROTECTION PROTOCOLS",
                color = ColorTextSecondary,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 1.0.sp
            )
        }

        // 2. Section: System Permissions Status
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "SHIELD PERMISSION PROTOCOLS (4)",
                color = ColorTextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.2.sp
            )
            Text(
                text = if (permissionState.allCriticalGranted) "ALL CRITICAL ARMED" else "ACTION REQUIRED",
                color = if (permissionState.allCriticalGranted) ColorElectricEmerald else ColorCyberAmber,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black
            )
        }

        // Permission Card 1: Accessibility Service
        PermissionItemCard(
            title = "Accessibility Service",
            description = "Required for real-time foreground package detection and zero-delay block screen enforcement.",
            icon = Icons.Default.AccessibilityNew,
            isGranted = permissionState.isAccessibilityServiceEnabled,
            actionLabel = "Enable Accessibility",
            onActionClick = {
                context.startActivity(PermissionHelper.createAccessibilitySettingsIntent())
            }
        )

        // Permission Card 2: Display Over Other Apps (Overlay)
        PermissionItemCard(
            title = "Display Over Other Apps (Overlay)",
            description = "Allows IronDiscipline to draw the full-screen vault overlay over distracting apps when limits expire.",
            icon = Icons.Default.Layers,
            isGranted = permissionState.hasOverlayPermission,
            actionLabel = "Enable Overlay Permission",
            onActionClick = {
                context.startActivity(PermissionHelper.createOverlayPermissionIntent(context))
            }
        )

        // Permission Card 3: Usage Access Permission
        PermissionItemCard(
            title = "Usage Access Permission",
            description = "Provides accurate daily app usage metrics and foreground session tracking.",
            icon = Icons.Default.QueryStats,
            isGranted = permissionState.hasUsageStatsPermission,
            actionLabel = "Grant Usage Access",
            onActionClick = {
                try {
                    context.startActivity(PermissionHelper.createUsageAccessIntent(context))
                } catch (_: Exception) {
                    context.startActivity(PermissionHelper.createFallbackUsageAccessIntent())
                }
            }
        )

        // Permission Card 4: Ignore Battery Optimizations
        PermissionItemCard(
            title = "Ignore Battery Optimizations",
            description = "Exempts IronDiscipline from system battery killers so protection is never halted.",
            icon = Icons.Default.BatteryChargingFull,
            isGranted = permissionState.isBatteryOptimizationExempt,
            actionLabel = "Disable Battery Optimization",
            onActionClick = {
                context.startActivity(PermissionHelper.createBatteryOptimizationExemptionIntent(context))
            }
        )

        // Refresh Permissions Button
        OutlinedButton(
            onClick = onRefreshPermissions,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(13.dp),
            border = BorderStroke(1.dp, Color(0xFF26334D))
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                tint = ColorSkyBlue,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Verify & Refresh Permission Status",
                color = ColorSkyBlue,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        // 3. Core Protection Protocols Summary Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.linearGradient(listOf(ColorCardBorderStart, ColorCardBorderEnd))
                    ),
                    RoundedCornerShape(20.dp)
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ColorElevatedSlate)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = ColorElectricEmerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CORE PROTECTION PROTOCOLS",
                        color = ColorTextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        letterSpacing = 1.0.sp
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                ProtocolFeatureItem(
                    title = "Strict Per-App Quotas",
                    detail = "Hardcoded 30-Minute independent allowance per application (YouTube, Instagram, Facebook, TikTok)."
                )

                Spacer(modifier = Modifier.height(10.dp))

                ProtocolFeatureItem(
                    title = "Audio & Video Silencing",
                    detail = "Instant exclusive audio focus gain + hardware media pause keys + STREAM_MUSIC mute upon app lock."
                )

                Spacer(modifier = Modifier.height(10.dp))

                ProtocolFeatureItem(
                    title = "Anti-Bypass Vault Overlay",
                    detail = "Programmatic native WindowManager overlay with live countdown timer and home-screen only exit."
                )

                Spacer(modifier = Modifier.height(10.dp))

                ProtocolFeatureItem(
                    title = "Persistent Background Engine",
                    detail = "Low-overhead continuous monitoring service guarded against system kills and idle teardown."
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Version Footer
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0B0F17))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Column {
                        Text(
                            text = "IronDiscipline • v1.0.0 (Production Engine)",
                            color = ColorTextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "Jetpack Compose M3 • Room DB • Target SDK 34",
                            color = ColorTextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 4. Emergency & Testing Controls Card (PIN Protected Uninstall & Security PIN Management)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    BorderStroke(
                        1.2.dp,
                        Brush.linearGradient(
                            listOf(Color(0xFFFF2E54).copy(alpha = 0.5f), ColorCardBorderEnd)
                        )
                    ),
                    RoundedCornerShape(20.dp)
                ),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = ColorElevatedSlate)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = Color(0xFFFF2E54),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "EMERGENCY & TESTING CONTROLS",
                        color = ColorTextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 13.sp,
                        letterSpacing = 1.0.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "PIN-guarded controls to disarm protection protocols or cleanly remove the application during development and testing.",
                    color = ColorTextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Delete / Uninstall App Button
                Button(
                    onClick = { showUninstallPinDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF220D14),
                        contentColor = Color(0xFFFF2E54)
                    ),
                    border = BorderStroke(1.2.dp, Color(0xFFFF2E54).copy(alpha = 0.85f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "🗑️ Delete / Uninstall App (PIN Required)",
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Change Security PIN Button
                OutlinedButton(
                    onClick = { showChangePinDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp),
                    shape = RoundedCornerShape(13.dp),
                    border = BorderStroke(1.dp, ColorSkyBlue.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = ColorSkyBlue
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.LockReset,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "🔐 Change Security PIN (Default: 1234)",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
    }
}

/**
 * Individual Permission Status Card with direct action button.
 */
@Composable
private fun PermissionItemCard(
    title: String,
    description: String,
    icon: ImageVector,
    isGranted: Boolean,
    actionLabel: String,
    onActionClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderStroke(
                    1.dp,
                    if (isGranted) Color(0xFF1E2D40) else ColorCyberAmber.copy(alpha = 0.5f)
                ),
                RoundedCornerShape(18.dp)
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = ColorElevatedSlate)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isGranted) Color(0x1A00E676) else Color(0x26FFB300)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isGranted) ColorElectricEmerald else ColorCyberAmber,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = title,
                        color = ColorTextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                // Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isGranted) Color(0x1A00E676) else Color(0x26FFB300)
                        )
                        .border(
                            width = 0.8.dp,
                            color = if (isGranted) ColorElectricEmerald else ColorCyberAmber,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isGranted) "ACTIVE" else "ACTION REQUIRED",
                        color = if (isGranted) ColorElectricEmerald else ColorCyberAmber,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 9.sp,
                        letterSpacing = 0.8.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = description,
                color = ColorTextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (!isGranted) {
                Button(
                    onClick = onActionClick,
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    shape = RoundedCornerShape(11.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ColorCyberAmber,
                        contentColor = ColorObsidianDeep
                    )
                ) {
                    Text(
                        text = actionLabel,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0F1722))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = ColorElectricEmerald,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Permission granted & active",
                            color = ColorElectricEmerald,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolFeatureItem(
    title: String,
    detail: String
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(ColorElectricEmerald)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = ColorTextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                text = detail,
                color = ColorTextSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
    }
}
