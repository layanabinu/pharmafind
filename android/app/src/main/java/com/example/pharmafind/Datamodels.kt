package com.example.pharmafind
import com.google.gson.annotations.SerializedName
// ── AUTH ──────────────────────────────────────────────────────────

data class LoginRequest(val email: String, val password: String)

data class RegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val role: String = "patient"   // defaults to patient; no need to pass it from UI
)

data class PharmacyCreate(
    val name:      String,
    val address:   String,
    val city:      String = "Kottayam",
    val state:     String = "Kerala",
    val latitude:  Double,
    val longitude: Double,
    val phone:     String = ""
)

data class OwnerRegisterRequest(
    val name:             String,
    val email:            String,
    val password:         String,
    val role:             String = "pharmacy",
    val pharmacy_name:    String,
    val pharmacy_address: String,
    val pharmacy_city:    String = "Kottayam",
    val pharmacy_state:   String = "Kerala",
    val pharmacy_lat:     Double,
    val pharmacy_lng:     Double,
    val pharmacy_phone:   String = ""
)

data class LoginResponse(
    val success: Boolean, val message: String,
    val user: User?, val token: String?
)

data class User(
    val id: Int,
    val email: String,
    val name: String,
    val role: String,
    val phone: String? = "",
    val pharmacy_id: Int? = null,       // only set for pharmacy owners
    val pharmacy_name: String? = null   // only set for pharmacy owners
)

// ── PHARMACY ──────────────────────────────────────────────────────

data class Pharmacy(
    val id: Int,
    val name: String,
    val place: String,
    val lat: Double,
    val lng: Double,
    val distance_km: Double,
    val is_open: Boolean,
    val rating: Double? = 0.0,
    val phone: String? = "",
    val address: String? = "",
    val price: Double? = null,
    val stock: Int? = null,
    val medicine_id: Int? = null,
    val medicine_name: String? = null,
    val medicine_strength: String? = null,
    val is_prescription_required: Boolean? = false,
    val medicine_uses: String? = "",
    val medicine_side_effects: String? = "",
    val medicine_image_url: String? = "",
    val medicine_manufacturer: String? = "",
    val excellent_review_pct: Int? = 0,
    val average_review_pct: Int? = 0,
    val poor_review_pct: Int? = 0,
    // ML ranking fields
    val rank_score: Double? = null,
    val rank_label: String? = null,
    val rank_position: Int? = null
)

// ── MEDICINE ──────────────────────────────────────────────────────

data class MedicineSearchResponse(
    val medicine: MedicineInfo,
    val available_at: List<PharmacyMedicineResult>,
    val total_pharmacies: Int = 0
)

data class MedicineInfo(
    val id: Int, val name: String,
    val generic_name: String, val strength: String,
    val form: String,
    val description: String = "",
    val uses: String = "",
    val side_effects: String = "",
    val image_url: String = "",
    val manufacturer: String = "",
    val excellent_review_pct: Int = 0,
    val average_review_pct: Int = 0,
    val poor_review_pct: Int = 0,
    val is_prescription_required: Boolean = false
)

data class PharmacyMedicineResult(
    val pharmacy_id: Int, val pharmacy_name: String,
    val pharmacy_address: String? = "",
    val medicine_id: Int, val medicine_name: String,
    val price: Double, val stock: Int,
    val is_available: Boolean,
    val lat: Double, val lng: Double,
    val distance_km: Double,
    val phone: String? = "", val rating: Double? = 0.0,
    val is_prescription_required: Boolean? = false
)

// ── UI MODEL ─────────────────────────────────────────────────────

data class PharmacyCard(
    val id: Int, val name: String, val address: String,
    val distance: String, val rating: Float, val phone: String,
    val isOpen: Boolean, val lat: Double, val lng: Double,
    val medicineName: String = "",
    val medicinePrice: String = "",
    val medicineStock: Int = 0,
    val medicineId: Int = -1,
    val isPrescriptionRequired: Boolean = false,
    val medicineStrength: String = "",
    val medicineUses: String = "",
    val medicineSideEffects: String = "",
    val medicineImageUrl: String = "",
    val medicineManufacturer: String = "",
    val excellentReviewPct: Int = 0,
    val averageReviewPct: Int = 0,
    val poorReviewPct: Int = 0
)

