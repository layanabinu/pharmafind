from fastapi import FastAPI, Depends, HTTPException
from contextlib import asynccontextmanager
from fastapi.middleware.cors import CORSMiddleware
from fastapi.security import HTTPBearer
from sqlalchemy.orm import Session
from passlib.context import CryptContext
from pydantic import BaseModel
from typing import Optional, List
from jose import JWTError, jwt
from datetime import datetime, timedelta
from dotenv import load_dotenv
import math
import os

load_dotenv()  # reads GOOGLE_PLACES_KEY from .env file

from database import get_db, engine
from models import Base, User, Pharmacy, Medicine, PharmacyInventory, UserMedicine, MedicationHistory, Reservation
from pharma_fuzzy import fuzzy_find_medicine, fuzzy_find_pharmacies, fuzzy_find_medicines_multi
from ml_ranking import rank_pharmacies, rank_pharmacies_for_medicine
from semantic_search import semantic_search, build_embeddings, get_search_suggestions_semantic, is_semantic_available
from collaborative_filter import get_recommendations, get_also_bought

Base.metadata.create_all(bind=engine)

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Create tables if they don't exist (important for first Vercel deploy)
    from models import Base
    Base.metadata.create_all(bind=engine)
    print("[Startup] Database ready")
    # ── Ensure reserved_stock column exists (safe for old DBs) ──
    try:
        from sqlalchemy import text as _text, inspect as _inspect
        with engine.connect() as _conn:
            _insp = _inspect(engine)
            _cols = [c["name"] for c in _insp.get_columns("pharmacy_inventory")]
            if "reserved_stock" not in _cols:
                if engine.dialect.name == "sqlite":
                    _conn.execute(_text(
                        "ALTER TABLE pharmacy_inventory "
                        "ADD COLUMN reserved_stock INTEGER DEFAULT 0 NOT NULL"
                    ))
                else:
                    _conn.execute(_text(
                        "ALTER TABLE pharmacy_inventory "
                        "ADD COLUMN IF NOT EXISTS reserved_stock INTEGER NOT NULL DEFAULT 0"
                    ))
                _conn.commit()
                print("[Startup] ✅ Added reserved_stock column automatically")
            if "last_stock_updated" not in _cols:
                _dt_type = "DATETIME" if engine.dialect.name == "sqlite" else "TIMESTAMP"
                _default = "CURRENT_TIMESTAMP" if engine.dialect.name == "sqlite" else "NOW()"
                if engine.dialect.name == "sqlite":
                    _conn.execute(_text(
                        f"ALTER TABLE pharmacy_inventory "
                        f"ADD COLUMN last_stock_updated {_dt_type} DEFAULT {_default}"
                    ))
                else:
                    _conn.execute(_text(
                        f"ALTER TABLE pharmacy_inventory "
                        f"ADD COLUMN IF NOT EXISTS last_stock_updated {_dt_type} DEFAULT {_default}"
                    ))
                _conn.commit()
                print("[Startup] ✅ Added last_stock_updated column automatically")
    except Exception as _e:
        print(f"[Startup] Migration warning: {_e}")
    print("[Startup] Database tables verified")

    # Load semantic search embeddings if available
    try:
        db = next(get_db())
        try:
            medicines = db.query(Medicine).all()
            if medicines:
                build_embeddings(medicines)
                print(f"[Startup] Semantic search ready for {len(medicines):,} medicines ✅")
            else:
                print("[Startup] No medicines in DB yet — run seed/load scripts")
        finally:
            db.close()
    except Exception as e:
        print(f"[Startup] Semantic search unavailable: {e} (app still works)")
    yield

app = FastAPI(lifespan=lifespan, title="PharmaFind API", version="3.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], allow_credentials=True,
    allow_methods=["*"], allow_headers=["*"],
)



pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")
SECRET_KEY = os.getenv("JWT_SECRET", "pharmafind-secret-key-change-in-production")
ALGORITHM = "HS256"
ACCESS_TOKEN_EXPIRE_DAYS = 30
security = HTTPBearer(auto_error=False)

# ── JWT ───────────────────────────────────────────────────────────

def create_access_token(user_id, email, role):
    payload = {"sub": str(user_id), "email": email, "role": role,
               "exp": datetime.utcnow() + timedelta(days=ACCESS_TOKEN_EXPIRE_DAYS)}
    return jwt.encode(payload, SECRET_KEY, algorithm=ALGORITHM)

def verify_token(token):
    try:
        return jwt.decode(token, SECRET_KEY, algorithms=[ALGORITHM])
    except JWTError:
        raise HTTPException(status_code=401, detail="Invalid or expired token")

# ── SCHEMAS ───────────────────────────────────────────────────────

class PharmacyCreate(BaseModel):
    name:      str
    address:   str
    city:      str = "Kottayam"
    state:     str = "Kerala"
    latitude:  float
    longitude: float
    phone:     str = ""

class OwnerRegisterRequest(BaseModel):
    name:            str
    email:           str
    password:        str
    role:            str = "pharmacy"
    pharmacy_name:   str
    pharmacy_address:str
    pharmacy_city:   str = "Kottayam"
    pharmacy_state:  str = "Kerala"
    pharmacy_lat:    float = 9.5916
    pharmacy_lng:    float = 76.5222
    pharmacy_phone:  str = ""

class UserCreate(BaseModel):
    email: str
    password: str
    name: str
    role: str = "patient"   # defaults to patient if not sent

class UserLogin(BaseModel):
    email: str
    password: str

class UserUpdate(BaseModel):
    name: Optional[str] = None
    phone: Optional[str] = None

class UserMedicineCreate(BaseModel):
    user_id: int
    medicine_name: str
    dosage: Optional[str] = None
    frequency: Optional[str] = None
    strips_remaining: Optional[int] = None
    tablets_per_strip: Optional[int] = 10
    reminder_enabled: Optional[bool] = True
    time_of_day: Optional[str] = "Morning"

class UserMedicineUpdate(BaseModel):
    dosage: Optional[str] = None
    frequency: Optional[str] = None
    strips_remaining: Optional[int] = None
    reminder_enabled: Optional[bool] = None

class InventoryUpdate(BaseModel):
    pharmacy_id: int
    medicine_id: int
    stock_quantity: int
    price: float
    is_available: bool = True

# ── HELPERS ───────────────────────────────────────────────────────

def calculate_distance(lat1, lon1, lat2, lon2):
    R = 6371
    dlat = math.radians(float(lat2) - float(lat1))
    dlon = math.radians(float(lon2) - float(lon1))
    a = (math.sin(dlat/2)**2 +
         math.cos(math.radians(float(lat1))) *
         math.cos(math.radians(float(lat2))) *
         math.sin(dlon/2)**2)
    return round(R * 2 * math.atan2(math.sqrt(a), math.sqrt(1-a)), 2)

def format_pharmacy(p, lat, lng):
    return {"id": p.id, "name": p.name, "place": p.city, "address": p.address,
            "lat": p.latitude, "lng": p.longitude,
            "distance_km": calculate_distance(lat, lng, p.latitude, p.longitude),
            "is_open": p.is_open, "rating": p.rating or 0.0, "phone": p.phone or ""}

# ── ROUTES ────────────────────────────────────────────────────────



