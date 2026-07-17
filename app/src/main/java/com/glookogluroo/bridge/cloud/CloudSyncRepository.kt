package com.glookogluroo.bridge.cloud

import com.glookogluroo.bridge.data.AppSettings
import com.glookogluroo.bridge.data.SettingsRepository
import com.glookogluroo.bridge.sync.ConnectionTestResult
import com.glookogluroo.bridge.sync.SyncPreview
import com.glookogluroo.bridge.sync.SyncResult
import com.glookogluroo.bridge.sync.db.SyncState
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncRepository @Inject constructor(
    private val api: BridgeApiClient,
    private val settingsRepository: SettingsRepository,
) {
    suspend fun saveSettings(settings: AppSettings): AppSettings {
        val withZone = settings.copy(
            timezone = settings.timezone.ifBlank {
                java.time.ZoneId.systemDefault().id
            },
        )
        val resolved = settingsRepository.saveSettings(withZone)
        api.putSettings(
            buildJsonObject {
                put("glookoEmail", resolved.glookoEmail)
                put("nightscoutUrl", resolved.nightscoutUrl)
                put("useTokenAuth", resolved.useTokenAuth)
                put("syncEnabled", resolved.syncEnabled)
                put("backfillDays", resolved.backfillDays)
                put("syncFromOverride", resolved.syncFromOverride)
                put("postPumpModeNotes", resolved.postPumpModeNotes)
                put("jitterInsulinTimestamps", resolved.jitterInsulinTimestamps)
                put("syncIntervalMinutes", resolved.syncIntervalMinutes.coerceAtLeast(1))
                put("timezone", resolved.timezone)
                if (resolved.glookoPassword.isNotBlank()) {
                    put("glookoPassword", resolved.glookoPassword)
                }
                if (resolved.nightscoutSecret.isNotBlank()) {
                    put("nightscoutSecret", resolved.nightscoutSecret)
                }
            },
        )
        return resolved
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

    suspend fun hydrateSettingsFromCloud(local: AppSettings): AppSettings? {
        val status = runCatching { api.getStatus() }.getOrNull() ?: return null
        val bridge = status.obj("bridge") ?: return null
        var merged = local.copy(
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
            glookoPassword = local.glookoPassword,
            nightscoutSecret = local.nightscoutSecret,
        )
        val needsGlooko = merged.glookoPassword.isBlank() &&
            bridge.bool("glookoPasswordConfigured")
        val needsSecret = merged.nightscoutSecret.isBlank() &&
            bridge.bool("nightscoutSecretConfigured")
        if (needsGlooko || needsSecret) {
            runCatching { api.getSettingsCredentials() }.getOrNull()?.let { creds ->
                merged = merged.copy(
                    glookoPassword = merged.glookoPassword.ifBlank {
                        creds.string("glookoPassword")
                    },
                    nightscoutSecret = merged.nightscoutSecret.ifBlank {
                        creds.string("nightscoutSecret")
                    },
                )
                settingsRepository.saveSettings(merged)
            }
        }
        return merged
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

    suspend fun fetchCloudStatus(): Triple<BridgeConfigSnapshot, CircuitBreakerStatus, Boolean> {
        val status = api.getStatus()
        val bridge = status.obj("bridge")
        val config = BridgeConfigSnapshot(
            bridgeId = bridge?.string("bridgeId").orEmpty(),
            glookoEmail = bridge?.string("glookoEmail").orEmpty(),
            nightscoutUrl = bridge?.string("nightscoutUrl").orEmpty(),
            useTokenAuth = bridge?.bool("useTokenAuth") ?: false,
            syncEnabled = bridge?.bool("syncEnabled") ?: false,
            backfillDays = bridge?.int("backfillDays", 7) ?: 7,
            syncFromOverride = bridge?.string("syncFromOverride").orEmpty(),
            postPumpModeNotes = bridge?.bool("postPumpModeNotes", true) ?: true,
            jitterInsulinTimestamps = bridge?.bool("jitterInsulinTimestamps") ?: false,
            syncIntervalMinutes = bridge?.int("syncIntervalMinutes", 15) ?: 15,
            timezone = bridge?.string("timezone").orEmpty(),
            lastSuccessfulSyncEpochMs = bridge?.long("lastSuccessfulSyncEpochMs") ?: 0L,
            nextScheduledSyncEpochMs = bridge?.long("nextScheduledSyncEpochMs") ?: 0L,
            lastBolusesUploaded = bridge?.int("lastBolusesUploaded") ?: 0,
            lastError = bridge?.stringOrNull("lastError"),
            lastPumpModeNote = bridge?.stringOrNull("lastPumpModeNote"),
            updatedAt = bridge?.stringOrNull("updatedAt"),
        )
        val cb = CircuitBreakerStatus(
            syncEnabled = status.bool("syncEnabled", true),
            syncAllowed = status.bool("syncAllowed", true),
            circuitBreakerTripped = status.bool("circuitBreakerTripped"),
            trippedAt = status.stringOrNull("trippedAt"),
            trippedReason = status.stringOrNull("trippedReason"),
            overrideUntil = status.stringOrNull("overrideUntil"),
            overrideActive = status.bool("overrideActive"),
            syncPaused = status.bool("syncPaused"),
        )
        return Triple(config, cb, status.bool("isAdmin"))
    }

    suspend fun isAdminUser(): Boolean =
        runCatching { api.getStatus().bool("isAdmin") }.getOrDefault(false)

    suspend fun listRuns(limit: Int = 30): List<SyncRunSummary> {
        val resp = api.listRuns(limit)
        val runs = resp["runs"]?.jsonArray ?: return emptyList()
        return runs.mapNotNull { element ->
            val obj = element.jsonObject
            SyncRunSummary(
                runId = obj.string("runId"),
                mode = obj.string("mode"),
                status = obj.string("status"),
                currentStep = obj.string("currentStep"),
                startedAt = obj.string("startedAt"),
                completedAt = obj.string("completedAt"),
                bolusesUploaded = obj.int("bolusesUploaded"),
                pumpNoteUploaded = obj.bool("pumpNoteUploaded"),
                error = obj.stringOrNull("error"),
                diagnostics = obj.stringOrNull("diagnostics"),
                executionArn = obj.string("executionArn"),
                glookoOk = if (obj.containsKey("glookoOk")) obj.bool("glookoOk") else null,
                nightscoutOk = if (obj.containsKey("nightscoutOk")) obj.bool("nightscoutOk") else null,
            )
        }.filter { it.runId.isNotBlank() }
    }

    suspend fun getRunDetail(runId: String): SyncRunSummary {
        val obj = api.getRun(runId)
        return SyncRunSummary(
            runId = obj.string("runId", runId),
            mode = obj.string("mode"),
            status = obj.string("status"),
            currentStep = obj.string("currentStep"),
            startedAt = obj.string("startedAt"),
            completedAt = obj.string("completedAt"),
            bolusesUploaded = obj.int("bolusesUploaded"),
            pumpNoteUploaded = obj.bool("pumpNoteUploaded"),
            error = obj.stringOrNull("error"),
            diagnostics = obj.stringOrNull("diagnostics"),
            executionArn = obj.string("executionArn"),
            glookoOk = if (obj.containsKey("glookoOk")) obj.bool("glookoOk") else null,
            nightscoutOk = if (obj.containsKey("nightscoutOk")) obj.bool("nightscoutOk") else null,
        )
    }

    suspend fun getRunRecords(runId: String): List<SyncedRecordSummary> {
        val resp = api.getRunRecords(runId)
        val records = resp["records"]?.jsonArray ?: return emptyList()
        return records.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            SyncedRecordSummary(
                dedupeKey = obj.string("dedupeKey"),
                eventType = obj.string("eventType"),
                createdAt = obj.string("createdAt"),
                uploadedAtEpochMs = obj.long("uploadedAtEpochMs"),
                runId = obj.string("runId", runId),
            )
        }
    }

    suspend fun tripCircuitBreaker(): CircuitBreakerStatus =
        parseCircuitBreaker(api.tripCircuitBreaker())

    suspend fun resetCircuitBreaker(): CircuitBreakerStatus =
        parseCircuitBreaker(api.resetCircuitBreaker())

    private fun parseCircuitBreaker(status: kotlinx.serialization.json.JsonObject): CircuitBreakerStatus =
        CircuitBreakerStatus(
            syncEnabled = status.bool("syncEnabled", true),
            syncAllowed = status.bool("syncAllowed", true),
            circuitBreakerTripped = status.bool("circuitBreakerTripped"),
            trippedAt = status.stringOrNull("trippedAt"),
            trippedReason = status.stringOrNull("trippedReason"),
            overrideUntil = status.stringOrNull("overrideUntil"),
            overrideActive = status.bool("overrideActive"),
            syncPaused = status.bool("syncPaused"),
        )

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
