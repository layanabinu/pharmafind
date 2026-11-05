"""
collaborative_filter.py
────────────────────────
Collaborative Filtering for PharmaFind.

"Users like you also take..." recommendations.

HOW IT WORKS:
─────────────
1. Build a user-medicine matrix from user_medicines table
   Each row = a user, each column = a medicine (1 if taking, 0 if not)

2. Find similar users using Cosine Similarity on their medicine vectors
   "Similar" = users who take many of the same medicines

3. Recommend medicines that similar users take but current user doesn't

4. Boost recommendations if many similar users take the same medicine
   (collaborative signal) — classic user-based CF

5. Also apply content-based boost: if user takes Metformin (diabetes),
   boost other diabetes medicines like Atorvastatin (cholesterol — common
   comorbidity)

EXAMPLE:
────────
User A takes: Metformin, Atorvastatin, Amlodipine
User B takes: Metformin, Atorvastatin, Omeprazole
User C takes: Metformin, Amlodipine

Query: "What should User C also consider?"
→ User C is most similar to User A (2 shared: Metformin, Amlodipine)
→ User A also takes Atorvastatin → recommend Atorvastatin to User C
→ Label: "Users with similar medicines also take Atorvastatin"

COMORBIDITY RULES (domain knowledge boost):
────────────────────────────────────────────
Diabetes (Metformin)    → often with Atorvastatin, Amlodipine, Aspirin
Hypertension (Amlodipine)→ often with Atorvastatin, Aspirin
Asthma (Salbutamol)     → often with Cetirizine
Allergy (Cetirizine)    → often with Loratadine
Acid reflux (Omeprazole)→ often with Domperidone
"""

import math
from typing import List, Dict, Optional
from collections import defaultdict

# ── Comorbidity rules (domain knowledge layer) ────────────────────
# medicine (lowercase) : list of commonly co-prescribed medicines
COMORBIDITY_RULES = {
    "metformin":    ["atorvastatin", "amlodipine", "aspirin", "vitamin d"],
    "amlodipine":   ["atorvastatin", "aspirin", "metformin"],
    "atenolol":     ["atorvastatin", "aspirin", "amlodipine"],
    "atorvastatin": ["aspirin", "amlodipine", "metformin"],
    "aspirin":      ["atorvastatin", "omeprazole"],       # aspirin often with PPI
    "omeprazole":   ["domperidone", "pantoprazole"],
    "pantoprazole": ["domperidone", "omeprazole"],
    "salbutamol":   ["cetirizine"],
    "cetirizine":   ["loratadine"],
    "amoxicillin":  ["omeprazole"],                       # antibiotic often with PPI
    "azithromycin": ["omeprazole"],
    "metronidazole":["omeprazole", "domperidone"],
    "warfarin":     ["omeprazole"],                       # anticoag with PPI
    "ibuprofen":    ["omeprazole"],                       # NSAID with PPI
    "diclofenac":   ["omeprazole"],
}

# ── Cosine similarity between two binary vectors ──────────────────

def _cosine(vec_a: Dict[int, float], vec_b: Dict[int, float]) -> float:
    """Sparse cosine similarity between two medicine-id:weight dicts."""
    shared_ids = set(vec_a.keys()) & set(vec_b.keys())
    if not shared_ids:
        return 0.0
    dot = sum(vec_a[i] * vec_b[i] for i in shared_ids)
    norm_a = math.sqrt(sum(v*v for v in vec_a.values()))
    norm_b = math.sqrt(sum(v*v for v in vec_b.values()))
    if norm_a == 0 or norm_b == 0:
        return 0.0
    return dot / (norm_a * norm_b)

# ── Build user-medicine vectors from DB records ───────────────────

def _build_vectors(all_user_medicines: list) -> Dict[int, Dict[int, float]]:
    """
    all_user_medicines: list of UserMedicine ORM objects
    Returns: {user_id: {medicine_id: 1.0}}
    """
    vectors = defaultdict(dict)
    for um in all_user_medicines:
        # Weight by tablets_remaining as a proxy for how actively they take it
        weight = 1.0 if um.tablets_remaining is None else min(1.0, um.tablets_remaining / 30.0 + 0.5)
        vectors[um.user_id][um.medicine_id] = weight
    return dict(vectors)

# ── Main recommendation function ──────────────────────────────────

