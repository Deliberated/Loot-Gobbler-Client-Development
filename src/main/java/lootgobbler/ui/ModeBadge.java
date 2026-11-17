package lootgobbler.ui;

import lootgobbler.Upe;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;

/**
 * The little plate of a character's game mode on its loadout card (under the fame): the mode's name on a fade of its
 * own colors. PPE orange into brown, UPE purple into gold, pure UPE purple, STPE gold, shiny UPE holographic with sparkles,
 * divine PE gold with stars and light beams. A guild version shows its plain mode in the plate and a small gold
 * shield right of it; a run without a pet a struck-out paw right of that.
 */
final class ModeBadge {
    static final int H = 26;
    private static final int PAD = 10, SHIELD = 16, SHIELD_GAP = 5, PAW = 22;

    /** Which way a plate's colors fade: from the first color to the second. */
    enum Fade { DOWN, UP, RIGHT, LEFT }

    static final Fade DEFAULT_FADE = Fade.DOWN;

    private ModeBadge() {
    }

    private static Font font() {
        return new Font(lootgobbler.Themes.HEAD_FONT, Font.PLAIN, 17);
    }

    /** What the plate says: the mode without its guild part (the shield shows that). */
    private static String text(String mode) {
        return Upe.label(Upe.base(mode));
    }

    private static int plateWidth(String mode) {
        Graphics2D m = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();
        int w = PAD + m.getFontMetrics(font()).stringWidth(text(mode)) + PAD;
        m.dispose();
        return w;
    }

    /** How wide the plate (with the guild's shield and the no-pet paw) is drawn. */
    static int width(String mode, boolean noPet) {
        return plateWidth(mode) + (Upe.guild(mode) ? SHIELD_GAP + SHIELD : 0) + (noPet ? SHIELD_GAP + PAW : 0);
    }

    static void draw(Graphics2D g, int x, int y, String mode, boolean noPet) {
        draw(g, x, y, mode, noPet, DEFAULT_FADE);
    }

    /** Draws the plate with its top-left corner at (x, y). */
    static void draw(Graphics2D g0, int x, int y, String mode, boolean noPet, Fade fade) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        String base = Upe.base(mode);
        int w = plateWidth(mode);
        RoundRectangle2D plate = new RoundRectangle2D.Float(x, y, w, H, 10, 10);
        Style s = style(base);
        boolean divine = Upe.DIVINE_PE.equals(base), shiny = Upe.SHINY_UPE.equals(base);

        if (divine) beams(g, x, y, w);   // light behind the plate
        if (s.glow != null) {   // a soft glow around it
            for (int i = 5; i >= 1; i--) {
                g.setColor(alpha(s.glow, divine ? 40 : 30));
                g.fill(new RoundRectangle2D.Float(x - i, y - i, w + 2 * i, H + 2 * i, 10 + 2 * i, 10 + 2 * i));
            }
        }
        g.setPaint(paint(s.colors, x, y, w, fade));
        g.fill(plate);
        g.setPaint(new GradientPaint(x, y, new Color(255, 255, 255, 60), x, y + H / 2f, new Color(255, 255, 255, 0)));   // a sheen on top
        g.fill(new RoundRectangle2D.Float(x + 1, y + 1, w - 2, H / 2f, 9, 9));
        if (divine) starsOn(g, x, y, w);
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(s.edge);
        g.draw(plate);

