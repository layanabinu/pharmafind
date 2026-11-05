
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.ensemble import GradientBoostingClassifier
from sklearn.metrics import classification_report, roc_auc_score
import pickle
import os

# Change to parent directory (backend folder)
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

print("🔄 Training Refill Risk Model...")

df = pd.read_csv(os.path.join('data', 'refill_training_data.csv'))

X = df[['days_since_last_refill', 'expected_interval', 'criticality',
        'past_missed_count', 'user_adherence_score', 'price']]
y = df['will_miss']

X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42, stratify=y)

print(f"   Training samples: {len(X_train)}")
print(f"   Testing samples: {len(X_test)}")

model = GradientBoostingClassifier(
    n_estimators=50,
    max_depth=4,
    learning_rate=0.1,
    random_state=42
)

print("\n🤖 Training Gradient Boosting...")
model.fit(X_train, y_train)

y_pred = model.predict(X_test)
y_pred_proba = model.predict_proba(X_test)[:, 1]

print("\n📊 Performance:")
print(classification_report(y_test, y_pred, target_names=['Low Risk', 'High Risk']))
print(f"ROC-AUC: {roc_auc_score(y_test, y_pred_proba):.3f}")

if not os.path.exists('models'):
    os.makedirs('models')

with open(os.path.join('models', 'refill_predictor.pkl'), 'wb') as f:
    pickle.dump(model, f)

print("\n✅ Model saved to models\\")