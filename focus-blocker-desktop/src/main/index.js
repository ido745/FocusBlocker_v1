const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const activeWin = require('active-win');
const Store = require('electron-store');
const fetch = require('node-fetch');
const { v4: uuidv4 } = require('uuid');
const os = require('os');

const store = new Store();
const API_URL = 'http://127.0.0.1:3000'; // Use IPv4 instead of localhost

let mainWindow;
let overlayWindow;
let tray;
let browserMonitorInterval;
let sessionPollInterval;
let currentSession = null;
let authToken = null;
let deviceId = null;

// ==================================
// APP LIFECYCLE
// ==================================

app.whenReady().then(() => {
  initializeApp();
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit();
  }
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) {
    createMainWindow();
  }
});

// ==================================
// INITIALIZATION
// ==================================

async function initializeApp() {
  // Load auth token and device ID
  authToken = store.get('authToken');
  deviceId = store.get('deviceId') || uuidv4();
  store.set('deviceId', deviceId);

  // Create windows
  createMainWindow();
  createOverlayWindow();
  createSystemTray();

  // If logged in, register device and start monitoring
  if (authToken) {
    await registerDevice();
    startBrowserMonitoring();
    startSessionPolling();
  }

  // Set up IPC handlers
  setupIPC();
}

// ==================================
// WINDOW MANAGEMENT
// ==================================

function createMainWindow() {
  mainWindow = new BrowserWindow({
    width: 900,
    height: 700,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false
    }
    // icon: path.join(__dirname, '../../assets/icons/icon.png') // Add when you have an icon
  });

  mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'));

  // Development mode
  if (process.argv.includes('--dev')) {
    mainWindow.webContents.openDevTools();
  }
}

function createOverlayWindow() {
  overlayWindow = new BrowserWindow({
    fullscreen: true,
    alwaysOnTop: true,
    frame: false,
    transparent: false,
    skipTaskbar: true,
    closable: false,
    backgroundColor: '#00FF00',
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false
    }
  });

  overlayWindow.loadFile(path.join(__dirname, '../renderer/overlay.html'));
  overlayWindow.hide();
  overlayWindow.setIgnoreMouseEvents(false);

  // Prevent overlay from being closed
  overlayWindow.on('close', (event) => {
    if (!app.isQuitting) {
      event.preventDefault();
      overlayWindow.hide();
    }
  });
}

function createSystemTray() {
  try {
    const iconPath = path.join(__dirname, '../../assets/icons/icon.png');
    const fs = require('fs');

    if (fs.existsSync(iconPath)) {
      const icon = nativeImage.createFromPath(iconPath);
      tray = new Tray(icon.resize({ width: 16, height: 16 }));
    } else {
      // Create empty icon if file doesn't exist
      const icon = nativeImage.createEmpty();
      tray = new Tray(icon);
    }

    updateTrayMenu();

    tray.on('click', () => {
      mainWindow.show();
      mainWindow.focus();
    });
  } catch (error) {
    console.error('System tray creation error:', error);
    // Continue without tray if it fails
  }
}

function updateTrayMenu() {
  const contextMenu = Menu.buildFromTemplate([
    {
      label: currentSession ? '⏸️ Stop Session' : '▶️ Start Session',
      click: () => {
        if (currentSession) {
          stopSession();
        } else {
          startSession('all', []);
        }
      }
    },
    { type: 'separator' },
    {
      label: 'Show App',
      click: () => {
        mainWindow.show();
        mainWindow.focus();
      }
    },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => {
        app.isQuitting = true;
        app.quit();
      }
    }
  ]);

  tray.setContextMenu(contextMenu);
  tray.setToolTip(currentSession ? 'Focus Blocker - Session Active' : 'Focus Blocker - Idle');
}

// ==================================
// BROWSER MONITORING
// ==================================

function startBrowserMonitoring() {
  if (browserMonitorInterval) return;

  console.log('🔍 Browser monitoring started!');

  browserMonitorInterval = setInterval(async () => {
    try {
      const window = await activeWin();

      if (!window) return;

      const isBrowser = checkIfBrowser(window.owner.name);

      if (isBrowser) {
        const url = extractUrlFromTitle(window.title);
        console.log('🌐 Browser detected:', window.owner.name, '| Title:', window.title, '| URL:', url);

        if (currentSession && currentSession.isActive) {
          console.log('✅ Session active, checking if blocked...');

          if (url && isUrlBlocked(url)) {
            console.log('🚫 URL IS BLOCKED! Showing overlay...');
            showOverlay(url);
          } else {
            console.log('✅ URL allowed');
            hideOverlay();
          }
        } else {
          console.log('⏸️ No active session');
          hideOverlay();
        }
      } else {
        hideOverlay();
      }
    } catch (error) {
      console.error('Browser monitoring error:', error);
    }
  }, 2000); // Changed to 2 seconds for easier debugging
}

