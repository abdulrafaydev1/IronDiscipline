package com.irondiscipline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.irondiscipline.app.alarm.AlarmRepository
import com.irondiscipline.app.ui.components.jellyElastic
import com.irondiscipline.app.ui.screens.AlarmScreen
import com.irondiscipline.app.ui.screens.DashboardScreen
import com.irondiscipline.app.ui.screens.SettingsScreen
import com.irondiscipline.app.ui.theme.IronDisciplineTheme
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

    override fun onResume() {
        super.onResume()
        IronDisciplineApp.isMainActivityVisible = true
    }

    override fun onPause() {
        IronDisciplineApp.isMainActivityVisible = false
        super.onPause()
    }
}

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

    val realtimeQuotaState by viewModel.realtimeQuotaState.collectAsState()
    val permissionState by viewModel.permissionState.collectAsState()
    val bannerMessage by viewModel.statusBannerMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    // Auto-prompt Device Admin on app launch if not enabled yet
    LaunchedEffect(Unit) {
        val dpm = context.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val adminComponent = android.content.ComponentName(context, FocusDeviceAdminReceiver::class.java)
        if (dpm != null && !dpm.isAdminActive(adminComponent)) {
            try {
                val intent = android.content.Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(
                        android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Prevents accidental uninstallation without your 4-digit PIN."
                    )
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    Scaffold(
        containerColor = Color(0xFF06080E),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = Color(0xFF0B0F19),
                modifier = Modifier.border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(Color(0xFF1E293B), Color(0xFF0B0F19))
                        )
                    )
                )
            ) {
                // Tab 1: Dashboard
                NavigationBarItem(
                    modifier = Modifier.jellyElastic(enabled = true, baseCornerRadius = 24.dp, maxTranslationDp = 16.dp, maxStretchScale = 0.25f),
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Dashboard"
                        )
                    },
                    label = {
                        Text(
                            text = "Dashboard",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF00E676),
                        selectedTextColor = Color(0xFF00E676),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B),
                        indicatorColor = Color(0xFF00E676).copy(alpha = 0.15f)
                    )
                )

                // Tab 2: Alarm (Center Tab)
                NavigationBarItem(
                    modifier = Modifier.jellyElastic(enabled = true, baseCornerRadius = 24.dp, maxTranslationDp = 16.dp, maxStretchScale = 0.25f),
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Alarm,
                            contentDescription = "Alarm"
                        )
                    },
                    label = {
                        Text(
                            text = "Alarm",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFFFF2E54),
                        selectedTextColor = Color(0xFFFF2E54),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B),
                        indicatorColor = Color(0xFFFF2E54).copy(alpha = 0.15f)
                    )
                )

                // Tab 3: Settings
                NavigationBarItem(
                    modifier = Modifier.jellyElastic(enabled = true, baseCornerRadius = 24.dp, maxTranslationDp = 16.dp, maxStretchScale = 0.25f),
                    selected = selectedTabIndex == 2,
                    onClick = { selectedTabIndex = 2 },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings"
                        )
                    },
                    label = {
                        Text(
                            text = "Settings",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFF38BDF8),
                        selectedTextColor = Color(0xFF38BDF8),
                        unselectedIconColor = Color(0xFF64748B),
                        unselectedTextColor = Color(0xFF64748B),
                        indicatorColor = Color(0xFF38BDF8).copy(alpha = 0.15f)
                    )
                )
            }
        }
    ) { innerPadding ->
        val context = LocalContext.current
        val alarmRepository = remember { AlarmRepository.getInstance(context) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            when (selectedTabIndex) {
                0 -> DashboardScreen(
                    realtimeQuotaState = realtimeQuotaState,
                    permissionState = permissionState,
                    onActivateOneMinuteTestMode = viewModel::activateOneMinuteTestMode,
                    onSwitchBackToNormalThirtyMinMode = viewModel::switchBackToNormalThirtyMinModeAndReset,
                    onRefreshPermissions = viewModel::refreshPermissionsAndApps,
                    onVerifyPin = viewModel::verifyUninstallPin,
                    onConfirmUninstall = viewModel::executeAuthorizedUninstall
                )
                1 -> AlarmScreen(
                    alarmRepository = alarmRepository
                )
                2 -> SettingsScreen(
                    permissionState = permissionState,
                    onRefreshPermissions = viewModel::refreshPermissionsAndApps,
                    onVerifyPin = viewModel::verifyUninstallPin,
                    onConfirmUninstall = viewModel::executeAuthorizedUninstall,
                    onChangePin = viewModel::updateUninstallPin
                )
            }
        }
    }
}
