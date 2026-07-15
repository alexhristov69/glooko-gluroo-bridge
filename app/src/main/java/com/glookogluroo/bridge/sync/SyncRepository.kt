package com.glookogluroo.bridge.sync

import com.glookogluroo.bridge.data.AppSettings
import com.glookogluroo.bridge.data.SettingsRepository
import com.glookogluroo.bridge.glooko.DeviceInfo
import com.glookogluroo.bridge.glooko.GlookoClient
import com.glookogluroo.bridge.glooko.GlookoParser
import com.glookogluroo.bridge.glooko.PumpStatistics
import com.glookogluroo.bridge.nightscout.NightscoutClient
import com.glookogluroo.bridge.nightscout.NightscoutJson
import com.glookogluroo.bridge.nightscout.NightscoutTreatment
import com.glookogluroo.bridge.nightscout.TreatmentMapper
import com.glookogluroo.bridge.sync.db.AppDatabase
import com.glookogluroo.bridge.sync.db.SyncState
import com.glookogluroo.bridge.sync.db.SyncedRecord
import com.glookogluroo.bridge.util.ErrorFormatter
import com.glookogluroo.bridge.util.JsonFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    val success: Boolean,
    val bolusesUploaded: Int = 0,
    val pumpNoteUploaded: Boolean = false,
    val devices: List<DeviceInfo> = emptyList(),
    val pumpStatistics: PumpStatistics? = null,
    val syncPreview: SyncPreview? = null,
    val error: String? = null,
    val diagnostics: String? = null,
)

data class ConnectionTestResult(
    val glookoOk: Boolean,
    val nightscoutOk: Boolean,
    val glookoError: String? = null,
    val nightscoutError: String? = null,
    val diagnostics: String = "",
    val syncPreview: SyncPreview? = null,
)

