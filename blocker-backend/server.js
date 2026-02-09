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
// Can be set via GOOGLE_CLIENT_IDS env var (comma-separated) or defaults to hardcoded values
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
// GOOGLE AUTHENTICATION
// ==================================

// Verify Google ID token and login/register user
app.post('/auth/google', async (req, res) => {
  const { idToken } = req.body;

  if (!idToken) {
    return res.status(400).json({ success: false, error: 'Missing ID token' });
  }

  try {
    // Verify the Google ID token (accepts tokens from any of our client IDs)
    const ticket = await googleClient.verifyIdToken({
      idToken: idToken,
      audience: GOOGLE_CLIENT_IDS
    });

    const payload = ticket.getPayload();
    const googleId = payload['sub'];
    const email = payload['email'];
    const name = payload['name'];
    const picture = payload['picture'];

    // Find or create user
    let user = Object.values(db.users).find(u => u.googleId === googleId);

    if (!user) {
      // Create new user
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
        }
      };
      db.users[user.id] = user;
      saveData();
      console.log(`New user registered via Google: ${email}`);
    } else {
      // Update user info
      user.email = email;
      user.name = name;
      user.picture = picture;
      saveData();
      console.log(`User logged in via Google: ${email}`);
    }

    // Generate JWT token
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

// Get current user info
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
// SESSION MANAGEMENT
// ==================================

app.post('/sessions/start', authenticate, (req, res) => {
  const userId = req.user.id;
  const { targetDevices, blockedWebsites, blockedPackages, blockedKeywords, duration } = req.body;

  // End any existing active sessions
  Object.values(db.sessions).forEach(session => {
    if (session.userId === userId && session.isActive) {
      session.isActive = false;
      session.endTime = new Date().toISOString();
    }
  });

  const sessionId = uuidv4();
  const user = db.users[userId];

  const session = {
    id: sessionId,
    userId,
    isActive: true,
    startTime: new Date().toISOString(),
    endTime: duration ? new Date(Date.now() + duration * 1000).toISOString() : null,
    targetDevices: targetDevices || 'all',
    blockedWebsites: blockedWebsites || user?.blocklists?.websites || [],
    blockedPackages: blockedPackages || user?.blocklists?.packages || [],
    blockedKeywords: blockedKeywords || user?.blocklists?.keywords || [],
    whitelistedWebsites: user?.whitelists?.websites || [],
    whitelistedPackages: user?.whitelists?.packages || []
  };

  db.sessions[sessionId] = session;
  saveData();

  console.log(`Session started by ${req.user.email}`);

  res.json({ success: true, message: 'Session started', session });
});

app.get('/sessions/active', authenticate, (req, res) => {
  const { deviceId } = req.query;
  const userId = req.user.id;

  const activeSession = Object.values(db.sessions).find(session => {
    if (!session.isActive || session.userId !== userId) return false;

    if (session.endTime && new Date(session.endTime) < new Date()) {
      session.isActive = false;
      saveData();
      return false;
    }

    if (session.targetDevices === 'all') return true;
    if (Array.isArray(session.targetDevices) && deviceId) {
      return session.targetDevices.includes(deviceId);
    }

    return false;
  });

  if (activeSession) {
    res.json({
      success: true,
      session: {
        id: activeSession.id,
        isActive: true,
        blockedWebsites: activeSession.blockedWebsites,
        blockedPackages: activeSession.blockedPackages,
        blockedKeywords: activeSession.blockedKeywords,
        whitelistedWebsites: activeSession.whitelistedWebsites,
        whitelistedPackages: activeSession.whitelistedPackages,
        startTime: activeSession.startTime,
        endTime: activeSession.endTime
      }
    });
  } else {
    res.json({ success: true, session: null });
  }
});

app.post('/sessions/stop', authenticate, (req, res) => {
  const sessionId = req.body?.sessionId;
  const userId = req.user.id;

  if (sessionId) {
    const session = db.sessions[sessionId];
    if (session && session.userId === userId) {
      session.isActive = false;
      session.endTime = new Date().toISOString();
    }
  } else {
    Object.values(db.sessions).forEach(session => {
      if (session.userId === userId && session.isActive) {
        session.isActive = false;
        session.endTime = new Date().toISOString();
      }
    });
  }

  saveData();
  console.log(`Session stopped by ${req.user.email}`);

  res.json({ success: true, message: 'Session stopped' });
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

  res.json({
    success: true,
    blocklists: user.blocklists || { websites: [], packages: [], keywords: [] },
    whitelists: user.whitelists || { websites: [], packages: [] }
  });
});

app.post('/config', authenticate, (req, res) => {
  const userId = req.user.id;
  const { blockedWebsites, blockedPackages, blockedKeywords, whitelistedWebsites, whitelistedPackages } = req.body;

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

  // Sync to active sessions
  Object.values(db.sessions).forEach(session => {
    if (session.userId === userId && session.isActive) {
      session.blockedWebsites = user.blocklists.websites || [];
      session.blockedPackages = user.blocklists.packages || [];
      session.blockedKeywords = user.blocklists.keywords || [];
      session.whitelistedWebsites = user.whitelists.websites || [];
      session.whitelistedPackages = user.whitelists.packages || [];
    }
  });

  saveData();
  console.log(`Config updated for ${user.email}`);

  res.json({
    success: true,
    blocklists: user.blocklists,
    whitelists: user.whitelists
  });
});

// ==================================
// HEALTH CHECK
// ==================================

app.get('/health', (req, res) => {
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
  console.log('Focus Blocker Backend V3');
  console.log('With Google Sign-In & Persistence');
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
  console.log('Sessions:');
  console.log('  POST /sessions/start');
  console.log('  GET  /sessions/active');
  console.log('  POST /sessions/stop');
  console.log('');
  console.log('Config:');
  console.log('  GET  /config');
  console.log('  POST /config');
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
