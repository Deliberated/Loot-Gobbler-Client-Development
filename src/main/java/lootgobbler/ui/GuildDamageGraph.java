package lootgobbler.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;

/**
 * The damage everyone of your guild took in a dungeon, added up per slice of time and stacked per player in their
 * color (you at the bottom). As many slices as fit (at least 2 seconds each); hovering one lists who took what.
 */
final class GuildDamageGraph extends JComponent {
    /** One player: name, color, and their hits as ms after the dungeon's start with the damage. */
    record Series(String name, Color color, long[] at, int[] damage) {
        long total() {
            long t = 0;
            for (int d : damage) t += d;
            return t;
        }
    }

    private static final int HEIGHT = 150, LEFT = 46, BOTTOM = 18, TOP = 8, MIN_BAR = 5;
    private final List<Series> series;
    private final long durationMs;
    private int hover = -1;

    GuildDamageGraph(List<Series> series, long durationMs) {
        this.series = series;
        this.durationMs = Math.max(1000, durationMs);
        setOpaque(false);
        setToolTipText("");   // the text comes from getToolTipText(MouseEvent)
        MouseAdapter m = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int b = binAt(e.getX());
                if (b != hover) {
                    hover = b;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = -1;
                repaint();
            }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(400, HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, HEIGHT);
    }

    private int plotWidth() {
        return Math.max(1, getWidth() - LEFT - 4);
    }

    /** The slice length: whole seconds, at least 2, so that every slice gets a bar at least MIN_BAR wide. */
    private long binMs() {
        int fit = Math.max(1, plotWidth() / MIN_BAR);
        long ms = (durationMs + fit - 1) / fit;
        return Math.max(2000, (ms + 999) / 1000 * 1000);
    }

    private int bins() {
        return (int) ((durationMs + binMs() - 1) / binMs());
    }

    private int binAt(int x) {
        if (x < LEFT || x >= LEFT + plotWidth()) return -1;
        return Math.min(bins() - 1, (int) ((long) (x - LEFT) * bins() / plotWidth()));
    }

    /** Per player and slice, the damage taken. */
    private long[][] sums() {
        long bin = binMs();
        int n = bins();
        long[][] out = new long[series.size()][n];
        for (int s = 0; s < series.size(); s++) {
            Series se = series.get(s);
            for (int i = 0; i < se.at().length && i < se.damage().length; i++) {
                int b = (int) (se.at()[i] / bin);
                if (b >= 0 && b < n) out[s][b] += se.damage()[i];
            }
        }
        return out;
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        int b = binAt(e.getX());
        if (b < 0) return null;
        long[][] sums = sums();
        long bin = binMs();
        StringBuilder sb = new StringBuilder("<html>").append(DpsPanel.duration(b * bin)).append(" to ")
                .append(DpsPanel.duration(Math.min(durationMs, (b + 1) * bin)));
        long total = 0;
        for (int s = 0; s < series.size(); s++) {
            if (sums[s][b] == 0) continue;
            total += sums[s][b];
            Color c = series.get(s).color();
            sb.append(String.format(Locale.ROOT, "<br><font color='#%02x%02x%02x'>&#9632;</font> %s: %,d",
                    c.getRed(), c.getGreen(), c.getBlue(), escape(series.get(s).name()), sums[s][b]));
        }
        if (total == 0) sb.append("<br>No damage taken");
        else sb.append(String.format(Locale.ROOT, "<br><b>Together: %,d</b>", total));
        return sb.append("</html>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        long[][] sums = sums();
        int n = bins(), w = plotWidth(), base = getHeight() - BOTTOM, h = base - TOP;
        long max = 1;
        for (int b = 0; b < n; b++) {
            long t = 0;
            for (long[] s : sums) t += s[b];
            max = Math.max(max, t);
        }
        Color ink = Section.color("LootGobbler.ink", Color.WHITE), soft = Section.color("LootGobbler.inkSoft", Palette.MUTED);
        g2.setColor(Section.color("TextArea.background", new Color(0x1E1610)));
        g2.fillRect(LEFT, TOP, w, h);
        g2.setColor(new Color(soft.getRed(), soft.getGreen(), soft.getBlue(), 40));   // a faint middle line
        g2.drawLine(LEFT, TOP + h / 2, LEFT + w - 1, TOP + h / 2);

        for (int b = 0; b < n; b++) {   // stacked: you at the bottom, then the others in order
            int x0 = LEFT + (int) ((long) b * w / n), x1 = LEFT + (int) ((long) (b + 1) * w / n);
            int bw = Math.max(1, x1 - x0 - 1);
            long below = 0;
            for (int s = 0; s < sums.length; s++) {
                if (sums[s][b] == 0) continue;
                int y0 = base - (int) Math.round(h * (double) below / max);
                below += sums[s][b];
                int y1 = base - (int) Math.round(h * (double) below / max);
                g2.setColor(series.get(s).color());
                g2.fillRect(x0, y1, bw, Math.max(1, y0 - y1));
            }
            if (b == hover) {
                g2.setColor(new Color(255, 255, 255, 40));
                g2.fillRect(x0, TOP, bw, h);
            }
        }

        g2.setFont(getFont().deriveFont(13f));
        FontMetrics fm = g2.getFontMetrics();
        g2.setColor(soft);
        String top = String.format(Locale.ROOT, "%,d", max);
        g2.drawString(top, LEFT - 6 - fm.stringWidth(top), TOP + fm.getAscent());
        g2.drawString("0", LEFT - 6 - fm.stringWidth("0"), base);
        g2.setColor(ink);
        String[] times = {"0:00", DpsPanel.duration(durationMs / 2), DpsPanel.duration(durationMs)};
        int[] xs = {LEFT, LEFT + w / 2 - fm.stringWidth(times[1]) / 2, LEFT + w - fm.stringWidth(times[2])};
        for (int i = 0; i < 3; i++) g2.drawString(times[i], xs[i], base + fm.getAscent() + 3);
        g2.dispose();
    }
}
