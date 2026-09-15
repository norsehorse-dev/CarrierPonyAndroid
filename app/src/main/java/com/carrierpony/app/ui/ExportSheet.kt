// ExportSheet.kt
// CarrierPony Android
//
// The chat-export UI, the Android counterpart of iOS Core/UI/ExportView.swift.
// A bottom sheet from the conversation ••• menu: pick a format, choose whether
// to encrypt (ON by default), and hand the finished file to the Android share
// sheet. Encryption reuses CPPassphraseBox, so the default output leaves no
// plaintext at rest. Nothing here touches the relay.

package com.carrierpony.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.carrierpony.app.R
import com.carrierpony.app.messaging.ChatExport
import com.carrierpony.app.messaging.Conversation
import com.carrierpony.app.messaging.ExportFormat

@Composable
private fun formatLabel(f: ExportFormat): String = when (f) {
    ExportFormat.TXT -> stringResource(R.string.export_format_txt)
    ExportFormat.JSON -> stringResource(R.string.export_format_json)
    ExportFormat.HTML -> stringResource(R.string.export_format_html)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(conversation: Conversation, peerName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var format by remember { mutableStateOf(ExportFormat.TXT) }
    var encrypt by remember { mutableStateOf(true) }
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val strChooser = stringResource(R.string.export_share_chooser)
    val strNeedPass = stringResource(R.string.export_need_passphrase)
    val strMismatch = stringResource(R.string.export_passphrase_mismatch)
    val strFailed = stringResource(R.string.export_failed)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.export_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Text(stringResource(R.string.export_format), style = MaterialTheme.typography.labelLarge)
            for (f in ExportFormat.entries) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { format = f },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = format == f, onClick = { format = f })
                    Text(formatLabel(f))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.export_encrypt))
                Switch(checked = encrypt, onCheckedChange = { encrypt = it })
            }

            if (encrypt) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.export_passphrase)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.export_confirm)) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.export_encrypt_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    stringResource(R.string.export_plaintext_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Text(
                stringResource(R.string.export_expiry_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (encrypt && passphrase.isEmpty()) { error = strNeedPass; return@Button }
                    if (encrypt && passphrase != confirm) { error = strMismatch; return@Button }
                    try {
                        val file = ChatExport.writeFile(
                            conversation = conversation,
                            peerName = peerName,
                            format = format,
                            passphrase = if (encrypt) passphrase else null,
                            dir = context.cacheDir
                        )
                        val uri = FileProvider.getUriForFile(context, "com.carrierpony.app.fileprovider", file)
                        val mime = if (encrypt) "application/octet-stream" else when (format) {
                            ExportFormat.TXT -> "text/plain"
                            ExportFormat.JSON -> "application/json"
                            ExportFormat.HTML -> "text/html"
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = mime
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, strChooser))
                        onDismiss()
                    } catch (e: Exception) {
                        error = strFailed
                    }
                }
            ) { Text(stringResource(R.string.export_action)) }
        }
    }
}
