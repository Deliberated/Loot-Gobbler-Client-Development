package lootgobbler.ui;

import lootgobbler.Themes;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * The look of a guided tour over the window (the Newcomer's Guide, the What's New tours): the rest of the page greyed
 * out, what a step is about in a gold frame, what to click glowing with CLICK ME, and the step's card on paper with a
 * gold edge. Only the card takes the mouse; every other click goes to the window below.
 */
final class GuideLayer extends JComponent {
    static final int RING = 6;
    private final JComponent card;
    /** What is framed and what glows, in this layer's coordinates. */
    List<Rectangle> spots = List.of(), glows = List.of();

    GuideLayer(JComponent card) {
        this.card = card;
        setLayout(null);
        setOpaque(false);
        add(card);
    }

    @Override
    public boolean contains(int x, int y) {
        return card.isVisible() && card.getBounds().contains(x, y);
    }

    /** Where the components are in this layer (their visible part; an item: just its icon), those not shown left out. */
    List<Rectangle> rects(List<Component> cs) {
        List<Rectangle> out = new ArrayList<>();
        Window window = SwingUtilities.getWindowAncestor(this);
        for (Component c : cs) {
            if (!c.isShowing()) continue;
            Rectangle r = c instanceof JComponent j ? j.getVisibleRect() : new Rectangle(0, 0, c.getWidth(), c.getHeight());
            if (c instanceof JComponent j && j.getClientProperty(ItemLinks.ITEM) != null) {
                r = r.intersection(ItemLinks.iconArea(j));   // an item: just its icon, which is what takes the click
            }
            if (r.isEmpty()) continue;
            if (SwingUtilities.getWindowAncestor(c) != window) {   // a menu in a window of its own
                Point p = c.getLocationOnScreen();
                SwingUtilities.convertPointFromScreen(p, this);
                out.add(new Rectangle(p.x + r.x, p.y + r.y, r.width, r.height));
            } else {
                out.add(SwingUtilities.convertRectangle(c, r, this));
            }
        }
        return out;
    }

    /** The card below what is framed if there is room, else above, right or left; in the middle when nothing is. */
    void placeCard() {
        Dimension d = card.getPreferredSize();
        int w = d.width, h = d.height, W = getWidth(), H = getHeight(), gap = 18;
        int x, y;
        if (spots.isEmpty()) {
            x = (W - w) / 2;
            y = (H - h) / 2;
        } else {
            Rectangle u = new Rectangle(spots.get(0));
            for (Rectangle r : spots) u = u.union(r);
            u.grow(RING, RING);
            if (u.y + u.height + gap + h + 30 <= H) {          // below (room for CLICK ME)
                x = u.x;
                y = u.y + u.height + gap + (glows.isEmpty() ? 0 : 30);
            } else if (u.y - gap - h >= 8) {                   // above
                x = u.x;
                y = u.y - gap - h;
            } else if (u.x + u.width + gap + w <= W) {         // right
                x = u.x + u.width + gap;
                y = u.y;
            } else if (u.x - gap - w >= 8) {                   // left
                x = u.x - gap - w;
                y = u.y;
            } else if (!glows.isEmpty()) {                     // a big frame: under what to click, inside it
                Rectangle t = glows.get(0);
                x = t.x;
                y = t.y + t.height + 16 + 34;
            } else {
                x = W - w - 24;
                y = H - h - 24;
            }
        }
        x = Math.max(8, Math.min(x, W - w - 8));
        y = Math.max(8, Math.min(y, H - h - 8));
        Rectangle now = new Rectangle(x, y, w, h);
        if (!now.equals(card.getBounds())) {
            card.setBounds(now);
            card.validate();
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
        // what the step is about stays as it is in a gold frame with a soft glow; the rest is greyed out, still readable
        {   // a step that frames nothing (the welcome, the end, waiting): all of it greyed, just the card stands out
            Area grey = new Area(new Rectangle(0, 0, getWidth(), getHeight()));
            for (Rectangle r : spots) grey.subtract(new Area(round(r)));
            g2.setColor(new Color(24, 22, 20, 140));
            g2.fill(grey);
        }
        for (Rectangle r : spots) {
            RoundRectangle2D frame = round(r);
            for (int i = 6; i >= 1; i--) {
                double falloff = 1 - (i - 1) / 6.0;
                g2.setColor(new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), (int) Math.round(110 * falloff * falloff)));
                g2.setStroke(new BasicStroke(2f));
                g2.draw(new RoundRectangle2D.Double(frame.getX() - i, frame.getY() - i, frame.getWidth() + 2 * i,
                        frame.getHeight() + 2 * i, 12 + i, 12 + i));
            }
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(gold);
            g2.draw(frame);
        }

