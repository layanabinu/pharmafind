"""
migrate_to_postgres.py
──────────────────────
One-time script to copy all data from SQLite → PostgreSQL.

Run this ONCE after setting DATABASE_URL in .env to your PostgreSQL URL:

  1. Set in .env:
       DATABASE_URL=postgresql://user:password@host:port/dbname

  2. Run:
       python migrate_to_postgres.py

  3. Verify:
       python migrate_to_postgres.py --verify

What it does:
  - Creates all tables in PostgreSQL
  - Copies users, pharmacies, medicines, inventory, user_medicines
  - Preserves all IDs and relationships
  - Safe to run multiple times (skips if data already exists)
"""

import os
import sys
from dotenv import load_dotenv

load_dotenv()

DATABASE_URL = os.getenv("DATABASE_URL", "")

if not DATABASE_URL or not DATABASE_URL.startswith("postgresql"):
    print("❌ DATABASE_URL not set or not a PostgreSQL URL.")
    print("   Add to .env: DATABASE_URL=postgresql://user:pass@host:port/dbname")
    sys.exit(1)


from sqlalchemy import create_engine, text
from sqlalchemy.orm import sessionmaker

# ── Connect to SQLite (source) ──
SQLITE_PATH = os.path.join(os.path.dirname(__file__), "pharmafind.db")
if not os.path.exists(SQLITE_PATH):
    print(f"❌ SQLite database not found at {SQLITE_PATH}")
    print("   Run seed_data.py first to create it.")
    sys.exit(1)

sqlite_engine = create_engine(
    f"sqlite:///{SQLITE_PATH}",
    connect_args={"check_same_thread": False}
)
SQLiteSession = sessionmaker(bind=sqlite_engine)

# ── Connect to PostgreSQL (destination) ──
pg_url = DATABASE_URL.replace("postgres://", "postgresql://", 1)
pg_engine = create_engine(pg_url, pool_pre_ping=True)
PGSession = sessionmaker(bind=pg_engine)

# ── Import models ──
from models import Base, User, Pharmacy, Medicine, PharmacyInventory, UserMedicine


def migrate():
    print("\n" + "="*60)
    print("🚀 PHARMAFIND: SQLite → PostgreSQL Migration")
    print("="*60)

    # Create all tables in PostgreSQL
    print("\n📦 Creating tables in PostgreSQL...")
    Base.metadata.create_all(bind=pg_engine)
    print("✅ Tables created!")

    sqlite_db = SQLiteSession()
    pg_db = PGSession()

    try:
        # ── Users ──
        users = sqlite_db.query(User).all()
        existing = pg_db.query(User).count()
        if existing == 0:
            for u in users:
                pg_db.merge(u)  # merge preserves IDs
            pg_db.commit()
            print(f"✅ Migrated {len(users)} users")
        else:
            print(f"⏭️  Users already exist ({existing}) — skipping")

        # ── Pharmacies ──
        pharmacies = sqlite_db.query(Pharmacy).all()
        existing = pg_db.query(Pharmacy).count()
        if existing == 0:
            for p in pharmacies:
                pg_db.merge(p)
            pg_db.commit()
            print(f"✅ Migrated {len(pharmacies)} pharmacies")
        else:
            print(f"⏭️  Pharmacies already exist ({existing}) — skipping")

        # ── Medicines ──
        medicines = sqlite_db.query(Medicine).all()
        existing = pg_db.query(Medicine).count()
        if existing == 0:
            for m in medicines:
                pg_db.merge(m)
            pg_db.commit()
            print(f"✅ Migrated {len(medicines)} medicines")
        else:
            print(f"⏭️  Medicines already exist ({existing}) — skipping")

        # ── Inventory ──
        inventory = sqlite_db.query(PharmacyInventory).all()
        existing = pg_db.query(PharmacyInventory).count()
        if existing == 0:
            for item in inventory:
                pg_db.merge(item)
            pg_db.commit()
            print(f"✅ Migrated {len(inventory)} inventory records")
        else:
            print(f"⏭️  Inventory already exists ({existing}) — skipping")

        # ── User Medicines ──
        user_medicines = sqlite_db.query(UserMedicine).all()
        existing = pg_db.query(UserMedicine).count()
        if existing == 0 and user_medicines:
            for um in user_medicines:
                pg_db.merge(um)
            pg_db.commit()
            print(f"✅ Migrated {len(user_medicines)} user medicine entries")
        else:
            print(f"⏭️  User medicines: {existing} existing — skipping")

        # ── Fix sequences (PostgreSQL needs this after bulk ID inserts) ──
        print("\n🔧 Fixing PostgreSQL sequences...")
        with pg_engine.connect() as conn:
            for table, seq_col in [
                ("users", "id"),
                ("pharmacies", "id"),
                ("medicines", "id"),
                ("pharmacy_inventory", "id"),
                ("user_medicines", "id"),
            ]:
                conn.execute(text(
                    f"SELECT setval(pg_get_serial_sequence('{table}', '{seq_col}'), "
                    f"COALESCE((SELECT MAX({seq_col}) FROM {table}), 1))"
                ))
            conn.commit()
        print("✅ Sequences fixed!")

        print("\n" + "="*60)
        print("🎉 MIGRATION COMPLETE!")
        print("="*60)
        print("\n📊 PostgreSQL now has:")
        print(f"   👤 Users:      {pg_db.query(User).count()}")
        print(f"   🏥 Pharmacies: {pg_db.query(Pharmacy).count()}")
        print(f"   💊 Medicines:  {pg_db.query(Medicine).count()}")
        print(f"   📦 Inventory:  {pg_db.query(PharmacyInventory).count()}")
        print("\n✅ Your app will now use PostgreSQL automatically.")
        print("   Restart backend: python main.py")

    except Exception as e:
        pg_db.rollback()
        print(f"\n❌ Migration failed: {e}")
        raise
    finally:
        sqlite_db.close()
        pg_db.close()


def verify():
    """Quick check that PostgreSQL has same counts as SQLite."""
    sqlite_db = SQLiteSession()
    pg_db = PGSession()
    print("\n🔍 Verifying migration...")
    tables = [
        ("Users",      User),
        ("Pharmacies", Pharmacy),
        ("Medicines",  Medicine),
        ("Inventory",  PharmacyInventory),
    ]
    all_ok = True
    for name, model in tables:
        sq = sqlite_db.query(model).count()
        pg = pg_db.query(model).count()
        status = "✅" if sq == pg else "❌"
        print(f"  {status} {name}: SQLite={sq}, PostgreSQL={pg}")
        if sq != pg:
            all_ok = False
    sqlite_db.close()
    pg_db.close()
    if all_ok:
        print("\n✅ All tables match — migration successful!")
    else:
        print("\n❌ Counts don't match — re-run migration")


if __name__ == "__main__":
    if "--verify" in sys.argv:
        verify()
    else:
        migrate()