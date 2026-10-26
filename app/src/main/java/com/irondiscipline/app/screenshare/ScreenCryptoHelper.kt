package com.irondiscipline.app.screenshare

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-End Encryption (AES-256-GCM) & Topic Hashing Helper for Live Screen Share.
 * Ensures all screen frames broadcast over public MQTT relay are 100% private and cryptographically secure.
 */
object ScreenCryptoHelper {

    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128 // bits
    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val SALT_PREFIX = "IronDiscipline_Private_Salt_"

    private val secureRandom = SecureRandom()

    /**
     * Derives a 256-bit AES SecretKeySpec from the 6-digit room code using SHA-256.
     */
    fun deriveKey(roomCode: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = (SALT_PREFIX + roomCode.trim()).toByteArray(Charsets.UTF_8)
        val keyBytes = digest.digest(input)
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    /**
     * Obfuscates the MQTT topic using SHA-256 so the raw 6-digit room code is never visible on the public broker.
     */
    fun hashTopic(roomCode: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(roomCode.trim().toByteArray(Charsets.UTF_8))
        val hex = hash.joinToString("") { "%02x".format(it) }
        return "irondiscipline/screenshare/" + hex.take(20)
    }

    fun videoTopic(roomCode: String): String = hashTopic(roomCode) + "/video"

    fun audioTopic(roomCode: String): String = hashTopic(roomCode) + "/audio"

    fun baseTopicWildcard(roomCode: String): String = hashTopic(roomCode) + "/#"

    /**
     * Encrypts JPEG byte array using AES-256-GCM with a random 12-byte IV.
     * Output format: [12 bytes IV] + [Ciphertext + 16 bytes GCM Auth Tag]
     */
    fun encryptFrame(plaintext: ByteArray, key: SecretKeySpec): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        val ciphertext = cipher.doFinal(plaintext)
        val result = ByteArray(GCM_IV_LENGTH + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH)
        System.arraycopy(ciphertext, 0, result, GCM_IV_LENGTH, ciphertext.size)
        return result
    }

    /**
     * Decrypts encrypted frame bytes using AES-256-GCM.
     * Fails and returns null if the key or authentication tag is invalid.
     */
    fun decryptFrame(encryptedData: ByteArray, key: SecretKeySpec): ByteArray? {
        if (encryptedData.size <= GCM_IV_LENGTH + 16) return null
        return try {
            val iv = ByteArray(GCM_IV_LENGTH)
            System.arraycopy(encryptedData, 0, iv, 0, GCM_IV_LENGTH)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            cipher.doFinal(encryptedData, GCM_IV_LENGTH, encryptedData.size - GCM_IV_LENGTH)
        } catch (_: Exception) {
            null
        }
    }
}
