package com.carrierpony.app

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.ui.AddAccountScreen
import com.carrierpony.app.ui.BackupScreen
import com.carrierpony.app.ui.ConversationListScreen
import com.carrierpony.app.ui.ConversationScreen
import com.carrierpony.app.ui.FilesScreen
import com.carrierpony.app.ui.GroupConversationScreen
import com.carrierpony.app.ui.GroupDetailScreen
import com.carrierpony.app.ui.ImportBackupScreen
import com.carrierpony.app.ui.ImportKeyScreen
import com.carrierpony.app.ui.LockScreen
import com.carrierpony.app.ui.OnboardingScreen
import com.carrierpony.app.ui.PairingScreen
import com.carrierpony.app.ui.PrivacyPolicyScreen
import com.carrierpony.app.ui.ReportScreen
import com.carrierpony.app.ui.SecurityEducationScreen
import com.carrierpony.app.ui.SettingsScreen
import com.carrierpony.app.ui.TermsScreen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import com.carrierpony.app.ui.theme.CarrierPonyTheme

// AppCompatActivity: FragmentActivity underneath (so androidx.biometric's
// BiometricPrompt still attaches) plus per-app locale support for the
// language pickers. Compose is unaffected.
class MainActivity : AppCompatActivity() {

    private lateinit var app: AppModel

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            notificationsOff = computeNotificationsOff()
        }

    private var notificationsOff by androidx.compose.runtime.mutableStateOf(false)

    /** True when pushes arrive but their banners are silently dropped: asked
     *  once, denied (or revoked), identity present, hint not yet dismissed. */
    private fun computeNotificationsOff(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 33) return false
        if (app.identity.value == null) return false
        val prefs = getSharedPreferences("cp.prefs", MODE_PRIVATE)
        if (prefs.getBoolean("cp.notifHintDismissed", false)) return false
        if (!prefs.getBoolean("cp.askedNotifications", false)) return false
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = AppModel(applicationContext)
        // Restore the chosen language before any UI exists. (AppCompat can
        // auto-store locales, but applying from our own pref keeps cp.lang the
        // single source of truth across both platforms.)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(app.appLanguage))
        app.appLock.lockIfEnabled()   // gate on launch, like iOS
        enableEdgeToEdge()
        setContent {
            CarrierPonyTheme {
                Root(
                    app = app,
                    onUnlock = { app.unlock(this) },
                    notificationsOff = notificationsOff,
                    onEnableNotifications = {
                        startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                    },
                    onDismissNotificationsHint = {
                        getSharedPreferences("cp.prefs", MODE_PRIVATE)
                            .edit().putBoolean("cp.notifHintDismissed", true).apply()
                        notificationsOff = false
                    }
                )
            }
        }
    }

    // Polling lifecycle mirrors iOS scenePhase: poll while visible, stop when
    // not — and engage the lock when leaving the foreground.
    override fun onStart() {
        super.onStart()
        app.startMessaging()
        requestNotificationPermissionOnce()
    }

    override fun onResume() {
        super.onResume()
        // Re-check on every return: the user may have toggled the permission
        // in system settings while we were backgrounded.
        notificationsOff = computeNotificationsOff()
    }

    /** Ask for POST_NOTIFICATIONS once, only when an identity exists (no point
     *  prompting during onboarding). Declining is fine: pushes still wake the
     *  poll; only the banner is skipped. */
    private fun requestNotificationPermissionOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        if (app.identity.value == null) return
        val prefs = getSharedPreferences("cp.prefs", MODE_PRIVATE)
        if (prefs.getBoolean("cp.askedNotifications", false)) return
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) return
        prefs.edit().putBoolean("cp.askedNotifications", true).apply()
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onStop() {
        app.stopMessaging()
        app.appLock.lockIfEnabled()
        super.onStop()
    }
}

/** The app's root, the Compose counterpart of iOS RootView: lock overlay,
 *  onboarding when no identity exists, otherwise the Messages/Files tabs with
 *  fingerprint-routed navigation, plus the pairing, settings, and report routes. */
