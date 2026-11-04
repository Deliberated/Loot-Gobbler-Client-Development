package lootgobbler.ui;

import lootgobbler.DungeonVisits.Visit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Damage in a dungeon per time slice (3 seconds), mirrored around one line: above it the damage you dealt (gold, the
 * biggest slice just below the top), below it the damage you took (the bottom being your whole life, marked in thirds
 * on the left). Slices taking 30% of your life or 300 damage are orange, with the icon of the enemy that dealt most of
 * it below them. Reads the visit on every paint, so the dungeon in progress follows along on repaint().
 */
final class DamageGraph extends JComponent {
    static final double BIG_SHARE = 0.30;   // of your maximum life, within one slice
    static final int BIG_DAMAGE = 300;      // or this much, within one slice
    static final int[] WINDOWS_MS = {3000};   // the graphs shown: slices of 3 seconds
    private static final double HEADROOM = 1.08;   // without a known life: the biggest slice just below the top
    private static final int PAD_LEFT = 44, PAD_RIGHT = 10, PAD_TOP = 28, PAD_BOTTOM = 20, ICON = 22;
    private static final Color BAR = new Color(0xE5636E), BIG = new Color(0xFF8A2A), DEALT = new Color(0xE8B83A);
    private final Visit visit;
    private final int windowMs;
    private final Map<Integer, Icon> icons = new HashMap<>();

    /** The hits of a visit added up per slice: total damage, number of hits and the enemy that dealt the most. */
    record Slices(long[] damage, int[] hits, int[] enemy, int[] enemyDamage) {
        int count() {
            return damage.length;
        }
    }

    DamageGraph(Visit visit, int windowMs) {
        this.visit = visit;
        this.windowMs = windowMs;
        setPreferredSize(new Dimension(300, 230));
        setToolTipText("");   // per slice, see getToolTipText(MouseEvent)
    }

    /** Damage at which a slice counts as dangerous: 30% of your life or 300, whichever is less. */
    static int threshold(Visit v) {
        return v.maxHp > 0 ? (int) Math.min(BIG_DAMAGE, Math.round(v.maxHp * BIG_SHARE)) : BIG_DAMAGE;
    }

    /** How long the graph covers: the visit, or at least up to its last hit. */
    private static long span(Visit v) {
        long last = v.hits() == 0 ? 0 : v.hitTime[v.hits() - 1];
        return Math.max(1000, Math.max(v.duration(), last + 1));
    }

    /** The visit's hits added up per slice of windowMs, from entering the dungeon on. */
    static Slices slices(Visit v, int windowMs) {
        int n = (int) Math.max(1, (span(v) + windowMs - 1) / windowMs);
        long[] damage = new long[n];
        int[] hits = new int[n], enemy = new int[n], enemyDamage = new int[n];
        Map<Long, Integer> byEnemy = new HashMap<>();   // slice in the high 32 bits, enemy type in the low ones
        for (int i = 0; i < v.hits(); i++) {
            int s = Math.min(n - 1, v.hitTime[i] / windowMs);
            damage[s] += v.hitDamage[i];
            hits[s]++;
            byEnemy.merge(((long) s << 32) | (v.hitEnemy[i] & 0xFFFFFFFFL), v.hitDamage[i], Integer::sum);
        }
        for (Map.Entry<Long, Integer> e : byEnemy.entrySet()) {   // an enemy before the ground or an area effect
            int s = (int) (e.getKey() >>> 32), type = (int) (long) e.getKey();
            boolean first = enemy[s] == 0 && enemyDamage[s] == 0;
            boolean better = type > 0 && (enemy[s] == 0 || e.getValue() > enemyDamage[s]);
            if (first || better) {
                enemy[s] = type;
                enemyDamage[s] = e.getValue();
            }
        }
        return new Slices(damage, hits, enemy, enemyDamage);
    }

    /** How many slices of windowMs reach 30% of your life or 300 damage. */
    static int dangerous(Visit v, int windowMs) {
        int threshold = threshold(v), n = 0;
        for (long d : slices(v, windowMs).damage()) if (d >= threshold) n++;
        return n;
    }

    private int x(long t) {
        int w = getWidth() - PAD_LEFT - PAD_RIGHT;
        return PAD_LEFT + (int) Math.round((double) t / span(visit) * w);
    }

