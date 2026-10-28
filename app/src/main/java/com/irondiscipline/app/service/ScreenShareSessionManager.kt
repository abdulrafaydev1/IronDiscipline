package com.irondiscipline.app.service

import android.content.Context
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import com.irondiscipline.app.screenshare.ScreenRelayClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Process-level singleton managing global persistent states for Live Screen Share (Sender & Viewer).
 * Survives dialog closes, back button navigation, activity destruction, and screen lock/off states.
 * Only stops when explicitly requested by user action.
 */
object ScreenShareSessionManager {

    // --- SENDER GLOBAL STATE ---
    private val _isSenderActive = MutableStateFlow(false)
    val isSenderActive: StateFlow<Boolean> = _isSenderActive.asStateFlow()

    private val _senderRoomCode = MutableStateFlow(generateRoomCode())
    val senderRoomCode: StateFlow<String> = _senderRoomCode.asStateFlow()

    private val _senderAudioMode = MutableStateFlow("BOTH") // Default: Internal + Mic
    val senderAudioMode: StateFlow<String> = _senderAudioMode.asStateFlow()

    private val _senderFramesSent = MutableStateFlow(0L)
    val senderFramesSent: StateFlow<Long> = _senderFramesSent.asStateFlow()

    private val _senderStatusText = MutableStateFlow("Idle")
    val senderStatusText: StateFlow<String> = _senderStatusText.asStateFlow()

    // --- VIEWER GLOBAL STATE ---
    private val _isViewerConnected = MutableStateFlow(false)
    val isViewerConnected: StateFlow<Boolean> = _isViewerConnected.asStateFlow()

    private val _viewerRoomCode = MutableStateFlow("")
    val viewerRoomCode: StateFlow<String> = _viewerRoomCode.asStateFlow()

    private val _viewerFrame = MutableStateFlow<Bitmap?>(null)
    val viewerFrame: StateFlow<Bitmap?> = _viewerFrame.asStateFlow()

    private val _viewerStatus = MutableStateFlow("Idle")
    val viewerStatus: StateFlow<String> = _viewerStatus.asStateFlow()

    private val _viewerFramesReceived = MutableStateFlow(0L)
    val viewerFramesReceived: StateFlow<Long> = _viewerFramesReceived.asStateFlow()

    private val _isViewerAudioMuted = MutableStateFlow(false)
    val isViewerAudioMuted: StateFlow<Boolean> = _isViewerAudioMuted.asStateFlow()

    private var activeViewerClient: ScreenRelayClient? = null
    private var viewerWakeLock: PowerManager.WakeLock? = null
    private var viewerWifiLock: WifiManager.WifiLock? = null

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

    /**
     * Starts persistent Viewer connection that stays alive even if the Viewer closes the dialog,
     * leaves the app, or locks their phone screen.
     * ONLY stops when `disconnectViewerExplicitly()` is called by the user.
     */
    @Synchronized
    fun connectViewerPersistent(context: Context, code: String) {
        val cleanCode = code.trim()
        if (cleanCode.length < 4) return

        // If already connected to this exact room code, keep running without interruption
        if (_isViewerConnected.value && _viewerRoomCode.value == cleanCode && activeViewerClient != null) {
            return
        }

        // Clean up any previous viewer connection
        disconnectViewerExplicitly()

        _viewerRoomCode.value = cleanCode
        _isViewerConnected.value = true
        _viewerStatus.value = "Connecting to relay..."
        _viewerFramesReceived.value = 0L

        acquireViewerLocks(context.applicationContext)

        val client = ScreenRelayClient(cleanCode)
        client.setAudioMuted(_isViewerAudioMuted.value)
        activeViewerClient = client

        client.startViewer(
            onFrameDecoded = { bmp ->
                _viewerFrame.value = bmp
                _viewerFramesReceived.value += 1
                _isViewerConnected.value = true
            },
            onStatusChanged = { status ->
                _viewerStatus.value = status
                if (status.contains("LIVE", true)) {
                    _isViewerConnected.value = true
                }
            }
        )
    }

    /**
     * ONLY called when the user explicitly clicks the "Disconnect" button.
     * NEVER call this from Compose `onDispose` or `onStop`.
     */
    @Synchronized
    fun disconnectViewerExplicitly() {
        _isViewerConnected.value = false
        _viewerStatus.value = "Disconnected"
        try {
            activeViewerClient?.stop()
        } catch (_: Exception) {}
        activeViewerClient = null
        _viewerFrame.value = null
        _viewerFramesReceived.value = 0L
        releaseViewerLocks()
    }

    fun toggleViewerMute(): Boolean {
        val next = !_isViewerAudioMuted.value
        _isViewerAudioMuted.value = next
        activeViewerClient?.setAudioMuted(next)
        return next
    }

    private fun acquireViewerLocks(appContext: Context) {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && viewerWakeLock == null) {
                viewerWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IronDiscipline:ViewerStreamLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (viewerWakeLock?.isHeld == false) {
                viewerWakeLock?.acquire()
            }

            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && viewerWifiLock == null) {
                @Suppress("DEPRECATION")
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                viewerWifiLock = wm.createWifiLock(lockMode, "IronDiscipline:ViewerWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (viewerWifiLock?.isHeld == false) {
                viewerWifiLock?.acquire()
            }
        } catch (_: Exception) {}
    }

    private fun releaseViewerLocks() {
        try {
            if (viewerWakeLock?.isHeld == true) viewerWakeLock?.release()
        } catch (_: Exception) {}
        viewerWakeLock = null

        try {
            if (wifiLockHeld(viewerWifiLock)) viewerWifiLock?.release()
        } catch (_: Exception) {}
        viewerWifiLock = null
    }

    private fun wifiLockHeld(lock: WifiManager.WifiLock?): Boolean {
        return try {
            lock?.isHeld == true
        } catch (_: Exception) {
            false
        }
    }
}
