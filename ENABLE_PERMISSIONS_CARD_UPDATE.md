# Enable Permissions Card Update ✅

## What Changed

### 1. Renamed Card
- **Old:** "⚙️ Accessibility Service"
- **New:** "⚠️ Enable Permissions"

### 2. Updated Content
Changed from technical service status to user-friendly permission instructions:

**New Card Shows:**
- Clear title: "⚠️ Enable Permissions"
- Explanation: "This app requires Accessibility Service permissions to block apps and websites."
- Step-by-step instructions:
  1. Tap the button below
  2. Find "Focus Blocker" in the list
  3. Toggle it ON
  4. Accept the permission
- Button: "Open Accessibility Settings"

### 3. Reordered Cards
**New Order on Home Page:**
1. **Focus Mode** toggle card (Session Active/Inactive)
2. **Enable Permissions** card (only if permissions not granted) ← Moved here
3. **Server URL** section
4. **Statistics** card

**Before:**
```
[Enable Permissions Card] ← Was here
[Focus Mode Card]
[Server URL]
[Stats]
```

**After:**
```
[Focus Mode Card]
[Enable Permissions Card] ← Now here (only if needed)
[Server URL]
[Stats]
```

### 4. Behavior
- ✅ Appears when permissions are NOT granted
- ✅ Disappears within 500ms when permissions are granted
- ✅ Reappears if permissions are revoked
- ✅ Checks status every 500ms for instant updates

## UI Preview

### When Permissions NOT Granted:
```
┌─────────────────────────────────────────┐
│ 🔴 Focus Mode                           │
│ Session Inactive                        │
│ [Start Session]                         │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│ ⚠️ Enable Permissions                   │
│                                         │
│ This app requires Accessibility         │
│ Service permissions to block apps       │
│ and websites.                           │
│                                         │
│ How to enable:                          │
│ 1. Tap the button below                │
│ 2. Find "Focus Blocker" in the list    │
│ 3. Toggle it ON                         │
│ 4. Accept the permission                │
│                                         │
│ [Open Accessibility Settings]           │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│ Server URL: http://10.0.0.10:3000      │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│ 📊 Statistics                            │
└─────────────────────────────────────────┘
```

### When Permissions Granted:
```
┌─────────────────────────────────────────┐
│ 🟢 Focus Mode                           │
│ Session Active                          │
│ [Stop Session]                          │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────┐
│ Server URL: http://10.0.0.10:3000      │
└─────────────────────────────────────────┘
                                          ← Clean! No permissions card
┌─────────────────────────────────────────┐
│ 📊 Statistics                            │
└─────────────────────────────────────────┘
```

## Files Changed

- [MainActivity.kt](DistractionBlocker/app/src/main/java/com/focusapp/blocker/MainActivity.kt)
  - Reordered cards in `HomePage` function
  - Added new `EnablePermissionsCard` function
  - Kept old `ServiceStatusCard` (unused, can be removed later)

## Testing

1. **Disable accessibility service:**
   - Settings > Accessibility > Focus Blocker > Toggle OFF

2. **Open the app:**
   - Go to Home page
   - Should see Focus Mode card at top
   - **Enable Permissions card appears BELOW it** with clear instructions

3. **Enable service:**
   - Tap "Open Accessibility Settings" button
   - Enable Focus Blocker service
   - Return to app
   - Enable Permissions card **disappears within 500ms**

4. **Clean interface when enabled:**
   - Only see: Focus Mode, Server URL, Statistics
   - No permissions card cluttering the UI!

## Summary

✅ Card renamed to "Enable Permissions"
✅ Clear step-by-step instructions for users
✅ Positioned below Focus Mode card (makes more sense)
✅ Disappears when permissions granted
✅ Reappears if permissions revoked
✅ Professional, user-friendly UX!

The card now provides much clearer guidance for users who need to enable permissions, and stays out of the way once everything is configured! 🎉
