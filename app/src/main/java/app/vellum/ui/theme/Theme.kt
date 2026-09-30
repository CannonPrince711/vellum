package app.vellum.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * VELLUM — identity
 * Paper + ink + an editor's vermilion pen. Serif display type, soft sheets,
 * a floating tool "dock" and a page "spine" instead of stock app bars.
 */

object Ink {
    val Paper = Color(0xFFF3EEE3)
    val PaperDeep = Color(0xFFE8E0D0)
    val Sheet = Color(0xFFFBF8F2)
    val InkBlue = Color(0xFF1C2533)
    val InkSoft = Color(0xFF4A5566)
    val Vermilion = Color(0xFFD9480F)
    val VermilionSoft = Color(0xFFF6D5C4)
    val Moss = Color(0xFF3F6B4F)
    val Graphite = Color(0xFF8A8478)

    val NightDesk = Color(0xFF131619)
    val NightSheet = Color(0xFF1C2026)
    val NightRaised = Color(0xFF252A31)
    val NightPaper = Color(0xFFEDE6D8)
    val Ember = Color(0xFFFF7A45)
}

@Immutable
data class VellumColors(
    val desk: Color,       // app background behind documents
    val sheet: Color,      // cards / dock surfaces
    val raised: Color,
    val ink: Color,
    val inkSoft: Color,
    val accent: Color,
    val accentSoft: Color,
    val rule: Color,
)

val LocalVellum = staticCompositionLocalOf {
    VellumColors(Ink.Paper, Ink.Sheet, Ink.PaperDeep, Ink.InkBlue, Ink.InkSoft, Ink.Vermilion, Ink.VermilionSoft, Ink.Graphite)
}

private val LightScheme = lightColorScheme(
    primary = Ink.Vermilion,
    onPrimary = Color.White,
    primaryContainer = Ink.VermilionSoft,
    onPrimaryContainer = Color(0xFF5A1C03),
    secondary = Ink.InkBlue,
    onSecondary = Ink.Paper,
    secondaryContainer = Ink.PaperDeep,
    onSecondaryContainer = Ink.InkBlue,
    tertiary = Ink.Moss,
    background = Ink.Paper,
    onBackground = Ink.InkBlue,
    surface = Ink.Sheet,
    onSurface = Ink.InkBlue,
    surfaceVariant = Ink.PaperDeep,
    onSurfaceVariant = Ink.InkSoft,
    surfaceContainer = Ink.Sheet,
    surfaceContainerHigh = Ink.Sheet,
    surfaceContainerHighest = Ink.PaperDeep,
    surfaceContainerLow = Ink.Paper,
    outline = Ink.Graphite,
    outlineVariant = Color(0xFFD6CDBC),
    inverseSurface = Ink.InkBlue,
    inverseOnSurface = Ink.Paper,
)

private val DarkScheme = darkColorScheme(
    primary = Ink.Ember,
    onPrimary = Color(0xFF2B0E00),
    primaryContainer = Color(0xFF5A2410),
    onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Ink.NightPaper,
    onSecondary = Ink.NightDesk,
    secondaryContainer = Ink.NightRaised,
    onSecondaryContainer = Ink.NightPaper,
    tertiary = Color(0xFF8FC0A0),
    background = Ink.NightDesk,
    onBackground = Ink.NightPaper,
    surface = Ink.NightSheet,
    onSurface = Ink.NightPaper,
    surfaceVariant = Ink.NightRaised,
    onSurfaceVariant = Color(0xFFB8B0A2),
    surfaceContainer = Ink.NightSheet,
    surfaceContainerHigh = Ink.NightRaised,
    surfaceContainerHighest = Ink.NightRaised,
    surfaceContainerLow = Ink.NightDesk,
    outline = Color(0xFF7D776C),
    outlineVariant = Color(0xFF3A3F47),
    inverseSurface = Ink.NightPaper,
    inverseOnSurface = Ink.NightDesk,
)

val Display = FontFamily.Serif

private val VellumType = Typography(
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 1.2.sp),
)

/** Italic serif "marginalia" style used for small annotations in the UI. */
val Marginalia = TextStyle(fontFamily = Display, fontStyle = FontStyle.Italic, fontSize = 13.sp)

private val VellumShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun VellumTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkScheme else LightScheme
    val v = if (dark) VellumColors(Ink.NightDesk, Ink.NightSheet, Ink.NightRaised, Ink.NightPaper, Color(0xFFB8B0A2), Ink.Ember, Color(0xFF5A2410), Color(0xFF3A3F47))
    else VellumColors(Ink.Paper, Ink.Sheet, Ink.PaperDeep, Ink.InkBlue, Ink.InkSoft, Ink.Vermilion, Ink.VermilionSoft, Color(0xFFD6CDBC))
    androidx.compose.runtime.CompositionLocalProvider(LocalVellum provides v) {
        MaterialTheme(colorScheme = colors, typography = VellumType, shapes = VellumShapes, content = content)
    }
}
