// OnboardingScreen.kt
// CarrierPony Android
//
// Immersive first run, ported from iOS Core/App/OnboardingView.swift in the
// house style shared with the other Pony apps: a paged flow with capsule dots
// that ends with the user fully set up — a real identity created or imported,
// optionally backed up, and optionally paired — before entering the app. A
// language step lays the rails for localization. iOS presents the sub-flows
// as sheets; here they are full-screen routes that return to the same step.

package com.carrierpony.app.ui

import com.carrierpony.app.R
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.carrierpony.app.AppModel
import com.carrierpony.app.ui.theme.CPTheme
import kotlinx.coroutines.launch

private const val LAST_STEP = 6

private sealed class OnboardRoute {
    data object Pages : OnboardRoute()
    data object Import : OnboardRoute()
    data object Restore : OnboardRoute()
    data object Backup : OnboardRoute()
    data object Pairing : OnboardRoute()
    data object Relay : OnboardRoute()
}

@Composable
fun OnboardingScreen(app: AppModel) {
    var step by remember { mutableStateOf(0) }
    var route by remember { mutableStateOf<OnboardRoute>(OnboardRoute.Pages) }
    val identity by app.identity.collectAsState()

    // Sub-flows as full-screen routes that come back to the same step.
    when (route) {
        is OnboardRoute.Import -> {
            BackHandler { route = OnboardRoute.Pages }
            ImportKeyScreen(app = app) { route = OnboardRoute.Pages }
            return
        }
        is OnboardRoute.Restore -> {
            BackHandler { route = OnboardRoute.Pages }
            ImportBackupScreen(app = app) { route = OnboardRoute.Pages }
            return
        }
        is OnboardRoute.Backup -> {
            BackHandler { route = OnboardRoute.Pages }
            identity?.let { BackupScreen(identity = it) { route = OnboardRoute.Pages } }
                ?: run { route = OnboardRoute.Pages }
            return
        }
        is OnboardRoute.Pairing -> {
            BackHandler { route = OnboardRoute.Pages }
            PairingScreen(app = app, onFinished = { route = OnboardRoute.Pages })
            return
        }
        is OnboardRoute.Relay -> {
            BackHandler { route = OnboardRoute.Pages }
            RelayScreen(app = app, onBack = { route = OnboardRoute.Pages }, onboarding = true)
            return
        }
        is OnboardRoute.Pages -> Unit
    }

    val canContinue = step != 3 || identity != null
    val skippable = step == 4 || step == 5

    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(250)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(250)))
                    .togetherWith(slideOutHorizontally(tween(250)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)))
            },
            modifier = Modifier.weight(1f),
            label = "onboarding"
        ) { current ->
            Box(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                when (current) {
                    0 -> WelcomeStep()
                    1 -> LanguageStep(app)
                    2 -> HowItWorksStep()
                    3 -> IdentityStep(
                        app = app,
                        onImport = { route = OnboardRoute.Import },
                        onRestore = { route = OnboardRoute.Restore },
                        onRelay = { route = OnboardRoute.Relay }
                    )
                    4 -> BackupStep { route = OnboardRoute.Backup }
                    5 -> ConnectStep { route = OnboardRoute.Pairing }
                    else -> DoneStep()
                }
            }
        }

        // ── Dots + controls ────────────────────────────────────────────
        Column(Modifier.padding(horizontal = 28.dp).padding(bottom = 20.dp, top = 8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                for (i in 0..LAST_STEP) {
                    Box(
                        Modifier
                            .height(7.dp)
                            .width(if (i == step) 22.dp else 7.dp)
                            .background(
                                color = if (i == step) CPTheme.accent else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) {
                    TextButton(onClick = { step -= 1 }) {
                        Text(stringResource(R.string.common_back), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Spacer(Modifier.width(44.dp))
                }
                Spacer(Modifier.weight(1f))
                if (skippable) {
                    TextButton(onClick = { step = minOf(step + 1, LAST_STEP) }) {
                        Text(stringResource(R.string.common_skip), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = {
                        if (step == LAST_STEP) {
                            app.onboardingComplete = true
                        } else {
                            step = minOf(step + 1, LAST_STEP)
                        }
                    },
                    enabled = canContinue
                ) {
                    Text(
                        if (step == LAST_STEP) stringResource(R.string.onb_enter) else stringResource(R.string.common_continue),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ── Steps ──────────────────────────────────────────────────────────────

@Composable
private fun WelcomeStep() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Email, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(76.dp))
        Spacer(Modifier.height(18.dp))
        Text("CarrierPony", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.onb_tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LanguageStep(app: AppModel) {
    var selected by remember { mutableStateOf(app.appLanguage) }
    Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
        Text(stringResource(R.string.onb_lang_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.onb_lang_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for ((code, label) in onboardingLanguages) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = code
                            app.appLanguage = code
                        }
                        .padding(vertical = 14.dp)
                ) {
                    Text(label, modifier = Modifier.weight(1f))
                    if (selected == code) {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.common_selected), tint = CPTheme.accent, modifier = Modifier.size(18.dp))
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun HowItWorksStep() {
    Column(Modifier.fillMaxSize().padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
        Column {
            Text(stringResource(R.string.onb_how_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.onb_how_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        InfoRow(Icons.Default.Lock, stringResource(R.string.onb_e2e_title), stringResource(R.string.onb_e2e_body))
        InfoRow(Icons.Default.Person, stringResource(R.string.onb_pair_title), stringResource(R.string.onb_pair_body))
        InfoRow(Icons.Default.Email, stringResource(R.string.onb_relay_title), stringResource(R.string.onb_relay_body))
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IdentityStep(app: AppModel, onImport: () -> Unit, onRestore: () -> Unit, onRelay: () -> Unit) {
    val identity by app.identity.collectAsState()
    var name by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val strCreateFailed = stringResource(R.string.onb_create_failed)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val current = identity
        when {
            current != null -> {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(60.dp))
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.onb_identity_ready), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    UiFormat.shortFingerprint(current.fingerprint),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.onb_fpr_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
            creating -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.onb_generating), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Icon(Icons.Default.Star, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(52.dp))
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.onb_setup_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.onb_setup_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.onb_name_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
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
                            } catch (e: Exception) {
                                createError = strCreateFailed
                            } finally {
                                creating = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.onb_create), fontWeight = FontWeight.SemiBold) }
                Spacer(Modifier.height(12.dp))
                Row {
                    TextButton(onClick = onImport) {
                        Text(stringResource(R.string.onb_import), color = CPTheme.accent, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(18.dp))
                    TextButton(onClick = onRestore) {
                        Text(stringResource(R.string.onb_restore), color = CPTheme.accent, fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = onRelay) {
                    Text(stringResource(R.string.relay_onb_use), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                createError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun BackupStep(onBackup: () -> Unit) {
    var didBackup by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            if (didBackup) Icons.Default.CheckCircle else Icons.Default.Star,
            contentDescription = null,
            tint = CPTheme.accent,
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(14.dp))
        Text(
            if (didBackup) stringResource(R.string.onb_backed_up) else stringResource(R.string.onb_backup_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onb_backup_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = { didBackup = true; onBackup() }) {
            Text(if (didBackup) stringResource(R.string.onb_backup_again) else stringResource(R.string.onb_backup_now), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ConnectStep(onPair: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Person, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.onb_connect_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onb_connect_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPair) {
            Text(stringResource(R.string.onb_pair_now), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DoneStep() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Star, contentDescription = null, tint = CPTheme.accent, modifier = Modifier.size(60.dp))
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.onb_done_title), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onb_done_body),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private val onboardingLanguages = listOf(
    "en" to "English",
    "es" to "Español",
    "de" to "Deutsch",
    "fr" to "Français",
    "pt" to "Português",
    "it" to "Italiano",
    "zh" to "中文",
    "ja" to "日本語",
    "ru" to "Русский"
)
