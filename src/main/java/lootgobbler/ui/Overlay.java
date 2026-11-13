package lootgobbler.ui;

import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A small window of its own above all other windows (the game included, unless it runs in exclusive fullscreen),
 * slightly see-through, that never takes the focus from the game. There are several, each switched on and off on its
 * own (Battles > Overlay): the dungeon you are in, your mana, the damage of the boss fight. Drag one anywhere; where
 * it was, whether it is on and how see-through it is are kept for each. When it has nothing to show it says so.
 */
public final class Overlay {
    static final int MIN_OPACITY = 30, DEFAULT_OPACITY = 85;   // percent

    /** The dungeon you are in: portal, rank, amulet and in Moonlight Village the flames (OverlayBadge). */
    public static final Overlay DUNGEON = new Overlay("dungeon", "Dungeon",
            "The dungeon you are in: its portal, your rank once you hit the boss, the amulet while it recharges"
                    + " from a save, and in Moonlight Village the flames of the run and of the current lantern phase.",
            "Not in a dungeon", () -> new OverlayBadge(false));
    /** Your mana as a bar. */
    public static final Overlay MANA = new Overlay("mana", "Mana",
            "Your mana as a bar, with the points you have and your maximum.",
            "No character", () -> new ManaBar(false));
    /** The boss fight's damage: you, the player above and the one below. */
    public static final Overlay DPS = new Overlay("dps", "DPS",
            "The boss you are fighting: your place, damage and share as under DPS, with the player above you and the"
                    + " one below.", "No boss fight", () -> new DpsOverlay(false));
    private static final List<Overlay> ALL = List.of(DUNGEON, MANA, DPS);
    private static final List<Runnable> anyListeners = new ArrayList<>();

    final String id, title, description, idleText;
    private final Supplier<JComponent> content;
    private final List<Runnable> listeners = new ArrayList<>();
    private JWindow window;

    private Overlay(String id, String title, String description, String idleText, Supplier<JComponent> content) {
        this.id = id;
        this.title = title;
        this.description = description;
        this.idleText = idleText;
        this.content = content;
    }

    public static List<Overlay> all() {
        return ALL;
    }

    /** Shows those that were on when the app was closed. */
    public static void restoreAll() {
        for (Overlay o : ALL) if ("true".equals(PropertiesManager.getProperty(o.key("On")))) o.setOn(true);
    }

    /** Whether any overlay is on. */
    public static boolean anyOn() {
        return ALL.stream().anyMatch(Overlay::isOn);
    }

    /**
     * The status bar's switch: hides all that are on (and remembers which), or shows those again (the dungeon one
     * if none were remembered).
     */
    public static void toggleAll() {
        if (anyOn()) {
            StringBuilder ids = new StringBuilder();
            for (Overlay o : ALL) {
                if (!o.isOn()) continue;
                ids.append(ids.isEmpty() ? "" : ",").append(o.id);
                o.setOn(false);
            }
            PropertiesManager.setProperties("overlayLast", ids.toString());
        } else {
            String last = PropertiesManager.getProperty("overlayLast");
            boolean any = false;
            for (Overlay o : ALL) {
                if (last != null && ("," + last + ",").contains("," + o.id + ",")) {
                    o.setOn(true);
                    any = true;
                }
            }
            if (!any) DUNGEON.setOn(true);
        }
    }

    /** Called when any overlay is switched on or off. */
    public static void onAnyChange(Runnable r) {
        anyListeners.add(r);
    }

    /** Its setting's name; the dungeon overlay keeps the names it had when it was the only one. */
    private String key(String what) {
        return this == DUNGEON ? "overlay" + what : "overlay." + id + "." + what;
    }

    public boolean isOn() {
        return window != null && window.isVisible();
    }

    public void setOn(boolean on) {
        if (on == isOn()) return;
        PropertiesManager.setProperties(key("On"), String.valueOf(on));
        if (on) {
            if (window == null) window = build();
            window.pack();
            window.setLocation(savedLocation(window.getSize()));
            window.setVisible(true);
        } else {
            window.setVisible(false);
        }
        listeners.forEach(Runnable::run);
        anyListeners.forEach(Runnable::run);
    }

    /** Called when it is switched on or off (e.g. to update a switch). */
    public void onChange(Runnable r) {
        listeners.add(r);
    }

