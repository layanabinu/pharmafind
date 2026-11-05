"""
directions_service.py
─────────────────────
Proxy for Google Directions API.

Why proxy through backend instead of calling from Android:
  - Keeps your API key off the device (can't be extracted from APK)
  - Lets us cache popular routes
  - Returns only what Android needs (the encoded polyline points)

Setup:
  1. Enable "Directions API" in Google Cloud Console
     (same project as your Maps/Places key)
  2. Same GOOGLE_PLACES_KEY works — it's the same key

Returns encoded polyline that Android decodes natively using
PolyUtil from Google Maps Android Utils library.
"""

import os
import requests
from typing import Optional

DIRECTIONS_URL = "https://maps.googleapis.com/maps/api/directions/json"
GOOGLE_KEY     = os.getenv("GOOGLE_PLACES_KEY", "")

# Simple in-memory cache — routes rarely change
_route_cache: dict = {}


def get_route(
    origin_lat: float,
    origin_lng: float,
    dest_lat: float,
    dest_lng: float,
    mode: str = "driving"   # driving | walking | transit | bicycling
) -> Optional[dict]:
    """
    Get a route between two points.

    Returns:
    {
        "polyline":       "encoded_string...",   # draw on map
        "distance_text":  "2.3 km",
        "distance_meters": 2300,
        "duration_text":  "8 mins",
        "duration_seconds": 480,
        "steps": [
            {
                "instruction": "Turn left onto MG Road",
                "distance":    "200 m",
                "maneuver":    "turn-left"
            }, ...
        ],
        "start_address": "Kottayam, Kerala",
        "end_address":   "Metro Medicals, Baker Jn"
    }
    """
    if not GOOGLE_KEY:
        return None   # frontend will fall back to intent

    # Cache key rounded to ~10m precision
    cache_key = f"{round(origin_lat,4)},{round(origin_lng,4)}" \
                f"->{round(dest_lat,4)},{round(dest_lng,4)}:{mode}"

    if cache_key in _route_cache:
        return _route_cache[cache_key]

    params = {
        "origin":      f"{origin_lat},{origin_lng}",
        "destination": f"{dest_lat},{dest_lng}",
        "mode":        mode,
        "key":         GOOGLE_KEY,
        "language":    "en",
        "units":       "metric",
    }

    try:
        resp = requests.get(DIRECTIONS_URL, params=params, timeout=10)
        resp.raise_for_status()
        data = resp.json()

        status = data.get("status")
        if status != "OK":
            print(f"[Directions] API status: {status}")
            return None

        route = data["routes"][0]
        leg   = route["legs"][0]

        # Extract all step instructions
        steps = []
        for step in leg.get("steps", []):
            # Strip HTML tags from instruction
            import re
            instruction = re.sub(r"<[^>]+>", "", step.get("html_instructions", ""))
            steps.append({
                "instruction": instruction,
                "distance":    step["distance"]["text"],
                "maneuver":    step.get("maneuver", ""),
            })

        result = {
            "polyline":        route["overview_polyline"]["points"],
            "distance_text":   leg["distance"]["text"],
            "distance_meters": leg["distance"]["value"],
            "duration_text":   leg["duration"]["text"],
            "duration_seconds": leg["duration"]["value"],
            "steps":           steps,
            "start_address":   leg.get("start_address", ""),
            "end_address":     leg.get("end_address", ""),
        }

        _route_cache[cache_key] = result
        return result

    except Exception as e:
        print(f"[Directions] Error: {e}")
        return None