package com.glookogluroo.bridge.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glookogluroo.bridge.cloud.BridgeConfigSnapshot
import com.glookogluroo.bridge.cloud.CircuitBreakerStatus
import com.glookogluroo.bridge.cloud.CloudConfig
import com.glookogluroo.bridge.cloud.CloudSyncRepository
import com.glookogluroo.bridge.cloud.CognitoAuthRepository
import com.glookogluroo.bridge.cloud.SyncRunSummary
import com.glookogluroo.bridge.cloud.SyncedRecordSummary
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
import kotlinx.coroutines.delay
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
    /** True while restoring a saved cloud session on cold start. */
    val isBootstrapping: Boolean = false,
    val bootstrapMessage: String? = null,
    val selectedTab: AppTab = AppTab.Stats,
    val isAdmin: Boolean = false,
    val bridgeConfig: BridgeConfigSnapshot = BridgeConfigSnapshot(),
    val circuitBreaker: CircuitBreakerStatus = CircuitBreakerStatus(),
    val recentRuns: List<SyncRunSummary> = emptyList(),
    val selectedRunId: String? = null,
    val selectedRun: SyncRunSummary? = null,
    val selectedRunRecords: List<SyncedRecordSummary> = emptyList(),
    val statsLoading: Boolean = false,
    /** When true, only show runs that uploaded at least one bolus or pump note. */
    val runsOnlyWithRecords: Boolean = false,
    /** 0-based page index for the sync-runs list. */
    val runsPage: Int = 0,
    /** True while pull/reset is waiting for verified backend commit. */
    val circuitBreakerPending: Boolean = false,
    val circuitBreakerPendingLabel: String? = null,
    /** Draft text for numeric fields so empty/partial input can be cleared while typing. */
    val backfillDaysText: String = AppSettings().backfillDays.toString(),
    val syncIntervalMinutesText: String = AppSettings().syncIntervalMinutes.toString(),
    /** Popup validation message for Settings Save/Test/Sync. */
    val settingsValidationError: String? = null,
) {
    companion object {
        const val RUNS_PAGE_SIZE = 10
    }

    val filteredRuns: List<SyncRunSummary>
        get() = if (runsOnlyWithRecords) {
            recentRuns.filter { it.hasSyncedRecords }
        } else {
            recentRuns
        }

    val runsTotalPages: Int
        get() = ((filteredRuns.size + RUNS_PAGE_SIZE - 1) / RUNS_PAGE_SIZE).coerceAtLeast(1)

    val runsPageClamped: Int
        get() = runsPage.coerceIn(0, (runsTotalPages - 1).coerceAtLeast(0))

    val pagedRuns: List<SyncRunSummary>
        get() {
            val start = runsPageClamped * RUNS_PAGE_SIZE
            return filteredRuns.drop(start).take(RUNS_PAGE_SIZE)
        }
}

private val SyncRunSummary.hasSyncedRecords: Boolean
    get() = bolusesUploaded > 0 || pumpNoteUploaded

enum class AppTab {
    Stats,
    Settings,
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val authRepository: CognitoAuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(createInitialUiState())
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

    private fun createInitialUiState(): MainUiState {
        val cloudEnabled = CloudConfig.enabled
        val hasStoredAuth = cloudEnabled && authRepository.isSignedIn()
        return MainUiState(
            cloudEnabled = cloudEnabled,
            isBootstrapping = hasStoredAuth,
            bootstrapMessage = if (hasStoredAuth) "Restoring session…" else null,
            authEmail = authRepository.getCachedSession()?.email,
        )
    }

    private fun setBootstrapMessage(message: String) {
        _uiState.update { it.copy(isBootstrapping = true, bootstrapMessage = message) }
    }