@Singleton
class SyncRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val database: AppDatabase,
    private val syncScheduler: SyncScheduler,
) {
    private val syncedRecordDao = database.syncedRecordDao()
    private val syncStateDao = database.syncStateDao()

    suspend fun getSyncState(): SyncState {
        return syncStateDao.get() ?: SyncState()
    }

    fun observeSyncState(): Flow<SyncState> {
        return syncStateDao.observe().map { it ?: SyncState() }
    }

    suspend fun scheduleBackgroundSync(intervalMinutes: Int) {
        withContext(Dispatchers.IO) {
            val minutes = intervalMinutes.coerceIn(
                SyncScheduler.MIN_INTERVAL_MINUTES,
                SyncScheduler.MAX_INTERVAL_MINUTES,
            )
            syncScheduler.schedule(minutes)
            updateNextScheduledSync(minutes)
        }
    }

    suspend fun rescheduleBackgroundSync() {
        val settings = settingsRepository.getSettings()
        if (!settings.syncEnabled) return
        scheduleBackgroundSync(settings.syncIntervalMinutes)
    }

    suspend fun cancelBackgroundSync() {
        withContext(Dispatchers.IO) {
            syncScheduler.cancel()
            val state = syncStateDao.get() ?: SyncState()
            syncStateDao.upsert(state.copy(nextScheduledSyncEpochMs = 0))
        }
    }

    private suspend fun updateNextScheduledSync(intervalMinutes: Int) {
        val state = syncStateDao.get() ?: SyncState()
        syncStateDao.upsert(
            state.copy(
                nextScheduledSyncEpochMs = System.currentTimeMillis() + intervalMinutes * 60_000L,
            ),
        )
    }

    suspend fun resetLastSync() {
        withContext(Dispatchers.IO) {
            val state = syncStateDao.get() ?: SyncState()
            syncStateDao.upsert(
                state.copy(
                    lastSuccessfulSyncEpochMs = 0,
                    lastBolusesUploaded = 0,
                    lastError = null,
                ),
            )
        }
    }

    suspend fun clearUploadHistory() {
        withContext(Dispatchers.IO) {
            syncedRecordDao.deleteAll()
            val state = syncStateDao.get() ?: SyncState()
            syncStateDao.upsert(state.copy(lastPumpModeNote = null))
        }
    }

    suspend fun testConnections(settings: AppSettings = settingsRepository.getSettings()): ConnectionTestResult {
        return withContext(Dispatchers.IO) {
            val log = StringBuilder()
            var glookoOk = false
            var nightscoutOk = false
            var glookoError: String? = null
            var nightscoutError: String? = null

            log.appendLine("=== Glooko ===")
            var glookoClient: GlookoClient? = null
            runCatching {
                log.appendLine("Authenticating as ${settings.glookoEmail}...")
                glookoClient = createGlookoClient(settings)
                glookoClient!!.sessionSummary()?.let {
                    log.appendLine("Session:")
                    log.appendLine(it)
                }
                log.appendLine("Fetching device settings...")
                val testResult = glookoClient!!.testConnection().getOrThrow()
                glookoOk = true
                log.appendLine("OK — ${testResult.deviceCount} device(s) found")
                log.appendLine("patient=${testResult.patientId}")
                log.appendLine("api=${testResult.apiBase}")
                log.appendLine("origin=${testResult.dashboardOrigin}")
            }.onFailure { error ->
                glookoError = ErrorFormatter.describe(error)
                log.appendLine("FAILED")
                log.appendLine(glookoError)
            }

            log.appendLine()
            log.appendLine("=== Gluroo / Nightscout ===")
            runCatching {
                log.appendLine("URL: ${settings.nightscoutUrl}")
                log.appendLine(
                    "Auth: ${if (settings.useTokenAuth) "token query param" else "api-secret header (SHA1)"}",
                )
                val client = createNightscoutClient(settings)
                val details = client.testConnection().getOrThrow()
                nightscoutOk = true
                log.appendLine("OK")
                log.appendLine(details)
            }.onFailure { error ->
                nightscoutError = ErrorFormatter.describe(error)
                log.appendLine("FAILED")
                log.appendLine(nightscoutError)
            }

            var syncPreview: SyncPreview? = null
            if (glookoOk) {
                log.appendLine()
                runCatching {
                    syncPreview = buildSyncPreview(settings, glookoClient)
                    log.append(syncPreview!!.formatDiagnostics())
                }.onFailure { error ->
                    val previewError = ErrorFormatter.describe(error)
                    log.appendLine("=== Mock sync preview (not uploaded) ===")
                    log.appendLine("FAILED")
                    log.appendLine(previewError)
                }
            }

            ConnectionTestResult(
                glookoOk = glookoOk,
                nightscoutOk = nightscoutOk,
                glookoError = glookoError,
                nightscoutError = nightscoutError,
                diagnostics = log.toString().trimEnd(),
                syncPreview = syncPreview,
            )
        }
    }

    suspend fun runSync(settings: AppSettings = settingsRepository.getSettings()): SyncResult {
        if (!settingsRepository.isConfigured()) {
            return SyncResult(success = false, error = "Credentials are not configured")
        }

        return withContext(Dispatchers.IO) {
            val log = StringBuilder()
            var prepared: SyncPreview? = null
            try {
                log.appendLine("=== Glooko ===")
                log.appendLine("Authenticating as ${settings.glookoEmail}...")
                val glookoClient = createGlookoClient(settings)
                glookoClient.sessionSummary()?.let {
                    log.appendLine("Session:")
                    log.appendLine(it)
                }
                log.appendLine("Fetching graph data and device settings...")
                prepared = buildSyncPreview(settings, glookoClient)
                log.appendLine("OK — ${prepared.devices.size} device(s) found")
                log.appendLine()
                log.append(prepared.formatDiagnostics(dryRun = false))

                log.appendLine()
                log.appendLine("=== Gluroo / Nightscout ===")
                log.appendLine("URL: ${settings.nightscoutUrl}")
                log.appendLine(
                    "Auth: ${if (settings.useTokenAuth) "token query param" else "api-secret header (SHA1)"}",
                )
                val nightscoutClient = createNightscoutClient(settings)
                val statusDetails = nightscoutClient.testConnection().getOrThrow()
                log.appendLine("Status check OK")
                log.appendLine(statusDetails)

                val treatmentsToUpload = prepared.treatmentsToUpload
                if (treatmentsToUpload.isEmpty()) {
                    log.appendLine()
                    log.appendLine("Nothing new to upload — sync finished")
                    val existingState = syncStateDao.get() ?: SyncState()
                    syncStateDao.upsert(
                        existingState.copy(
                            lastError = null,
                            lastBolusesUploaded = 0,
                        ),
                    )
                    return@withContext SyncResult(
                        success = true,
                        bolusesUploaded = 0,
                        pumpNoteUploaded = false,
                        devices = prepared.devices,
                        pumpStatistics = prepared.pumpStatistics,
                        syncPreview = prepared,
                        diagnostics = log.toString().trimEnd(),
                    )
                }

                log.appendLine()
                val uploadReport = nightscoutClient.postTreatments(treatmentsToUpload).getOrThrow()
                log.append(uploadReport.formatDiagnostics())

                val uploadedAt = System.currentTimeMillis()
                val newBolusTreatments = treatmentsToUpload.filter { it.eventType.endsWith("Bolus") }
                val pumpNoteTreatment = treatmentsToUpload.firstOrNull { TreatmentMapper.isPumpModeNote(it) }
                val newRecords = treatmentsToUpload.map {
                    SyncedRecord(
                        dedupeKey = TreatmentMapper.deduplicationKey(it),
                        eventType = it.eventType,
                        createdAt = it.createdAt,
                        uploadedAtEpochMs = uploadedAt,
                    )
                }
                if (newRecords.isNotEmpty()) {
                    syncedRecordDao.insertAll(newRecords)
                }

                log.appendLine()
                log.appendLine("=== Sync complete ===")
                log.appendLine("Uploaded ${uploadReport.uploadedCount} treatment(s)")

                val existingState = syncStateDao.get() ?: SyncState()
                syncStateDao.upsert(
                    existingState.copy(
                        lastSuccessfulSyncEpochMs = prepared.syncWindowEnd.toEpochMilli(),
                        lastPumpModeNote = pumpNoteTreatment?.notes ?: existingState.lastPumpModeNote,
                        lastError = null,
                        lastBolusesUploaded = newBolusTreatments.size,
                    ),
                )

                SyncResult(
                    success = true,
                    bolusesUploaded = newBolusTreatments.size,
                    pumpNoteUploaded = pumpNoteTreatment != null,
                    devices = prepared.devices,
                    pumpStatistics = prepared.pumpStatistics,
                    syncPreview = prepared,
                    diagnostics = log.toString().trimEnd(),
                )
            } catch (e: Exception) {
                val errorText = ErrorFormatter.describe(e)
                log.appendLine()
                log.appendLine("FAILED")
                log.appendLine(errorText)
                val existingState = syncStateDao.get() ?: SyncState()
                syncStateDao.upsert(existingState.copy(lastError = errorText))
                SyncResult(
                    success = false,
                    error = errorText,
                    syncPreview = prepared,
                    diagnostics = log.toString().trimEnd(),
                )
            }
        }
    }

    private suspend fun buildSyncPreview(
        settings: AppSettings,
        existingClient: GlookoClient? = null,
    ): SyncPreview {
        val now = Instant.now()
        val existingState = syncStateDao.get() ?: SyncState()
        val window = SyncWindowResolver.resolve(settings, existingState, now)
        val start = window.start

        val glookoClient = existingClient ?: createGlookoClient(settings)
        val graphData = glookoClient.getGraphData(start, now)
            ?: error("Failed to fetch Glooko graph data (null response — check auth and API access)")
        val statisticsData = glookoClient.getStatistics(start, now)
        val deviceData = glookoClient.getDeviceSettings()

        val boluses = GlookoParser.parseBolusEntries(graphData)
        val pumpStats = statisticsData?.let { GlookoParser.parsePumpMode(it) }
        val devices = deviceData?.let { GlookoParser.parseDevices(it) }.orEmpty()

        val knownKeys = syncedRecordDao.getAllKeys().toSet()
        val newBolusTreatments = boluses.flatMap { bolus ->
            val uploads = TreatmentMapper.bolusUploadTreatments(
                bolus = bolus,
                jitterTimestamps = settings.jitterInsulinTimestamps,
            )
            val bolusTreatment = uploads.first()
            if (TreatmentMapper.deduplicationKey(bolusTreatment) in knownKeys) {
                emptyList()
            } else {
                uploads
            }
        }

        val pumpNoteTreatment = if (settings.postPumpModeNotes && pumpStats != null) {
            buildPumpModeNote(pumpStats, existingState.lastPumpModeNote)
        } else {
            null
        }

        val treatmentsToUpload = buildList {
            addAll(newBolusTreatments)
            pumpNoteTreatment?.let { add(it) }
        }

        return SyncPreview(
            syncWindowStart = start,
            syncWindowEnd = now,
            windowSource = window.source,
            bolusesFound = boluses.size,
            bolusesAlreadySynced = boluses.size - newBolusTreatments.count { it.eventType.endsWith("Bolus") },
            treatmentsToUpload = treatmentsToUpload,
            devices = devices,
            pumpStatistics = pumpStats,
            jsonPayload = if (treatmentsToUpload.isEmpty()) {
                "[]"
            } else {
                JsonFormatter.prettify(NightscoutJson.encodeTreatments(treatmentsToUpload))
            },
            jitterInsulinTimestamps = settings.jitterInsulinTimestamps,
        )
    }

    private fun buildPumpModeNote(
        pumpStats: PumpStatistics,
        lastPumpModeNote: String?,
    ): NightscoutTreatment? {
        val note = TreatmentMapper.pumpModeToNote(pumpStats)
        return if (note.notes == lastPumpModeNote) null else note
    }

    private fun createGlookoClient(settings: AppSettings): GlookoClient {
        return GlookoClient(
            email = settings.glookoEmail,
            password = settings.glookoPassword,
        )
    }

    private fun createNightscoutClient(settings: AppSettings): NightscoutClient {
        return NightscoutClient(
            baseUrl = settings.nightscoutUrl,
            apiSecret = settings.nightscoutSecret,
            useTokenAuth = settings.useTokenAuth,
        )
    }
}
