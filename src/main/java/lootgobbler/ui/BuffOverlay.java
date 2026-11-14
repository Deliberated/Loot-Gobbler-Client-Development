package lootgobbler.ui;

import lootgobbler.AmuletSaves;
import lootgobbler.Amulets;
import lootgobbler.ItemCooldowns;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The buffs overlay: one round tile per item cooldown that is running (Coral Juice Refinery, Miniature Alien Generator,
 * the Protective Matrix, the Amulet of Restoration after a save), in a row. Behind each item a golden circle runs out
 * clockwise from the top as the cooldown goes by, with the time left on it. While nothing is charging: one empty grey circle.
 */
final class BuffOverlay extends JComponent {
    private static final int TILE = 46, GAP = 6, SPRITE = 24;
    private static final Color DISC = new Color(0x14, 0x10, 0x0C, 190), GOLD = new Color(0xFFC20E),
            GOLD_EDGE = new Color(0xB8860B), TEXT = new Color(0xF2ECDC), IDLE_EDGE = new Color(0x8A8272);

    private record Tile(int type, String name, long start, long durationMs) {
    }

    private final boolean sample;
    private final long sampleNow = System.currentTimeMillis();   // a sample stands still at this moment
    private List<Tile> tiles = new ArrayList<>();
    private final Map<Integer, Icon> icons = new HashMap<>();

    /** sample: fixed cooldowns, to show what it looks like; else the ones running now. */
    BuffOverlay(boolean sample) {
        this.sample = sample;
        setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
        if (sample) {
            long now = sampleNow;
            tiles = List.of(
                    new Tile(ItemCooldowns.REFINERY, "Coral Juice Refinery", now - 2_000, 15_000),
                    new Tile(ItemCooldowns.ALIEN_GENERATOR, "Miniature Alien Generator", now - 27_000, 45_000),
                    new Tile(0x0484, "Magic Protective Matrix", now - 70_000, 180_000),
                    new Tile(Amulets.restorationId(), "Amulet of Restoration", now - 1_500_000, AmuletSaves.RECHARGE_MS));
            return;
        }
        setVisible(false);
        new Timer(100, e -> update()).start();   // the circles run down smoothly
    }

    private void update() {
        List<Tile> now = new ArrayList<>();
        AmuletSaves amulet = AmuletSaves.get();
        if (amulet.charging()) {
            now.add(new Tile(Amulets.restorationId(), "Amulet of Restoration", amulet.lastSave(), AmuletSaves.RECHARGE_MS));
        }
        for (ItemCooldowns.Running r : ItemCooldowns.running()) now.add(new Tile(r.type(), r.name(), r.start(), r.durationMs()));
        boolean resized = now.size() != tiles.size();
        tiles = now;
        setToolTipText(now.isEmpty() ? "No cooldowns running" : tooltip());
        repaint();
        if (resized || !isVisible()) {   // always shown: an empty grey circle while nothing is charging
            setVisible(true);
            if (getParent() != null) getParent().revalidate();
        }
    }

    private String tooltip() {
        StringBuilder sb = new StringBuilder("<html>");
        long now = clock();
        for (Tile t : tiles) sb.append(t.name()).append(": ").append(left(t, now)).append("<br>");
        return sb.append("</html>").toString();
    }

    @Override
    public Dimension getPreferredSize() {
        int n = Math.max(1, tiles.size());
        return new Dimension(n * TILE + (n - 1) * GAP, TILE);
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
        long now = clock();
        int x = Math.max(0, (getWidth() - getPreferredSize().width) / 2);   // in the middle
        if (tiles.isEmpty()) {   // nothing charging: an empty grey circle
            g2.setColor(DISC);
            g2.fill(new Ellipse2D.Double(x, 0, TILE - 1, TILE - 1));
            g2.setColor(IDLE_EDGE);
            g2.setStroke(new BasicStroke(2f));
            g2.draw(new Ellipse2D.Double(x + 1, 1, TILE - 3, TILE - 3));
        }
        for (Tile t : tiles) {
            paintTile(g2, t, x, now);
            x += TILE + GAP;
        }
        g2.dispose();
    }

    private void paintTile(Graphics2D g2, Tile t, int x, long now) {
        double share = t.durationMs() <= 0 ? 0
                : Math.max(0, Math.min(1, (t.start() + t.durationMs() - now) / (double) t.durationMs()));
        g2.setColor(DISC);
        g2.fill(new Ellipse2D.Double(x, 0, TILE - 1, TILE - 1));
        if (share > 0) {
            // what is left, from the top: the part that has gone by opens clockwise from 12 o'clock
            Arc2D pie = new Arc2D.Double(x, 0, TILE - 1, TILE - 1, 90, share * 360, Arc2D.PIE);
            g2.setColor(GOLD);
            g2.fill(pie);
            g2.setColor(GOLD_EDGE);
            g2.draw(pie);
        }
        Icon icon = icons.computeIfAbsent(t.type(), id -> ActiveDungeonBadge.fit(CharactersView.sprite(id, 40), SPRITE));
        if (icon != null) icon.paintIcon(this, g2, x + (TILE - icon.getIconWidth()) / 2, (TILE - icon.getIconHeight()) / 2);
        String text = left(t, now);
        g2.setFont(getFont().deriveFont(getFont().getSize2D() - 1f));
        FontMetrics fm = g2.getFontMetrics();
        int tx = x + TILE - fm.stringWidth(text) - 1, ty = TILE - fm.getDescent();
        g2.setColor(new Color(0, 0, 0, 200));
        g2.drawString(text, tx + 1, ty + 1);
        g2.setColor(TEXT);
        g2.drawString(text, tx, ty);
    }

    private long clock() {
        return sample ? sampleNow : System.currentTimeMillis();
    }

    /** Time left: seconds below a minute ("12"), minutes and seconds below ten minutes ("2:45"), else minutes ("29m"). */
    private String left(Tile t, long now) {
        long s = (long) Math.ceil(Math.max(0, t.start() + t.durationMs() - now) / 1000.0);
        if (s < 60) return String.valueOf(s);
        if (s < 600) return String.format("%d:%02d", s / 60, s % 60);
        return (s + 59) / 60 + "m";
    }
}
