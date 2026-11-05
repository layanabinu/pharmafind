"""
train_semantic_model.py
────────────────────────
Fine-tunes all-MiniLM-L6-v2 on your 11,000-medicine dataset.

WHAT FINE-TUNING DOES:
  The pre-trained model knows general English but has never seen medical
  language specific to Indian medicines. Fine-tuning teaches it that:

    - "diarrhoea"    is semantically close to "Diarrhea in side effects"
    - "BP tablet"    is semantically close to "Treatment of hypertension"
    - "sugar tablet" is semantically close to "Diabetes management"
    - "cancer"       is semantically close to "Avastin" uses text
    - "cough syrup"  is semantically close to "Ascoril LS Syrup" uses

TRAINING APPROACH — MultipleNegativesRankingLoss:
  We create training pairs (anchor, positive):
    anchor   = a symptom/condition/query phrase
    positive = the medicine's full text (name + uses + side_effects)

  Pairs are generated automatically from your dataset:
    Uses text is split into individual conditions → each becomes an anchor
    The full medicine document is the positive match
    All other medicines in the same batch = implicit negatives

  This is contrastive learning — it teaches the model to push
  similar pairs close together and dissimilar pairs apart.

TRAINING PAIRS GENERATED AUTOMATICALLY:
  From "Treatment of Bacterial infections | Treatment of ear infection"
  → anchor: "bacterial infections treatment"   positive: full doc
  → anchor: "ear infection"                    positive: full doc
  → anchor: "augmentin antibiotic"             positive: full doc

  From "Cancer of colon and rectum | Kidney cancer | Brain tumor"
  → anchor: "colon cancer treatment"           positive: full doc
  → anchor: "kidney cancer"                    positive: full doc
  → anchor: "brain tumor medicine"             positive: full doc

USAGE:
  # Standard training (~10-30 min CPU, ~5 min GPU)
  python train_semantic_model.py

  # Custom epochs and batch size
  python train_semantic_model.py --epochs 5 --batch-size 32

  # Quick test run (1 epoch, small subset)
  python train_semantic_model.py --epochs 1 --max-pairs 2000

  After training, model saved to ml_models/semantic_finetuned/
  Then rebuild embeddings:
  python train_semantic_model.py --rebuild-embeddings-only

REQUIREMENTS:
  pip install sentence-transformers torch
"""

import argparse
import random
import re
from pathlib import Path

MODEL_DIR      = Path(__file__).parent / "ml_models"
MODEL_DIR.mkdir(exist_ok=True)
FINETUNED_PATH = MODEL_DIR / "semantic_finetuned"
BASE_MODEL     = "all-MiniLM-L6-v2"


# ─────────────────────────────────────────────────────────────────
# TRAINING PAIR GENERATION
# ─────────────────────────────────────────────────────────────────

