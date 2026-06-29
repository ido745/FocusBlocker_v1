const express = require('express');
const cors = require('cors');
const { v4: uuidv4 } = require('uuid');
const jwt = require('jsonwebtoken');
const { OAuth2Client } = require('google-auth-library');
const fs = require('fs');
const path = require('path');
const ytdl = require('@distube/ytdl-core');

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
          packages: ['com.focusapp.blocker']
        },
        pendingChanges: [],
        deletionProtectionEnabled: false,
        motivation: { videos: [], channels: [], duration: 10 }
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
      if (!user.motivation) user.motivation = { videos: [], channels: [], duration: 10 };
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
    deletionProtectionEnabled: user.deletionProtectionEnabled || false,
    motivation: user.motivation || { videos: [], channels: [], duration: 10 }
  });
});

// ==================================
// MOTIVATION MANAGEMENT
// ==================================

function ensureMotivation(user) {
  if (!user.motivation) user.motivation = { videos: [], channels: [], duration: 10 };
  if (!user.motivation.videos) user.motivation.videos = [];
  if (!user.motivation.channels) user.motivation.channels = [];
  if (user.motivation.duration === undefined) user.motivation.duration = 10;
}

app.post('/motivation/videos', authenticate, (req, res) => {
  const user = db.users[req.user.id];
  if (!user) return res.status(404).json({ success: false, error: 'User not found' });
  const { url, label } = req.body;
  if (!url) return res.status(400).json({ success: false, error: 'url required' });
  ensureMotivation(user);
  user.motivation.videos.push({ url, label: label || null });
  saveData();
  res.json({ success: true, motivation: user.motivation });
});

app.delete('/motivation/videos/:index', authenticate, (req, res) => {
  const user = db.users[req.user.id];
  if (!user) return res.status(404).json({ success: false, error: 'User not found' });
  ensureMotivation(user);
  const idx = parseInt(req.params.index, 10);
  if (isNaN(idx) || idx < 0 || idx >= user.motivation.videos.length)
    return res.status(400).json({ success: false, error: 'Invalid index' });
  user.motivation.videos.splice(idx, 1);
  saveData();
  res.json({ success: true, motivation: user.motivation });
});

app.post('/motivation/channels', authenticate, (req, res) => {
  const user = db.users[req.user.id];
  if (!user) return res.status(404).json({ success: false, error: 'User not found' });
  const { url, label } = req.body;
  if (!url) return res.status(400).json({ success: false, error: 'url required' });
  ensureMotivation(user);
  user.motivation.channels.push({ url, label: label || null });
  saveData();
  res.json({ success: true, motivation: user.motivation });
});

app.delete('/motivation/channels/:index', authenticate, (req, res) => {
  const user = db.users[req.user.id];
  if (!user) return res.status(404).json({ success: false, error: 'User not found' });
  ensureMotivation(user);
  const idx = parseInt(req.params.index, 10);
  if (isNaN(idx) || idx < 0 || idx >= user.motivation.channels.length)
    return res.status(400).json({ success: false, error: 'Invalid index' });
  user.motivation.channels.splice(idx, 1);
  saveData();
  res.json({ success: true, motivation: user.motivation });
});

app.put('/motivation/duration', authenticate, (req, res) => {
  const user = db.users[req.user.id];
  if (!user) return res.status(404).json({ success: false, error: 'User not found' });
  const { duration } = req.body;
  if (typeof duration !== 'number' || duration < 0 || duration > 300)
    return res.status(400).json({ success: false, error: 'duration must be 0-300 seconds' });
  ensureMotivation(user);
  user.motivation.duration = duration;
  saveData();
  res.json({ success: true, motivation: user.motivation });
});

// ==================================
// CHANNEL → RANDOM VIDEO RESOLUTION
// ==================================

function httpGet(url, redirectsLeft = 5, extraHeaders = {}) {
  return new Promise((resolve, reject) => {
    const lib = url.startsWith('https') ? require('https') : require('http');
    const headers = {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
      'Accept-Language': 'en-US,en;q=0.9',
      ...extraHeaders
    };
    lib.get(url, { headers }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirectsLeft > 0) {
        const next = res.headers.location.startsWith('http')
          ? res.headers.location
          : new URL(res.headers.location, url).href;
        res.resume();
        return resolve(httpGet(next, redirectsLeft - 1, extraHeaders));
      }
      let data = '';
      res.on('data', chunk => data += chunk);
      res.on('end', () => resolve(data));
      res.on('error', reject);
    }).on('error', reject);
  });
}

