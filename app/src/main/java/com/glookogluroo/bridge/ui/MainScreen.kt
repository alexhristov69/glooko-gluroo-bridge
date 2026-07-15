package com.glookogluroo.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val showTabs = !uiState.isBootstrapping &&
        (!uiState.cloudEnabled || uiState.signedIn)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            uiState.isBootstrapping -> "Glooko Gluroo Bridge"
                            !showTabs -> "Glooko Gluroo Bridge"
                            uiState.selectedTab == AppTab.Stats -> "Stats"
                            else -> "Settings"
                        },
                    )
                },
                actions = {
                    if (showTabs) {
                        IconButton(onClick = { viewModel.selectTab(AppTab.Stats) }) {
                            Icon(
                                imageVector = Icons.Filled.ShowChart,
                                contentDescription = "Stats",
                            )
                        }
                        IconButton(onClick = { viewModel.selectTab(AppTab.Settings) }) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.cloudEnabled && uiState.isBootstrapping) {
                StartupScreen(message = uiState.bootstrapMessage ?: "Starting…")
                return@Column
            }

            if (uiState.cloudEnabled && !uiState.signedIn) {
                AuthScreen(
                    isBusy = uiState.isBusy,
                    message = uiState.message,
                    error = uiState.error,
                    onSignIn = viewModel::signIn,
                    onSignUp = viewModel::signUp,
                    onConfirm = viewModel::confirmSignUp,
                )
                return@Column
            }

            when (uiState.selectedTab) {
                AppTab.Stats -> StatsTab(
                    uiState = uiState,
                    onSignOut = viewModel::signOut,
                    onRefresh = viewModel::refreshStats,
                    onSelectRun = viewModel::selectRun,
                    onTripCircuitBreaker = viewModel::tripCircuitBreaker,
                    onResetCircuitBreaker = viewModel::resetCircuitBreaker,
                    onRunsOnlyWithRecordsChange = viewModel::setRunsOnlyWithRecords,
                    onRunsPageChange = viewModel::setRunsPage,
                )
                AppTab.Settings -> SettingsTab(uiState = uiState, viewModel = viewModel)
            }
        }
    }
}