    private fun load() {
        viewModelScope.launch {
            val bootstrapping = _uiState.value.isBootstrapping
            try {
                if (bootstrapping) {
                    setBootstrapMessage("Restoring session…")
                }
                val settings = settingsRepository.getSettings()
                var session = authRepository.getCachedSession()
                var signedIn = session != null

                if (CloudConfig.enabled && bootstrapping) {
                    setBootstrapMessage("Refreshing sign-in…")
                    session = authRepository.refreshIfNeeded()
                    signedIn = session != null
                    if (!signedIn) {
                        authRepository.signOut()
                        _uiState.update {
                            it.copy(
                                settings = settings,
                                backfillDaysText = settings.backfillDays.toString(),
                                syncIntervalMinutesText = settings.syncIntervalMinutes.toString(),
                                isBootstrapping = false,
                                bootstrapMessage = null,
                                signedIn = false,
                                authEmail = null,
                                error = "Session expired — please sign in again",
                            )
                        }
                        return@launch
                    }
                }

                // Local WorkManager only when cloud is disabled
                if (!CloudConfig.enabled && settings.syncEnabled) {
                    syncRepository.scheduleBackgroundSync(settings.syncIntervalMinutes)
                } else {
                    syncRepository.cancelBackgroundSync()
                }

                var syncState = SyncState()
                var mergedSettings = settings
                var banner: String? = null

                if (CloudConfig.enabled && signedIn) {
                    if (bootstrapping) {
                        setBootstrapMessage("Loading sync status…")
                    }
                    syncState = runCatching { cloudSyncRepository.getSyncState() }
                        .getOrElse { SyncState() }
                    if (bootstrapping) {
                        setBootstrapMessage("Loading settings…")
                    }
                    cloudSyncRepository.hydrateSettingsFromCloud()?.let { mergedSettings = it }
                    banner = cloudSyncRepository.cloudStatusBanner()
                } else if (!CloudConfig.enabled) {
                    syncState = syncRepository.getSyncState()
                }

                _uiState.update {
                    it.copy(
                        settings = mergedSettings,
                        backfillDaysText = mergedSettings.backfillDays.toString(),
                        syncIntervalMinutesText = mergedSettings.syncIntervalMinutes.toString(),
                        syncState = syncState,
                        signedIn = signedIn || !CloudConfig.enabled,
                        authEmail = session?.email,
                        cloudPausedBanner = banner,
                        isBootstrapping = false,
                        bootstrapMessage = null,
                    )
                }
                if (CloudConfig.enabled && signedIn) {
                    refreshStats()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isBootstrapping = false,
                        bootstrapMessage = null,
                        signedIn = if (CloudConfig.enabled) {
                            authRepository.isSignedIn()
                        } else {
                            true
                        },
                        error = if (bootstrapping) {
                            e.message ?: "Could not connect to AWS"
                        } else {
                            it.error
                        },
                    )
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(selectedTab = tab, message = null, error = null) }
        if (tab == AppTab.Stats && CloudConfig.enabled && _uiState.value.signedIn) {
            refreshStats()
        }
    }

    fun refreshStats() {
        if (!CloudConfig.enabled || !_uiState.value.signedIn) return
        viewModelScope.launch {
            _uiState.update { it.copy(statsLoading = true, error = null) }
            try {
                val (config, cb, isAdmin) = cloudSyncRepository.fetchCloudStatus()
                val runs = cloudSyncRepository.listRuns(200)
                val state = _uiState.value
                val filtered = if (state.runsOnlyWithRecords) {
                    runs.filter { it.bolusesUploaded > 0 || it.pumpNoteUploaded }
                } else {
                    runs
                }
                val selectedId = when {
                    state.selectedRunId != null &&
                        filtered.any { it.runId == state.selectedRunId } -> state.selectedRunId
                    else -> filtered.firstOrNull()?.runId
                }
                var selectedRun: SyncRunSummary? = null
                var records: List<SyncedRecordSummary> = emptyList()
                if (selectedId != null) {
                    selectedRun = runCatching { cloudSyncRepository.getRunDetail(selectedId) }
                        .getOrElse { filtered.firstOrNull { it.runId == selectedId } }
                    records = runCatching { cloudSyncRepository.getRunRecords(selectedId) }
                        .getOrDefault(emptyList())
                }
                _uiState.update {
                    it.copy(
                        statsLoading = false,
                        bridgeConfig = config,
                        circuitBreaker = cb,
                        isAdmin = isAdmin,
                        recentRuns = runs,
                        selectedRunId = selectedId,
                        selectedRun = selectedRun,
                        selectedRunRecords = records,
                        syncState = SyncState(
                            lastSuccessfulSyncEpochMs = config.lastSuccessfulSyncEpochMs,
                            nextScheduledSyncEpochMs = config.nextScheduledSyncEpochMs,
                            lastError = config.lastError,
                            lastBolusesUploaded = config.lastBolusesUploaded,
                        ),
                        cloudPausedBanner = if (cb.syncPaused) {
                            "Sync paused (${cb.trippedReason ?: "cost guard"})."
                        } else {
                            null
                        },
                    )
                }
                // Clamp page after recentRuns update (computed getters use new list).
                _uiState.update { state ->
                    state.copy(
                        runsPage = state.runsPage.coerceIn(
                            0,
                            (state.runsTotalPages - 1).coerceAtLeast(0),
                        ),
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statsLoading = false, error = e.message)
                }
            }
        }
    }

    fun selectRun(runId: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(selectedRunId = runId, statsLoading = true, error = null)
            }
            try {
                val detail = cloudSyncRepository.getRunDetail(runId)
                val records = cloudSyncRepository.getRunRecords(runId)
                _uiState.update {
                    it.copy(
                        statsLoading = false,
                        selectedRun = detail,
                        selectedRunRecords = records,
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(statsLoading = false, error = e.message)
                }
            }
        }
    }

