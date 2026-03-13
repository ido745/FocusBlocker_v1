const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage } = require('electron');
const path = require('path');
const activeWin = require('active-win');
const Store = require('electron-store');
const fetch = require('node-fetch');
const { v4: uuidv4 } = require('uuid');
const os = require('os');
const { exec } = require('child_process');
const http = require('http');
const url = require('url');

const store = new Store();
const API_URL = 'https://focus-blocker-backend.onrender.com';
const BLOCKED_REDIRECT_URL = 'https://blocked.freedom.to/';

// Google OAuth Configuration (loaded from gitignored credentials.js)
const credentials = (() => { try { return require('./credentials'); } catch (_) { return {}; } })();
const GOOGLE_CLIENT_ID = process.env.GOOGLE_CLIENT_ID || credentials.GOOGLE_CLIENT_ID || '';
const GOOGLE_CLIENT_SECRET = process.env.GOOGLE_CLIENT_SECRET || credentials.GOOGLE_CLIENT_SECRET || '';
const GOOGLE_REDIRECT_PORT = 8089;

let mainWindow;
let tray;
let browserMonitorInterval;
let configPollInterval;
let currentConfig = null; // Always-on blocking config from server
let authToken = null;
let deviceId = null;
let lastRedirectTime = 0;

// ==================================
// APP LIFECYCLE
// ==================================

app.whenReady().then(() => {
  initializeApp();
});

app.on('before-quit', () => {
  app.isQuitting = true;
});

app.on('window-all-closed', () => {
  // Don't quit — keep running in system tray for always-on blocking
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
  authToken = store.get('authToken');
  deviceId = store.get('deviceId') || uuidv4();
  store.set('deviceId', deviceId);

  createMainWindow();
  createSystemTray();

  // Blocking is always on — start immediately if already authenticated
  if (authToken) {
    await registerDevice();
    startBrowserMonitoring();
    startConfigPolling();
  }

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

  // Minimize to tray instead of quitting (blocking continues)
  mainWindow.on('close', (event) => {
    if (!app.isQuitting) {
      event.preventDefault();
      mainWindow.hide();
    }
  });

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
      tray = new Tray(nativeImage.createEmpty());
    }
    updateTrayMenu();
    tray.on('click', () => { mainWindow.show(); mainWindow.focus(); });
  } catch (error) {
    console.error('System tray creation error:', error);
  }
}

function updateTrayMenu() {
  const isBlocking = currentConfig !== null && authToken !== null;
  const contextMenu = Menu.buildFromTemplate([
    {
      label: isBlocking ? '🔒 Blocking Active' : '⏸️ Sign in to enable blocking',
      enabled: false
    },
    { type: 'separator' },
    {
      label: 'Show App',
      click: () => { mainWindow.show(); mainWindow.focus(); }
    },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => { app.isQuitting = true; app.quit(); }
    }
  ]);
  tray.setContextMenu(contextMenu);
  tray.setToolTip(isBlocking ? 'Focus Blocker — Always Blocking' : 'Focus Blocker — Sign in to block');
}

// ==================================
// BROWSER MONITORING
// ==================================

function startBrowserMonitoring() {
  if (browserMonitorInterval) return;
  console.log('🔍 Browser monitoring started (always-on blocking)');

  browserMonitorInterval = setInterval(async () => {
    try {
      const win = await activeWin();
      if (!win) return;

      const isBrowser = checkIfBrowser(win.owner.name);
      if (!isBrowser) return;

      // Blocking is always active — no session check needed
      if (!currentConfig || !currentConfig.blockedWebsites) return;

      const titleLower = win.title.toLowerCase();
      if (titleLower.includes('blocked.freedom.to') || titleLower === 'freedom' ||
          titleLower.startsWith('freedom |') || titleLower.startsWith('freedom -')) {
        return;
      }

      const extractedUrl = extractUrlFromTitle(win.title);
      if (extractedUrl) {
        const blocked = isUrlBlocked(extractedUrl);
        if (blocked) {
          const now = Date.now();
          if (now - lastRedirectTime < 1500) return;
          console.log('🚫 Blocked site detected:', extractedUrl, '- Redirecting...');
          lastRedirectTime = now;
          redirectToBlockedPage();
        }
      }
    } catch (error) {
      console.error('Browser monitoring error:', error);
    }
  }, 2000);
}

