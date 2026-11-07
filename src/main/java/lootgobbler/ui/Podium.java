package lootgobbler.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * A podium for the best three: gold, silver and bronze blocks with the place on their face (2nd left, 1st in the
 * middle, 3rd right), each player's character standing on theirs with their name above. Hovering a player shows
 * their tip.
 */
final class Podium extends JComponent {
    /** One player on it: name, character (may be null) and what hovering them shows. */
    record Place(String name, Icon look, String tip) {
    }

    private static final Color[] MEDAL = {new Color(0xE5B23A), new Color(0xB9C0C8), new Color(0xC07A3C)};
    private static final int COL = 96, GAP = 6, NAME = 18;
    private static final int[] BLOCK = {64, 44, 30};   // block height per place
    private static final int[] ORDER = {1, 0, 2};   // left to right
    private final List<Place> places;
    private final int lookHeight;

    /** places best first; only the first three stand on it. */
    Podium(List<Place> places) {
        this.places = places.subList(0, Math.min(3, places.size()));
        int h = 0;
        for (Place p : this.places) if (p.look() != null) h = Math.max(h, p.look().getIconHeight());
        lookHeight = h;
        setOpaque(false);
        setToolTipText("");   // the text comes from getToolTipText(MouseEvent)
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(3 * COL + 2 * GAP, BLOCK[0] + lookHeight + NAME + 4);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    private int columnX(int k) {
        return k * (COL + GAP);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        for (int k = 0; k < 3; k++) {
            int place = ORDER[k];
            if (place < places.size() && e.getX() >= columnX(k) && e.getX() < columnX(k) + COL) return places.get(place).tip();
        }
        return null;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int base = getHeight();
        Font font = getFont() != null ? getFont() : UIManager.getFont("Label.font");
        for (int k = 0; k < 3; k++) {
            int place = ORDER[k];
            if (place >= places.size()) continue;
            Place p = places.get(place);
            int x = columnX(k), top = base - BLOCK[place];
            g.setColor(MEDAL[place].darker());
            g.fillRect(x, top, COL, BLOCK[place]);
            g.setColor(MEDAL[place]);
            g.fillRect(x, top, COL, 6);
            g.setFont(font.deriveFont(Font.BOLD, place == 0 ? 26f : 20f));
            g.setColor(new Color(255, 255, 255, 200));
            center(g, String.valueOf(place + 1), x + COL / 2, top + 6 + (BLOCK[place] - 6) / 2);
            int h = 0;
            if (p.look() != null) {
                h = p.look().getIconHeight();
                p.look().paintIcon(this, g, x + (COL - p.look().getIconWidth()) / 2, top - h);
            }
            g.setFont(font.deriveFont(Font.BOLD));
            g.setColor(Color.WHITE);
            center(g, fit(g, p.name()), x + COL / 2, top - h - NAME / 2);
        }
        g.dispose();
    }

    /** The name, cut with "..." to fit the column. */
    private static String fit(Graphics2D g, String s) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= COL - 4) return s;
        while (s.length() > 1 && fm.stringWidth(s + "...") > COL - 4) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private static void center(Graphics2D g, String s, int cx, int cy) {
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, cx - fm.stringWidth(s) / 2, cy + (fm.getAscent() - fm.getDescent()) / 2);
    }
}
