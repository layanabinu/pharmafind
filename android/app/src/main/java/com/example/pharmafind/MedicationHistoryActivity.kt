package com.example.pharmafind

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class MedicationHistoryActivity : AppCompatActivity() {

    private lateinit var rvMedicines: RecyclerView
    private lateinit var rvTimeline: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtEmpty: TextView
    private lateinit var cardInteractions: CardView
    private lateinit var llInteractionsList: LinearLayout
    private lateinit var txtSummaryMeds: TextView
    private lateinit var txtSummaryRefills: TextView
    private lateinit var txtSummaryInteractions: TextView
    private lateinit var btnTabMedicines: Button
    private lateinit var btnTabTimeline: Button

    private var userId = -1
    private var showingTimeline = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medication_history)

        userId = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
            .getInt("user_id", -1)

        initViews()
        loadHistory()
    }

    private fun initViews() {
        progressBar           = findViewById(R.id.progressHistory)
        txtEmpty              = findViewById(R.id.txtHistoryEmpty)
        cardInteractions      = findViewById(R.id.cardInteractions)
        llInteractionsList    = findViewById(R.id.llInteractionsList)
        txtSummaryMeds        = findViewById(R.id.txtSummaryMeds)
        txtSummaryRefills     = findViewById(R.id.txtSummaryRefills)
        txtSummaryInteractions = findViewById(R.id.txtSummaryInteractions)
        btnTabMedicines       = findViewById(R.id.btnTabMedicines)
        btnTabTimeline        = findViewById(R.id.btnTabTimeline)

        rvMedicines = findViewById(R.id.rvHistoryMedicines)
        rvMedicines.layoutManager = LinearLayoutManager(this)

        rvTimeline = findViewById(R.id.rvHistoryTimeline)
        rvTimeline.layoutManager = LinearLayoutManager(this)
        rvTimeline.visibility = View.GONE

        findViewById<ImageView>(R.id.btnHistoryBack).setOnClickListener { finish() }

        btnTabMedicines.setOnClickListener { switchTab(false) }
        btnTabTimeline.setOnClickListener  { switchTab(true) }
    }

    private fun switchTab(showTimeline: Boolean) {
        showingTimeline = showTimeline
        if (showTimeline) {
            rvMedicines.visibility = View.GONE
            rvTimeline.visibility  = View.VISIBLE
            btnTabMedicines.alpha  = 0.6f
            btnTabTimeline.alpha   = 1.0f
        } else {
            rvMedicines.visibility = View.VISIBLE
            rvTimeline.visibility  = View.GONE
            btnTabMedicines.alpha  = 1.0f
            btnTabTimeline.alpha   = 0.6f
        }
    }

    // =============================================
    // LOAD DATA
    // =============================================

    private fun loadHistory() {
        if (userId == -1) {
            txtEmpty.text = "Please log in to view history"
            txtEmpty.visibility = View.VISIBLE
            return
        }

        progressBar.visibility = View.VISIBLE

        ApiClient.apiService.getMedicationHistory(userId)
            .enqueue(object : Callback<MedicationHistoryResponse> {
                override fun onResponse(
                    call: Call<MedicationHistoryResponse>,
                    response: Response<MedicationHistoryResponse>
                ) {
                    progressBar.visibility = View.GONE
                    if (!response.isSuccessful || response.body() == null) {
                        showEmpty("Could not load history")
                        return
                    }

                    val body = response.body()!!
                    updateSummary(body.summary)
                    showMedicineCards(body.medicines)
                    showInteractionWarnings(body.interactions)
                }

                override fun onFailure(call: Call<MedicationHistoryResponse>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    showEmpty("Connection failed")
                }
            })

        // Load timeline in parallel
        ApiClient.apiService.getMedicationTimeline(userId)
            .enqueue(object : Callback<TimelineResponse> {
                override fun onResponse(
                    call: Call<TimelineResponse>,
                    response: Response<TimelineResponse>
                ) {
                    val events = response.body()?.timeline ?: return
                    showTimeline(events)
                }
                override fun onFailure(call: Call<TimelineResponse>, t: Throwable) {}
            })
    }

    // =============================================
    // SUMMARY ROW
    // =============================================

    private fun updateSummary(summary: HistorySummary?) {
        summary ?: return
        txtSummaryMeds.text        = summary.total_medicines.toString()
        txtSummaryRefills.text     = summary.needs_refill_soon.toString()
        txtSummaryInteractions.text = summary.interaction_warnings.toString()

        val refillColor = if (summary.needs_refill_soon > 0) 0xFFE53935.toInt()
        else 0xFF43A047.toInt()
        txtSummaryRefills.setTextColor(refillColor)

        val intColor = if (summary.interaction_warnings > 0) 0xFFE53935.toInt()
        else 0xFF43A047.toInt()
        txtSummaryInteractions.setTextColor(intColor)
    }

    // =============================================
    // MEDICINE CARDS (grouped view)
    // =============================================

    private fun showMedicineCards(medicines: List<MedHistoryItem>) {
        if (medicines.isEmpty()) {
            showEmpty("No medication history yet.\nStart adding medicines to track them.")
            return
        }
        txtEmpty.visibility = View.GONE
        rvMedicines.adapter = MedicineHistoryAdapter(medicines)
    }

    // =============================================
    // INTERACTION WARNINGS
    // =============================================

    private fun showInteractionWarnings(interactions: List<DrugInteraction>) {
        if (interactions.isEmpty()) {
            cardInteractions.visibility = View.GONE
            return
        }

        cardInteractions.visibility = View.VISIBLE
        llInteractionsList.removeAllViews()

        interactions.forEach { interaction ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val bgColor = if (interaction.severity == "high") 0xFFFFEBEE.toInt()
            else 0xFFFFF8E1.toInt()
            row.setBackgroundColor(bgColor)

            val txt = TextView(this).apply {
                text = interaction.message
                textSize = 13f
                setTextColor(if (interaction.severity == "high") 0xFFB71C1C.toInt()
                else 0xFF5D4037.toInt())
                setPadding(16, 8, 16, 8)
                setLineSpacing(4f, 1f)
            }
            row.addView(txt)

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(0xFFEEEEEE.toInt())
            }
            llInteractionsList.addView(row)
            llInteractionsList.addView(divider)
        }
    }

    // =============================================
    // TIMELINE (flat chronological)
    // =============================================

    private fun showTimeline(events: List<TimelineEvent>) {
        rvTimeline.adapter = TimelineAdapter(events)
    }

    private fun showEmpty(msg: String) {
        txtEmpty.text = msg
        txtEmpty.visibility = View.VISIBLE
    }
}


