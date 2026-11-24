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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Surface
import androidx.compose.material.icons.filled.NetworkCheck
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.Notifications
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.vector.ImageVector

/** The settings sections that open as their own page. Held by the caller, so
 *  the open section is still open after a screen launched from it is closed. */
enum class SettingsCategory { ACCOUNT, MORE, SUPPORT, ABOUT, RESET }

private data class PonyApp(val name: String, val subtitle: String, val url: String, val icon: ImageVector)

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
    onAddAccount: () -> Unit = {},
    category: SettingsCategory? = null,
    onCategory: (SettingsCategory?) -> Unit = {}
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
    var nostrOn by remember { mutableStateOf(AppConfig.nostrEnabled(context)) }

    var showingProfile by remember { mutableStateOf(false) }
    var showingLanguage by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var confirmResetOnboarding by remember { mutableStateOf(false) }
    var confirmResetApp by remember { mutableStateOf(false) }
    var appLockEnabled by remember { mutableStateOf(app.appLock.appLockEnabled) }
    var showingRelay by remember { mutableStateOf(false) }
    var showingNostrRelays by remember { mutableStateOf(false) }
    var showingNotifications by remember { mutableStateOf(false) }
    val openCategory = category
    var accountsList by remember { mutableStateOf(app.accounts()) }
    var pendingRemove by remember { mutableStateOf<AppModel.AccountSummary?>(null) }

    // Back from any page opened inside Settings goes up one level, not out.
    if (showingRelay) {
        androidx.activity.compose.BackHandler { showingRelay = false }
        RelayScreen(app = app, onBack = { showingRelay = false })
        return
    }

    if (showingNostrRelays) {
        androidx.activity.compose.BackHandler { showingNostrRelays = false }
        NostrRelaysScreen(app = app, onBack = { showingNostrRelays = false })
        return
    }

    if (showingNotifications) {
        androidx.activity.compose.BackHandler { showingNotifications = false }
        NotificationsScreen(onBack = { showingNotifications = false })
        return
    }

    val ponyApps = listOf(
        PonyApp("PGPony", stringResource(R.string.settings_sub_pgpony), "https://pgpony.app", Icons.Default.VpnKey),
        PonyApp("AgePony", stringResource(R.string.settings_sub_agepony), "https://agepony.com", Icons.Default.Lock),
        PonyApp("QuorumPony", stringResource(R.string.settings_sub_quorumpony), "https://quorumpony.com", Icons.Default.CallSplit),
        PonyApp("BurnPony", stringResource(R.string.settings_sub_burnpony), "https://burnpony.app", Icons.Default.LocalFireDepartment),
        PonyApp("RelayPony", stringResource(R.string.settings_sub_relaypony), "https://relaypony.app", Icons.Default.SwapHoriz),
        PonyApp("ScrubPony", stringResource(R.string.settings_sub_scrubpony), "https://scrubpony.app", Icons.Default.CleaningServices),
        PonyApp("VaultPony", stringResource(R.string.settings_sub_vaultpony), "https://vaultpony.app", Icons.Default.Inventory2),
        PonyApp("PassPony", stringResource(R.string.settings_sub_passpony), "https://passpony.app", Icons.Default.Password),
        PonyApp("TapPony", stringResource(R.string.settings_sub_tappony), "https://tappony.app", Icons.Default.Nfc),
        PonyApp("HashPony", stringResource(R.string.settings_sub_hashpony), "https://hashpony.app", Icons.Default.Tag),
        PonyApp("StegPony", stringResource(R.string.settings_sub_stegpony), "https://stegpony.app", Icons.Default.Image)
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

    androidx.activity.compose.BackHandler(enabled = openCategory != null) { onCategory(null) }

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
                            SettingsCategory.ABOUT -> stringResource(R.string.settings_about)
                            SettingsCategory.RESET -> stringResource(R.string.settings_reset)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (openCategory != null) onCategory(null) else onDone() }) {
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
                    CategoryCard(Icons.Default.Person, stringResource(R.string.settings_account), stringResource(R.string.settings_account_sub)) { accountsList = app.accounts(); onCategory(SettingsCategory.ACCOUNT) }
                    CategoryCard(Icons.Default.Cloud, stringResource(R.string.settings_relay), stringResource(R.string.settings_relay_sub)) { showingRelay = true }
                    CategoryCard(Icons.Default.Notifications, stringResource(R.string.settings_notifications), notificationsSummary()) { showingNotifications = true }
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
                    NostrCard(
                        nostrOn = nostrOn,
                        relayCount = AppConfig.nostrRelays(context).size,
                        onToggle = { on -> nostrOn = on; app.setNostrEnabled(on) },
                        onEditRelays = { showingNostrRelays = true },
                    )
                    if (app.smsSupport.available) {
                        SmsCard(app)
                    }
                    SealedResyncCard(app)
                    CategoryCard(Icons.Default.Language, stringResource(R.string.settings_language), categoryLangSub) { showingLanguage = true }
                    CategoryCard(Icons.Default.Apps, stringResource(R.string.settings_more_from), stringResource(R.string.settings_more_sub)) { onCategory(SettingsCategory.MORE) }
                    CategoryCard(Icons.Default.Info, stringResource(R.string.settings_support), stringResource(R.string.settings_support_sub)) { onCategory(SettingsCategory.SUPPORT) }
                    CategoryCard(Icons.Outlined.Info, stringResource(R.string.settings_about), stringResource(R.string.settings_about_sub)) { onCategory(SettingsCategory.ABOUT) }
                    CategoryCard(Icons.Default.Warning, stringResource(R.string.settings_reset), stringResource(R.string.settings_reset_sub), destructive = true) { onCategory(SettingsCategory.RESET) }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
                SettingsCategory.ACCOUNT -> {
                    val activeHex = app.activeFingerprintHex
                    SettingsPanel(stringResource(R.string.accounts_title)) {
                        for (acct in accountsList) {
                            val hex = acct.fingerprint.hex
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = hex != activeHex) { app.switchAccount(hex); onDone() }
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.AccountCircle,
                                    contentDescription = null,
                                    tint = if (hex == activeHex) CPTheme.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        acct.name ?: UiFormat.shortFingerprint(acct.fingerprint),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        UiFormat.shortFingerprint(acct.fingerprint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (hex == activeHex) {
                                    Text(
                                        stringResource(R.string.accounts_active),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CPTheme.accent
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Icon(Icons.Default.Check, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(20.dp))
                                } else {
                                    if ((accountUnread[hex] ?: 0) > 0) {
                                        Box(Modifier.size(10.dp).clip(androidx.compose.foundation.shape.CircleShape).background(CPTheme.accent))
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    if (accountsList.size > 1) {
                                        IconButton(onClick = { pendingRemove = acct }) {
                                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.accounts_remove), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                            PanelDivider()
                        }
                        PanelRow(PanelItem(Icons.Default.Add, stringResource(R.string.accounts_add), end = RowEnd.NONE) { onAddAccount() })
                    }
                    SettingsPanel(stringResource(R.string.settings_this_account)) {
                        PanelRows(listOfNotNull(
                            PanelItem(Icons.Default.Edit, stringResource(R.string.settings_edit_profile), stringResource(R.string.settings_edit_profile_hint), end = RowEnd.NONE) { showingProfile = true },
                            PanelItem(Icons.Default.PersonAdd, stringResource(R.string.settings_pair_with), stringResource(R.string.settings_pair_with_hint), onClick = onPair),
                            onBackup?.let { PanelItem(Icons.Default.Backup, stringResource(R.string.settings_backup), stringResource(R.string.settings_backup_hint), onClick = it) },
                            onRestore?.let { PanelItem(Icons.Default.Restore, stringResource(R.string.settings_restore), stringResource(R.string.settings_restore_hint), onClick = it) },
                            onImport?.let { PanelItem(Icons.Default.Key, stringResource(R.string.settings_import_key), stringResource(R.string.settings_import_key_hint), onClick = it) }
                        ))
                    }
                    if (biometricsAvailable) {
                        SettingsPanel(stringResource(R.string.settings_security)) {
                            // The whole row is the switch, not only the thumb.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = appLockEnabled,
                                        role = Role.Switch,
                                        onValueChange = {
                                            appLockEnabled = it
                                            app.setAppLockEnabled(it)
                                        }
                                    )
                                    .heightIn(min = 56.dp)
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_app_lock), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(R.string.settings_app_lock_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(checked = appLockEnabled, onCheckedChange = null)
                            }
                        }
                    }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
                SettingsCategory.MORE -> {
                    SettingsPanel(stringResource(R.string.settings_more_apps)) {
                        PanelRows(
                            ponyApps.map { item ->
                                PanelItem(item.icon, item.name, item.subtitle, end = RowEnd.EXTERNAL) { open(item.url) }
                            } + PanelItem(
                                Icons.Default.Apps,
                                stringResource(R.string.settings_pony_family),
                                stringResource(R.string.settings_pony_family_sub),
                                end = RowEnd.EXTERNAL
                            ) { open("https://pony.norsehor.se") }
                        )
                    }
                    SettingsPanel(stringResource(R.string.settings_open_source)) {
                        PanelRows(listOf(
                            PanelItem(Icons.Default.Code, stringResource(R.string.settings_source_repo), end = RowEnd.EXTERNAL) {
                                open("https://github.com/norsehorse-dev/CarrierPonyAndroid")
                            },
                            PanelItem(Icons.Default.Code, stringResource(R.string.settings_source_core), end = RowEnd.EXTERNAL) {
                                open("https://github.com/norsehorse-dev/CarrierPonyAndroid/tree/HEAD/carrierponycore")
                            }
                        ))
                    }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
                SettingsCategory.SUPPORT -> {
                    SettingsPanel {
                        PanelRows(listOfNotNull(
                            onLearn?.let { PanelItem(Icons.Default.School, stringResource(R.string.edu_title), onClick = it) },
                            PanelItem(Icons.Default.MailOutline, stringResource(R.string.settings_send_feedback), stringResource(R.string.settings_send_feedback_hint), end = RowEnd.EXTERNAL) { sendFeedback() }
                        ))
                    }
                    if (onPrivacy != null || onTerms != null) {
                        SettingsPanel(stringResource(R.string.settings_legal)) {
                            PanelRows(listOfNotNull(
                                onPrivacy?.let { PanelItem(Icons.Default.Policy, stringResource(R.string.settings_privacy_policy), onClick = it) },
                                onTerms?.let { PanelItem(Icons.Default.Description, stringResource(R.string.settings_terms), onClick = it) }
                            ))
                        }
                    }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
                SettingsCategory.ABOUT -> {
                    // Version and build come from the installed package, never a
                    // hard-coded string. Tapping a row copies the line for a report.
                    val info = remember {
                        try { context.packageManager.getPackageInfo(context.packageName, 0) } catch (e: Exception) { null }
                    }
                    val versionLine = stringResource(
                        R.string.about_version_value,
                        info?.versionName ?: "?",
                        info?.let { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(it) } ?: 0L
                    )
                    val buildLine = stringResource(
                        if (context.resources.getBoolean(R.bool.build_is_foss)) R.string.about_build_foss
                        else R.string.about_build_play
                    )
                    val copied = stringResource(R.string.common_copied)
                    val copyLine = "CarrierPony Android $versionLine, $buildLine"
                    SettingsPanel {
                        PanelRows(listOf(
                            PanelItem(Icons.Default.Tag, stringResource(R.string.about_version), versionLine, end = RowEnd.NONE) {
                                copyMessageText(context, copyLine, copied)
                            },
                            PanelItem(Icons.Default.Inventory2, stringResource(R.string.about_build), buildLine, end = RowEnd.NONE) {
                                copyMessageText(context, copyLine, copied)
                            }
                        ))
                    }
                    Spacer(Modifier.padding(bottom = 24.dp))
                }
                SettingsCategory.RESET -> {
                    SettingsPanel {
                        PanelRows(listOf(
                            PanelItem(Icons.Default.Refresh, stringResource(R.string.settings_reset_onboarding), stringResource(R.string.settings_reset_onboarding_hint), end = RowEnd.NONE) { confirmResetOnboarding = true }
                        ))
                    }
                    SettingsPanel {
                        PanelRows(listOf(
                            PanelItem(Icons.AutoMirrored.Filled.Logout, stringResource(R.string.settings_sign_out), stringResource(R.string.settings_sign_out_hint), destructive = true, end = RowEnd.NONE) { confirmSignOut = true },
                            PanelItem(Icons.Default.Warning, stringResource(R.string.settings_reset_app), stringResource(R.string.settings_reset_app_hint), destructive = true, end = RowEnd.NONE) { confirmResetApp = true }
                        ))
                    }
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
private fun SmsCard(app: AppModel) {
    val context = LocalContext.current
    var smsOn by remember { mutableStateOf(AppConfig.smsEnabled(context)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants[Manifest.permission.SEND_SMS] == true && grants[Manifest.permission.RECEIVE_SMS] == true
        smsOn = ok
        app.setSmsEnabled(ok)
    }

    fun setOn(on: Boolean) {
        if (!on) { smsOn = false; app.setSmsEnabled(false); return }
        val haveSend = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val haveRecv = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        if (haveSend && haveRecv) { smsOn = true; app.setSmsEnabled(true) }
        else permLauncher.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS))
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Sms, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.settings_sms_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = smsOn, onCheckedChange = { setOn(it) })
            }
            Text(
                stringResource(R.string.settings_sms_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun NostrCard(nostrOn: Boolean, relayCount: Int, onToggle: (Boolean) -> Unit, onEditRelays: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.settings_nostr_toggle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = nostrOn, onCheckedChange = onToggle)
            }
            Text(
                stringResource(R.string.settings_nostr_sub),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onEditRelays).padding(top = 12.dp)
            ) {
                Text(
                    stringResource(R.string.nostr_relays_count, relayCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

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

// ── Section pages: grouped panels ──────────────────────────────────────
//
// A section page (Account, More from NorseHorse, Support, Reset) is a few
// groups of rows. Each group sits in one rounded panel in the tint the main
// menu's cards use, with a hairline between rows and, where it has one, a
// plain heading above. A row is an icon, a title, an optional one-line hint,
// and a mark for what tapping does: an arrow for another screen, an
// open-in-new mark for something that leaves the app, nothing for a dialog.

internal enum class RowEnd { ARROW, EXTERNAL, NONE }

internal class PanelItem(
    val icon: ImageVector,
    val title: String,
    val hint: String? = null,
    val destructive: Boolean = false,
    val end: RowEnd = RowEnd.ARROW,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
internal fun SettingsPanel(heading: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (heading != null) {
        Text(
            text = heading,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp)
        )
    } else {
        Spacer(Modifier.height(12.dp))
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    ) {
        Column(content = content)
    }
}

/** Rows with a hairline between each pair. */
@Composable
internal fun PanelRows(items: List<PanelItem>) {
    items.forEachIndexed { index, item ->
        if (index > 0) PanelDivider()
        PanelRow(item)
    }
}

@Composable
internal fun PanelRow(item: PanelItem) {
    val off = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val tint = when {
        !item.enabled -> off
        item.destructive -> MaterialTheme.colorScheme.error
        else -> CPTheme.accent
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.enabled, onClick = item.onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !item.enabled -> off
                    item.destructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            if (item.hint != null) {
                Text(
                    item.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        val mark = when (item.end) {
            RowEnd.ARROW -> Icons.AutoMirrored.Filled.KeyboardArrowRight
            RowEnd.EXTERNAL -> Icons.AutoMirrored.Filled.OpenInNew
            RowEnd.NONE -> null
        }
        if (mark != null) {
            Spacer(Modifier.width(12.dp))
            Icon(
                mark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (item.end == RowEnd.EXTERNAL) 18.dp else 20.dp)
            )
        }
    }
}

/** A line of explanation under a panel. */
@Composable
internal fun PanelNote(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
    )
}

/** The hairline between two rows of a panel, starting where the titles start. */
@Composable
internal fun PanelDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    )
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

@Composable
private fun SealedResyncCard(app: AppModel) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    Card(
        onClick = { if (!running) confirming = true },
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
                Icons.Default.Refresh,
                contentDescription = null,
                tint = CPTheme.accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_sealed_resync),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.settings_sealed_resync_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_sealed_resync_confirm_title)) },
            text = { Text(stringResource(R.string.settings_sealed_resync_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    scope.launch {
                        running = true
                        app.resyncSealedAwait()
                        running = false
                        done = true
                    }
                }) { Text(stringResource(R.string.settings_sealed_resync_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (done) {
        AlertDialog(
            onDismissRequest = { done = false },
            title = { Text(stringResource(R.string.settings_sealed_resync_done_title)) },
            text = { Text(stringResource(R.string.settings_sealed_resync_done_body)) },
            confirmButton = {
                TextButton(onClick = { done = false }) { Text(stringResource(R.string.common_done)) }
            }
        )
    }
}

private fun isValidNostrRelay(url: String): Boolean {
    val u = try { java.net.URI(url.trim()) } catch (e: Exception) { return false }
    val scheme = u.scheme?.lowercase()
    return (scheme == "wss" || scheme == "ws") && !u.host.isNullOrEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NostrRelaysScreen(app: AppModel, onBack: () -> Unit) {
    val context = LocalContext.current
    var relays by remember { mutableStateOf(AppConfig.nostrRelays(context)) }
    var newUrl by remember { mutableStateOf("") }

    val trimmed = newUrl.trim()
    val validNew = isValidNostrRelay(trimmed) && !relays.contains(trimmed)

    fun apply(updated: List<String>) {
        relays = updated
        app.setNostrRelays(updated)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_nostr_relays)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_done))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsPanel(stringResource(R.string.nostr_relays_current)) {
                if (relays.isEmpty()) {
                    Text(
                        stringResource(R.string.nostr_relays_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                relays.forEachIndexed { index, relay ->
                    if (index > 0) PanelDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp)
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(
                            relay,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { apply(relays.filterNot { it == relay }) }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.common_remove), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            SettingsPanel(stringResource(R.string.nostr_relays_add)) {
                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    singleLine = true,
                    placeholder = { Text("wss://relay.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
                PanelDivider()
                PanelRow(PanelItem(Icons.Default.Add, stringResource(R.string.nostr_relays_add_action), end = RowEnd.NONE, enabled = validNew) {
                    apply(relays + trimmed)
                    newUrl = ""
                })
            }
            PanelNote(stringResource(R.string.nostr_relays_footer))

            SettingsPanel {
                PanelRow(PanelItem(Icons.Default.Restore, stringResource(R.string.nostr_relays_reset), destructive = true, end = RowEnd.NONE) {
                    apply(AppConfig.defaultNostrRelays)
                    newUrl = ""
                })
            }
            Spacer(Modifier.padding(bottom = 24.dp))
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

            SettingsPanel(stringResource(R.string.relay_current)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            AppConfig.relayBaseURL(context),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            if (AppConfig.usingCustomRelay(context)) stringResource(R.string.relay_custom)
                            else stringResource(R.string.relay_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (AppConfig.usingCustomRelay(context)) {
                var gatewayOn by remember { mutableStateOf(AppConfig.gatewayPushEnabled(context)) }
                SettingsPanel(stringResource(R.string.relay_push_header)) {
                    // The whole row is the switch, not only the thumb.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = gatewayOn,
                                role = Role.Switch,
                                onValueChange = { gatewayOn = it; app.setGatewayPush(it) }
                            )
                            .heightIn(min = 56.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text(
                            stringResource(R.string.relay_push_toggle),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = gatewayOn, onCheckedChange = null)
                    }
                }
                PanelNote(stringResource(R.string.relay_push_notice))
            }

            SettingsPanel(stringResource(R.string.relay_custom_section)) {
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it; testResult = null },
                    singleLine = true,
                    placeholder = { Text("https://relay.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                )
                PanelDivider()
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
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    val shade = if (valid) CPTheme.accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = shade, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(R.string.relay_test),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (valid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f)
                    )
                    if (testing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                PanelDivider()
                PanelRow(PanelItem(Icons.Default.Check, stringResource(R.string.relay_use), end = RowEnd.NONE, enabled = valid) { confirmSave = true })
            }

            testResult?.let { r ->
                val msg = when (r) {
                    RelayTestResult.Ok -> stringResource(R.string.relay_reachable)
                    RelayTestResult.BadUrl -> stringResource(R.string.relay_bad_url)
                    is RelayTestResult.Failed -> stringResource(R.string.relay_unreachable, r.reason)
                }
                PanelNote(msg, if (r is RelayTestResult.Ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            PanelNote(stringResource(if (onboarding) R.string.relay_onb_footer else R.string.relay_footer))

            if (AppConfig.usingCustomRelay(context)) {
                SettingsPanel {
                    PanelRow(PanelItem(Icons.Default.Restore, stringResource(R.string.relay_reset), destructive = true, end = RowEnd.NONE) {
                        AppConfig.setRelayBaseURL(context, null)
                        urlText = ""
                        testResult = null
                        if (!onboarding) savedNotice = true
                    })
                }
            }

            SettingsPanel(stringResource(R.string.relay_selfhost_header)) {
                PanelRows(listOf(
                    PanelItem(Icons.Default.Description, stringResource(R.string.relay_selfhost_setup), end = RowEnd.EXTERNAL) {
                        openInAppBrowser(context, "https://carrierpony.com/self-host")
                    },
                    PanelItem(Icons.Default.Code, stringResource(R.string.relay_selfhost_source), end = RowEnd.EXTERNAL) {
                        openInAppBrowser(context, "https://github.com/norsehorse-dev/CarrierPony-Relay")
                    }
                ))
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
