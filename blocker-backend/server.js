const express = require('express');
const cors = require('cors');
const { v4: uuidv4 } = require('uuid');
const jwt = require('jsonwebtoken');
const { OAuth2Client } = require('google-auth-library');
const fs = require('fs');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;
const JWT_SECRET = process.env.JWT_SECRET || 'your-secret-key-change-in-production';

// Google OAuth Client IDs - one for each platform
const DEFAULT_CLIENT_IDS = [
  '42261799101-ibarq1tjou7rag3de5aifg0vg68771j8.apps.googleusercontent.com', // Android (Web client)
  '42261799101-98ejerodh6qhv2rg9jd2s0b7ontmc9pj.apps.googleusercontent.com'  // Desktop
];
const GOOGLE_CLIENT_IDS = process.env.GOOGLE_CLIENT_IDS
  ? process.env.GOOGLE_CLIENT_IDS.split(',').map(id => id.trim())
  : DEFAULT_CLIENT_IDS;
const googleClient = new OAuth2Client();

// Middleware
app.use(cors());
app.use(express.json());

// ==================================
// FILE-BASED PERSISTENCE
// ==================================

const DATA_FILE = path.join(__dirname, 'data.json');

function loadData() {
  try {
    if (fs.existsSync(DATA_FILE)) {
      const data = fs.readFileSync(DATA_FILE, 'utf8');
      return JSON.parse(data);
    }
  } catch (error) {
    console.error('Error loading data:', error);
  }
  return {
    users: {},
    sessions: {},
    devices: {}
  };
}

function saveData() {
  try {
    fs.writeFileSync(DATA_FILE, JSON.stringify(db, null, 2));
  } catch (error) {
    console.error('Error saving data:', error);
  }
}

// Load existing data on startup
const db = loadData();

// ==================================
// MIDDLEWARE - Authentication
// ==================================

function authenticate(req, res, next) {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    return res.status(401).json({ success: false, error: 'Authentication required' });
  }

  const token = authHeader.substring(7);

  try {
    const decoded = jwt.verify(token, JWT_SECRET);
    req.user = decoded;
    next();
  } catch (error) {
    res.status(401).json({ success: false, error: 'Invalid token' });
  }
}

// ==================================
// PENDING CHANGES HELPERS
// ==================================

/**
 * Applies any pending changes that have matured (scheduledFor <= now).
 * Returns true if any changes were applied.
 */
function applyMaturePendingChanges(user) {
  if (!user.pendingChanges || user.pendingChanges.length === 0) return false;

  const now = new Date();
  let changed = false;

  user.pendingChanges = user.pendingChanges.filter(change => {
    if (new Date(change.scheduledFor) <= now) {
      applyChange(user, change);
      changed = true;
      console.log(`Applied pending change: ${change.type} = ${change.value} for user ${user.email}`);
      return false; // Remove from pending list
    }
    return true; // Keep in pending list
  });

  return changed;
}

/**
 * Applies a single pending change to the user's config.
 */
function applyChange(user, change) {
  if (!user.blocklists) user.blocklists = { websites: [], packages: [], keywords: [] };
  if (!user.whitelists) user.whitelists = { websites: [], packages: [] };

  switch (change.type) {
    case 'remove_blocked_website':
      user.blocklists.websites = (user.blocklists.websites || []).filter(w => w !== change.value);
      break;
    case 'remove_blocked_package':
      user.blocklists.packages = (user.blocklists.packages || []).filter(p => p !== change.value);
      break;
    case 'remove_blocked_keyword':
      user.blocklists.keywords = (user.blocklists.keywords || []).filter(k => k !== change.value);
      break;
    case 'add_whitelisted_website':
      if (!(user.whitelists.websites || []).includes(change.value)) {
        if (!user.whitelists.websites) user.whitelists.websites = [];
        user.whitelists.websites.push(change.value);
      }
      break;
    case 'add_whitelisted_package':
      if (!(user.whitelists.packages || []).includes(change.value)) {
        if (!user.whitelists.packages) user.whitelists.packages = [];
        user.whitelists.packages.push(change.value);
      }
      break;
    case 'disable_deletion_protection':
      user.deletionProtectionEnabled = false;
      break;
  }
}

// ==================================
// GOOGLE AUTHENTICATION
// ==================================

