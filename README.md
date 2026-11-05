# 💊 PharmaFind

**AI-powered medicine availability and pharmacy finder for Kerala, India.**

Patients search for a medicine by brand name, generic name, symptom, or even a misspelling, see which nearby pharmacies stock it, and reserve it for pickup. Pharmacy owners manage inventory and get stock forecasts and low-stock alerts.

`Kotlin (Android)` · `FastAPI (Python)` · `PostgreSQL / SQLite` · `PyTorch` · `sentence-transformers`

---

## ✨ Features

### For patients
- **3-stage AI medicine search:** exact match → fine-tuned semantic search (understands "cough with mucus") → fuzzy fallback (handles "paracetmol")
- **Multi-medicine search:** type `Paracetamol, Metformin` to find pharmacies that stock all of them
- **Live suggestions** with 250 ms debounce
- **Nearby pharmacy map** (Google Maps) with colour-coded availability markers and ML-ranked results
- **Pickup reservations** with a unique code (`PF-XXXXXXXX`), 24-hour hold, and live expiry countdown
- **Multi-medicine cart** with atomic all-or-nothing booking from a single pharmacy
- **Medicine Info screen:** uses, side effects, manufacturer, patient reviews, and prescription status for 11,825 medicines
- **My Medicines cabinet** with custom reminder times, refill tracking, and support for any medicine name
- **Dose and refill reminders** (exact alarms) and **geofence alerts** when you are near a pharmacy that stocks your medicines
- **In-app navigation** with turn-by-turn directions

### For pharmacy owners
- Inventory dashboard with inline ±10 / ±50 stock controls and preset quantity buttons
- **LSTM stock forecast** (7-day depletion prediction, bidirectional LSTM with attention)
- Reservation management (view, collect, cancel)
- Daily 9 AM low-stock notification

---

## 🗂️ Repository structure

```
pharmafind/
├── android/                  # Android app (Kotlin, XML layouts)
│   ├── app/src/main/java/com/example/pharmafind/   # Activities, adapters, API client
│   ├── app/src/main/res/                           # Layouts, drawables, values
│   └── build.gradle.kts
├── backend/                  # FastAPI backend
│   ├── main.py               # REST endpoints
│   ├── models.py             # SQLAlchemy models (7 tables)
│   ├── database.py           # Engine + session (SQLite / PostgreSQL)
│   ├── semantic_search.py    # Fine-tuned sentence-transformer search
│   ├── fuzzy_search.py       # FuzzyWuzzy typo-tolerant search
│   ├── ml_ranking.py         # 5-factor pharmacy ranking
│   ├── collaborative_filter.py
│   ├── lstm_stock_predictor.py
│   ├── seed_data.py          # 103 Kerala pharmacies
│   ├── load_medicine_dataset.py   # 11,825 medicines from 1mg
│   ├── train_semantic_model.py
│   ├── requirements.txt
│   └── railway.json
├── .gitignore
└── README.md
```

---

## 🏗️ Architecture

```
┌───────────────────┐   HTTPS / JSON   ┌───────────────────────────────┐
│  Android client   │ ───────────────► │  FastAPI backend              │
│  Kotlin + Retrofit│ ◄─────────────── │  JWT auth · REST endpoints    │
│  Google Maps SDK  │                  │  ┌─────────────────────────┐  │
│  AlarmManager     │                  │  │ ML: semantic · fuzzy ·  │  │
│  Geofencing       │                  │  │ ranking · CF · LSTM     │  │
└───────────────────┘                  │  └─────────────────────────┘  │
                                       └──────────────┬────────────────┘
                                                      │ SQLAlchemy
                                           SQLite (dev) / PostgreSQL (prod)
```

---

## 🚀 Getting started

