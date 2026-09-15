// GroupScreens.kt
// CarrierPony Android
//
// The group UI, the Android counterpart of iOS Core/UI/GroupViews.swift:
// create a group, message it (text + attachments), view the roster, and — as an
// admin — rename, add and remove members, or leave. Group crypto and fan-out
// live in ChatStore; these screens are the presentation only.

package com.carrierpony.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carrierpony.app.R
import com.carrierpony.app.messaging.ChatMessage
import com.carrierpony.app.messaging.ChatStore
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.messaging.OutgoingMessage
import com.carrierpony.app.ui.theme.CPTheme
import kotlinx.coroutines.launch

// ── New group ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewGroupSheet(contacts: List<Contact>, onDismiss: () -> Unit, onCreate: (String, List<Contact>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val sorted = remember(contacts) { contacts.sortedBy { (it.displayName ?: it.fingerprint.hex).lowercase() } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp).fillMaxWidth().imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Create stays in the header so it is always visible without scrolling,
            // even with a long contact list or the keyboard up.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.group_new), style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                TextButton(
                    enabled = name.isNotBlank() && selected.isNotEmpty(),
                    onClick = { onCreate(name.trim(), sorted.filter { it.fingerprint.hex in selected }) }
                ) { Text(stringResource(R.string.group_create)) }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.group_name)) },
                modifier = Modifier.fillMaxWidth()
            )
            if (sorted.isEmpty()) {
                Text(stringResource(R.string.group_need_contacts), color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(stringResource(R.string.group_members), style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(sorted.size, key = { sorted[it].fingerprint.hex }) { i ->
                        val c = sorted[i]
                        val checked = c.fingerprint.hex in selected
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = {
                                selected = if (checked) selected - c.fingerprint.hex else selected + c.fingerprint.hex
                            })
                            Text(c.displayName ?: UiFormat.shortFingerprint(c.fingerprint))
                        }
                    }
                }
            }
        }
    }
}

