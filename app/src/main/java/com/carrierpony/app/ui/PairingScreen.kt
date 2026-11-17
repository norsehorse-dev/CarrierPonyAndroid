// PairingScreen.kt
// CarrierPony Android
//
// The pairing flow. Two ways in:
//   In person  — stringResource(R.string.pair_my_code) shows a LIVE invite QR (a relay pairing offer),
//                so one scan pairs both phones: the scanner accepts the
//                invite, and this screen's polling completes our side the
//                moment they do. Because the fingerprint traveled physically
//                via the QR, both sides land VERIFIED. If the relay is
//                unreachable, the screen falls back to the static key QR
//                (one-way: the other phone must show you theirs too).
//   Remotely   — create an invite to send over a channel you trust, or enter
//                one you received. Remote pairs immediately but unverified;
//                the safety number upgrades it.
//
// The scanner accepts BOTH payload kinds: a live/remote invite (CPPAIR1:)
// and a static key QR (the offline fallback and older iOS builds).
//
// iOS parity note: iOS's MyCodeView still shows only the static key QR, so
// iPhone -> Android one-scan needs the same change there (My Code = offer +
// poll, scanner accepts invites).

package com.carrierpony.app.ui

import com.carrierpony.app.lock.rememberLockSafeLauncher
import com.carrierpony.app.R
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carrierpony.app.AddByKeyResult
import com.carrierpony.app.AppModel
import com.carrierpony.app.DemoMode
import com.carrierpony.app.PairingException
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.ContactRequest
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.pairing.Invite
import com.carrierpony.app.pairing.PairingCaps
import com.carrierpony.app.pairing.PairingPayload
import com.carrierpony.app.pairing.SafetyNumber
import com.carrierpony.app.ui.theme.CPTheme
import com.carrierpony.core.CPContactKey
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class PairRoute {
    data object Hub : PairRoute()
    data object MyCode : PairRoute()
    data object InviteCreate : PairRoute()
    data object InviteEnter : PairRoute()
    data object AddByKey : PairRoute()
    data class Paired(val contact: Contact, val subtitle: Int) : PairRoute()
    data class Verify(val contact: Contact) : PairRoute()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(app: AppModel, incomingKey: ByteArray? = null, onFinished: () -> Unit) {
    var route by remember {
        mutableStateOf<PairRoute>(if (incomingKey != null) PairRoute.AddByKey else PairRoute.Hub)
    }
    // A key another app handed over. It is loaded into the box once, so going
    // back and opening Add by public key again starts with an empty box.
    var pendingKey by remember(incomingKey) { mutableStateOf(incomingKey) }
    LaunchedEffect(incomingKey) {
        if (incomingKey != null) route = PairRoute.AddByKey
    }

    BackHandler {
        route = when (route) {
            is PairRoute.Hub -> { onFinished(); return@BackHandler }
            is PairRoute.Verify -> PairRoute.Hub
            else -> PairRoute.Hub
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (route) {
                            is PairRoute.Hub -> stringResource(R.string.pair_title)
                            is PairRoute.MyCode -> stringResource(R.string.pair_my_code)
                            is PairRoute.InviteCreate -> stringResource(R.string.pair_create_invite_title)
                            is PairRoute.InviteEnter -> stringResource(R.string.pair_enter_invite_title)
                            is PairRoute.AddByKey -> stringResource(R.string.pair_add_by_key_title)
                            is PairRoute.Paired -> stringResource(R.string.pair_paired)
                            is PairRoute.Verify -> stringResource(R.string.pair_verify)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (route is PairRoute.Hub) onFinished() else route = PairRoute.Hub
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val current = route) {
                is PairRoute.Hub -> PairHub(
                    app = app,
                    onPaired = { contact, subtitle -> route = PairRoute.Paired(contact, subtitle) },
                    onMyCode = { route = PairRoute.MyCode },
                    onInviteCreate = { route = PairRoute.InviteCreate },
                    onInviteEnter = { route = PairRoute.InviteEnter },
                    onAddByKey = { route = PairRoute.AddByKey }
                )
                is PairRoute.MyCode -> MyCodeView(app) { contact ->
                    route = PairRoute.Paired(contact, SCANNED_ME_SUBTITLE)
                }
                is PairRoute.InviteCreate -> InviteCreateView(app) { contact ->
                    route = PairRoute.Paired(contact, UNVERIFIED_SUBTITLE)
                }
                is PairRoute.InviteEnter -> InviteEnterView(app) { contact ->
                    route = PairRoute.Paired(contact, UNVERIFIED_SUBTITLE)
                }
                is PairRoute.AddByKey -> AddByKeyView(
                    app = app,
                    incoming = pendingKey,
                    onIncomingLoaded = { pendingKey = null }
                ) { contact ->
                    route = PairRoute.Paired(contact, REQUEST_SENT_SUBTITLE)
                }
                is PairRoute.Paired -> PairedContactView(
                    app = app,
                    contact = current.contact,
                    subtitle = current.subtitle,
                    onVerify = { route = PairRoute.Verify(current.contact) },
                    onDone = onFinished
                )
                is PairRoute.Verify -> SafetyNumberScreen(app, current.contact, onFinished)
            }
        }
    }
}

private val UNVERIFIED_SUBTITLE = R.string.pair_sub_unverified
private val VERIFIED_SUBTITLE = R.string.pair_sub_verified
private val SCANNED_ME_SUBTITLE = R.string.pair_sub_scanned_me
private val REQUEST_SENT_SUBTITLE = R.string.pair_sub_request_sent

// ── Hub ────────────────────────────────────────────────────────────────

@Composable
private fun PairHub(
    app: AppModel,
    onPaired: (Contact, Int) -> Unit,
    onMyCode: () -> Unit,
    onInviteCreate: () -> Unit,
    onInviteEnter: () -> Unit,
    onAddByKey: () -> Unit
) {
    var scanError by remember { mutableStateOf<String?>(null) }
    var accepting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val strCouldntPair = stringResource(R.string.pair_couldnt_pair)
    val strBadQr = stringResource(R.string.pair_bad_qr)
    val strPeerNeedsUpdate = stringResource(R.string.pair_peer_needs_update)
    val strScanPrompt = stringResource(R.string.pair_scan_prompt)

    val scanner = rememberLockSafeLauncher(ScanContract()) { result ->
        val code = result.contents ?: return@rememberLockSafeLauncher
        scanError = null

        // A live or remote invite (one-scan pairing).
        val invite = Invite.decode(code)
        if (invite != null) {
            accepting = true
            scope.launch {
                try {
                    // Scanned face to face: the fingerprint arrived physically,
                    // so this side lands verified.
                    val contact = app.acceptInvite(invite, trust = TrustLevel.VERIFIED)
                    onPaired(contact, VERIFIED_SUBTITLE)
                } catch (e: PairingException.PeerNeedsUpdate) {
                    scanError = strPeerNeedsUpdate
                } catch (e: PairingException) {
                    scanError = e.message
                } catch (e: Exception) {
                    scanError = strCouldntPair
                } finally {
                    accepting = false
                }
            }
            return@rememberLockSafeLauncher
        }

        // A static key QR (offline fallback; one-way — they must scan yours too).
        val payload = PairingPayload.decode(code)
        val contact = payload?.verifiedContact()
        val mine = app.identity.value?.fingerprint
        if (contact == null) {
            scanError = strBadQr
        } else if (mine != null && !PairingCaps.canPair(mine, contact.fingerprint, payload?.caps)) {
            scanError = strPeerNeedsUpdate
        } else {
            val withCaps = contact.copy(caps = payload?.caps)
            app.contactStore.add(withCaps)
            onPaired(withCaps, VERIFIED_SUBTITLE)
        }
    }

    val scanCtx = LocalContext.current
    var pendingScanPrompt by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val prompt = pendingScanPrompt
        pendingScanPrompt = null
        if (granted && prompt != null) scanner.launch(scanOptions(prompt))
    }
    // Ask for the camera before launching the scanner, so the preview starts on
    // the first run instead of only after backing out and returning.
    fun launchScan(prompt: String) {
        if (ContextCompat.checkSelfPermission(scanCtx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanner.launch(scanOptions(prompt))
        } else {
            pendingScanPrompt = prompt
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    val requests by app.contactRequests.requestsFlow.collectAsState()
    val knownContacts by app.contactStore.contactsFlow.collectAsState()
    // Someone who became a contact some other way no longer needs an answer.
    val waiting = requests.filter { request -> knownContacts.none { it.fingerprint == request.fingerprint } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        if (waiting.isNotEmpty()) {
            SectionHeader(stringResource(R.string.pair_requests_header))
            Text(
                text = stringResource(R.string.pair_request_explain),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            for (request in waiting) {
                ContactRequestRow(
                    request = request,
                    enabled = !accepting,
                    onAccept = {
                        scanError = null
                        accepting = true
                        scope.launch {
                            try {
                                onPaired(app.acceptContactRequest(request), UNVERIFIED_SUBTITLE)
                            } catch (e: PairingException.PeerNeedsUpdate) {
                                scanError = strPeerNeedsUpdate
                            } catch (e: Exception) {
                                scanError = strCouldntPair
                            } finally {
                                accepting = false
                            }
                        }
                    },
                    onDecline = { app.declineContactRequest(request) }
                )
            }
        }

        SectionHeader(stringResource(R.string.pair_in_person))
        HubRow(Icons.Default.Person, stringResource(R.string.pair_show_code), onMyCode)
        HubRow(Icons.Default.Check, stringResource(R.string.pair_scan_code)) { launchScan(strScanPrompt) }

        SectionHeader(stringResource(R.string.pair_remotely))
        HubRow(Icons.Default.Email, stringResource(R.string.pair_create_invite), onInviteCreate)
        HubRow(Icons.Default.Share, stringResource(R.string.pair_enter_invite), onInviteEnter)
        HubRow(Icons.Default.PersonAdd, stringResource(R.string.pair_add_by_key), onAddByKey)

        Text(
            text = stringResource(R.string.pair_hub_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        if (accepting) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.pair_pairing), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        scanError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
    }
}

private fun scanOptions(prompt: String): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setBeepEnabled(false)
    .setOrientationLocked(true)
    .setCaptureActivity(ScannerActivity::class.java)
    .setPrompt(prompt)

/** One waiting contact request: who is asking, the end of their fingerprint
 *  to tell two people of the same name apart, and the two answers. */
@Composable
private fun ContactRequestRow(
    request: ContactRequest,
    enabled: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    val tail = request.fingerprint.hex.takeLast(8).chunked(4).joinToString(" ")
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.pair_request_wants, request.name ?: tail),
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = stringResource(R.string.pair_request_fingerprint, tail),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onAccept, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.pair_request_accept))
            }
            OutlinedButton(onClick = onDecline, enabled = enabled, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.pair_request_decline))
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 20.dp))
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
    )
}

