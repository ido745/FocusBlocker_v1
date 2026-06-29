package com.focusapp.blocker.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Bump this number whenever the legal text changes — users will be required to re-agree.
const val TERMS_VERSION = 2

@Composable
fun TermsScreen(onAccepted: () -> Unit) {
    val activity = LocalContext.current as? Activity
    var agreed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // Back press exits the app entirely — cannot bypass by navigating back.
    BackHandler { activity?.finishAffinity() }

    Column(Modifier.fillMaxSize()) {
        // ── Header ───────────────────────────────────────────────────────────
        Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Text(
                    "Before You Continue",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Please read and accept the Terms of Service and Privacy Policy to use Focus Blocker.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        // ── Scrollable legal text ─────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ════════════════════════════════════════════════════════════════
            // TERMS OF SERVICE
            // ════════════════════════════════════════════════════════════════
            item {
                Text(
                    "TERMS OF SERVICE",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Last updated: May 2025",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(12.dp))
            }

            item {
                LegalSection("1. ACCEPTANCE OF TERMS",
                    "By downloading, installing, or using Focus Blocker (\"App\"), you (\"User\") agree to be " +
                    "legally bound by these Terms of Service (\"Terms\") in their entirety. If you do not " +
                    "agree to every provision of these Terms, you must immediately uninstall the App and " +
                    "cease all use. Your installation or continued use of the App constitutes your full and " +
                    "unconditional acceptance of these Terms and any future revisions thereof."
                )
            }

            item {
                LegalSection("2. DESCRIPTION OF THE APP",
                    "Focus Blocker is a productivity and digital-wellbeing application designed to restrict " +
                    "access to specific applications, websites, and content on your Android device. To " +
                    "provide its core functionality, the App requires access to a number of sensitive Android " +
                    "system-level permissions, each described in detail in Section 3 below. You understand " +
                    "that these permissions are necessary for the App to function and that granting them is " +
                    "your voluntary and informed choice."
                )
            }

            item {
                Text(
                    "3. SENSITIVE PERMISSIONS — YOUR EXPLICIT ACKNOWLEDGMENT",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "THE APP REQUIRES THE FOLLOWING SYSTEM PERMISSIONS. BY USING THE APP, YOU " +
                    "EXPLICITLY ACKNOWLEDGE THAT YOU HAVE READ, UNDERSTOOD, AND VOLUNTARILY CONSENTED " +
                    "TO EACH OF THE FOLLOWING:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
            }

            item {
                LegalSection("3.1  Accessibility Service",
                    "The App uses Android's Accessibility Service, which grants it the ability to observe " +
                    "and interact with all content displayed on your screen across all applications — " +
                    "including but not limited to text, images, interface elements, and navigation actions " +
                    "performed in any app. This is required for the App's blocking functionality. YOU " +
                    "ACKNOWLEDGE THAT THIS IS AMONG THE MOST POWERFUL PERMISSIONS AVAILABLE ON THE " +
                    "ANDROID PLATFORM AND THAT YOU GRANT IT KNOWINGLY AND VOLUNTARILY."
                )
            }

            item {
                LegalSection("3.2  Device Administrator",
                    "The App may request Device Administrator privileges, which grant it the ability to " +
                    "enforce device-level policies, resist its own uninstallation, and perform other " +
                    "administrative actions. YOU ACKNOWLEDGE THAT GRANTING DEVICE ADMINISTRATOR STATUS " +
                    "GIVES THE APP ELEVATED CONTROL OVER YOUR DEVICE and that you do so voluntarily."
                )
            }

            item {
                LegalSection("3.3  Display Over Other Apps",
                    "The App may display content (blocking screens, motivational content) over all other " +
                    "running applications at any time."
                )
            }

            item {
                LegalSection("3.4  Automatic Start on Boot",
                    "The App starts automatically whenever your device is powered on or restarted, " +
                    "in order to maintain continuous blocking without interruption."
                )
            }

            item {
                LegalSection("3.5  Persistent Background Operation",
                    "The App runs a permanent foreground service and requests exemption from Android's " +
                    "battery optimizations to remain active at all times, including when the screen is off."
                )
            }

            item {
                LegalSection("3.6  Exact Alarms & Scheduling",
                    "The App uses exact system alarms to apply scheduled changes to your configuration " +
                    "at precise times."
                )
            }

            item {
                LegalSection("3.7  Notifications",
                    "The App may post system notifications to inform you of its status and activity."
                )
            }

            item {
                LegalSection("3.8  Internet Access",
                    "The App communicates with a remote server to retrieve configuration data required " +
                    "for certain features."
                )
            }

            item {
                LegalSection("3.9  Access to All Installed Applications",
                    "The App requires access to the full list of applications installed on your device " +
                    "in order to block or allow specific apps."
                )
            }

            item {
                LegalSection("3.10  Kill Background Processes",
                    "The App may terminate background processes of other applications as part of its " +
                    "blocking enforcement mechanism."
                )
            }

            item {
                LegalSection("4. ALL PERMISSIONS ARE VOLUNTARY",
                    "No permission is granted automatically or covertly. Each permission is requested " +
                    "through Android's standard system dialogs and must be individually authorized by you. " +
                    "YOU BEAR FULL AND SOLE RESPONSIBILITY for all consequences arising from granting any " +
                    "or all of the above permissions to the App."
                )
            }

            item {
                LegalSection("5. DISCLAIMER OF WARRANTIES",
                    "THE APP IS PROVIDED \"AS IS\" AND \"AS AVAILABLE,\" WITHOUT ANY WARRANTY WHATSOEVER, " +
                    "EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO WARRANTIES OF MERCHANTABILITY, " +
                    "FITNESS FOR A PARTICULAR PURPOSE, ACCURACY, COMPLETENESS, RELIABILITY, SECURITY, " +
                    "OR NON-INFRINGEMENT. THE DEVELOPER DOES NOT WARRANT THAT: (a) THE APP WILL MEET " +
                    "YOUR REQUIREMENTS; (b) THE APP WILL OPERATE WITHOUT INTERRUPTION OR ERROR; " +
                    "(c) BUGS OR DEFECTS WILL BE CORRECTED; (d) THE APP WILL SUCCESSFULLY BLOCK ALL " +
                    "INTENDED CONTENT IN ALL CIRCUMSTANCES OR AT ALL TIMES; OR (e) THE APP WILL NOT " +
                    "AFFECT, INTERFERE WITH, OR CAUSE UNEXPECTED BEHAVIOR IN OTHER APPLICATIONS OR " +
                    "DEVICE FUNCTIONS."
                )
            }

            item {
                Text(
                    "6. LIMITATION OF LIABILITY",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "6.1  The Developer shall not be liable for any direct, indirect, incidental, special, " +
                    "consequential, punitive, or exemplary damages of any kind, including but not limited to: " +
                    "loss of data, loss of revenue, loss of profits, loss of goodwill, loss of access to " +
                    "applications or content, device malfunction, unauthorized access to device content, " +
                    "failure to block intended content, or any other tangible or intangible loss — " +
                    "regardless of whether the Developer was advised of the possibility of such damages.\n\n" +
                    "6.2  BUGS AND UNEXPECTED BEHAVIOR: You expressly acknowledge that bugs, glitches, " +
                    "crashes, system conflicts, and unexpected behavior are inherent in all software. Any " +
                    "such occurrence — including but not limited to the App blocking content it should not " +
                    "have blocked, failing to block content it should have blocked, interfering with other " +
                    "applications, causing device instability, or any other deviation from intended behavior " +
                    "— is YOUR SOLE RISK. The Developer assumes no liability for any such event.\n\n" +
                    "6.3  The Developer's total cumulative liability to you for any claim arising out of " +
                    "or relating to the App, regardless of the form of action, shall not exceed the total " +
                    "amount you have paid for the App, or zero (₪0 / \$0) if the App was obtained free " +
                    "of charge.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
            }

            item {
                LegalSection("7. USER ASSUMES ALL RISK",
                    "YOU EXPRESSLY AGREE THAT YOUR USE OF THE APP AND YOUR GRANTING OF PERMISSIONS TO " +
                    "THE APP IS ENTIRELY AT YOUR OWN RISK. You have been fully and clearly informed of " +
                    "the nature, scope, and potential consequences of the permissions the App requires. " +
                    "You voluntarily choose to install and use the App with complete knowledge of these facts. " +
                    "You waive any and all claims against the Developer arising from your voluntary use of " +
                    "the App and your voluntary grant of permissions."
                )
            }

            item {
                LegalSection("8. INDEMNIFICATION",
                    "You agree to indemnify, defend, and hold harmless the Developer from and against any " +
                    "and all claims, liabilities, damages, losses, and expenses (including reasonable legal " +
                    "fees) arising out of or relating to: (a) your use of the App; (b) any permission you " +
                    "granted to the App; (c) your violation of these Terms; or (d) your violation of any " +
                    "applicable law or the rights of any third party."
                )
            }

            item {
                Text(
                    "9. IN-APP DONATIONS AND PAYMENTS",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "9.1  VOLUNTARY DONATIONS ONLY: The App offers optional in-app donations exclusively " +
                    "as a voluntary means of supporting the Developer. Donations confer no rights, " +
                    "benefits, features, or entitlements of any kind. No refund, service, or obligation " +
                    "of any nature is owed to you in exchange for a donation.\n\n" +
                    "9.2  GOOGLE PLAY BILLING: All payments are processed exclusively by Google Play's " +
                    "In-App Billing system, operated and secured by Google LLC under Google's own terms " +
                    "and privacy policy. The Developer does not receive, store, or process any payment " +
                    "card details, banking information, or other financial data.\n\n" +
                    "9.3  ALL TRANSACTIONS ARE FINAL: Donations are non-refundable. Once a payment is " +
                    "processed by Google Play, the Developer has no ability to reverse, refund, or " +
                    "otherwise modify the transaction. Any refund requests, disputes, chargebacks, or " +
                    "billing complaints must be directed solely to Google Play Support.\n\n" +
                    "9.4  DEVELOPER NOT LIABLE FOR PAYMENT ISSUES: The Developer shall bear no " +
                    "responsibility for any payment processing failure, declined transaction, duplicate " +
                    "charge, billing error, unauthorized charge, or any other issue relating to Google " +
                    "Play Billing. The Developer's total liability in connection with any donation or " +
                    "attempted donation is zero (₪0 / \$0).\n\n" +
                    "9.5  NO CLAIM AGAINST DEVELOPER: You expressly waive any and all claims against " +
                    "the Developer arising from or relating to any payment, donation, or billing " +
                    "transaction, including any failure of the payment system, loss of funds, or " +
                    "dissatisfaction with the donation experience.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
            }

            item {
                LegalSection("10. MODIFICATIONS TO TERMS",
                    "The Developer reserves the right to modify these Terms at any time. Material changes " +
                    "will require your renewed in-app acceptance before you may continue using the App. " +
                    "If you do not accept the revised Terms, you must uninstall the App."
                )
            }

            item {
                LegalSection("11. GOVERNING LAW",
                    "These Terms shall be governed by and construed in accordance with the laws of the " +
                    "State of Israel, without regard to conflict-of-law principles. Any dispute arising " +
                    "under or in connection with these Terms shall be subject to the exclusive jurisdiction " +
                    "of the competent courts of Israel."
                )
            }

            item {
                LegalSection("12. SEVERABILITY",
                    "If any provision of these Terms is held to be invalid, illegal, or unenforceable, " +
                    "the remaining provisions shall remain in full force and effect."
                )
            }

            item {
                LegalSection("13. ENTIRE AGREEMENT",
                    "These Terms, together with the Privacy Policy below, constitute the entire and " +
                    "exclusive agreement between you and the Developer regarding the App and supersede " +
                    "all prior understandings, representations, or agreements."
                )
            }

            // ════════════════════════════════════════════════════════════════
            // PRIVACY POLICY
            // ════════════════════════════════════════════════════════════════
            item {
                Divider(Modifier.padding(vertical = 20.dp))
                Text(
                    "PRIVACY POLICY",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Last updated: May 2025",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(12.dp))
            }

            item {
                LegalSection("1. Overview",
                    "This Privacy Policy describes how Focus Blocker handles information in connection " +
                    "with your use of the App. We are committed to full transparency about our data practices."
                )
            }

            item {
                LegalSection("2. Data Stored on Your Device",
                    "All App configuration — including blocked apps, blocked websites, blocked keywords, " +
                    "whitelisted apps, scheduling preferences, and feature settings — is stored exclusively " +
                    "on your device using Android's DataStore system. This data does not leave your device " +
                    "except as described in Section 3."
                )
            }

            item {
                LegalSection("3. Server Communication",
                    "The App communicates with a remote server solely to retrieve configuration data " +
                    "for certain features (such as content filtering lists). No personally identifiable " +
                    "information, usage patterns, behavioral data, or analytics are transmitted."
                )
            }

            item {
                LegalSection("4. What We Do NOT Collect",
                    "We do not collect, store, or process:\n" +
                    "• Your name, email address, or any personal identifier\n" +
                    "• Device identifiers or advertising IDs\n" +
                    "• Location data\n" +
                    "• Screen content — data observed via the Accessibility Service is processed locally " +
                    "in real time and is never stored or transmitted\n" +
                    "• Financial information — payments are handled entirely by Google Play"
                )
            }

            item {
                LegalSection("5. How Permissions Are Used for Privacy",
                    "Accessibility Service: Used locally in real time. No screen content is ever recorded, " +
                    "stored, or transmitted.\n\n" +
                    "Device Administrator: Used solely to resist unauthorized uninstallation. No data is " +
                    "transmitted.\n\n" +
                    "All other permissions: Used solely for the functionality described in the Terms of " +
                    "Service. None are used for data collection."
                )
            }

            item {
                LegalSection("6. Third-Party Services",
                    "The App integrates Google Play Billing for optional in-app donations. Your payment " +
                    "details are handled exclusively by Google under Google's own Privacy Policy. The " +
                    "Developer receives no access to your payment information."
                )
            }

            item {
                LegalSection("7. Data Retention & Deletion",
                    "All data stored by the App resides locally on your device and is automatically " +
                    "deleted when you uninstall the App or clear its data through Android Settings."
                )
            }

            item {
                LegalSection("8. Children's Privacy",
                    "The App is not directed to children under 13. We do not knowingly collect personal " +
                    "information from children."
                )
            }

            item {
                LegalSection("9. Changes to This Policy",
                    "We may update this Privacy Policy from time to time. Material changes will be " +
                    "communicated within the App and may require your renewed acceptance."
                )
            }

            item {
                LegalSection("10. Contact",
                    "For privacy-related questions or concerns, please use the contact form available " +
                    "within the App."
                )
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Agreement checkbox and button ─────────────────────────────────────
        Surface(tonalElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 24.dp)) {
                AgreementRow(
                    checked = agreed,
                    onCheckedChange = { agreed = it },
                    label = "I have read and agree to the Terms of Service and Privacy Policy"
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onAccepted,
                    enabled = agreed,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Agree & Continue")
                }
            }
        }
    }
}

@Composable
private fun LegalSection(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(body, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun AgreementRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth()
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp)
        )
    }
}
