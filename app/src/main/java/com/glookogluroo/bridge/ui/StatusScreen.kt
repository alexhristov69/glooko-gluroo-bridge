package com.glookogluroo.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.glookogluroo.bridge.cloud.BridgeConfigSnapshot
import com.glookogluroo.bridge.cloud.CircuitBreakerStatus

@Composable
fun StatusTab(
    uiState: MainUiState,
    onSignOut: () -> Unit,
    onTripCircuitBreaker: () -> Unit,
    onResetCircuitBreaker: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(RelayTokens.Space3),
    ) {
        Text(
            text = "Connection health and recovery controls.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.relayColors.textMuted,
        )
        RelaySafetyNote()

        if (!uiState.cloudEnabled) {
            RelayStatusCard(
                stateLabel = "Local mode",
                impact = "Status requires AWS cloud sync. Configure g2g.apiBaseUrl / Cognito in gradle.properties and rebuild.",
                stateColor = MaterialTheme.relayColors.warning,
            )
            return@Column
        }

        val lastSync = formatEpoch(uiState.bridgeConfig.lastSuccessfulSyncEpochMs)
        val circuit = uiState.circuitBreaker
        val healthy = !circuit.syncPaused &&
            !circuit.circuitBreakerTripped &&
            circuit.syncEnabled &&
            !circuit.overrideActive
        RelayStatusCard(
            stateLabel = when {
                circuit.circuitBreakerTripped || circuit.syncPaused -> "Relay paused"
                !circuit.syncEnabled -> "Automatic relay off"
                circuit.overrideActive -> "Temporary override"
                else -> "Everything is connected"
            },
            impact = when {
                circuit.circuitBreakerTripped ->
                    "Automatic delivery is stopped until the circuit breaker is reset."
                !circuit.syncEnabled ->
                    "Connections can still be tested. Turn on automatic relay in Settings to resume."
                else ->
                    "Last successful relay: $lastSync. Freshness depends on upstream Glooko data — not real-time."
            },
            timestamp = "Next scheduled: ${formatEpoch(uiState.bridgeConfig.nextScheduledSyncEpochMs)}",
            stateColor = when {
                healthy -> MaterialTheme.relayColors.success
                circuit.overrideActive -> MaterialTheme.relayColors.warning
                else -> MaterialTheme.colorScheme.error
            },
        )

        RelayCard {
            RelayRoute(
                sourceState = if (healthy) RelayRouteSegmentState.Healthy else RelayRouteSegmentState.Interrupted,
                relayState = when {
                    circuit.circuitBreakerTripped || circuit.syncPaused -> RelayRouteSegmentState.Interrupted
                    healthy -> RelayRouteSegmentState.Healthy
                    else -> RelayRouteSegmentState.Idle
                },
                destinationState = if (healthy) RelayRouteSegmentState.Healthy else RelayRouteSegmentState.Interrupted,
            )
        }

        RelayCard {
            RelaySectionLabel("Cloud account")
            Text(
                "Signed in as ${uiState.authEmail.orEmpty()}",
                style = MaterialTheme.typography.bodyMedium,
            )
            uiState.cloudPausedBanner?.let {
                RelayBanner(message = it, tone = RelayBannerTone.Error)
            }
            RelayTextButton(text = "Sign out", onClick = onSignOut)
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
        pending -> MaterialTheme.relayColors.textMuted
        status.overrideActive -> MaterialTheme.relayColors.warning
        status.syncPaused || status.circuitBreakerTripped || !status.syncEnabled ->
            MaterialTheme.colorScheme.error
        else -> MaterialTheme.relayColors.success
    }
    RelayCard {
        RelaySectionLabel("Circuit breaker")
        if (pending) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RelayTokens.Space3),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
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
            Text(
                "syncAllowed=${status.syncAllowed}  syncEnabled=${status.syncEnabled}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
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
            pausedBanner?.let { RelayBanner(message = it, tone = RelayBannerTone.Error) }
            actionMessage?.let { RelayBanner(message = it, tone = RelayBannerTone.Success) }
            actionError?.let { RelayBanner(message = it, tone = RelayBannerTone.Error) }
        }
        if (isAdmin) {
            Row(horizontalArrangement = Arrangement.spacedBy(RelayTokens.Space2)) {
                RelaySecondaryButton(
                    text = "Pause automatic relay",
                    onClick = onTrip,
                    enabled = !isBusy && !pending,
                )
                RelayPrimaryButton(
                    text = "Resume relay",
                    onClick = onReset,
                    enabled = !isBusy && !pending,
                )
            }
        } else {
            Text(
                text = "Admin account required to pause or resume automatic relay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.relayColors.textMuted,
            )
        }
    }
}

@Composable
private fun BridgeConfigCard(config: BridgeConfigSnapshot) {
    var expanded by remember { mutableStateOf(false) }
    RelayCard {
        RelaySectionLabel("Bridge configuration")
        Text(
            "Read-only snapshot from DynamoDB",
            style = MaterialTheme.typography.bodySmall,
        )
        RelayTechnicalDisclosure(
            title = "View technical details",
            expanded = expanded,
            onToggle = { expanded = !expanded },
        ) {
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
internal fun StatLine(label: String, value: String) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
    )
}
