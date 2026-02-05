@echo off
echo Checking if Focus Blocker accessibility service is enabled...
echo.
adb shell settings get secure enabled_accessibility_services
echo.
echo.
echo If you see "com.focusapp.blocker" in the output above, the service is enabled.
echo If not, you need to enable it in Settings.
pause