        Font f = font();
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        String label = text(mode);
        int tx = x + (w - fm.stringWidth(label)) / 2, ty = y + (H + fm.getAscent() - fm.getDescent()) / 2;
        if (s.textShadow != null) {
            g.setColor(s.textShadow);
            g.drawString(label, tx + 1, ty + 1);
        }
        g.setColor(s.text);
        g.drawString(label, tx, ty);
        if (shiny) sparkles(g, x, y, w);   // over the text's edges too: they should be seen
        int after = x + w;
        if (Upe.guild(mode)) {
            shield(g, after + SHIELD_GAP, y + (H - SHIELD - 4) / 2f);
            after += SHIELD_GAP + SHIELD;
        }
        if (noPet) noPetIcon(g, after + SHIELD_GAP, y + (H - PAW) / 2);
        g.dispose();
    }

    private record Style(Color[] colors, Color edge, Color text, Color textShadow, Color glow) {
    }

    private static Style style(String base) {
        return switch (base) {
            case Upe.PPE -> new Style(c(0xF2A64A, 0x9A5420), new Color(0xFFCB8A), Color.WHITE, new Color(0x4A2208), null);   // orange into brown
            case Upe.PUPE -> new Style(c(0xB57BE8, 0x4E2384), new Color(0xDDB8F7), Color.WHITE, new Color(0x240B40), null);
            case Upe.STPE -> new Style(c(0xFFEA8A, 0xE0A815), new Color(0xFFF6C8), new Color(0x4A3000), new Color(255, 255, 255, 110), new Color(0xFFD24A));   // bright gold, a soft glow
            case Upe.SHINY_UPE -> new Style(new Color[]{new Color(0xFF7EC8), new Color(0xFFE27A), new Color(0x7DF3E1), new Color(0x8FA8FF), new Color(0xD58BFF)},
                    Color.WHITE, Color.WHITE, new Color(0x4A2A6A), new Color(0xBFF6FF));
            case Upe.DIVINE_PE -> new Style(c(0xFFE68C, 0xC98A12), new Color(0xFFF6CF), new Color(0x4A2E00), new Color(255, 255, 255, 90), new Color(0xF2C232));
            default -> new Style(c(0x8E4FD0, 0xE0A92A), new Color(0xE6C8FF), Color.WHITE, new Color(0x2A0F45), null);   // UPE: purple into gold
        };
    }

    private static Color[] c(int a, int b) {
        return new Color[]{new Color(a), new Color(b)};
    }

    /** The fade over the plate, from the first color to the last in the given direction. */
    private static Paint paint(Color[] colors, int x, int y, int w, Fade fade) {
        Point2D from, to;
        switch (fade) {
            case UP -> { from = new Point2D.Float(x, y + H); to = new Point2D.Float(x, y); }
            case RIGHT -> { from = new Point2D.Float(x, y); to = new Point2D.Float(x + w, y); }
            case LEFT -> { from = new Point2D.Float(x + w, y); to = new Point2D.Float(x, y); }
            default -> { from = new Point2D.Float(x, y); to = new Point2D.Float(x, y + H); }
        }
        float[] at = new float[colors.length];
        for (int i = 0; i < at.length; i++) at[i] = at.length == 1 ? 0 : i / (float) (at.length - 1);
        return new LinearGradientPaint(from, to, at, colors);
    }

    /** A four-pointed star around (cx, cy). */
    private static void star(Graphics2D g, float cx, float cy, float r, float waist, Color fill) {
        Path2D p = new Path2D.Float();
        p.moveTo(cx, cy - r);
        p.lineTo(cx + waist, cy - waist);
        p.lineTo(cx + r, cy);
        p.lineTo(cx + waist, cy + waist);
        p.lineTo(cx, cy + r);
        p.lineTo(cx - waist, cy + waist);
        p.lineTo(cx - r, cy);
        p.lineTo(cx - waist, cy - waist);
        p.closePath();
        g.setColor(fill);
        g.fill(p);
    }

    /** A star with a soft halo, to stand out on a bright plate. */
    private static void glint(Graphics2D g, float cx, float cy, float r) {
        g.setPaint(new RadialGradientPaint(cx, cy, r * 1.2f, new float[]{0f, 1f}, new Color[]{new Color(255, 255, 255, 170), new Color(255, 255, 255, 0)}));
        g.fill(new java.awt.geom.Ellipse2D.Float(cx - r * 1.2f, cy - r * 1.2f, r * 2.4f, r * 2.4f));
        star(g, cx, cy, r, r * 0.22f, Color.WHITE);
    }

    /** Sparkles on and around a shiny plate, big enough to be seen. */
    private static void sparkles(Graphics2D g, int x, int y, int w) {
        float[][] at = {{-3, 3, 6}, {w - 4, 4, 6.5f}, {w * 0.3f, H - 2, 4}, {w * 0.62f, 1, 4.5f}, {w + 2, H - 4, 4.5f},
                {6, H - 3, 3.2f}, {w * 0.82f, H - 1, 3.5f}};
        for (float[] a : at) glint(g, x + a[0], y + a[1], a[2]);
    }

    /** Small stars over a divine plate. */
    private static void starsOn(Graphics2D g, int x, int y, int w) {
        float[][] at = {{6, 6, 3.2f}, {w - 7, 7, 3.6f}, {w * 0.28f, H - 6, 2.4f}, {w * 0.7f, H - 5, 2.6f}, {w * 0.5f, 4, 2f}};
        for (float[] a : at) star(g, x + a[0], y + a[1], a[2], a[2] * 0.25f, new Color(255, 255, 240, 220));
    }

    /** Golden light beams fanning out behind a divine plate. */
    private static void beams(Graphics2D g, int x, int y, int w) {
        float cx = x + w / 2f, cy = y + H / 2f;
        Graphics2D r = (Graphics2D) g.create();
        r.setPaint(new RadialGradientPaint(cx, cy, w * 0.85f, new float[]{0f, 1f},
                new Color[]{new Color(255, 220, 100, 185), new Color(255, 220, 100, 0)}));
        for (int i = 0; i < 18; i++) {
            double a = Math.PI * 2 * i / 18, b = a + 0.11;
            Path2D p = new Path2D.Float();
            p.moveTo(cx, cy);
            p.lineTo(cx + Math.cos(a) * w * 0.85, cy + Math.sin(a) * H * 1.15);
            p.lineTo(cx + Math.cos(b) * w * 0.85, cy + Math.sin(b) * H * 1.15);
            p.closePath();
            r.fill(p);
        }
        r.dispose();
    }

    /** A small plain gold heater shield: the guild. */
    static void shield(Graphics2D g, float x, float y) {
        float w = SHIELD, h = SHIELD + 4;
        Path2D p = new Path2D.Float();
        p.moveTo(x, y);
        p.lineTo(x + w, y);
        p.lineTo(x + w, y + h * 0.45f);
        p.quadTo(x + w, y + h * 0.85f, x + w / 2, y + h);
        p.quadTo(x, y + h * 0.85f, x, y + h * 0.45f);
        p.closePath();
        g.setPaint(new GradientPaint(x, y, new Color(0xF6D36B), x, y + h, new Color(0xA8740E)));
        g.fill(p);
        g.setStroke(new BasicStroke(1.4f));
        g.setColor(new Color(0x4A3205));
        g.draw(p);
        g.setColor(new Color(255, 255, 255, 90));   // a light line down its middle
        g.setStroke(new BasicStroke(1f));
        g.drawLine(Math.round(x + w / 2), Math.round(y + 3), Math.round(x + w / 2), Math.round(y + h - 4));
    }

    /** A paw print struck out in red: played without a pet. */
    static void noPetIcon(Graphics2D g, int x, int y) {
        float k = PAW / 16f;   // the paw is drawn on a 16 px grid
        java.awt.geom.Area paw = new java.awt.geom.Area();
        float[][] parts = {{3.5f, 7.5f, 9, 7.5f}, {0.5f, 4.5f, 3.6f, 4.4f}, {4.2f, 1f, 3.6f, 4.6f}, {8.4f, 1f, 3.6f, 4.6f}, {12f, 4.5f, 3.6f, 4.4f}};
        for (float[] e : parts) paw.add(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Float(x + e[0] * k, y + e[1] * k, e[2] * k, e[3] * k)));
        Graphics2D sh = (Graphics2D) g.create();   // a soft dark shadow, so it shows on any card
        sh.translate(1, 1.2);
        sh.setColor(new Color(0, 0, 0, 120));
        sh.fill(paw);
        sh.dispose();
        g.setColor(new Color(0xE6EAEE));
        g.fill(paw);
        g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));   // the strike, outlined
        g.setColor(new Color(0x5A1410));
        g.drawLine(x + 1, y + PAW - 2, x + PAW - 1, y + 1);
        g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0xFF5A4E));
        g.drawLine(x + 1, y + PAW - 2, x + PAW - 1, y + 1);
    }

    /** The guild's shield as an icon (for the guild setting), size px tall. */
    static javax.swing.Icon shieldIcon(int size) {
        return icon(SHIELD + 1, SHIELD + 5, size, g -> shield(g, 0.5f, 0.5f));
    }

    /** The no-pet paw as an icon (for the no-pet setting), size px tall. */
    static javax.swing.Icon noPetIcon(int size) {
        return icon(PAW + 2, PAW + 2, size, g -> noPetIcon(g, 0, 0));
    }

    private static javax.swing.Icon icon(int w, int h, int size, java.util.function.Consumer<Graphics2D> paint) {
        float k = size / (float) h;
        return new javax.swing.Icon() {
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.translate(x, y);
                g.scale(k, k);
                paint.accept(g);
                g.dispose();
            }

            public int getIconWidth() {
                return Math.round(w * k);
            }

            public int getIconHeight() {
                return size;
            }
        };
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }
}
