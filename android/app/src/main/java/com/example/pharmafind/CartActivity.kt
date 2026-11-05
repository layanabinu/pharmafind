package com.example.pharmafind

import android.os.Bundle
import android.view.LayoutInflater
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

/**
 * CartActivity — Multi-medicine reservation screen.
 *
 * Launched from PharmacyBottomSheetFragment or PharmacyCardAdapter
 * when the user wants to book multiple medicines from one pharmacy.
 *
 * The cart is passed as an ArrayList<CartEntry> via intent extra "cart_items".
 * Users can adjust quantities, remove items, and confirm the entire order at once.
 */
class CartActivity : AppCompatActivity() {

    private var pharmacyId   = -1
    private var pharmacyName = ""
    private var userId       = -1

    private lateinit var rvCart:         RecyclerView
    private lateinit var txtPharmacy:    TextView
    private lateinit var txtTotal:       TextView
    private lateinit var txtItemCount:   TextView
    private lateinit var btnConfirm:     Button
    private lateinit var btnBack:        ImageView
    private lateinit var progressBar:    ProgressBar
    private lateinit var edtNotes:       EditText
    private lateinit var layoutEmpty:    LinearLayout

    private val cartItems = mutableListOf<CartEntry>()
    private lateinit var adapter: CartAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cart)

        val prefs = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        userId       = prefs.getInt("user_id", -1)
        pharmacyId   = intent.getIntExtra("pharmacy_id", -1)
        pharmacyName = intent.getStringExtra("pharmacy_name") ?: ""

        // Restore cart items passed from caller
        @Suppress("DEPRECATION")
        val passed = intent.getSerializableExtra("cart_items")
        if (passed != null) {
            @Suppress("UNCHECKED_CAST")
            cartItems.addAll(passed as ArrayList<CartEntry>)
        }

        bindViews()
        setupRecyclerView()
        updateSummary()
    }

    private fun bindViews() {
        txtPharmacy  = findViewById(R.id.txtCartPharmacy)
        txtTotal     = findViewById(R.id.txtCartTotal)
        txtItemCount = findViewById(R.id.txtCartItemCount)
        btnConfirm   = findViewById(R.id.btnCartConfirm)
        btnBack      = findViewById(R.id.btnCartBack)
        progressBar  = findViewById(R.id.progressCart)
        edtNotes     = findViewById(R.id.edtCartNotes)
        layoutEmpty  = findViewById(R.id.layoutCartEmpty)
        rvCart       = findViewById(R.id.rvCart)

        txtPharmacy.text = "📍 $pharmacyName"
        btnBack.setOnClickListener { finish() }
        btnConfirm.setOnClickListener { confirmOrder() }
    }

    private fun setupRecyclerView() {
        adapter = CartAdapter(
            items    = cartItems,
            onQtyChange = { updateSummary() },
            onRemove    = { entry ->
                cartItems.remove(entry)
                adapter.notifyDataSetChanged()
                updateSummary()
            }
        )
        rvCart.layoutManager = LinearLayoutManager(this)
        rvCart.adapter = adapter
    }

    private fun updateSummary() {
        val total = cartItems.sumOf { it.totalPrice }
        val count = cartItems.sumOf { it.quantity }

        txtTotal.text     = "Total: ₹${"%.2f".format(total)}"
        txtItemCount.text = "$count strip(s) across ${cartItems.size} medicine(s)"
        btnConfirm.isEnabled = cartItems.isNotEmpty()
        layoutEmpty.visibility = if (cartItems.isEmpty()) View.VISIBLE else View.GONE
        rvCart.visibility      = if (cartItems.isEmpty()) View.GONE  else View.VISIBLE
    }

    private fun confirmOrder() {
        if (userId == -1) {
            Toast.makeText(this, "Please log in first", Toast.LENGTH_SHORT).show()
            return
        }
        if (pharmacyId == -1) {
            Toast.makeText(this, "Pharmacy not found", Toast.LENGTH_SHORT).show()
            return
        }
        if (cartItems.isEmpty()) {
            Toast.makeText(this, "Your cart is empty", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        btnConfirm.isEnabled   = false
        btnConfirm.text        = "Booking…"

        val request = MultiReservationRequest(
            user_id     = userId,
            pharmacy_id = pharmacyId,
            items       = cartItems.map {
                CartItemRequest(
                    medicine_id              = it.medicineId,
                    quantity                 = it.quantity,
                    is_prescription_required = it.isPrescriptionRequired
                )
            },
            notes = edtNotes.text.toString().trim().ifEmpty { null }
        )

        ApiClient.apiService.createMultiReservation(request)
            .enqueue(object : Callback<MultiReservationResponse> {
                override fun onResponse(
                    call: Call<MultiReservationResponse>,
                    response: Response<MultiReservationResponse>
                ) {
                    progressBar.visibility = View.GONE
                    btnConfirm.isEnabled   = true
                    btnConfirm.text        = "Confirm Order"

                    if (response.isSuccessful && response.body()?.success == true) {
                        showOrderConfirmed(response.body()!!)
                    } else {
                        val err = response.errorBody()?.string() ?: ""
                        val msg = when {
                            err.contains("User not found", ignoreCase = true) ->
                                "Session expired — please log in again."
                            err.contains("not available", ignoreCase = true) ->
                                "One or more medicines are no longer available."
                            err.contains("stock", ignoreCase = true) ->
                                "Not enough stock for one of the medicines."
                            err.contains("empty", ignoreCase = true) ->
                                "Cart is empty."
                            else -> "Booking failed. Please try again."
                        }
                        Toast.makeText(this@CartActivity, msg, Toast.LENGTH_LONG).show()
                    }
                }
                override fun onFailure(call: Call<MultiReservationResponse>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    btnConfirm.isEnabled   = true
                    btnConfirm.text        = "Confirm Order"
                    Toast.makeText(this@CartActivity,
                        "Connection failed. Check your internet.", Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun showOrderConfirmed(resp: MultiReservationResponse) {
        val summary = buildString {
            appendLine("✅ Order Confirmed!")
            appendLine()
            appendLine("Order ID:  ${resp.order_id}")
            appendLine("Pharmacy:  ${resp.pharmacy_name}")
            appendLine()
            appendLine("Items booked:")
            resp.items.forEach { item ->
                appendLine("  • ${item.medicine_name} × ${item.quantity}  →  ₹${"%.2f".format(item.total_price)}")
                appendLine("    Code: ${item.reservation_id}")
            }
            appendLine()
            appendLine("Total: ₹${"%.2f".format(resp.total_amount)}")
            appendLine()
            appendLine("Collect within 24 hours. Pay at the counter.")
            if (resp.prescription_required) {
                appendLine()
                appendLine(resp.prescription_reminder ?: "⚕️ Bring prescriptions for Rx medicines.")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Order Confirmed 🎉")
            .setMessage(summary)
            .setPositiveButton("Done") { _, _ ->
                setResult(RESULT_OK)
                finish()
            }
            .setCancelable(false)
            .show()
    }

    // ── Cart RecyclerView Adapter ──────────────────────────────────
    // CartAdapter declared below as a standalone class (not inner)
    // to avoid Kotlin's restriction on nested class declarations inside inner classes
}

private class CartAdapter(
    private val items: MutableList<CartEntry>,
    private val onQtyChange: () -> Unit,
    private val onRemove: (CartEntry) -> Unit
) : androidx.recyclerview.widget.RecyclerView.Adapter<CartAdapter.VH>() {

    class VH(v: android.view.View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
        val txtName:    android.widget.TextView     = v.findViewById(R.id.txtCartItemName)
        val txtStrength:android.widget.TextView     = v.findViewById(R.id.txtCartItemStrength)
        val txtPrice:   android.widget.TextView     = v.findViewById(R.id.txtCartItemPrice)
        val txtTotal:   android.widget.TextView     = v.findViewById(R.id.txtCartItemTotal)
        val txtRx:      android.widget.TextView     = v.findViewById(R.id.txtCartItemRx)
        val btnMinus:   android.widget.LinearLayout = v.findViewById(R.id.btnCartMinus)
        val txtQty:     android.widget.TextView     = v.findViewById(R.id.txtCartQty)
        val btnPlus:    android.widget.LinearLayout = v.findViewById(R.id.btnCartPlus)
        val btnRemove:  android.widget.ImageView    = v.findViewById(R.id.btnCartRemove)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, vt: Int) =
        VH(android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cart, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val entry = items[pos]
        h.txtName.text    = entry.medicineName
        h.txtStrength.text= entry.medicineStrength.ifEmpty { "—" }
        h.txtPrice.text   = "₹${"%.2f".format(entry.pricePerStrip)}/strip"
        h.txtQty.text     = entry.quantity.toString()
        h.txtTotal.text   = "₹${"%.2f".format(entry.totalPrice)}"
        h.txtRx.visibility= if (entry.isPrescriptionRequired)
            android.view.View.VISIBLE else android.view.View.GONE

        h.btnMinus.setOnClickListener {
            if (entry.quantity > 1) {
                entry.quantity--
                h.txtQty.text   = entry.quantity.toString()
                h.txtTotal.text = "₹${"%.2f".format(entry.totalPrice)}"
                onQtyChange()
            }
        }
        h.btnPlus.setOnClickListener {
            entry.quantity++
            h.txtQty.text   = entry.quantity.toString()
            h.txtTotal.text = "₹${"%.2f".format(entry.totalPrice)}"
            onQtyChange()
        }
        h.btnRemove.setOnClickListener { onRemove(entry) }
    }
}