    /** How opaque it is, in percent (MIN_OPACITY .. 100). */
    public int opacity() {
        try {
            String v = PropertiesManager.getProperty(key("Opacity"));
            return v == null ? DEFAULT_OPACITY : Math.max(MIN_OPACITY, Math.min(100, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_OPACITY;
        }
    }

    public void setOpacity(int percent) {
        percent = Math.max(MIN_OPACITY, Math.min(100, percent));
        PropertiesManager.setProperties(key("Opacity"), String.valueOf(percent));
        if (window != null) applyOpacity(window);
    }

    private void applyOpacity(Window w) {
        GraphicsDevice screen = w.getGraphicsConfiguration().getDevice();
        if (screen.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.TRANSLUCENT)) {
            w.setOpacity(opacity() / 100f);
        }
    }

    private JWindow build() {
        JWindow w = new JWindow();
        w.setAlwaysOnTop(true);
        w.setFocusableWindowState(false);   // clicking or dragging it leaves the focus with the game
        w.setType(Window.Type.UTILITY);     // no taskbar button
        GraphicsDevice screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        boolean clear = screen.isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        if (clear) w.setBackground(new Color(0, 0, 0, 0));   // only the boxes show, with their round corners

        JComponent live = content.get();   // keeps itself up to date, and hidden while it has nothing to show
        JLabel idle = new JLabel(idleText);
        idle.setForeground(new Color(0xF2ECDC));
        JPanel idleBox = new RoundBox(new Color(0x2A2620), new Color(0x5A544A));
        idleBox.setBorder(new EmptyBorder(9, 16, 9, 16));
        idleBox.add(idle);

        JPanel panel = new JPanel(new BorderLayout()) {
            /** The content revalidates its parent when it shows, hides or changes size: the window follows. */
            @Override
            public void revalidate() {
                super.revalidate();
                SwingUtilities.invokeLater(() -> {
                    idleBox.setVisible(!live.isVisible());
                    if (w.isVisible() && !w.getSize().equals(w.getPreferredSize())) w.pack();
                });
            }
        };
        panel.setOpaque(!clear);
        panel.add(live, BorderLayout.NORTH);
        panel.add(idleBox, BorderLayout.SOUTH);
        idleBox.setVisible(!live.isVisible());
        w.setContentPane(panel);
        applyOpacity(w);
        draggable(w, panel);
        return w;
    }

    /** Dragging anywhere on it moves it; where it is let go is kept. */
    private void draggable(Window w, Component c) {
        Point[] grab = new Point[1];
        MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (!SwingUtilities.isLeftMouseButton(e)) return;
                Point at = e.getLocationOnScreen();
                grab[0] = new Point(at.x - w.getX(), at.y - w.getY());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (grab[0] == null) return;
                Point at = e.getLocationOnScreen();
                w.setLocation(at.x - grab[0].x, at.y - grab[0].y);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (grab[0] == null) return;
                grab[0] = null;
                PropertiesManager.setProperties(key("X"), String.valueOf(w.getX()));
                PropertiesManager.setProperties(key("Y"), String.valueOf(w.getY()));
            }
        };
        listen(c, drag);
        c.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
    }

    /** On every part: labels with their own listeners (tooltips) would take the mouse from the panel below. */
    private static void listen(Component c, MouseAdapter l) {
        c.addMouseListener(l);
        c.addMouseMotionListener(l);
        if (c instanceof JComponent j && j.getCursor().getType() == Cursor.HAND_CURSOR) j.setCursor(null);
        if (c instanceof Container k) for (Component sub : k.getComponents()) listen(sub, l);
    }

    /** Where it was last, if still on a screen; else near the top right of the main screen, one below the other. */
    private Point savedLocation(Dimension size) {
        try {
            String x = PropertiesManager.getProperty(key("X")), y = PropertiesManager.getProperty(key("Y"));
            if (x != null && y != null) {
                Point p = new Point(Integer.parseInt(x.trim()), Integer.parseInt(y.trim()));
                Rectangle grip = new Rectangle(p.x, p.y, Math.max(40, size.width / 2), Math.max(20, size.height / 2));
                for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                    if (d.getDefaultConfiguration().getBounds().intersects(grip)) return p;
                }
            }
        } catch (NumberFormatException ignored) {
            // a broken entry: place it anew
        }
        Rectangle main = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        return new Point(main.x + main.width - size.width - 40, main.y + 80 + 70 * ALL.indexOf(this));
    }

    /** A dark rounded box, as the dungeon box is drawn. */
    static final class RoundBox extends JPanel {
        private final Color fill, edge;

        RoundBox(Color fill, Color edge) {
            super(new BorderLayout());
            this.fill = fill;
            this.edge = edge;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.setColor(edge);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.dispose();
        }
    }
}
