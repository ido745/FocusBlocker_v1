package com.focusapp.blocker

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focusapp.blocker.data.DonationManager
import com.focusapp.blocker.data.PendingChange
import com.focusapp.blocker.receiver.FocusDeviceAdminReceiver
import com.focusapp.blocker.service.BlockingAccessibilityService
import com.focusapp.blocker.service.FocusBlockerForegroundService
import com.focusapp.blocker.ui.AccessibilityDisclosureDialog
import com.focusapp.blocker.ui.AppInfo
import com.focusapp.blocker.ui.AppPickerHelper
import com.focusapp.blocker.ui.AppStrings
import com.focusapp.blocker.ui.AuthViewModel
import com.focusapp.blocker.ui.EnglishStrings
import com.focusapp.blocker.ui.HebrewStrings
import com.focusapp.blocker.ui.LocalIsHebrew
import com.focusapp.blocker.ui.LocalOnThemeChange
import com.focusapp.blocker.ui.LocalOnToggleLanguage
import com.focusapp.blocker.ui.LocalStrings
import com.focusapp.blocker.ui.LocalThemeMode
import com.focusapp.blocker.ui.MotivationPage
import com.focusapp.blocker.ui.MotivationPlayerWithResolution
import com.focusapp.blocker.ui.TermsScreen
import com.focusapp.blocker.ui.TERMS_VERSION
import com.focusapp.blocker.ui.formatChangeType
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

private val AppColorScheme = lightColorScheme(
    primary          = Color(0xFF5C6BC0),
    onPrimary        = Color.White,
    primaryContainer = Color(0xFFE8EAF6),
    onPrimaryContainer = Color(0xFF1A237E),
    secondary        = Color(0xFF26A69A),
    onSecondary      = Color.White,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF004D40),
    tertiary         = Color(0xFF7C4DFF),
    surface          = Color(0xFFFFFFFF),
    onSurface        = Color(0xFF1A1A2E),
    surfaceVariant   = Color(0xFFF0F0F8),
    background       = Color(0xFFE8E8F0),
    onBackground     = Color(0xFF1A1A2E),
    error            = Color(0xFFE53935),
    onError          = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary             = Color(0xFF7986CB),
    onPrimary           = Color(0xFF1A1A2E),
    primaryContainer    = Color(0xFF283593),
    onPrimaryContainer  = Color(0xFFE8EAF6),
    secondary           = Color(0xFF4DB6AC),
    onSecondary         = Color(0xFF1A1A2E),
    secondaryContainer  = Color(0xFF00695C),
    onSecondaryContainer = Color(0xFFB2DFDB),
    tertiary            = Color(0xFFB39DDB),
    surface             = Color(0xFF1E1E2E),
    onSurface           = Color(0xFFE8E8F0),
    surfaceVariant      = Color(0xFF2A2A3E),
    onSurfaceVariant    = Color(0xFFCACADA),
    background          = Color(0xFF121218),
    onBackground        = Color(0xFFE8E8F0),
    outline             = Color(0xFF5A5A70),
    outlineVariant      = Color(0xFF3A3A50),
    error               = Color(0xFFEF9A9A),
    onError             = Color(0xFF1A1A2E)
)

