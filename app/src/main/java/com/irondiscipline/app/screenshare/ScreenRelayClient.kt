package com.irondiscipline.app.screenshare

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.irondiscipline.app.service.StreamCryptoEngine
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec

class ScreenRelayClient(private val roomCode: String) {

    private val isRunning = AtomicBoolean(false)
    private val isViewerAudioMuted = AtomicBoolean(false)
    private val sessionKey: SecretKeySpec = StreamCryptoEngine.deriveAes256Key(roomCode)
    private val videoTopic: String = StreamCryptoEngine.deriveEncryptedTopic(roomCode, "v")
    private val audioTopic: String = StreamCryptoEngine.deriveEncryptedTopic(roomCode, "a")

    // Lock-free Latest-Frame-Only holder (eliminates video lag 100%)
    private val latestEncryptedVideoFrame = AtomicReference<ByteArray?>(null)
    // Bounded audio queue (never lags behind real time)
    private val outgoingAudioQueue = LinkedBlockingQueue<ByteArray>(12)
    private val incomingAudioPlayQueue = LinkedBlockingQueue<ByteArray>(20)

    private var videoSocket: Socket? = null
    private var audioSocket: Socket? = null
    private var viewerAudioTrack: AudioTrack? = null

    companion object {
        private const val BROKER_HOST = "broker.emqx.io"
        private const val BROKER_PORT = 1883
        const val AUDIO_SAMPLE_RATE = 12000 // 12kHz 16-bit Mono PCM = crystal clear & ultra-low bandwidth
    }

    fun setAudioMuted(muted: Boolean) {
        isViewerAudioMuted.set(muted)
    }

    /**
     * SENDER: Queues the latest encrypted JPEG frame. If an older frame hasn't been sent yet,
     * it is immediately replaced by this newer frame -> ZERO LAG!
     */
    fun enqueueVideoFrame(rawJpegBytes: ByteArray) {
        if (!isRunning.get()) return
        val encrypted = StreamCryptoEngine.encryptPacket(rawJpegBytes, sessionKey) ?: return
        latestEncryptedVideoFrame.set(encrypted)
    }

    /**
     * SENDER: Queues an encrypted 16-bit PCM audio chunk for immediate transmission.
     */
    fun enqueueAudioChunk(rawPcmBytes: ByteArray) {
        if (!isRunning.get()) return
        val encrypted = StreamCryptoEngine.encryptPacket(rawPcmBytes, sessionKey) ?: return
        if (!outgoingAudioQueue.offer(encrypted)) {
            outgoingAudioQueue.poll() // Drop oldest chunk if full to keep real-time sync
            outgoingAudioQueue.offer(encrypted)
        }
    }

