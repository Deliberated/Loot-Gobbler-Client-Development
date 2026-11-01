package lootgobbler.ui;

import assets.ImageBuffer;
import lootgobbler.CollectionStore;
import lootgobbler.ItemDb;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Icon grid of collectible items on a dark background: collected items are bright on a lighter cell,
 * missing ones are dimmed. The column count follows the available width.
 */
public class CollectionGrid extends JComponent implements Scrollable {
    public static final int CELL = 34, ICON = 28, PAD = 6;
    private static final Color BG = new Color(0x050505), OWNED = new Color(0x19191D);
    private static final Map<Integer, Image> ICONS = new HashMap<>();   // EDT only
    private static final Image NO_ICON = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private List<ItemDb.Item> items = List.of();
    private Map<Integer, CollectionStore.Entry> owned = Map.of();
    private int lastWidth = -1;
    private int hover = -1;   // index of the cell under the mouse
    private static final int PULSE_MS = 2000, CLICK_PULSE_MS = 1000, PULSE_GROW = 14;
    private static final float PULSE_STROKE = 5f;
    private static final Color PULSE_GOLD = new Color(0xF5C542);
    private int pulseIndex = -1;
    private long pulseStart;
    private int pulseMs = PULSE_MS;
    // ~120 fps; only the area around the box is repainted each frame, so it stays smooth on large grids
    private final Timer pulseTimer = new Timer(8, e -> {
        repaintPulse();
        if (System.nanoTime() - pulseStart > pulseMs * 1_000_000L) {
            ((Timer) e.getSource()).stop();
            pulseIndex = -1;
            repaintPulse();
        }
    });

    private void repaintPulse() {
        if (pulseIndex < 0) return;
        int cols = columns(), margin = PULSE_GROW + (int) PULSE_STROKE + 2;
        repaint(PAD + (pulseIndex % cols) * CELL - margin, PAD + (pulseIndex / cols) * CELL - margin,
                CELL + 2 * margin, CELL + 2 * margin);
    }

