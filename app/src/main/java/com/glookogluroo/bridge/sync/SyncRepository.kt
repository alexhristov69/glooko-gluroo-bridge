package com.glookogluroo.bridge.sync

import com.glookogluroo.bridge.data.AppSettings
import com.glookogluroo.bridge.data.SettingsRepository
import com.glookogluroo.bridge.glooko.DeviceInfo
import com.glookogluroo.bridge.glooko.GlookoClient
import com.glookogluroo.bridge.glooko.GlookoParser
import com.glookogluroo.bridge.glooko.PumpStatistics
import com.glookogluroo.bridge.nightscout.NightscoutClient
import com.glookogluroo.bridge.nightscout.NightscoutTreatment
import com.glookogluroo.bridge.nightscout.TreatmentMapper
import com.glookogluroo.bridge.sync.db.AppDatabase
import com.glookogluroo.bridge.sync.db.SyncState
import com.glookogluroo.bridge.sync.db.SyncedRecord
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

data class SyncResult(
    val success: Boolean,
    val bolusesUploaded: Int = 0,
    val pumpNoteUploaded: Boolean = false,
    val devices: List<DeviceInfo> = emptyList(),
    val pumpStatistics: PumpStatistics? = null,
    val error: String? = null,
)

data class ConnectionTestResult(
    val glookoOk: Boolean,
    val nightscoutOk: Boolean,
    val glookoError: String? = null,
    val nightscoutError: String? = null,
)

@Singleton
class SyncRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val database: AppDatabase,
) {
    private val syncedRecordDao = database.syncedRecordDao()
    private val syncStateDao = database.syncStateDao()

    suspend fun getSyncState(): SyncState {
        return syncStateDao.get() ?: SyncState()
    }

    suspend fun testConnections(settings: AppSettings = settingsRepository.getSettings()): ConnectionTestResult {
        var glookoOk = false
        var nightscoutOk = false
        var glookoError: String? = null
        var nightscoutError: String? = null

        runCatching {
            val client = createGlookoClient(settings)
            glookoOk = client.testConnection()
            if (!glookoOk) glookoError = "Glooko connection test failed"
        }.onFailure {
            glookoError = it.message
        }

        runCatching {
            val client = createNightscoutClient(settings)
            client.testConnection().getOrThrow()
            nightscoutOk = true
        }.onFailure {
            nightscoutError = it.message
        }

        return ConnectionTestResult(
            glookoOk = glookoOk,
            nightscoutOk = nightscoutOk,
            glookoError = glookoError,
            nightscoutError = nightscoutError,
        )
    }

    suspend fun runSync(): SyncResult {
        val settings = settingsRepository.getSettings()
        if (!settingsRepository.isConfigured()) {
            return SyncResult(success = false, error = "Credentials are not configured")
        }

        return try {
            val now = Instant.now()
            val existingState = syncStateDao.get() ?: SyncState()
            val start = if (existingState.lastSuccessfulSyncEpochMs == 0L) {
                now.minus(settings.backfillDays.toLong(), ChronoUnit.DAYS)
            } else {
                Instant.ofEpochMilli(existingState.lastSuccessfulSyncEpochMs)
                    .minus(2, ChronoUnit.HOURS)
            }

            val glookoClient = createGlookoClient(settings)
            val graphData = glookoClient.getGraphData(start, now)
                ?: return fail("Failed to fetch Glooko graph data")
            val statisticsData = glookoClient.getStatistics(start, now)
            val deviceData = glookoClient.getDeviceSettings()

            val boluses = GlookoParser.parseBolusEntries(graphData)
            val pumpStats = statisticsData?.let { GlookoParser.parsePumpMode(it) }
            val devices = deviceData?.let { GlookoParser.parseDevices(it) }.orEmpty()

            val knownKeys = syncedRecordDao.getAllKeys().toSet()
            val bolusTreatments = boluses.map { TreatmentMapper.bolusToTreatment(it) }
            val newBolusTreatments = bolusTreatments.filter {
                TreatmentMapper.deduplicationKey(it) !in knownKeys
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

            val nightscoutClient = createNightscoutClient(settings)
            nightscoutClient.postTreatments(treatmentsToUpload).getOrThrow()

            val uploadedAt = System.currentTimeMillis()
            val newRecords = newBolusTreatments.map {
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
            if (pumpNoteTreatment != null) {
                syncedRecordDao.insert(
                    SyncedRecord(
                        dedupeKey = TreatmentMapper.deduplicationKey(pumpNoteTreatment),
                        eventType = pumpNoteTreatment.eventType,
                        createdAt = pumpNoteTreatment.createdAt,
                        uploadedAtEpochMs = uploadedAt,
                    ),
                )
            }

            syncStateDao.upsert(
                existingState.copy(
                    lastSuccessfulSyncEpochMs = now.toEpochMilli(),
                    lastPumpModeNote = pumpNoteTreatment?.notes ?: existingState.lastPumpModeNote,
                    lastError = null,
                    lastBolusesUploaded = newBolusTreatments.size,
                ),
            )

            SyncResult(
                success = true,
                bolusesUploaded = newBolusTreatments.size,
                pumpNoteUploaded = pumpNoteTreatment != null,
                devices = devices,
                pumpStatistics = pumpStats,
            )
        } catch (e: Exception) {
            val existingState = syncStateDao.get() ?: SyncState()
            syncStateDao.upsert(
                existingState.copy(lastError = e.message ?: "Unknown sync error"),
            )
            SyncResult(success = false, error = e.message)
        }
    }

    private suspend fun fail(message: String): SyncResult {
        val existingState = syncStateDao.get() ?: SyncState()
        syncStateDao.upsert(existingState.copy(lastError = message))
        return SyncResult(success = false, error = message)
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