### Prerequisites
- Python 3.11+
- Android Studio (latest stable) with an emulator or a physical device
- A Google Maps API key ([Google Cloud Console](https://console.cloud.google.com) → Maps SDK for Android; Places API optional)
- Git

### 1. Clone

```bash
git clone https://github.com/layanabinu/pharmafind.git
cd pharmafind
```

### 2. Backend

```bash
cd backend
python -m venv venv
venv\Scripts\activate          # Windows
# source venv/bin/activate     # macOS / Linux

pip install -r requirements.txt
```

Create `backend/.env`:

```env
# Leave DATABASE_URL out to use local SQLite (pharmafind.db)
DATABASE_URL=postgresql://user:password@host:5432/dbname

# Optional: enables live Google Places pharmacy search
# (the app falls back to the seeded database without it)
GOOGLE_PLACES_KEY=YOUR_KEY_HERE

# Use a long random string
JWT_SECRET=change-this-to-a-long-random-secret
```

Set up the data (first run only):

```bash
python migrate_add_medicine_dataset_fields.py
python load_medicine_dataset.py --csv Medicine_Details.csv
python seed_data.py
python train_semantic_model.py       # ~30 min on CPU; creates ml_models/
```

> `Medicine_Details.csv` is the 1mg medicine dataset and is not included in the repo. Place it in `backend/` before loading.
> `ml_models/` is gitignored (large files). If it is missing, search falls back to fuzzy matching.
> The `reserved_stock` column is added automatically on server startup.

Run the server:

```bash
uvicorn main:app --reload
```

- API: `http://localhost:8000`
- Interactive docs: `http://localhost:8000/docs`

### 3. Android

1. Open the `android/` folder in Android Studio.
2. Create (or edit) `android/local.properties` and add your Maps key:
   ```properties
   sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
   MAPS_API_KEY=YOUR_GOOGLE_MAPS_KEY
   ```
   This file is gitignored and the key is injected into the manifest at build time.
3. Choose the backend in `ApiClient.kt`:
   - **Local:** `IS_PRODUCTION = false`, then set `DEV_IP` to your PC's IPv4 address (`ipconfig`). The phone and PC must be on the same Wi-Fi.
   - **Deployed:** `IS_PRODUCTION = true` and set `PRODUCTION_URL` to your Railway URL.
4. Sync Gradle, then Run.

---

## 🔐 Environment variables

| Where | Variable | Purpose |
|---|---|---|
| `backend/.env` / Railway | `DATABASE_URL` | PostgreSQL connection string (defaults to SQLite) |
| `backend/.env` / Railway | `JWT_SECRET` | Signs login tokens |
| `backend/.env` / Railway | `GOOGLE_PLACES_KEY` | Optional live pharmacy search |
| `android/local.properties` | `MAPS_API_KEY` | Google Maps SDK key |

Secrets are never committed. `.env`, `local.properties`, databases, keystores, and ML weights are all in `.gitignore`.

---

## ☁️ Deployment (Railway)

1. Create a Railway project → **Deploy from GitHub repo** → select this repo.
2. Set **Root Directory** to `/backend`.
3. Add a PostgreSQL plugin and set the variables above.
4. Deploy, then check `https://<your-app>.up.railway.app/docs`.
5. Upload `ml_models/` to a persistent volume, or retrain on the server, to enable semantic search.

---

## 🧪 Demo accounts

Seeded for local testing only. Change or remove them before any real deployment.

| Role | Email | Password |
|---|---|---|
| Patient | `patient@test.com` | `password123` |
| Pharmacy owner | `pharmacy@test.com` | `pharmacy123` |
| Admin | `admin@pharmafind.com` | `admin123` |

---

## 🧠 ML modules

| Module | Technique |
|---|---|
| `semantic_search.py` | `all-MiniLM-L6-v2` fine-tuned on 68,153 pairs (contrastive loss 2.38 → 0.087), vectorised cosine similarity over 11,825 embeddings |
| `fuzzy_search.py` | FuzzyWuzzy `token_set_ratio`, threshold 60 |
| `ml_ranking.py` | 5-factor weighted score: distance, stock, rating, open status, price |
| `collaborative_filter.py` | User-based collaborative filtering + comorbidity rules |
| `lstm_stock_predictor.py` | Bidirectional 3-layer LSTM with attention and temporal features, 7-day forecast |

---

## 🛠️ Tech stack

| Layer | Technology |
|---|---|
| Android | Kotlin, XML layouts, Retrofit2, Google Maps SDK, Play Services (Location, Geofencing), AlarmManager |
| Backend | FastAPI, Uvicorn, SQLAlchemy 2.0, Pydantic, python-jose, passlib/bcrypt |
| Database | SQLite (development), PostgreSQL (production) |
| ML | PyTorch, sentence-transformers, FuzzyWuzzy, NumPy |
| Hosting | Railway |

---

## 📊 Project at a glance

- 38 REST endpoints, 7 database tables
- 5 ML modules
- 11,825 medicines (1mg dataset), 103 pharmacies across 15 Kerala cities
- Android API 26+ (Android 8.0 and above)

---

## 🗺️ Roadmap

- Payment gateway integration
- Home delivery
- Multi-branch pharmacy owner accounts
- Prescription upload and verification
- iOS client (the REST API is platform-agnostic)

---

## 👤 Author

**Layana Binu** · [@layanabinu](https://github.com/layanabinu)

> ⚠️ PharmaFind provides general medicine information for reference only. It is not a substitute for professional medical advice. Always follow your doctor's instructions.
