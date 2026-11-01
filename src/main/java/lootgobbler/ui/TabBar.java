package lootgobbler.ui;

import com.formdev.flatlaf.util.UIScale;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Main navigation: a single row of tabs that scrolls horizontally when it does not fit, and whose tabs can be
 * reordered by dragging. While dragging, the grabbed tab follows the mouse and the others glide out of the way.
 * The order is saved in the settings file.
 */
public class TabBar extends JPanel {
    private static final String KEY_ORDER = "tabOrder";
    private static final int DRAG_THRESHOLD = 5;
    private static final double EASING = 0.28;   // share of the remaining distance moved per frame

    private final Strip strip = new Strip();
    private final JScrollPane stripScroll;
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final List<Tab> order = new ArrayList<>();   // visual order
    private final Timer animation = new Timer(15, e -> step());
    private Tab selected;
    private Tab dragged;
    private int dragX;

    public TabBar() {
        super(new BorderLayout());
        stripScroll = new JScrollPane(strip, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBorder(new MatteBorder(0, 0, 1, 0, Section.color("Component.borderColor", Color.GRAY)));
                getViewport().setBackground(Section.color("TabbedPane.background", Color.LIGHT_GRAY));
            }
        };
        stripScroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 8));
        stripScroll.getHorizontalScrollBar().setUnitIncrement(30);
        // the mouse wheel scrolls the tabs sideways
        stripScroll.addMouseWheelListener(e -> {
            JScrollBar bar = stripScroll.getHorizontalScrollBar();
            bar.setValue(bar.getValue() + e.getWheelRotation() * 40);
        });
        content.setOpaque(false);
        add(stripScroll, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
    }

    /**
     * Same signature as JTabbedPane.addTab, so it can replace it.
     */
    public void addTab(String title, Component component) {
        Tab tab = new Tab(title);
        order.add(tab);
        strip.add(tab);
        content.add(component, title);
        if (selected == null) select(tab);
        snapAll();
    }

    /**
     * Applies the saved tab order (or defaultOrder if none is saved yet) and opens the leftmost tab. Call after all
     * tabs are added.
     */
    public void restoreOrder(String... defaultOrder) {
        String saved = PropertiesManager.getProperty(KEY_ORDER);
        List<String> names = saved != null && !saved.isBlank() ? withNewTabs(saved.split("\\|"), defaultOrder)
                : Arrays.asList(defaultOrder);
        if (!names.isEmpty()) {
            order.sort((a, b) -> Integer.compare(rank(names, a.title), rank(names, b.title)));
            snapAll();
        }
        if (!order.isEmpty()) select(order.get(0));   // the app opens on whatever tab you dragged to the far left
    }

    /**
     * The saved order plus the tabs that are new since it was saved, each placed in front of the saved tab that
     * follows it in the default order (so a new first tab really comes first).
     */
    private static List<String> withNewTabs(String[] saved, String[] defaultOrder) {
        List<String> names = new ArrayList<>(Arrays.asList(saved));
        for (int i = defaultOrder.length - 1; i >= 0; i--) {
            if (names.contains(defaultOrder[i])) continue;
            int at = names.size();
            for (int j = i + 1; j < defaultOrder.length; j++) {
                int k = names.indexOf(defaultOrder[j]);
                if (k >= 0) {
                    at = k;
                    break;
                }
            }
            names.add(at, defaultOrder[i]);
        }
        return names;
    }

    /**
     * Switches to the tab with this title, if there is one.
     */
    public void show(String title) {
        for (Tab t : order) {
            if (t.title.equals(title)) {
                select(t);
                return;
            }
        }
    }

    private static int rank(List<String> order, String title) {
        int i = order.indexOf(title);
        return i < 0 ? Integer.MAX_VALUE : i;   // tabs that are new since the order was saved go last
    }

    private void saveOrder() {
        List<String> titles = new ArrayList<>();
        for (Tab t : order) titles.add(t.title);
        PropertiesManager.setProperties(KEY_ORDER, String.join("|", titles));
    }

    private void select(Tab tab) {
        selected = tab;
        cards.show(content, tab.title);
        strip.repaint();
        tab.scrollRectToVisible(new Rectangle(0, 0, tab.getWidth(), tab.getHeight()));
    }

    /**
     * Target x of each tab in the current order (the dragged tab keeps its slot reserved).
     */
    private void computeTargets() {
        int x = 0;
        for (Tab t : order) {
            t.targetX = x;
            x += t.getPreferredSize().width;
        }
    }

    private void snapAll() {
        computeTargets();
        for (Tab t : order) t.x = t.targetX;
        strip.revalidate();
        strip.repaint();
    }

    /**
     * Dragged tab follows the mouse; when its center passes a neighbour's center, the order changes and the
     * neighbours start gliding to their new slots.
     */
    private void dragTo(int stripX, int grabOffset) {
        int width = dragged.getPreferredSize().width;
        dragX = Math.max(0, Math.min(stripX - grabOffset, strip.getPreferredSize().width - width));
        int center = dragX + width / 2;
        int index = 0;
        for (Tab t : order) {
            if (t == dragged) continue;
            if (t.targetX + t.getPreferredSize().width / 2 < center) index++;
        }
        if (index != order.indexOf(dragged)) {
            order.remove(dragged);
            order.add(index, dragged);
            computeTargets();
        }
        strip.scrollRectToVisible(new Rectangle(stripX - 30, 0, 60, 1));
        animation.start();
        step();
    }

    /**
     * One animation frame: moves every tab a bit closer to its target.
     */
    private void step() {
        boolean moving = false;
        for (Tab t : order) {
            if (t == dragged) {
                t.x = dragX;
                continue;
            }
            double d = t.targetX - t.x;
            if (Math.abs(d) < 0.5) {
                t.x = t.targetX;
            } else {
                t.x += d * EASING;
                moving = true;
            }
        }
        strip.doLayout();
        strip.repaint();
        if (!moving && dragged == null) animation.stop();
    }

    /**
     * Lays the tabs out at their (animated) positions instead of using a layout manager.
     */
    private class Strip extends JPanel {
        Strip() {
            super(null);
        }

        @Override
        public Dimension getPreferredSize() {
            int w = 0, h = 0;
            for (Tab t : order) {
                Dimension d = t.getPreferredSize();
                w += d.width;
                h = Math.max(h, d.height);
            }
            return new Dimension(w, h);
        }

        @Override
        public void doLayout() {
            if (!animation.isRunning() && dragged == null) {   // e.g. after a theme change: re-measure and snap
                computeTargets();
                for (Tab t : order) t.x = t.targetX;
            }
            int h = getHeight();
            for (Tab t : order) t.setBounds((int) Math.round(t.x), 0, t.getPreferredSize().width, h);
        }
    }

    private class Tab extends JComponent {
        final String title;
        double x;
        int targetX;
        private boolean hover;
        private Point pressAt;
        private boolean dragging;

        Tab(String title) {
            this.title = title;
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    pressAt = e.getPoint();
                    dragging = false;
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (pressAt == null) return;
                    if (!dragging && pressAt.distance(e.getPoint()) < DRAG_THRESHOLD) return;
                    if (!dragging) {
                        dragging = true;
                        dragged = Tab.this;
                        strip.setComponentZOrder(Tab.this, 0);   // draw above the tabs it passes
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    }
                    dragTo(SwingUtilities.convertPoint(Tab.this, e.getPoint(), strip).x, pressAt.x);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (dragging) {
                        dragged = null;   // glides into its slot
                        animation.start();
                        saveOrder();
                        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                    } else if (pressAt != null) {
                        select(Tab.this);
                    }
                    pressAt = null;
                    dragging = false;
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private Font tabFont() {
            Font f = UIManager.getFont("TabbedPane.font");
            return f != null ? f : getFont();
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(tabFont());
            // tabHeight is unscaled while fonts follow the Windows display scaling; always leave room for the text
            int h = Math.max(UIScale.scale(Math.max(UIManager.getInt("TabbedPane.tabHeight"), 26)), fm.getHeight() + UIScale.scale(12));
            return new Dimension(fm.stringWidth(title.toUpperCase(Locale.ROOT)) + UIScale.scale(26), h);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean on = this == selected;
            Color bg = on ? Section.color("TabbedPane.selectedBackground", Color.WHITE)
                    : hover || this == dragged ? Section.color("TabbedPane.hoverColor", Color.LIGHT_GRAY)
                    : Section.color("TabbedPane.background", Color.LIGHT_GRAY);
            g2.setColor(bg);
            g2.fillRect(0, 0, getWidth(), getHeight());
            int underline = UIScale.scale(3);
            if (on) {
                g2.setColor(Section.color("TabbedPane.underlineColor", Palette.GOLD));
                g2.fillRect(0, getHeight() - underline, getWidth(), underline);
            }
            g2.setColor(Section.color("TabbedPane.tabSeparatorColor", Color.BLACK));
            g2.fillRect(getWidth() - 1, 0, 1, getHeight());
            if (this == dragged) g2.fillRect(0, 0, 1, getHeight());

            g2.setFont(tabFont());
            g2.setColor(on ? Section.color("TabbedPane.selectedForeground", Color.BLACK)
                    : Section.color("TabbedPane.foreground", Color.DARK_GRAY));
            FontMetrics fm = g2.getFontMetrics();
            String text = title.toUpperCase(Locale.ROOT);
            g2.drawString(text, (getWidth() - 1 - fm.stringWidth(text)) / 2,
                    (getHeight() - underline - fm.getHeight()) / 2 + fm.getAscent());   // centered above the underline
            g2.dispose();
        }
    }
}
