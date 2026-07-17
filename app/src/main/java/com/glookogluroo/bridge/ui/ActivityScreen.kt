package com.glookogluroo.bridge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.glookogluroo.bridge.cloud.SyncRunSummary
import com.glookogluroo.bridge.cloud.SyncedRecordSummary
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

@Composable
fun ActivityTab(
    uiState: MainUiState,
    onRefresh: () -> Unit,
    onSelectRun: (String) -> Unit,
    onRunsOnlyWithRecordsChange: (Boolean) -> Unit,
    onRunsPageChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(RelayTokens.Space3),
    ) {
        Text(
            text = "Recent relay runs and journey details.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.relayColors.textMuted,
        )

        if (!uiState.cloudEnabled) {
            RelayStatusCard(
                stateLabel = "Local mode",
                impact = "Activity requires AWS cloud sync. Configure g2g.apiBaseUrl / Cognito in gradle.properties and rebuild.",
                stateColor = MaterialTheme.relayColors.warning,
            )
            return@Column
        }

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

        uiState.message?.let { RelayBanner(message = it, tone = RelayBannerTone.Success) }
        uiState.error?.let { RelayBanner(message = it, tone = RelayBannerTone.Error) }
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
    val switchColors = SwitchDefaults.colors(
        checkedTrackColor = MaterialTheme.colorScheme.primary,
        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
    )
    RelayCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RelaySectionLabel("Relay runs")
            RelayTextButton(
                text = if (loading) "Loading…" else "Refresh",
                onClick = onRefresh,
                enabled = !loading,
            )
        }
        Text(
            text = "Newest first · ${MainUiState.RUNS_PAGE_SIZE} per page · tap a run for the journey.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Only runs with synced records", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "Boluses uploaded > 0 or pump note posted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.relayColors.textMuted,
                )
            }
            Switch(
                checked = onlyWithRecords,
                onCheckedChange = onOnlyWithRecordsChange,
                colors = switchColors,
            )
        }
        if (filteredCount == 0) {
            Text(
                if (onlyWithRecords) {
                    "No runs with synced records."
                } else {
                    "No relays yet."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            pageRuns.forEach { run ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectRun(run.runId) }
                        .padding(vertical = RelayTokens.Space1),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = run.runId == selectedRunId,
                        onClick = { onSelectRun(run.runId) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = runOutcomeLabel(run),
                            style = MaterialTheme.typography.labelLarge,
                            color = outcomeColor(run),
                        )
                        Text(
                            text = formatRunLabel(run),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RelayTextButton(
                    text = "Previous",
                    onClick = { onPageChange(page - 1) },
                    enabled = page > 0 && !loading,
                )
                Text(
                    text = "Page ${page + 1} of $totalPages · $filteredCount run(s)",
                    style = MaterialTheme.typography.bodySmall,
                )
                RelayTextButton(
                    text = "Next",
                    onClick = { onPageChange(page + 1) },
                    enabled = page < totalPages - 1 && !loading,
                )
            }
        }

        selectedRun?.let { run ->
            Text("Relay journey", style = MaterialTheme.typography.titleSmall)
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
            Text("Select a run to view records.", style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun outcomeColor(run: SyncRunSummary) = when (run.status.uppercase()) {
    "SUCCEEDED" -> MaterialTheme.relayColors.success
    "FAILED" -> MaterialTheme.colorScheme.error
    "RUNNING", "QUEUED" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.relayColors.textMuted
}

private fun formatRunLabel(run: SyncRunSummary): String {
    val whenLabel = formatRunDateTime(run.startedAt)
    val boluses = "${run.bolusesUploaded} event${if (run.bolusesUploaded == 1) "" else "s"}"
    return "$whenLabel · $boluses"
}

private fun runOutcomeLabel(run: SyncRunSummary): String = when (run.status.uppercase()) {
    "SUCCEEDED" -> if (run.bolusesUploaded == 0) "No new events" else "Completed"
    "FAILED" -> "Failed"
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
