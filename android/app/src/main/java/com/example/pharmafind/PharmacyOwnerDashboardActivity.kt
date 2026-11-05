package com.example.pharmafind

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PharmacyOwnerDashboardActivity : AppCompatActivity() {

    private lateinit var rvInventory: RecyclerView
    private lateinit var inventoryAdapter: OwnerInventoryAdapter
    private lateinit var txtPharmacyName: TextView
    private lateinit var txtTotalMedicines: TextView
    private lateinit var txtLowStockCount: TextView
    private lateinit var txtCriticalCount: TextView
    private lateinit var fabAddMedicine: FloatingActionButton
    private lateinit var progressBar: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var alertBanner: LinearLayout
    private lateinit var txtAlertMessage: TextView

    private var pharmacyId: Int = -1
    private var pharmacyName: String = ""
    private var inventoryList = mutableListOf<OwnerInventoryItem>()
    private var filteredList  = mutableListOf<OwnerInventoryItem>()   // search-filtered view
    private var stockAlerts = mutableListOf<StockAlert>()
    private lateinit var edtSearch: com.google.android.material.textfield.TextInputEditText
    private lateinit var txtSearchCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pharmacy_owner_dashboard)

        val prefs = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        pharmacyId   = prefs.getInt("pharmacy_id", -1)
        pharmacyName = prefs.getString("pharmacy_name", "My Pharmacy") ?: "My Pharmacy"

        if (pharmacyId == -1) {
            Toast.makeText(this, "Pharmacy not linked to account", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        initViews()
        loadDashboard()
    }

    // =============================================
    // INIT
    // =============================================

    private fun initViews() {
        txtPharmacyName   = findViewById(R.id.txtOwnerPharmacyName)
        txtTotalMedicines = findViewById(R.id.txtTotalMedicines)
        txtLowStockCount  = findViewById(R.id.txtLowStockCount)
        txtCriticalCount  = findViewById(R.id.txtCriticalCount)
        progressBar       = findViewById(R.id.progressBarOwner)
        txtEmpty          = findViewById(R.id.txtOwnerEmpty)
        alertBanner       = findViewById(R.id.alertBanner)
        txtAlertMessage   = findViewById(R.id.txtAlertMessage)
        fabAddMedicine    = findViewById(R.id.fabAddInventory)

        txtPharmacyName.text = pharmacyName

        rvInventory = findViewById(R.id.rvOwnerInventory)
        rvInventory.layoutManager = LinearLayoutManager(this)

        inventoryAdapter = OwnerInventoryAdapter(
            items       = filteredList,
            onEdit      = { item -> showEditInventoryDialog(item) },
            onDelete    = { item -> confirmDeleteInventory(item) },
            onViewChart = { item -> showStockChart(item) },
            onSave      = { item, stock, price, callback ->
                updateInventoryItemWithCallback(item, stock, price, callback)
            }
        )
        rvInventory.adapter = inventoryAdapter

        fabAddMedicine.setOnClickListener { showAddInventoryDialog() }

        // Search bar
        edtSearch      = findViewById(R.id.edtInventorySearch)
        txtSearchCount = findViewById(R.id.txtInventorySearchCount)
        edtSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterInventory(s.toString())
            }
        })

        // Back button
        findViewById<ImageView>(R.id.btnOwnerBack).setOnClickListener { finish() }

        // Alert banner tap — scroll to first critical item
        alertBanner.setOnClickListener { scrollToFirstAlert() }
    }

    // =============================================
    // LOAD DASHBOARD DATA
    // =============================================

    private fun loadDashboard() {
        progressBar.visibility = View.VISIBLE
        txtEmpty.visibility = View.GONE

        // Load inventory + stock alerts in parallel
        loadInventory()
        loadStockAlerts()
    }

    private fun loadInventory() {
        ApiClient.apiService.getPharmacyInventory(pharmacyId)
            .enqueue(object : Callback<PharmacyDetailResponse> {
                override fun onResponse(
                    call: Call<PharmacyDetailResponse>,
                    response: Response<PharmacyDetailResponse>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful) {
                        val body = response.body()
                        val medicines = body?.medicines ?: emptyList()

                        inventoryList.clear()
                        inventoryList.addAll(medicines.map { m ->
                            OwnerInventoryItem(
                                medicineId   = m.medicine_id,
                                medicineName = m.name,
                                strength     = m.strength,
                                stock        = m.stock,
                                price        = m.price,
                                alertLevel   = "ok"
                            )
                        })

                        // Re-apply any active search filter
                        filterInventory(edtSearch.text.toString())
                        updateSummaryStats()
                        inventoryAdapter.notifyDataSetChanged()

                        if (inventoryList.isEmpty()) {
                            txtEmpty.text = "No medicines in inventory\nTap + to add your first medicine"
                            txtEmpty.visibility = View.VISIBLE
                        }
                    }
                }

                override fun onFailure(call: Call<PharmacyDetailResponse>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@PharmacyOwnerDashboardActivity,
                        "Failed to load inventory", Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun loadStockAlerts() {
        ApiClient.apiService.getStockAlerts()
            .enqueue(object : Callback<StockAlertsResponse> {
                override fun onResponse(
                    call: Call<StockAlertsResponse>,
                    response: Response<StockAlertsResponse>
                ) {
                    if (!response.isSuccessful) return
                    val body = response.body() ?: return

                    // Filter alerts to only this pharmacy
                    val myAlerts = (body.critical + body.warning)
                        .filter { it.pharmacy_id == pharmacyId }

                    stockAlerts.clear()
                    stockAlerts.addAll(myAlerts)

                    // Apply alert levels to inventory items
                    for (alert in myAlerts) {
                        val item = inventoryList.find { it.medicineId == alert.medicine_id }
                        item?.alertLevel = alert.alert_level
                        item?.daysUntilLow = alert.days_until_low
                        item?.alertMessage = alert.message
                    }

                    inventoryAdapter.notifyDataSetChanged()
                    updateSummaryStats()
                    showAlertBanner(body.critical.filter { it.pharmacy_id == pharmacyId })
                }

                override fun onFailure(call: Call<StockAlertsResponse>, t: Throwable) {
                    // Alerts are optional — silently skip if LSTM not trained yet
                }
            })
    }

    // =============================================
    // SUMMARY STATS
    // =============================================

    private fun updateSummaryStats() {
        txtTotalMedicines.text = inventoryList.size.toString()

        val lowStock  = inventoryList.count { it.alertLevel == "warning" }
        val critical  = inventoryList.count { it.alertLevel == "critical" }

        txtLowStockCount.text = lowStock.toString()
        txtCriticalCount.text = critical.toString()

        // Colour code critical count
        val critColor = if (critical > 0) getColor(R.color.error) else getColor(R.color.success)
        txtCriticalCount.setTextColor(critColor)
    }

    private fun showAlertBanner(criticalAlerts: List<StockAlert>) {
        if (criticalAlerts.isEmpty()) {
            alertBanner.visibility = View.GONE
            return
        }
        alertBanner.visibility = View.VISIBLE
        val first = criticalAlerts.first()
        txtAlertMessage.text = if (criticalAlerts.size == 1) {
            "⚠️ ${first.medicine_name} running low (${first.current_stock} left)"
        } else {
            "⚠️ ${criticalAlerts.size} medicines critically low — tap to review"
        }
    }

    private fun scrollToFirstAlert() {
        val idx = inventoryList.indexOfFirst { it.alertLevel == "critical" }
        if (idx >= 0) rvInventory.smoothScrollToPosition(idx)
    }

    // =============================================
    // ADD INVENTORY DIALOG
    // =============================================

    private fun showAddInventoryDialog() {
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_owner_inventory, null)

        val edtMedicine = dialogView.findViewById<TextInputEditText>(R.id.edtOwnerMedicineName)
        val edtStock    = dialogView.findViewById<TextInputEditText>(R.id.edtOwnerStock)
        val edtPrice    = dialogView.findViewById<TextInputEditText>(R.id.edtOwnerPrice)

        AlertDialog.Builder(this)
            .setTitle("➕ Add Medicine to Inventory")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name  = edtMedicine.text.toString().trim()
                val stock = edtStock.text.toString().toIntOrNull() ?: 0
                val price = edtPrice.text.toString().toDoubleOrNull() ?: 0.0

                if (name.isEmpty()) {
                    Toast.makeText(this, "Enter medicine name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                addInventoryItem(name, stock, price)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =============================================
    // EDIT INVENTORY DIALOG
    // =============================================

    private fun showEditInventoryDialog(item: OwnerInventoryItem) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val lblInfo = android.widget.TextView(this).apply {
            text = "${item.medicineName}  —  current: ${item.stock} strips"
            textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF212121.toInt()); setPadding(0,0,0,16)
        }
        val lblStock = android.widget.TextView(this).apply {
            text = "New stock quantity (strips)"; textSize = 12f
            setTextColor(0xFF555555.toInt())
        }
        val edtStock = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(item.stock.toString()); selectAll()
            setPadding(0, 8, 0, 8)
        }
        val lblPreset = android.widget.TextView(this).apply {
            text = "Quick add:"; textSize = 11f
            setTextColor(0xFF9E9E9E.toInt()); setPadding(0, 12, 0, 6)
        }
        val presetsRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 12)
        }
        for (delta in listOf(10, 25, 50, 100)) {
            presetsRow.addView(android.widget.Button(this).apply {
                text = "+$delta"; textSize = 11f
                setBackgroundColor(0xFFE3F2FD.toInt())
                setTextColor(0xFF1565C0.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(0,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 6, 0) }
                setOnClickListener {
                    edtStock.setText(((edtStock.text.toString().toIntOrNull() ?: 0) + delta).toString())
                }
            })
        }
        presetsRow.addView(android.widget.Button(this).apply {
            text = "Zero"; textSize = 11f
            setBackgroundColor(0xFFFFEBEE.toInt()); setTextColor(0xFFE53935.toInt())
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { edtStock.setText("0") }
        })
        val lblPrice = android.widget.TextView(this).apply {
            text = "Price per strip (₹)"; textSize = 12f
            setTextColor(0xFF555555.toInt()); setPadding(0, 8, 0, 0)
        }
        val edtPrice = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.2f".format(item.price)); setPadding(0, 8, 0, 8)
        }
        layout.apply {
            addView(lblInfo); addView(lblStock); addView(edtStock)
            addView(lblPreset); addView(presetsRow)
            addView(lblPrice); addView(edtPrice)
        }
        AlertDialog.Builder(this)
            .setTitle("✏️ Update Inventory")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newStock = edtStock.text.toString().toIntOrNull() ?: item.stock
                val newPrice = edtPrice.text.toString().toDoubleOrNull() ?: item.price
                updateInventoryItem(item, newStock, newPrice)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    // =============================================
    // STOCK CHART DIALOG (7-day LSTM forecast)
    // =============================================

    private fun showStockChart(item: OwnerInventoryItem) {
        ApiClient.apiService.predictStock(pharmacyId, item.medicineId)
            .enqueue(object : Callback<StockPrediction> {
                override fun onResponse(
                    call: Call<StockPrediction>,
                    response: Response<StockPrediction>
                ) {
                    val forecast = response.body() ?: return

                    val message = buildString {
                        appendLine("📊 7-Day Stock Forecast for ${item.medicineName}")
                        appendLine("Current: ${item.stock} units")
                        appendLine()

                        forecast.predictions.forEachIndexed { i, stock ->
                            val bar   = "█".repeat(minOf(stock / 5, 15))
                            val warn  = if (stock < 15) " ⚠️" else ""
                            appendLine("Day ${i+1}: ${"%-4d".format(stock)} $bar$warn")
                        }

                        appendLine()
                        when (forecast.alert_level) {
                            "critical" -> appendLine("🚨 CRITICAL: Restock immediately!")
                            "warning"  -> appendLine("⚠️ WARNING: Restock within ${forecast.days_until_low} days")
                            else       -> appendLine("✅ Stock levels look healthy")
                        }
                    }

                    AlertDialog.Builder(this@PharmacyOwnerDashboardActivity)
                        .setTitle("Stock Forecast")
                        .setMessage(message)
                        .setPositiveButton("Restock Now") { _, _ ->
                            showEditInventoryDialog(item)
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }

                override fun onFailure(call: Call<StockPrediction>, t: Throwable) {
                    Toast.makeText(
                        this@PharmacyOwnerDashboardActivity,
                        "Prediction unavailable — train LSTM first",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    // =============================================
    // API CALLS
    // =============================================

    private fun addInventoryItem(medicineName: String, stock: Int, price: Double) {
        // First find medicine ID by name, then add inventory
        ApiClient.apiService.getAllMedicines()
            .enqueue(object : Callback<List<MedicineInfo>> {
                override fun onResponse(
                    call: Call<List<MedicineInfo>>,
                    response: Response<List<MedicineInfo>>
                ) {
                    val medicine = response.body()?.find {
                        it.name.equals(medicineName, ignoreCase = true)
                    }

                    if (medicine == null) {
                        Toast.makeText(
                            this@PharmacyOwnerDashboardActivity,
                            "Medicine '$medicineName' not found in database",
                            Toast.LENGTH_LONG
                        ).show()
                        return
                    }

                    val request = InventoryUpdateRequest(
                        pharmacy_id    = pharmacyId,
                        medicine_id    = medicine.id,
                        stock_quantity = stock,
                        price          = price,
                        is_available   = true
                    )

                    ApiClient.apiService.updateInventory(request)
                        .enqueue(object : Callback<SuccessResponse> {
                            override fun onResponse(
                                call: Call<SuccessResponse>,
                                response: Response<SuccessResponse>
                            ) {
                                if (response.isSuccessful) {
                                    Toast.makeText(
                                        this@PharmacyOwnerDashboardActivity,
                                        "✅ $medicineName added to inventory",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    loadDashboard()
                                }
                            }

                            override fun onFailure(call: Call<SuccessResponse>, t: Throwable) {
                                Toast.makeText(
                                    this@PharmacyOwnerDashboardActivity,
                                    "Failed to add", Toast.LENGTH_SHORT
                                ).show()
                            }
                        })
                }

                override fun onFailure(call: Call<List<MedicineInfo>>, t: Throwable) {}
            })
    }

    private fun updateInventoryItem(item: OwnerInventoryItem, stock: Int, price: Double) {
        val request = InventoryUpdateRequest(
            pharmacy_id    = pharmacyId,
            medicine_id    = item.medicineId,
            stock_quantity = stock,
            price          = price,
            is_available   = stock > 0
        )

        ApiClient.apiService.updateInventory(request)
            .enqueue(object : Callback<SuccessResponse> {
                override fun onResponse(
                    call: Call<SuccessResponse>,
                    response: Response<SuccessResponse>
                ) {
                    if (response.isSuccessful) {
                        Toast.makeText(
                            this@PharmacyOwnerDashboardActivity,
                            "✅ Stock updated to $stock units",
                            Toast.LENGTH_SHORT
                        ).show()
                        loadDashboard()
                    }
                }

                override fun onFailure(call: Call<SuccessResponse>, t: Throwable) {
                    Toast.makeText(
                        this@PharmacyOwnerDashboardActivity,
                        "Update failed", Toast.LENGTH_SHORT
                    ).show()
                }
            })
    }

    private fun updateInventoryItemWithCallback(
        item: OwnerInventoryItem, stock: Int, price: Double,
        callback: (Boolean) -> Unit
    ) {
        val request = InventoryUpdateRequest(
            pharmacy_id    = pharmacyId,
            medicine_id    = item.medicineId,
            stock_quantity = stock,
            price          = price,
            is_available   = stock > 0
        )
        ApiClient.apiService.updateInventory(request)
            .enqueue(object : Callback<SuccessResponse> {
                override fun onResponse(
                    call: Call<SuccessResponse>,
                    response: Response<SuccessResponse>
                ) {
                    val ok = response.isSuccessful
                    runOnUiThread { callback(ok) }
                }
                override fun onFailure(call: Call<SuccessResponse>, t: Throwable) {
                    runOnUiThread { callback(false) }
                }
            })
    }


    private fun confirmDeleteInventory(item: OwnerInventoryItem) {
        AlertDialog.Builder(this)
            .setTitle("Remove from Inventory")
            .setMessage("Remove ${item.medicineName} from your inventory?")
            .setPositiveButton("Remove") { _, _ ->
                val request = InventoryUpdateRequest(
                    pharmacy_id    = pharmacyId,
                    medicine_id    = item.medicineId,
                    stock_quantity = 0,
                    price          = item.price,
                    is_available   = false
                )
                ApiClient.apiService.updateInventory(request)
                    .enqueue(object : Callback<SuccessResponse> {
                        override fun onResponse(
                            call: Call<SuccessResponse>,
                            response: Response<SuccessResponse>
                        ) {
                            Toast.makeText(
                                this@PharmacyOwnerDashboardActivity,
                                "${item.medicineName} removed",
                                Toast.LENGTH_SHORT
                            ).show()
                            loadDashboard()
                        }
                        override fun onFailure(call: Call<SuccessResponse>, t: Throwable) {}
                    })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =============================================
    // SEARCH / FILTER
    // =============================================

    private fun filterInventory(query: String) {
        filteredList.clear()
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            filteredList.addAll(inventoryList)
        } else {
            val matched = inventoryList.filter { item ->
                item.medicineName.lowercase().contains(q) ||
                        item.strength.lowercase().contains(q)
            }
            filteredList.addAll(matched)
        }
        inventoryAdapter.notifyDataSetChanged()

        val countText = when {
            q.isEmpty() -> "${filteredList.size} medicines"
            else        -> "${filteredList.size} of ${inventoryList.size} matches"
        }
        txtSearchCount.text = countText
        txtSearchCount.visibility = View.VISIBLE

        if (filteredList.isEmpty()) {
            txtEmpty.visibility = View.VISIBLE
            val emptyMsg = when {
                q.isEmpty() -> "No medicines in inventory\nTap + to add"
                else        -> "No medicines match \"$query\""
            }
            txtEmpty.text = emptyMsg
        } else {
            txtEmpty.visibility = View.GONE
        }
    }

} // end PharmacyOwnerDashboardActivity
// =============================================
// DATA CLASS
// =============================================

data class OwnerInventoryItem(
    val medicineId: Int,
    val medicineName: String,
    val strength: String,
    var stock: Int,
    var price: Double,
    var alertLevel: String = "ok",
    var daysUntilLow: Int? = null,
    var alertMessage: String? = null
)

// =============================================
// ADAPTER
// =============================================

class OwnerInventoryAdapter(
    private var items: MutableList<OwnerInventoryItem>,
    private val onEdit: (OwnerInventoryItem) -> Unit,
    private val onDelete: (OwnerInventoryItem) -> Unit,
    private val onViewChart: (OwnerInventoryItem) -> Unit,
    private val onSave: (OwnerInventoryItem, Int, Double, (Boolean) -> Unit) -> Unit
) : RecyclerView.Adapter<OwnerInventoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val card: CardView            = view.findViewById(R.id.cardOwnerItem)
        val txtName: TextView         = view.findViewById(R.id.txtOwnerMedName)
        val txtStrength: TextView     = view.findViewById(R.id.txtOwnerStrength)
        val txtPrice: TextView        = view.findViewById(R.id.txtOwnerPrice)
        val txtStock: TextView        = view.findViewById(R.id.txtOwnerStock)
        val txtAlert: TextView        = view.findViewById(R.id.txtOwnerAlert)
        val viewStockDot: android.view.View = view.findViewById(R.id.viewStockDot)
        val btnQuickMinus: android.widget.LinearLayout = view.findViewById(R.id.btnQuickMinus)
        val btnQuickPlus:  android.widget.LinearLayout = view.findViewById(R.id.btnQuickPlus)
        val btnEdit: Button           = view.findViewById(R.id.btnOwnerEdit)
        val btnSetPrice: Button       = view.findViewById(R.id.btnSetPrice)
        val btnDelete: Button         = view.findViewById(R.id.btnOwnerDelete)
        val btnChart: Button          = view.findViewById(R.id.btnOwnerChart)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_owner_inventory, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.txtName.text     = item.medicineName
        holder.txtStrength.text = item.strength.ifEmpty { "—" }
        holder.txtPrice.text    = "₹${"%.2f".format(item.price)}"
        holder.txtStock.text    = item.stock.toString()

        // Colour-coded stock dot
        val dotColor = when {
            item.stock == 0  -> 0xFFE53935.toInt()
            item.stock <= 10 -> 0xFFE53935.toInt()
            item.stock <= 20 -> 0xFFF57C00.toInt()
            item.stock <= 50 -> 0xFFFDD835.toInt()
            else             -> 0xFF43A047.toInt()
        }
        holder.viewStockDot.background?.setTint(dotColor)

        // Alert label
        val alertText = when {
            !item.alertMessage.isNullOrEmpty() -> item.alertMessage!!
            item.stock == 0  -> "✗ Out of stock"
            item.stock <= 10 -> "⚠ Critical — restock now"
            item.stock <= 20 -> "⚠ Running low"
            else             -> "✓ Stocked"
        }
        val alertColor = when {
            item.stock == 0  -> 0xFFE53935.toInt()
            item.stock <= 10 -> 0xFFE53935.toInt()
            item.stock <= 20 -> 0xFFF57C00.toInt()
            else             -> 0xFF2E7D32.toInt()
        }
        holder.txtAlert.text = alertText
        holder.txtAlert.setTextColor(alertColor)

        // ─ Quick −10 / +10 ────────────────────────────────────
        holder.btnQuickMinus.setOnClickListener {
            val cur = item.stock
            val newStock = maxOf(0, cur - 10)
            holder.txtStock.text = newStock.toString()
            onSave(item, newStock, item.price) { success ->
                if (success) {
                    items[holder.adapterPosition] = item.copy(stock = newStock)
                    notifyItemChanged(holder.adapterPosition)
                } else {
                    holder.txtStock.text = cur.toString()
                    android.widget.Toast.makeText(holder.itemView.context,
                        "Update failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        holder.btnQuickPlus.setOnClickListener {
            val cur = item.stock
            val newStock = cur + 10
            holder.txtStock.text = newStock.toString()
            onSave(item, newStock, item.price) { success ->
                if (success) {
                    items[holder.adapterPosition] = item.copy(stock = newStock)
                    notifyItemChanged(holder.adapterPosition)
                } else {
                    holder.txtStock.text = cur.toString()
                    android.widget.Toast.makeText(holder.itemView.context,
                        "Update failed", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Long-press quick buttons = ±50
        holder.btnQuickMinus.setOnLongClickListener {
            val newStock = maxOf(0, item.stock - 50)
            onSave(item, newStock, item.price) { success ->
                if (success) {
                    items[holder.adapterPosition] = item.copy(stock = newStock)
                    notifyItemChanged(holder.adapterPosition)
                }
            }
            true
        }
        holder.btnQuickPlus.setOnLongClickListener {
            val newStock = item.stock + 50
            onSave(item, newStock, item.price) { success ->
                if (success) {
                    items[holder.adapterPosition] = item.copy(stock = newStock)
                    notifyItemChanged(holder.adapterPosition)
                }
            }
            true
        }

        // Set Stock button — dialog for exact number entry
        holder.btnEdit.setOnClickListener { onEdit(item) }

        // Set Price button — quick price dialog
        holder.btnSetPrice.setOnClickListener {
            val ctx = holder.itemView.context
            val input = android.widget.EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText("%.2f".format(item.price))
                selectAll()
                setPadding(40, 30, 40, 30)
            }
            android.app.AlertDialog.Builder(ctx)
                .setTitle("Set Price — ${item.medicineName}")
                .setMessage("Current: ₹${"%.2f".format(item.price)}")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val newPrice = input.text.toString().toDoubleOrNull()
                    if (newPrice != null && newPrice > 0) {
                        onSave(item, item.stock, newPrice) { success ->
                            if (success) {
                                items[holder.adapterPosition] = item.copy(price = newPrice)
                                notifyItemChanged(holder.adapterPosition)
                                android.widget.Toast.makeText(ctx,
                                    "Price updated to ₹${"%.2f".format(newPrice)}",
                                    android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            // Request keyboard
            input.postDelayed({
                val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 100)
        }

        // ─ Other buttons ──────────────────────────────────────
        holder.btnChart.setOnClickListener  { onViewChart(item) }
        holder.btnDelete.setOnClickListener { onDelete(item) }
        holder.card.setOnLongClickListener  { onViewChart(item); true }

    }


    override fun getItemCount() = items.size
}