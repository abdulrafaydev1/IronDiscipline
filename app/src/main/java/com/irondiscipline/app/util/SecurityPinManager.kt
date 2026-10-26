package com.irondiscipline.app.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

sealed class PinVerificationResult {
    object Success : PinVerificationResult()
    data class Failed(val remainingAttempts: Int) : PinVerificationResult()
    data class LockedOut(val remainingSeconds: Long) : PinVerificationResult()
}

object SecurityPinManager {
    private const val PREFS_NAME = "iron_discipline_prefs"
    private const val KEY_PIN_HASH = "sec_pin_hash_v2"
    private const val KEY_PIN_SALT = "sec_pin_salt_v2"
    private const val KEY_FAILED_ATTEMPTS = "sec_failed_attempts"
    private const val KEY_LOCKOUT_UNTIL_MS = "sec_lockout_until_ms"
    private const val KEY_PIN_VERIFIED_UNTIL_MS = "pin_verified_until_ms"
    private const val KEY_IS_UNINSTALL_AUTHORIZED = "is_uninstall_authorized"

    private const val DEFAULT_PIN = "1234"
    private const val MAX_FAILED_ATTEMPTS = 5
    private const val LOCKOUT_DURATION_MS = 30_000L // 30-second exponential cooldown

    @Synchronized
    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Initializes PIN security if not yet initialized.
     * Migrates legacy plaintext PIN if present, or initializes default "1234".
     */
    @Synchronized
    fun ensureInitialized(context: Context) {
        val prefs = getPrefs(context)
        if (!prefs.contains(KEY_PIN_HASH)) {
            val legacyPin = prefs.getString("uninstall_pin", DEFAULT_PIN) ?: DEFAULT_PIN
            val salt = generateSalt()
            val hash = hashPin(legacyPin, salt)
            prefs.edit()
                .putString(KEY_PIN_SALT, salt)
                .putString(KEY_PIN_HASH, hash)
                .remove("uninstall_pin") // Erase plaintext PIN
                .apply()
        }
    }

    private fun generateSalt(): String {
        val random = SecureRandom()
        val saltBytes = ByteArray(16)
        random.nextBytes(saltBytes)
        return Base64.encodeToString(saltBytes, Base64.NO_WRAP)
    }

    private fun hashPin(pin: String, salt: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val combined = "$salt:${pin.trim()}".toByteArray(Charsets.UTF_8)
        val digest = md.digest(combined)
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /**
     * Verifies the entered 4-digit PIN against the persistent salted SHA-256 hash.
     * Enforces rate limiting against brute-force attempts.
     */
    @Synchronized
    fun verifyPin(context: Context, enteredPin: String): PinVerificationResult {
        ensureInitialized(context)
        val prefs = getPrefs(context)
        val now = System.currentTimeMillis()

        // 1. Check Lockout State
        val lockoutUntil = prefs.getLong(KEY_LOCKOUT_UNTIL_MS, 0L)
        if (now < lockoutUntil) {
            val remainingSec = ((lockoutUntil - now) / 1000L).coerceAtLeast(1L)
            return PinVerificationResult.LockedOut(remainingSec)
        }

        // 2. Compute and verify hash
        val salt = prefs.getString(KEY_PIN_SALT, null) ?: generateSalt()
        val storedHash = prefs.getString(KEY_PIN_HASH, null)
        val computedHash = hashPin(enteredPin, salt)

        val isMatch = storedHash != null && MessageDigest.isEqual(
            Base64.decode(storedHash, Base64.NO_WRAP),
            Base64.decode(computedHash, Base64.NO_WRAP)
        )

        return if (isMatch) {
            // Reset failure count on success
            prefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, 0)
                .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
                .apply()
            PinVerificationResult.Success
        } else {
            val currentFails = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
            val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, currentFails)
            if (currentFails >= MAX_FAILED_ATTEMPTS) {
                val newLockoutUntil = now + LOCKOUT_DURATION_MS
                editor.putLong(KEY_LOCKOUT_UNTIL_MS, newLockoutUntil)
                editor.apply()
                PinVerificationResult.LockedOut(LOCKOUT_DURATION_MS / 1000L)
            } else {
                editor.apply()
                PinVerificationResult.Failed((MAX_FAILED_ATTEMPTS - currentFails).coerceAtLeast(0))
            }
        }
    }

    /**
     * Updates the Security PIN after verifying the current PIN.
     */
    @Synchronized
    fun updatePin(context: Context, currentPin: String, newPin: String): Boolean {
        if (verifyPin(context, currentPin) !is PinVerificationResult.Success) {
            return false
        }
        val cleanNew = newPin.trim()
        if (cleanNew.length < 4) return false

        val newSalt = generateSalt()
        val newHash = hashPin(cleanNew, newSalt)

        getPrefs(context).edit()
            .putString(KEY_PIN_SALT, newSalt)
            .putString(KEY_PIN_HASH, newHash)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL_MS, 0L)
            .apply()
        return true
    }

    /**
     * Authorizes a temporary, time-bounded removal session (default: 60 seconds) strictly after
     * successful PIN verification.
     */
    @Synchronized
    fun authorizeRemovalSession(context: Context, durationMs: Long = 60_000L) {
        val until = System.currentTimeMillis() + durationMs
        getPrefs(context).edit()
            .putBoolean(KEY_IS_UNINSTALL_AUTHORIZED, true)
            .putLong(KEY_PIN_VERIFIED_UNTIL_MS, until)
            .apply()
    }

    /**
     * Immediately resets any authorized uninstall session.
     * Must be called on application launch, reboot, or deactivation to prevent stale bypasses.
     */
    @Synchronized
    fun resetAuthorizationSession(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_UNINSTALL_AUTHORIZED, false)
            .putLong(KEY_PIN_VERIFIED_UNTIL_MS, 0L)
            .apply()
    }

    /**
     * Checks if an authorized uninstall session is currently valid and unexpired.
     * If expired, it automatically resets the session to locked.
     */
    @Synchronized
    fun isUninstallSessionActive(context: Context): Boolean {
        val prefs = getPrefs(context)
        val isAuthorized = prefs.getBoolean(KEY_IS_UNINSTALL_AUTHORIZED, false)
        val until = prefs.getLong(KEY_PIN_VERIFIED_UNTIL_MS, 0L)
        val now = System.currentTimeMillis()

        return if (isAuthorized && now < until) {
            true
        } else {
            if (isAuthorized) {
                // Auto-expire stale authorization
                resetAuthorizationSession(context)
            }
            false
        }
    }
}
