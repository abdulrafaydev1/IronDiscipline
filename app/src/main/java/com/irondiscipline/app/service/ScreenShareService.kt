package com.irondiscipline.app.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.irondiscipline.app.MainActivity
import com.irondiscipline.app.R
import com.irondiscipline.app.screenshare.ScreenCryptoHelper
import com.irondiscipline.app.screenshare.ScreenRelayClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec

/**
 * Foreground Service for Real-Time MediaProjection Screen Capture & Live Audio Streaming.
 * Streams encrypted 360x640 JPEG video and 16kHz 16-bit PCM audio (Internal, Mic, or Both)
 * over MQTT broker with AES-256-GCM End-to-End Encryption.
 */
class ScreenShareService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var internalAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    private var audioThread: Thread? = null

    private var relayClient: ScreenRelayClient? = null
    private var currentRoomCode: String = ""
    private var hashedVideoTopic: String = ""
    private var hashedAudioTopic: String = ""
    private var aesKey: SecretKeySpec? = null
    private var audioMode: String = AUDIO_MODE_BOTH
    private var sessionStartTimeMs: Long = 0L

    private var isStreamingActive = false
    private val isCapturingFrame = AtomicBoolean(false)

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isStreamingActive) return

            // Auto-stop after 15 minutes of testing
            if (System.currentTimeMillis() - sessionStartTimeMs > MAX_SHARE_DURATION_MS) {
                Log.i("ScreenShareService", "15-minute screen share session expired. Auto-stopping.")
                stopScreenCaptureService()
                return
            }

            captureAndPublishFrame()
            backgroundHandler?.postDelayed(this, 220L) // ~4.5 FPS low-latency video stream
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val action = intent.action
        if (action == ACTION_STOP_SHARE) {
            stopScreenCaptureService()
            return START_NOT_STICKY
        }

        if (action == ACTION_START_SHARE) {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            val roomCode = intent.getStringExtra(EXTRA_ROOM_CODE) ?: ""
            val mode = intent.getStringExtra(EXTRA_AUDIO_MODE) ?: AUDIO_MODE_BOTH

            if (resultCode != Activity.RESULT_OK || resultData == null || roomCode.isBlank()) {
                stopScreenCaptureService()
                return START_NOT_STICKY
            }

            this.currentRoomCode = roomCode
            this.audioMode = mode
            this.hashedVideoTopic = ScreenCryptoHelper.videoTopic(roomCode)
            this.hashedAudioTopic = ScreenCryptoHelper.audioTopic(roomCode)
            this.aesKey = ScreenCryptoHelper.deriveKey(roomCode)
            this.sessionStartTimeMs = System.currentTimeMillis()

            startForegroundWithNotification(roomCode)
            startScreenAndAudioCapture(resultCode, resultData, roomCode)
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(roomCode: String) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = when (audioMode) {
            AUDIO_MODE_INTERNAL -> "Internal Audio"
            AUDIO_MODE_MIC -> "Mic Voice"
            else -> "Internal + Mic"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("IronDiscipline Live Screen & Audio Share")
            .setContentText("🔒 E2EE Active • $modeLabel • Room #$roomCode")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgsType = if (hasAudioPermission) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScreenAndAudioCapture(resultCode: Int, resultData: Intent, roomCode: String) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager == null) {
            stopScreenCaptureService()
            return
        }

        val projection = mpManager.getMediaProjection(resultCode, resultData)
        if (projection == null) {
            stopScreenCaptureService()
            return
        }
        this.mediaProjection = projection

        // 1. Initialize dedicated background thread for video encoding & network IO
        val thread = HandlerThread("IronScreenShareCapture").apply { start() }
        this.backgroundThread = thread
        val handler = Handler(thread.looper)
        this.backgroundHandler = handler

        // 2. Mandatory Android 14 (API 34+) rule: register callback BEFORE createVirtualDisplay!
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                super.onStop()
                stopScreenCaptureService()
            }
        }, handler)

        // 3. Setup ImageReader and VirtualDisplay for video
        val metrics = resources.displayMetrics
        val densityDpi = metrics.densityDpi
        val width = 360
        val height = 640

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        this.imageReader = reader

        this.virtualDisplay = projection.createVirtualDisplay(
            "IronScreenShare",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )

        // 4. Connect MQTT client on background thread and start both video + audio loops
        handler.post {
            val client = ScreenRelayClient()
            val connected = client.connect()
            if (connected) {
                this.relayClient = client
                this.isStreamingActive = true
                ScreenShareState.updateState(true, roomCode, "Streaming Live • Audio: $audioMode")

                // Start video capture loop
                handler.post(captureRunnable)

                // Start real-time audio capture loop
                startAudioCaptureLoop(projection)
            } else {
                Log.e("ScreenShareService", "Failed to connect to relay broker for room #$roomCode")
                stopScreenCaptureService()
            }
        }
    }

    private fun startAudioCaptureLoop(projection: MediaProjection) {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasAudioPermission) {
            Log.w("ScreenShareService", "RECORD_AUDIO permission not granted; audio streaming skipped.")
            return
        }

        val minBufSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        // Setup Internal Audio capture via AudioPlaybackCaptureConfiguration (Android 10+)
        if (audioMode == AUDIO_MODE_INTERNAL || audioMode == AUDIO_MODE_BOTH) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()

                    val audioFormat = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16000)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()

                    val record = AudioRecord.Builder()
                        .setAudioFormat(audioFormat)
                        .setBufferSizeInBytes(minBufSize * 2)
                        .setAudioPlaybackCaptureConfig(captureConfig)
                        .build()

                    if (record.state == AudioRecord.STATE_INITIALIZED) {
                        record.startRecording()
                        this.internalAudioRecord = record
                    }
                } catch (e: Exception) {
                    Log.e("ScreenShareService", "Failed to initialize AudioPlaybackCapture: ${e.message}")
                }
            }
        }

        // Setup Microphone Audio capture
        if (audioMode == AUDIO_MODE_MIC || audioMode == AUDIO_MODE_BOTH) {
            try {
                val micRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    16000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize * 2
                )
                if (micRecord.state == AudioRecord.STATE_INITIALIZED) {
                    micRecord.startRecording()
                    this.micAudioRecord = micRecord
                }
            } catch (e: Exception) {
                Log.e("ScreenShareService", "Failed to initialize Mic AudioRecord: ${e.message}")
            }
        }

        // Dedicated Audio Streaming Thread (50ms PCM chunks = 800 shorts = 1600 bytes)
        val thread = Thread({
            val internalShorts = ShortArray(800)
            val micShorts = ShortArray(800)
            val outBytes = ByteArray(1600)

            try {
                while (isStreamingActive) {
                    var hasInternal = false
                    var hasMic = false

                    val intRec = internalAudioRecord
                    if (intRec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val readInt = intRec.read(internalShorts, 0, 800)
                        if (readInt > 0) hasInternal = true
                    }

                    val mRec = micAudioRecord
                    if (mRec?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val readMic = mRec.read(micShorts, 0, 800)
                        if (readMic > 0) hasMic = true
                    }

                    if (!hasInternal && !hasMic) {
                        Thread.sleep(20L)
                        continue
                    }

                    // Real-Time 16-bit PCM Audio Mixing
                    for (i in 0 until 800) {
                        val sA = if (hasInternal) internalShorts[i].toInt() else 0
                        val sB = if (hasMic) micShorts[i].toInt() else 0
                        val mixed = (sA + sB).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        outBytes[i * 2] = (mixed and 0xFF).toByte()
                        outBytes[i * 2 + 1] = ((mixed shr 8) and 0xFF).toByte()
                    }

                    // AES-256-GCM Encrypt and publish to audio topic
                    val key = aesKey
                    val client = relayClient
                    if (key != null && client != null && client.isConnected && hashedAudioTopic.isNotEmpty()) {
                        val encryptedAudio = ScreenCryptoHelper.encryptFrame(outBytes, key)
                        client.publish(hashedAudioTopic, encryptedAudio)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    if (internalAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        internalAudioRecord?.stop()
                    }
                    internalAudioRecord?.release()
                } catch (_: Exception) {}
                internalAudioRecord = null

                try {
                    if (micAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        micAudioRecord?.stop()
                    }
                    micAudioRecord?.release()
                } catch (_: Exception) {}
                micAudioRecord = null
            }
        }, "IronScreenAudioStreamThread").apply {
            isDaemon = true
            start()
        }
        this.audioThread = thread
    }

    private fun captureAndPublishFrame() {
        if (!isStreamingActive) return
        val reader = imageReader ?: return
        val client = relayClient ?: return
        if (!client.isConnected) return

        if (!isCapturingFrame.compareAndSet(false, true)) {
            return // Skip if previous frame is still encoding
        }

        try {
            val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            if (image == null) {
                isCapturingFrame.set(false)
                return
            }

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val width = 360
                val height = 640
                val rowPadding = rowStride - pixelStride * width

                val fullBitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride,
                    height,
                    Bitmap.Config.ARGB_8888
                )
                fullBitmap.copyPixelsFromBuffer(buffer)

                val frameBitmap = if (rowPadding == 0) {
                    fullBitmap
                } else {
                    Bitmap.createBitmap(fullBitmap, 0, 0, width, height)
                }

                val baos = ByteArrayOutputStream()
                frameBitmap.compress(Bitmap.CompressFormat.JPEG, 45, baos)
                val jpegBytes = baos.toByteArray()

                // AES-256-GCM End-to-End Encryption
                val key = aesKey
                if (key != null && hashedVideoTopic.isNotEmpty()) {
                    val encryptedPayload = ScreenCryptoHelper.encryptFrame(jpegBytes, key)
                    client.publish(hashedVideoTopic, encryptedPayload)
                    ScreenShareState.incrementFrames()
                }

                if (frameBitmap !== fullBitmap) {
                    frameBitmap.recycle()
                }
                fullBitmap.recycle()
            } finally {
                image.close()
            }
        } catch (e: Exception) {
            Log.e("ScreenShareService", "Error during frame capture: ${e.message}")
        } finally {
            isCapturingFrame.set(false)
        }
    }

    private fun stopScreenCaptureService() {
        isStreamingActive = false
        backgroundHandler?.removeCallbacksAndMessages(null)

        audioThread?.interrupt()
        audioThread = null

        try {
            if (internalAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                internalAudioRecord?.stop()
            }
            internalAudioRecord?.release()
        } catch (_: Exception) {}
        internalAudioRecord = null

        try {
            if (micAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                micAudioRecord?.stop()
            }
            micAudioRecord?.release()
        } catch (_: Exception) {}
        micAudioRecord = null

        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null

        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null

        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaProjection = null

        try { backgroundThread?.quitSafely() } catch (_: Exception) {}
        backgroundThread = null
        backgroundHandler = null

        relayClient?.disconnect()
        relayClient = null

        ScreenShareState.updateState(false, null, "Stopped")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopScreenCaptureService()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Share Broadcast",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows notification while screen and audio are actively sharing"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "screen_share_channel"
        const val NOTIFICATION_ID = 4099

        const val ACTION_START_SHARE = "com.irondiscipline.app.ACTION_START_SHARE"
        const val ACTION_STOP_SHARE = "com.irondiscipline.app.ACTION_STOP_SHARE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_ROOM_CODE = "extra_room_code"
        const val EXTRA_AUDIO_MODE = "extra_audio_mode"

        const val AUDIO_MODE_INTERNAL = "INTERNAL"
        const val AUDIO_MODE_MIC = "MIC"
        const val AUDIO_MODE_BOTH = "BOTH"

        const val MAX_SHARE_DURATION_MS = 15 * 60 * 1000L // 15-minute safety timeout

        fun stopSharing(context: Context) {
            val intent = Intent(context, ScreenShareService::class.java).apply {
                action = ACTION_STOP_SHARE
            }
            context.startService(intent)
        }
    }
}

/**
 * Observable global state for Screen Share session.
 */
object ScreenShareState {
    private val _isSharing = MutableStateFlow(false)
    val isSharing = _isSharing.asStateFlow()

    private val _activeRoomCode = MutableStateFlow<String?>(null)
    val activeRoomCode = _activeRoomCode.asStateFlow()

    private val _framesSent = MutableStateFlow(0L)
    val framesSent = _framesSent.asStateFlow()

    private val _statusText = MutableStateFlow("Idle")
    val statusText = _statusText.asStateFlow()

    fun updateState(sharing: Boolean, roomCode: String? = null, status: String = "Idle") {
        _isSharing.value = sharing
        _activeRoomCode.value = roomCode
        _statusText.value = status
        if (!sharing) {
            _framesSent.value = 0L
        }
    }

    fun incrementFrames() {
        _framesSent.value += 1
    }
}
