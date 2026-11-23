package dev.hiorcraft.openssh.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.hiorcraft.openssh.app.data.ThemeMode
import dev.hiorcraft.openssh.app.state.AppState
import dev.hiorcraft.openssh.app.ui.MainScreen
import dev.hiorcraft.openssh.app.ui.theme.AppPalette
import dev.hiorcraft.openssh.app.ui.theme.AppTheme
import dev.hiorcraft.openssh.app.ui.theme.rememberWin11Style
import java.awt.Dimension
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.UIManager

fun main() {
    // Compose-Popups und -Dialoge werden über eingebettete Swing-Komponenten (Terminal) geblendet
    System.setProperty("compose.interop.blending", "true")
    System.setProperty("skiko.renderApi", System.getProperty("skiko.renderApi") ?: "DIRECT3D")
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    val app = AppState()
    installShortcuts(app)

    application {
        val settings by app.settings.collectAsState()
        val systemDark = isSystemInDarkTheme()
        val palette = when (settings.themeMode) {
            ThemeMode.POWERSHELL -> AppPalette.POWERSHELL
            ThemeMode.SYSTEM -> if (systemDark) AppPalette.DARK else AppPalette.LIGHT
            ThemeMode.DARK -> AppPalette.DARK
            ThemeMode.LIGHT -> AppPalette.LIGHT
        }
        val dark = palette != AppPalette.LIGHT
        val windowState = rememberWindowState(size = DpSize(1360.dp, 860.dp), position = WindowPosition.PlatformDefault)

        Window(
            onCloseRequest = {
                app.closeAll()
                exitApplication()
            },
            state = windowState,
            title = "OpenSSH",
            icon = remember { appIcon() },
        ) {
            remember { window.minimumSize = Dimension(900, 600) }
            // Im PowerShell-Blau bekommt auch die Titelleiste das Konsolen-Blau
            val caption = if (palette == AppPalette.POWERSHELL) java.awt.Color(0x01, 0x24, 0x56) else null
            val useMica = rememberWin11Style(dark, settings.mica, caption)
            AppTheme(palette = palette, mica = useMica) {
                MainScreen(app)
            }
        }
    }
}

/**
 * Globale Tastenkürzel wie im Windows Terminal. Läuft über AWT, damit sie auch greifen,
 * wenn das (Swing-)Terminal den Fokus hat; Strg+T/Strg+W bleiben so für die Shell frei.
 */
private fun installShortcuts(app: AppState) {
    KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
        if (e.id != KeyEvent.KEY_PRESSED || !e.isControlDown) return@addKeyEventDispatcher false
        when {
            e.isShiftDown && e.keyCode == KeyEvent.VK_T -> { app.openLocal(); true }
            e.isShiftDown && e.keyCode == KeyEvent.VK_W -> { app.activeTab?.let(app::closeTab); true }
            e.keyCode == KeyEvent.VK_TAB -> {
                val ids = listOf<String?>(null) + app.tabs.map { it.id }
                val step = if (e.isShiftDown) ids.size - 1 else 1
                app.activeTabId = ids[(ids.indexOf(app.activeTabId) + step) % ids.size]
                true
            }
            else -> false
        }
    }
}

/** Fenster-Icon aus dem Klassenpfad. */
private fun appIcon(): BitmapPainter {
    val stream = requireNotNull(AppState::class.java.getResourceAsStream("/icons/app.png")) { "App-Icon fehlt" }
    return BitmapPainter(stream.use { javax.imageio.ImageIO.read(it) }.toComposeImageBitmap())
}
