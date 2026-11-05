from database import SessionLocal, engine, Base
from models import User, Pharmacy, Medicine, PharmacyInventory
from passlib.context import CryptContext

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def seed_database():
    print("\n" + "="*60)
    print("📦 PHARMAFIND DATABASE SETUP — 122 PHARMACIES")
    print("="*60)
    Base.metadata.create_all(bind=engine)
    print("✅ Tables created!")

    db = SessionLocal()

    try:
        if db.query(User).count() > 0:
            print("\n⚠️  Database already has data!")
            print("To reset: delete pharmafind.db and run again")
            return

        # ==================
        # USERS
        # ==================
        print("\n👤 Creating users...")
        users = [
            User(email="patient@test.com",
                 password_hash=pwd_context.hash("password123"),
                 name="Layana Binu", role="patient",
                 phone="9400000001", is_active=True, is_verified=True),
            User(email="pharmacy@test.com",
                 password_hash=pwd_context.hash("pharmacy123"),
                 name="Pharmacy Owner", role="pharmacy",
                 phone="9400000002", is_active=True, is_verified=True),
            User(email="admin@pharmafind.com",
                 password_hash=pwd_context.hash("admin123"),
                 name="Admin User", role="admin",
                 phone="9400000003", is_active=True, is_verified=True),
        ]
        db.add_all(users)
        db.commit()
        print("✅ 3 users created!")

        # ==================
        # MEDICINES (20 medicines)
        # ==================
        print("\n💊 Creating medicines...")
        medicines_data = [
            # Common OTC
            ("MED001", "Paracetamol",    "Acetaminophen",        "GSK",          "500mg",  "Tablet",  "Pain reliever and fever reducer",           False),
            ("MED002", "Aspirin",         "Acetylsalicylic Acid", "Bayer",        "75mg",   "Tablet",  "Blood thinner and pain reliever",           False),
            ("MED003", "Cetirizine",      "Cetirizine HCl",       "UCB",          "10mg",   "Tablet",  "Antihistamine for allergies",               False),
            ("MED004", "Omeprazole",      "Omeprazole",           "AstraZeneca",  "20mg",   "Capsule", "Acid reflux and stomach ulcer treatment",   False),
            ("MED005", "Pantoprazole",    "Pantoprazole Sodium",  "Wyeth",        "40mg",   "Tablet",  "Proton pump inhibitor",                     False),
            ("MED006", "Ibuprofen",       "Ibuprofen",            "Abbott",       "400mg",  "Tablet",  "Anti-inflammatory pain reliever",           False),
            ("MED007", "Vitamin C",       "Ascorbic Acid",        "Himalaya",     "500mg",  "Tablet",  "Immunity booster",                          False),
            ("MED008", "Antacid",         "Magnesium Hydroxide",  "Cipla",        "400mg",  "Tablet",  "Fast relief from acidity",                  False),
            # Prescription
            ("MED009", "Metformin",       "Metformin HCl",        "Sun Pharma",   "500mg",  "Tablet",  "Diabetes management",                       True),
            ("MED010", "Amlodipine",      "Amlodipine Besylate",  "Cipla",        "5mg",    "Tablet",  "Blood pressure medication",                 True),
            ("MED011", "Amoxicillin",     "Amoxicillin Trihydrate","Cipla",       "250mg",  "Capsule", "Antibiotic for bacterial infections",        True),
            ("MED012", "Azithromycin",    "Azithromycin",         "Pfizer",       "500mg",  "Tablet",  "Antibiotic for respiratory infections",      True),
            ("MED013", "Atorvastatin",    "Atorvastatin Calcium", "Pfizer",       "10mg",   "Tablet",  "Cholesterol lowering medication",           True),
            ("MED014", "Metoprolol",      "Metoprolol Tartrate",  "Sun Pharma",   "50mg",   "Tablet",  "Beta blocker for heart conditions",         True),
            ("MED015", "Losartan",        "Losartan Potassium",   "Cipla",        "50mg",   "Tablet",  "Angiotensin receptor blocker",              True),
            ("MED016", "Glimepiride",     "Glimepiride",          "Sanofi",       "2mg",    "Tablet",  "Type 2 diabetes treatment",                 True),
            ("MED017", "Levothyroxine",   "Levothyroxine Sodium", "Abbott",       "50mcg",  "Tablet",  "Thyroid hormone replacement",               True),
            ("MED018", "Clopidogrel",     "Clopidogrel Bisulfate","Sanofi",       "75mg",   "Tablet",  "Antiplatelet medication",                   True),
            # Syrups / Topical
            ("MED019", "Ambroxol Syrup",  "Ambroxol HCl",         "Boehringer",   "30mg/5ml","Syrup",  "Cough expectorant",                         False),
            ("MED020", "ORS Sachet",      "Oral Rehydration Salts","WHO Formula", "21.8g",  "Sachet",  "Dehydration treatment",                     False),
        ]
        medicines = []
        for d in medicines_data:
            medicines.append(Medicine(
                medicine_id=d[0], brand_name=d[1], generic_name=d[2],
                manufacturer=d[3], strength=d[4], form=d[5],
                description=d[6], is_prescription_required=d[7]
            ))
        db.add_all(medicines)
        db.commit()
        print(f"✅ {len(medicines)} medicines created!")

        # ==================
        # PHARMACIES — 100 total
        # 60 in Kottayam district + 40 in surrounding districts
        # ==================
        print("\n🏥 Creating 100 pharmacies...")

        pharmacies_data = [

            # ── KOTTAYAM TOWN (20) ──────────────────────────────────────────
            ("Metro Medicals",            "KK Road, Kottayam Town",              "Kottayam", 9.5916, 76.5222, "9400100001", True,  4.7),
            ("City Pharmacy",             "Baker Junction, Kottayam",            "Kottayam", 9.5930, 76.5240, "9400100002", True,  4.5),
            ("Apollo Pharmacy Kottayam",  "MC Road, Kottayam",                   "Kottayam", 9.5880, 76.5200, "9400100003", True,  4.8),
            ("MedPlus KK Road",           "KK Road, Near KSRTC",                 "Kottayam", 9.5945, 76.5210, "9400100004", True,  4.3),
            ("HealthPlus Collectorate",   "Collectorate Junction, Kottayam",     "Kottayam", 9.5900, 76.5260, "9400100005", True,  4.5),
            ("Nagampadom Medicals",       "Nagampadom, Kottayam",                "Kottayam", 9.5950, 76.5180, "9400100006", True,  4.2),
            ("Shalom Pharmacy",           "Shastri Road, Kottayam",              "Kottayam", 9.5870, 76.5230, "9400100007", True,  4.6),
            ("Grace Medical Store",       "TB Road, Kottayam",                   "Kottayam", 9.5960, 76.5250, "9400100008", True,  4.4),
            ("Medicity Pharmacy",         "Manorama Junction, Kottayam",         "Kottayam", 9.5890, 76.5195, "9400100009", True,  4.7),
            ("Sunrise Medicals",          "Civil Station Road, Kottayam",        "Kottayam", 9.5910, 76.5270, "9400100010", True,  4.3),
            ("New Life Pharmacy",         "Gandhi Nagar, Kottayam",              "Kottayam", 9.5935, 76.5185, "9400100011", True,  4.1),
            ("Kuriakose Medicals",        "Bishop Jerome Nagar, Kottayam",       "Kottayam", 9.5875, 76.5245, "9400100012", True,  4.4),
            ("Star Pharmacy",             "Chingavanam Road, Kottayam",          "Kottayam", 9.5855, 76.5215, "9400100013", True,  4.2),
            ("Jeevan Medicals",           "Palai Road Junction, Kottayam",       "Kottayam", 9.5965, 76.5235, "9400100014", True,  4.5),
            ("KMC Pharmacy",              "Kottayam Medical College, Gandhinagar","Kottayam",9.5840, 76.5175, "9400100015", True,  4.8),
            ("Biji Medicals",             "Velloor Road, Kottayam",              "Kottayam", 9.5925, 76.5265, "9400100016", True,  4.0),
            ("SN Pharmacy",               "SN Junction, Kottayam",               "Kottayam", 9.5905, 76.5190, "9400100017", True,  4.3),
            ("Nirmala Medicals",          "Nirmala College Road, Kottayam",      "Kottayam", 9.5895, 76.5280, "9400100018", False, 3.9),
            ("Theresa Medical Store",     "Park Avenue, Kottayam",               "Kottayam", 9.5860, 76.5255, "9400100019", True,  4.6),
            ("FM Pharmacy",               "Film Nagar, Kottayam",                "Kottayam", 9.5940, 76.5205, "9400100020", True,  4.2),

            # ── KOTTAYAM DISTRICT — NEARBY TOWNS (40) ───────────────────────
            # Changanacherry (8)
            ("Changanacherry Medicals",   "Main Road, Changanacherry",           "Changanacherry", 9.4435, 76.5396, "9400200001", True,  4.5),
            ("Holy Cross Pharmacy",       "Hospital Road, Changanacherry",       "Changanacherry", 9.4450, 76.5410, "9400200002", True,  4.3),
            ("Karuna Medical Store",      "Tamaramoodu Junction, Changanacherry","Changanacherry", 9.4420, 76.5380, "9400200003", True,  4.1),
            ("SR Medicals",               "Bypass Road, Changanacherry",         "Changanacherry", 9.4460, 76.5420, "9400200004", True,  4.4),
            ("Medicare Changanacherry",   "Ponkunnam Road, Changanacherry",      "Changanacherry", 9.4410, 76.5365, "9400200005", True,  4.2),
            ("Vijaya Pharmacy",           "Church Road, Changanacherry",         "Changanacherry", 9.4445, 76.5405, "9400200006", False, 3.8),
            ("Leo Medicals",              "St. Joseph Street, Changanacherry",   "Changanacherry", 9.4430, 76.5390, "9400200007", True,  4.0),
            ("MedPlus Changanacherry",    "Town Hall Road, Changanacherry",      "Changanacherry", 9.4455, 76.5415, "9400200008", True,  4.6),
            # Ettumanoor (5)
            ("Ettumanoor Medicals",       "Temple Road, Ettumanoor",             "Ettumanoor", 9.6680, 76.5590, "9400300001", True,  4.1),
            ("Devi Pharmacy",             "Bypass Junction, Ettumanoor",         "Ettumanoor", 9.6695, 76.5605, "9400300002", True,  4.3),
            ("Vishnu Medicals",           "Market Road, Ettumanoor",             "Ettumanoor", 9.6665, 76.5575, "9400300003", True,  3.9),
            ("SP Pharmacy",               "Hospital Junction, Ettumanoor",       "Ettumanoor", 9.6705, 76.5615, "9400300004", True,  4.2),
            ("Town Medicals Ettumanoor",  "Main Bazaar, Ettumanoor",             "Ettumanoor", 9.6675, 76.5595, "9400300005", False, 3.7),
            # Vaikom (5)
            ("Vaikom Medical Store",      "Boat Jetty Road, Vaikom",             "Vaikom", 9.7530, 76.3900, "9400400001", True,  4.0),
            ("Lake View Pharmacy",        "Temple Road, Vaikom",                 "Vaikom", 9.7545, 76.3915, "9400400002", True,  4.2),
            ("Aswathy Medicals",          "Ferry Road, Vaikom",                  "Vaikom", 9.7515, 76.3885, "9400400003", True,  3.8),
            ("SV Pharmacy Vaikom",        "Town Square, Vaikom",                 "Vaikom", 9.7555, 76.3925, "9400400004", True,  4.1),
            ("Kairali Medicals",          "Market Street, Vaikom",               "Vaikom", 9.7525, 76.3895, "9400400005", False, 3.6),
            # Pala (5)
            ("Pala Medical Store",        "Church Road, Pala",                   "Pala", 9.7154, 76.6882, "9400500001", True,  4.3),
            ("Joseph Pharmacy",           "Town Hall Junction, Pala",            "Pala", 9.7168, 76.6896, "9400500002", True,  4.1),
            ("Good Health Pharmacy Pala", "Bypass Road, Pala",                   "Pala", 9.7140, 76.6868, "9400500003", True,  4.4),
            ("Carmel Medicals",           "College Road, Pala",                  "Pala", 9.7178, 76.6906, "9400500004", True,  4.0),
            ("Pala City Pharmacy",        "Market Road, Pala",                   "Pala", 9.7158, 76.6887, "9400500005", True,  3.9),
            # Kanjirappally (5)
            ("Kanjirappally Medicals",    "Town Road, Kanjirappally",            "Kanjirappally", 9.5580, 76.7810, "9400600001", True,  4.3),
            ("Hills Pharmacy",            "High Range Road, Kanjirappally",      "Kanjirappally", 9.5595, 76.7825, "9400600002", True,  4.1),
            ("Divine Medicals",           "Church Junction, Kanjirappally",      "Kanjirappally", 9.5565, 76.7795, "9400600003", True,  4.5),
            ("KJ Medical Store",          "Bypass, Kanjirappally",               "Kanjirappally", 9.5605, 76.7835, "9400600004", True,  4.0),
            ("Goodwill Pharmacy",         "Market Road, Kanjirappally",          "Kanjirappally", 9.5575, 76.7805, "9400600005", False, 3.8),
            # Erattupetta (5)
            ("Erattupetta Medicals",      "Market Road, Erattupetta",            "Erattupetta", 9.6840, 76.7810, "9400700001", True,  4.2),
            ("Green Cross Pharmacy",      "Temple Junction, Erattupetta",        "Erattupetta", 9.6855, 76.7825, "9400700002", True,  4.0),
            ("SR Medical Store",          "Bypass, Erattupetta",                 "Erattupetta", 9.6825, 76.7795, "9400700003", True,  3.9),
            ("Alpine Pharmacy",           "Hospital Road, Erattupetta",          "Erattupetta", 9.6865, 76.7835, "9400700004", True,  4.3),
            ("Marian Medicals",           "College Road, Erattupetta",           "Erattupetta", 9.6845, 76.7815, "9400700005", True,  4.1),
            # Thiruvalla (5) — southern edge of Kottayam dist
            ("Thiruvalla Medicals",       "MC Road, Thiruvalla",                 "Thiruvalla", 9.3847, 76.5733, "9400800001", True,  4.4),
            ("Holy Spirit Pharmacy",      "Chungam, Thiruvalla",                 "Thiruvalla", 9.3862, 76.5748, "9400800002", True,  4.2),
            ("Mar Thoma Medicals",        "Kozhencherry Road, Thiruvalla",       "Thiruvalla", 9.3832, 76.5718, "9400800003", True,  4.5),
            ("City Care Pharmacy",        "Town Junction, Thiruvalla",           "Thiruvalla", 9.3872, 76.5758, "9400800004", True,  4.1),
            ("Malabar Medicals Thiruvalla","Bypass Road, Thiruvalla",            "Thiruvalla", 9.3842, 76.5728, "9400800005", False, 3.7),

            # ── SURROUNDING DISTRICTS (40) ───────────────────────────────────
            # ── ERNAKULAM / KOCHI — KAKKANAD & THRIKKAKARA FOCUS (30) ─────
            # Kakkanad core (10.0121, 76.3487 area — Infopark/Smart City zone)
            ("MedPlus Kakkanad",            "NH-66 Bypass, Kakkanad",                "Ernakulam", 10.0121, 76.3487, "9400900004", True,  4.4),
            ("Apollo Pharmacy Kakkanad",    "Kakkanad Junction, Kochi",              "Ernakulam", 10.0135, 76.3502, "9400900021", True,  4.7),
            ("LifeCare Medicals Kakkanad",  "Infopark Road, Kakkanad",               "Ernakulam", 10.0098, 76.3471, "9400900022", True,  4.5),
            ("24Seven Pharmacy Kakkanad",   "Seaport-Airport Road, Kakkanad",        "Ernakulam", 10.0145, 76.3515, "9400900023", True,  4.3),
            ("Smart City Pharmacy",         "Smart City Phase 1, Kakkanad",          "Ernakulam", 10.0162, 76.3530, "9400900024", True,  4.6),
            ("Kakkanad Medical Centre",     "Cochin Special Economic Zone, Kakkanad","Ernakulam", 10.0088, 76.3460, "9400900025", True,  4.2),
            ("Wellcare Medicals Kakkanad",  "Petta Road, Kakkanad",                  "Ernakulam", 10.0175, 76.3478, "9400900026", True,  4.4),
            ("Health Hub Kakkanad",         "HiLite Mall Junction, Kakkanad",        "Ernakulam", 10.0110, 76.3498, "9400900027", True,  4.5),
            ("Medi Square Kakkanad",        "Kolenchery Road, Kakkanad",             "Ernakulam", 10.0055, 76.3510, "9400900028", True,  4.1),
            ("City Care Pharmacy Kakkanad", "NH Bypass Overbridge, Kakkanad",        "Ernakulam", 10.0190, 76.3542, "9400900029", True,  4.3),
            # Thrikkakara (10.0280, 76.3290 area)
            ("Thrikkakara Medical Store",   "Thrikkakara North, Kochi",              "Ernakulam", 10.0280, 76.3290, "9400900030", True,  4.4),
            ("Apollo Pharmacy Thrikkakara", "Thrikkakara South, Kochi",              "Ernakulam", 10.0265, 76.3275, "9400900031", True,  4.6),
            ("MedPlus Thrikkakara",         "South Chittoor Road, Thrikkakara",      "Ernakulam", 10.0295, 76.3305, "9400900032", True,  4.3),
            ("Sree Medicals Thrikkakara",   "Thrikkakara Temple Road",               "Ernakulam", 10.0258, 76.3261, "9400900033", True,  4.2),
            ("Prime Pharmacy Thrikkakara",  "Vyttila-Thrikkakara Road",              "Ernakulam", 10.0310, 76.3320, "9400900034", True,  4.5),
            ("Thrikkakara Medicals",        "NH Bypass Link Road, Thrikkakara",      "Ernakulam", 10.0242, 76.3248, "9400900035", True,  4.1),
            ("Meditrina Thrikkakara",       "Chittoor Road, Thrikkakara",            "Ernakulam", 10.0325, 76.3335, "9400900036", True,  4.4),
            # Edappally (near Lulu Mall)
            ("Lulu Mall Pharmacy",          "Lulu Mall, Edappally",                  "Ernakulam", 10.0272, 76.3086, "9400900001", True,  4.8),
            ("Apollo Pharmacy Edappally",   "NH-66, Edappally Junction",             "Ernakulam", 10.0258, 76.3072, "9400900037", True,  4.7),
            ("MedPlus Edappally",           "Edappally South, Near Metro",           "Ernakulam", 10.0284, 76.3098, "9400900038", True,  4.5),
            ("NetMeds Express Edappally",   "Edappally-Pulinchodu Road",             "Ernakulam", 10.0295, 76.3110, "9400900039", True,  4.3),
            # Palarivattom / Vyttila
            ("Medimart Palarivattom",       "Palarivattom Junction, Kochi",          "Ernakulam", 10.0044, 76.3079, "9400900003", True,  4.5),
            ("Vyttila Medicals",            "Vyttila Hub, Kochi",                    "Ernakulam", 9.9672,  76.3095, "9400900040", True,  4.6),
            ("Wellness Forever Vyttila",    "Vyttila Junction Bypass",               "Ernakulam", 9.9660,  76.3081, "9400900041", True,  4.4),
            # MG Road / Broadway / Fort Kochi core
            ("Apollo Pharmacy Kochi",       "MG Road, Ernakulam",                    "Ernakulam", 9.9816,  76.2999, "9400900002", True,  4.7),
            ("Wellness Forever Kochi",      "Broadway, Ernakulam",                   "Ernakulam", 9.9945,  76.2848, "9400900005", True,  4.6),
            ("NetMeds Outlet Thrippunithura","Thrippunithura Town",                  "Ernakulam", 9.9436,  76.3454, "9400900008", True,  4.5),
            # Aluva / Kalamassery / North Ernakulam
            ("Care Pharmacy Kalamassery",   "Kalamassery Junction, Kochi",           "Ernakulam", 10.0557, 76.3324, "9400900007", True,  4.2),
            ("24Seven Pharmacy Aluva",      "Aluva Town Junction",                   "Ernakulam", 10.0989, 76.3570, "9400900006", True,  4.3),
            ("HealthPlus Kaloor",           "Kaloor Junction, Kochi",                "Ernakulam", 10.0014, 76.2972, "9400900042", True,  4.5),
            # More Kakkanad / Thrikkakara / Kochi East
            ("Sree Ayyappa Medicals",       "Thrikkakara East, Kochi",               "Ernakulam", 10.0335, 76.3350, "9400900043", True,  4.2),
            ("Mediline Thrikkakara",        "Brahmapuram Road, Thrikkakara",         "Ernakulam", 10.0220, 76.3235, "9400900044", True,  4.3),
            ("Star Medicals Kakkanad",      "Rajagiri Valley Road, Kakkanad",        "Ernakulam", 10.0068, 76.3445, "9400900045", True,  4.4),
            ("Grace Pharmacy Kakkanad",     "Kalamasssery-Kakkanad Link Road",       "Ernakulam", 10.0202, 76.3558, "9400900046", True,  4.1),
            ("Caritas Medicals",            "Kureekad Road, Thrikkakara",            "Ernakulam", 10.0248, 76.3225, "9400900047", True,  4.5),
            ("Divine Medicals Kakkanad",    "Pukkattupady Road, Kakkanad",           "Ernakulam", 10.0038, 76.3425, "9400900048", True,  4.2),
            ("Alphons Pharmacy",            "Chittoor Road South, Thrikkakara",      "Ernakulam", 10.0205, 76.3268, "9400900049", True,  4.3),
            ("Kochi East Medicals",         "Seaport Airport Road, Near CSEZ",       "Ernakulam", 10.0180, 76.3598, "9400900050", True,  4.4),
            ("Metro Medicals Kakkanad",     "Kakkanad Post Office Junction",         "Ernakulam", 10.0115, 76.3540, "9400900051", True,  4.3),
            ("Healing Touch Pharmacy",      "Thrikkakara Panchayath Road",           "Ernakulam", 10.0272, 76.3312, "9400900052", True,  4.5),
            ("Nirmala Medicals Kakkanad",   "Near Rajagiri Hospital, Kakkanad",      "Ernakulam", 10.0050, 76.3465, "9400900053", True,  4.6),
            ("SN Medical Store Thrikkakara","Palachuvadu Road, Thrikkakara",         "Ernakulam", 10.0290, 76.3258, "9400900054", True,  4.1),

            # Idukki (6)
            ("Munnar Medicals",           "Munnar Town, Idukki",                 "Idukki", 10.0892, 77.0595, "9401000001", True,  4.1),
            ("Thodupuzha Pharmacy",       "Town Hall Road, Thodupuzha",          "Idukki", 9.8956,  76.7178, "9401000002", True,  4.3),
            ("Adimali Medical Store",     "Adimali Junction, Idukki",            "Idukki", 10.0071, 76.9597, "9401000003", True,  3.9),
            ("Nedumkandam Medicals",      "Town Road, Nedumkandam",              "Idukki", 9.8650,  77.0340, "9401000004", True,  4.0),
            ("High Range Pharmacy",       "Kattappana, Idukki",                  "Idukki", 9.7497,  77.1095, "9401000005", True,  4.2),
            ("Kumily Medical Store",      "Kumily, Idukki",                      "Idukki", 9.6050,  77.1650, "9401000006", True,  4.1),
            # Pathanamthitta (6)
            ("Pathanamthitta Medicals",   "KK Road, Pathanamthitta",             "Pathanamthitta", 9.2648, 76.7870, "9401100001", True,  4.4),
            ("Ranni Pharmacy",            "Ranni Town, Pathanamthitta",          "Pathanamthitta", 9.3806, 76.7826, "9401100002", True,  4.2),
            ("Adoor Medical Store",       "Town Junction, Adoor",                "Pathanamthitta", 9.1565, 76.7393, "9401100003", True,  4.3),
            ("Konni Medicals",            "Konni Junction, Pathanamthitta",      "Pathanamthitta", 9.2273, 76.8550, "9401100004", True,  4.0),
            ("Kozhencherry Pharmacy",     "MC Road, Kozhencherry",               "Pathanamthitta", 9.3127, 76.6460, "9401100005", True,  4.1),
            ("Pandalam Medicals",         "Pandalam Town, Pathanamthitta",       "Pathanamthitta", 9.2230, 76.6900, "9401100006", True,  3.9),
            # Alappuzha / Alleppey (6)
            ("Alappuzha Medicals",        "Mullackal, Alappuzha",                "Alappuzha", 9.4981, 76.3388, "9401200001", True,  4.5),
            ("Houseboat Pharmacy",        "Finishing Point, Alappuzha",          "Alappuzha", 9.4902, 76.3362, "9401200002", True,  4.3),
            ("Cherthala Medical Store",   "Cherthala Town",                      "Alappuzha", 9.6838, 76.3390, "9401200003", True,  4.1),
            ("Mararikkulam Pharmacy",     "Mararikkulam North",                  "Alappuzha", 9.5796, 76.2916, "9401200004", True,  3.9),
            ("Ambalappuzha Medicals",     "Ambalappuzha Town",                   "Alappuzha", 9.3846, 76.3596, "9401200005", True,  4.0),
            ("Haripad Pharmacy",          "Haripad Town",                        "Alappuzha", 9.2352, 76.4741, "9401200006", True,  4.2),
            # Thrissur (6)
            ("Thrissur Medicals",         "Swaraj Round, Thrissur",              "Thrissur", 10.5276, 76.2144, "9401300001", True,  4.6),
            ("Guruvayur Pharmacy",        "East Nada, Guruvayur",                "Thrissur", 10.5942, 76.0424, "9401300002", True,  4.4),
            ("Chalakudy Medicals",        "Chalakudy Town",                      "Thrissur", 10.3082, 76.3326, "9401300003", True,  4.2),
            ("Kodungallur Medical Store", "Kodungallur Town",                    "Thrissur", 10.2305, 76.1956, "9401300004", True,  4.1),
            ("Irinjalakuda Pharmacy",     "Town Road, Irinjalakuda",             "Thrissur", 10.3414, 76.2132, "9401300005", True,  4.0),
            ("MedPlus Thrissur",          "Round North, Thrissur",               "Thrissur", 10.5253, 76.2188, "9401300006", True,  4.5),
            # Thiruvananthapuram (4)
            ("Capital Pharmacy TVM",      "MG Road, Thiruvananthapuram",         "Thiruvananthapuram", 8.5241, 76.9366, "9401400001", True,  4.7),
            ("Pattom Medicals",           "Pattom, Thiruvananthapuram",          "Thiruvananthapuram", 8.5412, 76.9012, "9401400002", True,  4.5),
            ("Neyyattinkara Pharmacy",    "Neyyattinkara Town",                  "Thiruvananthapuram", 8.4008, 77.0862, "9401400003", True,  4.2),
            ("Attingal Medicals",         "Attingal Town",                       "Thiruvananthapuram", 8.6929, 76.8191, "9401400004", True,  4.1),
            # Kollam (4)
            ("Kollam City Meds",          "Main Road, Kollam Town",              "Kollam", 8.8932, 76.6141, "9401500001", True,  4.5),
            ("HealthPlus Kollam",         "Beach Road, Kollam",                  "Kollam", 8.8850, 76.6050, "9401500002", True,  4.3),
            ("MedCare Kollam",            "East Fort, Kollam",                   "Kollam", 8.8800, 76.6200, "9401500003", True,  4.7),
            ("Apollo Kollam",             "Chinnakada, Kollam",                  "Kollam", 8.8700, 76.6300, "9401500004", True,  4.8),
        ]

        pharmacies = []
        for d in pharmacies_data:
            pharmacies.append(Pharmacy(
                user_id=1, name=d[0], address=d[1], city=d[2],
                state="Kerala", latitude=d[3], longitude=d[4],
                phone=d[5], is_open=d[6], rating=d[7]
            ))
        db.add_all(pharmacies)
        db.commit()
        print(f"✅ {len(pharmacies)} pharmacies created!")

        # ==================
        # INVENTORY
        # Realistic stock & prices per medicine category
        # ==================
        print("\n📦 Creating inventory...")

        import random
        random.seed(42)

        # Price ranges per medicine (min, max) in INR
        price_range = {
            # Original 20
            1:  (4.0,   8.0),    # Paracetamol
            2:  (2.5,   5.0),    # Aspirin
            3:  (7.0,  12.0),    # Cetirizine
            4:  (12.0, 20.0),    # Omeprazole
            5:  (15.0, 22.0),    # Pantoprazole
            6:  (8.0,  15.0),    # Ibuprofen
            7:  (10.0, 18.0),    # Vitamin C
            8:  (5.0,  10.0),    # Antacid
            9:  (10.0, 16.0),    # Metformin
            10: (7.0,  13.0),    # Amlodipine
            11: (40.0, 55.0),    # Amoxicillin
            12: (50.0, 65.0),    # Azithromycin
            13: (22.0, 30.0),    # Atorvastatin
            14: (18.0, 26.0),    # Metoprolol
            15: (20.0, 28.0),    # Losartan
            16: (25.0, 35.0),    # Glimepiride
            17: (30.0, 45.0),    # Levothyroxine
            18: (55.0, 70.0),    # Clopidogrel
            19: (35.0, 50.0),    # Ambroxol Syrup
            20: (8.0,  14.0),    # ORS Sachet
            # Extended 30 — common medicines stocked by real Kerala pharmacies
            21: (6.0,  11.0),    # Domperidone (anti-nausea)
            22: (12.0, 18.0),    # Ranitidine / Famotidine (acidity)
            23: (45.0, 60.0),    # Metronidazole (antibiotic)
            24: (8.0,  14.0),    # Diclofenac (pain/inflammation)
            25: (20.0, 30.0),    # Ondansetron (anti-vomiting)
            26: (5.0,   9.0),    # Folic Acid (supplement)
            27: (12.0, 20.0),    # Calcium + Vitamin D3
            28: (15.0, 25.0),    # Iron + Folic tablet (Ferrous sulphate)
            29: (8.0,  15.0),    # Vitamin B Complex
            30: (30.0, 42.0),    # Salbutamol inhaler/tablet
            31: (18.0, 28.0),    # Prednisolone (steroid)
            32: (22.0, 35.0),    # Doxycycline (antibiotic)
            33: (10.0, 16.0),    # Loperamide (diarrhoea)
            34: (14.0, 22.0),    # Pheniramine / Avil (allergy)
            35: (25.0, 38.0),    # Fexofenadine / Allegra (allergy)
            36: (8.0,  13.0),    # Paracetamol + Caffeine combination
            37: (18.0, 28.0),    # Montelukast (asthma/allergy)
            38: (60.0, 80.0),    # Ramipril (BP/heart)
            39: (40.0, 55.0),    # Telmisartan (hypertension)
            40: (20.0, 32.0),    # Glipizide (diabetes)
            41: (30.0, 45.0),    # Sitagliptin (diabetes)
            42: (15.0, 24.0),    # Esomeprazole (GERD)
            43: (10.0, 16.0),    # Lactulose (constipation syrup)
            44: (5.0,   8.0),    # Zinc supplement tablet
            45: (12.0, 20.0),    # Dolo 650 (Paracetamol 650mg)
            46: (9.0,  15.0),    # Tramadol (pain, Rx)
            47: (35.0, 50.0),    # Ciprofloxacin (antibiotic)
            48: (55.0, 75.0),    # Levofloxacin (antibiotic)
            49: (20.0, 30.0),    # Aceclofenac (NSAID)
            50: (6.0,  10.0),    # Glucose powder / Dextrose
        }

        # Stock probability — not every pharmacy stocks every medicine
        # OTC = high chance, prescription = lower chance
        stock_chance = {
            # Original 20 (OTC high chance, Rx lower)
            1: 0.98, 2: 0.95, 3: 0.92, 4: 0.90, 5: 0.88,
            6: 0.93, 7: 0.85, 8: 0.90, 9: 0.80, 10: 0.78,
            11: 0.82, 12: 0.75, 13: 0.70, 14: 0.68, 15: 0.65,
            16: 0.60, 17: 0.55, 18: 0.58, 19: 0.85, 20: 0.88,
            # Extended 30 — varied stocking probabilities
            21: 0.88,  # Domperidone — very common OTC
            22: 0.87,  # Ranitidine/Famotidine — common
            23: 0.72,  # Metronidazole — common antibiotic
            24: 0.86,  # Diclofenac — common pain relief
            25: 0.70,  # Ondansetron — common anti-emetic
            26: 0.83,  # Folic Acid — supplement, very common
            27: 0.80,  # Calcium + Vit D — very common supplement
            28: 0.78,  # Iron + Folic — common
            29: 0.84,  # Vitamin B Complex — very common
            30: 0.65,  # Salbutamol — common Rx
            31: 0.60,  # Prednisolone — Rx steroid
            32: 0.62,  # Doxycycline — antibiotic
            33: 0.82,  # Loperamide — common OTC
            34: 0.88,  # Pheniramine/Avil — very common allergy
            35: 0.80,  # Fexofenadine/Allegra — common allergy
            36: 0.90,  # Paracetamol+Caffeine — very common OTC
            37: 0.65,  # Montelukast — asthma/allergy Rx
            38: 0.58,  # Ramipril — Rx cardiac
            39: 0.62,  # Telmisartan — Rx BP
            40: 0.55,  # Glipizide — Rx diabetes
            41: 0.50,  # Sitagliptin — Rx diabetes (newer)
            42: 0.82,  # Esomeprazole — common GERD
            43: 0.75,  # Lactulose syrup — common constipation
            44: 0.86,  # Zinc supplement — very common
            45: 0.95,  # Dolo 650 (Paracetamol 650mg) — most popular in Kerala
            46: 0.45,  # Tramadol — Rx pain (controlled)
            47: 0.68,  # Ciprofloxacin — antibiotic
            48: 0.60,  # Levofloxacin — antibiotic
            49: 0.78,  # Aceclofenac — NSAID, very common in India
            50: 0.88,  # Glucose powder — common OTC
        }

        # Medicine name → DB id mapping for all 50 common medicines
        MEDICINE_NAMES = {
            1:  "Paracetamol",            2:  "Aspirin",
            3:  "Cetirizine",             4:  "Omeprazole",
            5:  "Pantoprazole",           6:  "Ibuprofen",
            7:  "Vitamin C",              8:  "Antacid",
            9:  "Metformin",              10: "Amlodipine",
            11: "Amoxicillin",            12: "Azithromycin",
            13: "Atorvastatin",           14: "Metoprolol",
            15: "Losartan",               16: "Glimepiride",
            17: "Levothyroxine",          18: "Clopidogrel",
            19: "Ambroxol",               20: "ORS",
            21: "Domperidone",            22: "Ranitidine",
            23: "Metronidazole",          24: "Diclofenac",
            25: "Ondansetron",            26: "Folic Acid",
            27: "Calcium",                28: "Ferrous",
            29: "Vitamin B",              30: "Salbutamol",
            31: "Prednisolone",           32: "Doxycycline",
            33: "Loperamide",             34: "Pheniramine",
            35: "Fexofenadine",           36: "Paracetamol",
            37: "Montelukast",            38: "Ramipril",
            39: "Telmisartan",            40: "Glipizide",
            41: "Sitagliptin",            42: "Esomeprazole",
            43: "Lactulose",              44: "Zinc",
            45: "Dolo",                   46: "Tramadol",
            47: "Ciprofloxacin",          48: "Levofloxacin",
            49: "Aceclofenac",            50: "Glucose",
        }

        # Build name → DB id lookup (case-insensitive starts-with match)
        all_db_medicines = db.query(Medicine).all()
        name_to_id = {}
        for m in all_db_medicines:
            name_to_id[m.brand_name.lower()] = m.id

        def find_medicine_id(slot_num):
            keyword = MEDICINE_NAMES.get(slot_num, "").lower()
            if not keyword:
                return slot_num  # fallback to slot number
            # Exact match first
            if keyword in name_to_id:
                return name_to_id[keyword]
            # Starts-with match
            for name, mid in name_to_id.items():
                if name.startswith(keyword):
                    return mid
            # Contains match
            for name, mid in name_to_id.items():
                if keyword in name:
                    return mid
            return slot_num  # last resort

        inventory_items = []
        total_pharmacies = len(pharmacies)
        seen_pharmacy_medicine = set()   # prevent duplicates

        for med_id in range(1, 51):
            pmin, pmax = price_range[med_id]
            chance = stock_chance[med_id]
            for pharm_idx in range(total_pharmacies):
                pharm_id = pharm_idx + 1
                has_stock = random.random() < chance

                if has_stock:
                    # Larger pharmacies (Kottayam town, Apollo, MedPlus) stock more
                    city = pharmacies_data[pharm_idx][2]  # city name
                    if city in ("Kottayam",):
                        qty = random.randint(50, 200)
                    elif city in ("Ernakulam", "Thiruvananthapuram", "Thrissur", "Kollam"):
                        qty = random.randint(60, 250)   # larger city pharmacies
                    elif city in ("Changanacherry", "Pala", "Ettumanoor",
                                  "Vaikom", "Kanjirappally", "Erattupetta", "Thiruvalla"):
                        qty = random.randint(20, 100)
                    else:
                        qty = random.randint(10, 80)
                    price = round(random.uniform(pmin, pmax), 2)
                else:
                    qty = 0
                    price = round(random.uniform(pmin, pmax), 2)

                real_med_id = find_medicine_id(med_id)
                key = (pharm_id, real_med_id)
                if key not in seen_pharmacy_medicine:
                    seen_pharmacy_medicine.add(key)
                    inventory_items.append(PharmacyInventory(
                        pharmacy_id=pharm_id,
                        medicine_id=real_med_id,
                        stock_quantity=qty,
                        price=price,
                        is_available=(qty > 0)
                    ))

        db.add_all(inventory_items)
        db.commit()
        print(f"✅ {len(inventory_items)} inventory records created!")

        print("\n" + "="*60)
        print("🎉 DATABASE SEEDED SUCCESSFULLY!")
        print("="*60)
        print(f"\n📊 Summary:")
        print(f"   👤 Users:       3")
        print(f"   🏥 Pharmacies:  {len(pharmacies)}")
        print(f"   💊 Medicines:   50 per pharmacy")
        print(f"   📦 Inventory:   {len(inventory_items)} records")
        print(f"\n📍 Pharmacy breakdown:")
        print(f"   • Kottayam Town:        20")
        print(f"   • Kottayam District:    40  (Changanacherry, Ettumanoor,")
        print(f"                               Vaikom, Pala, Kanjirappally,")
        print(f"                               Erattupetta, Thiruvalla)")
        print(f"   • Surrounding Districts: 62 (Ernakulam/Kakkanad/Thrikkakara x30, Idukki,")
        print(f"                               Alappuzha, Thrissur, TVM, Kollam)")
        print(f"\n📱 Test Credentials:")
        print(f"   👤 Patient:  patient@test.com / password123")
        print(f"   🏥 Pharmacy: pharmacy@test.com / pharmacy123")
        print(f"   ⚙️  Admin:    admin@pharmafind.com / admin123")
        print("="*60)

    except Exception as e:
        print(f"\n❌ Error: {e}")
        db.rollback()
        raise e
    finally:
        db.close()


if __name__ == "__main__":
    seed_database()