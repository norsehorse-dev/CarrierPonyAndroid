// NotificationsScreen.kt
// CarrierPony Android
//
// Settings > Notifications: how this install hears about new messages while the
// app is closed. Play builds use FCM and only show that. The F-Droid / GitHub
// build lets the user pick a UnifiedPush distributor, or none, in which case a
// periodic background check stands in.

package com.carrierpony.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.carrierpony.app.AppConfig
import com.carrierpony.app.R
import com.carrierpony.app.push.PushMode
import com.carrierpony.app.push.PushSupport
import com.carrierpony.app.ui.theme.CPTheme
import kotlinx.coroutines.delay

/** One-line summary for the Settings card. */
@Composable
fun notificationsSummary(): String {
    val context = LocalContext.current
    return when (PushSupport.mode(context)) {
        PushMode.FCM -> stringResource(R.string.notif_sub_fcm)
        PushMode.UNIFIED_PUSH -> {
            val pkg = PushSupport.currentDistributor(context)
            val label = PushSupport.distributors(context).firstOrNull { it.packageName == pkg }?.label ?: "UnifiedPush"
            stringResource(R.string.notif_sub_up, label)
        }
        PushMode.BACKGROUND_CHECK -> stringResource(R.string.notif_sub_check)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // The distributor answers asynchronously, and the user may install one and
    // come back, so re-read the state every couple of seconds while this is open.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            tick++
        }
    }
    val mode = remember(tick) { PushSupport.mode(context) }
    val distributors = remember(tick) { PushSupport.distributors(context) }
    val current = remember(tick) { PushSupport.currentDistributor(context) }
    val currentLabel = distributors.firstOrNull { it.packageName == current }?.label ?: current
    val gatewayUsable = !AppConfig.usingCustomRelay(context) || AppConfig.gatewayPushEnabled(context)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_notifications)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_done))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            val status = when {
                mode == PushMode.FCM -> stringResource(R.string.notif_mode_fcm)
                mode == PushMode.UNIFIED_PUSH && currentLabel != null -> stringResource(R.string.notif_mode_up, currentLabel)
                currentLabel != null && gatewayUsable -> stringResource(R.string.notif_mode_pending, currentLabel)
                else -> stringResource(R.string.notif_mode_check)
            }
            SettingsPanel(stringResource(R.string.notif_heading_mode)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(status, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                }
            }

            if (PushSupport.supportsChoice) {
                SettingsPanel(stringResource(R.string.notif_heading_service)) {
                    for (d in distributors) {
                        ChoiceRow(
                            label = d.label,
                            selected = current == d.packageName,
                            onSelect = {
                                PushSupport.useDistributor(context, d.packageName)
                                tick++
                            }
                        )
                        PanelDivider()
                    }
                    ChoiceRow(
                        label = stringResource(R.string.notif_option_none),
                        selected = current == null,
                        onSelect = {
                            PushSupport.useBackgroundCheck(context)
                            tick++
                        }
                    )
                }
                if (!gatewayUsable) PanelNote(stringResource(R.string.notif_gateway_needed))
                if (distributors.isEmpty()) PanelNote(stringResource(R.string.notif_none_installed))
                if (current != null) PanelNote(stringResource(R.string.notif_privacy_up))
            }

            SettingsPanel {
                PanelRow(PanelItem(Icons.Default.Settings, stringResource(R.string.notif_system_settings), end = RowEnd.EXTERNAL) {
                    try {
                        context.startActivity(
                            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    } catch (e: Exception) {
                        // No settings activity for this; nothing else to offer.
                    }
                })
            }
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}
