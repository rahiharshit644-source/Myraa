package com.soltini.app.rag.parsers

import android.content.Context
import android.net.Uri
import com.soltini.app.rag.RagDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * CodeDocumentParser
 *
 * Specialized for software engineering artifacts: Kotlin, Java, Python, JavaScript,
 * TypeScript, C/C++, Rust, Go, SQL, Shell, HTML, and CSS.
 * Extracts structural elements like functions and class declarations into metadata.
 */
class CodeDocumentParser : DocumentParser {

    private val supportedExtensions = setOf(
        "kt", "kts", "java", "py", "js", "ts", "jsx", "tsx",
        "c", "cpp", "h", "hpp", "rs", "go", "sql", "sh", "bash",
        "css", "scss", "gradle"
    )

    override fun supports(mimeType: String, extension: String): Boolean {
        return supportedExtensions.contains(extension.lowercase()) ||
                mimeType.contains("javascript") ||
                mimeType.contains("python") ||
                mimeType.contains("x-java") ||
                mimeType.contains("x-c")
    }

    override suspend fun parse(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): RagDocument = withContext(Dispatchers.IO) {
        val content = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: ""
        } catch (e: Exception) {
            "Error reading code file: ${e.message}"
        }

        val lines = content.lines()
        val detectedClasses = mutableListOf<String>()
        val detectedFunctions = mutableListOf<String>()

        // Regex patterns for symbols across languages
        val classRegex = Regex("""(?:class|interface|struct|enum|object)\s+([A-Za-z0-9_]+)""")
        val funRegex = Regex("""(?:fun|def|function|fn|void|val\s+\w+\s*=\s*\{|int|String|bool)\s+([A-Za-z0-9_]+)\s*\(""")

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*")) continue

            classRegex.find(trimmed)?.let { match ->
                detectedClasses.add(match.groupValues[1])
            }
            funRegex.find(trimmed)?.let { match ->
                detectedFunctions.add(match.groupValues[1])
            }
        }

        RagDocument(
            uriString = uri.toString(),
            name = fileName,
            mimeType = mimeType.ifBlank { "text/x-source-code" },
            text = content,
            sizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong(),
            metadata = mapOf(
                "parser" to "CodeDocumentParser",
                "lineCount" to lines.size.toString(),
                "classes" to detectedClasses.distinct().take(15).joinToString(", "),
                "functions" to detectedFunctions.distinct().take(30).joinToString(", ")
            )
        )
    }
}
