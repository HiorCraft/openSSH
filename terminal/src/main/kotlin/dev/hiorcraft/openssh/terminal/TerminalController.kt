package dev.hiorcraft.openssh.terminal

import com.jediterm.terminal.TtyConnector
import com.jediterm.terminal.ui.JediTermWidget
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JScrollBar
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicScrollBarUI

/**
 * Hält ein JediTerm-Widget und seinen Connector. Die UI bettet [widget] ein
 * (z.B. per `SwingPanel`), Snippets werden über [send] eingefügt.
 */
class TerminalController(
    settings: TerminalSettings,
    val connector: TtyConnector,
    columns: Int = 120,
    rows: Int = 32,
) : AutoCloseable {

    val widget: JediTermWidget = object : JediTermWidget(columns, rows, ThemedSettingsProvider(settings)) {
        override fun createScrollBar(): JScrollBar = super.createScrollBar().apply { setUI(ThemedScrollBarUI(settings.theme)) }
    }

    @Volatile
    private var started = false

    /** Startet die Ausgabe-Verarbeitung; muss nur einmal aufgerufen werden. */
    fun start() {
        if (started) return
        started = true
        widget.setTtyConnector(connector)
        widget.start()
    }

    /** Schickt Text an die Shell, als hätte der Benutzer ihn getippt. */
    fun send(text: String, execute: Boolean = false) {
        val payload = if (execute && !text.endsWith("\n") && !text.endsWith("\r")) "$text\r" else text
        runCatching { connector.write(payload) }
    }

    fun focus() = SwingUtilities.invokeLater { widget.requestFocusInWindow() }

    val isConnected: Boolean get() = runCatching { connector.isConnected }.getOrDefault(false)

    override fun close() {
        runCatching { connector.close() }
        runCatching { widget.close() }
    }
}

/** Schlanke Scrollleiste in den Farben des Terminal-Themes statt der weißen Swing-Standardleiste. */
private class ThemedScrollBarUI(private val theme: TerminalTheme) : BasicScrollBarUI() {
    private fun color(rgb: Int, alpha: Int = 255) = Color(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF, alpha)

    override fun configureScrollBarColors() {
        trackColor = color(theme.background)
        thumbColor = color(theme.foreground, 70)
    }

    override fun getPreferredSize(c: JComponent): Dimension = Dimension(10, super.getPreferredSize(c).height)

    override fun createDecreaseButton(orientation: Int): JButton = invisibleButton()
    override fun createIncreaseButton(orientation: Int): JButton = invisibleButton()

    private fun invisibleButton() = JButton().apply {
        preferredSize = Dimension(0, 0)
        minimumSize = Dimension(0, 0)
        maximumSize = Dimension(0, 0)
    }

    override fun paintTrack(g: Graphics, c: JComponent, trackBounds: Rectangle) {
        g.color = trackColor
        g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height)
    }

    override fun paintThumb(g: Graphics, c: JComponent, thumbBounds: Rectangle) {
        if (thumbBounds.isEmpty) return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (isDragging) color(theme.foreground, 130) else thumbColor
        g2.fillRoundRect(thumbBounds.x + 2, thumbBounds.y + 2, thumbBounds.width - 4, thumbBounds.height - 4, 6, 6)
        g2.dispose()
    }
}
