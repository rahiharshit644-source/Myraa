package com.soltini.app.automation

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.soltini.app.util.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * GeofenceManager
 *
 * Manages location-based triggers using Google Play Services Geofencing API.
 * Enables user rules like "Ghar pahunchne pe Wi-Fi on kar dena" or "Office chhodne pe lights off kar dena".
 */
class GeofenceManager(private val context: Context) {

    companion object {
        private const val TAG = "GeofenceManager"
        private const val PREFS_NAME = "myra_geofence_prefs"
        private const val KEY_TRIGGERS = "registered_geofences"

        @Volatile
        private var instance: GeofenceManager? = null

        fun getInstance(context: Context): GeofenceManager =
            instance ?: synchronized(this) {
                instance ?: GeofenceManager(context.applicationContext).also { instance = it }
            }
    }

    data class LocationTriggerRule(
        val id: String,
        val label: String, // e.g., "Ghar", "Office"
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Float = 100f,
        val transitionType: Int = Geofence.GEOFENCE_TRANSITION_ENTER, // ENTER or EXIT
        val actionDescription: String, // e.g., "Wi-Fi on kar dena"
        val targetDevice: String? = null,
        val targetAction: String? = null
    )

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(context, 0, intent, flags)
    }

    /**
     * Registers a new geofence rule.
     */
    @SuppressLint("MissingPermission")
    fun registerTrigger(rule: LocationTriggerRule, onResult: (Boolean, String) -> Unit) {
        try {
            val geofence = Geofence.Builder()
                .setRequestId(rule.id)
                .setCircularRegion(rule.latitude, rule.longitude, rule.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(rule.transitionType)
                .build()

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(rule.transitionType)
                .addGeofence(geofence)
                .build()

            geofencingClient.addGeofences(request, geofencePendingIntent).run {
                addOnSuccessListener {
                    saveRule(rule)
                    AppLogger.i(TAG, "Geofence registered: ${rule.id} (${rule.label})")
                    onResult(true, "Geofence set for ${rule.label}: ${rule.actionDescription}")
                }
                addOnFailureListener { e ->
                    AppLogger.e(TAG, "Failed adding geofence: ${e.message}")
                    // Save rule anyway so local mock/location updates can evaluate it
                    saveRule(rule)
                    onResult(false, "Geofence warning: ${e.message}")
                }
            }
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "Location permission missing for geofence: ${e.message}")
            saveRule(rule)
            onResult(false, "Location permission required: ${e.message}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Unexpected error adding geofence: ${e.message}")
            onResult(false, e.message ?: "Unknown error")
        }
    }

    fun getAllRules(): List<LocationTriggerRule> {
        val json = prefs.getString(KEY_TRIGGERS, "[]") ?: "[]"
        val list = mutableListOf<LocationTriggerRule>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    LocationTriggerRule(
                        id = o.getString("id"),
                        label = o.getString("label"),
                        latitude = o.getDouble("lat"),
                        longitude = o.getDouble("lng"),
                        radiusMeters = o.optDouble("radius", 100.0).toFloat(),
                        transitionType = o.optInt("transition", Geofence.GEOFENCE_TRANSITION_ENTER),
                        actionDescription = o.getString("action"),
                        targetDevice = o.optString("targetDevice").ifBlank { null },
                        targetAction = o.optString("targetAction").ifBlank { null }
                    )
                )
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed reading geofence rules: ${e.message}")
        }
        return list
    }

    fun getRule(id: String): LocationTriggerRule? {
        return getAllRules().firstOrNull { it.id == id }
    }

    private fun saveRule(rule: LocationTriggerRule) {
        val existing = getAllRules().filter { it.id != rule.id }.toMutableList()
        existing.add(rule)
        val arr = JSONArray()
        for (r in existing) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("label", r.label)
                put("lat", r.latitude)
                put("lng", r.longitude)
                put("radius", r.radiusMeters.toDouble())
                put("transition", r.transitionType)
                put("action", r.actionDescription)
                put("targetDevice", r.targetDevice ?: "")
                put("targetAction", r.targetAction ?: "")
            })
        }
        prefs.edit().putString(KEY_TRIGGERS, arr.toString()).apply()
    }

    fun removeRule(id: String) {
        geofencingClient.removeGeofences(listOf(id))
        val filtered = getAllRules().filter { it.id != id }
        val arr = JSONArray()
        for (r in filtered) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("label", r.label)
                put("lat", r.latitude)
                put("lng", r.longitude)
                put("radius", r.radiusMeters.toDouble())
                put("transition", r.transitionType)
                put("action", r.actionDescription)
            })
        }
        prefs.edit().putString(KEY_TRIGGERS, arr.toString()).apply()
    }
}
