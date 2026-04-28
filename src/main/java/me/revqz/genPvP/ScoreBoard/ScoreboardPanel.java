package me.revqz.genPvP.Scoreboard;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link JPanel} that renders a multi-line scoreboard exactly like Minecraft's
 * in-game text — per-segment colors (&#RRGGBB hex + legacy &amp;codes) with the
 * classic Minecraft drop-shadow depth effect.
 *
 * <h3>Shadow algorithm (matches Minecraft's font renderer)</h3>
 * For each text segment:
 * <ol>
 *   <li>Compute shadow color: {@code new Color(r>>2, g>>2, b>>2)} — 25 % brightness.</li>
 *   <li>Draw the segment in the shadow color at {@code (x+1, y+1)}.</li>
 *   <li>Draw the segment in its real color at {@code (x, y)}.</li>
 * </ol>
 * This produces the characteristic bottom-right depth seen in vanilla Minecraft text.
 *
 * <h3>Usage</h3>
 * <pre>
 *   ScoreboardPanel panel = new ScoreboardPanel(200, 300);
 *   panel.setLines(resolvedLines);          // call each tick after PAPI resolution
 *   BufferedImage img = panel.renderToImage(200, 300);  // for map display
 * </pre>
 *
 * Lines are set via {@link #setLines(List)} and re-parsed on every paint/render,
 * so color codes and animation frames are always reflected immediately.
 */
public class ScoreboardPanel extends JPanel {

    private final List<String> lines = new ArrayList<>();
    private float   fontSize   = 14f;
    private int     lineHeight = 20;
    private int     padX       = 6;
    private int     padY       = 20;
    private Color   background = null; // null = transparent

    public ScoreboardPanel(int width, int height) {
        setPreferredSize(new Dimension(width, height));
        setOpaque(false);
    }

    // ── Configuration setters ─────────────────────────────────────────────────

    /** Replaces the current lines and triggers a repaint. */
    public void setLines(List<String> newLines) {
        lines.clear();
        lines.addAll(newLines);
        repaint();
    }

    public void setFontSize(float size)   { this.fontSize   = size; }
    public void setLineHeight(int h)       { this.lineHeight = h; }
    public void setPadding(int x, int y)   { this.padX = x; this.padY = y; }

    /** {@code null} = transparent background; otherwise fills the panel before drawing text. */
    public void setBackground(Color bg) {
        this.background = bg;
        setOpaque(bg != null);
    }

    // ── Swing rendering ───────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g.create();
        try {
            applyHints(g2d);
            paintBackground(g2d, getWidth(), getHeight());
            drawLines(g2d, resolveFont(), lines, padX, padY, lineHeight);
        } finally {
            g2d.dispose();
        }
    }

    // ── Off-screen rendering (for map display etc.) ───────────────────────────

    /**
     * Renders the current line list into a new {@link BufferedImage} of the
     * requested dimensions. Safe to call from any thread.
     */
    public BufferedImage renderToImage(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        try {
            applyHints(g2d);
            paintBackground(g2d, width, height);
            drawLines(g2d, resolveFont(), lines, padX, padY, lineHeight);
        } finally {
            g2d.dispose();
        }
        return img;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void applyHints(Graphics2D g2d) {
        // Nearest-neighbour keeps the pixelated Minecraft font sharp — no blurring
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_SPEED);
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,     RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }

    private void paintBackground(Graphics2D g2d, int w, int h) {
        if (background == null) return;
        g2d.setColor(background);
        g2d.fillRect(0, 0, w, h);
    }

    private Font resolveFont() {
        Font f = new Font("Minecraft", Font.PLAIN, Math.round(fontSize));
        // Fall back to monospaced if the Minecraft font is not installed on this JVM
        if (f.getFamily().equals("Dialog")) {
            f = new Font("Monospaced", Font.PLAIN, Math.round(fontSize));
        }
        return f;
    }

    /**
     * Renders all {@code rawLines} into the supplied {@link Graphics2D}, each at an
     * incrementing Y position. Parsing and outline drawing happen here.
     */
    private static void drawLines(Graphics2D g2d, Font base,
                                   List<String> rawLines, int x, int startY, int lineHeight) {
        int y = startY;
        for (String raw : rawLines) {
            drawOutlinedLine(g2d, base, ScoreboardColorUtil.parseSegments(raw), x, y);
            y += lineHeight;
        }
    }

    /**
     * Draws one line of colored, formatted text with Minecraft's classic drop-shadow.
     *
     * <p>For each {@link ScoreboardColorUtil.Segment}:
     * <ol>
     *   <li>Derive the shadow color: {@code new Color(r>>2, g>>2, b>>2)} (25 % brightness).</li>
     *   <li>Draw the segment in the shadow color at {@code (curX+1, y+1)} — the depth layer.</li>
     *   <li>Draw the segment in its real color at {@code (curX, y)} — on top.</li>
     *   <li>Advance {@code curX} by the segment's pixel width.</li>
     * </ol>
     */
    private static void drawOutlinedLine(Graphics2D g2d, Font base,
                                          List<ScoreboardColorUtil.Segment> segments,
                                          int x, int y) {
        int curX = x;
        for (ScoreboardColorUtil.Segment seg : segments) {
            if (seg.text().isEmpty()) continue;

            Font font = derivedFont(base, seg);
            g2d.setFont(font);
            FontMetrics fm = g2d.getFontMetrics(font);

            // ── Shadow pass: bottom-right at +1,+1 in 25 % brightness ─────
            Color real   = seg.color();
            Color shadow = new Color(real.getRed()   >> 2,
                                     real.getGreen() >> 2,
                                     real.getBlue()  >> 2);
            g2d.setColor(shadow);
            g2d.drawString(seg.text(), curX + 1, y + 1);

            // ── Real color pass on top ─────────────────────────────────────
            g2d.setColor(real);
            g2d.drawString(seg.text(), curX, y);

            curX += fm.stringWidth(seg.text());
        }
    }

    private static Font derivedFont(Font base, ScoreboardColorUtil.Segment seg) {
        int style = Font.PLAIN;
        if (seg.bold())   style |= Font.BOLD;
        if (seg.italic()) style |= Font.ITALIC;
        return style == Font.PLAIN ? base : base.deriveFont(style);
    }
}
