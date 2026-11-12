package lootgobbler.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.IntConsumer;

/**
 * A switch with three stops (or two: on / off, see onOff): a rounded track with a brushed metal knob that slides
 * from stop to stop. A click moves it on (1 - 2 - 3 - 1); the knob slides there, then onChange hears the new stop.
 */
final class ThreeWaySwitch extends JComponent {
    private static final int H = 30, PAD = 3, SLIDE_MS = 160;
    private final int stops, width;
    private final String[] labels;
    private final IntConsumer onChange;
    private int stop;
    private double knob;   // where the knob is drawn, in stops (0 .. 2): between them while it slides
    private Timer slide;

    ThreeWaySwitch(int stop, IntConsumer onChange, String... labels) {
        this(3, stop, onChange, labels);
    }

    /** An on / off switch (stop 1 = on: the track glows gold). */
    static ThreeWaySwitch onOff(boolean on, IntConsumer onChange) {
        return new ThreeWaySwitch(2, on ? 1 : 0, onChange, "off", "on");
    }

    private ThreeWaySwitch(int stops, int stop, IntConsumer onChange, String... labels) {
        this.stops = stops;
        this.width = stops == 2 ? 58 : 92;
        this.stop = stop;
        this.knob = stop;
        this.onChange = onChange;
        this.labels = labels;
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) advance();
            }
        });
    }

    /** Moves on to the next stop (after the last: the first), sliding the knob there. */
    void advance() {
        if (slide != null && slide.isRunning()) return;
        int to = (stop + 1) % stops;
        double from = knob;
        long start = System.currentTimeMillis();
        slide = new Timer(12, null);
        slide.addActionListener(e -> {
            double t = Math.min(1, (System.currentTimeMillis() - start) / (double) SLIDE_MS);
            double eased = 1 - Math.pow(1 - t, 3);
            knob = from + (to - from) * eased;
            repaint();
            if (t >= 1) {
                slide.stop();
                stop = to;
                knob = to;
                onChange.accept(to);
            }
        });
        slide.start();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(width, H);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = width - 1, h = H - 1;
        Color gold = Section.color("LootGobbler.gold", Palette.GOLD);

        // the track: a recessed pill, darker at the top (inner shadow), with a thin light rim
        RoundRectangle2D track = new RoundRectangle2D.Double(0.5, 0.5, w, h, h, h);
        g2.setPaint(new GradientPaint(0, 0, new Color(0x2A2620), 0, h, new Color(0x5A544A)));
        g2.fill(track);
        if (knob > 0) {   // the track turns gold step by step: full gold at the last stop (as an on / off switch at on)
            double level = Math.min(1, knob / (stops - 1));
            g2.setColor(new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), (int) (150 * level)));
            g2.fill(track);
        }
        g2.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 120), 0, h / 2f, new Color(0, 0, 0, 0)));
        g2.fill(new RoundRectangle2D.Double(1.5, 1.5, w - 2, h - 2, h - 2, h - 2));
        g2.setStroke(new BasicStroke(1.2f));
        g2.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 160), 0, h, new Color(255, 255, 255, 90)));
        g2.draw(track);

        int d = h - 2 * PAD;   // the knob's diameter
        double span = w - 2 * PAD - d;
        int gaps = stops - 1;
        // the stops: small dots, the one the knob sits on in gold
        for (int i = 0; i < stops; i++) {
            double cx = PAD + span * i / gaps + d / 2.0, cy = h / 2.0 + 0.5;
            boolean on = i == stop && Math.abs(knob - stop) < 1e-6;
            g2.setColor(on ? gold : new Color(255, 255, 255, 70));
            g2.fill(new Ellipse2D.Double(cx - 2, cy - 2, 4, 4));
        }

        // the knob: a brushed metal disc with a soft shadow below it
        double x = PAD + span * knob / gaps, y = PAD + 0.5;
        g2.setColor(new Color(0, 0, 0, 90));
        g2.fill(new Ellipse2D.Double(x + 1, y + 2, d, d));
        Ellipse2D disc = new Ellipse2D.Double(x, y, d, d);
        g2.setPaint(new RadialGradientPaint(new Point.Double(x + d * 0.38, y + d * 0.32), d * 0.75f,
                new float[]{0f, 0.45f, 1f}, new Color[]{new Color(0xFBFBFB), new Color(0xCFCFCF), new Color(0x8C8C8C)}));
        g2.fill(disc);
        // brushed look: fine rings around the center
        Shape clip = g2.getClip();
        g2.clip(disc);
        g2.setStroke(new BasicStroke(0.6f));
        for (double r = 2; r < d / 2.0; r += 1.6) {
            g2.setColor(new Color(255, 255, 255, (int) (25 + 20 * Math.sin(r))));
            g2.draw(new Ellipse2D.Double(x + d / 2.0 - r, y + d / 2.0 - r, 2 * r, 2 * r));
        }
        // a light sweep across the top left and a darker one bottom right, as on turned metal
        g2.setPaint(new GradientPaint((float) x, (float) y, new Color(255, 255, 255, 110),
                (float) (x + d), (float) (y + d), new Color(0, 0, 0, 70)));
        g2.fill(disc);
        g2.setClip(clip);
        g2.setStroke(new BasicStroke(1.2f));
        g2.setColor(new Color(0x6E6E6E));
        g2.draw(disc);
        g2.setColor(new Color(255, 255, 255, 150));
        g2.draw(new Ellipse2D.Double(x + 1.2, y + 1.2, d - 2.4, d - 2.4));
        g2.dispose();
    }

    /** The name of the stop it is at. */
    String label() {
        return labels.length > stop ? labels[stop] : String.valueOf(stop + 1);
    }
}
