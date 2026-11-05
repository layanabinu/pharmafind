package com.example.pharmafind

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ReservationActivity : AppCompatActivity() {

    private var pharmacyId             = -1
    private var medicineId             = -1
    private var pharmacyName           = ""
    private var medicineName           = ""
    private var medicinePrice          = 0.0
    private var medicineStock          = 0   // in strips
    private var medicineStrength       = ""
    private var isPrescriptionRequired = false

    private lateinit var txtMedicineName: TextView
    private lateinit var txtPharmacyName: TextView
    private lateinit var txtPricePerStrip: TextView
    private lateinit var txtStockInfo: TextView
    private lateinit var txtTotalPrice: TextView
    private lateinit var edtQuantity: TextInputEditText
    private lateinit var edtNotes: TextInputEditText
    private lateinit var btnMinus: ImageButton
    private lateinit var btnPlus: ImageButton
    private lateinit var btnReserve: Button
    private lateinit var prescriptionCard: CardView
    private lateinit var txtPrescriptionWarning: TextView
    private lateinit var checklistLayout: LinearLayout
    private lateinit var progressBar: ProgressBar

    private var currentQty = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reservation)

        pharmacyId             = intent.getIntExtra("pharmacy_id", -1)
        medicineId             = intent.getIntExtra("medicine_id", -1)
        pharmacyName           = intent.getStringExtra("pharmacy_name") ?: ""
        medicineName           = intent.getStringExtra("medicine_name") ?: ""
        medicinePrice          = intent.getDoubleExtra("medicine_price", 0.0)
        medicineStock          = intent.getIntExtra("medicine_stock", 0)
        medicineStrength       = intent.getStringExtra("medicine_strength") ?: ""
        isPrescriptionRequired = intent.getBooleanExtra("is_prescription_required", false)

        if (pharmacyId == -1 || medicineId == -1) {
            Toast.makeText(this, "Invalid reservation data", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        initViews()
        checkPrescriptionRequirement()
    }

    private fun initViews() {
        txtMedicineName      = findViewById(R.id.txtReserveMedicineName)
        txtPharmacyName      = findViewById(R.id.txtReservePharmacyName)
        txtPricePerStrip     = findViewById(R.id.txtReservePrice)
        txtStockInfo         = findViewById(R.id.txtReserveStock)
        txtTotalPrice        = findViewById(R.id.txtReserveTotalPrice)
        edtQuantity          = findViewById(R.id.edtReserveQuantity)
        edtNotes             = findViewById(R.id.edtReserveNotes)
        btnMinus             = findViewById(R.id.btnQtyMinus)
        btnPlus              = findViewById(R.id.btnQtyPlus)
        btnReserve           = findViewById(R.id.btnConfirmReservation)
        prescriptionCard     = findViewById(R.id.prescriptionWarningCard)
        txtPrescriptionWarning = findViewById(R.id.txtPrescriptionWarning)
        checklistLayout      = findViewById(R.id.prescriptionChecklist)
        progressBar          = findViewById(R.id.progressBarReservation)

        // Populate header
        txtMedicineName.text = if (medicineStrength.isNotEmpty())
            "$medicineName  $medicineStrength" else medicineName
        txtPharmacyName.text = "📍 $pharmacyName"
        txtPricePerStrip.text = "₹${"%.2f".format(medicinePrice)} per strip"
        txtStockInfo.text = "$medicineStock strips available"

        updateTotal()

        // Back
        findViewById<ImageView>(R.id.btnReserveBack).setOnClickListener { finish() }

        // +/- buttons
        btnMinus.setOnClickListener {
            if (currentQty > 1) { currentQty--; syncQtyUI() }
        }
        btnPlus.setOnClickListener {
            if (currentQty < medicineStock) { currentQty++; syncQtyUI() }
            else Toast.makeText(this, "Only $medicineStock strips in stock", Toast.LENGTH_SHORT).show()
        }

        // Manual entry
        edtQuantity.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val v = edtQuantity.text.toString().toIntOrNull() ?: 1
                currentQty = v.coerceIn(1, medicineStock)
                syncQtyUI()
            }
        }

        btnReserve.setOnClickListener { confirmReservation() }
    }

    private fun syncQtyUI() {
        edtQuantity.setText(currentQty.toString())
        updateTotal()
    }

    private fun updateTotal() {
        val total = medicinePrice * currentQty
        txtTotalPrice.text = "Total: ₹${"%.2f".format(total)} for $currentQty strip${if (currentQty > 1) "s" else ""}"
    }

    // ── Prescription check ────────────────────────────────────────
    private fun checkPrescriptionRequirement() {
        ApiClient.apiService.getPrescriptionInfo(medicineId)
            .enqueue(object : Callback<PrescriptionInfoResponse> {
                override fun onResponse(call: Call<PrescriptionInfoResponse>,
                                        response: Response<PrescriptionInfoResponse>) {
                    val info = response.body() ?: return
                    isPrescriptionRequired = info.is_prescription_required
                    if (info.is_prescription_required) showPrescriptionWarning(info)
                    else prescriptionCard.visibility = View.GONE
                }
                override fun onFailure(call: Call<PrescriptionInfoResponse>, t: Throwable) {
                    prescriptionCard.visibility = View.GONE
                }
            })
    }

    private fun showPrescriptionWarning(info: PrescriptionInfoResponse) {
        prescriptionCard.visibility = View.VISIBLE
        txtPrescriptionWarning.text = info.warning_message
        checklistLayout.removeAllViews()
        info.reminder_items?.forEach { item ->
            checklistLayout.addView(TextView(this).apply {
                text = item; textSize = 13f
                setTextColor(0xFF5D4037.toInt())
                setPadding(0, 6, 0, 6)
            })
        }
        btnReserve.text = "Reserve (Bring Prescription)"
    }

    // ── Confirm ───────────────────────────────────────────────────
    private fun confirmReservation() {
        val qty   = edtQuantity.text.toString().toIntOrNull()?.coerceIn(1, medicineStock) ?: 1
        currentQty = qty
        val notes = edtNotes.text.toString().trim()

        if (qty > medicineStock) {
            Toast.makeText(this, "Only $medicineStock strips available", Toast.LENGTH_SHORT).show()
            return
        }

        if (isPrescriptionRequired) {
            AlertDialog.Builder(this)
                .setTitle("Prescription Required")
                .setMessage(
                    "This medicine needs a valid doctor's prescription.\n\n" +
                            "Please bring:\n• Original prescription\n• Valid photo ID\n\n" +
                            "The pharmacist cannot dispense without it. Continue?"
                )
                .setPositiveButton("Yes, I'll bring it") { _, _ -> submitReservation(qty, notes) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            submitReservation(qty, notes)
        }
    }

    private fun submitReservation(quantity: Int, notes: String) {
        progressBar.visibility = View.VISIBLE
        btnReserve.isEnabled   = false

        val userId = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE).getInt("user_id", -1)
        if (userId == -1) {
            Toast.makeText(this, "Please log in first", Toast.LENGTH_SHORT).show()
            progressBar.visibility = View.GONE
            btnReserve.isEnabled   = true
            return
        }

        ApiClient.apiService.createReservation(ReservationRequest(
            user_id     = userId,
            pharmacy_id = pharmacyId,
            medicine_id = medicineId,
            quantity    = quantity,
            notes       = notes.ifEmpty { null },
            is_prescription_required = isPrescriptionRequired
        )).enqueue(object : Callback<ReservationResponse> {
            override fun onResponse(call: Call<ReservationResponse>,
                                    response: Response<ReservationResponse>) {
                progressBar.visibility = View.GONE
                btnReserve.isEnabled   = true

                if (response.isSuccessful && response.body()?.success == true) {
                    val body = response.body() ?: return
                    showConfirmationDialog(body, quantity)
                } else {
                    val errBody = response.errorBody()?.string() ?: ""
                    when {
                        errBody.contains("User not found", ignoreCase = true) ||
                                errBody.contains("user not found", ignoreCase = true) -> {
                            // Session is stale — clear and re-login
                            getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
                                .edit().clear().apply()
                            Toast.makeText(this@ReservationActivity,
                                "Session expired. Please log in again.",
                                Toast.LENGTH_LONG).show()
                            startActivity(android.content.Intent(
                                this@ReservationActivity, LoginActivity::class.java
                            ).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                            return
                        }
                        else -> {
                            val msg = when {
                                errBody.contains("stock", ignoreCase = true) ->
                                    "Not enough stock available. Please try a smaller quantity."
                                errBody.contains("available", ignoreCase = true) ->
                                    "This medicine is currently unavailable at this pharmacy."
                                errBody.contains("pharmacy", ignoreCase = true) ->
                                    "Pharmacy not found. Please go back and try again."
                                errBody.contains("medicine", ignoreCase = true) ->
                                    "Medicine not found. Please search again."
                                response.code() == 401 -> "Session expired. Please log in again."
                                response.code() == 403 -> "Access denied."
                                else -> "Booking failed. Please try again."
                            }
                            Toast.makeText(this@ReservationActivity, msg, Toast.LENGTH_LONG).show()
                        }  // end else
                    }  // end when
                }  // end else
            }
            override fun onFailure(call: Call<ReservationResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                btnReserve.isEnabled   = true
                Toast.makeText(this@ReservationActivity, "Connection failed", Toast.LENGTH_SHORT).show()
            }
        })
    }

    // ── Success dialog ────────────────────────────────────────────
    private fun showConfirmationDialog(resp: ReservationResponse, quantity: Int) {
        val info  = resp.pickup_info
        val total = medicinePrice * quantity

        val msg = buildString {
            appendLine("Reservation Confirmed!")
            appendLine()
            appendLine("Booking ID: ${resp.reservation_id}")
            appendLine("Medicine: ${info?.medicine_name ?: medicineName}")
            appendLine("Quantity: $quantity strip${if (quantity > 1) "s" else ""}")
            appendLine("Pharmacy: ${info?.pharmacy_name ?: pharmacyName}")
            appendLine("Amount to pay: Rs.${"%.2f".format(total)}")
            appendLine()
            appendLine("Collect within 24 hours.")
            appendLine("Pay at the pharmacy counter.")
            if (resp.prescription_required == true) {
                appendLine()
                appendLine("Remember to bring your prescription!")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Booking Confirmed")
            .setMessage(msg)
            .setPositiveButton("Done") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}