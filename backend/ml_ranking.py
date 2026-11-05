"""
ml_ranking.py
─────────────
Intelligent pharmacy ranking engine for PharmaFind.

Ranks pharmacies using a weighted multi-factor score instead of
just sorting by distance. Each factor is normalised to 0.0–1.0
before weighting so they're comparable.

Scoring formula:
  score = (distance  × 0.35)   # closer is better
        + (stock     × 0.30)   # in-stock medicines matter most after distance
        + (rating    × 0.20)   # user ratings
        + (open      × 0.10)   # is it open right now
        + (price     × 0.05)   # cheaper price is a tiebreaker

Result: pharmacies ranked by who is genuinely most useful to THIS user
        right now — not just who is geographically nearest.

Examples of smart ranking in action:
  - Apollo Pharmacy: 0.3km, out of stock Metformin      → ranked 3rd
  - City Medical:    1.1km, has Metformin, 4.5★, cheap  → ranked 1st
  - Metro Medicals:  0.8km, has Metformin, 3.8★         → ranked 2nd
"""

import math
from typing import Optional


# ─────────────────────────────────────────────────────────
# MAIN RANKING FUNCTION
# ─────────────────────────────────────────────────────────

def rank_pharmacies(
    pharmacies: list[dict],
    medicine_name: Optional[str] = None,
    user_lat: float = 0.0,
    user_lng: float = 0.0,
    weights: Optional[dict] = None
) -> list[dict]:
    """
    Rank a list of pharmacy dicts by intelligent multi-factor score.

    Args:
        pharmacies:    List of pharmacy dicts from DB query
        medicine_name: If set, stock score is for this specific medicine
        user_lat/lng:  User's GPS coordinates (for distance scoring)
        weights:       Override default weights if needed

    Returns:
        Same list, sorted by descending rank_score, with rank_score added
    """

    if not pharmacies:
        return []

    # Default weights — tuned for pharmacy finder UX
    w = weights or {
        "distance": 0.35,
        "stock":    0.30,
        "rating":   0.20,
        "open":     0.10,
        "price":    0.05,
    }

    # ── Step 1: Extract raw values ──
    distances = [p.get("distance_km", 999) for p in pharmacies]
    ratings   = [p.get("rating", 0.0) or 0.0 for p in pharmacies]
    prices    = [p.get("price", 0.0) or 0.0 for p in pharmacies]
    stocks    = [p.get("stock", 0) or 0 for p in pharmacies]

    # ── Step 2: Normalise each factor to 0.0–1.0 ──
    dist_scores   = _normalise_inverse(distances)   # lower distance = higher score
    rating_scores = _normalise(ratings)             # higher rating = higher score
    price_scores  = _normalise_inverse(             # lower price = higher score
        prices, default_if_zero=True
    )
    stock_scores  = _normalise_stock(stocks)        # stock with diminishing returns

    # ── Step 3: Score each pharmacy ──
    for i, pharmacy in enumerate(pharmacies):
        is_open     = 1.0 if pharmacy.get("is_open", True) else 0.0
        dist_score  = dist_scores[i]
        stock_score = stock_scores[i]
        rat_score   = rating_scores[i]
        price_score = price_scores[i]

        # Weighted sum
        raw_score = (
            dist_score  * w["distance"] +
            stock_score * w["stock"]    +
            rat_score   * w["rating"]   +
            is_open     * w["open"]     +
            price_score * w["price"]
        )

        # Hard penalty: closed pharmacy drops to bottom regardless
        if not pharmacy.get("is_open", True):
            raw_score *= 0.4

        # Hard penalty: zero stock when medicine was searched
        if medicine_name and stocks[i] == 0:
            raw_score *= 0.1

        pharmacy["rank_score"]    = round(raw_score, 4)
        pharmacy["dist_score"]    = round(dist_score, 3)
        pharmacy["stock_score"]   = round(stock_score, 3)
        pharmacy["rating_score"]  = round(rat_score, 3)

        # Human-readable rank label
        pharmacy["rank_label"] = _rank_label(raw_score, pharmacy)

    # ── Step 4: Sort by score descending ──
    ranked = sorted(pharmacies, key=lambda x: x["rank_score"], reverse=True)

    # Add rank position (1-based)
    for pos, pharmacy in enumerate(ranked):
        pharmacy["rank_position"] = pos + 1

    return ranked


# ─────────────────────────────────────────────────────────
# MEDICINE-SPECIFIC RANKING
# Called from /medicines/search endpoint
# ─────────────────────────────────────────────────────────

