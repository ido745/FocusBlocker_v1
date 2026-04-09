package com.focusapp.blocker

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
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
import com.focusapp.blocker.data.PendingChange
import com.focusapp.blocker.receiver.FocusDeviceAdminReceiver
import com.focusapp.blocker.service.BlockingAccessibilityService
import com.focusapp.blocker.service.FocusBlockerForegroundService
import com.focusapp.blocker.ui.AppInfo
import com.focusapp.blocker.ui.AppPickerHelper
import com.focusapp.blocker.ui.AuthViewModel
import com.focusapp.blocker.ui.LoginScreen
import com.focusapp.blocker.ui.MotivationPage
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val GOOGLE_CLIENT_ID = "42261799101-ibarq1tjou7rag3de5aifg0vg68771j8.apps.googleusercontent.com"
    }

    private lateinit var credentialManager: CredentialManager
    private var authViewModel: AuthViewModel? = null

    // Holds a video URL delivered from the accessibility guard via intent
    private val pendingMotivationUrl = mutableStateOf<String?>(null)

    // Launcher to handle device admin activation result
    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            authViewModel?.onDeviceAdminEnabled()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        credentialManager = CredentialManager.create(this)

        // Start foreground service to keep the process alive in the background
        FocusBlockerForegroundService.startService(this)

        // Handle motivation auto-play from guard trigger
        handleMotivationIntent(intent)

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
                        MainScreen(
                            viewModel = viewModel,
                            pendingMotivationUrl = pendingMotivationUrl.value,
                            onMotivationUrlConsumed = { pendingMotivationUrl.value = null },
                            onOpenAccessibilitySettings = { openAccessibilitySettings() },
                            isServiceEnabled = { isAccessibilityServiceEnabled() },
                            isBatteryOptimizationIgnored = { isBatteryOptimizationIgnored() },
                            onRequestBatteryExclusion = { requestBatteryOptimizationExclusion() },
                            canDrawOverlays = { canDrawOverlays() },
                            onOpenOverlaySettings = { openOverlaySettings() },
                            isMiui = { isMiui() },
                            isMiuiAutostartEnabled = { isMiuiAutostartEnabled() },
                            isMiuiBackgroundPopupEnabled = { isMiuiBackgroundPopupEnabled() },
                            onOpenMiuiAutostartSettings = { openMiuiAutostartSettings() },
                            onOpenMiuiOverlaySettings = { openMiuiOverlaySettings() },
                            onRequestDeviceAdmin = { requestDeviceAdmin() }
                        )
                    } else {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMotivationIntent(intent)
    }

    private fun handleMotivationIntent(intent: Intent?) {
        if (intent?.action == FocusBlockerForegroundService.ACTION_LAUNCH_MOTIVATION) {
            val url = intent.getStringExtra(FocusBlockerForegroundService.EXTRA_VIDEO_URL)
            if (!url.isNullOrBlank()) {
                pendingMotivationUrl.value = url
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
                        authViewModel?.signInWithGoogle(googleIdTokenCredential.idToken)
                    } catch (e: GoogleIdTokenParsingException) {
                        Log.e(TAG, "Failed to parse Google ID token", e)
                    }
                }
            }
        }
    }

    private fun openAccessibilitySettings() {
        BlockingAccessibilityService.openedViaSettingsIconAt = System.currentTimeMillis()
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        // Android stores the fully qualified name: "com.focusapp.blocker/com.focusapp.blocker.service.BlockingAccessibilityService"
        val expected = "$packageName/${packageName}.service.BlockingAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return enabledServices.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    fun openOverlaySettings() {
        BlockingAccessibilityService.openedFromApp = true
        startActivity(Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        ))
    }

    fun isMiui(): Boolean {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            val version = method.invoke(null, "ro.miui.ui.version.name") as? String
            version != null && version.isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }

    fun isMiuiAutostartEnabled(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val method = ops.javaClass.getMethod(
                "checkOpNoThrow", Int::class.java, Int::class.java, String::class.java
            )
            // Op 10008 = MIUI autostart permission
            val result = method.invoke(ops, 10008, android.os.Process.myUid(), packageName) as Int
            result != android.app.AppOpsManager.MODE_IGNORED
        } catch (e: Exception) {
            true // Assume granted if we can't check
        }
    }

    fun isMiuiBackgroundPopupEnabled(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val method = ops.javaClass.getMethod(
                "checkOpNoThrow", Int::class.java, Int::class.java, String::class.java
            )
            // Op 10021 = "open new windows while running in the background"
            val result = method.invoke(ops, 10021, android.os.Process.myUid(), packageName) as Int
            result != android.app.AppOpsManager.MODE_IGNORED
        } catch (e: Exception) {
            true
        }
    }

    fun openMiuiAutostartSettings() {
        BlockingAccessibilityService.openedFromApp = true
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
                putExtra("extra_pkgname", packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback: open general MIUI app permissions page
            try {
                val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName("com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity")
                    putExtra("extra_pkgname", packageName)
                }
                startActivity(intent)
            } catch (e2: Exception) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        }
    }

    fun openMiuiOverlaySettings() {
        BlockingAccessibilityService.openedFromApp = true
        // Opens MIUI's "Other permissions" page (PermissionsEditorActivity) where the user
        // can toggle "Display pop-up windows while running in background"
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                )
                putExtra("extra_pkgname", packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            })
        }
    }

    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    fun requestBatteryOptimizationExclusion() {
        if (!isBatteryOptimizationIgnored()) {
            BlockingAccessibilityService.openedFromApp = true
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    /** Launches the system dialog to activate device admin (deletion protection). */
    fun requestDeviceAdmin() {
        val adminComponent = ComponentName(this, FocusDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Prevents this app from being deleted in moments of weakness."
            )
        }
        adminLauncher.launch(intent)
    }
}

