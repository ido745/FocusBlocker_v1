const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const activeWin = require('active-win');
const Store = require('electron-store');
const fetch = require('node-fetch');
const { v4: uuidv4 } = require('uuid');
const os = require('os');
const { exec } = require('child_process');

const store = new Store();
const API_URL = 'http://127.0.0.1:3000'; // Use IPv4 instead of localhost
const BLOCKED_REDIRECT_URL = 'https://blocked.freedom.to/';

let mainWindow;
let tray;
let browserMonitorInterval;
let sessionPollInterval;
let currentSession = null;
let authToken = null;
let deviceId = null;
let lastRedirectTime = 0; // Prevent rapid redirects

// ==================================
// APP LIFECYCLE
// ==================================

app.whenReady().then(() => {
  initializeApp();
});

// Set isQuitting flag when app is about to quit
app.on('before-quit', () => {
  app.isQuitting = true;
});

app.on('window-all-closed', () => {
  // Don't quit - keep running in system tray
  // The app will only quit when user clicks "Quit" from tray menu
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
  });

  mainWindow.loadFile(path.join(__dirname, '../renderer/index.html'));

  // Minimize to tray on close instead of quitting
  mainWindow.on('close', (event) => {
    if (!app.isQuitting) {
      event.preventDefault();
      mainWindow.hide();
    }
  });

  // Development mode
  if (process.argv.includes('--dev')) {
    mainWindow.webContents.openDevTools();
  }
}