def rank_pharmacies_for_medicine(
    pharmacy_results: list[dict],
    medicine_name: str
) -> list[dict]:
    """
    Rank pharmacies specifically for a medicine search result.
    Boosts weight of stock and price since user is trying to buy.
    """
    medicine_weights = {
        "distance": 0.30,   # slightly less weight — worth going further for stock
        "stock":    0.40,   # stock is most important when buying
        "rating":   0.15,
        "open":     0.10,
        "price":    0.05,
    }

    return rank_pharmacies(
        pharmacies=pharmacy_results,
        medicine_name=medicine_name,
        weights=medicine_weights
    )


# ─────────────────────────────────────────────────────────
# NORMALISATION HELPERS
# ─────────────────────────────────────────────────────────

def _normalise(values: list[float]) -> list[float]:
    """Normalise values to 0–1 range. Higher value → higher score."""
    if not values:
        return []
    min_v = min(values)
    max_v = max(values)
    if max_v == min_v:
        return [1.0] * len(values)
    return [(v - min_v) / (max_v - min_v) for v in values]


def _normalise_inverse(values: list[float],
                        default_if_zero: bool = False) -> list[float]:
    """Normalise values to 0–1 range. Lower value → higher score."""
    if not values:
        return []

    # Replace zeros if needed (e.g. price=0 means no price data)
    if default_if_zero:
        non_zero = [v for v in values if v > 0]
        avg = sum(non_zero) / len(non_zero) if non_zero else 1.0
        values = [v if v > 0 else avg for v in values]

    min_v = min(values)
    max_v = max(values)
    if max_v == min_v:
        return [1.0] * len(values)
    return [(max_v - v) / (max_v - min_v) for v in values]


def _normalise_stock(stocks: list[int]) -> list[float]:
    """
    Stock score with diminishing returns.
    0  units → 0.0
    10 units → 0.6   (big jump)
    50 units → 0.85  (good)
    100+     → 1.0   (max)
    Uses log scale so difference between 0 and 10 matters more
    than difference between 90 and 100.
    """
    scores = []
    for s in stocks:
        if s <= 0:
            scores.append(0.0)
        else:
            # log(1 + stock) / log(1 + 100) normalised to ~0–1
            score = math.log(1 + s) / math.log(1 + 100)
            scores.append(min(score, 1.0))
    return scores


def _rank_label(score: float, pharmacy: dict) -> str:
    """Human-readable label shown on pharmacy card badge."""
    if not pharmacy.get("is_open", True):
        return "Closed"
    if pharmacy.get("stock", 0) == 0 and pharmacy.get("price") is not None:
        return "Out of stock"
    if score >= 0.80:
        return "Best match"
    if score >= 0.65:
        return "Good match"
    if score >= 0.50:
        return "Nearby"
    return ""


# ─────────────────────────────────────────────────────────
# QUICK TEST
# ─────────────────────────────────────────────────────────

if __name__ == "__main__":
    test_pharmacies = [
        {"id": 1, "name": "Apollo Pharmacy",  "distance_km": 0.3,
         "stock": 0,   "rating": 4.5, "is_open": True,  "price": 45.0},
        {"id": 2, "name": "City Medical",     "distance_km": 1.1,
         "stock": 85,  "rating": 4.5, "is_open": True,  "price": 38.0},
        {"id": 3, "name": "Metro Medicals",   "distance_km": 0.8,
         "stock": 40,  "rating": 3.8, "is_open": True,  "price": 42.0},
        {"id": 4, "name": "Nirmala Medicals", "distance_km": 2.1,
         "stock": 120, "rating": 4.8, "is_open": False, "price": 35.0},
        {"id": 5, "name": "Star Pharmacy",    "distance_km": 0.5,
         "stock": 15,  "rating": 3.2, "is_open": True,  "price": 50.0},
    ]

    print("="*60)
    print("Intelligent Pharmacy Ranking — Test")
    print("="*60)
    print("\nSearching for: Metformin\n")

    ranked = rank_pharmacies_for_medicine(test_pharmacies, "Metformin")

    for p in ranked:
        print(f"  #{p['rank_position']} {p['name']}")
        print(f"      Score: {p['rank_score']} | {p['rank_label']}")
        print(f"      Distance: {p['distance_km']}km | "
              f"Stock: {p['stock']} | "
              f"Rating: {p['rating']}★ | "
              f"{'Open' if p['is_open'] else 'CLOSED'}")
        print()