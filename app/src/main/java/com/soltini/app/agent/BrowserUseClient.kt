package com.soltini.app.agent

import android.content.Context
import android.util.Log
import com.soltini.app.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * BrowserUseClient
 *
 * Talks to a small bridge server (see soltini_bridge_server.py, shipped
 * alongside the browser-use repo you provided) that wraps the *unmodified*
 * browser_use.Agent. The library itself is Python + Playwright and needs a
 * real Chromium process, which can't run inside an Android APK — so instead
 * of faking it on-device, this client sends the task to that server (run on
 * a PC/laptop/VPS on the same network, or reachable via a tunnel URL) and
 * relays back whatever the real Browser-Use agent actually did.
 *
 * Configure the server's address in Settings -> "Browser-Use Server URL"
 * (e.g. http://192.168.1.23:8787). If it's blank, this tool simply reports
 * that no server is configured instead of pretending to do anything.
 */
class BrowserUseClient(private val context: Context) {

    companion object {
        private const val TAG = "BrowserUseClient"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Browser-Use tasks can genuinely take a while (multi-step web navigation) — give it room.
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Sends [task] to the configured bridge server's /run-task endpoint and
     * returns a result JSON with "status" ("success" | "error") and "message".
     */
    suspend fun runTask(task: String): JSONObject = withContext(Dispatchers.IO) {
        val appSettings = AppSettings(context)
        val serverUrl = appSettings.browserUseServerUrl.trim().trimEnd('/')

        if (serverUrl.isBlank()) {
            return@withContext JSONObject().apply {
                put("status", "error")
                put(
                    "message",
                    "No Browser-Use server configured. Run soltini_bridge_server.py on a PC/VPS " +
                        "with browser-use + Playwright installed, then set its URL in Settings -> Browser-Use Server URL."
                )
            }
        }

        try {
            val body = JSONObject().apply { put("task", task) }
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val request = Request.Builder()
                .url("$serverUrl/run-task")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Bridge server returned HTTP ${response.code}: $responseText")
                    return@withContext JSONObject().apply {
                        put("status", "error")
                        put("message", "Browser-Use server error (HTTP ${response.code}): $responseText")
                    }
                }

                val json = JSONObject(responseText)
                val success = json.optBoolean("success", false)
                val result = json.optString("result", "")
                JSONObject().apply {
                    put("status", if (success) "success" else "error")
                    put("message", result.ifBlank { "Browser-Use task finished with no message." })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reach Browser-Use bridge server: ${e.message}", e)
            JSONObject().apply {
                put("status", "error")
                put(
                    "message",
                    "Couldn't reach the Browser-Use server at $serverUrl (${e.message}). " +
                        "Make sure it's running and the phone can reach that address."
                )
            }
        }
    }
}