// ── USER MEDICINES ────────────────────────────────────────────────

data class UserMedicineItem(
    val id: Int,
    val medicineName: String,
    val dosage: String,
    val frequency: String,
    val stripsRemaining: Int,
    val tabletsPerStrip: Int = 10,
    val reminderEnabled: Boolean,
    val timeOfDay: String = "Morning"
)

// Request: POST /my-medicines
data class UserMedicineCreate(
    val user_id: Int,
    val medicine_name: String,
    val dosage: String? = null,
    val frequency: String? = null,
    val strips_remaining: Int? = null,
    val tablets_per_strip: Int = 10,
    val reminder_enabled: Boolean = true,
    val time_of_day: String = "Morning"
)

// Request: PUT /my-medicines/{id}  ← NEW
data class UserMedicineUpdate(
    val dosage: String? = null,
    val frequency: String? = null,
    @SerializedName("stripsremaining")
    val stripsRemaining: Int? = null,
    @SerializedName("reminderenabled")
    val reminderEnabled: Boolean? = null
)
// Response: POST /my-medicines
data class AddMedicineResponse(
    val success: Boolean, val message: String,
    val medicine: AddedMedicineDetail?
)

data class AddedMedicineDetail(
    val id: Int, val medicine_name: String,
    val dosage: String, val frequency: String,
    val strips_remaining: Int
)

// Response: PUT /my-medicines/{id}  ← NEW
data class UpdateMedicineResponse(
    val success: Boolean,
    val message: String
)

// Response: DELETE /my-medicines/{id}
data class DeleteMedicineResponse(
    val success: Boolean,
    val message: String
)

// ── SEARCH SUGGESTIONS ────────────────────────────────────────────

data class SuggestionItem(
    val text: String,       // display name e.g. "Paracetamol"
    val subtitle: String,   // e.g. "500mg · Tablet" or "Kottayam · 1.2 km"
    val type: String,       // "medicine" or "pharmacy"
    val id: Int
)

data class SuggestionResponse(
    val suggestions: List<SuggestionItem>
)

// ── SEMANTIC SEARCH ──────────────────────────────────────────────

data class SemanticSearchResult(
    val id: Int,
    val name: String,
    val generic_name: String,
    val strength: String,
    val type: String,
    val score: Float,
    val match_type: String,   // "exact" | "symptom_map" | "semantic"
    val is_prescription_required: Boolean
)

data class SemanticSearchResponse(
    val query: String,
    val semantic_available: Boolean,
    val results: List<SemanticSearchResult>
)

// ── RECOMMENDATIONS ───────────────────────────────────────────────

data class Recommendation(
    val medicine_id: Int,
    val medicine_name: String,
    val generic_name: String,
    val strength: String,
    val medicine_type: String,
    val reason: String,        // "Users with similar medicines also take this"
    val score: Float,
    val source: String,        // "collaborative" | "comorbidity" | "popular"
    val is_prescription_required: Boolean
)

data class RecommendationsResponse(
    val user_id: Int,
    val recommendations: List<Recommendation>,
    val count: Int,
    val algorithm: String
)

data class AlsoTakenItem(
    val medicine_id: Int,
    val medicine_name: String,
    val generic_name: String,
    val reason: String,
    val confidence: Float,
    val source: String
)

data class AlsoTakenResponse(
    val medicine_id: Int,
    val also_taken: List<AlsoTakenItem>
)

// ── DIRECTIONS / NAVIGATION ───────────────────────────────────────

data class RouteStep(
    val instruction: String,
    val distance: String,
    val maneuver: String
)

data class RouteInfo(
    val polyline: String,          // encoded polyline — decode with PolyUtil
    val distance_text: String,
    val distance_meters: Int,
    val duration_text: String,
    val duration_seconds: Int,
    val steps: List<RouteStep>,
    val start_address: String,
    val end_address: String
)

data class DirectionsResponse(
    val route: RouteInfo?,
    val fallback: Boolean,
    val message: String?
)

