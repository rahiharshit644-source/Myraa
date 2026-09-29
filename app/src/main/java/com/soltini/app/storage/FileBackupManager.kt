package com.soltini.app.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/**
 * FileBackupManager
 *
 * Provides safe pre-edit snapshot backups and undo capability before modifying
 * or overwriting files in user-authorized storage.
 */
class FileBackupManager(private val context: Context) {

    companion object {
        private const val TAG = "FileBackupManager"
        private const val BACKUP_DIR_NAME = "myra_file_backups"
        private const val MAX_BACKUPS = 50
    }

    private val backupDir: File by lazy {
        File(context.filesDir, BACKUP_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }

    private val backupHistory = mutableListOf<UndoBackupRecord>()

    /**
     * Creates a local snapshot copy of an authorized file before editing.
     */
    fun createBackup(originalUri: Uri, originalFileName: String): UndoBackupRecord? {
        return try {
            val resolver = context.contentResolver
            val backupId = "bak_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}"
            val safeName = originalFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val backupFile = File(backupDir, "${backupId}_$safeName")

            resolver.openInputStream(originalUri)?.use { input ->
                FileOutputStream(backupFile).use { output ->
                    input.copyTo(output)
                }
            }

            val record = UndoBackupRecord(
                id = backupId,
                originalUriString = originalUri.toString(),
                originalFileName = originalFileName,
                backupFilePath = backupFile.absolutePath,
                timestamp = System.currentTimeMillis()
            )

            synchronized(backupHistory) {
                backupHistory.add(0, record)
                if (backupHistory.size > MAX_BACKUPS) {
                    val removed = backupHistory.removeLast()
                    File(removed.backupFilePath).delete()
                }
            }

            Log.i(TAG, "Created backup for '$originalFileName' at: ${backupFile.absolutePath}")
            record
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup for $originalUri: ${e.message}")
            null
        }
    }

    /**
     * Restores a backup onto the original authorized file (Undo).
     */
    fun restoreBackup(backupId: String): StorageActionResult {
        val record = synchronized(backupHistory) {
            backupHistory.find { it.id == backupId }
        } ?: return StorageActionResult(false, "Backup record '$backupId' not found.")

        val backupFile = File(record.backupFilePath)
        if (!backupFile.exists()) {
            return StorageActionResult(false, "Backup file on disk no longer exists.")
        }

        return try {
            val targetUri = Uri.parse(record.originalUriString)
            context.contentResolver.openOutputStream(targetUri, "wt")?.use { output ->
                FileInputStream(backupFile).use { input ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "Restored backup ${record.id} to ${record.originalFileName}")
            StorageActionResult(
                isSuccess = true,
                message = "Successfully restored '${record.originalFileName}' from backup.",
                targetUri = record.originalUriString
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore backup: ${e.message}")
            StorageActionResult(false, "Restore failed: ${e.message}")
        }
    }

    fun getAllBackups(): List<UndoBackupRecord> {
        return synchronized(backupHistory) { backupHistory.toList() }
    }

    fun getLatestBackupForUri(uriString: String): UndoBackupRecord? {
        return synchronized(backupHistory) {
            backupHistory.firstOrNull { it.originalUriString == uriString }
        }
    }

    fun clearAllBackups(): Int {
        return synchronized(backupHistory) {
            val count = backupHistory.size
            for (record in backupHistory) {
                try {
                    File(record.backupFilePath).delete()
                } catch (_: Exception) {}
            }
            backupHistory.clear()
            count
        }
    }
}
