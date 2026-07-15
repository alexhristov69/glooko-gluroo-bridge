package com.glookogluroo.bridge.sync.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SyncedRecord::class, SyncState::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncedRecordDao(): SyncedRecordDao
    abstract fun syncStateDao(): SyncStateDao
}
