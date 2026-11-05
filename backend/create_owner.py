"""
create_owner.py
───────────────
Run this ONCE to:
  1. Create a pharmacy owner account
  2. Link it to an existing pharmacy in the database
  3. Print login credentials to use in the app

Usage:
  python create_owner.py

Then login in the app with:
  Email:    owner@pharmafind.com
  Password: owner123
"""

from database import SessionLocal, engine, Base
from models import User, Pharmacy
from passlib.context import CryptContext

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
db = SessionLocal()

try:
    # ── Step 1: Pick a pharmacy to own ──────────────────────────
    pharmacy = db.query(Pharmacy).first()
    if not pharmacy:
        print("❌ No pharmacies found. Run seed_data.py first.")
        exit(1)

    print(f"✅ Found pharmacy: [{pharmacy.id}] {pharmacy.name}")

    # ── Step 2: Create owner user (or reuse if already exists) ──
    email = "owner@pharmafind.com"
    existing = db.query(User).filter(User.email == email).first()

    if existing:
        owner = existing
        print(f"ℹ️  User {email} already exists (id={owner.id}), reusing.")
    else:
        owner = User(
            email         = email,
            password_hash = pwd_context.hash("owner123"),
            name          = f"{pharmacy.name} Owner",
            role          = "pharmacy",
            is_active     = True,
            is_verified   = True,
        )
        db.add(owner)
        db.commit()
        db.refresh(owner)
        print(f"✅ Created owner user: id={owner.id}, email={email}")

    # ── Step 3: Link pharmacy to owner ──────────────────────────
    pharmacy.user_id = owner.id
    db.commit()
    print(f"✅ Linked pharmacy '{pharmacy.name}' (id={pharmacy.id}) → owner id={owner.id}")

    # ── Step 4: Also create a test patient account ───────────────
    patient_email = "patient@pharmafind.com"
    if not db.query(User).filter(User.email == patient_email).first():
        patient = User(
            email         = patient_email,
            password_hash = pwd_context.hash("patient123"),
            name          = "Test Patient",
            role          = "patient",
            is_active     = True,
            is_verified   = True,
        )
        db.add(patient)
        db.commit()
        print(f"✅ Created patient user: email={patient_email}, password=patient123")
    else:
        print(f"ℹ️  Patient user already exists.")

    print()
    print("=" * 50)
    print("🔑 LOGIN CREDENTIALS")
    print("=" * 50)
    print()
    print("📱 PHARMACY OWNER (→ goes to Owner Dashboard):")
    print(f"   Email:    owner@pharmafind.com")
    print(f"   Password: owner123")
    print(f"   Pharmacy: {pharmacy.name}")
    print()
    print("📱 PATIENT (→ goes to Home/Map):")
    print(f"   Email:    patient@pharmafind.com")
    print(f"   Password: patient123")
    print()
    print("✅ Done! Open the app and login with either account.")

except Exception as e:
    print(f"❌ Error: {e}")
    db.rollback()
finally:
    db.close()