@app.get("/debug/status")
def debug_status():
    """Shows configuration status — useful for diagnosing Places API issues."""
    key = os.getenv("GOOGLE_PLACES_KEY", "")
    return {
        "places_api": {
            "key_loaded": bool(key),
            "key_preview": f"{key[:8]}..." if len(key) > 8 else "(not set)",
            "status": "active" if key else "missing — using seeded database"
        },
        "instructions": {
            "to_enable_real_pharmacies": [
                "1. Get a Google Places API key from console.cloud.google.com",
                "2. Enable 'Places API' in your Google Cloud project",
                "3. Create a .env file in the backend folder with: GOOGLE_PLACES_KEY=AIzaSy...",
                "4. Or run: set GOOGLE_PLACES_KEY=AIzaSy... (Windows) / export GOOGLE_PLACES_KEY=AIzaSy... (Linux/Mac)",
                "5. Restart the backend: uvicorn main:app --reload"
            ]
        }
    }

@app.get("/")
def root():
    return {"message": "PharmaFind API Running", "version": "3.0.0", "status": "OK"}

# AUTH
@app.post("/auth/register")
def register(user: UserCreate, db: Session = Depends(get_db)):
    if db.query(User).filter(User.email == user.email).first():
        raise HTTPException(400, "Email already registered")
    if user.role not in ["patient", "pharmacy", "admin"]:
        user.role = "patient"   # silently correct bad role instead of rejecting
    if len(user.password) < 6:
        raise HTTPException(400, "Password must be at least 6 characters")
    new_user = User(email=user.email, password_hash=pwd_context.hash(user.password),
                    name=user.name, role=user.role, is_active=True, is_verified=True)
    db.add(new_user)
    db.commit()
    db.refresh(new_user)
    return {"success": True, "message": f"Welcome to PharmaFind, {new_user.name}!",
            "user": {"id": new_user.id, "email": new_user.email,
                     "name": new_user.name, "role": new_user.role},
            "token": create_access_token(new_user.id, new_user.email, new_user.role)}

@app.post("/auth/login")
def login(credentials: UserLogin, db: Session = Depends(get_db)):
    user = db.query(User).filter(User.email == credentials.email).first()
    if not user or not pwd_context.verify(credentials.password, user.password_hash):
        raise HTTPException(401, "Invalid email or password")
    if not user.is_active:
        raise HTTPException(403, "Account is disabled")

    # If pharmacy owner, find their linked pharmacy
    pharmacy_id = None
    pharmacy_name = None
    if user.role == "pharmacy":
        pharmacy = db.query(Pharmacy).filter(Pharmacy.user_id == user.id).first()
        if pharmacy:
            pharmacy_id   = pharmacy.id
            pharmacy_name = pharmacy.name

    return {"success": True, "message": f"Welcome back, {user.name}!",
            "user": {"id": user.id, "email": user.email, "name": user.name,
                     "role": user.role, "phone": user.phone or "",
                     "pharmacy_id": pharmacy_id,
                     "pharmacy_name": pharmacy_name},
            "token": create_access_token(user.id, user.email, user.role)}

# USERS
@app.post("/auth/register-owner")
def register_owner(data: OwnerRegisterRequest, db: Session = Depends(get_db)):
    """
    Register a pharmacy owner + create their pharmacy in one step.
    The pharmacy location comes from the address + lat/lng they provide.
    """
    if db.query(User).filter(User.email == data.email).first():
        raise HTTPException(400, "Email already registered")
    if len(data.password) < 6:
        raise HTTPException(400, "Password must be at least 6 characters")

    # Resolve coordinates — geocode from address if GPS was not provided
    pharmacy_lat = data.pharmacy_lat or 0.0
    pharmacy_lng = data.pharmacy_lng or 0.0

    if pharmacy_lat == 0.0 or pharmacy_lng == 0.0:
        try:
            # Use OpenStreetMap Nominatim (free, no key needed)
            import requests as req_lib
            address_str = f"{data.pharmacy_address}, {data.pharmacy_city}, Kerala, India"
            geo_resp = req_lib.get(
                "https://nominatim.openstreetmap.org/search",
                params={"q": address_str, "format": "json", "limit": 1},
                headers={"User-Agent": "PharmaFind/1.0"},
                timeout=5
            )
            if geo_resp.status_code == 200:
                results = geo_resp.json()
                if results:
                    pharmacy_lat = float(results[0]["lat"])
                    pharmacy_lng = float(results[0]["lon"])
        except Exception as e:
            print(f"[Geocoding] Failed for {data.pharmacy_address}: {e}")
            # Fall back to city centre coords for common Kerala cities
            city_coords = {
                "kottayam":       (9.5916,  76.5222),
                "ernakulam":      (9.9816,  76.2999),
                "kochi":          (9.9312,  76.2673),
                "thrissur":       (10.5276, 76.2144),
                "thiruvananthapuram": (8.5241, 76.9366),
                "kollam":         (8.8932,  76.6141),
                "alappuzha":      (9.4981,  76.3388),
                "changanacherry": (9.4360,  76.5430),
                "pala":           (9.7011,  76.6886),
                "pathanamthitta": (9.2648,  76.7870),
            }
            city_key = data.pharmacy_city.lower().strip()
            if city_key in city_coords:
                pharmacy_lat, pharmacy_lng = city_coords[city_key]

    # Create the pharmacy
    pharmacy = Pharmacy(
        name      = data.pharmacy_name,
        address   = data.pharmacy_address,
        city      = data.pharmacy_city,
        state     = data.pharmacy_state,
        latitude  = pharmacy_lat,
        longitude = pharmacy_lng,
        phone     = data.pharmacy_phone,
        is_open   = True,
        rating    = 0.0,
    )
    db.add(pharmacy)
    db.flush()   # get pharmacy.id without committing

    # Create the owner user linked to pharmacy
    owner = User(
        email         = data.email,
        password_hash = pwd_context.hash(data.password),
        name          = data.name,
        role          = "pharmacy",
        is_active     = True,
        is_verified   = True,
    )
    db.add(owner)
    db.flush()

    # Link owner → pharmacy
    pharmacy.user_id = owner.id
    db.commit()
    db.refresh(owner)
    db.refresh(pharmacy)

    token = create_access_token(owner.id, owner.email, owner.role)
    return {
        "success": True,
        "message": f"Welcome, {owner.name}! Your pharmacy '{pharmacy.name}' is registered.",
        "user": {
            "id": owner.id, "name": owner.name,
            "email": owner.email, "role": owner.role,
            "pharmacy_id": pharmacy.id,
            "pharmacy_name": pharmacy.name,
        },
        "token": token,
        "pharmacy_id": pharmacy.id,
    }

@app.post("/pharmacies")
def add_pharmacy(data: PharmacyCreate, db: Session = Depends(get_db)):
    """Owner adds a new pharmacy (or additional branch)."""
    pharmacy = Pharmacy(
        name      = data.name,
        address   = data.address,
        city      = data.city,
        state     = data.state,
        latitude  = data.latitude,
        longitude = data.longitude,
        phone     = data.phone,
        is_open   = True,
        rating    = 0.0,
    )
    db.add(pharmacy)
    db.commit()
    db.refresh(pharmacy)
    return {"success": True, "pharmacy_id": pharmacy.id, "name": pharmacy.name}

@app.get("/users/{user_id}")
def get_user(user_id: int, db: Session = Depends(get_db)):
    user = db.query(User).filter(User.id == user_id).first()
    if not user: raise HTTPException(404, "User not found")
    return {"id": user.id, "email": user.email, "name": user.name,
            "role": user.role, "phone": user.phone or ""}