// ==================================
// MAIN SCREEN
// ==================================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    viewModel: AuthViewModel,
    pendingMotivationUrl: String?,
    onMotivationUrlConsumed: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    isServiceEnabled: () -> Boolean,
    isBatteryOptimizationIgnored: () -> Boolean,
    onRequestBatteryExclusion: () -> Unit,
    canDrawOverlays: () -> Boolean,
    onOpenOverlaySettings: () -> Unit,
    isMiui: () -> Boolean,
    isMiuiAutostartEnabled: () -> Boolean,
    isMiuiBackgroundPopupEnabled: () -> Boolean,
    onOpenMiuiAutostartSettings: () -> Unit,
    onOpenMiuiOverlaySettings: () -> Unit,
    onRequestDeviceAdmin: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val authState by viewModel.authState.collectAsState()
    var serviceEnabled by remember { mutableStateOf(false) }
    var batteryOptIgnored by remember { mutableStateOf(true) }
    var overlayGranted by remember { mutableStateOf(true) }
    var miuiDevice by remember { mutableStateOf(false) }
    var miuiAutostartGranted by remember { mutableStateOf(true) }
    var miuiBackgroundPopupGranted by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        selectedTab = pagerState.currentPage
    }

    // When guard fires and sends a motivation URL, jump to Motivation tab
    LaunchedEffect(pendingMotivationUrl) {
        if (!pendingMotivationUrl.isNullOrBlank()) {
            selectedTab = 3
            pagerState.animateScrollToPage(3)
        }
    }

    // Check service + permission statuses periodically
    LaunchedEffect(Unit) {
        while (true) {
            serviceEnabled = isServiceEnabled()
            batteryOptIgnored = isBatteryOptimizationIgnored()
            overlayGranted = canDrawOverlays()
            miuiDevice = isMiui()
            miuiAutostartGranted = isMiuiAutostartEnabled()
            miuiBackgroundPopupGranted = isMiuiBackgroundPopupEnabled()
            delay(2000)
        }
    }

    // Poll server for config and pending changes updates
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.fetchConfig()
            viewModel.fetchPendingChanges()
            delay(30_000) // Every 30 seconds (accessibility service handles the 3-second blocking poll)
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
                                3 -> "Motivation"
                                else -> "Focus Blocker"
                            }
                        )
                        authState.userEmail?.let { email ->
                            Text(email, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.fetchConfig()
                        viewModel.fetchPendingChanges()
                    }) {
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
                    onClick = { selectedTab = 0; scope.launch { pagerState.animateScrollToPage(0) } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Block, contentDescription = null) },
                    label = { Text("Block") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; scope.launch { pagerState.animateScrollToPage(1) } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Shield, contentDescription = null) },
                    label = { Text("Whitelist") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2; scope.launch { pagerState.animateScrollToPage(2) } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    label = { Text("Motivation") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3; scope.launch { pagerState.animateScrollToPage(3) } }
                )
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(padding)
        ) { page ->
            when (page) {
                0 -> HomePage(viewModel, uiState, serviceEnabled, batteryOptIgnored, overlayGranted, miuiDevice, miuiAutostartGranted, miuiBackgroundPopupGranted, onOpenAccessibilitySettings, onRequestBatteryExclusion, onOpenOverlaySettings, onOpenMiuiAutostartSettings, onOpenMiuiOverlaySettings, onRequestDeviceAdmin)
                1 -> BlockPage(viewModel, uiState)
                2 -> WhitelistPage(viewModel, uiState)
                3 -> MotivationPage(
                    viewModel = viewModel,
                    uiState = uiState,
                    autoPlayUrl = if (pagerState.currentPage == 3) pendingMotivationUrl else null,
                    onAutoPlayConsumed = onMotivationUrlConsumed
                )
            }
        }

        uiState.errorMessage?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearMessages() }) { Text("Dismiss") }
                }
            ) { Text("❌ $message") }
        }

        uiState.successMessage?.let { message ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = { viewModel.clearMessages() }) { Text("OK") }
                },
                containerColor = Color(0xFF4CAF50)
            ) { Text(message, color = Color.White) }
        }
    }
}

