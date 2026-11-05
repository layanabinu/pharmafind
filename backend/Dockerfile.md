FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE $PORT

CMD uvicorn main:app --host 0.0.0.0 --port $PORT
```

### Step 3 — Verify `requirements.txt`

Make sure this exists in your backend folder with these contents:
```
fastapi
uvicorn
sqlalchemy
psycopg2-binary
passlib
bcrypt
python-jose
fuzzywuzzy
python-Levenshtein
sentence-transformers
torch
numpy
requests
python-dotenv
python-multipart
```

### Step 4 — Update `.gitignore`

Open your `.gitignore` and make sure it contains:
```
venv/
.env
__pycache__/
*.pyc
pharmafind.db