package com.glookogluroo.bridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.glookogluroo.bridge.sync.SyncPreview
import com.glookogluroo.bridge.sync.SyncWindowResolver
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    var glookoPasswordVisible by remember { mutableStateOf(false) }
    var nightscoutSecretVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Glooko Gluroo Bridge") })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.cloudEnabled && uiState.isBootstrapping) {
                StartupScreen(message = uiState.bootstrapMessage ?: "Starting…")
                return@Column
            }

            Text(
                text = "Sync insulin and pump data from Glooko to Gluroo via Nightscout (GGC). CGM is not uploaded.",
                style = MaterialTheme.typography.bodyMedium,
            )

            if (uiState.cloudEnabled) {
                if (!uiState.signedIn) {
                    AuthScreen(
                        isBusy = uiState.isBusy,
                        message = uiState.message,
                        error = uiState.error,
                        onSignIn = viewModel::signIn,
                        onSignUp = viewModel::signUp,
                        onConfirm = viewModel::confirmSignUp,
                    )
                    return@Column
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("AWS account", style = MaterialTheme.typography.titleMedium)
                        Text("Signed in as ${uiState.authEmail.orEmpty()}")
                        uiState.cloudPausedBanner?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = viewModel::signOut) {
                            Text("Sign out")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Glooko credentials", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = uiState.settings.glookoEmail,
                        onValueChange = viewModel::updateGlookoEmail,
                        label = { Text("Glooko email") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.settings.glookoPassword,
                        onValueChange = viewModel::updateGlookoPassword,
                        label = { Text("Glooko password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (glookoPasswordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { glookoPasswordVisible = !glookoPasswordVisible }) {
                                Text(if (glookoPasswordVisible) "Hide" else "Show")
                            }
                        },
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gluroo Global Connect", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = uiState.settings.nightscoutUrl,
                        onValueChange = viewModel::updateNightscoutUrl,
                        label = { Text("Nightscout URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.settings.nightscoutSecret,
                        onValueChange = viewModel::updateNightscoutSecret,
                        label = { Text("API secret / token") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (nightscoutSecretVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { nightscoutSecretVisible = !nightscoutSecretVisible }) {
                                Text(if (nightscoutSecretVisible) "Hide" else "Show")
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Use token query auth")
                        Switch(
                            checked = uiState.settings.useTokenAuth,
                            onCheckedChange = viewModel::updateUseTokenAuth,
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sync settings", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (uiState.cloudEnabled) {
                                "Enable AWS scheduled sync"
                            } else {
                                "Enable background sync"
                            },
                        )
                        Switch(
                            checked = uiState.settings.syncEnabled,
                            onCheckedChange = viewModel::updateSyncEnabled,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Post pump mode notes")
                        Switch(
                            checked = uiState.settings.postPumpModeNotes,
                            onCheckedChange = viewModel::updatePostPumpModeNotes,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Jitter insulin timestamps (test)")
                            Text(
                                text = "Adds random microsecond jitter to bolus created_at. For Gluroo dedup testing.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = uiState.settings.jitterInsulinTimestamps,
                            onCheckedChange = viewModel::updateJitterInsulinTimestamps,
                        )
                    }
                    OutlinedTextField(
                        value = uiState.settings.backfillDays.toString(),
                        onValueChange = viewModel::updateBackfillDays,
                        label = { Text("Backfill days (when never synced)") },
                        supportingText = {
                            Text("Used after reset last sync, or before the first successful sync.")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.settings.syncFromOverride,
                        onValueChange = viewModel::updateSyncFromOverride,
                        label = { Text("Sync from (optional)") },
                        placeholder = { Text("yyyy-MM-dd HH:mm") },
                        supportingText = {
                            Text(
                                "Local time. Overrides last-sync window until cleared. Save settings, then Test/Sync.",
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = uiState.settings.syncFromOverride.isNotBlank() &&
                            SyncWindowResolver.parseCustomStart(uiState.settings.syncFromOverride) == null,
                    )
                    OutlinedTextField(
                        value = uiState.settings.syncIntervalMinutes.toString(),
                        onValueChange = viewModel::updateSyncIntervalMinutes,
                        label = {
                            Text(
                                if (uiState.cloudEnabled) {
                                    "Sync interval (minutes, min 1)"
                                } else {
                                    "Sync interval (minutes, min 1)"
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (uiState.settings.syncEnabled) {
                        NextSyncCountdown(nextSyncAtMs = uiState.syncState.nextScheduledSyncEpochMs)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::saveSettings) {
                    Text("Save")
                }
                Button(onClick = viewModel::testConnections) {
                    Text("Test")
                }
                Button(onClick = viewModel::syncNow) {
                    Text("Sync now")
                }
            }

            if (uiState.isBusy) {
                Text("Working...")
            }

            uiState.message?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.primary)
            }

            uiState.error?.let { error ->
                Text(error, color = MaterialTheme.colorScheme.error)
            }

            uiState.diagnostics?.takeIf { it.isNotBlank() }?.let { diagnostics ->
                CollapsibleDiagnosticsCard(
                    title = "Diagnostics",
                    contentKey = diagnostics,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = diagnostics,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            uiState.syncPreview?.let { preview ->
                val previewTitle = if (uiState.syncPreviewDryRun) {
                    "Upload preview (dry run)"
                } else {
                    "Sync upload"
                }
                CollapsibleDiagnosticsCard(
                    title = previewTitle,
                    contentKey = preview,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = if (uiState.syncPreviewDryRun) {
                            "These treatments would be POSTed to Nightscout but were not sent."
                        } else {
                            "Treatments fetched from Glooko for this sync window."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Window: ${formatInstant(preview.syncWindowStart)} → " +
                            formatInstant(preview.syncWindowEnd),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Source: ${preview.windowSource}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "Boluses: ${preview.bolusesFound} found, " +
                            "${preview.bolusesAlreadySynced} already synced, " +
                            "${preview.bolusesToUpload} new",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (preview.treatmentsToUpload.isEmpty()) {
                        Text("Nothing new to upload.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        preview.treatmentsToUpload.forEachIndexed { index, treatment ->
                            Text(
                                text = buildString {
                                    append("${index + 1}. ")
                                    append(SyncPreview.formatTreatmentLine(treatment))
                                    treatment.notes?.takeIf { it.isNotBlank() }?.let {
                                        append("\n   ")
                                        append(it)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("JSON payload:", style = MaterialTheme.typography.labelMedium)
                        Text(
                            text = preview.jsonPayload,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text("Last sync: ${formatEpoch(uiState.syncState.lastSuccessfulSyncEpochMs)}")
                    if (uiState.syncState.lastSuccessfulSyncEpochMs > 0L) {
                        Text(
                            text = "Incremental window starts 2h before last sync unless Sync from is set.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Last boluses uploaded: ${uiState.syncState.lastBolusesUploaded}")
                    uiState.syncState.lastError?.let { Text("Last error: $it") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = viewModel::resetLastSync) {
                            Text("Reset last sync")
                        }
                        TextButton(onClick = viewModel::clearUploadHistory) {
                            Text("Clear upload history")
                        }
                    }
                    uiState.connectionTest?.let { test ->
                        Text("Glooko test: ${if (test.glookoOk) "OK" else test.glookoError ?: "Failed"}")
                        Text("Nightscout test: ${if (test.nightscoutOk) "OK" else test.nightscoutError ?: "Failed"}")
                    }
                    uiState.pumpStatistics?.let { pump ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Pump mode: ${pump.mode?.name?.lowercase() ?: "unknown"}")
                        Text(
                            "Auto ${pump.autoPercentage.toInt()}% | " +
                                "Manual ${pump.manualPercentage.toInt()}% | " +
                                "Limited ${pump.limitedPercentage.toInt()}%",
                        )
                    }
                    uiState.devices.forEach { device ->
                        Text("${device.name} — last sync: ${device.lastSync?.let { formatInstant(it) } ?: "unknown"}")
                    }
                }
            }
        }
    }
}

private val displayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

@Composable
private fun CollapsibleDiagnosticsCard(
    title: String,
    contentKey: Any?,
    containerColor: Color,
    content: @Composable () -> Unit,
) {
    var expanded by remember(contentKey) { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (expanded) "Hide" else "Show",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun NextSyncCountdown(nextSyncAtMs: Long) {
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(nextSyncAtMs) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }
    Text(
        text = formatNextSyncCountdown(nextSyncAtMs, nowMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun formatNextSyncCountdown(nextSyncAtMs: Long, nowMs: Long): String {
    if (nextSyncAtMs <= 0L) return "Next sync: not scheduled (save settings)"
    val remainingMs = nextSyncAtMs - nowMs
    if (remainingMs <= 0L) return "Next sync: due now"
    val totalSeconds = remainingMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "Next sync in ${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "Next sync in ${minutes}m ${seconds}s"
        else -> "Next sync in ${seconds}s"
    }
}

private fun formatEpoch(epochMs: Long): String {
    if (epochMs == 0L) return "Never"
    return displayFormatter.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()),
    )
}

private fun formatInstant(instant: Instant): String {
    return displayFormatter.format(instant.atZone(ZoneId.systemDefault()))
}
