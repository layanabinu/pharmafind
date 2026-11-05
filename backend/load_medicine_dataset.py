"""
load_medicine_dataset.py
─────────────────────────
Loads the medicine dataset CSV into PharmaFind database.

Expected CSV columns (exact names from your file):
  Medicine Name, Composition, Uses, Side_effects, Image URL,
  Manufacturer, Excellent Review %, Average Review %, Poor Review %

Usage:
  python load_medicine_dataset.py --csv Medicine_Details.csv
  python load_medicine_dataset.py --csv Medicine_Details.csv --merge
  python load_medicine_dataset.py --csv Medicine_Details.csv --rebuild-embeddings
"""

import argparse
import csv
import re
import sys
from pathlib import Path


# ─────────────────────────────────────────────────────────────────
# HELPERS
# ─────────────────────────────────────────────────────────────────

def clean(text) -> str:
    """Strip whitespace, return empty string for None/NaN."""
    if text is None:
        return ""
    s = str(text).strip()
    return "" if s.lower() in ("nan", "none", "n/a", "-") else s


def safe_int(text) -> int:
    try:
        return int(float(clean(text)))
    except (ValueError, TypeError):
        return 0


def parse_generic(composition: str) -> str:
    """'Amoxycillin (500mg) + Clavulanic Acid (125mg)' → 'Amoxycillin + Clavulanic Acid'"""
    if not composition:
        return ""
    # Remove bracketed strengths
    result = re.sub(r'\([^)]*\)', '', composition).strip(" +,").strip()
    return result[:200] if result else composition[:200]


def parse_strength(composition: str) -> str:
    """Extract first strength like '500mg', '400mg/5ml'."""
    if not composition:
        return ""
    m = re.search(r'[\d.]+\s*(?:mg|mcg|g|ml|%|IU)(?:/\s*[\d.]*\s*(?:mg|ml))?',
                  composition, re.IGNORECASE)
    return m.group(0).strip() if m else ""


def infer_form(name: str, composition: str) -> str:
    text = (name + " " + composition).lower()
    if any(w in text for w in ["syrup", "suspension", "solution", "drops", "elixir"]):
        return "Syrup"
    if any(w in text for w in ["injection", "infusion", "vial", " iv", "i.v"]):
        return "Injection"
    if any(w in text for w in ["capsule", " cap "]):
        return "Capsule"
    if any(w in text for w in ["cream", "ointment", "gel", "lotion", "topical"]):
        return "Topical"
    if any(w in text for w in ["inhaler", "rotacap", "nasal spray"]):
        return "Inhaler"
    if any(w in text for w in ["sachet", "powder", "granule"]):
        return "Sachet"
    if any(w in text for w in ["patch", "transdermal"]):
        return "Patch"
    if any(w in text for w in ["eye drop", "ear drop", "ophthalmic"]):
        return "Drops"
    return "Tablet"


def is_prescription(name: str, uses: str, composition: str) -> bool:
    text = (name + " " + uses + " " + composition).lower()
    rx = ["cancer", "chemotherapy", "antibiotic", "injection",
          "insulin", "warfarin", "methotrexate", "immunosuppressant",
          "antifungal", "antiviral", "antiretroviral", "corticosteroid",
          "antipsychotic", "antidepressant", "seizure", "epilepsy",
          "narcotic", "opioid", "morphine", "lithium"]
    return any(k in text for k in rx)


def pipe_list(raw: str) -> str:
    """'Cancer\nKidney cancer\nBrain tumor' → 'Cancer | Kidney cancer | Brain tumor'"""
    if not raw:
        return ""
    items = [i.strip() for i in re.split(r'[\n,]+', raw) if i.strip()]
    return " | ".join(items)[:1000]


# ─────────────────────────────────────────────────────────────────
# FIND COLUMNS — works with BOM, case variations, extra spaces
# ─────────────────────────────────────────────────────────────────

COLUMN_ALIASES = {
    "medicine_name": ["Medicine Name", "medicine name", "name", "Name",
                      "MEDICINE NAME", "MedicineName"],
    "composition":   ["Composition", "composition", "COMPOSITION",
                      "Generic Name", "generic_name"],
    "uses":          ["Uses", "uses", "USES", "Indications", "Use"],
    "side_effects":  ["Side_effects", "Side effects", "side_effects",
                      "SideEffects", "Adverse Effects", "SIDE_EFFECTS"],
    "image_url":     ["Image URL", "image_url", "Image", "image",
                      "IMAGE URL", "ImageURL"],
    "manufacturer":  ["Manufacturer", "manufacturer", "MANUFACTURER",
                      "Company", "company", "Brand"],
    "excellent":     ["Excellent Review %", "Excellent Review%",
                      "excellent_review_pct", "Excellent %", "Excellent"],
    "average":       ["Average Review %", "Average Review%",
                      "average_review_pct", "Average %", "Average"],
    "poor":          ["Poor Review %", "Poor Review%",
                      "poor_review_pct", "Poor %", "Poor"],
}


def find_column(headers_lower: dict, key: str):
    """
    headers_lower: dict of {lowercased_stripped_header: original_header}
    Returns original header string if found, else None.
    Strips BOM (\ufeff) automatically.
    """
    for alias in COLUMN_ALIASES[key]:
        normalized = alias.strip().lower().lstrip('\ufeff')
        if normalized in headers_lower:
            return headers_lower[normalized]
    return None


