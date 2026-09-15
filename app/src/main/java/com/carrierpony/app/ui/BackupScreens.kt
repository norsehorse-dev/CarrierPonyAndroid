// BackupScreens.kt
// CarrierPony Android
//
// The backup and restore screens, ported from BackupView and
// ImportBackupView in iOS Core/App/IdentityBackup.swift. Copy matches iOS
// exactly, including the no-recovery warning.

package com.carrierpony.app.ui

import com.carrierpony.app.R
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.carrierpony.app.AppModel
import com.carrierpony.app.identity.Identity
import com.carrierpony.app.identity.IdentityBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── Back up ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(identity: Identity, onDone: () -> Unit) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var blob by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val strCreateFailed = stringResource(R.string.backup_create_failed)
    val strSaveFailed = stringResource(R.string.backup_save_failed)
    val strShareFailed = stringResource(R.string.backup_share_failed)
    val strShareChooser = stringResource(R.string.backup_share_chooser)
    val clipboard = LocalClipboard.current
    val context = LocalContext.current

    val suggestedName = "carrierpony-backup-" + identity.fingerprint.hex.takeLast(8).lowercase() + ".txt"

    // System document creator: the reliable "save to a file" on Android.
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        val text = blob
        if (uri != null && text != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                error = strSaveFailed
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_done))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            val ready = blob
            if (ready == null) {
                Text(
                    stringResource(R.string.backup_choose),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.backup_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text(stringResource(R.string.backup_confirm)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (passphrase.isNotEmpty() && passphrase.length < 8) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.backup_min8), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (confirm.isNotEmpty() && passphrase != confirm) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.backup_mismatch), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.backup_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(20.dp))
                val canCreate = passphrase.length >= 8 && passphrase == confirm && !working
                Button(
                    onClick = {
                        error = null
                        working = true
                        val pass = passphrase
                        scope.launch {
                            try {
                                blob = withContext(Dispatchers.Default) {
                                    IdentityBackup.export(identity, pass)
                                }
                            } catch (e: Exception) {
                                error = strCreateFailed
                            } finally {
                                working = false
                            }
                        }
                    },
                    enabled = canCreate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (working) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    } else {
                        Text(stringResource(R.string.backup_create), fontWeight = FontWeight.SemiBold)
                    }
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text(
                    stringResource(R.string.backup_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = ready,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(12.dp).heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row {
                    OutlinedButton(
                        onClick = {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("CarrierPony", ready))) }
                            copied = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(if (copied) stringResource(R.string.common_copied) else stringResource(R.string.common_copy)) }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = { saver.launch(suggestedName) },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.common_save)) }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(
                        onClick = {
                            // Share a real file, not EXTRA_TEXT: file-oriented
                            // targets (Save to Files, Drive) refuse plain text.
                            try {
                                val file = java.io.File(context.cacheDir, suggestedName)
                                file.writeText(ready)
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context, "com.carrierpony.app.fileprovider", file
                                )
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, strShareChooser))
                            } catch (e: Exception) {
                                error = strShareFailed
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.common_share)) }
                }
            }
        }
    }
}

// ── Restore ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportBackupScreen(app: AppModel, onDone: () -> Unit) {
    var blob by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val strRestoreFailed = stringResource(R.string.restore_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.restore_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(
                stringResource(R.string.restore_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = blob,
                onValueChange = { blob = it },
                placeholder = { Text("-----BEGIN PGP MESSAGE-----", fontFamily = FontFamily.Monospace) },
                minLines = 6,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = { scope.launch { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let { blob = it } } }) { Text(stringResource(R.string.common_paste)) }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = { Text(stringResource(R.string.backup_passphrase)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(18.dp))
            val canRestore = blob.trim().isNotEmpty() && passphrase.isNotEmpty() && !working
            Button(
                onClick = {
                    error = null
                    working = true
                    val trimmed = blob.trim()
                    val pass = passphrase
                    scope.launch {
                        try {
                            app.importBackup(trimmed, pass)
                            onDone()
                        } catch (e: Exception) {
                            error = strRestoreFailed
                        } finally {
                            working = false
                        }
                    }
                },
                enabled = canRestore,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (working) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(stringResource(R.string.restore_button), fontWeight = FontWeight.SemiBold)
                }
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
