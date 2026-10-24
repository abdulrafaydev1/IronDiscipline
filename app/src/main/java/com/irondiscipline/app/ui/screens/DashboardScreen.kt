package com.irondiscipline.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.data.local.entities.DeepWorkSession
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
import com.irondiscipline.app.ui.viewmodel.DashboardMetricsUiState
import com.irondiscipline.app.ui.viewmodel.DeepWorkTimerUiState
import com.irondiscipline.app.util.CoreAppQuotaStatus
import com.irondiscipline.app.util.RealtimeQuotaSnapshot
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    metrics: DashboardMetricsUiState,
    realtimeQuotaState: RealtimeQuotaSnapshot,
    timerState: DeepWorkTimerUiState,
    permissionState: PermissionStatusState,
    onActivateOneMinuteTestMode: () -> Unit,
    onSwitchBackToNormalThirtyMinMode: () -> Unit,
    onUnlockAndResetTimerForTesting: () -> Unit,
    onSetAppLimitMinutes: (appKey: String, minutes: Int) -> Unit,
    onResetSingleApp: (appKey: String) -> Unit,
    onActivateStrictMode: (durationHours: Int, allowEmergencyOverride: Boolean) -> Unit,
    onUpdateMotivationalQuote: (String) -> Unit,
    onSelectTimerPreset: (Int) -> Unit,
    onUpdateTimerTitle: (String) -> Unit,
    onStartOrPauseTimer: () -> Unit,
    onFinishTimerEarly: () -> Unit,
    onResetTimer: () -> Unit,
    onNavigateToPermissionsTab: () -> Unit
) {
    var showStrictModeDialog by remember { mutableStateOf(false) }
    var showEditQuoteDialog by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Permission Warning Banner if any critical permission is missing
        if (!permissionState.allCriticalGranted) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, NeonAmber, RoundedCornerShape(14.dp))
                    .clickable { onNavigateToPermissionsTab() },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = NeonAmber.copy(alpha = 0.12f))
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = NeonAmber,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Shield Setup Incomplete (${permissionState.grantedCount}/4 Granted)",
                            color = NeonAmber,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "Tap to open the Permissions Setup Wizard and arm real-time app interception.",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 1. TOP CARD: Discipline Streak, Saved vs Distraction Time, & Strict Mode Status
        TopDisciplineOverviewCard(
            metrics = metrics,
            onOpenStrictModeModal = { showStrictModeDialog = true }
        )

        // 2. GLOBAL TEST & RESET CONTROLS
        GlobalTestControlsCard(
            isTestMode = realtimeQuotaState.isTestModeActive,
            onActivateOneMinuteTestMode = onActivateOneMinuteTestMode,
            onSwitchBackToNormalThirtyMinMode = onSwitchBackToNormalThirtyMinMode,
            onUnlockAndResetTimerForTesting = onUnlockAndResetTimerForTesting
        )

        // 3. 4 SEPARATE PER-APP STATUS CARDS (YouTube, Instagram, Facebook, TikTok)
        Text(
            text = "INDEPENDENT PER-APP TARGETS",
            color = NeonCyan,
            fontWeight = FontWeight.Black,
            fontSize = 14.sp,
            letterSpacing = 1.sp
        )

        realtimeQuotaState.coreStatuses.forEach { status ->
            IndividualAppQuotaCard(
                status = status,
                onSetLimitMinutes = { mins -> onSetAppLimitMinutes(status.target.appKey, mins) },
                onResetApp = { onResetSingleApp(status.target.appKey) }
            )
        }

        // 4. MIDDLE SECTION: Deep Work Focus Timer (Pomodoro / Custom Duration)
        DeepWorkFocusTimerCard(
            timerState = timerState,
            todayDeepWorkMinutes = metrics.todayDeepWorkMinutes,
            onSelectPreset = onSelectTimerPreset,
            onUpdateTitle = onUpdateTimerTitle,
            onStartOrPause = onStartOrPauseTimer,
            onFinishEarly = onFinishTimerEarly,
            onReset = onResetTimer
        )

        // 5. Customizable Block Overlay Quote Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, DarkBorder, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DarkCard)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = NeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "BLOCK OVERLAY MANTRA",
                            color = NeonCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp
                        )
                    }
                    IconButton(onClick = { showEditQuoteDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Quote",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Text(
                    text = "\"${metrics.disciplineState.customQuote}\"",
                    color = TextPrimary,
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }

        // 6. Recent Deep Work Sessions Log
        if (metrics.recentSessions.isNotEmpty()) {
            RecentDeepWorkSessionsCard(sessions = metrics.recentSessions)
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showStrictModeDialog) {
        StrictModeActivationDialog(
            isCurrentlyLocked = metrics.disciplineState.isStrictLockedNow(),
            remainingMillis = metrics.strictRemainingMillis,
            onDismiss = { showStrictModeDialog = false },
            onConfirmLock = { hours, allowEmergency ->
                onActivateStrictMode(hours, allowEmergency)
                showStrictModeDialog = false
            }
        )
    }

    if (showEditQuoteDialog) {
        EditMotivationalQuoteDialog(
            currentQuote = metrics.disciplineState.customQuote,
            onDismiss = { showEditQuoteDialog = false },
            onSave = { newQuote ->
                onUpdateMotivationalQuote(newQuote)
                showEditQuoteDialog = false
            }
        )
    }
}

