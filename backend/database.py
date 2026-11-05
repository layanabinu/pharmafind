"""
database.py
───────────
Supports both SQLite (local dev) and PostgreSQL (production).

Automatically switches based on DATABASE_URL in .env:
  - Not set        → SQLite  (pharmafind.db, local dev)
  - Set to postgres → PostgreSQL (Railway / Render / Supabase)

.env examples:
  # Local dev — just leave DATABASE_URL empty or don't set it
  DATABASE_URL=

  # Railway PostgreSQL
  DATABASE_URL=postgresql://postgres:password@monorail.proxy.rlwy.net:12345/railway

  # Supabase
  DATABASE_URL=postgresql://postgres:password@db.xyz.supabase.co:5432/postgres

  # Render
  DATABASE_URL=postgresql://user:password@dpg-xxx.oregon-postgres.render.com/pharmafind
"""

import os
from dotenv import load_dotenv
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, declarative_base

load_dotenv()

DATABASE_URL = os.getenv("DATABASE_URL", "")

# ─────────────────────────────────────────────
# ENGINE SETUP
# ─────────────────────────────────────────────

if DATABASE_URL and DATABASE_URL.startswith("postgresql"):
    # ── PostgreSQL (production) ──
    # Handle Railway's "postgres://" prefix (SQLAlchemy needs "postgresql://")
    if DATABASE_URL.startswith("postgres://"):
        DATABASE_URL = DATABASE_URL.replace("postgres://", "postgresql://", 1)

    engine = create_engine(
        DATABASE_URL,
        pool_size=10,           # max 10 connections in pool
        max_overflow=20,        # allow 20 extra connections when busy
        pool_pre_ping=True,     # test connections before using (auto-reconnect)
        pool_recycle=300,       # recycle connections every 5 mins
        echo=False              # set True to log all SQL queries for debugging
    )
    DB_TYPE = "postgresql"
    print(f"🐘 Connected to PostgreSQL database")

else:
    # ── SQLite (local development) ──
    SQLITE_PATH = os.path.join(os.path.dirname(__file__), "pharmafind.db")
    engine = create_engine(
        f"sqlite:///{SQLITE_PATH}",
        connect_args={"check_same_thread": False},  # needed for FastAPI threading
        echo=False
    )
    DB_TYPE = "sqlite"
    print(f"📁 Using SQLite database: {SQLITE_PATH}")

# ─────────────────────────────────────────────
# SESSION & BASE
# ─────────────────────────────────────────────

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


def get_db():
    """FastAPI dependency — yields a database session per request."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def get_db_type() -> str:
    """Returns 'postgresql' or 'sqlite' — useful for debugging."""
    return DB_TYPE