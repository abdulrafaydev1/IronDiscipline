package com.irondiscipline.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.data.local.entities.ScheduleBlock
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

@Composable
fun ScheduleScreen(
    schedules: List<ScheduleBlock>,
    isStrictModeActive: Boolean,
    onCreateOrUpdateSchedule: (ScheduleBlock) -> Unit,
    onToggleSchedule: (ScheduleBlock, Boolean) -> Unit,
    onDeleteSchedule: (ScheduleBlock) -> Unit
) {
    var editingSchedule by remember { mutableStateOf<ScheduleBlock?>(null) }
    var isCreatingNew by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SCHEDULED FOCUS WINDOWS",
                    color = NeonCyan,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Automatically lock distracting apps & browser domains during work or sleep.",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    editingSchedule = null
                    isCreatingNew = true
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = DarkBg
                )
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Window", fontWeight = FontWeight.Black, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(schedules, key = { it.id }) { schedule ->
                ScheduleBlockItemCard(
                    schedule = schedule,
                    isStrictModeActive = isStrictModeActive,
                    onToggle = { enabled -> onToggleSchedule(schedule, enabled) },
                    onEdit = {
                        editingSchedule = schedule
                        isCreatingNew = true
                    },
                    onDelete = { onDeleteSchedule(schedule) }
                )
            }
        }
    }

    if (isCreatingNew) {
        ScheduleEditorDialog(
            initialSchedule = editingSchedule,
            onDismiss = {
                isCreatingNew = false
                editingSchedule = null
            },
            onSave = { updated ->
                onCreateOrUpdateSchedule(updated)
                isCreatingNew = false
                editingSchedule = null
            }
        )
    }
}

@Composable
private fun ScheduleBlockItemCard(
    schedule: ScheduleBlock,
    isStrictModeActive: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val activeRightNow = schedule.isActiveNow()
    val borderColor = when {
        activeRightNow && schedule.enforcesStrictMode -> NeonCrimson
        activeRightNow -> NeonLime
        schedule.isEnabled -> NeonCyan.copy(alpha = 0.45f)
        else -> DarkBorder
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
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
                    Icon(
                        imageVector = if (schedule.enforcesStrictMode) {
                            Icons.Default.Lock
                        } else {
                            Icons.Default.Schedule
                        },
                        contentDescription = null,
                        tint = if (activeRightNow) NeonLime else NeonCyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = schedule.title,
                                color = TextPrimary,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp
                            )
                            if (activeRightNow) {
                                Text(
                                    text = "ACTIVE NOW",
                                    color = NeonLime,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(NeonLime.copy(alpha = 0.16f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = schedule.formattedTimeRange(),
                            color = NeonCyan,
                            fontWeight = FontWeight.Black,
                            fontSize = 18.sp
                        )
                    }
                }

                Switch(
                    checked = schedule.isEnabled,
                    onCheckedChange = onToggle,
                    enabled = !(isStrictModeActive && schedule.isEnabled),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NeonCyan,
                        checkedTrackColor = NeonCyan.copy(alpha = 0.3f)
                    )
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Days: ${schedule.formattedDaysSummary()}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (schedule.enforcesStrictMode) {
                        Text(
                            text = "Strict Mode Enforced (Settings Tamper Protection ON)",
                            color = NeonAmber,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row {
                    IconButton(onClick = onEdit, enabled = !isStrictModeActive) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Schedule",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, enabled = !isStrictModeActive) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Schedule",
                            tint = NeonCrimson,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleEditorDialog(
    initialSchedule: ScheduleBlock?,
    onDismiss: () -> Unit,
    onSave: (ScheduleBlock) -> Unit
) {
    var title by remember { mutableStateOf(initialSchedule?.title ?: "Deep Focus Block") }
    var startHour by remember { mutableIntStateOf(initialSchedule?.startHour ?: 9) }
    var startMinute by remember { mutableIntStateOf(initialSchedule?.startMinute ?: 0) }
    var endHour by remember { mutableIntStateOf(initialSchedule?.endHour ?: 18) }
    var endMinute by remember { mutableIntStateOf(initialSchedule?.endMinute ?: 0) }
    var selectedDays by remember {
        mutableStateOf(initialSchedule?.getActiveDaysSet() ?: setOf(1, 2, 3, 4, 5))
    }
    var enforcesStrictMode by remember {
        mutableStateOf(initialSchedule?.enforcesStrictMode ?: false)
    }

    val dayLabels = listOf(
        1 to "M",
        2 to "T",
        3 to "W",
        4 to "T",
        5 to "F",
        6 to "S",
        7 to "S"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        title = {
            Text(
                text = if (initialSchedule == null) "New Focus Schedule" else "Edit Schedule",
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Schedule Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Time Interval (24-Hour Format, supports overnight e.g. 22:00 to 07:00):",
                    color = TextSecondary,
                    fontSize = 12.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startHour.toString(),
                        onValueChange = { startHour = (it.toIntOrNull() ?: 0).coerceIn(0, 23) },
                        label = { Text("Start Hr") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = startMinute.toString(),
                        onValueChange = { startMinute = (it.toIntOrNull() ?: 0).coerceIn(0, 59) },
                        label = { Text("Start Min") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = endHour.toString(),
                        onValueChange = { endHour = (it.toIntOrNull() ?: 0).coerceIn(0, 23) },
                        label = { Text("End Hr") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endMinute.toString(),
                        onValueChange = { endMinute = (it.toIntOrNull() ?: 0).coerceIn(0, 59) },
                        label = { Text("End Min") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = "Active Days of Week:",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    dayLabels.forEach { (dayNum, shortLabel) ->
                        val isSelected = dayNum in selectedDays
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) NeonCyan else DarkSurface
                                )
                                .border(
                                    1.dp,
                                    if (isSelected) NeonCyan else DarkBorder,
                                    CircleShape
                                )
                                .clickable {
                                    selectedDays = if (isSelected && selectedDays.size > 1) {
                                        selectedDays - dayNum
                                    } else {
                                        selectedDays + dayNum
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = shortLabel,
                                color = if (isSelected) DarkBg else TextSecondary,
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enforce Strict Mode in Window",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Automatically locks Android Settings while this schedule is active",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = enforcesStrictMode,
                        onCheckedChange = { enforcesStrictMode = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = NeonCrimson)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val csvDays = selectedDays.sorted().joinToString(",")
                    onSave(
                        ScheduleBlock(
                            id = initialSchedule?.id ?: 0L,
                            title = title.ifBlank { "Scheduled Focus Window" },
                            startHour = startHour.coerceIn(0, 23),
                            startMinute = startMinute.coerceIn(0, 59),
                            endHour = endHour.coerceIn(0, 23),
                            endMinute = endMinute.coerceIn(0, 59),
                            daysOfWeekCsv = csvDays,
                            targetPackagesCsv = initialSchedule?.targetPackagesCsv ?: "*",
                            isEnabled = true,
                            enforcesStrictMode = enforcesStrictMode
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = DarkBg
                )
            ) {
                Text("Save Schedule", fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
