"""
semantic_search.py
──────────────────
Semantic medicine search for PharmaFind.

HOW IT ACTUALLY WORKS (two modes):
─────────────────────────────────
MODE 1 — Fine-tuned model (recommended, run train_semantic_model.py first):
  The sentence-transformer is fine-tuned on your 11,000-medicine dataset using
  a contrastive learning objective. It learns that:
    "headache" ↔ "Treatment of headache pain fever" are SIMILAR
    "headache" ↔ "Treatment of colon cancer"        are DISSIMILAR
  After fine-tuning, the model understands medical language in the context
  of YOUR specific dataset far better than the generic pre-trained model.

MODE 2 — Pre-trained only (works without training, slightly less accurate):
  Uses all-MiniLM-L6-v2 directly to encode medicine texts.
  Still works well because the dataset's own Uses/Side_effects text is
  rich enough to produce good embeddings even without fine-tuning.

WHAT HAPPENS AT STARTUP:
  1. Load fine-tuned model if available, else load pre-trained
  2. For each medicine in DB: build embedding text from
     brand_name + generic_name + uses + side_effects + composition
  3. Encode all texts → 384-dim vectors, save to disk (embeddings.npy)
  4. On every search query: encode query → cosine similarity → top-K

INSTALLATION:
  pip install sentence-transformers torch numpy

TRAINING (one-time, ~10-30 min on CPU):
  python train_semantic_model.py

USAGE (automatic via main.py startup):
  from semantic_search import build_embeddings, semantic_search
  build_embeddings(all_medicines_from_db)
  results = semantic_search("headache fever", all_medicines_from_db)
"""

import os
import json
import pickle
import numpy as np
from pathlib import Path
from typing import List, Optional

MODEL_DIR       = Path(__file__).parent / "ml_models"
MODEL_DIR.mkdir(exist_ok=True)
FINETUNED_PATH  = MODEL_DIR / "semantic_finetuned"   # saved fine-tuned model
EMBEDDINGS_PATH = MODEL_DIR / "medicine_embeddings.npy"
NAMES_PATH      = MODEL_DIR / "medicine_names.json"

# ── Globals ───────────────────────────────────────────────────────
_model               = None
_medicine_embeddings = None   # numpy array (N, 384)
_medicine_names      = []     # list of brand_name strings matching rows
_medicine_objects    = {}     # brand_name → Medicine ORM object (for fast lookup)
_is_available        = False


# ─────────────────────────────────────────────────────────────────
# MODEL LOADING
# ─────────────────────────────────────────────────────────────────

def _load_model():
    """
    Load model in priority order:
    1. Fine-tuned model (train_semantic_model.py output)
    2. Pre-trained all-MiniLM-L6-v2

    Fine-tuned is preferred — it understands your specific medicine
    dataset's language and produces much better results for queries
    like symptom names, disease conditions, and drug categories.
    """
    global _model, _is_available
    if _model is not None:
        return _model

    try:
        from sentence_transformers import SentenceTransformer

        if FINETUNED_PATH.exists():
            print("[SemanticSearch] Loading fine-tuned model...")
            _model = SentenceTransformer(str(FINETUNED_PATH))
            print("[SemanticSearch] Fine-tuned model loaded ✅")
        else:
            print("[SemanticSearch] Fine-tuned model not found — using pre-trained.")
            print("[SemanticSearch] Run: python train_semantic_model.py")
            print("[SemanticSearch] Loading all-MiniLM-L6-v2...")
            _model = SentenceTransformer("all-MiniLM-L6-v2")
            print("[SemanticSearch] Pre-trained model loaded ✅")

        _is_available = True
        return _model

    except ImportError:
        print("[SemanticSearch] sentence-transformers not installed.")
        print("[SemanticSearch] Run: pip install sentence-transformers")
        _is_available = False
        return None
    except Exception as e:
        print(f"[SemanticSearch] Failed to load model: {e}")
        _is_available = False
        return None


# ─────────────────────────────────────────────────────────────────
# TEXT BUILDER — the richer the text, the better the embedding
# ─────────────────────────────────────────────────────────────────