    fun setRunsOnlyWithRecords(enabled: Boolean) {
        val previousSelected = _uiState.value.selectedRunId
        _uiState.update { state ->
            state.copy(runsOnlyWithRecords = enabled, runsPage = 0)
        }
        val filtered = _uiState.value.filteredRuns
        val nextSelected = when {
            previousSelected != null && filtered.any { it.runId == previousSelected } ->
                previousSelected
            else -> filtered.firstOrNull()?.runId
        }
        if (nextSelected == null) {
            _uiState.update {
                it.copy(selectedRunId = null, selectedRun = null, selectedRunRecords = emptyList())
            }
        } else if (nextSelected != previousSelected) {
            selectRun(nextSelected)
        }
    }

    fun setRunsPage(page: Int) {
        _uiState.update { state ->
            state.copy(runsPage = page.coerceIn(0, (state.runsTotalPages - 1).coerceAtLeast(0)))
        }
    }

    fun tripCircuitBreaker() {
        viewModelScope.launch {
            mutateCircuitBreaker(
                actionLabel = "Pulling circuit breaker…",
                verifyingLabel = "Verifying circuit breaker is tripped…",
                retryLabel = "Backend not updated yet — retrying…",
                successMessage = "Circuit breaker pulled — sync paused",
                expectTripped = true,
                call = { cloudSyncRepository.tripCircuitBreaker() },
            )
        }
    }

    fun resetCircuitBreaker() {
        viewModelScope.launch {
            mutateCircuitBreaker(
                actionLabel = "Resetting circuit breaker…",
                verifyingLabel = "Verifying circuit breaker is closed…",
                retryLabel = "Backend not updated yet — retrying…",
                successMessage = "Circuit breaker reset — sync allowed",
                expectTripped = false,
                call = { cloudSyncRepository.resetCircuitBreaker() },
            )
        }
    }

