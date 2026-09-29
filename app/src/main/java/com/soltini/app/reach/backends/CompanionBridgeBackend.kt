package com.soltini.app.reach.backends

import com.soltini.app.reach.model.HealthStatus
import com.soltini.app.reach.security.ReachCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * CompanionBridgeBackend
 *
 * Optional development / power-user bridge for Termux or desktop Agent Reach companion daemon.
 * If Termux runs `agent-reach serve --port=8765` or OpenCLI, MYRA can communicate over localhost.
 * If not running, MYRA smoothly uses its 100% native Android Kotlin backends.
 */
class CompanionBridgeBackend(
    private val httpClient: OkHttpClient,
    private val credentialStore: ReachCredentialStore
) : ReachBackend {
    override val id = "companion_bridge"
    override val name = "Termux / Local Companion Bridge"
    override val description = "Optional localhost bridge to external Agent Reach CLI or OpenCLI in Termux"

    override suspend fun probe(): Pair<HealthStatus, String> = withContext(Dispatchers.IO) {
        val bridgeUrl = credentialStore.getCompanionBridgeUrl()
        try {
            val req = Request.Builder()
                .url("$bridgeUrl/health")
                .header("User-Agent", "MyraAssistant/1.0")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    HealthStatus.READY to "Companion bridge connected at $bridgeUrl"
                } else {
                    HealthStatus.UNAVAILABLE to "Companion bridge returned HTTP ${resp.code} (Optional mode)"
                }
            }
        } catch (_: Exception) {
            HealthStatus.UNAVAILABLE to "Termux / Companion bridge not running (Native Android mode active)"
        }
    }
}
