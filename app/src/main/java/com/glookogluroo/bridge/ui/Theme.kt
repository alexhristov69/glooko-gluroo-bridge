package com.glookogluroo.bridge.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
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

/**
 * Relay design language tokens (Design Language v1.0).
 */
object RelayTokens {
    val ColorPrimary = Color(0xFF0B7C78)
    val ColorInfo = Color(0xFF3267D6)
    val ColorSuccess = Color(0xFF247A52)
    val ColorWarning = Color(0xFFB56912)
    val ColorError = Color(0xFFB53B44)
    val SurfaceCanvas = Color(0xFFF5F8F7)
    val SurfaceRaised = Color(0xFFFFFFFF)
    val TextInk = Color(0xFF10202A)
    val TextMuted = Color(0xFF4A5C64)
    val BorderSubtle = Color(0xFFD7E0DE)
    val PrimaryContainer = Color(0xFFD5EDEB)
    val SuccessContainer = Color(0xFFD8EEE4)
    val WarningContainer = Color(0xFFF5E6D0)
    val ErrorContainer = Color(0xFFF5D6D8)
    val InfoContainer = Color(0xFFD9E4F8)

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

data class RelayExtendedColors(
    val info: Color = RelayTokens.ColorInfo,
    val success: Color = RelayTokens.ColorSuccess,
    val warning: Color = RelayTokens.ColorWarning,
    val infoContainer: Color = RelayTokens.InfoContainer,
    val successContainer: Color = RelayTokens.SuccessContainer,
    val warningContainer: Color = RelayTokens.WarningContainer,
    val borderSubtle: Color = RelayTokens.BorderSubtle,
    val textMuted: Color = RelayTokens.TextMuted,
)

val LocalRelayColors = staticCompositionLocalOf { RelayExtendedColors() }

private val RelayColorScheme = lightColorScheme(
    primary = RelayTokens.ColorPrimary,
    onPrimary = Color.White,
    primaryContainer = RelayTokens.PrimaryContainer,
    onPrimaryContainer = RelayTokens.TextInk,
    secondary = RelayTokens.ColorInfo,
    onSecondary = Color.White,
    secondaryContainer = RelayTokens.InfoContainer,
    onSecondaryContainer = RelayTokens.TextInk,
    tertiary = RelayTokens.ColorSuccess,
    onTertiary = Color.White,
    tertiaryContainer = RelayTokens.SuccessContainer,
    onTertiaryContainer = RelayTokens.TextInk,
    error = RelayTokens.ColorError,
    onError = Color.White,
    errorContainer = RelayTokens.ErrorContainer,
    onErrorContainer = RelayTokens.TextInk,
    background = RelayTokens.SurfaceCanvas,
    onBackground = RelayTokens.TextInk,
    surface = RelayTokens.SurfaceRaised,
    onSurface = RelayTokens.TextInk,
    surfaceVariant = RelayTokens.PrimaryContainer,
    onSurfaceVariant = RelayTokens.TextMuted,
    outline = RelayTokens.BorderSubtle,
    outlineVariant = RelayTokens.BorderSubtle,
)

private val RelayTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(650),
        fontSize = 32.sp,
        lineHeight = 40.sp,
        color = RelayTokens.TextInk,
    ),
    headlineMedium = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(650),
        fontSize = 24.sp,
        lineHeight = 32.sp,
        color = RelayTokens.TextInk,
    ),
    titleLarge = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(650),
        fontSize = 24.sp,
        lineHeight = 32.sp,
        color = RelayTokens.TextInk,
    ),
    titleMedium = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(600),
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = RelayTokens.TextInk,
    ),
    titleSmall = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(600),
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = RelayTokens.TextInk,
    ),
    bodyLarge = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = RelayTokens.TextInk,
    ),
    bodyMedium = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = RelayTokens.TextInk,
    ),
    bodySmall = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(450),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = RelayTokens.TextMuted,
    ),
    labelLarge = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(550),
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = RelayTokens.TextInk,
    ),
    labelMedium = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(550),
        fontSize = 14.sp,
        lineHeight = 18.sp,
        color = RelayTokens.TextInk,
    ),
    labelSmall = TextStyle(
        fontFamily = RobotoFlexFamily,
        fontWeight = FontWeight(450),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = RelayTokens.TextMuted,
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
fun GlookoGlurooTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRelayColors provides RelayExtendedColors()) {
        MaterialTheme(
            colorScheme = RelayColorScheme,
            typography = RelayTypography,
            shapes = RelayShapes,
            content = content,
        )
    }
}

val MaterialTheme.relayColors: RelayExtendedColors
    @Composable
    get() = LocalRelayColors.current
