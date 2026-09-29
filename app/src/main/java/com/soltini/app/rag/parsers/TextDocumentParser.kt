package com.soltini.app.rag.parsers

import android.content.Context
import android.net.Uri
import com.soltini.app.rag.RagDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TextDocumentParser
 *
 * Handles plain text, Markdown, CSV, JSON, XML, HTML, YAML, and configuration files.
 */
class TextDocumentParser : DocumentParser {

    private val supportedExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml",
        "html", "htm", "yaml", "yml", "ini", "conf", "log", "properties"
    )

    override fun supports(mimeType: String, extension: String): Boolean {
        return mimeType.startsWith("text/") ||
                mimeType == "application/json" ||
                mimeType == "application/xml" ||
                supportedExtensions.contains(extension.lowercase())
    }

    override suspend fun parse(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): RagDocument = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: ""
        } catch (e: Exception) {
            "Error reading text document: ${e.message}"
        }

        RagDocument(
            uriString = uri.toString(),
            name = fileName,
            mimeType = mimeType.ifBlank { "text/plain" },
            text = text,
            sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            metadata = mapOf(
                "parser" to "TextDocumentParser",
                "charCount" to text.length.toString()
            )
        )
    }
}