def get_recommendations(
    user_id: int,
    all_user_medicines: list,   # all UserMedicine rows from DB
    all_medicines: list,         # all Medicine rows from DB
    top_k: int = 5,
    min_similarity: float = 0.15
) -> List[dict]:
    """
    Get medicine recommendations for a user.

    Returns list of:
    {
        "medicine_id":   int,
        "medicine_name": str,
        "generic_name":  str,
        "reason":        str,   # shown to user
        "score":         float,
        "source":        "collaborative" | "comorbidity" | "popular"
    }
    """
    if not all_user_medicines or not all_medicines:
        return _fallback_popular(all_medicines, user_id, all_user_medicines, top_k)

    # Build a lookup: medicine_id → medicine object
    med_lookup = {m.id: m for m in all_medicines}
    med_name_lookup = {m.brand_name.lower(): m.id for m in all_medicines if m.brand_name}

    # Build user vectors
    vectors = _build_vectors(all_user_medicines)
    user_vec = vectors.get(user_id, {})

    if not user_vec:
        return _fallback_popular(all_medicines, user_id, all_user_medicines, top_k)

    # ── Step 1: Find similar users ────────────────────────────────
    similarities = []
    for other_id, other_vec in vectors.items():
        if other_id == user_id:
            continue
        sim = _cosine(user_vec, other_vec)
        if sim >= min_similarity:
            similarities.append((other_id, sim))

    similarities.sort(key=lambda x: x[1], reverse=True)
    top_similar = similarities[:10]   # top 10 most similar users

    # ── Step 2: Aggregate recommendations from similar users ──────
    recommendation_scores = defaultdict(float)
    recommendation_reasons = {}

    for similar_user_id, sim_score in top_similar:
        similar_vec = vectors[similar_user_id]
        for med_id, weight in similar_vec.items():
            if med_id not in user_vec:   # user doesn't already take this
                recommendation_scores[med_id] += sim_score * weight
                if med_id not in recommendation_reasons:
                    med_name = med_lookup.get(med_id, None)
                    recommendation_reasons[med_id] = {
                        "reason": "Users with similar medicines also take this",
                        "source": "collaborative"
                    }

    # ── Step 3: Comorbidity boost ─────────────────────────────────
    user_medicine_names = set()
    for med_id in user_vec:
        if med_id in med_lookup:
            user_medicine_names.add(med_lookup[med_id].brand_name.lower())

    for user_med_name in user_medicine_names:
        related = COMORBIDITY_RULES.get(user_med_name, [])
        for related_name in related:
            related_id = med_name_lookup.get(related_name)
            if related_id and related_id not in user_vec:
                # Significant boost for comorbidity rules
                recommendation_scores[related_id] += 0.6
                recommendation_reasons[related_id] = {
                    "reason": f"Often taken with {user_med_name.capitalize()}",
                    "source": "comorbidity"
                }

    # ── Step 4: Sort and build response ──────────────────────────
    if not recommendation_scores:
        return _fallback_popular(all_medicines, user_id, all_user_medicines, top_k)

    sorted_recs = sorted(recommendation_scores.items(), key=lambda x: x[1], reverse=True)

    results = []
    for med_id, score in sorted_recs[:top_k]:
        med = med_lookup.get(med_id)
        if not med:
            continue
        reason_info = recommendation_reasons.get(med_id, {})
        results.append({
            "medicine_id":   med.id,
            "medicine_name": med.brand_name,
            "generic_name":  med.generic_name or "",
            "strength":      getattr(med, "strength", "") or "",
            "medicine_type": getattr(med, "medicine_type", "") or "",
            "reason":        reason_info.get("reason", "Recommended for you"),
            "score":         round(min(score, 1.0), 3),
            "source":        reason_info.get("source", "collaborative"),
            "is_prescription_required": med.is_prescription_required,
        })

    return results

def _fallback_popular(
    all_medicines: list,
    user_id: int,
    all_user_medicines: list,
    top_k: int
) -> List[dict]:
    """
    When no similar users found — return most commonly taken medicines
    that this user doesn't already take.
    """
    # Count how many users take each medicine
    counts = defaultdict(int)
    for um in all_user_medicines:
        if um.user_id != user_id:
            counts[um.medicine_id] += 1

    # Get user's current medicines
    user_meds = {um.medicine_id for um in all_user_medicines if um.user_id == user_id}
    med_lookup = {m.id: m for m in all_medicines}

    # Popular medicines user doesn't take
    popular = [(mid, cnt) for mid, cnt in counts.items() if mid not in user_meds]
    popular.sort(key=lambda x: x[1], reverse=True)

    results = []
    for med_id, count in popular[:top_k]:
        med = med_lookup.get(med_id)
        if not med:
            continue
        results.append({
            "medicine_id":   med.id,
            "medicine_name": med.brand_name,
            "generic_name":  med.generic_name or "",
            "strength":      getattr(med, "strength", "") or "",
            "medicine_type": getattr(med, "medicine_type", "") or "",
            "reason":        f"Commonly used by {count} patients",
            "score":         round(count / max(counts.values(), default=1), 3),
            "source":        "popular",
            "is_prescription_required": med.is_prescription_required,
        })

    return results

def get_also_bought(
    medicine_id: int,
    all_user_medicines: list,
    all_medicines: list,
    top_k: int = 4
) -> List[dict]:
    """
    Item-based: "Patients who take THIS medicine also take..."
    Used on the medicine detail / bottom sheet screen.
    """
    med_lookup = {m.id: m for m in all_medicines}

    # Find users who take this medicine
    users_with_med = {um.user_id for um in all_user_medicines if um.medicine_id == medicine_id}
    if not users_with_med:
        return []

    # Count co-occurrences
    co_counts = defaultdict(int)
    for um in all_user_medicines:
        if um.user_id in users_with_med and um.medicine_id != medicine_id:
            co_counts[um.medicine_id] += 1

    total_users = len(users_with_med)
    sorted_co = sorted(co_counts.items(), key=lambda x: x[1], reverse=True)

    results = []
    for co_med_id, count in sorted_co[:top_k]:
        med = med_lookup.get(co_med_id)
        if not med:
            continue
        confidence = round(count / total_users, 2)
        results.append({
            "medicine_id":   med.id,
            "medicine_name": med.brand_name,
            "generic_name":  med.generic_name or "",
            "reason":        f"{count} patients also take this",
            "confidence":    confidence,
            "source":        "also_bought",
        })

    return result