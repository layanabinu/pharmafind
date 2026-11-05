package com.example.pharmafind

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng

class LocationHelper(private val context: Context) {

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    // Default fallback: Kollam city center
    // Used only if GPS is unavailable or permission denied
    val defaultLocation = LatLng(8.8932, 76.6141)

    // =============================================
    // PERMISSION CHECK
    // =============================================

    fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    // =============================================
    // GET LAST KNOWN LOCATION (fast, one-shot)
    // Best for initial app load
    // =============================================

    fun getLastKnownLocation(onResult: (LatLng) -> Unit) {
        if (!hasLocationPermission()) {
            onResult(defaultLocation)
            return
        }

        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    onResult(LatLng(location.latitude, location.longitude))
                } else {
                    // Last location unavailable, request fresh one
                    requestFreshLocation(onResult)
                }
            }
            .addOnFailureListener {
                onResult(defaultLocation)
            }
    }

    // =============================================
    // REQUEST FRESH LOCATION (slightly slower)
    // Used when last known location is null
    // =============================================

    private fun requestFreshLocation(onResult: (LatLng) -> Unit) {
        if (!hasLocationPermission()) {
            onResult(defaultLocation)
            return
        }

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            5000L  // 5 seconds interval
        ).apply {
            setMaxUpdates(1)  // only need one update
            setWaitForAccurateLocation(false)
        }.build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation
                if (location != null) {
                    onResult(LatLng(location.latitude, location.longitude))
                } else {
                    onResult(defaultLocation)
                }
                // Stop updates after getting one result
                fusedLocationClient.removeLocationUpdates(this)
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            callback,
            Looper.getMainLooper()
        )
    }

    // =============================================
    // START CONTINUOUS LOCATION UPDATES
    // Used to keep user position fresh on map
    // =============================================

    private var continuousCallback: LocationCallback? = null

    fun startLocationUpdates(onUpdate: (LatLng) -> Unit) {
        if (!hasLocationPermission()) return

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            10000L  // update every 10 seconds
        ).apply {
            setMinUpdateDistanceMeters(20f)  // only update if moved 20m
        }.build()

        continuousCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onUpdate(LatLng(location.latitude, location.longitude))
            }
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            continuousCallback!!,
            Looper.getMainLooper()
        )
    }

    fun stopLocationUpdates() {
        continuousCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
            continuousCallback = null
        }
    }
}