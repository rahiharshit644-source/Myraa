package com.soltini.app.rag.parsers

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.soltini.app.rag.RagDocument
import com.soltini.app.settings.AppSettings
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
 * ImageVisionParser
 *
 * Provides Vision Analysis & OCR for photos and images using Gemini Multimodal Vision.
 * Identifies objects, scenes, text, diagrams, and labels inside images.
 */
class ImageVisionParser(private val appSettings: AppSettings) : DocumentParser {

    companion object {
        private const val TAG = "ImageVisionParser"
        private const val VISION_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val supportedExts = setOf("jpg", "jpeg", "png", "webp", "bmp")

    override fun supports(mimeType: String, extension: String): Boolean {
        return mimeType.startsWith("image/") || supportedExts.contains(extension.lowercase())
    }

    override suspend fun parse(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String
    ): RagDocument = withContext(Dispatchers.IO) {
        var width = 0
        var height = 0
        var imageBytes: ByteArray? = null

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                imageBytes = stream.readBytes()
            }
            if (imageBytes != null) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes!!.size, opts)
                width = opts.outWidth
                height = opts.outHeight
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading image bounds: ${e.message}")
        }

        // Perform Vision OCR / Description via Gemini if API key available and image is loaded
        val apiKey = appSettings.geminiApiKey
        var visionDescription = "Image: $fileName (${width}x${height} px)"

        if (apiKey.isNotBlank() && imageBytes != null && imageBytes!!.size <= 4_000_000) {
            try {
                val base64Data = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                val cleanMime = when {
                    fileName.endsWith(".png", true) -> "image/png"
                    fileName.endsWith(".webp", true) -> "image/webp"
                    else -> "image/jpeg"
                }

                val prompt = "Analyze this image thoroughly. 1) Describe what is shown in the image (objects, people, scene, colors). 2) Extract and transcribe ANY visible text, signs, labels, or numbers (OCR). Keep the summary clear, accurate, and concise in both English and Hindi context."
                val desc = callGeminiVision(apiKey, base64Data, cleanMime, prompt)
                if (desc.isNotBlank()) {
                    visionDescription = desc
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gemini Vision call failed for $fileName: ${e.message}")
            }
        }

        RagDocument(
            uriString = uri.toString(),
            name = fileName,
            mimeType = mimeType.ifBlank { "image/jpeg" },
            text = "[Image Analysis: $fileName]\nDimensions: ${width}x${height}\nVisual Description & OCR:\n$visionDescription",
            sizeBytes = (imageBytes?.size ?: 0).toLong(),
            metadata = mapOf(
                "parser" to "ImageVisionParser",
                "width" to width.toString(),
                "height" to height.toString(),
                "descriptionSnippet" to visionDescription.take(200)
            )
        )
    }

    /**
     * Public method to run on-demand visual analysis with custom prompt.
     */
    suspend fun analyzeImageWithPrompt(
        context: Context,
        uri: Uri,
        userPrompt: String = "What is shown in this image? Describe objects and extract all readable text."
    ): String = withContext(Dispatchers.IO) {
        val apiKey = appSettings.geminiApiKey
        if (apiKey.isBlank()) {
            return@withContext "Gemini API key is not configured. Please set your API key in Settings."
        }

        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            return@withContext "Error loading image file: ${e.message}"
        } ?: return@withContext "Could not read image data."

        val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
        callGeminiVision(apiKey, base64Data, mime, userPrompt)
    }

    private fun callGeminiVision(
        apiKey: String,
        base64Data: String,
        mimeType: String,
        prompt: String
    ): String {
        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("inline_data", JSONObject().apply {
                                put("mime_type", mimeType)
                                put("data", base64Data)
                            })
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", 1024)
            })
        }

        val request = Request.Builder()
            .url("$VISION_API_URL?key=$apiKey")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val respBody = response.body?.string() ?: return "No response from vision service."

        if (!response.isSuccessful) {
            return "Vision API error: HTTP ${response.code}"
        }

        val json = JSONObject(respBody)
        val candidates = json.optJSONArray("candidates") ?: return "No analysis returned."
        if (candidates.length() > 0) {
            val content = candidates.getJSONObject(0).optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                return parts.getJSONObject(0).optString("text", "")
            }
        }
        return "Could not extract vision description."
    }
}
