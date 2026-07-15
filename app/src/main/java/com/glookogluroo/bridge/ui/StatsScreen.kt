package com.glookogluroo.bridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.glookogluroo.bridge.cloud.BridgeConfigSnapshot
import com.glookogluroo.bridge.cloud.CircuitBreakerStatus
import com.glookogluroo.bridge.cloud.SyncRunSummary
import com.glookogluroo.bridge.cloud.SyncedRecordSummary
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

@Composable
fun StatsTab(
    uiState: MainUiState,
    onSignOut: () -> Unit,
    onRefresh: () -> Unit,
    onSelectRun: (String) -> Unit,
    onTripCircuitBreaker: () -> Unit,
    onResetCircuitBreaker: () -> Unit,
    onRunsOnlyWithRecordsChange: (Boolean) -> Unit,
    onRunsPageChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Read-only view of bridge config, sync runs, and circuit breaker state.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!uiState.cloudEnabled) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Stats require AWS cloud sync", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Configure g2g.apiBaseUrl / Cognito in gradle.properties and rebuild to view " +
                            "DynamoDB bridge config, runs, and circuit breaker controls.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
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
                TextButton(onClick = onSignOut) {
                    Text("Sign out")
                }
            }
        }

        CircuitBreakerCard(
            status = uiState.circuitBreaker,
            isAdmin = uiState.isAdmin,
            isBusy = uiState.isBusy,
            pending = uiState.circuitBreakerPending,
            pendingLabel = uiState.circuitBreakerPendingLabel,
            pausedBanner = uiState.cloudPausedBanner,
            actionMessage = uiState.message,
            actionError = uiState.error,
            onTrip = onTripCircuitBreaker,
            onReset = onResetCircuitBreaker,
        )

        BridgeConfigCard(config = uiState.bridgeConfig)

        SyncRunsCard(
            pageRuns = uiState.pagedRuns,
            filteredCount = uiState.filteredRuns.size,
            page = uiState.runsPageClamped,
            totalPages = uiState.runsTotalPages,
            onlyWithRecords = uiState.runsOnlyWithRecords,
            selectedRunId = uiState.selectedRunId,
            selectedRun = uiState.selectedRun,
            records = uiState.selectedRunRecords,
            loading = uiState.statsLoading,
            onSelectRun = onSelectRun,
            onRefresh = onRefresh,
            onOnlyWithRecordsChange = onRunsOnlyWithRecordsChange,
            onPageChange = onRunsPageChange,
        )

        uiState.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        uiState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun CircuitBreakerCard(
    status: CircuitBreakerStatus,
    isAdmin: Boolean,
    isBusy: Boolean,
    pending: Boolean,
    pendingLabel: String?,
    pausedBanner: String?,
    actionMessage: String?,
    actionError: String?,
    onTrip: () -> Unit,
    onReset: () -> Unit,
) {
    val statusColor = when {
        pending -> MaterialTheme.colorScheme.onSurfaceVariant
        status.overrideActive -> MaterialTheme.colorScheme.tertiary
        status.syncPaused || status.circuitBreakerTripped || !status.syncEnabled ->
            MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Circuit breaker", style = MaterialTheme.typography.titleMedium)
            if (pending) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text(
                        text = pendingLabel ?: "Waiting for backend…",
                        style = MaterialTheme.typography.titleSmall,
                        color = statusColor,
                    )
                }
            } else {
                Text(
                    text = status.stateLabel,
                    color = statusColor,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("syncAllowed=${status.syncAllowed}  syncEnabled=${status.syncEnabled}")
                status.trippedAt?.takeIf { it.isNotBlank() }?.let {
                    Text("Tripped at: $it", style = MaterialTheme.typography.bodySmall)
                }
                status.trippedReason?.takeIf { it.isNotBlank() }?.let {
                    Text("Reason: $it", style = MaterialTheme.typography.bodySmall)
                }
                status.overrideUntil?.takeIf { it.isNotBlank() }?.let {
                    Text("Override until: $it", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!pending) {
                pausedBanner?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
            if (!pending) {
                actionMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
                actionError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
            if (isAdmin) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onTrip, enabled = !isBusy && !pending) {
                        Text("Pull circuit breaker")
                    }
                    Button(onClick = onReset, enabled = !isBusy && !pending) {
                        Text("Reset circuit breaker")
                    }
                }
            } else {
                Text(
                    text = "Admin account required to pull or reset the circuit breaker.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BridgeConfigCard(config: BridgeConfigSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Bridge configuration", style = MaterialTheme.typography.titleMedium)
            Text("Read-only snapshot from DynamoDB", style = MaterialTheme.typography.bodySmall)
            StatLine("bridgeId", config.bridgeId)
            StatLine("glookoEmail", config.glookoEmail)
            StatLine("nightscoutUrl", config.nightscoutUrl)
            StatLine("useTokenAuth", config.useTokenAuth.toString())
            StatLine("syncEnabled", config.syncEnabled.toString())
            StatLine("syncIntervalMinutes", config.syncIntervalMinutes.toString())
            StatLine("backfillDays", config.backfillDays.toString())
            StatLine("syncFromOverride", config.syncFromOverride.ifBlank { "(none)" })
            StatLine("timezone", config.timezone)
            StatLine("postPumpModeNotes", config.postPumpModeNotes.toString())
            StatLine("jitterInsulinTimestamps", config.jitterInsulinTimestamps.toString())
            StatLine("lastSuccessfulSync", formatEpoch(config.lastSuccessfulSyncEpochMs))
            StatLine("nextScheduledSync", formatEpoch(config.nextScheduledSyncEpochMs))
            StatLine("lastBolusesUploaded", config.lastBolusesUploaded.toString())
            StatLine("lastError", config.lastError ?: "(none)")
            StatLine("lastPumpModeNote", config.lastPumpModeNote ?: "(none)")
            StatLine("updatedAt", config.updatedAt ?: "(unknown)")
        }
    }
}