@app.put("/users/{user_id}")
def update_user(user_id: int, data: UserUpdate, db: Session = Depends(get_db)):
    user = db.query(User).filter(User.id == user_id).first()
    if not user: raise HTTPException(404, "User not found")
    if data.name: user.name = data.name
    if data.phone: user.phone = data.phone
    db.commit()
    return {"success": True, "message": "Profile updated"}

# PHARMACIES
@app.get("/pharmacies")
def get_pharmacies(
    lat: float = 9.5916, lng: float = 76.5222,
    radius_km: float = 50.0, limit: int = 20,
    db: Session = Depends(get_db)
):
    """Home screen: open pharmacies within radius_km, ranked by score.
    Expanded radius (50km default) ensures newly registered pharmacies appear.
    Pharmacies with no GPS (0,0) are included last so they are always visible."""
    pharmacies = db.query(Pharmacy).filter(Pharmacy.is_open == True).all()
    nearby   = []
    no_gps   = []   # pharmacies with 0,0 coords — show separately

    for p in pharmacies:
        if (p.latitude == 0.0 and p.longitude == 0.0) or            p.latitude is None or p.longitude is None:
            # No GPS — still show them so owner knows they're registered
            formatted = format_pharmacy(p, lat, lng)
            formatted["distance_km"] = 999.0   # sort to end
            formatted["no_gps"] = True
            no_gps.append(formatted)
        else:
            dist = calculate_distance(lat, lng, p.latitude, p.longitude)
            if dist <= radius_km:
                nearby.append(format_pharmacy(p, lat, lng))

    # Apply intelligent ranking before limiting results
    ranked = rank_pharmacies(nearby)
    # Append no-GPS pharmacies at end so they appear in list (and owner sees them)
    all_results = ranked[:limit] + no_gps[:5]
    return all_results

@app.get("/pharmacies/search")
def search_pharmacies(
    query: str, lat: float = 9.5916, lng: float = 76.5222,
    radius_km: float = 20.0, db: Session = Depends(get_db)
):
    """
    FUZZY pharmacy name search within radius.
    Handles typos, partial names, city names.
    """
    all_pharmacies = db.query(Pharmacy).all()

    # 1. Try exact ilike first (fast)
    exact = db.query(Pharmacy).filter(Pharmacy.name.ilike(f"%{query}%")).all()

    # 2. FuzzyWuzzy fallback if exact finds nothing
    if not exact:
        matched = fuzzy_find_pharmacies(query, all_pharmacies, threshold=55, limit=15)
    else:
        matched = exact

    result = []
    for p in matched:
        dist = calculate_distance(lat, lng, p.latitude, p.longitude)
        if dist <= radius_km:
            result.append(format_pharmacy(p, lat, lng))

    result.sort(key=lambda x: x["distance_km"])
    return result

@app.get("/pharmacies/{pharmacy_id}")
def get_pharmacy_detail(pharmacy_id: int, lat: float = 9.5916, lng: float = 76.5222,
                         db: Session = Depends(get_db)):
    pharmacy = db.query(Pharmacy).filter(Pharmacy.id == pharmacy_id).first()
    if not pharmacy: raise HTTPException(404, "Pharmacy not found")
    inventory = db.query(PharmacyInventory, Medicine).join(
        Medicine, PharmacyInventory.medicine_id == Medicine.id
    ).filter(PharmacyInventory.pharmacy_id == pharmacy_id,
             PharmacyInventory.is_available == True).all()
    return {"id": pharmacy.id, "name": pharmacy.name, "address": pharmacy.address,
            "city": pharmacy.city, "lat": pharmacy.latitude, "lng": pharmacy.longitude,
            "phone": pharmacy.phone or "", "is_open": pharmacy.is_open,
            "rating": pharmacy.rating or 0.0,
            "distance_km": calculate_distance(lat, lng, pharmacy.latitude, pharmacy.longitude),
            "total_medicines": len(inventory),
            "medicines": [{"medicine_id": m.id, "name": m.brand_name,
                           "strength": m.strength or "", "price": inv.price,
                           "stock": inv.stock_quantity} for inv, m in inventory]}

# MEDICINES
@app.get("/medicines")
def get_all_medicines(db: Session = Depends(get_db)):
    return [{
        "id": m.id, "name": m.brand_name, "generic_name": m.generic_name or "",
        "strength": m.strength or "", "form": m.form or "",
        "description": m.description or "",
        "uses":                 getattr(m, 'uses', None) or m.description or "",
        "side_effects":         getattr(m, 'side_effects', None) or "",
        "image_url":            getattr(m, 'image_url', None) or "",
        "manufacturer":         m.manufacturer or "",
        "excellent_review_pct": getattr(m, 'excellent_review_pct', 0) or 0,
        "average_review_pct":   getattr(m, 'average_review_pct',  0) or 0,
        "poor_review_pct":      getattr(m, 'poor_review_pct',     0) or 0,
        "is_prescription_required": m.is_prescription_required,
    } for m in db.query(Medicine).all()]

@app.get("/medicines/search")
def search_medicine(
    query: str, lat: float = 9.5916, lng: float = 76.5222,
    radius_km: float = 25.0, limit: int = 10,
    db: Session = Depends(get_db)
):
    """
    Multi-stage medicine search:
    1. Exact ILIKE match (fastest)
    2. Semantic search via fine-tuned embeddings
    3. FuzzyWuzzy typo-tolerant fallback
    Returns pharmacies within radius_km that stock the medicine.
    """
    all_medicines = db.query(Medicine).all()
    medicine = None

    # Stage 1: Exact / partial name match
    medicine = (
        db.query(Medicine).filter(Medicine.brand_name.ilike(f"%{query}%")).first() or
        db.query(Medicine).filter(Medicine.generic_name.ilike(f"%{query}%")).first()
    )

    # Stage 2: Semantic search (uses fine-tuned model if available)
    if not medicine:
        try:
            from semantic_search import semantic_search as sem_search, is_semantic_available
            if is_semantic_available():
                results = sem_search(query, all_medicines, top_k=3, threshold=0.20)
                if results:
                    medicine = results[0]["medicine"]
                    print(f"[Search] Semantic match: '{query}' → {medicine.brand_name} "
                          f"(score={results[0]['score']:.2f})")
        except Exception as e:
            print(f"[Search] Semantic search failed: {e}")

    # Stage 3: FuzzyWuzzy fallback
    if not medicine:
        medicine = fuzzy_find_medicine(query, all_medicines, threshold=55)

    if not medicine:
        raise HTTPException(404, f"Medicine '{query}' not found. Try searching by symptom or condition.")

    results = db.query(PharmacyInventory, Pharmacy).join(
        Pharmacy, PharmacyInventory.pharmacy_id == Pharmacy.id
    ).filter(
        PharmacyInventory.medicine_id == medicine.id,
        PharmacyInventory.is_available == True,
        PharmacyInventory.stock_quantity > 0,
        Pharmacy.is_open == True
    ).all()

    available_at = []
    for inv, pharmacy in results:
        dist = calculate_distance(lat, lng, pharmacy.latitude, pharmacy.longitude)
        if dist <= radius_km:
            available_at.append({
                "pharmacy_id": pharmacy.id, "pharmacy_name": pharmacy.name,
                "pharmacy_address": pharmacy.address,
                "medicine_id": medicine.id, "medicine_name": medicine.brand_name,
                "price": inv.price, "stock": inv.stock_quantity,
                "is_prescription_required": medicine.is_prescription_required,
                "is_available": inv.is_available,
                "lat": pharmacy.latitude, "lng": pharmacy.longitude,
                "distance_km": dist, "phone": pharmacy.phone or "",
                "rating": pharmacy.rating or 0.0
            })

    available_at.sort(key=lambda x: x["distance_km"])
    available_at = available_at[:limit]

    # Apply medicine-specific intelligent ranking
    # Boosts stock weight since user is actively trying to buy
    available_at = rank_pharmacies_for_medicine(available_at, medicine.brand_name)

    return {
        "medicine": {
            "id":                    medicine.id,
            "name":                  medicine.brand_name,
            "generic_name":          medicine.generic_name or "",
            "strength":              medicine.strength or "",
            "form":                  medicine.form or "",
            "description":           medicine.description or "",
            "uses":                  getattr(medicine, 'uses', None) or medicine.description or "",
            "side_effects":          getattr(medicine, 'side_effects', None) or "",
            "image_url":             getattr(medicine, 'image_url', None) or "",
            "manufacturer":          medicine.manufacturer or "",
            "excellent_review_pct":  getattr(medicine, 'excellent_review_pct', 0) or 0,
            "average_review_pct":    getattr(medicine, 'average_review_pct',  0) or 0,
            "poor_review_pct":       getattr(medicine, 'poor_review_pct',     0) or 0,
        },
        "available_at": available_at,
        "total_pharmacies": len(available_at)
    }