@Composable
private fun Root(
    app: AppModel,
    onUnlock: () -> Unit,
    notificationsOff: Boolean = false,
    onEnableNotifications: () -> Unit = {},
    onDismissNotificationsHint: () -> Unit = {}
) {
    val identity by app.identity.collectAsState()
    val store by app.store.collectAsState()
    val contacts by app.contactStore.contactsFlow.collectAsState()
    val isLocked by app.appLock.isLocked.collectAsState()
    val onboarded by app.onboarded.collectAsState()
    val accountUnread by app.accountUnread.collectAsState()

    var tab by remember { mutableStateOf(0) }
    var openPeer by remember { mutableStateOf<Fingerprint?>(null) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    var groupDetail by remember { mutableStateOf<String?>(null) }
    var showingPairing by rememberSaveable { mutableStateOf(false) }
    var showingSettings by rememberSaveable { mutableStateOf(false) }
    var showingBackup by remember { mutableStateOf(false) }
    var showingRestore by remember { mutableStateOf(false) }
    var showingImportKey by remember { mutableStateOf(false) }
    var showingPrivacy by remember { mutableStateOf(false) }
    var showingTerms by remember { mutableStateOf(false) }
    var showingLearn by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var showingAddAccount by remember { mutableStateOf(false) }
    var reportPeer by remember { mutableStateOf<Fingerprint?>(null) }

    if (isLocked) {
        LockScreen(onUnlock = onUnlock)
        return
    }

    // Gate on onboarding completion, not identity presence: the identity
    // exists from the onboarding identity step onward, but the flow continues
    // through the backup and pairing pages.
    val currentIdentity = identity
    val currentStore = store
    if (!onboarded || currentIdentity == null || currentStore == null) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Column(Modifier.padding(innerPadding)) { OnboardingScreen(app) }
        }
        return
    }

    if (showingPairing) {
        PairingScreen(app = app, onFinished = { showingPairing = false })
        return
    }

    if (showingBackup) {
        BackHandler { showingBackup = false }
        BackupScreen(identity = currentIdentity, onDone = { showingBackup = false })
        return
    }

    if (showingRestore) {
        BackHandler { showingRestore = false }
        ImportBackupScreen(app = app, onDone = { showingRestore = false })
        return
    }

    if (showingImportKey) {
        BackHandler { showingImportKey = false }
        ImportKeyScreen(app = app, onDone = { showingImportKey = false })
        return
    }

    if (showingLearn) {
        BackHandler { showingLearn = false }
        SecurityEducationScreen(onDone = { showingLearn = false })
        return
    }

    if (showingPrivacy) {
        BackHandler { showingPrivacy = false }
        PrivacyPolicyScreen(onDone = { showingPrivacy = false })
        return
    }

    if (showingTerms) {
        BackHandler { showingTerms = false }
        TermsScreen(onDone = { showingTerms = false })
        return
    }

    if (showingSettings) {
        BackHandler { showingSettings = false }
        SettingsScreen(
            app = app,
            biometricsAvailable = app.appLock.biometricsAvailable(androidx.compose.ui.platform.LocalContext.current),
            onPair = { showingSettings = false; showingPairing = true },
            onDone = { showingSettings = false },
            onBackup = { showingSettings = false; showingBackup = true },
            onRestore = { showingSettings = false; showingRestore = true },
            onImport = { showingSettings = false; showingImportKey = true },
            onPrivacy = { showingSettings = false; showingPrivacy = true },
            onTerms = { showingSettings = false; showingTerms = true },
            onLearn = { showingSettings = false; showingLearn = true },
            onAddAccount = { showingSettings = false; showingAddAccount = true }
        )
        return
    }

    if (showingAddAccount) {
        BackHandler { showingAddAccount = false }
        AddAccountScreen(app = app, onDone = { showingAddAccount = false })
        return
    }

    reportPeer?.let { peer ->
        BackHandler { reportPeer = null }
        ReportScreen(
            store = currentStore,
            contact = resolveContact(peer, contacts, currentStore),
            onBlock = {
                app.contactStore.remove(peer)
                openPeer = null
            },
            onDone = { reportPeer = null }
        )
        return
    }

    val gDetail = groupDetail
    if (gDetail != null) {
        BackHandler { groupDetail = null }
        GroupDetailScreen(
            store = currentStore,
            groupID = gDetail,
            contacts = contacts,
            onBack = { groupDetail = null },
            onLeft = { groupDetail = null; openGroup = null }
        )
        return
    }

    val gid = openGroup
    if (gid != null) {
        BackHandler { openGroup = null }
        GroupConversationScreen(
            store = currentStore,
            groupID = gid,
            contacts = contacts,
            onBack = { openGroup = null },
            onOpenDetail = { groupDetail = gid }
        )
        return
    }

    val peer = openPeer
    if (peer != null) {
        BackHandler { openPeer = null }
        ConversationScreen(
            store = currentStore,
            contact = resolveContact(peer, contacts, currentStore),
            onBack = { openPeer = null },
            onSetNickname = { nickname -> app.contactStore.setNickname(peer, nickname) },
            onReport = { reportPeer = peer },
            smsAvailable = app.smsSupport.available,
            onSetSmsNumber = { number -> app.setContactSmsNumber(peer, number) }
        )
        return
    }

    // Home is the root of the back stack, so the system back would otherwise
    // close the app abruptly (tester feedback). On the Files tab, back first
    // returns to Messages; on Messages, it asks before exiting.
    BackHandler {
        if (tab != 0) tab = 0 else confirmExit = true
    }
    if (confirmExit) {
        val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.exit_q)) },
            text = { Text(stringResource(R.string.exit_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    activity?.finish()
                }) { Text(stringResource(R.string.exit_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Default.Email, contentDescription = null) },
                    label = { Text(stringResource(R.string.common_messages)) }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Default.MailOutline, contentDescription = null) },
                    label = { Text(stringResource(R.string.common_files)) }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (tab == 0) {
                ConversationListScreen(
                    store = currentStore,
                    contacts = contacts,
                    onOpen = { openPeer = it },
                    onOpenGroup = { openGroup = it },
                    onUnpair = { app.contactStore.remove(it) },
                    onPair = { showingPairing = true },
                    onSettings = { showingSettings = true },
                    accounts = app.accounts(),
                    activeFingerprintHex = app.activeFingerprintHex,
                    onSwitchAccount = { app.switchAccount(it) },
                    onAddAccount = { showingAddAccount = true },
                    accountUnread = accountUnread,
                    notificationsOff = notificationsOff,
                    onEnableNotifications = onEnableNotifications,
                    onDismissNotificationsHint = onDismissNotificationsHint
                )
            } else {
                FilesScreen(store = currentStore, contacts = contacts)
            }
        }
    }
}

/** Resolve a routed fingerprint to a Contact. Peers you can message are
 *  contacts (pairing is a prerequisite); if a thread's peer is not yet a
 *  contact, fall back to a minimal Contact so the thread still opens. */
private fun resolveContact(
    fingerprint: Fingerprint,
    contacts: List<Contact>,
    store: com.carrierpony.app.messaging.ChatStore
): Contact {
    contacts.firstOrNull { it.fingerprint == fingerprint }?.let { return it }
    val peerName = store.conversations.value.values.firstOrNull { it.peer == fingerprint }?.peerName
    return Contact(
        fingerprint = fingerprint,
        publicKey = PublicKey(fingerprint, ""),
        name = peerName
    )
}