// ── YouTube ──────────────────────────────────────────────────

async function resolveYoutubeChannelId(url) {
  const directMatch = url.match(/youtube\.com\/channel\/([A-Za-z0-9_-]+)/);
  if (directMatch) return directMatch[1];
  try {
    const html = await httpGet(url);
    const m = html.match(/"channelId":"([A-Za-z0-9_-]+)"/);
    if (m) return m[1];
    const m2 = html.match(/youtube\.com\/channel\/([A-Za-z0-9_-]+)/);
    if (m2) return m2[1];
  } catch (e) { /* ignore */ }
  return null;
}

async function getYoutubeChannelVideos(url) {
  const channelId = await resolveYoutubeChannelId(url);
  if (!channelId) return [];
  const feedXml = await httpGet(`https://www.youtube.com/feeds/videos.xml?channel_id=${channelId}`);
  return [...feedXml.matchAll(/<yt:videoId>([^<]+)<\/yt:videoId>/g)]
    .map(m => `https://www.youtube.com/watch?v=${m[1]}`);
}

// ── Instagram ────────────────────────────────────────────────

const NON_PROFILE_IG_PATHS = new Set(['p', 'reel', 'tv', 'stories', 'explore', 'accounts', 'direct', 'reels', 'about', 'blog', 'press', 'jobs', 'legal', 'privacy', 'security', 'help']);

function extractInstagramUsername(url) {
  const m = url.match(/instagram\.com\/([A-Za-z0-9._]+)/);
  if (!m) return null;
  return NON_PROFILE_IG_PATHS.has(m[1]) ? null : m[1];
}