# USER MEDICINES
@app.get("/my-medicines/{user_id}")
def get_user_medicines(user_id: int, db: Session = Depends(get_db)):
    if not db.query(User).filter(User.id == user_id).first():
        raise HTTPException(404, "User not found")
    medicines = db.query(UserMedicine).filter(
        UserMedicine.user_id == user_id, UserMedicine.is_active == True).all()
    result = []
    for m in medicines:
        detail = db.query(Medicine).filter(Medicine.id == m.medicine_id).first() \
                 if m.medicine_id else None
        result.append({"id": m.id, "medicine_id": m.medicine_id,
                       "medicineName": (detail.brand_name if detail else None) or m.custom_name or "Unknown",
                       "dosage": m.dosage or "", "frequency": m.frequency or "",
                       "stripsRemaining": m.strips_remaining or 0,
                       "tabletsPerStrip": getattr(m, 'tablets_per_strip', 10) or 10,
                       "reminderEnabled": m.reminder_enabled if m.reminder_enabled is not None else True,
                       "timeOfDay": m.time_of_day or "Morning"})
    return result

@app.post("/my-medicines")
def add_user_medicine(data: UserMedicineCreate, db: Session = Depends(get_db)):
    if not db.query(User).filter(User.id == data.user_id).first():
        raise HTTPException(404, "User not found")
    medicine = db.query(Medicine).filter(
        Medicine.brand_name.ilike(f"%{data.medicine_name}%")).first()
    new_med = UserMedicine(
        user_id=data.user_id,
        medicine_id=medicine.id if medicine else None,
        custom_name=data.medicine_name,
        dosage=data.dosage,
        frequency=data.frequency,
        strips_remaining=data.strips_remaining,
        reminder_enabled=data.reminder_enabled,
        tablets_per_strip=getattr(data, 'tablets_per_strip', 10) or 10,
        time_of_day=getattr(data, 'time_of_day', 'Morning'),
        is_active=True
    )
    db.add(new_med)
    db.commit()
    db.refresh(new_med)
    return {"success": True, "message": f"{data.medicine_name} added",
            "medicine": {"id": new_med.id, "medicine_name": data.medicine_name,
                         "dosage": new_med.dosage or "", "frequency": new_med.frequency or "",
                         "strips_remaining": new_med.strips_remaining or 0}}

@app.put("/my-medicines/{medicine_id}")
def update_user_medicine(medicine_id: int, data: UserMedicineUpdate,
                          db: Session = Depends(get_db)):
    m = db.query(UserMedicine).filter(UserMedicine.id == medicine_id).first()
    if not m: raise HTTPException(404, "Medicine not found")
    if data.dosage is not None: m.dosage = data.dosage
    if data.frequency is not None: m.frequency = data.frequency
    if data.strips_remaining is not None: m.strips_remaining = data.strips_remaining
    if data.reminder_enabled is not None: m.reminder_enabled = data.reminder_enabled
    db.commit()
    return {"success": True, "message": "Medicine updated"}

@app.delete("/my-medicines/{medicine_id}")
def delete_user_medicine(medicine_id: int, db: Session = Depends(get_db)):
    m = db.query(UserMedicine).filter(UserMedicine.id == medicine_id).first()
    if not m: raise HTTPException(404, "Medicine not found")
    m.is_active = False
    db.commit()
    return {"success": True, "message": "Medicine removed"}

# INVENTORY
@app.post("/inventory/update")
def update_inventory(data: InventoryUpdate, db: Session = Depends(get_db)):
    inv = db.query(PharmacyInventory).filter(
        PharmacyInventory.pharmacy_id == data.pharmacy_id,
        PharmacyInventory.medicine_id == data.medicine_id).first()
    if inv:
        inv.stock_quantity = data.stock_quantity
        inv.price = data.price
        inv.is_available = data.is_available
    else:
        inv = PharmacyInventory(pharmacy_id=data.pharmacy_id, medicine_id=data.medicine_id,
                                stock_quantity=data.stock_quantity, price=data.price,
                                is_available=data.is_available)
        db.add(inv)
    db.commit()
    return {"success": True, "message": "Inventory updated"}

# SUGGESTIONS
@app.get("/suggestions")
def get_suggestions(
    query: str,
    lat: float = 9.5916,
    lng: float = 76.5222,
    db: Session = Depends(get_db)
):
    """
    Live suggestions as user types — fires from 1 character.
    Priority: starts-with > contains > generic match > semantic.
    Returns up to 7 suggestions (medicines + pharmacies).
    """
    q = (query or "").strip()
    if not q:
        return {"suggestions": []}

    suggestions = []
    seen_ids    = set()

    def add_med(m, subtitle_override=None):
        if m.id not in seen_ids:
            suggestions.append({
                "text":     m.brand_name,
                "subtitle": subtitle_override or f"{m.strength or ''} · {m.form or ''}".strip(" ·"),
                "type":     "medicine",
                "id":       m.id
            })
            seen_ids.add(m.id)

    # ── 1. Starts-with match (fastest, most relevant) ──────────
    for m in db.query(Medicine).filter(
        Medicine.brand_name.ilike(f"{q}%")
    ).limit(5).all():
        add_med(m)

    # ── 2. Contains match ───────────────────────────────────────
    if len(suggestions) < 5:
        for m in db.query(Medicine).filter(
            Medicine.brand_name.ilike(f"%{q}%"),
            ~Medicine.brand_name.ilike(f"{q}%")
        ).limit(5 - len(suggestions)).all():
            add_med(m)

    # ── 3. Generic / composition match ─────────────────────────
    if len(suggestions) < 5:
        for m in db.query(Medicine).filter(
            Medicine.generic_name.ilike(f"%{q}%")
        ).limit(5 - len(suggestions)).all():
            add_med(m, subtitle_override=f"Generic: {m.generic_name}")

    # ── 4. Semantic match for symptom/condition queries ─────────
    # Fires when user types "headache", "fever", "cough", etc.
    if len(suggestions) < 5 and len(q) >= 3:
        try:
            from semantic_search import semantic_search as sem_search, is_semantic_available
            if is_semantic_available():
                all_meds = db.query(Medicine).all()
                sem_results = sem_search(q, all_meds, top_k=5 - len(suggestions), threshold=0.22)
                for r in sem_results:
                    m = r["medicine"]
                    if m.id not in seen_ids:
                        score_pct = int(r["score"] * 100)
                        add_med(m, subtitle_override=f"{m.form or 'Medicine'} · {m.generic_name or 'See details'}"[:60])
        except Exception:
            pass

    # ── 5. Nearby pharmacy name match ──────────────────────────
    if len(suggestions) < 7:
        pharmacies = db.query(Pharmacy).filter(
            Pharmacy.name.ilike(f"%{q}%"),
            Pharmacy.is_open == True
        ).limit(7 - len(suggestions)).all()
        for p in pharmacies:
            dist = calculate_distance(lat, lng, p.latitude, p.longitude)
            suggestions.append({
                "text":     p.name,
                "subtitle": f"{p.city} · {dist:.1f} km away",
                "type":     "pharmacy",
                "id":       p.id
            })

    return {"suggestions": suggestions[:7]}

