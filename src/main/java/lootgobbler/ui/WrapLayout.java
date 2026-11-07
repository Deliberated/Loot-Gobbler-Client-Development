package lootgobbler.ui;

import java.awt.*;

/**
 * Lays components left to right at their preferred size, top-aligned, starting a new row when the next one does
 * not fit the container's width. Unlike FlowLayout, its preferred height counts the rows it wraps into.
 */
final class WrapLayout implements LayoutManager {
    private final int hgap, vgap;

    WrapLayout(int hgap, int vgap) {
        this.hgap = hgap;
        this.vgap = vgap;
    }

    @Override
    public void addLayoutComponent(String name, Component comp) {
    }

    @Override
    public void removeLayoutComponent(Component comp) {
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        return arrange(parent, false);
    }

    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return arrange(parent, false);
    }

    @Override
    public void layoutContainer(Container parent) {
        arrange(parent, true);
    }

    /** Places the components (if place) and returns the size they take at the container's width. */
    private Dimension arrange(Container parent, boolean place) {
        synchronized (parent.getTreeLock()) {
            Insets in = parent.getInsets();
            // before the first layout the container has no width yet: its parent's, else one long row
            int width = parent.getWidth() > 0 ? parent.getWidth()
                    : parent.getParent() != null && parent.getParent().getWidth() > 0 ? parent.getParent().getWidth() : Integer.MAX_VALUE;
            int max = width - in.left - in.right;
            int x = 0, y = 0, rowH = 0, widest = 0;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) continue;
                Dimension d = c.getPreferredSize();
                if (x > 0 && x + d.width > max) {   // next row
                    y += rowH + vgap;
                    x = 0;
                    rowH = 0;
                }
                if (place) c.setBounds(in.left + x, in.top + y, d.width, d.height);
                x += d.width + hgap;
                widest = Math.max(widest, x - hgap);
                rowH = Math.max(rowH, d.height);
            }
            return new Dimension(widest + in.left + in.right, y + rowH + in.top + in.bottom);
        }
    }
}
