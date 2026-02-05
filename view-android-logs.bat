@echo off
echo ============================================
echo Focus Blocker Android Logs
echo ============================================
echo.
echo Watching logs... (Press Ctrl+C to stop)
echo.
adb logcat -s BlockingService:*
