package com.focusapp.blocker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Prominent disclosure for the Accessibility Service.
 *
 * Google Play's User Data policy requires that an app using the Accessibility API for
 * non-accessibility purposes shows a disclosure that:
 *   • appears inside the app, in normal use, BEFORE the permission is requested,
 *   • states in plain language what is accessed and why,
 *   • requires an affirmative action to proceed, and
 *   • is NOT buried in a terms of service or privacy policy.
 *
 * The old flow failed all four: the permission card only gave setup instructions ("tap the
 * button, toggle it on") and the actual explanation lived in section 3.1 of a long legal
 * document. This screen is what the Play declaration's demo video is meant to show.
 */
@Composable
fun AccessibilityDisclosureDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    val s = LocalStrings.current
    val accent = Color(0xFF3F51B5)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Visibility, null, tint = accent, modifier = Modifier.size(26.dp))
            }
        },
        title = {
            Text(
                s.a11yDisclosureTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(s.a11yDisclosureWhat, style = MaterialTheme.typography.bodyMedium)
                Text(s.a11yDisclosureHow, style = MaterialTheme.typography.bodyMedium)

                // The privacy guarantee is set apart deliberately: it is the claim users most
                // need to see, and the one a reviewer looks for in the disclosure video.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Lock, null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                    )
                    Text(
                        s.a11yDisclosurePrivacy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onAccept,
                colors = ButtonDefaults.buttonColors(containerColor = accent)
            ) { Text(s.a11yDisclosureAccept) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(s.cancel) }
        }
    )
}
