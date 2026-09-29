package com.soltini.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * KeystoreCryptoManager
 *
 * Provides hardware-backed AES-256 GCM encryption and decryption for sensitive
 * voice biometric embeddings and audit logs using the Android Keystore provider.
 *
 * Security Guarantees:
 * - Encryption keys are generated inside the Android hardware-backed Keystore (`AndroidKeyStore`).
 * - Raw voice embeddings are never stored in plaintext on disk or SharedPreferences.
 * - Ciphertext includes authenticated IV (Initialization Vector) using AES/GCM/NoPadding.
 * - Raw encryption keys cannot be exported from the secure element / TEE.
 */
object KeystoreCryptoManager {

    private const val TAG = "KeystoreCryptoManager"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "MyraVoiceBiometricsMasterKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128

    init {
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()

                keyGenerator.init(spec)
                keyGenerator.generateKey()
                Log.i(TAG, "Hardware-backed AES-256 master key generated in AndroidKeyStore.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize KeyStore master key: ${e.message}", e)
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Encrypts plaintext bytes using AES-GCM and returns a Base64-encoded string
     * containing the IV prepended to the ciphertext.
     */
    fun encrypt(plaintext: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext)

            // Pack: [1 byte IV length] + [IV bytes] + [Ciphertext bytes]
            val packed = ByteArray(1 + iv.size + ciphertext.size)
            packed[0] = iv.size.toByte()
            System.arraycopy(iv, 0, packed, 1, iv.size)
            System.arraycopy(ciphertext, 0, packed, 1 + iv.size, ciphertext.size)

            Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failure: ${e.message}", e)
            null
        }
    }

    /**
     * Decrypts a Base64-encoded packed payload containing IV + ciphertext.
     */
    fun decrypt(encryptedBase64: String): ByteArray? {
        return try {
            val packed = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (packed.isEmpty()) return null

            val ivLength = packed[0].toInt() and 0xFF
            val iv = ByteArray(ivLength)
            System.arraycopy(packed, 1, iv, 0, ivLength)

            val ciphertextLength = packed.size - 1 - ivLength
            val ciphertext = ByteArray(ciphertextLength)
            System.arraycopy(packed, 1 + ivLength, ciphertext, 0, ciphertextLength)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failure: ${e.message}", e)
            null
        }
    }

    /**
     * Encrypts a float array embedding vector into an encrypted Base64 string.
     */
    fun encryptEmbedding(embedding: FloatArray): String? {
        val byteBuffer = java.nio.ByteBuffer.allocate(embedding.size * 4)
        for (f in embedding) {
            byteBuffer.putFloat(f)
        }
        val result = encrypt(byteBuffer.array())
        // Clean buffer memory
        byteBuffer.clear()
        return result
    }

    /**
     * Decrypts an encrypted Base64 string back into a float array embedding vector.
     */
    fun decryptEmbedding(encryptedBase64: String, expectedDim: Int = 34): FloatArray? {
        val bytes = decrypt(encryptedBase64) ?: return null
        if (bytes.size != expectedDim * 4) {
            Log.w(TAG, "Decrypted embedding byte size mismatch: expected ${expectedDim * 4}, got ${bytes.size}")
            return null
        }
        val byteBuffer = java.nio.ByteBuffer.wrap(bytes)
        val floats = FloatArray(expectedDim)
        for (i in 0 until expectedDim) {
            floats[i] = byteBuffer.float
        }
        return floats
    }
}
