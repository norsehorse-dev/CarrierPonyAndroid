// LegalScreens.kt
// CarrierPony Android
//
// The Privacy Policy and Terms of Service pages, reachable from Settings.
// Both share one document layout. The document bodies are intentionally kept
// as Kotlin constants rather than string resources: legal text must stay
// exactly as reviewed, is not line-by-line translated, and keeping it out of
// strings.xml avoids MissingTranslation lint noise across the nine locales.
// Screen titles and the Settings entries are localized as usual.

package com.carrierpony.app.ui

import com.carrierpony.app.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
fun PrivacyPolicyScreen(onDone: () -> Unit) {
    LegalScreen(
        title = stringResource(R.string.settings_privacy_policy),
        updated = LegalContent.updated,
        sections = LegalContent.privacySections,
        onDone = onDone
    )
}

@Composable
fun TermsScreen(onDone: () -> Unit) {
    LegalScreen(
        title = stringResource(R.string.settings_terms),
        updated = LegalContent.updated,
        sections = LegalContent.termsSections,
        onDone = onDone
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalScreen(
    title: String,
    updated: String,
    sections: List<Pair<String, String>>,
    onDone: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = updated,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            for ((heading, body) in sections) {
                Spacer(Modifier.height(18.dp))
                Text(heading, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** The legal documents. English is authoritative; see the file header for why
 *  these are constants and not resources. */
object LegalContent {

    const val updated = "Last updated: July 18, 2026"

    val privacySections: List<Pair<String, String>> = listOf(
        "Overview" to
            "CarrierPony is a private messenger and file-transfer app built to know as " +
            "little about you as possible. There are no accounts: you never give us a " +
            "name, email address, or phone number. Your messages and files are end to " +
            "end encrypted with OpenPGP, and only you and the people you pair with can " +
            "read them.",

        "What we do not collect" to
            "We do not collect your name, email address, or phone number. We do not " +
            "upload your contact list. We cannot read your messages or files — they are " +
            "encrypted on your device before they are sent. The app contains no " +
            "advertising, no analytics SDKs, and no trackers.",

        "What the relay processes" to
            "To deliver messages between devices, the app talks to the CarrierPony " +
            "relay (api.carrierpony.com). The relay only ever sees sealed, encrypted " +
            "envelopes: it learns no names, no message contents, and no plaintext. " +
            "Envelopes are held only as long as needed to deliver them. The app also " +
            "sends the relay a random per-install device identifier (32 hex characters, " +
            "generated on your device) so envelopes can be routed to you. This " +
            "identifier is not derived from your identity key or from anything about " +
            "you personally.",

        "Push notifications" to
            "If you allow notifications, the app registers a push token with Firebase " +
            "Cloud Messaging (a Google service) so your device can be woken when a new " +
            "envelope arrives. Push messages carry no message content — decryption " +
            "happens only on your device. Declining notifications does not stop " +
            "delivery; the app still picks up messages when you open it.",

        "Your keys and your data" to
            "Your private key is generated on your device and never leaves it, except " +
            "in a backup that you create yourself, which is encrypted with a passphrase " +
            "only you know. Messages, files, and contacts are stored locally on your " +
            "device. Resetting the app (Settings → Reset App) or uninstalling it erases " +
            "them.",

        "Abuse reports" to
            "If you report a contact, the report is assembled on your device and " +
            "carries only what you choose to share: the reason, an optional " +
            "description, and — only with your explicit consent — the text of recent " +
            "messages you received from that contact. File and image bytes are never " +
            "uploaded with a report.",

        "Third parties" to
            "The only third-party service the app uses is Google Firebase Cloud " +
            "Messaging, for push delivery as described above. We do not sell, rent, or " +
            "share any data for advertising or marketing purposes.",

        "Children" to
            "CarrierPony is not directed at children under 13 (or the higher minimum " +
            "age that applies where you live), and we do not knowingly process their " +
            "data.",

        "Changes to this policy" to
            "If this policy changes, the updated version will ship with the app and be " +
            "noted in the release notes. Continued use of the app after a change means " +
            "the updated policy applies.",

        "Contact" to
            "Questions about privacy? Contact us at support@carrierpony.com."
    )

    val termsSections: List<Pair<String, String>> = listOf(
        "Acceptance of these terms" to
            "By installing or using CarrierPony you agree to these Terms of Service. " +
            "If you do not agree, please do not use the app.",

        "The service" to
            "CarrierPony provides end-to-end encrypted messaging and file transfer " +
            "between paired devices, delivered through a relay server that forwards " +
            "sealed envelopes. The app requires no account and is provided free of " +
            "charge.",

        "Your responsibilities" to
            "Your identity lives in a private key on your device. You are responsible " +
            "for keeping your device, your app lock, and your backup passphrase safe. " +
            "Encrypted backups can only be opened with the passphrase you chose; if " +
            "you lose both the device and the passphrase, your identity and messages " +
            "cannot be recovered by anyone, including us.",

        "Acceptable use" to
            "You agree not to use CarrierPony to send spam, to harass or threaten " +
            "others, or to store or share unlawful content, including child sexual " +
            "abuse material. Recipients can report abusive contacts; we review reports " +
            "and may block a device's access to the relay when the reported conduct " +
            "violates these terms or applicable law.",

        "Encryption" to
            "Messages and files are end to end encrypted. We cannot read, restore, or " +
            "hand over their contents. You are responsible for complying with the laws " +
            "that apply to your use of encryption software in your country.",

        "Open source" to
            "Portions of CarrierPony are built on open-source software, including " +
            "PGPonyCore. Those components remain under their own licenses.",

        "No warranty" to
            "CarrierPony is provided \"as is\" and \"as available\", without warranties " +
            "of any kind, express or implied. We do not guarantee that the service " +
            "will be uninterrupted, timely, or error-free.",

        "Limitation of liability" to
            "To the maximum extent permitted by law, the developer of CarrierPony is " +
            "not liable for any indirect, incidental, special, consequential, or " +
            "exemplary damages arising from your use of the app, including loss of " +
            "data or messages.",

        "Termination" to
            "You can stop using CarrierPony at any time; Settings → Reset App erases " +
            "everything on your device. We may suspend relay access for devices that " +
            "violate these terms.",

        "Changes to these terms" to
            "We may update these terms from time to time. The current version always " +
            "ships with the app. Continued use after a change means the updated terms " +
            "apply.",

        "Contact" to
            "Questions about these terms? Contact us at support@carrierpony.com."
    )
}
