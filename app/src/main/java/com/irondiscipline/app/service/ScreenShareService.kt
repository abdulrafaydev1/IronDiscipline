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
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.irondiscipline.app.MainActivity
import com.irondiscipline.app.R
import com.irondiscipline.app.screenshare.ScreenRelayClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service for Real-Time MediaProjection Screen Capture & Live Audio Streaming.
 * Streams encrypted 320x568 JPEG video (~10 FPS lock-free drop-frame buffer)
 * and 12,000 Hz 16-bit PCM audio (Internal, Mic, or Both) over dual parallel MQTT sockets
 * with AES-256-GCM End-to-End Encryption.
 * Runs 100% persistently in the background until the user explicitly stops sharing.
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
    private var audioMode: String = AUDIO_MODE_BOTH
    private val localPhoneName: String by lazy { getLocalPhoneName() }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var isStreamingActive = false
    private val isCapturingFrame = AtomicBoolean(false)
    private var lastCapturedJpegBytes: ByteArray? = null
    private var lastSentTimeMs: Long = 0L

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isStreamingActive) return
            captureAndPublishFrame()
            backgroundHandler?.postDelayed(this, 100L) // Smooth ~10 FPS screen capture
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return if (isStreamingActive) START_STICKY else START_NOT_STICKY

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

            acquireWakeAndWifiLocks()
            startForegroundWithNotification(roomCode)
            startScreenAndAudioCapture(resultCode, resultData, roomCode)
            return START_STICKY
        }

        return if (isStreamingActive) START_STICKY else START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // DO NOT stop sharing when the user swipes the app away from Recents!
        // Sharing must continue running until the user explicitly clicks "Unshare / Stop Sharing".
        super.onTaskRemoved(rootIntent)
    }

    private fun acquireWakeAndWifiLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IronDiscipline:SenderCpuLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wifiLock == null) {
                @Suppress("DEPRECATION")
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockMode, "IronDiscipline:SenderWifiLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            Log.e("ScreenShareService", "Failed to acquire wake/wifi locks: ${e.message}")
        }
    }

    private fun releaseWakeAndWifiLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        wifiLock = null
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

        val stopIntent = Intent(this, ScreenShareService::class.java).apply {
            action = ACTION_STOP_SHARE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modeLabel = when (audioMode) {
            AUDIO_MODE_INTERNAL -> "Internal Audio"
            AUDIO_MODE_MIC -> "Mic Voice"
            AUDIO_MODE_NONE -> "Silent (No Sound)"
            else -> "Internal + Mic"
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("IronDiscipline Live Screen & Audio Share")
            .setContentText("🔒 E2EE Active • $modeLabel • Room #$roomCode")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_launcher_foreground, "Stop Sharing", stopPendingIntent)
            .build()

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (hasAudioPermission && (audioMode == AUDIO_MODE_MIC || audioMode == AUDIO_MODE_BOTH)) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
            }
            startForeground(NOTIFICATION_ID, notification, fgsType)
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

        // 1. Initialize dedicated background thread for video encoding & frame handling
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

        // 3. Setup ImageReader and VirtualDisplay for video (fast, crisp 320 x 568 9:16 portrait screen stream)
        val metrics = resources.displayMetrics
        val densityDpi = metrics.densityDpi
        val width = 320
        val height = 568

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

        // 4. Start dual-channel ScreenRelayClient and capture loops
        handler.post {
            val client = ScreenRelayClient(roomCode)
            this.relayClient = client
            this.isStreamingActive = true
            client.startSender()

            ScreenShareSessionManager.setSenderAudioMode(audioMode)
            ScreenShareSessionManager.setSenderActive(true, roomCode, "Streaming Live • Audio: $audioMode")
            ScreenShareState.updateState(true, roomCode, "Streaming Live • Audio: $audioMode")

            // Start video capture loop (~10 FPS lock-free Latest-Frame-Only buffer)
            handler.post(captureRunnable)

            // Start real-time audio capture loop
            startAudioCaptureLoop(projection)
        }
    }

    private fun startAudioCaptureLoop(projection: MediaProjection) {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val sampleRate = ScreenRelayClient.AUDIO_SAMPLE_RATE // 12,000 Hz
        val minBufSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2400)

        // 1. Setup Internal Audio capture via AudioPlaybackCaptureConfiguration (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
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

        // 2. Setup Microphone Audio capture with fallback source if MIC is restricted
        if (hasAudioPermission) {
            try {
                var micRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize * 2
                )

                // Fallback to VOICE_RECOGNITION if MIC is restricted during MediaProjection
                if (micRecord.state != AudioRecord.STATE_INITIALIZED) {
                    try { micRecord.release() } catch (_: Exception) {}
                    micRecord = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        minBufSize * 2
                    )
                }

                if (micRecord.state == AudioRecord.STATE_INITIALIZED) {
                    micRecord.startRecording()
                    this.micAudioRecord = micRecord
                }
            } catch (e: Exception) {
                Log.e("ScreenShareService", "Failed to initialize Mic AudioRecord: ${e.message}")
            }
        }

        // 3. Dedicated Audio Capture Thread (100ms PCM chunks = 1200 shorts = 2400 bytes at 12kHz)
        val thread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val shortsPerChunk = 1200 // 100ms at 12,000 Hz
            val internalBuf = ShortArray(shortsPerChunk)
            val micBuf = ShortArray(shortsPerChunk)
            val outBytes = ByteArray(shortsPerChunk * 2)

            try {
                while (isStreamingActive) {
                    val currentMode = ScreenShareSessionManager.senderAudioMode.value

                    if (currentMode == AUDIO_MODE_NONE) {
                        Thread.sleep(80L)
                        continue
                    }

                    var internalRead = 0
                    var micRead = 0

                    val shouldReadInternal = currentMode == AUDIO_MODE_INTERNAL || currentMode == AUDIO_MODE_BOTH
                    val shouldReadMic = currentMode == AUDIO_MODE_MIC || currentMode == AUDIO_MODE_BOTH

                    val intRec = internalAudioRecord
                    if (shouldReadInternal && intRec != null && intRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        internalRead = intRec.read(internalBuf, 0, shortsPerChunk)
                    }
                    val mRec = micAudioRecord
                    if (shouldReadMic && mRec != null && mRec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        micRead = mRec.read(micBuf, 0, shortsPerChunk)
                    }

                    val samplesCount = maxOf(internalRead, micRead)
                    if (samplesCount > 0) {
                        for (i in 0 until samplesCount) {
                            val sInt = if (shouldReadInternal && i < internalRead) internalBuf[i].toInt() else 0
                            val sMic = if (shouldReadMic && i < micRead) micBuf[i].toInt() else 0
                            // Boost slightly and clamp to 16-bit signed range
                            val mixed = (sInt + sMic).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                            // Little-Endian 16-bit PCM byte packing
                            outBytes[i * 2] = (mixed.toInt() and 0xFF).toByte()
                            outBytes[i * 2 + 1] = ((mixed.toInt() shr 8) and 0xFF).toByte()
                        }
                        val validBytes = if (samplesCount == shortsPerChunk) outBytes else outBytes.copyOf(samplesCount * 2)
                        relayClient?.enqueueAudioChunk(validBytes)
                    } else {
                        Thread.sleep(20L)
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

        if (!isCapturingFrame.compareAndSet(false, true)) {
            return // Skip if previous frame is still encoding
        }

        try {
            val image = try { reader.acquireLatestImage() } catch (_: Exception) { null }
            if (image == null) {
                // Heartbeat: Sender screen is locked/off or static - keep viewer connection alive
                val now = System.currentTimeMillis()
                val cached = lastCapturedJpegBytes
                if (cached != null && now - lastSentTimeMs >= 400L) {
                    client.enqueueVideoFrame(cached)
                    lastSentTimeMs = now
                    ScreenShareSessionManager.incrementSenderFrames()
                    ScreenShareState.incrementFrames()
                }
                isCapturingFrame.set(false)
                return
            }

            try {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val width = 320
                val height = 568
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
                frameBitmap.compress(Bitmap.CompressFormat.JPEG, 38, baos)
                val rawJpegBytes = baos.toByteArray()

                val packedBytes = packVideoFrameWithDeviceName(localPhoneName, rawJpegBytes)

                lastCapturedJpegBytes = packedBytes
                lastSentTimeMs = System.currentTimeMillis()

                // Non-blocking lock-free enqueue to dedicated Video TCP socket thread
                client.enqueueVideoFrame(packedBytes)
                ScreenShareSessionManager.incrementSenderFrames()
                ScreenShareState.incrementFrames()

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
        if (!isStreamingActive && relayClient == null) return
        isStreamingActive = false
        releaseWakeAndWifiLocks()
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

        try { relayClient?.stop() } catch (_: Exception) {}
        relayClient = null

        ScreenShareSessionManager.setSenderActive(false, status = "Stopped")
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
        const val AUDIO_MODE_NONE = "NONE"

        fun getLocalPhoneName(): String {
            val manufacturer = Build.MANUFACTURER?.trim()?.replaceFirstChar { it.uppercase() } ?: ""
            val model = Build.MODEL?.trim() ?: "Android Phone"
            val full = if (model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
            return full.take(36).ifBlank { "Android Phone" }
        }

        /**
         * Packs [4-byte Magic 'I','D','M',1] + [1-byte Name Length] + [UTF-8 Device Name Bytes] + [Raw JPEG Bytes]
         * before encrypting the entire packet with AES-256-GCM!
         */
        fun packVideoFrameWithDeviceName(deviceName: String, jpegBytes: ByteArray): ByteArray {
            val nameBytes = deviceName.toByteArray(StandardCharsets.UTF_8)
            val safeNameLen = nameBytes.size.coerceAtMost(60)
            val out = ByteArray(5 + safeNameLen + jpegBytes.size)
            out[0] = 'I'.code.toByte()
            out[1] = 'D'.code.toByte()
            out[2] = 'M'.code.toByte()
            out[3] = 1.toByte()
            out[4] = safeNameLen.toByte()
            System.arraycopy(nameBytes, 0, out, 5, safeNameLen)
            System.arraycopy(jpegBytes, 0, out, 5 + safeNameLen, jpegBytes.size)
            return out
        }

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