def load_csv(csv_path: str, merge: bool = False,
             rebuild_embeddings: bool = False):

    from database import SessionLocal, engine, Base
    from models import Medicine

    Base.metadata.create_all(bind=engine)
    db = SessionLocal()

    try:
        print(f"\n{'='*60}")
        print(f"  PharmaFind Medicine Dataset Loader")
        print(f"{'='*60}")
        print(f"  CSV:  {csv_path}")
        print(f"  Mode: {'merge (keep existing)' if merge else 'replace all'}")
        print()

        # ── Read CSV with DictReader — handles BOM via utf-8-sig ──
        with open(csv_path, encoding="utf-8-sig", errors="replace") as f:
            reader = csv.DictReader(f)
            raw_headers = reader.fieldnames or []
            rows = list(reader)

        print(f"  Raw headers detected: {raw_headers}")

        # Build lowercase lookup (strips BOM and spaces)
        headers_lower = {
            h.strip().lower().lstrip('\ufeff'): h
            for h in raw_headers
        }

        # Find each column
        col = {key: find_column(headers_lower, key) for key in COLUMN_ALIASES}

        print(f"  Column mapping:")
        for k, v in col.items():
            status = f"✅ '{v}'" if v else "❌ NOT FOUND"
            print(f"    {k:20s} → {status}")
        print()

        # medicine_name is the only required column
        if col["medicine_name"] is None:
            print("ERROR: Cannot find the medicine name column.")
            print("Your CSV headers are:")
            for h in raw_headers:
                print(f"  '{h}'  (repr: {repr(h)})")
            sys.exit(1)

        print(f"  Detected {len(rows):,} data rows")
        print()

        # ── Clear or check existing ────────────────────────────────
        if not merge:
            existing = db.query(Medicine).count()
            print(f"  Removing {existing} existing medicines...")
            db.query(Medicine).delete()
            db.commit()

        existing_names = set()
        if merge:
            existing_names = {
                m.brand_name.lower()
                for m in db.query(Medicine.brand_name).all()
            }

        # ── Process rows ──────────────────────────────────────────
        added   = 0
        skipped = 0
        errors  = 0
        batch   = []
        BATCH   = 500

        def get(row, key) -> str:
            col_name = col.get(key)
            return clean(row.get(col_name, "")) if col_name else ""

        for i, row in enumerate(rows):
            try:
                name = get(row, "medicine_name")
                if not name:
                    skipped += 1
                    continue
                if merge and name.lower() in existing_names:
                    skipped += 1
                    continue

                composition  = get(row, "composition")
                uses_raw     = get(row, "uses")
                side_raw     = get(row, "side_effects")
                image_url    = get(row, "image_url")
                manufacturer = get(row, "manufacturer")
                excellent    = safe_int(get(row, "excellent"))
                average      = safe_int(get(row, "average"))
                poor         = safe_int(get(row, "poor"))

                generic  = parse_generic(composition)
                strength = parse_strength(composition)
                form     = infer_form(name, composition)
                rx       = is_prescription(name, uses_raw, composition)

                uses_clean  = pipe_list(uses_raw)
                sides_clean = pipe_list(side_raw)

                batch.append(Medicine(
                    brand_name               = name,
                    generic_name             = generic or name,
                    manufacturer             = manufacturer,
                    strength                 = strength,
                    form                     = form,
                    description              = uses_clean[:500],
                    uses                     = uses_clean,
                    side_effects             = sides_clean,
                    image_url                = image_url[:500],
                    excellent_review_pct     = excellent,
                    average_review_pct       = average,
                    poor_review_pct          = poor,
                    is_prescription_required = rx,
                ))
                added += 1

                if len(batch) >= BATCH:
                    db.bulk_save_objects(batch)
                    db.commit()
                    batch = []
                    print(f"  ... {added:,} loaded", end="\r")

            except Exception as e:
                errors += 1
                if errors <= 5:
                    print(f"\n  Row {i+2} error: {e} — row: {dict(list(row.items())[:3])}")

        if batch:
            db.bulk_save_objects(batch)
            db.commit()

        total = db.query(Medicine).count()

        print(f"\n{'='*60}")
        print(f"  ✅ Done!")
        print(f"     Added:   {added:,}")
        print(f"     Skipped: {skipped:,}")
        print(f"     Errors:  {errors}")
        print(f"     Total medicines in DB: {total:,}")
        print(f"{'='*60}\n")

        if rebuild_embeddings:
            print("Rebuilding semantic embeddings...")
            from semantic_search import build_embeddings
            medicines = db.query(Medicine).all()
            build_embeddings(medicines, force_rebuild=True)
            print("Embeddings rebuilt ✅")

        print("Next step — fine-tune semantic search on this data:")
        print("  python train_semantic_model.py")
        print()

    except Exception as e:
        db.rollback()
        print(f"\n❌ Fatal error: {e}")
        import traceback; traceback.print_exc()
        raise
    finally:
        db.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--csv",                required=True)
    parser.add_argument("--merge",              action="store_true")
    parser.add_argument("--rebuild-embeddings", action="store_true")
    args = parser.parse_args()

    if not Path(args.csv).exists():
        print(f"❌ File not found: {args.csv}")
        print(f"   Make sure the CSV is in the same folder as this script,")
        print(f"   or give the full path: --csv C:\\Users\\...\\Medicine_Details.csv")
        sys.exit(1)

    load_csv(args.csv, merge=args.merge,
             rebuild_embeddings=args.rebuild_embeddings)