@app.get("/recommendations/{user_id}")
def get_user_recommendations(user_id: int, db: Session = Depends(get_db)):
    """
    Collaborative filtering + comorbidity-based medicine recommendations.
    Returns: "Users like you also take..." suggestions.
    """
    all_user_medicines = db.query(UserMedicine).all()
    all_medicines = db.query(Medicine).all()
    recs = get_recommendations(
        user_id=user_id,
        all_user_medicines=all_user_medicines,
        all_medicines=all_medicines,
        top_k=5
    )
    return {
        "user_id": user_id,
        "recommendations": recs,
        "count": len(recs),
        "algorithm": "collaborative_filtering + comorbidity_rules"
    }


@app.get("/medicines/{medicine_id}/info")
def get_medicine_info(medicine_id: int, db: Session = Depends(get_db)):
    """
    Full medicine information page.
    Returns all dataset fields: uses, side effects, manufacturer,
    strength, form, reviews, prescription status, image, description.
    Used by the Medicine Info screen in the Android app.
    """
    m = db.query(Medicine).filter(Medicine.id == medicine_id).first()
    if not m:
        raise HTTPException(404, "Medicine not found")

    # Parse pipe-separated fields into clean lists
    def pipe_to_list(text):
        if not text:
            return []
        return [item.strip() for item in text.split("|") if item.strip()]

    uses_list        = pipe_to_list(m.uses)
    side_effects_list= pipe_to_list(m.side_effects)

    return {
        "id":                       m.id,
        "brand_name":               m.brand_name,
        "generic_name":             m.generic_name or "",
        "manufacturer":             m.manufacturer or "",
        "strength":                 m.strength or "",
        "form":                     m.form or "",
        "description":              m.description or "",
        "uses":                     uses_list,
        "side_effects":             side_effects_list,
        "uses_raw":                 m.uses or "",
        "side_effects_raw":         m.side_effects or "",
        "image_url":                m.image_url or "",
        "excellent_review_pct":     m.excellent_review_pct or 0,
        "average_review_pct":       m.average_review_pct or 0,
        "poor_review_pct":          m.poor_review_pct or 0,
        "is_prescription_required": m.is_prescription_required or False,
        "total_reviews":            (m.excellent_review_pct or 0) +
                                    (m.average_review_pct or 0) +
                                    (m.poor_review_pct or 0),
    }

@app.get("/medicines/{medicine_id}/also-taken")
def get_also_taken(medicine_id: int, db: Session = Depends(get_db)):
    """
    Item-based: 'Patients who take THIS medicine also take...'
    Shown on pharmacy bottom sheet / medicine detail.
    """
    all_user_medicines = db.query(UserMedicine).all()
    all_medicines = db.query(Medicine).all()
    results = get_also_bought(
        medicine_id=medicine_id,
        all_user_medicines=all_user_medicines,
        all_medicines=all_medicines,
        top_k=4
    )
    return {"medicine_id": medicine_id, "also_taken": results}

@app.get("/search/semantic")
def semantic_medicine_search(query: str, db: Session = Depends(get_db)):
    """
    Full semantic search endpoint.
    Handles symptom queries: "headache", "fever", "stomach pain", "sugar medicine"
    Returns ranked medicines with match_type: exact | symptom_map | semantic
    """
    all_meds = db.query(Medicine).all()
    results = semantic_search(query, all_meds, top_k=8)
    return {
        "query": query,
        "semantic_available": is_semantic_available(),
        "results": [
            {
                "id":           r["medicine"].id,
                "name":         r["medicine"].brand_name,
                "generic_name": r["medicine"].generic_name or "",
                "strength":     getattr(r["medicine"], "strength", "") or "",
                "type":         getattr(r["medicine"], "medicine_type", "") or "",
                "score":        round(r["score"], 3),
                "match_type":   r["match_type"],
                "is_prescription_required": r["medicine"].is_prescription_required,
            }
            for r in results
        ]
    }


# STATS
@app.get("/stats")
def get_stats(db: Session = Depends(get_db)):
    return {"total_users": db.query(User).count(),
            "total_pharmacies": db.query(Pharmacy).count(),
            "open_pharmacies": db.query(Pharmacy).filter(Pharmacy.is_open == True).count(),
            "total_medicines": db.query(Medicine).count(),
            "total_inventory_items": db.query(PharmacyInventory).filter(
                PharmacyInventory.is_available == True).count()}


# ── LSTM STOCK PREDICTION ─────────────────────────────────

@app.get("/ml/stock/predict")
def predict_stock_endpoint(
    pharmacy_id: int,
    medicine_id: int,
    db: Session = Depends(get_db)
):
    """
    Predict stock depletion for a specific pharmacy-medicine pair.
    Returns 7-day forecast + alert level.
    """
    from lstm_stock_predictor import predict_stock

    inv = db.query(PharmacyInventory).filter(
        PharmacyInventory.pharmacy_id == pharmacy_id,
        PharmacyInventory.medicine_id == medicine_id
    ).first()

    current_stock = inv.stock_quantity if inv else 0

    result = predict_stock(pharmacy_id, medicine_id, current_stock)
    result["pharmacy_id"]  = pharmacy_id
    result["medicine_id"]  = medicine_id
    return result


@app.get("/ml/stock/alerts")
def get_stock_alerts(db: Session = Depends(get_db)):
    """
    Get all low-stock alerts across all pharmacies.
    Used by pharmacy owner dashboard and admin panel.
    Returns only critical/warning items — not ok ones.
    """
    from lstm_stock_predictor import get_low_stock_alerts
    alerts = get_low_stock_alerts(db)
    return {
        "total_alerts": len(alerts),
        "critical": [a for a in alerts if a["alert_level"] == "critical"],
        "warning":  [a for a in alerts if a["alert_level"] == "warning"],
    }


