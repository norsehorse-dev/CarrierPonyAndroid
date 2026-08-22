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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carrierpony.app.AppModel
import com.carrierpony.app.DemoMode
import com.carrierpony.app.PairingException
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.pairing.Invite
import com.carrierpony.app.pairing.PairingPayload
import com.carrierpony.app.pairing.SafetyNumber
import com.carrierpony.app.ui.theme.CPTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private sealed class PairRoute {
    data object Hub : PairRoute()
    data object MyCode : PairRoute()
    data object InviteCreate : PairRoute()
    data object InviteEnter : PairRoute()
    data class Paired(val contact: Contact, val subtitle: Int) : PairRoute()
    data class Verify(val contact: Contact) : PairRoute()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(app: AppModel, onFinished: () -> Unit) {
    var route by remember { mutableStateOf<PairRoute>(PairRoute.Hub) }

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
                    onInviteEnter = { route = PairRoute.InviteEnter }
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

// ── Hub ────────────────────────────────────────────────────────────────

@Composable
private fun PairHub(
    app: AppModel,
    onPaired: (Contact, Int) -> Unit,
    onMyCode: () -> Unit,
    onInviteCreate: () -> Unit,
    onInviteEnter: () -> Unit
) {
    var scanError by remember { mutableStateOf<String?>(null) }
    var accepting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val strCouldntPair = stringResource(R.string.pair_couldnt_pair)
    val strBadQr = stringResource(R.string.pair_bad_qr)
    val strScanPrompt = stringResource(R.string.pair_scan_prompt)

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val code = result.contents ?: return@rememberLauncherForActivityResult
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
                } catch (e: PairingException) {
                    scanError = e.message
                } catch (e: Exception) {
                    scanError = strCouldntPair
                } finally {
                    accepting = false
                }
            }
            return@rememberLauncherForActivityResult
        }

        // A static key QR (offline fallback; one-way — they must scan yours too).
        val contact = PairingPayload.decode(code)?.verifiedContact()
        if (contact != null) {
            app.contactStore.add(contact)
            onPaired(contact, VERIFIED_SUBTITLE)
        } else {
            scanError = strBadQr
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SectionHeader(stringResource(R.string.pair_in_person))
        HubRow(Icons.Default.Person, stringResource(R.string.pair_show_code), onMyCode)
        HubRow(Icons.Default.Check, stringResource(R.string.pair_scan_code)) { scanner.launch(scanOptions(strScanPrompt)) }

        SectionHeader(stringResource(R.string.pair_remotely))
        HubRow(Icons.Default.Email, stringResource(R.string.pair_create_invite), onInviteCreate)
        HubRow(Icons.Default.Share, stringResource(R.string.pair_enter_invite), onInviteEnter)

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
    .setPrompt(prompt)

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

// ── My code (in person, one-scan) ──────────────────────────────────────

@Composable
private fun MyCodeView(app: AppModel, onPaired: (Contact) -> Unit) {
    val identity by app.identity.collectAsState()
    val current = identity ?: return

    // Live invite when the relay is reachable; static key QR when it isn't.
    var inviteText by remember { mutableStateOf<String?>(null) }
    var offline by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val created: Invite = app.createInvite()
            inviteText = created.encoded()
            while (isActive) {
                delay(3000)
                val contact = app.pollInvite(created)
                if (contact != null) {
                    // They scanned in person — mark them verified on our side too.
                    app.contactStore.markVerified(contact.fingerprint)
                    onPaired(contact)
                    break
                }
            }
        } catch (e: Exception) {
            offline = true
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

// ── Create invite (remote) ─────────────────────────────────────────────

@Composable
private fun InviteCreateView(app: AppModel, onPaired: (Contact) -> Unit) {
    var inviteText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val copyScope = rememberCoroutineScope()
    val strCouldntReach = stringResource(R.string.pair_couldnt_reach)
    val strShareInvite = stringResource(R.string.pair_share_invite)

    LaunchedEffect(Unit) {
        try {
            val created: Invite = app.createInvite()
            inviteText = created.encoded()
            while (isActive) {
                delay(3000)
                val contact = app.pollInvite(created)
                if (contact != null) {
                    onPaired(contact)
                    break
                }
            }
        } catch (e: PairingException) {
            error = e.message
        } catch (e: Exception) {
            error = strCouldntReach
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
                            context.startActivity(Intent.createChooser(intent, strShareInvite))
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
    val strScanPromptEnter = stringResource(R.string.pair_scan_prompt)

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { text = it }
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
            onClick = { scanner.launch(scanOptions(strScanPromptEnter)) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.pair_scan_instead)) }

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