function stopBrowserMonitoring() {
  if (browserMonitorInterval) {
    clearInterval(browserMonitorInterval);
    browserMonitorInterval = null;
  }
}

function checkIfBrowser(appName) {
  const browsers = ['chrome', 'firefox', 'safari', 'edge', 'brave', 'opera', 'vivaldi', 'msedge'];
  return browsers.some(b => appName.toLowerCase().includes(b));
}

function extractUrlFromTitle(title) {
  if (!title) return null;

  const lowerTitle = title.toLowerCase();
  if (lowerTitle.includes('blocked.freedom.to') || lowerTitle === 'freedom' ||
      lowerTitle.startsWith('freedom |') || lowerTitle.startsWith('freedom -')) {
    return null;
  }

  const fullDomainMatch = title.match(/([a-z0-9-]+\.[a-z]{2,}(?:\.[a-z]{2,})?)/i);
  if (fullDomainMatch) return fullDomainMatch[1].toLowerCase();

  const endSiteMatch = title.match(/[-|–—:]\s*([a-zA-Z0-9]+)\s*$/);
  if (endSiteMatch) {
    const siteName = endSiteMatch[1].trim().toLowerCase();
    const browserNames = ['chrome', 'firefox', 'safari', 'edge', 'brave', 'opera', 'new', 'tab'];
    if (!browserNames.includes(siteName) && siteName.length > 2) return siteName;
  }

  const siteNameMatch = title.match(/^([a-zA-Z0-9]+)(?:\s*[-|:])/);
  if (siteNameMatch) {
    const siteName = siteNameMatch[1].trim().toLowerCase();
    const browserNames = ['chrome', 'firefox', 'safari', 'edge', 'brave', 'opera', 'new', 'tab'];
    if (!browserNames.includes(siteName) && siteName.length > 2) return siteName;
  }

  return '__title__:' + lowerTitle;
}

function isUrlBlocked(extractedUrl) {
  if (!currentConfig || !currentConfig.blockedWebsites) return false;

  const isTitleFallback = extractedUrl.startsWith('__title__:');
  const searchText = isTitleFallback ? extractedUrl.substring(10) : extractedUrl.toLowerCase();

  if (currentConfig.whitelistedWebsites) {
    for (const whitelisted of currentConfig.whitelistedWebsites) {
      const wLower = whitelisted.toLowerCase();
      const wBase = wLower.split('.')[0];
      if (searchText.includes(wLower) || searchText.includes(wBase)) return false;
    }
  }

  for (const blocked of currentConfig.blockedWebsites) {
    const bLower = blocked.toLowerCase();
    const bBase = bLower.split('.')[0];
    if (isTitleFallback) {
      if (searchText.includes(bBase) && bBase.length > 3) return true;
    } else {
      if (searchText.includes(bLower) || bLower.includes(searchText)) return true;
      if (searchText === bBase || bBase.includes(searchText)) return true;
    }
  }

  return false;
}

// ==================================
// REDIRECT USING POWERSHELL
// ==================================

function redirectToBlockedPage() {
  const psScript = `
Add-Type -AssemblyName System.Windows.Forms
[System.Windows.Forms.SendKeys]::SendWait('^w')
Start-Sleep -Milliseconds 100
Start-Process '${BLOCKED_REDIRECT_URL}'
`;
  const fs = require('fs');
  const tempScript = path.join(app.getPath('temp'), 'redirect.ps1');
  fs.writeFileSync(tempScript, psScript);
  exec(`powershell -ExecutionPolicy Bypass -File "${tempScript}"`, (error) => {
    if (error) console.error('Redirect error:', error);
    try { fs.unlinkSync(tempScript); } catch (_) {}
  });
}

// ==================================
// CONFIG POLLING (always-on — replaces session polling)
// ==================================

/**
 * Polls /sessions/active which now always returns the user's blocklist as an active config.
 * This keeps the local blocklist in sync with any server-side changes (including matured
 * pending changes applied after 24 hours).
 */
function startConfigPolling() {
  if (configPollInterval) return;
  console.log('📊 Config polling started');
  pollConfig();
  configPollInterval = setInterval(pollConfig, 3000);
}

