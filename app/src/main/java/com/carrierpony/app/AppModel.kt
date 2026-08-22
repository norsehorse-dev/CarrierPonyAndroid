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
import com.carrierpony.app.pairing.PendingInvite
import com.carrierpony.app.push.PushService
import com.carrierpony.app.relay.RelayClient
import com.carrierpony.app.relay.RelayException
import com.carrierpony.core.CPArmor
import com.carrierpony.core.CPIdentityGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val vault = PassphraseVault(appContext)

    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val _store = MutableStateFlow<ChatStore?>(null)
    val store: StateFlow<ChatStore?> = _store.asStateFlow()

    private var relay: RelayClient? = null

    // Pairing offers created here that haven't been accepted yet. Persisted per
    // identity and swept on every inbox refresh, so a remote invite still
    // completes on this side after its screen — or the app — is closed.
    private val _pendingInvites = MutableStateFlow<List<PendingInvite>>(emptyList())
    val pendingInvites: StateFlow<List<PendingInvite>> = _pendingInvites.asStateFlow()
    private val completedInviteContacts = mutableMapOf<String, Contact>()
    private var lastPendingSweep = 0L

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
            // A freshly created/imported identity finished onboarding after the
            // Activity's onResume already ran (it returned early with no store).
            // Bring messaging + push up now so it can receive without a relaunch.
            if (value && inForeground) startMessaging()
        }

    private var inForeground = false

    init {
        // Wire-level failures (envelope opens, relay refreshes) go to logcat:
        //   adb logcat -s CarrierPony
        ChatStore.diagnostics = { message, error -> android.util.Log.w("CarrierPony", message, error) }
        AttachmentStore.directory = File(appContext.filesDir, "carrierpony-attachments")
        PushService.ensureChannel(appContext)
        // A rotated FCM token re-registers with the relay immediately.
        PushService.onNewToken = { token ->
            scope.launch {
                try { relay?.registerPush(token) } catch (e: Exception) { /* next start retries */ }
            }
        }
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
                loadPendingInvites()
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
    }

    fun stopMessaging() {
        inForeground = false
        _store.value?.stop()
    }

    /** Fetch the FCM token and register it with the relay. A no-op until the
     *  Firebase project exists (google-services.json absent => Firebase never
     *  initializes and the fetch throws immediately). */
    private fun registerPush() {
        if (relay == null) return
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    scope.launch {
                        try { relay?.registerPush(token) } catch (e: Exception) { /* polling still covers delivery */ }
                    }
                }
        } catch (e: Exception) {
            // Firebase not configured on this build; polling covers delivery.
        }
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
        try {
            identityStore.save(identity)
        } catch (e: Exception) {
            _lastError.value = "identity save failed"
        }
        _identity.value = identity
        loadProfileName()
        loadPendingInvites()
        buildStore(identity)
    }

    fun signOut() {
        _identity.value?.let {
            vault.delete(it.fingerprint.hex)
            defaults.edit().remove(pendingInvitesKey(it)).apply()
        }
        _pendingInvites.value = emptyList()
        completedInviteContacts.clear()
        appLock.clearSessionPassphrase()
        onboardingComplete = false   // a signed-out device sets up fresh
        _store.value?.stop()
        _store.value?.purge()
        identityStore.delete()
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
        appLock.appLockEnabled = false
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

    /** Publish a pairing offer and return an invite to share out-of-band. The
     *  invite carries your display name (if set) so the other side sees it. */
    suspend fun createInvite(): Invite {
        val identity = _identity.value ?: throw PairingException.NotReady()
        val relay = this.relay ?: throw PairingException.NotReady()
        val response = relay.pairOffer(pubkey = identity.armoredPublicKey)
        return Invite.create(token = response.token, fingerprint = identity.fingerprint, name = _profileName.value)
    }

    /** Publish a pairing offer and remember it, so acceptance completes on this
     *  side even after the invite screen closes: the pending list is swept on
     *  every inbox refresh and at the next launch. Used by the remote invite
     *  flow; the in-person live code stays ephemeral via createInvite(), since a
     *  code nobody can scan anymore has nothing left to wait for. Returns the
     *  shareable invite plus its expiry (epoch millis, or null if the relay
     *  gave none). */
    suspend fun createRememberedInvite(): Pair<Invite, Long?> {
        val identity = _identity.value ?: throw PairingException.NotReady()
        val relay = this.relay ?: throw PairingException.NotReady()
        val response = relay.pairOffer(pubkey = identity.armoredPublicKey)
        val expiresAt: Long? =
            if (response.expiresIn > 0) System.currentTimeMillis() + response.expiresIn * 1000 else null
        _pendingInvites.value = _pendingInvites.value + PendingInvite(
            token = response.token,
            name = _profileName.value,
            createdAt = System.currentTimeMillis(),
            expiresAt = expiresAt
        )
        savePendingInvites()
        return Invite.create(response.token, identity.fingerprint, _profileName.value) to expiresAt
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

    /** Complete this (the offerer's) side of an accepted invite exactly once:
     *  add the responder, drop the pending entry, and send our profile. Keyed by
     *  token so the on-screen poll loop and the background sweep can't both act
     *  on the same acceptance. The responder's key has no out-of-band anchor for
     *  us, so this side is trust-on-first-use: confirm the key is internally
     *  consistent, add it as unverified, and let the safety number upgrade it. */
    private suspend fun finishAcceptedInvite(
        token: String,
        responderFpr: String,
        responderPubkey: String
    ): Contact? {
        completedInviteContacts[token]?.let { return it }
        val contact = PairingSupport.consistentContact(responderFpr, responderPubkey, TrustLevel.UNVERIFIED)
            ?: run { forgetPendingInvite(token); throw PairingException.KeyMismatch() }
        completedInviteContacts[token] = contact
        contactStore.add(contact)
        forgetPendingInvite(token)
        _store.value?.sendProfile(name = _profileName.value, to = contact)
        return contact
    }

    /** Check every remembered invite against the relay. Runs from the inbox
     *  refresh cycle (via ChatStore's pairingSweep hook), before messages are
     *  processed, so a new contact's first messages open on the same pass that
     *  discovers their acceptance. Throttled: pending invites can outlive a
     *  session by days, and each check costs a challenge round trip. */
    suspend fun sweepPendingInvites() {
        val identity = _identity.value ?: return
        if (relay == null || _pendingInvites.value.isEmpty()) return
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastPendingSweep < 30_000) return
        lastPendingSweep = nowMs
        for (pending in _pendingInvites.value.toList()) {
            val exp = pending.expiresAt
            if (exp != null && exp <= System.currentTimeMillis()) {
                forgetPendingInvite(pending.token)
                continue
            }
            try {
                pollInvite(pending.invite(identity.fingerprint))
            } catch (e: RelayException) {
                // The relay no longer knows the token (expired or consumed).
                if (e.status in 400..499) forgetPendingInvite(pending.token)
            } catch (e: PairingException) {
                // A mismatched key can never become valid for this token.
                forgetPendingInvite(pending.token)
            } catch (e: Exception) {
                // Transient (offline, relay 5xx): keep the invite for next sweep.
            }
        }
    }

    private fun pendingInvitesKey(identity: Identity) = "cp.pendinginvites.${identity.fingerprint.hex}"

    private fun loadPendingInvites() {
        val identity = _identity.value ?: run { _pendingInvites.value = emptyList(); return }
        val raw = defaults.getString(pendingInvitesKey(identity), null)
        _pendingInvites.value = if (raw != null) PendingInvite.listFromJson(raw) else emptyList()
    }

    private fun savePendingInvites() {
        val identity = _identity.value ?: return
        val list = _pendingInvites.value
        if (list.isEmpty()) {
            defaults.edit().remove(pendingInvitesKey(identity)).apply()
        } else {
            defaults.edit().putString(pendingInvitesKey(identity), PendingInvite.listToJson(list)).apply()
        }
    }

    private fun forgetPendingInvite(token: String) {
        if (_pendingInvites.value.none { it.token == token }) return
        _pendingInvites.value = _pendingInvites.value.filterNot { it.token == token }
        savePendingInvites()
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
            baseURL = AppConfig.relayBaseURL,
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
            pairingSweep = { sweepPendingInvites() },
            storageDir = appContext.filesDir,
            scope = scope
        )
    }

    // ── Profile ────────────────────────────────────────────────────────

    private fun profileKey(identity: Identity) = "cp.profile.${identity.fingerprint.hex}"

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