def build_training_pairs(medicines: list) -> list:
    """
    Automatically generate (anchor, positive_doc) training pairs
    from the medicine dataset.

    anchor       = short symptom/query phrase (what the user might type)
    positive_doc = full medicine document (name + uses + side_effects)

    Multiple anchors per medicine → richer training signal.
    """
    def _build_medicine_text(medicine) -> str:
        """Build rich text for embedding — same logic as semantic_search.py."""
        parts = []
        brand = getattr(medicine, 'brand_name', '') or ''
        if brand:
            parts.append(brand)
        generic = getattr(medicine, 'generic_name', '') or ''
        if generic:
            parts.append(generic)
        uses = getattr(medicine, 'uses', '') or getattr(medicine, 'description', '') or ''
        if uses:
            parts.append(uses.replace(' | ', '. ').replace('|', '. ').strip()[:600])
        side_effects = getattr(medicine, 'side_effects', '') or ''
        if side_effects:
            parts.append(side_effects.replace(' | ', ' ').replace('|', ' ').strip()[:300])
        manufacturer = getattr(medicine, 'manufacturer', '') or ''
        if manufacturer:
            parts.append(manufacturer)
        form = getattr(medicine, 'form', '') or ''
        if form:
            parts.append(form)
        return ' '.join(filter(None, parts))


    pairs   = []
    skipped = 0

    for med in medicines:
        if not med.brand_name:
            skipped += 1
            continue

        # Full document — this is the "positive" for all anchors from this medicine
        full_doc = _build_medicine_text(med)
        if not full_doc.strip():
            skipped += 1
            continue

        brand   = med.brand_name
        generic = med.generic_name or ''

        # ── Anchor type 1: brand name variations ─────────────────
        pairs.append((brand, full_doc))
        # Short name (first 2 words)
        short = ' '.join(brand.split()[:2])
        if short != brand:
            pairs.append((short, full_doc))

        # ── Anchor type 2: generic/composition ───────────────────
        if generic:
            pairs.append((generic, full_doc))

        # ── Anchor type 3: individual use conditions ─────────────
        uses = getattr(med, 'uses', '') or getattr(med, 'description', '') or ''
        if uses:
            conditions = [u.strip() for u in re.split(r'[|\n,]', uses) if u.strip()]
            for cond in conditions[:6]:   # max 6 conditions per medicine
                # Clean "Treatment of " prefix for more natural queries
                query = re.sub(r'^Treatment of\s+', '', cond, flags=re.IGNORECASE).strip()
                query = re.sub(r'^Treatment\s+', '', query, flags=re.IGNORECASE).strip()
                if len(query) > 4:
                    pairs.append((query.lower(), full_doc))

        # ── Anchor type 4: side effects as negative symptoms ──────
        side_effects = getattr(med, 'side_effects', '') or ''
        if side_effects:
            sides = [s.strip() for s in re.split(r'[|\n,]', side_effects) if s.strip()]
            # Only add a few side effects as queries
            for side in sides[:2]:
                if len(side) > 3:
                    pairs.append((f"medicine that causes {side.lower()}", full_doc))

        # ── Anchor type 5: symptom + form queries ─────────────────
        form = getattr(med, 'form', '') or ''
        if uses and form:
            first_use = uses.split('|')[0].strip()
            first_use = re.sub(r'^Treatment of\s+', '', first_use, flags=re.IGNORECASE).strip()
            if first_use:
                pairs.append((f"{first_use.lower()} {form.lower()}", full_doc))

    print(f"  Generated {len(pairs):,} training pairs from {len(medicines):,} medicines")
    print(f"  Skipped {skipped} medicines with no text")
    return pairs


# ─────────────────────────────────────────────────────────────────
# FINE-TUNING
# ─────────────────────────────────────────────────────────────────


def _do_rebuild():
    """
    Rebuild medicine embeddings using the fine-tuned model.
    Implemented inline — no semantic_search import needed.
    """
    import json
    import numpy as np
    from pathlib import Path
    from sentence_transformers import SentenceTransformer
    from database import SessionLocal
    from models import Medicine

    FINETUNED = MODEL_DIR / "semantic_finetuned"
    EMB_PATH  = MODEL_DIR / "medicine_embeddings.npy"
    NAMES_PATH= MODEL_DIR / "medicine_names.json"

    model_path = str(FINETUNED) if FINETUNED.exists() else BASE_MODEL
    print(f"  Loading model from: {model_path}")
    model = SentenceTransformer(model_path)

    db = SessionLocal()
    try:
        medicines = db.query(Medicine).filter(Medicine.brand_name.isnot(None)).all()
        print(f"  Encoding {len(medicines):,} medicines...")

        def build_text(med):
            parts = []
            for attr in ["brand_name", "generic_name"]:
                v = getattr(med, attr, "") or ""
                if v: parts.append(v)
            uses = getattr(med, "uses", "") or getattr(med, "description", "") or ""
            if uses: parts.append(uses.replace(" | ", ". ")[:600])
            sides = getattr(med, "side_effects", "") or ""
            if sides: parts.append(sides.replace(" | ", " ")[:300])
            mfr = getattr(med, "manufacturer", "") or ""
            if mfr: parts.append(mfr)
            return " ".join(filter(None, parts))

        texts = [build_text(m) for m in medicines]
        names = [m.brand_name for m in medicines]

        # Encode in batches with progress
        batch_size = 256
        all_embs   = []
        for i in range(0, len(texts), batch_size):
            batch = texts[i:i+batch_size]
            embs  = model.encode(batch, convert_to_numpy=True,
                                  normalize_embeddings=True,
                                  show_progress_bar=False)
            all_embs.append(embs)
            done = min(i + batch_size, len(texts))
            print(f"  {done:,} / {len(texts):,} encoded", end="\r")

        matrix = np.vstack(all_embs).astype(np.float32)
        np.save(str(EMB_PATH), matrix)
        NAMES_PATH.write_text(json.dumps(names))
        print(f"\n  ✅ Embeddings saved — {len(names):,} medicines × 384 dims")
        print(f"  {EMB_PATH}")
    finally:
        db.close()



