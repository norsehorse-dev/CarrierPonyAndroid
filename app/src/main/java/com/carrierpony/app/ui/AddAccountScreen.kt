// AddAccountScreen.kt
// CarrierPony Android
//
// Add a second (or third) account without running the whole first-run
// onboarding. It always creates or imports a FRESH identity, even though one is
// already active, and setIdentity() switches the running stack to it. Reached
// from Settings > Accounts > Add Account and from the inbox account switcher.

package com.carrierpony.app.ui

import com.carrierpony.app.R
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.carrierpony.app.AppModel
import com.carrierpony.app.ui.theme.CPTheme
import kotlinx.coroutines.launch

private sealed class AddRoute {
    data object Main : AddRoute()
    data object Import : AddRoute()
    data object Restore : AddRoute()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(app: AppModel, onDone: () -> Unit) {
    var route by remember { mutableStateOf<AddRoute>(AddRoute.Main) }

    when (route) {
        is AddRoute.Import -> {
            BackHandler { route = AddRoute.Main }
            // Import switches to the new account (setIdentity); then dismiss.
            ImportKeyScreen(app = app) { onDone() }
            return
        }
        is AddRoute.Restore -> {
            BackHandler { route = AddRoute.Main }
            ImportBackupScreen(app = app) { onDone() }
            return
        }
        is AddRoute.Main -> Unit
    }

    var name by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val strFailed = stringResource(R.string.onb_create_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.accounts_add)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.accounts_add_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.onb_name_optional)) },
                singleLine = true,
                enabled = !creating,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            if (creating) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.onb_generating), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Button(
                    onClick = {
                        createError = null
                        creating = true
                        val displayName = name.trim()
                        scope.launch {
                            try {
                                app.createIdentity(
                                    name = displayName.ifEmpty { "CarrierPony User" },
                                    email = "user@carrierpony.app"
                                )
                                onDone()
                            } catch (e: Exception) {
                                createError = strFailed
                                creating = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.onb_create), fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = { route = AddRoute.Import }) {
                        Text(stringResource(R.string.onb_import), color = CPTheme.accent, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(18.dp))
                    TextButton(onClick = { route = AddRoute.Restore }) {
                        Text(stringResource(R.string.onb_restore), color = CPTheme.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            createError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
