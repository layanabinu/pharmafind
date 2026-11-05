package com.example.pharmafind

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// ── Activity ──────────────────────────────────────────────────────
class ReservationHistoryActivity : AppCompatActivity() {

    private lateinit var rvReservations: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var btnBack: View

    private var userId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reservation_history)

        userId = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
            .getInt("user_id", -1)

        progressBar   = findViewById(R.id.progressBarResHistory)
        rvReservations= findViewById(R.id.rvReservationHistory)
        txtEmpty      = findViewById(R.id.txtResHistoryEmpty)
        btnBack       = findViewById(R.id.btnResHistoryBack)

        rvReservations.layoutManager = LinearLayoutManager(this)
        btnBack.setOnClickListener { finish() }

        loadReservations()
    }

    override fun onResume() {
        super.onResume()
        loadReservations()
    }

    private fun loadReservations() {
        if (userId == -1) {
            txtEmpty.visibility = View.VISIBLE
            txtEmpty.text = "Please log in to view reservations"
            return
        }
        progressBar.visibility = View.VISIBLE
        txtEmpty.visibility    = View.GONE

        ApiClient.apiService.getUserReservations(userId)
            .enqueue(object : Callback<ReservationsListResponse> {
                override fun onResponse(
                    call: Call<ReservationsListResponse>,
                    response: Response<ReservationsListResponse>
                ) {
                    progressBar.visibility = View.GONE
                    val body = response.body()
                    if (response.isSuccessful && body != null && body.reservations.isNotEmpty()) {
                        rvReservations.adapter = ReservationHistoryAdapter(
                            body.reservations.toMutableList(),
                            onCancel = { resId -> cancelReservation(resId) },
                            onCollected = { resId -> markCollected(resId) }
                        )
                    } else {
                        txtEmpty.visibility = View.VISIBLE
                        txtEmpty.text = "No reservations yet.\nSearch for a medicine and tap Reserve!"
                    }
                }
                override fun onFailure(call: Call<ReservationsListResponse>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    txtEmpty.visibility = View.VISIBLE
                    txtEmpty.text = "Couldn't load reservations"
                }
            })
    }

    private fun cancelReservation(reservationId: String) {
        AlertDialog.Builder(this)
            .setTitle("Cancel Reservation?")
            .setMessage("Reservation $reservationId will be cancelled and stock released.")
            .setPositiveButton("Cancel Reservation") { _, _ ->
                ApiClient.apiService.cancelReservation(reservationId)
                    .enqueue(object : Callback<Map<String, Any>> {
                        override fun onResponse(call: Call<Map<String, Any>>, response: Response<Map<String, Any>>) {
                            Toast.makeText(this@ReservationHistoryActivity,
                                "Reservation cancelled", Toast.LENGTH_SHORT).show()
                            loadReservations()
                        }
                        override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {
                            Toast.makeText(this@ReservationHistoryActivity,
                                "Failed to cancel", Toast.LENGTH_SHORT).show()
                        }
                    })
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun markCollected(reservationId: String) {
        ApiClient.apiService.markReservationCollected(reservationId)
            .enqueue(object : Callback<Map<String, Any>> {
                override fun onResponse(call: Call<Map<String, Any>>, response: Response<Map<String, Any>>) {
                    Toast.makeText(this@ReservationHistoryActivity,
                        "Marked as collected ✅", Toast.LENGTH_SHORT).show()
                    loadReservations()
                }
                override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {
                    Toast.makeText(this@ReservationHistoryActivity,
                        "Failed", Toast.LENGTH_SHORT).show()
                }
            })
    }
}

// ── Adapter ───────────────────────────────────────────────────────
class ReservationHistoryAdapter(
    private val items: MutableList<ReservationItem>,
    private val onCancel: (String) -> Unit,
    private val onCollected: (String) -> Unit
) : RecyclerView.Adapter<ReservationHistoryAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card:        CardView = v.findViewById(R.id.cardResHistory)
        val txtId:       TextView = v.findViewById(R.id.txtResId)
        val txtStatus:   TextView = v.findViewById(R.id.txtResStatus)
        val txtMedicine: TextView = v.findViewById(R.id.txtResMedicine)
        val txtPharmacy: TextView = v.findViewById(R.id.txtResPharmacy)
        val txtPrice:    TextView = v.findViewById(R.id.txtResPrice)
        val txtExpiry:   TextView = v.findViewById(R.id.txtResExpiry)
        val txtRx:       TextView = v.findViewById(R.id.txtResRx)
        val btnCancel:   Button   = v.findViewById(R.id.btnResCancel)
        val btnCollected:Button   = v.findViewById(R.id.btnResCollected)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reservation_history, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]

        h.txtId.text      = "📋 ${item.reservation_id}"
        h.txtMedicine.text = "${item.medicine_name}  ×${item.quantity}"
        h.txtPharmacy.text = "🏪 ${item.pharmacy_name}"
        h.txtPrice.text   = "₹${"%.2f".format(item.total_price)}"

        // Status badge
        val (statusText, statusColor, bgColor) = when (item.status) {
            "pending"   -> Triple("⏳ Pending Pickup", "#E65100", "#FFF3E0")
            "collected" -> Triple("✅ Collected",      "#1B5E20", "#E8F5E9")
            "cancelled" -> Triple("❌ Cancelled",      "#B71C1C", "#FFEBEE")
            "expired"   -> Triple("⌛ Expired",        "#546E7A", "#ECEFF1")
            else        -> Triple(item.status,         "#424242", "#F5F5F5")
        }
        h.txtStatus.text = statusText
        h.txtStatus.setTextColor(Color.parseColor(statusColor))
        h.txtStatus.setBackgroundColor(Color.parseColor(bgColor))

        // Expiry
        if (item.status == "pending" && item.expires_at != null) {
            h.txtExpiry.visibility = View.VISIBLE
            h.txtExpiry.text = "⏰ Collect by: ${item.expires_at.take(16).replace("T"," ")}"
        } else {
            h.txtExpiry.visibility = View.GONE
        }

        // Prescription badge
        if (item.prescription_required) {
            h.txtRx.visibility = View.VISIBLE
        } else {
            h.txtRx.visibility = View.GONE
        }

        // Action buttons — only for pending
        if (item.status == "pending") {
            h.btnCancel.visibility   = View.VISIBLE
            h.btnCollected.visibility = View.VISIBLE
            h.btnCancel.setOnClickListener   { onCancel(item.reservation_id) }
            h.btnCollected.setOnClickListener { onCollected(item.reservation_id) }
        } else {
            h.btnCancel.visibility   = View.GONE
            h.btnCollected.visibility = View.GONE
        }
    }
}