// AppModel.kt
// CarrierPony Android
//
// The composition root, ported from iOS Core/App/AppModel.swift. Owns the
// identity and contact stores, and once an identity exists, builds the real
// crypto engine, relay client, and ChatStore and wires them together. The UI
// observes this to know whether to show the inbox or onboarding, and drives
// polling lifecycle through startMessaging()/stopMessaging().
//
// Remaining phase seams, marked where iOS calls them:
//   - PassphraseVault / LockManager session passphrase — key-import phase

package com.carrierpony.app

import android.content.Context
import com.carrierpony.app.messaging.GroupKeyStore
import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.crypto.PonyCryptoEngine
import com.carrierpony.app.crypto.PublicKey
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.identity.IdentityBackup
import com.carrierpony.app.identity.IdentityStore
import com.carrierpony.app.identity.PassphraseVault
import com.carrierpony.app.lock.LockManager
import com.carrierpony.app.messaging.ChatStore
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.ContactStore
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.pairing.Invite
import com.carrierpony.app.pairing.PairingSupport
import com.carrierpony.app.push.PushService
import com.carrierpony.app.relay.RelayClient
import com.carrierpony.app.relay.RelayException
import com.carrierpony.app.relay.GatewayClient
import com.carrierpony.core.CPArmor
import com.carrierpony.core.CPIdentityGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class IdentityCreationException : Exception("invalid fingerprint")

/** Pairing failures with the exact user-facing copy from iOS PairingError. */
sealed class PairingException(message: String) : Exception(message) {
    class NotReady : PairingException("You need an identity before you can pair.")
    class MalformedInvite : PairingException("That doesn't look like a CarrierPony invite.")
    class KeyMismatch : PairingException("The key the relay returned doesn't match the invite. Pairing was refused to keep you safe.")
}

class AppModel(context: Context) {

    private val appContext = context.applicationContext
    private val identityStore = IdentityStore(appContext)
    private val defaults = appContext.getSharedPreferences("cp.prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val contactStore = ContactStore(appContext.filesDir)
    val appLock = LockManager(appContext)

    // LAN-direct discovery (2.1 M2): advertise this device on the local
    // network and browse for other CarrierPony nodes so Settings can show how
    // many are nearby. Discovery only; no envelopes move yet.
    val lanDiscovery = com.carrierpony.app.net.LanDiscovery(appContext)
    val wanBridge = com.carrierpony.app.net.WanDirectBridge()
    private val vault = PassphraseVault(appContext)

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val _store = MutableStateFlow<ChatStore?>(null)
    val store: StateFlow<ChatStore?> = _store.asStateFlow()

    private var relay: RelayClient? = null

    private val _profileName = MutableStateFlow<String?>(null)
    val profileName: StateFlow<String?> = _profileName.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    var appLanguage: String
        get() = defaults.getString("cp.lang", "en") ?: "en"
        set(value) {
            defaults.edit().putString("cp.lang", value).apply()
            // Apply immediately: recreates activities with the new locale, so
            // the language pickers switch the UI in place. Callers are UI
            // (main thread), which setApplicationLocales requires.
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                androidx.core.os.LocaleListCompat.forLanguageTags(value)
            )
        }

    // Observable so the root can gate on it: the identity exists from the
    // onboarding identity step onward, but the flow continues through backup
    // and pairing — "has an identity" and "finished onboarding" differ.
    private val _onboarded = MutableStateFlow(defaults.getBoolean("cp.onboarded", false))
    val onboarded: StateFlow<Boolean> = _onboarded.asStateFlow()

    var onboardingComplete: Boolean
        get() = defaults.getBoolean("cp.onboarded", false)
        set(value) {
            defaults.edit().putBoolean("cp.onboarded", value).apply()
            _onboarded.value = value
        }

    private var inForeground = false

    init {
        // Wire-level failures (envelope opens, relay refreshes) go to logcat:
        //   adb logcat -s CarrierPony
        ChatStore.diagnostics = { message, error -> android.util.Log.w("CarrierPony", message, error) }
        AttachmentStore.directory = File(appContext.filesDir, "carrierpony-attachments")
        PushService.ensureChannel(appContext)
        // A rotated FCM token re-registers with the relay immediately.
        PushService.onNewToken = { token -> submitPushToken(token) }
        // A push wake with the app in the foreground refreshes in place and
        // suppresses the notification; backgrounded, the generic banner shows.
        PushService.onWake = {
            val live = _store.value
            if (live != null && inForeground) {
                scope.launch { live.refresh() }
                true
            } else {
                false
            }
        }
        try {
            val existing = identityStore.load()
            if (existing != null) {
                _identity.value = existing
                onboardingComplete = true   // existing users have already set up
                loadProfileName()
                // A protected identity's session passphrase does not survive
                // process death. With App Lock ON, unlock() reloads it from
                // the vault after biometrics; with App Lock OFF there is no
                // unlock moment, so load it now — the user chose no lock, and
                // the vault entry is Keystore-sealed either way. Without this,
                // a protected identity silently stops signing and decrypting
                // after the first restart.
                if (existing.protected && !appLock.appLockEnabled) {
                    appLock.setSessionPassphrase(vault.read(existing.fingerprint.hex))
                }
                buildStore(existing)
            }
        } catch (e: Exception) {
            _lastError.value = "identity load failed"
        }
    }

