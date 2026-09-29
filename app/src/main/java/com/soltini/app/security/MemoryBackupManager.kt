package com.soltini.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.soltini.app.memory2.ConversationRecord
import com.soltini.app.memory2.Memory2Database
import com.soltini.app.memory2.MemoryCategory
import com.soltini.app.memory2.MemoryItem
import com.soltini.app.memory2.MemoryType
import com.soltini.app.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * MemoryBackupManager
 *
 * Provides AES-GCM encrypted export and import for the Memory 2.0 database.
 * Protects persistent memory from loss across device changes, reinstalls, or Google Drive backups.
 */
object MemoryBackupManager {

    private const val TAG = "MemoryBackupManager"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "myra_memory_backup_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    const val PBKDF2_ITERATIONS = 100_000
    const val PBKDF2_KEY_LENGTH = 256
    const val PBKDF2_SALT_LENGTH = 16

    private val MAGIC_HEADER_V1 = "MYRABAK1".toByteArray(Charsets.UTF_8)
    private val MAGIC_HEADER_V2 = "MYRABAK2".toByteArray(Charsets.UTF_8)

    const val MODE_KEYSTORE: Byte = 0x01
    const val MODE_PASSWORD_PBKDF2: Byte = 0x02
    const val MODE_UNENCRYPTED_INSECURE: Byte = 0x03

    // Device-independent backup derivation salt for legacy v1 restoration
    private val BACKUP_FALLBACK_SALT = byteArrayOf(
        0x4D, 0x79, 0x72, 0x61, 0x5F, 0x4D, 0x65, 0x6D,
        0x30, 0x5F, 0x41, 0x45, 0x53, 0x5F, 0x32, 0x35
    )

    data class BackupRestoreStats(
        val longTermCount: Int = 0,
        val knowledgeCount: Int = 0,
        val experienceCount: Int = 0,
        val conversationsCount: Int = 0,
        /** Facts migrated from an old pre-unification backup that still had a separate "mem0" section. 0 for current-format backups. */
        val legacyMigratedCount: Int = 0,
        val isSuccess: Boolean = true,
        val errorMessage: String? = null
    ) {
        val totalCount: Int
            get() = longTermCount + knowledgeCount + experienceCount + conversationsCount + legacyMigratedCount
    }

