package com.soltini.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.security.MessageDigest

/**
 * SafStorageManager
 *
 * Implements Android Storage Access Framework (SAF) integration for MYRA.
 *
 * Strict Security Rule:
 * MYRA ONLY accesses storage locations explicitly authorized by the user via
 * ACTION_OPEN_DOCUMENT_TREE or ACTION_OPEN_DOCUMENT with persistable URI permissions.
 * It NEVER silently accesses the root filesystem or unauthorized private folders.
 */
class SafStorageManager(
    private val context: Context,
    val backupManager: FileBackupManager = FileBackupManager(context)
) {

    companion object {
        private const val TAG = "SafStorageManager"
        private const val PREFS_NAME = "myra_authorized_storage_prefs"
        private const val KEY_AUTHORIZED_TREES = "authorized_tree_uris"

        @Volatile
        private var instance: SafStorageManager? = null

        fun getInstance(context: Context): SafStorageManager =
            instance ?: synchronized(this) {
                instance ?: SafStorageManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Persists an authorized tree URI granted by the user via ACTION_OPEN_DOCUMENT_TREE.
     */
    fun addAuthorizedTree(treeUri: Uri, label: String? = null): Boolean {
        return try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(treeUri, flags)

            val docFile = DocumentFile.fromTreeUri(context, treeUri)
            val displayName = label?.ifBlank { null }
                ?: docFile?.name?.ifBlank { null }
                ?: treeUri.lastPathSegment
                ?: "Authorized Folder"

            val current = getAuthorizedLocations().toMutableList()
            val existing = current.indexOfFirst { it.uriString == treeUri.toString() }
            if (existing >= 0) {
                current[existing] = current[existing].copy(displayName = displayName)
            } else {
                current.add(AuthorizedLocation(treeUri.toString(), displayName))
            }
            saveAuthorizedLocations(current)
            Log.i(TAG, "Successfully added authorized SAF location: $displayName ($treeUri)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take persistable URI permission for $treeUri: ${e.message}")
            false
        }
    }

    /**
     * Removes an authorized tree URI and releases the persistable permission.
     */
    fun removeAuthorizedTree(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.releasePersistableUriPermission(uri, flags)
            } catch (_: Exception) { }

            val current = getAuthorizedLocations().toMutableList()
            current.removeAll { it.uriString == uriString }
            saveAuthorizedLocations(current)
            Log.i(TAG, "Removed authorized SAF location: $uriString")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error removing authorized tree: ${e.message}")
            false
        }
    }

    /**
     * Retrieves all persisted authorized locations.
     */
    fun getAuthorizedLocations(): List<AuthorizedLocation> {
        val raw = prefs.getString(KEY_AUTHORIZED_TREES, "[]") ?: "[]"
        val list = mutableListOf<AuthorizedLocation>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val uriString = obj.optString("uri")
                val name = obj.optString("display_name", "Authorized Folder")
                val addedAt = obj.optLong("added_at", System.currentTimeMillis())
                if (uriString.isNotBlank()) {
                    list.add(AuthorizedLocation(uriString, name, addedAt))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse authorized locations: ${e.message}")
        }
        return list
    }

    private fun saveAuthorizedLocations(locations: List<AuthorizedLocation>) {
        val arr = JSONArray()
        for (loc in locations) {
            arr.put(JSONObject().apply {
                put("uri", loc.uriString)
                put("display_name", loc.displayName)
                put("added_at", loc.addedAt)
            })
        }
        prefs.edit().putString(KEY_AUTHORIZED_TREES, arr.toString()).apply()
    }

    /**
     * Checks if a given URI belongs to an explicitly authorized location.
     */
    fun isUriAuthorized(uri: Uri): Boolean {
        val uriStr = uri.toString()
        val authorized = getAuthorizedLocations()
        return authorized.any {
            uriStr.startsWith(it.uriString) || uriStr == it.uriString
        }
    }

    /**
     * Lists files and folders within an authorized tree URI.
     */
    suspend fun listDirectory(
        treeUri: Uri,
        recursive: Boolean = false,
        maxDepth: Int = 2
    ): List<StorageItemInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<StorageItemInfo>()
        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return@withContext emptyList()

        fun traverse(doc: DocumentFile, depth: Int, parentUri: String?) {
            val children = try {
                doc.listFiles()
            } catch (e: Exception) {
                emptyArray<DocumentFile>()
            }
            for (child in children) {
                val info = StorageItemInfo(
                    uriString = child.uri.toString(),
                    name = child.name ?: "Unnamed",
                    isDirectory = child.isDirectory,
                    sizeBytes = if (child.isDirectory) 0L else child.length(),
                    mimeType = child.type ?: (if (child.isDirectory) "resource/folder" else "application/octet-stream"),
                    lastModified = child.lastModified(),
                    parentUriString = parentUri
                )
                results.add(info)
                if (recursive && child.isDirectory && depth < maxDepth) {
                    traverse(child, depth + 1, child.uri.toString())
                }
            }
        }

        traverse(rootDoc, 1, treeUri.toString())
        results
    }

    /**
     * Searches for files across all authorized folders or a specific folder.
     */
    suspend fun searchFiles(
        query: String,
        extensionFilter: String? = null,
        specificTreeUri: Uri? = null,
        limit: Int = 40
    ): List<StorageItemInfo> = withContext(Dispatchers.IO) {
        val results = mutableListOf<StorageItemInfo>()
        val locations = if (specificTreeUri != null) {
            listOf(AuthorizedLocation(specificTreeUri.toString(), "Target"))
        } else {
            getAuthorizedLocations()
        }

        val cleanQuery = query.lowercase().trim()
        val queryTokens = cleanQuery.split(" ", "_", "-").map { it.trim() }.filter { it.isNotBlank() }
        val extFilter = extensionFilter?.lowercase()?.removePrefix(".")

        for (loc in locations) {
            val treeUri = Uri.parse(loc.uriString)
            val doc = DocumentFile.fromTreeUri(context, treeUri) ?: continue

            fun searchRec(dir: DocumentFile, depth: Int) {
                if (depth > 5 || results.size >= limit) return
                val children = try { dir.listFiles() } catch (e: Exception) { emptyArray() }
                for (child in children) {
                    val name = child.name ?: ""
                    val nameLower = name.lowercase()
                    val ext = nameLower.substringAfterLast('.', "")

                    val matchesQuery = cleanQuery.isBlank() ||
                            nameLower.contains(cleanQuery) ||
                            (queryTokens.size > 1 && queryTokens.all { nameLower.contains(it) || it == ext })
                    val matchesExt = extFilter.isNullOrBlank() || ext == extFilter

                    if (!child.isDirectory && matchesQuery && matchesExt) {
                        results.add(
                            StorageItemInfo(
                                uriString = child.uri.toString(),
                                name = name,
                                isDirectory = false,
                                sizeBytes = child.length(),
                                mimeType = child.type ?: "application/octet-stream",
                                lastModified = child.lastModified(),
                                parentUriString = dir.uri.toString()
                            )
                        )
                    }
                    if (child.isDirectory) {
                        if (matchesQuery && extFilter.isNullOrBlank()) {
                            results.add(
                                StorageItemInfo(
                                    uriString = child.uri.toString(),
                                    name = name,
                                    isDirectory = true,
                                    sizeBytes = 0L,
                                    mimeType = "resource/folder",
                                    lastModified = child.lastModified(),
                                    parentUriString = dir.uri.toString()
                                )
                            )
                        }
                        searchRec(child, depth + 1)
                    }
                }
            }

            searchRec(doc, 1)
            if (results.size >= limit) break
        }

        results
    }

    /**
     * Reads text content from a supported file URI with safety character limits.
     */
    suspend fun readTextFile(uri: Uri, maxChars: Int = 120_000): String = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val reader = stream.bufferedReader(Charsets.UTF_8)
                val buffer = CharArray(4096)
                val sb = StringBuilder()
                var readCount: Int
                while (reader.read(buffer).also { readCount = it } != -1) {
                    sb.append(buffer, 0, readCount)
                    if (sb.length >= maxChars) {
                        sb.append("\n... [Content truncated at $maxChars characters]")
                        break
                    }
                }
                sb.toString()
            } ?: "Unable to open input stream for $uri"
        } catch (e: Exception) {
            Log.e(TAG, "Error reading text file: ${e.message}")
            "Error reading file: ${e.message}"
        }
    }

    /**
     * Reads raw bytes (for images, audio, or binary files).
     */
    suspend fun readBytes(uri: Uri, maxBytes: Long = 10_000_000): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading raw bytes: ${e.message}")
            null
        }
    }

    /**
     * Writes or edits text file content with automatic pre-modification backup.
     */
    suspend fun writeTextFile(
        uri: Uri,
        content: String,
        createBackup: Boolean = true
    ): StorageActionResult = withContext(Dispatchers.IO) {
        val docFile = DocumentFile.fromSingleUri(context, uri)
            ?: DocumentFile.fromTreeUri(context, uri)
        val fileName = docFile?.name ?: "file"

        var backupUri: String? = null
        if (createBackup) {
            val backup = backupManager.createBackup(uri, fileName)
            backupUri = backup?.backupFilePath
        }

        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            StorageActionResult(
                isSuccess = true,
                message = "File '$fileName' updated successfully.",
                targetUri = uri.toString(),
                backupUri = backupUri
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write file $uri: ${e.message}")
            StorageActionResult(false, "Failed to write file: ${e.message}", backupUri = backupUri)
        }
    }

    /**
     * Creates a new file inside an authorized directory.
     */
    suspend fun createNewFile(
        parentFolderUri: Uri,
        fileName: String,
        mimeType: String = "text/plain",
        initialContent: String = ""
    ): StorageActionResult = withContext(Dispatchers.IO) {
        val parentDoc = DocumentFile.fromTreeUri(context, parentFolderUri)
            ?: return@withContext StorageActionResult(false, "Parent folder not accessible via SAF.")

        try {
            val newFile = parentDoc.createFile(mimeType, fileName)
                ?: return@withContext StorageActionResult(false, "Could not create file '$fileName'.")

            if (initialContent.isNotEmpty()) {
                context.contentResolver.openOutputStream(newFile.uri)?.use { stream ->
                    stream.write(initialContent.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            }

            StorageActionResult(
                isSuccess = true,
                message = "File '${newFile.name}' created successfully.",
                targetUri = newFile.uri.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create file: ${e.message}")
            StorageActionResult(false, "Failed to create file: ${e.message}")
        }
    }

    /**
     * Creates a new directory inside an authorized directory.
     */
    suspend fun createNewFolder(
        parentFolderUri: Uri,
        folderName: String
    ): StorageActionResult = withContext(Dispatchers.IO) {
        val parentDoc = DocumentFile.fromTreeUri(context, parentFolderUri)
            ?: return@withContext StorageActionResult(false, "Parent folder not accessible via SAF.")

        try {
            val newDir = parentDoc.createDirectory(folderName)
                ?: return@withContext StorageActionResult(false, "Could not create directory '$folderName'.")

            StorageActionResult(
                isSuccess = true,
                message = "Folder '${newDir.name}' created successfully.",
                targetUri = newDir.uri.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create folder: ${e.message}")
            StorageActionResult(false, "Failed to create folder: ${e.message}")
        }
    }

    /**
     * Renames an authorized file or directory.
     */
    suspend fun renameFile(uri: Uri, newName: String): StorageActionResult = withContext(Dispatchers.IO) {
        try {
            val doc = DocumentFile.fromSingleUri(context, uri)
                ?: DocumentFile.fromTreeUri(context, uri)
                ?: return@withContext StorageActionResult(false, "Target file not found.")

            val oldName = doc.name ?: "file"
            val success = doc.renameTo(newName)
            if (success) {
                StorageActionResult(
                    isSuccess = true,
                    message = "Renamed '$oldName' to '$newName'.",
                    targetUri = doc.uri.toString()
                )
            } else {
                StorageActionResult(false, "Failed to rename '$oldName' to '$newName'.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error renaming: ${e.message}")
            StorageActionResult(false, "Rename error: ${e.message}")
        }
    }

    /**
     * Copies a file from sourceUri to targetFolderUri.
     */
    suspend fun copyFile(
        sourceUri: Uri,
        targetFolderUri: Uri,
        newName: String? = null
    ): StorageActionResult = withContext(Dispatchers.IO) {
        try {
            val sourceDoc = DocumentFile.fromSingleUri(context, sourceUri)
                ?: DocumentFile.fromTreeUri(context, sourceUri)
                ?: return@withContext StorageActionResult(false, "Source file not found.")

            val targetParent = DocumentFile.fromTreeUri(context, targetFolderUri)
                ?: return@withContext StorageActionResult(false, "Target folder not found.")

            val fileName = newName ?: sourceDoc.name ?: "copied_file"
            val mimeType = sourceDoc.type ?: "application/octet-stream"

            val newFile = targetParent.createFile(mimeType, fileName)
                ?: return@withContext StorageActionResult(false, "Could not create target copy file.")

            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(newFile.uri)?.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            StorageActionResult(
                isSuccess = true,
                message = "Copied '$fileName' to '${targetParent.name}'.",
                targetUri = newFile.uri.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Copy error: ${e.message}")
            StorageActionResult(false, "Failed to copy file: ${e.message}")
        }
    }

    /**
     * Moves a file by copying to destination and removing source.
     */
    suspend fun moveFile(
        sourceUri: Uri,
        targetFolderUri: Uri,
        newName: String? = null
    ): StorageActionResult = withContext(Dispatchers.IO) {
        val copyResult = copyFile(sourceUri, targetFolderUri, newName)
        if (!copyResult.isSuccess) {
            return@withContext copyResult
        }

        // Remove original
        val delResult = deleteFile(sourceUri)
        if (delResult.isSuccess) {
            StorageActionResult(
                isSuccess = true,
                message = "Moved file successfully.",
                targetUri = copyResult.targetUri
            )
        } else {
            StorageActionResult(
                isSuccess = true,
                message = "File copied, but source could not be deleted: ${delResult.message}",
                targetUri = copyResult.targetUri
            )
        }
    }

    /**
     * Deletes an authorized file or directory.
     * Note: FileSafetyManager must enforce confirmation BEFORE calling this!
     */
    suspend fun deleteFile(uri: Uri): StorageActionResult = withContext(Dispatchers.IO) {
        try {
            val doc = DocumentFile.fromSingleUri(context, uri)
                ?: DocumentFile.fromTreeUri(context, uri)
                ?: return@withContext StorageActionResult(false, "File not found.")

            val name = doc.name ?: "file"
            val success = doc.delete()
            if (success) {
                StorageActionResult(true, "Successfully deleted '$name'.")
            } else {
                StorageActionResult(false, "System was unable to delete '$name'.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete error: ${e.message}")
            StorageActionResult(false, "Delete failed: ${e.message}")
        }
    }

    /**
     * Scans authorized directories and identifies duplicate files by size and SHA-256 hash.
     */
    suspend fun findDuplicates(targetTreeUri: Uri? = null): List<DuplicateGroup> = withContext(Dispatchers.IO) {
        val allFiles = searchFiles("", null, targetTreeUri, limit = 500).filter { !it.isDirectory && it.sizeBytes > 0 }
        val bySize = allFiles.groupBy { it.sizeBytes }.filter { it.value.size > 1 }

        val duplicateGroups = mutableListOf<DuplicateGroup>()

        for ((size, files) in bySize) {
            val byHash = mutableMapOf<String, MutableList<StorageItemInfo>>()
            for (file in files) {
                val hash = computeSha256(file.uri)
                if (hash != null) {
                    byHash.getOrPut(hash) { mutableListOf() }.add(file)
                }
            }
            for ((hash, dups) in byHash) {
                if (dups.size > 1) {
                    duplicateGroups.add(DuplicateGroup(size, hash, dups))
                }
            }
        }

        duplicateGroups.sortedByDescending { it.fileSize * it.files.size }
    }

    private fun computeSha256(uri: Uri): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    md.update(buffer, 0, bytesRead)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}
