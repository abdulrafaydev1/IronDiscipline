package com.irondiscipline.app.screenshare

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Real-Time Screen Viewer Engine.
 * Subscribes to broker.emqx.io on the target Room Code and decodes incoming JPEG frames to Compose ImageBitmap.
 */
class ScreenViewerEngine {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var relayClient: ScreenRelayClient? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _isConnecting = MutableStateFlow(false)
    val isConnecting = _isConnecting.asStateFlow()

    private val _currentFrame = MutableStateFlow<ImageBitmap?>(null)
    val currentFrame = _currentFrame.asStateFlow()

    private val _framesReceived = MutableStateFlow(0L)
    val framesReceived = _framesReceived.asStateFlow()

    private val _statusMessage = MutableStateFlow("Enter 6-digit Room Code to watch")
    val statusMessage = _statusMessage.asStateFlow()

    fun connect(roomCode: String) {
        val cleanCode = roomCode.trim()
        if (cleanCode.length != 6) {
            _statusMessage.value = "Please enter a valid 6-digit Room Code"
            return
        }

        disconnect()

        scope.launch {
            _isConnecting.value = true
            _statusMessage.value = "Connecting to relay broker..."

            val client = ScreenRelayClient()
            val ok = client.connect(timeoutMs = 9000)
            if (!ok) {
                _isConnecting.value = false
                _statusMessage.value = "Connection failed. Please check internet and try again."
                return@launch
            }

            relayClient = client
            _isConnected.value = true
            _isConnecting.value = false
            _statusMessage.value = "Connected to Room #$cleanCode. Waiting for screen frames..."

            val topic = "irondiscipline/screenshare/$cleanCode"
            val subscribed = client.subscribe(topic) { frameBytes ->
                try {
                    val bmp = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                    if (bmp != null) {
                        _currentFrame.value = bmp.asImageBitmap()
                        _framesReceived.value += 1
                        _statusMessage.value = "Live Broadcast Active (Room #$cleanCode)"
                    }
                } catch (e: Exception) {
                    Log.e("ScreenViewerEngine", "Error decoding frame: ${e.message}")
                }
            }

            if (!subscribed) {
                _statusMessage.value = "Failed to subscribe to Room #$cleanCode"
                disconnect()
            }
        }
    }

    fun disconnect() {
        scope.launch {
            try {
                relayClient?.disconnect()
            } catch (_: Exception) {}
            relayClient = null
            _isConnected.value = false
            _isConnecting.value = false
            _currentFrame.value = null
            _framesReceived.value = 0L
            _statusMessage.value = "Disconnected"
        }
    }
}
