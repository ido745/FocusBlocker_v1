const express = require('express');
const cors = require('cors');
const { v4: uuidv4 } = require('uuid');
const jwt = require('jsonwebtoken');
const bcrypt = require('bcrypt');

const app = express();
const PORT = process.env.PORT || 3000;
const JWT_SECRET = process.env.JWT_SECRET || 'your-secret-key-change-in-production';
const SALT_ROUNDS = 10;

// Middleware
app.use(cors());
app.use(express.json());

// In-memory database (replace with PostgreSQL/MongoDB in production)
const db = {
  users: {}, // { userId: { id, email, passwordHash, name, devices: {}, blocklists: {} } }
  sessions: {}, // { sessionId: { id, userId, isActive, startTime, targetDevices, blockedWebsites, etc } }
  devices: {} // { deviceId: { id, userId, name, type, platform, lastSeen, isOnline } }
};

// ==================================
// MIDDLEWARE - Authentication
// ==================================

function authenticate(req, res, next) {
  const authHeader = req.headers.authorization;

  if (!authHeader || !authHeader.startsWith('Bearer ')) {
    // For backward compatibility with old Android app (no auth)
    req.user = { id: 'default-user', email: 'default@local' };
    return next();
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
// AUTHENTICATION ENDPOINTS
// ==================================

// Register new user with email/password
app.post('/auth/register', async (req, res) => {
  const { email, password, name } = req.body;

  if (!email || !password || !name) {
    return res.status(400).json({ success: false, error: 'Missing required fields' });
  }

  // Check if user already exists
  const existingUser = Object.values(db.users).find(u => u.email === email);
  if (existingUser) {
    return res.status(400).json({ success: false, error: 'Email already registered' });
  }

  try {
    // Hash password
    const passwordHash = await bcrypt.hash(password, SALT_ROUNDS);

    // Create new user
    const user = {
      id: uuidv4(),
      email,
      passwordHash,
      name,
      createdAt: new Date().toISOString(),
      devices: {},
      blocklists: {
        websites: ['facebook.com', 'instagram.com', 'twitter.com'],
        packages: ['com.instagram.android', 'com.facebook.katana'],
        keywords: ['gambling', 'casino']
      },
      whitelists: {
        websites: ['localhost', '10.0.2.2'],
        packages: ['com.focusapp.blocker', 'com.android.settings']
      }
    };

    db.users[user.id] = user;
    console.log(`✅ New user registered: ${email}`);

    // Generate JWT token
    const token = jwt.sign(
      { id: user.id, email: user.email },
      JWT_SECRET,
      { expiresIn: '30d' }
    );

    res.json({
      success: true,
      token,
      user: {
        id: user.id,
        email: user.email,
        name: user.name
      }
    });
  } catch (error) {
    console.error('Registration error:', error);
    res.status(500).json({ success: false, error: 'Registration failed' });
  }
});

// Login with email/password
app.post('/auth/login', async (req, res) => {
  const { email, password } = req.body;

  if (!email || !password) {
    return res.status(400).json({ success: false, error: 'Missing required fields' });
  }

  // Find user by email
  const user = Object.values(db.users).find(u => u.email === email);

  if (!user) {
    return res.status(401).json({ success: false, error: 'Invalid email or password' });
  }

  try {
    // Verify password
    const passwordMatch = await bcrypt.compare(password, user.passwordHash);

    if (!passwordMatch) {
      return res.status(401).json({ success: false, error: 'Invalid email or password' });
    }

    console.log(`✅ User logged in: ${email}`);

    // Generate JWT token
    const token = jwt.sign(
      { id: user.id, email: user.email },
      JWT_SECRET,
      { expiresIn: '30d' }
    );

    res.json({
      success: true,
      token,
      user: {
        id: user.id,
        email: user.email,
        name: user.name
      }
    });
  } catch (error) {
    console.error('Login error:', error);
    res.status(500).json({ success: false, error: 'Login failed' });
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
      deviceCount: Object.keys(user.devices || {}).length
    }
  });
});

// ==================================
// DEVICE MANAGEMENT
// ==================================

// Register or update device
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
    type: deviceType, // "android" or "desktop"
    platform: platform || 'unknown', // "android", "win32", "darwin", "linux"
    lastSeen: new Date().toISOString(),
    isOnline: true
  };

  db.devices[deviceId] = device;

  // Add to user's devices
  if (!db.users[userId]) {
    db.users[userId] = { id: userId, devices: {} };
  }
  if (!db.users[userId].devices) {
    db.users[userId].devices = {};
  }
  db.users[userId].devices[deviceId] = device;

  console.log(`📱 Device registered: ${deviceName} (${deviceType})`);

  res.json({
    success: true,
    device
  });
});

// Get all user devices
app.get('/devices', authenticate, (req, res) => {
  const userId = req.user.id;
  const user = db.users[userId];

  if (!user || !user.devices) {
    return res.json({ success: true, devices: [] });
  }

  const devices = Object.values(user.devices);

  res.json({
    success: true,
    devices
  });
});