app.post('/auth/google', async (req, res) => {
  const { idToken } = req.body;

  if (!idToken) {
    return res.status(400).json({ success: false, error: 'Missing ID token' });
  }

  try {
    const ticket = await googleClient.verifyIdToken({
      idToken: idToken,
      audience: GOOGLE_CLIENT_IDS
    });

    const payload = ticket.getPayload();
    const googleId = payload['sub'];
    const email = payload['email'];
    const name = payload['name'];
    const picture = payload['picture'];

    let user = Object.values(db.users).find(u => u.googleId === googleId);

    if (!user) {
      user = {
        id: uuidv4(),
        googleId,
        email,
        name,
        picture,
        createdAt: new Date().toISOString(),
        devices: {},
        blocklists: {
          websites: [],
          packages: [],
          keywords: []
        },
        whitelists: {
          websites: [],
          packages: ['com.focusapp.blocker', 'com.android.settings']
        },
        pendingChanges: [],
        deletionProtectionEnabled: false
      };
      db.users[user.id] = user;
      saveData();
      console.log(`New user registered via Google: ${email}`);
    } else {
      user.email = email;
      user.name = name;
      user.picture = picture;
      // Ensure new fields exist for existing users
      if (!user.pendingChanges) user.pendingChanges = [];
      if (user.deletionProtectionEnabled === undefined) user.deletionProtectionEnabled = false;
      saveData();
      console.log(`User logged in via Google: ${email}`);
    }

    const token = jwt.sign(
      { id: user.id, email: user.email, googleId: user.googleId },
      JWT_SECRET,
      { expiresIn: '30d' }
    );

    res.json({
      success: true,
      token,
      user: {
        id: user.id,
        email: user.email,
        name: user.name,
        picture: user.picture
      }
    });
  } catch (error) {
    console.error('Google auth error:', error);
    res.status(401).json({ success: false, error: 'Invalid Google token' });
  }
});

app.get('/auth/me', authenticate, (req, res) => {
  const user = db.users[req.user.id];

  if (!user) {
    return res.status(404).json({ success: false, error: 'User not found' });
  }

  res.json({
    success: true,
    user: {
      id: user.id,
      email: user.email,
      name: user.name,
      picture: user.picture,
      deviceCount: Object.keys(user.devices || {}).length
    }
  });
});

// ==================================
// DEVICE MANAGEMENT
// ==================================

app.post('/devices/register', authenticate, (req, res) => {
  const { deviceId, deviceName, deviceType, platform } = req.body;
  const userId = req.user.id;

  if (!deviceId || !deviceName || !deviceType) {
    return res.status(400).json({ success: false, error: 'Missing required fields' });
  }

  const device = {
    id: deviceId,
    userId,
    name: deviceName,
    type: deviceType,
    platform: platform || 'unknown',
    lastSeen: new Date().toISOString(),
    isOnline: true
  };

  db.devices[deviceId] = device;

  if (!db.users[userId]) {
    db.users[userId] = { id: userId, devices: {} };
  }
  if (!db.users[userId].devices) {
    db.users[userId].devices = {};
  }
  db.users[userId].devices[deviceId] = device;
  saveData();

  console.log(`Device registered: ${deviceName} (${deviceType})`);

  res.json({ success: true, device });
});

app.get('/devices', authenticate, (req, res) => {
  const userId = req.user.id;
  const user = db.users[userId];

  if (!user || !user.devices) {
    return res.json({ success: true, devices: [] });
  }

  res.json({ success: true, devices: Object.values(user.devices) });
});

app.post('/devices/heartbeat', authenticate, (req, res) => {
  const { deviceId } = req.body;

  if (deviceId && db.devices[deviceId]) {
    db.devices[deviceId].lastSeen = new Date().toISOString();
    db.devices[deviceId].isOnline = true;
  }

  res.json({ success: true });
});

// ==================================
// SESSION MANAGEMENT (kept for compatibility, but sessions are always-on)
// ==================================

app.post('/sessions/start', authenticate, (req, res) => {
  // Sessions are now always-on; this endpoint is kept for compatibility
  const userId = req.user.id;
  const user = db.users[userId];
  res.json({
    success: true,
    message: 'Blocking is always active',
    session: {
      id: 'always-on',
      isActive: true,
      blockedWebsites: user?.blocklists?.websites || [],
      blockedPackages: user?.blocklists?.packages || [],
      blockedKeywords: user?.blocklists?.keywords || [],
      whitelistedWebsites: user?.whitelists?.websites || [],
      whitelistedPackages: user?.whitelists?.packages || []
    }
  });
});

