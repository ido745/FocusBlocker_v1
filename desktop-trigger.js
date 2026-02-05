#!/usr/bin/env node

/**
 * Desktop Trigger - Focus Session Controller
 *
 * This script allows you to toggle the focus session on/off from your computer.
 * The Android app will respond to the session state and start/stop blocking.
 */

const SERVER_URL = 'http://localhost:3000';

async function getStatus() {
  try {
    const response = await fetch(`${SERVER_URL}/status`);
    const data = await response.json();

    if (data.success) {
      console.log('\n📊 Current Status:');
      console.log('─'.repeat(50));
      console.log(`Session Active: ${data.data.isSessionActive ? '🔒 YES' : '⏸️  NO'}`);
      console.log(`Blocked Apps: ${data.data.blockedPackages.length}`);
      console.log(`Blocked Keywords: ${data.data.blockedKeywords.length}`);
      console.log(`Blocked Websites: ${data.data.blockedWebsites.length}`);
      console.log('─'.repeat(50));
      console.log('\nBlocked Apps:');
      data.data.blockedPackages.forEach(pkg => console.log(`  - ${pkg}`));
      console.log('\nBlocked Keywords:');
      data.data.blockedKeywords.forEach(keyword => console.log(`  - ${keyword}`));
      console.log('\nBlocked Websites:');
      data.data.blockedWebsites.forEach(site => console.log(`  - ${site}`));
      console.log('');
    } else {
      console.error('❌ Failed to get status');
    }
  } catch (error) {
    console.error('❌ Error connecting to server:', error.message);
    console.log('\n💡 Make sure the backend server is running:');
    console.log('   cd blocker-backend && npm start\n');
  }
}

async function toggleSession() {
  try {
    const response = await fetch(`${SERVER_URL}/toggle`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      }
    });

    const data = await response.json();

    if (data.success) {
      console.log('\n✅ Success!');
      console.log('─'.repeat(50));
      console.log(data.message);
      console.log(`Focus Mode: ${data.data.isSessionActive ? '🔒 ACTIVE' : '⏸️  INACTIVE'}`);
      console.log('─'.repeat(50));
      console.log('');

      if (data.data.isSessionActive) {
        console.log('🎯 Focus session started!');
        console.log('   Distracting apps and content are now blocked on your Android device.');
      } else {
        console.log('✨ Focus session ended!');
        console.log('   All apps are now accessible on your Android device.');
      }
      console.log('');
    } else {
      console.error('❌ Failed to toggle session');
    }
  } catch (error) {
    console.error('❌ Error connecting to server:', error.message);
    console.log('\n💡 Make sure the backend server is running:');
    console.log('   cd blocker-backend && npm start\n');
  }
}

async function updateConfig(blockedPackages, blockedKeywords, blockedWebsites) {
  try {
    const response = await fetch(`${SERVER_URL}/config`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        blockedPackages,
        blockedKeywords,
        blockedWebsites
      })
    });

    const data = await response.json();

    if (data.success) {
      console.log('\n✅ Configuration updated successfully!');
      console.log('');
    } else {
      console.error('❌ Failed to update configuration');
    }
  } catch (error) {
    console.error('❌ Error connecting to server:', error.message);
  }
}

function printHelp() {
  console.log(`
🎯 Focus Blocker Desktop Trigger
${'─'.repeat(50)}

Usage:
  node desktop-trigger.js [command]

Commands:
  toggle        Toggle focus session on/off
  status        Show current session status
  start         Start focus session
  stop          Stop focus session
  help          Show this help message

Examples:
  node desktop-trigger.js toggle
  node desktop-trigger.js status

${'─'.repeat(50)}
  `);
}

// Main execution
const command = process.argv[2];

switch (command) {
  case 'toggle':
    toggleSession();
    break;

  case 'status':
    getStatus();
    break;

  case 'start':
    // First get status to check if already active
    (async () => {
      try {
        const response = await fetch(`${SERVER_URL}/status`);
        const data = await response.json();
        if (data.success && !data.data.isSessionActive) {
          await toggleSession();
        } else if (data.success && data.data.isSessionActive) {
          console.log('\n⚠️  Focus session is already active!\n');
        }
      } catch (error) {
        console.error('❌ Error:', error.message);
      }
    })();
    break;

  case 'stop':
    // First get status to check if already inactive
    (async () => {
      try {
        const response = await fetch(`${SERVER_URL}/status`);
        const data = await response.json();
        if (data.success && data.data.isSessionActive) {
          await toggleSession();
        } else if (data.success && !data.data.isSessionActive) {
          console.log('\n⚠️  Focus session is already inactive!\n');
        }
      } catch (error) {
        console.error('❌ Error:', error.message);
      }
    })();
    break;

  case 'help':
  default:
    printHelp();
    break;
}
