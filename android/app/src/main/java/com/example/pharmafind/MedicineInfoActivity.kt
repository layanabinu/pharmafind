package com.example.pharmafind

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * MedicineInfoActivity — Full medicine information screen.
 *
 * Shows:
 *  • Brand name, generic name, manufacturer, strength, form
 *  • What the medicine is used for (uses list from 1mg dataset)
 *  • Side effects (from 1mg dataset)
 *  • Prescription status warning
 *  • Patient review breakdown (Excellent / Average / Poor)
 *
 * Launched from:
 *  - PharmacyBottomSheetFragment "More Info" button
 *  - PharmacyCardAdapter "ℹ️" button in medicine search mode
 *  - HomeActivity FAB (replaces Recommendations)
 *  - MyMedicinesActivity medicine card long-press
 *
 * Intent extras required:
 *  "medicine_id"   Int    — database id
 *  "medicine_name" String — pre-fill name while loading
 */
class MedicineInfoActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────
    private lateinit var txtMedicineName:    TextView
    private lateinit var txtGenericName:     TextView
    private lateinit var txtManufacturer:    TextView
    private lateinit var txtStrengthForm:    TextView
    private lateinit var txtDescription:     TextView
    private lateinit var cardRx:             CardView
    private lateinit var txtRxWarning:       TextView
    private lateinit var layoutUses:         LinearLayout
    private lateinit var layoutSideEffects:  LinearLayout
    private lateinit var layoutReviews:      LinearLayout
    private lateinit var pbExcellent:        ProgressBar
    private lateinit var pbAverage:          ProgressBar
    private lateinit var pbPoor:             ProgressBar
    private lateinit var txtExcellentPct:    TextView
    private lateinit var txtAveragePct:      TextView
    private lateinit var txtPoorPct:         TextView
    private lateinit var txtReviewCount:     TextView
    private lateinit var progressBar:        ProgressBar
    private lateinit var layoutContent:      LinearLayout
    private lateinit var layoutError:        LinearLayout
    private lateinit var btnRetry:           Button
    private lateinit var btnBack:            ImageView
    private lateinit var btnSearch:          Button

    private var medicineId   = -1
    private var medicineName = ""

    // ── Lifecycle ─────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medicine_info)

        medicineId   = intent.getIntExtra("medicine_id", -1)
        medicineName = intent.getStringExtra("medicine_name") ?: ""

        bindViews()
        if (medicineId == -1) {
            showError("Medicine not found.")
            return
        }
        loadMedicineInfo()
    }

    // ── View binding ──────────────────────────────────────────────
    private fun bindViews() {
        txtMedicineName   = findViewById(R.id.txtInfoMedicineName)
        txtGenericName    = findViewById(R.id.txtInfoGenericName)
        txtManufacturer   = findViewById(R.id.txtInfoManufacturer)
        txtStrengthForm   = findViewById(R.id.txtInfoStrengthForm)
        txtDescription    = findViewById(R.id.txtInfoDescription)
        cardRx            = findViewById(R.id.cardInfoRx)
        txtRxWarning      = findViewById(R.id.txtInfoRxWarning)
        layoutUses        = findViewById(R.id.layoutInfoUses)
        layoutSideEffects = findViewById(R.id.layoutInfoSideEffects)
        layoutReviews     = findViewById(R.id.layoutInfoReviews)
        pbExcellent       = findViewById(R.id.pbInfoExcellent)
        pbAverage         = findViewById(R.id.pbInfoAverage)
        pbPoor            = findViewById(R.id.pbInfoPoor)
        txtExcellentPct   = findViewById(R.id.txtInfoExcellentPct)
        txtAveragePct     = findViewById(R.id.txtInfoAveragePct)
        txtPoorPct        = findViewById(R.id.txtInfoPoorPct)
        txtReviewCount    = findViewById(R.id.txtInfoReviewCount)
        progressBar       = findViewById(R.id.progressInfoBar)
        layoutContent     = findViewById(R.id.layoutInfoContent)
        layoutError       = findViewById(R.id.layoutInfoError)
        btnRetry          = findViewById(R.id.btnInfoRetry)
        btnBack           = findViewById(R.id.btnInfoBack)
        btnSearch         = findViewById(R.id.btnInfoSearch)

        txtMedicineName.text = medicineName.ifEmpty { "Loading…" }

        btnBack.setOnClickListener { finish() }
        btnRetry.setOnClickListener { loadMedicineInfo() }
        btnSearch.setOnClickListener {
            // Launch HomeActivity with search pre-filled
            val intent = android.content.Intent(this, HomeActivity::class.java).apply {
                putExtra("search_query", medicineName)
                flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(intent)
        }
    }

    // ── API call ──────────────────────────────────────────────────
    private fun loadMedicineInfo() {
        progressBar.visibility   = View.VISIBLE
        layoutContent.visibility = View.GONE
        layoutError.visibility   = View.GONE

        ApiClient.apiService.getMedicineInfo(medicineId)
            .enqueue(object : Callback<MedicineInfoDetail> {
                override fun onResponse(
                    call: Call<MedicineInfoDetail>,
                    response: Response<MedicineInfoDetail>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful) {
                        val info = response.body() ?: return showError("No data returned.")
                        populateUI(info)
                    } else {
                        showError("Could not load medicine information.")
                    }
                }
                override fun onFailure(call: Call<MedicineInfoDetail>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    showError("Connection failed. Check your internet.")
                }
            })
    }

    // ── Populate all fields ───────────────────────────────────────
    private fun populateUI(info: MedicineInfoDetail) {
        layoutContent.visibility = View.VISIBLE
        layoutError.visibility   = View.GONE

        // Header
        txtMedicineName.text  = info.brand_name
        txtGenericName.text   = info.generic_name.ifEmpty { "Generic name not available" }
        txtManufacturer.text  = if (info.manufacturer.isNotEmpty())
            "By ${info.manufacturer}" else ""
        txtManufacturer.visibility = if (info.manufacturer.isNotEmpty())
            View.VISIBLE else View.GONE

        val strengthForm = listOf(info.strength, info.form)
            .filter { it.isNotEmpty() }.joinToString(" · ")
        txtStrengthForm.text = strengthForm
        txtStrengthForm.visibility = if (strengthForm.isNotEmpty())
            View.VISIBLE else View.GONE

        txtDescription.text = info.description
        txtDescription.visibility = if (info.description.isNotEmpty())
            View.VISIBLE else View.GONE

        // Prescription warning
        if (info.is_prescription_required) {
            cardRx.visibility = View.VISIBLE
            txtRxWarning.text = "⚕️ Prescription Required — This medicine requires a " +
                    "valid doctor's prescription. Please consult your doctor before purchasing."
        } else {
            cardRx.visibility = View.GONE
        }

        // Uses
        layoutUses.removeAllViews()
        if (info.uses.isNotEmpty()) {
            addSectionHeader(layoutUses, "💊 What is it used for?")
            info.uses.take(10).forEach { use ->
                addBulletRow(layoutUses, use, 0xFF1565C0.toInt())
            }
            if (info.uses.size > 10) {
                addNoteRow(layoutUses, "…and ${info.uses.size - 10} more uses")
            }
        }

        // Side effects
        layoutSideEffects.removeAllViews()
        if (info.side_effects.isNotEmpty()) {
            addSectionHeader(layoutSideEffects, "⚠️ Possible Side Effects")
            info.side_effects.take(10).forEach { effect ->
                addBulletRow(layoutSideEffects, effect, 0xFFE65100.toInt())
            }
            if (info.side_effects.size > 10) {
                addNoteRow(layoutSideEffects,
                    "…and ${info.side_effects.size - 10} more side effects")
            }
            addNoteRow(layoutSideEffects,
                "Not everyone experiences side effects. Consult your doctor " +
                        "if you notice any persistent symptoms.")
        }

        // Reviews
        if (info.total_reviews > 0) {
            layoutReviews.visibility  = View.VISIBLE
            pbExcellent.progress      = info.excellent_review_pct
            pbAverage.progress        = info.average_review_pct
            pbPoor.progress           = info.poor_review_pct
            txtExcellentPct.text      = "${info.excellent_review_pct}%"
            txtAveragePct.text        = "${info.average_review_pct}%"
            txtPoorPct.text           = "${info.poor_review_pct}%"
            txtReviewCount.text       = "Based on patient reviews"
        } else {
            layoutReviews.visibility = View.GONE
        }

        // Update search button label
        btnSearch.text = "🔍 Find ${info.brand_name} Near Me"
        medicineName   = info.brand_name
    }

    // ── UI helpers ────────────────────────────────────────────────
    private fun addSectionHeader(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text      = title
            textSize  = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF212121.toInt())
            setPadding(0, 16, 0, 8)
        })
    }

    private fun addBulletRow(parent: LinearLayout, text: String, dotColor: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 4)
        }
        row.addView(TextView(this).apply {
            this.text     = "•"
            textSize      = 16f
            setTextColor(dotColor)
            setPadding(0, 0, 12, 0)
            layoutParams  = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        })
        row.addView(TextView(this).apply {
            this.text = text
            textSize  = 14f
            setTextColor(0xFF444444.toInt())
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        parent.addView(row)
    }

    private fun addNoteRow(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text  = text
            textSize   = 12f
            setTextColor(0xFF888888.toInt())
            setPadding(0, 8, 0, 4)
            setTypeface(null, android.graphics.Typeface.ITALIC)
        })
    }

    private fun showError(message: String) {
        progressBar.visibility   = View.GONE
        layoutContent.visibility = View.GONE
        layoutError.visibility   = View.VISIBLE
        findViewById<TextView>(R.id.txtInfoErrorMsg).text = message
    }
}