async function getInstagramProfileVideos(url) {
  const username = extractInstagramUsername(url);
  if (!username) return [];

  try {
    // Instagram's internal profile API — works for public accounts with the app-id header
    const body = await httpGet(
      `https://i.instagram.com/api/v1/users/web_profile_info/?username=${encodeURIComponent(username)}`,
      5,
      {
        'x-ig-app-id': '936619743392459',
        'x-asbd-id': '129477',
        'Accept': '*/*',
        'Referer': 'https://www.instagram.com/',
        'Origin': 'https://www.instagram.com'
      }
    );
    const data = JSON.parse(body);
    const edges = data?.data?.user?.edge_owner_to_timeline_media?.edges ?? [];
    const codes = edges
      .filter(e => e.node.is_video || e.node.__typename === 'GraphVideo')
      .map(e => `https://www.instagram.com/reel/${e.node.shortcode}/`);
    if (codes.length) return codes;
  } catch (e) {
    console.log(`Instagram API failed for @${username}: ${e.message}`);
  }

  // Fallback: scrape the profile page HTML for reel/post shortcodes
  try {
    const html = await httpGet(`https://www.instagram.com/${username}/reels/`, 5, {
      'Accept': 'text/html,application/xhtml+xml'
    });
    // shortcodes appear as "/reel/CODE/" or "/p/CODE/" in the HTML
    const codes = [...new Set(
      [...html.matchAll(/\/(reel|p)\/([A-Za-z0-9_-]{8,12})\//g)].map(m => m[2])
    )].map(code => `https://www.instagram.com/reel/${code}/`);
    if (codes.length) return codes;
  } catch (e) {
    console.log(`Instagram scrape failed for @${username}: ${e.message}`);
  }

  return [];
}

// ── TikTok ───────────────────────────────────────────────────

function extractTikTokUsername(url) {
  const m = url.match(/tiktok\.com\/@([A-Za-z0-9._]+)/);
  return m ? m[1] : null;
}

async function getTikTokProfileVideos(url) {
  const username = extractTikTokUsername(url);
  if (!username) return [];

  try {
    const html = await httpGet(`https://www.tiktok.com/@${encodeURIComponent(username)}`, 5, {
      'Accept': 'text/html,application/xhtml+xml',
      'Accept-Encoding': 'identity'
    });

    // TikTok embeds all page data in a SIGI_STATE script tag
    const sigiMatch = html.match(/<script id="SIGI_STATE"[^>]*>([\s\S]*?)<\/script>/);
    if (sigiMatch) {
      const data = JSON.parse(sigiMatch[1]);
      const itemModule = data?.ItemModule ?? {};
      const ids = Object.values(itemModule)
        .map(item => item.id)
        .filter(Boolean);
      if (ids.length) {
        return ids.map(id => `https://www.tiktok.com/@${username}/video/${id}`);
      }
    }

    // Fallback: extract bare numeric video IDs from the raw HTML
    const ids = [...new Set(
      [...html.matchAll(/\/video\/(\d{15,20})/g)].map(m => m[1])
    )];
    if (ids.length) return ids.map(id => `https://www.tiktok.com/@${username}/video/${id}`);
  } catch (e) {
    console.log(`TikTok scrape failed for @${username}: ${e.message}`);
  }

  return [];
}

// ── Endpoint ─────────────────────────────────────────────────

app.get('/channel/random-video', authenticate, async (req, res) => {
  const { url } = req.query;
  if (!url) return res.status(400).json({ success: false, error: 'url required' });

  try {
    let videos = [];

    if (url.includes('youtube.com') || url.includes('youtu.be')) {
      videos = await getYoutubeChannelVideos(url);
    } else if (url.includes('instagram.com')) {
      videos = await getInstagramProfileVideos(url);
    } else if (url.includes('tiktok.com')) {
      videos = await getTikTokProfileVideos(url);
    } else {
      return res.status(400).json({ success: false, error: 'Unsupported platform' });
    }

    if (!videos.length) {
      return res.status(404).json({ success: false, error: 'No videos found for this channel' });
    }

    const videoUrl = videos[Math.floor(Math.random() * videos.length)];
    return res.json({ success: true, videoUrl });
  } catch (e) {
    res.status(500).json({ success: false, error: e.message });
  }
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
    ensuredWhitelist.add('com.focusapp.blocker'); // Always keep our own app whitelisted
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
// VIDEO DOWNLOAD URL RESOLUTION
// ==================================

async function resolveTikTokDownloadUrl(url) {
  const html = await httpGet(url, 5, {
    'Accept': 'text/html,application/xhtml+xml',
    'Accept-Encoding': 'identity'
  });

  const sigiMatch = html.match(/<script id="SIGI_STATE"[^>]*>([\s\S]*?)<\/script>/);
  if (sigiMatch) {
    try {
      const data = JSON.parse(sigiMatch[1]);
      const item = Object.values(data?.ItemModule ?? {})[0];
      const playAddr = item?.video?.playAddr;
      if (playAddr) return playAddr.replace(/\\u002F/g, '/').replace(/\\\//g, '/');
    } catch (e) { /* ignore */ }
  }

  const match = html.match(/"playAddr"\s*:\s*"([^"]+)"/);
  if (match) return match[1].replace(/\\u002F/g, '/').replace(/\\\//g, '/');

  throw new Error('Could not extract TikTok video URL');
}

async function resolveInstagramDownloadUrl(url) {
  const html = await httpGet(url, 5, { 'Accept': 'text/html,application/xhtml+xml' });
  const match = html.match(/<meta[^>]+property="og:video"[^>]+content="([^"]+)"/)
    || html.match(/<meta[^>]+content="([^"]+)"[^>]+property="og:video"/);
  if (match) return match[1].replace(/&amp;/g, '&');
  throw new Error('Could not extract Instagram video URL');
}

app.get('/motivation/download-url', authenticate, async (req, res) => {
  const { url } = req.query;
  if (!url) return res.status(400).json({ success: false, error: 'url required' });

  try {
    let downloadUrl;

    if (url.includes('youtube.com') || url.includes('youtu.be')) {
      const info = await ytdl.getInfo(url);
      let format = ytdl.chooseFormat(info.formats, {
        filter: f => f.hasVideo && f.hasAudio && f.container === 'mp4'
      });
      if (!format) {
        format = ytdl.chooseFormat(info.formats, { filter: 'audioandvideo' });
      }
      if (!format) throw new Error('No downloadable format found for this video');
      downloadUrl = format.url;
    } else if (url.includes('tiktok.com')) {
      downloadUrl = await resolveTikTokDownloadUrl(url);
    } else if (url.includes('instagram.com')) {
      downloadUrl = await resolveInstagramDownloadUrl(url);
    } else {
      return res.status(400).json({ success: false, error: 'Unsupported platform for download' });
    }

    res.json({ success: true, downloadUrl });
  } catch (e) {
    console.error('Download URL resolution error:', e.message);
    res.status(500).json({ success: false, error: e.message });
  }
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
