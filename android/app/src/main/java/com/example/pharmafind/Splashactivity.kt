package com.example.pharmafind

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Wait 2 seconds then navigate based on login state
        Handler(Looper.getMainLooper()).postDelayed({
            val prefs       = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
            val isLoggedIn  = prefs.getBoolean("is_logged_in", false)
            val hasToken    = prefs.getString("auth_token", null) != null

            val role = prefs.getString("user_role", "patient") ?: "patient"
            val destination = when {
                !isLoggedIn || !hasToken                          -> LoginActivity::class.java
                role in listOf("pharmacy","owner","pharmacist")  -> OwnerHomeActivity::class.java
                else                                             -> HomeActivity::class.java
            }

            startActivity(Intent(this, destination))
            finish()
        }, 2000)
    }
}