    private suspend fun mutateCircuitBreaker(
        actionLabel: String,
        verifyingLabel: String,
        retryLabel: String,
        successMessage: String,
        expectTripped: Boolean,
        call: suspend () -> CircuitBreakerStatus,
    ) {
        _uiState.update {
            it.copy(
                isBusy = true,
                circuitBreakerPending = true,
                circuitBreakerPendingLabel = actionLabel,
                error = null,
                message = null,
            )
        }
        try {
            call()
            _uiState.update {
                it.copy(circuitBreakerPendingLabel = verifyingLabel)
            }
            val verified = verifyCircuitBreakerCommitted(expectTripped = expectTripped, retryLabel = retryLabel)
            if (verified != null) {
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        circuitBreakerPending = false,
                        circuitBreakerPendingLabel = null,
                        circuitBreaker = verified,
                        message = successMessage,
                        cloudPausedBanner = if (verified.syncPaused) {
                            "Sync paused (${verified.trippedReason ?: "cost guard"})."
                        } else {
                            null
                        },
                        error = null,
                    )
                }
            } else {
                // Refresh whatever the backend currently reports, then surface the failure.
                val current = runCatching {
                    cloudSyncRepository.fetchCloudStatus().second
                }.getOrDefault(_uiState.value.circuitBreaker)
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        circuitBreakerPending = false,
                        circuitBreakerPendingLabel = null,
                        circuitBreaker = current,
                        cloudPausedBanner = if (current.syncPaused) {
                            "Sync paused (${current.trippedReason ?: "cost guard"})."
                        } else {
                            null
                        },
                        message = null,
                        error = "Could not verify the circuit breaker change. Please try again later.",
                    )
                }
            }
        } catch (e: Exception) {
            val current = runCatching {
                cloudSyncRepository.fetchCloudStatus().second
            }.getOrDefault(_uiState.value.circuitBreaker)
            _uiState.update {
                it.copy(
                    isBusy = false,
                    circuitBreakerPending = false,
                    circuitBreakerPendingLabel = null,
                    circuitBreaker = current,
                    cloudPausedBanner = if (current.syncPaused) {
                        "Sync paused (${current.trippedReason ?: "cost guard"})."
                    } else {
                        null
                    },
                    message = null,
                    error = e.message ?: "Could not update the circuit breaker. Please try again later.",
                )
            }
        }
    }

    /**
     * Checks /status until the breaker matches [expectTripped].
     * Initial check, then up to two retries with 10s between each.
     */
    private suspend fun verifyCircuitBreakerCommitted(
        expectTripped: Boolean,
        retryLabel: String,
    ): CircuitBreakerStatus? {
        repeat(3) { attempt ->
            val status = cloudSyncRepository.fetchCloudStatus().second
            if (matchesExpectedCircuitBreaker(status, expectTripped)) {
                return status
            }
            if (attempt < 2) {
                _uiState.update {
                    it.copy(
                        circuitBreakerPendingLabel =
                            "$retryLabel (${attempt + 1}/2)",
                    )
                }
                delay(10_000)
            }
        }
        return null
    }

    private fun matchesExpectedCircuitBreaker(
        status: CircuitBreakerStatus,
        expectTripped: Boolean,
    ): Boolean {
        val looksTripped = status.circuitBreakerTripped || status.syncPaused || !status.syncEnabled
        return if (expectTripped) looksTripped else !looksTripped && status.syncEnabled && status.syncAllowed
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
            it.copy(
                signedIn = !CloudConfig.enabled,
                authEmail = null,
                message = "Signed out",
                selectedTab = AppTab.Stats,
                recentRuns = emptyList(),
                selectedRunId = null,
                selectedRun = null,
                selectedRunRecords = emptyList(),
                bridgeConfig = BridgeConfigSnapshot(),
                circuitBreaker = CircuitBreakerStatus(),
                isAdmin = false,
            )
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
        if (value.any { !it.isDigit() }) return
        _uiState.update {
            it.copy(
                backfillDaysText = value,
                message = null,
                error = null,
                settingsValidationError = null,
            )
        }
    }

    fun updateSyncFromOverride(value: String) = updateSettings { it.copy(syncFromOverride = value) }

    fun updateSyncIntervalMinutes(value: String) {
        if (value.any { !it.isDigit() }) return
        _uiState.update {
            it.copy(
                syncIntervalMinutesText = value,
                message = null,
                error = null,
                settingsValidationError = null,
            )
        }
    }

    fun dismissValidationError() {
        _uiState.update { it.copy(settingsValidationError = null) }
    }

    /**
     * Commits draft numeric fields into [AppSettings] or returns an error message.
     */
    private fun commitNumericDraftsOrError(): String? {
        val state = _uiState.value
        val backfill = state.backfillDaysText.toIntOrNull()
        if (backfill == null || backfill !in 1..30) {
            return "Set Backfill days to a whole number between 1 and 30."
        }
        val interval = state.syncIntervalMinutesText.toIntOrNull()
        if (interval == null || interval !in 1..240) {
            return "Set Sync interval to a whole number of minutes between 1 and 240."
        }
        _uiState.update {
            it.copy(
                settings = it.settings.copy(
                    backfillDays = backfill,
                    syncIntervalMinutes = interval,
                ),
                backfillDaysText = backfill.toString(),
                syncIntervalMinutesText = interval.toString(),
            )
        }
        return null
    }

    private fun validateSettingsForSave(): Boolean {
        commitNumericDraftsOrError()?.let { msg ->
            _uiState.update {
                it.copy(settingsValidationError = msg, message = null, error = null)
            }
            return false
        }
        val settings = _uiState.value.settings
        if (!SyncWindowResolver.isValidCustomStartInput(settings.syncFromOverride)) {
            _uiState.update {
                it.copy(
                    settingsValidationError =
                        "Set Sync from to blank or yyyy-MM-dd HH:mm (local time).",
                    message = null,
                    error = null,
                )
            }
            return false
        }
        return true
    }

    fun saveSettings() {
        viewModelScope.launch {
            if (!validateSettingsForSave()) return@launch
            val settings = _uiState.value.settings
            _uiState.update { it.copy(isBusy = true, error = null, settingsValidationError = null) }
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
                        backfillDaysText = settings.backfillDays.toString(),
                        syncIntervalMinutesText = settings.syncIntervalMinutes.toString(),
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
            if (!validateSettingsForSave()) return@launch
            _uiState.update {
                it.copy(
                    isBusy = true,
                    message = null,
                    error = null,
                    settingsValidationError = null,
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
            if (!validateSettingsForSave()) return@launch
            _uiState.update {
                it.copy(
                    isBusy = true,
                    message = null,
                    error = null,
                    settingsValidationError = null,
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
                if (CloudConfig.enabled) {
                    refreshStats()
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isBusy = false, error = e.message) }
            }
        }
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        _uiState.update {
            it.copy(
                settings = transform(it.settings),
                message = null,
                error = null,
                settingsValidationError = null,
            )
        }
    }
}
