package com.soltini.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import com.soltini.app.agent.SoltiniAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * VisualScreenAnalyzer
 *
 * Hybrid Vision Engine:
 * Combines Android Accessibility Text Tree with Real Visual Screen Bitmap Capture.
 *
 * Solves the critical limitations of text-only screen inspection:
 * 1. Sees images, product photos, memes, video thumbnails, graphs, and banners.
 * 2. Recognizes unlabeled icon buttons (e.g., cart, heart, thumbs up, share, mic, back, search, 3-dots).
 * 3. Understands visual aesthetics, colors, layouts, and rich graphical screens (Canvases, Flutter, WebViews).
 * 4. Feeds real-time image frames to Gemini Live & Gemini Vision models.
 */
class VisualScreenAnalyzer private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VisualScreenAnalyzer"
        private const val VISION_MODEL = "gemini-2.5-flash"
        private const val FALLBACK_VISION_MODEL = "gemini-2.0-flash"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        @Volatile
        private var instance: VisualScreenAnalyzer? = null

        fun getInstance(context: Context): VisualScreenAnalyzer {
            return instance ?: synchronized(this) {
                instance ?: VisualScreenAnalyzer(context.applicationContext).also { instance = it }
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    data class VisualAnalysisResult(
        val isVisualAvailable: Boolean,
        val visualSummary: String = "",
        val detectedIcons: List<String> = emptyList(),
        val directAnswerToQuery: String = "",
        val base64Jpeg: String? = null,
        val error: String? = null
    )

    /**
     * Captures the current physical screen buffer, resizes to a lightweight format (< 100KB),
     * and encodes as Base64 JPEG.
     */
    suspend fun captureScreenAsBase64(maxDimension: Int = 1024, quality: Int = 75): Pair<Bitmap?, String?> = withContext(Dispatchers.Default) {
        val a11y = SoltiniAccessibilityService.getInstance()
        if (a11y == null) {
            Log.w(TAG, "Accessibility service not running, cannot capture screen")
            return@withContext Pair(null, null)
        }

        val rawBitmap = a11y.takeScreenshotBitmap() ?: run {
            Log.w(TAG, "takeScreenshotBitmap returned null")
            return@withContext Pair(null, null)
        }

        try {
            val width = rawBitmap.width
            val height = rawBitmap.height

            val scaledBitmap = if (width > maxDimension || height > maxDimension) {
                val scale = maxDimension.toFloat() / maxOf(width, height)
                val matrix = Matrix().apply { postScale(scale, scale) }
                val scaled = Bitmap.createBitmap(rawBitmap, 0, 0, width, height, matrix, true)
                if (scaled != rawBitmap) {
                    rawBitmap.recycle()
                }
                scaled
            } else {
                rawBitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val byteArray = outputStream.toByteArray()
            val base64String = Base64.encodeToString(byteArray, Base64.NO_WRAP)

            Log.i(TAG, "Screen captured: ${scaledBitmap.width}x${scaledBitmap.height}, JPEG size: ${byteArray.size / 1024} KB")
            return@withContext Pair(scaledBitmap, base64String)
        } catch (e: Exception) {
            Log.e(TAG, "Error encoding screen bitmap: ${e.message}", e)
            try { rawBitmap.recycle() } catch (_: Exception) {}
            return@withContext Pair(null, null)
        }
    }

    /**
     * Conducts deep multimodal inspection of the screen by combining the real screenshot image
     * with the accessibility text tree.
     */
    suspend fun analyzeScreen(
        apiKey: String,
        focusQuery: String? = null,
        textTreeContext: String? = null
    ): VisualAnalysisResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext VisualAnalysisResult(
                isVisualAvailable = false,
                error = "API key missing for Vision analysis."
            )
        }

        val (_, base64Jpeg) = captureScreenAsBase64(maxDimension = 1024, quality = 75)

        if (base64Jpeg.isNullOrBlank()) {
            return@withContext VisualAnalysisResult(
                isVisualAvailable = false,
                error = "Device screenshot could not be captured (requires Android 11+ and Accessibility permission)."
            )
        }

        val promptText = buildString {
            append("You are the visual perception eye for Myra, an AI phone assistant.\n")
            append("Analyze this exact screenshot of Boss's Android phone screen.\n\n")

            if (!textTreeContext.isNullOrBlank()) {
                append("=== ACCESSIBILITY TEXT EXTRACTED FROM SCREEN ===\n")
                append(textTreeContext.take(1500))
                append("\n================================================\n\n")
            }

            if (!focusQuery.isNullOrBlank()) {
                append("USER'S SPECIFIC QUESTION OR FOCUS: \"$focusQuery\"\n\n")
            } else {
                append("Describe the screen's main content, visual state, images, and any prominent icons.\n\n")
            }

            append("""
INSTRUCTIONS:
1. Identify the active app, website, or view.
2. Note visual items that text cannot convey (photos, colors, memes, graphs, product pictures, video thumbnails, status indicators).
3. Identify prominent clickable icons (e.g. search, cart, back, profile, settings, mic, heart, send) with approximate position (e.g. top right, bottom center).
4. If the user asked a question, answer it directly based on visual evidence.
Keep response concise, conversational, and direct (max 4-5 key bullet points).
            """.trimIndent())
        }

        val requestBody = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        // Image part
                        put(JSONObject().apply {
                            put("inlineData", JSONObject().apply {
                                put("mimeType", "image/jpeg")
                                put("data", base64Jpeg)
                            })
                        })
                        // Prompt text part
                        put(JSONObject().apply {
                            put("text", promptText)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 500)
            })
        }

        val responseJson = try {
            callVisionApi(VISION_MODEL, apiKey, requestBody)
        } catch (e: Exception) {
            Log.w(TAG, "Primary vision model failed: ${e.message}, attempting fallback $FALLBACK_VISION_MODEL")
            try {
                callVisionApi(FALLBACK_VISION_MODEL, apiKey, requestBody)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback vision model failed: ${e2.message}", e2)
                return@withContext VisualAnalysisResult(
                    isVisualAvailable = true,
                    base64Jpeg = base64Jpeg,
                    error = "Vision model error: ${e2.message}"
                )
            }
        }

        val candidate = responseJson.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        val fullText = buildString {
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    if (part.has("text")) {
                        append(part.getString("text")).append(" ")
                    }
                }
            }
        }.trim()

        Log.i(TAG, "Visual analysis complete: ${fullText.take(120)}...")

        return@withContext VisualAnalysisResult(
            isVisualAvailable = true,
            visualSummary = fullText,
            base64Jpeg = base64Jpeg
        )
    }

    private fun callVisionApi(model: String, apiKey: String, body: JSONObject): JSONObject {
        val url = "$BASE_URL/$model:generateContent?key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw RuntimeException("Gemini Vision HTTP ${response.code}: $responseBody")
            }
            return JSONObject(responseBody)
        }
    }
}
