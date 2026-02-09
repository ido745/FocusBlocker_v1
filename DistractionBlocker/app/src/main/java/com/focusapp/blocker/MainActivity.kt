package com.focusapp.blocker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focusapp.blocker.ui.AppInfo
import com.focusapp.blocker.ui.AppPickerHelper
import com.focusapp.blocker.ui.AuthViewModel
import com.focusapp.blocker.ui.LoginScreen
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        // Replace with your actual Google Cloud OAuth Client ID (Web client)
        const val GOOGLE_CLIENT_ID = "42261799101-ibarq1tjou7rag3de5aifg0vg68771j8.apps.googleusercontent.com"
    }

    private lateinit var credentialManager: CredentialManager
    private var authViewModel: AuthViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        credentialManager = CredentialManager.create(this)

        setContent {
            MaterialTheme {
                val viewModel: AuthViewModel = viewModel()
                authViewModel = viewModel
                val authState by viewModel.authState.collectAsState()

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (authState.isAuthenticated) {
                        // User is logged in, show main app
                        MainScreen(
                            viewModel = viewModel,
                            onOpenAccessibilitySettings = { openAccessibilitySettings() },
                            isServiceEnabled = { isAccessibilityServiceEnabled() }
                        )
                    } else {
                        // User not logged in, show login screen
                        LoginScreen(
                            onGoogleSignInClick = { signInWithGoogle() },
                            isLoading = authState.isLoading,
                            errorMessage = authState.errorMessage
                        )
                    }
                }
            }
        }
    }

    private fun signInWithGoogle() {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(GOOGLE_CLIENT_ID)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        lifecycleScope.launch {
            try {
                val result = credentialManager.getCredential(
                    request = request,
                    context = this@MainActivity
                )
                handleSignIn(result)
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Google sign-in failed", e)
                // The error will be shown via authState.errorMessage
            }
        }
    }

    private fun handleSignIn(result: GetCredentialResponse) {
        val credential = result.credential

        when (credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                        val idToken = googleIdTokenCredential.idToken

                        Log.d(TAG, "Got Google ID token, sending to server...")
                        authViewModel?.signInWithGoogle(idToken)
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e(TAG, "Failed to parse Google ID token", e)
                    }
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = "${packageName}/.service.BlockingAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        return enabledServices.contains(expectedComponentName)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: AuthViewModel,
    onOpenAccessibilitySettings: () -> Unit,
    isServiceEnabled: () -> Boolean
) {
    val uiState by viewModel.uiState.collectAsState()
    val authState by viewModel.authState.collectAsState()
    var serviceEnabled by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // Sync tab and pager
    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
    }

    // Check service status periodically (every 500ms for quick response)
    LaunchedEffect(Unit) {
        while (true) {
            serviceEnabled = isServiceEnabled()
            delay(500)
        }
    }

    // Poll for active sessions
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.fetchActiveSession()
            delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (selectedTab) {
                                0 -> "Home"
                                1 -> "Block List"
                                2 -> "Whitelist"
                                else -> "Focus Blocker"
                            }
                        )
                        authState.userEmail?.let { email ->
                            Text(
                                email,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.fetchConfig() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenAccessibilitySettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { viewModel.logout() }) {
                        Icon(Icons.Default.Close, contentDescription = "Logout")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("Home") },
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        scope.launch { pagerState.animateScrollToPage(0) }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Block, contentDescription = null) },
                    label = { Text("Block") },
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        scope.launch { pagerState.animateScrollToPage(1) }
                    }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Shield, contentDescription = null) },
                    label = { Text("Whitelist") },
                    selected = selectedTab == 2,
                    onClick = {
                        selectedTab = 2
                        scope.launch { pagerState.animateScrollToPage(2) }
                    }
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { page ->
            when (page) {
                0 -> HomePage(viewModel, uiState, serviceEnabled, onOpenAccessibilitySettings)
                1 -> BlockPage(viewModel, uiState)
                2 -> WhitelistPage(viewModel, uiState)
            }
        }

        // Error/Success Messages
        uiState.errorMessage?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearMessages() }) {
                        Text("Dismiss")
                    }
                }
            ) {
                Text("❌ $message")
            }
        }
    }
}

@Composable
fun HomePage(
    viewModel: AuthViewModel,
    uiState: com.focusapp.blocker.ui.AppUiState,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Card (Focus Mode toggle)
        item {
            StatusCard(
                isSessionActive = uiState.isSessionActive,
                isLoading = uiState.isLoading,
                onToggle = { viewModel.toggleSession() }
            )
        }

        // Device Selection Section
        item {
            DeviceSelectionCard(
                devices = uiState.devices,
                currentDeviceId = uiState.currentDeviceId,
                allDevicesSelected = uiState.allDevicesSelected,
                selectedDeviceIds = uiState.selectedDeviceIds,
                onToggleAllDevices = { viewModel.toggleAllDevices() },
                onToggleDevice = { viewModel.toggleDevice(it) },
                onRefresh = { viewModel.fetchDevices() }
            )
        }

        // Enable Permissions Card (only show if permissions are NOT granted)
        if (!serviceEnabled) {
            item {
                EnablePermissionsCard(
                    onOpenSettings = onOpenAccessibilitySettings
                )
            }
        }

        // Server URL Section
        item {
            ServerUrlSection(
                serverUrl = uiState.serverUrl,
                onServerUrlChange = { viewModel.updateServerUrl(it) }
            )
        }

        // Statistics
        item {
            StatsCard(uiState)
        }
    }
}