@app.post("/ml/stock/train")
def train_lstm(db: Session = Depends(get_db)):
    """
    Trigger LSTM training. Run once after seeding data.
    Takes ~2 minutes on CPU. Saves model to ml_models/ folder.
    Call this from terminal: POST http://localhost:8000/ml/stock/train
    """
    from lstm_stock_predictor import generate_synthetic_history, train
    try:
        generate_synthetic_history(db)
        train(db)
        return {"success": True, "message": "LSTM trained and saved to ml_models/"}
    except Exception as e:
        raise HTTPException(500, f"Training failed: {str(e)}")

# This REPLACES the need for seed_data pharmacies.
# When Google Places key is available, returns REAL pharmacies.
# Falls back to database pharmacies if key is missing.

@app.get("/pharmacies/nearby")
def get_nearby_pharmacies_live(
    lat: float = 9.5916,
    lng: float = 76.5222,
    radius_km: float = 5.0,
    limit: int = 5,
    db: Session = Depends(get_db)
):
    """
    Tries Google Places API first, falls back to database.
    Returns fields matching Android Pharmacy data class exactly.
    Applies intelligent ranking to results from both sources.
    """
    from places_service import get_nearby_pharmacies_cached

    # ── Try Google Places ──
    if os.getenv("GOOGLE_PLACES_KEY"):
        try:
            pharmacies = get_nearby_pharmacies_cached(
                lat=lat, lng=lng,
                radius_meters=int(radius_km * 1000),
                limit=limit * 2  # fetch extra, ranking will pick best
            )
            if pharmacies:
                ranked = rank_pharmacies(pharmacies)
                print(f"[Places] Returning {len(ranked[:limit])} ranked real pharmacies")
                return {"source": "google_places", "pharmacies": ranked[:limit]}
        except Exception as e:
            print(f"[Places] ERROR: {e}")
            print(f"[Places] Falling back to seeded database. Check /debug/status for help.")

    # ── Fallback: seeded database ──
    all_pharmacies = db.query(Pharmacy).filter(Pharmacy.is_open == True).all()
    nearby = []
    for p in all_pharmacies:
        dist = calculate_distance(lat, lng, p.latitude, p.longitude)
        if dist <= radius_km:
            nearby.append(format_pharmacy(p, lat, lng))

    ranked = rank_pharmacies(nearby)
    return {"source": "database", "pharmacies": ranked[:limit]}


# ── MEDICINE RESERVATION / BOOKING ───────────────────────────────

class ReservationCreate(BaseModel):
    user_id: int
    pharmacy_id: int
    medicine_id: int
    quantity: int = 1
    notes: Optional[str] = None
    is_prescription_required: Optional[bool] = False

class CartItem(BaseModel):
    medicine_id: int
    quantity: int = 1
    is_prescription_required: bool = False

class MultiReservationCreate(BaseModel):
    user_id: int
    pharmacy_id: int
    items: List[CartItem]
    notes: Optional[str] = None


@app.post("/reservations")
def create_reservation(data: ReservationCreate, db: Session = Depends(get_db)):
    """
    Reserve a medicine at a pharmacy for pickup.
    Uses reserved_stock (not direct stock reduction) to support
    concurrent bookings safely. Row-level lock via with_for_update().
    Stock is only finally deducted when collected; cancelled orders
    release reserved_stock back immediately.
    """
    from sqlalchemy import select

    user = db.query(User).filter(User.id == data.user_id).first()
    if not user:
        raise HTTPException(404, "User not found")

    pharmacy = db.query(Pharmacy).filter(Pharmacy.id == data.pharmacy_id).first()
    if not pharmacy:
        raise HTTPException(404, "Pharmacy not found")

    medicine = db.query(Medicine).filter(Medicine.id == data.medicine_id).first()
    if not medicine:
        raise HTTPException(404, "Medicine not found")

    # Row-level lock to prevent concurrent over-booking
    inv = (
        db.query(PharmacyInventory)
        .filter(
            PharmacyInventory.pharmacy_id == data.pharmacy_id,
            PharmacyInventory.medicine_id == data.medicine_id,
        )
        .with_for_update()   # lock row for this transaction
        .first()
    )

    if not inv:
        raise HTTPException(400,
            f"{medicine.brand_name} is not stocked at {pharmacy.name}")

    # Available = physical stock minus what is already reserved
    reserved = inv.reserved_stock or 0
    available = inv.stock_quantity - reserved

    if available <= 0:
        raise HTTPException(400,
            f"{medicine.brand_name} is fully reserved at {pharmacy.name}. "
            "Try another pharmacy or check back later.")

    if available < data.quantity:
        raise HTTPException(400,
            f"Only {available} strip(s) of {medicine.brand_name} available "
            f"(stock: {inv.stock_quantity}, reserved: {reserved}). "
            f"You requested {data.quantity}.")

    import uuid
    from datetime import timedelta
    reservation_id = f"PF{uuid.uuid4().hex[:8].upper()}"

    # Hold stock: increment reserved_stock (do NOT reduce stock_quantity yet)
    inv.reserved_stock = reserved + data.quantity
    # Mark unavailable only if nothing left after this reservation
    inv.is_available = (inv.stock_quantity - inv.reserved_stock) > 0

    prescription_required = medicine.is_prescription_required or data.is_prescription_required
    total_price = round(inv.price * data.quantity, 2)
    expires_at  = datetime.utcnow() + timedelta(hours=24)

    db_reservation = Reservation(
        reservation_code         = reservation_id,
        user_id                  = data.user_id,
        pharmacy_id              = data.pharmacy_id,
        medicine_id              = data.medicine_id,
        quantity                 = data.quantity,
        unit_price               = inv.price,
        total_price              = total_price,
        notes                    = data.notes or "",
        status                   = "pending",
        is_prescription_required = prescription_required,
        expires_at               = expires_at,
    )
    db.add(db_reservation)
    db.commit()

    prescription_note = None
    if prescription_required:
        prescription_note = (
            f"⚕️ PRESCRIPTION REQUIRED: {medicine.brand_name} requires a valid "
            f"prescription. Bring it when collecting from {pharmacy.name}."
        )

    return {
        "success":               True,
        "reservation_id":        reservation_id,
        "message":               f"Reserved {data.quantity}x {medicine.brand_name} at {pharmacy.name}",
        "pickup_info": {
            "pharmacy_name":     pharmacy.name,
            "pharmacy_address":  pharmacy.address,
            "pharmacy_phone":    pharmacy.phone or "",
            "medicine_name":     medicine.brand_name,
            "strength":          medicine.strength or "",
            "quantity":          data.quantity,
            "price_per_unit":    inv.price,
            "total_price":       total_price,
            "reservation_id":    reservation_id,
            "status":            "confirmed",
            "expires_at":        expires_at.isoformat(),
            "note":              "Please collect within 24 hours. Pay at the pharmacy counter.",
        },
        "prescription_required": prescription_required,
        "prescription_reminder": prescription_note,
        "reservation_id":        reservation_id,
    }


