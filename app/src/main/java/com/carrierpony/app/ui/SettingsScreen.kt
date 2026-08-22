// SettingsScreen.kt
// CarrierPony Android
//
// The settings screen, ported from iOS Core/App/SettingsView.swift: account
// actions (edit profile, pair; back up and import light up with the key phase),
// the app lock toggle, language, links to the other Pony apps and PGPonyCore,
// the reset controls, and sign out. Confirmation copy matches iOS exactly.
//
// The language picker persists the preference; applying it as the app locale
// arrives with the localization phase.

package com.carrierpony.app.ui

import com.carrierpony.app.R
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.carrierpony.app.AppModel
import com.carrierpony.app.ui.theme.CPTheme

private data class PonyApp(val name: String, val subtitle: String, val url: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    app: AppModel,
    biometricsAvailable: Boolean,
    onPair: () -> Unit,
    onDone: () -> Unit,
    onBackup: (() -> Unit)? = null,
    onRestore: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,   // raw key import — key-import phase
    onPrivacy: (() -> Unit)? = null,  // legal pages — tester-feedback phase
    onTerms: (() -> Unit)? = null,
    onLearn: (() -> Unit)? = null     // security education — tester-feedback phase
) {
    val context = LocalContext.current
    val profileName by app.profileName.collectAsState()

    var showingProfile by remember { mutableStateOf(false) }
    var showingLanguage by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmResetOnboarding by remember { mutableStateOf(false) }
    var confirmResetApp by remember { mutableStateOf(false) }
    var appLockEnabled by remember { mutableStateOf(app.appLock.appLockEnabled) }

    val ponyApps = listOf(
        PonyApp("PGPony", stringResource(R.string.settings_sub_pgpony), "https://pgpony.app"),
        PonyApp("AgePony", stringResource(R.string.settings_sub_agepony), "https://agepony.com"),
        PonyApp("QuorumPony", stringResource(R.string.settings_sub_quorumpony), "https://quorumpony.com"),
        PonyApp("RelayPony", stringResource(R.string.settings_sub_relaypony), "https://relaypony.app")
    )

    fun open(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            // No browser is not our problem to solve here.
        }
    }

    val feedbackSubject = stringResource(R.string.feedback_subject)
    fun sendFeedback() {
        // Pre-fill the support address and enough environment detail to make
        // the report actionable; the user sees and controls everything sent.
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        val signature = "\n\n—\nCarrierPony Android ${version ?: "?"}\n" +
            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "Android ${android.os.Build.VERSION.RELEASE}"
        val intent = Intent(Intent.ACTION_SENDTO, "mailto:".toUri()).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf("support@carrierpony.com"))
            putExtra(Intent.EXTRA_SUBJECT, feedbackSubject)
            putExtra(Intent.EXTRA_TEXT, signature)
        }
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // No email app installed; nothing sensible to do here.
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_done))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {

            SettingsHeader(stringResource(R.string.settings_account))
            SettingsRow(stringResource(R.string.settings_edit_profile)) { showingProfile = true }
            SettingsRow(stringResource(R.string.settings_pair_with), onClick = onPair)
            if (onBackup != null) SettingsRow(stringResource(R.string.settings_backup), onClick = onBackup)
            if (onRestore != null) SettingsRow(stringResource(R.string.settings_restore), onClick = onRestore)
            if (onImport != null) SettingsRow(stringResource(R.string.settings_import_key), onClick = onImport)

            if (biometricsAvailable) {
                SettingsHeader(stringResource(R.string.settings_security))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.settings_app_lock), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = appLockEnabled,
                        onCheckedChange = {
                            appLockEnabled = it
                            app.setAppLockEnabled(it)
                        }
                    )
                }
                HorizontalDivider(Modifier.padding(start = 20.dp))
            }

            SettingsHeader(stringResource(R.string.settings_language))
            SettingsRow(languages.firstOrNull { it.first == app.appLanguage }?.second ?: "English") {
                showingLanguage = true
            }

            SettingsHeader(stringResource(R.string.settings_more_apps))
            for (item in ponyApps) {
                Column(
                    Modifier.fillMaxWidth().clickable { open(item.url) }.padding(horizontal = 20.dp, vertical = 10.dp)
                ) {
                    Text(item.name, style = MaterialTheme.typography.bodyLarge)
                    Text(item.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(Modifier.padding(start = 20.dp))
            }

            SettingsHeader(stringResource(R.string.settings_open_source))
            SettingsRow(stringResource(R.string.settings_pgponycore)) { open("https://github.com/norsehorse-dev/PGPonyCore") }

            SettingsHeader(stringResource(R.string.settings_support))
            if (onLearn != null) SettingsRow(stringResource(R.string.edu_title), onClick = onLearn)
            SettingsRow(stringResource(R.string.settings_send_feedback)) { sendFeedback() }

            if (onPrivacy != null || onTerms != null) {
                SettingsHeader(stringResource(R.string.settings_legal))
                if (onPrivacy != null) SettingsRow(stringResource(R.string.settings_privacy_policy), onClick = onPrivacy)
                if (onTerms != null) SettingsRow(stringResource(R.string.settings_terms), onClick = onTerms)
            }

            SettingsHeader(stringResource(R.string.settings_reset))
            SettingsRow(stringResource(R.string.settings_reset_onboarding)) { confirmResetOnboarding = true }
            SettingsRow(stringResource(R.string.settings_reset_app), destructive = true) { confirmResetApp = true }

            SettingsHeader("")
            SettingsRow(stringResource(R.string.settings_sign_out), destructive = true) { confirmSignOut = true }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    if (showingProfile) {
        var draft by remember { mutableStateOf(profileName ?: "") }
        AlertDialog(
            onDismissRequest = { showingProfile = false },
            title = { Text(stringResource(R.string.settings_edit_profile)) },
            text = {
                Column {
                    Text(stringResource(R.string.settings_profile_body))
                    Spacer(Modifier.padding(top = 12.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.settings_your_name)) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    app.setProfileName(draft)
                    showingProfile = false
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showingProfile = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showingLanguage) {
        AlertDialog(
            onDismissRequest = { showingLanguage = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    for ((code, name) in languages) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    app.appLanguage = code
                                    showingLanguage = false
                                }
                                .padding(vertical = 10.dp)
                        ) {
                            Text(name, modifier = Modifier.weight(1f))
                            if (app.appLanguage == code) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showingLanguage = false }) { Text(stringResource(R.string.common_done)) }
            }
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.settings_sign_out_q)) },
            text = { Text(stringResource(R.string.settings_sign_out_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    onDone()
                    app.signOut()
                }) { Text(stringResource(R.string.settings_sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (confirmResetOnboarding) {
        AlertDialog(
            onDismissRequest = { confirmResetOnboarding = false },
            title = { Text(stringResource(R.string.settings_reset_onboarding_q)) },
            text = { Text(stringResource(R.string.settings_reset_onboarding_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmResetOnboarding = false
                    app.resetOnboarding()
                }) { Text(stringResource(R.string.settings_reset_onboarding)) }
            },
            dismissButton = { TextButton(onClick = { confirmResetOnboarding = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (confirmResetApp) {
        AlertDialog(
            onDismissRequest = { confirmResetApp = false },
            title = { Text(stringResource(R.string.settings_reset_app_q)) },
            text = { Text(stringResource(R.string.settings_reset_app_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmResetApp = false
                    onDone()
                    app.resetApp()
                }) { Text(stringResource(R.string.settings_erase), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmResetApp = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

private val languages = listOf(
    "en" to "English",
    "es" to "Español",
    "de" to "Deutsch",
    "fr" to "Français",
    "pt" to "Português",
    "it" to "Italiano",
    "zh" to "中文",
    "ja" to "日本語",
    "ru" to "Русский"
)

@Composable
private fun SettingsHeader(text: String) {
    if (text.isNotEmpty()) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 6.dp)
        )
    } else {
        Spacer(Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun SettingsRow(title: String, destructive: Boolean = false, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.width(8.dp))
    }
    HorizontalDivider(Modifier.padding(start = 20.dp))
}