@Composable
fun BlockPage(viewModel: AuthViewModel, uiState: com.focusapp.blocker.ui.AppUiState) {
    var showAppPicker by remember { mutableStateOf(false) }

    if (showAppPicker) {
        AppPickerDialog(
            onDismiss = { showAppPicker = false },
            onAppSelected = { packageName ->
                viewModel.addBlockedPackage(packageName)
            },
            title = "Select App to Block"
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Blocked Apps
        item {
            Text(
                "Blocked Apps",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            ItemInputWithPicker(
                placeholder = "com.instagram.android",
                onAdd = { viewModel.addBlockedPackage(it) },
                showAppPicker = true,
                onPickApp = { showAppPicker = true }
            )
        }
        items(uiState.blockedPackages.toList()) { packageName ->
            ItemCard(
                text = packageName,
                onRemove = { viewModel.removeBlockedPackage(packageName) }
            )
        }

        // Blocked Keywords
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Blocked Keywords",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            ItemInputSection(
                placeholder = "gambling",
                onAdd = { viewModel.addBlockedKeyword(it) }
            )
        }
        items(uiState.blockedKeywords.toList()) { keyword ->
            ItemCard(
                text = keyword,
                onRemove = { viewModel.removeBlockedKeyword(keyword) }
            )
        }

        // Blocked Websites
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Blocked Websites",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            ItemInputSection(
                placeholder = "facebook.com",
                onAdd = { viewModel.addBlockedWebsite(it) }
            )
        }
        items(uiState.blockedWebsites.toList()) { website ->
            ItemCard(
                text = website,
                onRemove = { viewModel.removeBlockedWebsite(website) }
            )
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
fun WhitelistPage(viewModel: AuthViewModel, uiState: com.focusapp.blocker.ui.AppUiState) {
    var showAppPicker by remember { mutableStateOf(false) }

    if (showAppPicker) {
        AppPickerDialog(
            onDismiss = { showAppPicker = false },
            onAppSelected = { packageName ->
                viewModel.addWhitelistedPackage(packageName)
            },
            title = "Select App to Whitelist"
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "ℹ️ About Whitelists",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Whitelisted apps and websites will NEVER be blocked, even during focus sessions.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Whitelisted Apps
        item {
            Text(
                "Whitelisted Apps",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            ItemInputWithPicker(
                placeholder = "com.yourbank.app",
                onAdd = { viewModel.addWhitelistedPackage(it) },
                showAppPicker = true,
                onPickApp = { showAppPicker = true }
            )
        }
        items(uiState.whitelistedPackages.toList()) { packageName ->
            ItemCard(
                text = packageName,
                onRemove = { viewModel.removeWhitelistedPackage(packageName) },
                isProtected = packageName == "com.focusapp.blocker"
            )
        }

        // Whitelisted Websites
        item {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Whitelisted Websites",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            ItemInputSection(
                placeholder = "yourbank.com",
                onAdd = { viewModel.addWhitelistedWebsite(it) }
            )
        }
        items(uiState.whitelistedWebsites.toList()) { website ->
            ItemCard(
                text = website,
                onRemove = { viewModel.removeWhitelistedWebsite(website) }
            )
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
fun StatusCard(isSessionActive: Boolean, isLoading: Boolean, onToggle: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSessionActive) Color(0xFF4CAF50) else Color(0xFFFF9800)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isSessionActive) "🔒 FOCUS MODE ACTIVE" else "⏸️ Focus Mode Inactive",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            } else {
                Button(
                    onClick = onToggle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White.copy(alpha = 0.2f),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = if (isSessionActive) "⏸️ STOP FOCUS SESSION" else "▶️ START FOCUS SESSION",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun EnablePermissionsCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFEBEE)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚠️ Enable Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFC62828)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "How to enable:",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF424242)
            )
            Text(
                "1. Tap the button below\n2. Go to \"downloaded apps -> Focus Blocker\"\n3. Toggle \"Use Focus Blocker\" ON",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF616161)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF44336)
                )
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Accessibility Settings")
            }
        }
    }
}

@Composable
fun ServiceStatusCard(onOpenSettings: () -> Unit, isServiceEnabled: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isServiceEnabled) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "⚙️ Accessibility Service",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isServiceEnabled) "✅ ENABLED" else "❌ DISABLED",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isServiceEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (isServiceEnabled) {
                    "Service is running in the background. Blocking is active!"
                } else {
                    "⚠️ Service NOT running! Enable it for blocking to work."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (isServiceEnabled) Color(0xFF2E7D32) else Color(0xFFC62828)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isServiceEnabled) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isServiceEnabled) "Manage Settings" else "Enable Service")
            }
        }
    }
}

