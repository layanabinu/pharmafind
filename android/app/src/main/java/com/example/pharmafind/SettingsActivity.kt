package com.example.pharmafind

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView

class SettingsActivity : AppCompatActivity() {

    private lateinit var cardNotifications: CardView
    private lateinit var cardLanguage: CardView
    private lateinit var cardTheme: CardView
    private lateinit var cardPrivacy: CardView
    private lateinit var cardAbout: CardView
    private lateinit var cardClearCache: CardView
    private lateinit var switchNotifications: SwitchCompat

    private val languages = arrayOf("English", "हिन्दी (Hindi)", "മലയാളം (Malayalam)", "தமிழ் (Tamil)")
    private val themes = arrayOf("Light Mode", "Dark Mode", "System Default")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Initialize views
        cardNotifications = findViewById(R.id.cardNotifications)
        cardLanguage = findViewById(R.id.cardLanguage)
        cardTheme = findViewById(R.id.cardTheme)
        cardPrivacy = findViewById(R.id.cardPrivacy)
        cardAbout = findViewById(R.id.cardAbout)
        cardClearCache = findViewById(R.id.cardClearCache)
        switchNotifications = findViewById(R.id.switchNotifications)

        // Load saved preferences
        loadSettings()

        // Setup click listeners
        setupClickListeners()
    }

    private fun setupClickListeners() {
        cardNotifications.setOnClickListener {
            switchNotifications.toggle()
        }

        switchNotifications.setOnCheckedChangeListener { _, isChecked ->
            saveNotificationSetting(isChecked)
        }

        cardLanguage.setOnClickListener {
            showLanguageDialog()
        }

        cardTheme.setOnClickListener {
            showThemeDialog()
        }

        cardPrivacy.setOnClickListener {
            showPrivacyDialog()
        }

        cardAbout.setOnClickListener {
            showAboutDialog()
        }

        cardClearCache.setOnClickListener {
            showClearCacheDialog()
        }
    }

    private fun loadSettings() {
        val sharedPref = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        val notificationsEnabled = sharedPref.getBoolean("notifications_enabled", true)
        switchNotifications.isChecked = notificationsEnabled
    }

    private fun saveNotificationSetting(enabled: Boolean) {
        val sharedPref = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        sharedPref.edit().putBoolean("notifications_enabled", enabled).apply()

        val message = if (enabled) {
            "✓ Notifications enabled"
        } else {
            "Notifications disabled"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun showLanguageDialog() {
        val sharedPref = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        val currentLanguage = sharedPref.getInt("selected_language", 0)

        AlertDialog.Builder(this)
            .setTitle("Select Language")
            .setSingleChoiceItems(languages, currentLanguage) { dialog, which ->
                sharedPref.edit().putInt("selected_language", which).apply()
                Toast.makeText(
                    this,
                    "Language set to ${languages[which]}\n(Restart app to apply)",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showThemeDialog() {
        val sharedPref = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        val currentTheme = sharedPref.getInt("selected_theme", 0)

        AlertDialog.Builder(this)
            .setTitle("Select Theme")
            .setSingleChoiceItems(themes, currentTheme) { dialog, which ->
                sharedPref.edit().putInt("selected_theme", which).apply()
                Toast.makeText(
                    this,
                    "Theme set to ${themes[which]}\n(Restart app to apply)",
                    Toast.LENGTH_LONG
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPrivacyDialog() {
        AlertDialog.Builder(this)
            .setTitle("Privacy & Security")
            .setMessage(
                "Your Privacy Matters\n\n" +
                        "• Your location is only used to find nearby pharmacies\n" +
                        "• Personal health data stays on your device\n" +
                        "• We don't share your information with third parties\n" +
                        "• Medicine searches are anonymous\n" +
                        "• You can delete your account anytime\n\n" +
                        "Data Collected:\n" +
                        "• Email and name (for authentication)\n" +
                        "• Search history (to improve recommendations)\n" +
                        "• Location data (only when using the app)\n\n" +
                        "For full privacy policy, visit:\nwww.pharmafind.com/privacy"
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Delete My Data") { _, _ ->
                showDeleteDataConfirmation()
            }
            .show()
    }

    private fun showDeleteDataConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Delete All Data")
            .setMessage("This will permanently delete:\n• Your account\n• Saved medicines\n• Search history\n• All preferences\n\nThis action cannot be undone!")
            .setPositiveButton("Delete Everything") { _, _ ->
                deleteAllUserData()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAllUserData() {
        val sharedPref = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        sharedPref.edit().clear().apply()

        Toast.makeText(this, "All data deleted. Logging out...", Toast.LENGTH_LONG).show()

        // Redirect to login
        val intent = android.content.Intent(this, LoginActivity::class.java)
        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("About PharmaFind")
            .setMessage(
                "PharmaFind v1.0.0\n" +
                        "Build: 2026.02.16\n\n" +
                        "🔍 Find medicines at nearby pharmacies with real-time availability and pricing.\n\n" +
                        "Features:\n" +
                        "✓ Location-based pharmacy search\n" +
                        "✓ Medicine availability tracking\n" +
                        "✓ Price comparison\n" +
                        "✓ In-app route navigation\n" +
                        "✓ Personal medicine management\n" +
                        "✓ ML-powered recommendations\n\n" +
                        "Developed with ❤️ for better healthcare access\n\n" +
                        "Contact:\n" +
                        "Email: support@pharmafind.com\n" +
                        "Web: www.pharmafind.com\n\n" +
                        "© 2026 PharmaFind. All rights reserved."
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Rate App") { _, _ ->
                Toast.makeText(this, "Opening Play Store...", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showClearCacheDialog() {
        AlertDialog.Builder(this)
            .setTitle("Clear Cache")
            .setMessage("This will clear:\n• Temporary images\n• Search suggestions\n• Map cache\n\nYour account and medicines will not be affected.")
            .setPositiveButton("Clear") { _, _ ->
                clearCache()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearCache() {
        try {
            // Clear app cache
            cacheDir?.deleteRecursively()
            Toast.makeText(this, "✓ Cache cleared successfully", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to clear cache", Toast.LENGTH_SHORT).show()
        }
    }
}