package com.glookogluroo.bridge.sync.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "synced_records")
data class SyncedRecord(
    @PrimaryKey val dedupeKey: String,
    val eventType: String,
    val createdAt: String,
    val uploadedAtEpochMs: Long,
)

@Dao
interface SyncedRecordDao {
    @Query("SELECT dedupeKey FROM synced_records")
    suspend fun getAllKeys(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: SyncedRecord)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(records: List<SyncedRecord>)

    @Query("DELETE FROM synced_records")
    suspend fun deleteAll()
}

@Entity(tableName = "sync_state")
data class SyncState(
    @PrimaryKey val id: Int = 1,
    val lastSuccessfulSyncEpochMs: Long = 0,
    val nextScheduledSyncEpochMs: Long = 0,
    val lastPumpModeNote: String? = null,
    val lastError: String? = null,
    val lastBolusesUploaded: Int = 0,
)

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncState?

    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncState)
}
