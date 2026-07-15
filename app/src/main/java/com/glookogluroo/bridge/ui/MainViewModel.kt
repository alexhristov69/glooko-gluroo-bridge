package com.glookogluroo.bridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glookogluroo.bridge.cloud.CloudConfig
import com.glookogluroo.bridge.cloud.CloudSyncRepository
import com.glookogluroo.bridge.cloud.CognitoAuthRepository
import com.glookogluroo.bridge.data.AppSettings
import com.glookogluroo.bridge.data.SettingsRepository
import com.glookogluroo.bridge.glooko.DeviceInfo
import com.glookogluroo.bridge.glooko.PumpStatistics
import com.glookogluroo.bridge.sync.ConnectionTestResult
import com.glookogluroo.bridge.sync.SyncPreview
import com.glookogluroo.bridge.sync.SyncRepository
import com.glookogluroo.bridge.sync.SyncWindowResolver
import com.glookogluroo.bridge.sync.db.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val settings: AppSettings = AppSettings(),
    val syncState: SyncState = SyncState(),
    val connectionTest: ConnectionTestResult? = null,
    val syncPreview: SyncPreview? = null,
    val syncPreviewDryRun: Boolean = true,
    val pumpStatistics: PumpStatistics? = null,
    val devices: List<DeviceInfo> = emptyList(),
    val isBusy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val diagnostics: String? = null,
    val signedIn: Boolean = false,
    val authEmail: String? = null,
    val cloudEnabled: Boolean = false,
    val cloudPausedBanner: String? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val authRepository: CognitoAuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState(cloudEnabled = CloudConfig.enabled))
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        load()
        if (!CloudConfig.enabled) {
            viewModelScope.launch {
                syncRepository.observeSyncState().collect { syncState ->
                    _uiState.update { it.copy(syncState = syncState) }
                }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val settings = settingsRepository.getSettings()
            val session = authRepository.getCachedSession()
            val signedIn = session != null
            // Local WorkManager only when cloud is disabled
            if (!CloudConfig.enabled && settings.syncEnabled) {
                syncRepository.scheduleBackgroundSync(settings.syncIntervalMinutes)
            } else {
                syncRepository.cancelBackgroundSync()
            }
            var syncState = if (CloudConfig.enabled && signedIn) {
                runCatching { cloudSyncRepository.getSyncState() }.getOrElse { SyncState() }
            } else {
                syncRepository.getSyncState()
            }
            var mergedSettings = settings
            if (CloudConfig.enabled && signedIn) {
                cloudSyncRepository.hydrateSettingsFromCloud()?.let { mergedSettings = it }
            }
            val banner = if (CloudConfig.enabled && signedIn) {
                cloudSyncRepository.cloudStatusBanner()
            } else {
                null
            }
            _uiState.update {
                it.copy(
                    settings = mergedSettings,
                    syncState = syncState,
                    signedIn = signedIn || !CloudConfig.enabled,
                    authEmail = session?.email,
                    cloudPausedBanner = banner,
                )
            }
        }
    }

    fun signIn(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null, message = null) }
            authRepository.signIn(email, password)
                .onSuccess {
                    _uiState.update {
                        it.copy(isBusy = false, signedIn = true, authEmail = email, message = "Signed in")
                    }
                    load()
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isBusy = false, error = e.message) }
                }
        }
    }

    fun signUp(email: String, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null, message = null) }
            authRepository.signUp(email, password)
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isBusy = false,
                            message = "Check your email for a confirmation code, then tap Confirm",
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isBusy = false, error = e.message) }
                }
        }
    }

    fun confirmSignUp(email: String, code: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, error = null, message = null) }
            authRepository.confirmSignUp(email, code)
                .onSuccess {
                    _uiState.update {
                        it.copy(isBusy = false, message = "Confirmed — now sign in")
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isBusy = false, error = e.message) }
                }
        }
    }

    fun signOut() {
        authRepository.signOut()
        _uiState.update {
            it.copy(signedIn = !CloudConfig.enabled, authEmail = null, message = "Signed out")
        }
    }

    fun updateGlookoEmail(value: String) = updateSettings { it.copy(glookoEmail = value) }
    fun updateGlookoPassword(value: String) = updateSettings { it.copy(glookoPassword = value) }
    fun updateNightscoutUrl(value: String) = updateSettings { it.copy(nightscoutUrl = value) }
    fun updateNightscoutSecret(value: String) = updateSettings { it.copy(nightscoutSecret = value) }
    fun updateUseTokenAuth(value: Boolean) = updateSettings { it.copy(useTokenAuth = value) }
    fun updateSyncEnabled(value: Boolean) = updateSettings { it.copy(syncEnabled = value) }
    fun updatePostPumpModeNotes(value: Boolean) = updateSettings { it.copy(postPumpModeNotes = value) }
    fun updateJitterInsulinTimestamps(value: Boolean) =
        updateSettings { it.copy(jitterInsulinTimestamps = value) }

    fun updateBackfillDays(value: String) {
        val days = value.toIntOrNull() ?: return
        updateSettings { it.copy(backfillDays = days) }
    }

    fun updateSyncFromOverride(value: String) = updateSettings { it.copy(syncFromOverride = value) }

    fun updateSyncIntervalMinutes(value: String) {
        val minutes = value.toIntOrNull() ?: return
        updateSettings { it.copy(syncIntervalMinutes = minutes) }
    }

    fun saveSettings() {
        viewModelScope.launch {
            val settings = _uiState.value.settings
            if (!SyncWindowResolver.isValidCustomStartInput(settings.syncFromOverride)) {
                _uiState.update {
                    it.copy(
                        error = "Sync from must be blank or yyyy-MM-dd HH:mm (local time)",
                        message = null,
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(isBusy = true, error = null) }
            try {
                if (CloudConfig.enabled) {
                    cloudSyncRepository.saveSettings(settings)
                } else {
                    settingsRepository.saveSettings(settings)
                    if (settings.syncEnabled) {
                        syncRepository.scheduleBackgroundSync(settings.syncIntervalMinutes)
                    } else {
                        syncRepository.cancelBackgroundSync()
                    }
                }
                val syncState = if (CloudConfig.enabled) {
                    cloudSyncRepository.getSyncState()
                } else {
                    syncRepository.getSyncState()
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        message = if (CloudConfig.enabled) {
                            "Settings saved to AWS"
                        } else {
                            "Settings saved"
                        },
                        error = null,
                        syncState = syncState,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, error = e.message) }
            }
        }
    }

    fun resetLastSync() {
        viewModelScope.launch {
            if (CloudConfig.enabled) {
                cloudSyncRepository.resetLastSync()
            } else {
                syncRepository.resetLastSync()
            }
            val syncState = if (CloudConfig.enabled) {
                cloudSyncRepository.getSyncState()
            } else {
                syncRepository.getSyncState()
            }
            _uiState.update {
                it.copy(
                    syncState = syncState,
                    message = "Last sync reset — next sync uses backfill days (unless Sync from is set)",
                    error = null,
                )
            }
        }
    }

    fun clearUploadHistory() {
        viewModelScope.launch {
            if (CloudConfig.enabled) {
                cloudSyncRepository.clearUploadHistory()
            } else {
                syncRepository.clearUploadHistory()
            }
            _uiState.update {
                it.copy(
                    message = "Upload history cleared — previously synced boluses may upload again",
                    error = null,
                )
            }
        }
    }

    fun testConnections() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    message = null,
                    error = null,
                    diagnostics = null,
                    syncPreview = null,
                    syncPreviewDryRun = true,
                )
            }
            try {
                val result = if (CloudConfig.enabled) {
                    cloudSyncRepository.testConnections(_uiState.value.settings)
                } else {
                    syncRepository.testConnections(_uiState.value.settings)
                }
                val preview = result.syncPreview
                val uploadCount = preview?.cloudTreatmentCount
                    ?: preview?.treatmentsToUpload?.size
                    ?: 0
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        connectionTest = result,
                        syncPreview = preview,
                        syncPreviewDryRun = true,
                        diagnostics = result.diagnostics,
                        pumpStatistics = preview?.pumpStatistics,
                        devices = preview?.devices.orEmpty(),
                        message = when {
                            result.glookoOk && result.nightscoutOk -> {
                                if (uploadCount > 0) {
                                    "Connections OK — preview: $uploadCount treatment(s) would upload (not sent)"
                                } else {
                                    "Both connections succeeded — nothing new to upload"
                                }
                            }
                            result.glookoOk -> {
                                if (uploadCount > 0) {
                                    "Glooko OK — preview: $uploadCount treatment(s) would upload (not sent)"
                                } else {
                                    "Glooko OK — Nightscout failed"
                                }
                            }
                            else -> null
                        },
                        error = when {
                            result.glookoOk && result.nightscoutOk -> null
                            else -> buildList {
                                if (!result.glookoOk) {
                                    add("Glooko: ${result.glookoError ?: "failed"}")
                                }
                                if (!result.nightscoutOk) {
                                    add("Nightscout: ${result.nightscoutError ?: "failed"}")
                                }
                            }.joinToString("\n")
                        },
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, error = e.message) }
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isBusy = true,
                    message = null,
                    error = null,
                    diagnostics = null,
                    connectionTest = null,
                    syncPreview = null,
                )
            }
            try {
                val result = if (CloudConfig.enabled) {
                    cloudSyncRepository.runSync(_uiState.value.settings)
                } else {
                    syncRepository.runSync(_uiState.value.settings)
                }
                if (!CloudConfig.enabled && result.success && _uiState.value.settings.syncEnabled) {
                    syncRepository.rescheduleBackgroundSync()
                }
                val syncState = if (CloudConfig.enabled) {
                    cloudSyncRepository.getSyncState()
                } else {
                    syncRepository.getSyncState()
                }
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        syncState = syncState,
                        pumpStatistics = result.pumpStatistics,
                        devices = result.devices,
                        syncPreview = result.syncPreview,
                        syncPreviewDryRun = false,
                        diagnostics = result.diagnostics,
                        message = if (result.success) {
                            "Sync complete: ${result.bolusesUploaded} boluses uploaded" +
                                if (result.pumpNoteUploaded) ", pump note posted" else ""
                        } else {
                            null
                        },
                        error = result.error,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, error = e.message) }
            }
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _uiState.update { it.copy(settings = transform(it.settings), message = null, error = null) }
    }
}
