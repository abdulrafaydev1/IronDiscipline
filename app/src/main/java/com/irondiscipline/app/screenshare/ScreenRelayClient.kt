package com.irondiscipline.app.screenshare

import android.util.Log
import com.irondiscipline.app.service.StreamCryptoEngine
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * Lightweight Zero-Dependency MQTT v3.1.1 Client implemented with standard Kotlin TCP Sockets.
 * Connects to public MQTT broker (broker.emqx.io:1883) to relay screen frames between Sender and Viewer.
 */
class ScreenRelayClient(
    private val host: String = "broker.emqx.io",
    private val port: Int = 1883
) {

    @Volatile
    var isConnected: Boolean = false
        private set

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null

    private var pingThread: Thread? = null
    private var readerThread: Thread? = null

    private var frameListener: ((ByteArray) -> Unit)? = null
    private var messageListener: ((topic: String, payload: ByteArray) -> Unit)? = null
    private var subscribedTopic: String? = null

    /**
     * Connects to the MQTT broker and executes standard MQTT 3.1.1 CONNECT handshake.
     */
    @Synchronized
    fun connect(timeoutMs: Int = 9000): Boolean {
        if (isConnected && socket?.isConnected == true && socket?.isClosed == false) {
            return true
        }

        disconnect()

        return try {
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = 0 // Blocking read on background threads

            val inStream = BufferedInputStream(s.getInputStream(), 64 * 1024)
            val outStream = BufferedOutputStream(s.getOutputStream(), 64 * 1024)

            // Send MQTT 3.1.1 CONNECT packet
            val clientId = "iron_" + UUID.randomUUID().toString().replace("-", "").take(10)

            val vHeadAndPayload = ByteArrayOutputStream().apply {
                // Protocol Name: "MQTT"
                write(0x00)
                write(0x04)
                write('M'.code)
                write('Q'.code)
                write('T'.code)
                write('T'.code)
                // Protocol Level: 4 (MQTT 3.1.1)
                write(0x04)
                // Connect Flags: Clean Session (0x02)
                write(0x02)
                // Keep Alive: 60 seconds (0x003C)
                write(0x00)
                write(0x3C)
                // Client ID
                val cidBytes = clientId.toByteArray(Charsets.UTF_8)
                write((cidBytes.size shr 8) and 0xFF)
                write(cidBytes.size and 0xFF)
                write(cidBytes)
            }.toByteArray()

            val packet = ByteArrayOutputStream().apply {
                write(0x10) // CONNECT packet type
                encodeRemainingLength(vHeadAndPayload.size, this)
                write(vHeadAndPayload)
            }.toByteArray()

            outStream.write(packet)
            outStream.flush()

            // Read CONNACK response (0x20, 0x02, sessionPresent, returnCode)
            val header = inStream.read()
            if (header == -1) {
                s.close()
                return false
            }
            if ((header and 0xF0) != 0x20) {
                s.close()
                return false
            }
            val remLen = readRemainingLength(inStream)
            val connackBody = ByteArray(remLen)
            readFully(inStream, connackBody)
            if (connackBody.size >= 2 && connackBody[1] != 0.toByte()) {
                s.close()
                return false
            }

            this.socket = s
            this.inputStream = inStream
            this.outputStream = outStream
            this.isConnected = true

            startPingThread()
            true
        } catch (e: Exception) {
            Log.e("ScreenRelayClient", "Failed to connect to $host:$port: ${e.message}")
            disconnect()
            false
        }
    }

    /**
     * Publishes a raw byte array (JPEG frame) to the specified MQTT topic using QoS 0.
     */
    @Synchronized
    fun publish(topic: String, payload: ByteArray): Boolean {
        if (!isConnected || socket?.isClosed == true) return false
        val out = outputStream ?: return false

        return try {
            val topicBytes = topic.toByteArray(Charsets.UTF_8)
            val remainingLength = 2 + topicBytes.size + payload.size
            val packet = ByteArrayOutputStream(remainingLength + 5).apply {
                write(0x30) // PUBLISH QoS 0
                encodeRemainingLength(remainingLength, this)
                write((topicBytes.size shr 8) and 0xFF)
                write(topicBytes.size and 0xFF)
                write(topicBytes)
                write(payload)
            }.toByteArray()

            out.write(packet)
            out.flush()
            true
        } catch (e: Exception) {
            Log.e("ScreenRelayClient", "Error publishing to $topic: ${e.message}")
            disconnect()
            false
        }
    }

    /**
     * Subscribes to the specified MQTT topic (QoS 0) and delivers incoming payloads to onFrame.
     */
    @Synchronized
    fun subscribe(topic: String, onFrame: (ByteArray) -> Unit): Boolean {
        return subscribeWithTopic(topic) { _, payload -> onFrame(payload) }
    }

    /**
     * Subscribes to the specified MQTT topic (QoS 0) and delivers both topic name and payload to onMessage.
     */
    @Synchronized
    fun subscribeWithTopic(topic: String, onMessage: (topic: String, payload: ByteArray) -> Unit): Boolean {
        return subscribeTopics(listOf(topic), onMessage)
    }

    /**
     * Subscribes to multiple MQTT topics in a single SUBSCRIBE packet (QoS 0).
     */
    @Synchronized
    fun subscribeTopics(topics: List<String>, onMessage: (topic: String, payload: ByteArray) -> Unit): Boolean {
        if (!isConnected || socket?.isClosed == true || topics.isEmpty()) return false
        val out = outputStream ?: return false

        this.messageListener = onMessage
        this.subscribedTopic = topics.joinToString(",")

        return try {
            val packetId = (1..65535).random()
            val payloadBaos = ByteArrayOutputStream()
            for (t in topics) {
                val tBytes = t.toByteArray(Charsets.UTF_8)
                payloadBaos.write((tBytes.size shr 8) and 0xFF)
                payloadBaos.write(tBytes.size and 0xFF)
                payloadBaos.write(tBytes)
                payloadBaos.write(0x00) // Requested QoS 0
            }
            val payloadBytes = payloadBaos.toByteArray()
            val remainingLength = 2 + payloadBytes.size

            val packet = ByteArrayOutputStream(remainingLength + 5).apply {
                write(0x82) // SUBSCRIBE
                encodeRemainingLength(remainingLength, this)
                write((packetId shr 8) and 0xFF)
                write(packetId and 0xFF)
                write(payloadBytes)
            }.toByteArray()

            out.write(packet)
            out.flush()

            startReaderThread()
            true
        } catch (e: Exception) {
            Log.e("ScreenRelayClient", "Error subscribing to topics: ${e.message}")
            disconnect()
            false
        }
    }

    /**
     * Subscribes to the cloaked E2EE video and audio topics for the given roomCode,
     * decrypting every incoming packet using AES-256-GCM (StreamCryptoEngine).
     */
    @Synchronized
    fun subscribeEncryptedRoom(
        roomCode: String,
        onDecryptedVideo: (ByteArray) -> Unit,
        onDecryptedAudio: (ByteArray) -> Unit
    ): Boolean {
        val sessionAesKey = StreamCryptoEngine.deriveAes256Key(roomCode)
        val videoTopic = StreamCryptoEngine.deriveEncryptedTopic(roomCode, "v")
        val audioTopic = StreamCryptoEngine.deriveEncryptedTopic(roomCode, "a")

        return subscribeTopics(listOf(videoTopic, audioTopic)) { topic, incomingPayload ->
            if (topic == videoTopic || topic.endsWith("/v")) {
                val decryptedJpeg = StreamCryptoEngine.decryptPacket(incomingPayload, sessionAesKey)
                    ?: return@subscribeTopics
                onDecryptedVideo(decryptedJpeg)
            } else if (topic == audioTopic || topic.endsWith("/a")) {
                val decryptedPcm = StreamCryptoEngine.decryptPacket(incomingPayload, sessionAesKey)
                    ?: return@subscribeTopics
                onDecryptedAudio(decryptedPcm)
            }
        }
    }

    private fun startReaderThread() {
        if (readerThread?.isAlive == true) return
        readerThread = Thread({
            val input = inputStream ?: return@Thread
            try {
                while (isConnected && socket?.isClosed == false) {
                    val headerByte = input.read()
                    if (headerByte == -1) break
                    val remainingLength = readRemainingLength(input)
                    val packetType = headerByte and 0xF0

                    if (packetType == 0x30) { // PUBLISH packet
                        if (remainingLength < 2) continue
                        val tLenMsb = input.read()
                        val tLenLsb = input.read()
                        if (tLenMsb == -1 || tLenLsb == -1) break
                        val topicLen = (tLenMsb shl 8) or tLenLsb
                        val topicBytes = ByteArray(topicLen)
                        readFully(input, topicBytes)

                        val qos = (headerByte and 0x06) shr 1
                        if (qos > 0) {
                            input.read()
                            input.read()
                        }
                        val topicStr = String(topicBytes, Charsets.UTF_8)
                        val payloadLen = remainingLength - 2 - topicLen - (if (qos > 0) 2 else 0)
                        if (payloadLen > 0) {
                            val payload = ByteArray(payloadLen)
                            readFully(input, payload)
                            messageListener?.invoke(topicStr, payload)
                            frameListener?.invoke(payload)
                        }
                    } else if (packetType == 0x90) { // SUBACK
                        skipFully(input, remainingLength)
                    } else if (packetType == 0xD0) { // PINGRESP
                        // Heartbeat confirmed
                    } else {
                        skipFully(input, remainingLength)
                    }
                }
            } catch (e: Exception) {
                if (isConnected) {
                    Log.d("ScreenRelayClient", "Reader thread stopped: ${e.message}")
                }
            } finally {
                disconnect()
            }
        }, "MqttReaderThread").apply {
            isDaemon = true
            start()
        }
    }

    private fun startPingThread() {
        pingThread?.interrupt()
        pingThread = Thread({
            try {
                while (isConnected && socket?.isClosed == false) {
                    Thread.sleep(20_000L)
                    if (!isConnected || socket?.isClosed == true) break
                    sendPing()
                }
            } catch (_: InterruptedException) {}
        }, "MqttPingThread").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    private fun sendPing() {
        try {
            outputStream?.write(byteArrayOf(0xC0.toByte(), 0x00))
            outputStream?.flush()
        } catch (_: Exception) {}
    }

    @Synchronized
    fun disconnect() {
        isConnected = false
        try {
            outputStream?.write(byteArrayOf(0xE0.toByte(), 0x00))
            outputStream?.flush()
        } catch (_: Exception) {}

        try { socket?.close() } catch (_: Exception) {}
        pingThread?.interrupt()
        readerThread?.interrupt()

        socket = null
        inputStream = null
        outputStream = null
        frameListener = null
        messageListener = null
    }

    companion object {
        fun encodeRemainingLength(length: Int, out: OutputStream) {
            var x = length
            do {
                var encodedByte = x % 128
                x /= 128
                if (x > 0) {
                    encodedByte = encodedByte or 128
                }
                out.write(encodedByte)
            } while (x > 0)
        }

        fun readRemainingLength(input: InputStream): Int {
            var multiplier = 1
            var value = 0
            var digit: Int
            do {
                digit = input.read()
                if (digit == -1) throw EOFException("Socket closed")
                value += (digit and 127) * multiplier
                multiplier *= 128
                if (multiplier > 128 * 128 * 128) throw IOException("Malformed Remaining Length")
            } while ((digit and 128) != 0)
            return value
        }

        fun readFully(input: InputStream, buffer: ByteArray) {
            var offset = 0
            while (offset < buffer.size) {
                val count = input.read(buffer, offset, buffer.size - offset)
                if (count == -1) throw EOFException("Unexpected EOF reading MQTT frame")
                offset += count
            }
        }

        fun skipFully(input: InputStream, length: Int) {
            var remaining = length.toLong()
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) {
                    if (input.read() == -1) throw EOFException("Unexpected EOF skipping MQTT frame")
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }
    }
}
