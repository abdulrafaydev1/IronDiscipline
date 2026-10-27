package com.irondiscipline.app.screenshare

import com.irondiscipline.app.service.StreamCryptoEngine
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Encryption (AES-256-GCM) & Topic Hashing Helper for Live Screen Share.
 * Delegates directly to StreamCryptoEngine for unified cryptographic security.
 */
object ScreenCryptoHelper {

    fun deriveKey(roomCode: String): SecretKeySpec =
        StreamCryptoEngine.deriveAes256Key(roomCode)

    fun hashTopic(roomCode: String): String =
        StreamCryptoEngine.deriveEncryptedTopic(roomCode, "base")

    fun videoTopic(roomCode: String): String =
        StreamCryptoEngine.deriveEncryptedTopic(roomCode, "v")

    fun audioTopic(roomCode: String): String =
        StreamCryptoEngine.deriveEncryptedTopic(roomCode, "a")

    fun baseTopicWildcard(roomCode: String): String =
        StreamCryptoEngine.deriveEncryptedTopic(roomCode, "v")

    fun encryptFrame(plaintext: ByteArray, key: SecretKeySpec): ByteArray =
        StreamCryptoEngine.encryptPacket(plaintext, key) ?: ByteArray(0)

    fun decryptFrame(encryptedData: ByteArray, key: SecretKeySpec): ByteArray? =
        StreamCryptoEngine.decryptPacket(encryptedData, key)
}
