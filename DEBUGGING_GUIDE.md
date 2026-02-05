# Debugging Guide - Focus Blocker

If blocking isn't working, follow these steps to diagnose the issue.

## Step 1: Check Logcat

In Android Studio, open **Logcat** (bottom toolbar) and filter by `BlockingService`:

```
Filter: BlockingService
```

You should see logs like:
```
BlockingService: 📡 Initializing with server URL: http://10.0.2.2:3000
BlockingService: Initial blocked packages: 3
BlockingService: ✅ Status: Active=true | Apps=3 | Keywords=3 | Sites=4
BlockingService: Event received for package: com.instagram.android (Session active: true)
BlockingService: 🚫 BLOCKING APP: com.instagram.android
```

### What to Look For:

1. **Service initialization:**
   ```
   BlockingService: Service created
   BlockingService: Service connected
   BlockingService: 📡 Initializing with server URL: ...
   ```
   ✅ If you see this, the service is running!

2. **Status polling:**
   ```
   BlockingService: ✅ Status: Active=true | Apps=3 | Keywords=3 | Sites=4
   ```
   ✅ Should appear every 3 seconds
   ❌ If you see `Failed to fetch status`, there's a connection issue

3. **Session state:**
   ```
   BlockingService: 🔄 Session state changed: ACTIVE
   ```
   ✅ Should appear when you toggle the session

4. **Event detection:**
   ```
   BlockingService: Event received for package: com.instagram.android (Session active: true)
   ```
   ✅ Should appear when you open apps

5. **Blocking action:**
   ```
   BlockingService: 🚫 BLOCKING APP: com.instagram.android
   ```
   ✅ Should appear when blocked content is detected

## Step 2: Common Issues & Fixes

### Issue 1: "Service created" but no "Service connected"

**Problem:** Accessibility service not properly enabled.

**Fix:**
1. Go to Settings > Accessibility
2. Find "Focus Blocker"
3. Toggle it OFF, then ON again
4. Accept the permission dialog
5. Restart the app

### Issue 2: "❌ Failed to fetch status"

**Problem:** Can't connect to backend server.

**Fix:**
1. Make sure backend is running: `cd blocker-backend && npm start`
2. Check the server URL in the app
3. For emulator: Should be `http://10.0.2.2:3000`
4. For physical device: Use your computer's local IP

Test connection manually:
```bash
# In terminal
curl http://10.0.2.2:3000/status
```

Should return:
```json
{"success":true,"data":{"isSessionActive":false,...}}
```

### Issue 3: "Session active: false" even after toggling

**Problem:** Backend not responding or session toggle failed.

**Fix:**
1. Check backend logs - should show:
   ```
   [timestamp] Session ACTIVATED
   ```
2. Test with desktop trigger:
   ```bash
   node desktop-trigger.js status
   ```
3. Try toggling from desktop:
   ```bash
   node desktop-trigger.js toggle
   ```

### Issue 4: No "Event received" logs when opening apps

**Problem:** Accessibility events not being captured.

**Fix:**
1. Check accessibility service config is correct
2. Try rebooting the device/emulator
3. Disable and re-enable the accessibility service
4. Check if Android's battery optimization is killing the service:
   - Settings > Apps > Focus Blocker > Battery > Unrestricted

### Issue 5: Events received but no blocking

**Problem:** Blocking conditions not met.

**Debug:**
Check the logs show:
- `Session active: true` ✅
- Package name matches blocked list ✅
- Actually triggering the block action ✅

Make sure the package name is EXACTLY correct:
```bash
# Find the exact package name
adb shell pm list packages | grep instagram
# Should show: package:com.instagram.android
```

## Step 3: Test with Known Apps

Try these guaranteed-to-work tests:

### Test 1: Block Chrome

1. Add to blocked apps: `com.android.chrome`
2. Save and sync
3. Toggle session ON
4. Open Chrome
5. **Expected:** Immediately returns to home with toast "🚫 Focus Mode: App is blocked..."

### Test 2: Block Keyword

1. Add keyword: `test`
2. Toggle session ON
3. Open Notes app
4. Type "test" in a note
5. **Expected:** Should return to home (may take 1-2 seconds)

### Test 3: Block Website

1. Add website: `google.com`
2. Toggle session ON
3. Open Chrome
4. Navigate to google.com
5. **Expected:** Should return to home when URL loads

## Step 4: View All Logs

If still not working, capture full logs:

```bash
# In terminal
adb logcat -s BlockingService:* > blocker-logs.txt
```

Then:
1. Toggle the session ON
2. Try to open a blocked app
3. Stop the log capture (Ctrl+C)
4. Check `blocker-logs.txt` for errors

## Step 5: Nuclear Option - Full Reset

If nothing works:

1. **Uninstall the app completely**
2. **Clear accessibility services:**
   - Settings > Accessibility > Installed services
   - Make sure Focus Blocker is gone
3. **Restart the emulator/device**
4. **Reinstall the app**
5. **Enable accessibility service again**
6. **Check logs from the beginning**

## Expected Behavior

When everything is working correctly:

1. ✅ Backend shows: `Session ACTIVATED`
2. ✅ Logcat shows: `Status: Active=true`
3. ✅ Opening blocked app shows toast: `🚫 Focus Mode: App is blocked...`
4. ✅ Immediately returns to home screen
5. ✅ Logcat shows: `BLOCKING APP: com.instagram.android`

## Quick Logcat Commands

```bash
# View only BlockingService logs
adb logcat -s BlockingService:*

# View last 100 lines
adb logcat -s BlockingService:* -t 100

# Save to file
adb logcat -s BlockingService:* > logs.txt

# Clear log and start fresh
adb logcat -c && adb logcat -s BlockingService:*
```

## Need More Help?

If still not working after all these steps:
1. Capture the full log output
2. Note the exact steps you're taking
3. Note what you see in Logcat at each step
4. Check if Toast notifications appear (they should now!)
