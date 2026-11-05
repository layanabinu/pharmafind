"""
places_service.py
─────────────────
Fetches real pharmacies near a location using Google Places API.
Returns data in the SAME format as our database pharmacies so
the Android app gets consistent fields regardless of source.

Fixed issues:
  - Field names now match Pharmacy data class exactly
  - id field uses place_id hash (no int ID from Google)
  - distance_km always populated
  - is_open always populated (defaults True if unknown)
"""

import os
import math
import time
import hashlib
import requests
from typing import Optional

GOOGLE_PLACES_KEY  = os.getenv("GOOGLE_PLACES_KEY", "")
PLACES_NEARBY_URL  = "https://maps.googleapis.com/maps/api/place/nearbysearch/json"
PLACES_DETAIL_URL  = "https://maps.googleapis.com/maps/api/place/details/json"

# Simple in-memory cache — 5 minute TTL
_cache: dict = {}


def get_nearby_pharmacies(lat: float, lng: float,
                           radius_meters: int = 5000,
                           limit: int = 5) -> list[dict]:
    """
    Fetch real pharmacies from Google Places API.
    Returns list of dicts with IDENTICAL fields to our DB pharmacy format
    so the Android Pharmacy data class deserialises correctly.
    """
    if not GOOGLE_PLACES_KEY:
        raise ValueError("GOOGLE_PLACES_KEY not set")

    params = {
        "location": f"{lat},{lng}",
        "radius":   radius_meters,
        "type":     "pharmacy",
        "key":      GOOGLE_PLACES_KEY,
        "language": "en",
    }

    response = requests.get(PLACES_NEARBY_URL, params=params, timeout=10)
    response.raise_for_status()
    data = response.json()

    status = data.get("status")
    if status == "ZERO_RESULTS":
        return []
    if status not in ("OK",):
        raise Exception(f"Places API error: {status} — {data.get('error_message','')}")

    results = []
    for place in data.get("results", [])[:limit]:
        parsed = _parse_place(place, lat, lng)
        if parsed:
            results.append(parsed)

    results.sort(key=lambda x: x["distance_km"])
    return results


def _parse_place(place: dict, user_lat: float, user_lng: float) -> Optional[dict]:
    """
    Parse a Google Places result into a dict matching our Pharmacy format exactly.

    Android Pharmacy data class fields:
      id, name, place, lat, lng, distance_km,
      is_open, rating, phone, address,
      price, stock, rank_score, rank_label, rank_position
    """
    try:
        loc   = place["geometry"]["location"]
        p_lat = loc["lat"]
        p_lng = loc["lng"]

        # Haversine distance
        R     = 6371
        dlat  = math.radians(p_lat - user_lat)
        dlon  = math.radians(p_lng - user_lng)
        a     = (math.sin(dlat/2)**2 +
                 math.cos(math.radians(user_lat)) *
                 math.cos(math.radians(p_lat)) *
                 math.sin(dlon/2)**2)
        dist  = round(R * 2 * math.atan2(math.sqrt(a), math.sqrt(1-a)), 2)

        # Stable int-like ID from place_id hash (fits Android Int)
        place_id = place.get("place_id", "")
        stable_id = int(hashlib.md5(place_id.encode()).hexdigest()[:7], 16)

        # Extract city from vicinity: "Baker Junction, Kottayam" → "Kottayam"
        vicinity = place.get("vicinity", "")
        city     = vicinity.split(",")[-1].strip() if "," in vicinity else vicinity

        is_open = place.get("opening_hours", {}).get("open_now", True)

        return {
            # ── Fields matching Android Pharmacy data class exactly ──
            "id":           stable_id,
            "name":         place.get("name", "Unknown Pharmacy"),
            "place":        city,
            "address":      vicinity,
            "lat":          p_lat,
            "lng":          p_lng,
            "distance_km":  dist,
            "is_open":      is_open,
            "rating":       place.get("rating", 0.0),
            "phone":        "",      # needs a Detail call — skip for speed
            "price":        None,
            "stock":        None,
            "rank_score":   None,
            "rank_label":   None,
            "rank_position":None,
            # ── Extra metadata ──
            "place_id":     place_id,
            "source":       "google_places",
        }
    except (KeyError, TypeError, ValueError):
        return None


def get_nearby_pharmacies_cached(lat: float, lng: float,
                                  radius_meters: int = 5000,
                                  limit: int = 5) -> list[dict]:
    """Cached version — reuses result for 5 minutes within ~100m grid."""
    cache_key = f"{round(lat,3)}_{round(lng,3)}_{radius_meters}_{limit}"
    now = time.time()

    if cache_key in _cache:
        result, ts = _cache[cache_key]
        if now - ts < 300:   # 5 min TTL
            print(f"[Places] Cache hit for {cache_key}")
            return result

    result = get_nearby_pharmacies(lat, lng, radius_meters, limit)
    _cache[cache_key] = (result, now)
    return result


def get_pharmacy_phone(place_id: str) -> str:
    """Fetch phone number for a specific pharmacy by place_id."""
    if not GOOGLE_PLACES_KEY or not place_id:
        return ""
    try:
        params = {
            "place_id": place_id,
            "fields":   "formatted_phone_number",
            "key":      GOOGLE_PLACES_KEY,
        }
        r    = requests.get(PLACES_DETAIL_URL, params=params, timeout=5)
        data = r.json()
        return data.get("result", {}).get("formatted_phone_number", "")
    except Exception:
        return ""