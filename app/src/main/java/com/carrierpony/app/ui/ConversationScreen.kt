// ConversationScreen.kt
// CarrierPony Android
//
// The conversation screen, ported from iOS Core/UI/ConversationView.swift:
// one thread with one peer, day-grouped bubbles, image thumbnails with a
// full-screen viewer, attachment chips that share through FileProvider, read
// checkmarks, the imminent-expiry timer, and a composer with pending-
// attachment chips, a file picker, and a photo picker. The ••• menu carries
// nickname editing and Report (which also offers Block). Camera capture rides
// with a later sub-phase (it needs the permission plumbing).

package com.carrierpony.app.ui

import com.carrierpony.app.R
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.messaging.ChatMessage
import com.carrierpony.app.messaging.ChatStore
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.messaging.OutgoingMessage
import com.carrierpony.app.ui.theme.CPTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConversationScreen(
    store: ChatStore,
    contact: Contact,
    onBack: () -> Unit,
    onSetNickname: ((String?) -> Unit)? = null,
    onReport: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val conversations by store.conversations.collectAsState()
    val conversation = conversations.values.firstOrNull { it.peer == contact.fingerprint }
    val messages = conversation?.sortedMessages ?: emptyList()
    val title = contact.displayName ?: conversation?.peerName ?: UiFormat.shortFingerprint(contact.fingerprint)

    var draft by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<OutgoingMessage.Attachment>>(emptyList()) }
    var showingMenu by remember { mutableStateOf(false) }
    var showingNicknamePrompt by remember { mutableStateOf(false) }
    var showingClearConfirm by remember { mutableStateOf(false) }
    var showingExport by remember { mutableStateOf(false) }
    var nicknameDraft by remember { mutableStateOf("") }

    // Mark read on open and whenever new messages arrive.
    LaunchedEffect(conversation?.threadID, messages.size) {
        conversation?.threadID?.let { store.markRead(it) }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    // When the keyboard opens, the list shrinks; keep the newest messages in
    // view instead of letting them slide off behind the composer.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // ── Pickers ────────────────────────────────────────────────────────

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val loaded = uris.mapNotNull { uri -> readAttachment(context, uri) }
        pending = pending + loaded
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(maxItems = 10)
    ) { uris ->
        val loaded = uris.mapNotNull { uri -> readAttachment(context, uri) }
        pending = pending + loaded
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name = contact.displayName ?: conversation?.peerName, size = 32.dp)
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sealed = store.isSealed(contact.fingerprint)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (sealed) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                    contentDescription = if (sealed) stringResource(R.string.chat_sealed) else stringResource(R.string.chat_standard),
                                    modifier = Modifier.size(12.dp),
                                    tint = if (sealed) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    UiFormat.shortFingerprint(contact.fingerprint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (onSetNickname != null || onReport != null || conversation != null) {
                        IconButton(onClick = { showingMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.common_more), tint = CPTheme.accent)
                        }
                        DropdownMenu(expanded = showingMenu, onDismissRequest = { showingMenu = false }) {
                            if (onSetNickname != null) {
                                DropdownMenuItem(
                                    text = { Text(if (contact.nickname == null) stringResource(R.string.chat_set_nickname) else stringResource(R.string.chat_edit_nickname)) },
                                    onClick = {
                                        showingMenu = false
                                        nicknameDraft = contact.nickname ?: ""
                                        showingNicknamePrompt = true
                                    }
                                )
                                if (contact.nickname != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.chat_remove_nickname)) },
                                        onClick = {
                                            showingMenu = false
                                            onSetNickname(null)
                                        }
                                    )
                                }
                            }
                            if (onReport != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_report), color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showingMenu = false
                                        onReport()
                                    }
                                )
                            }
                            if (conversation != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_clear_history)) },
                                    onClick = {
                                        showingMenu = false
                                        showingClearConfirm = true
                                    }
                                )
                            }
                            if (conversation != null) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.chat_export)) },
                                    onClick = {
                                        showingMenu = false
                                        showingExport = true
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        // imePadding + adjustResize (manifest) make the keyboard RESIZE this
        // column, so the thread stays visible above the composer.
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {

            // ── Message list ───────────────────────────────────────────
            Box(Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    EmptyThread()
                } else {
                    val grouped = messages.groupBy { UiFormat.startOfDay(it.sentAt) }.toSortedMap()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        for ((day, dayMessages) in grouped) {
                            item(key = "day-$day") {
                                DaySeparator(dayLabelText(day))
                            }
                            items(dayMessages.size, key = { dayMessages[it].id }) { index ->
                                val msg = dayMessages[index]
                                MessageRow(
                                    message = msg,
                                    onDeleteForMe = { scope.launch { store.deleteLocally(msg.id) } },
                                    onDeleteForEveryone = if (msg.direction == MessageDirection.OUTGOING) {
                                        { scope.launch { store.deleteForEveryone(msg.id, to = contact) } }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // ── Composer ───────────────────────────────────────────────
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
                    var showingAttachMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showingAttachMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.chat_attach), tint = CPTheme.accent)
                        }
                        DropdownMenu(expanded = showingAttachMenu, onDismissRequest = { showingAttachMenu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_files)) }, onClick = {
                                showingAttachMenu = false
                                filePicker.launch(arrayOf("*/*"))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.chat_photos)) }, onClick = {
                                showingAttachMenu = false
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                            })
                        }
                    }
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text(stringResource(R.string.chat_placeholder)) },
                        maxLines = 5,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(10.dp))
                    val canSend = draft.trim().isNotEmpty() || pending.isNotEmpty()
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .let {
                                if (canSend) it.background(CPTheme.send)
                                else it.background(MaterialTheme.colorScheme.surfaceVariant)
                            }
                            .clickable(enabled = canSend) {
                                val text = draft.trim()
                                val attachments = pending
                                draft = ""
                                pending = emptyList()
                                scope.launch {
                                    store.send(
                                        text = text.ifEmpty { null },
                                        attachments = attachments,
                                        to = contact
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    if (showingNicknamePrompt) {
        AlertDialog(
            onDismissRequest = { showingNicknamePrompt = false },
            title = { Text(stringResource(R.string.chat_nickname)) },
            text = {
                Column {
                    Text(stringResource(R.string.chat_nickname_body))
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = nicknameDraft,
                        onValueChange = { nicknameDraft = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.chat_nickname)) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSetNickname?.invoke(nicknameDraft)
                    showingNicknamePrompt = false
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showingNicknamePrompt = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showingClearConfirm) {
        AlertDialog(
            onDismissRequest = { showingClearConfirm = false },
            title = { Text(stringResource(R.string.chat_clear_history_title)) },
            text = { Text(stringResource(R.string.chat_clear_history_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showingClearConfirm = false
                    conversation?.threadID?.let { tid -> scope.launch { store.clearHistory(tid) } }
                }) { Text(stringResource(R.string.chat_clear_history), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showingClearConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showingExport) {
        conversation?.let { conv ->
            ExportSheet(conversation = conv, peerName = title, onDismiss = { showingExport = false })
        }
    }
}

// ── Attachment loading ─────────────────────────────────────────────────

internal fun readAttachment(context: android.content.Context, uri: Uri): OutgoingMessage.Attachment? {
    return try {
        val data = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        var name = "file"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                cursor.getString(index)?.let { name = it }
            }
        }
        OutgoingMessage.Attachment(filename = name, mime = mime, data = data)
    } catch (e: Exception) {
        null
    }
}

// ── Rows and bubbles ───────────────────────────────────────────────────

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: ChatMessage,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: (() -> Unit)?
) {
    val isOutgoing = message.direction == MessageDirection.OUTGOING
    var showMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (isOutgoing) Spacer(Modifier.weight(1f, fill = true).widthIn(min = 48.dp))
        Box {
            Column(
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true }
                ),
                horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
            ) {
                Bubble(message, isOutgoing)
                Metadata(message, isOutgoing)
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_delete_for_me)) },
                    onClick = {
                        showMenu = false
                        onDeleteForMe()
                    }
                )
                if (onDeleteForEveryone != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_delete_for_everyone), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDeleteForEveryone()
                        }
                    )
                }
            }
        }
        if (!isOutgoing) Spacer(Modifier.weight(1f, fill = true).widthIn(min = 48.dp))
    }
}

