package com.glookogluroo.bridge.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.glookogluroo.bridge.data.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncRepository: SyncRepository,
    private val settingsRepository: SettingsRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.getSettings()
        if (!settings.syncEnabled || !settingsRepository.isConfigured()) {
            return Result.success()
        }

        val syncResult = syncRepository.runSync(settings)
        if (!settingsRepository.getSettings().syncEnabled) {
            return Result.success()
        }
        if (!syncResult.success) {
            return Result.retry()
        }

        syncRepository.rescheduleBackgroundSync()
        return Result.success()
    }
}
