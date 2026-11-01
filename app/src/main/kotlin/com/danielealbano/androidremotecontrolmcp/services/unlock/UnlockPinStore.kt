package com.danielealbano.androidremotecontrolmcp.services.unlock

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the device unlock PIN encrypted with an Android Keystore AES-GCM key.
 *
 * The PIN can only be written through the adb-only configuration receiver (gated on DUMP); there is no
 * MCP tool that reads it. A single failed unlock sets a lockout flag that only adb can clear, so a wrong or
 * changed PIN never burns through the device's own attempt limit.
 */
class UnlockPinStore(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasPin(): Boolean = prefs.contains(KEY_PIN)

    fun setPin(pin: String) {
        require(PIN_REGEX.matches(pin)) { "PIN must be 4-32 letters or digits" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = pin.toByteArray(Charsets.UTF_8)
        val ct = cipher.doFinal(bytes)
        bytes.fill(0)
        val blob =
            ByteBuffer
                .allocate(1 + cipher.iv.size + ct.size)
                .put(cipher.iv.size.toByte())
                .put(cipher.iv)
                .put(ct)
                .array()
        prefs
            .edit()
            .putString(KEY_PIN, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putBoolean(KEY_LOCKOUT, false)
            .apply()
    }

    fun clearPin() {
        prefs
            .edit()
            .remove(KEY_PIN)
            .putBoolean(KEY_LOCKOUT, false)
            .apply()
    }

    /** Returns the PIN as a char array; callers must wipe it with [CharArray.fill] after use. */
    fun readPin(): CharArray? {
        val encoded = prefs.getString(KEY_PIN, null) ?: return null
        return try {
            val buf = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
            val iv = ByteArray(buf.get().toInt()).also { buf.get(it) }
            val ct = ByteArray(buf.remaining()).also { buf.get(it) }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(ct)
            val chars = Charsets.UTF_8.decode(ByteBuffer.wrap(plain))
            plain.fill(0)
            CharArray(chars.remaining()).also {
                chars.get(it)
                wipe(chars)
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Stored unlock PIN could not be decrypted", e)
            null
        }
    }

    fun isLockedOut(): Boolean = prefs.getBoolean(KEY_LOCKOUT, false)

    fun setLockedOut() {
        prefs.edit().putBoolean(KEY_LOCKOUT, true).apply()
    }

    fun resetLockout() {
        prefs.edit().putBoolean(KEY_LOCKOUT, false).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                // Deliberately NOT unlockedDeviceRequired: the key must work while the device is locked.
                .build(),
        )
        return gen.generateKey()
    }

    private fun wipe(buf: CharBuffer) {
        if (buf.hasArray()) buf.array().fill('\u0000')
    }

    companion object {
        private const val TAG = "MCP:UnlockPinStore"
        private const val PREFS = "unlock_pin_store"
        private const val KEY_PIN = "pin_blob"
        private const val KEY_LOCKOUT = "lockout"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "unlock_pin_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val KEY_BITS = 256
        private val PIN_REGEX = Regex("^[A-Za-z0-9]{4,32}$")
    }
}
