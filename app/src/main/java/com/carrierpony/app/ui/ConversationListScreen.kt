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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import com.carrierpony.app.AppModel
import com.carrierpony.app.crypto.Fingerprint
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.carrierpony.app.messaging.ChatGroup
import com.carrierpony.app.messaging.ChatMessage
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
    onOpenGroup: (String) -> Unit = {},
    onUnpair: ((Fingerprint) -> Unit)? = null,
    onPair: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    accounts: List<AppModel.AccountSummary> = emptyList(),
    activeFingerprintHex: String? = null,
    onSwitchAccount: (String) -> Unit = {},
    onAddAccount: () -> Unit = {},
    accountUnread: Map<String, Int> = emptyMap(),
    notificationsOff: Boolean = false,
    onEnableNotifications: () -> Unit = {},
    onDismissNotificationsHint: () -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    val conversations by store.conversations.collectAsState()
    val groupsMap by store.groups.collectAsState()
    val groupThreads by store.groupMessages.collectAsState()
    // One inbox, sorted by most-recent activity: groups and 1:1 threads share a
    // single list instead of groups pinning to the top. A group rises only when
    // it holds the newest message.
    val inboxItems = remember(conversations, groupsMap, groupThreads) {
        val convItems = conversations.values.map { c ->
            InboxItem.Conv(c, c.lastMessage?.sentAt ?: 0L)
        }
        val groupItems = groupsMap.values.map { g ->
            // Sort by the newest message, or by when we created/joined when it has no
            // messages yet, so a freshly made channel appears at the top instead of the bottom.
            val latest = groupThreads[g.groupID]?.maxOfOrNull { m -> m.sentAt } ?: 0L
            InboxItem.Grp(g, maxOf(latest, g.createdAt))
        }
        (convItems + groupItems).sortedWith(
            compareByDescending<InboxItem> { it.activity }.thenBy { it.sortName.lowercase() }
        )
    }

    var showingNewMessage by remember { mutableStateOf(false) }
    var showingNewGroup by remember { mutableStateOf(false) }
    var showingNewChannel by remember { mutableStateOf(false) }
    var showingSubscribe by remember { mutableStateOf(false) }
    var showingComposeMenu by remember { mutableStateOf(false) }
    var pendingUnpair by remember { mutableStateOf<Fingerprint?>(null) }
    var showingAccounts by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (accounts.size > 1) {
                        val active = accounts.firstOrNull { it.fingerprint.hex == activeFingerprintHex }
                        val label = active?.let { it.name ?: UiFormat.shortFingerprint(it.fingerprint) }
                            ?: stringResource(R.string.app_name)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showingAccounts = true }
                        ) {
                            Text(label, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.accounts_switch_cd), tint = CPTheme.accent)
                            if (accounts.any { it.fingerprint.hex != activeFingerprintHex && (accountUnread[it.fingerprint.hex] ?: 0) > 0 }) {
                                Spacer(Modifier.width(4.dp))
                                Box(Modifier.size(8.dp).clip(CircleShape).background(CPTheme.accent))
                            }
                        }
                    } else {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                    }
                },
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
                    Box {
                        IconButton(onClick = { showingComposeMenu = true }) {
                            Icon(Icons.Default.Create, contentDescription = stringResource(R.string.inbox_new_message_cd), tint = CPTheme.accent)
                        }
                        DropdownMenu(expanded = showingComposeMenu, onDismissRequest = { showingComposeMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.group_new_message)) }, onClick = {
                                showingComposeMenu = false; showingNewMessage = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.group_new)) }, onClick = {
                                showingComposeMenu = false; showingNewGroup = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.channel_new)) }, onClick = {
                                showingComposeMenu = false; showingNewChannel = true
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.channel_subscribe)) }, onClick = {
                                showingComposeMenu = false; showingSubscribe = true
                            })
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
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
                if (inboxItems.isEmpty()) {
                    EmptyInbox(onCompose = { showingNewMessage = true })
                } else {
                    LazyColumn {
                        items(inboxItems, key = { it.key }) { item ->
                            when (item) {
                                is InboxItem.Grp -> {
                                    val gmsgs = groupThreads[item.group.groupID] ?: emptyList()
                                    val unread = gmsgs.count { it.direction == MessageDirection.INCOMING && !it.isRead }
                                    val last = gmsgs.maxByOrNull { it.sentAt }
                                    GroupListRow(group = item.group, unread = unread, last = last, onClick = { onOpenGroup(item.group.groupID) })
                                }
                                is InboxItem.Conv -> {
                                    val conversation = item.conversation
                                    ConversationRow(
                                        conversation = conversation,
                                        displayName = displayName(conversation, contacts),
                                        verified = contacts.firstOrNull { it.fingerprint == conversation.peer }?.trust == TrustLevel.VERIFIED,
                                        onClick = { onOpen(conversation.peer) },
                                        onUnpair = onUnpair?.let { { pendingUnpair = conversation.peer } },
                                        onDelete = { scope.launch { store.deleteConversation(conversation.threadID) } }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showingAccounts) {
        ModalBottomSheet(onDismissRequest = { showingAccounts = false }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = stringResource(R.string.accounts_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                for (acct in accounts) {
                    val hex = acct.fingerprint.hex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showingAccounts = false; if (hex != activeFingerprintHex) onSwitchAccount(hex) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(name = acct.name, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(acct.name ?: UiFormat.shortFingerprint(acct.fingerprint), fontWeight = FontWeight.SemiBold)
                            Text(
                                UiFormat.shortFingerprint(acct.fingerprint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (hex == activeFingerprintHex) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.accounts_active), tint = CPTheme.accent)
                        } else if ((accountUnread[hex] ?: 0) > 0) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(CPTheme.accent))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(start = 20.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showingAccounts = false; onAddAccount() }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = CPTheme.accent)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.accounts_add), color = CPTheme.accent)
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

    if (showingNewGroup) {
        NewGroupSheet(
            contacts = contacts,
            onDismiss = { showingNewGroup = false },
            onCreate = { name, members ->
                showingNewGroup = false
                scope.launch { store.createGroup(name, members) }
            }
        )
    }

    if (showingSubscribe) {
        var pasted by remember { mutableStateOf("") }
        var bad by remember { mutableStateOf(false) }
        val clip = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { showingSubscribe = false },
            title = { Text(stringResource(R.string.channel_subscribe)) },
            text = {
                Column {
                    Text(stringResource(R.string.channel_subscribe_confirm_body))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it; bad = false },
                        placeholder = { Text(stringResource(R.string.channel_subscribe_paste)) },
                        trailingIcon = {
                            TextButton(onClick = {
                                clip.getText()?.text?.let { pasted = it; bad = false }
                            }) { Text(stringResource(R.string.common_paste)) }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (bad) Text(stringResource(R.string.channel_subscribe_bad),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val invite = com.carrierpony.app.pairing.ChannelInvite.decode(pasted.trim())
                    if (invite == null) bad = true else {
                        showingSubscribe = false
                        scope.launch { store.subscribeToChannel(invite) }
                    }
                }) { Text(stringResource(R.string.channel_subscribe)) }
            },
            dismissButton = { TextButton(onClick = { showingSubscribe = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (showingNewChannel) {
        NewGroupSheet(
            contacts = contacts,
            channel = true,
            onDismiss = { showingNewChannel = false },
            onCreate = { name, members ->
                showingNewChannel = false
                scope.launch { store.createChannel(name, members) }
            }
        )
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

// A single inbox row: a 1:1 conversation or a group, tagged with the last
// activity time so the list interleaves both and sorts by recency. Groups no
// longer pin to the top; a group rises only when it has the newest message.
private sealed interface InboxItem {
    val activity: Long
    val key: String
    val sortName: String

    data class Conv(val conversation: Conversation, override val activity: Long) : InboxItem {
        override val key get() = "c-" + conversation.threadID
        override val sortName get() = conversation.threadID
    }

    data class Grp(val group: ChatGroup, override val activity: Long) : InboxItem {
        override val key get() = "g-" + group.groupID
        override val sortName get() = group.name
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
private fun GroupListRow(group: ChatGroup, unread: Int, last: ChatMessage?, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name = group.name, size = 46.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                group.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val lastText = last?.text
            val subtitle = when {
                !lastText.isNullOrEmpty() -> lastText
                last != null && last.attachments.isNotEmpty() -> "\uD83D\uDCCE " + last.attachments.first().filename
                group.members.size == 1 -> stringResource(R.string.group_member_one)
                else -> stringResource(R.string.group_member_many, group.members.size)
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = last?.let { listTimeLabelText(it.sentAt) } ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            if (unread > 0) {
                Surface(color = CPTheme.accent, shape = CircleShape) {
                    Text(
                        text = unread.toString(),
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
}

@Composable
private fun ConversationRow(
    conversation: Conversation,
    displayName: String,
    verified: Boolean,
    onClick: () -> Unit,
    onUnpair: (() -> Unit)?,
    onDelete: (() -> Unit)? = null
) {
    var showingMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { if (onUnpair != null || onDelete != null) showingMenu = true }
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
            if (onDelete != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.inbox_delete_conversation)) },
                    onClick = {
                        showingMenu = false
                        onDelete()
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