@Composable
private fun HubRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Icon(icon, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

/** Shown under a v6 identity's own code or invite: apps before 4.0 cannot use it. */
@Composable
private fun NeedsFourNote() {
    Text(
        text = stringResource(R.string.pair_needs_4_0_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center
    )
}

// ── My code (in person, one-scan) ──────────────────────────────────────

@Composable
private fun MyCodeView(app: AppModel, onPaired: (Contact) -> Unit) {
    val identity by app.identity.collectAsState()
    val current = identity ?: return

    // Live invite when the relay is reachable; static key QR when it isn't.
    var inviteText by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // Persist the offer (inPerson) so the offerer's side still completes from
        // the background sweep if this screen closes before the poll catches the
        // scan. Otherwise the shower silently half-pairs. Only a failure to CREATE
        // the offer means we're offline; a transient poll error (network blip, or
        // the offer already completed by the background sweep) must NOT flip the
        // live code to the static offline fallback.
        val created = try {
            app.createRememberedInvite(inPerson = true).first
        } catch (e: Exception) {
            offline = true
            return@LaunchedEffect
        }
        inviteText = created.encoded()
        while (isActive) {
            delay(3000)
            try {
                val contact = app.pollInvite(created)
                if (contact != null) {
                    onPaired(contact)
                    break
                }
            } catch (e: Exception) {
                // keep the live code up and retry on the next tick
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val text = inviteText
        val qr = remember(text, offline) {
            when {
                text != null -> QRCode.make(text)
                offline -> QRCode.make(
                    PairingPayload.create(
                        fingerprint = current.fingerprint,
                        name = null,
                        armoredPublicKey = current.armoredPublicKey
                    ).encoded()
                )
                else -> null
            }
        }

        if (qr != null) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Image(
                    bitmap = qr.asImageBitmap(),
                    contentDescription = stringResource(R.string.pair_your_code_cd),
                    modifier = Modifier.size(260.dp).padding(16.dp)
                )
            }
        } else {
            Spacer(Modifier.height(40.dp))
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.pair_preparing), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.pair_your_fingerprint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            text = current.fingerprint.hex.chunked(4).joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))

        if (current.fingerprint.isV6) {
            NeedsFourNote()
            Spacer(Modifier.height(12.dp))
        }

        MyKeyButtons(current.armoredPublicKey, current.fingerprint.hex)
        Spacer(Modifier.height(16.dp))

        when {
            text != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.pair_one_scan_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            offline -> {
                Text(
                    text = stringResource(R.string.pair_offline_code),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/** The file a shared public key is saved under: the name on the key, or the
 *  end of its fingerprint when the key has no name. Only letters, digits,
 *  spaces, dashes and underscores are kept, so it is safe as a file name. */
internal fun publicKeyFileName(armoredPublicKey: String, fingerprintHex: String): String {
    val name = com.carrierpony.core.CPKeyInfo.userIDName(armoredPublicKey.toByteArray(Charsets.UTF_8))
        ?.filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
        ?.trim()
        ?.take(40)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return (name ?: "carrierpony-${fingerprintHex.takeLast(8)}") + ".asc"
}

/** Copy or share this identity's public key as text, for someone who will
 *  add it with Add by public key. */
@Composable
private fun MyKeyButtons(armoredPublicKey: String, fingerprintHex: String) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val strCopy = stringResource(R.string.pair_copy_key)
    val strShare = stringResource(R.string.pair_share_key)
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("CarrierPony", armoredPublicKey))) }
                copied = true
            },
            modifier = Modifier.weight(1f)
        ) { Text(if (copied) stringResource(R.string.common_copied) else strCopy, textAlign = TextAlign.Center) }
        OutlinedButton(
            onClick = {
                // Share a real file, not EXTRA_TEXT: file-oriented targets
                // (Save to Files, Drive) refuse plain text. Copy covers pasting.
                try {
                    val file = java.io.File(context.cacheDir, publicKeyFileName(armoredPublicKey, fingerprintHex))
                    file.writeText(armoredPublicKey)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "com.carrierpony.app.fileprovider", file
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    com.carrierpony.app.lock.startActivityHoldingLock(context, Intent.createChooser(intent, strShare))
                } catch (e: Exception) {
                    // No share target or the cache is unwritable: Copy still works.
                }
            },
            modifier = Modifier.weight(1f)
        ) { Text(strShare, textAlign = TextAlign.Center) }
    }
}

