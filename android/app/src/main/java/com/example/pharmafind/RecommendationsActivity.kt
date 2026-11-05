package com.example.pharmafind

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class RecommendationsActivity : AppCompatActivity() {

    private lateinit var rvRecommendations: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var txtTitle: TextView
    private lateinit var txtSubtitle: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var btnBack: View

    private var userId = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recommendations)

        userId = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
            .getInt("user_id", -1)

        progressBar      = findViewById(R.id.progressBarRec)
        rvRecommendations= findViewById(R.id.rvRecommendations)
        txtTitle         = findViewById(R.id.txtRecTitle)
        txtSubtitle      = findViewById(R.id.txtRecSubtitle)
        txtEmpty         = findViewById(R.id.txtRecEmpty)
        btnBack          = findViewById(R.id.btnRecBack)

        rvRecommendations.layoutManager = LinearLayoutManager(this)

        btnBack.setOnClickListener { finish() }

        loadRecommendations()
    }

    private fun loadRecommendations() {
        if (userId == -1) {
            txtEmpty.visibility = View.VISIBLE
            txtEmpty.text = "Please log in to see recommendations"
            return
        }

        progressBar.visibility = View.VISIBLE
        txtSubtitle.text = "Finding medicines for you..."

        ApiClient.apiService.getRecommendations(userId)
            .enqueue(object : Callback<RecommendationsResponse> {
                override fun onResponse(
                    call: Call<RecommendationsResponse>,
                    response: Response<RecommendationsResponse>
                ) {
                    progressBar.visibility = View.GONE
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val recs = body.recommendations

                        if (recs.isEmpty()) {
                            txtEmpty.visibility = View.VISIBLE
                            txtSubtitle.text = "Add medicines to your list to get personalised recommendations"
                        } else {
                            txtEmpty.visibility = View.GONE
                            txtSubtitle.text = "${recs.size} personalised suggestions based on your medicines"
                            rvRecommendations.adapter = RecommendationAdapter(recs) { rec ->
                                // Search for this medicine when tapped
                                val intent = Intent(this@RecommendationsActivity, HomeActivity::class.java)
                                intent.putExtra("search_query", rec.medicine_name)
                                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                                startActivity(intent)
                            }
                        }
                    } else {
                        txtEmpty.visibility = View.VISIBLE
                        txtEmpty.text = "Couldn't load recommendations"
                    }
                }

                override fun onFailure(call: Call<RecommendationsResponse>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    txtEmpty.visibility = View.VISIBLE
                    txtEmpty.text = "Server unavailable"
                    Toast.makeText(this@RecommendationsActivity,
                        "Can't connect to server", Toast.LENGTH_SHORT).show()
                }
            })
    }
}

// ── Adapter ───────────────────────────────────────────────────────

class RecommendationAdapter(
    private val items: List<Recommendation>,
    private val onSearch: (Recommendation) -> Unit
) : RecyclerView.Adapter<RecommendationAdapter.VH>() {

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card:       CardView = v.findViewById(R.id.cardRec)
        val txtName:    TextView = v.findViewById(R.id.txtRecMedicineName)
        val txtGeneric: TextView = v.findViewById(R.id.txtRecGenericName)
        val txtReason:  TextView = v.findViewById(R.id.txtRecReason)
        val txtSource:  TextView = v.findViewById(R.id.txtRecSource)
        val txtRx:      TextView = v.findViewById(R.id.txtRecPrescription)
        val txtSearch:  TextView = v.findViewById(R.id.txtRecSearch)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
        val v = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recommendation, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val rec = items[pos]

        h.txtName.text    = rec.medicine_name
        h.txtGeneric.text = if (rec.generic_name.isNotEmpty()) rec.generic_name else rec.strength
        h.txtReason.text  = rec.reason

        // Source badge
        when (rec.source) {
            "collaborative" -> {
                h.txtSource.text = "👥 Users like you"
                h.txtSource.setTextColor(android.graphics.Color.parseColor("#1565C0"))
                h.txtSource.setBackgroundColor(android.graphics.Color.parseColor("#E3F2FD"))
            }
            "comorbidity" -> {
                h.txtSource.text = "🩺 Often co-prescribed"
                h.txtSource.setTextColor(android.graphics.Color.parseColor("#00695C"))
                h.txtSource.setBackgroundColor(android.graphics.Color.parseColor("#E0F2F1"))
            }
            else -> {
                h.txtSource.text = "⭐ Popular"
                h.txtSource.setTextColor(android.graphics.Color.parseColor("#E65100"))
                h.txtSource.setBackgroundColor(android.graphics.Color.parseColor("#FFF3E0"))
            }
        }

        // Prescription warning
        if (rec.is_prescription_required) {
            h.txtRx.visibility = View.VISIBLE
            h.txtRx.text = "⚕️ Prescription required"
        } else {
            h.txtRx.visibility = View.GONE
        }

        h.txtSearch.setOnClickListener { onSearch(rec) }
        h.card.setOnClickListener { onSearch(rec) }
    }
}