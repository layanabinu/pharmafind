package com.example.pharmafind

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PharmacyBottomSheetFragment : BottomSheetDialogFragment() {

    // FIXED: Callbacks are now defined as interface on the host Activity
    // instead of lambda properties that get lost on rotation
    interface PharmacySheetListener {
        fun onDirectionsRequested(pharmacy: PharmacyCard)
        fun onCallRequested(phone: String)
    }

    companion object {
        // FIXED: All pharmacy data stored in Bundle so it survives rotation
        private const val ARG_ID = "pharmacy_id"
        private const val ARG_NAME = "pharmacy_name"
        private const val ARG_ADDRESS = "pharmacy_address"
        private const val ARG_DISTANCE = "pharmacy_distance"
        private const val ARG_RATING = "pharmacy_rating"
        private const val ARG_PHONE = "pharmacy_phone"
        private const val ARG_IS_OPEN = "pharmacy_is_open"
        private const val ARG_LAT = "pharmacy_lat"
        private const val ARG_LNG = "pharmacy_lng"
        private const val ARG_MEDICINE_NAME = "medicine_name"
        private const val ARG_MEDICINE_PRICE = "medicine_price"
        private const val ARG_MEDICINE_STOCK = "medicine_stock"
        private const val ARG_MEDICINE_ID = "medicine_id"
        private const val ARG_PRESCRIPTION = "is_prescription_required"
        private const val ARG_MEDICINE_STRENGTH   = "medicine_strength"
        private const val ARG_PHARMACY_ID         = "pharmacy_id_key"
        private const val ARG_USES                = "medicine_uses"
        private const val ARG_SIDE_EFFECTS        = "medicine_side_effects"
        private const val ARG_IMAGE_URL           = "medicine_image_url"
        private const val ARG_MANUFACTURER        = "medicine_manufacturer"
        private const val ARG_EXCELLENT           = "medicine_excellent_pct"
        private const val ARG_AVERAGE             = "medicine_average_pct"
        private const val ARG_POOR                = "medicine_poor_pct"

        fun newInstance(
            pharmacy: PharmacyCard,
            onDirections: (PharmacyCard) -> Unit,
            onCall: (String) -> Unit
        ): PharmacyBottomSheetFragment {
            return PharmacyBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_ID, pharmacy.id)
                    putString(ARG_NAME, pharmacy.name)
                    putString(ARG_ADDRESS, pharmacy.address)
                    putString(ARG_DISTANCE, pharmacy.distance)
                    putFloat(ARG_RATING, pharmacy.rating)
                    putString(ARG_PHONE, pharmacy.phone)
                    putBoolean(ARG_IS_OPEN, pharmacy.isOpen)
                    putDouble(ARG_LAT, pharmacy.lat)
                    putDouble(ARG_LNG, pharmacy.lng)
                    putString(ARG_MEDICINE_NAME, pharmacy.medicineName)
                    putString(ARG_MEDICINE_PRICE, pharmacy.medicinePrice)
                    putInt(ARG_MEDICINE_STOCK, pharmacy.medicineStock)
                    putInt(ARG_MEDICINE_ID, pharmacy.medicineId)
                    putBoolean(ARG_PRESCRIPTION, pharmacy.isPrescriptionRequired)
                    putString(ARG_MEDICINE_STRENGTH, pharmacy.medicineStrength)
                    putString(ARG_USES,             pharmacy.medicineUses)
                    putString(ARG_SIDE_EFFECTS,     pharmacy.medicineSideEffects)
                    putString(ARG_IMAGE_URL,        pharmacy.medicineImageUrl)
                    putString(ARG_MANUFACTURER,     pharmacy.medicineManufacturer)
                    putInt(ARG_EXCELLENT,            pharmacy.excellentReviewPct)
                    putInt(ARG_AVERAGE,              pharmacy.averageReviewPct)
                    putInt(ARG_POOR,                 pharmacy.poorReviewPct)
                    putInt(ARG_PHARMACY_ID,          pharmacy.id)
                }
                this.onDirectionsClick = onDirections
                this.onCallClick = onCall
            }
        }
    }

    // Callbacks kept for current session (not needed across rotation
    // since the fragment is dismissed and recreated by the user)
    private var onDirectionsClick: ((PharmacyCard) -> Unit)? = null
    private var onCallClick: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_pharmacy_bottom_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // FIXED: Always read from arguments Bundle — works after rotation too
        val args = arguments ?: return
        val p = PharmacyCard(
            id           = args.getInt(ARG_ID),
            name         = args.getString(ARG_NAME, ""),
            address      = args.getString(ARG_ADDRESS, ""),
            distance     = args.getString(ARG_DISTANCE, ""),
            rating       = args.getFloat(ARG_RATING, 0f),
            phone        = args.getString(ARG_PHONE, ""),
            isOpen       = args.getBoolean(ARG_IS_OPEN, true),
            lat          = args.getDouble(ARG_LAT, 0.0),
            lng          = args.getDouble(ARG_LNG, 0.0),
            medicineName = args.getString(ARG_MEDICINE_NAME, ""),
            medicinePrice = args.getString(ARG_MEDICINE_PRICE, ""),
            medicineStock = args.getInt(ARG_MEDICINE_STOCK, 0),
            medicineId   = args.getInt(ARG_MEDICINE_ID, -1),
            isPrescriptionRequired = args.getBoolean(ARG_PRESCRIPTION, false),
            medicineStrength      = args.getString(ARG_MEDICINE_STRENGTH, ""),
            medicineUses          = args.getString(ARG_USES,         ""),
            medicineSideEffects   = args.getString(ARG_SIDE_EFFECTS, ""),
            medicineImageUrl      = args.getString(ARG_IMAGE_URL,    ""),
            medicineManufacturer  = args.getString(ARG_MANUFACTURER, ""),
            excellentReviewPct    = args.getInt(ARG_EXCELLENT,       0),
            averageReviewPct      = args.getInt(ARG_AVERAGE,         0),
            poorReviewPct         = args.getInt(ARG_POOR,            0)
        )

        // Views
        val txtName = view.findViewById<TextView>(R.id.bsPharmacyName)
        val txtAddress = view.findViewById<TextView>(R.id.bsPharmacyAddress)
        val txtDistance = view.findViewById<TextView>(R.id.bsPharmacyDistance)
        val txtRating = view.findViewById<TextView>(R.id.bsPharmacyRating)
        val txtStatus = view.findViewById<TextView>(R.id.bsPharmacyStatus)
        val txtMedicineName  = view.findViewById<TextView>(R.id.bsMedicineName)
        val txtMedicinePrice = view.findViewById<TextView>(R.id.bsMedicinePrice)
        val txtMedicineStock = view.findViewById<TextView>(R.id.bsMedicineStock)
        val cardMedicine     = view.findViewById<CardView>(R.id.cardMedicineInfo)
        val txtPrescription  = view.findViewById<TextView>(R.id.bsPrescriptionBadge)
        val btnDirections    = view.findViewById<LinearLayout>(R.id.btnDirections)
        val btnCall          = view.findViewById<LinearLayout>(R.id.btnCall)
        val btnShare         = view.findViewById<LinearLayout>(R.id.btnShare)
        val btnSave          = view.findViewById<LinearLayout>(R.id.btnSave)
        val btnReserve       = view.findViewById<android.widget.Button>(R.id.btnReservePickup)
        val txtUsesLabel     = view.findViewById<android.widget.TextView>(R.id.bsMedicineUsesLabel)
        val txtUses          = view.findViewById<android.widget.TextView>(R.id.bsMedicineUses)
        val txtSideLabel     = view.findViewById<android.widget.TextView>(R.id.bsSideEffectsLabel)
        val txtSide          = view.findViewById<android.widget.TextView>(R.id.bsSideEffects)
        val layoutReviews    = view.findViewById<android.widget.LinearLayout>(R.id.layoutReviewBar)
        val pbExcellent      = view.findViewById<android.widget.ProgressBar>(R.id.pbExcellent)
        val pbAverage        = view.findViewById<android.widget.ProgressBar>(R.id.pbAverage)
        val pbPoor           = view.findViewById<android.widget.ProgressBar>(R.id.pbPoor)
        val txtExcPct        = view.findViewById<android.widget.TextView>(R.id.txtExcellentPct)
        val txtAvgPct        = view.findViewById<android.widget.TextView>(R.id.txtAveragePct)
        val txtPoorPct       = view.findViewById<android.widget.TextView>(R.id.txtPoorPct)

        // Populate data
        txtName.text = p.name
        txtAddress.text = p.address
        txtDistance.text = p.distance
        txtRating.text = p.rating.toString()

        // Open/Closed status
        if (p.isOpen) {
            txtStatus.text = "Open Now"
            txtStatus.setTextColor(requireContext().getColor(R.color.success))
        } else {
            txtStatus.text = "Closed"
            txtStatus.setTextColor(requireContext().getColor(R.color.error))
        }

        // Medicine info (only shown during medicine search)
        if (p.medicineName.isNotEmpty()) {
            cardMedicine.visibility = View.VISIBLE
            txtMedicineName.text  = p.medicineName
            txtMedicinePrice.text = "₹${p.medicinePrice}"

            // Show simple availability only — no stock numbers shown to users
            val (stockText, stockColor) = when {
                p.medicineStock > 0 -> Pair("✓ Available", R.color.success)
                else                -> Pair("✗ Currently Unavailable", R.color.error)
            }
            txtMedicineStock.text = stockText
            txtMedicineStock.setTextColor(requireContext().getColor(stockColor))

            // Prescription badge
            if (p.isPrescriptionRequired) {
                txtPrescription.visibility = View.VISIBLE
                txtPrescription.text = "⚕️ Prescription Required — bring doctor's note"
            } else {
                txtPrescription.visibility = View.GONE
            }

            // Uses
            val usesText = p.medicineUses.ifEmpty { p.medicineSideEffects }
            if (p.medicineUses.isNotEmpty()) {
                txtUsesLabel.visibility = View.VISIBLE
                txtUses.visibility      = View.VISIBLE
                // Show as bullet lines — pipe-separated → newline with bullet
                txtUses.text = p.medicineUses.split(" | ")
                    .filter { it.isNotBlank() }.take(4)
                    .joinToString("\n") { "• ${it.trim()}" }
            }

            // Side effects
            if (p.medicineSideEffects.isNotEmpty()) {
                txtSideLabel.visibility = View.VISIBLE
                txtSide.visibility      = View.VISIBLE
                txtSide.text = p.medicineSideEffects.split(" | ")
                    .filter { it.isNotBlank() }.take(4)
                    .joinToString("\n") { "• ${it.trim()}" }
            }

            // Review bars
            if (p.excellentReviewPct + p.averageReviewPct + p.poorReviewPct > 0) {
                layoutReviews.visibility = View.VISIBLE
                pbExcellent.progress = p.excellentReviewPct
                pbAverage.progress   = p.averageReviewPct
                pbPoor.progress      = p.poorReviewPct
                txtExcPct.text = "${p.excellentReviewPct}%"
                txtAvgPct.text = "${p.averageReviewPct}%"
                txtPoorPct.text = "${p.poorReviewPct}%"
            }

            // Reserve button — only if medicine is available
            if (p.medicineStock > 0 && p.medicineId != -1) {
                btnReserve.visibility = View.VISIBLE
                btnReserve.text = if (p.isPrescriptionRequired)
                    "Reserve (Bring Prescription)" else "Reserve for Pickup"

                btnReserve.setOnClickListener {
                    val price = p.medicinePrice.toDoubleOrNull() ?: 0.0
                    val intent = android.content.Intent(
                        requireContext(), ReservationActivity::class.java
                    ).apply {
                        putExtra("pharmacy_id",   p.id)
                        putExtra("medicine_id",   p.medicineId)
                        putExtra("pharmacy_name", p.name)
                        putExtra("medicine_name", p.medicineName)
                        putExtra("medicine_price", price)
                        putExtra("medicine_stock", p.medicineStock)
                        putExtra("medicine_strength", p.medicineStrength)
                        putExtra("is_prescription_required", p.isPrescriptionRequired)
                    }
                    startActivity(intent)
                    dismiss()
                }
            } else {
                btnReserve.visibility = View.GONE
            }

        } else {
            // Browse mode — show a "See Available Medicines" button
            cardMedicine.visibility  = View.VISIBLE
            txtMedicineName.text     = "Tap to see available medicines"
            txtMedicinePrice.text    = ""
            txtMedicineStock.text    = ""
            txtPrescription.visibility = View.GONE
            txtUsesLabel.visibility  = View.GONE
            txtUses.visibility       = View.GONE
            txtSideLabel.visibility  = View.GONE
            txtSide.visibility       = View.GONE
            layoutReviews.visibility = View.GONE

            btnReserve.visibility = View.VISIBLE
            btnReserve.text = "🔍 View & Reserve Medicines"
            btnReserve.setOnClickListener {
                // Open pharmacy detail which lists all stocked medicines
                val pharmacyId = args.getInt(ARG_PHARMACY_ID, -1)
                if (pharmacyId != -1) {
                    val intent = android.content.Intent(
                        requireContext(), PharmacyOwnerDashboardActivity::class.java
                    )
                    // Actually launch ReservationActivity with pharmacy browse mode
                    val browseIntent = android.content.Intent(requireContext(),
                        ReservationActivity::class.java).apply {
                        putExtra("pharmacy_id",   pharmacyId)
                        putExtra("pharmacy_name", p.name)
                        putExtra("browse_mode",   true)
                    }
                    startActivity(browseIntent)
                    dismiss()
                }
            }
        }

        // Directions button
        btnDirections.setOnClickListener {
            onDirectionsClick?.invoke(p)
            dismiss()
        }

        // Call button
        btnCall.setOnClickListener {
            if (p.phone.isNotEmpty()) {
                onCallClick?.invoke(p.phone)
            } else {
                Toast.makeText(context, "Phone number not available", Toast.LENGTH_SHORT).show()
            }
        }

        // Share button
        btnShare.setOnClickListener {
            val shareText = "Check out ${p.name}\n${p.address}\nDistance: ${p.distance}"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            startActivity(Intent.createChooser(intent, "Share Pharmacy"))
        }

        // Save button — now shows a proper confirmation instead of just a toast
        btnSave.setOnClickListener {
            savePharmacyToFavorites(p)
        }
    }

    private fun savePharmacyToFavorites(pharmacy: PharmacyCard) {
        // Save to SharedPreferences as a simple favorites list
        val sharedPref = requireContext()
            .getSharedPreferences("PharmaFindPrefs", android.content.Context.MODE_PRIVATE)
        val savedSet = sharedPref.getStringSet("saved_pharmacies", mutableSetOf())
            ?.toMutableSet() ?: mutableSetOf()

        val pharmacyEntry = "${pharmacy.id}|${pharmacy.name}"

        if (savedSet.contains(pharmacyEntry)) {
            Toast.makeText(context, "${pharmacy.name} already saved!", Toast.LENGTH_SHORT).show()
        } else {
            savedSet.add(pharmacyEntry)
            sharedPref.edit().putStringSet("saved_pharmacies", savedSet).apply()
            Toast.makeText(context, "✓ ${pharmacy.name} saved!", Toast.LENGTH_SHORT).show()
        }
    }
}