// ── MEDICATION HISTORY ────────────────────────────────────────────

data class RefillPrediction(
    val predicted_refill_date: String,
    val days_until_refill: Int,
    val days_of_supply_left: Int?,
    val avg_daily_consumption: Double?,
    val confidence: String,
    val message: String
)

data class AdherenceInfo(
    val score: Int?,
    val label: String,
    val message: String,
    val color: String,
    val days_tracked: Int?,
    val doses_taken: Int?,
    val doses_expected: Int?
)

data class HistoryEvent(
    val id: Int,
    val event_type: String,
    val strips_remaining: Int,
    val dosage: String,
    val frequency: String,
    val notes: String,
    val pharmacy_name: String,
    val event_date: String
)

data class MedHistoryItem(
    val medicine_name: String,
    val current_tablets: Int,
    val last_event: String,
    val last_event_date: String,
    val dosage: String,
    val frequency: String,
    val history: List<HistoryEvent>,
    val refill_prediction: RefillPrediction?,
    val adherence: AdherenceInfo?
)

data class DrugInteraction(
    val drug_a: String,
    val drug_b: String,
    val severity: String,
    val message: String
)

data class HistorySummary(
    val total_medicines: Int,
    val needs_refill_soon: Int,
    val interaction_warnings: Int
)

data class MedicationHistoryResponse(
    val user_id: Int,
    val medicines: List<MedHistoryItem>,
    val interactions: List<DrugInteraction>,
    val total_events: Int,
    val summary: HistorySummary?
)

data class TimelineEvent(
    val id: Int,
    val medicine_name: String,
    val event_type: String,
    val event_date: String,
    val tablets: Int,
    val pharmacy: String,
    val notes: String
)

data class TimelineResponse(
    val timeline: List<TimelineEvent>
)

data class HistoryEventRequest(
    val user_id: Int,
    val medicine_name: String,
    val event_type: String,
    val strips_remaining: Int = 0,
    val dosage: String? = null,
    val frequency: String? = null,
    val notes: String? = null,
    val pharmacy_name: String? = null,
    val medicine_id: Int? = null
)

// ── RESERVATION / BOOKING ─────────────────────────────────────────


// ── Medicine Information Module ───────────────────────────────────
data class MedicineInfoDetail(
    val id: Int,
    val brand_name: String,
    val generic_name: String = "",
    val manufacturer: String = "",
    val strength: String = "",
    val form: String = "",
    val description: String = "",
    val uses: List<String> = emptyList(),
    val side_effects: List<String> = emptyList(),
    val uses_raw: String = "",
    val side_effects_raw: String = "",
    val image_url: String = "",
    val excellent_review_pct: Int = 0,
    val average_review_pct: Int = 0,
    val poor_review_pct: Int = 0,
    val is_prescription_required: Boolean = false,
    val total_reviews: Int = 0
)

data class ReservationRequest(
    val user_id: Int,
    val pharmacy_id: Int,
    val medicine_id: Int,
    val quantity: Int = 1,
    val notes: String? = null,
    val is_prescription_required: Boolean = false
)

// ── Cart / multi-medicine reservation ─────────────────────────────
data class CartItemRequest(
    val medicine_id: Int,
    val quantity: Int = 1,
    val is_prescription_required: Boolean = false
)

data class MultiReservationRequest(
    val user_id: Int,
    val pharmacy_id: Int,
    val items: List<CartItemRequest>,
    val notes: String? = null
)

data class CartItemSummary(
    val medicine_name: String,
    val strength: String = "",
    val quantity: Int,
    val unit_price: Double,
    val total_price: Double,
    val reservation_id: String,
    val rx_required: Boolean = false
)

data class MultiReservationResponse(
    val success: Boolean,
    val order_id: String,
    val reservation_ids: List<String>,
    val message: String,
    val items: List<CartItemSummary>,
    val total_amount: Double,
    val pharmacy_name: String,
    val pharmacy_address: String? = "",
    val pharmacy_phone: String? = "",
    val expires_at: String? = null,
    val prescription_required: Boolean = false,
    val prescription_reminder: String? = null
)

