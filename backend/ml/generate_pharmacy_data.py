import pandas as pd
import numpy as np
import os
import sys

# Change to parent directory (backend folder)
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

np.random.seed(42)

# Create data directory if it doesn't exist
if not os.path.exists('data'):
    os.makedirs('data')

print("🔄 Generating pharmacy training data...")

n_samples = 800

data = {
    'distance_km': np.random.uniform(0.5, 15, n_samples),
    'stock_availability': np.random.randint(0, 101, n_samples),
    'reliability_score': np.random.uniform(0.5, 1.0, n_samples),
    'avg_rating': np.random.uniform(2.0, 5.0, n_samples),
    'price_index': np.random.uniform(0.8, 1.3, n_samples),
    'current_queue': np.random.randint(0, 20, n_samples)
}

df = pd.DataFrame(data)

# Create target score
df['pharmacy_score'] = (
    (100 - df['distance_km'] * 3) * 0.25 +
    df['stock_availability'] * 0.35 +
    df['reliability_score'] * 40 * 0.20 +
    df['avg_rating'] * 8 * 0.15 +
    (1.5 - df['price_index']) * 20 * 0.05
)

df['pharmacy_score'] += np.random.normal(0, 5, n_samples)
df['pharmacy_score'] = df['pharmacy_score'].clip(0, 100)

df.to_csv(os.path.join('data', 'pharmacy_training_data.csv'), index=False)

print(f"✅ Generated {n_samples} pharmacy scenarios")
print(f"   Score range: {df['pharmacy_score'].min():.1f} - {df['pharmacy_score'].max():.1f}")
print(f"   Saved to: data\\pharmacy_training_data.csv")