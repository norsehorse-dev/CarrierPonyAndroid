// ReportScreen.kt
// CarrierPony Android
//
// Report a contact for abuse or illegal content, ported from iOS
// Core/UI/ReportView.swift. Because messages are end-to-end encrypted, a
// report is assembled on the reporter's device and only carries what they
// choose to share: the reason, an optional description, and — with explicit
// consent — the text of recent messages they received. File and image bytes
// are never uploaded. Blocking (unpair) is offered in the same step, covering
// both halves of the report-and-block requirement.

package com.carrierpony.app.ui

import com.carrierpony.app.R
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.carrierpony.app.messaging.ChatStore
import com.carrierpony.app.messaging.Contact
import com.carrierpony.app.ui.theme.CPTheme
import kotlinx.coroutines.launch

private val categories = listOf(
    "spam" to R.string.report_spam,
    "harassment" to R.string.report_harassment,
    "illegal" to R.string.report_illegal,
    "csam" to R.string.report_csam,
    "other" to R.string.report_other
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    store: ChatStore,
    contact: Contact,
    onBlock: () -> Unit,
    onDone: () -> Unit
) {
    var category by remember { mutableStateOf("harassment") }
    var details by remember { mutableStateOf("") }
    var includeContent by remember { mutableStateOf(false) }
    var alsoBlock by remember { mutableStateOf(true) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val strReportFailed = stringResource(R.string.report_failed_prefix)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.common_report)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(
                text = stringResource(R.string.report_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))
            Text("REASON", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            for ((id, labelRes) in categories) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { category = id }
                        .padding(vertical = 9.dp)
                ) {
                    Text(stringResource(labelRes), modifier = Modifier.weight(1f))
                    if (category == id) {
                        Icon(Icons.Default.CheckCircle, contentDescription = stringResource(R.string.common_selected), tint = CPTheme.accent, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = details,
                onValueChange = { details = it },
                label = { Text(stringResource(R.string.report_details)) },
                placeholder = { Text(stringResource(R.string.report_details_hint)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.report_include), modifier = Modifier.weight(1f))
                Switch(checked = includeContent, onCheckedChange = { includeContent = it })
            }
            Text(
                text = stringResource(R.string.report_include_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.report_block), modifier = Modifier.weight(1f))
                Switch(checked = alsoBlock, onCheckedChange = { alsoBlock = it })
            }
            Text(
                text = stringResource(R.string.report_block_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(22.dp))
            Button(
                onClick = {
                    submitting = true
                    error = null
                    val trimmed = details.trim()
                    scope.launch {
                        try {
                            store.submitReport(
                                contact = contact,
                                category = category,
                                description = trimmed.ifEmpty { null },
                                includeContent = includeContent
                            )
                            if (alsoBlock) onBlock()
                            onDone()
                        } catch (e: Exception) {
                            error = "$strReportFailed: ${e.message ?: ""}"
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !submitting,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                } else {
                    Text(stringResource(R.string.report_submit))
                }
            }
        }
    }
}
