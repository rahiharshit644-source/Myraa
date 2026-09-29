package com.soltini.app.reach.data

/**
 * ReachSessionState
 *
 * Holds in-memory session pointers for the latest discovered research items
 * so that subsequent commands like "jo result mila use Chrome mein kholkar dikhao"
 * can seamlessly open the target web page in Chrome.
 */
object ReachSessionState {
    @Volatile
    var lastResultUrl: String? = null

    @Volatile
    var lastResultTitle: String? = null

    @Volatile
    var lastResearchSummary: String? = null

    fun clear() {
        lastResultUrl = null
        lastResultTitle = null
        lastResearchSummary = null
    }
}
