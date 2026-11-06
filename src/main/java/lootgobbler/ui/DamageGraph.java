package lootgobbler.ui;

import lootgobbler.DungeonVisits.Visit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Damage in a dungeon per time slice (2 seconds), mirrored around one line: above it the damage you dealt (gold, the
 * biggest slice just below the top), below it the damage you took (the bottom being your whole life, marked in thirds
 * on the left). Slices taking 30% of your life or 300 damage are orange, with the icon of the enemy that dealt most of
 * it on the floor of the graph. Every slice is SLICE_PX wide, whatever the dungeon and the window size: a wider window
 * shows more of them. Scrolled with the bar under it (see withScrollbar) and starting at the latest end; dangerous slices outside the window show as a
 * teardrop at that edge with the nearest one's enemy (click: scroll to it). Reads the visit on every paint, so the
 * dungeon in progress follows along on repaint().
 */
final class DamageGraph extends JComponent {
    static final double BIG_SHARE = 0.30;   // of your maximum life, within one slice
    static final int BIG_DAMAGE = 300;      // or this much, within one slice
    static final int[] WINDOWS_MS = {2000};   // the graphs shown: slices of 2 seconds
    private static final double HEADROOM = 1.08;   // without a known life: the biggest slice just below the top
    private static final int PAD_LEFT = 44, PAD_RIGHT = 10, PAD_TOP = 28, PAD_BOTTOM = 20, ICON = 28;
    private static final Color BAR = new Color(0xE5636E), BIG = new Color(0xFF8A2A), DEALT = new Color(0xE8B83A),
            AMULET = new Color(0x3A9BFF);
    private static final int BARS = 60;    // slices shown until the graph knows its width
    private static final int SLICE_PX = 14;   // one slice's width (the bar and a 1 px gap)
    private static final int BUBBLE = 13, BUBBLE_ICON = 18;   // the off-screen teardrops: radius, enemy icon
    private final Visit visit;
    private final int windowMs;
    private long viewMs;   // the window shown: as many slices as fit the width
    private final Map<Integer, Icon> icons = new HashMap<>(), smallIcons = new HashMap<>();
    private final JScrollBar bar = new JScrollBar(JScrollBar.HORIZONTAL);
    private long viewStart;                           // ms after entering, at the left edge
    private boolean pendingUpdate, wasAdjusting;
    private Runnable dragEnd = () -> {
    };
    private Rectangle leftBubble, rightBubble;        // as last painted
    private int leftTarget = -1, rightTarget = -1, leftCount, rightCount;   // nearest dangerous slice off each edge

    /** The hits of a visit added up per slice: total damage, number of hits and the enemy that dealt the most. */
    record Slices(long[] damage, int[] hits, int[] enemy, int[] enemyDamage) {
        int count() {
            return damage.length;
        }
    }