// Device heartbeat (updates lastSeen)
app.post('/devices/heartbeat', authenticate, (req, res) => {
  const { deviceId } = req.body;

  if (!deviceId) {
    return res.status(400).json({ success: false, error: 'Missing deviceId' });
  }

  const device = db.devices[deviceId];

  if (device) {
    device.lastSeen = new Date().toISOString();
    device.isOnline = true;
  }

  res.json({ success: true });
});

// ==================================
// SESSION MANAGEMENT (Multi-Device)
// ==================================

// Start a new session
app.post('/sessions/start', authenticate, (req, res) => {
  const userId = req.user.id;
  const { targetDevices, blockedWebsites, blockedPackages, blockedKeywords, duration } = req.body;

  // End any existing active sessions for this user
  Object.values(db.sessions).forEach(session => {
    if (session.userId === userId && session.isActive) {
      session.isActive = false;
      session.endTime = new Date().toISOString();
    }
  });

  // Create new session
  const sessionId = uuidv4();
  const user = db.users[userId];

  const session = {
    id: sessionId,
    userId,
    isActive: true,
    startTime: new Date().toISOString(),
    endTime: duration ? new Date(Date.now() + duration * 1000).toISOString() : null,
    targetDevices: targetDevices || 'all', // "all" or ["device1", "device2"]
    blockedWebsites: blockedWebsites || user?.blocklists?.websites || [],
    blockedPackages: blockedPackages || user?.blocklists?.packages || [],
    blockedKeywords: blockedKeywords || user?.blocklists?.keywords || [],
    whitelistedWebsites: user?.whitelists?.websites || [],
    whitelistedPackages: user?.whitelists?.packages || []
  };

  db.sessions[sessionId] = session;

  console.log(`▶️ Session started by ${req.user.email} - Target: ${targetDevices === 'all' ? 'All Devices' : targetDevices.join(', ')}`);

  res.json({
    success: true,
    message: 'Session started',
    session
  });
});

