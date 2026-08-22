// ImportKeyScreen.kt
// CarrierPony Android
//
// The raw key import screen, ported from ImportKeyView in iOS
// Core/App/KeyImport.swift: paste an OpenPGP private key or pick it from a
// file, a passphrase step when the key is protected, and the same
// replaces-your-identity warning.

package com.carrierpony.app.ui

import com.carrierpony.app.R
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.carrierpony.app.identity.KeyImport
import com.carrierpony.app.identity.KeyImportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportKeyScreen(app: AppModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var armored by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var inspection by remember { mutableStateOf<KeyImport.Inspection?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val strFileUnreadable = stringResource(R.string.import_file_unreadable)
    val strImportFailed = stringResource(R.string.import_failed)

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val text = bytes?.toString(Charsets.UTF_8)
            if (text != null && text.contains("BEGIN PGP")) {
                armored = text
                error = null
            } else {
                error = strFileUnreadable
            }
        } catch (e: Exception) {
            error = strFileUnreadable
        }
    }

    fun adopt(current: KeyImport.Inspection, pass: String?) {
        busy = true
        scope.launch {
            try {
                val identity = withContext(Dispatchers.Default) {
                    KeyImport.makeIdentity(current, pass)
                }
                app.adoptImportedIdentity(identity, pass)
                onDone()
            } catch (e: KeyImportException) {
                error = e.message
            } catch (e: Exception) {
                error = strImportFailed
            } finally {
                busy = false
            }
        }
    }

    fun proceed() {
        error = null
        val current = inspection
        if (current == null) {
            busy = true
            scope.launch {
                try {
                    val result = withContext(Dispatchers.Default) { KeyImport.inspect(armored) }
                    inspection = result
                    busy = false
                    if (!result.needsPassphrase) {
                        adopt(result, null)
                    }
                } catch (e: KeyImportException) {
                    error = e.message
                    busy = false
                } catch (e: Exception) {
                    error = strImportFailed
                    busy = false
                }
            }
        } else {
            adopt(current, passphrase.ifEmpty { null })
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_title)) },
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
                stringResource(R.string.import_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))

            if (inspection == null) {
                OutlinedTextField(
                    value = armored,
                    onValueChange = { armored = it },
                    placeholder = { Text("-----BEGIN PGP PRIVATE KEY BLOCK-----", fontFamily = FontFamily.Monospace) },
                    minLines = 6,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedButton(onClick = { scope.launch { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let { armored = it } } }) { Text(stringResource(R.string.common_paste)) }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { armored = ""; error = null },
                        enabled = armored.isNotEmpty()
                    ) { Text(stringResource(R.string.common_clear)) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.import_from_file)) }
            } else if (inspection?.needsPassphrase == true) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.import_key_passphrase)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.import_protected_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(18.dp))
            val canProceed = !busy && (inspection != null || armored.trim().isNotEmpty())
            Button(
                onClick = { proceed() },
                enabled = canProceed,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(if (inspection == null) stringResource(R.string.common_continue) else stringResource(R.string.import_button), fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.import_replaces),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
