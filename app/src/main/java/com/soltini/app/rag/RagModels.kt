package com.soltini.app.rag

import android.net.Uri
import org.json.JSONObject

/**
 * Document
 *
 * Core document abstraction modeled after LangChain4j Document:
 * Contains raw text along with structured metadata.
 */
data class RagDocument(
    val uriString: String,
    val name: String,
    val mimeType: String,
    val text: String,
    val sizeBytes: Long = 0L,
    val lastModified: Long = System.currentTimeMillis(),
    val metadata: Map<String, String> = emptyMap()
) {
    val uri: Uri get() = Uri.parse(uriString)
}

/**
 * RagChunk (equivalent to LangChain4j TextSegment)
 *
 * An individual semantic segment of a document, indexed for retrieval.
 */
data class RagChunk(
    val id: String,
    val documentUri: String,
    val documentName: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val text: String,
    val tokenCount: Int,
    val mimeType: String,
    val category: String = "general",
    val termFrequencies: Map<String, Int> = emptyMap()
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("document_uri", documentUri)
        put("document_name", documentName)
        put("chunk_index", chunkIndex)
        put("total_chunks", totalChunks)
        put("text", text)
        put("token_count", tokenCount)
        put("mime_type", mimeType)
        put("category", category)
    }
}

/**
 * RagSearchResult
 *
 * Scored search hit returned by the ContentRetriever.
 */
data class RagSearchResult(
    val chunk: RagChunk,
    val score: Float,
    val matchSnippet: String,
    val matchedTerms: List<String> = emptyList()
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("chunk_id", chunk.id)
        put("document_name", chunk.documentName)
        put("document_uri", chunk.documentUri)
        put("score", score)
        put("snippet", matchSnippet)
        put("chunk_index", chunk.chunkIndex)
        put("matched_terms", org.json.JSONArray(matchedTerms))
    }
}

/**
 * CodeSymbolResult
 *
 * Code-specific search result identifying functions, classes, and definitions.
 */
data class CodeSymbolResult(
    val symbolName: String,
    val symbolType: String, // "class", "function", "variable", "interface"
    val documentName: String,
    val documentUri: String,
    val lineNumber: Int,
    val previewCode: String
)

/**
 * RagStats
 *
 * Overview statistics for the RAG Knowledge System.
 */
data class RagStats(
    val totalDocuments: Int,
    val totalChunks: Int,
    val lastIndexedAt: Long,
    val documentTypes: Map<String, Int> = emptyMap()
)