// Get active session for a device
app.get('/sessions/active', authenticate, (req, res) => {
  const { deviceId } = req.query;
  const userId = req.user.id;

  // Find active session for this user
  const activeSession = Object.values(db.sessions).find(session => {
    if (!session.isActive || session.userId !== userId) {
      return false;
    }

    // Check if session has expired
    if (session.endTime && new Date(session.endTime) < new Date()) {
      session.isActive = false;
      return false;
    }

    // Check if this device is targeted
    if (session.targetDevices === 'all') {
      return true;
    }

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
    res.json({
      success: true,
      session: null
    });
  }
});

// Stop session
app.post('/sessions/stop', authenticate, (req, res) => {
  const sessionId = req.body?.sessionId;
  const userId = req.user.id;

  if (sessionId) {
    // Stop specific session
    const session = db.sessions[sessionId];
    if (session && session.userId === userId) {
      session.isActive = false;
      session.endTime = new Date().toISOString();
    }
  } else {
    // Stop all user's active sessions
    Object.values(db.sessions).forEach(session => {
      if (session.userId === userId && session.isActive) {
        session.isActive = false;
        session.endTime = new Date().toISOString();
      }
    });
  }

  console.log(`⏸️ Session stopped by ${req.user.email}`);

  res.json({
    success: true,
    message: 'Session stopped'
  });
});

// ==================================
// BLOCKLIST MANAGEMENT
// ==================================

// Get user's blocklists and whitelists
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

// Update user's blocklists
app.post('/config', authenticate, (req, res) => {
  const userId = req.user.id;
  const { blockedWebsites, blockedPackages, blockedKeywords, whitelistedWebsites, whitelistedPackages } = req.body;

  const user = db.users[userId];

  if (!user) {
    return res.status(404).json({ success: false, error: 'User not found' });
  }

  if (!user.blocklists) user.blocklists = {};
  if (!user.whitelists) user.whitelists = {};

  if (blockedWebsites !== undefined) {
    user.blocklists.websites = blockedWebsites;
  }
  if (blockedPackages !== undefined) {
    user.blocklists.packages = blockedPackages;
  }
  if (blockedKeywords !== undefined) {
    user.blocklists.keywords = blockedKeywords;
  }
  if (whitelistedWebsites !== undefined) {
    user.whitelists.websites = whitelistedWebsites;
  }
  if (whitelistedPackages !== undefined) {
    // Always ensure our app is whitelisted
    const ensuredWhitelist = new Set(whitelistedPackages);
    ensuredWhitelist.add('com.focusapp.blocker');
    user.whitelists.packages = Array.from(ensuredWhitelist);
  }

  // SYNC to any active sessions for this user
  // This ensures that changes made during an active session take effect immediately
  Object.values(db.sessions).forEach(session => {
    if (session.userId === userId && session.isActive) {
      session.blockedWebsites = user.blocklists.websites || [];
      session.blockedPackages = user.blocklists.packages || [];
      session.blockedKeywords = user.blocklists.keywords || [];
      session.whitelistedWebsites = user.whitelists.websites || [];
      session.whitelistedPackages = user.whitelists.packages || [];
      console.log(`🔄 Active session ${session.id} updated with new blocklists`);
    }
  });

  console.log(`⚙️ Config updated for ${user.email}`);

  res.json({
    success: true,
    blocklists: user.blocklists,
    whitelists: user.whitelists
  });
});

// ==================================
// BACKWARD COMPATIBILITY (for old Android app)
// ==================================

// Legacy /status endpoint
app.get('/status', (req, res) => {
  // For backward compatibility, return default user's data
  const defaultUser = db.users['default-user'] || {
    blocklists: { websites: [], packages: [], keywords: [] },
    whitelists: { websites: [], packages: [] }
  };

  // Check if there's an active session for default user
  const activeSession = Object.values(db.sessions).find(s =>
    s.userId === 'default-user' && s.isActive
  );

  res.json({
    success: true,
    data: {
      isSessionActive: activeSession ? true : false,
      blockedPackages: defaultUser.blocklists?.packages || [],
      blockedKeywords: defaultUser.blocklists?.keywords || [],
      blockedWebsites: defaultUser.blocklists?.websites || [],
      whitelistedPackages: defaultUser.whitelists?.packages || ['com.focusapp.blocker'],
      whitelistedWebsites: defaultUser.whitelists?.websites || []
    }
  });
});

// Legacy /toggle endpoint
app.post('/toggle', (req, res) => {
  const userId = 'default-user';

  // Find active session for default user
  const activeSession = Object.values(db.sessions).find(s =>
    s.userId === userId && s.isActive
  );

  if (activeSession) {
    // Stop session
    activeSession.isActive = false;
    activeSession.endTime = new Date().toISOString();
    res.json({
      success: true,
      message: 'Session deactivated',
      data: { isSessionActive: false }
    });
  } else {
    // Start session
    const defaultUser = db.users[userId] || {
      id: userId,
      blocklists: { websites: [], packages: [], keywords: [] },
      whitelists: { websites: [], packages: [] }
    };

    if (!db.users[userId]) {
      db.users[userId] = defaultUser;
    }

    const sessionId = uuidv4();
    const session = {
      id: sessionId,
      userId,
      isActive: true,
      startTime: new Date().toISOString(),
      endTime: null,
      targetDevices: 'all',
      blockedWebsites: defaultUser.blocklists?.websites || [],
      blockedPackages: defaultUser.blocklists?.packages || [],
      blockedKeywords: defaultUser.blocklists?.keywords || [],
      whitelistedWebsites: defaultUser.whitelists?.websites || [],
      whitelistedPackages: defaultUser.whitelists?.packages || []
    };

    db.sessions[sessionId] = session;

    res.json({
      success: true,
      message: 'Session activated',
      data: { isSessionActive: true }
    });
  }
});

// ==================================
// HEALTH & STATS
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
  console.log('📡 Focus Blocker Backend V2');
  console.log('=================================');
  console.log(`Server running on port ${PORT}`);
  console.log(`Health: http://localhost:${PORT}/health`);
  console.log('');
  console.log('🔐 Authentication:');
  console.log(`  POST /auth/register - Register new user`);
  console.log(`  POST /auth/login - Login with email/password`);
  console.log(`  GET  /auth/me - Get current user`);
  console.log('');
  console.log('📱 Devices:');
  console.log(`  POST /devices/register - Register device`);
  console.log(`  GET  /devices - List devices`);
  console.log('');
  console.log('🎯 Sessions:');
  console.log(`  POST /sessions/start - Start session`);
  console.log(`  GET  /sessions/active - Get active session`);
  console.log(`  POST /sessions/stop - Stop session`);
  console.log('');
  console.log('⚙️  Configuration:');
  console.log(`  GET  /config - Get blocklists`);
  console.log(`  POST /config - Update blocklists`);
  console.log('=================================');
  console.log('✅ Server is ready! Press Ctrl+C to stop.\n');
});

// Error handling
server.on('error', (error) => {
  if (error.code === 'EADDRINUSE') {
    console.error(`❌ Port ${PORT} is already in use!`);
    console.error('Please close any other instances or change the port.');
  } else {
    console.error('❌ Server error:', error);
  }
  process.exit(1);
});

// Graceful shutdown
process.on('SIGINT', () => {
  console.log('\n⏹️  Shutting down server...');
  server.close(() => {
    console.log('✅ Server closed');
    process.exit(0);
  });
});

process.on('SIGTERM', () => {
  console.log('\n⏹️  Shutting down server...');
  server.close(() => {
    console.log('✅ Server closed');
    process.exit(0);
  });
});
