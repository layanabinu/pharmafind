import os
from sqlalchemy import create_engine, text

DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///./pharmafind.db")
IS_SQLITE = DATABASE_URL.startswith("sqlite")

engine = create_engine(
    DATABASE_URL,
    connect_args={"check_same_thread": False} if IS_SQLITE else {}
)

if IS_SQLITE:
    migrations = [
        ("reserved_stock",
         "ALTER TABLE pharmacy_inventory ADD COLUMN reserved_stock INTEGER NOT NULL DEFAULT 0"),
        ("last_stock_updated",
         "ALTER TABLE pharmacy_inventory ADD COLUMN last_stock_updated DATETIME")
    ]
else:
    migrations = [
        ("reserved_stock",
         "ALTER TABLE pharmacy_inventory ADD COLUMN IF NOT EXISTS reserved_stock INTEGER NOT NULL DEFAULT 0"),
        ("last_stock_updated",
         "ALTER TABLE pharmacy_inventory ADD COLUMN IF NOT EXISTS last_stock_updated TIMESTAMP DEFAULT NOW()")
    ]

with engine.connect() as conn:
    if IS_SQLITE:
        result = conn.execute(text("PRAGMA table_info(pharmacy_inventory)"))
        existing = {row[1] for row in result}
        print(f"Existing columns: {existing}")

        for col_name, sql in migrations:
            if col_name not in existing:
                conn.execute(text(sql))
                conn.commit()
                print(f"✅ Added column: {col_name}")
            else:
                print(f"⏭ Column already exists: {col_name}")

        conn.execute(text("""
            UPDATE pharmacy_inventory
            SET reserved_stock = 0
            WHERE reserved_stock IS NULL
        """))
        conn.execute(text("""
            UPDATE pharmacy_inventory
            SET last_stock_updated = CURRENT_TIMESTAMP
            WHERE last_stock_updated IS NULL
        """))
        conn.commit()
        print("✅ Backfilled SQLite values")

    else:
        for col_name, sql in migrations:
            try:
                conn.execute(text(sql))
                conn.commit()
                print(f"✅ Applied: {col_name}")
            except Exception as e:
                conn.rollback()
                print(f"⏭ {col_name}: {e}")

        conn.execute(text("""
            UPDATE pharmacy_inventory
            SET reserved_stock = 0
            WHERE reserved_stock IS NULL
        """))
        conn.execute(text("""
            UPDATE pharmacy_inventory
            SET last_stock_updated = NOW()
            WHERE last_stock_updated IS NULL
        """))
        conn.commit()
        print("✅ Backfilled NULLs")

print("\nMigration complete. You can now start the server.")