// A local cart entry (stored in memory while user shops)
data class CartEntry(
    val pharmacyId: Int,
    val pharmacyName: String,
    val medicineId: Int,
    val medicineName: String,
    val medicineStrength: String,
    val pricePerStrip: Double,
    val isPrescriptionRequired: Boolean,
    var quantity: Int = 1
) : java.io.Serializable {
    val totalPrice: Double get() = pricePerStrip * quantity
}

data class PickupInfo(
    val pharmacy_name: String,
    val pharmacy_address: String,
    val pharmacy_phone: String,
    val medicine_name: String,
    val strength: String,
    val quantity: Int,
    val price_per_unit: Double,
    val total_price: Double,
    val reservation_id: String,
    val status: String,
    val note: String
)

data class ReservationResponse(
    val success: Boolean,
    val reservation_id: String,
    val message: String,
    val pickup_info: PickupInfo?,
    val prescription_required: Boolean?,
    val prescription_reminder: String?
)

data class PrescriptionInfoResponse(
    val medicine_id: Int,
    val medicine_name: String,
    val is_prescription_required: Boolean,
    val warning_title: String?,
    val warning_message: String?,
    val reminder_items: List<String>?
)

// ── PHARMACY OWNER DASHBOARD ──────────────────────────────────────

data class PharmacyDetailResponse(
    val id: Int,
    val name: String,
    val address: String,
    val city: String,
    val lat: Double,
    val lng: Double,
    val phone: String,
    val is_open: Boolean,
    val rating: Double,
    val distance_km: Double,
    val total_medicines: Int,
    val medicines: List<PharmacyMedicineDetail>
)

data class PharmacyMedicineDetail(
    val medicine_id: Int,
    val name: String,
    val strength: String,
    val price: Double,
    val stock: Int
)

data class InventoryUpdateRequest(
    val pharmacy_id: Int,
    val medicine_id: Int,
    val stock_quantity: Int,
    val price: Double,
    val is_available: Boolean
)

data class SuccessResponse(
    val success: Boolean,
    val message: String
)

data class StockPrediction(
    val pharmacy_id: Int,
    val medicine_id: Int,
    val current_stock: Int,
    val alert_level: String,       // "ok", "warning", "critical"
    val will_run_low: Boolean,
    val days_until_low: Int?,
    val predictions: List<Int>,    // 7-day forecast
    val low_threshold: Int = 15,
    val note: String? = null
)

data class StockAlertsResponse(
    val total_alerts: Int,
    val critical: List<StockAlert>,
    val warning: List<StockAlert>
)

data class StockAlert(
    val pharmacy_id: Int,
    val pharmacy_name: String,
    val medicine_id: Int,
    val medicine_name: String,
    val current_stock: Int,
    val alert_level: String,
    val days_until_low: Int?,
    val predicted_stocks: List<Int>,
    val message: String
)

// Wraps the /pharmacies/nearby response which includes source info
data class NearbyPharmaciesResponse(
    val source: String,          // "google_places" or "database"
    val pharmacies: List<Pharmacy>
)

// ── RESERVATION HISTORY ───────────────────────────────────────────

data class ReservationItem(
    val reservation_id: String,
    val status: String,
    val medicine_name: String,
    val pharmacy_name: String,
    val quantity: Int,
    val total_price: Double,
    val created_at: String,
    val expires_at: String?,
    val prescription_required: Boolean
)

data class ReservationsListResponse(
    val user_id: Int,
    val reservations: List<ReservationItem>,
    val count: Int
)

// Owner pharmacy reservations
data class PharmacyReservation(
    val reservation_id: String,
    val medicine_name: String,
    val medicine_id: Int,
    val quantity: Int,
    val total_price: Double,
    val status: String,
    val patient_name: String,
    val patient_phone: String,
    val patient_id: Int,
    val created_at: String?,
    val expires_at: String?,
    val notes: String
)

data class PharmacyReservationsResponse(
    val reservations: List<PharmacyReservation>,
    val total: Int
)

// Profile update
data class UserUpdateRequest(
    val name: String?,
    val phone: String?
)

data class UserUpdateResponse(
    val success: Boolean,
    val name: String,
    val phone: String
)