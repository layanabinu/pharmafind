package com.example.pharmafind

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class LoginActivity : AppCompatActivity() {

    private lateinit var edtEmail: TextInputEditText
    private lateinit var edtPassword: TextInputEditText
    private lateinit var btnLogin: Button
    private lateinit var btnRegister: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // If already logged in, go straight to correct screen
        val prefs = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("is_logged_in", false)) {
            val savedRole = prefs.getString("user_role", "patient") ?: "patient"
            Log.d("LoginActivity", "Already logged in as role=$savedRole")
            routeByRole(savedRole)
            return
        }

        edtEmail    = findViewById(R.id.edtLoginEmail)
        edtPassword = findViewById(R.id.edtLoginPassword)
        btnLogin    = findViewById(R.id.btnLogin)
        btnRegister = findViewById(R.id.txtRegisterLink)
        progressBar = findViewById(R.id.progressBarLogin)

        btnLogin.setOnClickListener { attemptLogin() }
        btnRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun attemptLogin() {
        val email    = edtEmail.text.toString().trim()
        val password = edtPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please enter email and password", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        btnLogin.isEnabled = false

        ApiClient.apiService.login(LoginRequest(email, password))
            .enqueue(object : Callback<LoginResponse> {
                override fun onResponse(
                    call: Call<LoginResponse>,
                    response: Response<LoginResponse>
                ) {
                    progressBar.visibility = View.GONE
                    btnLogin.isEnabled = true

                    Log.d("LoginActivity", "Response code: ${response.code()}")

                    if (response.isSuccessful && response.body()?.success == true) {
                        val body = response.body()!!
                        val user = body.user!!

                        Log.d("LoginActivity", "role=${user.role} pharmacy_id=${user.pharmacy_id}")

                        // ── Save full session ─────────────────────────────
                        val prefs = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
                        val editor = prefs.edit()
                        editor.putBoolean("is_logged_in", true)
                        editor.putInt("user_id", user.id)
                        editor.putString("user_name", user.name)
                        editor.putString("user_email", user.email)
                        editor.putString("user_role", user.role)
                        editor.putString("auth_token", body.token)

                        if (user.role == "pharmacy") {
                            val pid   = user.pharmacy_id
                            val pname = user.pharmacy_name
                            if (pid != null) {
                                editor.putInt("pharmacy_id", pid)
                                Log.d("LoginActivity", "Saved pharmacy_id=$pid")
                            } else {
                                Log.w("LoginActivity",
                                    "WARNING: pharmacy_id is null. " +
                                            "Did you run create_owner.py and link the pharmacy?")
                                Toast.makeText(
                                    this@LoginActivity,
                                    "⚠️ No pharmacy linked to this account.\nRun create_owner.py on the server.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            if (pname != null) editor.putString("pharmacy_name", pname)
                        }
                        editor.apply()

                        Toast.makeText(this@LoginActivity, body.message, Toast.LENGTH_SHORT).show()
                        routeByRole(user.role)

                    } else {
                        val errorMsg = when (response.code()) {
                            401  -> "Wrong email or password"
                            403  -> "Account is disabled"
                            else -> "Login failed (code ${response.code()})"
                        }
                        Toast.makeText(this@LoginActivity, errorMsg, Toast.LENGTH_LONG).show()
                    }
                }

                override fun onFailure(call: Call<LoginResponse>, t: Throwable) {
                    progressBar.visibility = View.GONE
                    btnLogin.isEnabled = true
                    Log.e("LoginActivity", "Network error: ${t.message}")
                    Toast.makeText(
                        this@LoginActivity,
                        "Can't connect to server. Is backend running?",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    private fun routeByRole(role: String) {
        Log.d("LoginActivity", "Routing → role=$role")
        val destination = when (role) {
            "pharmacy", "owner", "pharmacist" -> OwnerHomeActivity::class.java
            else                              -> HomeActivity::class.java
        }
        startActivity(Intent(this, destination).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}