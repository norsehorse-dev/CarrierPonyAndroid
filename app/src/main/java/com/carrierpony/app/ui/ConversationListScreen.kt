// ConversationListScreen.kt
// CarrierPony Android
//
// The home screen, ported from iOS Core/UI/ConversationListView.swift: every
// conversation, most-recent first, tapping into the thread. Contacts are
// supplied by the app layer (same source the ChatStore uses). Long-press on a
// row offers Unpair (with the same confirmation copy as iOS); iOS uses swipe
// actions for the same operations. Optional callbacks hide their
// affordances when null, exactly like the iOS optional closures.

package com.carrierpony.app.ui

import com.carrierpony.app.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.ChatStore
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.Conversation
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.messaging.TrustLevel
import com.carrierpony.app.ui.theme.CPTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    store: ChatStore,
    contacts: List<Contact>,
    onOpen: (Fingerprint) -> Unit,
    onUnpair: ((Fingerprint) -> Unit)? = null,
    onReport: ((Fingerprint) -> Unit)? = null,
    onPair: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    notificationsOff: Boolean = false,
    onEnableNotifications: () -> Unit = {},
    onDismissNotificationsHint: () -> Unit = {}
) {
    val conversations by store.conversations.collectAsState()
    val ordered = conversations.values.sortedByDescending { it.lastMessage?.sentAt ?: 0 }
    val error by store.lastError.collectAsState()

    var showingNewMessage by remember { mutableStateOf(false) }
    var pendingUnpair by remember { mutableStateOf<Fingerprint?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    if (onSettings != null) {
                        IconButton(onClick = onSettings) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.common_settings), tint = CPTheme.accent)
                        }
                    }
                },
                actions = {
                    if (onPair != null) {
                        IconButton(onClick = onPair) {
                            Icon(Icons.Default.Person, contentDescription = stringResource(R.string.common_pair), tint = CPTheme.accent)
                        }
                    }
                    IconButton(onClick = { showingNewMessage = true }) {
                        Icon(Icons.Default.Create, contentDescription = stringResource(R.string.inbox_new_message_cd), tint = CPTheme.accent)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            error?.let { msg ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { store.clearError() }) { Text(stringResource(R.string.common_dismiss)) }
                    }
                }
            }
            if (notificationsOff) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.inbox_notifications_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onEnableNotifications) { Text(stringResource(R.string.common_enable)) }
                        TextButton(onClick = onDismissNotificationsHint) { Text(stringResource(R.string.common_dismiss)) }
                    }
                }
            }
            Box(Modifier.fillMaxSize()) {
                if (ordered.isEmpty()) {
                    EmptyInbox(onCompose = { showingNewMessage = true })
                } else {
                    LazyColumn {
                        items(ordered, key = { it.threadID }) { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                displayName = displayName(conversation, contacts),
                                verified = contacts.firstOrNull { it.fingerprint == conversation.peer }?.trust == TrustLevel.VERIFIED,
                                onClick = { onOpen(conversation.peer) },
                                onUnpair = onUnpair?.let { { pendingUnpair = conversation.peer } },
                                onReport = onReport?.let { r -> { r(conversation.peer) } }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showingNewMessage) {
        ModalBottomSheet(onDismissRequest = { showingNewMessage = false }) {
            NewMessageSheet(contacts = contacts) { contact ->
                showingNewMessage = false
                onOpen(contact.fingerprint)
            }
        }
    }

    pendingUnpair?.let { fingerprint ->
        AlertDialog(
            onDismissRequest = { pendingUnpair = null },
            title = { Text(stringResource(R.string.inbox_unpair_q)) },
            text = { Text(stringResource(R.string.inbox_unpair_body)) },
            confirmButton = {
                TextButton(onClick = {
                    onUnpair?.invoke(fingerprint)
                    pendingUnpair = null
                }) { Text(stringResource(R.string.inbox_unpair), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnpair = null }) { Text("Cancel") }
            }
        )
    }
}

private fun displayName(conversation: Conversation, contacts: List<Contact>): String {
    val contact = contacts.firstOrNull { it.fingerprint == conversation.peer }
    return contact?.displayName
        ?: conversation.peerName
        ?: UiFormat.shortFingerprint(conversation.peer)
}

// ── Row ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: Conversation,
    displayName: String,
    verified: Boolean,
    onClick: () -> Unit,
    onUnpair: (() -> Unit)?,
    onReport: (() -> Unit)? = null
) {
    var showingMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (onUnpair != null || onReport != null) showingMenu = true }
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Avatar(name = displayName, size = 46.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = stringResource(R.string.common_verified),
                            tint = CPTheme.accent,
                            modifier = Modifier.height(14.dp)
                        )
                    }
                }
                Text(
                    text = preview(conversation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = conversation.lastMessage?.let { listTimeLabelText(it.sentAt) } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                if (conversation.unreadCount > 0) {
                    Surface(color = CPTheme.accent, shape = CircleShape) {
                        Text(
                            text = conversation.unreadCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.height(18.dp))
                }
            }
        }

        DropdownMenu(expanded = showingMenu, onDismissRequest = { showingMenu = false }) {
            if (onReport != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.inbox_report)) },
                    onClick = {
                        showingMenu = false
                        onReport()
                    }
                )
            }
            if (onUnpair != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.inbox_unpair), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showingMenu = false
                        onUnpair.invoke()
                    }
                )
            }
        }
    }
}

@Composable
private fun preview(conversation: Conversation): String {
    val last = conversation.lastMessage ?: return stringResource(R.string.inbox_no_messages)
    val prefix = if (last.direction == MessageDirection.OUTGOING) stringResource(R.string.inbox_you_prefix) else ""
    last.text?.takeIf { it.isNotEmpty() }?.let { return prefix + it }
    last.attachments.firstOrNull()?.let { return prefix + "\uD83D\uDCCE " + it.filename }
    return prefix + stringResource(R.string.inbox_attachment_fallback)
}

// ── New message sheet ──────────────────────────────────────────────────

@Composable
private fun NewMessageSheet(contacts: List<Contact>, onSelect: (Contact) -> Unit) {
    Column(Modifier.padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.inbox_new_message),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        if (contacts.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.inbox_no_contacts), fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.inbox_pair_to_start),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // displayName resolves nickname first, then the shared name — the
            // same resolution the inbox uses, so the two never disagree.
            val sorted = contacts.sortedBy { (it.displayName ?: it.fingerprint.hex).lowercase() }
            LazyColumn {
                items(sorted, key = { it.fingerprint.hex }) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(contact) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(name = contact.displayName, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = contact.displayName ?: UiFormat.shortFingerprint(contact.fingerprint),
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = UiFormat.shortFingerprint(contact.fingerprint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Empty state ────────────────────────────────────────────────────────

@Composable
private fun EmptyInbox(onCompose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .height(72.dp)
                .width(72.dp)
                .clip(CircleShape)
                .background(CPTheme.brand),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Email,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.height(34.dp).width(34.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.inbox_no_conversations), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.inbox_threads_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onCompose) {
            Text(stringResource(R.string.inbox_new_message), fontWeight = FontWeight.SemiBold)
        }
    }
}