// ==================================
// HOME PAGE
// ==================================

@Composable
fun HomePage(
    viewModel: AuthViewModel,
    uiState: com.focusapp.blocker.ui.AppUiState,
    serviceEnabled: Boolean,
    batteryOptIgnored: Boolean,
    overlayGranted: Boolean,
    miuiDevice: Boolean,
    miuiAutostartGranted: Boolean,
    miuiBackgroundPopupGranted: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestBatteryExclusion: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenMiuiAutostartSettings: () -> Unit,
    onOpenMiuiOverlaySettings: () -> Unit,
    onRequestDeviceAdmin: () -> Unit
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Only show warning cards when something needs attention
        if (!serviceEnabled) {
            item {
                EnablePermissionsCard(onOpenSettings = onOpenAccessibilitySettings)
            }
        }

        if (!batteryOptIgnored) {
            item {
                BatteryOptimizationCard(onRequestExclusion = onRequestBatteryExclusion)
            }
        }

        // On MIUI: show overlay card only if not granted AND not the MIUI device
        // (on MIUI the dedicated MiuiOverlayCard handles this below)
        if (!overlayGranted && !miuiDevice) {
            item {
                OverlayPermissionCard(onRequest = onOpenOverlaySettings)
            }
        }

        // MIUI-specific: Autostart card (disappears once granted)
        if (miuiDevice && !miuiAutostartGranted) {
            item {
                MiuiAutostartCard(onOpenSettings = onOpenMiuiAutostartSettings)
            }
        }

        // MIUI-specific: Display pop-up windows card (disappears once overlay is granted)
        if (miuiDevice && (!overlayGranted || !miuiBackgroundPopupGranted)) {
            item {
                MiuiOverlayCard(
                    overlayGranted = overlayGranted,
                    backgroundPopupGranted = miuiBackgroundPopupGranted,
                    onOpenSettings = onOpenMiuiOverlaySettings
                )
            }
        }

        // Deletion protection toggle
        item {
            DeletionProtectionCard(
                isEnabled = uiState.deletionProtectionEnabled,
                isPendingDisable = uiState.pendingChanges.any { it.type == "disable_deletion_protection" },
                pendingChange = uiState.pendingChanges.firstOrNull { it.type == "disable_deletion_protection" },
                onEnable = {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminComponent = ComponentName(context, FocusDeviceAdminReceiver::class.java)
                    if (dpm.isAdminActive(adminComponent)) {
                        viewModel.onDeviceAdminEnabled()
                    } else {
                        onRequestDeviceAdmin()
                    }
                },
                onRequestDisable = { viewModel.requestDisableDeletionProtection() },
                onCancelDisable = { change -> viewModel.cancelPendingChange(change.id) }
            )
        }

        // Pending changes summary (if any)
        if (uiState.pendingChanges.isNotEmpty()) {
            item {
                PendingChangesCard(
                    pendingChanges = uiState.pendingChanges,
                    onCancel = { change -> viewModel.cancelPendingChange(change.id) }
                )
            }
        }

        // Statistics
        item {
            StatsCard(uiState)
        }

        // Advanced Settings
        item {
            AdvancedSettingsSection(
                serverUrl = uiState.serverUrl,
                onServerUrlChange = { viewModel.updateServerUrl(it) }
            )
        }
    }
}

