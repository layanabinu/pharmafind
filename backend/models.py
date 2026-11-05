"""
models.py
─────────
SQLAlchemy ORM models.
Compatible with both SQLite and PostgreSQL — no changes needed when switching.

PostgreSQL-specific improvements:
  - String lengths enforced (VARCHAR instead of TEXT)
  - Timestamps use server_default for DB-side generation
  - Indexes added on commonly queried columns
"""

from sqlalchemy import (
    Column, Integer, String, Float, Boolean,
    Text, DateTime, ForeignKey, Index
)
from sqlalchemy.sql import func
from sqlalchemy.orm import relationship, declarative_base
from database import Base


class User(Base):
    __tablename__ = "users"

    id              = Column(Integer, primary_key=True, index=True)
    email           = Column(String(255), unique=True, nullable=False, index=True)
    password_hash   = Column(String(255), nullable=False)
    name            = Column(String(100), nullable=False)
    role            = Column(String(20), nullable=False, default="patient")
    phone           = Column(String(20), nullable=True)
    is_active       = Column(Boolean, default=True)
    is_verified     = Column(Boolean, default=False)
    created_at      = Column(DateTime, server_default=func.now())
    updated_at      = Column(DateTime, server_default=func.now(), onupdate=func.now())

    medicines       = relationship("UserMedicine", back_populates="user")


class Pharmacy(Base):
    __tablename__ = "pharmacies"

    id          = Column(Integer, primary_key=True, index=True)
    user_id     = Column(Integer, ForeignKey("users.id"), nullable=True)
    name        = Column(String(200), nullable=False)
    address     = Column(Text, nullable=True)
    city        = Column(String(100), nullable=True, index=True)
    state       = Column(String(100), nullable=True)
    latitude    = Column(Float, nullable=False)
    longitude   = Column(Float, nullable=False)
    phone       = Column(String(20), nullable=True)
    is_open     = Column(Boolean, default=True, index=True)
    rating      = Column(Float, default=0.0)
    created_at  = Column(DateTime, server_default=func.now())

    inventory   = relationship("PharmacyInventory", back_populates="pharmacy")

    # Index for fast geo queries — find pharmacies by location range
    __table_args__ = (
        Index("ix_pharmacies_location", "latitude", "longitude"),
    )


class Medicine(Base):
    __tablename__ = "medicines"

    id                       = Column(Integer, primary_key=True, index=True)
    medicine_id              = Column(String(20), unique=True, nullable=True)
    brand_name               = Column(String(200), nullable=False, index=True)
    generic_name             = Column(String(200), nullable=True, index=True)
    manufacturer             = Column(String(200), nullable=True)
    strength                 = Column(String(50), nullable=True)
    form                     = Column(String(50), nullable=True)
    description              = Column(Text, nullable=True)
    uses                     = Column(Text, nullable=True)           # pipe-separated list
    side_effects             = Column(Text, nullable=True)           # pipe-separated list
    image_url                = Column(String(500), nullable=True)
    excellent_review_pct     = Column(Integer, default=0)
    average_review_pct       = Column(Integer, default=0)
    poor_review_pct          = Column(Integer, default=0)
    is_prescription_required = Column(Boolean, default=False)
    created_at               = Column(DateTime, server_default=func.now())

    inventory = relationship("PharmacyInventory", back_populates="medicine")


class PharmacyInventory(Base):
    __tablename__ = "pharmacy_inventory"

    id             = Column(Integer, primary_key=True, index=True)
    pharmacy_id    = Column(Integer, ForeignKey("pharmacies.id"), nullable=False, index=True)
    medicine_id    = Column(Integer, ForeignKey("medicines.id"), nullable=False, index=True)
    stock_quantity = Column(Integer, default=0)
    price          = Column(Float, nullable=False)
    is_available   = Column(Boolean, default=True, index=True)
    updated_at     = Column(DateTime, server_default=func.now(), onupdate=func.now())

    pharmacy = relationship("Pharmacy", back_populates="inventory")
    medicine = relationship("Medicine", back_populates="inventory")

    # Composite index — fast lookup by pharmacy + medicine
    __table_args__ = (
        Index("ix_inventory_pharmacy_medicine", "pharmacy_id", "medicine_id"),
    )


class UserMedicine(Base):
    __tablename__ = "user_medicines"

    id                = Column(Integer, primary_key=True, index=True)
    user_id           = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    medicine_id       = Column(Integer, ForeignKey("medicines.id"), nullable=True)
    custom_name       = Column(String(200), nullable=True)   # user-typed name when not in DB
    dosage            = Column(String(100), nullable=True)
    frequency         = Column(String(100), nullable=True)
    strips_remaining  = Column(Integer, default=0)
    tablets_per_strip = Column(Integer, default=10)  # default 10 tablets/strip
    time_of_day       = Column(String(20), default="Morning")  # Morning/Evening/Both/As needed
    reminder_enabled  = Column(Boolean, default=True)
    is_active         = Column(Boolean, default=True, index=True)
    created_at        = Column(DateTime, server_default=func.now())
    updated_at        = Column(DateTime, server_default=func.now(), onupdate=func.now())

    user = relationship("User", back_populates="medicines")


class MedicationHistory(Base):
    __tablename__ = "medication_history"

    id                = Column(Integer, primary_key=True, index=True)
    user_id           = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    medicine_id       = Column(Integer, ForeignKey("medicines.id"), nullable=True)
    medicine_name     = Column(String(200), nullable=False)
    event_type        = Column(String(30), nullable=False)
    # "started" | "refilled" | "stopped" | "updated" | "reserved"
    strips_remaining  = Column(Integer, default=0)
    tablets_per_strip = Column(Integer, default=10)  # default 10 tablets/strip
    dosage            = Column(String(100), nullable=True)
    frequency         = Column(String(100), nullable=True)
    notes             = Column(Text, nullable=True)
    pharmacy_name     = Column(String(200), nullable=True)  # where they got it
    event_date        = Column(DateTime, server_default=func.now(), index=True)

    __table_args__ = (
        Index("ix_history_user_medicine", "user_id", "medicine_id"),
    )


class Reservation(Base):
    __tablename__ = "reservations"

    id              = Column(Integer, primary_key=True, index=True)
    reservation_code= Column(String(20), unique=True, nullable=False, index=True)
    # e.g. "PF3A9B2C1"
    user_id         = Column(Integer, ForeignKey("users.id"), nullable=False, index=True)
    pharmacy_id     = Column(Integer, ForeignKey("pharmacies.id"), nullable=False)
    medicine_id     = Column(Integer, ForeignKey("medicines.id"), nullable=False)
    quantity        = Column(Integer, default=1)
    unit_price      = Column(Float, nullable=True)
    total_price     = Column(Float, nullable=True)
    notes           = Column(Text, nullable=True)
    status          = Column(String(20), default="pending")
    # "pending" | "collected" | "cancelled" | "expired"
    is_prescription_required = Column(Boolean, default=False)
    created_at      = Column(DateTime, server_default=func.now(), index=True)
    expires_at      = Column(DateTime, nullable=True)   # 24 hrs from created_at
    collected_at    = Column(DateTime, nullable=True)

    user     = relationship("User")
    pharmacy = relationship("Pharmacy")
    medicine = relationship("Medicine")