@Composable
private fun Bubble(message: ChatMessage, isOutgoing: Boolean) {
    val imageOnly = message.text == null &&
        message.attachments.isNotEmpty() &&
        message.attachments.all { it.isImage }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isOutgoing) CPTheme.accent else CPTheme.incomingBubble
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (imageOnly) 4.dp else 14.dp,
                vertical = if (imageOnly) 4.dp else 9.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (attachment in message.attachments) {
                if (attachment.isImage) {
                    AttachmentImage(attachment)
                } else {
                    AttachmentChip(attachment, isOutgoing)
                }
            }
            message.text?.takeIf { it.isNotEmpty() }?.let {
                val context = LocalContext.current
                val linkColor = if (isOutgoing) Color.White else CPTheme.accent
                Text(
                    text = linkifiedMessage(it, linkColor) { url -> openInAppBrowser(context, url) },
                    color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun Metadata(message: ChatMessage, isOutgoing: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        UiFormat.disappearingLabel(message.expiresAt)?.let {
            Text("⏱ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            UiFormat.messageTimeLabel(message.sentAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (message.viaLan) {
            Icon(
                imageVector = Icons.Default.CompareArrows,
                contentDescription = stringResource(R.string.chat_via_lan),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
        }
        if (isOutgoing) {
            Icon(
                imageVector = if (message.isRead) Icons.Default.CheckCircle else Icons.Default.Check,
                contentDescription = if (message.isRead) stringResource(R.string.chat_read) else stringResource(R.string.chat_sent),
                tint = if (message.isRead) CPTheme.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(13.dp)
            )
        }
    }
}

@Composable
internal fun AttachmentImage(attachment: ChatMessage.Attachment) {
    var showingFull by remember { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, attachment.localPath) {
        value = withContext(Dispatchers.IO) {
            AttachmentThumbnail.make(AttachmentStore.file(attachment.localPath).path, maxPixel = 640)
        }
    }
    val current = bitmap
    if (current != null) {
        Image(
            bitmap = current.asImageBitmap(),
            contentDescription = attachment.filename,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable { showingFull = true }
        )
    } else {
        Box(
            Modifier.size(180.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
    if (showingFull) {
        Dialog(onDismissRequest = { showingFull = false }) {
            val full by produceState<android.graphics.Bitmap?>(initialValue = null, attachment.localPath) {
                value = withContext(Dispatchers.IO) {
                    AttachmentThumbnail.make(AttachmentStore.file(attachment.localPath).path, maxPixel = 2048)
                }
            }
            full?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = attachment.filename,
                    modifier = Modifier.fillMaxWidth().clickable { showingFull = false }
                )
            }
        }
    }
}

@Composable
internal fun AttachmentChip(attachment: ChatMessage.Attachment, onAccent: Boolean) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable { shareAttachment(context, attachment) }
            .padding(vertical = 4.dp)
    ) {
        Icon(
            Icons.Default.MailOutline,
            contentDescription = null,
            tint = if (onAccent) Color.White else CPTheme.accent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = attachment.filename,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (onAccent) Color.White else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = UiFormat.sizeLabel(attachment.size),
                style = MaterialTheme.typography.labelSmall,
                color = if (onAccent) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Copy the attachment to cache under its original name and hand it to the
 *  system share sheet through FileProvider — the Android counterpart of the
 *  iOS temporaryCopy + share flow. */
private fun shareAttachment(context: android.content.Context, attachment: ChatMessage.Attachment) {
    val copy = AttachmentStore.temporaryCopy(attachment.localPath, attachment.filename) ?: return
    try {
        val uri = FileProvider.getUriForFile(context, "com.carrierpony.app.fileprovider", copy)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = attachment.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, attachment.filename))
    } catch (e: Exception) {
        // A missing viewer or provider mismatch should not crash the thread.
    }
}

@Composable
internal fun PendingChip(attachment: OutgoingMessage.Attachment, onRemove: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp)
        ) {
            Column {
                Text(
                    text = attachment.filename,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
                Text(
                    text = UiFormat.sizeLabel(attachment.data.size.toLong()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_remove), modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun EmptyThread() {
    Column(
        modifier = Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.chat_empty_title), fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.chat_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
