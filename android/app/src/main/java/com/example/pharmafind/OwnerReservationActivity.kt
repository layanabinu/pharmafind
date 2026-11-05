package com.example.pharmafind

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * OwnerReservationsActivity
 * -------------------------
 * Shows the pharmacy owner ALL reservations for THEIR pharmacy,
 * fetched from GET /pharmacies/{id}/reservations.
 * Each row shows patient name, phone, medicine, qty, status.
 * Actions: Mark as Collected, Cancel (pending only).
 */
class OwnerReservationsActivity : AppCompatActivity() {

    private lateinit var rvReservations: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var txtReservHeader: TextView
    private lateinit var chipAll: TextView
    private lateinit var chipPending: TextView
    private lateinit var chipCollected: TextView

    private var pharmacyId   = -1
    private var pharmacyName = "My Pharmacy"

    private val allReservations  = mutableListOf<PharmacyReservation>()
    private val displayList      = mutableListOf<PharmacyReservation>()
    private lateinit var adapter: OwnerReservationAdapter
    private var activeFilter = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_owner_reservations)

        val prefs    = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        pharmacyId   = prefs.getInt("pharmacy_id", -1)
        pharmacyName = prefs.getString("pharmacy_name", "My Pharmacy") ?: "My Pharmacy"

        if (pharmacyId == -1) {
            Toast.makeText(this, "No pharmacy linked to account", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        bindViews()
        wireChips()
        loadReservations()
    }

    // ─────────────────────────────────────────────────────────
    // INIT
    // ─────────────────────────────────────────────────────────

    private fun bindViews() {
        rvReservations  = findViewById(R.id.rvOwnerReservations)
        progressBar     = findViewById(R.id.progressOwnerReserv)
        txtEmpty        = findViewById(R.id.txtOwnerReservEmpty)
        txtReservHeader = findViewById(R.id.txtOwnerReservHeader)
        chipAll         = findViewById(R.id.chipOwnerAll)
        chipPending     = findViewById(R.id.chipOwnerPending)
        chipCollected   = findViewById(R.id.chipOwnerCollected)

        txtReservHeader.text = "$pharmacyName — Reservations"

        rvReservations.layoutManager = LinearLayoutManager(this)
        adapter = OwnerReservationAdapter(
            items     = displayList,
            onCollect = { item -> markCollected(item) },
            onCancel  = { item -> cancelReservation(item) }
        )
        rvReservations.adapter = adapter

        findViewById<View>(R.id.btnOwnerReservBack).setOnClickListener { finish() }
    }

    private fun wireChips() {
        val chips = listOf(chipAll, chipPending, chipCollected)
        chips.forEach { chip ->
            chip.setOnClickListener {
                activeFilter = when (chip.id) {
                    R.id.chipOwnerPending   -> "pending"
                    R.id.chipOwnerCollected -> "collected"
                    else                    -> "all"
                }
                applyFilter()
                chips.forEach { c ->
                    val active = (c == chip)
                    c.setBackgroundColor(if (active) 0xFF1565C0.toInt() else 0xFFE3F2FD.toInt())
                    c.setTextColor(if (active) 0xFFFFFFFF.toInt() else 0xFF1565C0.toInt())
                }
            }
        }
        // Default active = "all"
        chipAll.setBackgroundColor(0xFF1565C0.toInt())
        chipAll.setTextColor(0xFFFFFFFF.toInt())
    }

    // ─────────────────────────────────────────────────────────
    // LOAD — pharmacy-scoped endpoint
    // ─────────────────────────────────────────────────────────

    private fun loadReservations() {
        progressBar.visibility = View.VISIBLE
        txtEmpty.visibility    = View.GONE

        ApiClient.apiService.getPharmacyReservations(pharmacyId)
            .enqueue(object : Callback<PharmacyReservationsResponse> {
                override fun onResponse(
                    call: Call<PharmacyReservationsResponse>,
                    response: Response<PharmacyReservationsResponse>
                ) {
                    progressBar.visibility = View.GONE
                    val items = response.body()?.reservations ?: emptyList()
                    allReservations.clear()
                    allReservations.addAll(items)
                    applyFilter()
                    updateChipBadges()
                }

                override fun onFailure(call: Call<PharmacyReservationsResponse>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    txtEmpty.text = "Failed to load reservations\nCheck your connection"
                    txtEmpty.visibility = View.VISIBLE
                }
            })
    }

    private fun updateChipBadges() {
        val pending   = allReservations.count { it.status == "pending" }
        val collected = allReservations.count { it.status == "collected" }
        chipAll.text       = "All (${allReservations.size})"
        chipPending.text   = "Pending ($pending)"
        chipCollected.text = "Collected ($collected)"
    }

    // ─────────────────────────────────────────────────────────
    // FILTER
    // ─────────────────────────────────────────────────────────

    private fun applyFilter() {
        displayList.clear()
        val filtered = when (activeFilter) {
            "pending"   -> allReservations.filter { it.status == "pending" }
            "collected" -> allReservations.filter { it.status == "collected" }
            else        -> allReservations
        }
        displayList.addAll(filtered)
        adapter.notifyDataSetChanged()

        if (displayList.isEmpty()) {
            txtEmpty.text = when (activeFilter) {
                "pending"   -> "No pending reservations"
                "collected" -> "No collected reservations"
                else        -> "No reservations yet"
            }
            txtEmpty.visibility = View.VISIBLE
        } else {
            txtEmpty.visibility = View.GONE
        }
    }

    // ─────────────────────────────────────────────────────────
    // ACTIONS
    // ─────────────────────────────────────────────────────────

    private fun markCollected(item: PharmacyReservation) {
        ApiClient.apiService.markReservationCollected(item.reservation_id)
            .enqueue(object : Callback<Map<String, Any>> {
                override fun onResponse(
                    call: Call<Map<String, Any>>,
                    response: Response<Map<String, Any>>
                ) {
                    if (response.isSuccessful) {
                        Toast.makeText(
                            this@OwnerReservationsActivity,
                            "✅ ${item.medicine_name} collected by ${item.patient_name}",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadReservations()
                    } else {
                        Toast.makeText(this@OwnerReservationsActivity,
                            "Failed to update", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {
                    Toast.makeText(this@OwnerReservationsActivity,
                        "Network error", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun cancelReservation(item: PharmacyReservation) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Cancel Reservation")
            .setMessage("Cancel ${item.medicine_name} reservation for ${item.patient_name}?")
            .setPositiveButton("Cancel Reservation") { _, _ ->
                ApiClient.apiService.cancelReservation(item.reservation_id)
                    .enqueue(object : Callback<Map<String, Any>> {
                        override fun onResponse(
                            call: Call<Map<String, Any>>,
                            response: Response<Map<String, Any>>
                        ) {
                            Toast.makeText(this@OwnerReservationsActivity,
                                "Reservation cancelled", Toast.LENGTH_SHORT).show()
                            loadReservations()
                        }
                        override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {
                            Toast.makeText(this@OwnerReservationsActivity,
                                "Network error", Toast.LENGTH_SHORT).show()
                        }
                    })
            }
            .setNegativeButton("Keep", null)
            .show()
    }
}

// ─────────────────────────────────────────────────────────────────────────
// ADAPTER  (uses PharmacyReservation — shows patient info)
// ─────────────────────────────────────────────────────────────────────────

class OwnerReservationAdapter(
    private val items: List<PharmacyReservation>,
    private val onCollect: (PharmacyReservation) -> Unit,
    private val onCancel:  (PharmacyReservation) -> Unit
) : RecyclerView.Adapter<OwnerReservationAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txtCode:    TextView = view.findViewById(R.id.txtOwnerReservCode)
        val txtMed:     TextView = view.findViewById(R.id.txtOwnerReservMed)
        val txtQty:     TextView = view.findViewById(R.id.txtOwnerReservQty)
        val txtStatus:  TextView = view.findViewById(R.id.txtOwnerReservStatus)
        val txtExpiry:  TextView = view.findViewById(R.id.txtOwnerReservExpiry)
        val txtPatient: TextView = view.findViewById(R.id.txtOwnerReservPatient)
        val btnCollect: Button   = view.findViewById(R.id.btnOwnerCollect)
        val btnCancel:  Button   = view.findViewById(R.id.btnOwnerCancelReserv)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_owner_reservation, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.txtCode.text    = "#${item.reservation_id.takeLast(8).uppercase()}"
        holder.txtMed.text     = item.medicine_name
        holder.txtQty.text     = "${item.quantity} strip(s)  •  ₹${"%.2f".format(item.total_price)}"
        holder.txtPatient.text = "👤 ${item.patient_name}" +
                if (item.patient_phone.isNotEmpty()) "  📞 ${item.patient_phone}" else ""
        holder.txtExpiry.text  = item.expires_at?.let { "Expires: ${it.take(16).replace("T", " ")}" } ?: ""

        val (statusText, statusColor, bgColor) = when (item.status) {
            "pending"   -> Triple("⏳ Pending pickup", 0xFFF57F17.toInt(), 0xFFFFFDE7.toInt())
            "collected" -> Triple("✅ Collected",       0xFF2E7D32.toInt(), 0xFFE8F5E9.toInt())
            "cancelled" -> Triple("❌ Cancelled",       0xFF757575.toInt(), 0xFFF5F5F5.toInt())
            "expired"   -> Triple("⌛ Expired",         0xFFB71C1C.toInt(), 0xFFFFEBEE.toInt())
            else        -> Triple(item.status,          0xFF212121.toInt(), 0xFFFFFFFF.toInt())
        }
        holder.txtStatus.text = statusText
        holder.txtStatus.setTextColor(statusColor)
        holder.itemView.setBackgroundColor(bgColor)

        val isPending = item.status == "pending"
        holder.btnCollect.visibility = if (isPending) View.VISIBLE else View.GONE
        holder.btnCancel.visibility  = if (isPending) View.VISIBLE else View.GONE

        holder.btnCollect.setOnClickListener { onCollect(item) }
        holder.btnCancel.setOnClickListener  { onCancel(item) }
    }

    override fun getItemCount() = items.size
}