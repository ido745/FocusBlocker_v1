# Distraction Blocker Ecosystem

A complete focus management system consisting of an Android app, backend server, and desktop control script to help you stay productive by blocking distracting apps, keywords, and websites.

## 🎯 Project Overview

This ecosystem includes three components:

1. **Backend Server** (Node.js/Express) - Manages focus session state and configuration
2. **Android App** (Kotlin/Jetpack Compose) - Monitors and blocks distracting content using AccessibilityService
3. **Desktop Trigger** (Node.js script) - Control focus sessions from your computer

## 🏗️ Architecture

```
┌─────────────────┐
│  Desktop Script │ ──┐
└─────────────────┘   │
                      │ HTTP/REST
┌─────────────────┐   │
│   Android App   │ ──┼──> ┌────────────────┐
│ (Accessibility) │   │    │ Backend Server │
└─────────────────┘   └──> │   (Express)    │
                            └────────────────┘
```

## 📁 Project Structure

```
FocusApp/
├── blocker-backend/          # Node.js Express server
│   ├── server.js             # Main server file
│   ├── package.json
│   └── README.md
│
├── DistractionBlocker/       # Android app
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/focusapp/blocker/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── data/
│   │   │   │   │   ├── ApiService.kt
│   │   │   │   │   ├── BlockerRepository.kt
│   │   │   │   │   └── PreferencesManager.kt
│   │   │   │   ├── service/
│   │   │   │   │   └── BlockingAccessibilityService.kt
│   │   │   │   └── ui/
│   │   │   │       └── MainViewModel.kt
│   │   │   ├── res/
│   │   │   │   ├── xml/
│   │   │   │   │   └── accessibility_service_config.xml
│   │   │   │   └── values/
│   │   │   └── AndroidManifest.xml
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── desktop-trigger.js        # Desktop control script
└── README.md                 # This file
```

## 🚀 Quick Start

### Step 1: Start the Backend Server

```bash
cd blocker-backend
npm install
npm start
```

The server will start on `http://localhost:3000`

### Step 2: Set Up the Android App

1. Open Android Studio
2. Select "Open an Existing Project"
3. Navigate to the `DistractionBlocker` folder
4. Wait for Gradle sync to complete
5. Run the app on your emulator or device

### Step 3: Configure the Android App

1. The app will open with default settings
2. The backend URL is pre-configured as `http://10.0.2.2:3000` (for emulator)
3. Tap "Open Accessibility Settings"
4. Find "Focus Blocker" and enable it
5. Return to the app

### Step 4: Test the System

```bash
# Check status
node desktop-trigger.js status

# Start a focus session
node desktop-trigger.js start

# Try opening a blocked app (e.g., Instagram) on Android
# It should immediately return to home screen

# Stop the focus session
node desktop-trigger.js stop
```

## 🎛️ How It Works

### Backend Server

The server maintains the global state:
- `isSessionActive` - Boolean indicating if blocking is enabled
- `blockedPackages` - List of Android app package names to block
- `blockedKeywords` - List of keywords to detect in screen content
- `blockedWebsites` - List of websites to block in browsers

### Android App

The `BlockingAccessibilityService` runs in the background:

1. **Polls the server** every 5 seconds for status updates
2. **Monitors app launches** via accessibility events
3. **Checks conditions**:
   - Is the current app in the blocked list?
   - Is the user viewing a blocked website in a browser?
   - Does the screen content contain blocked keywords?
4. **Takes action**: If blocking is active and a condition is met, triggers `GLOBAL_ACTION_HOME`

### Desktop Trigger

Simple CLI tool to control focus sessions:
- `toggle` - Switch session on/off
- `start` - Start a focus session
- `stop` - Stop the current session
- `status` - View current configuration

## 🔧 Configuration

### Adding Blocked Apps

**Via Android App:**
1. Open the app
2. Scroll to "Blocked Apps"
3. Enter package name (e.g., `com.instagram.android`)
4. Tap "+"

**Finding Package Names:**
```bash
# List all installed packages
adb shell pm list packages

# Search for a specific app
adb shell pm list packages | grep instagram
```

### Adding Blocked Keywords

Keywords are case-insensitive and trigger blocking if found anywhere on screen:
- "gambling" blocks any page containing the word
- "bet", "casino", etc.

### Adding Blocked Websites

Enter domain names only:
- `facebook.com` (not `https://facebook.com`)
- `reddit.com`
- `twitter.com`

## 📱 Supported Browsers

The app detects URLs in these browsers:
- Chrome (`com.android.chrome`)
- Firefox (`org.mozilla.firefox`)
- Microsoft Edge (`com.microsoft.emmx`)
- Brave (`com.brave.browser`)
- Opera (`com.opera.browser`)

## 🧪 Testing

### Test 1: App Blocking

1. Start backend server
2. Open Android app and add Chrome to blocked apps: `com.android.chrome`
3. Run `node desktop-trigger.js start`
4. Try to open Chrome on Android
5. **Expected**: Immediately returns to home screen

