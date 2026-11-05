package com.example.pharmafind

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {

    // =============================================
    // HOW TO FIND YOUR PC's IP ADDRESS:
    // Windows: Open CMD → type "ipconfig" → look for IPv4 Address
    // Mac/Linux: Open Terminal → type "ifconfig" → look for inet
    //
    // Your phone and PC must be on the SAME WiFi network!
    //
    // Example IPs:
    //   192.168.1.x  (most home routers)
    //   192.168.0.x  (some routers)
    //   10.0.0.x     (some networks)
    //
    // After changing IP, just rebuild the app — no other changes needed.
    // =============================================

    private const val DEV_IP = "192.168.125.140"       // ← CHANGE THIS to your PC's IP
    private const val DEV_PORT = "8000"
    private const val BASE_URL = "http://$DEV_IP:$DEV_PORT/"


    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }

    // Quick way to check what URL is being used (useful for debugging)
    fun getBaseUrl(): String = BASE_URL
}