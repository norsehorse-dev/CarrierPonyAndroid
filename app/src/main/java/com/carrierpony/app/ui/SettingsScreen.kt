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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.AccountCircle
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
import com.carrierpony.app.AppConfig
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import com.carrierpony.app.ui.theme.CPTheme
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.vector.ImageVector

private enum class SettingsCategory { ACCOUNT, MORE, SUPPORT, RESET }

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
    onLearn: (() -> Unit)? = null,    // security education — tester-feedback phase
    onAddAccount: () -> Unit = {}
) {
    val context = LocalContext.current
    val profileName by app.profileName.collectAsState()
    val accountUnread by app.accountUnread.collectAsState()
    val nearbyNodes by app.lanDiscovery.nearby.collectAsState()
    val lanReachable by app.lanDiscovery.reachable.collectAsState()
    var lanDirectOn by remember { mutableStateOf(AppConfig.lanDirectEnabled(context)) }
    var lanSkipRelayOn by remember { mutableStateOf(AppConfig.lanDirectSkipRelay(context)) }
    val wanConnected by app.wanConnectedCount.collectAsState()
    var wanDirectOn by remember { mutableStateOf(AppConfig.wanDirectEnabled(context)) }

    var showingProfile by remember { mutableStateOf(false) }
    var showingLanguage by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmResetOnboarding by remember { mutableStateOf(false) }
    var confirmResetApp by remember { mutableStateOf(false) }
    var appLockEnabled by remember { mutableStateOf(app.appLock.appLockEnabled) }
    var showingRelay by remember { mutableStateOf(false) }
    var openCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var accountsList by remember { mutableStateOf(app.accounts()) }
    var pendingRemove by remember { mutableStateOf<AppModel.AccountSummary?>(null) }

    if (showingRelay) {
        RelayScreen(app = app, onBack = { showingRelay = false })
        return
    }

    val ponyApps = listOf(
        PonyApp("PGPony", stringResource(R.string.settings_sub_pgpony), "https://pgpony.app"),
        PonyApp("AgePony", stringResource(R.string.settings_sub_agepony), "https://agepony.com"),
        PonyApp("QuorumPony", stringResource(R.string.settings_sub_quorumpony), "https://quorumpony.com"),
        PonyApp("BurnPony", stringResource(R.string.settings_sub_burnpony), "https://burnpony.app"),
        PonyApp("RelayPony", stringResource(R.string.settings_sub_relaypony), "https://relaypony.app"),
        PonyApp("ScrubPony", stringResource(R.string.settings_sub_scrubpony), "https://scrubpony.app"),
        PonyApp("VaultPony", stringResource(R.string.settings_sub_vaultpony), "https://vaultpony.app"),
        PonyApp("PassPony", stringResource(R.string.settings_sub_passpony), "https://passpony.app")
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

    androidx.activity.compose.BackHandler(enabled = openCategory != null) { openCategory = null }

    val categoryLangSub = languages.firstOrNull { it.first == app.appLanguage }?.second ?: "English"

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (openCategory) {
                            null -> stringResource(R.string.settings_title)
                            SettingsCategory.ACCOUNT -> stringResource(R.string.settings_account)
                            SettingsCategory.MORE -> stringResource(R.string.settings_more_from)
                            SettingsCategory.SUPPORT -> stringResource(R.string.settings_support)
                            SettingsCategory.RESET -> stringResource(R.string.settings_reset)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (openCategory != null) openCategory = null else onDone() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_done))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            when (openCategory) {
                null -> {
                    Spacer(Modifier.padding(top = 8.dp))
                    CategoryCard(Icons.Default.Person, stringResource(R.string.settings_account), stringResource(R.string.settings_account_sub)) { accountsList = app.accounts(); openCategory = SettingsCategory.ACCOUNT }
                    CategoryCard(Icons.Default.Cloud, stringResource(R.string.settings_relay), stringResource(R.string.settings_relay_sub)) { showingRelay = true }
                    NearbyCard(
                        nearby = nearbyNodes.size,
                        reachable = lanReachable.size,
                        directOn = lanDirectOn,
                        skipRelay = lanSkipRelayOn,
                        onToggle = { on -> lanDirectOn = on; app.setLanDirectEnabled(on) },
                        onSkipToggle = { on -> lanSkipRelayOn = on; app.setLanDirectSkipRelay(on) },
                    )
                    WanDirectCard(
                        directOn = wanDirectOn,
                        connectedCount = wanConnected,
                        onToggle = { on -> wanDirectOn = on; app.setWanDirectEnabled(on) },
                    )
                    CategoryCard(Icons.Default.Language, stringResource(R.string.settings_language), categoryLangSub) { showingLanguage = true }
                    CategoryCard(Icons.Default.Apps, stringResource(R.string.settings_more_from), stringResource(R.string.settings_more_sub)) { openCategory = SettingsCategory.MORE }
                    CategoryCard(Icons.Default.Info, stringResource(R.string.settings_support), stringResource(R.string.settings_support_sub)) { openCategory = SettingsCategory.SUPPORT }
                    CategoryCard(Icons.Default.Warning, stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_sub), destructive = true) { openCategory = SettingsCategory.RESET }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
                SettingsCategory.ACCOUNT -> {
                    val activeHex = app.activeFingerprintHex
                    for (acct in accountsList) {
                        val hex = acct.fingerprint.hex
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = hex != activeHex) { app.switchAccount(hex); onDone() }
                                .padding(horizontal = 20.dp, vertical = 11.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    acct.name ?: UiFormat.shortFingerprint(acct.fingerprint),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    UiFormat.shortFingerprint(acct.fingerprint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (hex == activeHex) {
                                Text(
                                    stringResource(R.string.accounts_active),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CPTheme.accent
                                )
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Default.Check, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(18.dp))
                            } else {
                                if ((accountUnread[hex] ?: 0) > 0) {
                                    Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(CPTheme.accent))
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (accountsList.size > 1) {
                                    IconButton(onClick = { pendingRemove = acct }) {
                                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.accounts_remove), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(start = 20.dp))
                    }
                    SettingsHeader("")
                    SettingsRow(stringResource(R.string.accounts_add)) { onAddAccount() }
                    SettingsHeader("")
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
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
                SettingsCategory.MORE -> {
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
                    Column(
                        Modifier.fillMaxWidth().clickable { open("https://pony.norsehor.se") }.padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(stringResource(R.string.settings_pony_family), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.settings_pony_family_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(Modifier.padding(start = 20.dp))
                    SettingsHeader(stringResource(R.string.settings_open_source))
                    SettingsRow(stringResource(R.string.settings_source_repo)) { open("https://github.com/norsehorse-dev/CarrierPonyAndroid") }
                    SettingsRow(stringResource(R.string.settings_source_core)) { open("https://github.com/norsehorse-dev/CarrierPonyAndroid/tree/HEAD/carrierponycore") }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
                SettingsCategory.SUPPORT -> {
                    if (onLearn != null) SettingsRow(stringResource(R.string.edu_title), onClick = onLearn)
                    SettingsRow(stringResource(R.string.settings_send_feedback)) { sendFeedback() }
                    if (onPrivacy != null) SettingsRow(stringResource(R.string.settings_privacy_policy), onClick = onPrivacy)
                    if (onTerms != null) SettingsRow(stringResource(R.string.settings_terms), onClick = onTerms)
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
                SettingsCategory.RESET -> {
                    SettingsRow(stringResource(R.string.settings_reset_onboarding)) { confirmResetOnboarding = true }
                    SettingsRow(stringResource(R.string.settings_reset_app), destructive = true) { confirmResetApp = true }
                    SettingsHeader("")
                    SettingsRow(stringResource(R.string.settings_sign_out), destructive = true) { confirmSignOut = true }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
            }
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

    pendingRemove?.let { acct ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.accounts_remove_q)) },
            text = { Text(stringResource(R.string.accounts_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    val hex = acct.fingerprint.hex
                    val wasActive = hex == app.activeFingerprintHex
                    pendingRemove = null
                    app.removeAccount(hex)
                    if (wasActive) onDone() else accountsList = app.accounts()
                }) { Text(stringResource(R.string.accounts_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text(stringResource(R.string.common_cancel)) } }
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
private fun WanDirectCard(directOn: Boolean, connectedCount: Int, onToggle: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.settings_wan_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = directOn, onCheckedChange = onToggle)
            }
            if (directOn && connectedCount > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.settings_wan_connected, connectedCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                stringResource(R.string.settings_wan_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun NearbyCard(
    nearby: Int,
    reachable: Int,
    directOn: Boolean,
    skipRelay: Boolean,
    onToggle: (Boolean) -> Unit,
    onSkipToggle: (Boolean) -> Unit,
) {
    // LAN-direct surface. Read-only nearby count from discovery (M2), plus the
    // opt-in "direct on this network" toggle and, when on, how many known contacts
    // have been identified on this Wi-Fi (M3a). No messages move yet.
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.settings_lan_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        stringResource(R.string.settings_lan_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    nearby.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.settings_lan_direct),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = directOn, onCheckedChange = onToggle)
            }
            if (directOn) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.settings_lan_reachable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        reachable.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.settings_lan_skip_relay),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = skipRelay, onCheckedChange = onSkipToggle)
                }
                Text(
                    stringResource(R.string.settings_lan_skip_relay_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stringResource(R.string.settings_lan_direct_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

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


@Composable
private fun CategoryCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (destructive) MaterialTheme.colorScheme.error else CPTheme.accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RelayScreen(app: AppModel, onBack: () -> Unit, onboarding: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var urlText by remember {
        mutableStateOf(if (AppConfig.usingCustomRelay(context)) AppConfig.relayBaseURL(context) else "")
    }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<RelayTestResult?>(null) }
    var confirmSave by remember { mutableStateOf(false) }
    var savedNotice by remember { mutableStateOf(false) }

    val valid = AppConfig.isValidRelayURL(urlText)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_relay)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_done))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {

            SettingsHeader(stringResource(R.string.relay_current))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            ) {
                Text(
                    AppConfig.relayBaseURL(context),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (AppConfig.usingCustomRelay(context)) stringResource(R.string.relay_custom)
                    else stringResource(R.string.relay_default),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (AppConfig.usingCustomRelay(context)) {
                SettingsHeader(stringResource(R.string.relay_push_header))
                var gatewayOn by remember { mutableStateOf(AppConfig.gatewayPushEnabled(context)) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                ) {
                    Text(
                        stringResource(R.string.relay_push_toggle),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = gatewayOn,
                        onCheckedChange = { gatewayOn = it; app.setGatewayPush(it) }
                    )
                }
                Text(
                    stringResource(R.string.relay_push_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            SettingsHeader(stringResource(R.string.relay_custom_section))
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it; testResult = null },
                singleLine = true,
                placeholder = { Text("https://relay.example.com") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !testing && valid) {
                        scope.launch {
                            testing = true
                            testResult = probeRelay(urlText)
                            testing = false
                        }
                    }
                    .padding(horizontal = 20.dp, vertical = 13.dp)
            ) {
                Text(
                    stringResource(R.string.relay_test),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (valid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (testing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }

            testResult?.let { r ->
                val msg = when (r) {
                    RelayTestResult.Ok -> stringResource(R.string.relay_reachable)
                    RelayTestResult.BadUrl -> stringResource(R.string.relay_bad_url)
                    is RelayTestResult.Failed -> stringResource(R.string.relay_unreachable, r.reason)
                }
                val color = if (r is RelayTestResult.Ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = color,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            Text(
                stringResource(if (onboarding) R.string.relay_onb_footer else R.string.relay_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )

            SettingsHeader("")
            SettingsRow(stringResource(R.string.relay_use)) { if (valid) confirmSave = true }
            if (AppConfig.usingCustomRelay(context)) {
                SettingsRow(stringResource(R.string.relay_reset), destructive = true) {
                    AppConfig.setRelayBaseURL(context, null)
                    urlText = ""
                    testResult = null
                    if (!onboarding) savedNotice = true
                }
            }

            SettingsHeader(stringResource(R.string.relay_selfhost_header))
            SettingsRow(stringResource(R.string.relay_selfhost_setup)) {
                openInAppBrowser(context, "https://carrierpony.com/self-host")
            }
            SettingsRow(stringResource(R.string.relay_selfhost_source)) {
                openInAppBrowser(context, "https://github.com/norsehorse-dev/CarrierPony-Relay")
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }

    if (confirmSave) {
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text(stringResource(R.string.relay_use_q)) },
            text = { Text(stringResource(if (onboarding) R.string.relay_onb_switch_body else R.string.relay_switch_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSave = false
                    if (AppConfig.setRelayBaseURL(context, urlText)) { if (onboarding) onBack() else savedNotice = true }
                }) { Text(stringResource(R.string.relay_use)) }
            },
            dismissButton = { TextButton(onClick = { confirmSave = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (savedNotice) {
        AlertDialog(
            onDismissRequest = { savedNotice = false; onBack() },
            title = { Text(stringResource(R.string.relay_saved)) },
            text = { Text(stringResource(R.string.relay_saved_body)) },
            confirmButton = { TextButton(onClick = { savedNotice = false; onBack() }) { Text(stringResource(R.string.common_done)) } }
        )
    }
}

private sealed interface RelayTestResult {
    data object Ok : RelayTestResult
    data object BadUrl : RelayTestResult
    data class Failed(val reason: String) : RelayTestResult
}

// A non-destructive reachability + protocol probe: POST /v1/challenge with a
// well-formed dummy fingerprint. A 200 carrying a nonce means the relay is up
// and speaks the CarrierPony protocol. The nonce is one-time and unused.
private suspend fun probeRelay(url: String): RelayTestResult = withContext(Dispatchers.IO) {
    val base = AppConfig.normalizedRelayURL(url)
    if (!AppConfig.isValidRelayURL(base)) return@withContext RelayTestResult.BadUrl
    val fpr = "0".repeat(40)
    try {
        val conn = URL("$base/v1/challenge").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.outputStream.use { it.write(JSONObject().put("fpr", fpr).toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val result = if (code == 200) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val hasNonce = try { JSONObject(body).has("nonce") } catch (e: Exception) { false }
            if (hasNonce) RelayTestResult.Ok else RelayTestResult.Failed("HTTP 200")
        } else {
            RelayTestResult.Failed("HTTP $code")
        }
        conn.disconnect()
        result
    } catch (e: Exception) {
        RelayTestResult.Failed(e.message ?: "unreachable")
    }
}