// ==================================
// BLOCK PAGE
// ==================================

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
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Blocked Apps
        item {
            Text("Blocked Apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
            val pendingRemoval = uiState.pendingChanges.firstOrNull {
                it.type == "remove_blocked_package" && it.value == packageName
            }
            BlockedItemCard(
                text = packageName,
                pendingChange = pendingRemoval,
                onRemove = { viewModel.removeBlockedPackage(packageName) },
                onCancelPending = { pendingRemoval?.let { viewModel.cancelPendingChange(it.id) } }
            )
        }

        // Blocked Keywords
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Blocked Keywords", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            ItemInputSection(placeholder = "gambling", onAdd = { viewModel.addBlockedKeyword(it) })
        }
        items(uiState.blockedKeywords.toList()) { keyword ->
            val pendingRemoval = uiState.pendingChanges.firstOrNull {
                it.type == "remove_blocked_keyword" && it.value == keyword
            }
            BlockedItemCard(
                text = keyword,
                pendingChange = pendingRemoval,
                onRemove = { viewModel.removeBlockedKeyword(keyword) },
                onCancelPending = { pendingRemoval?.let { viewModel.cancelPendingChange(it.id) } }
            )
        }

        // Blocked Websites
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Blocked Websites", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            ItemInputSection(placeholder = "facebook.com", onAdd = { viewModel.addBlockedWebsite(it) })
        }
        items(uiState.blockedWebsites.toList()) { website ->
            val pendingRemoval = uiState.pendingChanges.firstOrNull {
                it.type == "remove_blocked_website" && it.value == website
            }
            BlockedItemCard(
                text = website,
                pendingChange = pendingRemoval,
                onRemove = { viewModel.removeBlockedWebsite(website) },
                onCancelPending = { pendingRemoval?.let { viewModel.cancelPendingChange(it.id) } }
            )
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

// ==================================
// WHITELIST PAGE
// ==================================

@Composable
fun WhitelistPage(viewModel: AuthViewModel, uiState: com.focusapp.blocker.ui.AppUiState) {
    var showAppPicker by remember { mutableStateOf(false) }

    if (showAppPicker) {
        AppPickerDialog(
            onDismiss = { showAppPicker = false },
            onAppSelected = { packageName -> viewModel.addWhitelistedPackage(packageName) },
            title = "Select App to Whitelist"
        )
    }

    // Pending whitelist additions not yet confirmed
    val pendingWhitelistPackages = uiState.pendingChanges.filter { it.type == "add_whitelisted_package" }
    val pendingWhitelistWebsites = uiState.pendingChanges.filter { it.type == "add_whitelisted_website" }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("ℹ️ About Whitelists", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Whitelisted apps and websites are never blocked. Adding items takes 24 hours to take effect.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        // Whitelisted Apps
        item {
            Text("Whitelisted Apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
        // Pending whitelist package additions
        items(pendingWhitelistPackages) { change ->
            PendingAddCard(
                text = change.value ?: "",
                scheduledFor = change.scheduledFor,
                onCancel = { viewModel.cancelPendingChange(change.id) }
            )
        }

        // Whitelisted Websites
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Whitelisted Websites", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            ItemInputSection(placeholder = "yourbank.com", onAdd = { viewModel.addWhitelistedWebsite(it) })
        }
        items(uiState.whitelistedWebsites.toList()) { website ->
            ItemCard(
                text = website,
                onRemove = { viewModel.removeWhitelistedWebsite(website) }
            )
        }
        // Pending whitelist website additions
        items(pendingWhitelistWebsites) { change ->
            PendingAddCard(
                text = change.value ?: "",
                scheduledFor = change.scheduledFor,
                onCancel = { viewModel.cancelPendingChange(change.id) }
            )
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

// ==================================
// UI COMPONENTS
// ==================================

@Composable
fun DeletionProtectionCard(
    isEnabled: Boolean,
    isPendingDisable: Boolean,
    pendingChange: PendingChange?,
    onEnable: () -> Unit,
    onRequestDisable: () -> Unit,
    onCancelDisable: (PendingChange) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isEnabled) Color(0xFF1565C0) else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Deletion Protection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    if (isEnabled) "🔒 ON" else "OFF",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled) Color(0xFF1565C0) else Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "When enabled, the app becomes a Device Administrator. " +
                    "Android requires deactivating admin status before the app can be uninstalled.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (!isEnabled && !isPendingDisable) {
                Button(
                    onClick = onEnable,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                ) {
                    Text("Enable Deletion Protection")
                }
            } else if (isEnabled && !isPendingDisable) {
                OutlinedButton(
                    onClick = onRequestDisable,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Request to Disable (24h delay)")
                }
            } else if (isPendingDisable && pendingChange != null) {
                // Show pending disable with countdown and cancel option
                val hoursLeft = hoursUntil(pendingChange.scheduledFor)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "⏳ Disabling in ~${hoursLeft}h",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                            Text(
                                "Tap cancel to keep protection on",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                        TextButton(
                            onClick = { onCancelDisable(pendingChange) },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1565C0))
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PendingChangesCard(
    pendingChanges: List<PendingChange>,
    onCancel: (PendingChange) -> Unit
) {
    // Exclude deletion protection from this summary (it has its own card)
    val displayChanges = pendingChanges.filter { it.type != "disable_deletion_protection" }
    if (displayChanges.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "⏳ Scheduled Changes (${displayChanges.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "These changes will take effect after 24 hours. Tap × to cancel.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            displayChanges.forEach { change ->
                val hoursLeft = hoursUntil(change.scheduledFor)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            formatChangeType(change.type),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Text(
                            change.value ?: "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "~${hoursLeft}h",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100)
                    )
                    IconButton(onClick = { onCancel(change) }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Red, modifier = Modifier.size(18.dp))
                    }
                }
                Divider(color = Color(0xFFFFE082))
            }
        }
    }
}

@Composable
fun EnablePermissionsCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "⚠️ Enable Permissions",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "1. Tap the button below\n2. Go to \"Downloaded apps → Focus Blocker\"\n3. Toggle \"Use Focus Blocker\" ON",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF616161)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Accessibility Settings")
            }
        }
    }
}

