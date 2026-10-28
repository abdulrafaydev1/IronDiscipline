package com.irondiscipline.app.screenshare

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-Time Screen & Audio Viewer Engine.
 * Wraps ScreenRelayClient's dual-channel network engine and audio playback system.
 */
class ScreenViewerEngine {

    private var relayClient: ScreenRelayClient? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    private val _currentFrame = MutableStateFlow<ImageBitmap?>(null)
    val currentFrame = _currentFrame.asStateFlow()

    private val _framesReceived = MutableStateFlow(0L)
    val framesReceived = _framesReceived.asStateFlow()

    private val _isAudioMuted = MutableStateFlow(false)
    val isAudioMuted = _isAudioMuted.asStateFlow()

    private val _statusMessage = MutableStateFlow("Enter 6-digit Room Code to watch")
    val statusMessage = _statusMessage.asStateFlow()

    fun toggleAudioMute() {
        val newMuted = !_isAudioMuted.value
        _isAudioMuted.value = newMuted
        relayClient?.setAudioMuted(newMuted)
    }

    fun connect(roomCode: String) {
        val cleanCode = roomCode.trim()
        if (cleanCode.length != 6) {
            _statusMessage.value = "Please enter a valid 6-digit Room Code"
            return
        }

        disconnect()

        _isConnecting.value = true
        _statusMessage.value = "Connecting to encrypted stream..."

        val client = ScreenRelayClient(cleanCode)
        client.setAudioMuted(_isAudioMuted.value)
        this.relayClient = client

        client.startViewer(
            onFrameDecoded = { bmp ->
                _currentFrame.value = bmp.asImageBitmap()
                _framesReceived.value += 1
                _isConnected.value = true
                _isConnecting.value = false
            },
            onStatusChanged = { status ->
                _statusMessage.value = status
                if (status.contains("LIVE", true)) {
                    _isConnected.value = true
                    _isConnecting.value = false
                }
            }
        )
    }

    fun disconnect() {
        try {
            relayClient?.stop()
        } catch (_: Exception) {}
        relayClient = null
        _isConnected.value = false
        _isConnecting.value = false
        _currentFrame.value = null
        _framesReceived.value = 0L
        _statusMessage.value = "Disconnected"
    }
}
