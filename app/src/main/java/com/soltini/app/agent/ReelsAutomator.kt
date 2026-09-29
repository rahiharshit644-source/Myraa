package com.soltini.app.agent

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * ReelsAutomator
 *
 * Automates scrolling through short-form video feeds (Instagram Reels, YouTube Shorts, TikTok).
 * A reel scroll is a fast, full-screen vertical swipe.
 *
 * Tools:
 *  - next_reel
 *  - previous_reel
 */
class ReelsAutomator(private val context: Context) {

    companion object {
        private const val TAG = "ReelsAutomator"
    }

    /**
     * Swipes UP to go to the next reel.
     */
    fun nextReel(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        val screen = context.resources.displayMetrics
        val centerX = screen.widthPixels / 2f
        val bottomY = screen.heightPixels * 0.8f
        val topY = screen.heightPixels * 0.2f

        // Fast swipe up
        if (svc.swipe(centerX, bottomY, centerX, topY, durationMs = 150)) {
            return result("status", "scrolled_next")
        }
        return error("Swipe gesture failed")
    }

    /**
     * Swipes DOWN to go to the previous reel.
     */
    fun previousReel(): JSONObject {
        val svc = SoltiniAccessibilityService.getInstance()
            ?: return error("Accessibility Service not active")

        val screen = context.resources.displayMetrics
        val centerX = screen.widthPixels / 2f
        val topY = screen.heightPixels * 0.2f
        val bottomY = screen.heightPixels * 0.8f

        // Fast swipe down
        if (svc.swipe(centerX, topY, centerX, bottomY, durationMs = 150)) {
            return result("status", "scrolled_previous")
        }
        return error("Swipe gesture failed")
    }

    private fun result(vararg pairs: Any): JSONObject = JSONObject().apply {
        var i = 0
        while (i < pairs.size - 1) {
            put(pairs[i].toString(), pairs[i + 1])
            i += 2
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply { put("error", msg) }
}
