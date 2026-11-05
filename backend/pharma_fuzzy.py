"""
fuzzy_search.py
FuzzyWuzzy-powered search helper.
Used by main.py to find medicines/pharmacies even with typos or partial names.

Examples of what now works:
  "paracetmol"   → Paracetamol
  "amoxcilin"    → Amoxicillin
  "metfromin"    → Metformin
  "apollo pharm" → Apollo Pharmacy Kottayam
"""

from fuzzywuzzy import fuzz, process


# ─────────────────────────────────────────────
# MEDICINE FUZZY SEARCH
# ─────────────────────────────────────────────

def fuzzy_find_medicine(query: str, all_medicines: list, threshold: int = 60):
    """
    Given a search query and a list of Medicine ORM objects,
    returns the best matching medicine or None.

    Searches across: brand_name, generic_name, manufacturer
    threshold: minimum score (0-100) to consider a match
    """
    if not query or not all_medicines:
        return None

    query = query.strip().lower()

    # Build candidate list: (medicine_obj, best_field_match_score)
    scored = []
    for med in all_medicines:
        scores = []

        if med.brand_name:
            scores.append(fuzz.token_set_ratio(query, med.brand_name.lower()))
        if med.generic_name:
            scores.append(fuzz.token_set_ratio(query, med.generic_name.lower()))
        if med.manufacturer:
            scores.append(fuzz.partial_ratio(query, med.manufacturer.lower()))

        best_score = max(scores) if scores else 0
        scored.append((med, best_score))

    # Sort by score descending
    scored.sort(key=lambda x: x[1], reverse=True)

    best_med, best_score = scored[0] if scored else (None, 0)

    if best_score >= threshold:
        return best_med
    return None


def fuzzy_find_medicines_multi(query: str, all_medicines: list,
                                threshold: int = 55, limit: int = 5):
    """
    Returns multiple medicine matches ranked by fuzzy score.
    Useful for search suggestions.
    """
    if not query or not all_medicines:
        return []

    query = query.strip().lower()
    scored = []

    for med in all_medicines:
        scores = []
        if med.brand_name:
            scores.append(fuzz.token_set_ratio(query, med.brand_name.lower()))
        if med.generic_name:
            scores.append(fuzz.token_set_ratio(query, med.generic_name.lower()))
        best = max(scores) if scores else 0
        if best >= threshold:
            scored.append((med, best))

    scored.sort(key=lambda x: x[1], reverse=True)
    return [med for med, score in scored[:limit]]


# ─────────────────────────────────────────────
# PHARMACY FUZZY SEARCH
# ─────────────────────────────────────────────

def fuzzy_find_pharmacies(query: str, all_pharmacies: list,
                           threshold: int = 55, limit: int = 10):
    """
    Given a search query and list of Pharmacy ORM objects,
    returns pharmacies matching by name or city, ranked by fuzzy score.
    """
    if not query or not all_pharmacies:
        return []

    query = query.strip().lower()
    scored = []

    for pharmacy in all_pharmacies:
        scores = []
        if pharmacy.name:
            scores.append(fuzz.token_set_ratio(query, pharmacy.name.lower()))
            scores.append(fuzz.partial_ratio(query, pharmacy.name.lower()))
        if pharmacy.city:
            scores.append(fuzz.token_set_ratio(query, pharmacy.city.lower()))

        best = max(scores) if scores else 0
        if best >= threshold:
            scored.append((pharmacy, best))

    scored.sort(key=lambda x: x[1], reverse=True)
    return [pharmacy for pharmacy, score in scored[:limit]]


# ─────────────────────────────────────────────
# QUICK TEST
# ─────────────────────────────────────────────

if __name__ == "__main__":
    # Simulate medicine objects
    class FakeMed:
        def __init__(self, brand, generic, manufacturer):
            self.brand_name = brand
            self.generic_name = generic
            self.manufacturer = manufacturer
            self.id = 1

    medicines = [
        FakeMed("Paracetamol", "Acetaminophen", "GSK"),
        FakeMed("Amoxicillin", "Amoxicillin Trihydrate", "Cipla"),
        FakeMed("Metformin", "Metformin HCl", "Sun Pharma"),
        FakeMed("Cetirizine", "Cetirizine HCl", "UCB"),
        FakeMed("Azithromycin", "Azithromycin", "Pfizer"),
    ]

    test_queries = [
        "paracetmol",    # typo
        "amoxcilin",     # typo
        "metfromin",     # typo
        "cough medicine",# conceptual
        "sun pharma",    # by manufacturer
        "acetamin",      # partial generic name
    ]

    print("=" * 50)
    print("FuzzyWuzzy Medicine Search Test")
    print("=" * 50)
    for q in test_queries:
        result = fuzzy_find_medicine(q, medicines)
        print(f"  '{q}' → {result.brand_name if result else 'No match'}")