// =============================================
// MEDICINE HISTORY ADAPTER
// =============================================

class MedicineHistoryAdapter(
    private val items: List<MedHistoryItem>
) : RecyclerView.Adapter<MedicineHistoryAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView      = view.findViewById(R.id.txtHistMedName)
        val txtDosage: TextView    = view.findViewById(R.id.txtHistDosage)
        val txtStatus: TextView    = view.findViewById(R.id.txtHistStatus)
        val txtRefill: TextView    = view.findViewById(R.id.txtHistRefill)
        val txtAdherence: TextView = view.findViewById(R.id.txtHistAdherence)
        val txtTablets: TextView   = view.findViewById(R.id.txtHistTablets)
        val progressAdherence: ProgressBar = view.findViewById(R.id.progressAdherence)
        val card: CardView         = view.findViewById(R.id.cardHistMed)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_medicine, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        holder.txtName.text   = item.medicine_name
        holder.txtDosage.text = buildString {
            if (item.dosage.isNotEmpty())    append(item.dosage)
            if (item.frequency.isNotEmpty()) append(" · ${item.frequency}")
        }.ifEmpty { "No dosage info" }

        holder.txtTablets.text = "${item.current_tablets} tablets remaining"
        val tabletColor = when {
            item.current_tablets == 0  -> 0xFFE53935.toInt()
            item.current_tablets <= 10 -> 0xFFF57C00.toInt()
            item.current_tablets <= 20 -> 0xFFFDD835.toInt()
            else                       -> 0xFF43A047.toInt()
        }
        holder.txtTablets.setTextColor(tabletColor)

        // Last event badge
        val (eventText, eventColor) = when (item.last_event) {
            "started"  -> "Started"    to 0xFF1E88E5.toInt()
            "refilled" -> "Refilled"   to 0xFF43A047.toInt()
            "stopped"  -> "Stopped"    to 0xFF9E9E9E.toInt()
            "reserved" -> "Reserved"   to 0xFF7B1FA2.toInt()
            else       -> "Updated"    to 0xFF757575.toInt()
        }
        holder.txtStatus.text = eventText
        holder.txtStatus.setTextColor(eventColor)

        // Refill prediction
        val refill = item.refill_prediction
        if (refill != null) {
            holder.txtRefill.visibility = View.VISIBLE
            holder.txtRefill.text = refill.message
            val refillColor = when {
                refill.days_until_refill <= 0 -> 0xFFE53935.toInt()
                refill.days_until_refill <= 3 -> 0xFFE53935.toInt()
                refill.days_until_refill <= 7 -> 0xFFF57C00.toInt()
                else                          -> 0xFF43A047.toInt()
            }
            holder.txtRefill.setTextColor(refillColor)
        } else {
            holder.txtRefill.visibility = View.GONE
        }

        // Adherence
        val adherence = item.adherence
        if (adherence?.score != null) {
            holder.txtAdherence.visibility    = View.VISIBLE
            holder.progressAdherence.visibility = View.VISIBLE
            holder.txtAdherence.text = "${adherence.label} (${adherence.score}%)"
            holder.progressAdherence.progress = adherence.score

            val progressColor = when (adherence.color) {
                "green"  -> 0xFF43A047.toInt()
                "blue"   -> 0xFF1E88E5.toInt()
                "orange" -> 0xFFF57C00.toInt()
                else     -> 0xFFE53935.toInt()
            }
            holder.progressAdherence.progressTintList =
                android.content.res.ColorStateList.valueOf(progressColor)
        } else {
            holder.txtAdherence.visibility = View.GONE
            holder.progressAdherence.visibility = View.GONE
        }

        // Card highlight for urgent refills
        if (refill != null && refill.days_until_refill <= 3) {
            holder.card.setCardBackgroundColor(0xFFFFF8F8.toInt())
        } else {
            holder.card.setCardBackgroundColor(0xFFFFFFFF.toInt())
        }
    }

    override fun getItemCount() = items.size
}


