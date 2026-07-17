package com.glookogluroo.bridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun StartupScreen(message: String) {
    RelayCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = RelayTokens.Space4),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(RelayTokens.Space3),
        ) {
            RelayRoute(
                sourceState = RelayRouteSegmentState.Idle,
                relayState = RelayRouteSegmentState.Active,
                destinationState = RelayRouteSegmentState.Idle,
            )
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
            Text("Relay", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.relayColors.textMuted,
            )
            Spacer(modifier = Modifier.height(RelayTokens.Space1))
            RelaySafetyNote()
        }
    }
}