@Composable
fun BatteryOptimizationCard(onRequestExclusion: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "⚡ Battery Optimization",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Android may stop blocking after 1-2 days unless you disable battery optimization for this app. Tap below to fix this.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF616161)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRequestExclusion,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6F00))
            ) {
                Text("Disable Battery Optimization")
            }
        }
    }
}

@Composable
fun OverlayPermissionCard(onRequest: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🪟 Display Pop-up Permission",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Required so the app can reopen itself when you try to access protected settings. Tap below and enable \"Display pop-up windows\".",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF616161)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRequest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6F00))
            ) {
                Text("Grant Permission")
            }
        }
    }
}

@Composable
fun MiuiAutostartCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🚀 Enable Autostart (MIUI)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "MIUI/Poco restricts apps from starting automatically. Enable Autostart so the blocker can restart itself after a reboot and reopen when you access protected settings.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF616161)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6F00))
            ) {
                Text("Enable Autostart")
            }
        }
    }
}

@Composable
fun MiuiOverlayCard(
    overlayGranted: Boolean,
    backgroundPopupGranted: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🪟 Allow Pop-up Windows (MIUI)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "MIUI/Poco requires two permissions so the app can reopen itself when you access protected settings. Open \"Other permissions\" and enable both:",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF616161)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (backgroundPopupGranted) "✅" else "❌",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Open new windows while running in the background",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF616161)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (overlayGranted) "✅" else "❌",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Display pop-up windows",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF616161)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6F00))
            ) {
                Text("Open Other Permissions")
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
            Text("📊 Current Configuration", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Blocked Apps", uiState.blockedPackages.size.toString())
                StatItem("Blocked Keywords", uiState.blockedKeywords.size.toString())
                StatItem("Blocked Sites", uiState.blockedWebsites.size.toString())
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
fun AdvancedSettingsSection(serverUrl: String, onServerUrlChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var editedUrl by remember { mutableStateOf(serverUrl) }

    LaunchedEffect(serverUrl) { editedUrl = serverUrl }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Advanced Settings", style = MaterialTheme.typography.titleSmall, color = Color.Gray)
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.Gray
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Backend Server URL", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = editedUrl,
                    onValueChange = { editedUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://focus-blocker-backend.onrender.com") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { onServerUrlChange(editedUrl) }, modifier = Modifier.align(Alignment.End)) {
                    Text("Save URL")
                }
            }
        }
    }
}

