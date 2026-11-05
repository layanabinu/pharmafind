package com.example.pharmafind

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class ProfileActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences
    private var userId = -1
    private var isEditing = false

    // Display views
    private lateinit var txtProfileName:        TextView
    private lateinit var txtProfileEmail:       TextView
    private lateinit var txtProfileNameDetail:  TextView
    private lateinit var txtProfileEmailDetail: TextView
    private lateinit var txtProfileRole:        TextView
    private lateinit var txtProfilePhone:       TextView

    // Edit views (hidden by default)
    private lateinit var layoutEditFields:  View
    private lateinit var edtEditName:       TextInputEditText
    private lateinit var edtEditPhone:      TextInputEditText
    private lateinit var btnSaveProfile:    Button
    private lateinit var btnCancelEdit:     Button

    // Edit toggle button
    private lateinit var btnEditProfile:    View
    private lateinit var progressProfile:   ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        prefs  = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        userId = prefs.getInt("user_id", -1)

        bindViews()
        loadFromPrefs()
        wireListeners()
    }

    // ─────────────────────────────────────────────────────────
    // BIND
    // ─────────────────────────────────────────────────────────

    private fun bindViews() {
        txtProfileName        = findViewById(R.id.txtProfileName)
        txtProfileEmail       = findViewById(R.id.txtProfileEmail)
        txtProfileNameDetail  = findViewById(R.id.txtProfileNameDetail)
        txtProfileEmailDetail = findViewById(R.id.txtProfileEmailDetail)
        txtProfileRole        = findViewById(R.id.txtProfileRole)
        txtProfilePhone       = findViewById(R.id.txtProfilePhone)
        layoutEditFields      = findViewById(R.id.layoutEditFields)
        edtEditName           = findViewById(R.id.edtEditName)
        edtEditPhone          = findViewById(R.id.edtEditPhone)
        btnSaveProfile        = findViewById(R.id.btnSaveProfile)
        btnCancelEdit         = findViewById(R.id.btnCancelEdit)
        btnEditProfile        = findViewById(R.id.btnEditProfile)
        progressProfile       = findViewById(R.id.progressProfile)

        layoutEditFields.visibility = View.GONE
        progressProfile.visibility  = View.GONE
    }

    // ─────────────────────────────────────────────────────────
    // LOAD FROM PREFS (instant display before API call)
    // ─────────────────────────────────────────────────────────

    private fun loadFromPrefs() {
        val name  = prefs.getString("user_name",  "User")    ?: "User"
        val email = prefs.getString("user_email", "")        ?: ""
        val role  = prefs.getString("user_role",  "patient") ?: "patient"
        val phone = prefs.getString("user_phone", "")        ?: ""

        applyToViews(name, email, role, phone)
    }

    private fun applyToViews(name: String, email: String, role: String, phone: String) {
        txtProfileName.text        = name
        txtProfileEmail.text       = email
        txtProfileNameDetail.text  = name
        txtProfileEmailDetail.text = email
        txtProfileRole.text        = role.replaceFirstChar { it.uppercase() }
        txtProfilePhone.text       = phone.ifEmpty { "Not set" }
    }

    // ─────────────────────────────────────────────────────────
    // LISTENERS
    // ─────────────────────────────────────────────────────────

    private fun wireListeners() {
        findViewById<View>(R.id.btnProfileBack).setOnClickListener { finish() }

        btnEditProfile.setOnClickListener { startEdit() }
        btnCancelEdit.setOnClickListener  { cancelEdit() }
        btnSaveProfile.setOnClickListener { saveProfile() }

        findViewById<View>(R.id.btnProfileToHistory).setOnClickListener {
            startActivity(Intent(this, MedicationHistoryActivity::class.java))
        }
        findViewById<View>(R.id.btnProfileToReservations).setOnClickListener {
            startActivity(Intent(this, ReservationHistoryActivity::class.java))
        }
        findViewById<View>(R.id.btnProfileToReminders).setOnClickListener {
            startActivity(Intent(this, ReminderSettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnProfileLogout).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Logout") { _, _ ->
                    prefs.edit().clear().apply()
                    startActivity(Intent(this, LoginActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    // ─────────────────────────────────────────────────────────
    // EDIT MODE
    // ─────────────────────────────────────────────────────────

    private fun startEdit() {
        isEditing = true
        // Pre-fill edit fields
        edtEditName.setText(prefs.getString("user_name", "") ?: "")
        edtEditPhone.setText(prefs.getString("user_phone", "") ?: "")

        layoutEditFields.visibility = View.VISIBLE
        btnEditProfile.visibility   = View.GONE
        // Scroll to edit fields
        edtEditName.requestFocus()
    }

    private fun cancelEdit() {
        isEditing = false
        layoutEditFields.visibility = View.GONE
        btnEditProfile.visibility   = View.VISIBLE
    }

    private fun saveProfile() {
        val newName  = edtEditName.text.toString().trim()
        val newPhone = edtEditPhone.text.toString().trim()

        if (newName.isEmpty()) {
            edtEditName.error = "Name cannot be empty"
            return
        }
        if (userId == -1) {
            Toast.makeText(this, "Not logged in", Toast.LENGTH_SHORT).show()
            return
        }

        progressProfile.visibility = View.VISIBLE
        btnSaveProfile.isEnabled   = false

        val request = UserUpdateRequest(
            name  = newName.ifEmpty { null },
            phone = newPhone.ifEmpty { null }
        )

        ApiClient.apiService.updateUserProfile(userId, request)
            .enqueue(object : Callback<UserUpdateResponse> {
                override fun onResponse(
                    call: Call<UserUpdateResponse>,
                    response: Response<UserUpdateResponse>
                ) {
                    progressProfile.visibility = View.GONE
                    btnSaveProfile.isEnabled   = true

                    if (response.isSuccessful) {
                        val body = response.body() ?: return

                        // Update prefs
                        prefs.edit()
                            .putString("user_name",  body.name)
                            .putString("user_phone", body.phone)
                            .apply()

                        // Refresh views
                        val email = prefs.getString("user_email", "") ?: ""
                        val role  = prefs.getString("user_role",  "patient") ?: "patient"
                        applyToViews(body.name, email, role, body.phone)

                        cancelEdit()
                        Toast.makeText(this@ProfileActivity,
                            "✅ Profile updated", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ProfileActivity,
                            "Failed to update profile", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<UserUpdateResponse>, t: Throwable) {
                    progressProfile.visibility = View.GONE
                    btnSaveProfile.isEnabled   = true
                    Toast.makeText(this@ProfileActivity,
                        "Network error — changes not saved", Toast.LENGTH_SHORT).show()
                }
            })
    }
}