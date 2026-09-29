package com.soltini.app.reach.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * HealthStatus
 *
 * Maps to Agent Reach doctor statuses:
 * READY (ok), SETUP_REQUIRED (warn), UNAVAILABLE / OFF (off), ERROR (error).
 */
enum class HealthStatus {
    READY,
    SETUP_REQUIRED,
    UNAVAILABLE,
    DEPENDENCY_MISSING,
    RATE_LIMITED,
    NETWORK_UNAVAILABLE,
    ERROR
}

/**
 * AuthenticationRequirement
 */
enum class AuthenticationRequirement {
    NONE,                   // Zero-config (Jina, DDG, Public Reddit/V2EX/Wikipedia)
    FREE_KEY,               // Needs free API key (Exa, Groq, GitHub Token)
    SESSION_CREDENTIAL,     // Needs session credentials / cookie
    OAUTH_TOKEN,            // Standard OAuth / bearer token
    COMPANION_REQUIRED      // Requires Termux / desktop companion (OpenCLI)
}

/**
 * AuthenticationState
 */
enum class AuthenticationState {
    NOT_REQUIRED,
    NOT_CONFIGURED,
    CONFIGURED,
    EXPIRED,
    FAILED,
    REQUIRES_USER_ACTION
}

/**
 * PermissionLevel
 */
enum class PermissionLevel {
    READ_ONLY,
    LOW_RISK,
    EXTERNAL_ACTION,
    SENSITIVE
}

/**
 * BackendCandidate
 */
data class BackendCandidate(
    val id: String,
    val name: String,
    val description: String,
    val isPrimary: Boolean = false,
    val requiresKey: Boolean = false,
    val requiresCompanion: Boolean = false
)

/**
 * ReachCapability
 *
 * Structured registry entry for an internet research capability.
 */
data class ReachCapability(
    val id: String,
    val name: String,
    val description: String,
    val platform: ResearchPlatform,
    val tier: Int, // 0 = zero-config, 1 = needs free key / login, 2 = complex setup
    val supportedOperations: List<String>,
    val backends: List<String>,
    var activeBackend: String? = null,
    val authRequirement: AuthenticationRequirement = AuthenticationRequirement.NONE,
    var authState: AuthenticationState = AuthenticationState.NOT_REQUIRED,
    var healthStatus: HealthStatus = HealthStatus.READY,
    var statusMessage: String = "",
    val permissionLevel: PermissionLevel = PermissionLevel.READ_ONLY,
    var lastChecked: Long = 0L,
    var reliabilityScore: Float = 1.0f
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("platform", platform.name)
        put("tier", tier)
        put("operations", JSONArray(supportedOperations))
        put("backends", JSONArray(backends))
        activeBackend?.let { put("active_backend", it) }
        put("auth_requirement", authRequirement.name)
        put("auth_state", authState.name)
        put("health_status", healthStatus.name)
        put("status_message", statusMessage)
        put("permission_level", permissionLevel.name)
        put("last_checked", lastChecked)
        put("reliability_score", reliabilityScore)
    }
}

/**
 * DiagnosticReport
 *
 * Formatted health report modeled after `agent-reach doctor`.
 */