    private int y(long dmg, long scale) {
        int h = getHeight() - PAD_TOP - PAD_BOTTOM;
        return getHeight() - PAD_BOTTOM - (int) Math.round(Math.min(1.0, (double) dmg / scale) * h);
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
        long span = span(visit);   // time along the bottom
        for (int i = 0; i <= 4; i++) {
            int t = (int) (span * i / 4);
            String s = DpsPanel.duration(t);
            int x = x(t) - (i == 4 ? fm.stringWidth(s) : i == 0 ? 0 : fm.stringWidth(s) / 2);
            g2.drawString(s, x, h - 4);
        }
        long[] dealt = dealtSlices(visit, windowMs);
        long dealtPeak = 0;
        for (long d : dealt) dealtPeak = Math.max(dealtPeak, d);
        if (visit.hits() == 0 && dealtPeak == 0) {
            String s = "No damage taken or dealt";
            g2.drawString(s, PAD_LEFT + (w - PAD_LEFT - PAD_RIGHT - fm.stringWidth(s)) / 2, (PAD_TOP + base) / 2);
            g2.dispose();
            return;
        }
        int mid = mid();
        Slices sl = slices(visit, windowMs);
        // below the line: damage taken, the bottom being your whole life (a slice taking more is cut off there)
        long scale;
        if (visit.maxHp > 0) {
            scale = visit.maxHp;
        } else {
            long peak = 1;
            for (long d : sl.damage()) peak = Math.max(peak, d);
            scale = Math.max(1, Math.round(peak * HEADROOM));
        }
        for (long v : new long[]{scale / 3, scale * 2 / 3, scale}) {   // three steps of your life on the left
            String s = String.format(Locale.ROOT, "%,d", v);
            g2.drawString(s, PAD_LEFT - 6 - fm.stringWidth(s), down(v, scale) + fm.getAscent() / 2 - 1);
        }
        // above the line: the damage you dealt, the biggest slice just below the top
        long dealtScale = Math.max(1, Math.round(dealtPeak * HEADROOM));
        if (dealtPeak > 0) {
            String s = String.format(Locale.ROOT, "%,d", dealtPeak);
            g2.setColor(DEALT);
            g2.drawString(s, PAD_LEFT - 6 - fm.stringWidth(s), up(dealtPeak, dealtScale) + fm.getAscent() / 2 - 1);
        }
        int threshold = threshold(visit);
        int lastIcon = -10_000;
        for (int i = 0; i < Math.max(sl.count(), dealt.length); i++) {   // one bar up and one down per slice
            int x0 = x((long) i * windowMs), x1 = x((long) (i + 1) * windowMs);
            int bw = Math.max(1, x1 - x0 - (x1 - x0 >= 4 ? 1 : 0));
            if (i < dealt.length && dealt[i] > 0) {
                int y = up(dealt[i], dealtScale);
                g2.setColor(DEALT);
                g2.fillRect(x0, y, bw, Math.max(1, mid - y));
            }
            if (i >= sl.count() || sl.damage()[i] == 0) continue;
            int y = down(sl.damage()[i], scale);
            boolean big = sl.damage()[i] >= threshold;
            g2.setColor(big ? BIG : BAR);
            g2.fillRect(x0, mid + 1, bw, Math.max(1, y - mid - 1));
            if (big && x0 - lastIcon >= ICON) {   // the enemy behind a dangerous slice, below its bar
                Icon icon = enemyIcon(sl.enemy()[i]);
                if (icon != null) {
                    icon.paintIcon(this, g2, x0 + bw / 2 - ICON / 2, Math.min(base - ICON, y + 2));
                    lastIcon = x0;
                }
            }
        }
        g2.setColor(Palette.MUTED);   // the line between dealt and taken
        g2.drawLine(PAD_LEFT, mid, w - PAD_RIGHT, mid);
        g2.dispose();
    }

    /** The line between damage dealt (above) and taken (below). */
    private int mid() {
        return PAD_TOP + (getHeight() - PAD_BOTTOM - PAD_TOP) * 2 / 5;
    }

    private int up(long dmg, long scale) {
        return mid() - (int) Math.round(Math.min(1.0, (double) dmg / scale) * (mid() - PAD_TOP));
    }

    private int down(long dmg, long scale) {
        int bottom = getHeight() - PAD_BOTTOM;
        return mid() + (int) Math.round(Math.min(1.0, (double) dmg / scale) * (bottom - mid()));
    }

    /** The damage you dealt, added up per slice of windowMs (from its per-second record). */
    static long[] dealtSlices(Visit v, int windowMs) {
        if (v.dealt == null || v.dealt.length == 0) return new long[0];
        int per = Math.max(1, windowMs / 1000);
        long[] out = new long[(v.dealt.length + per - 1) / per];
        for (int s = 0; s < v.dealt.length; s++) out[s / per] += v.dealt[s];
        return out;
    }

    private Icon enemyIcon(int type) {
        if (type <= 0) return null;
        return icons.computeIfAbsent(type, t -> CharactersView.sprite(t, ICON));
    }

    /** The slice under the mouse: its time, total damage (and share of your life), hits and the main enemy. */
    @Override
    public String getToolTipText(MouseEvent e) {
        if (e.getX() < PAD_LEFT) return null;
        Slices sl = slices(visit, windowMs);
        long[] dealt = dealtSlices(visit, windowMs);
        int i = (int) (((long) (e.getX() - PAD_LEFT) * span(visit)) / Math.max(1, getWidth() - PAD_LEFT - PAD_RIGHT) / windowMs);
        String when = DpsPanel.duration((long) i * windowMs) + " \u2013 " + DpsPanel.duration((long) (i + 1) * windowMs);
        if (e.getY() < mid()) {   // above the line: what you dealt
            if (i < 0 || i >= dealt.length || dealt[i] == 0) return null;
            return when + ":  " + String.format(Locale.ROOT, "%,d", dealt[i]) + " damage dealt ("
                    + String.format(Locale.ROOT, "%,d", dealt[i] * 1000 / windowMs) + " per second)";
        }
        if (i < 0 || i >= sl.count() || sl.damage()[i] == 0) return null;
        long dmg = sl.damage()[i];
        String share = visit.maxHp > 0 ? " (" + Math.round(100.0 * dmg / visit.maxHp) + "% of your life)" : "";
        int type = sl.enemy()[i];
        String who = type > 0 ? assets.IdToAsset.objectName(type) : "the ground or area effects";
        return "<html>" + when + ":  <b>" + String.format(Locale.ROOT, "%,d", dmg) + " damage</b>" + share + " in " + sl.hits()[i]
                + (sl.hits()[i] == 1 ? " hit" : " hits") + "<br>mostly from " + who + " (" + sl.enemyDamage()[i] + ")</html>";
    }
}