    /**
     * Checks if the Android KeyStore provider is operational on this device.
     */
    fun isKeyStoreAvailable(): Boolean {
        return try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER)
            ks.load(null)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Derives a 256-bit AES key from [passphrase] and [salt] using PBKDF2 with 100,000 iterations.
     */
    fun deriveKeyFromPassphrase(passphrase: String, salt: ByteArray): SecretKey {
        val keySpec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        val factory = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        } catch (_: Exception) {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        }
        val keyBytes = factory.generateSecret(keySpec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun getOrCreateSecretKeyFromKeystore(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    return entry.secretKey
                }
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val keyGenSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            AppLogger.w(TAG, "KeyStore key generation unavailable: ${e.message}")
            null
        }
    }

    /**
     * Dumps all tables from Memory2Database into a single encrypted JSON file.
     * If [passphrase] is provided, uses PBKDF2 (100,000 iterations) AES-256 encryption.
     * Otherwise attempts hardware KeyStore encryption.
     */
    fun exportEncryptedBackup(
        context: Context,
        memory2Db: Memory2Database,
        passphrase: String? = null
    ): Result<File> {
        return try {
            val backupDir = File(context.filesDir, "memory_backups").apply { if (!exists()) mkdirs() }
            val timestamp = System.currentTimeMillis()
            val backupFile = File(backupDir, "myra_memory_backup_$timestamp.myrabak")

            val success = FileOutputStream(backupFile).use { fos ->
                exportToStream(fos, memory2Db, passphrase)
            }

            if (!success) {
                backupFile.delete()
                return Result.failure(IllegalStateException("Failed writing memory backup stream"))
            }

            AppLogger.i(TAG, "Memory backup saved to: ${backupFile.absolutePath} (${backupFile.length()} bytes)")
            Result.success(backupFile)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed exporting memory backup: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Serializes the memory database, encrypts, and writes to [outputStream].
     */
    fun exportToStream(
        outputStream: OutputStream,
        memory2Db: Memory2Database,
        passphrase: String? = null
    ): Boolean {
        return try {
            val root = JSONObject().apply {
                put("version", 1)
                put("app", "MYRA")
                put("timestamp", System.currentTimeMillis())

                // 1. Memory 2.0 tables
                val m2Obj = JSONObject()

                // Long Term
                val ltArr = JSONArray()
                for (lt in memory2Db.getAllLongTermMemories()) {
                    ltArr.put(JSONObject().apply {
                        put("id", lt.id)
                        put("key", lt.key)
                        put("content", lt.content)
                        put("importance", lt.importance.toDouble())
                        put("created_at", lt.createdAt)
                        put("last_accessed_at", lt.lastAccessedAt)
                        put("access_count", lt.accessCount)
                        put("tags", lt.metadata["tags"] ?: "")
                    })
                }
                m2Obj.put("long_term", ltArr)

                // Knowledge
                val knArr = JSONArray()
                for (kn in memory2Db.getAllKnowledge()) {
                    knArr.put(JSONObject().apply {
                        put("id", kn.id)
                        put("title", kn.title)
                        put("content", kn.content)
                        put("source", kn.source)
                        put("tags", kn.tags.joinToString(","))
                        put("created_at", kn.createdAt)
                    })
                }
                m2Obj.put("knowledge", knArr)

                // Experience
                val exArr = JSONArray()
                for (ex in memory2Db.getAllExperiences()) {
                    exArr.put(JSONObject().apply {
                        put("pattern_key", ex.patternKey)
                        put("task_type", ex.taskType)
                        put("tool_sequence", JSONArray(ex.toolSequence))
                        put("success_count", ex.successCount)
                        put("fail_count", ex.failCount)
                        put("last_used_at", ex.lastUsedAt)
                    })
                }
                m2Obj.put("experience", exArr)

                // Conversations
                val convArr = JSONArray()
                for (c in memory2Db.getAllConversationEntries(limit = 1000)) {
                    convArr.put(JSONObject().apply {
                        put("id", c.id)
                        put("sender", c.sender)
                        put("text", c.text)
                        put("timestamp", c.timestamp)
                    })
                }
                m2Obj.put("conversations", convArr)

                put("memory2", m2Obj)
            }

            val plainJsonBytes = root.toString(2).toByteArray(Charsets.UTF_8)

            // Determine encryption mode and key:
            // 1. If passphrase provided -> use PBKDF2-derived AES key (MODE_PASSWORD_PBKDF2)
            // 2. If no passphrase, try KeyStore -> MODE_KEYSTORE
            // 3. If KeyStore fails and no passphrase -> MODE_UNENCRYPTED_INSECURE (fallback with warning)
            val trimmedPass = passphrase?.trim()
            val (mode, key, salt) = when {
                !trimmedPass.isNullOrEmpty() -> {
                    val genSalt = ByteArray(PBKDF2_SALT_LENGTH)
                    SecureRandom().nextBytes(genSalt)
                    val derivedKey = deriveKeyFromPassphrase(trimmedPass, genSalt)
                    Triple(MODE_PASSWORD_PBKDF2, derivedKey, genSalt)
                }
                else -> {
                    val ksKey = getOrCreateSecretKeyFromKeystore()
                    if (ksKey != null) {
                        Triple(MODE_KEYSTORE, ksKey, ByteArray(0))
                    } else {
                        AppLogger.w(TAG, "WARNING: KeyStore unavailable and no passphrase provided! Backup written unencrypted.")
                        Triple(MODE_UNENCRYPTED_INSECURE, null, ByteArray(0))
                    }
                }
            }

            if (mode == MODE_UNENCRYPTED_INSECURE || key == null) {
                // Write V2 header with unencrypted flag
                outputStream.write(MAGIC_HEADER_V2)
                outputStream.write(byteArrayOf(MODE_UNENCRYPTED_INSECURE))
                outputStream.write(byteArrayOf(0)) // salt length = 0
                outputStream.write(ByteArray(GCM_IV_LENGTH)) // blank IV
                outputStream.write(plainJsonBytes)
                outputStream.flush()
                return true
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = ByteArray(GCM_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, spec)
            val cipherText = cipher.doFinal(plainJsonBytes)

            // Format V2: [MAGIC_V2 (8B)] + [MODE (1B)] + [SALT_LEN (1B)] + [SALT (N bytes)] + [IV (12B)] + [CIPHERTEXT]
            outputStream.write(MAGIC_HEADER_V2)
            outputStream.write(byteArrayOf(mode))
            outputStream.write(byteArrayOf(salt.size.toByte()))
            if (salt.isNotEmpty()) {
                outputStream.write(salt)
            }
            outputStream.write(iv)
            outputStream.write(cipherText)
            outputStream.flush()
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error writing memory backup stream: ${e.message}")
            false
        }
    }

    /**
     * Decrypts and restores memory backup from [inputStream] into [memory2Db].
     * Supports both V2 (PBKDF2 / Keystore / Unencrypted) and legacy V1 backup formats.
     * Backups made before mem0 was folded into Memory 2.0 still have a separate
     * "mem0" section — those facts get migrated straight into long-term memory.
     */
    fun importFromStream(
        inputStream: InputStream,
        memory2Db: Memory2Database,
        passphrase: String? = null
    ): BackupRestoreStats {
        return try {
            val data = inputStream.readBytes()
            if (data.size < 16) {
                return BackupRestoreStats(isSuccess = false, errorMessage = "Backup file is corrupt or too small.")
            }

            val isV2 = data.size >= MAGIC_HEADER_V2.size &&
                    MAGIC_HEADER_V2.indices.all { data[it] == MAGIC_HEADER_V2[it] }
            val isV1 = !isV2 && data.size >= MAGIC_HEADER_V1.size &&
                    MAGIC_HEADER_V1.indices.all { data[it] == MAGIC_HEADER_V1[it] }

            if (!isV1 && !isV2) {
                return BackupRestoreStats(isSuccess = false, errorMessage = "Invalid backup file format or unrecognized header.")
            }

            val plainBytes: ByteArray = if (isV2) {
                var offset = MAGIC_HEADER_V2.size
                val mode = data[offset++]
                val saltLen = data[offset++].toInt() and 0xFF
                val salt = if (saltLen > 0) {
                    val s = data.copyOfRange(offset, offset + saltLen)
                    offset += saltLen
                    s
                } else {
                    ByteArray(0)
                }
                val iv = data.copyOfRange(offset, offset + GCM_IV_LENGTH)
                offset += GCM_IV_LENGTH
                val cipherText = data.copyOfRange(offset, data.size)

                when (mode) {
                    MODE_UNENCRYPTED_INSECURE -> {
                        // Unencrypted plain payload
                        cipherText
                    }
                    MODE_PASSWORD_PBKDF2 -> {
                        val trimmed = passphrase?.trim()
                        if (trimmed.isNullOrEmpty()) {
                            return BackupRestoreStats(
                                isSuccess = false,
                                errorMessage = "Password required! This backup was encrypted with a passphrase."
                            )
                        }
                        val key = deriveKeyFromPassphrase(trimmed, salt)
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                        try {
                            cipher.doFinal(cipherText)
                        } catch (e: Exception) {
                            return BackupRestoreStats(
                                isSuccess = false,
                                errorMessage = "Incorrect password or corrupted backup file."
                            )
                        }
                    }
                    MODE_KEYSTORE -> {
                        val ksKey = getOrCreateSecretKeyFromKeystore()
                        if (ksKey == null) {
                            return BackupRestoreStats(
                                isSuccess = false,
                                errorMessage = "Hardware KeyStore key unavailable on this device."
                            )
                        }
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, ksKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
                        cipher.doFinal(cipherText)
                    }
                    else -> {
                        return BackupRestoreStats(isSuccess = false, errorMessage = "Unsupported encryption mode: $mode")
                    }
                }
            } else {
                // Legacy V1 format
                val iv = data.copyOfRange(MAGIC_HEADER_V1.size, MAGIC_HEADER_V1.size + GCM_IV_LENGTH)
                val cipherText = data.copyOfRange(MAGIC_HEADER_V1.size + GCM_IV_LENGTH, data.size)
                val key = getOrCreateSecretKeyFromKeystore()
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                try {
                    if (key != null) {
                        val cipher = Cipher.getInstance(TRANSFORMATION)
                        cipher.init(Cipher.DECRYPT_MODE, key, spec)
                        cipher.doFinal(cipherText)
                    } else {
                        throw IllegalStateException("Keystore key unavailable")
                    }
                } catch (e: Exception) {
                    // Try passphrase if supplied, else legacy salt
                    val fallbackKey = if (!passphrase.isNullOrBlank()) {
                        deriveKeyFromPassphrase(passphrase.trim(), BACKUP_FALLBACK_SALT)
                    } else {
                        val fallbackKeyBytes = ByteArray(32) { i -> (BACKUP_FALLBACK_SALT[i % BACKUP_FALLBACK_SALT.size].toInt() xor (i * 7)).toByte() }
                        SecretKeySpec(fallbackKeyBytes, "AES")
                    }
                    val fallbackCipher = Cipher.getInstance(TRANSFORMATION)
                    fallbackCipher.init(Cipher.DECRYPT_MODE, fallbackKey, spec)
                    fallbackCipher.doFinal(cipherText)
                }
            }

            val jsonString = String(plainBytes, Charsets.UTF_8)
            val root = JSONObject(jsonString)

            var ltCount = 0
            var knCount = 0
            var exCount = 0
            var convCount = 0
            var legacyMigratedCount = 0

            // 1. Restore Memory 2.0
            val m2Obj = root.optJSONObject("memory2")
            if (m2Obj != null) {
                // Long term
                val ltArr = m2Obj.optJSONArray("long_term")
                if (ltArr != null) {
                    for (i in 0 until ltArr.length()) {
                        val o = ltArr.getJSONObject(i)
                        val item = MemoryItem(
                            id = o.getString("id"),
                            category = MemoryCategory.LONG_TERM,
                            key = o.optString("key"),
                            content = o.getString("content"),
                            importance = o.optDouble("importance", 0.5).toFloat(),
                            createdAt = o.optLong("created_at", System.currentTimeMillis()),
                            lastAccessedAt = o.optLong("last_accessed_at", System.currentTimeMillis()),
                            accessCount = o.optInt("access_count", 0),
                            metadata = mapOf("tags" to o.optString("tags"))
                        )
                        if (memory2Db.saveLongTermMemory(item)) ltCount++
                    }
                }

                // Knowledge
                val knArr = m2Obj.optJSONArray("knowledge")
                if (knArr != null) {
                    for (i in 0 until knArr.length()) {
                        val o = knArr.getJSONObject(i)
                        val tags = o.optString("tags").split(",").map { it.trim() }.filter { it.isNotBlank() }
                        val entry = Memory2Database.KnowledgeEntry(
                            id = o.getString("id"),
                            title = o.getString("title"),
                            content = o.getString("content"),
                            source = o.optString("source"),
                            tags = tags,
                            createdAt = o.optLong("created_at", System.currentTimeMillis())
                        )
                        memory2Db.saveKnowledge(entry)
                        knCount++
                    }
                }

                // Experience
                val exArr = m2Obj.optJSONArray("experience")
                if (exArr != null) {
                    for (i in 0 until exArr.length()) {
                        val o = exArr.getJSONObject(i)
                        val seqArr = o.optJSONArray("tool_sequence")
                        val seqList = mutableListOf<String>()
                        if (seqArr != null) {
                            for (j in 0 until seqArr.length()) seqList.add(seqArr.getString(j))
                        }
                        val rec = Memory2Database.ExperienceRecord(
                            patternKey = o.getString("pattern_key"),
                            taskType = o.getString("task_type"),
                            toolSequence = seqList,
                            successCount = o.optInt("success_count", 1),
                            failCount = o.optInt("fail_count", 0),
                            lastUsedAt = o.optLong("last_used_at", System.currentTimeMillis())
                        )
                        if (memory2Db.upsertExperience(rec)) exCount++
                    }
                }

                // Conversations
                val convArr = m2Obj.optJSONArray("conversations")
                if (convArr != null) {
                    for (i in 0 until convArr.length()) {
                        val o = convArr.getJSONObject(i)
                        if (memory2Db.saveConversationEntry(
                                id = o.getString("id"),
                                sender = o.getString("sender"),
                                text = o.getString("text"),
                                timestamp = o.getLong("timestamp")
                            )) convCount++
                    }
                }
            }

            // 2. Backward compatibility: old backups (pre-unification) had a
            // separate "mem0" section. Fold any such facts straight into
            // long-term memory instead of restoring a second parallel store.
            val mem0Obj = root.optJSONObject("mem0")
            if (mem0Obj != null) {
                val memArr = mem0Obj.optJSONArray("memories")
                if (memArr != null) {
                    for (i in 0 until memArr.length()) {
                        val o = memArr.getJSONObject(i)
                        val state = o.optString("state", "ACTIVE")
                        if (state != "ACTIVE") continue
                        val text = o.optString("memory")
                        if (text.isBlank()) continue
                        val category = o.optString("categories").split(",").firstOrNull()?.trim().orEmpty()
                        val item = MemoryItem(
                            id = o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                            type = MemoryType.fromString(category),
                            category = MemoryCategory.LONG_TERM,
                            key = category.ifBlank { "migrated_fact" },
                            content = text,
                            importance = o.optDouble("importance", 0.7).toFloat(),
                            createdAt = o.optLong("created_at", System.currentTimeMillis()),
                            updatedAt = o.optLong("updated_at", System.currentTimeMillis()),
                            userConfirmed = true,
                            metadata = mapOf("tags" to "migrated_from_mem0")
                        )
                        if (memory2Db.saveLongTermMemory(item)) legacyMigratedCount++
                    }
                }
            }

            AppLogger.i(TAG, "Memory restore complete! LT: $ltCount, KN: $knCount, EX: $exCount, Conv: $convCount, Legacy: $legacyMigratedCount")
            BackupRestoreStats(
                longTermCount = ltCount,
                knowledgeCount = knCount,
                experienceCount = exCount,
                conversationsCount = convCount,
                legacyMigratedCount = legacyMigratedCount,
                isSuccess = true
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed decrypting and restoring memory backup: ${e.message}")
            BackupRestoreStats(
                isSuccess = false,
                errorMessage = e.message ?: "Failed to restore backup"
            )
        }
    }
}
