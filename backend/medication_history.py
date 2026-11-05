"""
medication_history.py
─────────────────────
Medication history tracking + ML pattern analysis for PharmaFind.

Features:
  1. Track every time user adds/removes/refills a medicine
  2. Detect refill patterns — predict next refill date
  3. Detect potential drug interactions (simple rule-based)
  4. Generate health timeline for the user
  5. Flag medicines not refilled on time (adherence tracking)

No heavy ML needed here — smart pattern matching + date math
is enough to produce genuinely useful insights.
"""

from datetime import datetime, timedelta
from typing import Optional
import json


# ─────────────────────────────────────────────────────────
# REFILL PREDICTION
# Looks at refill history and predicts next refill date
# ─────────────────────────────────────────────────────────

def predict_refill_date(history_entries: list[dict]) -> Optional[dict]:
    """
    Given a list of history events for one medicine, predict
    when the user will need to refill next.

    history_entries: list of dicts with keys:
      event_type: "started" | "refilled" | "stopped" | "updated"
      tablets_remaining: int
      event_date: ISO date string

    Returns:
      {
        "predicted_refill_date": "2024-08-15",
        "days_until_refill": 12,
        "confidence": "high" | "medium" | "low",
        "message": "Based on your usage, you'll need a refill in ~12 days"
      }
    """
    if not history_entries:
        return None

    # Get most recent stock level
    sorted_entries = sorted(history_entries,
                            key=lambda x: x.get("event_date", ""), reverse=True)
    latest = sorted_entries[0]
    current_tablets = latest.get("tablets_remaining", 0)

    if current_tablets <= 0:
        return {
            "predicted_refill_date": datetime.now().strftime("%Y-%m-%d"),
            "days_until_refill": 0,
            "confidence": "high",
            "message": "⚠️ You're out of tablets — refill now!"
        }

    # Calculate average daily consumption from refill history
    refill_events = [e for e in history_entries
                     if e.get("event_type") in ("refilled", "updated")]

    avg_daily = _estimate_daily_consumption(history_entries)

    if avg_daily <= 0:
        # Fallback: estimate from frequency string
        freq = latest.get("frequency", "once daily")
        avg_daily = _frequency_to_daily(freq)

    if avg_daily <= 0:
        return None

    days_left = int(current_tablets / avg_daily)
    # Suggest refill when 5 days remain (buffer)
    days_until_refill = max(0, days_left - 5)
    refill_date = datetime.now() + timedelta(days=days_until_refill)

    confidence = "high" if len(refill_events) >= 3 else \
                 "medium" if len(refill_events) >= 1 else "low"

    return {
        "predicted_refill_date": refill_date.strftime("%Y-%m-%d"),
        "days_until_refill": days_until_refill,
        "days_of_supply_left": days_left,
        "avg_daily_consumption": round(avg_daily, 2),
        "confidence": confidence,
        "message": _refill_message(days_until_refill, current_tablets)
    }


def _estimate_daily_consumption(entries: list[dict]) -> float:
    """Estimate tablets/day from history gaps between refills."""
    sorted_e = sorted(entries, key=lambda x: x.get("event_date", ""))
    pairs = []

    for i in range(1, len(sorted_e)):
        prev = sorted_e[i-1]
        curr = sorted_e[i]
        if curr.get("event_type") in ("refilled", "updated"):
            try:
                d1 = datetime.fromisoformat(prev["event_date"])
                d2 = datetime.fromisoformat(curr["event_date"])
                days = (d2 - d1).days
                tablets_used = (prev.get("tablets_remaining", 0) -
                                curr.get("tablets_remaining", 0))
                if days > 0 and tablets_used > 0:
                    pairs.append(tablets_used / days)
            except (ValueError, KeyError):
                continue

    return sum(pairs) / len(pairs) if pairs else 0.0


def _frequency_to_daily(frequency: str) -> float:
    """Convert frequency string to tablets per day."""
    freq = frequency.lower()
    if "twice" in freq or "2 times" in freq or "bd" in freq:
        return 2.0
    if "three" in freq or "3 times" in freq or "tds" in freq:
        return 3.0
    if "four" in freq or "4 times" in freq or "qid" in freq:
        return 4.0
    if "every other" in freq or "alternate" in freq:
        return 0.5
    if "weekly" in freq:
        return 1/7
    return 1.0  # default: once daily


def _refill_message(days: int, tablets: int) -> str:
    if days == 0:
        return "⚠️ Refill now — running out!"
    if days <= 3:
        return f"🔴 Refill very soon — only {tablets} tablets left"
    if days <= 7:
        return f"🟡 Refill within a week — {tablets} tablets left"
    return f"✅ Refill in ~{days} days — {tablets} tablets remaining"


