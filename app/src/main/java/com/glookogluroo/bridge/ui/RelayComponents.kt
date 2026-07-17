package com.glookogluroo.bridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class RelayRouteSegmentState {
    Healthy,
    Interrupted,
    Idle,
    Active,
}

@Composable
fun RelayCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(RelayTokens.RadiusCard),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.relayColors.borderSubtle),
    ) {
        Column(
            modifier = Modifier.padding(RelayTokens.Space4),
            verticalArrangement = Arrangement.spacedBy(RelayTokens.Space2),
            content = content,
        )
    }
}

@Composable
fun RelayStatusCard(
    stateLabel: String,
    impact: String,
    timestamp: String? = null,
    stateColor: Color = MaterialTheme.colorScheme.primary,
    primaryActionLabel: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryActionEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    RelayCard(modifier = modifier) {
        Text(
            text = stateLabel,
            style = MaterialTheme.typography.titleMedium,
            color = stateColor,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = impact,
            style = MaterialTheme.typography.bodyMedium,
        )
        timestamp?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.relayColors.textMuted,
            )
        }
        if (primaryActionLabel != null && onPrimaryAction != null) {
            Spacer(modifier = Modifier.height(RelayTokens.Space2))
            RelayPrimaryButton(
                text = primaryActionLabel,
                onClick = onPrimaryAction,
                enabled = primaryActionEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun RelayRoute(
    sourceState: RelayRouteSegmentState = RelayRouteSegmentState.Healthy,
    relayState: RelayRouteSegmentState = RelayRouteSegmentState.Healthy,
    destinationState: RelayRouteSegmentState = RelayRouteSegmentState.Healthy,
    sourceLabel: String = "Glooko",
    relayLabel: String = "Relay",
    destinationLabel: String = "Gluroo",
    modifier: Modifier = Modifier,
) {
    val healthy = MaterialTheme.colorScheme.tertiary
    val interrupted = MaterialTheme.colorScheme.error
    val idle = MaterialTheme.relayColors.borderSubtle
    val active = MaterialTheme.colorScheme.primary

    fun colorFor(state: RelayRouteSegmentState): Color = when (state) {
        RelayRouteSegmentState.Healthy -> healthy
        RelayRouteSegmentState.Interrupted -> interrupted
        RelayRouteSegmentState.Idle -> idle
        RelayRouteSegmentState.Active -> active
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = RelayTokens.Space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RouteNode(color = colorFor(sourceState), modifier = Modifier.size(14.dp))
            RouteLine(
                color = colorFor(
                    if (sourceState == RelayRouteSegmentState.Interrupted ||
                        relayState == RelayRouteSegmentState.Interrupted
                    ) {
                        RelayRouteSegmentState.Interrupted
                    } else {
                        relayState
                    },
                ),
                modifier = Modifier.weight(1f),
            )
            RouteNode(color = colorFor(relayState), modifier = Modifier.size(18.dp))
            RouteLine(
                color = colorFor(
                    if (destinationState == RelayRouteSegmentState.Interrupted ||
                        relayState == RelayRouteSegmentState.Interrupted
                    ) {
                        RelayRouteSegmentState.Interrupted
                    } else {
                        destinationState
                    },
                ),
                modifier = Modifier.weight(1f),
            )
            RouteNode(color = colorFor(destinationState), modifier = Modifier.size(14.dp))
        }
        Spacer(modifier = Modifier.height(RelayTokens.Space2))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(sourceLabel, style = MaterialTheme.typography.labelSmall)
            Text(relayLabel, style = MaterialTheme.typography.labelSmall)
            Text(destinationLabel, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun RouteNode(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color, CircleShape)
            .border(2.dp, Color.White, CircleShape),
    )
}

@Composable
private fun RouteLine(color: Color, modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .height(3.dp)
            .padding(horizontal = 4.dp),
    ) {
        drawLine(
            color = color,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
fun RelayBanner(
    message: String,
    tone: RelayBannerTone = RelayBannerTone.Info,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.relayColors
    val (bg, fg) = when (tone) {
        RelayBannerTone.Info -> colors.infoContainer to colors.info
        RelayBannerTone.Success -> colors.successContainer to colors.success
        RelayBannerTone.Warning -> colors.warningContainer to colors.warning
        RelayBannerTone.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(RelayTokens.RadiusField))
            .padding(RelayTokens.Space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(fg, CircleShape),
        )
        Spacer(modifier = Modifier.width(RelayTokens.Space3))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

enum class RelayBannerTone {
    Info,
    Success,
    Warning,
    Error,
}

@Composable
fun RelayPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(RelayTokens.MinTouchTarget),
        shape = RoundedCornerShape(RelayTokens.RadiusButton),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = RelayTokens.Space6),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RelaySecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(RelayTokens.MinTouchTarget),
        shape = RoundedCornerShape(RelayTokens.RadiusButton),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.relayColors.borderSubtle,
        ),
        contentPadding = PaddingValues(horizontal = RelayTokens.Space4),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RelayTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(RelayTokens.MinTouchTarget),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RelaySectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier,
    )
}

@Composable
fun RelaySafetyNote(modifier: Modifier = Modifier) {
    Text(
        text = "For sharing and retrospective monitoring. Not for dosing decisions.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.relayColors.textMuted,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
fun RelayTechnicalDisclosure(
    title: String = "View technical details",
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = if (expanded) "Hide technical details" else title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .clickable(onClick = onToggle)
                .padding(vertical = RelayTokens.Space2),
        )
        if (expanded) {
            Column(
                verticalArrangement = Arrangement.spacedBy(RelayTokens.Space2),
                content = content,
            )
        }
    }
}