data class DiagnosticReport(
    val timestamp: Long = System.currentTimeMillis(),
    val networkAvailable: Boolean,
    val totalCapabilities: Int,
    val readyCapabilities: Int,
    val capabilities: List<ReachCapability>,
    val cacheStatus: String = "In-memory LRU + Disk cache active",
    val version: String = "1.0.0 (Agent Reach Engine)"
) {
    fun toFormattedReport(): String = buildString {
        appendLine("MYRA INTERNET CAPABILITIES REPORT")
        appendLine("=".repeat(45))
        appendLine("Legend: [READY] Available  [!] Setup required  [X] Unavailable")
        appendLine()

        val tier0 = capabilities.filter { it.tier == 0 }
        val tier1 = capabilities.filter { it.tier == 1 }
        val tier2 = capabilities.filter { it.tier == 2 }

        appendLine("--- ZERO-CONFIG CAPABILITIES (Ready to use) ---")
        for (c in tier0) {
            val badge = when (c.healthStatus) {
                HealthStatus.READY -> "[READY]"
                HealthStatus.SETUP_REQUIRED -> "[!]"
                else -> "[X]"
            }
            val activeStr = c.activeBackend?.let { " (Active: $it)" } ?: ""
            appendLine("  $badge ${c.name} - ${c.statusMessage}$activeStr")
        }

        if (tier1.isNotEmpty()) {
            appendLine()
            appendLine("--- OPTIONAL / KEY-SUPPORTED CHANNELS ---")
            for (c in tier1) {
                val badge = when (c.healthStatus) {
                    HealthStatus.READY -> "[READY]"
                    HealthStatus.SETUP_REQUIRED -> "[!]"
                    else -> "[X]"
                }
                val activeStr = c.activeBackend?.let { " (Active: $it)" } ?: ""
                appendLine("  $badge ${c.name} - ${c.statusMessage}$activeStr")
            }
        }

        if (tier2.isNotEmpty()) {
            appendLine()
            appendLine("--- ADVANCED / COMPANION CHANNELS ---")
            for (c in tier2) {
                val badge = when (c.healthStatus) {
                    HealthStatus.READY -> "[READY]"
                    HealthStatus.SETUP_REQUIRED -> "[!]"
                    else -> "[X]"
                }
                val activeStr = c.activeBackend?.let { " (Active: $it)" } ?: ""
                appendLine("  $badge ${c.name} - ${c.statusMessage}$activeStr")
            }
        }

        appendLine()
        appendLine("Status: $readyCapabilities/$totalCapabilities capabilities available")
        appendLine("Network: ${if (networkAvailable) "Connected" else "Offline"}")
        appendLine("Cache: $cacheStatus")
        appendLine("Security Boundary: Untrusted external content tagging active (SSRF defense enabled)")
    }

    fun toAgentReachStatusSummary(): String = buildString {
        if (!networkAvailable) {
            appendLine("Agent Reach:")
            appendLine("UNAVAILABLE")
            appendLine()
            appendLine("Runtime:")
            appendLine("ERROR")
            appendLine()
            appendLine("Reason:")
            appendLine("Device network is offline (Mobile Data / Wi-Fi disconnected)")
            appendLine()
            appendLine("Required action:")
            appendLine("Please turn on Mobile Data or connect to Wi-Fi to use Agent Reach.")
            return@buildString
        }

        val webReady = capabilities.find { it.id == "web.search" }?.healthStatus == HealthStatus.READY
        val ytReady = capabilities.find { it.id == "youtube.content" }?.healthStatus == HealthStatus.READY
        val ghReady = capabilities.find { it.id == "github.inspect" }?.healthStatus == HealthStatus.READY
        val redditReady = capabilities.find { it.id == "reddit.discussions" }?.healthStatus == HealthStatus.READY
        val rssReady = capabilities.find { it.id == "rss.feed" }?.healthStatus == HealthStatus.READY

        val isAnyReady = readyCapabilities > 0

        if (isAnyReady) {
            appendLine("Agent Reach:")
            appendLine("CONNECTED")
            appendLine()
            appendLine("Runtime:")
            appendLine("READY")
            appendLine()
            appendLine("Web:")
            appendLine(if (webReady) "READY" else "UNAVAILABLE")
            appendLine()
            appendLine("YouTube:")
            appendLine(if (ytReady) "READY" else "UNAVAILABLE")
            appendLine()
            appendLine("GitHub:")
            appendLine(if (ghReady) "READY" else "UNAVAILABLE")
            appendLine()
            appendLine("Reddit:")
            appendLine(if (redditReady) "READY" else "UNAVAILABLE")
            appendLine()
            appendLine("RSS:")
            appendLine(if (rssReady) "READY" else "UNAVAILABLE")
        } else {
            appendLine("Status:")
            appendLine("UNAVAILABLE")
            appendLine()
            appendLine("Reason:")
            appendLine("All candidate search and retrieval backends returned an error or were blocked.")
            appendLine()
            appendLine("Required action:")
            appendLine("Verify internet connectivity, check firewall/proxy, or configure Exa/Groq keys in Settings.")
        }
    }

    fun toStatusJsonObject(): JSONObject = JSONObject().apply {
        val isConnected = networkAvailable && readyCapabilities > 0
        put("agent_reach", if (isConnected) "CONNECTED" else "UNAVAILABLE")
        put("runtime", if (networkAvailable && isConnected) "READY" else "ERROR")
        val webCap = capabilities.find { it.id == "web.search" }
        val readerCap = capabilities.find { it.id == "web.read" }
        val ytCap = capabilities.find { it.id == "youtube.content" }
        val ghCap = capabilities.find { it.id == "github.inspect" }
        val redditCap = capabilities.find { it.id == "reddit.discussions" }
        val rssCap = capabilities.find { it.id == "rss.feed" }

        put("web", if (webCap?.healthStatus == HealthStatus.READY) "READY" else "UNAVAILABLE")
        put("web_search", if (webCap?.healthStatus == HealthStatus.READY) "READY" else "UNAVAILABLE")
        put("web_reader", if (readerCap?.healthStatus == HealthStatus.READY) "READY" else "UNAVAILABLE")
        put("youtube", if (ytCap?.healthStatus == HealthStatus.READY) "READY" else "UNAVAILABLE")
        put("github", if (ghCap?.healthStatus == HealthStatus.READY) "READY" else "UNAVAILABLE")
        put("reddit", if (redditCap?.healthStatus == HealthStatus.READY) "READY" else "UNAVAILABLE")
        put("rss", if (rssCap?.healthStatus == HealthStatus.READY) "READY" else "UNAVAILABLE")
        put("browser", "READY")
        put("network_available", networkAvailable)
        put("ready_capabilities", readyCapabilities)
        put("total_capabilities", totalCapabilities)
        put("summary", toAgentReachStatusSummary())
    }
}
