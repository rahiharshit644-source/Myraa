package com.soltini.app.rag

import android.content.Context
import android.net.Uri
import android.util.Log
import com.soltini.app.rag.db.RagKnowledgeDatabase
import com.soltini.app.rag.parsers.*
import com.soltini.app.rag.splitter.RecursiveDocumentSplitter
import com.soltini.app.settings.AppSettings
import com.soltini.app.storage.SafStorageManager
import com.soltini.app.storage.StorageItemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * RagKnowledgeEngine
 *
 * Master Ingestion & Retrieval Pipeline modeled after LangChain4j:
 *   DocumentLoaders / Parsers -> RecursiveDocumentSplitter -> EmbeddingStore (SQLite) -> ContentRetriever
 *
 * Capabilities:
 * - Incremental indexing with duplicate prevention.
 * - Multi-document semantic search across authorized files.
 * - Specific document search ("Chapter 3 खोजो", "Paragraph find karo").
 * - Document summarization (PDFs, text, docs).
 * - Code symbol and function discovery.
 * - Automated index synchronization & stale document pruning.
 */
class RagKnowledgeEngine private constructor(
    private val context: Context,
    private val appSettings: AppSettings
) {

    companion object {
        private const val TAG = "RagKnowledgeEngine"
        private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

        @Volatile
        private var instance: RagKnowledgeEngine? = null

        fun getInstance(context: Context, appSettings: AppSettings): RagKnowledgeEngine =
            instance ?: synchronized(this) {
                instance ?: RagKnowledgeEngine(context.applicationContext, appSettings).also { instance = it }
            }
    }

    val db = RagKnowledgeDatabase(context)
    val splitter = RecursiveDocumentSplitter(maxSegmentSizeInChars = 800, maxOverlapSizeInChars = 140)

    val imageVisionParser = ImageVisionParser(appSettings)
    val pdfParser = PdfDocumentParser()
    val textParser = TextDocumentParser()
    val codeParser = CodeDocumentParser()
    val zipParser = ZipArchiveParser()

    private val parsers: List<DocumentParser> = listOf(
        pdfParser,
        codeParser,
        textParser,
        imageVisionParser,
        zipParser
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Resolves the best parser for a given file item.
     */
    fun getParserFor(mimeType: String, extension: String): DocumentParser? {
        return parsers.firstOrNull { it.supports(mimeType, extension) }
    }

    /**
     * Indexes a single file incrementally: skips if file has not been modified.
     */
    suspend fun indexFile(item: StorageItemInfo, forceReindex: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (item.isDirectory) return@withContext false

        // Check if already indexed and unmodified
        if (!forceReindex) {
            val lastMod = db.getDocumentLastModified(item.uriString)
            if (lastMod != null && lastMod == item.lastModified && item.lastModified > 0) {
                Log.d(TAG, "File '${item.name}' unchanged, skipping indexing.")
                return@withContext true
            }
        }

        val parser = getParserFor(item.mimeType, item.extension)
        if (parser == null) {
            Log.d(TAG, "No parser supports '${item.name}' (mime: ${item.mimeType})")
            return@withContext false
        }

        try {
            val doc = parser.parse(context, item.uri, item.name, item.mimeType)
            val chunks = splitter.split(doc)
            db.saveDocumentWithChunks(doc, chunks)
            Log.i(TAG, "Indexed '${item.name}': ${chunks.size} chunks generated.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed indexing '${item.name}': ${e.message}")
            false
        }
    }

    /**
     * Scans and indexes all documents in all authorized SAF folders.
     */
    suspend fun indexAllAuthorizedFolders(
        storageManager: SafStorageManager,
        onProgress: ((current: Int, total: Int, fileName: String) -> Unit)? = null
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val files = storageManager.searchFiles("", null, null, limit = 400).filter { !it.isDirectory }
        var indexedDocs = 0
        var totalChunks = 0

        for ((idx, file) in files.withIndex()) {
            onProgress?.invoke(idx + 1, files.size, file.name)
            val success = indexFile(file)
            if (success) {
                indexedDocs++
            }
        }

        // Prune any deleted or inaccessible files
        purgeInaccessibleFiles(storageManager)

        val stats = db.getStats()
        totalChunks = stats.totalChunks
        Pair(indexedDocs, totalChunks)
    }

    /**
     * Multi-document search across authorized knowledge base.
     */
    suspend fun searchKnowledge(
        query: String,
        categoryFilter: String? = null,
        limit: Int = 6
    ): List<RagSearchResult> = withContext(Dispatchers.IO) {
        db.searchChunks(query = query, categoryFilter = categoryFilter, limit = limit)
    }

    /**
     * Searches within a specific document (e.g. "Chapter 3", "Section 5").
     */
    suspend fun searchInDocument(
        documentUri: String,
        query: String,
        limit: Int = 5
    ): List<RagSearchResult> = withContext(Dispatchers.IO) {
        db.searchChunks(query = query, targetDocumentUri = documentUri, limit = limit)
    }

    /**
     * Generates an intelligent summary of a document.
     */
    suspend fun summarizeDocument(item: StorageItemInfo): String = withContext(Dispatchers.IO) {
        // First ensure file is indexed
        indexFile(item)
        val chunks = db.getChunksForDocument(item.uriString)

        if (chunks.isEmpty()) {
            return@withContext "Document '${item.name}' is empty or could not be parsed."
        }

        val combinedText = chunks.take(8).joinToString("\n\n") { it.text }
        val apiKey = appSettings.geminiApiKey

        if (apiKey.isNotBlank() && combinedText.length > 80) {
            try {
                val prompt = """
                    Please provide a clear, structured executive summary of this document: '${item.name}'.
                    Highlight key takeaways, main sections/topics, and important details.
                    Context:
                    $combinedText
                """.trimIndent()
                val summary = callGeminiGenerate(apiKey, prompt)
                if (summary.isNotBlank()) return@withContext summary
            } catch (e: Exception) {
                Log.w(TAG, "Gemini summarization failed: ${e.message}")
            }
        }

        // Algorithmic summary fallback
        val firstChunk = chunks.first().text.take(300)
        "Document: ${item.name}\nTotal Chunks: ${chunks.size}\nPreview: $firstChunk..."
    }

    /**
     * Search code symbols (functions, classes) across indexed code files.
     */
    suspend fun findCodeSymbols(query: String): List<CodeSymbolResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CodeSymbolResult>()
        val hits = db.searchChunks(query = query, categoryFilter = "code", limit = 15)

        val cleanQuery = query.lowercase().trim()
        val classRegex = Regex("""(?:class|interface|struct|enum|object)\s+([A-Za-z0-9_]+)""")
        val funRegex = Regex("""(?:fun|def|function|fn|void|val\s+\w+\s*=\s*\{|int|String)\s+([A-Za-z0-9_]+)\s*\(""")

        for (hit in hits) {
            val lines = hit.chunk.text.lines()
            for ((idx, line) in lines.withIndex()) {
                val lineLower = line.lowercase()
                if (lineLower.contains(cleanQuery)) {
                    val classMatch = classRegex.find(line)
                    val funMatch = funRegex.find(line)

                    val (symName, symType) = when {
                        classMatch != null -> Pair(classMatch.groupValues[1], "class")
                        funMatch != null -> Pair(funMatch.groupValues[1], "function")
                        else -> Pair(cleanQuery, "keyword")
                    }

                    results.add(
                        CodeSymbolResult(
                            symbolName = symName,
                            symbolType = symType,
                            documentName = hit.chunk.documentName,
                            documentUri = hit.chunk.documentUri,
                            lineNumber = idx + 1,
                            previewCode = line.trim()
                        )
                    )
                }
            }
        }
        results.distinctBy { "${it.documentName}_${it.lineNumber}" }.take(10)
    }

    /**
     * Removes records of files that have been deleted or whose SAF access was revoked.
     */
    suspend fun purgeInaccessibleFiles(storageManager: SafStorageManager) = withContext(Dispatchers.IO) {
        val indexedUris = db.getAllIndexedUris()
        for (uriStr in indexedUris) {
            val uri = Uri.parse(uriStr)
            var exists = false
            try {
                context.contentResolver.openInputStream(uri)?.use { exists = true }
            } catch (_: Exception) {
                exists = false
            }
            if (!exists) {
                Log.i(TAG, "Purging inaccessible or deleted document from RAG index: $uriStr")
                db.deleteDocument(uriStr)
            }
        }
    }

    /**
     * Formats retrieved RAG knowledge chunks for injection into MYRA's LLM prompt.
     */
    fun formatRagContextForPrompt(results: List<RagSearchResult>): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder("=== USER AUTHORIZED RAG KNOWLEDGE BASE ===\n")
        for ((idx, r) in results.withIndex()) {
            sb.append("[Doc #${idx + 1}: ${r.chunk.documentName} (Score: ${(r.score * 100).toInt()}%)]\n")
            sb.append("${r.matchSnippet}\n\n")
        }
        sb.append("=== END RAG KNOWLEDGE BASE ===")
        return sb.toString()
    }

    private fun callGeminiGenerate(apiKey: String, prompt: String): String {
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 800)
            })
        }

        val request = Request.Builder()
            .url("$GEMINI_API_URL?key=$apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = httpClient.newCall(request).execute()
        val str = resp.body?.string() ?: return ""
        if (!resp.isSuccessful) return ""

        val json = JSONObject(str)
        val candidates = json.optJSONArray("candidates") ?: return ""
        if (candidates.length() > 0) {
            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                return parts.getJSONObject(0).optString("text", "")
            }
        }
        return ""
    }
}
