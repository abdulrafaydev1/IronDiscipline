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
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.irondiscipline.app.ui.screens.DashboardScreen
import com.irondiscipline.app.ui.theme.DarkBg
import com.irondiscipline.app.ui.theme.DarkSurface
import com.irondiscipline.app.ui.theme.IronDisciplineTheme
import com.irondiscipline.app.ui.theme.NeonAmber
import com.irondiscipline.app.ui.theme.NeonCyan
import com.irondiscipline.app.ui.theme.NeonLime
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

    override fun onResume() {
        super.onResume()
        IronDisciplineApp.isMainActivityVisible = true
    }

    override fun onPause() {
        IronDisciplineApp.isMainActivityVisible = false
        super.onPause()
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

    val realtimeQuotaState by viewModel.realtimeQuotaState.collectAsState()
    val permissionState by viewModel.permissionState.collectAsState()
    val bannerMessage by viewModel.statusBannerMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

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
                            tint = if (permissionState.allCriticalGranted) NeonLime else NeonAmber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "IronDiscipline - App Blocker",
                                fontWeight = FontWeight.Black,
                                fontSize = 16.sp,
                                letterSpacing = 1.0.sp
                            )
                            Text(
                                text = if (!permissionState.allCriticalGranted) {
                                    "PERMISSIONS SETUP REQUIRED"
                                } else if (realtimeQuotaState.isTestModeActive) {
                                    "TEST MODE: 1-MIN LIMIT PER APP"
                                } else {
                                    "SHIELD ACTIVE • FIXED 30-MIN LIMIT PER APP"
                                },
                                color = when {
                                    !permissionState.allCriticalGranted -> NeonAmber
                                    realtimeQuotaState.isTestModeActive -> NeonAmber
                                    else -> NeonLime
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
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            DashboardScreen(
                realtimeQuotaState = realtimeQuotaState,
                permissionState = permissionState,
                onActivateOneMinuteTestMode = viewModel::activateOneMinuteTestMode,
                onSwitchBackToNormalThirtyMinMode = viewModel::switchBackToNormalThirtyMinModeAndReset,
                onResetAllTimers = viewModel::unlockAndResetTimerForTesting,
                onResetSingleApp = viewModel::resetSingleApp,
                onRefreshPermissions = viewModel::refreshPermissionsAndApps
            )
        }
    }
}