function stopBrowserMonitoring() {
  if (browserMonitorInterval) {
    clearInterval(browserMonitorInterval);
    browserMonitorInterval = null;
  }
  hideOverlay();
}

function checkIfBrowser(appName) {
  const browsers = ['chrome', 'firefox', 'safari', 'edge', 'brave', 'opera', 'vivaldi'];
  const lowerName = appName.toLowerCase();
  return browsers.some(b => lowerName.includes(b));
}

function extractUrlFromTitle(title) {
  // Extract domain from browser window title
  // Common patterns:
  // "Facebook - Google Chrome"
  // "Instagram | Profile - Mozilla Firefox"
  // "twitter.com - Brave"
  // "https://www.facebook.com/..." - Edge sometimes shows full URL

  if (!title) return null;

  // First, try to extract a full domain with TLD (e.g., "facebook.com")
  const fullDomainMatch = title.match(/([a-z0-9-]+\.[a-z]{2,}(?:\.[a-z]{2,})?)/i);
  if (fullDomainMatch) {
    return fullDomainMatch[1].toLowerCase();
  }

  // If no full domain found, extract the site name before dash/pipe
  // "Facebook - Chrome" -> "facebook"
  // "Instagram | Profile - Firefox" -> "instagram"
  const siteNameMatch = title.match(/^([a-zA-Z0-9]+)(?:\s*[-|])/);
  if (siteNameMatch) {
    const siteName = siteNameMatch[1].trim().toLowerCase();
    // Filter out browser names
    const browserNames = ['chrome', 'firefox', 'safari', 'edge', 'brave', 'opera'];
    if (!browserNames.includes(siteName) && siteName.length > 2) {
      return siteName;
    }
  }

  // Last resort: try to extract first word if it looks like a site name
  const firstWordMatch = title.match(/^([a-zA-Z0-9]+)/);
  if (firstWordMatch) {
    const firstWord = firstWordMatch[1].toLowerCase();
    const browserNames = ['chrome', 'firefox', 'safari', 'edge', 'brave', 'opera'];
    if (!browserNames.includes(firstWord) && firstWord.length > 2) {
      return firstWord;
    }
  }

  return null;
}

function isUrlBlocked(url) {
  if (!currentSession || !currentSession.blockedWebsites) return false;

  const domain = url.toLowerCase();

  // Check whitelist first
  if (currentSession.whitelistedWebsites) {
    for (const whitelisted of currentSession.whitelistedWebsites) {
      const whitelistedLower = whitelisted.toLowerCase();
      if (domain.includes(whitelistedLower) || whitelistedLower.includes(domain)) {
        return false;
      }
    }
  }

  // Check blocklist - match both ways:
  // - "facebook.com" in blocklist matches "facebook" from title
  // - "facebook" in blocklist matches "facebook.com" from title
  for (const blocked of currentSession.blockedWebsites) {
    const blockedLower = blocked.toLowerCase();

    // Direct match or substring match
    if (domain.includes(blockedLower) || blockedLower.includes(domain)) {
      return true;
    }

    // Also check if domain is site name and blocked is full domain
    // e.g., domain="facebook" and blocked="facebook.com"
    const blockedBase = blockedLower.split('.')[0]; // "facebook.com" -> "facebook"
    if (domain === blockedBase || blockedBase.includes(domain)) {
      return true;
    }
  }

  return false;
}

// ==================================
// OVERLAY MANAGEMENT
// ==================================

function showOverlay(url) {
  overlayWindow.webContents.send('update-blocked-url', url);
  overlayWindow.show();
  overlayWindow.focus();
  overlayWindow.setFullScreen(true);
}

function hideOverlay() {
  overlayWindow.hide();
}

// ==================================
// SESSION MANAGEMENT
// ==================================

function startSessionPolling() {
  if (sessionPollInterval) return;

  sessionPollInterval = setInterval(async () => {
    try {
      const response = await apiRequest(`/sessions/active?deviceId=${deviceId}`);

      if (response.success && response.session) {
        currentSession = response.session;
      } else {
        currentSession = null;
      }

      updateTrayMenu();

      // Send session update to main window
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('session-updated', currentSession);
      }
    } catch (error) {
      console.error('Session polling error:', error);
    }
  }, 3000);
}

async function startSession(targetDevices, blockedWebsites, blockedPackages, blockedKeywords) {
  try {
    const body = { targetDevices };
    if (blockedWebsites) body.blockedWebsites = blockedWebsites;
    if (blockedPackages) body.blockedPackages = blockedPackages;
    if (blockedKeywords) body.blockedKeywords = blockedKeywords;

    const response = await apiRequest('/sessions/start', {
      method: 'POST',
      body: JSON.stringify(body)
    });

    if (response.success) {
      currentSession = response.session;
      updateTrayMenu();
      return { success: true };
    }

    return { success: false, error: 'Failed to start session' };
  } catch (error) {
    console.error('Start session error:', error);
    return { success: false, error: error.message };
  }
}

