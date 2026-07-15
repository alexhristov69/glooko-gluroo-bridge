package com.glookogluroo.bridge.sync

import com.glookogluroo.bridge.data.AppSettings
import com.glookogluroo.bridge.sync.db.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

class SyncWindowResolverTest {
    @Test
    fun resolve_usesBackfillWhenNeverSynced() {
        val now = Instant.parse("2026-07-06T18:00:00Z")
        val window = SyncWindowResolver.resolve(
            settings = AppSettings(backfillDays = 7),
            syncState = SyncState(),
            now = now,
        )

        assertEquals(now.minus(7, ChronoUnit.DAYS), window.start)
        assertTrue(window.source.contains("backfill"))
    }

    @Test
    fun resolve_usesIncrementalAfterSync() {
        val now = Instant.parse("2026-07-06T18:00:00Z")
        val lastSync = Instant.parse("2026-07-06T10:16:17Z")
        val window = SyncWindowResolver.resolve(
            settings = AppSettings(),
            syncState = SyncState(lastSuccessfulSyncEpochMs = lastSync.toEpochMilli()),
            now = now,
        )

        assertEquals(lastSync.minus(2, ChronoUnit.HOURS), window.start)
        assertTrue(window.source.contains("incremental"))
    }

    @Test
    fun resolve_customStartOverridesIncremental() {
        val now = Instant.parse("2026-07-06T18:00:00Z")
        val customInput = "2026-07-01 08:00"
        val expectedStart = SyncWindowResolver.parseCustomStart(customInput)
        requireNotNull(expectedStart)
        val window = SyncWindowResolver.resolve(
            settings = AppSettings(syncFromOverride = customInput),
            syncState = SyncState(lastSuccessfulSyncEpochMs = now.minus(1, java.time.temporal.ChronoUnit.DAYS).toEpochMilli()),
            now = now,
        )

        assertEquals(expectedStart, window.start)
        assertTrue(window.source.contains("custom start"))
    }
}
