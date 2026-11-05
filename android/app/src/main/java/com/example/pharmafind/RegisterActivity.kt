package com.example.pharmafind

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class RegisterActivity : AppCompatActivity() {

    private val TAG = "RegisterActivity"
    private val LOCATION_PERMISSION_REQUEST = 1001

    private lateinit var edtName: TextInputEditText
    private lateinit var edtEmail: TextInputEditText
    private lateinit var edtPassword: TextInputEditText
    private lateinit var btnRolePatient: Button
    private lateinit var btnRoleOwner: Button
    private lateinit var layoutOwnerFields: LinearLayout
    private lateinit var edtPharmacyName: TextInputEditText
    private lateinit var edtPharmacyAddress: TextInputEditText
    private lateinit var edtPharmacyCity: TextInputEditText
    private lateinit var edtPharmacyPhone: TextInputEditText
    private lateinit var edtPharmacyLat: TextInputEditText
    private lateinit var edtPharmacyLng: TextInputEditText
    private lateinit var btnPickLocation: Button
    private lateinit var txtLocationStatus: android.widget.TextView
    private lateinit var btnRegister: Button
    private lateinit var txtLoginLink: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var isOwnerMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        bindViews()
        setupRoleToggle()
        setupListeners()
    }

    private fun bindViews() {
        edtName            = findViewById(R.id.edtRegName)
        edtEmail           = findViewById(R.id.edtRegEmail)
        edtPassword        = findViewById(R.id.edtRegPassword)
        btnRolePatient     = findViewById(R.id.btnRolePatient)
        btnRoleOwner       = findViewById(R.id.btnRoleOwner)
        layoutOwnerFields  = findViewById(R.id.layoutOwnerFields)
        edtPharmacyName    = findViewById(R.id.edtPharmacyName)
        edtPharmacyAddress = findViewById(R.id.edtPharmacyAddress)
        edtPharmacyCity    = findViewById(R.id.edtPharmacyCity)
        edtPharmacyPhone   = findViewById(R.id.edtPharmacyPhone)
        edtPharmacyLat     = findViewById(R.id.edtPharmacyLat)
        edtPharmacyLng     = findViewById(R.id.edtPharmacyLng)
        btnPickLocation    = findViewById(R.id.btnPickLocation)
        txtLocationStatus  = findViewById(R.id.txtLocationStatus)
        btnRegister        = findViewById(R.id.btnRegister)
        txtLoginLink       = findViewById(R.id.txtLoginLink)
        progressBar        = findViewById(R.id.regProgressBar)
    }

    private fun setupRoleToggle() {
        setPatientMode()
        btnRolePatient.setOnClickListener { isOwnerMode = false; setPatientMode() }
        btnRoleOwner.setOnClickListener   { isOwnerMode = true;  setOwnerMode()  }
    }

    private fun setPatientMode() {
        layoutOwnerFields.visibility = View.GONE
        btnRegister.text = "Create Account"
        btnRolePatient.alpha = 1.0f
        btnRoleOwner.alpha   = 0.5f
    }

    private fun setOwnerMode() {
        layoutOwnerFields.visibility = View.VISIBLE
        btnRegister.text = "Register Pharmacy"
        btnRoleOwner.alpha   = 1.0f
        btnRolePatient.alpha = 0.5f
    }

    private fun setupListeners() {
        txtLoginLink.setOnClickListener    { finish() }
        btnPickLocation.setOnClickListener { requestAndFillLocation() }
        btnRegister.setOnClickListener     { if (isOwnerMode) submitOwnerReg() else submitPatientReg() }
    }

    // ── GPS location pick ─────────────────────────────────────────
    private fun requestAndFillLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST)
        } else {
            fetchLocation()
        }
    }

    private fun fetchLocation() {
        btnPickLocation.isEnabled = false
        btnPickLocation.text      = "Getting location..."
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
            btnPickLocation.isEnabled = true
            btnPickLocation.text      = "Use My Current Location"
            if (loc != null) {
                edtPharmacyLat.setText("%.6f".format(loc.latitude))
                edtPharmacyLng.setText("%.6f".format(loc.longitude))
                txtLocationStatus.text = "📍 Location captured (${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)})"
                txtLocationStatus.visibility = View.VISIBLE
                btnPickLocation.text = "✅ Location Set — Tap to Update"
            } else {
                Toast.makeText(this, "Could not get location. Enter manually.", Toast.LENGTH_LONG).show()
            }
        }.addOnFailureListener {
            btnPickLocation.isEnabled = true
            btnPickLocation.text      = "Use My Current Location"
            Toast.makeText(this, "Location error: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST
            && grantResults.isNotEmpty()
            && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            fetchLocation()
        } else {
            Toast.makeText(this, "Permission denied. Enter coordinates manually.",
                Toast.LENGTH_LONG).show()
        }
    }

    // ── Patient submit ────────────────────────────────────────────
    private fun submitPatientReg() {
        val name     = edtName.text?.toString()?.trim().orEmpty()
        val email    = edtEmail.text?.toString()?.trim().orEmpty()
        val password = edtPassword.text?.toString()?.trim().orEmpty()
        if (!validateCommon(name, email, password)) return

        progressBar.visibility = View.VISIBLE
        btnRegister.isEnabled  = false

        ApiClient.apiService.register(
            RegisterRequest(name = name, email = email, password = password, role = "patient")
        ).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                progressBar.visibility = View.GONE
                btnRegister.isEnabled  = true
                handleAuthResponse(response, name, email)
            }
            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                btnRegister.isEnabled  = true
                Toast.makeText(this@RegisterActivity,
                    "Cannot connect to server. Is the backend running?", Toast.LENGTH_LONG).show()
            }
        })
    }

    // ── Owner submit ──────────────────────────────────────────────
    private fun submitOwnerReg() {
        val name     = edtName.text?.toString()?.trim().orEmpty()
        val email    = edtEmail.text?.toString()?.trim().orEmpty()
        val password = edtPassword.text?.toString()?.trim().orEmpty()
        if (!validateCommon(name, email, password)) return

        val pharmName    = edtPharmacyName.text?.toString()?.trim().orEmpty()
        val pharmAddress = edtPharmacyAddress.text?.toString()?.trim().orEmpty()
        val pharmCity    = edtPharmacyCity.text?.toString()?.trim().orEmpty().ifEmpty { "Kottayam" }
        val pharmPhone   = edtPharmacyPhone.text?.toString()?.trim().orEmpty()
        val lat          = edtPharmacyLat.text?.toString()?.trim()?.toDoubleOrNull()
        val lng          = edtPharmacyLng.text?.toString()?.trim()?.toDoubleOrNull()

        if (pharmName.isEmpty())    { Toast.makeText(this, "Enter pharmacy name", Toast.LENGTH_SHORT).show(); return }
        if (pharmAddress.isEmpty()) { Toast.makeText(this, "Enter pharmacy address", Toast.LENGTH_SHORT).show(); return }
        // GPS not required — address entry is sufficient
        // Use 0.0,0.0 if GPS not captured; owner can update later
        val finalLat = lat ?: 0.0
        val finalLng = lng ?: 0.0

        if (pharmAddress.isEmpty() && finalLat == 0.0) {
            Toast.makeText(this, "Enter pharmacy address or tap 'Use My Current Location'",
                Toast.LENGTH_LONG).show(); return
        }

        progressBar.visibility = View.VISIBLE
        btnRegister.isEnabled  = false

        ApiClient.apiService.registerOwner(OwnerRegisterRequest(
            name             = name,
            email            = email,
            password         = password,
            role             = "pharmacy",
            pharmacy_name    = pharmName,
            pharmacy_address = pharmAddress,
            pharmacy_city    = pharmCity,
            pharmacy_state   = "Kerala",
            pharmacy_lat     = finalLat,
            pharmacy_lng     = finalLng,
            pharmacy_phone   = pharmPhone
        )).enqueue(object : Callback<LoginResponse> {
            override fun onResponse(call: Call<LoginResponse>, response: Response<LoginResponse>) {
                progressBar.visibility = View.GONE
                btnRegister.isEnabled  = true
                handleAuthResponse(response, name, email)
            }
            override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                btnRegister.isEnabled  = true
                Toast.makeText(this@RegisterActivity,
                    "Cannot connect to server.", Toast.LENGTH_LONG).show()
            }
        })
    }

    // ── Shared auth response handler ──────────────────────────────
    private fun handleAuthResponse(response: Response<LoginResponse>, name: String, email: String) {
        Log.d(TAG, "HTTP ${response.code()}")
        if (!response.isSuccessful) {
            val msg = when (response.code()) {
                400  -> "Email already registered or invalid data"
                409  -> "An account with this email already exists"
                422  -> "Invalid data — check your inputs"
                500  -> "Server error. Is the backend running?"
                else -> response.errorBody()?.string()?.take(120) ?: "Registration failed (${response.code()})"
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        val body = response.body()
        if (body == null || body.success != true) {
            Toast.makeText(this, body?.message ?: "Registration failed", Toast.LENGTH_LONG).show()
            return
        }
        val user = body.user
        getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE).edit().apply {
            putBoolean("is_logged_in",  true)
            putInt("user_id",           user?.id            ?: -1)
            putString("user_name",      user?.name          ?: name)
            putString("user_email",     user?.email         ?: email)
            putString("user_role",      user?.role          ?: "patient")
            putString("auth_token",     body.token          ?: "")
            putInt("pharmacy_id",       user?.pharmacy_id   ?: -1)
            putString("pharmacy_name",  user?.pharmacy_name ?: "")
            apply()
        }
        Log.d(TAG, "Registered OK — id=${user?.id} role=${user?.role}")
        Toast.makeText(this, "Welcome, ${user?.name ?: name}!", Toast.LENGTH_SHORT).show()
        val dest = when (user?.role) {
            "pharmacy", "owner", "pharmacist" -> OwnerHomeActivity::class.java
            else                              -> HomeActivity::class.java
        }
        startActivity(Intent(this, dest).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    // ── Validation ────────────────────────────────────────────────
    private fun validateCommon(name: String, email: String, password: String): Boolean {
        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show(); return false
        }
        if (!email.contains("@") || !email.contains(".")) {
            Toast.makeText(this, "Enter a valid email address", Toast.LENGTH_SHORT).show(); return false
        }
        if (password.length < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show(); return false
        }
        return true
    }
}