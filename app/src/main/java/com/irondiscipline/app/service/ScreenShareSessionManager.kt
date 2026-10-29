package com.irondiscipline.app.service

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.irondiscipline.app.screenshare.ScreenRelayClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class RemotePhoneSession(
    val roomCode: String,
    val phoneName: String = "Connecting (#$roomCode)...",
    val latestFrame: Bitmap? = null,
    val status: String = "Connecting...",
    val isAudioMuted: Boolean = false,
    val isStreamAvailable: Boolean = true,
    val lastFrameTimeMs: Long = 0L
)

/**
 * Process-level singleton managing global persistent states for Live Screen Share (Sender & Multi-Phone Viewer).
 * Survives dialog closes, back button navigation, activity destruction, and screen lock/off states.
 * Connects to multiple Sender phones simultaneously, each in its own dedicated named tab.
 */
object ScreenShareSessionManager {

    private val managerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isNetworkMonitoringRegistered = false

    // --- SENDER GLOBAL STATE ---
    private val _isSenderActive = MutableStateFlow(false)
    val isSenderActive: StateFlow<Boolean> = _isSenderActive.asStateFlow()

    private val _senderRoomCode = MutableStateFlow(generateRoomCode())
    val senderRoomCode: StateFlow<String> = _senderRoomCode.asStateFlow()

    // Supports: "INTERNAL", "MIC", "BOTH", "NONE" (Silent Mode)
    private val _senderAudioMode = MutableStateFlow("BOTH")
    val senderAudioMode: StateFlow<String> = _senderAudioMode.asStateFlow()

    private val _senderFramesSent = MutableStateFlow(0L)
    val senderFramesSent: StateFlow<Long> = _senderFramesSent.asStateFlow()

    private val _senderStatusText = MutableStateFlow("Idle")
    val senderStatusText: StateFlow<String> = _senderStatusText.asStateFlow()

    // --- MULTI-PHONE VIEWER STATE ---
    private val _viewerSessions = MutableStateFlow<List<RemotePhoneSession>>(emptyList())
    val viewerSessions: StateFlow<List<RemotePhoneSession>> = _viewerSessions.asStateFlow()

    private val _selectedTabRoomCode = MutableStateFlow<String?>(null)
    val selectedTabRoomCode: StateFlow<String?> = _selectedTabRoomCode.asStateFlow()

    private val _isViewerConnected = MutableStateFlow(false)
    val isViewerConnected: StateFlow<Boolean> = _isViewerConnected.asStateFlow()

    private val activeClients = ConcurrentHashMap<String, ScreenRelayClient>()
    private var viewerWakeLock: PowerManager.WakeLock? = null
    private var viewerWifiLock: WifiManager.WifiLock? = null

    init {
        // Continuous Watchdog: If no frames received for > 3000ms, mark stream offline
        managerScope.launch {
            while (true) {
                delay(750)
                val now = System.currentTimeMillis()
                _viewerSessions.update { list ->
                    list.map { session ->
                        if (session.isStreamAvailable && session.lastFrameTimeMs > 0L && (now - session.lastFrameTimeMs > 3000L)) {
                            session.copy(isStreamAvailable = false, status = "🔴 Stream Offline")
                        } else {
                            session
                        }
                    }
                }
            }
        }
    }

    fun generateRoomCode(): String = Random.nextInt(100000, 999999).toString()

    fun regenerateSenderRoomCode() {
        if (!_isSenderActive.value) {
            _senderRoomCode.value = generateRoomCode()
        }
    }

    fun setSenderActive(active: Boolean, code: String? = null, status: String = "Idle") {
        if (code != null) _senderRoomCode.value = code
        _isSenderActive.value = active
        _senderStatusText.value = status
        if (!active) {
            _senderFramesSent.value = 0L
        }
    }

    fun setSenderAudioMode(mode: String) {
        _senderAudioMode.value = mode
    }

    fun incrementSenderFrames() {
        _senderFramesSent.value += 1
    }

    fun startNetworkMonitoring(context: Context) {
        if (isNetworkMonitoringRegistered) return
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Internet restored! Immediately trigger reconnect on all clients
                activeClients.values.forEach { it.triggerImmediateReconnect() }
            }

            override fun onLost(network: Network) {
                // Internet lost! Immediately mark all sessions stream unavailable
                _viewerSessions.update { list ->
                    list.map { it.copy(isStreamAvailable = false, status = "🔴 Disconnected") }
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            isNetworkMonitoringRegistered = true
        } catch (_: Exception) {}
    }

