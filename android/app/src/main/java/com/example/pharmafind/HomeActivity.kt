package com.example.pharmafind

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class HomeActivity : AppCompatActivity(), OnMapReadyCallback, PharmacyBottomSheetFragment.PharmacySheetListener {

    // =============================================
    // PROPERTIES
    // =============================================

    private lateinit var gMap: GoogleMap
    private var currentRoute: Polyline? = null
    private var userLocationMarker: Marker? = null
    private lateinit var locationHelper: LocationHelper
    private lateinit var geofenceManager: GeofenceManager

    // Real GPS location — falls back to Kottayam town center
    private var userLocation = LatLng(9.5916, 76.5222)

    private var pharmaciesFromApi: List<Pharmacy> = emptyList()
    private var isMedicineSearch = false
    private var currentMedicineName = ""

    // ── Cart ───────────────────────────────────────────────────────
    private val cart = mutableListOf<CartEntry>()
    private var cartPharmacyId   = -1
    private var cartPharmacyName = ""
    private lateinit var fabCart: com.google.android.material.floatingactionbutton.FloatingActionButton
    private var userName = "User"

    // Bottom sheet views
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>
    private lateinit var rvPharmacies: RecyclerView
    private lateinit var pharmacyAdapter: PharmacyCardAdapter
    private lateinit var txtBottomSheetTitle: TextView
    private lateinit var txtPharmacyCount: TextView

    // FABs
    private lateinit var fabMyLocation: FloatingActionButton
    private lateinit var fabClearRoute: FloatingActionButton

    // In-app navigation panel
    private lateinit var cardNavigationPanel: androidx.cardview.widget.CardView
    private lateinit var txtNavDestination: TextView
    private lateinit var txtNavDistance: TextView
    private lateinit var txtNavDuration: TextView
    private lateinit var txtNavMode: TextView
    private lateinit var txtNavStep:         TextView
    private lateinit var txtNavStepCounter:  TextView
    private lateinit var txtNavStepDistance: TextView
    private lateinit var pbNavProgress:      android.widget.ProgressBar
    private lateinit var btnNavPrev: android.widget.Button
    private lateinit var btnNavNext: android.widget.Button
    private lateinit var btnNavOpenGMaps: android.widget.Button
    private var navSteps: List<RouteStep> = emptyList()
    private var navStepIndex = 0
    private var navDestination: LatLng? = null
    private var navDestinationName = ""

    // Search bar views
    private lateinit var edtSearch: EditText
    private lateinit var suggestionContainer: LinearLayout  // shown below search bar
    private var suggestionJob: Runnable? = null             // debounce handler
    private val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // =============================================
    // PERMISSION LAUNCHER
    // =============================================

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) fetchUserLocation()
        else onLocationReady(userLocation) // use default silently
    }

    // =============================================
    // LIFECYCLE
    // =============================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("is_logged_in", false)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        userName = prefs.getString("user_name", "User") ?: "User"
        setContentView(R.layout.activity_home)

        locationHelper = LocationHelper(this)
        geofenceManager = GeofenceManager(this)

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.home_map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        setupViews()
        applyRoleBasedUI()
        requestLocationPermission()
        requestNotificationPermission()

        // Handle tap from geofence notification — highlight that pharmacy
        intent.getIntExtra("highlight_pharmacy_id", -1).let { pharmacyId ->
            if (pharmacyId != -1) {
                // Store it — will be used once pharmacies load
                getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
                    .edit().putInt("pending_highlight_pharmacy", pharmacyId).apply()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (locationHelper.hasLocationPermission()) {
            locationHelper.startLocationUpdates { updateUserMarkerPosition(it) }
        }
        // Handle search_query passed from RecommendationsActivity
        intent?.getStringExtra("search_query")?.let { query ->
            intent.removeExtra("search_query")
            edtSearch.postDelayed({
                edtSearch.setText(query)
                handleSearch(query)
            }, 600)
        }
    }

    override fun onPause() {
        super.onPause()
        locationHelper.stopLocationUpdates()
    }

    // =============================================
    // SETUP
    // =============================================

    // =============================================
    // ROLE-BASED UI ADJUSTMENTS
    // Owners who land on HomeActivity (e.g. via back button) should
    // not see patient-specific buttons.
    // =============================================

    private fun applyRoleBasedUI() {
        val prefs    = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "patient") ?: "patient"
        val isOwner  = userRole in listOf("pharmacy", "owner", "pharmacist")

        if (isOwner) {
            // Hide patient-only elements
            findViewById<android.view.View>(R.id.btnMyMedicines)?.visibility      = View.GONE
            findViewById<android.view.View>(R.id.fabRecommendations)?.visibility  = View.GONE
            findViewById<android.view.View>(R.id.btnReservationsShortcut)?.visibility = View.GONE
        }
    }

    private fun setupViews() {
        edtSearch = findViewById(R.id.edtSearch)

        // Create suggestion dropdown container
        // White rounded card below the search bar, elevated above the map
        val shape = android.graphics.drawable.GradientDrawable().apply {
            setColor(android.graphics.Color.WHITE)
            cornerRadius = 18f
        }
        suggestionContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = shape
            elevation   = 16f
            visibility  = View.GONE
            setPadding(0, 4, 0, 4)
        }

        // Insert directly after the top bar in the CoordinatorLayout
        val topBar = edtSearch.rootView.findViewById<android.view.View>(R.id.topBar)
        val rootLayout = topBar?.parent as? android.view.ViewGroup
        if (rootLayout != null) {
            val topBarPos = rootLayout.indexOfChild(topBar)
            val lp = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin   = (56 * resources.displayMetrics.density).toInt() + 8  // ~56dp search bar height
                marginStart = 12
                marginEnd   = 12
            }
            rootLayout.addView(suggestionContainer, topBarPos + 1, lp)
        } else {
            // Fallback: add to root of activity
            val root = findViewById<android.view.ViewGroup>(android.R.id.content)
            val lp2  = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 80 }
            root.addView(suggestionContainer, lp2)
        }

        // Submit on keyboard search button
        edtSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = v.text.toString().trim()
                if (query.isNotEmpty()) {
                    hideSuggestions()
                    handleSearch(query)
                }
                hideKeyboard()
                true
            } else false
        }

        // Show suggestions as user types — debounced 300ms
        edtSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                suggestionJob?.let { searchHandler.removeCallbacks(it) }
                val query = s.toString().trim()
                if (query.length >= 1) {
                    suggestionJob = Runnable { fetchSuggestions(query) }
                    searchHandler.postDelayed(suggestionJob!!, 250)
                } else {
                    hideSuggestions()
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Hide suggestions when user clears field
        edtSearch.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) hideSuggestions()
        }

        findViewById<ImageView>(R.id.imgProfileMenu).setOnClickListener { showProfileMenu() }
        // My Medicines is a LinearLayout pill (icon + label)
        findViewById<android.view.View>(R.id.btnMyMedicines).setOnClickListener {
            startActivity(Intent(this, MyMedicinesActivity::class.java))
        }

        fabMyLocation = findViewById(R.id.fabMyLocation)
        fabCart       = findViewById(R.id.fabCart)
        fabCart.setOnClickListener { openCart() }
        fabMyLocation.setOnClickListener { recenterToMyLocation() }

        fabClearRoute = findViewById(R.id.fabClearRoute)
        fabClearRoute.visibility = View.GONE
        fabClearRoute.setOnClickListener { clearRoute() }

        // Recommendations FAB — AI suggestions button
        val fabRecommendations = findViewById<FloatingActionButton>(R.id.fabRecommendations)
        fabRecommendations?.setOnClickListener {
            // Show medicine info search prompt
            val input = android.widget.EditText(this).apply {
                hint = "Enter medicine name (e.g. Paracetamol)"
                setPadding(40, 24, 40, 24)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("💊 Medicine Information")
                .setMessage("Search for a medicine to see its uses, side effects, and reviews")
                .setView(input)
                .setPositiveButton("Search") { _, _ ->
                    val query = input.text.toString().trim()
                    if (query.isNotEmpty()) {
                        // Use the existing search to find the medicine then show info
                        edtSearch.setText(query)
                        handleSearch(query)
                        android.widget.Toast.makeText(this,
                            "Tap a pharmacy card's ℹ️ button to view medicine details",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Reservations shortcut in bottom sheet header
        findViewById<android.view.View>(R.id.btnReservationsShortcut)?.setOnClickListener {
            startActivity(Intent(this, ReservationHistoryActivity::class.java))
        }

        // In-app navigation panel
        cardNavigationPanel = findViewById(R.id.cardNavigationPanel)
        txtNavDestination   = findViewById(R.id.txtNavDestination)
        txtNavDistance      = findViewById(R.id.txtNavDistance)
        txtNavDuration      = findViewById(R.id.txtNavDuration)
        txtNavMode          = findViewById(R.id.txtNavMode)
        txtNavStep          = findViewById(R.id.txtNavStep)
        txtNavStepCounter   = findViewById(R.id.txtNavStepCounter)
        txtNavStepDistance  = findViewById(R.id.txtNavStepDistance)
        pbNavProgress       = findViewById(R.id.pbNavProgress)
        btnNavPrev          = findViewById(R.id.btnNavPrev)
        btnNavNext          = findViewById(R.id.btnNavNext)
        btnNavOpenGMaps     = findViewById(R.id.btnNavOpenGMaps)

        btnNavPrev.setOnClickListener { navigateToPrevStep() }
        btnNavNext.setOnClickListener { navigateToNextStep() }
        btnNavOpenGMaps.setOnClickListener {
            navDestination?.let { openGoogleMapsNavigation(it, navDestinationName) }
        }
        findViewById<android.view.View>(R.id.btnNavClose).setOnClickListener {
            clearRoute()
        }

        // Filter chips
        setupFilterChips()

        initBottomSheet()
    }

    private fun initBottomSheet() {
        val bottomSheet = findViewById<LinearLayout>(R.id.bottomSheet)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.peekHeight = 420
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        bottomSheetBehavior.isHideable = false

        txtBottomSheetTitle = findViewById(R.id.txtBottomSheetTitle)
        txtPharmacyCount = findViewById(R.id.txtPharmacyCount)

        // Hide count on load — will update after search
        txtPharmacyCount.visibility = View.GONE

        rvPharmacies = findViewById(R.id.rvPharmacies)
        rvPharmacies.layoutManager = LinearLayoutManager(this)

        pharmacyAdapter = PharmacyCardAdapter(
            pharmacies = emptyList(),
            isMedicineSearch = false,
            onCardClick = { pharmacy ->
                showRouteToPharmacy(LatLng(pharmacy.lat, pharmacy.lng), pharmacy.name)
            },
            onDirectionsClick = { pharmacy ->
                showRouteToPharmacy(LatLng(pharmacy.lat, pharmacy.lng), pharmacy.name)
            },
            onCallClick = { pharmacy -> makeCall(pharmacy.phone) },
            onMoreClick = { pharmacy ->
                showPharmacyDetailSheet(pharmacy, currentMedicineName, isMedicineSearch)
            },
            onAddToCart = { pharmacy -> addToCart(pharmacy) }
        )
        rvPharmacies.adapter = pharmacyAdapter
    }

    // =============================================
    // LOCATION
    // =============================================

    private fun requestLocationPermission() {
        when {
            locationHelper.hasLocationPermission() -> fetchUserLocation()
            ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) -> {
                AlertDialog.Builder(this)
                    .setTitle("Location Access")
                    .setMessage("PharmaFind uses your location to show pharmacies near you.")
                    .setPositiveButton("Allow") { _, _ -> launchPermissionRequest() }
                    .setNegativeButton("Not now") { _, _ -> onLocationReady(userLocation) }
                    .show()
            }
            else -> launchPermissionRequest()
        }
    }

    private fun launchPermissionRequest() {
        locationPermissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    private fun requestNotificationPermission() {
        // Required on Android 13+ (API 33+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    private fun fetchUserLocation() {
        locationHelper.getLastKnownLocation { location ->
            userLocation = location
            onLocationReady(location)
        }
    }

    private fun onLocationReady(location: LatLng) {
        userLocation = location
        if (::gMap.isInitialized) {
            gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 14f))
            updateUserMarkerPosition(location)
        }
        // Load only nearest 5 pharmacies within 5km
        loadNearbyPharmacies()
    }

    private fun recenterToMyLocation() {
        if (!::gMap.isInitialized) return
        if (locationHelper.hasLocationPermission()) {
            locationHelper.getLastKnownLocation { location ->
                userLocation = location
                updateUserMarkerPosition(location)
                gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(location, 15f))
            }
        } else {
            gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 15f))
        }
    }

    // =============================================
    // MAP
    // =============================================

    override fun onMapReady(googleMap: GoogleMap) {
        gMap = googleMap
        gMap.uiSettings.isZoomControlsEnabled = false
        gMap.uiSettings.isCompassEnabled = true
        gMap.uiSettings.isMyLocationButtonEnabled = false
        gMap.moveCamera(CameraUpdateFactory.newLatLngZoom(userLocation, 14f))
        addUserLocationMarker()

        gMap.setOnMarkerClickListener { marker ->
            if (marker != userLocationMarker) marker.showInfoWindow()
            true
        }
        gMap.setOnInfoWindowClickListener { marker ->
            if (marker != userLocationMarker)
                showRouteToPharmacy(marker.position, marker.title ?: "")
        }

        // If pharmacies already loaded before map was ready, render them now
        if (pharmaciesFromApi.isNotEmpty()) {
            showNearbyPharmaciesOnMap(pharmaciesFromApi)
            updateBottomSheet(
                pharmacies = pharmaciesFromApi,
                title = "Nearby Pharmacies",
                showCount = false
            )
        }
    }

    private fun addUserLocationMarker() {
        userLocationMarker?.remove()
        userLocationMarker = gMap.addMarker(
            MarkerOptions()
                .position(userLocation)
                .title("You are here")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
    }

    private fun updateUserMarkerPosition(location: LatLng) {
        if (!::gMap.isInitialized) return
        userLocation = location
        userLocationMarker?.remove()
        userLocationMarker = gMap.addMarker(
            MarkerOptions()
                .position(location)
                .title("You are here")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
    }

    // =============================================
    // ROUTE — Real Google Directions API
    // =============================================

    private fun showRouteToPharmacy(destination: LatLng, pharmacyName: String = "") {
        currentRoute?.remove()
        currentRoute = null
        navDestination = destination
        navDestinationName = pharmacyName

        Toast.makeText(this, "Getting directions...", Toast.LENGTH_SHORT).show()

        ApiClient.apiService.getDirections(
            originLat = userLocation.latitude,
            originLng = userLocation.longitude,
            destLat   = destination.latitude,
            destLng   = destination.longitude,
            mode      = "driving"
        ).enqueue(object : Callback<DirectionsResponse> {
            override fun onResponse(
                call: Call<DirectionsResponse>,
                response: Response<DirectionsResponse>
            ) {
                val body = response.body()

                if (body?.fallback == true || body?.route == null) {
                    // No Directions API key — draw straight route line in-app
                    drawInAppRoute(userLocation, destination, pharmacyName, null)
                    return
                }

                val route = body.route!!
                val points = com.google.maps.android.PolyUtil.decode(route.polyline)

                // Draw real road route — smooth blue polyline
                currentRoute = gMap.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .width(14f)
                        .color(0xFF1A73E8.toInt())
                        .jointType(com.google.android.gms.maps.model.JointType.ROUND)
                        .startCap(com.google.android.gms.maps.model.RoundCap())
                        .endCap(com.google.android.gms.maps.model.RoundCap())
                        .geodesic(false)
                )

                fabClearRoute.visibility = View.VISIBLE

                val bounds = LatLngBounds.Builder()
                val step = maxOf(1, points.size / 50)
                for (i in points.indices step step) bounds.include(points[i])
                bounds.include(userLocation).include(destination)
                gMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 140))

                showRouteInfoCard(route, pharmacyName)
            }

            override fun onFailure(call: Call<DirectionsResponse>, t: Throwable) {
                Log.e("HomeActivity", "Directions API failed: ${t.message}")
                drawInAppRoute(userLocation, destination, pharmacyName, null)
            }
        })
    }

    /**
     * Draw a simple in-app route when Directions API is unavailable.
     * Shows a dashed blue line from user to pharmacy with distance/ETA estimate.
     * No external app opened — everything stays in PharmaFind.
     */
    private fun drawInAppRoute(
        origin: LatLng,
        destination: LatLng,
        pharmacyName: String,
        route: RouteInfo?
    ) {
        // Draw dashed line on map
        // Simple dashed polyline — no CustomCap, no external bitmap needed
        currentRoute = gMap.addPolyline(
            PolylineOptions()
                .add(origin, destination)
                .width(10f)
                .color(0xFF1A73E8.toInt())
                .jointType(com.google.android.gms.maps.model.JointType.ROUND)
                .startCap(com.google.android.gms.maps.model.RoundCap())
                .endCap(com.google.android.gms.maps.model.RoundCap())
                .pattern(listOf(
                    com.google.android.gms.maps.model.Dash(30f),
                    com.google.android.gms.maps.model.Gap(15f)
                ))
        )

        fabClearRoute.visibility = View.VISIBLE

        val bounds = LatLngBounds.Builder()
            .include(origin)
            .include(destination)
            .build()
        gMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 180))

        // Haversine straight-line distance
        val earthRadius = 6371.0
        val dLat = Math.toRadians(destination.latitude - origin.latitude)
        val dLon = Math.toRadians(destination.longitude - origin.longitude)
        val a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(Math.toRadians(origin.latitude)) *
                Math.cos(Math.toRadians(destination.latitude)) *
                Math.sin(dLon/2) * Math.sin(dLon/2)
        val distanceKm = earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a))
        val etaMinutes = (distanceKm / 30.0 * 60).toInt().coerceAtLeast(1)

        val fakeRoute = RouteInfo(
            polyline         = "",
            distance_text    = "${"%.1f".format(distanceKm)} km",
            distance_meters  = (distanceKm * 1000).toInt(),
            duration_text    = "$etaMinutes mins (est.)",
            duration_seconds = etaMinutes * 60,
            steps            = listOf(
                RouteStep(
                    instruction = "Head towards $pharmacyName",
                    distance    = "${"%.1f".format(distanceKm)} km",
                    maneuver    = "straight"
                ),
                RouteStep(
                    instruction = "Arrive at $pharmacyName",
                    distance    = "0 m",
                    maneuver    = "arrive"
                )
            ),
            start_address = "",
            end_address   = pharmacyName
        )
        showRouteInfoCard(fakeRoute, pharmacyName)
    }

    private fun openGoogleMapsNavigation(destination: LatLng, label: String) {
        // Opens Google Maps with full turn-by-turn navigation
        // Works even without Directions API key
        val uri = Uri.parse(
            "google.navigation:q=${destination.latitude},${destination.longitude}" +
                    "&mode=d"  // d = driving
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage("com.google.android.apps.maps")
        }
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            // Google Maps not installed — open in browser
            val webUri = Uri.parse(
                "https://www.google.com/maps/dir/?api=1" +
                        "&destination=${destination.latitude},${destination.longitude}" +
                        "&travelmode=driving"
            )
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    private fun showRouteInfoCard(route: RouteInfo, pharmacyName: String) {
        navSteps = route.steps ?: emptyList()
        navStepIndex = 0
        navDestinationName = pharmacyName

        // Populate in-app navigation panel
        txtNavDestination.text = pharmacyName.ifEmpty { "Pharmacy" }
        txtNavDistance.text    = route.distance_text ?: ""
        txtNavDuration.text    = route.duration_text ?: ""
        txtNavMode.text        = "🚗 Driving"

        if (navSteps.isNotEmpty()) {
            updateNavStep()
            btnNavNext.visibility = if (navSteps.size > 1) View.VISIBLE else View.GONE
            btnNavPrev.visibility = View.GONE
        } else {
            txtNavStep.text = "Follow the blue route"
            btnNavNext.visibility = View.GONE
            btnNavPrev.visibility = View.GONE
        }

        cardNavigationPanel.visibility = View.VISIBLE
    }

    private fun updateNavStep() {
        if (navSteps.isEmpty()) return
        val step  = navSteps[navStepIndex]
        val total = navSteps.size

        // Clean HTML tags from instruction (Google sends <b>Turn left</b> etc)
        val instruction = step.instruction
            .replace(Regex("<[^>]*>"), "")
            .trim()
            .ifEmpty { "Continue on route" }

        // Maneuver arrow emoji
        val arrow = when {
            step.maneuver.contains("left",  ignoreCase = true) -> "⬅️ "
            step.maneuver.contains("right", ignoreCase = true) -> "➡️ "
            step.maneuver.contains("straight", ignoreCase = true) -> "⬆️ "
            step.maneuver.contains("uturn", ignoreCase = true) -> "↩️ "
            step.maneuver.contains("roundabout", ignoreCase = true) -> "🔄 "
            step.maneuver.contains("merge", ignoreCase = true) -> "↗️ "
            navStepIndex == total - 1 -> "🏁 "
            else -> "⬆️ "
        }

        txtNavStep.text         = "$arrow$instruction"
        txtNavStepCounter.text  = "Step ${navStepIndex + 1} of $total"
        txtNavStepDistance.text = step.distance.ifEmpty { "" }

        // Progress bar
        val progress = if (total > 1) ((navStepIndex.toFloat() / (total - 1)) * 100).toInt() else 100
        pbNavProgress.progress = progress

        btnNavPrev.visibility = if (navStepIndex > 0) View.VISIBLE else View.GONE
        btnNavNext.visibility = if (navStepIndex < total - 1) View.VISIBLE else View.GONE
    }

    private fun navigateToNextStep() {
        if (navStepIndex < navSteps.size - 1) {
            navStepIndex++
            updateNavStep()
        }
    }

    private fun navigateToPrevStep() {
        if (navStepIndex > 0) {
            navStepIndex--
            updateNavStep()
        }
    }

    private fun clearRoute() {
        currentRoute?.remove()
        currentRoute = null
        fabClearRoute.visibility = View.GONE
        if (::cardNavigationPanel.isInitialized) {
            cardNavigationPanel.visibility = View.GONE
        }
        navSteps = emptyList()
        navStepIndex = 0
        // Restore title
        if (isMedicineSearch) {
            txtBottomSheetTitle.text = "Pharmacies with $currentMedicineName"
        } else {
            txtBottomSheetTitle.text = "Nearby Pharmacies"
        }
    }

    private fun setupFilterChips() {
        val chipAll       = findViewById<com.google.android.material.chip.Chip>(R.id.chipAll)
        val chipOpen      = findViewById<com.google.android.material.chip.Chip>(R.id.chipOpen)
        val chipNearest   = findViewById<com.google.android.material.chip.Chip>(R.id.chipNearest)
        val chipBestRated = findViewById<com.google.android.material.chip.Chip>(R.id.chipBestRated)

        listOf(chipAll, chipOpen, chipNearest, chipBestRated).forEach { chip ->
            chip?.setOnClickListener {
                val filtered = when (chip.id) {
                    R.id.chipOpen      -> pharmaciesFromApi.filter { it.is_open }
                    R.id.chipNearest   -> pharmaciesFromApi.sortedBy { it.distance_km }
                    R.id.chipBestRated -> pharmaciesFromApi.sortedByDescending { it.rating }
                    else               -> pharmaciesFromApi
                }
                pharmacyAdapter.updateData(filtered, isMedicineSearch)
            }
        }
    }

    // =============================================
    // LOAD NEARBY PHARMACIES (Home screen)
    // Only nearest 5 within 5km — clean opening experience
    // =============================================

    private fun loadNearbyPharmacies() {
        txtBottomSheetTitle.text = "Finding nearby pharmacies..."
        txtPharmacyCount.visibility = View.GONE

        // Uses /pharmacies/nearby — tries Google Places first, falls back to DB
        ApiClient.apiService.getNearbyPlacesPharmacies(
            lat       = userLocation.latitude,
            lng       = userLocation.longitude,
            radius_km = 25.0,
            limit     = 20
        ).enqueue(object : Callback<NearbyPharmaciesResponse> {
            override fun onResponse(
                call: Call<NearbyPharmaciesResponse>,
                response: Response<NearbyPharmaciesResponse>
            ) {
                if (response.isSuccessful) {
                    val body    = response.body()
                    val results = body?.pharmacies ?: emptyList()
                    val source  = body?.source ?: "database"

                    Log.d("HomeActivity", "Loaded ${results.size} pharmacies from $source")

                    pharmaciesFromApi = results
                    isMedicineSearch  = false
                    currentMedicineName = ""

                    if (results.isEmpty()) {
                        loadPharmaciesWiderRadius()
                    } else {
                        showNearbyPharmaciesOnMap(results)
                        updateBottomSheet(
                            pharmacies = results,
                            title      = if (source == "google_places")
                                "Real Pharmacies Nearby"
                            else "Nearby Pharmacies",
                            showCount  = false
                        )
                        geofenceManager.registerPharmacyGeofences(results)
                        updateSavedMedicinesFlag()
                        checkPendingHighlight(results)
                    }
                } else {
                    Log.e("HomeActivity", "Error ${response.code()}: ${response.message()}")
                    // Fallback to plain DB endpoint
                    loadPharmaciesFromDatabase()
                }
            }
            override fun onFailure(call: Call<NearbyPharmaciesResponse>, t: Throwable) {
                Log.e("HomeActivity", "Load failed: ${t.message}")
                loadPharmaciesFromDatabase()
            }
        })
    }

    // Plain DB fallback — always works even without Google key
    private fun loadPharmaciesFromDatabase() {
        ApiClient.apiService.getPharmacies(
            lat       = userLocation.latitude,
            lng       = userLocation.longitude,
            radius_km = 5.0,
            limit     = 5
        ).enqueue(object : Callback<List<Pharmacy>> {
            override fun onResponse(
                call: Call<List<Pharmacy>>,
                response: Response<List<Pharmacy>>
            ) {
                val results = response.body() ?: emptyList()
                pharmaciesFromApi = results
                if (results.isEmpty()) loadPharmaciesWiderRadius()
                else {
                    showNearbyPharmaciesOnMap(results)
                    updateBottomSheet(results, "Nearby Pharmacies", showCount = false)
                    geofenceManager.registerPharmacyGeofences(results)
                }
            }
            override fun onFailure(call: Call<List<Pharmacy>>, t: Throwable) {
                txtBottomSheetTitle.text = "Nearby Pharmacies"
                Toast.makeText(
                    this@HomeActivity,
                    "Couldn't connect to server. Is backend running?",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    private fun loadPharmaciesWiderRadius() {
        // Nothing within 5km — silently try 15km
        ApiClient.apiService.getPharmacies(
            lat = userLocation.latitude,
            lng = userLocation.longitude,
            radius_km = 50.0,
            limit = 20
        ).enqueue(object : Callback<List<Pharmacy>> {
            override fun onResponse(
                call: Call<List<Pharmacy>>,
                response: Response<List<Pharmacy>>
            ) {
                val results = response.body() ?: emptyList()
                pharmaciesFromApi = results
                if (results.isNotEmpty()) {
                    showNearbyPharmaciesOnMap(results)
                    updateBottomSheet(
                        pharmacies = results,
                        title = "Nearby Pharmacies",
                        showCount = false
                    )
                } else {
                    txtBottomSheetTitle.text = "No pharmacies found nearby"
                }
            }
            override fun onFailure(call: Call<List<Pharmacy>>, t: Throwable) {
                txtBottomSheetTitle.text = "Nearby Pharmacies"
            }
        })
    }

    // =============================================
    // SHOW PHARMACIES ON MAP — only nearby ones
    // =============================================

    private fun showNearbyPharmaciesOnMap(pharmacies: List<Pharmacy>) {
        if (!::gMap.isInitialized) return
        gMap.clear()
        addUserLocationMarker()

        var hasValidCoords = false

        for (pharmacy in pharmacies) {
            val noGps = pharmacy.distance_km >= 999.0 ||
                    (pharmacy.lat == 0.0 && pharmacy.lng == 0.0)

            if (noGps) {
                // Newly registered pharmacy with no GPS yet
                // Show at a slight offset from user so it's visible on map
                val offset = LatLng(
                    userLocation.latitude  + (pharmacies.indexOf(pharmacy) * 0.002),
                    userLocation.longitude + 0.005
                )
                gMap.addMarker(
                    MarkerOptions()
                        .position(offset)
                        .title(pharmacy.name)
                        .snippet("📍 Location pending — ${pharmacy.address ?: "Address not set"}")
                        .icon(BitmapDescriptorFactory.defaultMarker(
                            BitmapDescriptorFactory.HUE_YELLOW))
                )
                continue
            }

            hasValidCoords = true
            val markerColor = when {
                !pharmacy.is_open -> BitmapDescriptorFactory.HUE_VIOLET
                else              -> BitmapDescriptorFactory.HUE_RED
            }

            gMap.addMarker(
                MarkerOptions()
                    .position(LatLng(pharmacy.lat, pharmacy.lng))
                    .title(pharmacy.name)
                    .snippet(
                        "${if (pharmacy.is_open) "Open" else "Closed"} • " +
                                "${"%.1f".format(pharmacy.distance_km)} km away • " +
                                "★ ${"%.1f".format(pharmacy.rating ?: 0.0)}"
                    )
                    .icon(BitmapDescriptorFactory.defaultMarker(markerColor))
            )
        }

        // Fit map to show all markers
        if (hasValidCoords && pharmacies.isNotEmpty()) {
            val valid = pharmacies.filter {
                it.distance_km < 999.0 && !(it.lat == 0.0 && it.lng == 0.0)
            }
            if (valid.size > 1) {
                val bounds = valid.fold(
                    com.google.android.gms.maps.model.LatLngBounds.builder()
                        .include(userLocation)
                ) { b, p -> b.include(LatLng(p.lat, p.lng)) }.build()
                try {
                    gMap.animateCamera(
                        CameraUpdateFactory.newLatLngBounds(bounds, 120)
                    )
                } catch (e: Exception) {
                    gMap.animateCamera(
                        CameraUpdateFactory.newLatLngZoom(userLocation, 13f)
                    )
                }
            }
        }
    }


    // =============================================
    // SEARCH SUGGESTIONS
    // =============================================

    private fun fetchSuggestions(query: String) {
        ApiClient.apiService.getSuggestions(
            query = query,
            lat = userLocation.latitude,
            lng = userLocation.longitude
        ).enqueue(object : Callback<SuggestionResponse> {
            override fun onResponse(
                call: Call<SuggestionResponse>,
                response: Response<SuggestionResponse>
            ) {
                val items = response.body()?.suggestions ?: emptyList()
                if (items.isNotEmpty()) showSuggestions(items)
                else hideSuggestions()
            }
            override fun onFailure(call: Call<SuggestionResponse>, t: Throwable) {
                hideSuggestions()
            }
        })
    }

    private fun showSuggestions(items: List<SuggestionItem>) {
        suggestionContainer.removeAllViews()
        suggestionContainer.visibility = View.VISIBLE

        val inflater = android.view.LayoutInflater.from(this)

        items.take(7).forEach { item ->
            val row = inflater.inflate(R.layout.item_suggestion, suggestionContainer, false)

            // Icon + background colour by type
            val icon       = row.findViewById<android.widget.ImageView>(R.id.imgSuggestionIcon)
            val iconLayout = row.findViewById<android.widget.LinearLayout>(R.id.layoutSuggestionIcon)
            when (item.type) {
                "medicine" -> {
                    icon.setImageResource(android.R.drawable.ic_menu_search)
                    iconLayout.setBackgroundColor(0xFF1565C0.toInt())  // blue
                }
                "pharmacy" -> {
                    icon.setImageResource(android.R.drawable.ic_menu_mapmode)
                    iconLayout.setBackgroundColor(0xFF2E7D32.toInt())  // green
                }
                else -> {
                    icon.setImageResource(android.R.drawable.ic_menu_search)
                    iconLayout.setBackgroundColor(0xFF757575.toInt())  // grey
                }
            }

            // Main text — bold the matching prefix
            val txtMain = row.findViewById<android.widget.TextView>(R.id.txtSuggestionMain)
            val query   = edtSearch.text.toString().trim()
            if (query.isNotEmpty() && item.text.lowercase().startsWith(query.lowercase())) {
                val spannable = android.text.SpannableString(item.text)
                spannable.setSpan(
                    android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                    0, query.length.coerceAtMost(item.text.length),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                spannable.setSpan(
                    android.text.style.ForegroundColorSpan(getColor(R.color.primary)),
                    0, query.length.coerceAtMost(item.text.length),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                txtMain.text = spannable
            } else {
                txtMain.text = item.text
            }

            // Subtitle (generic name, strength, etc.)
            val txtSub = row.findViewById<android.widget.TextView>(R.id.txtSuggestionSub)
            txtSub.text     = item.subtitle.ifEmpty { item.type.replaceFirstChar { it.uppercase() } }
            txtSub.visibility = View.VISIBLE

            // Tap on row → fill + search immediately
            row.setOnClickListener {
                edtSearch.setText(item.text)
                edtSearch.setSelection(item.text.length)
                hideSuggestions()
                hideKeyboard()
                handleSearch(item.text)
            }

            // Tap the arrow icon → fill search bar only, don't search yet
            // (lets user edit the suggestion before searching)
            row.findViewById<android.widget.ImageView>(R.id.imgSuggestionFill)
                ?.setOnClickListener {
                    edtSearch.setText(item.text)
                    edtSearch.setSelection(item.text.length)
                    hideSuggestions()
                    edtSearch.requestFocus()
                    // Don't submit — let user edit
                }

            suggestionContainer.addView(row)
        }

        // "Search for X" footer row if there are results
        if (items.isNotEmpty()) {
            val footer = android.widget.TextView(this).apply {
                val q = edtSearch.text.toString().trim()
                text = "  🔍 Search for $q  →"
                textSize = 13f
                setTextColor(getColor(R.color.primary))
                setPadding(48, 20, 48, 20)
                setOnClickListener {
                    hideSuggestions()
                    hideKeyboard()
                    handleSearch(edtSearch.text.toString().trim())
                }
            }
            suggestionContainer.addView(footer)
        }
    }

    private fun hideSuggestions() {
        suggestionContainer.removeAllViews()
        suggestionContainer.visibility = View.GONE
    }

    // =============================================
    // SEARCH
    // =============================================

    private fun handleSearch(query: String) {
        clearRoute()
        val queryLower = query.lowercase().trim()

        // Detect symptom-like queries — no exact medicine name match likely
        val isSymptomQuery = queryLower.split(" ").size >= 2 ||
                listOf("headache", "fever", "pain", "cold", "flu", "nausea", "stomach",
                    "allergy", "diabetes", "sugar", "pressure", "cholesterol",
                    "acidity", "vomiting", "asthma", "breathing", "infection",
                    "antibiotic", "inflammation", "heartburn", "tablet", "medicine",
                    "pill", "syrup", "drop", "killer", "relief").any { queryLower.contains(it) }

        if (isSymptomQuery) {
            searchSemantic(query)
        } else {
            searchMedicine(query)
        }
    }

    private fun searchSemantic(query: String) {
        // Show loading
        txtBottomSheetTitle.text = "🧠 Finding medicines for \"$query\"..."

        ApiClient.apiService.semanticSearch(query)
            .enqueue(object : Callback<SemanticSearchResponse> {
                override fun onResponse(
                    call: Call<SemanticSearchResponse>,
                    response: Response<SemanticSearchResponse>
                ) {
                    val body = response.body()
                    if (response.isSuccessful && body != null && body.results.isNotEmpty()) {
                        // Pick the top result and search for it normally
                        val topMedicine = body.results.first()
                        val matchCount  = body.results.size
                        val matchLabel  = when (topMedicine.match_type) {
                            "exact"       -> topMedicine.name
                            "symptom_map" -> topMedicine.name
                            else          -> topMedicine.name
                        }
                        Log.d("HomeActivity",
                            "Semantic: '$query' → ${body.results.map { it.name }}")
                        // Search pharmacies using the matched medicine name
                        searchMedicine(matchLabel, semanticMatches = body.results)
                    } else {
                        // Fallback to regular search
                        searchMedicine(query)
                    }
                }
                override fun onFailure(call: Call<SemanticSearchResponse>, t: Throwable) {
                    searchMedicine(query)   // graceful fallback
                }
            })
    }

    private fun searchMedicine(query: String, semanticMatches: List<SemanticSearchResult>? = null) {
        ApiClient.apiService.searchMedicine(
            query = query,
            lat = userLocation.latitude,
            lng = userLocation.longitude,
            radius_km = 10.0,
            limit = 8   // max 8 results for medicine search
        ).enqueue(object : Callback<MedicineSearchResponse> {
            override fun onResponse(
                call: Call<MedicineSearchResponse>,
                response: Response<MedicineSearchResponse>
            ) {
                if (response.isSuccessful && (response.body()?.available_at?.isNotEmpty() == true)) {
                    val body = response.body() ?: return
                    showMedicineResults(body)
                } else {
                    searchPharmaciesByName(query)
                }
            }
            override fun onFailure(call: Call<MedicineSearchResponse>, t: Throwable) {
                searchPharmaciesByName(query)
            }
        })
    }

    private fun searchPharmaciesByName(query: String) {
        ApiClient.apiService.searchPharmacies(
            query = query,
            lat = userLocation.latitude,
            lng = userLocation.longitude,
            radius_km = 20.0
        ).enqueue(object : Callback<List<Pharmacy>> {
            override fun onResponse(call: Call<List<Pharmacy>>, response: Response<List<Pharmacy>>) {
                val results = response.body() ?: emptyList()
                pharmaciesFromApi = results
                isMedicineSearch = false
                currentMedicineName = ""

                if (results.isEmpty()) {
                    Toast.makeText(
                        this@HomeActivity,
                        "No results found for \"$query\"",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Reset to nearby view
                    loadNearbyPharmacies()
                } else {
                    showNearbyPharmaciesOnMap(results)
                    updateBottomSheet(
                        pharmacies = results,
                        title = "\"$query\"",
                        showCount = true  // Show count only for search results
                    )
                }
            }
            override fun onFailure(call: Call<List<Pharmacy>>, t: Throwable) {
                Toast.makeText(this@HomeActivity, "Search failed", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // =============================================
    // MEDICINE RESULTS DISPLAY
    // =============================================

    private fun showMedicineResults(result: MedicineSearchResponse) {
        if (!::gMap.isInitialized) return

        isMedicineSearch = true
        currentMedicineName = result.medicine.name

        gMap.clear()
        addUserLocationMarker()

        for (pm in result.available_at) {
            val markerColor = when {
                pm.stock > 20 -> BitmapDescriptorFactory.HUE_GREEN
                pm.stock > 10 -> BitmapDescriptorFactory.HUE_YELLOW
                else          -> BitmapDescriptorFactory.HUE_ORANGE
            }
            gMap.addMarker(
                MarkerOptions()
                    .position(LatLng(pm.lat, pm.lng))
                    .title(pm.pharmacy_name)
                    .snippet(
                        "₹${"%.0f".format(pm.price)} • " +
                                "Stock: ${pm.stock} • " +
                                "${"%.1f".format(pm.distance_km)} km"
                    )
                    .icon(BitmapDescriptorFactory.defaultMarker(markerColor))
            )
        }

        val pharmacyList = result.available_at.map { pm ->
            Pharmacy(
                id = pm.pharmacy_id, name = pm.pharmacy_name,
                place = pm.pharmacy_address ?: "",
                lat = pm.lat, lng = pm.lng,
                distance_km = pm.distance_km, is_open = true,
                rating = pm.rating ?: 0.0, phone = pm.phone ?: "",
                address = pm.pharmacy_address ?: "",
                price = pm.price, stock = pm.stock,
                medicine_id = pm.medicine_id,
                medicine_name = result.medicine.name,
                medicine_strength = result.medicine.strength,
                is_prescription_required = pm.is_prescription_required ?: false,
                medicine_uses          = result.medicine.uses,
                medicine_side_effects  = result.medicine.side_effects,
                medicine_image_url     = result.medicine.image_url,
                medicine_manufacturer  = result.medicine.manufacturer,
                excellent_review_pct   = result.medicine.excellent_review_pct,
                average_review_pct     = result.medicine.average_review_pct,
                poor_review_pct        = result.medicine.poor_review_pct
            )
        }
        pharmaciesFromApi = pharmacyList

        if (result.available_at.isNotEmpty()) {
            val bounds = LatLngBounds.Builder().include(userLocation)
            result.available_at.forEach { bounds.include(LatLng(it.lat, it.lng)) }
            gMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 160))
        }

        updateBottomSheet(
            pharmacies = pharmacyList,
            title = result.medicine.name,
            subtitle = "${result.medicine.strength} ${result.medicine.form}",
            showCount = true,
            isMedicineSearch = true
        )
    }

    // =============================================
    // BOTTOM SHEET UPDATE — clean & smart
    // =============================================

    private fun updateBottomSheet(
        pharmacies: List<Pharmacy>,
        title: String,
        subtitle: String = "",
        showCount: Boolean = false,
        isMedicineSearch: Boolean = false
    ) {
        txtBottomSheetTitle.text = title

        // Only show count for search results, not on home screen
        if (showCount && pharmacies.isNotEmpty()) {
            txtPharmacyCount.visibility = View.VISIBLE
            txtPharmacyCount.text = when {
                isMedicineSearch -> "${pharmacies.size} pharmacies"
                else             -> "${pharmacies.size} found"
            }
        } else {
            txtPharmacyCount.visibility = View.GONE
        }

        pharmacyAdapter.updateData(pharmacies, isMedicineSearch)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    // =============================================
    // GEOFENCE HELPERS
    // =============================================

    private fun updateSavedMedicinesFlag() {
        // Tell the receiver whether user has medicines so it shows smarter notifications
        val userId = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
            .getInt("user_id", -1)
        if (userId == -1) return

        ApiClient.apiService.getUserMedicines(userId)
            .enqueue(object : Callback<List<UserMedicineItem>> {
                override fun onResponse(
                    call: Call<List<UserMedicineItem>>,
                    response: Response<List<UserMedicineItem>>
                ) {
                    val hasMeds = (response.body()?.size ?: 0) > 0
                    getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
                        .edit().putBoolean("has_saved_medicines", hasMeds).apply()
                }
                override fun onFailure(call: Call<List<UserMedicineItem>>, t: Throwable) {}
            })
    }

    private fun checkPendingHighlight(pharmacies: List<Pharmacy>) {
        val prefs = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        val pharmacyId = prefs.getInt("pending_highlight_pharmacy", -1)
        if (pharmacyId == -1) return

        // Clear it so it doesn't trigger again
        prefs.edit().remove("pending_highlight_pharmacy").apply()

        // Find and focus the pharmacy
        val pharmacy = pharmacies.find { it.id == pharmacyId } ?: return
        val pos = LatLng(pharmacy.lat, pharmacy.lng)
        if (::gMap.isInitialized) {
            gMap.animateCamera(CameraUpdateFactory.newLatLngZoom(pos, 17f))
        }
        showPharmacyDetailSheet(pharmacy, "", false)
    }

    // =============================================
    // PROFILE MENU
    // =============================================

    private fun showProfileMenu() {
        val prefs    = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        val userRole = prefs.getString("user_role", "patient") ?: "patient"

        val menuItems = mutableListOf(
            "👤  My Profile",
            "📋  Medicine History",
            "🎟  My Reservations",
            "🔔  Reminders & Sound",
            "🚪  Logout"
        )
        // Only show Owner Dashboard for pharmacy owners
        if (userRole == "owner" || userRole == "pharmacist") {
            menuItems.add(2, "🏥  Owner Dashboard")
        }

        AlertDialog.Builder(this)
            .setTitle("Hello, $userName 👋")
            .setItems(menuItems.toTypedArray()) { _, which ->
                val item = menuItems[which]
                when {
                    item.contains("My Profile")       -> startActivity(Intent(this, ProfileActivity::class.java))
                    item.contains("Medicine History") -> startActivity(Intent(this, MedicationHistoryActivity::class.java))
                    item.contains("My Reservations")  -> startActivity(Intent(this, ReservationHistoryActivity::class.java))
                    item.contains("Reminders")        -> startActivity(Intent(this, ReminderSettingsActivity::class.java))
                    item.contains("Owner Dashboard")  -> startActivity(Intent(this, PharmacyOwnerDashboardActivity::class.java))
                    item.contains("Logout")           -> confirmLogout()
                }
            }
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure?")
            .setPositiveButton("Logout") { _, _ ->
                geofenceManager.removeAllGeofences()
                getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE).edit().clear().apply()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =============================================
    // PHARMACY DETAIL SHEET
    // =============================================

    private fun showPharmacyDetailSheet(
        pharmacy: Pharmacy, medicineName: String, isMedicineSearch: Boolean
    ) {
        PharmacyBottomSheetFragment.newInstance(
            pharmacy = PharmacyCard(
                id = pharmacy.id,
                name = pharmacy.name,
                address = pharmacy.address ?: pharmacy.place,
                distance = "${"%.1f".format(pharmacy.distance_km)} km",
                rating = pharmacy.rating?.toFloat() ?: 0f,
                phone = pharmacy.phone ?: "",
                isOpen = pharmacy.is_open,
                lat = pharmacy.lat, lng = pharmacy.lng,
                medicineName = if (isMedicineSearch) (pharmacy.medicine_name ?: currentMedicineName) else "",
                medicinePrice = pharmacy.price?.let { "%.2f".format(it) } ?: "",
                medicineStock = pharmacy.stock ?: 0,
                medicineId    = pharmacy.medicine_id ?: -1,
                isPrescriptionRequired = pharmacy.is_prescription_required ?: false,
                medicineStrength      = pharmacy.medicine_strength ?: "",
                medicineUses          = pharmacy.medicine_uses ?: "",
                medicineSideEffects   = pharmacy.medicine_side_effects ?: "",
                medicineImageUrl      = pharmacy.medicine_image_url ?: "",
                medicineManufacturer  = pharmacy.medicine_manufacturer ?: "",
                excellentReviewPct    = pharmacy.excellent_review_pct ?: 0,
                averageReviewPct      = pharmacy.average_review_pct ?: 0,
                poorReviewPct         = pharmacy.poor_review_pct ?: 0
            ),
            onDirections = { p -> showRouteToPharmacy(LatLng(p.lat, p.lng), p.name) },
            onCall = { phone -> makeCall(phone) }
        ).show(supportFragmentManager, "PharmacyDetail")
    }

    // =============================================
    // HELPERS
    // =============================================

    // ── PharmacySheetListener implementation ─────────────────────
    override fun onDirectionsRequested(pharmacy: PharmacyCard) {
        showRouteToPharmacy(LatLng(pharmacy.lat, pharmacy.lng), pharmacy.name)
    }
    override fun onCallRequested(phone: String) {
        makeCall(phone)
    }

    private fun makeCall(phone: String?) {
        if (!phone.isNullOrEmpty()) {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
        } else {
            Toast.makeText(this, "Phone number not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(edtSearch.windowToken, 0)
    }
    // ═══════════════════════════════════════════════════════
    // CART
    // ═══════════════════════════════════════════════════════

    private fun addToCart(pharmacy: Pharmacy) {
        if (pharmacy.medicine_id == null || pharmacy.medicine_id == -1) {
            Toast.makeText(this, "Search for a medicine first to add it to cart",
                Toast.LENGTH_SHORT).show()
            return
        }

        // Cart only supports one pharmacy at a time
        if (cart.isNotEmpty() && cartPharmacyId != pharmacy.id) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Start New Cart?")
                .setMessage("Your cart has items from $cartPharmacyName. " +
                        "Starting a new cart will clear it.\n\nSwitch to ${pharmacy.name}?")
                .setPositiveButton("Switch") { _, _ ->
                    cart.clear()
                    cartPharmacyId   = pharmacy.id
                    cartPharmacyName = pharmacy.name
                    doAddToCart(pharmacy)
                }
                .setNegativeButton("Keep Current", null)
                .show()
            return
        }

        cartPharmacyId   = pharmacy.id
        cartPharmacyName = pharmacy.name
        doAddToCart(pharmacy)
    }

    private fun doAddToCart(pharmacy: Pharmacy) {
        val existing = cart.find { it.medicineId == (pharmacy.medicine_id ?: -1) }
        if (existing != null) {
            existing.quantity++
            Toast.makeText(this,
                "✓ ${pharmacy.medicine_name ?: "Medicine"} qty updated (${existing.quantity})",
                Toast.LENGTH_SHORT).show()
        } else {
            cart.add(CartEntry(
                pharmacyId             = pharmacy.id,
                pharmacyName           = pharmacy.name,
                medicineId             = pharmacy.medicine_id ?: -1,
                medicineName           = pharmacy.medicine_name ?: "Medicine",
                medicineStrength       = pharmacy.medicine_strength ?: "",
                pricePerStrip          = pharmacy.price ?: 0.0,
                isPrescriptionRequired = pharmacy.is_prescription_required ?: false,
                quantity               = 1
            ))
            Toast.makeText(this,
                "🛒 ${pharmacy.medicine_name ?: "Medicine"} added to cart",
                Toast.LENGTH_SHORT).show()
        }
        updateCartFab()
    }

    private fun updateCartFab() {
        if (!::fabCart.isInitialized) return
        val total = cart.size
        if (total > 0) {
            fabCart.visibility = View.VISIBLE
            // Show count badge on FAB label
            fabCart.contentDescription = "Cart ($total)"
        } else {
            fabCart.visibility = View.GONE
        }
    }

    private fun openCart() {
        if (cart.isEmpty()) {
            Toast.makeText(this, "Your cart is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, CartActivity::class.java).apply {
            putExtra("pharmacy_id",   cartPharmacyId)
            putExtra("pharmacy_name", cartPharmacyName)
            putExtra("cart_items",    ArrayList(cart))
        }
        startActivityForResult(intent, CART_REQUEST_CODE)
    }

    companion object {
        private const val CART_REQUEST_CODE = 9001
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CART_REQUEST_CODE && resultCode == RESULT_OK) {
            // Order placed — clear cart
            cart.clear()
            cartPharmacyId   = -1
            cartPharmacyName = ""
            updateCartFab()
            Toast.makeText(this, "Order confirmed! Check My Reservations.",
                Toast.LENGTH_LONG).show()
        }
    }


}