private const val CONTACT_FORM_URL =
    "https://docs.google.com/forms/d/e/1FAIpQLSfGoROA5ZLNd-ZJbE854WRLdwyaBy_CPub8kNCkk8eG8N62WA/viewform?usp=publish-editor"

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var authViewModel: AuthViewModel? = null

    private val pendingMotivationUrl = mutableStateOf<String?>(null)

    // Gate in front of every accessibility-permission request. Activity-scoped so any entry
    // point into the setup flow goes through the disclosure.
    private val showA11yDisclosure = mutableStateOf(false)

    private val adminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            authViewModel?.onDeviceAdminEnabled()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — notification silently absent if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val isDark = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        @Suppress("DEPRECATION")
        window.statusBarColor = if (isDark) 0xFF121218.toInt() else 0xFFE8E8F0.toInt()
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !isDark

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        FocusBlockerForegroundService.startService(this)

        handleMotivationIntent(intent)

        val langPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val savedLang = langPrefs.getString("language", null)
        val systemIsHebrew = Locale.getDefault().language.let { it == "iw" || it == "he" }
        val initialHebrew = if (savedLang != null) savedLang == "he" else systemIsHebrew
        val initialTheme = langPrefs.getString("theme", "system") ?: "system"

        setContent {
            var isHebrew by remember { mutableStateOf(initialHebrew) }
            var themeMode by remember { mutableStateOf(initialTheme) }
            val strings = if (isHebrew) HebrewStrings else EnglishStrings
            val systemIsDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> systemIsDark
            }
            val toggleLanguage: () -> Unit = {
                isHebrew = !isHebrew
                langPrefs.edit().putString("language", if (isHebrew) "he" else "en").apply()
            }
            val onThemeChange: (String) -> Unit = { mode ->
                themeMode = mode
                langPrefs.edit().putString("theme", mode).apply()
            }

            CompositionLocalProvider(
                LocalStrings provides strings,
                LocalIsHebrew provides isHebrew,
                LocalOnToggleLanguage provides toggleLanguage,
                LocalLayoutDirection provides if (isHebrew) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalThemeMode provides themeMode,
                LocalOnThemeChange provides onThemeChange
            ) {
                MaterialTheme(colorScheme = if (isDark) DarkColorScheme else AppColorScheme) {
                    val viewModel: AuthViewModel = viewModel()
                    authViewModel = viewModel
                    val termsVersion by viewModel.termsAcceptedVersion.collectAsState(initial = -1)

                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        if (showA11yDisclosure.value) {
                            AccessibilityDisclosureDialog(
                                onAccept = {
                                    showA11yDisclosure.value = false
                                    openAccessibilitySettings()
                                },
                                onDismiss = { showA11yDisclosure.value = false }
                            )
                        }

                        when {
                            termsVersion == -1 -> Box(Modifier.fillMaxSize())
                            termsVersion < TERMS_VERSION -> TermsScreen(onAccepted = { viewModel.acceptTerms(TERMS_VERSION) })
                            else -> MainScreen(
                                viewModel = viewModel,
                                pendingMotivationUrl = pendingMotivationUrl.value,
                                onMotivationUrlConsumed = { pendingMotivationUrl.value = null },
                                // Routed through the disclosure rather than straight to
                                // settings — Play requires the explanation to precede the
                                // permission request, every time, from wherever it starts.
                                onOpenAccessibilitySettings = { showA11yDisclosure.value = true },
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
                                onRequestDeviceAdmin = { requestDeviceAdmin() },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleMotivationIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // If protection is still active but the player isn't showing (e.g. the activity was
        // restarted after being swiped from recents), immediately re-display the motivation.
        val activeUrl = BlockingAccessibilityService.lastMotivationUrl
        if (activeUrl.isNotEmpty() && pendingMotivationUrl.value.isNullOrBlank()) {
            pendingMotivationUrl.value = activeUrl
        }
    }

    // Called when the user presses HOME or opens the recents screen.
    // If motivation is active (triggered by protection), immediately bring ourselves back
    // to the foreground so the user cannot swipe us away from recents.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (BlockingAccessibilityService.lastMotivationUrl.isNotEmpty()) {
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
        }
    }

    private fun handleMotivationIntent(intent: Intent?) {
        if (intent?.action == FocusBlockerForegroundService.ACTION_LAUNCH_MOTIVATION) {
            val url = intent.getStringExtra(FocusBlockerForegroundService.EXTRA_VIDEO_URL)
            if (!url.isNullOrBlank()) {
                pendingMotivationUrl.value = url
            }
        }
    }

    private fun openAccessibilitySettings() {
        BlockingAccessibilityService.openedFromApp = true
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
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
            val result = method.invoke(ops, 10008, android.os.Process.myUid(), packageName) as Int
            result != android.app.AppOpsManager.MODE_IGNORED
        } catch (e: Exception) {
            true
        }
    }

    fun isMiuiBackgroundPopupEnabled(): Boolean {
        return try {
            val ops = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val method = ops.javaClass.getMethod(
                "checkOpNoThrow", Int::class.java, Int::class.java, String::class.java
            )
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
            if (isMiui()) {
                // MIUI ignores ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS and shows a
                // useless battery-usage page. ACTION_APPLICATION_DETAILS_SETTINGS only
                // shows the battery toggle for apps that have consumed significant battery,
                // so new installs never see it. The standard battery-optimization list
                // (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) shows all apps via the
                // "All apps" dropdown — reliable across all MIUI versions.
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
            } else {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        }
    }

    fun requestDeviceAdmin() {
        // Opens the system Device Admin page, which lives in the Settings package and names
        // this app — so settings protection treats it as a page to evict. Every other
        // app-initiated settings launch opens this grant window first; this one was missed,
        // which made it impossible to turn deletion protection ON while settings protection
        // was active. Reaching the same page from anywhere else is still blocked, because
        // the window is only opened here, immediately before the launch.
        BlockingAccessibilityService.openedFromApp = true
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
    var serviceEnabled by remember { mutableStateOf(true) }
    // Whether the service has ever been confirmed on in this process lifetime.
    var serviceEverConfirmedOn by remember { mutableStateOf(false) }
    // Timestamp of the first poll in the *current* consecutive off-run; Long.MIN_VALUE when service is on.
    var serviceWentOffAt by remember { mutableLongStateOf(Long.MIN_VALUE) }
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

    LaunchedEffect(pendingMotivationUrl) {
        if (!pendingMotivationUrl.isNullOrBlank()) {
            selectedTab = 3
            pagerState.scrollToPage(3)
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val isOn = isServiceEnabled()
            val now = System.currentTimeMillis()
            if (isOn) {
                serviceEverConfirmedOn = true
                serviceWentOffAt = Long.MIN_VALUE   // reset current off-run
                serviceEnabled = true
            } else {
                // Record the start of this off-run (only on first off-poll, reset when service comes back).
                if (serviceWentOffAt == Long.MIN_VALUE) serviceWentOffAt = now
                val offRunMs = now - serviceWentOffAt

                if (!serviceEverConfirmedOn) {
                    serviceEnabled = false
                } else {
                    if (offRunMs >= 5_000L) serviceEnabled = false
                }
            }
            batteryOptIgnored = isBatteryOptimizationIgnored()
            overlayGranted = canDrawOverlays()
            miuiDevice = isMiui()
            miuiAutostartGranted = isMiuiAutostartEnabled()
            miuiBackgroundPopupGranted = isMiuiBackgroundPopupEnabled()
            kotlinx.coroutines.delay(2000)
        }
    }

    val s = LocalStrings.current
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text(s.tabHome) },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0; scope.launch { pagerState.animateScrollToPage(0) } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Block, contentDescription = null) },
                    label = { Text(s.tabBlock) },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1; scope.launch { pagerState.animateScrollToPage(1) } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Shield, contentDescription = null) },
                    label = { Text(s.tabWhitelist) },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2; scope.launch { pagerState.animateScrollToPage(2) } }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) },
                    label = { Text(s.tabMotivation) },
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
                3 -> MotivationPage(viewModel = viewModel, uiState = uiState)
            }
        }
    }

    pendingMotivationUrl?.let { url ->
        MotivationPlayerWithResolution(
            rawUrl = url,
            viewModel = viewModel,
            duration = uiState.motivation.duration,
            onDismiss = onMotivationUrlConsumed
        )
    }
    }
}