function createSystemTray() {
  try {
    const iconPath = path.join(__dirname, '../../assets/icons/icon.png');
    const fs = require('fs');

    if (fs.existsSync(iconPath)) {
      const icon = nativeImage.createFromPath(iconPath);
      tray = new Tray(icon.resize({ width: 16, height: 16 }));
    } else {
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
// BROWSER MONITORING & REDIRECT
// ==================================

function startBrowserMonitoring() {
  if (browserMonitorInterval) return;

  console.log('🔍 Browser monitoring started!');

  browserMonitorInterval = setInterval(async () => {
    try {
      const window = await activeWin();

      if (!window) {
        return;
      }

      const isBrowser = checkIfBrowser(window.owner.name);
      console.log('📍 Active window:', window.owner.name, '| Browser:', isBrowser, '| Title:', window.title?.substring(0, 50));

      if (isBrowser) {
        const url = extractUrlFromTitle(window.title);
        console.log('🌐 Extracted URL:', url);

        console.log('📊 Session status:', currentSession ? `Active: ${currentSession.isActive}, Blocked sites: ${JSON.stringify(currentSession.blockedWebsites)}` : 'No session');

        if (currentSession && currentSession.isActive) {
          // Check if we're already on the blocked page (be specific to avoid false matches)
          const titleLower = window.title.toLowerCase();
          if (titleLower.includes('blocked.freedom.to') || titleLower === 'freedom' || titleLower.startsWith('freedom |') || titleLower.startsWith('freedom -')) {
            console.log('✅ Already on blocked page, skipping');
            return;
          }

          if (url) {
            const blocked = isUrlBlocked(url);
            console.log('🔍 Is blocked:', blocked);

            if (blocked) {
              // Prevent rapid redirects (wait at least 1.5 seconds between redirects)
              const now = Date.now();
              if (now - lastRedirectTime < 1500) {
                console.log('⏳ Cooldown active, skipping redirect');
                return;
              }

              console.log('🚫 Blocked site detected:', url, '- Redirecting...');
              lastRedirectTime = now;
              redirectToBlockedPage();
            }
          }
        } else {
          console.log('⏸️ No active session');
        }
      }
    } catch (error) {
      console.error('Browser monitoring error:', error);
    }
  }, 2000); // Check every 2 seconds
}

function stopBrowserMonitoring() {
  if (browserMonitorInterval) {
    clearInterval(browserMonitorInterval);
    browserMonitorInterval = null;
  }
}

function checkIfBrowser(appName) {
  const browsers = ['chrome', 'firefox', 'safari', 'edge', 'brave', 'opera', 'vivaldi', 'msedge'];
  const lowerName = appName.toLowerCase();
  return browsers.some(b => lowerName.includes(b));
}

function extractUrlFromTitle(title) {
  if (!title) return null;

  // Skip if already on blocked page (be specific to avoid false positives)
  const lowerTitle = title.toLowerCase();
  if (lowerTitle.includes('blocked.freedom.to') || lowerTitle === 'freedom' || lowerTitle.startsWith('freedom |') || lowerTitle.startsWith('freedom -')) {
    return null;
  }

  // Try to extract a full domain with TLD (e.g., "facebook.com")
  const fullDomainMatch = title.match(/([a-z0-9-]+\.[a-z]{2,}(?:\.[a-z]{2,})?)/i);
  if (fullDomainMatch) {
    return fullDomainMatch[1].toLowerCase();
  }

  // Check for site name at the END of the title (e.g., "Settings - Reddit", "Home | Facebook")
  // This handles pages like reddit.com/settings where title is "Settings - Reddit"
  const endSiteMatch = title.match(/[-|–—:]\s*([a-zA-Z0-9]+)\s*$/);
  if (endSiteMatch) {
    const siteName = endSiteMatch[1].trim().toLowerCase();
    const browserNames = ['chrome', 'firefox', 'safari', 'edge', 'brave', 'opera', 'new', 'tab'];
    if (!browserNames.includes(siteName) && siteName.length > 2) {
      console.log('📍 Extracted site from end of title:', siteName);
      return siteName;
    }
  }

  // Extract the site name before dash/pipe/colon (e.g., "Reddit - Search", "reddit: settings")
  const siteNameMatch = title.match(/^([a-zA-Z0-9]+)(?:\s*[-|:])/);
  if (siteNameMatch) {
    const siteName = siteNameMatch[1].trim().toLowerCase();
    const browserNames = ['chrome', 'firefox', 'safari', 'edge', 'brave', 'opera', 'new', 'tab'];
    if (!browserNames.includes(siteName) && siteName.length > 2) {
      return siteName;
    }
  }

  // Return the full lowercase title for fallback matching against blocklist
  // This allows isUrlBlocked to check if any blocked site name appears in the title
  return '__title__:' + lowerTitle;
}

function isUrlBlocked(url) {
  if (!currentSession || !currentSession.blockedWebsites) return false;

  // Check if this is a fallback title match (prefixed with __title__:)
  const isTitleFallback = url.startsWith('__title__:');
  const searchText = isTitleFallback ? url.substring(10) : url.toLowerCase();

  // Check whitelist first
  if (currentSession.whitelistedWebsites) {
    for (const whitelisted of currentSession.whitelistedWebsites) {
      const whitelistedLower = whitelisted.toLowerCase();
      const whitelistedBase = whitelistedLower.split('.')[0];
      if (searchText.includes(whitelistedLower) || searchText.includes(whitelistedBase)) {
        return false;
      }
    }
  }

  // Check blocklist
  for (const blocked of currentSession.blockedWebsites) {
    const blockedLower = blocked.toLowerCase();
    const blockedBase = blockedLower.split('.')[0]; // e.g., "reddit" from "reddit.com"

    // For title fallback, check if the blocked site name appears anywhere in the title
    if (isTitleFallback) {
      if (searchText.includes(blockedBase) && blockedBase.length > 3) {
        console.log('📍 Title fallback match:', blockedBase, 'found in title');
        return true;
      }
    } else {
      // Normal domain matching
      if (searchText.includes(blockedLower) || blockedLower.includes(searchText)) {
        return true;
      }
      if (searchText === blockedBase || blockedBase.includes(searchText)) {
        return true;
      }
    }
  }

  return false;
}

// ==================================
// REDIRECT USING POWERSHELL
// ==================================

function redirectToBlockedPage() {
  console.log('🔄 Attempting redirect to blocked page...');

  // Use PowerShell to:
  // 1. Focus address bar with Ctrl+L
  // 2. Clear it and type the URL using clipboard (more reliable)
  // 3. Press Enter

  const psScript = `
Add-Type -AssemblyName System.Windows.Forms
Start-Sleep -Milliseconds 50
[System.Windows.Forms.SendKeys]::SendWait('^l')
Start-Sleep -Milliseconds 50
Set-Clipboard -Value '${BLOCKED_REDIRECT_URL}'
[System.Windows.Forms.SendKeys]::SendWait('^v')
Start-Sleep -Milliseconds 30
[System.Windows.Forms.SendKeys]::SendWait('{ENTER}')
`;

  // Write script to temp file and execute (avoids escaping issues)
  const fs = require('fs');
  const tempScript = path.join(app.getPath('temp'), 'redirect.ps1');

  fs.writeFileSync(tempScript, psScript);

  exec(`powershell -ExecutionPolicy Bypass -File "${tempScript}"`, (error, stdout, stderr) => {
    if (error) {
      console.error('Redirect error:', error);
      console.error('stderr:', stderr);
    } else {
      console.log('✅ Redirected to blocked page');
    }

    // Clean up temp file
    try {
      fs.unlinkSync(tempScript);
    } catch (e) {
      // Ignore cleanup errors
    }
  });
}

// ==================================
// SESSION MANAGEMENT
// ==================================

function startSessionPolling() {
  if (sessionPollInterval) return;

  console.log('📊 Session polling started');

  // Poll immediately on start
  pollSession();

  sessionPollInterval = setInterval(pollSession, 3000);
}

async function pollSession() {
  try {
    const response = await apiRequest(`/sessions/active?deviceId=${deviceId}`);
    console.log('📊 Session poll response:', JSON.stringify(response).substring(0, 200));

    if (response.success && response.session) {
      currentSession = response.session;
      console.log('✅ Session active, blocked sites:', currentSession.blockedWebsites);
    } else {
      currentSession = null;
    }

    updateTrayMenu();

    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('session-updated', currentSession);
    }
  } catch (error) {
    console.error('Session polling error:', error);
  }
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

  // Check auth status - verify token is valid with server
  ipcMain.handle('is-authenticated', async () => {
    if (!authToken) {
      return false;
    }

    try {
      const response = await apiRequest('/auth/me');
      if (response.success) {
        return true;
      } else {
        authToken = null;
        store.delete('authToken');
        return false;
      }
    } catch (error) {
      console.error('Auth check failed:', error);
      return false;
    }
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
}
