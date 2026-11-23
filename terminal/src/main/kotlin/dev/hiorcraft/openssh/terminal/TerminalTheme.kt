package dev.hiorcraft.openssh.terminal

import com.jediterm.terminal.TerminalColor
import com.jediterm.terminal.TextStyle
import com.jediterm.terminal.emulator.ColorPalette
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider
import java.awt.Font
import java.awt.GraphicsEnvironment
import com.jediterm.core.Color as TermColor

/** Farbschema eines Terminals: Vorder-/Hintergrund, Auswahl und die 16 ANSI-Farben. */
data class TerminalTheme(
    val id: String,
    val displayName: String,
    val foreground: Int,
    val background: Int,
    val selectionBackground: Int,
    val ansi: List<Int>,
    /** Textfarbe in der Auswahl; `null` = normale Vordergrundfarbe. */
    val selectionForeground: Int? = null,
) {
    init {
        require(ansi.size == 16) { "Ein Theme braucht genau 16 ANSI-Farben" }
    }

    companion object {
        /** Die Campbell-Farben, die Windows Terminal und die PowerShell-Konsole verwenden. */
        private val campbell = listOf(
            0x0C0C0C, 0xC50F1F, 0x13A10E, 0xC19C00, 0x0037DA, 0x881798, 0x3A96DD, 0xCCCCCC,
            0x767676, 0xE74856, 0x16C60C, 0xF9F1A5, 0x3B78FF, 0xB4009E, 0x61D6D6, 0xF2F2F2,
        )

        /** Das klassische Windows-PowerShell-Blau („Campbell Powershell“ in Windows Terminal). */
        val PowerShell = TerminalTheme(
            id = "powershell",
            displayName = "PowerShell (blau)",
            foreground = 0xCCCCCC,
            background = 0x012456,
            selectionBackground = 0xF2F2F2,
            selectionForeground = 0x012456,
            ansi = campbell,
        )

        /** Campbell auf Schwarz, wie CMD und das Standardprofil von Windows Terminal. */
        val Campbell = TerminalTheme(
            id = "campbell",
            displayName = "Campbell (schwarz)",
            foreground = 0xCCCCCC,
            background = 0x0C0C0C,
            selectionBackground = 0xF2F2F2,
            selectionForeground = 0x0C0C0C,
            ansi = campbell,
        )

        /** Dunkles Schema, abgestimmt auf den Windows-11-Dark-Mode. */
        val Midnight = TerminalTheme(
            id = "midnight",
            displayName = "Mitternacht (dunkel)",
            foreground = 0xD6DBE4,
            background = 0x1B1D23,
            selectionBackground = 0x3A4A66,
            ansi = listOf(
                0x1B1D23, 0xE06C75, 0x98C379, 0xE5C07B, 0x61AFEF, 0xC678DD, 0x56B6C2, 0xABB2BF,
                0x5C6370, 0xFF7A85, 0xB5E890, 0xFFD58A, 0x82C4FF, 0xDC9CF0, 0x7FD6E0, 0xFFFFFF,
            ),
        )

        /** Helles Schema. */
        val Daylight = TerminalTheme(
            id = "daylight",
            displayName = "Tageslicht (hell)",
            foreground = 0x383A42,
            background = 0xFAFAFA,
            selectionBackground = 0xBFD4F2,
            ansi = listOf(
                0x383A42, 0xE45649, 0x50A14F, 0xC18401, 0x0184BC, 0xA626A4, 0x0997B3, 0xA0A1A7,
                0x696C77, 0xF0675A, 0x6CBF6B, 0xD9A21B, 0x2B9FE0, 0xC045BE, 0x26B2CC, 0x202227,
            ),
        )

        /** Hoher Kontrast, z.B. für Präsentationen. */
        val Contrast = TerminalTheme(
            id = "contrast",
            displayName = "Kontrast",
            foreground = 0xFFFFFF,
            background = 0x000000,
            selectionBackground = 0x264F78,
            ansi = listOf(
                0x000000, 0xFF5555, 0x55FF55, 0xFFFF55, 0x5599FF, 0xFF55FF, 0x55FFFF, 0xCCCCCC,
                0x777777, 0xFF8888, 0x88FF88, 0xFFFF99, 0x99BBFF, 0xFF99FF, 0x99FFFF, 0xFFFFFF,
            ),
        )

        val all = listOf(PowerShell, Campbell, Midnight, Daylight, Contrast)

        fun byId(id: String?): TerminalTheme = all.firstOrNull { it.id == id } ?: PowerShell
    }
}

data class TerminalSettings(
    val theme: TerminalTheme = TerminalTheme.PowerShell,
    val fontFamily: String = defaultFontFamily(),
    val fontSize: Float = 14f,
    val scrollbackLines: Int = 10_000,
    val copyOnSelect: Boolean = false,
    val bell: Boolean = false,
) {
    companion object {
        val preferredFonts = listOf("Cascadia Mono", "Cascadia Code", "JetBrains Mono", "Consolas", "Lucida Console")

        fun availableMonospaceFonts(): List<String> {
            val installed = runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
            }.getOrDefault(emptySet())
            return preferredFonts.filter { it in installed } + Font.MONOSPACED
        }

        fun defaultFontFamily(): String = availableMonospaceFonts().first()
    }
}

/** Verbindet [TerminalSettings] mit JediTerm. */
class ThemedSettingsProvider(private val settings: TerminalSettings) : DefaultSettingsProvider() {

    private val palette = object : ColorPalette() {
        override fun getForegroundByColorIndex(colorIndex: Int): TermColor = TermColor(settings.theme.ansi[colorIndex])
        override fun getBackgroundByColorIndex(colorIndex: Int): TermColor = TermColor(settings.theme.ansi[colorIndex])
    }

    override fun getTerminalColorPalette(): ColorPalette = palette

    override fun getTerminalFont(): Font = Font(settings.fontFamily, Font.PLAIN, settings.fontSize.toInt())

    override fun getTerminalFontSize(): Float = settings.fontSize

    override fun getDefaultForeground(): TerminalColor = TerminalColor.rgb(
        settings.theme.foreground shr 16 and 0xFF, settings.theme.foreground shr 8 and 0xFF, settings.theme.foreground and 0xFF,
    )

    override fun getDefaultBackground(): TerminalColor = TerminalColor.rgb(
        settings.theme.background shr 16 and 0xFF, settings.theme.background shr 8 and 0xFF, settings.theme.background and 0xFF,
    )

    @Deprecated("JediTerm liest den Standardstil beim Start noch hierüber aus")
    override fun getDefaultStyle(): TextStyle = TextStyle(getDefaultForeground(), getDefaultBackground())

    override fun getSelectionColor(): TextStyle = TextStyle(
        settings.theme.selectionForeground?.let { TerminalColor(TermColor(it)) } ?: getDefaultForeground(),
        TerminalColor(TermColor(settings.theme.selectionBackground)),
    )

    override fun useInverseSelectionColor(): Boolean = false

    override fun copyOnSelect(): Boolean = settings.copyOnSelect

    override fun getBufferMaxLinesCount(): Int = settings.scrollbackLines

    override fun audibleBell(): Boolean = settings.bell

    override fun useAntialiasing(): Boolean = true
}
