package com.soltini.app.reach.backends

import com.soltini.app.reach.model.HealthStatus

/**
 * ReachBackend
 *
 * Base interface for every upstream platform tool or API backend.
 * Provides live capability health probing and execution.
 */
interface ReachBackend {
    val id: String
    val name: String
    val description: String

    /**
     * Checks if this backend is healthy and reachable.
     * Returns Pair(HealthStatus, StatusExplanation).
     */
    suspend fun probe(): Pair<HealthStatus, String>
}