def fine_tune(epochs: int = 3, batch_size: int = 16, max_pairs: int = None):
    """
    Fine-tune all-MiniLM-L6-v2 on the medicine dataset.
    Uses pure PyTorch — no sentence-transformers training API needed.
    """
    import torch
    import torch.nn.functional as F
    from torch.optim import AdamW
    from torch.optim.lr_scheduler import LinearLR, SequentialLR
    from torch.utils.data import DataLoader
    from transformers import AutoTokenizer, AutoModel
    from database import SessionLocal
    from models import Medicine

    print("\n" + "="*60)
    print("  PharmaFind Semantic Search — Fine-tuning")
    print("="*60)
    print(f"  Base model:  {BASE_MODEL}")
    print(f"  Epochs:      {epochs}")
    print(f"  Batch size:  {batch_size}")
    print(f"  Output:      {FINETUNED_PATH}")
    print()

    # Load medicines
    db = SessionLocal()
    try:
        medicines = db.query(Medicine).filter(Medicine.brand_name.isnot(None)).all()
        print(f"  Loaded {len(medicines):,} medicines from database")
    finally:
        db.close()

    if len(medicines) < 10:
        print("  ❌ Not enough medicines. Run: python load_medicine_dataset.py --csv ...")
        return

    # Generate training pairs
    print("\n  Generating training pairs...")
    pairs = build_training_pairs(medicines)
    if max_pairs:
        import random
        random.shuffle(pairs)
        pairs = pairs[:max_pairs]
        print(f"  Capped to {len(pairs):,} pairs")

    # ── Load raw HuggingFace transformer (bypasses sentence-transformers) ──
    # This guarantees gradients flow — no sentence-transformers inference layer
    print(f"\n  Loading base model ({BASE_MODEL})...")
    hf_name    = "sentence-transformers/" + BASE_MODEL
    tokenizer  = AutoTokenizer.from_pretrained(hf_name)
    bert_model = AutoModel.from_pretrained(hf_name)

    device = "cuda" if torch.cuda.is_available() else "cpu"
    print(f"  Device: {device}")
    bert_model = bert_model.to(device)
    bert_model.train()

    # Temperature for contrastive loss
    temperature = 20.0

    def mean_pool(token_emb, attn_mask):
        mask = attn_mask.unsqueeze(-1).expand(token_emb.size()).float()
        return (token_emb * mask).sum(1) / mask.sum(1).clamp(min=1e-9)

    def encode_batch(texts):
        """Tokenise + forward + mean-pool with gradient tracking."""
        tok = tokenizer(
            texts, padding=True, truncation=True,
            max_length=128, return_tensors="pt"
        )
        tok = {k: v.to(device) for k, v in tok.items()}
        out = bert_model(**tok)                                  # gradients flow here
        emb = mean_pool(out.last_hidden_state, tok["attention_mask"])
        return F.normalize(emb, p=2, dim=1)

    # DataLoader — returns plain lists of (anchor, positive) tuples
    def collate(batch):
        anchors   = [a for a, _ in batch]
        positives = [p for _, p in batch]
        return anchors, positives

    dataset    = pairs
    dataloader = DataLoader(
        dataset,
        batch_size = batch_size,
        shuffle    = True,
        drop_last  = True,
        collate_fn = collate
    )

    # Optimizer + scheduler
    optimizer = AdamW(bert_model.parameters(), lr=2e-5, weight_decay=0.01)
    total_steps  = len(dataloader) * epochs
    warmup_steps = max(1, total_steps // 10)
    warmup_sched = LinearLR(optimizer, start_factor=0.1, end_factor=1.0,
                             total_iters=warmup_steps)
    decay_sched  = LinearLR(optimizer, start_factor=1.0, end_factor=0.1,
                             total_iters=max(1, total_steps - warmup_steps))
    scheduler    = SequentialLR(optimizer,
                                schedulers=[warmup_sched, decay_sched],
                                milestones=[warmup_steps])

    print(f"\n  Training...")
    print(f"  Total steps:   {total_steps:,}")
    print(f"  Warmup steps:  {warmup_steps:,}")
    print(f"  Pairs/epoch:   {len(pairs):,}")
    print()

    best_loss   = float("inf")
    global_step = 0

    for epoch in range(1, epochs + 1):
        bert_model.train()
        epoch_loss = 0.0
        n_batches  = 0

        for batch_idx, (anchors, positives) in enumerate(dataloader):
            emb_a = encode_batch(anchors)      # (B, 384) — with grad
            emb_p = encode_batch(positives)    # (B, 384) — with grad

            # In-batch negatives: correct pair is on diagonal
            scores   = torch.mm(emb_a, emb_p.T) * temperature   # (B, B)
            labels   = torch.arange(len(anchors), device=device)
            loss_val = F.cross_entropy(scores, labels)

            optimizer.zero_grad()
            loss_val.backward()
            torch.nn.utils.clip_grad_norm_(bert_model.parameters(), 1.0)
            optimizer.step()
            scheduler.step()

            epoch_loss  += loss_val.item()
            n_batches   += 1
            global_step += 1

            # Progress every 50 steps
            if batch_idx % 50 == 0 or batch_idx == len(dataloader) - 1:
                avg = epoch_loss / n_batches
                pct = 100 * batch_idx / len(dataloader)
                print(f"  Epoch {epoch}/{epochs}  [{pct:5.1f}%]  "
                      f"step {global_step}/{total_steps}  loss={avg:.4f}", end="\r")

        avg_loss = epoch_loss / max(n_batches, 1)
        lr_now   = optimizer.param_groups[0]["lr"]
        print(f"  Epoch {epoch}/{epochs}  [100.0%]  "
              f"loss={avg_loss:.4f}  lr={lr_now:.2e}          ")

        if avg_loss < best_loss:
            best_loss = avg_loss

    # ── Save as SentenceTransformer ────────────────────────────────
    # Save raw HF model + tokenizer, then wrap in SentenceTransformer
    print(f"\n  Saving model...")
    tmp_path = MODEL_DIR / "hf_finetuned_tmp"
    bert_model.eval()
    bert_model.save_pretrained(str(tmp_path))
    tokenizer.save_pretrained(str(tmp_path))

    # Reload as SentenceTransformer and save in ST format
    from sentence_transformers import SentenceTransformer
    st_model = SentenceTransformer(str(tmp_path))
    st_model.save(str(FINETUNED_PATH))

    # Clean up temp
    import shutil
    shutil.rmtree(str(tmp_path), ignore_errors=True)

    print(f"  ✅ Fine-tuning complete!  Best loss: {best_loss:.4f}")
    print(f"  Model saved to: {FINETUNED_PATH}")
    print()


def evaluate(n_queries: int = 20):
    """
    Quick qualitative evaluation: run test queries and show top results.
    Compares fine-tuned vs pre-trained if both are available.
    """
    from sentence_transformers import SentenceTransformer
    from database import SessionLocal
    from models import Medicine
    def _build_medicine_text(medicine) -> str:
        parts = []
        for attr in ['brand_name','generic_name']:
            v = getattr(medicine, attr, '') or ''
            if v: parts.append(v)
        uses = getattr(medicine,'uses','') or getattr(medicine,'description','') or ''
        if uses: parts.append(uses.replace(' | ','. ')[:600])
        sides = getattr(medicine,'side_effects','') or ''
        if sides: parts.append(sides.replace(' | ',' ')[:300])
        mfr = getattr(medicine,'manufacturer','') or ''
        if mfr: parts.append(mfr)
        return ' '.join(filter(None, parts))

    def _cosine_sim_batch(q, matrix):
        q = q / (np.linalg.norm(q) + 1e-10)
        return matrix @ q

    db = SessionLocal()
    try:
        medicines = db.query(Medicine).all()
    finally:
        db.close()

    if not medicines:
        print("No medicines in database.")
        return

    # Build texts + encode
    texts = [_build_medicine_text(m) for m in medicines]
    names = [m.brand_name for m in medicines]

    TEST_QUERIES = [
        "headache fever tablet",
        "bacterial infection antibiotic",
        "blood pressure medicine",
        "diabetes sugar control",
        "cough with mucus syrup",
        "acid reflux heartburn",
        "allergy sneezing runny nose",
        "colon cancer treatment",
        "kidney cancer",
        "cholesterol statin",
        "nausea vomiting",
        "thyroid medicine",
        "blood thinner",
        "asthma inhaler breathing",
        "pain killer anti-inflammatory",
    ][:n_queries]

    models_to_test = []
    if FINETUNED_PATH.exists():
        models_to_test.append(("Fine-tuned (your dataset)", str(FINETUNED_PATH)))
    models_to_test.append(("Pre-trained (generic)", BASE_MODEL))

    for model_label, model_path in models_to_test:
        print(f"\n{'='*60}")
        print(f"  {model_label}")
        print(f"{'='*60}")

        model = SentenceTransformer(model_path)
        doc_embs = model.encode(
            texts,
            convert_to_numpy     = True,
            normalize_embeddings = True,
            show_progress_bar    = False,
            batch_size           = 64
        )

        for query in TEST_QUERIES:
            q_emb = model.encode(
                [query],
                convert_to_numpy     = True,
                normalize_embeddings = True,
                show_progress_bar    = False
            )[0]

            scores  = _cosine_sim_batch(q_emb, doc_embs)
            top_idx = np.argsort(-scores)[:3]

            results = [f"{names[i]} ({scores[i]:.2f})" for i in top_idx]
            print(f"  '{query}'")
            print(f"    → {' | '.join(results)}")


# ─────────────────────────────────────────────────────────────────
# CLI
# ─────────────────────────────────────────────────────────────────

if __name__ == '__main__':
    import numpy as np   # needed for evaluate()

    parser = argparse.ArgumentParser(
        description='Fine-tune semantic search model on medicine dataset'
    )
    parser.add_argument('--epochs',     type=int,  default=3,
                        help='Training epochs (default: 3)')
    parser.add_argument('--batch-size', type=int,  default=16,
                        help='Batch size (default: 16, reduce if OOM)')
    parser.add_argument('--max-pairs',  type=int,  default=None,
                        help='Max training pairs (default: all)')
    parser.add_argument('--evaluate',   action='store_true',
                        help='Run qualitative evaluation after training')
    parser.add_argument('--evaluate-only', action='store_true',
                        help='Only evaluate, skip training')
    parser.add_argument('--rebuild-embeddings-only', action='store_true',
                        help='Only rebuild embeddings from DB, skip training')
    args = parser.parse_args()

    if args.rebuild_embeddings_only:
        _do_rebuild()

    elif args.evaluate_only:
        evaluate()

    else:
        fine_tune(
            epochs     = args.epochs,
            batch_size = args.batch_size,
            max_pairs  = args.max_pairs
        )
        # Rebuild embeddings with the new model
        print("\nRebuilding medicine embeddings with fine-tuned model...")
        _do_rebuild()

        if args.evaluate:
            evaluate()

    print("\nDone! Restart the backend to use the updated model.")
    print("  uvicorn main:app --reload")