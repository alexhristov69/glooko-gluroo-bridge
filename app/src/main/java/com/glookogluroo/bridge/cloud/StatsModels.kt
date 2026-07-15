package com.glookogluroo.bridge.cloud

data class CircuitBreakerStatus(
    val syncEnabled: Boolean = true,
    val syncAllowed: Boolean = true,
    val circuitBreakerTripped: Boolean = false,
    val trippedAt: String? = null,
    val trippedReason: String? = null,
    val overrideUntil: String? = null,
    val overrideActive: Boolean = false,
    val syncPaused: Boolean = false,
) {
    val stateLabel: String = when {
        overrideActive -> "Overridden (sync allowed)"
        circuitBreakerTripped || syncPaused -> "Tripped — sync paused"
        !syncEnabled -> "Kill switch off — sync paused"
        else -> "Closed (allowing sync)"
    }
}

data class BridgeConfigSnapshot(
    val bridgeId: String = "",
    val glookoEmail: String = "",
    val nightscoutUrl: String = "",
    val useTokenAuth: Boolean = false,
    val syncEnabled: Boolean = false,
    val backfillDays: Int = 7,
    val syncFromOverride: String = "",
    val postPumpModeNotes: Boolean = true,
    val jitterInsulinTimestamps: Boolean = false,
    val syncIntervalMinutes: Int = 15,
    val timezone: String = "",
    val lastSuccessfulSyncEpochMs: Long = 0,
    val nextScheduledSyncEpochMs: Long = 0,
    val lastBolusesUploaded: Int = 0,
    val lastError: String? = null,
    val lastPumpModeNote: String? = null,
    val updatedAt: String? = null,
)

data class SyncRunSummary(
    val runId: String,
    val mode: String = "",
    val status: String = "",
    val currentStep: String = "",
    val startedAt: String = "",
    val completedAt: String = "",
    val bolusesUploaded: Int = 0,
    val pumpNoteUploaded: Boolean = false,
    val error: String? = null,
    val diagnostics: String? = null,
    val executionArn: String = "",
    val glookoOk: Boolean? = null,
    val nightscoutOk: Boolean? = null,
)

data class SyncedRecordSummary(
    val dedupeKey: String,
    val eventType: String = "",
    val createdAt: String = "",
    val uploadedAtEpochMs: Long = 0,
    val runId: String = "",
)
