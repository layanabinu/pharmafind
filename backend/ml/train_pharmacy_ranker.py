import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestRegressor
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import mean_squared_error, r2_score
import pickle
import os

# Change to parent directory (backend folder)
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

print("🔄 Training Pharmacy Ranking Model...")

df = pd.read_csv(os.path.join('data', 'pharmacy_training_data.csv'))

X = df[['distance_km', 'stock_availability', 'reliability_score', 
        'avg_rating', 'price_index', 'current_queue']]
y = df['pharmacy_score']

X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

print(f"   Training samples: {len(X_train)}")
print(f"   Testing samples: {len(X_test)}")

scaler = StandardScaler()
X_train_scaled = scaler.fit_transform(X_train)
X_test_scaled = scaler.transform(X_test)

model = RandomForestRegressor(
    n_estimators=100,
    max_depth=10,
    min_samples_split=5,
    random_state=42,
    n_jobs=-1
)

print("\n🤖 Training Random Forest...")
model.fit(X_train_scaled, y_train)

y_pred = model.predict(X_test_scaled)
mse = mean_squared_error(y_test, y_pred)
r2 = r2_score(y_test, y_pred)

print(f"\n📊 Performance:")
print(f"   MSE: {mse:.2f}")
print(f"   R² Score: {r2:.3f}")

# Feature importance
feature_importance = pd.DataFrame({
    'feature': X.columns,
    'importance': model.feature_importances_
}).sort_values('importance', ascending=False)

print("\n🎯 Feature Importance:")
for idx, row in feature_importance.iterrows():
    print(f"   {row['feature']:.<30} {row['importance']:.3f}")

if not os.path.exists('models'):
    os.makedirs('models')

with open(os.path.join('models', 'pharmacy_ranker.pkl'), 'wb') as f:
    pickle.dump(model, f)

with open(os.path.join('models', 'pharmacy_scaler.pkl'), 'wb') as f:
    pickle.dump(scaler, f)

print("\n✅ Models saved to models\\")