    /**
     * Starts SENDER mode with 2 dedicated parallel TCP sockets (1 for Video, 1 for Audio).
     */
    fun startSender() {
        if (isRunning.getAndSet(true)) return

        // 1. Dedicated Video Sender Thread
        Thread {
            while (isRunning.get()) {
                var sock: Socket? = null
                try {
                    sock = createOptimizedSocket()
                    videoSocket = sock
                    val out = BufferedOutputStream(sock.getOutputStream(), 32768)
                    val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 4096))
                    sendMqttConnect(out, "ID_VS_${UUID.randomUUID().toString().take(8)}")
                    readMqttPacket(inp) // Read CONNACK

                    var lastPing = System.currentTimeMillis()
                    while (isRunning.get() && !sock.isClosed) {
                        val frame = latestEncryptedVideoFrame.getAndSet(null)
                        if (frame != null) {
                            sendMqttPublish(out, videoTopic, frame)
                        } else {
                            Thread.sleep(12)
                        }
                        if (System.currentTimeMillis() - lastPing > 15000L) {
                            sendMqttPing(out)
                            lastPing = System.currentTimeMillis()
                        }
                    }
                } catch (_: Exception) {
                    Thread.sleep(600)
                } finally {
                    try { sock?.close() } catch (_: Exception) {}
                }
            }
        }.apply { isDaemon = true; name = "SenderVideoSocketThread"; start() }

        // 2. Dedicated Audio Sender Thread (Never blocked by video frames!)
        Thread {
            while (isRunning.get()) {
                var sock: Socket? = null
                try {
                    sock = createOptimizedSocket()
                    audioSocket = sock
                    val out = BufferedOutputStream(sock.getOutputStream(), 16384)
                    val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 4096))
                    sendMqttConnect(out, "ID_AS_${UUID.randomUUID().toString().take(8)}")
                    readMqttPacket(inp) // Read CONNACK

                    var lastPing = System.currentTimeMillis()
                    while (isRunning.get() && !sock.isClosed) {
                        val audioChunk = outgoingAudioQueue.poll(100, TimeUnit.MILLISECONDS)
                        if (audioChunk != null) {
                            sendMqttPublish(out, audioTopic, audioChunk)
                        }
                        if (System.currentTimeMillis() - lastPing > 15000L) {
                            sendMqttPing(out)
                            lastPing = System.currentTimeMillis()
                        }
                    }
                } catch (_: Exception) {
                    Thread.sleep(600)
                } finally {
                    try { sock?.close() } catch (_: Exception) {}
                }
            }
        }.apply { isDaemon = true; name = "SenderAudioSocketThread"; start() }
    }

    /**
     * Starts VIEWER mode: Receives & decrypts both Video frames and Audio chunks with 100% intact `readFully()`.
     */
    fun startViewer(
        onFrameDecoded: (Bitmap) -> Unit,
        onStatusChanged: (String) -> Unit
    ) {
        if (isRunning.getAndSet(true)) return

        // 1. Start Dedicated AudioTrack Speaker Playback Thread
        startViewerAudioPlaybackThread()

        // 2. Start Dedicated Video Receiver Thread
        Thread {
            while (isRunning.get()) {
                var sock: Socket? = null
                try {
                    onStatusChanged("Connecting to encrypted stream...")
                    sock = createOptimizedSocket()
                    videoSocket = sock
                    val out = BufferedOutputStream(sock.getOutputStream(), 4096)
                    val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 65536))

                    sendMqttConnect(out, "ID_VR_${UUID.randomUUID().toString().take(8)}")
                    readMqttPacket(inp) // CONNACK
                    sendMqttSubscribe(out, videoTopic, packetId = 1)
                    onStatusChanged("🟢 LIVE: Connected (AES-256 Encrypted)")

                    var lastPing = System.currentTimeMillis()
                    while (isRunning.get() && !sock.isClosed) {
                        val packet = readMqttPacket(inp) ?: break
                        if ((packet.first and 0xF0) == 0x30) {
                            val payload = extractPublishPayload(packet.second)
                            if (payload != null) {
                                val decryptedJpeg = StreamCryptoEngine.decryptPacket(payload, sessionKey)
                                if (decryptedJpeg != null) {
                                    val bmp = BitmapFactory.decodeByteArray(decryptedJpeg, 0, decryptedJpeg.size)
                                    if (bmp != null) {
                                        onFrameDecoded(bmp)
                                    }
                                }
                            }
                        }
                        if (System.currentTimeMillis() - lastPing > 15000L) {
                            sendMqttPing(out)
                            lastPing = System.currentTimeMillis()
                        }
                    }
                } catch (_: Exception) {
                    if (isRunning.get()) {
                        onStatusChanged("Reconnecting...")
                        Thread.sleep(600)
                    }
                } finally {
                    try { sock?.close() } catch (_: Exception) {}
                }
            }
        }.apply { isDaemon = true; name = "ViewerVideoRecvThread"; start() }

        // 3. Start Dedicated Audio Receiver Thread
        Thread {
            while (isRunning.get()) {
                var sock: Socket? = null
                try {
                    sock = createOptimizedSocket()
                    audioSocket = sock
                    val out = BufferedOutputStream(sock.getOutputStream(), 4096)
                    val inp = DataInputStream(BufferedInputStream(sock.getInputStream(), 32768))

                    sendMqttConnect(out, "ID_AR_${UUID.randomUUID().toString().take(8)}")
                    readMqttPacket(inp) // CONNACK
                    sendMqttSubscribe(out, audioTopic, packetId = 2)

                    var lastPing = System.currentTimeMillis()
                    while (isRunning.get() && !sock.isClosed) {
                        val packet = readMqttPacket(inp) ?: break
                        if ((packet.first and 0xF0) == 0x30) {
                            val payload = extractPublishPayload(packet.second)
                            if (payload != null && !isViewerAudioMuted.get()) {
                                val decryptedPcm = StreamCryptoEngine.decryptPacket(payload, sessionKey)
                                if (decryptedPcm != null && decryptedPcm.isNotEmpty()) {
                                    if (!incomingAudioPlayQueue.offer(decryptedPcm)) {
                                        incomingAudioPlayQueue.poll()
                                        incomingAudioPlayQueue.offer(decryptedPcm)
                                    }
                                }
                            }
                        }
                        if (System.currentTimeMillis() - lastPing > 15000L) {
                            sendMqttPing(out)
                            lastPing = System.currentTimeMillis()
                        }
                    }
                } catch (_: Exception) {
                    Thread.sleep(600)
                } finally {
                    try { sock?.close() } catch (_: Exception) {}
                }
            }
        }.apply { isDaemon = true; name = "ViewerAudioRecvThread"; start() }
    }

    private fun startViewerAudioPlaybackThread() {
        Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(2400)

                track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MEDIA)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AUDIO_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                viewerAudioTrack = track
                track.setVolume(1.0f)
                track.play()

                while (isRunning.get()) {
                    val pcmBytes = incomingAudioPlayQueue.poll(200, TimeUnit.MILLISECONDS)
                    if (pcmBytes != null && !isViewerAudioMuted.get()) {
                        track.write(pcmBytes, 0, pcmBytes.size)
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    track?.stop()
                    track?.release()
                } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; name = "ViewerAudioSpeakerThread"; start() }
    }

    fun stop() {
        isRunning.set(false)
        latestEncryptedVideoFrame.set(null)
        outgoingAudioQueue.clear()
        incomingAudioPlayQueue.clear()
        try { videoSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        try {
            viewerAudioTrack?.stop()
            viewerAudioTrack?.release()
        } catch (_: Exception) {}
        viewerAudioTrack = null
    }

    private fun createOptimizedSocket(): Socket {
        return Socket().apply {
            tcpNoDelay = true
            keepAlive = true
            sendBufferSize = 65536
            receiveBufferSize = 65536
            soTimeout = 25000
            connect(InetSocketAddress(BROKER_HOST, BROKER_PORT), 8000)
        }
    }

    // --- EXACT RFC MQTT v3.1.1 PACKET FRAMING WITH `readFully` ---

    private fun writeMqttVarInt(out: ByteArrayOutputStream, length: Int) {
        var x = length
        do {
            var encodedByte = x % 128
            x /= 128
            if (x > 0) encodedByte = encodedByte or 128
            out.write(encodedByte)
        } while (x > 0)
    }

    private fun readMqttPacket(inp: DataInputStream): Pair<Int, ByteArray>? {
        val header = inp.readUnsignedByte()
        var multiplier = 1
        var value = 0
        var encodedByte: Int
        do {
            encodedByte = inp.readUnsignedByte()
            value += (encodedByte and 127) * multiplier
            multiplier *= 128
            if (multiplier > 128 * 128 * 128) return null
        } while ((encodedByte and 128) != 0)

        val payload = ByteArray(value)
        if (value > 0) {
            // CRITICAL FIX: readFully guarantees the ENTIRE frame is read across TCP packets!
            inp.readFully(payload)
        }
        return Pair(header, payload)
    }

    private fun extractPublishPayload(body: ByteArray): ByteArray? {
        if (body.size < 2) return null
        val topicLen = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        val payloadOffset = 2 + topicLen
        if (body.size <= payloadOffset) return null
        return body.copyOfRange(payloadOffset, body.size)
    }

    private fun sendMqttConnect(out: BufferedOutputStream, clientId: String) {
        val clientBytes = clientId.toByteArray(StandardCharsets.UTF_8)
        val body = ByteArrayOutputStream()
        // Protocol Name "MQTT" (0x00, 0x04, 'M', 'Q', 'T', 'T'), Level 4, Flags 0x02 (Clean Session), KeepAlive 30s
        body.write(byteArrayOf(0x00, 0x04, 'M'.code.toByte(), 'Q'.code.toByte(), 'T'.code.toByte(), 'T'.code.toByte(), 0x04, 0x02, 0x00, 0x1E))
        body.write((clientBytes.size shr 8) and 0xFF)
        body.write(clientBytes.size and 0xFF)
        body.write(clientBytes)

        val bodyBytes = body.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(0x10)
        writeMqttVarInt(packet, bodyBytes.size)
        packet.write(bodyBytes)
        out.write(packet.toByteArray())
        out.flush()
    }

    private fun sendMqttSubscribe(out: BufferedOutputStream, topic: String, packetId: Int) {
        val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)
        val body = ByteArrayOutputStream()
        body.write((packetId shr 8) and 0xFF)
        body.write(packetId and 0xFF)
        body.write((topicBytes.size shr 8) and 0xFF)
        body.write(topicBytes.size and 0xFF)
        body.write(topicBytes)
        body.write(0x00) // QoS 0

        val bodyBytes = body.toByteArray()
        val packet = ByteArrayOutputStream()
        packet.write(0x82)
        writeMqttVarInt(packet, bodyBytes.size)
        packet.write(bodyBytes)
        out.write(packet.toByteArray())
        out.flush()
    }

    private fun sendMqttPublish(out: BufferedOutputStream, topic: String, payload: ByteArray) {
        val topicBytes = topic.toByteArray(StandardCharsets.UTF_8)
        val remainingLen = 2 + topicBytes.size + payload.size
        val header = ByteArrayOutputStream()
        header.write(0x30) // PUBLISH QoS 0
        writeMqttVarInt(header, remainingLen)
        header.write((topicBytes.size shr 8) and 0xFF)
        header.write(topicBytes.size and 0xFF)
        header.write(topicBytes)

        out.write(header.toByteArray())
        out.write(payload)
        out.flush()
    }

    private fun sendMqttPing(out: BufferedOutputStream) {
        out.write(byteArrayOf(0xC0.toByte(), 0x00))
        out.flush()
    }
}
