package com.soltini.app.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject

/**
 * MapsAutomator
 *
 * Unlike WhatsApp/Instagram automation, Maps navigation does NOT need fragile
 * accessibility-service UI clicking — Google Maps supports well-documented Intent
 * deep links, so these actions are essentially 100% reliable (no "find the right
 * on-screen button" guessing, no Thread.sleep waits, no wrong-element clicks).
 *
 * Tools:
 *  - maps_navigate   -> turn-by-turn navigation to a destination
 *  - maps_search     -> show a place/search query on the map (no route)
 */
class MapsAutomator(private val context: Context) {

    companion object {
        private const val TAG = "MapsAutomator"
        private const val MAPS_PKG = "com.google.android.apps.maps"
    }

    /**
     * Starts turn-by-turn navigation to [destination] (address, place name, or "lat,lng").
     * [mode] can be "driving" (d), "walking" (w), "bicycling" (b), or "transit" (r).
     */
    fun navigate(destination: String, mode: String = "driving"): JSONObject {
        if (destination.isBlank()) return error("destination is required")

        val travelMode = when (mode.lowercase()) {
            "walking", "walk" -> "w"
            "bicycling", "bike", "cycling" -> "b"
            "transit", "public_transport", "bus", "train" -> "r"
            else -> "d" // driving is the default
        }

        return try {
            val uri = Uri.parse(
                "google.navigation:q=${Uri.encode(destination)}&mode=$travelMode"
            )
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(MAPS_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.i(TAG, "navigate('$destination', mode=$travelMode) -> launched Maps navigation")
                result("status", "navigating", "destination", destination, "mode", mode)
            } else {
                // Fallback: Maps app not installed, open in browser instead
                openInBrowserFallback(destination)
            }
        } catch (e: Exception) {
            Log.e(TAG, "navigate failed: ${e.message}", e)
            error("Could not start navigation: ${e.message}")
        }
    }

    /**
     * Shows [query] (a place, business, or address) on the map without starting navigation.
     */
    fun searchPlace(query: String): JSONObject {
        if (query.isBlank()) return error("query is required")

        return try {
            val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage(MAPS_PKG)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                Log.i(TAG, "searchPlace('$query') -> launched Maps search")
                result("status", "showing", "query", query)
            } else {
                openInBrowserFallback(query)
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchPlace failed: ${e.message}", e)
            error("Could not search location: ${e.message}")
        }
    }

    private fun openInBrowserFallback(query: String): JSONObject {
        return try {
            val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, webUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.w(TAG, "Google Maps app not installed, opened '$query' in browser instead")
            result("status", "showing_in_browser", "query", query, "note", "Maps app not installed, opened in browser")
        } catch (e: Exception) {
            error("Google Maps is not installed and browser fallback failed: ${e.message}")
        }
    }

    private fun result(vararg pairs: Any): JSONObject = JSONObject().apply {
        var i = 0
        while (i < pairs.size - 1) {
            put(pairs[i].toString(), pairs[i + 1])
            i += 2
        }
    }

    private fun error(msg: String): JSONObject = JSONObject().apply { put("status", "error"); put("error", msg) }
}
