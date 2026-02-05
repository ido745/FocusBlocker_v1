package com.focusapp.blocker

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.focusapp.blocker.ui.MainViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onOpenAccessibilitySettings = { openAccessibilitySettings() }
                    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onOpenAccessibilitySettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Show messages
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            // In production, use Snackbar
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Focus Blocker") },
                actions = {
                    IconButton(onClick = { viewModel.fetchStatus() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onOpenAccessibilitySettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status Card
            item {
                StatusCard(
                    isSessionActive = uiState.isSessionActive,
                    isLoading = uiState.isLoading
                )
            }

            // Server URL Section
            item {
                ServerUrlSection(
                    serverUrl = uiState.serverUrl,
                    onServerUrlChange = { viewModel.updateServerUrl(it) }
                )
            }

            // Error/Success Messages
            item {
                uiState.errorMessage?.let { message ->
                    ErrorCard(message) { viewModel.clearMessages() }
                }
                uiState.successMessage?.let { message ->
                    SuccessCard(message) { viewModel.clearMessages() }
                }
            }

            // Accessibility Permission
            item {
                PermissionCard(onOpenAccessibilitySettings)
            }

            // Blocked Apps Section
            item {
                Text(
                    "Blocked Apps",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                ItemInputSection(
                    placeholder = "com.instagram.android",
                    onAdd = { viewModel.addBlockedPackage(it) }
                )
            }

            items(uiState.blockedPackages.toList()) { packageName ->
                ItemCard(
                    text = packageName,
                    onRemove = { viewModel.removeBlockedPackage(packageName) }
                )
            }

            // Blocked Keywords Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Blocked Keywords",
                    style = MaterialTheme.typography.titleMedium,
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

            // Blocked Websites Section
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Blocked Websites",
                    style = MaterialTheme.typography.titleMedium,
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

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun StatusCard(isSessionActive: Boolean, isLoading: Boolean) {
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

            if (isLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
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
fun PermissionCard(onOpenSettings: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "⚙️ Accessibility Permission Required",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Enable the Focus Blocker accessibility service to start blocking distractions.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Settings, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open Accessibility Settings")
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
fun ItemCard(text: String, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, modifier = Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Red)
            }
        }
    }
}

@Composable
fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("❌ $message", modifier = Modifier.weight(1f), color = Color.Red)
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
fun SuccessCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✅ $message", modifier = Modifier.weight(1f), color = Color(0xFF4CAF50))
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}
