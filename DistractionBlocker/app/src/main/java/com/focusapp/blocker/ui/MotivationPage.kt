package com.focusapp.blocker.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.focusapp.blocker.data.MotivationConfig
import com.focusapp.blocker.data.MotivationItem
import kotlinx.coroutines.delay

// ============================================================
// Motivation Page — manages videos, channels, and duration
// ============================================================

@Composable
fun MotivationPage(
    viewModel: AuthViewModel,
    uiState: AppUiState,
    autoPlayUrl: String? = null,
    onAutoPlayConsumed: () -> Unit = {}
) {
    val motivation = uiState.motivation
    var playerUrl by remember { mutableStateOf<String?>(null) }
    var showAddVideoDialog by remember { mutableStateOf(false) }
    var showAddChannelDialog by remember { mutableStateOf(false) }

    // Auto-play when guard fires
    LaunchedEffect(autoPlayUrl) {
        if (!autoPlayUrl.isNullOrBlank()) {
            playerUrl = autoPlayUrl
            onAutoPlayConsumed()
        }
    }

    // Show full-screen player when URL is set
    playerUrl?.let { url ->
        MotivationPlayerDialog(
            url = url,
            duration = motivation.duration,
            onDismiss = { playerUrl = null }
        )
    }

    if (showAddVideoDialog) {
        AddItemDialog(
            title = "Add Motivation Video",
            hint = "Paste TikTok, Instagram or YouTube URL",
            onDismiss = { showAddVideoDialog = false },
            onAdd = { url, label ->
                viewModel.addMotivationVideo(url, label)
                showAddVideoDialog = false
            }
        )
    }

    if (showAddChannelDialog) {
        AddItemDialog(
            title = "Add Channel",
            hint = "Paste TikTok or YouTube channel URL",
            onDismiss = { showAddChannelDialog = false },
            onAdd = { url, label ->
                viewModel.addMotivationChannel(url, label)
                showAddChannelDialog = false
            }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Duration slider
        item {
            DurationCard(
                duration = motivation.duration,
                onChange = { viewModel.updateMotivationDuration(it) }
            )
        }

        // Videos section
        item {
            SectionHeader(
                title = "Motivation Videos",
                onAdd = { showAddVideoDialog = true }
            )
        }

        if (motivation.videos.isEmpty()) {
            item {
                Text(
                    "No videos yet — add a TikTok, Instagram Reel, or YouTube video.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        } else {
            itemsIndexed(motivation.videos) { index, item ->
                MotivationItemRow(
                    item = item,
                    onPlay = { playerUrl = item.url },
                    onDelete = { viewModel.removeMotivationVideo(index) }
                )
            }
        }

        // Channels section
        item {
            SectionHeader(
                title = "Channels",
                onAdd = { showAddChannelDialog = true }
            )
        }

        if (motivation.channels.isEmpty()) {
            item {
                Text(
                    "No channels yet — add a TikTok or YouTube channel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
        } else {
            itemsIndexed(motivation.channels) { index, item ->
                MotivationItemRow(
                    item = item,
                    onPlay = { playerUrl = item.url },
                    onDelete = { viewModel.removeMotivationChannel(index) }
                )
            }
        }

        // Test button
        if (motivation.videos.isNotEmpty() || motivation.channels.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        val all = motivation.videos + motivation.channels
                        playerUrl = all.random().url
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Test Random Video")
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ============================================================
// Duration card
// ============================================================

@Composable
fun DurationCard(duration: Int, onChange: (Int) -> Unit) {
    var sliderValue by remember(duration) { mutableFloatStateOf(duration.toFloat()) }
    var pendingChange by remember { mutableStateOf(false) }

    // Debounce: only call onChange after user stops sliding
    LaunchedEffect(sliderValue) {
        if (pendingChange) {
            delay(600)
            onChange(sliderValue.toInt())
            pendingChange = false
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Unskippable Duration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "Video plays for ${sliderValue.toInt()} seconds before you can close it.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it; pendingChange = true },
                valueRange = 0f..300f,
                steps = 29,
                modifier = Modifier.fillMaxWidth()
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0s", style = MaterialTheme.typography.labelSmall)
                Text("${sliderValue.toInt()}s", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Text("300s", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ============================================================
// Section header
// ============================================================

@Composable
fun SectionHeader(title: String, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        IconButton(onClick = onAdd) {
            Icon(Icons.Default.Add, contentDescription = "Add")
        }
    }
}

// ============================================================
// Single item row
// ============================================================

@Composable
fun MotivationItemRow(item: MotivationItem, onPlay: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (!item.label.isNullOrBlank()) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                }
                Text(
                    item.url.take(50) + if (item.url.length > 50) "…" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFE53935))
            }
        }
    }
}

// ============================================================
// Add item dialog
// ============================================================

@Composable
fun AddItemDialog(
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    onAdd: (url: String, label: String?) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(hint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (url.isNotBlank()) onAdd(url.trim(), label.trim().ifBlank { null }) },
                enabled = url.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ============================================================
// Full-screen motivation player with countdown overlay
// ============================================================

@Composable
fun MotivationPlayerDialog(url: String, duration: Int, onDismiss: () -> Unit) {
    var secondsLeft by remember(duration) { mutableIntStateOf(duration) }
    var canClose by remember(duration) { mutableStateOf(duration <= 0) }

    LaunchedEffect(url) {
        secondsLeft = duration
        canClose = duration <= 0
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        canClose = true
    }

    Dialog(
        onDismissRequest = { if (canClose) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = canClose,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            MotivationWebView(url = url)

            // Top bar: timer / close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.End
            ) {
                if (!canClose) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xAA000000), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "$secondsLeft s",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                } else {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .background(Color(0xAA000000), RoundedCornerShape(50))
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
            }

            // "Stay strong" overlay message for first 3 seconds
            AnimatedVisibility(
                visible = secondsLeft > duration - 3 && duration > 3,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        "Stay strong 💪",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ============================================================
// WebView that loads embed URL
// ============================================================

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MotivationWebView(url: String) {
    val embedUrl = remember(url) { toEmbedUrl(url) }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(false)
                }
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
                loadUrl(embedUrl)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

// ============================================================
// URL → embed URL conversion
// ============================================================

fun toEmbedUrl(url: String): String {
    val u = url.trim()

    // YouTube: https://youtu.be/VIDEO_ID  or  https://www.youtube.com/watch?v=VIDEO_ID
    val youtubeId = extractYoutubeId(u)
    if (youtubeId != null) return "https://www.youtube.com/embed/$youtubeId?autoplay=1"

    // Instagram reel/post: https://www.instagram.com/reel/CODE/ or /p/CODE/
    val instagramCode = extractInstagramCode(u)
    if (instagramCode != null) return "https://www.instagram.com/p/$instagramCode/embed/"

    // TikTok: https://www.tiktok.com/@user/video/VIDEO_ID
    val tiktokId = extractTiktokId(u)
    if (tiktokId != null) return "https://www.tiktok.com/embed/v2/$tiktokId"

    // Fallback: load as-is (e.g. YouTube channel page)
    return u
}

private fun extractYoutubeId(url: String): String? {
    // youtu.be/ID
    val shortMatch = Regex("""youtu\.be/([A-Za-z0-9_\-]{11})""").find(url)
    if (shortMatch != null) return shortMatch.groupValues[1]
    // youtube.com/watch?v=ID
    val longMatch = Regex("""[?&]v=([A-Za-z0-9_\-]{11})""").find(url)
    if (longMatch != null) return longMatch.groupValues[1]
    // youtube.com/shorts/ID
    val shortsMatch = Regex("""youtube\.com/shorts/([A-Za-z0-9_\-]{11})""").find(url)
    if (shortsMatch != null) return shortsMatch.groupValues[1]
    return null
}

private fun extractInstagramCode(url: String): String? {
    val match = Regex("""instagram\.com/(?:reel|p|tv)/([A-Za-z0-9_\-]+)""").find(url)
    return match?.groupValues?.get(1)
}

private fun extractTiktokId(url: String): String? {
    val match = Regex("""tiktok\.com/@[^/]+/video/(\d+)""").find(url)
    return match?.groupValues?.get(1)
}
