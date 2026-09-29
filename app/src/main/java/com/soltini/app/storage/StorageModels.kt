package com.soltini.app.storage

import android.net.Uri
import org.json.JSONObject

/**
 * StorageItemInfo
 *
 * Encapsulates metadata for an authorized file or directory accessed via
 * Android Storage Access Framework (SAF).
 */
data class StorageItemInfo(
    val uriString: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mimeType: String,
    val lastModified: Long,
    val parentUriString: String? = null
) {
    val uri: Uri get() = Uri.parse(uriString)

    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    val formattedSize: String
        get() = when {
            isDirectory -> "Folder"
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> String.format("%.1f KB", sizeBytes / 1024.0)
            sizeBytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", sizeBytes / (1024.0 * 1024.0 * 1024.0))
        }

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("uri", uriString)
        put("name", name)
        put("is_directory", isDirectory)
        put("size_bytes", sizeBytes)
        put("formatted_size", formattedSize)
        put("mime_type", mimeType)
        put("last_modified", lastModified)
        parentUriString?.let { put("parent_uri", it) }
    }
}

/**
 * AuthorizedLocation
 *
 * Represents an explicitly user-authorized directory tree via SAF ACTION_OPEN_DOCUMENT_TREE.
 */
data class AuthorizedLocation(
    val uriString: String,
    val displayName: String,
    val addedAt: Long = System.currentTimeMillis(),
    val documentCount: Int = 0
) {
    val uri: Uri get() = Uri.parse(uriString)

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("uri", uriString)
        put("display_name", displayName)
        put("added_at", addedAt)
        put("document_count", documentCount)
    }
}

/**
 * StorageActionResult
 *
 * Standardized outcome of a file operation.
 */
data class StorageActionResult(
    val isSuccess: Boolean,
    val message: String,
    val targetUri: String? = null,
    val backupUri: String? = null,
    val details: JSONObject? = null
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("is_success", isSuccess)
        put("message", message)
        targetUri?.let { put("target_uri", it) }
        backupUri?.let { put("backup_uri", it) }
        details?.let { put("details", it) }
    }
}

/**
 * FileActionType
 *
 * Categories of storage actions, distinguishing read-only from destructive operations.
 */
enum class FileActionType {
    SEARCH,
    READ,
    CREATE,
    EDIT,
    COPY,
    MOVE,
    RENAME,
    DELETE,
    BULK_DELETE,
    OVERWRITE
}

/**
 * PendingFileConfirmation
 *
 * Holds details for any destructive file action awaiting user confirmation.
 */
data class PendingFileConfirmation(
    val id: String,
    val actionType: FileActionType,
    val targetUri: String,
    val targetName: String,
    val extraParams: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val confirmationPrompt: String
)

/**
 * DuplicateGroup
 *
 * Groups identical files identified by size and SHA-256 hash.
 */
data class DuplicateGroup(
    val fileSize: Long,
    val sha256Hash: String,
    val files: List<StorageItemInfo>
)

/**
 * UndoBackupRecord
 *
 * Tracks local backup copies created before modifying or overwriting files.
 */
data class UndoBackupRecord(
    val id: String,
    val originalUriString: String,
    val originalFileName: String,
    val backupFilePath: String,
    val timestamp: Long = System.currentTimeMillis()
)