@app.post("/reservations/multi")
def create_multi_reservation(data: MultiReservationCreate, db: Session = Depends(get_db)):
    """
    Reserve multiple medicines from the same pharmacy in one order.
    Creates one Reservation record per medicine under a shared order prefix.
    Stock is reduced atomically — if any item fails the whole order is rolled back.
    """
    from datetime import timedelta
    import uuid

    # Validate user and pharmacy once for all items
    user = db.query(User).filter(User.id == data.user_id).first()
    if not user:
        raise HTTPException(404, "User not found")

    pharmacy = db.query(Pharmacy).filter(Pharmacy.id == data.pharmacy_id).first()
    if not pharmacy:
        raise HTTPException(404, "Pharmacy not found")

    if not data.items:
        raise HTTPException(400, "Cart is empty")

    # ── Validate all items before touching stock ──────────────────
    # This ensures we either book everything or nothing
    order_prefix = f"PF{uuid.uuid4().hex[:6].upper()}"
    validated    = []
    total_order  = 0.0
    needs_rx     = False

    for cart_item in data.items:
        medicine = db.query(Medicine).filter(Medicine.id == cart_item.medicine_id).first()
        if not medicine:
            raise HTTPException(404, f"Medicine ID {cart_item.medicine_id} not found")

        inv = db.query(PharmacyInventory).filter(
            PharmacyInventory.pharmacy_id == data.pharmacy_id,
            PharmacyInventory.medicine_id == cart_item.medicine_id,
            PharmacyInventory.is_available == True
        ).first()

        if not inv:
            raise HTTPException(400,
                f"{medicine.brand_name} is not available at {pharmacy.name}")

        if inv.stock_quantity < cart_item.quantity:
            raise HTTPException(400,
                f"Only {inv.stock_quantity} strip(s) of {medicine.brand_name} "
                f"available. You requested {cart_item.quantity}.")

        item_total = round(inv.price * cart_item.quantity, 2)
        total_order += item_total
        rx = medicine.is_prescription_required or cart_item.is_prescription_required
        if rx:
            needs_rx = True

        validated.append({
            "medicine": medicine,
            "inv":      inv,
            "quantity": cart_item.quantity,
            "price":    inv.price,
            "total":    item_total,
            "rx":       rx,
        })

    # ── All valid — now create reservations and deduct stock ──────
    expires_at      = datetime.utcnow() + timedelta(hours=24)
    reservation_ids = []
    item_summaries  = []

    for i, v in enumerate(validated):
        res_code = f"{order_prefix}-{i+1:02d}"

        v["inv"].stock_quantity -= v["quantity"]
        if v["inv"].stock_quantity == 0:
            v["inv"].is_available = False

        db_res = Reservation(
            reservation_code         = res_code,
            user_id                  = data.user_id,
            pharmacy_id              = data.pharmacy_id,
            medicine_id              = v["medicine"].id,
            quantity                 = v["quantity"],
            unit_price               = v["price"],
            total_price              = v["total"],
            notes                    = data.notes or "",
            status                   = "pending",
            is_prescription_required = v["rx"],
            expires_at               = expires_at,
        )
        db.add(db_res)
        reservation_ids.append(res_code)
        item_summaries.append({
            "medicine_name": v["medicine"].brand_name,
            "strength":      v["medicine"].strength or "",
            "quantity":      v["quantity"],
            "unit_price":    v["price"],
            "total_price":   v["total"],
            "reservation_id": res_code,
            "rx_required":   v["rx"],
        })

    db.commit()

    rx_note = None
    if needs_rx:
        rx_names = [s["medicine_name"] for s in item_summaries if s["rx_required"]]
        rx_note = (f"⚕️ PRESCRIPTION REQUIRED for: {', '.join(rx_names)}. "
                   f"Please bring your doctor's prescription when collecting.")

    return {
        "success":         True,
        "order_id":        order_prefix,
        "reservation_ids": reservation_ids,
        "message":         f"Booked {len(validated)} medicine(s) at {pharmacy.name}",
        "items":           item_summaries,
        "total_amount":    round(total_order, 2),
        "pharmacy_name":   pharmacy.name,
        "pharmacy_address":pharmacy.address,
        "pharmacy_phone":  pharmacy.phone or "",
        "expires_at":      expires_at.isoformat(),
        "prescription_required": needs_rx,
        "prescription_reminder": rx_note,
    }

@app.get("/reservations/user/{user_id}")
def get_user_reservations(user_id: int, db: Session = Depends(get_db)):
    """Get all reservations for a user (active + history)."""
    reservations = db.query(Reservation).filter(
        Reservation.user_id == user_id
    ).order_by(Reservation.created_at.desc()).all()

    result = []
    for r in reservations:
        med  = db.query(Medicine).filter(Medicine.id == r.medicine_id).first()
        pharm= db.query(Pharmacy).filter(Pharmacy.id == r.pharmacy_id).first()
        # Auto-expire reservations older than 24h
        if r.status == "pending" and r.expires_at and datetime.utcnow() > r.expires_at:
            r.status = "expired"
            db.commit()
        result.append({
            "reservation_id":   r.reservation_code,
            "status":           r.status,
            "medicine_name":    med.brand_name if med else "Unknown",
            "pharmacy_name":    pharm.name if pharm else "Unknown",
            "quantity":         r.quantity,
            "total_price":      r.total_price,
            "created_at":       str(r.created_at),
            "expires_at":       str(r.expires_at) if r.expires_at else None,
            "prescription_required": r.is_prescription_required,
        })
    return {"user_id": user_id, "reservations": result, "count": len(result)}


@app.patch("/reservations/{reservation_code}/collect")
def mark_reservation_collected(reservation_code: str, db: Session = Depends(get_db)):
    """
    Mark reservation collected. NOW we finalize the stock deduction:
      - Reduce stock_quantity by reservation quantity
      - Release reserved_stock (no longer held)
    """
    r = db.query(Reservation).filter(
        Reservation.reservation_code == reservation_code
    ).first()
    if not r:
        raise HTTPException(404, "Reservation not found")
    if r.status != "pending":
        raise HTTPException(400, f"Reservation is already {r.status}")

    inv = (
        db.query(PharmacyInventory)
        .filter(
            PharmacyInventory.pharmacy_id == r.pharmacy_id,
            PharmacyInventory.medicine_id == r.medicine_id,
        )
        .with_for_update()
        .first()
    )
    if inv:
        # Final stock deduction on actual collection
        inv.stock_quantity = max(0, inv.stock_quantity - r.quantity)
        inv.reserved_stock = max(0, (inv.reserved_stock or 0) - r.quantity)
        inv.is_available   = inv.stock_quantity > 0

    r.status       = "collected"
    r.collected_at = datetime.utcnow()
    db.commit()
    return {"success": True, "reservation_id": reservation_code, "status": "collected"}


@app.get("/pharmacies/{pharmacy_id}/reservations")
def get_pharmacy_reservations(
    pharmacy_id: int,
    status: str = None,
    db: Session = Depends(get_db)
):
    """Get all reservations for a pharmacy (owner dashboard)."""
    query = db.query(Reservation).filter(Reservation.pharmacy_id == pharmacy_id)
    if status:
        query = query.filter(Reservation.status == status)
    reservations = query.order_by(Reservation.created_at.desc()).all()

    result = []
    for r in reservations:
        med   = db.query(Medicine).filter(Medicine.id == r.medicine_id).first()
        user  = db.query(User).filter(User.id == r.user_id).first()
        # Auto-expire
        if r.status == "pending" and r.expires_at and datetime.utcnow() > r.expires_at:
            r.status = "expired"
            db.commit()
        result.append({
            "reservation_id":   r.reservation_code,
            "medicine_name":    med.brand_name if med else "Unknown",
            "medicine_id":      r.medicine_id,
            "quantity":         r.quantity,
            "total_price":      r.total_price,
            "status":           r.status,
            "patient_name":     user.name if user else "Unknown",
            "patient_phone":    user.phone if user else "",
            "patient_id":       r.user_id,
            "created_at":       r.created_at.isoformat() if r.created_at else None,
            "expires_at":       r.expires_at.isoformat() if r.expires_at else None,
            "notes":            r.notes or "",
        })
    return {"reservations": result, "total": len(result)}


