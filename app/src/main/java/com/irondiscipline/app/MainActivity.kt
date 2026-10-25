package com.irondiscipline.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.irondiscipline.app.ui.screens.DashboardScreen
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

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearBannerMessage()
        }
    }

    Scaffold(
        containerColor = Color(0xFF06080E),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
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
