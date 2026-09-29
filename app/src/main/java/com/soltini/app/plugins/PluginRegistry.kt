package com.soltini.app.plugins

import android.content.Context
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plugin
 *
 * Base interface for every capability registered into MYRA's dynamic capability architecture.
 */
interface Plugin {
    val metadata: PluginMetadata

    /**
     * Executes the capability with the supplied context.
     */
    suspend fun execute(context: PluginContext): PluginResult

    /**
     * Checks if runtime prerequisites (e.g. accessibility, internet, camera) are met.
     */
    fun isAvailable(context: Context): Boolean = true
}

/**
 * PluginCandidate
 *
 * Scored candidate returned during dynamic capability discovery.
 */
data class PluginCandidate(
    val plugin: Plugin,
    val score: Float,
    val matchReason: String,
    val reliability: Float
)

/**
 * PluginStats
 *
 * Tracks execution count, success rate, and average latency for autonomous selection.
 */
data class PluginStats(
    val callCount: AtomicInteger = AtomicInteger(0),
    val successCount: AtomicInteger = AtomicInteger(0),
    val failCount: AtomicInteger = AtomicInteger(0),
    var totalLatencyMs: Long = 0L
) {
    val reliability: Float
        get() {
            val total = callCount.get()
            if (total == 0) return 0.95f // optimistic default for new plugins
            return successCount.get().toFloat() / total.toFloat()
        }
}

/**
 * PluginRegistry
 *
 * Central, dynamic capability system. Holds all tools, APIs, services, and specialized agents.
 * Supports runtime registration, dynamic intent matching, capability fallback, and telemetry.
 */
class PluginRegistry private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PluginRegistry"

        @Volatile
        private var instance: PluginRegistry? = null

        fun getInstance(context: Context): PluginRegistry =
            instance ?: synchronized(this) {
                instance ?: PluginRegistry(context.applicationContext).also { instance = it }
            }
    }

    private val plugins = ConcurrentHashMap<String, Plugin>()
    private val stats = ConcurrentHashMap<String, PluginStats>()
    private val registryMutex = Mutex()

    /**
     * Register a new plugin into the active registry.
     */
    fun registerPlugin(plugin: Plugin) {
        val id = plugin.metadata.id
        plugins[id] = plugin
        stats.putIfAbsent(id, PluginStats())
        Log.d(TAG, "Registered capability: [$id] - ${plugin.metadata.name} (${plugin.metadata.category})")
    }

    /**
     * Unregister a plugin by ID.
     */
    fun unregisterPlugin(id: String) {
        plugins.remove(id)
        stats.remove(id)
        Log.d(TAG, "Unregistered capability: [$id]")
    }

    /**
     * Retrieves a registered plugin by ID.
     */
    fun getPlugin(id: String): Plugin? = plugins[id]

    /**
     * Retrieves all registered plugins.
     */
    fun getAllPlugins(): List<Plugin> = plugins.values.toList()

    /**
     * Retrieves all registered capabilities as a JSON structure for Gemini/Orchestrator prompts.
     */
    fun getCapabilitiesManifest(): JSONArray {
        val arr = JSONArray()
        for (plugin in plugins.values) {
            val meta = plugin.metadata
            val st = stats[meta.id]
            val obj = meta.toJsonObject().apply {
                put("available", plugin.isAvailable(context))
                put("reliability", String.format("%.2f", st?.reliability ?: 0.95f))
            }
            arr.put(obj)
        }
        return arr
    }

    /**
     * Record execution metrics for autonomous selection.
     */
    fun recordExecution(pluginId: String, isSuccess: Boolean, latencyMs: Long) {
        val st = stats.getOrPut(pluginId) { PluginStats() }
        st.callCount.incrementAndGet()
        if (isSuccess) {
            st.successCount.incrementAndGet()
        } else {
            st.failCount.incrementAndGet()
        }
        synchronized(st) {
            st.totalLatencyMs += latencyMs
        }
    }

    /**
     * Dynamically finds the best matching plugins for a task description or intent.
     */
    fun findCandidatesForTask(
        taskDescription: String,
        categoryFilter: PluginCategory? = null,
        limit: Int = 5
    ): List<PluginCandidate> {
        val queryTokens = taskDescription.lowercase()
            .split(" ", "_", "-", ",", ".")
            .filter { it.length > 2 }

        val candidates = mutableListOf<PluginCandidate>()

        for (plugin in plugins.values) {
            val meta = plugin.metadata
            if (categoryFilter != null && meta.category != categoryFilter) {
                continue
            }

            if (!plugin.isAvailable(context)) {
                continue
            }

            var matchScore = 0f
            val matchReasons = mutableListOf<String>()

            val nameLower = meta.name.lowercase()
            val descLower = meta.description.lowercase()
            val tagsLower = meta.tags.map { it.lowercase() }
            val idLower = meta.id.lowercase()

            for (token in queryTokens) {
                if (idLower.contains(token)) {
                    matchScore += 30f
                    matchReasons.add("id match ($token)")
                }
                if (nameLower.contains(token)) {
                    matchScore += 25f
                    matchReasons.add("name match ($token)")
                }
                if (tagsLower.any { it.contains(token) }) {
                    matchScore += 20f
                    matchReasons.add("tag match ($token)")
                }
                if (descLower.contains(token)) {
                    matchScore += 10f
                    matchReasons.add("description match ($token)")
                }
            }

            // Factor in reliability score
            val st = stats[meta.id]
            val reliability = st?.reliability ?: 0.95f
            val finalScore = (matchScore * (0.5f + (reliability * 0.5f)))

            if (finalScore > 5f) {
                candidates.add(
                    PluginCandidate(
                        plugin = plugin,
                        score = finalScore,
                        matchReason = matchReasons.distinct().take(3).joinToString(", "),
                        reliability = reliability
                    )
                )
            }
        }

        return candidates.sortedByDescending { it.score }.take(limit)
    }

    /**
     * Resolves a viable fallback plugin for a failed plugin.
     */
    fun findFallbackPlugin(failedPluginId: String): Plugin? {
        val failed = plugins[failedPluginId] ?: return null
        // 1. Explicit configured fallback ID
        failed.metadata.fallbackPluginId?.let { fbId ->
            val explicit = plugins[fbId]
            if (explicit != null && explicit.isAvailable(context)) {
                return explicit
            }
        }

        // 2. Discover alternative candidate in same category with highest reliability
        return plugins.values
            .filter { it.metadata.id != failedPluginId && it.metadata.category == failed.metadata.category && it.isAvailable(context) }
            .maxByOrNull { stats[it.metadata.id]?.reliability ?: 0.9f }
    }
}