async function stopSession() {
  try {
    await apiRequest('/sessions/stop', {
      method: 'POST',
      body: JSON.stringify({})
    });

    currentSession = null;
    hideOverlay();
    updateTrayMenu();

    return { success: true };
  } catch (error) {
    console.error('Stop session error:', error);
    return { success: false, error: error.message };
  }
}

// ==================================
// DEVICE MANAGEMENT
// ==================================

async function registerDevice() {
  try {
    await apiRequest('/devices/register', {
      method: 'POST',
      body: JSON.stringify({
        deviceId,
        deviceName: os.hostname(),
        deviceType: 'desktop',
        platform: os.platform()
      })
    });

    console.log('✅ Device registered');
  } catch (error) {
    console.error('Device registration error:', error);
  }
}

// ==================================
// API COMMUNICATION
// ==================================

async function apiRequest(endpoint, options = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...(authToken && { 'Authorization': `Bearer ${authToken}` })
  };

  const response = await fetch(`${API_URL}${endpoint}`, {
    ...options,
    headers
  });

  return response.json();
}

async function register(email, password, name) {
  try {
    console.log('🔐 Registering user:', email);

    const response = await fetch(`${API_URL}/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password, name })
    });

    const data = await response.json();

    if (data.success) {
      authToken = data.token;
      store.set('authToken', authToken);

      console.log('✅ Registration successful, registering device...');
      await registerDevice();

      console.log('🔍 Starting browser monitoring...');
      startBrowserMonitoring();

      console.log('📊 Starting session polling...');
      startSessionPolling();

      return { success: true, user: data.user };
    }

    return { success: false, error: data.error || 'Registration failed' };
  } catch (error) {
    console.error('❌ Registration error:', error);
    return { success: false, error: error.message };
  }
}

async function login(email, password) {
  try {
    console.log('🔐 Logging in user:', email);

    const response = await fetch(`${API_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password })
    });

    const data = await response.json();

    if (data.success) {
      authToken = data.token;
      store.set('authToken', authToken);

      console.log('✅ Login successful, registering device...');
      await registerDevice();

      console.log('🔍 Starting browser monitoring...');
      startBrowserMonitoring();

      console.log('📊 Starting session polling...');
      startSessionPolling();

      return { success: true, user: data.user };
    }

    return { success: false, error: data.error || 'Login failed' };
  } catch (error) {
    console.error('❌ Login error:', error);
    return { success: false, error: error.message };
  }
}

// ==================================
// IPC HANDLERS
// ==================================

function setupIPC() {
  // Authentication
  ipcMain.handle('register', async (event, { email, password, name }) => {
    console.log('📧 Registration attempt:', email);
    const result = await register(email, password, name);
    console.log('✅ Registration result:', result.success ? 'Success' : 'Failed');
    return result;
  });

  ipcMain.handle('login', async (event, { email, password }) => {
    console.log('📧 Login attempt:', email);
    const result = await login(email, password);
    console.log('✅ Login result:', result.success ? 'Success' : 'Failed');
    return result;
  });

  ipcMain.handle('logout', () => {
    authToken = null;
    store.delete('authToken');
    stopBrowserMonitoring();
    if (sessionPollInterval) {
      clearInterval(sessionPollInterval);
      sessionPollInterval = null;
    }
    currentSession = null;
    hideOverlay();
    return { success: true };
  });

  // Session management
  ipcMain.handle('start-session', async (event, { targetDevices, blockedWebsites, blockedPackages, blockedKeywords }) => {
    return await startSession(targetDevices, blockedWebsites, blockedPackages, blockedKeywords);
  });

  ipcMain.handle('stop-session', async () => {
    return await stopSession();
  });

  ipcMain.handle('get-current-session', () => {
    return currentSession;
  });

  // Device management
  ipcMain.handle('get-devices', async () => {
    try {
      const response = await apiRequest('/devices');
      return response;
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  // Configuration
  ipcMain.handle('get-config', async () => {
    try {
      const response = await apiRequest('/config');
      return response;
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  ipcMain.handle('update-config', async (event, config) => {
    try {
      const response = await apiRequest('/config', {
        method: 'POST',
        body: JSON.stringify(config)
      });
      return response;
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  // Check auth status
  ipcMain.handle('is-authenticated', () => {
    return !!authToken;
  });

  // Get this device's ID
  ipcMain.handle('get-device-id', () => {
    return deviceId;
  });

  // Get current user info
  ipcMain.handle('get-user-info', async () => {
    try {
      return await apiRequest('/auth/me');
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  // End session from overlay (emergency exit)
  ipcMain.handle('emergency-stop', async () => {
    await stopSession();
    hideOverlay();
    return { success: true };
  });
}
