# Distraction Blocker Backend

A simple Express.js server that manages focus session state and configuration for the Distraction Blocker ecosystem.

## Installation

```bash
npm install
```

## Running the Server

```bash
npm start
```

The server will start on port 3000.

## API Endpoints

### GET /status
Returns the current session state and configuration.

**Response:**
```json
{
  "success": true,
  "data": {
    "isSessionActive": false,
    "blockedPackages": ["com.instagram.android", ...],
    "blockedKeywords": ["gambling", ...],
    "blockedWebsites": ["facebook.com", ...]
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
Updates the blocked apps, keywords, and websites.

**Request Body:**
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

### GET /health
Health check endpoint.

## Testing

Access from Android Emulator:
- Use `http://10.0.2.2:3000` to reach localhost from the emulator

Access from Local Network:
- Use `http://<your-local-ip>:3000` from other devices on your network
