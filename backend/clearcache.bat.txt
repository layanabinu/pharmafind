@echo off
echo Clearing Python bytecode cache...
if exist __pycache__ rmdir /s /q __pycache__
echo Done! Now restart the backend:
echo uvicorn main:app --reload