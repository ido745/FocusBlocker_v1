# Quick Start Guide - Distraction Blocker

Get up and running in 5 minutes!

## Step 1: Start the Backend (2 minutes)

```bash
cd blocker-backend
npm install
npm start
```

You should see:
```
📡 Distraction Blocker Backend
Server running on port 3000
```

**Keep this terminal window open!**

## Step 2: Open the Android App (1 minute)

1. Launch **Android Studio**
2. Click **"Open"** or **"Open an Existing Project"**
3. Navigate to `FocusApp/DistractionBlocker`
4. Click **"OK"**
5. Wait for Gradle sync (you'll see a progress bar at the bottom)

## Step 3: Run the App (1 minute)

1. Make sure your **emulator is running** or your **physical device is connected**
2. Click the green **"Run"** button (▶️) in Android Studio toolbar
3. Select your device from the list
4. Wait for the app to install and launch

## Step 4: Enable Accessibility Service (1 minute)

Once the app opens:

1. Tap the **"Open Accessibility Settings"** button
2. Find **"Focus Blocker"** in the list
3. Toggle it **ON**
4. Tap **"Allow"** on the permission dialog
5. Press the **back button** to return to the app

The app should now show the backend status!

## Step 5: Test It! (30 seconds)

### From your computer:

```bash
# Check the current status
node desktop-trigger.js status

# Start a focus session
node desktop-trigger.js start
```

### On your Android device:

1. Try to open **Instagram** or **Facebook** (if you have them installed)
2. The app should **immediately return to home screen**! 🎉

### Stop the session:

```bash
node desktop-trigger.js stop
```

Now you can open apps normally again.

---

## 🎯 What's Next?

### Customize Your Blocking List

In the Android app:

1. **Add your distracting apps:**
   - Scroll to "Blocked Apps"
   - Enter package names like `com.instagram.android`
   - Tap the "+" button

2. **Add blocked keywords:**
   - Scroll to "Blocked Keywords"
   - Enter words like "gambling", "casino"
   - Tap the "+" button

3. **Add blocked websites:**
   - Scroll to "Blocked Websites"
   - Enter domains like "reddit.com", "facebook.com"
   - Tap the "+" button

### Common Package Names

- Instagram: `com.instagram.android`
- Facebook: `com.facebook.katana`
- Twitter: `com.twitter.android`
- TikTok: `com.zhiliaoapp.musically`
- YouTube: `com.google.android.youtube`
- Reddit: `com.reddit.frontpage`

To find package names:
```bash
adb shell pm list packages | grep <app-name>
```

---

## 🐛 Troubleshooting

### "Failed to connect to server"

**For Emulator:**
- The default URL `http://10.0.2.2:3000` should work
- Make sure the backend server is running

**For Physical Device:**
1. Find your computer's IP address:
   - Windows: `ipconfig` → look for "IPv4 Address"
   - Mac/Linux: `ifconfig` → look for "inet"
2. In the Android app, update Server URL to `http://<your-ip>:3000`
3. Make sure both devices are on the **same WiFi network**

### Blocking isn't working

Check these:
- ✅ Backend server is running
- ✅ Accessibility service is enabled
- ✅ Session is active (green status card)
- ✅ App package name is correct

### Can't find Android Studio emulator

In Android Studio:
1. Tools → Device Manager
2. Click the "Play" button next to any emulator
3. Wait for it to boot up
4. Then click "Run" in your project

---

## 🎉 Success!

You now have a complete distraction blocking system!

**Desktop Commands:**
```bash
node desktop-trigger.js status   # Check status
node desktop-trigger.js start    # Start blocking
node desktop-trigger.js stop     # Stop blocking
node desktop-trigger.js toggle   # Toggle on/off
```

**Pro tip:** Create keyboard shortcuts or scripts to trigger focus sessions instantly!

---

## 📚 Need More Help?

- Full documentation: [README.md](README.md)
- Backend docs: [blocker-backend/README.md](blocker-backend/README.md)
- Android docs: [DistractionBlocker/README.md](DistractionBlocker/README.md)

**Happy focusing!** 🎯✨