// ── Create invite (remote) ─────────────────────────────────────────────

@Composable
private fun InviteCreateView(app: AppModel, onPaired: (Contact) -> Unit) {
    val identity by app.identity.collectAsState()
    var inviteText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val copyScope = rememberCoroutineScope()
    val strCouldntReach = stringResource(R.string.pair_couldnt_reach)
    val strShareInvite = stringResource(R.string.pair_share_invite)

    LaunchedEffect(Unit) {
        val created = try {
            app.createRememberedInvite(inPerson = false).first
        } catch (e: PairingException) {
            error = e.message
            return@LaunchedEffect
        } catch (e: Exception) {
            error = strCouldntReach
            return@LaunchedEffect
        }
        inviteText = created.encoded()
        while (isActive) {
            delay(3000)
            try {
                val contact = app.pollInvite(created)
                if (contact != null) {
                    onPaired(contact)
                    break
                }
            } catch (e: Exception) {
                // transient poll failure (or completed by the background sweep);
                // keep the invite up and retry on the next tick
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val text = inviteText
        when {
            error != null -> {
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 30.dp)
                )
            }
            text == null -> {
                Spacer(Modifier.height(40.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.pair_creating_invite), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                val qr = remember(text) { QRCode.make(text) }
                if (qr != null) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = stringResource(R.string.pair_invite_cd),
                            modifier = Modifier.size(220.dp).padding(14.dp)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Text(
                    text = stringResource(R.string.pair_send_invite_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                if (identity?.fingerprint?.isV6 == true) {
                    NeedsFourNote()
                    Spacer(Modifier.height(12.dp))
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 5,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            copyScope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("CarrierPony", text))) }
                            copied = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (copied) stringResource(R.string.common_copied) else stringResource(R.string.common_copy)) }
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            com.carrierpony.app.lock.startActivityHoldingLock(context, Intent.createChooser(intent, strShareInvite))
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.common_share)) }
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pair_waiting_accept), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Enter invite (remote) ──────────────────────────────────────────────

