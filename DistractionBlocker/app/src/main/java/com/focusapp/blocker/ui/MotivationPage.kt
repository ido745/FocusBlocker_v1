package com.focusapp.blocker.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.focusapp.blocker.data.PendingChange
import com.focusapp.blocker.hoursUntil
import com.focusapp.blocker.ui.LocalStrings
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.focusapp.blocker.data.MotivationConfig
import com.focusapp.blocker.data.MotivationItem
import com.focusapp.blocker.receiver.FocusDeviceAdminReceiver
import com.focusapp.blocker.service.BlockingAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

private const val COLLAPSE_THRESHOLD = 4

// ============================================================
// Phrase color palette — each phrase gets a distinct vivid color
// ============================================================

private val phraseColors = listOf(
    Color(0xFF1A237E), // Deep indigo
    Color(0xFF4A148C), // Deep purple
    Color(0xFF1B5E20), // Deep green
    Color(0xFFBF360C), // Deep orange
    Color(0xFF880E4F), // Deep rose
    Color(0xFF006064), // Dark teal
)

private val sectionColorVideos   = Color(0xFF1565C0)
private val sectionColorChannels = Color(0xFF2E7D32)
private val sectionColorPhrases  = Color(0xFF6A1B9A)
private val sectionColorGallery  = Color(0xFFE65100)
private val contentLockColor     = Color(0xFF37474F)

// ============================================================
// Motivation Page
// ============================================================

