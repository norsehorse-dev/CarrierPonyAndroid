// FilesScreen.kt
// CarrierPony Android
//
// The Files tab, ported from iOS Core/UI/FilesView.swift: a dedicated
// transfer space over the same message store. Every attachment ever sent or
// received, as a flat reverse-chronological list of transfers (a file-manager
// feel, not chat bubbles), plus a "send a file" flow that picks a contact and
// files directly. All the crypto, relay, and on-disk storage are reused from
// messaging — this is purely a new surface. stringResource(R.string.files_save_device) uses the system
// document creator; Share goes through the FileProvider.

package com.carrierpony.app.ui

import com.carrierpony.app.R
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.carrierpony.app.attachments.AttachmentStore
import com.carrierpony.app.crypto.Fingerprint
import com.carrierpony.app.messaging.ChatMessage
import com.carrierpony.app.messaging.ChatStore
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.messaging.MessageDirection
import com.carrierpony.app.ui.theme.CPTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class FileTransfer(
    val attachment: ChatMessage.Attachment,
    val peer: Fingerprint,
    val direction: MessageDirection,
    val sentAt: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(store: ChatStore, contacts: List<Contact>) {
    val conversations by store.conversations.collectAsState()
    val transfers = conversations.values
        .flatMap { conversation ->
            conversation.messages.flatMap { message ->
                message.attachments.map { FileTransfer(it, message.peer, message.direction, message.sentAt) }
            }
        }
        .sortedByDescending { it.sentAt }

    var showingSend by remember { mutableStateOf(false) }

    fun name(peer: Fingerprint): String =
        contacts.firstOrNull { it.fingerprint == peer }?.displayName ?: UiFormat.shortFingerprint(peer)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_files), fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showingSend = true }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.files_send_cd), tint = CPTheme.accent)
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (transfers.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Default.MailOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.files_no_files), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.files_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn {
                    items(transfers, key = { it.attachment.id }) { transfer ->
                        TransferRow(transfer, name(transfer.peer))
                    }
                }
            }
        }
    }

    if (showingSend) {
        ModalBottomSheet(onDismissRequest = { showingSend = false }) {
            SendFileSheet(store = store, contacts = contacts) { showingSend = false }
        }
    }
}

// ── Transfer row ───────────────────────────────────────────────────────

@Composable
private fun TransferRow(transfer: FileTransfer, contactName: String) {
    val context = LocalContext.current
    val attachment = transfer.attachment
    val isIncoming = transfer.direction == MessageDirection.INCOMING
    var showingFull by remember { mutableStateOf(false) }
    var showingMenu by remember { mutableStateOf(false) }

    // System document creator for stringResource(R.string.files_save_device).
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(attachment.mime.ifEmpty { "application/octet-stream" })
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val bytes = AttachmentStore.data(attachment.localPath)
                if (bytes != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                }
            } catch (e: Exception) {
                // Save failures should not crash the tab.
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (attachment.isImage) showingFull = true else showingMenu = true }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        FileThumb(attachment)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = attachment.filename,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isIncoming) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = if (isIncoming) MaterialTheme.colorScheme.tertiary else CPTheme.accent,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = (if (isIncoming) stringResource(R.string.files_from) else stringResource(R.string.files_to)) + contactName +
                        " · " + UiFormat.sizeLabel(attachment.size) +
                        " · " + listTimeLabelText(transfer.sentAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box {
            IconButton(onClick = { showingMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.files_actions), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = showingMenu, onDismissRequest = { showingMenu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.files_save_device)) }, onClick = {
                    showingMenu = false
                    saver.launch(attachment.filename)
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, onClick = {
                    showingMenu = false
                    shareTransfer(context, attachment)
                })
            }
        }
    }

    if (showingFull && attachment.isImage) {
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
private fun FileThumb(attachment: ChatMessage.Attachment) {
    if (attachment.isImage) {
        val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, attachment.localPath) {
            value = withContext(Dispatchers.IO) {
                AttachmentThumbnail.make(AttachmentStore.file(attachment.localPath).path, maxPixel = 128)
            }
        }
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            )
            return
        }
    }
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.MailOutline, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(20.dp))
        }
    }
}

private fun shareTransfer(context: android.content.Context, attachment: ChatMessage.Attachment) {
    val copy = AttachmentStore.temporaryCopy(attachment.localPath, attachment.filename) ?: return
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "com.carrierpony.app.fileprovider", copy)
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = attachment.mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(intent, attachment.filename))
    } catch (e: Exception) {
        // A missing viewer should not crash the tab.
    }
}

// ── Send a file ────────────────────────────────────────────────────────

@Composable
private fun SendFileSheet(store: ChatStore, contacts: List<Contact>, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf<Contact?>(null) }
    var sending by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val contact = selected ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        sending = true
        scope.launch {
            val attachments = withContext(Dispatchers.IO) {
                uris.mapNotNull { readFilesAttachment(context, it) }
            }
            if (attachments.isNotEmpty()) {
                store.send(text = null, attachments = attachments, to = contact)
            }
            sending = false
            onDone()
        }
    }

    Column(Modifier.padding(bottom = 24.dp)) {
        Text(
            text = if (selected == null) stringResource(R.string.files_send_to) else stringResource(R.string.files_pick),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        when {
            sending -> {
                Text(
                    stringResource(R.string.files_sending),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
            contacts.isEmpty() -> {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.inbox_no_contacts), fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.files_pair_first),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                val sorted = contacts.sortedBy { (it.displayName ?: it.fingerprint.hex).lowercase() }
                LazyColumn {
                    items(sorted, key = { it.fingerprint.hex }) { contact ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected = contact
                                    filePicker.launch(arrayOf("*/*"))
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Avatar(name = contact.displayName, size = 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = contact.displayName ?: UiFormat.shortFingerprint(contact.fingerprint),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun readFilesAttachment(context: android.content.Context, uri: Uri): com.carrierpony.app.messaging.OutgoingMessage.Attachment? {
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
        com.carrierpony.app.messaging.OutgoingMessage.Attachment(filename = name, mime = mime, data = data)
    } catch (e: Exception) {
        null
    }
}
