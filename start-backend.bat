@echo off
echo ========================================
echo Starting Distraction Blocker Backend
echo ========================================
echo.

cd blocker-backend

if not exist node_modules (
    echo Installing dependencies...
    call npm install
    echo.
)

echo Starting server...
echo.
call npm start