@Composable
fun MotivationPage(
    viewModel: AuthViewModel,
    uiState: AppUiState
) {
    val s = LocalStrings.current
    val motivation = uiState.motivation
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var playerUrl by remember { mutableStateOf<String?>(null) }
    var showAddVideoDialog by remember { mutableStateOf(false) }
    var showAddChannelDialog by remember { mutableStateOf(false) }
    var showAddPhraseDialog by remember { mutableStateOf(false) }
    var videosExpanded by remember { mutableStateOf(false) }
    var channelsExpanded by remember { mutableStateOf(false) }
    var galleryExpanded by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) viewModel.addGalleryVideo(context, uri, null)
    }

    playerUrl?.let { url ->
        MotivationPlayerWithResolution(
            rawUrl = url,
            viewModel = viewModel,
            duration = motivation.duration,
            onDismiss = { playerUrl = null }
        )
    }

    if (showAddVideoDialog) {
        AddItemDialog(
            title = s.addVideoTitle,
            hint = s.addVideoHint,
            onDismiss = { showAddVideoDialog = false },
            onAdd = { url, label -> viewModel.addMotivationVideo(url, label); showAddVideoDialog = false }
        )
    }
    if (showAddChannelDialog) {
        AddItemDialog(
            title = s.addChannelTitle,
            hint = s.addChannelHint,
            onDismiss = { showAddChannelDialog = false },
            onAdd = { url, label -> viewModel.addMotivationChannel(url, label); showAddChannelDialog = false }
        )
    }
    if (showAddPhraseDialog) {
        AddPhraseDialog(
            onDismiss = { showAddPhraseDialog = false },
            onAdd = { phrase -> viewModel.addMotivationPhrase(phrase); showAddPhraseDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        // Duration card
        item {
            DurationCard(
                duration = motivation.duration,
                onChange = { viewModel.updateMotivationDuration(it) },
                isLocked = uiState.durationLocked,
                lockEnabled = uiState.lockEnabled,
                pendingUnlockChange = uiState.pendingChanges.firstOrNull { it.type == "unlock_duration" },
                onLock = { viewModel.lockDuration() },
                onUnlock = { viewModel.unlockDuration() },
                onCancelUnlock = { change -> viewModel.cancelPendingChange(change.id) }
            )
        }

        // Content lock card
        item {
            ContentLockCard(
                isLocked = uiState.contentLocked,
                lockEnabled = uiState.lockEnabled,
                pendingUnlockChange = uiState.pendingChanges.firstOrNull { it.type == "unlock_content" },
                onLock = { viewModel.lockContent() },
                onUnlock = { viewModel.unlockContent() },
                onCancelUnlock = { change -> viewModel.cancelPendingChange(change.id) }
            )
        }

        // Motivation behavior toggles
        item {
            MotivationBehaviorCard(
                motivationOnBlock = uiState.motivationOnBlock,
                motivationOnSettings = uiState.motivationOnSettings,
                onBlockToggle = { viewModel.setMotivationOnBlock(it) },
                onSettingsToggle = { viewModel.setMotivationOnSettings(it) },
                blockPendingChange = uiState.pendingChanges.firstOrNull { it.type == "disable_motivation_on_block" },
                settingsPendingChange = uiState.pendingChanges.firstOrNull { it.type == "disable_motivation_on_settings" },
                onCancelBlockPending = { change -> viewModel.cancelPendingChange(change.id) },
                onCancelSettingsPending = { change -> viewModel.cancelPendingChange(change.id) }
            )
        }

        // ── Videos ──────────────────────────────────────────────
        item {
            SectionHeader(
                title = s.sectionMotivVideos,
                icon = Icons.Default.VideoLibrary,
                color = sectionColorVideos,
                onAdd = { showAddVideoDialog = true }
            )
        }
        if (motivation.videos.isEmpty()) {
            item { EmptyHint(s.emptyVideosHint) }
        } else {
            val displayedVideos = if (videosExpanded) motivation.videos else motivation.videos.take(COLLAPSE_THRESHOLD)
            itemsIndexed(displayedVideos) { index, item ->
                MediaItemRow(
                    item = item,
                    accentColor = sectionColorVideos,
                    onPlay = { playerUrl = item.url },
                    onDelete = { viewModel.removeMotivationVideo(index) },
                    onCopyLink = { clipboardManager.setText(AnnotatedString(item.url)) },
                    isContentLocked = uiState.contentLocked
                )
            }
            if (motivation.videos.size > COLLAPSE_THRESHOLD) {
                item {
                    ExpandCollapseButton(
                        expanded = videosExpanded,
                        remaining = motivation.videos.size - COLLAPSE_THRESHOLD,
                        color = sectionColorVideos
                    ) { videosExpanded = !videosExpanded }
                }
            }
        }

        // ── Channels ─────────────────────────────────────────────
        item {
            SectionHeader(
                title = s.sectionChannels,
                icon = Icons.Default.Subscriptions,
                color = sectionColorChannels,
                onAdd = { showAddChannelDialog = true }
            )
        }
        if (motivation.channels.isEmpty()) {
            item { EmptyHint(s.emptyChannelsHint) }
        } else {
            val displayedChannels = if (channelsExpanded) motivation.channels else motivation.channels.take(COLLAPSE_THRESHOLD)
            itemsIndexed(displayedChannels) { index, item ->
                MediaItemRow(
                    item = item,
                    accentColor = sectionColorChannels,
                    onPlay = { playerUrl = item.url },
                    onDelete = { viewModel.removeMotivationChannel(index) },
                    onCopyLink = { clipboardManager.setText(AnnotatedString(item.url)) },
                    isContentLocked = uiState.contentLocked
                )
            }
            if (motivation.channels.size > COLLAPSE_THRESHOLD) {
                item {
                    ExpandCollapseButton(
                        expanded = channelsExpanded,
                        remaining = motivation.channels.size - COLLAPSE_THRESHOLD,
                        color = sectionColorChannels
                    ) { channelsExpanded = !channelsExpanded }
                }
            }
        }

        // ── Phrases ───────────────────────────────────────────────
        item {
            SectionHeader(
                title = s.sectionPhrases,
                icon = Icons.Default.FormatQuote,
                color = sectionColorPhrases,
                onAdd = { showAddPhraseDialog = true }
            )
        }
        if (motivation.phrases.isEmpty()) {
            item { EmptyHint(s.emptyPhrasesHint) }
        } else {
            item {
                PhraseGrid(
                    phrases = motivation.phrases,
                    onPlay = { playerUrl = "allphrases://" },
                    onDelete = { index -> viewModel.removeMotivationPhrase(index) },
                    isContentLocked = uiState.contentLocked
                )
            }
        }

        // ── Gallery Videos ────────────────────────────────────────
        item {
            SectionHeader(
                title = s.sectionGallery,
                icon = Icons.Default.PhotoLibrary,
                color = sectionColorGallery,
                onAdd = { galleryLauncher.launch("video/*") }
            )
        }
        if (motivation.galleryVideos.isEmpty()) {
            item { EmptyHint(s.emptyGalleryHint) }
        } else {
            val displayedGallery = if (galleryExpanded) motivation.galleryVideos else motivation.galleryVideos.take(COLLAPSE_THRESHOLD)
            itemsIndexed(displayedGallery) { index, item ->
                MediaItemRow(
                    item = item,
                    accentColor = sectionColorGallery,
                    onPlay = { playerUrl = item.url },
                    onDelete = { viewModel.removeGalleryVideo(index) },
                    isContentLocked = uiState.contentLocked
                )
            }
            if (motivation.galleryVideos.size > COLLAPSE_THRESHOLD) {
                item {
                    ExpandCollapseButton(
                        expanded = galleryExpanded,
                        remaining = motivation.galleryVideos.size - COLLAPSE_THRESHOLD,
                        color = sectionColorGallery
                    ) { galleryExpanded = !galleryExpanded }
                }
            }
        }

        // ── Test button ───────────────────────────────────────────
        val hasAny = motivation.videos.isNotEmpty() || motivation.channels.isNotEmpty() ||
            motivation.phrases.isNotEmpty() || motivation.galleryVideos.isNotEmpty()
        if (hasAny) {
            item {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Button(
                        onClick = {
                            val videoUrls = (motivation.videos + motivation.channels + motivation.galleryVideos).map { it.url }
                            val allUrls = videoUrls + (if (motivation.phrases.isNotEmpty()) listOf("allphrases://") else emptyList())
                            playerUrl = allUrls.random()
                        },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF5C6BC0)
                        )
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(s.testRandom, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

// ============================================================
// ============================================================
// Motivation behavior toggles card
// ============================================================

@Composable
fun MotivationBehaviorCard(
    motivationOnBlock: Boolean,
    motivationOnSettings: Boolean,
    onBlockToggle: (Boolean) -> Unit,
    onSettingsToggle: (Boolean) -> Unit,
    blockPendingChange: PendingChange? = null,
    settingsPendingChange: PendingChange? = null,
    onCancelBlockPending: (PendingChange) -> Unit = {},
    onCancelSettingsPending: (PendingChange) -> Unit = {}
) {
    val s = LocalStrings.current
    Card(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = androidx.compose.ui.Modifier.padding(16.dp)) {
            Text(s.whenToShowTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(androidx.compose.ui.Modifier.height(12.dp))
            Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                    Text(s.motivOnBlockTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(s.motivOnBlockSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    if (blockPendingChange != null) {
                        Text(
                            s.disablingInHours(hoursUntil(blockPendingChange.scheduledFor)),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (blockPendingChange != null) {
                    TextButton(
                        onClick = { onCancelBlockPending(blockPendingChange) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1565C0))
                    ) { Text(s.cancel, style = MaterialTheme.typography.labelMedium) }
                } else {
                    Switch(checked = motivationOnBlock, onCheckedChange = onBlockToggle)
                }
            }
            Divider(modifier = androidx.compose.ui.Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.surfaceVariant)
            Row(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                    Text(s.motivOnSettingsTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(s.motivOnSettingsSubtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    if (settingsPendingChange != null) {
                        Text(
                            s.disablingInHours(hoursUntil(settingsPendingChange.scheduledFor)),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFE65100),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (settingsPendingChange != null) {
                    TextButton(
                        onClick = { onCancelSettingsPending(settingsPendingChange) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF1565C0))
                    ) { Text(s.cancel, style = MaterialTheme.typography.labelMedium) }
                } else {
                    Switch(checked = motivationOnSettings, onCheckedChange = onSettingsToggle)
                }
            }
        }
    }
}

// ============================================================
// Duration card
// ============================================================

@Composable
fun DurationCard(
    duration: Int,
    onChange: (Int) -> Unit,
    isLocked: Boolean = false,
    lockEnabled: Boolean = false,
    pendingUnlockChange: PendingChange? = null,
    onLock: () -> Unit = {},
    onUnlock: () -> Unit = {},
    onCancelUnlock: ((PendingChange) -> Unit)? = null
) {
    var inputText by remember(duration) { mutableStateOf(duration.toString()) }

    fun applyValue() {
        val v = inputText.toIntOrNull()?.coerceIn(0, 300) ?: duration
        inputText = v.toString()
        onChange(v)
    }

    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).background(Color(0xFF5C6BC0).copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Timer, contentDescription = null, tint = Color(0xFF5C6BC0), modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(s.durationTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        s.durationSubtitle(duration),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { if (isLocked) onUnlock() else onLock() }) {
                    Icon(
                        if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (isLocked) s.unlockDuration else s.lockDuration,
                        tint = if (isLocked) Color(0xFF5C6BC0) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Box(
                    modifier = Modifier
                        .background(Color(0xFF5C6BC0), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        "${duration}${s.durationSec}",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }

            if (pendingUnlockChange != null) {
                val hoursLeft = hoursUntil(pendingUnlockChange.scheduledFor)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        s.unlockingInHours(hoursLeft),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100),
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        onClick = { onCancelUnlock?.invoke(pendingUnlockChange) },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF5C6BC0))
                    ) { Text(s.cancel, style = MaterialTheme.typography.labelMedium) }
                }
            } else if (isLocked) {
                Text(
                    if (lockEnabled) s.durationLockedWithLock else s.durationLockedWithoutLock,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { new ->
                            if (new.isEmpty() || (new.all { it.isDigit() } && new.length <= 3)) {
                                inputText = new
                            }
                        },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { applyValue() }),
                        singleLine = true,
                        modifier = Modifier.width(120.dp),
                        suffix = { Text(s.durationSec) },
                        label = { Text(s.durationLabel) }
                    )
                    Button(
                        onClick = { applyValue() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5C6BC0))
                    ) {
                        Text(s.set_)
                    }
                    Text(
                        s.maxDuration,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

// ============================================================
// Content lock card — single lock for all sections
// ============================================================

@Composable
fun ContentLockCard(
    isLocked: Boolean = false,
    lockEnabled: Boolean = false,
    pendingUnlockChange: PendingChange? = null,
    onLock: () -> Unit = {},
    onUnlock: () -> Unit = {},
    onCancelUnlock: ((PendingChange) -> Unit)? = null
) {
    val s = LocalStrings.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.size(40.dp).background(
                        if (isLocked) contentLockColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                        RoundedCornerShape(12.dp)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = null,
                        tint = if (isLocked) contentLockColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        s.contentProtTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (isLocked) s.contentProtLocked else s.contentProtUnlocked,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                IconButton(onClick = { if (isLocked) onUnlock() else onLock() }) {
                    Icon(
                        if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (isLocked) s.unlockContent else s.lockContent,
                        tint = if (isLocked) contentLockColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (pendingUnlockChange != null) {
                val hoursLeft = hoursUntil(pendingUnlockChange.scheduledFor)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        s.unlockingInHours(hoursLeft),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE65100),
                        fontWeight = FontWeight.Medium
                    )
                    TextButton(
                        onClick = { onCancelUnlock?.invoke(pendingUnlockChange) },
                        colors = ButtonDefaults.textButtonColors(contentColor = contentLockColor)
                    ) { Text(s.cancel, style = MaterialTheme.typography.labelMedium) }
                }
            } else if (isLocked) {
                Text(
                    if (lockEnabled) s.contentLockedWithLock else s.contentLockedWithoutLock,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// ============================================================
// Section header — colored icon + title
// ============================================================

@Composable
fun SectionHeader(title: String, icon: ImageVector, color: Color, onAdd: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.copy(alpha = 0.13f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        IconButton(onClick = onAdd) {
            Box(
                modifier = Modifier.size(32.dp).background(color, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ============================================================
// Empty hint text
// ============================================================

@Composable
fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        fontStyle = FontStyle.Italic
    )
}

// ============================================================
// Media item row (video / channel / gallery)
// ============================================================

@Composable
fun MediaItemRow(
    item: MotivationItem,
    accentColor: Color,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onCopyLink: (() -> Unit)? = null,
    isContentLocked: Boolean = false
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accentColor)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (!item.label.isNullOrBlank()) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val displayUrl = when {
                    item.url.startsWith("gallery://") -> item.label ?: item.url.removePrefix("gallery://")
                    else -> item.url.take(45) + if (item.url.length > 45) "…" else ""
                }
                if (item.label.isNullOrBlank() || !item.url.startsWith("gallery://")) {
                    Text(displayUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onCopyLink != null) {
                IconButton(onClick = onCopyLink) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy link", tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
                }
            }
            IconButton(onClick = onPlay) {
                Box(
                    modifier = Modifier.size(32.dp).background(accentColor, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            if (!isContentLocked) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350))
                }
            }
        }
    }
}

// ============================================================
// Phrase item row — colored background
// ============================================================

@Composable
fun PhraseItemRow(phrase: String, color: Color, onPlay: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = color)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.FormatQuote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                phrase,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White.copy(alpha = 0.9f))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun MotivationItemRow(
    item: MotivationItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onCopyLink: (() -> Unit)? = null
) = MediaItemRow(item, sectionColorVideos, onPlay, onDelete, onCopyLink)

// ============================================================
// Expand / collapse button for long sections
// ============================================================

@Composable
fun ExpandCollapseButton(expanded: Boolean, remaining: Int, color: Color, onToggle: () -> Unit) {
    val s = LocalStrings.current
    TextButton(
        onClick = onToggle,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Text(
            if (expanded) s.showLess else s.showMore(remaining),
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

// ============================================================
// Phrase 2-column staggered grid
// ============================================================

@Composable
fun PhraseGrid(phrases: List<String>, onPlay: () -> Unit, onDelete: (index: Int) -> Unit, isContentLocked: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            phrases.filterIndexed { i, _ -> i % 2 == 0 }.forEachIndexed { colIdx, phrase ->
                val globalIdx = colIdx * 2
                PhraseGridCard(
                    phrase = phrase,
                    color = phraseColors[globalIdx % phraseColors.size],
                    onTap = onPlay,
                    onDelete = { onDelete(globalIdx) },
                    isContentLocked = isContentLocked
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            phrases.filterIndexed { i, _ -> i % 2 != 0 }.forEachIndexed { colIdx, phrase ->
                val globalIdx = colIdx * 2 + 1
                PhraseGridCard(
                    phrase = phrase,
                    color = phraseColors[globalIdx % phraseColors.size],
                    onTap = onPlay,
                    onDelete = { onDelete(globalIdx) },
                    isContentLocked = isContentLocked
                )
            }
        }
    }
}

@Composable
fun PhraseGridCard(phrase: String, color: Color, onTap: () -> Unit, onDelete: () -> Unit, isContentLocked: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(color)
            .clickable { onTap() }
    ) {
        Text(
            phrase,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 32.dp, bottom = 12.dp),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 22.sp
        )
        if (!isContentLocked) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.TopEnd).size(32.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Delete phrase",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

// ============================================================
// Add video/channel dialog
// ============================================================

private fun isSupportedPlatformUrl(url: String): Boolean {
    val u = url.trim().lowercase()
    return u.contains("youtube.com") || u.contains("youtu.be") || u.contains("instagram.com")
}

@Composable
fun AddItemDialog(
    title: String,
    hint: String,
    onDismiss: () -> Unit,
    onAdd: (url: String, label: String?) -> Unit
) {
    var url by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf(false) }

    val s = LocalStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; urlError = false },
                    label = { Text(hint) },
                    singleLine = true,
                    isError = urlError,
                    supportingText = if (urlError) {
                        { Text(s.unsupportedPlatform, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(s.labelOptional) },
                    singleLine = true,
                    supportingText = { Text(s.labelAutoFetch, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (url.isNotBlank()) {
                        if (!isSupportedPlatformUrl(url)) {
                            urlError = true
                        } else {
                            onAdd(url.trim(), label.trim().ifBlank { null })
                        }
                    }
                },
                enabled = url.isNotBlank()
            ) { Text(s.add) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}

// ============================================================
// Add phrase dialog
// ============================================================

@Composable
fun AddPhraseDialog(onDismiss: () -> Unit, onAdd: (phrase: String) -> Unit) {
    val s = LocalStrings.current
    var phrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.addPhraseTitle, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = phrase,
                onValueChange = { phrase = it },
                label = { Text(s.addPhraseHint) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )
        },
        confirmButton = {
            Button(onClick = { if (phrase.isNotBlank()) onAdd(phrase.trim()) }, enabled = phrase.isNotBlank()) { Text(s.add) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } }
    )
}

// ============================================================
// Wrapper: resolves URLs before playing
// ============================================================

@Composable
fun MotivationPlayerWithResolution(
    rawUrl: String,
    viewModel: AuthViewModel,
    duration: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val isOnline = remember { isNetworkAvailable(context) }

    DisposableEffect(Unit) {
        BlockingAccessibilityService.motivationActive = true
        // If protection triggered this motivation (not user-initiated), pin the app so
        // HOME/BACK/RECENTS are disabled — the user cannot leave until the timer ends.
        val isProtectionTriggered = BlockingAccessibilityService.lastMotivationUrl.isNotEmpty()
        val activity = context as? Activity
        if (isProtectionTriggered && activity != null) {
            try {
                val dpm = context.getSystemService(DevicePolicyManager::class.java)
                val admin = ComponentName(context, FocusDeviceAdminReceiver::class.java)
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.setLockTaskPackages(admin, arrayOf(context.packageName))
                    activity.startLockTask()
                }
            } catch (_: Exception) {}
        }
        onDispose {
            BlockingAccessibilityService.motivationActive = false
            // Only clear protection state when the full duration has elapsed.
            // If the user exits early (HOME → recents → swipe), keep lastMotivationUrl set
            // so the service guard re-launches motivation on the next window event.
            val elapsed = System.currentTimeMillis() - BlockingAccessibilityService.motivationStartedAt
            val requiredMs = BlockingAccessibilityService.motivationDuration * 1000L
            if (!isProtectionTriggered || elapsed >= requiredMs) {
                BlockingAccessibilityService.lastMotivationUrl = ""
                BlockingAccessibilityService.settingsProtectionArmed = false
            }
            if (isProtectionTriggered && activity != null) {
                try { activity.stopLockTask() } catch (_: Exception) {}
            }
        }
    }

    var currentRawUrl by remember(rawUrl) { mutableStateOf(rawUrl) }

    // phrase://, allphrases://, gallery:// resolve immediately; others need async
    var resolvedUrl by remember(currentRawUrl) {
        val t = currentRawUrl.trim()
        mutableStateOf<String?>(
            when {
                t.startsWith("phrase://") || t.startsWith("allphrases://") || t.startsWith("gallery://") -> t
                else -> null
            }
        )
    }

    val onNextVideo: () -> Unit = {
        val videoUrls = BlockingAccessibilityService.motivationVideos +
                BlockingAccessibilityService.motivationChannels +
                BlockingAccessibilityService.motivationGalleryVideos
        val phraseEntry = if (BlockingAccessibilityService.motivationPhrases.isNotEmpty()) listOf("allphrases://") else emptyList()
        val all = videoUrls + phraseEntry
        if (all.isNotEmpty()) currentRawUrl = all.random()
    }

    LaunchedEffect(currentRawUrl) {
        val t = currentRawUrl.trim()
        if (t.startsWith("phrase://") || t.startsWith("allphrases://") || t.startsWith("gallery://")) return@LaunchedEffect
        resolvedUrl = when {
            toEmbedUrl(t) != t -> t
            t.contains("youtube.com") || t.contains("youtu.be") ->
                withTimeoutOrNull(8_000) { viewModel.resolveChannelUrl(t) } ?: t
            t.contains("instagram.com") ->
                withTimeoutOrNull(10_000) { viewModel.resolveInstagramChannelUrl(t) } ?: t
            else -> t
        }
    }

    val url = resolvedUrl
    when {
        url?.startsWith("allphrases://") == true -> {
            val phrases = uiState.motivation.phrases.ifEmpty {
                BlockingAccessibilityService.motivationPhrases
            }
            AllPhrasesMotivationScreen(phrases = phrases, duration = duration, onDismiss = onDismiss, onNext = onNextVideo)
        }
        url?.startsWith("phrase://") == true -> {
            PhraseMotivationScreen(phrase = url.removePrefix("phrase://"), duration = duration, onDismiss = onDismiss, onNext = onNextVideo)
        }
        url?.startsWith("gallery://") == true -> {
            val filename = url.removePrefix("gallery://")
            val filePath = File(context.getExternalFilesDir(null), "motivation_videos/$filename").absolutePath
            if (File(filePath).exists()) LocalVideoPlayer(filePath, duration, onDismiss)
            else TextOnlyMotivationScreen(duration, onDismiss)
        }
        !isOnline -> {
            val downloadedPath = uiState.downloadedVideos.values.firstOrNull { File(it).exists() }
            val galleryPath = uiState.motivation.galleryVideos.firstOrNull()?.let { item ->
                val fn = item.url.removePrefix("gallery://")
                File(context.getExternalFilesDir(null), "motivation_videos/$fn").absolutePath.takeIf { File(it).exists() }
            }
            val phrases = uiState.motivation.phrases
            when {
                downloadedPath != null -> LocalVideoPlayer(downloadedPath, duration, onDismiss)
                galleryPath != null -> LocalVideoPlayer(galleryPath, duration, onDismiss)
                phrases.isNotEmpty() -> AllPhrasesMotivationScreen(phrases, duration, onDismiss, onNextVideo)
                else -> TextOnlyMotivationScreen(duration, onDismiss)
            }
        }
        url == null -> {
            BackHandler {}
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
        else -> {
            MotivationPlayerDialog(url = url, duration = duration, onDismiss = onDismiss, onNextVideo = onNextVideo)
        }
    }
}

// ============================================================
// All Phrases screen — shows every phrase at once
// ============================================================

@Composable
fun AllPhrasesMotivationScreen(
    phrases: List<String>,
    duration: Int,
    onDismiss: () -> Unit,
    onNext: (() -> Unit)? = null
) {
    var secondsLeft by remember(duration) { mutableStateOf(duration) }
    var canClose by remember(duration) { mutableStateOf(duration <= 0) }

    BackHandler(enabled = !canClose) {}

    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            activity?.window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(Unit) {
        secondsLeft = duration
        canClose = duration <= 0
        while (secondsLeft > 0) { delay(1000); secondsLeft-- }
        secondsLeft = 0; canClose = true
    }

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0D0D2B), Color(0xFF1A0535), Color(0xFF0D1B3B)))
        )
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    LocalStrings.current.rememberYourWhy,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
            }
            itemsIndexed(phrases) { index, phrase ->
                var visible by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { delay(index * 80L); visible = true }

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 }
                ) {
                    val bgColor = phraseColors[index % phraseColors.size]
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = bgColor)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Icon(
                                Icons.Default.FormatQuote,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.4f),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = phrase,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 30.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Timer / action row
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!canClose) {
                Box(
                    modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("$secondsLeft s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                if (onNext != null) {
                    IconButton(onClick = onNext, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(50))) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(50))) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

// ============================================================
// Single phrase screen (used from individual play button)
// ============================================================

@Composable
fun PhraseMotivationScreen(
    phrase: String,
    duration: Int,
    onDismiss: () -> Unit,
    onNext: (() -> Unit)? = null
) {
    var secondsLeft by remember(duration) { mutableStateOf(duration) }
    var canClose by remember(duration) { mutableStateOf(duration <= 0) }

    BackHandler(enabled = !canClose) {}

    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            activity?.window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(phrase) {
        secondsLeft = duration; canClose = duration <= 0
        while (secondsLeft > 0) { delay(1000); secondsLeft-- }
        secondsLeft = 0; canClose = true
    }

    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF0D0D2B), Color(0xFF1A0535)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp).align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!canClose) {
                Box(Modifier.background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("$secondsLeft s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                if (onNext != null) {
                    IconButton(onClick = onNext, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(50))) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(50))) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(0.85f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(Icons.Default.FormatQuote, contentDescription = null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(48.dp))
            Text(
                text = phrase,
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 38.sp
            )
        }
    }
}

// ============================================================
// Full-screen web video player with countdown
// ============================================================

@Composable
fun MotivationPlayerDialog(url: String, duration: Int, onDismiss: () -> Unit, onNextVideo: (() -> Unit)? = null) {
    var secondsLeft by remember(duration) { mutableStateOf(duration) }
    var canClose by remember(duration) { mutableStateOf(duration <= 0) }
    var videoEnded by remember(url) { mutableStateOf(false) }

    BackHandler(enabled = !canClose) {}

    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            activity?.window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(url) {
        secondsLeft = duration; canClose = duration <= 0
        while (secondsLeft > 0 && !videoEnded) { delay(1000); secondsLeft-- }
        secondsLeft = 0; canClose = true
    }
    LaunchedEffect(videoEnded) { if (videoEnded) { secondsLeft = 0; canClose = true } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        MotivationWebView(url = url, onVideoEnded = { videoEnded = true })

        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp).align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!canClose) {
                Box(Modifier.background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("$secondsLeft s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                if (onNextVideo != null) {
                    IconButton(onClick = onNextVideo, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(50))) {
                        Icon(Icons.Default.SkipNext, contentDescription = "Next", tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(50))) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }

        AnimatedVisibility(
            visible = secondsLeft > duration - 3 && duration > 3,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(Modifier.background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 16.dp)) {
                Text(LocalStrings.current.stayStrong, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ============================================================
// WebView
// ============================================================

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MotivationWebView(url: String, onVideoEnded: () -> Unit = {}) {
    val trimmed = url.trim()
    val embedUrl = remember(trimmed) { toEmbedUrl(trimmed) }
    val isSpecificVideo = embedUrl != trimmed

    AndroidView(
        factory = { context ->
            val switched = BooleanArray(1) { false }
            WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    // NEVER_ALLOW, not ALWAYS_ALLOW: every embed host (YouTube, Instagram,
                    // TikTok) is HTTPS, so nothing needs insecure loads — and allowing them
                    // would let a network attacker inject script into the player page. It
                    // also contradicts the "encrypted in transit" answer on Play's Data
                    // Safety form, which should be true by construction, not by luck.
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    setSupportZoom(false)
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 Mobile Safari/537.36"
                }
                webChromeClient = WebChromeClient()
                val webViewRef = this
                addJavascriptInterface(VideoJsBridge(onVideoEnded) { pickedUrl ->
                    val pickedEmbed = toEmbedUrl(pickedUrl)
                    if (pickedEmbed != pickedUrl && !switched[0]) {
                        switched[0] = true
                        webViewRef.post {
                            webViewRef.loadDataWithBaseURL(
                                "https://www.instagram.com/",
                                buildEmbedHtml(pickedEmbed),
                                "text/html", "utf-8", null
                            )
                        }
                    }
                }, "NativeApp")
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val navUrl = request.url.toString()
                        val scheme = request.url.scheme ?: return false
                        if (scheme != "http" && scheme != "https") return true
                        if (!isSpecificVideo && !switched[0]) {
                            val navEmbed = toEmbedUrl(navUrl)
                            if (navEmbed != navUrl && (navUrl.contains("instagram.com") || navUrl.contains("tiktok.com"))) {
                                switched[0] = true
                                val baseUrl = if (navUrl.contains("tiktok.com")) "https://www.tiktok.com/" else "https://www.instagram.com/"
                                view.post { view.loadDataWithBaseURL(baseUrl, buildEmbedHtml(navEmbed), "text/html", "utf-8", null) }
                                return true
                            }
                        }
                        return false
                    }

                    override fun onPageFinished(view: WebView, pageUrl: String) {
                        view.evaluateJavascript("""
                            (function() {
                                function addEndedListeners() {
                                    document.querySelectorAll('video').forEach(function(v) {
                                        if (!v._nativeEnded) {
                                            v._nativeEnded = true;
                                            v.addEventListener('ended', function() { try { window.NativeApp.videoEnded(); } catch(e) {} });
                                        }
                                    });
                                }
                                addEndedListeners();
                                var mo = new MutationObserver(function() { addEndedListeners(); });
                                mo.observe(document.body || document.documentElement, {childList:true, subtree:true});
                                if (!window._ytEndedListener) {
                                    window._ytEndedListener = true;
                                    window.addEventListener('message', function(e) {
                                        try {
                                            var d = typeof e.data === 'string' ? JSON.parse(e.data) : e.data;
                                            if (d && d.event === 'onStateChange' && d.info === 0) window.NativeApp.videoEnded();
                                        } catch(ex) {}
                                    });
                                }
                            })();
                        """.trimIndent(), null)

                        if (!isSpecificVideo && !switched[0]) {
                            val js: String? = when {
                                pageUrl.contains("instagram.com") -> """
                                    (function() {
                                        var played = false;
                                        var allLinks = [];
                                        function addLink(url) {
                                            if (/\/(reel|p|tv)\/[A-Za-z0-9_\-]{8,}/.test(url) && allLinks.indexOf(url) === -1) allLinks.push(url);
                                        }
                                        function tryPlay() {
                                            if (played || allLinks.length === 0) return false;
                                            played = true;
                                            var url = allLinks[Math.floor(Math.random()*allLinks.length)];
                                            try { window.NativeApp.pickVideo(url); } catch(e) { window.location.href = url; }
                                            return true;
                                        }
                                        function dismissOverlays() {
                                            document.querySelectorAll('[role="dialog"],[role="presentation"]').forEach(function(el){try{el.parentNode.removeChild(el);}catch(e){}});
                                            try{document.body.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(e){}
                                        }
                                        function collectLinks() {
                                            Array.from(document.querySelectorAll('a[href]')).forEach(function(a){addLink(a.href);});
                                            var text = Array.from(document.querySelectorAll('script:not([src])')).map(function(s){return s.textContent;}).join('');
                                            (text.match(/"(?:shortcode|code)"\s*:\s*"([A-Za-z0-9_\-]{8,15})"/g)||[]).forEach(function(m){var c=m.match(/"([A-Za-z0-9_\-]{8,15})"\s*$/);if(c)addLink('https://www.instagram.com/reel/'+c[1]+'/');});
                                        }
                                        function attempt() {
                                            if (played) return;
                                            dismissOverlays(); collectLinks(); tryPlay();
                                        }
                                        var obs = new MutationObserver(function(){if(!played){dismissOverlays();collectLinks();}});
                                        obs.observe(document.body||document.documentElement,{childList:true,subtree:true});
                                        [0,200,600,1200,2000,3000,5000,8000].forEach(function(t){setTimeout(attempt,t);});
                                        setTimeout(function(){obs.disconnect();},10000);
                                    })();
                                """.trimIndent()
                                pageUrl.contains("tiktok.com") -> """
                                    (function() {
                                        function pick() {
                                            var links = Array.from(document.querySelectorAll('a[href]')).map(function(a){return a.href;}).filter(function(h){return /tiktok\.com\/@[^\/]+\/video\/\d+/.test(h);});
                                            var unique = links.filter(function(v,i,a){return a.indexOf(v)===i;});
                                            if (unique.length > 0) { window.location.href = unique[Math.floor(Math.random()*unique.length)]; return true; }
                                            return false;
                                        }
                                        if (!pick()) { var obs = new MutationObserver(function(){if(pick())obs.disconnect();}); obs.observe(document.body||document.documentElement,{childList:true,subtree:true}); setTimeout(pick,1500); setTimeout(pick,3000); setTimeout(pick,6000); }
                                    })();
                                """.trimIndent()
                                else -> null
                            }
                            js?.let { view.evaluateJavascript(it, null) }
                        }
                    }
                }
                when {
                    isSpecificVideo && (embedUrl.contains("tiktok.com") || embedUrl.contains("instagram.com")) -> {
                        val baseUrl = if (embedUrl.contains("tiktok.com")) "https://www.tiktok.com/" else "https://www.instagram.com/"
                        loadDataWithBaseURL(baseUrl, buildEmbedHtml(embedUrl), "text/html", "utf-8", null)
                    }
                    else -> loadUrl(embedUrl)
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private class VideoJsBridge(
    private val onEnded: () -> Unit,
    private val onPickedVideo: (String) -> Unit = {}
) {
    @android.webkit.JavascriptInterface
    fun videoEnded() { android.os.Handler(android.os.Looper.getMainLooper()).post(onEnded) }

    @android.webkit.JavascriptInterface
    fun pickVideo(url: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { onPickedVideo(url) }
    }
}

private fun buildEmbedHtml(embedUrl: String): String = """
<!DOCTYPE html><html>
<head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
<style>*{margin:0;padding:0;box-sizing:border-box}body{background:#000;overflow:hidden;width:100vw;height:100vh}#f{position:fixed;top:0;left:0;width:100%;height:100%;border:none}</style>
</head><body>
<iframe id="f" src="$embedUrl" frameborder="0" allow="autoplay;fullscreen;encrypted-media;picture-in-picture" allowfullscreen></iframe>
<script>
var frame=document.getElementById('f');
frame.addEventListener('load',function(){
  try{
    var doc=frame.contentDocument||frame.contentWindow.document;
    function fixVideo(){
      var vids=Array.from(doc.querySelectorAll('video'));if(!vids.length)return false;
      var main=vids.find(function(v){return !v.paused&&!v.ended;})||vids[0];
      main.style.cssText='position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;object-fit:contain!important;z-index:2147483647!important;background:#000!important';
      vids.forEach(function(v){if(v!==main)v.style.display='none';});main.play().catch(function(){});
      if(!main._nativeEnded){main._nativeEnded=true;main.addEventListener('ended',function(){try{window.NativeApp.videoEnded();}catch(e){}});}
      var s=doc.createElement('style');s.textContent='body,html{margin:0;padding:0;overflow:hidden;background:#000}';if(doc.head)doc.head.appendChild(s);return true;
    }
    if(!fixVideo()){var obs=new MutationObserver(function(){if(fixVideo())obs.disconnect();});obs.observe(doc.documentElement,{childList:true,subtree:true});}
    setTimeout(fixVideo,1000);setTimeout(fixVideo,3000);
  }catch(e){}
});
</script></body></html>
""".trimIndent()

// ============================================================
// Local video player
// ============================================================

@Composable
fun LocalVideoPlayer(filePath: String, duration: Int, onDismiss: () -> Unit) {
    var secondsLeft by remember(duration) { mutableStateOf(duration) }
    var canClose by remember(duration) { mutableStateOf(duration <= 0) }
    var videoEnded by remember { mutableStateOf(false) }

    BackHandler(enabled = !canClose) {}

    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            activity?.window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(Unit) { while (secondsLeft > 0 && !videoEnded) { delay(1000); secondsLeft-- }; secondsLeft = 0; canClose = true }
    LaunchedEffect(videoEnded) { if (videoEnded) { secondsLeft = 0; canClose = true } }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                android.widget.VideoView(ctx).apply {
                    setVideoURI(Uri.fromFile(File(filePath)))
                    setOnPreparedListener { mp -> mp.isLooping = false; mp.start() }
                    setOnCompletionListener { videoEnded = true }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp).align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End
        ) {
            if (!canClose) {
                Box(Modifier.background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("$secondsLeft s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(50))) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}

// ============================================================
// Text-only fallback
// ============================================================

@Composable
fun TextOnlyMotivationScreen(duration: Int, onDismiss: () -> Unit) {
    var secondsLeft by remember(duration) { mutableStateOf(duration) }
    var canClose by remember(duration) { mutableStateOf(duration <= 0) }

    BackHandler(enabled = !canClose) {}

    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            WindowInsetsControllerCompat(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            activity?.window?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, true)
                WindowInsetsControllerCompat(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(Unit) { while (secondsLeft > 0) { delay(1000); secondsLeft-- }; canClose = true }

    Box(
        modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0D0D2B), Color(0xFF1A0535)))),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp).align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End
        ) {
            if (!canClose) {
                Box(Modifier.background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text("$secondsLeft s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            } else {
                IconButton(onClick = onDismiss, modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(50))) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
        Box(Modifier.background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Text(LocalStrings.current.stayStrong, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// ============================================================
// URL → embed URL conversion
// ============================================================

fun toEmbedUrl(url: String): String {
    val u = url.trim()
    extractYoutubeId(u)?.let { return "https://www.youtube.com/embed/$it?autoplay=1&enablejsapi=1" }
    extractInstagramCode(u)?.let { return "https://www.instagram.com/p/$it/embed/" }
    extractTiktokId(u)?.let { return "https://www.tiktok.com/embed/v2/$it" }
    return u
}

private fun extractYoutubeId(url: String): String? {
    Regex("""youtu\.be/([A-Za-z0-9_\-]{11})""").find(url)?.groupValues?.get(1)?.let { return it }
    Regex("""[?&]v=([A-Za-z0-9_\-]{11})""").find(url)?.groupValues?.get(1)?.let { return it }
    Regex("""youtube\.com/shorts/([A-Za-z0-9_\-]{11})""").find(url)?.groupValues?.get(1)?.let { return it }
    return null
}

private fun extractInstagramCode(url: String): String? =
    Regex("""instagram\.com/(?:reel|p|tv)/([A-Za-z0-9_\-]+)""").find(url)?.groupValues?.get(1)

private fun extractTiktokId(url: String): String? =
    Regex("""tiktok\.com/@[^/]+/video/(\d+)""").find(url)?.groupValues?.get(1)
