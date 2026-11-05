"""
migrate_add_medicine_dataset_fields.py
───────────────────────────────────────
Adds all new columns needed for the medicine dataset and new features.
Safe to run multiple times — skips columns that already exist.

Covers:
  medicines table:     uses, side_effects, image_url, *_review_pct
  user_medicines table: tablets_per_strip, time_of_day

Usage:
    python migrate_add_medicine_dataset_fields.py
"""

from database import engine
from sqlalchemy import text


def migrate():
    # Step 1: Create all tables from models (safe — skips existing tables)
    from database import Base
    Base.metadata.create_all(bind=engine)
    print("  ✅ Tables created/verified")

    with engine.connect() as conn:
        print("\n" + "="*55)
        print("  PharmaFind — Database Migration")
        print("="*55)

        # ── medicines table ────────────────────────────────────────
        print("\n  medicines table:")
        medicine_cols = {
            "uses":                 "TEXT",
            "side_effects":         "TEXT",
            "image_url":            "VARCHAR(500)",
            "excellent_review_pct": "INTEGER DEFAULT 0",
            "average_review_pct":   "INTEGER DEFAULT 0",
            "poor_review_pct":      "INTEGER DEFAULT 0",
        }
        for col, col_type in medicine_cols.items():
            try:
                conn.execute(text(f"SELECT {col} FROM medicines LIMIT 1"))
                print(f"    {col}: already exists")
            except Exception:
                try:
                    conn.execute(text(
                        f"ALTER TABLE medicines ADD COLUMN {col} {col_type}"
                    ))
                    conn.commit()
                    print(f"    {col}: added ✅")
                except Exception as e:
                    print(f"    {col}: ERROR — {e}")

        # ── user_medicines table ───────────────────────────────────
        print("\n  user_medicines table:")
        user_med_cols = {
            "tablets_per_strip": "INTEGER DEFAULT 10",
            "time_of_day":       "VARCHAR(20) DEFAULT 'Morning'",
        }
        for col, col_type in user_med_cols.items():
            try:
                conn.execute(text(f"SELECT {col} FROM user_medicines LIMIT 1"))
                print(f"    {col}: already exists")
            except Exception:
                try:
                    conn.execute(text(
                        f"ALTER TABLE user_medicines ADD COLUMN {col} {col_type}"
                    ))
                    conn.commit()
                    print(f"    {col}: added ✅")
                except Exception as e:
                    print(f"    {col}: ERROR — {e}")

        print("\n" + "="*55)
        print("  Migration complete!")
        print("="*55)
        print("\nNext steps:")
        print("  python load_medicine_dataset.py --csv Medicine_Details.csv")
        print("  python train_semantic_model.py")
        print("="*55 + "\n")


if __name__ == "__main__":
    migrate()