@Composable
private fun SyncRunsCard(
    pageRuns: List<SyncRunSummary>,
    filteredCount: Int,
    page: Int,
    totalPages: Int,
    onlyWithRecords: Boolean,
    selectedRunId: String?,
    selectedRun: SyncRunSummary?,
    records: List<SyncedRecordSummary>,
    loading: Boolean,
    onSelectRun: (String) -> Unit,
    onRefresh: () -> Unit,
    onOnlyWithRecordsChange: (Boolean) -> Unit,
    onPageChange: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Sync runs", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text(if (loading) "Loading…" else "Refresh")
                }
            }
            Text(
                text = "Newest first · ${MainUiState.RUNS_PAGE_SIZE} per page · tap a run for details.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Only runs with synced records")
                    Text(
                        text = "Boluses uploaded > 0 or pump note posted",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = onlyWithRecords,
                    onCheckedChange = onOnlyWithRecordsChange,
                )
            }
            if (filteredCount == 0) {
                Text(
                    if (onlyWithRecords) {
                        "No runs with synced records."
                    } else {
                        "No sync runs yet."
                    },
                )
            } else {
                pageRuns.forEach { run ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectRun(run.runId) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = run.runId == selectedRunId,
                            onClick = { onSelectRun(run.runId) },
                        )
                        Text(
                            text = formatRunLabel(run),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { onPageChange(page - 1) },
                        enabled = page > 0 && !loading,
                    ) {
                        Text("Previous")
                    }
                    Text(
                        text = "Page ${page + 1} of $totalPages · $filteredCount run(s)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(
                        onClick = { onPageChange(page + 1) },
                        enabled = page < totalPages - 1 && !loading,
                    ) {
                        Text("Next")
                    }
                }
            }

            selectedRun?.let { run ->
                Text("Run details", style = MaterialTheme.typography.titleSmall)
                StatLine("runId", run.runId)
                StatLine("mode", run.mode)
                StatLine("status", run.status)
                StatLine("currentStep", run.currentStep)
                StatLine("startedAt", formatRunDateTime(run.startedAt))
                StatLine("completedAt", formatRunDateTime(run.completedAt).ifBlank { "(in progress / n/a)" })
                StatLine("bolusesUploaded", run.bolusesUploaded.toString())
                StatLine("pumpNoteUploaded", run.pumpNoteUploaded.toString())
                run.glookoOk?.let { StatLine("glookoOk", it.toString()) }
                run.nightscoutOk?.let { StatLine("nightscoutOk", it.toString()) }
                StatLine("error", run.error ?: "(none)")
                if (run.executionArn.isNotBlank()) {
                    StatLine("executionArn", run.executionArn)
                }
                run.diagnostics?.takeIf { it.isNotBlank() }?.let { diag ->
                    Text("Diagnostics", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = diag,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Text(
                "Synced records for this run (${records.size})",
                style = MaterialTheme.typography.titleSmall,
            )
            if (selectedRunId == null) {
                Text("Select a run to view records.")
            } else if (records.isEmpty()) {
                Text(
                    text = "No records tagged with this runId. " +
                        "Older uploads (before this feature) won’t list here.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                records.forEach { record ->
                    Text(
                        text = "${record.eventType} · ${record.createdAt}\n${record.dedupeKey}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}

private fun formatRunLabel(run: SyncRunSummary): String {
    val whenLabel = formatRunDateTime(run.startedAt)
    val outcome = runOutcomeLabel(run)
    val boluses = "${run.bolusesUploaded} bolus${if (run.bolusesUploaded == 1) "" else "es"}"
    return "$whenLabel · $outcome · $boluses"
}

private fun runOutcomeLabel(run: SyncRunSummary): String = when (run.status.uppercase()) {
    "SUCCEEDED" -> "Success"
    "FAILED" -> "Failure"
    "RUNNING" -> "Running"
    "QUEUED" -> "Queued"
    else -> run.status.ifBlank { "Unknown" }
}

private fun formatRunDateTime(raw: String): String {
    if (raw.isBlank()) return "(unknown)"
    return runCatching {
        val instant = Instant.parse(raw)
        displayFormatter.format(instant.atZone(ZoneId.systemDefault()))
    }.recoverCatching {
        val odt = OffsetDateTime.parse(raw)
        displayFormatter.format(odt.atZoneSameInstant(ZoneId.systemDefault()))
    }.getOrDefault(raw)
}
