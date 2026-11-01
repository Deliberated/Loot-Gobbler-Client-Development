package lootgobbler.ui;

import util.PropertiesManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * Main area on the left, a side column on the right, and a draggable gap between them: drag it to make the side
 * column wider or narrower. The width is saved per view (settings key "sideWidth.<name>"), and the column keeps
 * that width when its content changes, so nothing jumps around. With setSnap, the divider glides and settles only
 * where the grid on the left shows whole columns.
 */
public class SideSplit extends JPanel {
    private static final int GAP = 8;
    private int minMain = 200;

    /**
     * Snaps a width of the measured component inside the main area to one that holds whole grid columns.
     */
    public interface Snap {
        int snap(int width, boolean nearest);   // nearest = round to the closest column count, else round down
    }

    private final JComponent main, side;
    private final Divider divider = new Divider();
    private final String key;
    private final int minSide;
    private int sideWidth;
    private JComponent snapInner;   // e.g. the grid's viewport; null = no snapping
    private Snap snap;
    private double shownMain = -1;  // main width on screen; glides towards the snapped target while dragging
    private final Timer glide = new Timer(8, e -> {
        int target = targetMain(getWidth());
        shownMain += (target - shownMain) * 0.35;
        if (Math.abs(target - shownMain) < 0.5) {
            shownMain = target;
            ((Timer) e.getSource()).stop();
        }
        relayout();
    });

    /**
     * Lays out this split and everything inside both columns now (moving a column alone would leave its
     * contents at the old size), then repaints.
     */
    private void relayout() {
        invalidate();
        validate();
        repaint();
    }

    public SideSplit(JComponent main, JComponent side, String name, int defaultWidth, int minWidth) {
        this.main = main;
        this.side = side;
        this.key = "sideWidth." + name;
        this.minSide = minWidth;
        this.sideWidth = savedWidth(defaultWidth);
        setOpaque(false);
        setLayout(null);
        add(main);
        add(divider);
        add(side);
    }

    private int savedWidth(int fallback) {
        try {
            return Integer.parseInt(PropertiesManager.getProperty(key));
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * Side width that fits the current size: at least minSide, leaving the main area at least minMain.
     */
    private int effectiveSide() {
        int max = Math.max(minSide, getWidth() - GAP - minMain);
        return Math.max(minSide, Math.min(sideWidth, max));
    }

    /**
     * Makes the divider settle only where the grid inside the main area shows whole columns.
     *
     * @param inner the component whose width decides the columns (its offset to the main area is measured)
     */
    public void setSnap(JComponent inner, Snap snap) {
        this.snapInner = inner;
        this.snap = snap;
        revalidate();
    }

    /**
     * Narrowest the main area may get (e.g. so its header with search box and filters still fits).
     */
    public SideSplit minMain(int width) {
        this.minMain = width;
        revalidate();
        return this;
    }

    /**
     * Main-area width to lay out: from the saved side width, snapped down to whole columns (the side column takes
     * the leftover pixels).
     */
    private int targetMain(int total) {
        int mainW = Math.max(0, total - effectiveSide() - GAP);
        return snapMain(mainW, false);
    }

    private int snapMain(int mainW, boolean nearest) {
        if (snap == null || snapInner == null || snapInner.getWidth() <= 0 || main.getWidth() <= 0) return mainW;
        int overhead = main.getWidth() - snapInner.getWidth();   // borders, scrollbar, padding around the grid
        int snapped = overhead + snap.snap(mainW - overhead, nearest);
        int max = getWidth() - GAP - minSide;
        while (snapped > max && snapped > overhead) snapped = overhead + snap.snap(snapped - overhead - 1, false);
        return Math.max(0, snapped);
    }

    @Override
    public void doLayout() {
        int w = getWidth(), h = getHeight();
        int target = targetMain(w);
        if (!glide.isRunning() || shownMain < 0) shownMain = target;
        int mainW = (int) Math.round(shownMain);
        main.setBounds(0, 0, mainW, h);
        divider.setBounds(mainW, 0, GAP, h);
        side.setBounds(mainW + GAP, 0, Math.max(0, w - mainW - GAP), h);
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension m = main.getPreferredSize(), s = side.getPreferredSize();
        return new Dimension(m.width + GAP + sideWidth, Math.max(m.height, s.height));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(minMain + GAP + minSide, 0);
    }

    /**
     * The gap between the columns: shows a resize cursor and a thin accent line while hovered or dragged.
     */
    private class Divider extends JComponent {
        private boolean active;
        private int grabOffset;

        Divider() {
            setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
            setToolTipText("Drag to resize the column");
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    active = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if ((e.getModifiersEx() & MouseEvent.BUTTON1_DOWN_MASK) == 0) {
                        active = false;
                        repaint();
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    grabOffset = e.getX();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    // new divider x within the split panel -> side width
                    int x = SwingUtilities.convertPoint(Divider.this, e.getX() - grabOffset, 0, SideSplit.this).x;
                    int total = SideSplit.this.getWidth();
                    int mainW = snapMain(Math.max(0, x), true);   // nearest whole-column position
                    sideWidth = total - mainW - GAP;
                    sideWidth = effectiveSide();
                    if (!glide.isRunning()) glide.start();   // glide there instead of jumping
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    PropertiesManager.setProperties(key, String.valueOf(sideWidth));
                    if (!contains(e.getPoint())) {
                        active = false;
                        repaint();
                    }
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (!active) return;
            Color accent = UIManager.getColor("LootGobbler.gold");
            g.setColor(accent != null ? accent : Palette.GOLD);
            g.fillRect(getWidth() / 2 - 1, 0, 2, getHeight());
        }
    }
}
