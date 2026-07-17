package com.glookogluroo.bridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glookogluroo.bridge.R

/**
 * Roboto Flex loaded via the Compose Google Fonts provider.
 * Falls back to the platform sans if GMS is unavailable.
 */
private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val RobotoFlex = GoogleFont("Roboto Flex")

private val RobotoFlexFamily = FontFamily(
    Font(googleFont = RobotoFlex, fontProvider = GoogleFontsProvider, weight = FontWeight.Normal),
    Font(googleFont = RobotoFlex, fontProvider = GoogleFontsProvider, weight = FontWeight(450)),
    Font(googleFont = RobotoFlex, fontProvider = GoogleFontsProvider, weight = FontWeight(550)),
    Font(googleFont = RobotoFlex, fontProvider = GoogleFontsProvider, weight = FontWeight(600)),
    Font(googleFont = RobotoFlex, fontProvider = GoogleFontsProvider, weight = FontWeight(650)),
    Font(googleFont = RobotoFlex, fontProvider = GoogleFontsProvider, weight = FontWeight.Bold),
)

/** Layout tokens shared across light and dark Relay themes. */
object RelayTokens {
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 20.dp
    val Space6 = 24.dp
    val Space8 = 32.dp

    val RadiusField = 16.dp
    val RadiusButton = 22.dp
    val RadiusCard = 24.dp

    val MinTouchTarget = 48.dp
    val SideMargin = 16.dp
}

private object RelayLightPalette {
    val primary = Color(0xFF0B7C78)
    val info = Color(0xFF3267D6)
    val success = Color(0xFF247A52)
    val warning = Color(0xFFB56912)
    val error = Color(0xFFB53B44)
    val canvas = Color(0xFFF5F8F7)
    val raised = Color(0xFFFFFFFF)
    val ink = Color(0xFF10202A)
    val muted = Color(0xFF4A5C64)
    val border = Color(0xFFD7E0DE)
    val primaryContainer = Color(0xFFD5EDEB)
    val successContainer = Color(0xFFD8EEE4)
    val warningContainer = Color(0xFFF5E6D0)
    val errorContainer = Color(0xFFF5D6D8)
    val infoContainer = Color(0xFFD9E4F8)
    val onPrimary = Color.White
    val onBrand = Color.White
}

/** Relay design language dark palette (Phase 2) — navy canvas matches launcher mark. */
private object RelayDarkPalette {
    val primary = Color(0xFF2EC4B6)
    val info = Color(0xFF5B8DEF)
    val success = Color(0xFF3FA872)
    val warning = Color(0xFFD4953A)
    val error = Color(0xFFD45A62)
    val canvas = Color(0xFF051025)
    val raised = Color(0xFF0C1A32)
    val ink = Color(0xFFE6F2F0)
    val muted = Color(0xFF8FA3AD)
    val border = Color(0xFF243548)
    val primaryContainer = Color(0xFF0F3D3A)
    val successContainer = Color(0xFF123528)
    val warningContainer = Color(0xFF3D2E14)
    val errorContainer = Color(0xFF3D1A1E)
    val infoContainer = Color(0xFF152A4D)
    val onPrimary = Color(0xFF051025)
    val onBrand = Color(0xFF051025)
}

data class RelayExtendedColors(
    val info: Color,
    val success: Color,
    val warning: Color,
    val infoContainer: Color,
    val successContainer: Color,
    val warningContainer: Color,
    val borderSubtle: Color,
    val textMuted: Color,
)

private val RelayLightExtendedColors = RelayExtendedColors(
    info = RelayLightPalette.info,
    success = RelayLightPalette.success,
    warning = RelayLightPalette.warning,
    infoContainer = RelayLightPalette.infoContainer,
    successContainer = RelayLightPalette.successContainer,
    warningContainer = RelayLightPalette.warningContainer,
    borderSubtle = RelayLightPalette.border,
    textMuted = RelayLightPalette.muted,
)

private val RelayDarkExtendedColors = RelayExtendedColors(
    info = RelayDarkPalette.info,
    success = RelayDarkPalette.success,
    warning = RelayDarkPalette.warning,
    infoContainer = RelayDarkPalette.infoContainer,
    successContainer = RelayDarkPalette.successContainer,
    warningContainer = RelayDarkPalette.warningContainer,
    borderSubtle = RelayDarkPalette.border,
    textMuted = RelayDarkPalette.muted,
)