### Test 2: Keyword Blocking

1. Add "test" to blocked keywords in the app
2. Ensure session is active
3. Open any app (e.g., Notes) and type "test"
4. **Expected**: Returns to home screen when keyword appears

### Test 3: Website Blocking

1. Add "google.com" to blocked websites
2. Ensure session is active
3. Open Chrome and navigate to google.com
4. **Expected**: Returns to home screen when URL loads

## 🐛 Troubleshooting

### Backend Connection Issues

**Problem**: Android app shows "Failed to connect to server"

**Solutions**:
- Ensure backend is running: `cd blocker-backend && npm start`
- For **emulator**: Use `http://10.0.2.2:3000`
- For **physical device**:
  1. Find your computer's IP: `ipconfig` (Windows) or `ifconfig` (Mac/Linux)
  2. Update the URL in the app to `http://<your-ip>:3000`
  3. Ensure both devices are on the same network

### Blocking Not Working

**Problem**: Apps aren't being blocked

**Checklist**:
- ✅ Accessibility service is enabled (Settings > Accessibility > Focus Blocker)
- ✅ Session is active (green status card in app)
- ✅ App package name is correctly entered
- ✅ Backend server is running and reachable

**Debug**:
```bash
# View Android logs
adb logcat | grep BlockingService
```

### Accessibility Permission Denied

**Problem**: Can't enable accessibility service

**Solution**:
- Some devices (especially Xiaomi, Huawei) have additional security settings
- Go to Settings > Apps > Focus Blocker > Permissions
- Enable all required permissions
- Try enabling accessibility service again

## 🔐 Security & Privacy

- All blocking happens **locally** on your device
- The backend server runs **on your local network**
- No data is sent to external servers
- No analytics or tracking
- Accessibility permissions are only used for the blocking feature

## 📊 API Reference

### GET /status

Returns current session state and configuration.

**Response:**
```json
{
  "success": true,
  "data": {
    "isSessionActive": false,
    "blockedPackages": ["com.instagram.android"],
    "blockedKeywords": ["gambling"],
    "blockedWebsites": ["facebook.com"]
  }
}
```

### POST /toggle

Toggles the focus session on/off.

**Response:**
```json
{
  "success": true,
  "message": "Session activated",
  "data": {
    "isSessionActive": true
  }
}
```

### POST /config

Updates blocked content lists.

**Request:**
```json
{
  "blockedPackages": ["com.instagram.android"],
  "blockedKeywords": ["gambling", "casino"],
  "blockedWebsites": ["facebook.com"]
}
```

**Response:**
```json
{
  "success": true,
  "message": "Configuration updated successfully",
  "data": { ... }
}
```

## 🎨 Customization Ideas

### Desktop App Enhancement

Create a full desktop GUI using:
- Electron (cross-platform)
- Tauri (lightweight)
- Qt (native)

### Android Features

- **Timer**: Auto-start/stop sessions at scheduled times
- **Notifications**: Alert when trying to access blocked content
- **Stats**: Track how many times you tried to access blocked apps
- **Profiles**: Different blocking configurations for "Work", "Study", "Sleep"

### Backend Enhancements

- **Database**: Replace in-memory storage with MongoDB/PostgreSQL
- **User accounts**: Multi-user support
- **Scheduling**: Time-based blocking rules
- **Analytics**: Track blocking events and productivity metrics

## 🛠️ Development

### Backend

```bash
cd blocker-backend
npm install
npm start
```

### Android

1. Open `DistractionBlocker` in Android Studio
2. Build > Make Project
3. Run > Run 'app'

### Testing Desktop Trigger

```bash
# Test toggle
node desktop-trigger.js toggle

# Test status
node desktop-trigger.js status

# Test help
node desktop-trigger.js help
```

## 📝 Common Package Names

Here are some common app package names for blocking:

**Social Media:**
- Instagram: `com.instagram.android`
- Facebook: `com.facebook.katana`
- Twitter: `com.twitter.android`
- TikTok: `com.zhiliaoapp.musically`
- Snapchat: `com.snapchat.android`
- Reddit: `com.reddit.frontpage`

**Entertainment:**
- YouTube: `com.google.android.youtube`
- Netflix: `com.netflix.mediaclient`
- Spotify: `com.spotify.music`
- Twitch: `tv.twitch.android.app`

**Games:**
- Candy Crush: `com.king.candycrushsaga`
- PUBG: `com.tencent.ig`
- Among Us: `com.innersloth.spacemafia`

**Browsers:**
- Chrome: `com.android.chrome`
- Firefox: `org.mozilla.firefox`
- Brave: `com.brave.browser`

## 📄 License

This is a personal productivity tool. Feel free to modify and adapt it to your needs.

## 🙏 Acknowledgments

Built with:
- Express.js
- Android AccessibilityService API
- Jetpack Compose
- Kotlin Coroutines
- Retrofit

---

**Stay focused, stay productive!** 🎯✨