        double pulse = 0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 1100.0 * 2 * Math.PI);
        for (Rectangle r : glows) glow(g2, r, pulse);
        if (!glows.isEmpty()) clickMe(g2, glows.get(0), pulse);

        // the card: paper with a gold edge and a soft shadow
        Rectangle c = card.getBounds();
        g2.setColor(new Color(0, 0, 0, 90));
        g2.fillRoundRect(c.x + 4, c.y + 6, c.width, c.height, 14, 14);
        g2.setColor(paper());
        g2.fillRoundRect(c.x, c.y, c.width, c.height, 14, 14);
        g2.setColor(gold);
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(c.x + 1, c.y + 1, c.width - 2, c.height - 2, 14, 14);
        g2.dispose();
    }

    private static RoundRectangle2D round(Rectangle r) {
        return new RoundRectangle2D.Float(r.x - RING, r.y - RING, r.width + 2 * RING, r.height + 2 * RING, 12, 12);
    }

    /** A gold glow around r, growing and shrinking with the pulse. */
    private static void glow(Graphics2D g2, Rectangle r, double pulse) {
        int size = 4 + (int) Math.round(10 * pulse);
        for (int i = size; i >= 1; i--) {
            double falloff = 1 - (i - 1) / (double) size;
            int a = (int) Math.round(255 * (0.25 + 0.4 * pulse) * falloff * falloff);
            g2.setColor(new Color(255, 194, 14, Math.min(255, a)));
            g2.setStroke(new BasicStroke(2f));
            g2.drawRoundRect(r.x - i, r.y - i, r.width + 2 * i, r.height + 2 * i, 8 + i, 8 + i);
        }
        g2.setColor(new Color(255, 194, 14));
        g2.setStroke(new BasicStroke(2f));
        g2.drawRoundRect(r.x - 1, r.y - 1, r.width + 2, r.height + 2, 8, 8);
    }

    /** "CLICK ME" under r (above it at the bottom edge), pulsing with the glow. */
    private void clickMe(Graphics2D g2, Rectangle r, double pulse) {
        Font f = new Font(Themes.HEAD_FONT, Font.PLAIN, (int) Math.round(20 + 3 * pulse));
        g2.setFont(f);
        FontMetrics fm = g2.getFontMetrics();
        String text = "CLICK ME";
        int w = fm.stringWidth(text) + 16, h = fm.getHeight() + 2;
        int x = r.x + r.width / 2 - w / 2;
        int y = r.y + r.height + 16;
        if (y + h > getHeight() - 4) y = r.y - 16 - h;
        x = Math.max(4, Math.min(x, getWidth() - w - 4));
        g2.setColor(new Color(255, 194, 14));
        g2.fillRoundRect(x, y, w, h, 8, 8);
        g2.setColor(new Color(0x1F1A14));
        g2.drawString(text, x + 8, y + 1 + fm.getAscent());
    }

    private static Color paper() {
        Color c = UIManager.getColor("LootGobbler.paper");
        return c != null ? c : UIManager.getColor("Panel.background");
    }
}
