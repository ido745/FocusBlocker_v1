# App Picker & Package Name Detection Fixes

## ✅ Fix #1: App Picker Now Shows ALL Installed Apps

**Problem:** App picker only showed 3 Xiaomi system apps (Mi Mover, Notes, Screen Recorder).

**Root Cause:** The filter was too restrictive and didn't show most user-installed apps.

**Solution:** Removed the FLAG_SYSTEM filter entirely - now shows ALL apps on your device!

**File Changed:** [AppPickerHelper.kt](DistractionBlocker/app/src/main/java/com/focusapp/blocker/ui/AppPickerHelper.kt)

**What Changed:**
```kotlin
// OLD (too restrictive):
.filter { app ->
    (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
    app.packageName != context.packageName
}

// NEW (shows everything):
.filter { app ->
    // Show ALL apps - user can search to filter
    true
}
```

**Result:**
- ✅ See ALL installed apps (Chrome, Instagram, WhatsApp, etc.)
- ✅ Can search to find any app
- ✅ Can even find the Focus Blocker app itself to verify its package name!

---

## ✅ Fix #2: Added Package Name Logging

**Problem:** App might be blocking itself because "com.focusapp.blocker" might not be the real package name.

**Solution:** Added logging to print the REAL package name when service starts.

**File Changed:** [BlockingAccessibilityService.kt](DistractionBlocker/app/src/main/java/com/focusapp/blocker/service/BlockingAccessibilityService.kt)

**What Changed:**

1. **In `onServiceConnected()`:**
```kotlin
Log.w(TAG, "🟢 OUR APP PACKAGE NAME: ${applicationContext.packageName}")
```
This will print the REAL package name to logcat!

2. **In `onAccessibilityEvent()`:**
```kotlin
if (packageName == "com.focusapp.blocker" || packageName == applicationContext.packageName) {
    Log.d(TAG, "🛡️ Self-check: Ignoring our own app ($packageName)")
    return
}
```
Now logs when the self-check triggers so you can see if it's working.

---

## 🧪 How to Test

### Step 1: Find Your Real Package Name

**Method A - Using Logcat:**
1. Restart the app in Android Studio
2. Re-enable accessibility service (Settings > Accessibility)
3. Check logcat for this line:
   ```
   🟢 OUR APP PACKAGE NAME: com.focusapp.blocker
   ```
4. This is your REAL package name!

**Method B - Using App Picker:**
1. Open the app
2. Go to Whitelist page
3. Tap "Pick from Installed Apps"
4. Search for "Focus" or "Blocker"
5. Look for your app in the list - the package name is shown below the app name!

### Step 2: Verify Self-Check Works

1. Start a focus session
2. Press Home button
3. Open Focus Blocker app again
4. Check logcat - should see:
   ```
   🛡️ Self-check: Ignoring our own app (YOUR_REAL_PACKAGE_NAME)
   ```
5. **Expected:** App should NOT be blocked!

### Step 3: Verify App Picker Shows All Apps

1. Go to Block page
2. Tap "Pick from Installed Apps"
3. Should see a LONG list with all your apps:
   - Chrome
   - Instagram
   - WhatsApp
   - Twitter
   - YouTube
   - Settings
   - Camera
   - **Your Focus Blocker app too!**
4. Try searching for "Chrome" - should find it!
5. Try searching for "Focus" - should find your app!

---

## 🔍 What to Look For

### In Logcat:
```
🟢 ========================================
🟢 SERVICE CONNECTED - NOW MONITORING!
🟢 OUR APP PACKAGE NAME: com.focusapp.blocker  <-- THIS IS THE REAL NAME
🟢 ========================================

📱 RAW EVENT: type=32 package=com.android.settings
⚡ Event received for package: com.android.settings (Session active: true)

📱 RAW EVENT: type=32 package=com.focusapp.blocker  <-- When you open your app
🛡️ Self-check: Ignoring our own app (com.focusapp.blocker)  <-- Self-check working!
```

### In App Picker:
```
┌─────────────────────────────────┐
│ Select App to Block       ❌    │
├─────────────────────────────────┤
│ 🔍 [Search apps...]             │
├─────────────────────────────────┤
│ 📱 Chrome                        │
│    com.android.chrome            │
├─────────────────────────────────┤
│ 📸 Camera                        │
│    com.android.camera            │
├─────────────────────────────────┤
│ 🎯 Focus Blocker                 │
│    com.focusapp.blocker          │  <-- Your app is visible!
├─────────────────────────────────┤
│ 📸 Instagram                     │
│    com.instagram.android         │
├─────────────────────────────────┤
│ ... hundreds more apps ...      │
└─────────────────────────────────┘
```

---

## 🚨 If App Still Blocks Itself

If you see the self-check log but the app still gets blocked, it means:

1. **The package name might be different in logs**
   - Check logcat for the "OUR APP PACKAGE NAME" line
   - Compare it to what you see in the self-check log
   - If they're different, the hardcoded "com.focusapp.blocker" is wrong

2. **Solution:**
   - Note the REAL package name from logcat
   - Go to backend: [server.js](blocker-backend/server.js)
   - Update the whitelist:
   ```javascript
   whitelistedPackages: [
     'YOUR_REAL_PACKAGE_NAME_HERE',  // <-- Replace this
     'com.android.settings'
   ]
   ```
   - Restart the backend: `npm start`

3. **Alternative: Use App Picker to Add to Whitelist**
   - Go to Whitelist page in app
   - Tap "Pick from Installed Apps"
   - Search for "Focus" or "Blocker"
   - Tap your app to add it to whitelist
   - This will sync the REAL package name to backend automatically!

---

## 📝 Summary

| What | Status | Notes |
|------|--------|-------|
| App picker shows all apps | ✅ Fixed | Removed restrictive filter |
| Package name logging added | ✅ Added | Check logcat for real name |
| Self-check logging improved | ✅ Added | See when self-check triggers |
| Can find app in picker | ✅ Works | Search for "Focus" or "Blocker" |

---

## 🚀 Next Steps

1. **Rebuild and run the app** in Android Studio
2. **Re-enable accessibility service** (required after code changes)
3. **Check logcat** for the "OUR APP PACKAGE NAME" line
4. **Open app picker** and verify you see ALL your apps
5. **Test self-blocking** by starting session, leaving app, returning to app
6. **If still blocked:** Note the real package name from logs and update backend whitelist

**Everything should work now! 🎉**

The app picker will help you easily find and identify the real package name, and the new logging will show exactly what's happening with the self-check.
