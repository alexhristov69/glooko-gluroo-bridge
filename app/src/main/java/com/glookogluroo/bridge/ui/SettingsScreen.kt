package com.glookogluroo.bridge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import com.glookogluroo.bridge.sync.SyncPreview
import com.glookogluroo.bridge.sync.SyncWindowResolver
import kotlinx.coroutines.delay

@Composable
fun SettingsTab(uiState: MainUiState, viewModel: MainViewModel) {
    var glookoPasswordVisible by remember { mutableStateOf(false) }
    var nightscoutSecretVisible by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.relayColors.borderSubtle,
        cursorColor = MaterialTheme.colorScheme.primary,
        errorBorderColor = MaterialTheme.colorScheme.error,
    )
    val fieldShape = RoundedCornerShape(RelayTokens.RadiusField)
    val switchColors = SwitchDefaults.colors(
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    )

    uiState.settingsValidationError?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissValidationError,
            title = { Text("Check these settings") },
            text = { Text(message) },
            confirmButton = {
                RelayTextButton(text = "OK", onClick = viewModel::dismissValidationError)
            },
            shape = RoundedCornerShape(RelayTokens.RadiusCard),
            containerColor = MaterialTheme.colorScheme.surface,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(RelayTokens.Space3),
    ) {
        Text(
            text = "Connect services and choose how often Relay should move events from Glooko to Gluroo.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.relayColors.textMuted,
        )
        RelaySafetyNote()

        RelayCard {
            RelaySectionLabel("Glooko")
            OutlinedTextField(
                value = uiState.settings.glookoEmail,
                onValueChange = viewModel::updateGlookoEmail,
                label = { Text("Glooko email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = fieldShape,
                colors = fieldColors,
            )
            RelaySecretField(
                value = uiState.settings.glookoPassword,
                onValueChange = viewModel::updateGlookoPassword,
                label = "Glooko password",
                visible = glookoPasswordVisible,
                onVisibilityChange = { glookoPasswordVisible = it },
            )
        }

        RelayCard {
            RelaySectionLabel("Gluroo Global Connect")
            OutlinedTextField(
                value = uiState.settings.nightscoutUrl,
                onValueChange = viewModel::updateNightscoutUrl,
                label = { Text("Nightscout URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = fieldShape,
                colors = fieldColors,
            )
            RelaySecretField(
                value = uiState.settings.nightscoutSecret,
                onValueChange = viewModel::updateNightscoutSecret,
                label = "API secret / token",
                visible = nightscoutSecretVisible,
                onVisibilityChange = { nightscoutSecretVisible = it },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Use token query auth", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = uiState.settings.useTokenAuth,
                    onCheckedChange = viewModel::updateUseTokenAuth,
                    colors = switchColors,
                )
            }
        }

        RelayCard {
            RelaySectionLabel("Automatic relay")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (uiState.cloudEnabled) {
                        "Enable AWS scheduled relay"
                    } else {
                        "Enable automatic relay"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = uiState.settings.syncEnabled,
                    onCheckedChange = viewModel::updateSyncEnabled,
                    colors = switchColors,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Post pump mode notes", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = uiState.settings.postPumpModeNotes,
                    onCheckedChange = viewModel::updatePostPumpModeNotes,
                    colors = switchColors,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Jitter insulin timestamps (test)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "Adds random microsecond jitter to bolus created_at. For Gluroo dedup testing.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = uiState.settings.jitterInsulinTimestamps,
                    onCheckedChange = viewModel::updateJitterInsulinTimestamps,
                    colors = switchColors,
                )
            }
            OutlinedTextField(
                value = uiState.backfillDaysText,
                onValueChange = viewModel::updateBackfillDays,
                label = { Text("Backfill days (when never synced)") },
                supportingText = {
                    Text("Whole number from 1 to 30. Used after reset last sync, or before the first successful relay.")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = uiState.backfillDaysText.isNotEmpty() &&
                    uiState.backfillDaysText.toIntOrNull()?.let { it !in 1..30 } != false,
                shape = fieldShape,
                colors = fieldColors,
            )
            OutlinedTextField(
                value = uiState.settings.syncFromOverride,
                onValueChange = viewModel::updateSyncFromOverride,
                label = { Text("Relay from (optional)") },
                placeholder = { Text("yyyy-MM-dd HH:mm") },
                supportingText = {
                    Text("Local time. Overrides last-sync window until cleared. Save settings, then Test connection / Relay now.")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = uiState.settings.syncFromOverride.isNotBlank() &&
                    SyncWindowResolver.parseCustomStart(uiState.settings.syncFromOverride) == null,
                shape = fieldShape,
                colors = fieldColors,
            )
            OutlinedTextField(
                value = uiState.syncIntervalMinutesText,
                onValueChange = viewModel::updateSyncIntervalMinutes,
                label = { Text("Relay interval (minutes, min 1)") },
                supportingText = {
                    Text("Whole number from 1 to 240 minutes.")
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = uiState.syncIntervalMinutesText.isNotEmpty() &&
                    uiState.syncIntervalMinutesText.toIntOrNull()?.let { it !in 1..240 } != false,
                shape = fieldShape,
                colors = fieldColors,
            )
            if (uiState.settings.syncEnabled) {
                NextSyncCountdown(nextSyncAtMs = uiState.syncState.nextScheduledSyncEpochMs)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(RelayTokens.Space2),
        ) {
            RelayPrimaryButton(text = "Save", onClick = viewModel::saveSettings)
            RelaySecondaryButton(text = "Test connection", onClick = viewModel::testConnections)
            RelayPrimaryButton(text = "Relay now", onClick = viewModel::syncNow)
        }

        if (uiState.isBusy) {
            RelayBanner(message = "Working…", tone = RelayBannerTone.Info)
        }

        uiState.message?.let { message ->
            RelayBanner(message = message, tone = RelayBannerTone.Success)
        }

        uiState.error?.let { error ->
            RelayBanner(message = error, tone = RelayBannerTone.Error)
        }

        uiState.diagnostics?.takeIf { it.isNotBlank() }?.let { diagnostics ->
            CollapsibleDiagnosticsCard(
                title = "View technical details",
                contentKey = diagnostics,
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
                "Relay upload"
            }
            CollapsibleDiagnosticsCard(
                title = previewTitle,
                contentKey = preview,
            ) {
                Text(
                    text = if (uiState.syncPreviewDryRun) {
                        "These treatments would be POSTed to Nightscout but were not sent."
                    } else {
                        "Events fetched from Glooko for this relay window."
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
                    Text(
                        "No new events were found.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
                    Spacer(modifier = Modifier.height(RelayTokens.Space1))
                    Text("JSON payload:", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = preview.jsonPayload,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        RelayCard {
            RelaySectionLabel("Connection status")
            val glookoOk = uiState.connectionTest?.glookoOk
            val nightscoutOk = uiState.connectionTest?.nightscoutOk
            RelayRoute(
                sourceState = when (glookoOk) {
                    true -> RelayRouteSegmentState.Healthy
                    false -> RelayRouteSegmentState.Interrupted
                    null -> RelayRouteSegmentState.Idle
                },
                relayState = when {
                    glookoOk == true && nightscoutOk == true -> RelayRouteSegmentState.Healthy
                    glookoOk == false || nightscoutOk == false -> RelayRouteSegmentState.Interrupted
                    else -> RelayRouteSegmentState.Idle
                },
                destinationState = when (nightscoutOk) {
                    true -> RelayRouteSegmentState.Healthy
                    false -> RelayRouteSegmentState.Interrupted
                    null -> RelayRouteSegmentState.Idle
                },
            )
            Text(
                "Last successful relay: ${formatEpoch(uiState.syncState.lastSuccessfulSyncEpochMs)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (uiState.syncState.lastSuccessfulSyncEpochMs > 0L) {
                Text(
                    text = "Incremental window starts 2h before last relay unless Relay from is set.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "Last events uploaded: ${uiState.syncState.lastBolusesUploaded}",
                style = MaterialTheme.typography.bodyMedium,
            )
            uiState.syncState.lastError?.let {
                RelayBanner(message = it, tone = RelayBannerTone.Error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(RelayTokens.Space2)) {
                RelayTextButton(text = "Reset last sync", onClick = viewModel::resetLastSync)
                RelayTextButton(text = "Clear upload history", onClick = viewModel::clearUploadHistory)
            }
            uiState.connectionTest?.let { test ->
                Text(
                    "Glooko: ${if (test.glookoOk) "Connected" else test.glookoError ?: "Needs attention"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (test.glookoOk) {
                        MaterialTheme.relayColors.success
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
                Text(
                    "Gluroo: ${if (test.nightscoutOk) "Connected" else test.nightscoutError ?: "Needs attention"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (test.nightscoutOk) {
                        MaterialTheme.relayColors.success
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            uiState.pumpStatistics?.let { pump ->
                Spacer(modifier = Modifier.height(RelayTokens.Space1))
                Text(
                    "Pump mode: ${pump.mode?.name?.lowercase() ?: "unknown"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Auto ${pump.autoPercentage.toInt()}% | " +
                        "Manual ${pump.manualPercentage.toInt()}% | " +
                        "Limited ${pump.limitedPercentage.toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            uiState.devices.forEach { device ->
                Text(
                    "${device.name} — last sync: ${device.lastSync?.let { formatInstant(it) } ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun CollapsibleDiagnosticsCard(
    title: String,
    contentKey: Any?,
    content: @Composable () -> Unit,
) {
    var expanded by remember(contentKey) { mutableStateOf(false) }

    RelayCard {
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
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = RelayTokens.Space2),
                verticalArrangement = Arrangement.spacedBy(RelayTokens.Space2),
            ) {
                content()
            }
        }
    }
}

@Composable
fun NextSyncCountdown(nextSyncAtMs: Long) {
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
        color = MaterialTheme.relayColors.textMuted,
    )
}

private fun formatNextSyncCountdown(nextSyncAtMs: Long, nowMs: Long): String {
    if (nextSyncAtMs <= 0L) return "Next relay: not scheduled (save settings)"
    val remainingMs = nextSyncAtMs - nowMs
    if (remainingMs <= 0L) return "Next relay: due now"
    val totalSeconds = remainingMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "Next relay in ${hours}h ${minutes}m ${seconds}s"
        minutes > 0 -> "Next relay in ${minutes}m ${seconds}s"
        else -> "Next relay in ${seconds}s"
    }
}