def _build_medicine_text(medicine) -> str:
    """
    Build a rich document string for each medicine.

    This is what gets embedded — the quality of this text directly
    determines how well semantic search works.

    For dataset medicines, this combines:
      - Brand name            (e.g. "Augmentin 625 Duo Tablet")
      - Generic/composition   (e.g. "Amoxycillin Clavulanic Acid")
      - Uses                  (e.g. "Treatment of Bacterial infections")
      - Side effects          (e.g. "Vomiting Nausea Diarrhea")
      - Manufacturer

    The uses + side effects from your 11k dataset are the most
    important part — they let the model map:
      "diarrhoea treatment"  →  finds Augmentin (has "Diarrhea" in side effects)
      "bacterial infection"  →  finds Augmentin, Azithral (uses)
      "colon cancer"         →  finds Avastin (uses)
      "sneezing runny nose"  →  finds Allegra, Avil (uses)
    """
    parts = []

    # 1. Brand name — most important for exact match boosting
    brand = getattr(medicine, 'brand_name', '') or ''
    if brand:
        parts.append(brand)

    # 2. Generic name / composition
    generic = getattr(medicine, 'generic_name', '') or ''
    if generic:
        parts.append(generic)

    # 3. Uses — the core semantic signal from your dataset
    #    Stored pipe-separated: "Cancer of colon | Non-small cell lung cancer | ..."
    uses = getattr(medicine, 'uses', '') or getattr(medicine, 'description', '') or ''
    if uses:
        # Clean pipe separators → plain space-separated sentences
        uses_clean = uses.replace(' | ', '. ').replace('|', '. ').strip()
        parts.append(uses_clean[:600])   # cap at 600 chars

    # 4. Side effects — secondary signal (helps map symptom queries)
    side_effects = getattr(medicine, 'side_effects', '') or ''
    if side_effects:
        side_clean = side_effects.replace(' | ', ' ').replace('|', ' ').strip()
        parts.append(side_clean[:300])

    # 5. Manufacturer — helps "Pfizer antibiotic" type queries
    manufacturer = getattr(medicine, 'manufacturer', '') or ''
    if manufacturer:
        parts.append(manufacturer)

    # 6. Form — helps "tablet for fever", "syrup for cough" queries
    form = getattr(medicine, 'form', '') or ''
    if form:
        parts.append(form)

    return ' '.join(filter(None, parts))


# ─────────────────────────────────────────────────────────────────
# EMBEDDING BUILDER — run at startup or after dataset load
# ─────────────────────────────────────────────────────────────────

