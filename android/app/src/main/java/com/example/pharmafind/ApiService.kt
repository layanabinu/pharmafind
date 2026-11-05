package com.example.pharmafind

import retrofit2.Call
import retrofit2.http.*

interface ApiService {

    // ── AUTH ──────────────────────────────────────────────────────

    @POST("auth/login")
    fun login(@Body request: LoginRequest): Call<LoginResponse>

    @POST("auth/register")
    fun register(@Body request: RegisterRequest): Call<LoginResponse>

    @POST("auth/register-owner")
    fun registerOwner(@Body request: OwnerRegisterRequest): Call<LoginResponse>

    // ── PHARMACIES ────────────────────────────────────────────────

    @POST("pharmacies")
    fun addPharmacy(@Body request: PharmacyCreate): Call<SuccessResponse>

    @GET("pharmacies")
    fun getPharmacies(
        @Query("lat") lat: Double = 9.5916,
        @Query("lng") lng: Double = 76.5222,
        @Query("radius_km") radius_km: Double = 5.0,
        @Query("limit") limit: Int = 5
    ): Call<List<Pharmacy>>

    @GET("pharmacies/search")
    fun searchPharmacies(
        @Query("query") query: String,
        @Query("lat") lat: Double = 9.5916,
        @Query("lng") lng: Double = 76.5222,
        @Query("radius_km") radius_km: Double = 20.0
    ): Call<List<Pharmacy>>

    // ── MEDICINES ─────────────────────────────────────────────────

    @GET("medicines/search")
    fun searchMedicine(
        @Query("query") query: String,
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radius_km") radius_km: Double = 10.0,
        @Query("limit") limit: Int = 8
    ): Call<MedicineSearchResponse>

    @GET("medicines")
    fun getAllMedicines(): Call<List<MedicineInfo>>

    // ── USER MEDICINES ────────────────────────────────────────────

    @GET("my-medicines/{user_id}")
    fun getUserMedicines(
        @Path("user_id") userId: Int
    ): Call<List<UserMedicineItem>>

    @POST("my-medicines")
    fun addUserMedicine(
        @Body request: UserMedicineCreate
    ): Call<AddMedicineResponse>

    // NEW: update dosage, frequency, tablets
    @PUT("my-medicines/{id}")
    fun updateUserMedicine(
        @Path("id") medicineId: Int,
        @Body request: UserMedicineUpdate
    ): Call<UpdateMedicineResponse>

    @DELETE("my-medicines/{id}")
    fun deleteUserMedicine(
        @Path("id") medicineId: Int
    ): Call<DeleteMedicineResponse>

    // ── SEARCH SUGGESTIONS ────────────────────────────────────────

    @GET("suggestions")
    fun getSuggestions(
        @Query("query") query: String,
        @Query("lat") lat: Double,
        @Query("lng") lng: Double
    ): Call<SuggestionResponse>

    // ── GOOGLE PLACES POWERED NEARBY ─────────────────────────────
    // This is the real-world pharmacy endpoint.
    // Returns pharmacies from Google Places if key is set,
    // falls back to database automatically if not.

    @GET("pharmacies/nearby")
    fun getNearbyPlacesPharmacies(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radius_km") radius_km: Double = 5.0,
        @Query("limit") limit: Int = 5
    ): Call<NearbyPharmaciesResponse>

    // ── SEMANTIC SEARCH & RECOMMENDATIONS ───────────────────────────

    @GET("search/semantic")
    fun semanticSearch(
        @Query("query") query: String
    ): Call<SemanticSearchResponse>

    @GET("recommendations/{userId}")
    fun getRecommendations(
        @Path("userId") userId: Int
    ): Call<RecommendationsResponse>

    @GET("medicines/{medicineId}/info")
    fun getMedicineInfo(
        @Path("medicineId") medicineId: Int
    ): Call<MedicineInfoDetail>

    @GET("medicines/{medicineId}/also-taken")
    fun getAlsoTaken(
        @Path("medicineId") medicineId: Int
    ): Call<AlsoTakenResponse>

    // ── DIRECTIONS ────────────────────────────────────────────────

    @GET("directions")
    fun getDirections(
        @Query("origin_lat")  originLat: Double,
        @Query("origin_lng")  originLng: Double,
        @Query("dest_lat")    destLat: Double,
        @Query("dest_lng")    destLng: Double,
        @Query("mode")        mode: String = "driving"
    ): Call<DirectionsResponse>

    // ── MEDICATION HISTORY ────────────────────────────────────────

    @GET("history/{userId}")
    fun getMedicationHistory(
        @Path("userId") userId: Int
    ): Call<MedicationHistoryResponse>

    @GET("history/{userId}/timeline")
    fun getMedicationTimeline(
        @Path("userId") userId: Int
    ): Call<TimelineResponse>

    @POST("history")
    fun addHistoryEvent(
        @Body request: HistoryEventRequest
    ): Call<SuccessResponse>

    // ── RESERVATIONS / BOOKING ────────────────────────────────────

    @POST("reservations")
    fun createReservation(
        @Body request: ReservationRequest
    ): Call<ReservationResponse>

    @POST("reservations/multi")
    fun createMultiReservation(
        @Body request: MultiReservationRequest
    ): Call<MultiReservationResponse>

    // (removed duplicate getUserReservations — use reservations/user/{userId})

    // ── PRESCRIPTION INFO ─────────────────────────────────────────

    @GET("medicines/{medicineId}/prescription-info")
    fun getPrescriptionInfo(
        @Path("medicineId") medicineId: Int
    ): Call<PrescriptionInfoResponse>

    // ── PHARMACY OWNER DASHBOARD ──────────────────────────────────

    @GET("pharmacies/{id}")
    fun getPharmacyInventory(
        @Path("id") pharmacyId: Int
    ): Call<PharmacyDetailResponse>

    @POST("inventory/update")
    fun updateInventory(
        @Body request: InventoryUpdateRequest
    ): Call<SuccessResponse>

    // ── LSTM STOCK PREDICTION ─────────────────────────────────────

    @GET("ml/stock/predict")
    fun predictStock(
        @Query("pharmacy_id") pharmacyId: Int,
        @Query("medicine_id") medicineId: Int
    ): Call<StockPrediction>

    @GET("ml/stock/alerts")
    fun getStockAlerts(): Call<StockAlertsResponse>

    // ── RESERVATION HISTORY ───────────────────────────────────────

    @GET("reservations/user/{userId}")
    fun getUserReservations(
        @Path("userId") userId: Int
    ): Call<ReservationsListResponse>

    @PATCH("reservations/{reservationCode}/collect")
    fun markReservationCollected(
        @Path("reservationCode") code: String
    ): Call<Map<String, Any>>

    @DELETE("reservations/{reservationCode}")
    fun cancelReservation(
        @Path("reservationCode") code: String
    ): Call<Map<String, Any>>

    // ── OWNER PHARMACY RESERVATIONS ──────────────────────────────

    @GET("pharmacies/{id}/reservations")
    fun getPharmacyReservations(
        @Path("id") pharmacyId: Int
    ): Call<PharmacyReservationsResponse>

    // ── PROFILE UPDATE ────────────────────────────────────────────

    @PUT("users/{userId}")
    fun updateUserProfile(
        @Path("userId") userId: Int,
        @Body request: UserUpdateRequest
    ): Call<UserUpdateResponse>

    @GET("users/{userId}")
    fun getUserProfile(
        @Path("userId") userId: Int
    ): Call<Map<String, Any>>

}