/**
 * GET /sessions/active
 * Now always returns the user's current config as an active session.
 * Also applies any matured pending changes before returning.
 */
app.get('/sessions/active', authenticate, (req, res) => {
  const userId = req.user.id;
  const user = db.users[userId];

  if (!user) {
    return res.json({ success: true, session: null });
  }

  // Apply any pending changes that have matured
  if (applyMaturePendingChanges(user)) {
    saveData();
  }

  // Always return an active session - blocking is always on
  res.json({
    success: true,
    session: {
      id: 'always-on',
      isActive: true,
      blockedWebsites: user.blocklists?.websites || [],
      blockedPackages: user.blocklists?.packages || [],
      blockedKeywords: user.blocklists?.keywords || [],
      whitelistedWebsites: user.whitelists?.websites || [],
      whitelistedPackages: user.whitelists?.packages || [],
      deletionProtectionEnabled: user.deletionProtectionEnabled || false
    }
  });
});

app.post('/sessions/stop', authenticate, (_req, res) => {
  // Sessions are always-on; stopping is not allowed
  res.json({ success: true, message: 'Blocking is always active and cannot be stopped' });
});

// ==================================
// BLOCKLIST MANAGEMENT
// ==================================

app.get('/config', authenticate, (req, res) => {
  const userId = req.user.id;
  const user = db.users[userId];

  if (!user) {
    return res.status(404).json({ success: false, error: 'User not found' });
  }

  // Apply matured pending changes
  if (applyMaturePendingChanges(user)) {
    saveData();
  }

  res.json({
    success: true,
    blocklists: user.blocklists || { websites: [], packages: [], keywords: [] },
    whitelists: user.whitelists || { websites: [], packages: [] },
    deletionProtectionEnabled: user.deletionProtectionEnabled || false
  });
});

app.post('/config', authenticate, (req, res) => {
  const userId = req.user.id;
  const { blockedWebsites, blockedPackages, blockedKeywords, whitelistedWebsites, whitelistedPackages, deletionProtectionEnabled } = req.body;

  const user = db.users[userId];

  if (!user) {
    return res.status(404).json({ success: false, error: 'User not found' });
  }

  if (!user.blocklists) user.blocklists = {};
  if (!user.whitelists) user.whitelists = {};

  if (blockedWebsites !== undefined) user.blocklists.websites = blockedWebsites;
  if (blockedPackages !== undefined) user.blocklists.packages = blockedPackages;
  if (blockedKeywords !== undefined) user.blocklists.keywords = blockedKeywords;
  if (whitelistedWebsites !== undefined) user.whitelists.websites = whitelistedWebsites;
  if (whitelistedPackages !== undefined) {
    const ensuredWhitelist = new Set(whitelistedPackages);
    ensuredWhitelist.add('com.focusapp.blocker');
    user.whitelists.packages = Array.from(ensuredWhitelist);
  }
  // Enable deletion protection immediately (tightening constraint)
  if (deletionProtectionEnabled === true) {
    user.deletionProtectionEnabled = true;
  }

  saveData();
  console.log(`Config updated for ${user.email}`);

  res.json({
    success: true,
    blocklists: user.blocklists,
    whitelists: user.whitelists,
    deletionProtectionEnabled: user.deletionProtectionEnabled || false
  });
});

// ==================================
// PENDING CHANGES MANAGEMENT
// ==================================

/**
 * GET /config/pending
 * Returns all pending (queued) changes for the user.
 */
app.get('/config/pending', authenticate, (req, res) => {
  const userId = req.user.id;
  const user = db.users[userId];

  if (!user) {
    return res.status(404).json({ success: false, error: 'User not found' });
  }

  // Apply any matured changes first
  if (applyMaturePendingChanges(user)) {
    saveData();
  }

  res.json({
    success: true,
    pendingChanges: user.pendingChanges || []
  });
});

