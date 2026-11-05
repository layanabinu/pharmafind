package com.example.pharmafind

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

class PharmacyCardAdapter(
    private var pharmacies:       List<Pharmacy>,
    private var isMedicineSearch: Boolean,
    private val onCardClick:      (Pharmacy) -> Unit,
    private val onDirectionsClick:(Pharmacy) -> Unit,
    private val onCallClick:      (Pharmacy) -> Unit,
    private val onMoreClick:      (Pharmacy) -> Unit,
    private val onAddToCart:      (Pharmacy) -> Unit = {}
) : RecyclerView.Adapter<PharmacyCardAdapter.PharmacyViewHolder>() {

    class PharmacyViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card:                CardView     = view.findViewById(R.id.cardPharmacy)
        val rankBadge:           TextView     = view.findViewById(R.id.txtRank)
        val name:                TextView     = view.findViewById(R.id.txtPharmacyName)
        val distance:            TextView     = view.findViewById(R.id.txtDistance)
        val status:              TextView     = view.findViewById(R.id.txtStatus)
        val rating:              TextView     = view.findViewById(R.id.txtRating)
        val address:             TextView     = view.findViewById(R.id.txtPharmacyDetails)
        val price:               TextView     = view.findViewById(R.id.txtPrice)

        // Medicine banner (visible during medicine search)
        val layoutMedicineBanner: LinearLayout = view.findViewById(R.id.layoutMedicineBanner)
        val txtCardMedicineName:  TextView     = view.findViewById(R.id.txtCardMedicineName)
        val txtCardStock:         TextView     = view.findViewById(R.id.txtCardStock)

        // Button layouts
        val layoutNormalButtons:  LinearLayout = view.findViewById(R.id.layoutNormalButtons)
        val layoutMedicineButtons:LinearLayout = view.findViewById(R.id.layoutMedicineButtons)

        // Normal buttons
        val btnDirections: LinearLayout = view.findViewById(R.id.btnDirections)
        val btnCall:       LinearLayout = view.findViewById(R.id.btnCall)
        val btnMore:       LinearLayout = view.findViewById(R.id.btnMore)

        // Medicine buttons
        val btnBookNow:       LinearLayout = view.findViewById(R.id.btnBookNow)
        val btnAddToCart:     LinearLayout = view.findViewById(R.id.btnAddToCart)
        val btnDirectionsMed: LinearLayout = view.findViewById(R.id.btnDirectionsMed)
        val btnMoreMed:       LinearLayout = view.findViewById(R.id.btnMoreMed)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PharmacyViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_pharmacy_card, parent, false)
        return PharmacyViewHolder(view)
    }

    override fun onBindViewHolder(holder: PharmacyViewHolder, position: Int) {
        val pharmacy = pharmacies[position]
        val ctx      = holder.itemView.context

        // ── Rank badge ───────────────────────────────────────────
        val rankLabel = pharmacy.rank_label
        if (!rankLabel.isNullOrEmpty() && rankLabel !in listOf("Nearby", "Closed", "")) {
            holder.rankBadge.text = rankLabel
            holder.rankBadge.setBackgroundColor(when (rankLabel) {
                "Best match"   -> 0xFF1E88E5.toInt()
                "Good match"   -> 0xFF43A047.toInt()
                "Out of stock" -> 0xFFE53935.toInt()
                else           -> 0xFF9E9E9E.toInt()
            })
        } else {
            holder.rankBadge.text = "#${position + 1}"
            holder.rankBadge.setBackgroundColor(when (position) {
                0    -> ctx.getColor(R.color.primary)
                1    -> ctx.getColor(R.color.orange)
                else -> ctx.getColor(R.color.text_secondary)
            })
        }

        // ── Basic info ───────────────────────────────────────────
        holder.name.text     = pharmacy.name
        holder.distance.text = "%.1f km".format(pharmacy.distance_km)
        holder.address.text  = pharmacy.address?.takeIf { it.isNotEmpty() } ?: pharmacy.place
        holder.rating.text   = "★ %.1f".format(pharmacy.rating ?: 0.0)

        if (pharmacy.is_open) {
            holder.status.text = "● Open"
            holder.status.setTextColor(ctx.getColor(R.color.success))
        } else {
            holder.status.text = "● Closed"
            holder.status.setTextColor(ctx.getColor(R.color.error))
        }

        // ── Medicine search mode ─────────────────────────────────
        if (isMedicineSearch && pharmacy.price != null) {
            // Show medicine banner
            holder.layoutMedicineBanner.visibility = View.VISIBLE

            val medName = pharmacy.medicine_name
                ?.takeIf { it.isNotEmpty() } ?: "Medicine"
            val strength = pharmacy.medicine_strength
                ?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
            holder.txtCardMedicineName.text = "$medName$strength"
            holder.price.text = "₹${"%.0f".format(pharmacy.price)}"

            val stock = pharmacy.stock ?: 0
            // Show availability only — no stock numbers shown to users
            val (stockText, stockColor) = when {
                stock == 0  -> Pair("✗ Not Available",  0xFFE53935.toInt())
                stock <= 10 -> Pair("⚠ Limited Supply", 0xFFF57C00.toInt())
                else        -> Pair("✓ Available",       0xFF43A047.toInt())
            }
            holder.txtCardStock.text = stockText
            holder.txtCardStock.setTextColor(stockColor)

            // Show medicine button set, hide normal buttons
            holder.layoutNormalButtons.visibility  = View.GONE
            holder.layoutMedicineButtons.visibility = View.VISIBLE

            // Book button — direct to reservation
            if (stock > 0 && (pharmacy.medicine_id ?: -1) != -1) {
                holder.btnBookNow.alpha = 1.0f
                holder.btnBookNow.isEnabled = true
                holder.btnBookNow.setOnClickListener {
                    val intent = Intent(ctx, ReservationActivity::class.java).apply {
                        putExtra("pharmacy_id",             pharmacy.id)
                        putExtra("medicine_id",             pharmacy.medicine_id ?: -1)
                        putExtra("pharmacy_name",           pharmacy.name)
                        putExtra("medicine_name",           pharmacy.medicine_name ?: "")
                        putExtra("medicine_price",          pharmacy.price ?: 0.0)
                        putExtra("medicine_stock",          stock)
                        putExtra("medicine_strength",       pharmacy.medicine_strength ?: "")
                        putExtra("is_prescription_required",pharmacy.is_prescription_required ?: false)
                    }
                    ctx.startActivity(intent)
                }
            } else {
                // Out of stock — grey out book button
                holder.btnBookNow.alpha = 0.4f
                holder.btnBookNow.isEnabled = false
                holder.btnBookNow.setOnClickListener(null)
                // Find the label TextView inside and update text
                (holder.btnBookNow.getChildAt(0) as? TextView)?.text = "✗ Out of Stock"
            }

            holder.btnAddToCart.setOnClickListener    { onAddToCart(pharmacy) }
            holder.btnDirectionsMed.setOnClickListener { onDirectionsClick(pharmacy) }
            // ℹ️ button → open Medicine Info screen
            holder.btnMoreMed.setOnClickListener {
                val ctx = holder.itemView.context
                val intent = android.content.Intent(ctx, MedicineInfoActivity::class.java).apply {
                    putExtra("medicine_id",   pharmacy.medicine_id ?: -1)
                    putExtra("medicine_name", pharmacy.medicine_name ?: pharmacy.name)
                }
                ctx.startActivity(intent)
            }

        } else {
            // Normal mode — hide medicine banner, show normal buttons
            holder.layoutMedicineBanner.visibility  = View.GONE
            holder.layoutNormalButtons.visibility   = View.VISIBLE
            holder.layoutMedicineButtons.visibility = View.GONE

            holder.btnDirections.setOnClickListener { onDirectionsClick(pharmacy) }
            holder.btnCall.setOnClickListener       { onCallClick(pharmacy) }
            holder.btnMore.setOnClickListener       { onMoreClick(pharmacy) }
        }

        // Card tap always opens detail sheet
        holder.card.setOnClickListener { onMoreClick(pharmacy) }
    }

    override fun getItemCount() = pharmacies.size

    fun updateData(newPharmacies: List<Pharmacy>, isMedicineSearch: Boolean) {
        this.pharmacies       = newPharmacies
        this.isMedicineSearch = isMedicineSearch
        notifyDataSetChanged()
    }
}