val LocalRelayColors = staticCompositionLocalOf { RelayLightExtendedColors }

private val RelayLightColorScheme = lightColorScheme(
    primary = RelayLightPalette.primary,
    onPrimary = RelayLightPalette.onPrimary,
    primaryContainer = RelayLightPalette.primaryContainer,
    onPrimaryContainer = RelayLightPalette.ink,
    secondary = RelayLightPalette.info,
    onSecondary = RelayLightPalette.onBrand,
    secondaryContainer = RelayLightPalette.infoContainer,
    onSecondaryContainer = RelayLightPalette.ink,
    tertiary = RelayLightPalette.success,
    onTertiary = RelayLightPalette.onBrand,
    tertiaryContainer = RelayLightPalette.successContainer,
    onTertiaryContainer = RelayLightPalette.ink,
    error = RelayLightPalette.error,
    onError = RelayLightPalette.onBrand,
    errorContainer = RelayLightPalette.errorContainer,
    onErrorContainer = RelayLightPalette.ink,
    background = RelayLightPalette.canvas,
    onBackground = RelayLightPalette.ink,
    surface = RelayLightPalette.raised,
    onSurface = RelayLightPalette.ink,
    surfaceVariant = RelayLightPalette.primaryContainer,
    onSurfaceVariant = RelayLightPalette.muted,
    outline = RelayLightPalette.border,
    outlineVariant = RelayLightPalette.border,
)

private val RelayDarkColorScheme = darkColorScheme(
    primary = RelayDarkPalette.primary,
    onPrimary = RelayDarkPalette.onPrimary,
    primaryContainer = RelayDarkPalette.primaryContainer,
    onPrimaryContainer = RelayDarkPalette.ink,
    secondary = RelayDarkPalette.info,
    onSecondary = RelayDarkPalette.onBrand,
    secondaryContainer = RelayDarkPalette.infoContainer,
    onSecondaryContainer = RelayDarkPalette.ink,
    tertiary = RelayDarkPalette.success,
    onTertiary = RelayDarkPalette.onBrand,
    tertiaryContainer = RelayDarkPalette.successContainer,
    onTertiaryContainer = RelayDarkPalette.ink,
    error = RelayDarkPalette.error,
    onError = RelayDarkPalette.onBrand,
    errorContainer = RelayDarkPalette.errorContainer,
    onErrorContainer = RelayDarkPalette.ink,
    background = RelayDarkPalette.canvas,
    onBackground = RelayDarkPalette.ink,
    surface = RelayDarkPalette.raised,
    onSurface = RelayDarkPalette.ink,
    surfaceVariant = RelayDarkPalette.primaryContainer,
    onSurfaceVariant = RelayDarkPalette.muted,
    outline = RelayDarkPalette.border,
    outlineVariant = RelayDarkPalette.border,
)

private val RelayTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(650),
        fontSize = 32.sp,
        lineHeight = 40.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(650),
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(650),
        fontSize = 24.sp,
        lineHeight = 32.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(600),
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(600),
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(450),
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(550),
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(550),
        fontSize = 14.sp,
        lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(450),
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

private val RelayShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(RelayTokens.RadiusField),
    medium = RoundedCornerShape(RelayTokens.RadiusField),
    large = RoundedCornerShape(RelayTokens.RadiusCard),
    extraLarge = RoundedCornerShape(RelayTokens.RadiusCard),
)

object RelaySpacing {
    val xs: Dp = RelayTokens.Space1
    val sm: Dp = RelayTokens.Space2
    val md: Dp = RelayTokens.Space3
    val lg: Dp = RelayTokens.Space4
    val xl: Dp = RelayTokens.Space6
    val xxl: Dp = RelayTokens.Space8
}

@Composable
fun GlookoGlurooTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) RelayDarkColorScheme else RelayLightColorScheme
    val relayColors = if (darkTheme) RelayDarkExtendedColors else RelayLightExtendedColors

    CompositionLocalProvider(LocalRelayColors provides relayColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RelayTypography,
            shapes = RelayShapes,
            content = content,
        )
    }
}

val MaterialTheme.relayColors: RelayExtendedColors
    @Composable
    get() = LocalRelayColors.current