// =============================================
// TIMELINE ADAPTER
// =============================================

class TimelineAdapter(
    private val events: List<TimelineEvent>
) : RecyclerView.Adapter<TimelineAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtMedicine: TextView = view.findViewById(R.id.txtTimelineMedicine)
        val txtEvent: TextView    = view.findViewById(R.id.txtTimelineEvent)
        val txtDate: TextView     = view.findViewById(R.id.txtTimelineDate)
        val txtPharmacy: TextView = view.findViewById(R.id.txtTimelinePharmacy)
        val dot: View             = view.findViewById(R.id.timelineDot)
        val line: View            = view.findViewById(R.id.timelineLine)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_timeline_event, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = events[position]

        holder.txtMedicine.text = event.medicine_name

        val (label, color) = when (event.event_type) {
            "started"  -> "💊 Started taking"  to 0xFF1E88E5.toInt()
            "refilled" -> "🔄 Refilled"         to 0xFF43A047.toInt()
            "stopped"  -> "⏹ Stopped"           to 0xFF9E9E9E.toInt()
            "reserved" -> "📋 Reserved pickup"  to 0xFF7B1FA2.toInt()
            "updated"  -> "✏️ Updated"           to 0xFF757575.toInt()
            else       -> event.event_type       to 0xFF757575.toInt()
        }

        holder.txtEvent.text = label
        holder.dot.setBackgroundColor(color)

        // Format date
        val rawDate = event.event_date
        holder.txtDate.text = try {
            val dt = java.time.LocalDateTime.parse(rawDate.take(19))
            "${dt.dayOfMonth} ${dt.month.name.take(3)} ${dt.year}"
        } catch (e: Exception) { rawDate.take(10) }

        holder.txtPharmacy.text = event.pharmacy.ifEmpty { "" }
        holder.txtPharmacy.visibility = if (event.pharmacy.isEmpty()) View.GONE else View.VISIBLE

        // Hide line connector for last item
        holder.line.visibility = if (position == itemCount - 1) View.INVISIBLE else View.VISIBLE
    }

    override fun getItemCount() = events.size
}