// ── Group conversation ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupConversationScreen(store: ChatStore, groupID: String, contacts: List<Contact>, onBack: () -> Unit, onOpenDetail: () -> Unit) {
    val groups by store.groups.collectAsState()
    val group = groups[groupID]
    val threads by store.groupMessages.collectAsState()
    val messages = remember(threads, groupID) { (threads[groupID] ?: emptyList()).sortedBy { it.sentAt } }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<OutgoingMessage.Attachment>>(emptyList()) }

    // Group vanished (left/deleted): pop back out.
    LaunchedEffect(group == null) { if (group == null) onBack() }
    if (group == null) return

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readAttachment(context, it)?.let { a -> pending = pending + a } }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { readAttachment(context, it)?.let { a -> pending = pending + a } }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LaunchedEffect(groupID, messages.size) { store.markGroupRead(groupID) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.name, maxLines = 1, fontWeight = FontWeight.SemiBold)
                        Text(
                            memberCountLabel(group.members.size),
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDetail) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.group_details), tint = CPTheme.accent)
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.group_empty), color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                        items(messages.size, key = { messages[it].id }) { i ->
                            val m = messages[i]
                            val senderName = if (m.direction == MessageDirection.OUTGOING) null
                                else contacts.firstOrNull { it.fingerprint == m.peer }?.displayName
                                    ?: group.member(m.peer)?.name
                                    ?: UiFormat.shortFingerprint(m.peer)
                            GroupMessageRow(m, senderName)
                        }
                    }
                }
            }

            HorizontalDivider()

            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (pending.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pending.size) { index ->
                            PendingChip(pending[index]) {
                                pending = pending.toMutableList().also { it.removeAt(index) }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    var showAttach by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showAttach = true }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_attach), tint = CPTheme.accent)
                        }
                        DropdownMenu(expanded = showAttach, onDismissRequest = { showAttach = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_files)) }, onClick = {
                                showAttach = false; filePicker.launch(arrayOf("*/*"))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_photos)) }, onClick = {
                                showAttach = false
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.chat_placeholder)) }
                    )
                    val canSend = draft.isNotBlank() || pending.isNotEmpty()
                    IconButton(
                        enabled = canSend,
                        onClick = {
                            val text = draft
                            val atts = pending
                            draft = ""; pending = emptyList()
                            scope.launch { store.sendGroupMessage(groupID, text, atts) }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.chat_send),
                            tint = if (canSend) CPTheme.accent else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupMessageRow(message: ChatMessage, senderName: String?) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (isOutgoing) Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start) {
            if (senderName != null) {
                Text(senderName, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = CPTheme.accent, modifier = Modifier.padding(start = 6.dp, bottom = 1.dp))
            }
            Surface(shape = RoundedCornerShape(20.dp), color = if (isOutgoing) CPTheme.accent else CPTheme.incomingBubble) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (attachment in message.attachments) {
                        if (attachment.isImage) AttachmentImage(attachment) else AttachmentChip(attachment, isOutgoing)
                    }
                    val context = LocalContext.current
                    message.text?.takeIf { it.isNotEmpty() }?.let {
                        val linkColor = if (isOutgoing) Color.White else CPTheme.accent
                        Text(
                            text = linkifiedMessage(it, linkColor) { url -> openInAppBrowser(context, url) },
                            color = if (isOutgoing) Color.White else androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
        if (!isOutgoing) Spacer(Modifier.weight(1f))
    }
}

// ── Group detail (roster + admin) ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupDetailScreen(store: ChatStore, groupID: String, contacts: List<Contact>, onBack: () -> Unit, onLeft: () -> Unit) {
    val groups by store.groups.collectAsState()
    val group = groups[groupID]
    val scope = rememberCoroutineScope()
    val me = store.identityFingerprint
    val amAdmin = group?.isAdmin(me) == true

    var showRename by remember { mutableStateOf(false) }
    var renameDraft by remember { mutableStateOf("") }
    var showAddMembers by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(group == null) { if (group == null) onLeft() }
    if (group == null) return

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(group.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (amAdmin) {
                        IconButton(onClick = { renameDraft = group.name; showRename = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.group_rename), tint = CPTheme.accent)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(memberCountLabel(group.members.size), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (amAdmin) {
                    TextButton(onClick = { showAddMembers = true }) { Text(stringResource(R.string.group_add_members)) }
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f)) {
                items(group.members.size, key = { group.members[it].fingerprint.hex }) { i ->
                    val member = group.members[i]
                    var rowMenu by remember { mutableStateOf(false) }
                    val isSelf = member.fingerprint == me
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(contacts.firstOrNull { it.fingerprint == member.fingerprint }?.displayName ?: member.name ?: UiFormat.shortFingerprint(member.fingerprint), fontWeight = FontWeight.Medium)
                            if (member.isAdmin) {
                                Text(stringResource(R.string.group_admin), style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = CPTheme.accent)
                            }
                        }
                        if (amAdmin && !isSelf) {
                            Box {
                                IconButton(onClick = { rowMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more))
                                }
                                DropdownMenu(expanded = rowMenu, onDismissRequest = { rowMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.group_remove), color = androidx.compose.material3.MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            rowMenu = false
                                            scope.launch { store.removeMember(member.fingerprint, groupID) }
                                        }
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
            TextButton(
                onClick = { showLeaveConfirm = true },
                modifier = Modifier.padding(16.dp)
            ) { Text(stringResource(R.string.group_leave), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(stringResource(R.string.group_rename)) },
            text = {
                OutlinedTextField(value = renameDraft, onValueChange = { renameDraft = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.group_name)) })
            },
            confirmButton = {
                TextButton(onClick = {
                    showRename = false
                    scope.launch { store.renameGroup(groupID, renameDraft) }
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text(stringResource(R.string.group_leave_q)) },
            text = { Text(stringResource(R.string.group_leave_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    scope.launch { store.leaveGroup(groupID) }
                }) { Text(stringResource(R.string.group_leave), color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showLeaveConfirm = false }) { Text(stringResource(R.string.common_cancel)) } }
        )
    }

    if (showAddMembers) {
        val present = group.members.map { it.fingerprint.hex }.toSet()
        val candidates = contacts.filter { it.fingerprint.hex !in present }
        MemberPickerSheet(
            candidates = candidates,
            onDismiss = { showAddMembers = false },
            onAdd = { chosen ->
                showAddMembers = false
                if (chosen.isNotEmpty()) scope.launch { store.addMembers(chosen, groupID) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberPickerSheet(candidates: List<Contact>, onDismiss: () -> Unit, onAdd: (List<Contact>) -> Unit) {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val sorted = remember(candidates) { candidates.sortedBy { (it.displayName ?: it.fingerprint.hex).lowercase() } }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.group_add_members), style = androidx.compose.material3.MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (sorted.isEmpty()) {
                Text(stringResource(R.string.group_no_more_contacts), color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.fillMaxWidth().height(280.dp)) {
                    items(sorted.size, key = { sorted[it].fingerprint.hex }) { i ->
                        val c = sorted[i]
                        val checked = c.fingerprint.hex in selected
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = {
                                selected = if (checked) selected - c.fingerprint.hex else selected + c.fingerprint.hex
                            })
                            Text(c.displayName ?: UiFormat.shortFingerprint(c.fingerprint))
                        }
                    }
                }
            }
            TextButton(enabled = selected.isNotEmpty(), onClick = { onAdd(sorted.filter { it.fingerprint.hex in selected }) }) {
                Text(stringResource(R.string.group_add))
            }
        }
    }
}

@Composable
private fun memberCountLabel(n: Int): String =
    if (n == 1) stringResource(R.string.group_member_one) else stringResource(R.string.group_member_many, n)
