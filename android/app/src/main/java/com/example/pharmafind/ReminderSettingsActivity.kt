package com.example.pharmafind

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ReminderSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: android.content.SharedPreferences

    private lateinit var switchRefillReminder: Switch
    private lateinit var switchMorningDose: Switch
    private lateinit var switchEveningDose: Switch
    private lateinit var switchGeofence: Switch
    private lateinit var switchStockAlert: Switch
    private lateinit var switchReservationExpiry: Switch
    private lateinit var layoutMorningTime: LinearLayout
    private lateinit var layoutEveningTime: LinearLayout
    private lateinit var txtMorningTime: TextView
    private lateinit var txtEveningTime: TextView
    private lateinit var rgNotificationSound: RadioGroup
    private lateinit var rbSoundDefault: RadioButton
    private lateinit var rbSoundChime: RadioButton
    private lateinit var rbSoundSilent: RadioButton
    private lateinit var switchVibrate: Switch
    private lateinit var switchDndRespect: Switch
    private lateinit var seekBarRefillDays: SeekBar
    private lateinit var txtRefillDaysLabel: TextView
    private lateinit var btnTestNotification: Button
    private lateinit var btnSaveSettings: Button
    private lateinit var btnBack: View

    private var testPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder_settings)
        prefs = getSharedPreferences("PharmaFindPrefs", MODE_PRIVATE)
        bindViews()
        loadSavedSettings()
        setupListeners()
        ensureNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        testPlayer?.release()
        testPlayer = null
    }

    private fun bindViews() {
        btnBack                 = findViewById(R.id.btnReminderBack)
        switchRefillReminder    = findViewById(R.id.switchRefillReminder)
        switchMorningDose       = findViewById(R.id.switchMorningDose)
        switchEveningDose       = findViewById(R.id.switchEveningDose)
        switchGeofence          = findViewById(R.id.switchGeofenceAlert)
        switchStockAlert        = findViewById(R.id.switchStockAlert)
        switchReservationExpiry = findViewById(R.id.switchReservationExpiry)
        layoutMorningTime       = findViewById(R.id.layoutMorningTime)
        layoutEveningTime       = findViewById(R.id.layoutEveningTime)
        txtMorningTime          = findViewById(R.id.txtMorningTime)
        txtEveningTime          = findViewById(R.id.txtEveningTime)
        rgNotificationSound     = findViewById(R.id.rgNotificationSound)
        rbSoundDefault          = findViewById(R.id.rbSoundDefault)
        rbSoundChime            = findViewById(R.id.rbSoundChime)
        rbSoundSilent           = findViewById(R.id.rbSoundSilent)
        switchVibrate           = findViewById(R.id.switchVibrate)
        switchDndRespect        = findViewById(R.id.switchDndRespect)
        seekBarRefillDays       = findViewById(R.id.seekBarRefillDays)
        txtRefillDaysLabel      = findViewById(R.id.txtRefillDaysLabel)
        btnTestNotification     = findViewById(R.id.btnTestNotification)
        btnSaveSettings         = findViewById(R.id.btnSaveReminderSettings)
    }

    private fun loadSavedSettings() {
        switchRefillReminder.isChecked    = prefs.getBoolean("remind_refill", true)
        switchMorningDose.isChecked       = prefs.getBoolean("remind_morning", false)
        switchEveningDose.isChecked       = prefs.getBoolean("remind_evening", false)
        switchGeofence.isChecked          = prefs.getBoolean("remind_geofence", true)
        switchStockAlert.isChecked        = prefs.getBoolean("remind_stock", true)
        switchReservationExpiry.isChecked = prefs.getBoolean("remind_reservation", true)
        switchVibrate.isChecked           = prefs.getBoolean("remind_vibrate", true)
        switchDndRespect.isChecked        = prefs.getBoolean("remind_dnd", false)
        txtMorningTime.text = prefs.getString("remind_morning_time", "08:00 AM")
        txtEveningTime.text = prefs.getString("remind_evening_time", "08:00 PM")
        layoutMorningTime.visibility = if (switchMorningDose.isChecked) View.VISIBLE else View.GONE
        layoutEveningTime.visibility = if (switchEveningDose.isChecked) View.VISIBLE else View.GONE
        val days = prefs.getInt("remind_refill_days", 7)
        seekBarRefillDays.progress = days
        txtRefillDaysLabel.text    = "Alert me when $days days of medicine remain"
        when (prefs.getString("remind_sound", "default")) {
            "chime"  -> rbSoundChime.isChecked  = true
            "silent" -> rbSoundSilent.isChecked = true
            else     -> rbSoundDefault.isChecked = true
        }
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        switchMorningDose.setOnCheckedChangeListener { _, c ->
            layoutMorningTime.visibility = if (c) View.VISIBLE else View.GONE
        }
        switchEveningDose.setOnCheckedChangeListener { _, c ->
            layoutEveningTime.visibility = if (c) View.VISIBLE else View.GONE
        }

        layoutMorningTime.setOnClickListener { showTimePicker(true) }
        layoutEveningTime.setOnClickListener { showTimePicker(false) }
        txtMorningTime.setOnClickListener    { showTimePicker(true) }
        txtEveningTime.setOnClickListener    { showTimePicker(false) }

        seekBarRefillDays.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                txtRefillDaysLabel.text = "Alert me when ${progress.coerceAtLeast(1)} days of medicine remain"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?)  {}
        })

        // Preview sound when user picks a radio button
        rgNotificationSound.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbSoundDefault -> previewSound("default")
                R.id.rbSoundChime   -> previewSound("chime")
                R.id.rbSoundSilent  -> stopPreview()
            }
        }

        btnTestNotification.setOnClickListener { sendTestNotification() }
        btnSaveSettings.setOnClickListener     { saveSettings() }
    }

    // ── Sound preview ─────────────────────────────────────────────
    private fun previewSound(type: String) {
        stopPreview()
        val uri = getSoundUri(type) ?: return
        try {
            testPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@ReminderSettingsActivity, uri)
                setOnPreparedListener { start() }
                setOnCompletionListener { release(); testPlayer = null }
                prepareAsync()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot preview sound: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPreview() {
        testPlayer?.release()
        testPlayer = null
    }

    private fun getSoundUri(type: String): Uri? = when (type) {
        "silent" -> null
        "chime"  -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        else     -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
    }

    // ── Time picker ───────────────────────────────────────────────
    private fun showTimePicker(isMorning: Boolean) {
        val current = if (isMorning) txtMorningTime.text.toString()
        else           txtEveningTime.text.toString()
        val (hour, minute) = try {
            val parts = current.replace(" AM", "").replace(" PM", "").split(":")
            val h  = parts[0].toInt()
            val m  = parts[1].toInt()
            val pm = current.contains("PM")
            Pair(if (pm && h != 12) h + 12 else if (!pm && h == 12) 0 else h, m)
        } catch (e: Exception) {
            Pair(if (isMorning) 8 else 20, 0)
        }

        android.app.TimePickerDialog(this, { _, selHour, selMin ->
            val amPm        = if (selHour < 12) "AM" else "PM"
            val displayHour = when { selHour == 0 -> 12; selHour > 12 -> selHour - 12; else -> selHour }
            val timeStr     = "%02d:%02d %s".format(displayHour, selMin, amPm)
            if (isMorning) { txtMorningTime.text = timeStr; scheduleAlarm(selHour, selMin, MORNING_ALARM_ID, "morning") }
            else           { txtEveningTime.text = timeStr; scheduleAlarm(selHour, selMin, EVENING_ALARM_ID, "evening") }
        }, hour, minute, false).show()
    }

    // ── Alarm scheduling ──────────────────────────────────────────
    private fun scheduleAlarm(hour: Int, minute: Int, requestCode: Int, label: String) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            this, requestCode,
            Intent(this, MedicineReminderReceiver::class.java).apply { putExtra("reminder_type", label) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(java.util.Calendar.DAY_OF_MONTH, 1)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            else
                am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            Toast.makeText(this, "Reminder set for ${if (hour < 12) "morning" else "evening"}",
                Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this,
                "Allow exact alarms in Settings > Apps > PharmaFind > Permissions",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun cancelAlarm(requestCode: Int) {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        PendingIntent.getBroadcast(
            this, requestCode,
            Intent(this, MedicineReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { am.cancel(it) }
    }

    // ── Save ──────────────────────────────────────────────────────
    private fun saveSettings() {
        val sound = when (rgNotificationSound.checkedRadioButtonId) {
            R.id.rbSoundChime  -> "chime"
            R.id.rbSoundSilent -> "silent"
            else               -> "default"
        }
        prefs.edit().apply {
            putBoolean("remind_refill",        switchRefillReminder.isChecked)
            putBoolean("remind_morning",       switchMorningDose.isChecked)
            putBoolean("remind_evening",       switchEveningDose.isChecked)
            putBoolean("remind_geofence",      switchGeofence.isChecked)
            putBoolean("remind_stock",         switchStockAlert.isChecked)
            putBoolean("remind_reservation",   switchReservationExpiry.isChecked)
            putBoolean("remind_vibrate",       switchVibrate.isChecked)
            putBoolean("remind_dnd",           switchDndRespect.isChecked)
            putString("remind_morning_time",   txtMorningTime.text.toString())
            putString("remind_evening_time",   txtEveningTime.text.toString())
            putInt("remind_morning_minute",    parsedMinute(txtMorningTime.text.toString()))
            putInt("remind_evening_minute",    parsedMinute(txtEveningTime.text.toString()))
            putString("remind_sound",          sound)
            putInt("remind_refill_days",       seekBarRefillDays.progress.coerceAtLeast(1))
            apply()
        }
        // Schedule or cancel morning alarm
        if (switchMorningDose.isChecked) {
            val t = txtMorningTime.text.toString()
            val h = parsedHour(t); val m = parsedMinute(t)
            scheduleAlarm(h, m, MORNING_ALARM_ID, "morning")
        } else {
            cancelAlarm(MORNING_ALARM_ID)
        }
        // Schedule or cancel evening alarm
        if (switchEveningDose.isChecked) {
            val t = txtEveningTime.text.toString()
            val h = parsedHour(t); val m = parsedMinute(t)
            scheduleAlarm(h, m, EVENING_ALARM_ID, "evening")
        } else {
            cancelAlarm(EVENING_ALARM_ID)
        }
        // Recreate notification channel with new sound setting BEFORE finishing
        ensureNotificationChannel()
        Toast.makeText(this, "✅ Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    // ── Test notification (plays real sound) ──────────────────────
    private fun sendTestNotification() {
        val soundChoice = prefs.getString("remind_sound", "default") ?: "default"
        val vibrate     = prefs.getBoolean("remind_vibrate", true)
        val soundUri    = getSoundUri(soundChoice)

        // Play sound through device speakers right now so user can hear it
        if (soundUri != null) {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_SILENT) {
                previewSound(soundChoice)
            } else {
                Toast.makeText(this, "Phone is on silent — unmute to hear the alarm sound",
                    Toast.LENGTH_LONG).show()
            }
        }

        // Also vibrate
        if (vibrate) {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 300, 200, 300), -1)
            }
        }

        // Post a visible notification
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(this)
        }
        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PharmaFind Test Reminder")
            .setContentText("This is how your medicine reminders will look and sound!")
            .setAutoCancel(true)
        manager.notify(9999, builder.build())

        Toast.makeText(this, "Test sent! Check your notifications.", Toast.LENGTH_SHORT).show()
    }

    // ── Notification channel (sound + vibration wired in) ─────────
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager  = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val soundChoice = prefs.getString("remind_sound", "default") ?: "default"
            val soundUri    = getSoundUri(soundChoice)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            // Delete and recreate so sound preference changes take effect
            manager.deleteNotificationChannel(CHANNEL_ID)

            val channel = NotificationChannel(
                CHANNEL_ID, "Medicine Reminders", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Daily medicine dose reminders and refill alerts"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                setShowBadge(true)
                setSound(soundUri, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID            = "pharmafind_reminders"
        const val OWNER_CHANNEL_ID      = "pharmafind_owner_alerts"
        const val MORNING_ALARM_ID      = 1001
        const val EVENING_ALARM_ID      = 1002
        const val REFILL_ALARM_ID       = 1003
        const val OWNER_STOCK_ALARM_ID  = 1004

        fun parsedMinute(timeStr: String): Int = try {
            timeStr.replace(" AM", "").replace(" PM", "").split(":")[1].toIntOrNull() ?: 0
        } catch (e: Exception) { 0 }

        fun parsedHour(timeStr: String): Int = try {
            val parts = timeStr.replace(" AM","").replace(" PM","").split(":")
            val h = parts[0].toInt()
            val isPm = timeStr.contains("PM")
            if (isPm && h != 12) h + 12 else if (!isPm && h == 12) 0 else h
        } catch (e: Exception) { if (timeStr.contains("PM")) 20 else 8 }

        fun checkAndFireRefillReminders(context: Context) {
            val prefs      = context.getSharedPreferences("PharmaFindPrefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("remind_refill", true)) return
            if (prefs.getInt("user_id", -1) == -1) return

            val refillDays = prefs.getInt("remind_refill_days", 7)
            val medCache   = prefs.getString("medicines_cache", "") ?: ""
            if (medCache.isEmpty()) return

            val lowMeds = mutableListOf<String>()
            medCache.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size >= 2) {
                    val name        = parts[0]
                    val strips      = parts[1].toIntOrNull() ?: 999
                    val dosesPerDay = if (parts.size >= 3) parts[2].toIntOrNull() ?: 1 else 1
                    val daysLeft    = if (dosesPerDay > 0) strips / dosesPerDay else strips
                    if (daysLeft <= refillDays) lowMeds.add("$name ($strips strips, ~$daysLeft days left)")
                }
            }
            if (lowMeds.isEmpty()) return

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val vibrate = prefs.getBoolean("remind_vibrate", true)

            val pendingIntent = PendingIntent.getActivity(
                context, REFILL_ALARM_ID,
                Intent(context, MyMedicinesActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                android.app.Notification.Builder(context, CHANNEL_ID)
            else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(context)
            }
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Medicine Refill Reminder")
                .setContentText(
                    if (lowMeds.size == 1) "${lowMeds[0]} — refill soon!"
                    else "${lowMeds.size} medicines running low — tap to view"
                )
                .setStyle(android.app.Notification.BigTextStyle()
                    .bigText("Low stock:\n" + lowMeds.joinToString("\n")))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
            if (vibrate) builder.setVibrate(longArrayOf(0, 300, 200, 300))
            manager.notify(REFILL_ALARM_ID, builder.build())

            // Play refill alarm sound directly
            val soundChoice = prefs.getString("remind_sound", "default") ?: "default"
            if (soundChoice != "silent") {
                try {
                    val soundUri = if (soundChoice == "chime")
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                    else
                        android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)
                    android.media.RingtoneManager.getRingtone(context, soundUri)?.play()
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }
}

// ── Alarm Receiver ─────────────────────────────────────────────────
class MedicineReminderReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs        = context.getSharedPreferences("PharmaFindPrefs", Context.MODE_PRIVATE)
        val reminderType = intent.getStringExtra("reminder_type") ?: "morning"
        val soundChoice  = prefs.getString("remind_sound", "default") ?: "default"
        val vibrate      = prefs.getBoolean("remind_vibrate", true)
        val dndRespect   = prefs.getBoolean("remind_dnd", false)

        // Respect DND
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (dndRespect && audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            rescheduleNextDay(context, reminderType, prefs)
            return
        }

        val title = if (reminderType == "morning") "Morning Medicines" else "Evening Medicines"
        val body  = "Time to take your medicines. Stay on track!"

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val pendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MyMedicinesActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri: Uri? = when (soundChoice) {
            "silent" -> null
            "chime"  -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            else     -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, ReminderSettingsActivity.CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context).also { b ->
                if (soundUri != null) b.setSound(soundUri)
            }
        }
        builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        if (vibrate) builder.setVibrate(longArrayOf(0, 300, 200, 300))

        val notifId = if (reminderType == "morning") 1001 else 1002
        manager.notify(notifId, builder.build())

        // Play sound directly — guarantees audibility on all Android versions
        // On O+ this supplements the channel sound (one will play)
        if (soundChoice != "silent" && soundUri != null) {
            try {
                val ringtone = RingtoneManager.getRingtone(context, soundUri)
                ringtone?.play()
            } catch (e: Exception) { /* ignore */ }
        }

        // Vibrate device
        if (vibrate) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(longArrayOf(0, 300, 200, 300), -1)
            }
        }

        // Check refill while we're awake
        if (prefs.getBoolean("remind_refill", true)) {
            ReminderSettingsActivity.checkAndFireRefillReminders(context)
        }

        rescheduleNextDay(context, reminderType, prefs)
    }

    private fun rescheduleNextDay(context: Context, reminderType: String,
                                  prefs: android.content.SharedPreferences) {
        val timeStr = if (reminderType == "morning")
            prefs.getString("remind_morning_time", "08:00 AM") ?: "08:00 AM"
        else
            prefs.getString("remind_evening_time", "08:00 PM") ?: "08:00 PM"

        val hour   = parseHour(timeStr)
        val minute = if (reminderType == "morning")
            prefs.getInt("remind_morning_minute", 0)
        else
            prefs.getInt("remind_evening_minute", 0)

        val requestCode = if (reminderType == "morning") 1001 else 1002

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, MedicineReminderReceiver::class.java).apply {
                putExtra("reminder_type", reminderType)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        else
            am.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    private fun parseHour(timeStr: String): Int = try {
        val parts = timeStr.replace(" AM", "").replace(" PM", "").split(":")
        val h  = parts[0].toInt()
        val pm = timeStr.contains("PM")
        if (pm && h != 12) h + 12 else if (!pm && h == 12) 0 else h
    } catch (e: Exception) { 8 }
}