    /**
     * Scrolls to the item and plays a gold box around it that grows and fades out.
     *
     * @return False if the item is not in the grid (e.g. hidden by the current filter).
     */
    public boolean pulse(int itemId) {
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id == itemId) {
                index = i;
                break;
            }
        }
        if (index < 0) return false;
        int target = index;
        Runnable start = new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                if (getWidth() == 0 && tries++ < 10) {   // grid just became visible; wait for its layout
                    SwingUtilities.invokeLater(this);
                    return;
                }
                int cols = columns();
                int cellY = PAD + (target / cols) * CELL;
                if (getParent() instanceof JViewport vp) {   // scroll so the item sits in the vertical middle
                    int y = cellY + CELL / 2 - vp.getHeight() / 2;
                    y = Math.max(0, Math.min(y, getPreferredSize().height - vp.getHeight()));
                    vp.setViewPosition(new Point(0, y));
                } else {
                    scrollRectToVisible(new Rectangle(PAD + (target % cols) * CELL, cellY, CELL, CELL));
                }
                startPulse(target, PULSE_MS);
            }
        };
        SwingUtilities.invokeLater(start);
        return true;
    }

    private void startPulse(int index, int ms) {
        repaintPulse();   // clear a box still running elsewhere
        pulseIndex = index;
        pulseMs = ms;
        pulseStart = System.nanoTime();
        pulseTimer.restart();
    }

    // Hover light, like the Windows calendar: a soft glow under the mouse that also lights up nearby cell borders,
    // fading in when the mouse enters and out when it leaves.
    private static final int LIGHT_RADIUS = 90, LIGHT_FADE_IN_MS = 120, LIGHT_FADE_OUT_MS = 350;
    private Point light;            // mouse position, or the last one while fading out
    private float lightAlpha;       // 0..1
    private boolean lightOn;
    private long lightTick;
    private final Timer lightTimer = new Timer(8, e -> {
        long now = System.nanoTime();
        float dt = (now - lightTick) / 1_000_000f;
        lightTick = now;
        lightAlpha = lightOn ? Math.min(1f, lightAlpha + dt / LIGHT_FADE_IN_MS) : Math.max(0f, lightAlpha - dt / LIGHT_FADE_OUT_MS);
        repaintLight(light);
        if (lightAlpha == (lightOn ? 1f : 0f)) ((Timer) e.getSource()).stop();
    });

    private void moveLight(Point p, boolean on) {
        Point old = light;
        if (p != null) light = p;
        if (on != lightOn) {
            lightOn = on;
            lightTick = System.nanoTime();
            lightTimer.start();
        }
        repaintLight(old);
        repaintLight(light);
    }

    private void repaintLight(Point p) {
        if (p != null) repaint(p.x - LIGHT_RADIUS - 2, p.y - LIGHT_RADIUS - 2, 2 * LIGHT_RADIUS + 4, 2 * LIGHT_RADIUS + 4);
    }

    private void paintLight(Graphics2D g, int cols) {
        if (light == null || lightAlpha <= 0f) return;
        float a = lightAlpha;
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // the glow itself
        g2.setPaint(new RadialGradientPaint(light, LIGHT_RADIUS, new float[]{0f, 0.45f, 1f}, new Color[]{
                new Color(1f, 1f, 1f, 0.13f * a), new Color(1f, 1f, 1f, 0.05f * a), new Color(1f, 1f, 1f, 0f)}));
        g2.fillOval(light.x - LIGHT_RADIUS, light.y - LIGHT_RADIUS, 2 * LIGHT_RADIUS, 2 * LIGHT_RADIUS);
        // cell borders revealed by the light, brighter the closer they are
        int c0 = Math.max(0, (light.x - LIGHT_RADIUS - PAD) / CELL), c1 = Math.min(cols - 1, (light.x + LIGHT_RADIUS - PAD) / CELL);
        int r0 = Math.max(0, (light.y - LIGHT_RADIUS - PAD) / CELL), r1 = (light.y + LIGHT_RADIUS - PAD) / CELL;
        g2.setStroke(new BasicStroke(1f));
        for (int r = r0; r <= r1; r++) {
            for (int c = c0; c <= c1; c++) {
                if (r * cols + c >= items.size()) continue;
                int x = PAD + c * CELL, y = PAD + r * CELL;
                double d = light.distance(x + CELL / 2.0, y + CELL / 2.0) / LIGHT_RADIUS;
                if (d >= 1) continue;
                float strength = (float) ((1 - d) * (1 - d));
                g2.setColor(new Color(1f, 1f, 1f, Math.min(1f, 0.38f * strength * a)));
                g2.drawRect(x, y, CELL - 1, CELL - 1);
            }
        }
        g2.dispose();
    }
    private Consumer<ItemDb.Item> onClick;

    public void onItemClick(Consumer<ItemDb.Item> listener) {
        onClick = listener;
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }

    public CollectionGrid() {
        setOpaque(true);
        MouseAdapter mouse = new MouseAdapter() {
            private int pressIndex = -1;

            @Override
            public void mouseMoved(MouseEvent e) {
                setHover(indexAt(e.getX(), e.getY()));
                moveLight(e.getPoint(), true);
                restartTip(e.getPoint());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                setHover(indexAt(e.getX(), e.getY()));
                moveLight(e.getPoint(), true);
            }

            @Override
            public void mousePressed(MouseEvent e) {
                hideTip();
                pressIndex = indexAt(e.getX(), e.getY());
            }

            // a click counts when press and release land on the same item, even if the mouse moved a pixel in between
            // (mouseClicked is dropped on the slightest movement, which made clicks feel unreliable)
            @Override
            public void mouseReleased(MouseEvent e) {
                int index = indexAt(e.getX(), e.getY());
                boolean click = index >= 0 && index == pressIndex && SwingUtilities.isLeftMouseButton(e);
                pressIndex = -1;
                if (!click) return;
                startPulse(index, CLICK_PULSE_MS);   // same box as the ST Sets jump, at double speed
                if (onClick != null) onClick.accept(items.get(index));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                moveLight(null, false);
                setHover(-1);
                tipRest.stop();
                hideTip();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    private void setHover(int index) {
        if (index == hover) return;
        repaintCell(hover);
        hover = index;
        repaintCell(hover);
    }

    private void repaintCell(int index) {
        if (index < 0) return;
        int cols = columns();
        repaint(PAD + (index % cols) * CELL - 2, PAD + (index / cols) * CELL - 2, CELL + 4, CELL + 4);
    }

    private int indexAt(int x, int y) {
        int cols = columns();
        if (x < PAD || y < PAD) return -1;
        int c = (x - PAD) / CELL, r = (y - PAD) / CELL;
        if (c >= cols) return -1;
        int index = r * cols + c;
        return index < items.size() ? index : -1;
    }

    public void setData(List<ItemDb.Item> items, Map<Integer, CollectionStore.Entry> owned) {
        this.items = items;
        this.owned = owned;
        revalidate();
        repaint();
    }

    public int columns() {
        return columnsFor(getWidth());
    }

    static int columnsFor(int width) {
        return Math.max(1, (width - 2 * PAD) / CELL);
    }

    /**
     * Grid width that holds whole columns exactly (for snapping a resizable column next to the grid).
     */
    public static int snapWidth(int width, boolean nearest) {
        float cols = (width - 2 * PAD) / (float) CELL;
        return 2 * PAD + Math.max(1, nearest ? Math.round(cols) : (int) cols) * CELL;
    }

    private int rows(int cols) {
        return (items.size() + cols - 1) / cols;
    }

    @Override
    public Dimension getPreferredSize() {
        int w = getParent() instanceof JViewport vp && vp.getWidth() > 0 ? vp.getWidth() : 12 * CELL + 2 * PAD;
        return new Dimension(w, rows(columnsFor(w)) * CELL + 2 * PAD);
    }

    @Override
    public void setBounds(int x, int y, int w, int h) {
        super.setBounds(x, y, w, h);
        if (w != lastWidth) {   // row count depends on width
            lastWidth = w;
            SwingUtilities.invokeLater(this::revalidate);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Rectangle clip = g2.getClipBounds();
        g2.setColor(BG);
        g2.fill(clip != null ? clip : new Rectangle(getSize()));
        paintCells(g2, columns(), clip);
        paintLight(g2, columns());
        if (hover >= 0) {
            int cols = columns();
            Color accent = UIManager.getColor("LootGobbler.gold");
            g2.setColor(Palette.mix(accent != null ? accent : Palette.GOLD, Color.WHITE, 0.5));   // light gold
            g2.setStroke(new BasicStroke(2f));
            g2.drawRect(PAD + (hover % cols) * CELL + 1, PAD + (hover / cols) * CELL + 1, CELL - 2, CELL - 2);
        }
        if (pulseIndex >= 0) {   // gold box that grows and fades (sub-pixel, eased)
            int cols = columns();
            float t = Math.min(1f, (System.nanoTime() - pulseStart) / (pulseMs * 1_000_000f));
            float eased = 1f - (1f - t) * (1f - t);   // ease-out: quick start, gentle end
            float grow = eased * PULSE_GROW;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f - t));
            g2.setColor(PULSE_GOLD);
            g2.setStroke(new BasicStroke(PULSE_STROKE));
            g2.draw(new java.awt.geom.Rectangle2D.Float(PAD + (pulseIndex % cols) * CELL - grow,
                    PAD + (pulseIndex / cols) * CELL - grow, CELL + 2 * grow, CELL + 2 * grow));
            g2.setComposite(AlphaComposite.SrcOver);
        }
        paintTip(g2);   // on top of everything
        g2.dispose();
    }

    private void paintCells(Graphics2D g, int cols, Rectangle clip) {
        int rows = rows(cols);
        int first = clip == null ? 0 : Math.max(0, (clip.y - PAD) / CELL);
        int last = clip == null ? rows : Math.min(rows, (clip.y + clip.height - PAD) / CELL + 1);
        Composite normal = g.getComposite();
        Composite dim = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f);
        for (int r = first; r < last; r++) {
            for (int c = 0; c < cols; c++) {
                int index = r * cols + c;
                if (index >= items.size()) return;
                ItemDb.Item item = items.get(index);
                int x = PAD + c * CELL, y = PAD + r * CELL;
                boolean have = owned.containsKey(item.id);
                if (have) {
                    g.setColor(OWNED);
                    g.fillRect(x + 1, y + 1, CELL - 2, CELL - 2);
                }
                Image icon = icon(item.id);
                if (icon != null) {
                    g.setComposite(have ? normal : dim);
                    g.drawImage(icon, x + (CELL - ICON) / 2, y + (CELL - ICON) / 2, ICON, ICON, null);
                    g.setComposite(normal);
                }
                if (have) {
                    CollectionStore.Entry entry = owned.get(item.id);
                    EnchantBadge.draw(g, x + CELL - 3, y + CELL - 3, entry.maxEnchants(), 5);   // best copy
                    drawCount(g, x + 2, y + CELL - 3, entry.count);
                }
            }
        }
    }

    private static final Font COUNT_FONT = new Font(lootgobbler.Themes.BODY_FONT, Font.PLAIN, 16);
    private static final Color COUNT_GOLD = new Color(0xF5C542);

    /**
     * How often the item is tracked, bottom-left: gold pixel digits with a black outline so they read on any icon.
     */
    private static void drawCount(Graphics2D g, int x, int baseline, int count) {
        if (count <= 0) return;
        String text = String.valueOf(count);
        Object aa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setFont(COUNT_FONT);
        g.setColor(Color.BLACK);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) g.drawString(text, x + dx, baseline + dy);
            }
        }
        g.setColor(COUNT_GOLD);
        g.drawString(text, x, baseline);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
    }

    /**
     * Renders the whole grid (all rows) with the given column count, e.g. for the clipboard.
     */
    public BufferedImage renderImage(int cols) {
        cols = Math.max(1, cols);
        BufferedImage img = new BufferedImage(cols * CELL + 2 * PAD, Math.max(1, rows(cols)) * CELL + 2 * PAD,
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(BG);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        paintCells(g, cols, null);
        g.dispose();
        return img;
    }

    // Item name bubble, drawn in the grid itself: appears once the mouse rests on an item, fades in, and hides
    // again as soon as the mouse moves or a button is pressed.
    private static final int TIP_REST_MS = 200, TIP_FADE_MS = 150;
    private int tipIndex = -1;
    private Point tipPoint, restPoint;
    private float tipAlpha;
    private long tipShown;
    private Rectangle tipBounds;
    private final Timer tipRest = new Timer(TIP_REST_MS, e -> showTip());
    private final Timer tipFade = new Timer(8, e -> {
        tipAlpha = Math.min(1f, (System.nanoTime() - tipShown) / (TIP_FADE_MS * 1_000_000f));
        if (tipBounds != null) repaint(tipBounds);
        if (tipAlpha >= 1f) ((Timer) e.getSource()).stop();
    });

    {
        tipRest.setRepeats(false);
    }

    private void restartTip(Point p) {
        hideTip();
        restPoint = p;
        tipRest.restart();
    }

    private void showTip() {
        int index = restPoint == null ? -1 : indexAt(restPoint.x, restPoint.y);
        if (index < 0) return;
        tipIndex = index;
        tipPoint = restPoint;
        tipAlpha = 0f;
        tipBounds = null;
        tipShown = System.nanoTime();
        tipFade.restart();
        repaint();   // bounds are measured while painting
    }

    private void hideTip() {
        tipFade.stop();
        if (tipIndex < 0) return;
        tipIndex = -1;
        if (tipBounds != null) repaint(tipBounds);
        tipBounds = null;
    }

    private void paintTip(Graphics2D g) {
        if (tipIndex < 0 || tipIndex >= items.size() || tipPoint == null) return;
        ItemDb.Item item = items.get(tipIndex);
        CollectionStore.Entry entry = owned.get(item.id);
        String name = item.name + (item.isShiny() && !item.name.contains("Shiny") ? " (Shiny)" : "");
        String sub = entry == null ? "Not found yet" : entry.count == 1 ? "Found 1 time" : "Found " + entry.count + " times";
        Font base = UIManager.getFont("Label.font");
        if (base == null) base = getFont();
        FontMetrics fm = g.getFontMetrics(base);
        int padX = 8, padY = 5, gap = 1;
        int w = Math.max(fm.stringWidth(name), fm.stringWidth(sub)) + 2 * padX;
        int h = 2 * fm.getHeight() + gap + 2 * padY;
        // below-right of the mouse, flipped to stay inside the visible part of the grid
        Rectangle vis = getVisibleRect();
        int x = tipPoint.x + 14, y = tipPoint.y + 18;
        if (x + w > vis.x + vis.width - 2) x = tipPoint.x - w - 8;
        if (y + h > vis.y + vis.height - 2) y = tipPoint.y - h - 8;
        x = Math.max(vis.x + 2, x);
        y = Math.max(vis.y + 2, y);
        tipBounds = new Rectangle(x - 1, y - 1, w + 3, h + 3);

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, tipAlpha));
        Color bg = UIManager.getColor("ToolTip.background"), fg = UIManager.getColor("ToolTip.foreground");
        Color line = UIManager.getColor("LootGobbler.line");
        g2.setColor(bg != null ? bg : new Color(0x24321C));
        g2.fillRect(x, y, w, h);
        g2.setColor(line != null ? line : Color.GRAY);
        g2.drawRect(x, y, w - 1, h - 1);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(base);
        g2.setColor(fg != null ? fg : Color.WHITE);
        g2.drawString(name, x + padX, y + padY + fm.getAscent());
        Color muted = UIManager.getColor("LootGobbler.muted");
        g2.setColor(entry == null ? (muted != null ? muted : Color.GRAY) : COUNT_GOLD);
        g2.drawString(sub, x + padX, y + padY + fm.getHeight() + gap + fm.getAscent());
        g2.dispose();
    }

    /**
     * Outlined item sprite at grid size, cached. Call on the EDT.
     */
    public static Image icon(int id) {
        Image img = ICONS.get(id);
        if (img == null) {
            try {
                ImageIcon ii = ImageBuffer.getOutlinedIcon(id, ICON);
                img = ii != null ? ii.getImage() : NO_ICON;
            } catch (Exception e) {
                img = NO_ICON;
            }
            ICONS.put(id, img);
        }
        return img == NO_ICON ? null : img;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return CELL;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(CELL, visible.height - CELL);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        // stretch to fill when there are few rows, so the dark background covers the whole view
        return getParent() instanceof JViewport vp && vp.getHeight() > getPreferredSize().height;
    }
}
