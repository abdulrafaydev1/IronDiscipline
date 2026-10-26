package com.irondiscipline.app.screenshare

import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
 * Real-Time Screen & Audio Viewer Engine.
 * Subscribes to broker.emqx.io on the hashed wildcard topic and decrypts AES-256-GCM
 * video frames and real-time audio PCM stream for playback via AudioTrack.
 */
class ScreenViewerEngine {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var relayClient: ScreenRelayClient? = null
    private var viewerAudioTrack: AudioTrack? = null

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
        _isAudioMuted.value = !_isAudioMuted.value
    }

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

            // Initialize AudioTrack for real-time 16kHz 16-bit PCM streaming
            try {
                val minTrackBuf = AudioTrack.getMinBufferSize(
                    16000,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(3200)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(16000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minTrackBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()
                viewerAudioTrack = track
            } catch (e: Exception) {
                Log.e("ScreenViewerEngine", "Failed to initialize AudioTrack: ${e.message}")
            }

            relayClient = client
            _isConnected.value = true
            _isConnecting.value = false
            _statusMessage.value = "Connected to Room #$cleanCode. Waiting for stream..."

            val wildcardTopic = ScreenCryptoHelper.baseTopicWildcard(cleanCode)
            val aesKey = ScreenCryptoHelper.deriveKey(cleanCode)

            val subscribed = client.subscribeWithTopic(wildcardTopic) { topic, encryptedBytes ->
                try {
                    val decryptedBytes = ScreenCryptoHelper.decryptFrame(encryptedBytes, aesKey)
                        ?: return@subscribeWithTopic

                    if (topic.endsWith("/audio")) {
                        // Real-Time Audio PCM Chunk
                        if (!_isAudioMuted.value) {
                            viewerAudioTrack?.write(decryptedBytes, 0, decryptedBytes.size)
                        }
                    } else {
                        // Real-Time Video Frame
                        val bmp = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                        if (bmp != null) {
                            _currentFrame.value = bmp.asImageBitmap()
                            _framesReceived.value += 1
                            _statusMessage.value = "🔒 E2EE Live Video & Audio (Room #$cleanCode)"
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ScreenViewerEngine", "Error handling incoming stream payload: ${e.message}")
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

            try {
                if (viewerAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    viewerAudioTrack?.stop()
                }
                viewerAudioTrack?.release()
            } catch (_: Exception) {}
            viewerAudioTrack = null

            _isConnected.value = false
            _isConnecting.value = false
            _currentFrame.value = null
            _framesReceived.value = 0L
            _statusMessage.value = "Disconnected"
        }
    }
}