@Composable
fun StatsCard(uiState: com.focusapp.blocker.ui.AppUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📊 Current Configuration",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("Blocked Apps", uiState.blockedPackages.size.toString())
                StatItem("Blocked Keywords", uiState.blockedKeywords.size.toString())
                StatItem("Blocked Sites", uiState.blockedWebsites.size.toString())
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("Whitelisted Apps", uiState.whitelistedPackages.size.toString())
                StatItem("Whitelisted Sites", uiState.whitelistedWebsites.size.toString())
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
fun DeviceSelectionCard(
    devices: List<com.focusapp.blocker.data.Device>,
    currentDeviceId: String?,
    allDevicesSelected: Boolean,
    selectedDeviceIds: Set<String>,
    onToggleAllDevices: () -> Unit,
    onToggleDevice: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Devices, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Select Devices for Session",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh devices", modifier = Modifier.size(20.dp))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // All Devices checkbox
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleAllDevices() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = allDevicesSelected,
                    onCheckedChange = { onToggleAllDevices() }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "All Devices",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // Individual devices
            if (devices.isEmpty()) {
                Text(
                    "No devices registered yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 48.dp)
                )
            } else {
                devices.forEach { device ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !allDevicesSelected) { onToggleDevice(device.id) }
                            .padding(vertical = 4.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(
                                checked = allDevicesSelected || selectedDeviceIds.contains(device.id),
                                onCheckedChange = { onToggleDevice(device.id) },
                                enabled = !allDevicesSelected
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                if (device.type == "android") Icons.Default.PhoneAndroid else Icons.Default.Computer,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color.Gray
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val isCurrentDevice = device.id == currentDeviceId
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        device.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    if (isCurrentDevice) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            "(This device)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    "${device.type} - ${device.platform}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.Gray
                                )
                            }
                        }
                        Text(
                            if (device.isOnline) "Online" else "Offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (device.isOnline) Color(0xFF4CAF50) else Color.Gray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ServerUrlSection(serverUrl: String, onServerUrlChange: (String) -> Unit) {
    var editedUrl by remember { mutableStateOf(serverUrl) }

    LaunchedEffect(serverUrl) {
        editedUrl = serverUrl
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Backend Server URL", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = editedUrl,
                onValueChange = { editedUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://10.0.2.2:3000") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { onServerUrlChange(editedUrl) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Save URL")
            }
        }
    }
}

@Composable
fun ItemInputSection(placeholder: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(placeholder) },
            singleLine = true
        )
        IconButton(
            onClick = {
                if (text.isNotBlank()) {
                    onAdd(text.trim())
                    text = ""
                }
            }
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add")
        }
    }
}

@Composable
fun ItemCard(text: String, onRemove: () -> Unit, isProtected: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isProtected) Color(0xFFE8F5E9) else Color(0xFFF5F5F5)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (isProtected) {
                    Text("🔒 ", fontSize = MaterialTheme.typography.bodyLarge.fontSize)
                }
                Text(text)
            }
            if (!isProtected) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Red)
                }
            } else {
                Text(
                    "Protected",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50)
                )
            }
        }
    }
}

@Composable
fun AppPickerDialog(
    onDismiss: () -> Unit,
    onAppSelected: (String) -> Unit,
    title: String = "Select App"
) {
    val context = LocalContext.current
    val appPickerHelper = remember { AppPickerHelper(context) }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(searchQuery) {
        isLoading = true
        apps = if (searchQuery.isBlank()) {
            appPickerHelper.getInstalledApps()
        } else {
            appPickerHelper.searchApps(searchQuery)
        }
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                    ) {
                        items(apps) { app ->
                            AppPickerItem(
                                appInfo = app,
                                onClick = {
                                    onAppSelected(app.packageName)
                                    onDismiss()
                                }
                            )
                        }

                        if (apps.isEmpty()) {
                            item {
                                Text(
                                    "No apps found",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.Gray
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun AppPickerItem(appInfo: AppInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (appInfo.icon != null) {
            val bitmap = remember(appInfo.icon) {
                appInfo.icon.toBitmap(48, 48).asImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
        } else {
            Icon(
                Icons.Default.Apps,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = Color.Gray
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                appInfo.appName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                appInfo.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
    Divider()
}

@Composable
fun ItemInputWithPicker(
    placeholder: String,
    onAdd: (String) -> Unit,
    showAppPicker: Boolean = false,
    onPickApp: () -> Unit = {}
) {
    var text by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                singleLine = true
            )
            IconButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onAdd(text.trim())
                        text = ""
                    }
                }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add manually")
            }
        }

        if (showAppPicker) {
            OutlinedButton(
                onClick = onPickApp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Apps, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pick from Installed Apps")
            }
        }
    }
}
