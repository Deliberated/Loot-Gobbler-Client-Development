package lootgobbler.ui;

import lootgobbler.MoonlightLog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Battles > Overlay: one part per overlay, with what it looks like (sample values, every part showing, as see-through
 * as it is set), whether it is on (a switch in the header, and ON / OFF after the title) and how see-through it is.
 */
final class OverlaysPage extends JPanel {
    private static final Color ON = new Color(0x4CAF50);

    OverlaysPage() {
        super(new BorderLayout());
        setOpaque(false);
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.setBorder(new EmptyBorder(0, 0, 8, 0));
        JLabel intro = new JLabel("Small windows above the game, each switched on here on its own. Drag one where you want it;"
                + " it stays there. One that has nothing to show says so.");
        intro.setForeground(Palette.MUTED);
        intro.setBorder(new EmptyBorder(4, 4, 10, 4));
        intro.setAlignmentX(LEFT_ALIGNMENT);
        list.add(intro);
        for (Overlay o : Overlay.all()) {
            Section s = part(o);
            s.setAlignmentX(LEFT_ALIGNMENT);
            list.add(s);
            list.add(Box.createVerticalStrut(8));
        }
        add(FitWidthPanel.scroll(list, 24), BorderLayout.CENTER);
    }

    private Section part(Overlay o) {
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(8, 10, 10, 10));

        JLabel about = new JLabel(o.description);
        about.setAlignmentX(LEFT_ALIGNMENT);
        body.add(about);
        body.add(Box.createVerticalStrut(8));

        Stage stage = new Stage(o);
        if (o == Overlay.DUNGEON) {   // both looks: any dungeon, and Moonlight Village with its flames
            stage.add(sample("Any dungeon", new OverlayBadge("Ocean Trench")));
            stage.add(sample("Moonlight Village", new OverlayBadge(MoonlightLog.DUNGEON)));
        } else {
            stage.add(sample(null, o == Overlay.MANA ? new ManaBar(true) : new DpsOverlay(true)));
        }
        stage.setAlignmentX(LEFT_ALIGNMENT);
        body.add(stage);
        body.add(Box.createVerticalStrut(8));

        JSlider opacity = new JSlider(Overlay.MIN_OPACITY, 100, o.opacity());
        opacity.setOpaque(false);
        opacity.setFocusable(false);
        JLabel value = new JLabel(o.opacity() + "%");
        value.setPreferredSize(new Dimension(value.getFontMetrics(value.getFont()).stringWidth("100%") + 4, value.getPreferredSize().height));
        opacity.addChangeListener(e -> {
            value.setText(opacity.getValue() + "%");
            o.setOpacity(opacity.getValue());   // follows while dragging, on the overlay itself too
            stage.repaint();
        });
        JPanel opacityRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        opacityRow.setOpaque(false);
        opacityRow.setAlignmentX(LEFT_ALIGNMENT);
        opacityRow.add(new JLabel("Opacity"));
        opacityRow.add(Box.createHorizontalStrut(12));
        opacityRow.add(opacity);
        opacityRow.add(Box.createHorizontalStrut(6));
        opacityRow.add(value);
        body.add(opacityRow);

        JLabel status = new JLabel();
        status.setFont(status.getFont().deriveFont(Font.BOLD));
        Segmented onOff = new Segmented(true, o.isOn() ? 1 : 0, i -> o.setOn(i == 1), "Off", "On");
        Runnable show = () -> {
            status.setText(o.isOn() ? "ON" : "OFF");
            status.setForeground(o.isOn() ? ON : Palette.MUTED);
            onOff.select(o.isOn() ? 1 : 0);
        };
        show.run();
        o.onChange(show);   // also switched from the status bar
        return new Section(o.title + " overlay", Palette.BLUE, body).afterTitle(status).actions(onOff);
    }

    /** A sample with a caption above it (none: no caption). */
    private static JComponent sample(String caption, JComponent look) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        if (caption != null) {
            JLabel c = new JLabel(caption);
            c.setForeground(new Color(0xC8C2B4));
            c.setFont(c.getFont().deriveFont(Font.BOLD, c.getFont().getSize2D() - 1f));
            c.setAlignmentX(LEFT_ALIGNMENT);
            p.add(c);
            p.add(Box.createVerticalStrut(4));
        }
        JPanel holder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        holder.setOpaque(false);
        holder.setAlignmentX(LEFT_ALIGNMENT);
        holder.add(look);
        p.add(holder);
        return p;
    }

    /**
     * Where the samples stand: a dark, tiled floor like the game's, the samples on it as see-through as the
     * overlay is set to be.
     */
    private static final class Stage extends JPanel {
        private final Overlay overlay;

        Stage(Overlay overlay) {
            super(new FlowLayout(FlowLayout.LEFT, 24, 16));
            this.overlay = overlay;
            setOpaque(false);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Shape clip = new java.awt.geom.RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 8, 8);
            g2.clip(clip);
            int t = 24;
            for (int y = 0; y < getHeight(); y += t) {
                for (int x = 0; x < getWidth(); x += t) {
                    g2.setColor((x / t + y / t) % 2 == 0 ? new Color(0x2E3A2C) : new Color(0x283426));
                    g2.fillRect(x, y, t, t);
                }
            }
            g2.dispose();
        }

        @Override
        protected void paintChildren(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, overlay.opacity() / 100f));
            super.paintChildren(g2);
            g2.dispose();
        }
    }
}