async function pollConfig() {
  try {
    const response = await apiRequest(`/sessions/active?deviceId=${deviceId}`);
    if (response.success && response.session) {
      currentConfig = response.session;
      console.log('✅ Config synced, blocked sites:', currentConfig.blockedWebsites?.length || 0);
    }
    // On failure: keep current config so blocking continues
    updateTrayMenu();
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('config-updated', currentConfig);
    }
  } catch (error) {
    console.error('Config polling error (keeping current config):', error);
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

async function apiRequest(endpoint, options = {}, retries = 2) {
  const headers = {
    'Content-Type': 'application/json',
    ...(authToken && { 'Authorization': `Bearer ${authToken}` })
  };

  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const response = await fetch(`${API_URL}${endpoint}`, { ...options, headers });
      return await response.json();
    } catch (error) {
      if (attempt < retries) {
        console.log(`⏳ Server may be waking up, retrying in 3s... (attempt ${attempt + 1}/${retries})`);
        await new Promise(resolve => setTimeout(resolve, 3000));
      } else {
        throw error;
      }
    }
  }
}

async function register(email, password, name) {
  try {
    const response = await fetch(`${API_URL}/auth/register`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password, name })
    });
    const data = await response.json();
    if (data.success) {
      authToken = data.token;
      store.set('authToken', authToken);
      await registerDevice();
      startBrowserMonitoring();
      startConfigPolling();
      return { success: true, user: data.user };
    }
    return { success: false, error: data.error || 'Registration failed' };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

async function login(email, password) {
  try {
    const response = await fetch(`${API_URL}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password })
    });
    const data = await response.json();
    if (data.success) {
      authToken = data.token;
      store.set('authToken', authToken);
      await registerDevice();
      startBrowserMonitoring();
      startConfigPolling();
      return { success: true, user: data.user };
    }
    return { success: false, error: data.error || 'Login failed' };
  } catch (error) {
    return { success: false, error: error.message };
  }
}

// ==================================
// GOOGLE SIGN-IN
// ==================================

async function googleSignIn() {
  return new Promise((resolve) => {
    const server = http.createServer(async (req, res) => {
      try {
        const parsedUrl = url.parse(req.url, true);
        if (parsedUrl.pathname === '/callback') {
          const code = parsedUrl.query.code;
          const error = parsedUrl.query.error;

          if (error) {
            res.writeHead(200, { 'Content-Type': 'text/html' });
            res.end('<html><body><h1>Sign-in cancelled</h1><script>window.close()</script></body></html>');
            server.close();
            resolve({ success: false, error: 'Sign-in cancelled' });
            return;
          }

          if (code) {
            try {
              const tokenResponse = await fetch('https://oauth2.googleapis.com/token', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: new URLSearchParams({
                  code,
                  client_id: GOOGLE_CLIENT_ID,
                  client_secret: GOOGLE_CLIENT_SECRET,
                  redirect_uri: `http://localhost:${GOOGLE_REDIRECT_PORT}/callback`,
                  grant_type: 'authorization_code'
                })
              });
              const tokens = await tokenResponse.json();

              if (tokens.id_token) {
                const backendResponse = await fetch(`${API_URL}/auth/google`, {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ idToken: tokens.id_token })
                });
                const backendData = await backendResponse.json();

                if (backendData.success) {
                  authToken = backendData.token;
                  store.set('authToken', authToken);
                  if (backendData.user) {
                    store.set('userEmail', backendData.user.email);
                    store.set('userName', backendData.user.name);
                    store.set('userPicture', backendData.user.picture);
                  }
                  await registerDevice();
                  startBrowserMonitoring();
                  startConfigPolling();
                  res.writeHead(200, { 'Content-Type': 'text/html' });
                  res.end('<html><body><h1>Sign-in successful!</h1><p>You can close this window.</p><script>window.close()</script></body></html>');
                  server.close();
                  resolve({ success: true, user: backendData.user });
                } else {
                  res.writeHead(200, { 'Content-Type': 'text/html' });
                  res.end(`<html><body><h1>Sign-in failed</h1><p>${backendData.error || 'Unknown error'}</p><script>window.close()</script></body></html>`);
                  server.close();
                  resolve({ success: false, error: backendData.error || 'Backend authentication failed' });
                }
              } else {
                res.writeHead(200, { 'Content-Type': 'text/html' });
                res.end('<html><body><h1>Sign-in failed</h1><p>No ID token received from Google.</p><script>window.close()</script></body></html>');
                server.close();
                resolve({ success: false, error: 'No ID token received' });
              }
            } catch (tokenError) {
              res.writeHead(200, { 'Content-Type': 'text/html' });
              res.end(`<html><body><h1>Sign-in failed</h1><p>${tokenError.message}</p><script>window.close()</script></body></html>`);
              server.close();
              resolve({ success: false, error: tokenError.message });
            }
          }
        } else {
          res.writeHead(404);
          res.end('Not found');
        }
      } catch (err) {
        res.writeHead(500);
        res.end('Internal error');
        server.close();
        resolve({ success: false, error: err.message });
      }
    });

    server.listen(GOOGLE_REDIRECT_PORT, '127.0.0.1', () => {
      const authUrl = new URL('https://accounts.google.com/o/oauth2/v2/auth');
      authUrl.searchParams.set('client_id', GOOGLE_CLIENT_ID);
      authUrl.searchParams.set('redirect_uri', `http://localhost:${GOOGLE_REDIRECT_PORT}/callback`);
      authUrl.searchParams.set('response_type', 'code');
      authUrl.searchParams.set('scope', 'openid email profile');
      authUrl.searchParams.set('access_type', 'offline');
      authUrl.searchParams.set('prompt', 'select_account');

      const authWindow = new BrowserWindow({
        width: 500,
        height: 700,
        show: true,
        webPreferences: { nodeIntegration: false, contextIsolation: true }
      });
      authWindow.loadURL(authUrl.toString());
      authWindow.on('closed', () => {
        setTimeout(() => { try { server.close(); } catch (_) {} }, 1000);
      });
    });

    server.on('error', (err) => {
      resolve({ success: false, error: `Could not start OAuth server: ${err.message}` });
    });

    setTimeout(() => {
      try { server.close(); } catch (_) {}
      resolve({ success: false, error: 'Sign-in timed out' });
    }, 300000);
  });
}

