package com.soltini.app.rag.splitter

import com.soltini.app.rag.RagChunk
import com.soltini.app.rag.RagDocument
import java.util.UUID

/**
 * RecursiveDocumentSplitter
 *
 * Implements LangChain4j's DocumentSplitters.recursive pattern:
 * Splits documents hierarchically using natural boundary separators:
 *   Double newline (\n\n) -> Single newline (\n) -> Sentence punctuation (. / ? / !) -> Space (' ')
 * Preserves semantic continuity while maintaining bounded chunk sizes and overlap.
 */
class RecursiveDocumentSplitter(
    val maxSegmentSizeInChars: Int = 800,
    val maxOverlapSizeInChars: Int = 150
) {

    private val separators = listOf("\n\n", "\n", ". ", "? ", "! ", " ", "")

    fun split(document: RagDocument): List<RagChunk> {
        val rawText = document.text.trim()
        if (rawText.isEmpty()) return emptyList()

        val rawChunks = splitRecursively(rawText, separators)
        val combinedSegments = mergeWithOverlap(rawChunks, maxSegmentSizeInChars, maxOverlapSizeInChars)

        val total = combinedSegments.size
        return combinedSegments.mapIndexed { index, segText ->
            val tokens = segText.split(Regex("\\s+")).filter { it.isNotBlank() }
            val termFreqs = tokens
                .map { cleanToken(it) }
                .filter { it.length > 2 }
                .groupingBy { it }
                .eachCount()

            RagChunk(
                id = "${document.name.take(15)}_${index}_${UUID.randomUUID().toString().take(6)}",
                documentUri = document.uriString,
                documentName = document.name,
                chunkIndex = index,
                totalChunks = total,
                text = segText,
                tokenCount = tokens.size,
                mimeType = document.mimeType,
                category = categorizeMime(document.mimeType),
                termFrequencies = termFreqs
            )
        }
    }

    private fun splitRecursively(text: String, separators: List<String>): List<String> {
        if (separators.isEmpty() || text.length <= maxSegmentSizeInChars) {
            return if (text.isNotBlank()) listOf(text) else emptyList()
        }

        val sep = separators.first()
        val remainingSeps = separators.drop(1)

        val splits = if (sep.isEmpty()) {
            text.chunked(maxSegmentSizeInChars)
        } else {
            text.split(sep).filter { it.isNotBlank() }
        }

        val result = mutableListOf<String>()
        for (part in splits) {
            if (part.length <= maxSegmentSizeInChars) {
                result.add(part)
            } else {
                result.addAll(splitRecursively(part, remainingSeps))
            }
        }
        return result
    }

    private fun mergeWithOverlap(
        parts: List<String>,
        maxSize: Int,
        overlap: Int
    ): List<String> {
        val segments = mutableListOf<String>()
        var current = StringBuilder()

        for (part in parts) {
            if (current.isNotEmpty() && current.length + part.length + 1 > maxSize) {
                segments.add(current.toString().trim())

                // Retain overlap tail for continuity
                val currentStr = current.toString()
                val overlapStart = (currentStr.length - overlap).coerceAtLeast(0)
                val overlapTail = currentStr.substring(overlapStart)

                current = StringBuilder(overlapTail).append(" ").append(part)
            } else {
                if (current.isNotEmpty()) current.append(" ")
                current.append(part)
            }
        }

        if (current.isNotBlank()) {
            segments.add(current.toString().trim())
        }

        return segments
    }

    private fun cleanToken(token: String): String {
        return token.lowercase().replace(Regex("[^a-z0-9_]"), "")
    }

    private fun categorizeMime(mimeType: String): String {
        return when {
            mimeType.startsWith("text/x-") || mimeType.contains("javascript") || mimeType.contains("python") -> "code"
            mimeType == "application/pdf" -> "pdf"
            mimeType.startsWith("image/") -> "image_vision"
            mimeType.contains("zip") -> "archive"
            else -> "document"
        }
    }
}
