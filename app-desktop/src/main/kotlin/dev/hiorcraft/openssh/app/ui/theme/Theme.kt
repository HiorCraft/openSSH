package dev.hiorcraft.openssh.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Zusätzliche Farben, die Material 3 nicht abdeckt. */
data class ExtraColors(
    val windowBackground: Color,
    val sidebar: Color,
    val card: Color,
    val cardHover: Color,
    val subtle: Color,
    val success: Color,
    val warning: Color,
    val divider: Color,
)

val LocalExtraColors = staticCompositionLocalOf {
    ExtraColors(Color.Black, Color.Black, Color.Black, Color.Black, Color.Gray, Color.Green, Color.Yellow, Color.Gray)
}

// Fluent-/Windows-11-Akzentfarben
private val AccentDark = Color(0xFF60CDFF)
private val AccentLight = Color(0xFF005FB8)

private fun darkScheme(): ColorScheme = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF00243A),
    primaryContainer = Color(0xFF0F3A55),
    onPrimaryContainer = Color(0xFFCDEBFF),
    secondary = Color(0xFFB4C7D9),
    secondaryContainer = Color(0xFF2F3A44),
    onSecondaryContainer = Color(0xFFDDE7F0),
    background = Color(0xFF202020),
    onBackground = Color(0xFFF2F2F2),
    surface = Color(0xFF272727),
    onSurface = Color(0xFFF2F2F2),
    surfaceVariant = Color(0xFF323232),
    onSurfaceVariant = Color(0xFFC5C5C5),
    surfaceContainer = Color(0xFF2B2B2B),
    surfaceContainerHigh = Color(0xFF333333),
    surfaceContainerHighest = Color(0xFF3A3A3A),
    surfaceContainerLow = Color(0xFF242424),
    outline = Color(0xFF5A5A5A),
    outlineVariant = Color(0xFF3D3D3D),
    error = Color(0xFFFF99A4),
    errorContainer = Color(0xFF5C1C24),
    onErrorContainer = Color(0xFFFFDADF),
)

private fun lightScheme(): ColorScheme = lightColorScheme(
    primary = AccentLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E9FF),
    onPrimaryContainer = Color(0xFF001D33),
    secondary = Color(0xFF4F6070),
    secondaryContainer = Color(0xFFE3EAF1),
    onSecondaryContainer = Color(0xFF1A2733),
    background = Color(0xFFF3F3F3),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFFBFBFB),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF5C5C5C),
    surfaceContainer = Color(0xFFF6F6F6),
    surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    surfaceContainerLow = Color(0xFFF9F9F9),
    outline = Color(0xFFB8B8B8),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFC42B1C),
    errorContainer = Color(0xFFFDE7E9),
    onErrorContainer = Color(0xFF5F1009),
)

/** Farbwelt der App. */
enum class AppPalette { LIGHT, DARK, POWERSHELL }

/**
 * Das Blau der Windows-PowerShell-Konsole (#012456) mit den Campbell-Akzenten:
 * Gelb wie Befehle in PSReadLine, Cyan für Hervorhebungen.
 */
object PowerShellColors {
    val Background = Color(0xFF012456)
    val Sidebar = Color(0xFF011C45)
    val Surface = Color(0xFF07306A)
    val SurfaceHigh = Color(0xFF0D3B7C)
    val SurfaceHighest = Color(0xFF15478E)
    val Foreground = Color(0xFFF2F2F2)
    val Muted = Color(0xFFA9B8D0)
    val Yellow = Color(0xFFF9F1A5)
    val Cyan = Color(0xFF61D6D6)
    val Green = Color(0xFF16C60C)
    val Red = Color(0xFFE74856)
}

private fun powerShellScheme(): ColorScheme = darkColorScheme(
    primary = PowerShellColors.Yellow,
    onPrimary = PowerShellColors.Background,
    primaryContainer = Color(0xFF3A3A12),
    onPrimaryContainer = PowerShellColors.Yellow,
    secondary = PowerShellColors.Cyan,
    onSecondary = PowerShellColors.Background,
    secondaryContainer = Color(0xFF0F4F73),
    onSecondaryContainer = Color(0xFFD5F5F5),
    tertiary = PowerShellColors.Cyan,
    background = PowerShellColors.Background,
    onBackground = PowerShellColors.Foreground,
    surface = PowerShellColors.Surface,
    onSurface = PowerShellColors.Foreground,
    surfaceVariant = PowerShellColors.SurfaceHigh,
    onSurfaceVariant = PowerShellColors.Muted,
    surfaceContainer = PowerShellColors.Surface,
    surfaceContainerLow = Color(0xFF042A60),
    surfaceContainerHigh = PowerShellColors.SurfaceHigh,
    surfaceContainerHighest = PowerShellColors.SurfaceHighest,
    outline = Color(0xFF4A6A9E),
    outlineVariant = Color(0xFF1E4C8C),
    error = PowerShellColors.Red,
    errorContainer = Color(0xFF5A1430),
    onErrorContainer = Color(0xFFFFD6DC),
)

