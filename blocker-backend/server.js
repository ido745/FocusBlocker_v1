const express = require('express');
const cors = require('cors');

const app = express();
const PORT = 3000;

// Middleware
app.use(cors());
app.use(express.json());

// In-memory state
let state = {
  isSessionActive: false,
  blockedPackages: [
    'com.instagram.android',
    'com.facebook.katana',
    'com.twitter.android'
  ],
  blockedKeywords: [
    'gambling',
    'casino',
    'bet'
  ],
  blockedWebsites: [
    'facebook.com',
    'instagram.com',
    'twitter.com',
    'reddit.com'
  ],
  whitelistedPackages: [
    'com.focusapp.blocker',  // Always whitelist our own app
    'com.android.settings'    // Settings app
  ],
  whitelistedWebsites: [
    'localhost',
    '10.0.2.2'  // Emulator localhost
  ]
};

// GET /status - Returns current blocking status and configuration
app.get('/status', (req, res) => {
  res.json({
    success: true,
    data: state
  });
});

// POST /toggle - Toggles the blocking session on/off
app.post('/toggle', (req, res) => {
  state.isSessionActive = !state.isSessionActive;
  console.log(`[${new Date().toISOString()}] Session ${state.isSessionActive ? 'ACTIVATED' : 'DEACTIVATED'}`);

  res.json({
    success: true,
    message: `Session ${state.isSessionActive ? 'activated' : 'deactivated'}`,
    data: {
      isSessionActive: state.isSessionActive
    }
  });
});

// POST /config - Updates blocked apps, keywords, websites, and whitelists
app.post('/config', (req, res) => {
  const { blockedPackages, blockedKeywords, blockedWebsites, whitelistedPackages, whitelistedWebsites } = req.body;

  if (blockedPackages !== undefined) {
    state.blockedPackages = blockedPackages;
  }

  if (blockedKeywords !== undefined) {
    state.blockedKeywords = blockedKeywords;
  }

  if (blockedWebsites !== undefined) {
    state.blockedWebsites = blockedWebsites;
  }

  if (whitelistedPackages !== undefined) {
    // Always ensure our own app is whitelisted
    const ensuredWhitelist = new Set(whitelistedPackages);
    ensuredWhitelist.add('com.focusapp.blocker');
    state.whitelistedPackages = Array.from(ensuredWhitelist);
  }

  if (whitelistedWebsites !== undefined) {
    state.whitelistedWebsites = whitelistedWebsites;
  }

  console.log(`[${new Date().toISOString()}] Configuration updated`);

  res.json({
    success: true,
    message: 'Configuration updated successfully',
    data: state
  });
});

// Health check endpoint
app.get('/health', (req, res) => {
  res.json({
    success: true,
    message: 'Server is running',
    timestamp: new Date().toISOString()
  });
});

// Start server
app.listen(PORT, '0.0.0.0', () => {
  console.log('=================================');
  console.log('📡 Distraction Blocker Backend');
  console.log('=================================');
  console.log(`Server running on port ${PORT}`);
  console.log(`Status: http://localhost:${PORT}/status`);
  console.log(`Health: http://localhost:${PORT}/health`);
  console.log('=================================');
  console.log('Current Configuration:');
  console.log(`Session Active: ${state.isSessionActive}`);
  console.log(`Blocked Apps: ${state.blockedPackages.length}`);
  console.log(`Blocked Keywords: ${state.blockedKeywords.length}`);
  console.log(`Blocked Websites: ${state.blockedWebsites.length}`);
  console.log('=================================\n');
});
