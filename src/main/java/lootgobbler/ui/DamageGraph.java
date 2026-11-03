package lootgobbler.ui;

import lootgobbler.DungeonVisits.Visit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;

/**
 * The damage you took in a dungeon over time: one bar per hit, its height the share of your maximum life (the top
 * is a full life bar), so dangerous hits stand out. A dashed line marks 30%; hits above it get the icon of the enemy
 * that dealt them. Reads the visit's hits on every paint, so the dungeon in progress follows along on repaint().
 */
final class DamageGraph extends JComponent {
    static final double BIG_HIT = 0.30;   // of your maximum life
    private static final int PAD_LEFT = 38, PAD_RIGHT = 10, PAD_TOP = 30, PAD_BOTTOM = 20, ICON = 22;
    private static final Color BAR = new Color(0xE5636E), BIG = new Color(0xFF8A2A);
    private final Visit visit;
    private final Map<Integer, Icon> icons = new HashMap<>();

    DamageGraph(Visit visit) {
        this.visit = visit;
        setPreferredSize(new Dimension(300, 190));
        setToolTipText("");   // per hit, see getToolTipText(MouseEvent)
    }

    /** Your maximum life, or else the biggest hit (so the graph still has a scale). */
    private int scale() {
        if (visit.maxHp > 0) return visit.maxHp;
        int max = 1;
        for (int i = 0; i < visit.hits(); i++) max = Math.max(max, visit.hitDamage[i]);
        return max;
    }

    private long span() {
        long last = visit.hits() == 0 ? 0 : visit.hitTime[visit.hits() - 1];
        return Math.max(1000, Math.max(visit.duration(), last));
    }

    private int x(int t) {
        int w = getWidth() - PAD_LEFT - PAD_RIGHT;
        return PAD_LEFT + (int) Math.round((double) t / span() * w);
    }

    private int y(int dmg) {
        int h = getHeight() - PAD_TOP - PAD_BOTTOM;
        return getHeight() - PAD_BOTTOM - (int) Math.round(Math.min(1.0, (double) dmg / scale()) * h);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight(), base = h - PAD_BOTTOM;
        Font small = getFont().deriveFont(Math.max(10f, getFont().getSize2D() - 2));
        g2.setFont(small);
        FontMetrics fm = g2.getFontMetrics();

        g2.setColor(new Color(0, 0, 0, 60));   // the plot
        g2.fillRect(PAD_LEFT, PAD_TOP, w - PAD_LEFT - PAD_RIGHT, base - PAD_TOP);
        g2.setColor(Palette.MUTED);
        for (int pct : new int[]{0, 50, 100}) {   // life scale on the left
            int y = y(scale() * pct / 100);
            String s = pct + "%";
            g2.drawString(s, PAD_LEFT - 6 - fm.stringWidth(s), y + fm.getAscent() / 2 - 1);
        }
        long span = span();   // time along the bottom
        for (int i = 0; i <= 4; i++) {
            int t = (int) (span * i / 4);
            String s = DpsPanel.duration(t);
            int x = x(t) - (i == 4 ? fm.stringWidth(s) : i == 0 ? 0 : fm.stringWidth(s) / 2);
            g2.drawString(s, x, h - 4);
        }
        int line = y((int) Math.round(scale() * BIG_HIT));   // the 30% mark
        g2.setColor(new Color(BIG.getRed(), BIG.getGreen(), BIG.getBlue(), 150));
        g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 4f}, 0f));
        g2.drawLine(PAD_LEFT, line, w - PAD_RIGHT, line);
        g2.setStroke(new BasicStroke(1f));

        int n = visit.hits();
        if (n == 0) {
            g2.setColor(Palette.MUTED);
            String s = "No damage taken";
            g2.drawString(s, PAD_LEFT + (w - PAD_LEFT - PAD_RIGHT - fm.stringWidth(s)) / 2, (PAD_TOP + base) / 2);
            g2.dispose();
            return;
        }
        int bigAt = (int) Math.round(scale() * BIG_HIT);
        for (int i = 0; i < n; i++) {   // one bar per hit
            int x = x(visit.hitTime[i]), y = y(visit.hitDamage[i]);
            g2.setColor(visit.hitDamage[i] >= bigAt ? BIG : BAR);
            g2.fillRect(x - 1, y, 2, Math.max(1, base - y));
        }
        int lastIcon = -10_000;   // the enemies behind the big hits, without piling icons on each other
        for (int i = 0; i < n; i++) {
            if (visit.hitDamage[i] < bigAt) continue;
            int x = x(visit.hitTime[i]);
            if (x - lastIcon < ICON) continue;
            Icon icon = enemyIcon(visit.hitEnemy[i]);
            if (icon == null) continue;
            icon.paintIcon(this, g2, x - ICON / 2, Math.max(2, y(visit.hitDamage[i]) - ICON - 2));
            lastIcon = x;
        }
        g2.dispose();
    }

    private Icon enemyIcon(int type) {
        if (type <= 0) return null;
        return icons.computeIfAbsent(type, t -> CharactersView.sprite(t, ICON));
    }

    /** The hit under the mouse: when, how much (and the share of your life) and by whom. */
    @Override
    public String getToolTipText(MouseEvent e) {
        int best = -1, bestDist = 4;
        for (int i = 0; i < visit.hits(); i++) {
            int d = Math.abs(x(visit.hitTime[i]) - e.getX());
            if (d <= bestDist && (best < 0 || visit.hitDamage[i] > visit.hitDamage[best])) {
                best = i;
                bestDist = d;
            }
        }
        if (best < 0) return null;
        int dmg = visit.hitDamage[best];
        String who = visit.hitEnemy[best] > 0 ? assets.IdToAsset.objectName(visit.hitEnemy[best]) : "the ground or an area effect";
        String share = visit.maxHp > 0 ? " (" + Math.round(100.0 * dmg / visit.maxHp) + "% of your life)" : "";
        return DpsPanel.duration(visit.hitTime[best]) + ":  " + dmg + " damage" + share + " from " + who;
    }
}