private fun powerShellExtras() = ExtraColors(
    windowBackground = PowerShellColors.Background,
    sidebar = PowerShellColors.Sidebar,
    card = PowerShellColors.Surface,
    cardHover = PowerShellColors.SurfaceHigh,
    subtle = PowerShellColors.Muted,
    success = PowerShellColors.Green,
    warning = PowerShellColors.Yellow,
    divider = Color(0x26FFFFFF),
)

private fun extras(dark: Boolean, mica: Boolean): ExtraColors {
    // Mit Mica sind Fenster- und Seitenhintergrund durchscheinend, Inhaltsflächen bleiben deckend lesbar
    val alphaWindow = if (mica) 0f else 1f
    return if (dark) {
        ExtraColors(
            windowBackground = Color(0xFF202020).copy(alpha = alphaWindow),
            sidebar = Color(0xFF202020).copy(alpha = if (mica) 0f else 1f),
            card = Color(0xFF2D2D2D).copy(alpha = if (mica) 0.78f else 1f),
            cardHover = Color(0xFF383838).copy(alpha = if (mica) 0.85f else 1f),
            subtle = Color(0xFF9E9E9E),
            success = Color(0xFF6CCB5F),
            warning = Color(0xFFFCE100),
            divider = Color(0x1FFFFFFF),
        )
    } else {
        ExtraColors(
            windowBackground = Color(0xFFF3F3F3).copy(alpha = alphaWindow),
            sidebar = Color(0xFFF3F3F3).copy(alpha = if (mica) 0f else 1f),
            card = Color(0xFFFFFFFF).copy(alpha = if (mica) 0.75f else 1f),
            cardHover = Color(0xFFF6F6F6).copy(alpha = if (mica) 0.85f else 1f),
            subtle = Color(0xFF6E6E6E),
            success = Color(0xFF0F7B0F),
            warning = Color(0xFF9D5D00),
            divider = Color(0x14000000),
        )
    }
}

/** Segoe UI Variable ist die Systemschrift von Windows 11; Skia findet sie über den Familiennamen. */
private val uiFont = FontFamily.Default

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.ui() = copy(fontFamily = uiFont)
    return Typography(
        displaySmall = base.displaySmall.ui(),
        headlineSmall = base.headlineSmall.ui().copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
        titleLarge = base.titleLarge.ui().copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = base.titleMedium.ui().copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        titleSmall = base.titleSmall.ui().copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
        bodyLarge = base.bodyLarge.ui().copy(fontSize = 14.sp),
        bodyMedium = base.bodyMedium.ui().copy(fontSize = 13.sp),
        bodySmall = base.bodySmall.ui().copy(fontSize = 12.sp),
        labelLarge = base.labelLarge.ui().copy(fontSize = 13.sp),
        labelMedium = base.labelMedium.ui().copy(fontSize = 12.sp),
        labelSmall = base.labelSmall.ui().copy(fontSize = 11.sp),
    )
}

val MonoFont: FontFamily = FontFamily.Monospace

@Composable
fun AppTheme(palette: AppPalette, mica: Boolean, content: @Composable () -> Unit) {
    val extras = when (palette) {
        AppPalette.POWERSHELL -> powerShellExtras()
        AppPalette.DARK -> extras(dark = true, mica = mica)
        AppPalette.LIGHT -> extras(dark = false, mica = mica)
    }
    CompositionLocalProvider(LocalExtraColors provides extras) {
        val scheme = when (palette) {
            AppPalette.POWERSHELL -> powerShellScheme()
            AppPalette.DARK -> darkScheme()
            AppPalette.LIGHT -> lightScheme()
        }
        MaterialTheme(
            colorScheme = scheme,
            typography = typography(),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(4.dp),
                small = RoundedCornerShape(4.dp),
                medium = RoundedCornerShape(8.dp),
                large = RoundedCornerShape(8.dp),
                extraLarge = RoundedCornerShape(12.dp),
            ),
        ) {
            // Ohne umgebende Surface wäre die Standard-Textfarbe Schwarz
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

val extraColors: ExtraColors
    @Composable get() = LocalExtraColors.current
