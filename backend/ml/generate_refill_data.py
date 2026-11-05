import pandas as pd
import numpy as np
import os

# Change to parent directory (backend folder)
os.chdir(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

np.random.seed(42)

if not os.path.exists('data'):
    os.makedirs('data')

print("🔄 Generating refill training data...")

n_samples = 1000

data = {
    'medicine_id': [f'MED{i:04d}' for i in range(n_samples)],
    'days_since_last_refill': np.random.randint(1, 60, n_samples),
    'expected_interval': np.random.choice([7, 15, 30, 60, 90], n_samples),
    'criticality': np.random.choice([1, 2, 3], n_samples, p=[0.3, 0.5, 0.2]),
    'past_missed_count': np.random.poisson(1.5, n_samples),
    'user_adherence_score': np.random.beta(8, 2, n_samples),
    'price': np.random.uniform(50, 2000, n_samples)
}

df = pd.DataFrame(data)
df['days_overdue'] = df['days_since_last_refill'] - df['expected_interval']

df['will_miss'] = 0
df.loc[df['days_overdue'] > 3, 'will_miss'] = 1
df.loc[(df['criticality'] == 3) & (df['days_overdue'] > 1), 'will_miss'] = 1
df.loc[(df['user_adherence_score'] < 0.6) & (df['days_overdue'] > 0), 'will_miss'] = 1
df.loc[df['past_missed_count'] > 3, 'will_miss'] = 1

flip_indices = np.random.choice(df.index, size=int(0.1 * len(df)), replace=False)
df.loc[flip_indices, 'will_miss'] = 1 - df.loc[flip_indices, 'will_miss']

df = df.drop('days_overdue', axis=1)
df.to_csv(os.path.join('data', 'refill_training_data.csv'), index=False)

print(f"✅ Generated {n_samples} records")
print(f"   High risk: {df['will_miss'].sum()} ({df['will_miss'].mean()*100:.1f}%)")
print(f"   Saved to: data\\refill_training_data.csv")