// ─────────────────────────────────────────────────────────────────────────
// OwnerStockAlertReceiver
// ─────────────────────────────────────────────────────────────────────────
// Fires once daily (scheduled when owner logs in).
// Calls GET /ml/stock/alerts, filters to this owner's pharmacy,
// fires a notification if any critical/warning alerts exist,
// then reschedules itself for the next day.

class OwnerStockAlertReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        val prefs      = context.getSharedPreferences("PharmaFindPrefs", android.content.Context.MODE_PRIVATE)
        val pharmacyId = prefs.getInt("pharmacy_id", -1)
        val role       = prefs.getString("user_role", "") ?: ""

        // Only run for pharmacy owners
        if (pharmacyId == -1 || role != "pharmacy") {
            return
        }

        // Call stock alerts endpoint
        ApiClient.apiService.getStockAlerts()
            .enqueue(object : retrofit2.Callback<StockAlertsResponse> {
                override fun onResponse(
                    call: retrofit2.Call<StockAlertsResponse>,
                    response: retrofit2.Response<StockAlertsResponse>
                ) {
                    val body     = response.body() ?: return
                    val critical = body.critical.filter { it.pharmacy_id == pharmacyId }
                    val warning  = body.warning.filter  { it.pharmacy_id == pharmacyId }

                    if (critical.isEmpty() && warning.isEmpty()) {
                        reschedule(context)
                        return
                    }

                    fireOwnerStockNotification(context, critical, warning, prefs)
                    reschedule(context)
                }

                override fun onFailure(
                    call: retrofit2.Call<StockAlertsResponse>,
                    t: Throwable
                ) {
                    // Silent fail — try again tomorrow
                    reschedule(context)
                }
            })
    }

    private fun fireOwnerStockNotification(
        context:  android.content.Context,
        critical: List<StockAlert>,
        warning:  List<StockAlert>,
        prefs:    android.content.SharedPreferences
    ) {
        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager

        // Ensure owner notification channel exists
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (manager.getNotificationChannel(ReminderSettingsActivity.OWNER_CHANNEL_ID) == null) {
                val ch = android.app.NotificationChannel(
                    ReminderSettingsActivity.OWNER_CHANNEL_ID,
                    "Stock Alerts",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Low stock warnings for pharmacy owners"
                    enableVibration(true)
                    setSound(
                        android.media.RingtoneManager.getDefaultUri(
                            android.media.RingtoneManager.TYPE_NOTIFICATION),
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .build()
                    )
                }
                manager.createNotificationChannel(ch)
            }
        }

        // Build notification body
        val totalAlerts = critical.size + warning.size
        val title = when {
            critical.isNotEmpty() -> "🚨 ${critical.size} medicine(s) critically low!"
            else                  -> "⚠️ ${warning.size} medicine(s) running low"
        }

        val lines = buildString {
            critical.take(3).forEach { appendLine("🔴 ${it.medicine_name}: ${it.current_stock} units left") }
            warning.take(3).forEach  { appendLine("🟡 ${it.medicine_name}: ${it.current_stock} units left") }
            if (totalAlerts > 6) appendLine("...and ${totalAlerts - 6} more")
        }.trim()

        val pendingIntent = android.app.PendingIntent.getActivity(
            context,
            ReminderSettingsActivity.OWNER_STOCK_ALARM_ID,
            android.content.Intent(context, PharmacyOwnerDashboardActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            android.app.Notification.Builder(context, ReminderSettingsActivity.OWNER_CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }

        builder
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(if (totalAlerts == 1) lines else "$totalAlerts medicines need attention")
            .setStyle(android.app.Notification.BigTextStyle().bigText(lines))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        if (prefs.getBoolean("remind_vibrate", true)) {
            builder.setVibrate(longArrayOf(0, 300, 200, 300))
        }

        manager.notify(ReminderSettingsActivity.OWNER_STOCK_ALARM_ID, builder.build())

        // Play notification sound directly
        try {
            val soundUri = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(context, soundUri)?.play()
        } catch (e: Exception) { /* ignore */ }
    }

    private fun reschedule(context: android.content.Context) {
        // Reschedule for same time tomorrow
        val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi = android.app.PendingIntent.getBroadcast(
            context,
            ReminderSettingsActivity.OWNER_STOCK_ALARM_ID,
            android.content.Intent(context, OwnerStockAlertReceiver::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_MONTH, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 9)   // 9 AM daily check
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setExact(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (e: SecurityException) { /* ignore */ }
    }

    companion object {
        /**
         * Schedule the daily owner stock check.
         * Call this when owner logs in or app starts with owner role.
         */
        fun schedule(context: android.content.Context) {
            val am = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = android.app.PendingIntent.getBroadcast(
                context,
                ReminderSettingsActivity.OWNER_STOCK_ALARM_ID,
                android.content.Intent(context, OwnerStockAlertReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            // First fire: today at 9 AM, or in 1 minute if already past 9 AM
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 9)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    // Already past 9 AM — fire in 1 minute so owner gets immediate feedback
                    timeInMillis = System.currentTimeMillis() + 60_000
                }
            }
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                } else {
                    am.setExact(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                }
            } catch (e: SecurityException) { /* ignore */ }
        }
    }
}