/** Shows a blocked item. If a pending removal exists, shows an orange warning + cancel instead of the X. */
@Composable
fun BlockedItemCard(
    text: String,
    pendingChange: PendingChange?,
    onRemove: () -> Unit,
    onCancelPending: () -> Unit
) {
    val hasPending = pendingChange != null
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasPending) Color(0xFFFFF3E0) else Color(0xFFF5F5F5)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text)
                if (hasPending) {
                    val hoursLeft = hoursUntil(pendingChange!!.scheduledFor)
                    Text(
                        "⏳ Removing in ~${hoursLeft}h",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100)
                    )
                }
            }
            if (hasPending) {
                TextButton(
                    onClick = onCancelPending,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1565C0))
                ) { Text("Undo") }
            } else {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Red)
                }
            }
        }
    }
}

/** Shows a pending whitelist addition (not yet active). */
@Composable
fun PendingAddCard(text: String, scheduledFor: String, onCancel: () -> Unit) {
    val hoursLeft = hoursUntil(scheduledFor)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text)
                Text(
                    "⏳ Will be whitelisted in ~${hoursLeft}h",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE65100)
                )
            }
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1565C0))
            ) { Text("Cancel") }
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
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (isProtected) Text("🔒 ", fontSize = MaterialTheme.typography.bodyLarge.fontSize)
                Text(text)
            }
            if (!isProtected) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Red)
                }
            } else {
                Text("Protected", style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
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
        IconButton(onClick = {
            if (text.isNotBlank()) { onAdd(text.trim()); text = "" }
        }) {
            Icon(Icons.Default.Add, contentDescription = "Add")
        }
    }
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
            IconButton(onClick = {
                if (text.isNotBlank()) { onAdd(text.trim()); text = "" }
            }) {
                Icon(Icons.Default.Add, contentDescription = "Add manually")
            }
        }
        if (showAppPicker) {
            OutlinedButton(onClick = onPickApp, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Apps, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pick from Installed Apps")
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

    LaunchedEffect(searchQuery) {
        isLoading = true
        apps = if (searchQuery.isBlank()) appPickerHelper.getInstalledApps()
        else appPickerHelper.searchApps(searchQuery)
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
                    Box(modifier = Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                        items(apps) { app ->
                            AppPickerItem(appInfo = app, onClick = { onAppSelected(app.packageName); onDismiss() })
                        }
                        if (apps.isEmpty()) {
                            item {
                                Text(
                                    "No apps found",
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun AppPickerItem(appInfo: AppInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (appInfo.icon != null) {
            val bitmap = remember(appInfo.icon) { appInfo.icon.toBitmap(48, 48).asImageBitmap() }
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(48.dp))
        } else {
            Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color.Gray)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(appInfo.appName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(appInfo.packageName, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
    Divider()
}

// ==================================
// HELPERS
// ==================================

/** Returns approximate hours until the ISO date string. */
fun hoursUntil(isoDate: String): Long {
    return try {
        val target = Instant.parse(isoDate)
        val now = Instant.now()
        val hours = ChronoUnit.HOURS.between(now, target)
        maxOf(0L, hours)
    } catch (e: Exception) {
        24L
    }
}

fun formatChangeType(type: String): String = when (type) {
    "remove_blocked_website" -> "Remove blocked website"
    "remove_blocked_package" -> "Remove blocked app"
    "remove_blocked_keyword" -> "Remove blocked keyword"
    "add_whitelisted_website" -> "Add whitelisted website"
    "add_whitelisted_package" -> "Add whitelisted app"
    "disable_deletion_protection" -> "Disable deletion protection"
    else -> type
}
