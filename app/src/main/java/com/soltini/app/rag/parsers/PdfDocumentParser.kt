package com.soltini.app.rag.parsers

import android.content.Context
import android.net.Uri
import android.util.Log
import com.soltini.app.rag.RagDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.regex.Pattern

/**
 * PdfDocumentParser
 *
 * Extracts textual content, chapter headers, and metadata from PDF files
 * using stream analysis and PDF text extraction operators (BT...ET, Tj, TJ).
 */
class PdfDocumentParser : DocumentParser {

    companion object {
        private const val TAG = "PdfDocumentParser"
    }

    override fun supports(mimeType: String, extension: String): Boolean {
        return mimeType == "application/pdf" || extension.equals("pdf", ignoreCase = true)
    }

    override suspend fun parse(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): RagDocument = withContext(Dispatchers.IO) {
        val extractedText = StringBuilder()
        var pageCount = 0
        var title = fileName

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                val rawString = String(bytes, Charsets.ISO_8859_1)

                // 1. Detect page count from /Count or /Type /Page
                val pageMatches = Pattern.compile("/Type\\s*/Page[^s]").matcher(rawString)
                while (pageMatches.find()) {
                    pageCount++
                }
                if (pageCount == 0) pageCount = 1

                // 2. Extract Title if present in metadata dictionary
                val titleMatcher = Pattern.compile("/Title\\s*\\(([^)]+)\\)").matcher(rawString)
                if (titleMatcher.find()) {
                    title = titleMatcher.group(1) ?: fileName
                }

                // 3. Extract text blocks enclosed between BT (Begin Text) and ET (End Text)
                val btEtPattern = Pattern.compile("BT[\\r\\n\\s]+(.*?)[\\r\\n\\s]+ET", Pattern.DOTALL)
                val btMatcher = btEtPattern.matcher(rawString)

                var foundBtText = false
                while (btMatcher.find()) {
                    val block = btMatcher.group(1) ?: continue
                    val textFromBlock = parsePdfTextBlock(block)
                    if (textFromBlock.isNotBlank()) {
                        extractedText.append(textFromBlock).append("\n")
                        foundBtText = true
                    }
                }

                // 4. Fallback if PDF uses compressed stream or simple text
                if (!foundBtText || extractedText.length < 50) {
                    val literalTextMatcher = Pattern.compile("\\(([^()]{3,150})\\)\\s*(?:Tj|'|\")").matcher(rawString)
                    while (literalTextMatcher.find()) {
                        val str = literalTextMatcher.group(1) ?: continue
                        val clean = cleanPdfString(str)
                        if (clean.length > 2) {
                            extractedText.append(clean).append(" ")
                        }
                    }
                }

                // 5. Array text extraction (TJ)
                val tjMatcher = Pattern.compile("\\[([^\\]]+)\\]\\s*TJ").matcher(rawString)
                while (tjMatcher.find()) {
                    val arrayContent = tjMatcher.group(1) ?: continue
                    val innerMatcher = Pattern.compile("\\(([^)]+)\\)").matcher(arrayContent)
                    val line = StringBuilder()
                    while (innerMatcher.find()) {
                        line.append(cleanPdfString(innerMatcher.group(1) ?: ""))
                    }
                    if (line.isNotBlank()) {
                        extractedText.append(line).append("\n")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PDF $fileName: ${e.message}")
            extractedText.append("Error parsing PDF text: ${e.message}")
        }

        val finalText = if (extractedText.isNotBlank()) {
            extractedText.toString().trim()
        } else {
            "[PDF Document: $fileName ($pageCount pages). Contains scanned/visual data or specialized formatting. You can ask MYRA to inspect pages.]"
        }

        RagDocument(
            uriString = uri.toString(),
            name = fileName,
            mimeType = "application/pdf",
            text = finalText,
            sizeBytes = finalText.length.toLong(),
            metadata = mapOf(
                "parser" to "PdfDocumentParser",
                "title" to title,
                "estimatedPages" to pageCount.toString(),
                "textLength" to finalText.length.toString()
            )
        )
    }

    private fun parsePdfTextBlock(block: String): String {
        val result = StringBuilder()
        // Match (text) Tj
        val tjMatcher = Pattern.compile("\\(([^)]+)\\)\\s*Tj").matcher(block)
        while (tjMatcher.find()) {
            val s = cleanPdfString(tjMatcher.group(1) ?: "")
            if (s.isNotBlank()) result.append(s).append(" ")
        }
        // Match [(t)(e)(x)(t)] TJ
        val arrayMatcher = Pattern.compile("\\[([^\\]]+)\\]\\s*TJ").matcher(block)
        while (arrayMatcher.find()) {
            val content = arrayMatcher.group(1) ?: ""
            val inner = Pattern.compile("\\(([^)]+)\\)").matcher(content)
            while (inner.find()) {
                result.append(cleanPdfString(inner.group(1) ?: ""))
            }
            result.append(" ")
        }
        return result.toString().trim()
    }

    private fun cleanPdfString(raw: String): String {
        return raw.replace("\\n", "\n")
            .replace("\\r", " ")
            .replace("\\t", " ")
            .replace("\\(", "(")
            .replace("\\)", ")")
            .replace("\\\\", "\\")
            .trim()
    }
}
