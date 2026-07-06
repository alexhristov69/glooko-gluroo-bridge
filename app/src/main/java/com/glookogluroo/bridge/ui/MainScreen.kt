package com.glookogluroo.bridge.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

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
            Text(
                text = "Sync insulin and pump data from Glooko to Gluroo via Nightscout (GGC). CGM is not uploaded.",
                style = MaterialTheme.typography.bodyMedium,
            )

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
                        visualTransformation = PasswordVisualTransformation(),
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
                        visualTransformation = PasswordVisualTransformation(),
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
                        Text("Enable background sync")
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
                    OutlinedTextField(
                        value = uiState.settings.backfillDays.toString(),
                        onValueChange = viewModel::updateBackfillDays,
                        label = { Text("Backfill days (first sync)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = uiState.settings.syncIntervalMinutes.toString(),
                        onValueChange = viewModel::updateSyncIntervalMinutes,
                        label = { Text("Sync interval (minutes, min 15)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Text("Last sync: ${formatEpoch(uiState.syncState.lastSuccessfulSyncEpochMs)}")
                    Text("Last boluses uploaded: ${uiState.syncState.lastBolusesUploaded}")
                    uiState.syncState.lastError?.let { Text("Last error: $it") }
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

private fun formatEpoch(epochMs: Long): String {
    if (epochMs == 0L) return "Never"
    return displayFormatter.format(
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()),
    )
}

private fun formatInstant(instant: Instant): String {
    return displayFormatter.format(instant.atZone(ZoneId.systemDefault()))
}
