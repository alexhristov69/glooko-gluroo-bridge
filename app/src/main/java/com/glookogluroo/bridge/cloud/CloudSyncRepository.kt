package com.glookogluroo.bridge.cloud

import com.glookogluroo.bridge.data.AppSettings
import com.glookogluroo.bridge.data.SettingsRepository
import com.glookogluroo.bridge.sync.ConnectionTestResult
import com.glookogluroo.bridge.sync.SyncPreview
import com.glookogluroo.bridge.sync.SyncResult
import com.glookogluroo.bridge.sync.db.SyncState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncRepository @Inject constructor(
    private val api: BridgeApiClient,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun saveSettings(settings: AppSettings) {
        val withZone = settings.copy(
            timezone = settings.timezone.ifBlank {
                java.time.ZoneId.systemDefault().id
            },
        )
        api.putSettings(
            buildJsonObject {
                put("glookoEmail", withZone.glookoEmail)
                put("glookoPassword", withZone.glookoPassword)
                put("nightscoutUrl", withZone.nightscoutUrl)
                put("nightscoutSecret", withZone.nightscoutSecret)
                put("useTokenAuth", withZone.useTokenAuth)
                put("syncEnabled", withZone.syncEnabled)
                put("backfillDays", withZone.backfillDays)
                put("syncFromOverride", withZone.syncFromOverride)
                put("postPumpModeNotes", withZone.postPumpModeNotes)
                put("jitterInsulinTimestamps", withZone.jitterInsulinTimestamps)
                put("syncIntervalMinutes", withZone.syncIntervalMinutes.coerceAtLeast(1))
                put("timezone", withZone.timezone)
            },
        )
        // Keep non-secret settings locally for UI; secrets stay for edit-in-place
        settingsRepository.saveSettings(withZone)
    }

    suspend fun getSyncState(): SyncState {
        val status = api.getStatus()
        val bridge = status.obj("bridge") ?: return SyncState()
        return SyncState(
            lastSuccessfulSyncEpochMs = bridge.long("lastSuccessfulSyncEpochMs"),
            nextScheduledSyncEpochMs = bridge.long("nextScheduledSyncEpochMs"),
            lastError = bridge.stringOrNull("lastError"),
            lastBolusesUploaded = bridge.int("lastBolusesUploaded"),
        )
    }

    suspend fun hydrateSettingsFromCloud(): AppSettings? {
        val status = runCatching { api.getStatus() }.getOrNull() ?: return null
        val bridge = status.obj("bridge") ?: return null
        val local = settingsRepository.getSettings()
        return local.copy(
            glookoEmail = bridge.string("glookoEmail", local.glookoEmail),
            nightscoutUrl = bridge.string("nightscoutUrl", local.nightscoutUrl),
            useTokenAuth = bridge.bool("useTokenAuth", local.useTokenAuth),
            syncEnabled = bridge.bool("syncEnabled", local.syncEnabled),
            backfillDays = bridge.int("backfillDays", local.backfillDays),
            syncFromOverride = bridge.string("syncFromOverride", local.syncFromOverride),
            postPumpModeNotes = bridge.bool("postPumpModeNotes", local.postPumpModeNotes),
            jitterInsulinTimestamps = bridge.bool("jitterInsulinTimestamps", local.jitterInsulinTimestamps),
            syncIntervalMinutes = bridge.int("syncIntervalMinutes", local.syncIntervalMinutes),
            timezone = bridge.string("timezone", local.timezone).ifBlank {
                java.time.ZoneId.systemDefault().id
            },
        )
    }

    suspend fun testConnections(settings: AppSettings): ConnectionTestResult {
        saveSettings(settings)
        val started = api.startRun("test")
        val runId = started.string("runId")
        if (runId.isBlank()) error("No runId returned")
        val run = api.pollRunUntilDone(runId)
        val diagnostics = run.string("diagnostics")
        val preview = parsePreview(run.obj("syncPreview"))
        return ConnectionTestResult(
            glookoOk = run.bool("glookoOk") || run.string("status") == "SUCCEEDED",
            nightscoutOk = run.bool("nightscoutOk"),
            glookoError = run.stringOrNull("glookoError"),
            nightscoutError = run.stringOrNull("nightscoutError"),
            diagnostics = diagnostics,
            syncPreview = preview,
        )
    }

    suspend fun runSync(settings: AppSettings): SyncResult {
        saveSettings(settings)
        val started = api.startRun("sync")
        val runId = started.string("runId")
        if (runId.isBlank()) error("No runId returned")
        val run = api.pollRunUntilDone(runId)
        val success = run.string("status") == "SUCCEEDED"
        val diagnostics = run.string("diagnostics")
        val preview = parsePreview(run.obj("syncPreview"))
        return SyncResult(
            success = success,
            bolusesUploaded = run.int("bolusesUploaded"),
            pumpNoteUploaded = run.bool("pumpNoteUploaded"),
            devices = emptyList(),
            pumpStatistics = null,
            syncPreview = preview,
            error = if (success) null else run.string("error").ifBlank { diagnostics },
            diagnostics = diagnostics,
        )
    }

    suspend fun resetLastSync() {
        api.resetSync()
    }

    suspend fun clearUploadHistory() {
        api.clearHistory()
    }

    suspend fun cloudStatusBanner(): String? {
        val status = runCatching { api.getStatus() }.getOrNull() ?: return null
        return if (status.bool("syncPaused")) {
            val reason = status.string("trippedReason").ifBlank { "cost guard" }
            "Sync paused ($reason). Contact admin to override/reset circuit breaker."
        } else {
            null
        }
    }

    private fun parsePreview(obj: kotlinx.serialization.json.JsonObject?): SyncPreview? {
        if (obj == null) return null
        return runCatching {
            val start = Instant.parse(obj.string("syncWindowStart"))
            val end = Instant.parse(obj.string("syncWindowEnd"))
            SyncPreview(
                syncWindowStart = start,
                syncWindowEnd = end,
                windowSource = obj.string("windowSource"),
                bolusesFound = obj.int("bolusesFound"),
                bolusesAlreadySynced = obj.int("bolusesAlreadySynced"),
                treatmentsToUpload = emptyList(),
                devices = emptyList(),
                pumpStatistics = null,
                jsonPayload = obj.string("jsonPayload", "[]"),
                jitterInsulinTimestamps = false,
                cloudTreatmentCount = obj.int("treatmentsToUpload"),
            )
        }.getOrNull()
    }
}