@Composable
private fun InviteEnterView(app: AppModel, onPaired: (Contact) -> Unit) {
    var text by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val strDemoNeedsIdentity = stringResource(R.string.pair_demo_needs_identity)
    val strCouldntPairInvite = stringResource(R.string.pair_couldnt_pair_invite)
    val strPeerNeedsUpdate = stringResource(R.string.pair_peer_needs_update)
    val strScanPromptEnter = stringResource(R.string.pair_scan_prompt)

    val scanner = rememberLockSafeLauncher(ScanContract()) { result ->
        result.contents?.let { text = it }
    }

    val scanCtx = LocalContext.current
    var pendingScanPrompt by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val prompt = pendingScanPrompt
        pendingScanPrompt = null
        if (granted && prompt != null) scanner.launch(scanOptions(prompt))
    }
    // Ask for the camera before launching the scanner, so the preview starts on
    // the first run instead of only after backing out and returning.
    fun launchScan(prompt: String) {
        if (ContextCompat.checkSelfPermission(scanCtx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            scanner.launch(scanOptions(prompt))
        } else {
            pendingScanPrompt = prompt
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    suspend fun accept() {
        error = null
        working = true
        try {
            if (DemoMode.matches(text)) {
                val contact = app.startDemo()
                if (contact != null) {
                    onPaired(contact)
                } else {
                    error = strDemoNeedsIdentity
                }
                return
            }
            val invite = Invite.decode(text)
            if (invite == null) {
                error = PairingException.MalformedInvite().message
                return
            }
            try {
                onPaired(app.acceptInvite(invite))
            } catch (e: PairingException.PeerNeedsUpdate) {
                error = strPeerNeedsUpdate
            } catch (e: PairingException) {
                error = e.message
            } catch (e: Exception) {
                error = strCouldntPairInvite
            }
        } finally {
            working = false
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(
            stringResource(R.string.pair_paste_or_scan),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("CPPAIR1:…", fontFamily = FontFamily.Monospace) },
            minLines = 4,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = {
                scope.launch { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let { text = it } }
            }) { Text(stringResource(R.string.common_paste)) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { text = ""; error = null },
                enabled = text.isNotEmpty()
            ) { Text(stringResource(R.string.common_clear)) }
        }
        Spacer(Modifier.height(16.dp))
        val canPair = text.trim().isNotEmpty() && !working
        Button(
            onClick = { scope.launch { accept() } },
            enabled = canPair,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text(stringResource(R.string.pair_title), fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { launchScan(strScanPromptEnter) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.pair_scan_instead)) }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ── Add by public key ──────────────────────────────────────────────────

@Composable
private fun AddByKeyView(
    app: AppModel,
    incoming: ByteArray? = null,
    onIncomingLoaded: () -> Unit = {},
    onAdded: (Contact) -> Unit
) {
    val identity by app.identity.collectAsState()
    var text by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val strSecret = stringResource(R.string.pair_key_err_secret)
    val strNotKey = stringResource(R.string.pair_key_err_not_key)
    val strUnsupported = stringResource(R.string.pair_key_err_unsupported)
    val strOwn = stringResource(R.string.pair_key_err_own)
    val strNotOnRelay = stringResource(R.string.pair_key_err_not_on_relay)
    val strSend = stringResource(R.string.pair_key_err_send)
    val strFile = stringResource(R.string.pair_key_err_file)
    val strNotReady = stringResource(R.string.pair_couldnt_pair)

    suspend fun add(input: ByteArray) {
        error = null
        working = true
        try {
            when (val result = app.addContactByKey(input)) {
                is AddByKeyResult.Added -> onAdded(result.contact)
                is AddByKeyResult.AlreadyContact -> error = context.getString(
                    R.string.pair_key_err_already,
                    result.contact.displayName ?: result.contact.fingerprint.hex.takeLast(8)
                )
                AddByKeyResult.SecretKey -> { text = ""; error = strSecret }
                AddByKeyResult.NotAKey -> error = strNotKey
                AddByKeyResult.Unsupported -> error = strUnsupported
                AddByKeyResult.OwnKey -> error = strOwn
                AddByKeyResult.NotOnRelay -> error = strNotOnRelay
                AddByKeyResult.CouldNotSend -> error = strSend
                AddByKeyResult.NotReady -> error = strNotReady
            }
        } finally {
            working = false
        }
    }

    // A key file can be armored text or binary, so it is handed over as bytes
    // and not shown in the box.
    val filePicker = rememberLockSafeLauncher(ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLockSafeLauncher
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { IncomingKey.readCapped(it) }
        } catch (e: Exception) {
            null
        }
        if (bytes == null) {
            error = strFile
        } else {
            scope.launch { add(bytes) }
        }
    }

    // A key from another app is checked and shown in the box. Adding it is
    // still the person's own tap.
    LaunchedEffect(incoming) {
        val bytes = incoming ?: return@LaunchedEffect
        val result = if (bytes.isEmpty()) null else withContext(Dispatchers.Default) { CPContactKey.inspect(bytes) }
        when (result) {
            null -> { text = ""; error = strFile }
            is CPContactKey.Result.Usable -> { text = result.armoredPublicKey; error = null }
            CPContactKey.Result.SecretKey -> { text = ""; error = strSecret }
            CPContactKey.Result.NotAKey -> { text = ""; error = strNotKey }
            CPContactKey.Result.Unsupported -> { text = ""; error = strUnsupported }
        }
        onIncomingLoaded()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text(
            stringResource(R.string.pair_key_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (identity?.fingerprint?.isV6 == true) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.pair_key_needs_4_0_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            placeholder = { Text("-----BEGIN PGP PUBLIC KEY BLOCK-----", fontFamily = FontFamily.Monospace) },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            minLines = 5,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row {
            OutlinedButton(onClick = {
                scope.launch {
                    clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let {
                        text = it
                        error = null
                    }
                }
            }) { Text(stringResource(R.string.common_paste)) }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                enabled = !working
            ) { Text(stringResource(R.string.pair_key_open_file)) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { text = ""; error = null },
                enabled = text.isNotEmpty()
            ) { Text(stringResource(R.string.common_clear)) }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { scope.launch { add(text.toByteArray(Charsets.UTF_8)) } },
            enabled = text.trim().isNotEmpty() && !working,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text(stringResource(R.string.pair_key_add), fontWeight = FontWeight.SemiBold)
            }
        }

        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ── Paired + verify ────────────────────────────────────────────────────

@Composable
private fun PairedContactView(
    app: AppModel,
    contact: Contact,
    subtitle: Int,
    onVerify: () -> Unit,
    onDone: () -> Unit
) {
    var nickname by remember { mutableStateOf(contact.displayName ?: "") }

    // Live trust: an in-person scan already landed VERIFIED on both sides, so
    // offering the safety number there is redundant (and the other phone has
    // no screen to show it). It stays for remote invites, where relay TOFU is
    // exactly what the number exists to check.
    val contacts by app.contactStore.contactsFlow.collectAsState()
    val verified = contacts.firstOrNull { it.fingerprint == contact.fingerprint }?.trust == TrustLevel.VERIFIED

    fun commit() {
        app.contactStore.setNickname(contact.fingerprint, nickname)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            tint = CPTheme.accent,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.pair_paired_with, contact.displayName ?: UiFormat.shortFingerprint(contact.fingerprint)),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text(stringResource(R.string.pair_name_contact)) },
            supportingText = { Text(stringResource(R.string.pair_name_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        if (!verified) {
            Button(
                onClick = { commit(); onVerify() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.pair_verify_button), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { commit(); onDone() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.common_done)) }
        } else {
            Button(
                onClick = { commit(); onDone() },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.common_done), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun SafetyNumberScreen(app: AppModel, contact: Contact, onFinished: () -> Unit) {
    val identity by app.identity.collectAsState()
    val contacts by app.contactStore.contactsFlow.collectAsState()
    val liveTrust = contacts.firstOrNull { it.fingerprint == contact.fingerprint }?.trust ?: contact.trust

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.safety_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.safety_compare, contact.displayName ?: stringResource(R.string.safety_your_contact)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(18.dp))
        identity?.let { me ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = SafetyNumber.grouped(me.fingerprint, contact.fingerprint),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(18.dp).fillMaxWidth()
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        if (liveTrust == TrustLevel.VERIFIED) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CPTheme.accent)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.common_verified), fontWeight = FontWeight.SemiBold, color = CPTheme.accent)
            }
        } else {
            Button(
                onClick = {
                    app.contactStore.markVerified(contact.fingerprint)
                    onFinished()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.safety_mark), fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.safety_only_after),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