    // ── Messaging lifecycle (driven by the Activity, like iOS scenePhase) ──

    fun startMessaging() {
        inForeground = true
        val store = _store.value ?: return
        scope.launch { store.start() }
        registerPush()
        startOtherAccountPolling()
        lanDiscovery.keyProvider = { _store.value?.lanContactKeys() ?: emptyMap() }
        lanDiscovery.onEnvelope = { data -> _store.value?.let { st -> scope.launch { st.ingestLanEnvelope(data) } } }
        lanDiscovery.start()
        // Bring up WAN direct if it was left enabled: the toggle's onChange is the only
        // other caller, so without this an app that launches (or updates) with the
        // switch already on never sets up the transport. Idempotent on each foreground.
        if (AppConfig.wanDirectEnabled(appContext)) setWanDirectEnabled(true)
    }

    fun stopMessaging() {
        inForeground = false
        _store.value?.stop()
        otherPollLoop?.cancel()
        otherPollLoop = null
        lanDiscovery.stop()
    }

    /** Toggle LAN-direct (2.1). Off by default; on lets the app identify known
     *  contacts on the local network (and, once M3b lands, deliver directly,
     *  exposing this device's IP to that contact). */
    fun setLanDirectEnabled(enabled: Boolean) {
        AppConfig.setLanDirectEnabled(appContext, enabled)
        lanDiscovery.lanDidToggle()
    }

    /** Toggle LAN-direct "skip the relay" (2.1 M4b). Takes effect on the next send;
     *  deliverToPeer reads it live via the lanSkipRelay closure. */
    fun setLanDirectSkipRelay(enabled: Boolean) {
        AppConfig.setLanDirectSkipRelay(appContext, enabled)
    }

    /** Toggle WAN-direct (2.2). M1: enabling fires a synthetic signaling round trip
     *  to each sealed-capable contact so the offer/answer/ice control-op path can be
     *  verified over the relay before the WebRTC dependency lands. */
    fun setWanDirectEnabled(enabled: Boolean) {
        AppConfig.setWanDirectEnabled(appContext, enabled)
        if (!enabled) {
            wanBridge.disable()
            _wanConnectedCount.value = 0
            return
        }
        val store = _store.value ?: return
        val myHex = _identity.value?.fingerprint?.hex?.lowercase() ?: return
        val keys = store.lanContactKeys()
        wanBridge.updateKeys(keys)
        val host = AppConfig.wanStunHost(appContext).ifEmpty {
            try { java.net.URI(AppConfig.relayBaseURL(appContext)).host ?: "" } catch (e: Exception) { "" }
        }
        if (host.isEmpty() || !wanBridge.enable(host, AppConfig.wanStunPort(appContext))) return
        // Exactly one side offers: the peer whose fingerprint sorts after ours. The
        // other side creates its session when the offer arrives over signaling.
        for (peerHex in keys.keys) {
            if (myHex < peerHex) wanBridge.openPath(peerHex)
        }
    }

    // ── Background delivery for non-active accounts ─────────────────────

    // Unread counts for accounts other than the active one, refreshed by the
    // background poll so the switcher can flag which accounts have messages waiting.
    private val _accountUnread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val accountUnread: StateFlow<Map<String, Int>> = _accountUnread.asStateFlow()

    // WAN-direct (2.2 M1) signaling test surface.
    private val _wanSignalsReceived = MutableStateFlow(0)
    val wanSignalsReceived: StateFlow<Int> = _wanSignalsReceived.asStateFlow()
    private val _lastWanSignal = MutableStateFlow<String?>(null)
    val lastWanSignal: StateFlow<String?> = _lastWanSignal.asStateFlow()
    private val _wanConnectedCount = MutableStateFlow(0)
    val wanConnectedCount: StateFlow<Int> = _wanConnectedCount.asStateFlow()

    private var otherPollLoop: Job? = null

