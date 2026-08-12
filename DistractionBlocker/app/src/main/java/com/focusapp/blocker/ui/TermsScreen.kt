package com.focusapp.blocker.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Bump this number whenever the legal text changes — users will be required to re-agree.
const val TERMS_VERSION = 3

private const val TERMS_URL = "https://ido745.github.io/FocusBlocker_v1/terms.html"
private const val PRIVACY_URL = "https://ido745.github.io/FocusBlocker_v1/"

/**
 * Consent gate shown on first launch.
 *
 * Previously this was ~480 lines of legal text in a scrolling list. It has been replaced by a
 * plain-language summary plus links to the hosted documents, which is both easier to read and
 * closer to what people actually consent to.
 *
 * Note this screen is NOT the accessibility prominent disclosure — Google Play requires that
 * to be separate and adjacent to the permission request, which is what
 * [AccessibilityDisclosureDialog] does.
 */
@Composable
fun TermsScreen(onAccepted: () -> Unit) {
    val activity = LocalContext.current as? Activity
    val context = LocalContext.current
    val s = LocalStrings.current
    var agreed by remember { mutableStateOf(false) }
    val accent = Color(0xFF3F51B5)

    // Back press exits the app entirely — the gate cannot be bypassed by navigating back.
    BackHandler { activity?.finishAffinity() }

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                // targetSdk 35 forces edge-to-edge, so the app draws under the system bars.
                // Without these the header slides under the status bar and — the reason this
                // came up — the Continue button sits beneath the gesture/home bar.
                .statusBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Shield, null, tint = accent, modifier = Modifier.size(32.dp))
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    s.legalTitle,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    s.legalIntro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            SummaryPoint(Icons.Default.Shield, Color(0xFF3F51B5), s.legalSummaryPermissions)
            SummaryPoint(Icons.Default.Lock, Color(0xFF2E7D32), s.legalSummaryPrivacy)
            SummaryPoint(Icons.Default.VolunteerActivism, Color(0xFFAD1457), s.legalSummaryDonations)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                DocumentLink(s.legalReadTerms) { open(TERMS_URL) }
                DocumentLink(s.legalReadPrivacy) { open(PRIVACY_URL) }
            }
        }

        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Column(
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                    Text(
                        s.legalAgree,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
                Button(
                    onClick = onAccepted,
                    enabled = agreed,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text(s.legalContinue, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SummaryPoint(icon: ImageVector, tint: Color, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(tint.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(19.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun DocumentLink(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(18.dp))
    }
}
