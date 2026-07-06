package com.glookogluroo.bridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glookogluroo.bridge.data.AppSettings
import com.glookogluroo.bridge.data.SettingsRepository
import com.glookogluroo.bridge.glooko.DeviceInfo
import com.glookogluroo.bridge.glooko.PumpStatistics
import com.glookogluroo.bridge.sync.ConnectionTestResult
import com.glookogluroo.bridge.sync.SyncRepository
import com.glookogluroo.bridge.sync.SyncScheduler
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
    val pumpStatistics: PumpStatistics? = null,
    val devices: List<DeviceInfo> = emptyList(),
    val isBusy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val settings = settingsRepository.getSettings()
            val syncState = syncRepository.getSyncState()
            _uiState.update {
                it.copy(settings = settings, syncState = syncState)
            }
            if (settings.syncEnabled) {
                syncScheduler.schedule(settings.syncIntervalMinutes)
            }
        }
    }

    fun updateGlookoEmail(value: String) = updateSettings { it.copy(glookoEmail = value) }
    fun updateGlookoPassword(value: String) = updateSettings { it.copy(glookoPassword = value) }
    fun updateNightscoutUrl(value: String) = updateSettings { it.copy(nightscoutUrl = value) }
    fun updateNightscoutSecret(value: String) = updateSettings { it.copy(nightscoutSecret = value) }
    fun updateUseTokenAuth(value: Boolean) = updateSettings { it.copy(useTokenAuth = value) }
    fun updateSyncEnabled(value: Boolean) = updateSettings { it.copy(syncEnabled = value) }
    fun updatePostPumpModeNotes(value: Boolean) = updateSettings { it.copy(postPumpModeNotes = value) }

    fun updateBackfillDays(value: String) {
        val days = value.toIntOrNull() ?: return
        updateSettings { it.copy(backfillDays = days) }
    }

    fun updateSyncIntervalMinutes(value: String) {
        val minutes = value.toIntOrNull() ?: return
        updateSettings { it.copy(syncIntervalMinutes = minutes) }
    }

    fun saveSettings() {
        viewModelScope.launch {
            val settings = _uiState.value.settings
            settingsRepository.saveSettings(settings)
            if (settings.syncEnabled) {
                syncScheduler.schedule(settings.syncIntervalMinutes)
            } else {
                syncScheduler.cancel()
            }
            _uiState.update { it.copy(message = "Settings saved", error = null) }
        }
    }

    fun testConnections() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null, error = null) }
            val result = syncRepository.testConnections(_uiState.value.settings)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    connectionTest = result,
                    message = if (result.glookoOk && result.nightscoutOk) {
                        "Both connections succeeded"
                    } else {
                        null
                    },
                    error = when {
                        result.glookoOk && result.nightscoutOk -> null
                        else -> listOfNotNull(result.glookoError, result.nightscoutError)
                            .joinToString(" | ")
                            .ifBlank { "Connection test failed" }
                    },
                )
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true, message = null, error = null) }
            val result = syncRepository.runSync()
            val syncState = syncRepository.getSyncState()
            _uiState.update {
                it.copy(
                    isBusy = false,
                    syncState = syncState,
                    pumpStatistics = result.pumpStatistics,
                    devices = result.devices,
                    message = if (result.success) {
                        "Sync complete: ${result.bolusesUploaded} boluses uploaded" +
                            if (result.pumpNoteUploaded) ", pump note posted" else ""
                    } else {
                        null
                    },
                    error = result.error,
                )
            }
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _uiState.update { it.copy(settings = transform(it.settings), message = null, error = null) }
    }
}
