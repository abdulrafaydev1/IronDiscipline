package com.irondiscipline.app.ui.screens

import android.Manifest
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.irondiscipline.app.alarm.AlarmItem
import com.irondiscipline.app.alarm.AlarmRepository
import com.irondiscipline.app.alarm.HardAlarmSoundEngine
import com.irondiscipline.app.ui.components.jellyElastic
import com.irondiscipline.app.util.PermissionHelper
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale

@Composable
fun AlarmScreen(
    alarmRepository: AlarmRepository,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val alarms by alarmRepository.alarmsFlow.collectAsState()
    var isCreateDialogOpen by remember { mutableStateOf(false) }

    // Live calculation of next upcoming alarm
    var nextAlarmRemainingMs by remember { mutableStateOf<Long?>(null) }
    var nextAlarmItem by remember { mutableStateOf<AlarmItem?>(null) }

    // Runtime Permission States
    var hasNotificationPermission by remember {
        mutableStateOf(PermissionHelper.hasNotificationPermission(context))
    }
    var canScheduleExactAlarms by remember {
        mutableStateOf(PermissionHelper.canScheduleExactAlarms(context))
    }
    var canUseFullScreenIntent by remember {
        mutableStateOf(PermissionHelper.canUseFullScreenIntent(context))
    }
    var isBatteryOptimizationExempt by remember {
        mutableStateOf(PermissionHelper.isIgnoringBatteryOptimizations(context))
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    // Auto-prompt POST_NOTIFICATIONS on Android 13+ if not granted
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PermissionHelper.hasNotificationPermission(context)) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Periodically verify critical alarm permissions
    LaunchedEffect(Unit) {
        while (true) {
            hasNotificationPermission = PermissionHelper.hasNotificationPermission(context)
            canScheduleExactAlarms = PermissionHelper.canScheduleExactAlarms(context)
            canUseFullScreenIntent = PermissionHelper.canUseFullScreenIntent(context)
            isBatteryOptimizationExempt = PermissionHelper.isIgnoringBatteryOptimizations(context)
            delay(2000L)
        }
    }

    LaunchedEffect(alarms) {
        while (true) {
            val upcoming = alarmRepository.getNextUpcomingAlarm()
            nextAlarmItem = upcoming?.first
            nextAlarmRemainingMs = upcoming?.second
            delay(1000L)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF06080E))
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header Title
            item {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF2A121E))
                                .border(1.dp, Color(0xFFFF2E54), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Alarm,
                                contentDescription = null,
                                tint = Color(0xFFFF2E54),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "HARD DISCIPLINE ALARM",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Non-negotiable high-decibel wake-up protocols",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Permission Warning Banner if any critical screen-off alarm permission is missing
            if (!hasNotificationPermission || !canScheduleExactAlarms || !canUseFullScreenIntent || !isBatteryOptimizationExempt) {
                item {
                    AlarmPermissionsBanner(
                        hasNotification = hasNotificationPermission,
                        canExactAlarm = canScheduleExactAlarms,
                        canFullScreen = canUseFullScreenIntent,
                        isBatteryOptimized = !isBatteryOptimizationExempt,
                        onRequestNotification = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onRequestExactAlarm = {
                            try {
                                context.startActivity(PermissionHelper.createRequestExactAlarmIntent(context))
                            } catch (_: Exception) {}
                        },
                        onRequestFullScreen = {
                            try {
                                context.startActivity(PermissionHelper.createManageFullScreenIntent(context))
                            } catch (_: Exception) {}
                        },
                        onRequestBatteryExemption = {
                            try {
                                context.startActivity(PermissionHelper.createBatteryOptimizationExemptionIntent(context))
                            } catch (_: Exception) {}
                        }
                    )
                }
            }

            // Next Alarm Hero Card
            item {
                NextAlarmHeroBanner(
                    nextAlarm = nextAlarmItem,
                    remainingMs = nextAlarmRemainingMs
                )
            }

            // Section Header & Add Alarm Action
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SCHEDULED PROTOCOLS (${alarms.size})",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )

                    Button(
                        onClick = { isCreateDialogOpen = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier
                            .jellyElastic(baseCornerRadius = 20.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFFFF2E54), Color(0xFFFF5252))
                                )
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "New Alarm",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Alarms List or Empty State
            if (alarms.isEmpty()) {
                item {
                    EmptyAlarmsCard(onSetAlarm = { isCreateDialogOpen = true })
                }
            } else {
                items(
                    items = alarms,
                    key = { it.id }
                ) { alarm ->
                    AlarmItemCard(
                        alarm = alarm,
                        onToggle = { isEnabled ->
                            alarmRepository.toggleAlarm(alarm.id, isEnabled)
                        },
                        onDelete = {
                            alarmRepository.deleteAlarm(alarm.id)
                        }
                    )
                }
            }
        }

        // Dialog for creating new alarm
        if (isCreateDialogOpen) {
            CreateAlarmDialog(
                onDismiss = {
                    HardAlarmSoundEngine.getInstance().stopSound()
                    isCreateDialogOpen = false
                },
                onSave = { newAlarm ->
                    HardAlarmSoundEngine.getInstance().stopSound()
                    alarmRepository.saveAlarm(newAlarm)
                    isCreateDialogOpen = false
                }
            )
        }
    }
}

