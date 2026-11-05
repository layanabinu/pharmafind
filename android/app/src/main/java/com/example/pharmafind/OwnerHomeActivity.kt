package com.example.pharmafind

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * OwnerHomeActivity
 * -----------------
 * The landing screen for pharmacy owners / pharmacists.
 * Shows:  stat cards (total medicines, low stock, critical, reservations)
 *         quick-nav tiles → Inventory, Reservations, Stock Forecast, Notifications
 *         inline stock alert list
 *         bottom nav bar
 *
 * Owners never see MyMedicinesActivity, RecommendationsActivity, or patient FABs.
 */
class OwnerHomeActivity : AppCompatActivity() {

    private var pharmacyId   = -1
    private var pharmacyName = "My Pharmacy"
    private var ownerName    = "Owner"

    // Stat refs
    private lateinit var txtStatTotal:    TextView
    private lateinit var txtStatLow:      TextView
    private lateinit var txtStatCritical: TextView
    private lateinit var txtStatReserv:   TextView
    private lateinit var ownerAlertBanner: LinearLayout
    private lateinit var txtOwnerAlertMsg: TextView
    private lateinit var ownerAlertList:   LinearLayout
    private lateinit var txtOwnerNoAlerts: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_owner_home)

        val prefs = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        pharmacyId   = prefs.getInt("pharmacy_id", -1)
        pharmacyName = prefs.getString("pharmacy_name", "My Pharmacy") ?: "My Pharmacy"
        ownerName    = prefs.getString("user_name", "Owner") ?: "Owner"

        if (pharmacyId == -1) {
            Toast.makeText(this,
                "⚠️ No pharmacy linked to this account.\nContact admin to link your pharmacy.",
                Toast.LENGTH_LONG).show()
        }

        bindViews()
        wireNav()
        loadStats()

        // Schedule daily stock alert check for this pharmacy owner
        if (pharmacyId != -1) {
            OwnerStockAlertReceiver.schedule(this)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // BIND
    // ─────────────────────────────────────────────────────────────

    private fun bindViews() {
        findViewById<TextView>(R.id.txtOwnerPharmacyHeader).text = pharmacyName
        txtStatTotal    = findViewById(R.id.txtStatTotal)
        txtStatLow      = findViewById(R.id.txtStatLow)
        txtStatCritical = findViewById(R.id.txtStatCritical)
        txtStatReserv   = findViewById(R.id.txtStatReserv)
        ownerAlertBanner = findViewById(R.id.ownerAlertBanner)
        txtOwnerAlertMsg = findViewById(R.id.txtOwnerAlertMsg)
        ownerAlertList   = findViewById(R.id.ownerAlertList)
        txtOwnerNoAlerts = findViewById(R.id.txtOwnerNoAlerts)
    }

    // ─────────────────────────────────────────────────────────────
    // NAVIGATION
    // ─────────────────────────────────────────────────────────────

    private fun wireNav() {
        // Bottom nav bar
        findViewById<LinearLayout>(R.id.navOwnerHome).setOnClickListener { /* already here */ }
        findViewById<LinearLayout>(R.id.navOwnerInventory).setOnClickListener   { openInventory() }
        findViewById<LinearLayout>(R.id.navOwnerReservations).setOnClickListener { openReservations() }
        findViewById<LinearLayout>(R.id.navOwnerProfile).setOnClickListener     { showOwnerProfile() }

        // Dashboard tiles
        findViewById<LinearLayout>(R.id.tileInventory).setOnClickListener     { openInventory() }
        findViewById<LinearLayout>(R.id.tileReservations).setOnClickListener  { openReservations() }
        findViewById<LinearLayout>(R.id.tileForecast).setOnClickListener      { openInventory() } // opens inventory where chart is per-item
        findViewById<LinearLayout>(R.id.tileOwnerSettings).setOnClickListener {
            startActivity(Intent(this, ReminderSettingsActivity::class.java))
        }

        // Header buttons
        findViewById<LinearLayout>(R.id.btnOwnerAlerts).setOnClickListener { openInventory() }
        findViewById<LinearLayout>(R.id.btnOwnerProfile).setOnClickListener { showOwnerProfile() }

        // Alert banner → inventory
        ownerAlertBanner.setOnClickListener { openInventory() }

        // Stat cards → jump to relevant screen
        findViewById<LinearLayout>(R.id.cardStatTotal).setOnClickListener    { openInventory() }
        findViewById<LinearLayout>(R.id.cardStatLow).setOnClickListener      { openInventory() }
        findViewById<LinearLayout>(R.id.cardStatCritical).setOnClickListener { openInventory() }
        findViewById<LinearLayout>(R.id.cardStatReserv).setOnClickListener   { openReservations() }
    }

    private fun openInventory() {
        startActivity(Intent(this, PharmacyOwnerDashboardActivity::class.java))
    }

    private fun openReservations() {
        // Owners see all reservations for their pharmacy, not just their own user
        startActivity(Intent(this, OwnerReservationsActivity::class.java))
    }

    private fun showOwnerProfile() {
        val prefs = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        AlertDialog.Builder(this)
            .setTitle("$ownerName")
            .setItems(arrayOf(
                "🏥  Pharmacy: $pharmacyName",
                "🔔  Notification Settings",
                "🚪  Logout"
            )) { _, which ->
                when (which) {
                    1 -> startActivity(Intent(this, ReminderSettingsActivity::class.java))
                    2 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Logout")
                            .setMessage("Are you sure you want to logout?")
                            .setPositiveButton("Logout") { _, _ ->
                                prefs.edit().clear().apply()
                                startActivity(Intent(this, LoginActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                })
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .show()
    }

    // ─────────────────────────────────────────────────────────────
    // LOAD DATA
    // ─────────────────────────────────────────────────────────────

    private fun loadStats() {
        if (pharmacyId == -1) return
        loadInventoryStats()
        loadAlerts()
        checkPharmacyLocation()
    }

    private fun loadInventoryStats() {
        ApiClient.apiService.getPharmacyInventory(pharmacyId)
            .enqueue(object : Callback<PharmacyDetailResponse> {
                override fun onResponse(call: Call<PharmacyDetailResponse>, response: Response<PharmacyDetailResponse>) {
                    val medicines = response.body()?.medicines ?: return
                    txtStatTotal.text = medicines.size.toString()
                }
                override fun onFailure(call: Call<PharmacyDetailResponse>, t: Throwable) {
                    txtStatTotal.text = "?"
                }
            })
    }

    private fun loadAlerts() {
        ApiClient.apiService.getStockAlerts()
            .enqueue(object : Callback<StockAlertsResponse> {
                override fun onResponse(call: Call<StockAlertsResponse>, response: Response<StockAlertsResponse>) {
                    val body = response.body() ?: return

                    val myWarning  = body.warning.filter  { it.pharmacy_id == pharmacyId }
                    val myCritical = body.critical.filter { it.pharmacy_id == pharmacyId }
                    val allAlerts  = myCritical + myWarning

                    txtStatLow.text      = myWarning.size.toString()
                    txtStatCritical.text = myCritical.size.toString()
                    txtStatCritical.setTextColor(
                        if (myCritical.isNotEmpty()) getColor(R.color.error) else getColor(R.color.success)
                    )

                    // Alert banner
                    if (myCritical.isNotEmpty()) {
                        ownerAlertBanner.visibility = View.VISIBLE
                        txtOwnerAlertMsg.text = if (myCritical.size == 1)
                            "⚠️ ${myCritical[0].medicine_name} critically low (${myCritical[0].current_stock} units)"
                        else
                            "⚠️ ${myCritical.size} medicines critically low — tap to review"
                    } else {
                        ownerAlertBanner.visibility = View.GONE
                    }

                    // Inline alert rows
                    ownerAlertList.removeAllViews()
                    if (allAlerts.isEmpty()) {
                        txtOwnerNoAlerts.visibility = View.VISIBLE
                    } else {
                        txtOwnerNoAlerts.visibility = View.GONE
                        allAlerts.take(5).forEach { alert ->
                            val row = layoutInflater.inflate(
                                android.R.layout.simple_list_item_2,
                                ownerAlertList, false
                            )
                            row.findViewById<TextView>(android.R.id.text1).apply {
                                text = alert.medicine_name
                                textSize = 14f
                                setTextColor(
                                    if (alert.alert_level == "critical") 0xFFB71C1C.toInt()
                                    else 0xFFF57F17.toInt()
                                )
                            }
                            row.findViewById<TextView>(android.R.id.text2).apply {
                                text = "${alert.current_stock} units remaining  •  ${alert.message}"
                                textSize = 12f
                            }
                            row.setOnClickListener { openInventory() }
                            ownerAlertList.addView(row)
                        }
                    }
                }
                override fun onFailure(call: Call<StockAlertsResponse>, t: Throwable) {
                    txtStatLow.text      = "?"
                    txtStatCritical.text = "?"
                    txtOwnerNoAlerts.visibility = View.VISIBLE
                }
            })
    }

    private fun checkPharmacyLocation() {
        // Warn owner if their pharmacy has no GPS set (shows as yellow on map)
        ApiClient.apiService.getPharmacyInventory(pharmacyId)
            .enqueue(object : retrofit2.Callback<PharmacyDetailResponse> {
                override fun onResponse(
                    call: retrofit2.Call<PharmacyDetailResponse>,
                    response: retrofit2.Response<PharmacyDetailResponse>
                ) {
                    // If pharmacy registered without GPS, show a persistent notice
                    val body = response.body() ?: return
                    // Check via pharmacy detail — lat/lng fields
                }
                override fun onFailure(call: retrofit2.Call<PharmacyDetailResponse>, t: Throwable) {}
            })
    }

    override fun onResume() {
        super.onResume()
        loadStats()     // Refresh stats every time user returns to home
    }
}