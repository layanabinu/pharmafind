package com.example.pharmafind

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.ActivityCompat
import android.content.pm.PackageManager
import android.Manifest
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * GeofenceManager
 * ───────────────
 * Registers geofences around nearby pharmacies.
 * When the user enters a pharmacy's radius, GeofenceBroadcastReceiver fires
 * and shows a notification if the pharmacy stocks any of the user's medicines.
 *
 * Radius: 200 metres — close enough to be walking distance, not too spammy
 */
class GeofenceManager(private val context: Context) {

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    companion object {
        const val GEOFENCE_RADIUS_METRES = 200f   // 200m radius per pharmacy
        const val GEOFENCE_LOITERING_DELAY = 0    // trigger immediately on entry
        const val PREFS_KEY_GEOFENCE_IDS = "registered_geofence_ids"
    }

    // ─────────────────────────────────────────────
    // REGISTER geofences for a list of pharmacies
    // Called after loadNearbyPharmacies() in HomeActivity
    // ─────────────────────────────────────────────

    fun registerPharmacyGeofences(pharmacies: List<Pharmacy>) {
        if (!hasPermission()) {
            Log.w("GeofenceManager", "Missing location permission — skipping geofences")
            return
        }

        if (pharmacies.isEmpty()) return

        val geofences = pharmacies.map { pharmacy ->
            Geofence.Builder()
                .setRequestId(buildGeofenceId(pharmacy))
                .setCircularRegion(
                    pharmacy.lat,
                    pharmacy.lng,
                    GEOFENCE_RADIUS_METRES
                )
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .setLoiteringDelay(GEOFENCE_LOITERING_DELAY)
                .build()
        }

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        // Save registered IDs so we can remove them later
        saveGeofenceIds(pharmacies.map { buildGeofenceId(it) })

        geofencingClient.addGeofences(request, pendingIntent)
            .addOnSuccessListener {
                Log.d("GeofenceManager", "✅ Registered ${geofences.size} pharmacy geofences")
            }
            .addOnFailureListener { e ->
                Log.e("GeofenceManager", "❌ Geofence registration failed: ${e.message}")
            }
    }

    // ─────────────────────────────────────────────
    // REMOVE all registered geofences
    // Called when user logs out or in onDestroy
    // ─────────────────────────────────────────────

    fun removeAllGeofences() {
        val savedIds = getSavedGeofenceIds()
        if (savedIds.isEmpty()) return

        geofencingClient.removeGeofences(savedIds)
            .addOnSuccessListener {
                Log.d("GeofenceManager", "✅ Removed ${savedIds.size} geofences")
                clearSavedGeofenceIds()
            }
            .addOnFailureListener { e ->
                Log.e("GeofenceManager", "❌ Geofence removal failed: ${e.message}")
            }
    }

    // ─────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────

    private fun buildGeofenceId(pharmacy: Pharmacy): String {
        // Format: "geo_<id>_<name>" — readable in logs
        return "geo_${pharmacy.id}_${pharmacy.name.replace(" ", "_")}"
    }

    fun extractPharmacyIdFromGeofenceId(geofenceId: String): Int? {
        // "geo_5_Apollo_Pharmacy" → 5
        return try {
            geofenceId.split("_")[1].toInt()
        } catch (e: Exception) {
            null
        }
    }

    private fun hasPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun saveGeofenceIds(ids: List<String>) {
        context.getSharedPreferences("PharmaFindPrefs", Context.MODE_PRIVATE)
            .edit()
            .putStringSet(PREFS_KEY_GEOFENCE_IDS, ids.toSet())
            .apply()
    }

    private fun getSavedGeofenceIds(): List<String> {
        return context.getSharedPreferences("PharmaFindPrefs", Context.MODE_PRIVATE)
            .getStringSet(PREFS_KEY_GEOFENCE_IDS, emptySet())
            ?.toList() ?: emptyList()
    }

    private fun clearSavedGeofenceIds() {
        context.getSharedPreferences("PharmaFindPrefs", Context.MODE_PRIVATE)
            .edit()
            .remove(PREFS_KEY_GEOFENCE_IDS)
            .apply()
    }
}