    /** While foreground, poll every OTHER account's inbox on a slow cadence so a
     *  message to a non-active account is received (persisted to that account's
     *  conversations) and its unread count surfaces in the switcher, without the
     *  user having to switch to it first. */
    private fun startOtherAccountPolling() {
        otherPollLoop?.cancel()
        if (identityStore.list().size <= 1) { _accountUnread.value = emptyMap(); return }
        otherPollLoop = scope.launch(Dispatchers.Default) {
            while (isActive) {
                pollOtherAccounts()
                delay(20_000)
            }
        }
    }

    private suspend fun pollOtherAccounts() {
        val activeHex = _identity.value?.fingerprint?.hex
        val others = identityStore.list().filter { it != activeHex }
        if (others.isEmpty()) { _accountUnread.value = emptyMap(); return }
        val unread = mutableMapOf<String, Int>()
        for (fprHex in others) {
            try {
                val id = identityStore.load(fprHex) ?: continue
                // A protected account's key needs a passphrase we don't hold in the
                // background, so skip it; it syncs when the user switches to it.
                if (id.protected) continue
                pollAccountOnce(id)?.let { unread[fprHex] = it }
            } catch (e: Exception) {
                // best-effort; retry next cycle
            }
        }
        _accountUnread.value = unread
    }

    /** Build a throwaway stack for one account, poll its inbox once (receiving and
     *  persisting new messages, and keeping its sealed mailboxes registered so
     *  senders can reach it while it is backgrounded), and return its unread count. */
    private suspend fun pollAccountOnce(id: Identity): Int? {
        val tContacts = ContactStore(appContext.filesDir).apply { activate(id.fingerprint.hex) }
        val crypto = PonyCryptoEngine(
            fingerprint = id.fingerprint,
            secretKey = id.secretKey,
            armored = id.armoredPublicKey,
            passphrase = { null },
            publicKeyResolver = { fingerprint ->
                if (fingerprint == id.fingerprint) {
                    CPArmor.dearmor(id.armoredPublicKey)?.let { PublicKey(id.fingerprint, id.armoredPublicKey) }
                } else {
                    tContacts.contact(fingerprint)?.publicKey
                }
            }
        )
        val relay = RelayClient(
            baseURL = AppConfig.relayBaseURL(appContext),
            crypto = crypto,
            deviceID = AppConfig.deviceID(appContext)
        )
        val store = ChatStore(
            identity = id.fingerprint,
            relay = relay,
            crypto = crypto,
            contacts = { tContacts.contacts },
            updatePeerName = { fingerprint, name -> tContacts.setName(fingerprint, name) },
            storageDir = appContext.filesDir,
            scope = scope,
            groupKeys = GroupKeyStore(appContext),
            sealedKeys = com.carrierpony.app.messaging.SealedKeyStore(appContext, id.fingerprint.hex),
            pairingSweep = null
        )
        return try {
            sweepBackgroundInvites(id, tContacts, relay, store)
            store.refresh()
            store.conversations.value.values.sumOf { it.unreadCount }
        } finally {
            store.stop()
        }
    }