def build_embeddings(medicines: list, force_rebuild: bool = False):
    """
    Encode all medicines from the database into embedding vectors.

    Saves to disk so server restarts don't re-encode 11k medicines
    (encoding 11k docs takes ~30-60s on CPU).

    Args:
        medicines:      List of Medicine ORM objects from DB
        force_rebuild:  If True, ignore cached embeddings and rebuild

    Called automatically by main.py lifespan on startup.
    Call manually after loading new dataset:
        python -c "from database import SessionLocal; from models import Medicine;
                   from semantic_search import build_embeddings;
                   db=SessionLocal(); build_embeddings(db.query(Medicine).all())"
    """
    global _medicine_embeddings, _medicine_names, _medicine_objects, _is_available

    model = _load_model()
    if not model or not medicines:
        return

    # Load cached embeddings if available and not forced rebuild
    if not force_rebuild and EMBEDDINGS_PATH.exists() and NAMES_PATH.exists():
        try:
            cached_names = json.loads(NAMES_PATH.read_text())
            # Rebuild if medicine count changed significantly (>5% difference)
            if abs(len(cached_names) - len(medicines)) < len(medicines) * 0.05:
                print(f"[SemanticSearch] Loading cached embeddings "
                      f"({len(cached_names)} medicines)...")
                _medicine_embeddings = np.load(str(EMBEDDINGS_PATH))
                _medicine_names      = cached_names
                _medicine_objects    = {m.brand_name: m for m in medicines}
                _is_available        = True
                print(f"[SemanticSearch] Cached embeddings loaded ✅")
                return
        except Exception as e:
            print(f"[SemanticSearch] Cache load failed ({e}) — rebuilding...")

    print(f"[SemanticSearch] Building embeddings for {len(medicines):,} medicines...")
    print(f"[SemanticSearch] This takes ~30-60s for 11k medicines on CPU...")

    texts      = []
    names      = []
    obj_map    = {}

    for med in medicines:
        if not med.brand_name:
            continue
        text = _build_medicine_text(med)
        texts.append(text)
        names.append(med.brand_name)
        obj_map[med.brand_name] = med

    # Encode in batches of 256 — optimal for CPU memory
    batch_size = 256
    all_embeddings = []

    for i in range(0, len(texts), batch_size):
        batch = texts[i:i + batch_size]
        embs  = model.encode(
            batch,
            batch_size       = 64,
            show_progress_bar= False,
            convert_to_numpy = True,
            normalize_embeddings = True   # L2-normalise → cosine = dot product
        )
        all_embeddings.append(embs)
        if (i // batch_size) % 5 == 0:
            print(f"[SemanticSearch]   {min(i + batch_size, len(texts)):,} / {len(texts):,}")

    embeddings_matrix = np.vstack(all_embeddings).astype(np.float32)

    # Save to disk
    np.save(str(EMBEDDINGS_PATH), embeddings_matrix)
    NAMES_PATH.write_text(json.dumps(names))

    _medicine_embeddings = embeddings_matrix
    _medicine_names      = names
    _medicine_objects    = obj_map
    _is_available        = True

    print(f"[SemanticSearch] ✅ Embeddings built and cached")
    print(f"[SemanticSearch]    {len(names):,} medicines × 384 dimensions")
    print(f"[SemanticSearch]    Saved to {EMBEDDINGS_PATH}")


# ─────────────────────────────────────────────────────────────────
# COSINE SIMILARITY — vectorised for 11k medicines
# ─────────────────────────────────────────────────────────────────

def _cosine_sim_batch(query_vec: np.ndarray,
                      matrix: np.ndarray) -> np.ndarray:
    """
    Vectorised cosine similarity: query_vec (384,) vs matrix (N, 384).
    Since embeddings are L2-normalised, cosine = dot product.
    This computes similarity against ALL 11k medicines in one numpy op.
    Much faster than looping.
    """
    # query_vec already normalised in encode() call
    q = query_vec / (np.linalg.norm(query_vec) + 1e-10)
    return matrix @ q   # shape (N,) — one score per medicine


# ─────────────────────────────────────────────────────────────────
# MAIN SEARCH FUNCTION
# ─────────────────────────────────────────────────────────────────

def semantic_search(
    query:     str,
    medicines: list,
    top_k:     int   = 5,
    threshold: float = 0.25
) -> List[dict]:
    """
    Find medicines semantically similar to the query.

    Three-layer pipeline:
      Layer 1 — Exact/partial name match  (score 1.0)
      Layer 2 — Vectorised cosine similarity across all embeddings
                (uses the fine-tuned model if available)
      Layer 3 — Fallback symptom map for very common queries

    Args:
        query:      User's free-text query (symptom, condition, drug name)
        medicines:  List of Medicine ORM objects from DB
        top_k:      Max results to return
        threshold:  Min cosine similarity to include (0-1)

    Returns:
        List of dicts: {medicine, score, match_type}
    """
    if not query or not medicines:
        return []

    query_clean = query.strip().lower()
    results     = []
    seen_ids    = set()

    # ── Layer 1: Exact / partial name match ─────────────────────
    # Always highest priority — if user typed the brand name,
    # return it immediately regardless of embedding similarity
    for med in medicines:
        brand   = (med.brand_name   or '').lower()
        generic = (med.generic_name or '').lower()
        if query_clean in brand or query_clean in generic:
            if med.id not in seen_ids:
                results.append({
                    'medicine':   med,
                    'score':      1.0,
                    'match_type': 'exact'
                })
                seen_ids.add(med.id)
        if len(results) >= top_k:
            break

    # ── Layer 2: Semantic embedding similarity ───────────────────
    # This is the core: encode the query, compare against all
    # pre-computed medicine embeddings using vectorised dot product
    if _is_available and _medicine_embeddings is not None:
        model = _load_model()
        if model:
            # Encode query — L2-normalised for fast dot-product cosine
            query_vec = model.encode(
                [query],
                convert_to_numpy     = True,
                normalize_embeddings = True,
                show_progress_bar    = False
            )[0]

            # Vectorised similarity against ALL medicines at once
            scores = _cosine_sim_batch(query_vec, _medicine_embeddings)

            # Get top-K indices above threshold
            candidate_idx = np.where(scores >= threshold)[0]
            top_idx = candidate_idx[np.argsort(-scores[candidate_idx])]

            for idx in top_idx:
                if len(results) >= top_k * 2:   # collect extra, filter after
                    break
                med_name = _medicine_names[idx]
                med_obj  = _medicine_objects.get(med_name)
                if med_obj is None or med_obj.id in seen_ids:
                    continue
                results.append({
                    'medicine':   med_obj,
                    'score':      float(scores[idx]),
                    'match_type': 'semantic'
                })
                seen_ids.add(med_obj.id)

    # ── Layer 3: Fallback symptom keywords ──────────────────────
    # For very common queries if embedding didn't catch them
    # (only kicks in if we have fewer than 2 results so far)
    if len(results) < 2:
        QUICK_MAP = {
            'headache':         ['paracetamol', 'ibuprofen', 'aspirin'],
            'fever':            ['paracetamol', 'ibuprofen'],
            'cough':            ['ascoril', 'salbutamol', 'ambroxol'],
            'allergy':          ['cetirizine', 'allegra', 'fexofenadine'],
            'stomach':          ['omeprazole', 'pantoprazole', 'domperidone'],
            'diabetes':         ['metformin', 'glimepiride', 'insulin'],
            'blood pressure':   ['amlodipine', 'atenolol', 'losartan'],
            'cholesterol':      ['atorvastatin'],
            'antibiotic':       ['amoxicillin', 'azithromycin', 'augmentin'],
            'pain':             ['paracetamol', 'ibuprofen', 'diclofenac'],
            'acid':             ['omeprazole', 'pantoprazole', 'aciloc'],
            'cancer':           ['avastin'],
            'infection':        ['augmentin', 'azithral', 'amoxicillin'],
            'sneezing':         ['allegra', 'cetirizine', 'avil'],
        }
        for keyword, names in QUICK_MAP.items():
            if keyword in query_clean:
                for name in names:
                    for med in medicines:
                        if (med.brand_name or '').lower().startswith(name) and \
                                med.id not in seen_ids:
                            results.append({
                                'medicine':   med,
                                'score':      0.70,
                                'match_type': 'keyword_map'
                            })
                            seen_ids.add(med.id)

    results.sort(key=lambda x: x['score'], reverse=True)
    return results[:top_k]


def is_semantic_available() -> bool:
    return _is_available


def get_search_suggestions_semantic(
    query:    str,
    medicines: list,
    limit:    int = 5
) -> List[dict]:
    """Suggestions for the live dropdown as user types."""
    results = semantic_search(query, medicines, top_k=limit, threshold=0.20)
    return [{
        'id':           r['medicine'].id,
        'name':         r['medicine'].brand_name,
        'generic_name': r['medicine'].generic_name or '',
        'form':         getattr(r['medicine'], 'form', '') or '',
        'match_type':   r['match_type'],
        'score':        round(r['score'], 2),
    } for r in results]


def rebuild_embeddings_from_db():
    """
    Utility: rebuild embeddings from database.
    Call this after loading new medicines.
    """
    from database import SessionLocal
    from models import Medicine
    db = SessionLocal()
    try:
        medicines = db.query(Medicine).all()
        print(f"Rebuilding embeddings for {len(medicines):,} medicines...")
        build_embeddings(medicines, force_rebuild=True)
    finally:
        db.close()


if __name__ == '__main__':
    rebuild_embeddings_from_db()