// ==================================
// SUPPORT HEADER
// ==================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportHeader() {
    val context = LocalContext.current
    val s = LocalStrings.current
    val isHebrew = LocalIsHebrew.current
    val onToggleLanguage = LocalOnToggleLanguage.current
    val themeMode = LocalThemeMode.current
    val onThemeChange = LocalOnThemeChange.current
    var showCoffeeDialog by remember { mutableStateOf(false) }
    var showSettingsMenu by remember { mutableStateOf(false) }

    if (showCoffeeDialog) {
        CoffeeDialog(onDismiss = { showCoffeeDialog = false })
    }

    if (showSettingsMenu) {
        Dialog(
            onDismissRequest = { showSettingsMenu = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp
            var cardVisible by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { cardVisible = true }

            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                        .clickable { showSettingsMenu = false }
                )
                AnimatedVisibility(
                    visible = cardVisible,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 3 }),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = topPad, end = 12.dp)
                ) {
                    Card(
                        onClick = {},
                        shape = RoundedCornerShape(24.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 16.dp),
                        modifier = Modifier.width(300.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.Language, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(s.settingsLanguage, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SettingsChip("English", !isHebrew) { if (isHebrew) onToggleLanguage() }
                                    SettingsChip("עברית", isHebrew) { if (!isHebrew) onToggleLanguage() }
                                }
                            }
                            Divider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(Icons.Default.Palette, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                                    Text(s.settingsTheme, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    SettingsChip(s.themeLight, themeMode == "light", modifier = Modifier.weight(1f)) { onThemeChange("light") }
                                    SettingsChip(s.themeDark, themeMode == "dark", modifier = Modifier.weight(1f)) { onThemeChange("dark") }
                                    SettingsChip(s.themeSystem, themeMode == "system", modifier = Modifier.weight(1f)) { onThemeChange("system") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = { openUrlInBrowser(context, CONTACT_FORM_URL) },
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(s.contactUs, style = MaterialTheme.typography.labelMedium)
        }
        OutlinedButton(
            onClick = { showCoffeeDialog = true },
            modifier = Modifier.weight(1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Text("☕", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(6.dp))
            Text(s.supportUs, style = MaterialTheme.typography.labelMedium)
        }
        IconButton(onClick = { showSettingsMenu = !showSettingsMenu }) {
            Icon(
                Icons.Default.Settings,
                contentDescription = s.settingsTitle,
                tint = if (showSettingsMenu) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

// ==================================
// COFFEE DIALOG
// ==================================

/**
 * Compose's LocalContext is not reliably the Activity — inside a Dialog it is usually a
 * ContextThemeWrapper. `context as? Activity` therefore returns null, and because the
 * donate buttons were guarded by `activity?.let { ... }` they would have silently done
 * nothing: taps with no billing sheet, no error, no way to donate. Unwrap instead.
 */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun CoffeeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val manager = remember { DonationManager(context) }
    val donationState by manager.state.collectAsState()
    val s = LocalStrings.current

    DisposableEffect(Unit) {
        manager.connect()
        onDispose { manager.disconnect() }
    }

    AlertDialog(
        onDismissRequest = { if (donationState !is DonationManager.State.Loading) onDismiss() },
        title = { Text(s.supportUsDialogTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val ds = donationState) {
                    is DonationManager.State.Loading -> {
                        Text(s.supportUsBody, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    is DonationManager.State.Ready -> {
                        Text(s.supportUsBody, style = MaterialTheme.typography.bodyMedium)
                        ds.products.forEach { product ->
                            val price = product.oneTimePurchaseOfferDetails?.formattedPrice
                                ?: return@forEach
                            OutlinedButton(
                                onClick = { activity?.let { manager.launchPurchase(it, product) } },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 12.dp)
                            ) {
                                Text(price, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    is DonationManager.State.Success -> {
                        Text(s.thankYou, style = MaterialTheme.typography.bodyMedium)
                    }
                    is DonationManager.State.Unavailable -> {
                        Text(s.tipsUnavailable, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            when (donationState) {
                is DonationManager.State.Success,
                is DonationManager.State.Unavailable -> Button(onClick = onDismiss) { Text(s.close) }
                else -> {}
            }
        },
        dismissButton = {
            when (donationState) {
                is DonationManager.State.Loading,
                is DonationManager.State.Ready -> TextButton(onClick = onDismiss) { Text(s.cancel) }
                else -> {}
            }
        }
    )
}

// ==================================
// SETTINGS CHIP
// ==================================

@Composable
fun SettingsChip(
    label: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(
                start = if (icon != null) 10.dp else 14.dp,
                end = 14.dp, top = 7.dp, bottom = 7.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
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
        item { SupportHeader() }

        item { StatsCard(uiState) }

        if (!serviceEnabled) {
            item { EnablePermissionsCard(onOpenSettings = onOpenAccessibilitySettings) }
        }

        if (!batteryOptIgnored) {
            item { BatteryOptimizationCard(onRequestExclusion = onRequestBatteryExclusion) }
        }

        if (!overlayGranted) {
            item { OverlayPermissionCard(onRequest = onOpenOverlaySettings) }
        }

        if (miuiDevice && !miuiAutostartGranted) {
            item { MiuiAutostartCard(onOpenSettings = onOpenMiuiAutostartSettings) }
        }

        if (miuiDevice && (!overlayGranted || !miuiBackgroundPopupGranted)) {
            item {
                MiuiOverlayCard(
                    overlayGranted = overlayGranted,
                    backgroundPopupGranted = miuiBackgroundPopupGranted,
                    onOpenSettings = onOpenMiuiOverlaySettings
                )
            }
        }

        item {
            ProtectionSettingsCard(
                deletionProtectionEnabled = uiState.deletionProtectionEnabled,
                deletionPendingChange = uiState.pendingChanges.firstOrNull { it.type == "disable_deletion_protection" },
                onEnableDeletion = {
                    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminComponent = ComponentName(context, FocusDeviceAdminReceiver::class.java)
                    if (dpm.isAdminActive(adminComponent)) viewModel.onDeviceAdminEnabled()
                    else onRequestDeviceAdmin()
                },
                onRequestDisableDeletion = { viewModel.requestDisableDeletionProtection() },
                onCancelDeletionDisable = { change -> viewModel.cancelPendingChange(change.id) },
                lockEnabled = uiState.lockEnabled,
                lockPendingChange = uiState.pendingChanges.firstOrNull { it.type == "disable_lock" },
                onEnableLock = { viewModel.enableLock() },
                onRequestDisableLock = { viewModel.requestDisableLock() },
                onCancelLockDisable = { change -> viewModel.cancelPendingChange(change.id) },
                hideAppIcon = uiState.hideAppIcon,
                onHideAppIconChange = { viewModel.setHideAppIcon(it) },
                hideIconPendingChange = uiState.pendingChanges.firstOrNull { it.type == "show_app_icon" },
                onCancelHideIconDisable = { change -> viewModel.cancelPendingChange(change.id) },
                settingsProtectionLevel = uiState.settingsProtectionLevel,
                onSettingsProtectionChange = { viewModel.setSettingsProtectionLevel(it) },
                settingsProtectionPendingChange = uiState.pendingChanges.firstOrNull { it.type == "lower_settings_protection" },
                onCancelSettingsProtectionChange = { change -> viewModel.cancelPendingChange(change.id) }
            )
        }

        item {
            AdditionalBlockingCard(
                adultBlockingLevel = uiState.adultBlockingLevel,
                adultPendingChange = uiState.pendingChanges.firstOrNull {
                    it.type == "disable_adult_blocking" || it.type == "lower_adult_blocking"
                },
                onAdultLevelChange = { level -> viewModel.setAdultBlockingLevel(level) },
                onCancelAdultDisable = { change -> viewModel.cancelPendingChange(change.id) },
                blockYoutubeShorts = uiState.blockYoutubeShorts,
                onBlockYoutubeShortsChange = { viewModel.setBlockYoutubeShorts(it) },
                blockInstagramReels = uiState.blockInstagramReels,
                onBlockInstagramReelsChange = { viewModel.setBlockInstagramReels(it) }
            )
        }

        if (uiState.pendingChanges.isNotEmpty()) {
            item {
                PendingChangesCard(
                    pendingChanges = uiState.pendingChanges,
                    onCancel = { change -> viewModel.cancelPendingChange(change.id) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

// ==================================
// BLOCK PAGE
// ==================================

@Composable
fun BlockPage(viewModel: AuthViewModel, uiState: com.focusapp.blocker.ui.AppUiState) {
    val s = LocalStrings.current
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
        item {
            Text(s.blockedAppsTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            ItemInputWithPicker(
                placeholder = "com.instagram.android",
                onAdd = { viewModel.addBlockedPackage(it) },
                showAppPicker = true,
                onPickApp = { showAppPicker = true }
            )
        }
        item {
            AppIconGrid(
                packages = uiState.blockedPackages.toList(),
                pendingChanges = uiState.pendingChanges.filter { it.type == "remove_blocked_package" },
                onRemove = { viewModel.removeBlockedPackage(it) },
                onCancelPending = { viewModel.cancelPendingChange(it.id) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(s.blockedWebsitesTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            // example.com is reserved by IANA (RFC 2606) precisely for documentation and
            // examples, so it carries no trademark and can't imply any affiliation.
            ItemInputSection(placeholder = "example.com", onAdd = { viewModel.addBlockedWebsite(it) })
        }
        item {
            WebsiteIconGrid(
                websites = uiState.blockedWebsites.toList(),
                pendingChanges = uiState.pendingChanges.filter { it.type == "remove_blocked_website" },
                onRemove = { viewModel.removeBlockedWebsite(it) },
                onCancelPending = { viewModel.cancelPendingChange(it) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(s.blockedKeywordsTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

// ==================================
// WHITELIST PAGE
// ==================================

@Composable
fun WhitelistPage(viewModel: AuthViewModel, uiState: com.focusapp.blocker.ui.AppUiState) {
    val s = LocalStrings.current
    var showAppPicker by remember { mutableStateOf(false) }

    if (showAppPicker) {
        AppPickerDialog(
            onDismiss = { showAppPicker = false },
            onAppSelected = { packageName -> viewModel.addWhitelistedPackage(packageName) },
            title = "Select App to Whitelist"
        )
    }

    val pendingWhitelistPackages = uiState.pendingChanges.filter { it.type == "add_whitelisted_package" }
    val pendingWhitelistWebsites = uiState.pendingChanges.filter { it.type == "add_whitelisted_website" }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(s.whitelistedAppsTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            ItemInputWithPicker(
                placeholder = "com.yourbank.app",
                onAdd = { viewModel.addWhitelistedPackage(it) },
                showAppPicker = true,
                onPickApp = { showAppPicker = true }
            )
        }
        item {
            AppIconGrid(
                packages = uiState.whitelistedPackages.toList(),
                pendingChanges = emptyList(),
                onRemove = { viewModel.removeWhitelistedPackage(it) },
                onCancelPending = {},
                protectedPackages = setOf("com.focusapp.blocker")
            )
        }
        items(pendingWhitelistPackages) { change ->
            PendingAddCard(
                text = change.value ?: "",
                scheduledFor = change.scheduledFor,
                onCancel = { viewModel.cancelPendingChange(change.id) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(s.whitelistedWebsitesTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            ItemInputSection(placeholder = "yourbank.com", onAdd = { viewModel.addWhitelistedWebsite(it) })
        }
        item {
            WebsiteIconGrid(
                websites = uiState.whitelistedWebsites.toList(),
                pendingChanges = emptyList(),
                onRemove = { viewModel.removeWhitelistedWebsite(it) },
                onCancelPending = {}
            )
        }
        items(pendingWhitelistWebsites) { change ->
            PendingAddCard(
                text = change.value ?: "",
                scheduledFor = change.scheduledFor,
                onCancel = { viewModel.cancelPendingChange(change.id) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(s.whitelistedKeywordsTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        item {
            ItemInputSection(placeholder = "mysite.com", onAdd = { viewModel.addWhitelistedKeyword(it) })
        }
        items(uiState.whitelistedKeywords.toList()) { keyword ->
            ItemCard(
                text = keyword,
                onRemove = { viewModel.removeWhitelistedKeyword(keyword) }
            )
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }
}

// ==================================
// UI COMPONENTS
// ==================================

@Composable
fun ProtectionSettingsCard(
    deletionProtectionEnabled: Boolean,
    deletionPendingChange: PendingChange?,
    onEnableDeletion: () -> Unit,
    onRequestDisableDeletion: () -> Unit,
    onCancelDeletionDisable: (PendingChange) -> Unit,
    lockEnabled: Boolean,
    lockPendingChange: PendingChange?,
    onEnableLock: () -> Unit,
    onRequestDisableLock: () -> Unit,
    onCancelLockDisable: (PendingChange) -> Unit,
    hideAppIcon: Boolean,
    onHideAppIconChange: (Boolean) -> Unit,
    hideIconPendingChange: PendingChange?,
    onCancelHideIconDisable: (PendingChange) -> Unit,
    settingsProtectionLevel: Int,
    onSettingsProtectionChange: (Int) -> Unit,
    settingsProtectionPendingChange: PendingChange?,
    onCancelSettingsProtectionChange: (PendingChange) -> Unit
) {
    val s = LocalStrings.current
    val levelLabels = listOf(s.levelOff, s.levelLow, s.levelHigh)
    var showLockWarning by remember { mutableStateOf(false) }

    if (showLockWarning) {
        AlertDialog(
            onDismissRequest = { showLockWarning = false },
            title = { Text(s.lock24hWarningTitle, fontWeight = FontWeight.Bold) },
            text = { Text(s.lock24hWarningBody, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                Button(
                    onClick = { showLockWarning = false; onEnableLock() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
                ) { Text(s.lock24hWarningConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { showLockWarning = false }) { Text(s.cancel) }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(s.protectionTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            ProtectionToggleRow(
                icon = Icons.Default.Shield,
                iconColor = Color(0xFF1565C0),
                title = s.deletionProtTitle,
                subtitle = s.deletionProtSubtitle,
                isEnabled = deletionProtectionEnabled,
                pendingChange = deletionPendingChange,
                onEnable = onEnableDeletion,
                onRequestDisable = onRequestDisableDeletion,
                onCancelDisable = onCancelDeletionDisable
            )
            Divider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            ProtectionToggleRow(
                icon = Icons.Default.Lock,
                iconColor = Color(0xFF7C4DFF),
                title = s.lock24hTitle,
                subtitle = s.lock24hSubtitle,
                isEnabled = lockEnabled,
                pendingChange = lockPendingChange,
                onEnable = { showLockWarning = true },
                onRequestDisable = onRequestDisableLock,
                onCancelDisable = onCancelLockDisable
            )
            Divider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            ProtectionToggleRow(
                icon = Icons.Default.Settings,
                iconColor = Color(0xFF37474F),
                title = s.hideIconTitle,
                subtitle = s.hideIconSubtitle,
                isEnabled = hideAppIcon,
                pendingChange = hideIconPendingChange,
                onEnable = { onHideAppIconChange(true) },
                onRequestDisable = { onHideAppIconChange(false) },
                onCancelDisable = onCancelHideIconDisable
            )
            Divider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(38.dp).background(Color(0xFF1565C0).copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = Color(0xFF1565C0), modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.protectSettingsTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when (settingsProtectionLevel) {
                            1 -> s.protectSettingsLow
                            2 -> s.protectSettingsHigh
                            else -> s.protectSettingsOff
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                levelLabels.forEachIndexed { index, label ->
                    val selected = settingsProtectionLevel == index
                    OutlinedButton(
                        onClick = { onSettingsProtectionChange(index) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) Color(0xFF1565C0) else Color.Transparent,
                            contentColor = if (selected) Color.White else Color(0xFF1565C0)
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (selected) Color(0xFF1565C0) else MaterialTheme.colorScheme.outline
                        ),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text(label, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (settingsProtectionPendingChange != null) {
                val targetLevel = settingsProtectionPendingChange.value?.toIntOrNull() ?: 0
                val targetLabel = levelLabels.getOrElse(targetLevel) { s.levelOff }
                val hoursLeft = hoursUntil(settingsProtectionPendingChange.scheduledFor)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        s.loweringToLevel(targetLabel, hoursLeft),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE65100),
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { onCancelSettingsProtectionChange(settingsProtectionPendingChange) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1565C0))
                    ) { Text(s.cancel, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
fun AdditionalBlockingCard(
    adultBlockingLevel: Int,
    adultPendingChange: PendingChange?,
    onAdultLevelChange: (Int) -> Unit,
    onCancelAdultDisable: (PendingChange) -> Unit,
    blockYoutubeShorts: Boolean,
    onBlockYoutubeShortsChange: (Boolean) -> Unit,
    blockInstagramReels: Boolean,
    onBlockInstagramReelsChange: (Boolean) -> Unit
) {
    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(s.additionalBlockingTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            AdultBlockingLevelRow(
                level = adultBlockingLevel,
                pendingChange = adultPendingChange,
                onLevelChange = onAdultLevelChange,
                onCancelPending = onCancelAdultDisable
            )
            Divider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            ProtectionToggleRow(
                icon = Icons.Default.PlayArrow,
                iconColor = Color(0xFFFF0000),
                title = s.blockShortsTitle,
                subtitle = s.blockShortsSubtitle,
                isEnabled = blockYoutubeShorts,
                pendingChange = null,
                onEnable = { onBlockYoutubeShortsChange(true) },
                onRequestDisable = { onBlockYoutubeShortsChange(false) },
                onCancelDisable = {}
            )
            Divider(modifier = Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            ProtectionToggleRow(
                icon = Icons.Default.VideoLibrary,
                iconColor = Color(0xFFE1306C),
                title = s.blockReelsTitle,
                subtitle = s.blockReelsSubtitle,
                isEnabled = blockInstagramReels,
                pendingChange = null,
                onEnable = { onBlockInstagramReelsChange(true) },
                onRequestDisable = { onBlockInstagramReelsChange(false) },
                onCancelDisable = {}
            )
        }
    }
}

@Composable
fun AdultBlockingLevelRow(
    level: Int,
    pendingChange: PendingChange?,
    onLevelChange: (Int) -> Unit,
    onCancelPending: (PendingChange) -> Unit
) {
    val s = LocalStrings.current
    val iconColor = Color(0xFFAD1457)
    val pendingColor = Color(0xFFE65100)
    val levels = listOf(s.adultLevelOff, s.adultLevelSites, s.adultLevelStrict)
    // Which level the queued change will drop to when it fires. "disable" carries no value
    // and always means off; "lower" carries its target level.
    val pendingTargetLevel = when (pendingChange?.type) {
        "lower_adult_blocking" -> pendingChange.value?.toIntOrNull() ?: 0
        "disable_adult_blocking" -> 0
        else -> -1
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(iconColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Block, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(s.adultBlockingTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(s.adultBlockingSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (pendingChange != null) {
                val hoursLeft = hoursUntil(pendingChange.scheduledFor)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // A downgrade is not a disable — name the level it is dropping to.
                        if (pendingTargetLevel == 0) s.disablingInHours(hoursLeft)
                        else s.loweringInHours(levels.getOrElse(pendingTargetLevel) { "" }, hoursLeft),
                        style = MaterialTheme.typography.labelSmall,
                        color = pendingColor,
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        onClick = { onCancelPending(pendingChange) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1565C0))
                    ) { Text(s.cancel, style = MaterialTheme.typography.labelSmall) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                levels.forEachIndexed { idx, label ->
                    val isSelected = idx == level
                    // Highlight whichever level is queued, not just "Off" — a pending
                    // downgrade to Sites has to show on the Sites chip.
                    val isPendingOff = idx == pendingTargetLevel
                    val containerColor = when {
                        isPendingOff -> pendingColor.copy(alpha = 0.15f)
                        isSelected -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val labelColor = when {
                        isPendingOff -> pendingColor
                        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    }
                    Surface(
                        onClick = { onLevelChange(idx) },
                        shape = RoundedCornerShape(8.dp),
                        color = containerColor,
                        modifier = Modifier.height(28.dp)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 10.dp)
                        ) {
                            Text(label, style = MaterialTheme.typography.labelSmall, color = labelColor, fontWeight = if (isSelected || isPendingOff) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProtectionToggleRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    isEnabled: Boolean,
    pendingChange: PendingChange?,
    onEnable: () -> Unit,
    onRequestDisable: () -> Unit,
    onCancelDisable: (PendingChange) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier.size(38.dp).background(iconColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        }
        val s = LocalStrings.current
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (pendingChange != null) {
                val hoursLeft = hoursUntil(pendingChange.scheduledFor)
                Text(
                    s.disablingInHours(hoursLeft),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFE65100),
                    fontWeight = FontWeight.Medium
                )
            }
        }
        if (pendingChange != null) {
            TextButton(
                onClick = { onCancelDisable(pendingChange) },
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1565C0))
            ) { Text(s.cancel, style = MaterialTheme.typography.labelMedium) }
        } else {
            Switch(
                checked = isEnabled,
                onCheckedChange = { checked -> if (checked) onEnable() else onRequestDisable() }
            )
        }
    }
}

@Composable
fun PendingChangesCard(
    pendingChanges: List<PendingChange>,
    onCancel: (PendingChange) -> Unit
) {
    val displayChanges = pendingChanges.filter {
        it.type != "disable_deletion_protection" && it.type != "disable_adult_blocking" &&
            it.type != "lower_adult_blocking" &&
            it.type != "disable_lock" && it.type != "unlock_duration" && it.type != "unlock_content" &&
            it.type != "show_app_icon" && it.type != "lower_settings_protection" &&
            it.type != "disable_motivation_on_block" && it.type != "disable_motivation_on_settings"
    }
    if (displayChanges.isEmpty()) return

    val isDark = isSystemInDarkTheme()
    val cardBg       = if (isDark) Color(0xFF2D2600) else Color(0xFFFFF8E1)
    val accentColor  = if (isDark) Color(0xFFFFB300) else Color(0xFFE65100)
    val dividerColor = if (isDark) Color(0xFF4D3B00) else Color(0xFFFFE082)
    val closeColor   = if (isDark) Color(0xFFFF6B6B) else Color.Red

    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                s.scheduledChangesTitle(displayChanges.size),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                s.scheduledChangesBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                            s.formatChangeType(change.type),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            change.value ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        s.hoursLeft(hoursLeft),
                        style = MaterialTheme.typography.bodySmall,
                        color = accentColor
                    )
                    IconButton(onClick = { onCancel(change) }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = closeColor, modifier = Modifier.size(18.dp))
                    }
                }
                Divider(color = dividerColor)
            }
        }
    }
}

@Composable
fun EnablePermissionsCard(onOpenSettings: () -> Unit) {
    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                s.enablePermTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFC62828)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                s.enablePermBody,
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
                Text(s.openAccessibilitySettings)
            }
        }
    }
}

@Composable
fun BatteryOptimizationCard(onRequestExclusion: () -> Unit) {
    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                s.batteryOptTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                s.batteryOptBody,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF616161)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRequestExclusion,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF616161))
            ) {
                Text(s.disableBatteryOpt)
            }
        }
    }
}

@Composable
fun OverlayPermissionCard(onRequest: () -> Unit) {
    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                s.overlayPermTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                s.overlayPermBody,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF616161)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRequest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6F00))
            ) {
                Text(s.grantPermission)
            }
        }
    }
}

@Composable
fun MiuiAutostartCard(onOpenSettings: () -> Unit) {
    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                s.miuiAutostartTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                s.miuiAutostartBody,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF616161)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6F00))
            ) {
                Text(s.enableAutostart)
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
    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                s.miuiOverlayTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE65100)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                s.miuiOverlayBody,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF616161)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (backgroundPopupGranted) "✅" else "❌", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.width(6.dp))
                Text(s.miuiOverlayPerm1, style = MaterialTheme.typography.bodySmall, color = Color(0xFF616161))
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (overlayGranted) "✅" else "❌", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.width(6.dp))
                Text(s.miuiOverlayPerm2, style = MaterialTheme.typography.bodySmall, color = Color(0xFF616161))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6F00))
            ) {
                Text(s.openOtherPermissions)
            }
        }
    }
}

@Composable
fun StatsCard(uiState: com.focusapp.blocker.ui.AppUiState) {
    val s = LocalStrings.current
    val blockedColor = Color(0xFFE53935)
    val whitelistedColor = Color(0xFF2E7D32)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surface else Color(0xFFD5D8F8))
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                s.activeRules,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = blockedColor.copy(alpha = 0.10f)
            ) {
                Text(
                    s.sectionBlocked,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = blockedColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem(s.statApps, uiState.blockedPackages.size.toString(), blockedColor)
                StatItem(s.statSites, uiState.blockedWebsites.size.toString(), blockedColor)
                StatItem(s.statKeywords, uiState.blockedKeywords.size.toString(), blockedColor)
            }

            Spacer(modifier = Modifier.height(14.dp))
            Divider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                shape = RoundedCornerShape(6.dp),
                color = whitelistedColor.copy(alpha = 0.10f)
            ) {
                Text(
                    s.sectionWhitelisted,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = whitelistedColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem(s.statApps, uiState.whitelistedPackages.size.toString(), whitelistedColor)
                StatItem(s.statSites, uiState.whitelistedWebsites.size.toString(), whitelistedColor)
                StatItem(s.statKeywords, uiState.whitelistedKeywords.size.toString(), whitelistedColor)
            }
        }
    }
}

@Composable
fun RowScope.StatItem(label: String, value: String, color: Color = MaterialTheme.colorScheme.primary) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), textAlign = TextAlign.Center)
    }
}

@Composable
fun AppIconGrid(
    packages: List<String>,
    pendingChanges: List<PendingChange>,
    onRemove: (String) -> Unit,
    onCancelPending: (PendingChange) -> Unit,
    protectedPackages: Set<String> = emptySet(),
    columns: Int = 4
) {
    if (packages.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        packages.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { pkg ->
                    val pending = pendingChanges.firstOrNull { it.value == pkg }
                    AppIconItem(
                        packageName = pkg,
                        pendingChange = pending,
                        onRemove = { onRemove(pkg) },
                        onCancelPending = { pending?.let { onCancelPending(it) } },
                        isProtected = pkg in protectedPackages,
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun AppIconItem(
    packageName: String,
    pendingChange: PendingChange?,
    onRemove: () -> Unit,
    onCancelPending: () -> Unit,
    isProtected: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val (iconBitmap, appName) = remember(packageName) {
        try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationIcon(info).toBitmap(64, 64).asImageBitmap() to
                pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            null to packageName
        }
    }
    val hasPending = pendingChange != null

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(52.dp)) {
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = appName,
                    modifier = Modifier.size(48.dp).align(Alignment.Center)
                        .alpha(if (hasPending) 0.5f else 1f)
                )
            } else {
                Box(
                    modifier = Modifier.size(48.dp).align(Alignment.Center)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                        .alpha(if (hasPending) 0.5f else 1f),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
                }
            }
            if (!isProtected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .background(if (hasPending) Color(0xFFE65100) else Color(0xFFE53935), CircleShape)
                        .clickable { if (hasPending) onCancelPending() else onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
            } else {
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
                        .background(Color(0xFF4CAF50), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Lock, null, tint = Color.White, modifier = Modifier.size(11.dp))
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            appName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (hasPending) {
            val hoursLeft = hoursUntil(pendingChange!!.scheduledFor)
            Text(
                "~${hoursLeft}h",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFE65100),
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun WebsiteIconGrid(
    websites: List<String>,
    pendingChanges: List<PendingChange>,
    onRemove: (String) -> Unit,
    onCancelPending: (String) -> Unit,
    columns: Int = 4
) {
    if (websites.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        websites.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { site ->
                    val pending = pendingChanges.firstOrNull { it.value == site }
                    WebsiteIconItem(
                        domain = site,
                        pendingChange = pending,
                        onRemove = { onRemove(site) },
                        onCancelPending = { pending?.let { onCancelPending(it.id) } },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Palette for locally drawn site icons. Picked by a stable hash of the domain, so a given
 * site always gets the same colour.
 */
private val SITE_ICON_COLORS = listOf(
    Color(0xFF5C6BC0), Color(0xFF26A69A), Color(0xFFEF5350), Color(0xFFAB47BC),
    Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726), Color(0xFF8D6E63)
)

private fun siteIconColor(domain: String): Color =
    SITE_ICON_COLORS[((domain.hashCode().toLong() and 0x7fffffffL) % SITE_ICON_COLORS.size).toInt()]

@Composable
fun WebsiteIconItem(
    domain: String,
    pendingChange: PendingChange?,
    onRemove: () -> Unit,
    onCancelPending: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Icons are drawn on device from the domain's first letter.
    //
    // This used to fetch google.com/s2/favicons?domain=… for every entry, which handed Google
    // a list of every site the user blocks or whitelists — adult ones included — tied to
    // their IP. It was the only place user data left the device, and it would have had to be
    // declared as data sharing on Play's Data Safety form. Nothing is fetched now.
    val hasPending = pendingChange != null
    val displayName = domain.removePrefix("www.").let {
        if (it.length > 10) it.take(9) + "…" else it
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(52.dp)) {
            Box(
                modifier = Modifier.size(48.dp).align(Alignment.Center)
                    .background(siteIconColor(domain), RoundedCornerShape(12.dp))
                    .alpha(if (hasPending) 0.5f else 1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = domain.removePrefix("www.").take(1).uppercase().ifBlank { "?" },
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .background(if (hasPending) Color(0xFFE65100) else Color(0xFFE53935), CircleShape)
                    .clickable { if (hasPending) onCancelPending() else onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(11.dp))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            displayName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (hasPending) {
            val hoursLeft = hoursUntil(pendingChange!!.scheduledFor)
            Text(
                "~${hoursLeft}h",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFE65100),
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun BlockedItemCard(
    text: String,
    pendingChange: PendingChange?,
    onRemove: () -> Unit,
    onCancelPending: () -> Unit
) {
    val hasPending = pendingChange != null
    val isDark = isSystemInDarkTheme()
    val pendingBg   = if (isDark) Color(0xFF2D2600) else Color(0xFFFFF3E0)
    val pendingText = if (isDark) Color(0xFFFFB300) else Color(0xFFE65100)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hasPending) pendingBg else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val s = LocalStrings.current
            Column(modifier = Modifier.weight(1f)) {
                Text(text)
                if (hasPending) {
                    val hoursLeft = hoursUntil(pendingChange!!.scheduledFor)
                    Text(
                        s.removingInHours(hoursLeft),
                        style = MaterialTheme.typography.bodySmall,
                        color = pendingText
                    )
                }
            }
            if (hasPending) {
                TextButton(
                    onClick = onCancelPending,
                    colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1565C0))
                ) { Text(s.undo) }
            } else {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Red)
                }
            }
        }
    }
}

@Composable
fun PendingAddCard(text: String, scheduledFor: String, onCancel: () -> Unit) {
    val s = LocalStrings.current
    val hoursLeft = hoursUntil(scheduledFor)
    val isDark = isSystemInDarkTheme()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (isDark) Color(0xFF2D2600) else Color(0xFFFFF3E0))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text)
                Text(
                    s.willBeWhitelistedIn(hoursLeft),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDark) Color(0xFFFFB300) else Color(0xFFE65100)
                )
            }
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1565C0))
            ) { Text(s.cancel) }
        }
    }
}

@Composable
fun ItemCard(text: String, onRemove: () -> Unit, isProtected: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isProtected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
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
                Text(LocalStrings.current.protected_, style = MaterialTheme.typography.bodySmall, color = Color(0xFF4CAF50))
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
                Text(LocalStrings.current.pickFromApps)
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

    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(s.searchApps) },
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
                                    s.noAppsFound,
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
            TextButton(onClick = onDismiss) { Text(s.cancel) }
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

fun openUrlInBrowser(context: Context, url: String) {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val hasInternet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } else {
        @Suppress("DEPRECATION")
        cm.activeNetworkInfo?.isConnected == true
    }
    if (!hasInternet) {
        Toast.makeText(context, "No internet connection", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open browser", Toast.LENGTH_SHORT).show()
    }
}

fun hoursUntil(isoDate: String): Long {
    return try {
        val target = Instant.parse(isoDate)
        val now = Instant.now()
        maxOf(0L, ChronoUnit.HOURS.between(now, target))
    } catch (e: Exception) {
        24L
    }
}
