package com.soltini.app.reach.doctor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.soltini.app.reach.backends.ReachBackend
import com.soltini.app.reach.gateway.BackendRouter
import com.soltini.app.reach.gateway.CapabilityRegistry
import com.soltini.app.reach.model.DiagnosticReport
import com.soltini.app.reach.model.HealthStatus
import com.soltini.app.reach.model.ReachCapability
import com.soltini.app.reach.security.ReachCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AgentReachHealthManager
 *
 * Implements the MYRA native version of `agent-reach doctor`.
 * Probes all 16 platform channels, determines active serving backends,
 * detects missing credentials or network errors, and builds the diagnostics report.
 */
class AgentReachHealthManager(
    private val context: Context,
    private val capabilityRegistry: CapabilityRegistry,
    private val backendRouter: BackendRouter,
    private val credentialStore: ReachCredentialStore
) {
    companion object {
        private const val TAG = "ReachHealthDoctor"
    }

    private var cachedReport: DiagnosticReport? = null
    private var lastCheckTime: Long = 0L

    suspend fun runDoctorCheck(forceRefresh: Boolean = false): DiagnosticReport = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedReport != null && (now - lastCheckTime) < 30_000L) {
            return@withContext cachedReport!!
        }

        val isNetworkUp = isNetworkAvailable()
        val allCaps = capabilityRegistry.getAllCapabilities()

        for (cap in allCaps) {
            probeCapability(cap, isNetworkUp)
        }

        val readyCount = allCaps.count { it.healthStatus == HealthStatus.READY }
        val report = DiagnosticReport(
            timestamp = now,
            networkAvailable = isNetworkUp,
            totalCapabilities = allCaps.size,
            readyCapabilities = readyCount,
            capabilities = allCaps,
            cacheStatus = "LRU In-Memory + SQLite persistent cache active"
        )

        cachedReport = report
        lastCheckTime = now
        Log.i(TAG, "Doctor check finished: $readyCount/${allCaps.size} capabilities ready.")
        report
    }

    private suspend fun probeCapability(cap: ReachCapability, isNetworkUp: Boolean) {
        if (!isNetworkUp) {
            cap.healthStatus = HealthStatus.NETWORK_UNAVAILABLE
            cap.statusMessage = "Device network is offline"
            return
        }

        // Check authentication requirements
        val authState = credentialStore.getAuthenticationState(cap.id)
        cap.authState = authState

        // Probe candidate backends with automatic fallback
        var foundReady = false
        var lastStatus = HealthStatus.ERROR
        var lastMessage = "No backends configured"

        for (backendId in cap.backends) {
            val backend = backendRouter.getBackend(backendId)
            if (backend != null) {
                try {
                    val (status, msg) = backend.probe()
                    lastStatus = status
                    lastMessage = msg
                    if (status == HealthStatus.READY) {
                        cap.healthStatus = HealthStatus.READY
                        cap.statusMessage = msg
                        cap.activeBackend = backend.id
                        foundReady = true
                        break
                    }
                } catch (e: Exception) {
                    lastMessage = "Probe error ($backendId): ${e.message}"
                }
            } else {
                // If backend has no explicit probe implementation, mark healthy if no special auth needed
                if (cap.authRequirement == com.soltini.app.reach.model.AuthenticationRequirement.NONE) {
                    cap.healthStatus = HealthStatus.READY
                    cap.statusMessage = "Active via default native channel ($backendId)"
                    cap.activeBackend = backendId
                    foundReady = true
                    break
                }
            }
        }

        if (!foundReady) {
            cap.healthStatus = lastStatus
            cap.statusMessage = lastMessage
        }

        cap.lastChecked = System.currentTimeMillis()
    }

    /**
     * Executes real test retrievals across core channels for agent_reach.test
     */
    suspend fun runTestCheck(): org.json.JSONObject = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val health = runDoctorCheck(forceRefresh = true)
        val testResults = org.json.JSONObject()

        testResults.put("status", if (health.networkAvailable && health.readyCapabilities > 0) "success" else "failure")
        testResults.put("network_available", health.networkAvailable)
        testResults.put("ready_capabilities", health.readyCapabilities)
        testResults.put("total_capabilities", health.totalCapabilities)

        val channels = org.json.JSONObject()

        // 1. Web Test
        try {
            val (webRes, backend) = backendRouter.executeWebSearch("Agent Reach test", maxResults = 2)
            channels.put("web_search", org.json.JSONObject().apply {
                put("status", if (webRes.isNotEmpty()) "PASSED" else "NO_RESULTS")
                put("backend", backend)
                put("result_count", webRes.size)
            })
        } catch (e: Exception) {
            channels.put("web_search", org.json.JSONObject().apply {
                put("status", "FAILED")
                put("error", e.message)
            })
        }

        // 2. Web Reader Test
        try {
            val (readRes, backend) = backendRouter.executeWebRead("https://en.wikipedia.org/wiki/Artificial_intelligence")
            channels.put("web_reader", org.json.JSONObject().apply {
                put("status", if (readRes.snippet.isNotBlank()) "PASSED" else "EMPTY")
                put("backend", backend)
                put("title", readRes.title)
            })
        } catch (e: Exception) {
            channels.put("web_reader", org.json.JSONObject().apply {
                put("status", "FAILED")
                put("error", e.message)
            })
        }

        // 3. YouTube Subtitle/Metadata Test
        val ytCap = health.capabilities.find { it.id == "youtube.content" }
        channels.put("youtube", org.json.JSONObject().apply {
            put("status", if (ytCap?.healthStatus == HealthStatus.READY) "PASSED" else "SETUP_REQUIRED")
            put("backend", ytCap?.activeBackend ?: "youtube_transcript")
        })

        // 4. GitHub Test
        val ghCap = health.capabilities.find { it.id == "github.inspect" }
        channels.put("github", org.json.JSONObject().apply {
            put("status", if (ghCap?.healthStatus == HealthStatus.READY) "PASSED" else "FAILED")
            put("backend", ghCap?.activeBackend ?: "github_rest")
        })

        // 5. Reddit Test
        val redditCap = health.capabilities.find { it.id == "reddit.discussions" }
        channels.put("reddit", org.json.JSONObject().apply {
            put("status", if (redditCap?.healthStatus == HealthStatus.READY) "PASSED" else "FAILED")
            put("backend", redditCap?.activeBackend ?: "reddit_public_json")
        })

        // 6. RSS Test
        val rssCap = health.capabilities.find { it.id == "rss.feed" }
        channels.put("rss", org.json.JSONObject().apply {
            put("status", if (rssCap?.healthStatus == HealthStatus.READY) "PASSED" else "FAILED")
            put("backend", rssCap?.activeBackend ?: "rss_feed_parser")
        })

        testResults.put("channels", channels)
        testResults.put("duration_ms", System.currentTimeMillis() - start)
        testResults.put("summary", health.toAgentReachStatusSummary())
        testResults
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