/**
 * Top Global Test & Reset Controls Card.
 */
@Composable
private fun GlobalTestControlsCard(
    isTestMode: Boolean,
    onActivateOneMinuteTestMode: () -> Unit,
    onSwitchBackToNormalThirtyMinMode: () -> Unit,
    onUnlockAndResetTimerForTesting: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = if (isTestMode) NeonAmber else NeonCyan,
                shape = RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        tint = if (isTestMode) NeonAmber else NeonCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isTestMode) "⚡ TEST MODE ACTIVE (60s Limit)" else "⚡ TEST & GLOBAL CONTROLS",
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Action Buttons
            Button(
                onClick = onActivateOneMinuteTestMode,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonAmber,
                    contentColor = DarkBg
                )
            ) {
                Icon(imageVector = Icons.Default.Bolt, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Activate 1-Min Test Mode (All 4 Apps = 1 Min)",
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onSwitchBackToNormalThirtyMinMode,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "30-Min Normal Mode",
                        color = NeonCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Button(
                    onClick = onUnlockAndResetTimerForTesting,
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
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
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
 * Individual App Status Card (YouTube, Instagram, Facebook, TikTok).
 * Completely independent timer, limit, and lock state!
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndividualAppQuotaCard(
    status: CoreAppQuotaStatus,
    onSetLimitMinutes: (Int) -> Unit,
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
            // App Name & Lock Status Badge
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
                            .background(if (isLocked) NeonCrimson.copy(alpha = 0.2f) else NeonCyan.copy(alpha = 0.2f)),
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
                    Text(
                        text = status.target.displayName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Black,
                        fontSize = 17.sp
                    )
                }

                // Status Badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isLocked) NeonCrimson.copy(alpha = 0.18f) else NeonLime.copy(alpha = 0.18f))
                        .border(1.dp, if (isLocked) NeonCrimson else NeonLime, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isLocked) {
                            "LOCKED - ${status.formattedLockRemainingHhMmSs} left"
                        } else {
                            "UNLOCKED"
                        },
                        color = if (isLocked) NeonCrimson else NeonLime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = if (isLocked) FontFamily.Monospace else FontFamily.Default
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Live Remaining Usage Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isLocked) {
                        "Usage time exhausted (Limit: ${status.formattedLimitMinutes})"
                    } else {
                        "${status.formattedRemainingMmSs} remaining (Limit: ${status.formattedLimitMinutes})"
                    },
                    color = if (isLocked) NeonCrimson else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )

                Text(
                    text = "${status.usedSeconds}s used",
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

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Limit Buttons + Reset Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1, 15, 30, 60).forEach { mins ->
                        val isSelected = (status.limitSeconds / 60) == mins
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) NeonCyan.copy(alpha = 0.25f) else DarkSurface)
                                .border(1.dp, if (isSelected) NeonCyan else DarkBorder, RoundedCornerShape(8.dp))
                                .clickable { onSetLimitMinutes(mins) }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${mins}m",
                                color = if (isSelected) NeonCyan else TextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Individual App Reset Button
                OutlinedButton(
                    onClick = onResetApp,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reset",
                        tint = NeonLime,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Reset",
                        color = NeonLime,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun TopDisciplineOverviewCard(
    metrics: DashboardMetricsUiState,
    onOpenStrictModeModal: () -> Unit
) {
    val strictLocked = metrics.isStrictModeEffective
    val borderBrush = if (strictLocked) {
        Brush.horizontalGradient(listOf(NeonCrimson, NeonAmber))
    } else {
        Brush.horizontalGradient(listOf(NeonCyan, NeonLime))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderBrush, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(NeonLime.copy(alpha = 0.14f))
                            .border(1.5.dp, NeonLime, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = NeonLime,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "${metrics.disciplineState.currentStreakDays} DAY STREAK",
                            color = NeonLime,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Best: ${metrics.disciplineState.longestStreakDays} days • ${metrics.todayBlockedAttempts} blocks today",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (strictLocked) NeonCrimson.copy(alpha = 0.18f)
                            else NeonCyan.copy(alpha = 0.14f)
                        )
                        .border(
                            1.dp,
                            if (strictLocked) NeonCrimson else NeonCyan,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onOpenStrictModeModal() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (strictLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            tint = if (strictLocked) NeonCrimson else NeonCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (strictLocked) "STRICT LOCKED" else "STRICT MODE",
                            color = if (strictLocked) NeonCrimson else NeonCyan,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            if (metrics.strictRemainingMillis > 0L) {
                Spacer(modifier = Modifier.height(10.dp))
                val totalSecs = metrics.strictRemainingMillis / 1000L
                val hrs = totalSecs / 3600L
                val mins = (totalSecs % 3600L) / 60L
                val secs = totalSecs % 60L
                Text(
                    text = String.format(
                        Locale.US,
                        "Anti-Bypass Lock Active: %02dh %02dm %02ds remaining (Settings Protected)",
                        hrs,
                        mins,
                        secs
                    ),
                    color = NeonCrimson,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "Saved Time Today",
                    value = formatMinutesToHoursMins(metrics.todaySavedMinutes),
                    accentColor = NeonCyan
                )
                StatMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "Distraction Time",
                    value = formatMinutesToHoursMins(metrics.todayDistractionMinutes),
                    accentColor = if (metrics.todayDistractionMinutes > 45) NeonCrimson else NeonAmber
                )
                StatMetricBox(
                    modifier = Modifier.weight(1f),
                    label = "Deep Work Today",
                    value = formatMinutesToHoursMins(metrics.todayDeepWorkMinutes),
                    accentColor = NeonLime
                )
            }
        }
    }
}

@Composable
private fun StatMetricBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    accentColor: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
            .border(1.dp, accentColor.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(vertical = 12.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = accentColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            color = TextMuted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeepWorkFocusTimerCard(
    timerState: DeepWorkTimerUiState,
    todayDeepWorkMinutes: Int,
    onSelectPreset: (Int) -> Unit,
    onUpdateTitle: (String) -> Unit,
    onStartOrPause: () -> Unit,
    onFinishEarly: () -> Unit,
    onReset: () -> Unit
) {
    val presets = listOf(25, 50, 90, 120)
    val mins = timerState.remainingSeconds / 60
    val secs = timerState.remainingSeconds % 60
    val formattedClock = String.format(Locale.US, "%02d:%02d", mins, secs)
    val progress = if (timerState.totalSeconds > 0) {
        (timerState.totalSeconds - timerState.remainingSeconds).toFloat() / timerState.totalSeconds.toFloat()
    } else 0f

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DEEP WORK FOCUS ENGINE",
                        color = NeonCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp
                    )
                }
                Text(
                    text = "Today: ${formatMinutesToHoursMins(todayDeepWorkMinutes)}",
                    color = NeonLime,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = timerState.focusTitle,
                onValueChange = onUpdateTitle,
                enabled = !timerState.isRunning,
                singleLine = true,
                label = { Text("Focus Objective / Task") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = formattedClock,
                color = if (timerState.isRunning) NeonLime else TextPrimary,
                fontFamily = FontFamily.Monospace,
                fontSize = 54.sp,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = NeonLime,
                trackColor = DarkSurface
            )

            Spacer(modifier = Modifier.height(14.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                presets.forEach { presetMins ->
                    val selected = timerState.selectedMinutes == presetMins
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectPreset(presetMins) },
                        enabled = !timerState.isRunning,
                        label = {
                            Text(
                                text = "${presetMins}m",
                                fontWeight = FontWeight.Bold
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                            selectedLabelColor = NeonCyan
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartOrPause,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (timerState.isRunning) NeonAmber else NeonCyan,
                        contentColor = DarkBg
                    )
                ) {
                    Icon(
                        imageVector = if (timerState.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (timerState.isRunning) "PAUSE" else "START FOCUS",
                        fontWeight = FontWeight.Black
                    )
                }

                if (timerState.remainingSeconds < timerState.totalSeconds) {
                    OutlinedButton(
                        onClick = onFinishEarly,
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Log Session",
                            tint = NeonLime
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("LOG", color = NeonLime, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reset Timer",
                            tint = TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentDeepWorkSessionsCard(sessions: List<DeepWorkSession>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DarkBorder, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "RECENT DEEP WORK SESSIONS",
                color = NeonLime,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )

            sessions.take(5).forEach { session ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(DarkSurface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = session.focusTitle,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = session.dateString,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Text(
                        text = "+${session.durationMinutes} mins",
                        color = NeonLime,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StrictModeActivationDialog(
    isCurrentlyLocked: Boolean,
    remainingMillis: Long,
    onDismiss: () -> Unit,
    onConfirmLock: (durationHours: Int, allowEmergencyOverride: Boolean) -> Unit
) {
    var selectedHours by remember { mutableIntStateOf(4) }
    var allowEmergencyOverride by remember { mutableStateOf(true) }
    val durationOptions = listOf(4, 8, 12, 24)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = NeonCrimson
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Strict Mode Anti-Sabotage",
                    color = TextPrimary,
                    fontWeight = FontWeight.Black,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Once activated, Strict Mode CANNOT be turned off until the timer expires. Android Settings (Accessibility, App Info, Device Admin) will be actively guarded against tampering.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )

                if (isCurrentlyLocked) {
                    val hrs = (remainingMillis / 3_600_000L)
                    val mins = (remainingMillis % 3_600_000L) / 60_000L
                    Text(
                        text = "Currently Locked: ${hrs}h ${mins}m remaining. You can extend the lock duration below.",
                        color = NeonAmber,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }

                Text(
                    text = "Select Lock Duration:",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    durationOptions.forEach { hrs ->
                        FilterChip(
                            selected = selectedHours == hrs,
                            onClick = { selectedHours = hrs },
                            label = { Text("${hrs}h", fontWeight = FontWeight.Bold) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCrimson.copy(alpha = 0.25f),
                                selectedLabelColor = NeonCrimson
                            )
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Allow 1 Emergency Unlock/Day",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Requires 60s wait + 3 math equations + 100-char pledge",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = allowEmergencyOverride,
                        onCheckedChange = { allowEmergencyOverride = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = NeonCyan)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirmLock(selectedHours, allowEmergencyOverride) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCrimson,
                    contentColor = TextPrimary
                )
            ) {
                Text(
                    text = "ENGAGE ${selectedHours}H LOCK",
                    fontWeight = FontWeight.Black
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun EditMotivationalQuoteDialog(
    currentQuote: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var quoteText by remember { mutableStateOf(currentQuote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        title = {
            Text(
                text = "Customize Block Overlay Mantra",
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            OutlinedTextField(
                value = quoteText,
                onValueChange = { quoteText = it },
                label = { Text("Motivational Quote") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
        },
        confirmButton = {
            Button(
                onClick = { onSave(quoteText) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = DarkBg
                )
            ) {
                Text("Save Quote", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

private fun formatMinutesToHoursMins(totalMinutes: Int): String {
    val hrs = totalMinutes / 60
    val mins = totalMinutes % 60
    return if (hrs > 0) "${hrs}h ${mins}m" else "${mins}m"
}
