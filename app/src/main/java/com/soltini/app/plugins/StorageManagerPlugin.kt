package com.soltini.app.plugins

import android.content.Context
import android.net.Uri
import com.soltini.app.rag.RagKnowledgeEngine
import com.soltini.app.settings.AppSettings
import com.soltini.app.storage.FileActionType
import com.soltini.app.storage.FileSafetyManager
import com.soltini.app.storage.SafStorageManager
import com.soltini.app.storage.StorageActionResult
import com.soltini.app.storage.StorageItemInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * StorageManagerPlugin
 *
 * Dedicated Plugin for MYRA's Advanced Storage Intelligence, File Manager & RAG System.
 *
 * Enforces:
 * - Access only to explicitly authorized SAF directory trees.
 * - Automatic snapshot backup before file modifications.
 * - Mandatory confirmation barriers before destructive operations (delete, overwrite, move).
 * - Full integration with LangChain4j-inspired RAG Knowledge Engine.
 */
class StorageManagerPlugin(
    private val context: Context,
    private val storageManager: SafStorageManager,
    private val safetyManager: FileSafetyManager,
    private val ragEngine: RagKnowledgeEngine,
    private val appSettings: AppSettings
) : Plugin {

    override val metadata: PluginMetadata = PluginMetadata(
        id = "file_manager_service",
        name = "Storage Intelligence & File Manager",
        description = "Intelligent file manager, document search, RAG knowledge retrieval, PDF reading, photo OCR/vision, text/code editing, and file operations (copy, move, rename, delete with confirmation).",
        category = PluginCategory.DEVELOPER,
        tags = listOf(
            "file", "folder", "storage", "pdf", "document", "read", "edit",
            "search", "copy", "move", "rename", "delete", "duplicate",
            "photo", "image", "ocr", "vision", "rag", "code", "dhoondo", "padho"
        ),
        riskLevel = RiskLevel.MEDIUM,
        requiresAccessibility = false,
        requiresInternet = false
    )

    override suspend fun execute(context: PluginContext): PluginResult {
        val action = context.optString("action", "search").lowercase().trim()
        val start = System.currentTimeMillis()

        return when (action) {
            "search", "find", "search_files" -> {
                val query = context.optString("query", context.optString("name", ""))
                val ext = context.optString("extension", "")
                val results = storageManager.searchFiles(query, ext.ifBlank { null })

                val json = JSONObject().apply {
                    put("count", results.size)
                    put("files", JSONArray().apply {
                        for (f in results.take(20)) {
                            put(f.toJsonObject())
                        }
                    })
                }
                val summary = if (results.isNotEmpty()) {
                    "Found ${results.size} file(s) matching '$query': " + results.take(4).joinToString(", ") { it.name }
                } else {
                    "No files found matching '$query' in authorized storage."
                }
                PluginResult.success(metadata.id, summary, json, System.currentTimeMillis() - start)
            }

            "read", "read_file", "view" -> {
                val fileNameOrQuery = context.optString("file_name", context.optString("name", context.optString("query", context.optString("raw_query", ""))))
                val uriStr = context.optString("uri", "")

                val targetItem = resolveFileItem(fileNameOrQuery, uriStr)
                    ?: return PluginResult.failure(metadata.id, "Could not find file '$fileNameOrQuery' in authorized folders.", System.currentTimeMillis() - start)

                // Auto-index file in RAG if not already indexed or modified
                ragEngine.indexFile(targetItem, forceReindex = false)

                val content = storageManager.readTextFile(targetItem.uri)
                val json = JSONObject().apply {
                    put("name", targetItem.name)
                    put("size", targetItem.formattedSize)
                    put("mime_type", targetItem.mimeType)
                    put("content_preview", content.take(3000))
                    put("total_chars", content.length)
                }
                PluginResult.success(
                    metadata.id,
                    "Read file '${targetItem.name}' (${targetItem.formattedSize}):\n${content.take(600)}",
                    json,
                    System.currentTimeMillis() - start
                )
            }

            "edit", "edit_file", "replace_text" -> {
                val fileNameOrQuery = context.optString("file_name", context.optString("name", context.optString("query", context.optString("raw_query", ""))))
                val newContent = context.optString("content", "")
                val replaceFrom = context.optString("find_text", "")
                val replaceTo = context.optString("replace_with", "")

                val targetItem = resolveFileItem(fileNameOrQuery, context.optString("uri", ""))
                    ?: return PluginResult.failure(metadata.id, "File '$fileNameOrQuery' not found in authorized folders.", System.currentTimeMillis() - start)

                val finalContent = if (replaceFrom.isNotEmpty()) {
                    val current = storageManager.readTextFile(targetItem.uri)
                    current.replace(replaceFrom, replaceTo)
                } else {
                    newContent
                }

                val editResult = storageManager.writeTextFile(targetItem.uri, finalContent, createBackup = true)
                // Re-index modified file in RAG
                ragEngine.indexFile(targetItem, forceReindex = true)

                val json = JSONObject().apply {
                    put("is_success", editResult.isSuccess)
                    put("backup_path", editResult.backupUri)
                    put("file", targetItem.name)
                }
                if (editResult.isSuccess) {
                    val msg = "Updated '${targetItem.name}' safely (Snapshot backup created: ${editResult.backupUri != null})."
                    PluginResult.success(metadata.id, msg, json, System.currentTimeMillis() - start)
                } else {
                    PluginResult.failure(metadata.id, editResult.message, System.currentTimeMillis() - start)
                }
            }

            "create", "create_file", "new_file" -> {
                val fileName = context.optString("file_name", "new_document.txt")
                val content = context.optString("content", "")
                val folderUriStr = context.optString("folder_uri", "")

                val parentUri = if (folderUriStr.isNotBlank()) {
                    Uri.parse(folderUriStr)
                } else {
                    storageManager.getAuthorizedLocations().firstOrNull()?.uri
                } ?: return PluginResult.failure(metadata.id, "No authorized folder available to create file.", System.currentTimeMillis() - start)

                val res = storageManager.createNewFile(parentUri, fileName, "text/plain", content)
                if (res.isSuccess && res.targetUri != null) {
                    val createdItem = StorageItemInfo(
                        uriString = res.targetUri,
                        name = fileName,
                        isDirectory = false,
                        sizeBytes = content.toByteArray().size.toLong(),
                        mimeType = "text/plain",
                        lastModified = System.currentTimeMillis()
                    )
                    ragEngine.indexFile(createdItem, forceReindex = true)
                }
                fromStorageResult(res, start)
            }

            "create_folder", "new_folder" -> {
                val folderName = context.optString("folder_name", "New Folder")
                val parentUri = storageManager.getAuthorizedLocations().firstOrNull()?.uri
                    ?: return PluginResult.failure(metadata.id, "No authorized folder available.", System.currentTimeMillis() - start)

                val res = storageManager.createNewFolder(parentUri, folderName)
                fromStorageResult(res, start)
            }

            "rename", "rename_file" -> {
                val fileName = context.optString("file_name", context.optString("file_reference", context.optString("name", "")))
                val newName = context.optString("new_name", "")
                val targetItem = resolveFileItem(fileName, context.optString("uri", ""))
                    ?: return PluginResult.failure(metadata.id, "File '$fileName' not found.", System.currentTimeMillis() - start)

                val res = storageManager.renameFile(targetItem.uri, newName)
                if (res.isSuccess) {
                    ragEngine.db.deleteDocument(targetItem.uriString)
                    val renamedItem = targetItem.copy(name = newName)
                    ragEngine.indexFile(renamedItem, forceReindex = true)
                }
                fromStorageResult(res, start)
            }

            "copy", "copy_file" -> {
                val fileName = context.optString("file_name", context.optString("file_reference", context.optString("name", "")))
                val targetItem = resolveFileItem(fileName, context.optString("uri", ""))
                    ?: return PluginResult.failure(metadata.id, "Source file '$fileName' not found.", System.currentTimeMillis() - start)

                val destFolderUri = resolveFolderUri(context.optString("destination_folder", context.optString("target_folder", "")))
                    ?: return PluginResult.failure(metadata.id, "Destination folder not found in authorized locations.", System.currentTimeMillis() - start)

                val res = storageManager.copyFile(targetItem.uri, destFolderUri)
                if (res.isSuccess && res.targetUri != null) {
                    val copiedItem = targetItem.copy(uriString = res.targetUri)
                    ragEngine.indexFile(copiedItem, forceReindex = true)
                }
                fromStorageResult(res, start)
            }

            "move", "move_file" -> {
                val fileName = context.optString("file_name", context.optString("file_reference", context.optString("name", "")))
                val targetItem = resolveFileItem(fileName, context.optString("uri", ""))
                    ?: return PluginResult.failure(metadata.id, "File '$fileName' not found.", System.currentTimeMillis() - start)

                val destFolderUri = resolveFolderUri(context.optString("destination_folder", context.optString("target_folder", "")))
                    ?: return PluginResult.failure(metadata.id, "Destination folder not found.", System.currentTimeMillis() - start)

                // Enforce safety confirmation if not explicitly pre-confirmed
                val isConfirmed = context.optBoolean("confirmed", false)
                if (!isConfirmed) {
                    val pending = safetyManager.registerDestructiveAction(
                        FileActionType.MOVE,
                        targetItem.uri,
                        targetItem.name,
                        mapOf("target_folder_uri" to destFolderUri.toString())
                    )
                    return PluginResult.success(
                        metadata.id,
                        pending.confirmationPrompt,
                        JSONObject().apply { put("pending_id", pending.id); put("requires_confirmation", true) },
                        System.currentTimeMillis() - start
                    )
                }

                val res = storageManager.moveFile(targetItem.uri, destFolderUri)
                if (res.isSuccess) {
                    ragEngine.db.deleteDocument(targetItem.uriString)
                    if (res.targetUri != null) {
                        val movedItem = targetItem.copy(uriString = res.targetUri)
                        ragEngine.indexFile(movedItem, forceReindex = true)
                    }
                }
                fromStorageResult(res, start)
            }

            "delete", "delete_file", "remove" -> {
                val fileName = context.optString("file_name", context.optString("name", context.optString("query", context.optString("raw_query", ""))))
                val isConfirmed = context.optBoolean("confirmed", false)

                val targetItem = resolveFileItem(fileName, context.optString("uri", ""))
                    ?: return PluginResult.failure(metadata.id, "File '$fileName' not found to delete.", System.currentTimeMillis() - start)

                // STRICT SAFETY BARRIER: Mandatory explicit user confirmation before delete
                if (!isConfirmed) {
                    val pending = safetyManager.registerDestructiveAction(
                        FileActionType.DELETE,
                        targetItem.uri,
                        targetItem.name
                    )
                    return PluginResult.success(
                        metadata.id,
                        pending.confirmationPrompt,
                        JSONObject().apply {
                            put("pending_id", pending.id)
                            put("requires_confirmation", true)
                            put("target_name", targetItem.name)
                        },
                        System.currentTimeMillis() - start
                    )
                }

                // If user confirmed, execute safely
                val delRes = storageManager.deleteFile(targetItem.uri)
                if (delRes.isSuccess) {
                    ragEngine.db.deleteDocument(targetItem.uriString)
                }
                fromStorageResult(delRes, start)
            }

            "confirm_action" -> {
                val confirmationId = context.optString("confirmation_id", "")
                val latest = safetyManager.getLatestPending()
                val idToExecute = confirmationId.ifBlank { latest?.id ?: "" }

                if (idToExecute.isBlank()) {
                    return PluginResult.failure(metadata.id, "No pending confirmation found.", System.currentTimeMillis() - start)
                }

                val actionType = latest?.actionType
                val targetUri = latest?.targetUri
                val res = safetyManager.executeConfirmedAction(idToExecute)
                if (res.isSuccess && actionType == FileActionType.DELETE && targetUri != null) {
                    ragEngine.db.deleteDocument(targetUri)
                }
                fromStorageResult(res, start)
            }

            "cancel_action" -> {
                val msg = safetyManager.cancelPendingAction(context.optString("confirmation_id", ""))
                PluginResult.success(metadata.id, msg, JSONObject(), System.currentTimeMillis() - start)
            }

            "summarize", "summarize_document" -> {
                val fileName = context.optString("file_name", context.optString("name", context.optString("query", context.optString("raw_query", ""))))
                val targetItem = resolveFileItem(fileName, context.optString("uri", ""))
                    ?: return PluginResult.failure(metadata.id, "Document '$fileName' not found in authorized folders.", System.currentTimeMillis() - start)

                // Auto-index file in RAG if not already indexed or modified
                ragEngine.indexFile(targetItem, forceReindex = false)

                val summary = ragEngine.summarizeDocument(targetItem)
                val json = JSONObject().apply {
                    put("file_name", targetItem.name)
                    put("summary", summary)
                }
                PluginResult.success(metadata.id, "Summary of '${targetItem.name}':\n$summary", json, System.currentTimeMillis() - start)
            }

            "search_document", "find_in_doc", "search_knowledge" -> {
                val query = context.optString("query", "")
                val fileName = context.optString("file_name", context.optString("name", ""))

                val targetItem = if (fileName.isNotBlank()) {
                    resolveFileItem(fileName, "")
                } else null

                // Auto-index file in RAG if targetItem resolved
                if (targetItem != null) {
                    ragEngine.indexFile(targetItem, forceReindex = false)
                }

                val targetUri = targetItem?.uriString

                var hits = if (targetUri != null) {
                    ragEngine.searchInDocument(targetUri, query)
                } else {
                    ragEngine.searchKnowledge(query)
                }

                // If no hits found in RAG DB, scan authorized storage for files matching query, auto-index, and retry
                if (hits.isEmpty() && query.isNotBlank()) {
                    val matchingFiles = storageManager.searchFiles(query, limit = 5).filter { !it.isDirectory }
                    for (file in matchingFiles) {
                        ragEngine.indexFile(file, forceReindex = false)
                    }
                    if (matchingFiles.isNotEmpty()) {
                        hits = if (targetUri != null) {
                            ragEngine.searchInDocument(targetUri, query)
                        } else {
                            ragEngine.searchKnowledge(query)
                        }
                    }
                }

                val json = JSONObject().apply {
                    put("hits_count", hits.size)
                    put("hits", JSONArray().apply {
                        for (h in hits) put(h.toJsonObject())
                    })
                }

                val summary = if (hits.isNotEmpty()) {
                    "Found ${hits.size} relevant excerpt(s) for '$query':\n" +
                            hits.take(3).joinToString("\n---\n") { "${it.chunk.documentName}: ${it.matchSnippet}" }
                } else {
                    "No relevant information found for '$query' in authorized documents."
                }
                PluginResult.success(metadata.id, summary, json, System.currentTimeMillis() - start)
            }

            "analyze_image", "photo_ocr", "inspect_photo" -> {
                val fileName = context.optString("file_name", "")
                val targetItem = resolveFileItem(fileName, context.optString("uri", ""))
                    ?: return PluginResult.failure(metadata.id, "Photo/image '$fileName' not found.", System.currentTimeMillis() - start)

                val prompt = context.optString("prompt", "What is shown in this image? Describe objects and extract any readable text.")
                val analysis = ragEngine.imageVisionParser.analyzeImageWithPrompt(context.context, targetItem.uri, prompt)

                val json = JSONObject().apply {
                    put("file_name", targetItem.name)
                    put("analysis", analysis)
                }
                PluginResult.success(metadata.id, "Photo '${targetItem.name}':\n$analysis", json, System.currentTimeMillis() - start)
            }

            "find_duplicates" -> {
                val dups = storageManager.findDuplicates()
                val json = JSONObject().apply {
                    put("duplicate_groups", dups.size)
                    put("groups", JSONArray().apply {
                        for (g in dups) {
                            put(JSONObject().apply {
                                put("size_bytes", g.fileSize)
                                put("hash", g.sha256Hash)
                                put("files", JSONArray().apply {
                                    for (f in g.files) put(f.toJsonObject())
                                })
                            })
                        }
                    })
                }

                val summary = if (dups.isNotEmpty()) {
                    val totalDupFiles = dups.sumOf { it.files.size - 1 }
                    "Found ${dups.size} group(s) of duplicate files ($totalDupFiles duplicate files detected). Example: " +
                            dups.take(2).joinToString("; ") { g -> g.files.joinToString(", ") { it.name } }
                } else {
                    "No duplicate files detected in authorized storage."
                }
                PluginResult.success(metadata.id, summary, json, System.currentTimeMillis() - start)
            }

            "list_directory", "list_folder" -> {
                val loc = storageManager.getAuthorizedLocations().firstOrNull()
                    ?: return PluginResult.failure(metadata.id, "No authorized folder locations found.", System.currentTimeMillis() - start)
                val items = storageManager.listDirectory(loc.uri, recursive = true, maxDepth = 2)

                val json = JSONObject().apply {
                    put("folder", loc.displayName)
                    put("count", items.size)
                    put("items", JSONArray().apply {
                        for (i in items.take(40)) put(i.toJsonObject())
                    })
                }
                val summary = "Folder '${loc.displayName}' contains ${items.size} item(s): " +
                        items.take(8).joinToString(", ") { "${it.name} ${if (it.isDirectory) "[dir]" else ""}" }
                PluginResult.success(metadata.id, summary, json, System.currentTimeMillis() - start)
            }

            "undo", "restore_backup" -> {
                val backupId = context.optString("backup_id", "")
                val latest = if (backupId.isNotBlank()) backupId else storageManager.backupManager.getAllBackups().firstOrNull()?.id

                if (latest == null) {
                    return PluginResult.failure(metadata.id, "No backups available to undo.", System.currentTimeMillis() - start)
                }
                val res = storageManager.backupManager.restoreBackup(latest)
                fromStorageResult(res, start)
            }

            else -> PluginResult.failure(metadata.id, "Unknown file manager action: $action", System.currentTimeMillis() - start)
        }
    }

    private suspend fun resolveFileItem(nameOrQuery: String, uriStr: String): StorageItemInfo? {
        if (uriStr.isNotBlank()) {
            val matches = storageManager.searchFiles("", null, null)
            matches.find { it.uriString == uriStr }?.let { return it }
        }

        if (nameOrQuery.isBlank()) return null

        val clean = nameOrQuery.lowercase().trim()

        // 1. Direct search from storageManager
        val directMatches = storageManager.searchFiles(clean, null, null).filter { !it.isDirectory }
        // Exact match first
        directMatches.find { it.name.equals(clean, ignoreCase = true) }?.let { return it }
        // Substring match
        directMatches.find { it.name.contains(clean, ignoreCase = true) }?.let { return it }
        if (directMatches.isNotEmpty()) return directMatches.first()

        // 2. Fuzzy / Token / Extension-aware matching across all files in authorized storage
        val allFiles = storageManager.searchFiles("", null, null, limit = 300).filter { !it.isDirectory }
        if (allFiles.isEmpty()) return null

        // Detect desired extension if mentioned in query (e.g. "pdf", "txt", "md", "json", "png", "jpg", etc.)
        val desiredExt = when {
            clean.contains(".pdf") || clean.contains(" pdf") || clean.endsWith("pdf") -> "pdf"
            clean.contains(".txt") || clean.contains(" txt") || clean.contains("text") -> "txt"
            clean.contains(".md") || clean.contains(" md") || clean.contains("markdown") -> "md"
            clean.contains(".json") || clean.contains(" json") -> "json"
            clean.contains(".png") || clean.contains(" png") -> "png"
            clean.contains(".jpg") || clean.contains(".jpeg") || clean.contains(" jpg") || clean.contains("image") || clean.contains("photo") -> "jpg"
            clean.contains(".csv") || clean.contains(" csv") -> "csv"
            clean.contains(".zip") || clean.contains(" zip") -> "zip"
            else -> null
        }

        // Clean out common conversational/command words to extract significant keywords
        val stopWords = setOf(
            "meri", "mera", "mere", "my", "the", "a", "an", "file", "files", "document", "doc", "pdf", "txt", "text",
            "kholo", "padho", "read", "open", "summarize", "dikhao", "wali", "wala", "vale", "ka", "ki", "ke", "ko",
            "in", "me", "from", "se", "please", "kripya", "notes", "show", "content", "batao", "hai", "karo", "do",
            "edit", "delete", "hatao", "search", "dhoondo", "find"
        )
        val tokens = clean
            .replace(Regex("[^a-zA-Z0-9_\\-\\.]"), " ")
            .split(" ")
            .map { it.trim().lowercase() }
            .filter { it.length >= 2 && !stopWords.contains(it) }

        val scored = allFiles.map { file ->
            val fileNameLower = file.name.lowercase()
            val fileExt = file.extension.lowercase()
            var score = 0

            // Extension match
            if (desiredExt != null && (fileExt == desiredExt || (desiredExt == "jpg" && (fileExt == "jpeg" || fileExt == "png")))) {
                score += 5
            }

            // Keyword token matches
            for (token in tokens) {
                if (fileNameLower.contains(token)) {
                    score += 10
                }
            }

            // All keywords present
            if (tokens.isNotEmpty() && tokens.all { fileNameLower.contains(it) }) {
                score += 15
            }

            file to score
        }

        val best = scored.filter { it.second > 0 }.maxByOrNull { it.second }
        if (best != null && best.second >= 5) {
            return best.first
        }

        return null
    }

    private suspend fun resolveFolderUri(folderName: String): Uri? {
        val locs = storageManager.getAuthorizedLocations()
        if (folderName.isBlank()) return locs.firstOrNull()?.uri
        val directMatch = locs.find { it.displayName.contains(folderName, ignoreCase = true) }
        if (directMatch != null) return directMatch.uri

        // Search subfolders within authorized locations
        val subDirs = storageManager.searchFiles(folderName, null, null).filter { it.isDirectory }
        val foundDir = subDirs.find { it.name.equals(folderName, ignoreCase = true) }
            ?: subDirs.find { it.name.contains(folderName, ignoreCase = true) }
        return foundDir?.uri ?: locs.firstOrNull()?.uri
    }

    private fun fromStorageResult(res: StorageActionResult, start: Long): PluginResult {
        val latency = System.currentTimeMillis() - start
        return if (res.isSuccess) {
            PluginResult.success(metadata.id, res.message, res.toJsonObject(), latency)
        } else {
            PluginResult.failure(metadata.id, res.message, latency)
        }
    }
}