// ==================================
// IPC HANDLERS
// ==================================

function setupIPC() {
  // Authentication
  ipcMain.handle('google-sign-in', async () => {
    return await googleSignIn();
  });

  ipcMain.handle('register', async (_event, { email, password, name }) => {
    return await register(email, password, name);
  });

  ipcMain.handle('login', async (_event, { email, password }) => {
    return await login(email, password);
  });

  ipcMain.handle('logout', () => {
    authToken = null;
    store.delete('authToken');
    stopBrowserMonitoring();
    if (configPollInterval) {
      clearInterval(configPollInterval);
      configPollInterval = null;
    }
    currentConfig = null;
    updateTrayMenu();
    return { success: true };
  });

  // Config management
  ipcMain.handle('get-config', async () => {
    try {
      return await apiRequest('/config');
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  ipcMain.handle('update-config', async (_event, config) => {
    try {
      const response = await apiRequest('/config', {
        method: 'POST',
        body: JSON.stringify(config)
      });
      await pollConfig(); // Immediately refresh local config
      return response;
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  // Pending changes (24-hour delay for constraint relaxation)
  ipcMain.handle('get-pending-changes', async () => {
    try {
      return await apiRequest('/config/pending');
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  ipcMain.handle('add-pending-change', async (_event, { type, value }) => {
    try {
      return await apiRequest('/config/pending', {
        method: 'POST',
        body: JSON.stringify({ type, value })
      });
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  ipcMain.handle('cancel-pending-change', async (_event, { changeId }) => {
    try {
      return await apiRequest(`/config/pending/${changeId}`, { method: 'DELETE' });
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  // Device management
  ipcMain.handle('get-devices', async () => {
    try {
      return await apiRequest('/devices');
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  // Auth status
  ipcMain.handle('is-authenticated', async () => {
    if (!authToken) return false;
    try {
      const response = await apiRequest('/auth/me');
      if (response.success) return true;
      authToken = null;
      store.delete('authToken');
      return false;
    } catch (_) {
      return false;
    }
  });

  ipcMain.handle('get-device-id', () => deviceId);

  ipcMain.handle('get-user-info', async () => {
    try {
      return await apiRequest('/auth/me');
    } catch (error) {
      return { success: false, error: error.message };
    }
  });

  // Current blocking config (replaces get-current-session)
  ipcMain.handle('get-current-config', () => currentConfig);
}
