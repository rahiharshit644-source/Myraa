package com.soltini.app.rag.parsers

import android.content.Context
import android.net.Uri
import com.soltini.app.rag.RagDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * ZipArchiveParser
 *
 * Inspects ZIP and archive files: lists nested files/directories, file sizes,
 * and extracts text content from supported text/code files inside the archive.
 */
class ZipArchiveParser : DocumentParser {

    private val supportedExts = setOf("zip", "jar", "aar")

    override fun supports(mimeType: String, extension: String): Boolean {
        return mimeType == "application/zip" ||
                mimeType == "application/x-zip-compressed" ||
                supportedExts.contains(extension.lowercase())
    }

    override suspend fun parse(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): RagDocument = withContext(Dispatchers.IO) {
        val fileTree = StringBuilder("Archive Structure of '$fileName':\n")
        val sampleContents = StringBuilder("\n--- Archive Contents Preview ---\n")
        var fileCount = 0
        var totalUncompressedBytes = 0L

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ZipInputStream(stream).use { zip ->
                    var entry: ZipEntry? = zip.nextEntry
                    while (entry != null) {
                        fileCount++
                        val entryName = entry.name
                        val size = entry.size
                        if (size > 0) totalUncompressedBytes += size

                        val indent = "  ".repeat(entryName.count { it == '/' })
                        fileTree.append("$indent- $entryName ${if (entry.isDirectory) "[DIR]" else "(${size} B)"}\n")

                        // Extract preview for text/code files inside zip (up to 5 files, 2KB each)
                        val ext = entryName.substringAfterLast('.', "").lowercase()
                        val isTextFile = setOf("txt", "md", "json", "xml", "kt", "java", "py", "js", "html", "csv", "gradle").contains(ext)
                        if (!entry.isDirectory && isTextFile && sampleContents.length < 8000) {
                            val buffer = ByteArray(2048)
                            val read = zip.read(buffer)
                            if (read > 0) {
                                val preview = String(buffer, 0, read, Charsets.UTF_8)
                                sampleContents.append("\n[File: $entryName]\n$preview\n...")
                            }
                        }

                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }
        } catch (e: Exception) {
            fileTree.append("\nError reading archive: ${e.message}")
        }

        val combined = "$fileTree\nTotal entries: $fileCount\nEstimated uncompressed size: ${totalUncompressedBytes / 1024} KB\n$sampleContents"

        RagDocument(
            uriString = uri.toString(),
            name = fileName,
            mimeType = "application/zip",
            text = combined,
            sizeBytes = totalUncompressedBytes,
            metadata = mapOf(
                "parser" to "ZipArchiveParser",
                "entryCount" to fileCount.toString()
            )
        )
    }
}