    DamageGraph(Visit visit, int windowMs) {
        this.visit = visit;
        this.windowMs = windowMs;
        this.viewMs = (long) BARS * windowMs;
        setPreferredSize(new Dimension(300, 230));
        setToolTipText("");   // per slice, see getToolTipText(MouseEvent)
        // the numbers pop up at once over the graph (the usual tooltip delay elsewhere)
        addMouseListener(new java.awt.event.MouseAdapter() {
            private int initial = -1, reshow;

            @Override
            public void mouseEntered(MouseEvent e) {
                initial = ToolTipManager.sharedInstance().getInitialDelay();
                reshow = ToolTipManager.sharedInstance().getReshowDelay();
                ToolTipManager.sharedInstance().setInitialDelay(0);
                ToolTipManager.sharedInstance().setReshowDelay(0);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (initial < 0) return;
                ToolTipManager.sharedInstance().setInitialDelay(initial);
                ToolTipManager.sharedInstance().setReshowDelay(reshow);
            }
        });
        bar.setUnitIncrement(windowMs / 1000);
        bar.setBlockIncrement(60);
        bar.addAdjustmentListener(e -> {
            viewStart = bar.getValue() * 1000L;
            repaint();
            if (wasAdjusting && !e.getValueIsAdjusting()) {   // let go: catch up with what came in during the drag
                if (pendingUpdate) update(false);
                dragEnd.run();
            }
            wasAdjusting = e.getValueIsAdjusting();
        });
        Clicks.onClick(this, e -> {   // a teardrop: scroll so its slice is in the middle
            int target = leftBubble != null && leftBubble.contains(e.getPoint()) ? leftTarget
                    : rightBubble != null && rightBubble.contains(e.getPoint()) ? rightTarget : -1;
            if (target >= 0) bar.setValue((int) Math.max(0, ((long) target * windowMs - viewMs / 2) / 1000));
        });
        addComponentListener(new java.awt.event.ComponentAdapter() {   // resized: more or fewer slices, same width
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                long fit = (long) Math.max(1, (getWidth() - PAD_LEFT - PAD_RIGHT + SLICE_PX - 1) / SLICE_PX) * windowMs;
                if (fit != viewMs) {
                    viewMs = fit;
                    update(false);
                }
            }
        });
        update(true);
    }

    /** The graph with its scrollbar under it (shown when the dungeon is longer than the window). */
    JComponent withScrollbar() {
        JPanel p = new JPanel(new BorderLayout(0, 2));
        p.setOpaque(false);
        p.add(this, BorderLayout.CENTER);
        p.add(bar, BorderLayout.SOUTH);
        return p;
    }

    /** Whether the scrollbar is being dragged right now. */
    boolean dragging() {
        return bar.getValueIsAdjusting();
    }

    /** Runs when a drag of the scrollbar ends (e.g. to catch up with what changed meanwhile). */
    void onDragEnd(Runnable r) {
        dragEnd = r;
    }

    /** Where the window is (seconds after entering) and whether it is at the latest end, to keep across rebuilds. */
    int scrollValue() {
        return bar.getValue();
    }

    boolean atLatestEnd() {
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 1;
    }

    /** Back to a position kept from an earlier graph of the same visit. */
    void restore(int value, boolean atEnd) {
        if (atEnd) {
            update(true);
        } else {
            bar.setValue(Math.max(bar.getMinimum(), Math.min(value, bar.getMaximum() - bar.getVisibleAmount())));
            viewStart = bar.getValue() * 1000L;
            repaint();
        }
    }

    /**
     * New data (the dungeon in progress): the scroll range follows the visit's length; a view at the latest end
     * stays there. With toEnd, jump to the latest end.
     */
    void update(boolean toEnd) {
        if (dragging()) {   // never reset the bar under the mouse; the new range comes when it is let go
            pendingUpdate = true;
            repaint();
            return;
        }
        pendingUpdate = false;
        int total = (int) Math.ceil(span(visit) / 1000.0), extent = (int) (viewMs / 1000);
        boolean atEnd = toEnd || bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 1;
        int max = Math.max(total, extent);
        bar.setValues(atEnd ? max - extent : Math.min(bar.getValue(), max - extent), extent, 0, max);
        bar.setVisible(total > extent);
        viewStart = bar.getValue() * 1000L;
        repaint();
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
        return PAD_LEFT + (int) Math.round((double) (t - viewStart) * SLICE_PX / windowMs);
    }

    /** The slice at a screen x, or -1 left of the plot. */
    private int sliceAt(int x) {
        if (x < PAD_LEFT) return -1;
        long t = viewStart + (long) (x - PAD_LEFT) * windowMs / SLICE_PX;
        return (int) (t / windowMs);
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
        // time along the bottom: every minute within the window
        for (long t = (viewStart + 59_999) / 60_000 * 60_000; t <= viewStart + viewMs; t += 60_000) {
            String s = DpsPanel.duration(t);
            int x = x(t) - fm.stringWidth(s) / 2;
            x = Math.max(PAD_LEFT, Math.min(w - PAD_RIGHT - fm.stringWidth(s), x));
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
        java.util.List<Object[]> icons = new java.util.ArrayList<>();   // {Icon, x, y}: painted last, on top
        Shape oldClip = g2.getClip();
        g2.clipRect(PAD_LEFT, 0, w - PAD_LEFT - PAD_RIGHT, h);   // only the window
        int first = (int) (viewStart / windowMs), last = (int) ((viewStart + viewMs) / windowMs);
        for (int i = Math.max(0, first); i <= Math.min(last, Math.max(sl.count(), dealt.length) - 1); i++) {   // a bar up and one down
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
                    icons.add(new Object[]{icon, x0 + bw / 2 - ICON / 2, base - ICON - 2});   // all on the floor
                    lastIcon = x0;
                }
            }
        }
        // where your Amulet of Restoration fired: a blue line on the taken side only (over the bars, so it is never
        // hidden), apart from the orange 30% / 300 slices
        if (visit.amuletSaves != null) {
            g2.setColor(AMULET);
            for (int t : visit.amuletSaves) g2.fillRect(x(t) - 1, mid + 1, 2, base - mid - 1);
        }
        for (Object[] i : icons) ((Icon) i[0]).paintIcon(this, g2, (int) i[1], (int) i[2]);
        g2.setClip(oldClip);
        g2.setColor(Palette.MUTED);   // the line between dealt and taken
        g2.drawLine(PAD_LEFT, mid, w - PAD_RIGHT, mid);
        bubbles(g2, sl, threshold, first, last, mid, base);
        g2.dispose();
    }

    /**
     * Dangerous slices outside the window: a teardrop at that edge pointing out, with the enemy of the nearest one
     * (and how many there are if more than one).
     */
    private void bubbles(Graphics2D g2, Slices sl, int threshold, int first, int last, int mid, int base) {
        leftTarget = rightTarget = -1;
        leftCount = rightCount = 0;
        for (int i = 0; i < sl.count(); i++) {
            if (sl.damage()[i] < threshold) continue;
            if (i < first) {
                leftTarget = i;   // the last one before the window is the nearest
                leftCount++;
            } else if (i > last) {
                if (rightTarget < 0) rightTarget = i;
                rightCount++;
            }
        }
        int cy = (mid + base) / 2;   // in the middle of the taken side (the enemy icons stand on the floor below)
        leftBubble = leftTarget < 0 ? null : teardrop(g2, PAD_LEFT + 4, cy, true, sl.enemy()[leftTarget], leftCount);
        rightBubble = rightTarget < 0 ? null : teardrop(g2, getWidth() - PAD_RIGHT - 4, cy, false, sl.enemy()[rightTarget], rightCount);
    }

    /** A teardrop with its tip at (tipX, cy), pointing left or right, holding an enemy's icon. Returns its bounds. */
    private Rectangle teardrop(Graphics2D g2, int tipX, int cy, boolean left, int enemy, int count) {
        int r = BUBBLE, cx = left ? tipX + (int) (r * 1.9) : tipX - (int) (r * 1.9);
        double a = Math.toRadians(40);   // where the sides meet the circle
        java.awt.geom.Path2D drop = new java.awt.geom.Path2D.Double();
        double dir = left ? -1 : 1;
        drop.moveTo(tipX, cy);
        drop.lineTo(cx + dir * r * Math.cos(a), cy - r * Math.sin(a));
        drop.append(new java.awt.geom.Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r,
                left ? 180 - 40 : 40, left ? -(360 - 80) : 360 - 80, java.awt.geom.Arc2D.OPEN), true);
        drop.closePath();
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fill(drop);
        g2.setStroke(new BasicStroke(2f));
        g2.setColor(new Color(0xC9C2B4));
        g2.draw(drop);
        g2.setStroke(new BasicStroke(1f));
        Icon icon = enemy <= 0 ? null : smallIcons.computeIfAbsent(enemy, t -> bigIcon(t, BUBBLE_ICON));
        if (icon == null) icon = smallIcons.computeIfAbsent(0, t -> warning(BUBBLE_ICON));
        if (icon != null) {
            Shape clip = g2.getClip();
            g2.clip(new java.awt.geom.Ellipse2D.Double(cx - r + 2, cy - r + 2, 2 * r - 4, 2 * r - 4));
            icon.paintIcon(this, g2, cx - icon.getIconWidth() / 2, cy - icon.getIconHeight() / 2);
            g2.setClip(clip);
        }
        if (count > 1) {
            String n = "\u00D7" + count;
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(new Color(0xC9C2B4));
            g2.drawString(n, cx - fm.stringWidth(n) / 2, cy + r + fm.getAscent() + 1);
        }
        int x0 = Math.min(tipX, cx - r), x1 = Math.max(tipX, cx + r);
        return new Rectangle(x0, cy - r, x1 - x0, 2 * r);
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

    /**
     * An enemy's sprite filling the icon: many enemies only use a corner of their sprite square, so the outlined
     * sprite is cut to its visible pixels and scaled up (nearest neighbour, crisp) to fit ICON.
     */
    private static Icon bigIcon(int type) {
        return bigIcon(type, ICON);
    }

    private static Icon bigIcon(int type, int size) {
        Icon own = croppedIcon(type, size);
        if (own != null) return own;
        // a helper object (spawn portal, beam, invisible boss part): show the enemy it belongs to
        for (int enemy : EnemySprites.candidates(type)) {
            Icon icon = croppedIcon(enemy, size);
            if (icon != null) return icon;
        }
        return null;
    }

    private static Icon croppedIcon(int type, int size) {
        Icon src = CharactersView.sprite(type, 64);
        if (src == null) return null;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(src.getIconWidth(), src.getIconHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        src.paintIcon(null, g, 0, 0);
        g.dispose();
        int x0 = img.getWidth(), y0 = img.getHeight(), x1 = -1, y1 = -1, opaque = 0;
        java.util.Set<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                if ((c >>> 24) < 30) continue;
                opaque++;
                colors.add(c & 0xFFFFFF);
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
        }
        if (x1 < 0) return null;
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        // a placeholder, not a picture: one colour, or a plain filled square in its outline (e.g. the Oryx Minister
        // Portal's white dot, the Sanctuary's black walls); scaled up it would only be a blank square
        if (colors.size() <= 1 || colors.size() == 2 && opaque * 10 >= w * h * 9) return null;
        double scale = (double) size / Math.max(w, h);
        int dw = Math.max(1, (int) Math.round(w * scale)), dh = Math.max(1, (int) Math.round(h * scale));
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img.getSubimage(x0, y0, w, h), (size - dw) / 2, size - dh, dw, dh, null);   // centered, on the bottom
        g.dispose();
        return new ImageIcon(out);
    }

    /** The enemy's sprite, or a warning badge for the ground, area effects and enemies with an invisible sprite. */
    private Icon enemyIcon(int type) {
        Icon icon = type <= 0 ? null : icons.computeIfAbsent(type, DamageGraph::bigIcon);
        return icon != null ? icon : icons.computeIfAbsent(0, t -> warning(ICON));
    }

    /** A small round warning badge ("!"), for a dangerous slice without a recognisable enemy. */
    private static Icon warning(int size) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int d = size - 6, o = 3;
        g.setColor(new Color(0, 0, 0, 170));
        g.fillOval(o, o, d, d);
        g.setColor(BIG);
        g.setStroke(new BasicStroke(2f));
        g.drawOval(o, o, d, d);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, size / 2)));
        FontMetrics fm = g.getFontMetrics();
        g.drawString("!", (size - fm.stringWidth("!")) / 2, (size + fm.getAscent()) / 2 - 2);
        g.dispose();
        return new ImageIcon(img);
    }

    private String bubbleTip(int target, int count, String side) {
        Slices sl = slices(visit, windowMs);
        int type = target >= 0 && target < sl.count() ? sl.enemy()[target] : 0;
        String who = type > 0 ? assets.IdToAsset.objectName(type) : "the ground or area effects";
        return "<html>" + (count == 1 ? "A dangerous moment" : count + " dangerous moments") + " to the " + side
                + "<br>nearest: " + DpsPanel.duration((long) target * windowMs) + ", " + who + "<br><i>click to scroll there</i></html>";
    }

    /** The slice under the mouse: its time, total damage (and share of your life), hits and the main enemy. */
    @Override
    public String getToolTipText(MouseEvent e) {
        if (leftBubble != null && leftBubble.contains(e.getPoint())) return bubbleTip(leftTarget, leftCount, "left");
        if (rightBubble != null && rightBubble.contains(e.getPoint())) return bubbleTip(rightTarget, rightCount, "right");
        if (e.getX() < PAD_LEFT) return null;
        Slices sl = slices(visit, windowMs);
        long[] dealt = dealtSlices(visit, windowMs);
        int i = sliceAt(e.getX());
        if (visit.amuletSaves != null && e.getY() > mid()) {
            for (int t : visit.amuletSaves) {
                if (Math.abs(x(t) - e.getX()) <= 3) return DpsPanel.duration(t) + ":  your Amulet of Restoration fired";
            }
        }
        // just the number: the damage dealt above the line, the damage taken below it
        boolean above = e.getY() < mid();
        long v = above ? (i >= 0 && i < dealt.length ? dealt[i] : 0) : (i >= 0 && i < sl.count() ? sl.damage()[i] : 0);
        return v == 0 ? null : String.format(Locale.ROOT, "%,d", v);
    }
}