# ─────────────────────────────────────────────────────────
# DRUG INTERACTION CHECKER
# Simple rule-based — flags known dangerous combinations
# ─────────────────────────────────────────────────────────

# Known interaction pairs (generic names, lowercased)
# Format: (drug_a, drug_b): severity, message
KNOWN_INTERACTIONS = {
    ("warfarin", "aspirin"): (
        "high",
        "⚠️ Warfarin + Aspirin: Increased bleeding risk. Consult your doctor."
    ),
    ("metformin", "alcohol"): (
        "medium",
        "⚠️ Metformin + Alcohol: Risk of lactic acidosis. Avoid alcohol."
    ),
    ("atorvastatin", "clarithromycin"): (
        "high",
        "⚠️ Statin + Clarithromycin: Muscle damage risk. Inform your doctor."
    ),
    ("amlodipine", "simvastatin"): (
        "medium",
        "⚠️ Amlodipine + Simvastatin: Risk of muscle problems at high doses."
    ),
    ("lisinopril", "potassium"): (
        "medium",
        "⚠️ ACE Inhibitor + Potassium: Risk of high potassium levels."
    ),
    ("ibuprofen", "aspirin"): (
        "medium",
        "⚠️ Ibuprofen + Aspirin: Increased GI bleeding risk. Don't combine."
    ),
    ("metronidazole", "alcohol"): (
        "high",
        "🚨 Metronidazole + Alcohol: Severe reaction — nausea, vomiting. Avoid alcohol completely."
    ),
    ("ssri", "tramadol"): (
        "high",
        "🚨 SSRI + Tramadol: Risk of serotonin syndrome. Consult doctor immediately."
    ),
    ("ciprofloxacin", "antacid"): (
        "medium",
        "⚠️ Ciprofloxacin + Antacids: Antacids reduce antibiotic absorption. Take 2hr apart."
    ),
}


def check_interactions(medicine_names: list[str]) -> list[dict]:
    """
    Check a list of medicine names for known interactions.
    Returns list of interaction warnings (empty if none found).
    """
    warnings = []
    names_lower = [n.lower() for n in medicine_names]

    for (drug_a, drug_b), (severity, message) in KNOWN_INTERACTIONS.items():
        a_found = any(drug_a in n for n in names_lower)
        b_found = any(drug_b in n for n in names_lower)

        if a_found and b_found:
            warnings.append({
                "drug_a":   drug_a.title(),
                "drug_b":   drug_b.title(),
                "severity": severity,
                "message":  message
            })

    return sorted(warnings, key=lambda x: 0 if x["severity"] == "high" else 1)


# ─────────────────────────────────────────────────────────
# ADHERENCE TRACKING
# Checks if user is taking medicines consistently
# ─────────────────────────────────────────────────────────

def calculate_adherence(history_entries: list[dict],
                         expected_daily: float = 1.0) -> dict:
    """
    Calculate medication adherence score (0–100%).
    Based on whether refills happen on schedule.
    """
    if not history_entries or len(history_entries) < 2:
        return {"score": None, "label": "Insufficient data", "message": ""}

    sorted_e = sorted(history_entries, key=lambda x: x.get("event_date", ""))

    # Days tracked
    try:
        first = datetime.fromisoformat(sorted_e[0]["event_date"])
        last  = datetime.fromisoformat(sorted_e[-1]["event_date"])
        days_tracked = max(1, (last - first).days)
    except (ValueError, KeyError):
        return {"score": None, "label": "Error", "message": ""}

    # Total tablets consumed (estimated)
    total_consumed = 0
    for i in range(1, len(sorted_e)):
        prev_tablets = sorted_e[i-1].get("tablets_remaining", 0)
        curr_tablets = sorted_e[i].get("tablets_remaining", 0)
        diff = prev_tablets - curr_tablets
        if diff > 0:
            total_consumed += diff

    expected_total = days_tracked * expected_daily
    if expected_total <= 0:
        return {"score": None, "label": "Error", "message": ""}

    score = min(100, round((total_consumed / expected_total) * 100))

    if score >= 90:
        label   = "Excellent"
        message = "✅ Great job! You're taking your medicine consistently."
        color   = "green"
    elif score >= 75:
        label   = "Good"
        message = "👍 You're mostly on track. Try not to miss doses."
        color   = "blue"
    elif score >= 50:
        label   = "Fair"
        message = "⚠️ You've missed several doses. Set a reminder!"
        color   = "orange"
    else:
        label   = "Poor"
        message = "🔴 Significant doses missed. Talk to your doctor."
        color   = "red"

    return {
        "score":         score,
        "label":         label,
        "message":       message,
        "color":         color,
        "days_tracked":  days_tracked,
        "doses_taken":   int(total_consumed),
        "doses_expected": int(expected_total)
    }