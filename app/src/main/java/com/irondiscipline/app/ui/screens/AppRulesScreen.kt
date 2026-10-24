package com.irondiscipline.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.irondiscipline.app.data.local.entities.AppBlockRule
import com.irondiscipline.app.data.local.entities.DailyUsageLog
import com.irondiscipline.app.domain.model.InstalledAppInfo
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

private enum class AppFilterMode {
    ALL,
    HIGH_RISK,
    BLOCKED_ONLY
}

@Composable
fun AppRulesScreen(
    installedApps: List<InstalledAppInfo>,
    appRules: List<AppBlockRule>,
    usageLogsToday: List<DailyUsageLog>,
    isStrictModeActive: Boolean,
    onToggleAppBlock: (InstalledAppInfo, AppBlockRule?, Boolean) -> Unit,
    onSaveAppRuleConfig: (
        appInfo: InstalledAppInfo,
        existingRule: AppBlockRule?,
        quotaMinutes: Int,
        blockDuringSchedules: Boolean,
        hasCustomSchedule: Boolean,
        startH: Int,
        startM: Int,
        endH: Int,
        endM: Int
    ) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(AppFilterMode.ALL) }
    var selectedAppForConfig by remember { mutableStateOf<InstalledAppInfo?>(null) }

    val rulesByPkg = remember(appRules) { appRules.associateBy { it.packageName } }
    val usageByPkg = remember(usageLogsToday) { usageLogsToday.associateBy { it.packageName } }

    // Merge installedApps with any saved rule whose package isn't in installedApps yet
    val mergedApps = remember(installedApps, appRules) {
        val map = LinkedHashMap<String, InstalledAppInfo>()
        installedApps.forEach { map[it.packageName] = it }
        appRules.forEach { rule ->
            if (!map.containsKey(rule.packageName)) {
                map[rule.packageName] = InstalledAppInfo(
                    packageName = rule.packageName,
                    appName = rule.appName,
                    isHighRisk = rule.isHighRisk,
                    isInstalledOnDevice = false
                )
            }
        }
        map.values.toList()
    }

    val filteredApps = remember(mergedApps, rulesByPkg, searchQuery, filterMode) {
        mergedApps.filter { app ->
            val rule = rulesByPkg[app.packageName]
            val matchesQuery = searchQuery.isBlank() ||
                app.appName.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (filterMode) {
                AppFilterMode.ALL -> true
                AppFilterMode.HIGH_RISK -> app.isHighRisk
                AppFilterMode.BLOCKED_ONLY -> rule?.isBlocked == true
            }
            matchesQuery && matchesFilter
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Search Field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            singleLine = true,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = NeonCyan
                )
            },
            placeholder = { Text("Search Instagram, TikTok, YouTube, or any app...") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = DarkBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Filter Chips Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterMode == AppFilterMode.ALL,
                onClick = { filterMode = AppFilterMode.ALL },
                label = { Text("All (${mergedApps.size})") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NeonCyan.copy(alpha = 0.2f),
                    selectedLabelColor = NeonCyan
                )
            )
            FilterChip(
                selected = filterMode == AppFilterMode.HIGH_RISK,
                onClick = { filterMode = AppFilterMode.HIGH_RISK },
                label = { Text("High-Risk Targets") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NeonCrimson.copy(alpha = 0.2f),
                    selectedLabelColor = NeonCrimson
                )
            )
            FilterChip(
                selected = filterMode == AppFilterMode.BLOCKED_ONLY,
                onClick = { filterMode = AppFilterMode.BLOCKED_ONLY },
                label = { Text("Targeted") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = NeonLime.copy(alpha = 0.2f),
                    selectedLabelColor = NeonLime
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredApps, key = { it.packageName }) { appInfo ->
                val rule = rulesByPkg[appInfo.packageName]
                val usageLog = usageByPkg[appInfo.packageName]
                AppTargetRuleItemCard(
                    appInfo = appInfo,
                    rule = rule,
                    usageLog = usageLog,
                    isStrictModeActive = isStrictModeActive,
                    onToggleBlock = { enabled ->
                        onToggleAppBlock(appInfo, rule, enabled)
                    },
                    onOpenConfig = {
                        selectedAppForConfig = appInfo
                    }
                )
            }
        }
    }

    selectedAppForConfig?.let { targetApp ->
        val existingRule = rulesByPkg[targetApp.packageName]
        AppQuotaAndScheduleConfigDialog(
            appInfo = targetApp,
            existingRule = existingRule,
            isStrictModeActive = isStrictModeActive,
            onDismiss = { selectedAppForConfig = null },
            onSave = { quotaMins, blockInSchedules, hasCustomSched, sh, sm, eh, em ->
                onSaveAppRuleConfig(
                    targetApp,
                    existingRule,
                    quotaMins,
                    blockInSchedules,
                    hasCustomSched,
                    sh,
                    sm,
                    eh,
                    em
                )
                selectedAppForConfig = null
            }
        )
    }
}

