package dev.hiorcraft.openssh.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.FrameWindowScope
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Windows-11-Fensterstil über die DWM-API: Mica-Hintergrund, dunkle Titelleiste und
 * abgerundete Ecken. Ersetzt ComposeWindowStyler, dessen letzte Version (0.3.2) auf
 * Compose-Interna zugreift, die es in aktuellen Compose-Versionen nicht mehr gibt.
 */
object Win11Window {

    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")

    /** Windows-Build, z.B. 22631; 0 außerhalb von Windows. */
    val build: Int by lazy {
        if (!isWindows) 0 else runCatching {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "CurrentBuildNumber").toInt()
        }.getOrDefault(0)
    }

    /** Mica als Systemhintergrund gibt es ab Windows 11 22H2 (Build 22621). */
    val supportsMica: Boolean get() = build >= 22621

    /**
     * Wendet den Stil an. Liefert `true`, wenn Mica aktiv ist – dann muss der Inhalt
     * durchscheinende Hintergründe verwenden.
     */
    fun apply(window: ComposeWindow, dark: Boolean, mica: Boolean, captionColor: java.awt.Color? = null): Boolean {
        if (!isWindows || build < 22000) return false
        val hwnd = HWND(Native.getComponentPointer(window))
        setInt(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0)
        setInt(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, DWMWCP_ROUND)
        // Titelleiste und Rahmen in der Farbe des Inhalts (COLORREF = 0x00BBGGRR); DWMWA_COLOR_DEFAULT setzt zurück
        val colorRef = captionColor?.let { (it.blue shl 16) or (it.green shl 8) or it.red } ?: DWMWA_COLOR_DEFAULT
        setInt(hwnd, DWMWA_CAPTION_COLOR, colorRef)
        setInt(hwnd, DWMWA_BORDER_COLOR, colorRef)

        val useMica = mica && supportsMica && captionColor == null
        // Der Skia-Layer muss transparent sein, damit DWM den Mica-Hintergrund durchscheinen lässt
        window.isTransparent = useMica
        val clear = java.awt.Color(0, 0, 0, 0)
        (window.contentPane as? JComponent)?.let {
            it.isOpaque = !useMica
            it.background = when {
                useMica -> clear
                captionColor != null -> captionColor
                dark -> java.awt.Color(0x20, 0x20, 0x20)
                else -> java.awt.Color(0xF3, 0xF3, 0xF3)
            }
        }
        window.rootPane.isOpaque = !useMica
        if (useMica) window.rootPane.background = clear

        dwm.DwmExtendFrameIntoClientArea(hwnd, Margins(if (useMica) -1 else 0))
        setInt(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, if (useMica) DWMSBT_MAINWINDOW else DWMSBT_NONE)
        window.repaint()
        return useMica
    }

    private fun setInt(hwnd: HWND, attribute: Int, value: Int) {
        runCatching { dwm.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value).pointer, 4) }
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWA_SYSTEMBACKDROP_TYPE = 38
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_COLOR_DEFAULT = -1
    private const val DWMWCP_ROUND = 2
    private const val DWMSBT_NONE = 1
    private const val DWMSBT_MAINWINDOW = 2

    @Suppress("FunctionName")
    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(hwnd: HWND, attribute: Int, value: Pointer, size: Int): Int
        fun DwmExtendFrameIntoClientArea(hwnd: HWND, margins: Margins): Int
    }

    @Suppress("PropertyName")
    @Structure.FieldOrder("cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight")
    class Margins(all: Int = 0) : Structure() {
        @JvmField var cxLeftWidth: Int = all
        @JvmField var cxRightWidth: Int = all
        @JvmField var cyTopHeight: Int = all
        @JvmField var cyBottomHeight: Int = all
    }

    private val dwm: DwmApi by lazy { Native.load("dwmapi", DwmApi::class.java) }
}

/**
 * Wendet [Win11Window] auf das aktuelle Fenster an und liefert, ob Mica aktiv ist.
 * Wird bei Theme-Wechseln automatisch neu angewendet.
 */
@Composable
fun FrameWindowScope.rememberWin11Style(dark: Boolean, mica: Boolean, captionColor: java.awt.Color? = null): Boolean {
    var micaActive by remember { mutableStateOf(mica && Win11Window.supportsMica && captionColor == null) }
    LaunchedEffect(dark, mica, captionColor) {
        SwingUtilities.invokeLater {
            micaActive = runCatching { Win11Window.apply(window, dark, mica, captionColor) }.getOrDefault(false)
        }
    }
    return micaActive
}
