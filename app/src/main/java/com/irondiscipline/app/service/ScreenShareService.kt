package com.irondiscipline.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.irondiscipline.app.MainActivity
import com.irondiscipline.app.R
import com.irondiscipline.app.screenshare.ScreenRelayClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground Service for Real-Time MediaProjection Screen Capture and Frame Broadcasting.
 * Uses Pure Kotlin MQTT Relay Client to broadcast 360x640 JPEG frames to broker.emqx.io.
 */
class ScreenShareService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var relayClient: ScreenRelayClient? = null
    private var currentRoomCode: String = ""
    private var isStreamingActive = false
    private val isCapturingFrame = AtomicBoolean(false)

    private val captureRunnable = object : Runnable {
        override fun run() {
            if (!isStreamingActive) return
            captureAndPublishFrame()
            backgroundHandler?.postDelayed(this, 220L) // ~4.5 FPS low-latency stream
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

            if (resultCode != Activity.RESULT_OK || resultData == null || roomCode.isBlank()) {
                stopScreenCaptureService()
                return START_NOT_STICKY
            }

            this.currentRoomCode = roomCode
            startForegroundWithNotification(roomCode)
            startScreenCapture(resultCode, resultData, roomCode)
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

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("IronDiscipline Live Screen Share")
            .setContentText("Broadcasting live screen • Room Code: #$roomCode")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startScreenCapture(resultCode: Int, resultData: Intent, roomCode: String) {
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

        // 1. Initialize dedicated background thread for frame encoding & network IO
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

        // 3. Setup ImageReader and VirtualDisplay
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

        // 4. Connect MQTT client on background thread
        handler.post {
            val client = ScreenRelayClient()
            val connected = client.connect()
            if (connected) {
                this.relayClient = client
                this.isStreamingActive = true
                ScreenShareState.updateState(true, roomCode, "Streaming Live")
                handler.post(captureRunnable)
            } else {
                Log.e("ScreenShareService", "Failed to connect to relay broker for room #$roomCode")
                stopScreenCaptureService()
            }
        }
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

                client.publish("irondiscipline/screenshare/$currentRoomCode", jpegBytes)
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
        isStreamingActive = false
        backgroundHandler?.removeCallbacksAndMessages(null)

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
                description = "Shows notification while screen is actively sharing"
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
