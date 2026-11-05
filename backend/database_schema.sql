-- =============================================
-- PHARMAFIND DATABASE SCHEMA
-- PostgreSQL 14+
-- =============================================

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- =============================================
-- TABLE: users
-- Stores user account information
-- =============================================
CREATE TABLE users (
    id SERIAL PRIMARY KEY,
    uuid UUID DEFAULT uuid_generate_v4() UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL CHECK (role IN ('patient', 'pharmacy', 'admin')),
    phone VARCHAR(20),
    profile_image_url TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    is_verified BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role);

-- =============================================
-- TABLE: pharmacies
-- Stores pharmacy information
-- =============================================
CREATE TABLE pharmacies (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    license_number VARCHAR(100) UNIQUE,
    address TEXT NOT NULL,
    city VARCHAR(100) NOT NULL,
    state VARCHAR(100) NOT NULL,
    pincode VARCHAR(10) NOT NULL,
    latitude DECIMAL(10, 8) NOT NULL,
    longitude DECIMAL(11, 8) NOT NULL,
    phone VARCHAR(20),
    email VARCHAR(255),
    opening_time TIME DEFAULT '09:00:00',
    closing_time TIME DEFAULT '21:00:00',
    is_open BOOLEAN DEFAULT TRUE,
    is_24_hours BOOLEAN DEFAULT FALSE,
    rating DECIMAL(3, 2) DEFAULT 0.00 CHECK (rating >= 0 AND rating <= 5),
    total_reviews INTEGER DEFAULT 0,
    reliability_score DECIMAL(5, 2) DEFAULT 0.00,
    average_wait_time INTEGER DEFAULT 15, -- in minutes
    delivery_available BOOLEAN DEFAULT FALSE,
    home_delivery_radius DECIMAL(5, 2), -- in km
    delivery_charge DECIMAL(10, 2),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pharmacies_location ON pharmacies(latitude, longitude);
CREATE INDEX idx_pharmacies_city ON pharmacies(city);
CREATE INDEX idx_pharmacies_user_id ON pharmacies(user_id);

-- =============================================
-- TABLE: medicines
-- Master medicine database
-- =============================================
CREATE TABLE medicines (
    id SERIAL PRIMARY KEY,
    medicine_id VARCHAR(50) UNIQUE NOT NULL,
    brand_name VARCHAR(255) NOT NULL,
    generic_name VARCHAR(255),
    manufacturer VARCHAR(255),
    strength VARCHAR(50),
    form VARCHAR(50) CHECK (form IN ('Tablet', 'Capsule', 'Syrup', 'Injection', 'Cream', 'Ointment', 'Drops', 'Inhaler', 'Other')),
    pack_size VARCHAR(50),
    description TEXT,
    usage TEXT,
    side_effects TEXT,
    precautions TEXT,
    is_prescription_required BOOLEAN DEFAULT FALSE,
    category VARCHAR(100),
    therapeutic_class VARCHAR(100),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_medicines_brand_name ON medicines(brand_name);
CREATE INDEX idx_medicines_generic_name ON medicines(generic_name);
CREATE INDEX idx_medicines_category ON medicines(category);
CREATE INDEX idx_medicines_medicine_id ON medicines(medicine_id);

-- =============================================
-- TABLE: pharmacy_inventory
-- Tracks medicine stock at each pharmacy
-- =============================================
CREATE TABLE pharmacy_inventory (
    id SERIAL PRIMARY KEY,
    pharmacy_id INTEGER REFERENCES pharmacies(id) ON DELETE CASCADE,
    medicine_id INTEGER REFERENCES medicines(id) ON DELETE CASCADE,
    stock_quantity INTEGER NOT NULL DEFAULT 0 CHECK (stock_quantity >= 0),
    price DECIMAL(10, 2) NOT NULL CHECK (price >= 0),
    mrp DECIMAL(10, 2),
    discount_percentage DECIMAL(5, 2) DEFAULT 0.00,
    batch_number VARCHAR(50),
    expiry_date DATE,
    last_restocked TIMESTAMP,
    low_stock_threshold INTEGER DEFAULT 10,
    is_available BOOLEAN DEFAULT TRUE,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(pharmacy_id, medicine_id)
);

CREATE INDEX idx_inventory_pharmacy ON pharmacy_inventory(pharmacy_id);
CREATE INDEX idx_inventory_medicine ON pharmacy_inventory(medicine_id);
CREATE INDEX idx_inventory_availability ON pharmacy_inventory(is_available, stock_quantity);

-- =============================================
-- TABLE: user_medicines
-- User's personal medicine list
-- =============================================
CREATE TABLE user_medicines (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    medicine_id INTEGER REFERENCES medicines(id),
    custom_medicine_name VARCHAR(255), -- if medicine not in master DB
    dosage VARCHAR(100),
    frequency VARCHAR(100),
    frequency_times_per_day INTEGER,
    tablets_per_dose INTEGER DEFAULT 1,
    total_tablets INTEGER,
    tablets_remaining INTEGER,
    last_refill_date DATE,
    next_refill_date DATE,
    reminder_enabled BOOLEAN DEFAULT TRUE,
    reminder_time TIME,
    reminder_days_before INTEGER DEFAULT 3,
    doctor_name VARCHAR(255),
    prescription_url TEXT,
    notes TEXT,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_medicines_user ON user_medicines(user_id);
CREATE INDEX idx_user_medicines_medicine ON user_medicines(medicine_id);
CREATE INDEX idx_user_medicines_refill ON user_medicines(next_refill_date);

-- =============================================
-- TABLE: medicine_reminders
-- Tracks reminder notifications
-- =============================================
CREATE TABLE medicine_reminders (
    id SERIAL PRIMARY KEY,
    user_medicine_id INTEGER REFERENCES user_medicines(id) ON DELETE CASCADE,
    reminder_time TIMESTAMP NOT NULL,
    is_sent BOOLEAN DEFAULT FALSE,
    is_acknowledged BOOLEAN DEFAULT FALSE,
    acknowledged_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reminders_time ON medicine_reminders(reminder_time, is_sent);
CREATE INDEX idx_reminders_user_medicine ON medicine_reminders(user_medicine_id);

-- =============================================
-- TABLE: orders
-- Medicine orders/bookings
-- =============================================
CREATE TABLE orders (
    id SERIAL PRIMARY KEY,
    order_number VARCHAR(50) UNIQUE NOT NULL,
    user_id INTEGER REFERENCES users(id) ON DELETE SET NULL,
    pharmacy_id INTEGER REFERENCES pharmacies(id) ON DELETE SET NULL,
    order_type VARCHAR(20) CHECK (order_type IN ('pickup', 'delivery')) DEFAULT 'pickup',
    status VARCHAR(50) CHECK (status IN ('pending', 'confirmed', 'preparing', 'ready', 'out_for_delivery', 'completed', 'cancelled')) DEFAULT 'pending',
    total_amount DECIMAL(10, 2) NOT NULL,
    discount_amount DECIMAL(10, 2) DEFAULT 0.00,
    delivery_charge DECIMAL(10, 2) DEFAULT 0.00,
    final_amount DECIMAL(10, 2) NOT NULL,
    payment_method VARCHAR(50),
    payment_status VARCHAR(50) CHECK (payment_status IN ('pending', 'paid', 'failed', 'refunded')) DEFAULT 'pending',
    delivery_address TEXT,
    delivery_latitude DECIMAL(10, 8),
    delivery_longitude DECIMAL(11, 8),
    estimated_delivery_time TIMESTAMP,
    actual_delivery_time TIMESTAMP,
    prescription_url TEXT,
    notes TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_pharmacy ON orders(pharmacy_id);
CREATE INDEX idx_orders_status ON orders(status);
CREATE INDEX idx_orders_created ON orders(created_at);

-- =============================================
-- TABLE: order_items
-- Individual items in an order
-- =============================================
CREATE TABLE order_items (
    id SERIAL PRIMARY KEY,
    order_id INTEGER REFERENCES orders(id) ON DELETE CASCADE,
    medicine_id INTEGER REFERENCES medicines(id),
    medicine_name VARCHAR(255) NOT NULL,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    unit_price DECIMAL(10, 2) NOT NULL,
    total_price DECIMAL(10, 2) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_order_items_order ON order_items(order_id);

-- =============================================
-- TABLE: pharmacy_reviews
-- User reviews for pharmacies
-- =============================================
CREATE TABLE pharmacy_reviews (
    id SERIAL PRIMARY KEY,
    pharmacy_id INTEGER REFERENCES pharmacies(id) ON DELETE CASCADE,
    user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    order_id INTEGER REFERENCES orders(id) ON DELETE SET NULL,
    rating INTEGER NOT NULL CHECK (rating >= 1 AND rating <= 5),
    review_text TEXT,
    service_rating INTEGER CHECK (service_rating >= 1 AND service_rating <= 5),
    availability_rating INTEGER CHECK (availability_rating >= 1 AND availability_rating <= 5),
    price_rating INTEGER CHECK (price_rating >= 1 AND price_rating <= 5),
    is_verified_purchase BOOLEAN DEFAULT FALSE,
    helpful_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(pharmacy_id, user_id, order_id)
);

CREATE INDEX idx_reviews_pharmacy ON pharmacy_reviews(pharmacy_id);
CREATE INDEX idx_reviews_rating ON pharmacy_reviews(rating);

-- =============================================
-- TABLE: search_history
-- Track user searches for analytics
-- =============================================
CREATE TABLE search_history (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    search_query VARCHAR(255) NOT NULL,
    search_type VARCHAR(50) CHECK (search_type IN ('medicine', 'pharmacy', 'generic')),
    result_count INTEGER DEFAULT 0,
    user_latitude DECIMAL(10, 8),
    user_longitude DECIMAL(11, 8),
    searched_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_search_history_user ON search_history(user_id);
CREATE INDEX idx_search_history_query ON search_history(search_query);
CREATE INDEX idx_search_history_date ON search_history(searched_at);

-- =============================================
-- TABLE: favorite_pharmacies
-- User's saved/favorite pharmacies
-- =============================================
CREATE TABLE favorite_pharmacies (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    pharmacy_id INTEGER REFERENCES pharmacies(id) ON DELETE CASCADE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, pharmacy_id)
);

CREATE INDEX idx_favorites_user ON favorite_pharmacies(user_id);

-- =============================================
-- TABLE: notifications
-- System notifications for users
-- =============================================
CREATE TABLE notifications (
    id SERIAL PRIMARY KEY,
    user_id INTEGER REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(50) CHECK (type IN ('reminder', 'order', 'promotional', 'system')),
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    related_id INTEGER, -- ID of related entity (order, medicine, etc.)
    is_read BOOLEAN DEFAULT FALSE,
    read_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notifications_user ON notifications(user_id, is_read);
CREATE INDEX idx_notifications_created ON notifications(created_at);

-- =============================================
-- TRIGGERS: Update timestamps automatically
-- =============================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_pharmacies_updated_at BEFORE UPDATE ON pharmacies
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_medicines_updated_at BEFORE UPDATE ON medicines
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_user_medicines_updated_at BEFORE UPDATE ON user_medicines
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_orders_updated_at BEFORE UPDATE ON orders
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- =============================================
-- VIEWS: Useful queries
-- =============================================

-- View: Active pharmacies with inventory count
CREATE VIEW v_active_pharmacies AS
SELECT 
    p.*,
    COUNT(DISTINCT pi.medicine_id) as total_medicines,
    SUM(pi.stock_quantity) as total_stock,
    AVG(pi.price) as average_price
FROM pharmacies p
LEFT JOIN pharmacy_inventory pi ON p.id = pi.pharmacy_id
WHERE p.is_active = TRUE
GROUP BY p.id;

-- View: Popular medicines
CREATE VIEW v_popular_medicines AS
SELECT 
    m.*,
    COUNT(DISTINCT pi.pharmacy_id) as available_at_pharmacies,
    AVG(pi.price) as average_price,
    MIN(pi.price) as min_price,
    MAX(pi.price) as max_price
FROM medicines m
LEFT JOIN pharmacy_inventory pi ON m.id = pi.medicine_id
WHERE pi.is_available = TRUE
GROUP BY m.id
ORDER BY available_at_pharmacies DESC;

-- =============================================
-- SAMPLE DATA INSERTION
-- =============================================

-- Insert sample admin user
INSERT INTO users (email, password_hash, name, role, is_verified) VALUES
('admin@pharmafind.com', '$2b$12$examplehash', 'Admin User', 'admin', TRUE);

-- Insert sample pharmacies
INSERT INTO pharmacies (user_id, name, address, city, state, pincode, latitude, longitude, phone, email) VALUES
(1, 'City Meds Pharmacy', 'Main Road, Kollam', 'Kollam', 'Kerala', '691001', 8.8932, 76.6141, '9876543210', 'citymeds@example.com'),
(1, 'HealthPlus Pharmacy', 'Beach Road, Kollam', 'Kollam', 'Kerala', '691001', 8.8850, 76.6050, '9876543211', 'healthplus@example.com'),
(1, 'MedCare Pharmacy', 'East Fort, Kollam', 'Kollam', 'Kerala', '691001', 8.8800, 76.6200, '9876543212', 'medcare@example.com'),
(1, 'Apollo Pharmacy', 'Chinnakada, Kollam', 'Kollam', 'Kerala', '691001', 8.8700, 76.6300, '9876543213', 'apollo@example.com');

-- =============================================
-- CONSTRAINTS & VALIDATION
-- =============================================

-- Ensure expiry date is in the future
ALTER TABLE pharmacy_inventory ADD CONSTRAINT check_expiry_future 
CHECK (expiry_date IS NULL OR expiry_date > CURRENT_DATE);

-- Ensure next refill is after last refill
ALTER TABLE user_medicines ADD CONSTRAINT check_refill_dates
CHECK (next_refill_date IS NULL OR last_refill_date IS NULL OR next_refill_date >= last_refill_date);

-- Ensure tablets remaining doesn't exceed total
ALTER TABLE user_medicines ADD CONSTRAINT check_tablets_remaining
CHECK (tablets_remaining IS NULL OR total_tablets IS NULL OR tablets_remaining <= total_tablets);

-- =============================================
-- END OF SCHEMA
-- =============================================