package com.glookogluroo.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            uiState.isBootstrapping -> "Relay"
                            !showTabs -> "Relay"
                            uiState.selectedTab == AppTab.Status -> "Status"
                            uiState.selectedTab == AppTab.Activity -> "Activity"
                            else -> "Settings"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
        bottomBar = {
            if (showTabs) {
                val navItemColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.relayColors.textMuted,
                    unselectedTextColor = MaterialTheme.relayColors.textMuted,
                )
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                ) {
                    NavigationBarItem(
                        selected = uiState.selectedTab == AppTab.Status,
                        onClick = { viewModel.selectTab(AppTab.Status) },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.Timeline,
                                contentDescription = "Status",
                            )
                        },
                        label = { Text("Status") },
                        colors = navItemColors,
                    )
                    NavigationBarItem(
                        selected = uiState.selectedTab == AppTab.Activity,
                        onClick = { viewModel.selectTab(AppTab.Activity) },
                        icon = {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = "Activity",
                            )
                        },
                        label = { Text("Activity") },
                        colors = navItemColors,
                    )
                    NavigationBarItem(
                        selected = uiState.selectedTab == AppTab.Settings,
                        onClick = { viewModel.selectTab(AppTab.Settings) },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Settings",
                            )
                        },
                        label = { Text("Settings") },
                        colors = navItemColors,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = RelayTokens.SideMargin, vertical = RelayTokens.Space3),
            verticalArrangement = Arrangement.spacedBy(RelayTokens.Space3),
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
                AppTab.Status -> StatusTab(
                    uiState = uiState,
                    onSignOut = viewModel::signOut,
                    onTripCircuitBreaker = viewModel::tripCircuitBreaker,
                    onResetCircuitBreaker = viewModel::resetCircuitBreaker,
                )
                AppTab.Activity -> ActivityTab(
                    uiState = uiState,
                    onRefresh = viewModel::refreshStats,
                    onSelectRun = viewModel::selectRun,
                    onRunsOnlyWithRecordsChange = viewModel::setRunsOnlyWithRecords,
                    onRunsPageChange = viewModel::setRunsPage,
                )
                AppTab.Settings -> SettingsTab(uiState = uiState, viewModel = viewModel)
            }
        }
    }
}
