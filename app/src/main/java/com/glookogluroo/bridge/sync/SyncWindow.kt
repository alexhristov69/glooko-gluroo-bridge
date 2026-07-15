package com.glookogluroo.bridge.sync

import com.glookogluroo.bridge.data.AppSettings
import com.glookogluroo.bridge.sync.db.SyncState
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

data class SyncWindow(
    val start: Instant,
    val end: Instant,
    val source: String,
)

object SyncWindowResolver {
    private val localInputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun resolve(
        settings: AppSettings,
        syncState: SyncState,
        now: Instant = Instant.now(),
    ): SyncWindow {
        parseCustomStart(settings.syncFromOverride)?.let { customStart ->
            val clampedStart = if (customStart.isAfter(now)) now else customStart
            return SyncWindow(
                start = clampedStart,
                end = now,
                source = "custom start (${formatLocal(clampedStart)})",
            )
        }

        if (syncState.lastSuccessfulSyncEpochMs == 0L) {
            val start = now.minus(settings.backfillDays.toLong(), ChronoUnit.DAYS)
            return SyncWindow(
                start = start,
                end = now,
                source = "backfill (${settings.backfillDays} days, no prior sync)",
            )
        }

        val lastSync = Instant.ofEpochMilli(syncState.lastSuccessfulSyncEpochMs)
        val start = lastSync.minus(2, ChronoUnit.HOURS)
        return SyncWindow(
            start = start,
            end = now,
            source = "incremental (last sync ${formatLocal(lastSync)} − 2h overlap)",
        )
    }

    fun parseCustomStart(value: String): Instant? {
        if (value.isBlank()) return null
        return try {
            LocalDateTime.parse(value.trim(), localInputFormatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }

    fun formatLocal(instant: Instant): String {
        return localInputFormatter.format(instant.atZone(ZoneId.systemDefault()))
    }

    fun isValidCustomStartInput(value: String): Boolean {
        return value.isBlank() || parseCustomStart(value) != null
    }
}
