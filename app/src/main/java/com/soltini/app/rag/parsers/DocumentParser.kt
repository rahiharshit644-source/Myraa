package com.soltini.app.rag.parsers

import android.content.Context
import android.net.Uri
import com.soltini.app.rag.RagDocument

/**
 * DocumentParser
 *
 * Modeled after LangChain4j DocumentParser.
 * Parses raw input streams or bytes from various file formats into structured RagDocuments.
 */
interface DocumentParser {
    fun supports(mimeType: String, extension: String): Boolean

    suspend fun parse(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): RagDocument
}