@Composable
private fun AppTargetRuleItemCard(
    appInfo: InstalledAppInfo,
    rule: AppBlockRule?,
    usageLog: DailyUsageLog?,
    isStrictModeActive: Boolean,
    onToggleBlock: (Boolean) -> Unit,
    onOpenConfig: () -> Unit
) {
    val isBlocked = rule?.isBlocked == true
    val quotaMinutes = rule?.dailyQuotaMinutes ?: 15
    val usedSeconds = usageLog?.usedSeconds ?: 0L
    val usedMinutes = (usedSeconds / 60L).toInt()
    val blockedCount = usageLog?.blockedAttemptsCount ?: 0

    val quotaProgress = if (quotaMinutes <= 0) {
        1f
    } else {
        (usedSeconds.toFloat() / (quotaMinutes * 60f)).coerceIn(0f, 1f)
    }

    val borderColor = when {
        isBlocked && appInfo.isHighRisk -> NeonCrimson.copy(alpha = 0.7f)
        isBlocked -> NeonCyan.copy(alpha = 0.6f)
        else -> DarkBorder
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DarkCard)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(
                                if (appInfo.isHighRisk) NeonCrimson.copy(alpha = 0.16f)
                                else NeonCyan.copy(alpha = 0.14f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (appInfo.isHighRisk) {
                                Icons.Default.WarningAmber
                            } else {
                                Icons.Default.Security
                            },
                            contentDescription = null,
                            tint = if (appInfo.isHighRisk) NeonCrimson else NeonCyan,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = appInfo.appName,
                                color = TextPrimary,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 16.sp
                            )
                            if (appInfo.isHighRisk) {
                                Text(
                                    text = "HIGH RISK",
                                    color = NeonCrimson,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(NeonCrimson.copy(alpha = 0.16f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = appInfo.packageName,
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }

                Switch(
                    checked = isBlocked,
                    onCheckedChange = onToggleBlock,
                    enabled = !(isStrictModeActive && isBlocked),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NeonCyan,
                        checkedTrackColor = NeonCyan.copy(alpha = 0.3f)
                    )
                )
            }

            if (isBlocked) {
                Spacer(modifier = Modifier.height(12.dp))

                // Quota Usage Summary
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val quotaDesc = if (quotaMinutes <= 0) {
                        "Zero-Tolerance (0m/day allowed)"
                    } else {
                        "Used today: ${usedMinutes}m / ${quotaMinutes}m daily quota"
                    }
                    Text(
                        text = quotaDesc,
                        color = if (quotaProgress >= 1f) NeonCrimson else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (blockedCount > 0) {
                        Text(
                            text = "$blockedCount intercepts today",
                            color = NeonAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { quotaProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = if (quotaProgress >= 1f) NeonCrimson else NeonCyan,
                    trackColor = DarkSurface
                )

                if (rule?.hasCustomSchedule == true) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Custom App Lock Window: ${rule.formattedCustomSchedule()}",
                        color = NeonAmber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onOpenConfig,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = NeonCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Configure Daily Quota & App Schedule",
                        color = NeonCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppQuotaAndScheduleConfigDialog(
    appInfo: InstalledAppInfo,
    existingRule: AppBlockRule?,
    isStrictModeActive: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        quotaMinutes: Int,
        blockDuringSchedules: Boolean,
        hasCustomSchedule: Boolean,
        startHour: Int,
        startMinute: Int,
        endHour: Int,
        endMinute: Int
    ) -> Unit
) {
    val quotaPresets = listOf(0, 5, 10, 15, 30, 45, 60)
    var quotaMinutesText by remember {
        mutableStateOf((existingRule?.dailyQuotaMinutes ?: 15).toString())
    }
    var blockDuringSchedules by remember {
        mutableStateOf(existingRule?.blockDuringSchedules ?: true)
    }
    var hasCustomSchedule by remember {
        mutableStateOf(existingRule?.hasCustomSchedule ?: false)
    }
    var startHour by remember { mutableIntStateOf(existingRule?.customStartHour ?: 9) }
    var startMinute by remember { mutableIntStateOf(existingRule?.customStartMinute ?: 0) }
    var endHour by remember { mutableIntStateOf(existingRule?.customEndHour ?: 18) }
    var endMinute by remember { mutableIntStateOf(existingRule?.customEndMinute ?: 0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkCard,
        title = {
            Text(
                text = "Configure ${appInfo.appName}",
                color = TextPrimary,
                fontWeight = FontWeight.Black,
                fontSize = 18.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Daily Time Quota (Minutes / Day):",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = "Set to 0m for complete all-day blocking, or choose a strict daily allowance.",
                    color = TextMuted,
                    fontSize = 11.sp
                )

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quotaPresets.forEach { preset ->
                        val selected = quotaMinutesText.toIntOrNull() == preset
                        FilterChip(
                            selected = selected,
                            onClick = { quotaMinutesText = preset.toString() },
                            label = {
                                Text(
                                    text = if (preset == 0) "0m (Hard Lock)" else "${preset}m",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = NeonCyan.copy(alpha = 0.22f),
                                selectedLabelColor = NeonCyan
                            )
                        )
                    }
                }

                OutlinedTextField(
                    value = quotaMinutesText,
                    onValueChange = { input ->
                        if (input.length <= 3 && input.all { it.isDigit() }) {
                            quotaMinutesText = input
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("Custom Daily Quota (Minutes)") },
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Lock During Global Schedules",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Blocks this app during active Schedule Windows regardless of remaining quota",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = blockDuringSchedules,
                        onCheckedChange = { blockDuringSchedules = it },
                        enabled = !isStrictModeActive
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Per-App Custom Block Window",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Additional daily lock interval specific to ${appInfo.appName}",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = hasCustomSchedule,
                        onCheckedChange = { hasCustomSchedule = it }
                    )
                }

                if (hasCustomSchedule) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = startHour.toString(),
                            onValueChange = {
                                startHour = (it.toIntOrNull() ?: 0).coerceIn(0, 23)
                            },
                            label = { Text("Start Hr (0-23)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = startMinute.toString(),
                            onValueChange = {
                                startMinute = (it.toIntOrNull() ?: 0).coerceIn(0, 59)
                            },
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
                            onValueChange = {
                                endHour = (it.toIntOrNull() ?: 0).coerceIn(0, 23)
                            },
                            label = { Text("End Hr (0-23)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = endMinute.toString(),
                            onValueChange = {
                                endMinute = (it.toIntOrNull() ?: 0).coerceIn(0, 59)
                            },
                            label = { Text("End Min") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedQuota = (quotaMinutesText.toIntOrNull() ?: 15).coerceIn(0, 360)
                    onSave(
                        parsedQuota,
                        blockDuringSchedules,
                        hasCustomSchedule,
                        startHour,
                        startMinute,
                        endHour,
                        endMinute
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = NeonCyan,
                    contentColor = DarkBg
                )
            ) {
                Text("Save Rule", fontWeight = FontWeight.Black)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
