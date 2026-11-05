package com.example.pharmafind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

/**
 * GeofenceBroadcastReceiver
 * ──────────────────────────
 * Fires when user physically enters a 200m radius around a pharmacy.
 *
 * Notification logic:
 *  1. Get triggered pharmacy ID from geofence event
 *  2. Check if user has any saved medicines
 *  3. If yes → "Metro Medicals is nearby — check if they stock your medicines!"
 *  4. Tap notification → opens HomeActivity with that pharmacy focused
 */
class GeofenceBroadcastReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "pharmafind_geofence"
        const val CHANNEL_NAME = "Nearby Pharmacy Alerts"
        const val NOTIFICATION_ID_BASE = 1000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return

        if (geofencingEvent.hasError()) {
            Log.e("GeofenceReceiver", "Geofencing error: ${geofencingEvent.errorCode}")
            return
        }

        // Only care about ENTER transitions
        if (geofencingEvent.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return

        val triggeredGeofences = geofencingEvent.triggeringGeofences ?: return
        if (triggeredGeofences.isEmpty()) return

        val manager = GeofenceManager(context)
        val prefs = context.getSharedPreferences("PharmaFindPrefs", Context.MODE_PRIVATE)

        // Check if user has saved medicines — only notify if they do
        val userId = prefs.getInt("user_id", -1)
        val hasMedicines = prefs.getBoolean("has_saved_medicines", false)

        for (geofence in triggeredGeofences) {
            val pharmacyId = manager.extractPharmacyIdFromGeofenceId(geofence.requestId)
            val pharmacyName = extractPharmacyName(geofence.requestId)

            Log.d("GeofenceReceiver", "Entered geofence: $pharmacyName (id=$pharmacyId)")

            // Build appropriate notification message
            val (title, message) = if (hasMedicines) {
                Pair(
                    "💊 $pharmacyName is nearby!",
                    "You might be able to refill your medicines here. Tap to check availability."
                )
            } else {
                Pair(
                    "🏥 $pharmacyName is nearby!",
                    "Tap to view medicines and hours."
                )
            }

            showNotification(
                context = context,
                notificationId = NOTIFICATION_ID_BASE + (pharmacyId ?: 0),
                title = title,
                message = message,
                pharmacyId = pharmacyId
            )
        }
    }

    // ─────────────────────────────────────────────
    // SHOW NOTIFICATION
    // ─────────────────────────────────────────────

    private fun showNotification(
        context: Context,
        notificationId: Int,
        title: String,
        message: String,
        pharmacyId: Int?
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create channel (required on Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when you're near a pharmacy that stocks your medicines"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Tap notification → open HomeActivity
        val tapIntent = Intent(context, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            pharmacyId?.let { putExtra("highlight_pharmacy_id", it) }
        }
        val pendingTapIntent = PendingIntent.getActivity(
            context, notificationId, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)  // use your app icon in production
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingTapIntent)
            .setAutoCancel(true)   // dismiss on tap
            .build()

        notificationManager.notify(notificationId, notification)
        Log.d("GeofenceReceiver", "📬 Notification shown: $title")
    }

    // ─────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────

    private fun extractPharmacyName(geofenceId: String): String {
        // "geo_5_Apollo_Pharmacy_Kottayam" → "Apollo Pharmacy Kottayam"
        return try {
            geofenceId.split("_").drop(2).joinToString(" ")
        } catch (e: Exception) {
            "Nearby Pharmacy"
        }
    }
}