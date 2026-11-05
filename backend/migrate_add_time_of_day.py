"""
migrate_add_time_of_day.py
─────────────────────────
Adds time_of_day column to user_medicines table.
Run this ONCE on an existing database to add the new column without data loss.

Usage:
    python migrate_add_time_of_day.py
"""

from database import engine
from sqlalchemy import text

def migrate():
    with engine.connect() as conn:
        # Check if column already exists
        try:
            conn.execute(text("SELECT time_of_day FROM user_medicines LIMIT 1"))
            print("✅ time_of_day column already exists — no migration needed")
            return
        except Exception:
            pass

        # Add the column
        try:
            conn.execute(text(
                "ALTER TABLE user_medicines ADD COLUMN time_of_day VARCHAR(20) DEFAULT \'Morning\'"
            ))
            conn.commit()
            print("✅ Added time_of_day column to user_medicines")
        except Exception as e:
            print(f"❌ Migration failed: {e}")
            raise

if __name__ == "__main__":
    migrate()