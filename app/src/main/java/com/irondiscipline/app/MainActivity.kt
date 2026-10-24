package com.irondiscipline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.irondiscipline.app.ui.screens.AppRulesScreen
import com.irondiscipline.app.ui.screens.DashboardScreen
import com.irondiscipline.app.ui.screens.PermissionsWizardScreen
import com.irondiscipline.app.ui.screens.ScheduleScreen
import com.irondiscipline.app.ui.theme.DarkBg
import com.irondiscipline.app.ui.theme.DarkSurface
import com.irondiscipline.app.ui.theme.IronDisciplineTheme
import com.irondiscipline.app.ui.theme.NeonAmber
import com.irondiscipline.app.ui.theme.NeonCrimson
import com.irondiscipline.app.ui.theme.NeonCyan
import com.irondiscipline.app.ui.theme.NeonLime
import com.irondiscipline.app.ui.theme.TextMuted
import com.irondiscipline.app.ui.theme.TextPrimary
import com.irondiscipline.app.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IronDisciplineTheme {
                IronDisciplineMainApp(viewModel = mainViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IronDisciplineMainApp(viewModel: MainViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionsAndApps()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val dashboardMetrics by viewModel.dashboardMetrics.collectAsState()
    val realtimeQuotaState by viewModel.realtimeQuotaState.collectAsState()
    val timerState by viewModel.timerState.collectAsState()
    val permissionState by viewModel.permissionState.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val appRules by viewModel.appRules.collectAsState()
    val schedules by viewModel.schedules.collectAsState()
    val usageLogsToday by viewModel.usageLogsToday.collectAsState()
    val bannerMessage by viewModel.statusBannerMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    Scaffold(
        containerColor = DarkBg,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DarkSurface,
                    titleContentColor = TextPrimary
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = if (dashboardMetrics.isStrictModeEffective) NeonCrimson else NeonCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "IRON DISCIPLINE",
                                fontWeight = FontWeight.Black,
                                fontSize = 17.sp,
                                letterSpacing = 1.2.sp
                            )
                            Text(
                                text = if (dashboardMetrics.isStrictModeEffective) {
                                    "STRICT ANTI-BYPASS SHIELD ENGAGED"
                                } else if (permissionState.allCriticalGranted) {
                                    "FOCUS SHIELD ARMED • STREAK: ${dashboardMetrics.disciplineState.currentStreakDays}D"
                                } else {
                                    "SETUP REQUIRED: GRANT SHIELD PERMISSIONS"
                                },
                                color = when {
                                    dashboardMetrics.isStrictModeEffective -> NeonCrimson
                                    permissionState.allCriticalGranted -> NeonLime
                                    else -> NeonAmber
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 14.dp)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (permissionState.allCriticalGranted) NeonLime else NeonAmber
                            )
                    )
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface
            ) {
                NavigationBarItem(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Dashboard,
                            contentDescription = "Dashboard"
                        )
                    },
                    label = { Text("Dashboard", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = NeonCyan.copy(alpha = 0.14f)
                    )
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = "Blocked Apps & Quotas"
                        )
                    },
                    label = { Text("Apps & Quotas", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = NeonCyan.copy(alpha = 0.14f)
                    )
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "Schedules"
                        )
                    },
                    label = { Text("Schedules", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = NeonCyan.copy(alpha = 0.14f)
                    )
                )

                NavigationBarItem(
                    selected = selectedTabIndex == 3,
                    onClick = { selectedTabIndex = 3 },
                    icon = {
                        BadgedBox(
                            badge = {
                                if (!permissionState.allCriticalGranted) {
                                    Badge(containerColor = NeonAmber) {
                                        Text("!")
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = "Permissions Setup Wizard"
                            )
                        }
                    },
                    label = { Text("Permissions", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = NeonCyan,
                        selectedTextColor = NeonCyan,
                        unselectedIconColor = TextMuted,
                        unselectedTextColor = TextMuted,
                        indicatorColor = NeonCyan.copy(alpha = 0.14f)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTabIndex) {
                0 -> DashboardScreen(
                    metrics = dashboardMetrics,
                    realtimeQuotaState = realtimeQuotaState,
                    timerState = timerState,
                    permissionState = permissionState,
                    onActivateOneMinuteTestMode = viewModel::activateOneMinuteTestMode,
                    onSwitchBackToNormalThirtyMinMode = viewModel::switchBackToNormalThirtyMinModeAndReset,
                    onActivateStrictMode = viewModel::activateStrictMode,
                    onUpdateMotivationalQuote = viewModel::updateMotivationalQuote,
                    onSelectTimerPreset = viewModel::setDeepWorkPresetMinutes,
                    onUpdateTimerTitle = viewModel::updateDeepWorkTitle,
                    onStartOrPauseTimer = viewModel::startOrPauseDeepWorkTimer,
                    onFinishTimerEarly = viewModel::finishAndLogDeepWorkEarly,
                    onResetTimer = viewModel::resetDeepWorkTimer,
                    onNavigateToPermissionsTab = { selectedTabIndex = 3 }
                )

                1 -> AppRulesScreen(
                    installedApps = installedApps,
                    appRules = appRules,
                    usageLogsToday = usageLogsToday,
                    isStrictModeActive = dashboardMetrics.isStrictModeEffective,
                    onToggleAppBlock = viewModel::toggleAppBlocking,
                    onSaveAppRuleConfig = viewModel::updateAppRuleConfig
                )

                2 -> ScheduleScreen(
                    schedules = schedules,
                    isStrictModeActive = dashboardMetrics.isStrictModeEffective,
                    onCreateOrUpdateSchedule = viewModel::createOrUpdateSchedule,
                    onToggleSchedule = viewModel::toggleScheduleEnabled,
                    onDeleteSchedule = viewModel::deleteSchedule
                )

                3 -> PermissionsWizardScreen(
                    permissionState = permissionState,
                    onRefreshPermissions = viewModel::refreshPermissionsAndApps
                )
            }
        }
    }
}
