package com.irondiscipline.app.service

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object StreamCryptoEngine {
    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val SECRET_PEPPER = "IronDiscipline_E2EE_AES256_MediaVault_2026"
    private val secureRandom = SecureRandom()

    /**
     * Derives a 256-bit (32-byte) AES SecretKeySpec from the 6-digit roomCode.
     * Creates a new Cipher per encrypt/decrypt call so concurrent Video and Audio threads are 100% thread-safe.
     */
    fun deriveAes256Key(roomCode: String): SecretKeySpec {
        val normalized = roomCode.trim()
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(
            "$SECRET_PEPPER|KEY|$normalized".toByteArray(StandardCharsets.UTF_8)
        )
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Hashes the roomCode with SHA-256 so the 6-digit code is NEVER exposed in plaintext on the relay broker.
     */
    fun deriveEncryptedTopic(roomCode: String, channelType: String): String {
        val normalized = roomCode.trim()
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(
            "$SECRET_PEPPER|TOPIC|$normalized|$channelType".toByteArray(StandardCharsets.UTF_8)
        )
        val hexHash = hashBytes.joinToString("") { "%02x".format(it) }.take(32)
        return "irondiscipline/e2ee/$hexHash/$channelType"
    }

    /**
     * Encrypts a raw Video (JPEG) or Audio (16-bit PCM) byte array using AES-256-GCM.
     * Output format: [12-byte random IV] + [AES-256-GCM Ciphertext + 16-byte Auth Tag]
     */
    fun encryptPacket(plainBytes: ByteArray, secretKey: SecretKeySpec): ByteArray? {
        if (plainBytes.isEmpty()) return null
        return try {
            val iv = ByteArray(GCM_IV_LENGTH_BYTES)
            secureRandom.nextBytes(iv)

            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

            val cipherText = cipher.doFinal(plainBytes)
            val output = ByteArray(GCM_IV_LENGTH_BYTES + cipherText.size)
            System.arraycopy(iv, 0, output, 0, GCM_IV_LENGTH_BYTES)
            System.arraycopy(cipherText, 0, output, GCM_IV_LENGTH_BYTES, cipherText.size)
            output
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decrypts and authenticates an incoming Video or Audio packet using AES-256-GCM.
     * If any unauthorized third party tampers with the packet or uses a wrong key,
     * GCM authentication fails and returns null immediately (zero leaked audio/video).
     */
    fun decryptPacket(encryptedPayload: ByteArray, secretKey: SecretKeySpec): ByteArray? {
        if (encryptedPayload.size <= GCM_IV_LENGTH_BYTES + 16) return null
        return try {
            val iv = encryptedPayload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val cipherText = encryptedPayload.copyOfRange(GCM_IV_LENGTH_BYTES, encryptedPayload.size)

            val cipher = Cipher.getInstance(AES_TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            // AEADBadTagException or tampered packet -> reject completely
            null
        }
    }
}
