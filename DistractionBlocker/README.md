# Focus Blocker - Android App

An Android app that blocks distracting apps, keywords, and websites during focus sessions using AccessibilityService.

## Features

- **App Blocking**: Blocks specific apps by package name (e.g., Instagram, Facebook)
- **Keyword Blocking**: Scans screen content and blocks pages containing specific keywords
- **Website Blocking**: Detects and blocks websites in browsers (Chrome, Firefox, etc.)
- **Remote Control**: Syncs with backend server to enable/disable blocking from any device
- **Jetpack Compose UI**: Modern, intuitive interface to manage blocked content

## Prerequisites

- Android Studio (latest version)
- Android device or emulator running Android 7.0 (API 24) or higher
- Backend server running (see blocker-backend folder)

## Setup Instructions

### 1. Open in Android Studio

1. Open Android Studio
2. Select "Open an Existing Project"
3. Navigate to the `DistractionBlocker` folder
4. Click "OK"

### 2. Sync Gradle

Android Studio will automatically sync Gradle dependencies. If not, click "Sync Project with Gradle Files" in the toolbar.

### 3. Configure Backend URL

The default backend URL is `http://10.0.2.2:3000`, which is the localhost address accessible from the Android emulator.

If testing on a physical device:
1. Find your computer's local IP address
2. Update the URL in the app to `http://<your-local-ip>:3000`

### 4. Run the App

1. Start your Android emulator or connect a physical device
2. Click the "Run" button (green play icon) in Android Studio
3. Select your target device

## Enabling the Accessibility Service

The app requires accessibility permissions to monitor and block apps:

1. Open the app
2. Tap "Open Accessibility Settings"
3. Find "Focus Blocker" in the list
4. Toggle it ON
5. Accept the permission dialog

**Note**: Android will warn you about accessibility services. This is normal - the service needs these permissions to detect and block distracting apps.

## How It Works

### BlockingAccessibilityService

The core blocking logic is in `BlockingAccessibilityService.kt`:

1. **Status Polling**: Every 5 seconds, the service checks the backend server for the current session status
2. **App Detection**: When an app launches, it checks if the app's package name is in the blocked list
3. **Keyword Scanning**: For all apps, it recursively scans the UI tree for blocked keywords
4. **Website Detection**: For browsers, it reads the URL bar and checks against blocked websites
5. **Blocking Action**: If a match is found and the session is active, it triggers `GLOBAL_ACTION_HOME` to return to the home screen

### Architecture

```
MainActivity (Jetpack Compose UI)
    ↓
MainViewModel (State management)
    ↓
BlockerRepository (Network calls)
    ↓
ApiService (Retrofit interface)

BlockingAccessibilityService (Background monitoring)
    ↓
PreferencesManager (Local storage)
```

## Configuration

### Adding Blocked Apps

1. In the app, scroll to "Blocked Apps"
2. Enter the package name (e.g., `com.instagram.android`)
3. Tap the "+" button

**Finding Package Names**:
- Use apps like "Package Name Viewer" from Play Store
- Or use ADB: `adb shell pm list packages | grep <app-name>`

### Adding Blocked Keywords

Keywords are case-insensitive and will trigger blocking if found anywhere on screen:
- "gambling" will block pages containing "gambling", "Gambling", "GAMBLING", etc.

### Adding Blocked Websites

Enter just the domain name:
- `facebook.com` (not `https://facebook.com`)
- `reddit.com`

## Testing

### Test App Blocking

1. Start the backend server
2. Add a test app to the blocked list (e.g., Chrome: `com.android.chrome`)
3. Use the desktop trigger to activate the session
4. Open Chrome - it should immediately return to home screen

### Test Keyword Blocking

1. Add "test" to blocked keywords
2. Activate the session
3. Open any app and type "test" in a visible text field
4. The app should return to home screen

### Test Website Blocking

1. Add "google.com" to blocked websites
2. Activate the session
3. Open Chrome and navigate to google.com
4. Should return to home screen when URL is detected

## Troubleshooting

### "Failed to connect to server"

- Ensure the backend server is running
- Check the server URL in the app
- For emulator, use `http://10.0.2.2:3000`
- For physical device, use your computer's local IP

### Blocking not working

- Verify the accessibility service is enabled
- Check that the session is active (green status card)
- Look at Logcat in Android Studio for debug logs (filter by "BlockingService")

### App crashes or doesn't compile

- Clean and rebuild: Build > Clean Project, then Build > Rebuild Project
- Invalidate caches: File > Invalidate Caches / Restart
- Check Gradle sync completed successfully

## Development

### Key Files

- `MainActivity.kt` - Main UI
- `MainViewModel.kt` - UI state management
- `BlockingAccessibilityService.kt` - Core blocking logic
- `ApiService.kt` - Backend API interface
- `PreferencesManager.kt` - Local data storage

### Debugging

View logs in Android Studio Logcat:
```
adb logcat | grep BlockingService
```

### Building APK

1. Build > Build Bundle(s) / APK(s) > Build APK(s)
2. APK location: `app/build/outputs/apk/debug/app-debug.apk`

## Security & Privacy

- The accessibility service only runs when enabled by the user
- All data is stored locally on the device
- Network calls only go to your configured backend server
- No data is collected or sent to third parties

## License

This is a personal project for focus and productivity improvement.