    /**
     * Connects to a new Sender phone by its 6-digit roomCode and adds a new Tab for it
     * without disconnecting existing phone tabs!
     */
    @Synchronized
    fun addOrSelectViewerPhone(context: Context, rawCode: String) {
        val code = rawCode.trim()
        if (code.length < 4) return

        // If already connected to this code, simply switch to its tab!
        if (activeClients.containsKey(code)) {
            selectViewerTab(code)
            return
        }

        acquireViewerLocks(context.applicationContext)
        startNetworkMonitoring(context.applicationContext)

        val initialSession = RemotePhoneSession(
            roomCode = code,
            phoneName = "Phone #$code",
            latestFrame = null,
            status = "Connecting...",
            isAudioMuted = false,
            isStreamAvailable = true,
            lastFrameTimeMs = System.currentTimeMillis()
        )
        _viewerSessions.update { current -> current + initialSession }
        _selectedTabRoomCode.value = code
        _isViewerConnected.value = true
        syncAudioFocusAcrossTabs(selectedCode = code)

        val client = ScreenRelayClient(code)
        activeClients[code] = client

        client.startViewer(
            onFrameDecoded = { detectedPhoneName, bmp ->
                val now = System.currentTimeMillis()
                _viewerSessions.update { list ->
                    list.map { item ->
                        if (item.roomCode == code) {
                            item.copy(
                                phoneName = detectedPhoneName.ifBlank { item.phoneName },
                                latestFrame = bmp,
                                status = "🟢 LIVE",
                                isStreamAvailable = true,
                                lastFrameTimeMs = now
                            )
                        } else item
                    }
                }
            },
            onStatusChanged = { newStatus ->
                _viewerSessions.update { list ->
                    list.map { item ->
                        if (item.roomCode == code) {
                            val isLive = newStatus.contains("LIVE", true)
                            item.copy(
                                status = newStatus,
                                isStreamAvailable = if (isLive) true else if (newStatus.contains("Reconnecting", true) || newStatus.contains("Connecting", true)) false else item.isStreamAvailable
                            )
                        } else item
                    }
                }
            }
        )
    }

    /**
     * Switches the active Viewer Tab to `code` and automatically routes speaker audio
     * to the selected tab (unless manually muted by the user) so multiple phones don't talk over each other.
     */
    fun selectViewerTab(code: String) {
        _selectedTabRoomCode.value = code
        syncAudioFocusAcrossTabs(selectedCode = code)
    }

    fun toggleMuteForPhone(code: String) {
        _viewerSessions.update { list ->
            list.map { item ->
                if (item.roomCode == code) {
                    item.copy(isAudioMuted = !item.isAudioMuted)
                } else item
            }
        }
        syncAudioFocusAcrossTabs(selectedCode = _selectedTabRoomCode.value)
    }

    private fun syncAudioFocusAcrossTabs(selectedCode: String?) {
        val sessions = _viewerSessions.value
        for (session in sessions) {
            val client = activeClients[session.roomCode] ?: continue
            // Play audio only for the currently selected tab if its mute toggle is OFF
            val shouldMute = (session.roomCode != selectedCode) || session.isAudioMuted
            client.setAudioMuted(shouldMute)
        }
    }

    /**
     * Disconnects ONLY the specified phone tab (`code`). Other connected phone tabs keep running!
     */
    @Synchronized
    fun disconnectSinglePhoneTab(code: String) {
        try {
            activeClients.remove(code)?.stop()
        } catch (_: Exception) {}

        _viewerSessions.update { list -> list.filterNot { it.roomCode == code } }

        val remaining = _viewerSessions.value
        _isViewerConnected.value = remaining.isNotEmpty()

        if (remaining.isEmpty()) {
            _selectedTabRoomCode.value = null
            releaseViewerLocks()
        } else if (_selectedTabRoomCode.value == code) {
            val nextCode = remaining.last().roomCode
            _selectedTabRoomCode.value = nextCode
            syncAudioFocusAcrossTabs(selectedCode = nextCode)
        }
    }

    @Synchronized
    fun disconnectAllViewerTabs() {
        activeClients.values.forEach {
            try { it.stop() } catch (_: Exception) {}
        }
        activeClients.clear()
        _viewerSessions.value = emptyList()
        _selectedTabRoomCode.value = null
        _isViewerConnected.value = false
        releaseViewerLocks()
    }

    private fun acquireViewerLocks(appContext: Context) {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && viewerWakeLock == null) {
                viewerWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IronDiscipline:MultiViewerLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (viewerWakeLock?.isHeld == false) viewerWakeLock?.acquire()

            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && viewerWifiLock == null) {
                @Suppress("DEPRECATION")
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                viewerWifiLock = wm.createWifiLock(lockMode, "IronDiscipline:MultiViewerWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (viewerWifiLock?.isHeld == false) viewerWifiLock?.acquire()
        } catch (_: Exception) {}
    }

    private fun releaseViewerLocks() {
        try { if (viewerWakeLock?.isHeld == true) viewerWakeLock?.release() } catch (_: Exception) {}
        viewerWakeLock = null
        try { if (viewerWifiLock?.isHeld == true) viewerWifiLock?.release() } catch (_: Exception) {}
        viewerWifiLock = null
    }
}