@Composable
fun AlarmPermissionsBanner(
    hasNotification: Boolean,
    canExactAlarm: Boolean,
    canFullScreen: Boolean,
    isBatteryOptimized: Boolean,
    onRequestNotification: () -> Unit,
    onRequestExactAlarm: () -> Unit,
    onRequestFullScreen: () -> Unit,
    onRequestBatteryExemption: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF221115)),
        border = BorderStroke(1.2.dp, Color(0xFFFF2E54).copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFFF2E54),
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "CRITICAL PERMISSIONS REQUIRED",
                    color = Color(0xFFFF2E54),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
            }

            Text(
                text = "To guarantee the alarm fires at full volume and pops open over the lock screen while your display is OFF, please authorize these permissions:",
                color = Color(0xFFCBD5E1),
                fontSize = 11.sp,
                lineHeight = 15.sp
            )

            if (!canExactAlarm) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "• Exact Alarm Scheduling",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Prevents Android Doze from delaying alarms.",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                    Button(
                        onClick = onRequestExactAlarm,
                        modifier = Modifier.jellyElastic(baseCornerRadius = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2E54)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Grant", fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            if (!canFullScreen) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "• Full-Screen Lock Popup",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Allows waking and rendering over lock screen.",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                    Button(
                        onClick = onRequestFullScreen,
                        modifier = Modifier.jellyElastic(baseCornerRadius = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2E54)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Authorize", fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            if (!hasNotification) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "• Notifications Permission",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Required for high-priority lock-screen alerts.",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                    Button(
                        onClick = onRequestNotification,
                        modifier = Modifier.jellyElastic(baseCornerRadius = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2E54)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Enable", fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }

            if (isBatteryOptimized) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "• Unrestricted Battery Usage",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Prevents system Doze from delaying locked alarms.",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.sp
                        )
                    }
                    Button(
                        onClick = onRequestBatteryExemption,
                        modifier = Modifier.jellyElastic(baseCornerRadius = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2E54)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Exempt", fontSize = 11.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun NextAlarmHeroBanner(
    nextAlarm: AlarmItem?,
    remainingMs: Long?
) {
    val bannerBrush = Brush.linearGradient(
        listOf(Color(0xFF1F111E), Color(0xFF131A29), Color(0xFF0F172A))
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
        border = BorderStroke(1.dp, Color(0xFFFF2E54).copy(alpha = 0.5f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bannerBrush)
                .padding(20.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = Color(0xFFFF2E54),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "NEXT HARD ALARM",
                            color = Color(0xFFFF4D6D),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    }

                    if (nextAlarm != null) {
                        val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
                        Text(
                            text = nextAlarm.getFormattedTime(is24Hour),
                            color = Color(0xFFFFB300),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (nextAlarm != null && remainingMs != null && remainingMs > 0) {
                    val totalSec = remainingMs / 1000
                    val hours = totalSec / 3600
                    val minutes = (totalSec % 3600) / 60
                    val seconds = totalSec % 60

                    val timeString = if (hours > 0) {
                        "${hours}h ${minutes}m ${seconds}s"
                    } else {
                        "${minutes}m ${seconds}s"
                    }

                    Text(
                        text = "RINGS IN $timeString",
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "“${nextAlarm.label}” — No excuses, zero hesitation.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = "NO ALARMS ARMED",
                        color = Color(0xFF64748B),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Set an early protocol to conquer the morning.",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmItemCard(
    alarm: AlarmItem,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (alarm.isEnabled) Color(0xFF131927) else Color(0xFF0C1019)
        ),
        border = BorderStroke(
            1.dp,
            if (alarm.isEnabled) Color(0xFF1E293B) else Color(0xFF151C2C)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Top Row: Time, Label, and Toggle Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
                Column {
                    Text(
                        text = alarm.getFormattedTime(is24Hour),
                        color = if (alarm.isEnabled) Color.White else Color(0xFF64748B),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = alarm.label,
                        color = if (alarm.isEnabled) Color(0xFF38BDF8) else Color(0xFF475569),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Switch(
                        checked = alarm.isEnabled,
                        onCheckedChange = onToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF06080E),
                            checkedTrackColor = Color(0xFF00E676),
                            uncheckedThumbColor = Color(0xFF64748B),
                            uncheckedTrackColor = Color(0xFF1E293B)
                        )
                    )

                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(32.dp)
                            .jellyElastic(baseCornerRadius = 16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Alarm",
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Repeat Days Pill Row
            RepeatDaysRow(
                activeDays = alarm.repeatDays,
                isAlarmEnabled = alarm.isEnabled
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Sound and Badges FlowRow
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Sound Name Tag
                BadgePill(
                    icon = Icons.Default.VolumeUp,
                    text = alarm.soundDisplayName.substringAfter(". "),
                    textColor = if (alarm.isEnabled) Color(0xFFFF4D6D) else Color(0xFF64748B),
                    backgroundColor = if (alarm.isEnabled) Color(0xFF28131E) else Color(0xFF151821)
                )

                // Math Mission Badge
                if (alarm.dismissChallenge != "NONE") {
                    val missionLabel = if (alarm.dismissChallenge == "MATH_HARD") "🔥 Hard Math (3 Sums)" else "🧠 Math (3 Sums)"
                    BadgePill(
                        icon = Icons.Default.Psychology,
                        text = missionLabel,
                        textColor = if (alarm.isEnabled) Color(0xFFFFB300) else Color(0xFF64748B),
                        backgroundColor = if (alarm.isEnabled) Color(0xFF292011) else Color(0xFF151821)
                    )
                }

                // Auto Delete Badge
                if (alarm.deleteAfterRinging) {
                    BadgePill(
                        icon = Icons.Default.Delete,
                        text = "Auto-Delete",
                        textColor = if (alarm.isEnabled) Color(0xFFA855F7) else Color(0xFF64748B),
                        backgroundColor = if (alarm.isEnabled) Color(0xFF231533) else Color(0xFF151821)
                    )
                }

                // Vibrate Badge
                if (alarm.vibrateWhileRinging) {
                    BadgePill(
                        icon = null,
                        text = "📳 Vibrate",
                        textColor = if (alarm.isEnabled) Color(0xFF00E676) else Color(0xFF64748B),
                        backgroundColor = if (alarm.isEnabled) Color(0xFF0F261B) else Color(0xFF151821)
                    )
                }

                // Snooze Badge
                if (alarm.snoozeEnabled) {
                    BadgePill(
                        icon = null,
                        text = "💤 Snooze ${alarm.snoozeMinutes}m",
                        textColor = if (alarm.isEnabled) Color(0xFF38BDF8) else Color(0xFF64748B),
                        backgroundColor = if (alarm.isEnabled) Color(0xFF122436) else Color(0xFF151821)
                    )
                }
            }
        }
    }
}

@Composable
fun RepeatDaysRow(
    activeDays: Set<Int>,
    isAlarmEnabled: Boolean
) {
    val dayMap = listOf(
        Calendar.MONDAY to "M",
        Calendar.TUESDAY to "T",
        Calendar.WEDNESDAY to "W",
        Calendar.THURSDAY to "T",
        Calendar.FRIDAY to "F",
        Calendar.SATURDAY to "S",
        Calendar.SUNDAY to "S"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        dayMap.forEach { (calDay, letter) ->
            val isActive = calDay in activeDays
            val bg = when {
                isActive && isAlarmEnabled -> Color(0xFF00E676).copy(alpha = 0.2f)
                isActive && !isAlarmEnabled -> Color(0xFF334155)
                else -> Color(0xFF151A24)
            }
            val textColor = when {
                isActive && isAlarmEnabled -> Color(0xFF00E676)
                isActive && !isAlarmEnabled -> Color(0xFF94A3B8)
                else -> Color(0xFF475569)
            }

            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(bg),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter,
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun BadgePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    text: String,
    textColor: Color,
    backgroundColor: Color
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun EmptyAlarmsCard(onSetAlarm: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1522)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A2234)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Alarm,
                    contentDescription = null,
                    tint = Color(0xFF64748B),
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "NO ACTIVE ALARMS",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Set an unyielding alarm protocol with 12 high-decibel sounds and math wake-up missions.",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onSetAlarm,
                modifier = Modifier.jellyElastic(baseCornerRadius = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF2E54)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "CREATE HARD ALARM",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

/**
 * Full Obsidian Luxury Dialog for Creating a new Hard Alarm.
 */
@Composable
fun CreateAlarmDialog(
    onDismiss: () -> Unit,
    onSave: (AlarmItem) -> Unit
) {
    val context = LocalContext.current
    val is24Hour = remember { DateFormat.is24HourFormat(context) }
    val now = remember { Calendar.getInstance() }
    val initialHour24 = remember { now.get(Calendar.HOUR_OF_DAY) }
    val initialMinute = remember { now.get(Calendar.MINUTE) }

    var hour12 by remember {
        mutableIntStateOf(
            when {
                initialHour24 == 0 -> 12
                initialHour24 > 12 -> initialHour24 - 12
                else -> initialHour24
            }
        )
    }
    var hour24 by remember { mutableIntStateOf(initialHour24) }
    var minute by remember { mutableIntStateOf(initialMinute) }
    var isPm by remember { mutableStateOf(initialHour24 >= 12) }

    var label by remember { mutableStateOf("Hard Discipline Wake-Up") }
    var repeatDays by remember { mutableStateOf(setOf<Int>()) }
    var deleteAfterRinging by remember { mutableStateOf(false) }
    var selectedSoundIndex by remember { mutableIntStateOf(0) }

    var previewingSoundIndex by remember { mutableStateOf<Int?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            HardAlarmSoundEngine.getInstance().stopSound()
        }
    }

    Dialog(
        onDismissRequest = {
            HardAlarmSoundEngine.getInstance().stopSound()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF0B101A),
            border = BorderStroke(1.dp, Color(0xFF1E293B))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Dialog Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "NEW DISCIPLINE ALARM",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Unforgiving sound & mental wake-up challenge",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = {
                            HardAlarmSoundEngine.getInstance().stopSound()
                            onDismiss()
                        }
                    ) {
                        Text(text = "✕", color = Color(0xFF94A3B8), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Time Selector (Hour, Minute, AM/PM initialized with current time and system 12h/24h format)
                    TimePickerCard(
                        is24Hour = is24Hour,
                        hour12 = hour12,
                        hour24 = hour24,
                        minute = minute,
                        isPm = isPm,
                        onHour12Change = { hour12 = it },
                        onHour24Change = { hour24 = it },
                        onMinuteChange = { minute = it },
                        onAmPmToggle = { isPm = !isPm }
                    )

                    // 2. Alarm Label Input
                    OutlinedTextField(
                        value = label,
                        onValueChange = { label = it },
                        label = { Text("Alarm Label / Goal", color = Color(0xFF94A3B8)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E676),
                            unfocusedBorderColor = Color(0xFF1E293B),
                            focusedContainerColor = Color(0xFF121824),
                            unfocusedContainerColor = Color(0xFF121824)
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    // 3. Repeat Days Selector
                    RepeatDaysSelector(
                        selectedDays = repeatDays,
                        onToggleDay = { day ->
                            repeatDays = if (day in repeatDays) {
                                repeatDays - day
                            } else {
                                repeatDays + day
                            }
                        },
                        onSelectPreset = { preset ->
                            repeatDays = preset
                        }
                    )

                    // 4. Brain Mission Enforced: Math Hard (3 Multi-Digit Equations Mandatory)
                    BrainMissionEnforcedCard()

                    // 5. 12 Hard Synthesized Alarm Sounds with Preview
                    SoundSelectorCard(
                        selectedIndex = selectedSoundIndex,
                        previewingIndex = previewingSoundIndex,
                        onSelectSound = { index ->
                            selectedSoundIndex = index
                        },
                        onTogglePreview = { index ->
                            if (previewingSoundIndex == index) {
                                HardAlarmSoundEngine.getInstance().stopSound()
                                previewingSoundIndex = null
                            } else {
                                previewingSoundIndex = index
                                HardAlarmSoundEngine.getInstance().previewSound(index)
                            }
                        }
                    )

                    // 6. Discipline Controls & Locked Toggles
                    DisciplineTogglesCard(
                        deleteAfterRinging = deleteAfterRinging,
                        onToggleDeleteAfter = { deleteAfterRinging = it }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            HardAlarmSoundEngine.getInstance().stopSound()
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .jellyElastic(baseCornerRadius = 12.dp)
                    ) {
                        Text("Cancel", color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            HardAlarmSoundEngine.getInstance().stopSound()
                            // Calculate 24-hour hour
                            val calculated24Hour = if (is24Hour) {
                                hour24
                            } else {
                                when {
                                    isPm && hour12 < 12 -> hour12 + 12
                                    !isPm && hour12 == 12 -> 0
                                    else -> hour12
                                }
                            }

                            val item = AlarmItem(
                                hour = calculated24Hour,
                                minute = minute,
                                isEnabled = true,
                                label = label.ifBlank { "Discipline Alarm" },
                                repeatDays = repeatDays,
                                deleteAfterRinging = deleteAfterRinging,
                                soundIndex = selectedSoundIndex,
                                snoozeEnabled = false,
                                snoozeMinutes = 0,
                                vibrateWhileRinging = true,
                                dismissChallenge = "MATH_HARD",
                                maxVolumeOverride = true
                            )
                            onSave(item)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF2E54)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(2f)
                            .jellyElastic(baseCornerRadius = 12.dp)
                    ) {
                        Text("SAVE DISCIPLINE ALARM", color = Color.White, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
    }
}

@Composable
fun TimePickerCard(
    is24Hour: Boolean,
    hour12: Int,
    hour24: Int,
    minute: Int,
    isPm: Boolean,
    onHour12Change: (Int) -> Unit,
    onHour24Change: (Int) -> Unit,
    onMinuteChange: (Int) -> Unit,
    onAmPmToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "WAKE-UP TIME",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = if (is24Hour) "(24H FORMAT)" else "(12H FORMAT)",
                    color = Color(0xFF00E676),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // Hour Selector
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            if (is24Hour) {
                                val next = (hour24 + 1) % 24
                                onHour24Change(next)
                            } else {
                                val next = if (hour12 == 12) 1 else hour12 + 1
                                onHour12Change(next)
                            }
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .jellyElastic(baseCornerRadius = 16.dp)
                    ) {
                        Icon(Icons.Default.ArrowDropUp, contentDescription = null, tint = Color(0xFF00E676))
                    }
                    Text(
                        text = String.format(Locale.US, "%02d", if (is24Hour) hour24 else hour12),
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(
                        onClick = {
                            if (is24Hour) {
                                val prev = if (hour24 == 0) 23 else hour24 - 1
                                onHour24Change(prev)
                            } else {
                                val prev = if (hour12 == 1) 12 else hour12 - 1
                                onHour12Change(prev)
                            }
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .jellyElastic(baseCornerRadius = 16.dp)
                    ) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF00E676))
                    }
                }

                Text(
                    text = ":",
                    color = Color(0xFFFF2E54),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                // Minute Selector
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = {
                            val next = (minute + 1) % 60
                            onMinuteChange(next)
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .jellyElastic(baseCornerRadius = 16.dp)
                    ) {
                        Icon(Icons.Default.ArrowDropUp, contentDescription = null, tint = Color(0xFF00E676))
                    }
                    Text(
                        text = String.format(Locale.US, "%02d", minute),
                        color = Color.White,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    IconButton(
                        onClick = {
                            val prev = if (minute == 0) 59 else minute - 1
                            onMinuteChange(prev)
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .jellyElastic(baseCornerRadius = 16.dp)
                    ) {
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color(0xFF00E676))
                    }
                }

                if (!is24Hour) {
                    Spacer(modifier = Modifier.width(20.dp))

                    // AM / PM Toggle Pill
                    Box(
                        modifier = Modifier
                            .jellyElastic(baseCornerRadius = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF1E293B))
                            .clickable { onAmPmToggle() }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (isPm) "PM" else "AM",
                            color = Color(0xFFFFB300),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RepeatDaysSelector(
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit,
    onSelectPreset: (Set<Int>) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REPEAT DAYS",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                // Quick Presets
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Once",
                        color = if (selectedDays.isEmpty()) Color(0xFF00E676) else Color(0xFF64748B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { onSelectPreset(emptySet()) }
                    )
                    Text(
                        text = "Mon-Fri",
                        color = if (selectedDays == setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY)) Color(0xFF00E676) else Color(0xFF64748B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            onSelectPreset(setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY))
                        }
                    )
                    Text(
                        text = "Every Day",
                        color = if (selectedDays.size == 7) Color(0xFF00E676) else Color(0xFF64748B),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            onSelectPreset(
                                setOf(
                                    Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
                                    Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
                                )
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val dayList = listOf(
                Calendar.MONDAY to "Mon",
                Calendar.TUESDAY to "Tue",
                Calendar.WEDNESDAY to "Wed",
                Calendar.THURSDAY to "Thu",
                Calendar.FRIDAY to "Fri",
                Calendar.SATURDAY to "Sat",
                Calendar.SUNDAY to "Sun"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                dayList.forEach { (dayInt, dayLabel) ->
                    val isSelected = dayInt in selectedDays
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .jellyElastic(baseCornerRadius = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) Color(0xFF00E676) else Color(0xFF1E293B))
                            .clickable { onToggleDay(dayInt) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = dayLabel.take(2),
                            color = if (isSelected) Color(0xFF06080E) else Color(0xFF94A3B8),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BrainMissionEnforcedCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16121E)),
        border = BorderStroke(1.dp, Color(0xFFFFB300).copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "DISMISS MODE: MATH HARD",
                        color = Color(0xFFFFB300),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF261D12))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "MANDATORY",
                        color = Color(0xFFFFB300),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "🧠 3 Multi-Digit Equations: You must solve 3 multi-digit equations (e.g., (14 × 7) + 39) sequentially. Back button and simple dismiss are strictly locked until answered correctly.",
                color = Color(0xFFE2E8F0),
                fontSize = 11.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun SoundSelectorCard(
    selectedIndex: Int,
    previewingIndex: Int?,
    onSelectSound: (Int) -> Unit,
    onTogglePreview: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HARD ALARM SOUND (12 SYNTHESIZED)",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "High-dB PCM",
                    color = Color(0xFFFF2E54),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            HardAlarmSoundEngine.SOUND_NAMES.forEachIndexed { index, name ->
                val isSelected = selectedIndex == index
                val isPlaying = previewingIndex == index

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            when {
                                isSelected -> Color(0xFF28131E)
                                else -> Color(0xFF161C2A)
                            }
                        )
                        .border(
                            1.dp,
                            if (isSelected) Color(0xFFFF2E54) else Color.Transparent,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectSound(index) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = name,
                            color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }

                    // Preview Button
                    Button(
                        onClick = { onTogglePreview(index) },
                        modifier = Modifier.jellyElastic(baseCornerRadius = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlaying) Color(0xFFFF2E54) else Color(0xFF1E293B)
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Stop" else "Preview",
                            tint = if (isPlaying) Color.White else Color(0xFF38BDF8),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isPlaying) "Stop" else "Preview",
                            color = if (isPlaying) Color.White else Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
fun DisciplineTogglesCard(
    deleteAfterRinging: Boolean,
    onToggleDeleteAfter: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "DISCIPLINE CONTROLS",
                color = Color(0xFF64748B),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            // Auto Delete Toggle (User Configurable)
            ToggleRow(
                title = "Delete this alarm after ringing",
                subtitle = "One-time execution; auto-removes on dismiss",
                checked = deleteAfterRinging,
                onCheckedChange = onToggleDeleteAfter,
                activeColor = Color(0xFFA855F7),
                enabled = true
            )

            // Vibrate Toggle - Permanently Locked ON
            LockedToggleRow(
                title = "Vibrate while ringing",
                subtitle = "Continuous heavy haptic vibration waveform",
                checked = true,
                badgeText = "🔒 ALWAYS ON (Mandatory)",
                badgeColor = Color(0xFF00E676)
            )

            // Max Volume Override Toggle - Permanently Locked ON
            LockedToggleRow(
                title = "100% Max Volume Override",
                subtitle = "Bypasses silent/vibrate switch to ring at full power",
                checked = true,
                badgeText = "🔒 ALWAYS ON (Mandatory)",
                badgeColor = Color(0xFFFF2E54)
            )

            // Snooze Toggle - Permanently Locked DISABLED
            LockedToggleRow(
                title = "Allow Snooze",
                subtitle = "No snooze option permitted under strict discipline protocol",
                checked = false,
                badgeText = "🚫 DISABLED (No Snooze Allowed)",
                badgeColor = Color(0xFF64748B)
            )
        }
    }
}

@Composable
fun LockedToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    badgeText: String,
    badgeColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
            Text(
                text = badgeText,
                color = badgeColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = false,
            colors = SwitchDefaults.colors(
                disabledCheckedThumbColor = Color(0xFF06080E),
                disabledCheckedTrackColor = badgeColor.copy(alpha = 0.7f),
                disabledUncheckedThumbColor = Color(0xFF475569),
                disabledUncheckedTrackColor = Color(0xFF1E293B)
            )
        )
    }
}

@Composable
fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = subtitle,
                color = Color(0xFF94A3B8),
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF06080E),
                checkedTrackColor = activeColor,
                uncheckedThumbColor = Color(0xFF64748B),
                uncheckedTrackColor = Color(0xFF1E293B)
            )
        )
    }
}