/**
 * POST /config/pending
 * Queues a change that relaxes constraints. Takes effect after 24 hours.
 * Body: { type, value }
 * Types: remove_blocked_website, remove_blocked_package, remove_blocked_keyword,
 *        add_whitelisted_website, add_whitelisted_package, disable_deletion_protection
 */
app.post('/config/pending', authenticate, (req, res) => {
  const userId = req.user.id;
  const { type, value } = req.body;

  const user = db.users[userId];
  if (!user) {
    return res.status(404).json({ success: false, error: 'User not found' });
  }

  const validTypes = [
    'remove_blocked_website',
    'remove_blocked_package',
    'remove_blocked_keyword',
    'add_whitelisted_website',
    'add_whitelisted_package',
    'disable_deletion_protection'
  ];

  if (!type || !validTypes.includes(type)) {
    return res.status(400).json({ success: false, error: 'Invalid change type' });
  }

  if (!user.pendingChanges) user.pendingChanges = [];

  // Check if an identical pending change already exists
  const existing = user.pendingChanges.find(c => c.type === type && c.value === value);
  if (existing) {
    return res.json({ success: true, change: existing, message: 'Change already pending' });
  }

  const change = {
    id: uuidv4(),
    type,
    value: value || null,
    createdAt: new Date().toISOString(),
    scheduledFor: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString()
  };

  user.pendingChanges.push(change);
  saveData();

  console.log(`Pending change queued: ${type} = ${value} for ${user.email}, takes effect at ${change.scheduledFor}`);

  res.json({ success: true, change });
});

/**
 * DELETE /config/pending/:id
 * Cancels a pending change (user changed their mind).
 */
app.delete('/config/pending/:id', authenticate, (req, res) => {
  const userId = req.user.id;
  const changeId = req.params.id;

  const user = db.users[userId];
  if (!user) {
    return res.status(404).json({ success: false, error: 'User not found' });
  }

  if (!user.pendingChanges) {
    return res.status(404).json({ success: false, error: 'No pending changes' });
  }

  const index = user.pendingChanges.findIndex(c => c.id === changeId);
  if (index === -1) {
    return res.status(404).json({ success: false, error: 'Change not found' });
  }

  user.pendingChanges.splice(index, 1);
  saveData();

  console.log(`Pending change cancelled: ${changeId} for ${user.email}`);

  res.json({ success: true, message: 'Pending change cancelled' });
});

// ==================================
// HEALTH CHECK
// ==================================

app.get('/health', (_req, res) => {
  res.json({
    success: true,
    message: 'Server is running',
    timestamp: new Date().toISOString(),
    stats: {
      users: Object.keys(db.users).length,
      devices: Object.keys(db.devices).length,
      activeSessions: Object.values(db.sessions).filter(s => s.isActive).length
    }
  });
});

// ==================================
// START SERVER
// ==================================

const server = app.listen(PORT, '0.0.0.0', () => {
  console.log('=================================');
  console.log('Focus Blocker Backend V4');
  console.log('Always-On + Pending Changes');
  console.log('=================================');
  console.log(`Server running on port ${PORT}`);
  console.log(`Health: http://localhost:${PORT}/health`);
  console.log('');
  console.log('Authentication:');
  console.log('  POST /auth/google - Sign in with Google');
  console.log('  GET  /auth/me - Get current user');
  console.log('');
  console.log('Devices:');
  console.log('  POST /devices/register');
  console.log('  GET  /devices');
  console.log('');
  console.log('Sessions (always-on):');
  console.log('  GET  /sessions/active - Always returns user config as active');
  console.log('');
  console.log('Config:');
  console.log('  GET  /config');
  console.log('  POST /config');
  console.log('');
  console.log('Pending Changes:');
  console.log('  GET    /config/pending');
  console.log('  POST   /config/pending');
  console.log('  DELETE /config/pending/:id');
  console.log('=================================');
});

server.on('error', (error) => {
  if (error.code === 'EADDRINUSE') {
    console.error(`Port ${PORT} is already in use!`);
  } else {
    console.error('Server error:', error);
  }
  process.exit(1);
});

process.on('SIGINT', () => {
  console.log('\nShutting down...');
  saveData();
  server.close(() => {
    console.log('Server closed');
    process.exit(0);
  });
});

process.on('SIGTERM', () => {
  console.log('\nShutting down...');
  saveData();
  server.close(() => {
    console.log('Server closed');
    process.exit(0);
  });
});
