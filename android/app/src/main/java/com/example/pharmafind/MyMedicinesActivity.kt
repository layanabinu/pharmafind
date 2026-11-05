package com.example.pharmafind

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MyMedicinesActivity : AppCompatActivity() {

    private lateinit var medicinesContainer: LinearLayout
    private lateinit var btnAddMedicine: View
    private lateinit var txtEmptyState: TextView
    private lateinit var progressBar: ProgressBar

    private val medicines = mutableListOf<UserMedicineItem>()
    private var userId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_my_medicines)

        medicinesContainer = findViewById(R.id.medicinesContainer)
        btnAddMedicine = findViewById(R.id.btnAddMedicine)
        txtEmptyState = findViewById(R.id.txtEmptyState)

        val sharedPref = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        userId = sharedPref.getInt("user_id", -1)

        btnAddMedicine.setOnClickListener { showAddMedicineDialog() }

        // Back button
        findViewById<android.view.View>(R.id.btnBack)?.setOnClickListener { finish() }

        // Tab navigation
        findViewById<android.view.View>(R.id.tabHistory)?.setOnClickListener {
            startActivity(android.content.Intent(this, MedicationHistoryActivity::class.java))
        }
        findViewById<android.view.View>(R.id.tabReservations)?.setOnClickListener {
            startActivity(android.content.Intent(this, ReservationHistoryActivity::class.java))
        }
        findViewById<android.view.View>(R.id.tabRecommendations)?.setOnClickListener {
            startActivity(android.content.Intent(this, RecommendationsActivity::class.java))
        }

        loadMedicinesFromApi()
    }

    // =============================================
    // LOAD
    // =============================================

    private fun loadMedicinesFromApi() {
        if (userId == -1) {
            showEmptyState("Please log in to view your medicines")
            return
        }

        txtEmptyState.text = "Loading..."
        txtEmptyState.visibility = View.VISIBLE
        medicinesContainer.removeAllViews()

        ApiClient.apiService.getUserMedicines(userId)
            .enqueue(object : Callback<List<UserMedicineItem>> {
                override fun onResponse(
                    call: Call<List<UserMedicineItem>>,
                    response: Response<List<UserMedicineItem>>
                ) {
                    if (response.isSuccessful) {
                        medicines.clear()
                        medicines.addAll(response.body() ?: emptyList())
                        updateMedicinesList()
                    } else {
                        showEmptyState("Failed to load medicines")
                    }
                }
                override fun onFailure(call: Call<List<UserMedicineItem>>, t: Throwable) {
                    showEmptyState("Connection error. Check your network.")
                }
            })
    }

    // =============================================
    // ADD DIALOG
    // =============================================

    private fun showAddMedicineDialog() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_medicine, null)

        val edtName      = dialogView.findViewById<TextInputEditText>(R.id.edtMedicineName)
        val edtDosage    = dialogView.findViewById<TextInputEditText>(R.id.edtDosage)
        val edtFrequency = dialogView.findViewById<TextInputEditText>(R.id.edtFrequency)
        val edtTablets       = dialogView.findViewById<TextInputEditText>(R.id.edtTabletsLeft)
        val edtTabsPerStrip  = dialogView.findViewById<TextInputEditText>(R.id.edtTabletsPerStrip)

        // Time-of-day toggle
        val btnMorning = dialogView.findViewById<android.widget.TextView>(R.id.btnTimeMorning)
        val btnEvening = dialogView.findViewById<android.widget.TextView>(R.id.btnTimeEvening)
        val btnBoth    = dialogView.findViewById<android.widget.TextView>(R.id.btnTimeBoth)
        var selectedTime = "Morning"
        fun selectTime(t: String) {
            selectedTime = t
            for ((btn, v) in listOf(btnMorning to "Morning", btnEvening to "Evening", btnBoth to "Both")) {
                btn.setBackgroundColor(if (v == t) 0xFF1565C0.toInt() else 0xFFE3F2FD.toInt())
                btn.setTextColor(if (v == t) 0xFFFFFFFF.toInt() else 0xFF1565C0.toInt())
            }
        }
        btnMorning.setOnClickListener { selectTime("Morning") }
        btnEvening.setOnClickListener { selectTime("Evening") }
        btnBoth.setOnClickListener    { selectTime("Both") }
        selectTime("Morning")

        AlertDialog.Builder(this)
            .setTitle("Add Medicine")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = edtName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Please enter medicine name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addMedicineToApi(
                    name           = name,
                    dosage         = edtDosage.text.toString().trim(),
                    frequency      = edtFrequency.text.toString().trim(),
                    tablets        = edtTablets.text.toString().toIntOrNull() ?: 0,
                    tabletsPerStrip= edtTabsPerStrip.text.toString().toIntOrNull() ?: 10,
                    timeOfDay      = selectedTime
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =============================================
    // EDIT DIALOG  ← NEW
    // =============================================

    private fun showEditMedicineDialog(medicine: UserMedicineItem) {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_add_medicine, null)

        val edtName      = dialogView.findViewById<TextInputEditText>(R.id.edtMedicineName)
        val edtDosage    = dialogView.findViewById<TextInputEditText>(R.id.edtDosage)
        val edtFrequency = dialogView.findViewById<TextInputEditText>(R.id.edtFrequency)
        val edtTablets       = dialogView.findViewById<TextInputEditText>(R.id.edtTabletsLeft)
        val edtTabsPerStrip  = dialogView.findViewById<TextInputEditText>(R.id.edtTabletsPerStrip)

        // Pre-fill with existing values
        edtName.setText(medicine.medicineName)
        edtName.isEnabled = false   // can't change medicine name, only details
        edtDosage.setText(medicine.dosage)
        edtFrequency.setText(medicine.frequency)
        edtTablets.setText(medicine.stripsRemaining.toString())

        AlertDialog.Builder(this)
            .setTitle("Edit Medicine")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val newDosage    = edtDosage.text.toString().trim()
                val newFrequency = edtFrequency.text.toString().trim()
                val newTablets   = edtTablets.text.toString().toIntOrNull()
                    ?: medicine.stripsRemaining

                // Only call API if something actually changed
                if (newDosage    == medicine.dosage &&
                    newFrequency == medicine.frequency &&
                    newTablets   == medicine.stripsRemaining) {
                    Toast.makeText(this, "No changes made", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                updateMedicineOnApi(
                    medicine  = medicine,
                    dosage    = newDosage,
                    frequency = newFrequency,
                    tablets   = newTablets
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =============================================
    // API — ADD
    // =============================================

    private fun addMedicineToApi(name: String, dosage: String,
                                 frequency: String, tablets: Int,
                                 tabletsPerStrip: Int = 10,
                                 timeOfDay: String = "Morning") {
        val request = UserMedicineCreate(
            user_id          = userId,
            medicine_name    = name,
            dosage           = dosage,
            frequency        = frequency,
            strips_remaining = tablets,
            reminder_enabled = true,
            tablets_per_strip = tabletsPerStrip,
            time_of_day       = timeOfDay
        )

        ApiClient.apiService.addUserMedicine(request)
            .enqueue(object : Callback<AddMedicineResponse> {
                override fun onResponse(
                    call: Call<AddMedicineResponse>,
                    response: Response<AddMedicineResponse>
                ) {
                    if (response.isSuccessful && response.body()?.success == true) {
                        Toast.makeText(
                            this@MyMedicinesActivity,
                            "$name added successfully ✓",
                            Toast.LENGTH_SHORT
                        ).show()
                        // Log history event
                        logHistoryEvent(
                            medicineName = name,
                            eventType    = "started",
                            tablets      = tablets,
                            dosage       = dosage,
                            frequency    = frequency
                        )
                        loadMedicinesFromApi()
                    } else {
                        Toast.makeText(this@MyMedicinesActivity,
                            "Failed to add medicine", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<AddMedicineResponse>, t: Throwable) {
                    Toast.makeText(this@MyMedicinesActivity,
                        "Connection error", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // =============================================
    // API — UPDATE  ← NEW
    // =============================================

    private fun updateMedicineOnApi(
        medicine: UserMedicineItem,
        dosage: String,
        frequency: String,
        tablets: Int
    ) {
        val tabletsPerStrip = medicine.tabletsPerStrip.takeIf { it > 0 } ?: 10
        val newStrips = if (tabletsPerStrip > 0)
            kotlin.math.ceil(tablets.toDouble() / tabletsPerStrip).toInt()
        else
            tablets   // fallback

        val request = UserMedicineUpdate(
            dosage          = dosage,
            frequency       = frequency,
            stripsRemaining = newStrips
        )

        ApiClient.apiService.updateUserMedicine(medicine.id, request)
            .enqueue(object : Callback<UpdateMedicineResponse> {
                override fun onResponse(
                    call: Call<UpdateMedicineResponse>,
                    response: Response<UpdateMedicineResponse>
                ) {
                    if (response.isSuccessful && response.body()?.success == true) {
                        Toast.makeText(
                            this@MyMedicinesActivity,
                            "${medicine.medicineName} updated ✓",
                            Toast.LENGTH_SHORT
                        ).show()

                        val eventType =
                            if (newStrips > medicine.stripsRemaining) "refilled" else "updated"

                        logHistoryEvent(
                            medicineName = medicine.medicineName,
                            eventType    = eventType,
                            tablets      = tablets,  // total tablets for history
                            dosage       = dosage,
                            frequency    = frequency
                        )

                        loadMedicinesFromApi()
                    } else {
                        Toast.makeText(
                            this@MyMedicinesActivity,
                            "Failed to update medicine",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onFailure(call: Call<UpdateMedicineResponse>, t: Throwable) {
                    Toast.makeText(
                        this@MyMedicinesActivity,
                        "Connection error",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }
    // =============================================
    // API — DELETE
    // =============================================

    private fun deleteMedicineFromApi(medicine: UserMedicineItem) {
        ApiClient.apiService.deleteUserMedicine(medicine.id)
            .enqueue(object : Callback<DeleteMedicineResponse> {
                override fun onResponse(
                    call: Call<DeleteMedicineResponse>,
                    response: Response<DeleteMedicineResponse>
                ) {
                    if (response.isSuccessful) {
                        Toast.makeText(
                            this@MyMedicinesActivity,
                            "${medicine.medicineName} removed",
                            Toast.LENGTH_SHORT
                        ).show()
                        logHistoryEvent(
                            medicineName = medicine.medicineName,
                            eventType    = "stopped",
                            tablets      = medicine.stripsRemaining
                        )
                        loadMedicinesFromApi()
                    } else {
                        Toast.makeText(this@MyMedicinesActivity,
                            "Failed to remove", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<DeleteMedicineResponse>, t: Throwable) {
                    Toast.makeText(this@MyMedicinesActivity,
                        "Connection error", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // =============================================
    // UI
    // =============================================

    private fun updateMedicinesList() {
        medicinesContainer.removeAllViews()
        if (medicines.isEmpty()) {
            showEmptyState("No medicines added yet\nTap + to add your first medicine")
        } else {
            txtEmptyState.visibility = View.GONE
            medicines.forEach { medicine ->
                medicinesContainer.addView(createMedicineCard(medicine))
            }
        }
    }

    private fun showEmptyState(message: String) {
        txtEmptyState.text = message
        txtEmptyState.visibility = View.VISIBLE
        medicinesContainer.removeAllViews()
    }

    private fun createMedicineCard(medicine: UserMedicineItem): CardView {
        val card = CardView(this).apply {
            radius = 16f
            cardElevation = 4f
            setCardBackgroundColor(getColor(R.color.surface))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 40, 48, 40)
        }

        // ── Top row: name + action buttons ──
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val txtName = TextView(this).apply {
            text = medicine.medicineName
            textSize = 17f
            setTextColor(getColor(R.color.text_primary))
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(txtName)

        // Edit button
        val btnEdit = Button(this).apply {
            text = "Edit"
            textSize = 11f
            setTextColor(getColor(R.color.primary))
            background = null
            setPadding(16, 0, 8, 0)
            setOnClickListener { showEditMedicineDialog(medicine) }
        }
        topRow.addView(btnEdit)

        // Delete button
        val btnDelete = Button(this).apply {
            text = "Remove"
            textSize = 11f
            setTextColor(getColor(R.color.error))
            background = null
            setPadding(8, 0, 0, 0)
            setOnClickListener {
                AlertDialog.Builder(this@MyMedicinesActivity)
                    .setTitle("Remove Medicine")
                    .setMessage("Remove ${medicine.medicineName} from your list?")
                    .setPositiveButton("Remove") { _, _ -> deleteMedicineFromApi(medicine) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        topRow.addView(btnDelete)
        outer.addView(topRow)

        // ── Dosage & Frequency ──
        val txtInfo = TextView(this).apply {
            text = buildString {
                if (medicine.dosage.isNotEmpty()) append(medicine.dosage)
                if (medicine.dosage.isNotEmpty() && medicine.frequency.isNotEmpty()) append("  •  ")
                if (medicine.frequency.isNotEmpty()) append(medicine.frequency)
                if (isEmpty()) append("No dosage info")
            }
            textSize = 14f
            setTextColor(getColor(R.color.text_secondary))
            setPadding(0, 10, 0, 0)
        }
        outer.addView(txtInfo)

        // ── Stock display: strips -> total tablets ──
        val tabletsPerStrip = medicine.tabletsPerStrip.takeIf { it > 0 } ?: 10
        val totalTablets = medicine.stripsRemaining * tabletsPerStrip

        val stockColor = when {
            totalTablets <= 0  -> getColor(R.color.error)
            totalTablets <= 5  -> getColor(R.color.error)
            totalTablets <= 15 -> getColor(R.color.warning)
            else               -> getColor(R.color.success)
        }

        val stockLabel = when {
            totalTablets <= 0  -> "⚠ Out of stock"
            totalTablets <= 5  -> "⚠ Only $totalTablets tablets left — refill soon!"
            totalTablets <= 15 -> "⚡ $totalTablets tablets remaining"
            else               -> "✓ $totalTablets tablets remaining"
        }

        val txtStock = TextView(this).apply {
            text = "$stockLabel (${medicine.stripsRemaining} strips × $tabletsPerStrip)"
            textSize = 13f
            setTextColor(stockColor)
            setPadding(0, 8, 0, 0)
        }
        outer.addView(txtStock)

        // ── Reminder status ──
        val txtReminder = TextView(this).apply {
            text = if (medicine.reminderEnabled) "🔔 Reminder on" else "🔕 Reminder off"
            textSize = 12f
            setTextColor(getColor(R.color.text_hint))
            setPadding(0, 6, 0, 0)
        }
        outer.addView(txtReminder)

        card.addView(outer)
        return card
    }

    // =============================================
    // HISTORY LOGGING — fires silently after every action
    // =============================================

    private fun logHistoryEvent(
        medicineName: String,
        eventType: String,
        tablets: Int,
        dosage: String = "",
        frequency: String = ""
    ) {
        val userId = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
            .getInt("user_id", -1)
        if (userId == -1) return

        val request = HistoryEventRequest(
            user_id          = userId,
            medicine_name    = medicineName,
            event_type       = eventType,
            strips_remaining = tablets,
            dosage           = dosage.ifEmpty { null },
            frequency        = frequency.ifEmpty { null }
        )

        // Fire and forget — don't block UI
        ApiClient.apiService.addHistoryEvent(request)
            .enqueue(object : Callback<SuccessResponse> {
                override fun onResponse(
                    call: Call<SuccessResponse>,
                    response: Response<SuccessResponse>
                ) {} // silently logged
                override fun onFailure(call: Call<SuccessResponse>, t: Throwable) {}
            })
    }
    // ── Refill reminder support ───────────────────────────────────────────
    // Called every time medicines are refreshed from the server.
    // Writes a cache to SharedPreferences so the alarm receiver can check
    // stock without a network call, then immediately fires a refill check.
    private fun writeMedicineCacheAndCheckRefill() {
        val prefs = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)

        // Format: "MedName:tablets:dosesPerDay,MedName2:tablets2:dosesPerDay2"
        val cache = medicines.joinToString(",") { med ->
            val dosesPerDay     = parseDosesPerDay(med.frequency)
            val tabletsPerStrip = med.tabletsPerStrip.takeIf { it > 0 } ?: 10
            val totalTablets    = med.stripsRemaining * tabletsPerStrip
            val safeName        = med.medicineName.replace(",", ";")
            // Format: name:strips:dosesPerDay:tabletsPerStrip
            "$safeName:${med.stripsRemaining}:$dosesPerDay:$tabletsPerStrip"
        }
        prefs.edit().putString("medicines_cache", cache).apply()

        // Check refill status immediately on every load
        ReminderSettingsActivity.checkAndFireRefillReminders(this)
    }

    // Parses the frequency text field into doses per day
    private fun parseDosesPerDay(frequency: String): Int {
        val f = frequency.lowercase()
        return when {
            f.contains("once")     || f == "daily" || f.contains("od") -> 1
            f.contains("twice")    || f.contains("bd") || f.contains("bid") -> 2
            f.contains("three")    || f.contains("tds") || f.contains("tid") -> 3
            f.contains("four")     || f.contains("qds") || f.contains("qid") -> 4
            f.contains("every 8")  || f.contains("8 hour") -> 3
            f.contains("every 6")  || f.contains("6 hour") -> 4
            f.contains("every 12") || f.contains("12 hour") -> 2
            else -> 1  // default: 1/day is safest — overestimates days remaining
        }
    }


}