@app.delete("/reservations/{reservation_code}")
def cancel_reservation(reservation_code: str, db: Session = Depends(get_db)):
    """Cancel a reservation and release reserved_stock (not add to stock_quantity)."""
    r = db.query(Reservation).filter(
        Reservation.reservation_code == reservation_code
    ).first()
    if not r:
        raise HTTPException(404, "Reservation not found")
    if r.status not in ("pending",):
        raise HTTPException(400, f"Cannot cancel a {r.status} reservation")

    # Release reserved stock — do NOT inflate stock_quantity
    inv = (
        db.query(PharmacyInventory)
        .filter(
            PharmacyInventory.pharmacy_id == r.pharmacy_id,
            PharmacyInventory.medicine_id == r.medicine_id,
        )
        .with_for_update()
        .first()
    )
    if inv:
        inv.reserved_stock = max(0, (inv.reserved_stock or 0) - r.quantity)
        # Re-open availability if stock is available again
        inv.is_available = (inv.stock_quantity - inv.reserved_stock) > 0

    r.status = "cancelled"
    db.commit()
    return {"success": True, "reservation_id": reservation_code, "status": "cancelled"}


@app.get("/medicines/{medicine_id}/prescription-info")
def get_prescription_info(medicine_id: int, db: Session = Depends(get_db)):
    """
    Check if a medicine requires a prescription.
    Returns a clear warning message if so.
    Called when user taps a medicine in search results.
    """
    medicine = db.query(Medicine).filter(Medicine.id == medicine_id).first()
    if not medicine:
        raise HTTPException(404, "Medicine not found")

    if medicine.is_prescription_required:
        return {
            "medicine_id":             medicine.id,
            "medicine_name":           medicine.brand_name,
            "is_prescription_required": True,
            "warning_title":           "Prescription Required",
            "warning_message": (
                f"{medicine.brand_name} ({medicine.generic_name or ''}) "
                f"is a prescription-only medicine. You will need to present "
                f"a valid prescription or doctor's note at the pharmacy counter. "
                f"Dispensing without a prescription is not permitted."
            ),
            "reminder_items": [
                "✅ Doctor's prescription (original)",
                "✅ Valid photo ID",
                "✅ Insurance card (if applicable)",
            ]
        }
    else:
        return {
            "medicine_id":              medicine.id,
            "medicine_name":            medicine.brand_name,
            "is_prescription_required": False,
            "warning_title":            None,
            "warning_message":          None,
        }


# ── DIRECTIONS API ────────────────────────────────────────────────

@app.get("/directions")
def get_directions(
    origin_lat:  float,
    origin_lng:  float,
    dest_lat:    float,
    dest_lng:    float,
    mode:        str = "driving"
):
    """
    Proxy Google Directions API — returns encoded polyline + turn-by-turn steps.
    Android decodes the polyline using Maps Utils PolyUtil.decode().

    If no Google key is set, returns null so Android falls back to
    opening Google Maps app directly.
    """
    from directions_service import get_route

    route = get_route(origin_lat, origin_lng, dest_lat, dest_lng, mode)
    if not route:
        return {"route": None, "fallback": True,
                "message": "Directions unavailable — open in Google Maps"}
    return {"route": route, "fallback": False}


# ── MEDICATION HISTORY ────────────────────────────────────────────

from models import MedicationHistory as MedicationHistoryModel

class HistoryEventCreate(BaseModel):
    user_id: int
    medicine_name: str
    event_type: str
    strips_remaining: int = 0
    dosage: Optional[str] = None
    frequency: Optional[str] = None
    notes: Optional[str] = None
    pharmacy_name: Optional[str] = None
    medicine_id: Optional[int] = None


@app.post("/history")
def add_history_event(data: HistoryEventCreate, db: Session = Depends(get_db)):
    """Log a medication history event (started/refilled/stopped/updated/reserved)."""
    event = MedicationHistoryModel(
        user_id           = data.user_id,
        medicine_id       = data.medicine_id,
        medicine_name     = data.medicine_name,
        event_type        = data.event_type,
        strips_remaining = data.strips_remaining,
        dosage            = data.dosage,
        frequency         = data.frequency,
        notes             = data.notes,
        pharmacy_name     = data.pharmacy_name,
    )
    db.add(event)
    db.commit()
    db.refresh(event)
    return {"success": True, "event_id": event.id}


@app.get("/history/{user_id}")
def get_history(user_id: int, db: Session = Depends(get_db)):
    """
    Full medication history grouped by medicine.
    Includes refill predictions + interaction warnings.
    """
    from medication_history import predict_refill_date, check_interactions, calculate_adherence

    events = db.query(MedicationHistoryModel).filter(
        MedicationHistoryModel.user_id == user_id
    ).order_by(MedicationHistoryModel.event_date.desc()).all()

    grouped: dict = {}
    for e in events:
        name = e.medicine_name
        if name not in grouped:
            grouped[name] = []
        grouped[name].append({
            "id": e.id, "event_type": e.event_type,
            "strips_remaining": e.strips_remaining,
            "dosage": e.dosage or "", "frequency": e.frequency or "",
            "notes": e.notes or "", "pharmacy_name": e.pharmacy_name or "",
            "event_date": e.event_date.isoformat() if e.event_date else "",
        })

    medicines = []
    all_names = list(grouped.keys())

    for name, history in grouped.items():
        latest   = history[0]
        refill   = predict_refill_date(history)
        adherence = calculate_adherence(history)
        medicines.append({
            "medicine_name":     name,
            "current_tablets":   latest["strips_remaining"],
            "last_event":        latest["event_type"],
            "last_event_date":   latest["event_date"],
            "dosage":            latest["dosage"],
            "frequency":         latest["frequency"],
            "history":           history,
            "refill_prediction": refill,
            "adherence":         adherence,
        })

    medicines.sort(key=lambda x: (
        x["refill_prediction"]["days_until_refill"] if x["refill_prediction"] else 999
    ))

    interactions = check_interactions(all_names)

    return {
        "user_id": user_id, "medicines": medicines,
        "interactions": interactions, "total_events": len(events),
        "summary": {
            "total_medicines": len(medicines),
            "needs_refill_soon": sum(
                1 for m in medicines
                if m["refill_prediction"]
                and m["refill_prediction"]["days_until_refill"] <= 7
            ),
            "interaction_warnings": len(interactions),
        }
    }


@app.get("/history/{user_id}/timeline")
def get_timeline(user_id: int, db: Session = Depends(get_db)):
    """Flat chronological timeline — for the timeline tab."""
    events = db.query(MedicationHistoryModel).filter(
        MedicationHistoryModel.user_id == user_id
    ).order_by(MedicationHistoryModel.event_date.desc()).limit(50).all()

    return {"timeline": [
        {"id": e.id, "medicine_name": e.medicine_name,
         "event_type": e.event_type,
         "event_date": e.event_date.isoformat() if e.event_date else "",
         "tablets": e.strips_remaining, "pharmacy": e.pharmacy_name or "",
         "notes": e.notes or ""}
        for e in events
    ]}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)