    /** Complete a background account's outstanding pairing OFFERS from the relay
     *  without making it active. Mirrors sweepPendingInvites/finishAcceptedInvite
     *  but works off the persisted pending-invite list and the throwaway stack, so
     *  pairing an account that has since been switched away from still finishes on
     *  its side (otherwise the offerer half-pairs and you have to pair the other
     *  direction too). */
    private suspend fun sweepBackgroundInvites(id: Identity, contacts: ContactStore, relay: RelayClient, store: ChatStore) {
        val key = "cp.pendinginvites.${id.fingerprint.hex}"
        val raw = defaults.getString(key, null) ?: return
        val arr = try { org.json.JSONArray(raw) } catch (e: Exception) { return }
        val keep = org.json.JSONArray()
        var changed = false
        val nowSec = System.currentTimeMillis() / 1000
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val token = o.getString("token")
            val inPerson = o.optBoolean("inPerson", false)
            val expiresAt = if (o.isNull("expiresAt")) null else o.optLong("expiresAt")
            if (expiresAt != null && expiresAt <= nowSec) { changed = true; continue }
            try {
                val status = relay.pairStatus(token = token)
                when (status.state) {
                    "accepted" -> {
                        val rFpr = status.responderFpr
                        val rPub = status.responderPubkey
                        if (rFpr != null && rPub != null) {
                            val trust = if (inPerson) TrustLevel.VERIFIED else TrustLevel.UNVERIFIED
                            PairingSupport.consistentContact(rFpr, rPub, trust)?.let { contact ->
                                contacts.add(contact)
                                if (inPerson) contacts.markVerified(contact.fingerprint)
                                store.sendProfile(name = defaults.getString("cp.profile.${id.fingerprint.hex}", null), to = contact)
                            }
                        }
                        changed = true
                    }
                    "expired" -> changed = true
                    else -> keep.put(o)
                }
            } catch (e: RelayException) {
                if (e.status in 400..499) changed = true else keep.put(o)
            } catch (e: Exception) {
                keep.put(o)
            }
        }
        if (changed) {
            if (keep.length() == 0) defaults.edit().remove(key).apply()
            else defaults.edit().putString(key, keep.toString()).apply()
        }
    }

    /** Fetch the FCM token and register it with the relay. A no-op until the
     *  Firebase project exists (google-services.json absent => Firebase never
     *  initializes and the fetch throws immediately). */
    private fun registerPush() {
        if (relay == null) return
        // On a custom relay, push only makes sense if the user opted into the
        // gateway; the relay itself cannot push.
        if (AppConfig.usingCustomRelay(appContext) && !AppConfig.gatewayPushEnabled(appContext)) return
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> submitPushToken(token) }
        } catch (e: Exception) {
            // Firebase not configured on this build; polling covers delivery.
        }
    }

    /** Route a push token: to the gateway on a custom relay (if opted in), or to
     *  the relay directly on the default relay. */
    private fun submitPushToken(token: String) {
        scope.launch {
            if (AppConfig.usingCustomRelay(appContext)) {
                // Self-hosted relay: the gateway is the user's own opt-in choice,
                // so their push token never reaches the default gateway unasked.
                if (AppConfig.gatewayPushEnabled(appContext)) registerWithGateway(token)
            } else {
                // Default relay: every device registers with the gateway so it
                // gets a sealed wake token and can be woken in the background
                // (the relay can't push a sealed message itself). Also keep the
                // fingerprint-keyed push for any remaining legacy delivery.
                registerWithGateway(token)
                try { relay?.registerPush(token) } catch (e: Exception) { /* polling covers delivery */ }
            }
        }
    }

    private suspend fun registerWithGateway(token: String) {
        val r = relay ?: return
        try {
            // Register the ACTIVE account's sealed device with the gateway, so each
            // account gets its OWN wake token; a shared token would let the relay
            // link the accounts. Falls back to the install id if the sealed device
            // is not up yet.
            val gwId = _store.value?.sealedDeviceId() ?: AppConfig.deviceID(appContext)
            val wake = GatewayClient.registerPush(gwId, token, "fcm")
            AppConfig.setStoredWakeToken(appContext, wake, _identity.value?.fingerprint?.hex)
            r.registerWake(wake)
            // Attach the wake token to the sealed device now, rather than waiting
            // for the next app start, so background sealed pushes work from the
            // first session (the token arrives async, after the sealed device was
            // first registered with an empty token).
            _store.value?.sealedReregisterDevice()
        } catch (e: Exception) {
            // polling covers delivery
        }
    }

    /** Toggle gateway push (the custom-relay opt-in). On enable it registers the
     *  current token with the gateway; on disable it deregisters and clears the
     *  wake token on the relay. */
    fun setGatewayPush(on: Boolean) {
        AppConfig.setGatewayPushEnabled(appContext, on)
        if (on) {
            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                    .addOnSuccessListener { token -> scope.launch { registerWithGateway(token) } }
            } catch (e: Exception) {
                // Firebase not configured; nothing to register.
            }
        } else {
            scope.launch { disableGateway() }
        }
    }

    private suspend fun disableGateway() {
        val fpr = _identity.value?.fingerprint?.hex
        val wake = AppConfig.storedWakeToken(appContext, fpr)
        AppConfig.setStoredWakeToken(appContext, null, fpr)
        if (wake != null) GatewayClient.deregister(wake)
        try { relay?.registerWake("") } catch (e: Exception) { }
    }

    /** Keep the session passphrase consistent when the App Lock toggle
     *  changes: turning the lock OFF must immediately load a protected
     *  identity's passphrase (there will be no unlock moment to do it);
     *  turning it ON leaves the current session, which the next lock drops. */
    fun setAppLockEnabled(enabled: Boolean) {
        appLock.appLockEnabled = enabled
        val identity = _identity.value ?: return
        if (!enabled && identity.protected && appLock.sessionPassphrase == null) {
            appLock.setSessionPassphrase(vault.read(identity.fingerprint.hex))
        }
    }

    /** Run the biometric unlock; on success, load a protected identity's
     *  passphrase from the vault into the session so the engine can use it. */
    fun unlock(activity: androidx.fragment.app.FragmentActivity) {
        appLock.unlock(activity) { ok ->
            if (ok) {
                val identity = _identity.value
                if (identity != null && identity.protected) {
                    appLock.setSessionPassphrase(vault.read(identity.fingerprint.hex))
                }
            }
        }
    }

    /** Install an imported identity. For a protected key, the passphrase goes
     *  into the vault and the current session, so messaging works before the
     *  first lock/unlock cycle. Prefills the profile name from the key's User
     *  ID when none is set. */
    fun adoptImportedIdentity(identity: Identity, passphrase: String?) {
        if (identity.protected && !passphrase.isNullOrEmpty()) {
            vault.store(passphrase, identity.fingerprint.hex)
            appLock.setSessionPassphrase(passphrase)
        } else {
            appLock.clearSessionPassphrase()
        }
        setIdentity(identity)
        onboardingComplete = true
        if (_profileName.value == null) {
            com.carrierpony.app.identity.KeyImport.userIDName(identity.secretKey)?.let { setProfileName(it) }
        }
    }

    // ── Identity ───────────────────────────────────────────────────────

    /** Generate a fresh v4 identity off the main thread, persist it, and go live. */
    suspend fun createIdentity(name: String, email: String = "user@carrierpony.app") {
        val generated = withContext(Dispatchers.Default) {
            CPIdentityGenerator.generateV4Identity(name, email)
        }
        val fingerprint = Fingerprint.from(generated.fingerprint)
            ?: throw IdentityCreationException()
        setIdentity(Identity(
            fingerprint = fingerprint,
            secretKey = generated.secretKey,
            armoredPublicKey = generated.armoredPublicKey
        ))
        setProfileName(name)
    }

    /** Restore an identity from an encrypted backup blob. Throws
     *  BackupException on a wrong passphrase or malformed blob. Marks
     *  onboarding complete: a restored user has already been set up. */
    suspend fun importBackup(blob: String, passphrase: String) {
        val restored = withContext(Dispatchers.Default) {
            IdentityBackup.restore(blob, passphrase)
        }
        setIdentity(restored)
        onboardingComplete = true
    }

    /** Install a new identity (from onboarding, import, or pairing), persist it,
     *  and stand up the messaging stack around it. */
    fun setIdentity(identity: Identity) {
        // Stop the previous account's poll loop before switching the stack over.
        _store.value?.stop()
        try {
            identityStore.save(identity)
        } catch (e: Exception) {
            _lastError.value = "identity save failed"
        }
        _identity.value = identity
        loadProfileName()
        buildStore(identity)
        // Adding an account happens past onboarding, so bring the new account live
        // now. During first-run this stays false until the final step starts it.
        if (onboardingComplete) startMessaging()
    }

    // ── Multiple accounts ──────────────────────────────────────────────

    /** Fingerprint and display name for each stored account, for the switcher. */
    data class AccountSummary(val fingerprint: Fingerprint, val name: String?)

    fun accounts(): List<AccountSummary> = identityStore.list().mapNotNull { hex ->
        Fingerprint.from(hex)?.let { AccountSummary(it, defaults.getString("cp.profile.$hex", null)) }
    }

    val activeFingerprintHex: String? get() = _identity.value?.fingerprint?.hex

    /** Switch the active account, rebuilding the messaging stack around it. */
    fun switchAccount(fprHex: String) {
        if (fprHex == _identity.value?.fingerprint?.hex) return
        val next = try { identityStore.load(fprHex) } catch (e: Exception) { null } ?: return
        _store.value?.stop()
        identityStore.setSelected(fprHex)
        _identity.value = next
        // A protected identity needs its session passphrase to sign and decrypt;
        // load it now when there is no App Lock unlock moment (mirrors launch).
        if (next.protected && !appLock.appLockEnabled) {
            appLock.setSessionPassphrase(vault.read(fprHex))
        }
        loadProfileName()
        buildStore(next)
        startMessaging()
    }

    /** Remove an account and erase its local data (messages, contacts, sealed
     *  keys, pending invites, profile, wake token). If it was the active account,
     *  switch to the next remaining one; if none remain, return to onboarding. */
    fun removeAccount(fprHex: String) {
        val isActive = fprHex == _identity.value?.fingerprint?.hex
        if (isActive) {
            // The live store owns this identity's sealed keys and conversations;
            // purge() wipes them (and the conversations file and attachments).
            _store.value?.stop()
            _store.value?.purge()
        } else {
            // A non-active account has no live store, so wipe its files directly.
            com.carrierpony.app.messaging.SealedKeyStore(appContext, fprHex).purge()
            File(appContext.filesDir, "carrierpony-conversations-$fprHex.json").delete()
        }
        contactStore.deleteFile(fprHex)
        vault.delete(fprHex)
        defaults.edit()
            .remove(profileKeyFor(fprHex))
            .remove("cp.pendinginvites.$fprHex")
            .apply()
        AppConfig.setStoredWakeToken(appContext, null, fprHex)
        val newSelected = identityStore.delete(fprHex)
        if (!isActive) return
        if (newSelected == null) {
            appLock.clearSessionPassphrase()
            onboardingComplete = false
            _identity.value = null
            _store.value = null
            relay = null
            _profileName.value = null
        } else {
            val next = try { identityStore.load(newSelected) } catch (e: Exception) { null } ?: return
            _identity.value = next
            if (next.protected && !appLock.appLockEnabled) {
                appLock.setSessionPassphrase(vault.read(newSelected))
            }
            loadProfileName()
            buildStore(next)
            startMessaging()
        }
    }

    fun signOut() {
        _identity.value?.let { vault.delete(it.fingerprint.hex) }
        appLock.clearSessionPassphrase()
        onboardingComplete = false   // a signed-out device sets up fresh
        _store.value?.stop()
        _store.value?.purge()
        identityStore.deleteAll()
        _identity.value = null
        _store.value = null
        relay = null
    }

    /** Show the first-run setup again. Identity and messages are kept. */
    fun resetOnboarding() {
        onboardingComplete = false
    }

    /** Factory reset: wipe the identity, contacts, conversations, attachments,
     *  and all preferences. Returns to onboarding. */
    fun resetApp() {
        signOut()
        contactStore.removeAll()
        AttachmentStore.directory.deleteRecursively()
        val editor = defaults.edit()
        for (key in defaults.all.keys.filter { it.startsWith("cp.") }) {
            editor.remove(key)
        }
        editor.apply()
        appLanguage = "en"
        onboardingComplete = false
        _profileName.value = null
    }

    // ── Pairing (over the relay) ───────────────────────────────────────

    /** App Review demo: create a simulated local contact and seed a welcome
     *  message. Uses no network. See DemoMode. */
    fun startDemo(): Contact? {
        val identity = _identity.value ?: return null
        val contact = Contact(
            fingerprint = DemoMode.fingerprint,
            publicKey = PublicKey(DemoMode.fingerprint, identity.armoredPublicKey),
            name = DemoMode.peerName,
            trust = TrustLevel.VERIFIED
        )
        contactStore.add(contact)
        val store = _store.value
        scope.launch {
            store?.injectDemoIncoming(
                text = "Welcome to the CarrierPony demo. Send me a message, or attach a file, and I'll reply. Everything here is encrypted and decrypted on this device. You can also open the ••• menu to try Report and Block."
            )
        }
        return contact
    }

    /** Publish a pairing offer and remember it, so the offerer's side completes
     *  even after the invite screen closes: the pending list is swept on every
     *  inbox refresh and at the next launch. Both the in-person live QR
     *  (inPerson = true, completes VERIFIED) and the remote invite use this, so
     *  the offerer never half-pairs by dismissing before the peer's scan lands. */
    suspend fun createRememberedInvite(inPerson: Boolean = false): Pair<Invite, Long?> {
        val identity = _identity.value ?: throw PairingException.NotReady()
        val relay = this.relay ?: throw PairingException.NotReady()
        val response = relay.pairOffer(pubkey = identity.armoredPublicKey)
        val expiresAt = if (response.expiresIn > 0) System.currentTimeMillis() / 1000 + response.expiresIn else null
        synchronized(pendingInvitesLock) {
            pendingInvites.add(PendingInvite(response.token, _profileName.value, expiresAt, inPerson))
            savePendingInvites()
        }
        return Invite.create(token = response.token, fingerprint = identity.fingerprint, name = _profileName.value) to expiresAt
    }

    /** Poll an outstanding invite this identity created. Returns the newly paired
     *  contact once the peer accepts, or null while still waiting. */
    suspend fun pollInvite(invite: Invite): Contact? {
        completedInviteContacts[invite.t]?.let { return it }
        val relay = this.relay ?: throw PairingException.NotReady()
        val status = relay.pairStatus(token = invite.t)
        if (status.state != "accepted") {
            if (status.state == "expired") forgetPendingInvite(invite.t)
            return null
        }
        val responderFpr = status.responderFpr ?: return null
        val responderPubkey = status.responderPubkey ?: return null
        return finishAcceptedInvite(invite.t, responderFpr, responderPubkey)
    }

    /** Complete the offerer's side of an accepted invite exactly once. An
     *  in-person invite was exchanged face to face, so the responder's key is
     *  trusted and the contact is added VERIFIED; a remote invite has no
     *  out-of-band anchor for the responder's key here, so it is UNVERIFIED
     *  (trust-on-first-use, upgraded later by the safety number). Keyed by token
     *  so the on-screen poll and the background sweep can't double-add. */
    private suspend fun finishAcceptedInvite(token: String, responderFpr: String, responderPubkey: String): Contact? {
        completedInviteContacts[token]?.let { return it }
        val inPerson = synchronized(pendingInvitesLock) { pendingInvites.firstOrNull { it.token == token }?.inPerson ?: false }
        val trust = if (inPerson) TrustLevel.VERIFIED else TrustLevel.UNVERIFIED
        val contact = PairingSupport.consistentContact(responderFpr, responderPubkey, trust)
            ?: run { forgetPendingInvite(token); throw PairingException.KeyMismatch() }
        completedInviteContacts[token] = contact
        contactStore.add(contact)
        if (inPerson) contactStore.markVerified(contact.fingerprint)
        forgetPendingInvite(token)
        _store.value?.sendProfile(name = _profileName.value, to = contact)
        return contact
    }

    /** Check every remembered invite against the relay. Runs from the inbox
     *  refresh cycle, before messages are processed, so a new contact's first
     *  messages open cleanly on the same pass that discovers their acceptance.
     *  Throttled: a pending invite can outlive a session by a day. */
    suspend fun sweepPendingInvites() {
        val identity = _identity.value ?: return
        if (this.relay == null) return
        val snapshot = synchronized(pendingInvitesLock) {
            if (pendingInvites.isEmpty()) return
            if (System.currentTimeMillis() - lastPendingSweep < 30_000) return
            lastPendingSweep = System.currentTimeMillis()
            pendingInvites.toList()
        }
        val nowSec = System.currentTimeMillis() / 1000
        for (pending in snapshot) {
            if (pending.expiresAt != null && pending.expiresAt <= nowSec) { forgetPendingInvite(pending.token); continue }
            try {
                pollInvite(Invite.create(pending.token, identity.fingerprint, pending.name))
            } catch (e: PairingException.KeyMismatch) {
                forgetPendingInvite(pending.token)   // can never become valid
            } catch (e: RelayException) {
                if (e.status in 400..499) forgetPendingInvite(pending.token)   // relay forgot the token
            } catch (e: Exception) {
                // transient (offline / 5xx): keep for the next sweep
            }
        }
    }

    // Remembered pairing offers this identity created but has not yet collected
    // the acceptance for. Persisted per identity so the sweep survives restarts.
    data class PendingInvite(val token: String, val name: String?, val expiresAt: Long?, val inPerson: Boolean)
    private val pendingInvites = mutableListOf<PendingInvite>()
    private val pendingInvitesLock = Any()
    private val completedInviteContacts = mutableMapOf<String, Contact>()
    @Volatile private var lastPendingSweep = 0L

    private fun pendingInvitesKey(identity: Identity) = "cp.pendinginvites.${identity.fingerprint.hex}"

    private fun loadPendingInvites() {
        val identity = _identity.value ?: return
        val raw = defaults.getString(pendingInvitesKey(identity), null) ?: return
        synchronized(pendingInvitesLock) {
            pendingInvites.clear()
            try {
                val arr = org.json.JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    pendingInvites.add(PendingInvite(
                        o.getString("token"),
                        if (o.isNull("name")) null else o.optString("name"),
                        if (o.isNull("expiresAt")) null else o.getLong("expiresAt"),
                        o.optBoolean("inPerson", false)
                    ))
                }
            } catch (e: Exception) { }
        }
    }

    private fun savePendingInvites() {
        val identity = _identity.value ?: return
        val arr = org.json.JSONArray()
        for (p in pendingInvites) {
            val o = org.json.JSONObject()
            o.put("token", p.token)
            o.put("name", p.name)
            o.put("expiresAt", p.expiresAt)
            o.put("inPerson", p.inPerson)
            arr.put(o)
        }
        if (pendingInvites.isEmpty()) defaults.edit().remove(pendingInvitesKey(identity)).apply()
        else defaults.edit().putString(pendingInvitesKey(identity), arr.toString()).apply()
    }

    private fun forgetPendingInvite(token: String) {
        synchronized(pendingInvitesLock) {
            if (pendingInvites.removeAll { it.token == token }) savePendingInvites()
        }
    }

    /** Accept an invite: fetch the offerer's key and require it to match the
     *  fingerprint carried in the invite before adding the contact. A mismatch
     *  is a hard failure — we never add a contact whose key doesn't match.
     *  Trust depends on how the invite arrived: pasted from a remote channel
     *  is UNVERIFIED (the safety number upgrades it); scanned face to face is
     *  VERIFIED, because the fingerprint traveled physically via the QR. */
    suspend fun acceptInvite(invite: Invite, trust: TrustLevel = TrustLevel.UNVERIFIED): Contact {
        val identity = _identity.value ?: throw PairingException.NotReady()
        val relay = this.relay ?: throw PairingException.NotReady()
        val invitedFpr = invite.fingerprint ?: throw PairingException.MalformedInvite()
        val response = relay.pairAccept(token = invite.t, pubkey = identity.armoredPublicKey)
        if (response.offererFpr.uppercase() != invitedFpr.hex) throw PairingException.KeyMismatch()
        val contact = PairingSupport.consistentContact(invitedFpr.hex, response.offererPubkey, trust)
            ?: throw PairingException.KeyMismatch()
        val named = contact.copy(name = invite.n)
        contactStore.add(named)
        _store.value?.sendProfile(name = _profileName.value, to = named)
        return named
    }

    // ── Store wiring ───────────────────────────────────────────────────

    /** This is the one place the concrete PonyCryptoEngine, RelayClient, and
     *  ChatStore are constructed; everything above codes against the stores. */
    private fun buildStore(identity: Identity) {
        // Load this identity's contacts (per-account file) before the stack reads them.
        this.contactStore.activate(identity.fingerprint.hex)
        val contactStore = this.contactStore
        val crypto = PonyCryptoEngine(
            fingerprint = identity.fingerprint,
            secretKey = identity.secretKey,
            armored = identity.armoredPublicKey,
            passphrase = { if (identity.protected) appLock.sessionPassphrase else null },
            publicKeyResolver = { fingerprint ->
                // Resolve our own key for self-copy verification, else a contact.
                if (fingerprint == identity.fingerprint) {
                    CPArmor.dearmor(identity.armoredPublicKey)
                        ?.let { PublicKey(identity.fingerprint, identity.armoredPublicKey) }
                } else {
                    contactStore.contact(fingerprint)?.publicKey
                }
            }
        )
        val relay = RelayClient(
            baseURL = AppConfig.relayBaseURL(appContext),
            crypto = crypto,
            deviceID = AppConfig.deviceID(appContext)
        )
        this.relay = relay
        _store.value = ChatStore(
            identity = identity.fingerprint,
            relay = relay,
            crypto = crypto,
            contacts = { contactStore.contacts },
            updatePeerName = { fingerprint, name -> contactStore.setName(fingerprint, name) },
            storageDir = appContext.filesDir,
            scope = scope,
            groupKeys = GroupKeyStore(appContext),
            sealedKeys = com.carrierpony.app.messaging.SealedKeyStore(appContext, identity.fingerprint.hex),
            pairingSweep = { sweepPendingInvites() },
            lanTransport = com.carrierpony.app.messaging.LanDirectTransport(lanDiscovery),
            wanTransport = com.carrierpony.app.messaging.WanDirectTransport(wanBridge),
            lanSkipRelay = { AppConfig.lanDirectSkipRelay(appContext) }
        )
        _store.value?.onSignal = { peerHex, op, sdp, candidate ->
            _wanSignalsReceived.value = _wanSignalsReceived.value + 1
            _lastWanSignal.value = "$op from ${peerHex.takeLast(8)}"
            wanBridge.handleIncoming(peerHex, op, sdp, candidate)
        }
        wanBridge.sendOp = { op, peer, sdp, candidate ->
            scope.launch { _store.value?.sendWanSignal(op, peer, sdp, candidate) }
        }
        wanBridge.onConnectedChange = { count -> _wanConnectedCount.value = count }
        wanBridge.payloadSink = { _, data -> _store.value?.let { st -> scope.launch { st.ingestLanEnvelope(data) } } }
        loadPendingInvites()
    }

    // ── Profile ────────────────────────────────────────────────────────

    private fun profileKey(identity: Identity) = profileKeyFor(identity.fingerprint.hex)
    private fun profileKeyFor(fprHex: String) = "cp.profile.$fprHex"

    private fun loadProfileName() {
        val identity = _identity.value ?: run { _profileName.value = null; return }
        _profileName.value = defaults.getString(profileKey(identity), null)
    }

    /** Set your own display name (null/empty = anonymous). Persists per-identity
     *  and pushes the change to existing contacts as an encrypted profile message. */
    fun setProfileName(name: String?) {
        val identity = _identity.value ?: return
        val value = name?.trim()?.takeIf { it.isNotEmpty() }
        _profileName.value = value
        if (value != null) {
            defaults.edit().putString(profileKey(identity), value).apply()
        } else {
            defaults.edit().remove(profileKey(identity)).apply()
        }
        val toNotify = contactStore.contacts
        val store = _store.value ?: return
        scope.launch {
            for (contact in toNotify) {
                store.sendProfile(name = value, to = contact)
            }
        }
    }
}
