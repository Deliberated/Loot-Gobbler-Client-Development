package lootgobbler.ui;

import lootgobbler.DpsHistory;
import lootgobbler.DpsHistory.Boss;
import lootgobbler.DpsHistory.PlayerRow;
import lootgobbler.DpsHistory.Run;
import lootgobbler.DungeonVisits;

import javax.swing.*;
import java.awt.*;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The DPS overlay: the boss you are fighting in the dungeon you are in, as under Battles > DPS (place, name, damage,
 * share, a gold bar for the share), but only three players: you, the one above you and the one below (at the top or
 * the bottom of the list, the two nearest). Your row in gold. Always the same size. Hidden while there is no fight.
 */
final class DpsOverlay extends JComponent {
    private static final int W = 320, PAD = 8, HEAD = 18, ROW = 22, ROWS = 3;
    private static final Color BOX = new Color(0x2A2620), EDGE = new Color(0x5A544A), TEXT = new Color(0xF2ECDC),
            MUTED = new Color(0xA39A86), ME = new Color(0xE8C552), BAR = new Color(232, 197, 82, 48);
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance(Locale.ROOT);

    private record Line(int place, String name, long damage, double share, boolean me) {
    }

    private String boss = "";
    private List<Line> lines = new ArrayList<>();
    private double maxShare = 1;
    private String shown = "";

    /** sample: made-up players, to show what it looks like; else the fight you are in. */
    DpsOverlay(boolean sample) {
        NUM.setGroupingUsed(true);
        setFont(UIManager.getFont("Label.font"));
        if (sample) {
            boss = "Main boss";
            lines = List.of(new Line(3, "Brightvale", 412_880, 18.2, false), new Line(4, "Kestrel", 371_045, 16.4, true),
                    new Line(5, "Moru", 298_114, 13.1, false));
            maxShare = 24.0;
            return;
        }
        setVisible(false);
        DpsHistory.get().onChange(() -> SwingUtilities.invokeLater(this::update));
        DungeonVisits.get().onChange(() -> SwingUtilities.invokeLater(this::update));
        new Timer(500, e -> update()).start();
        update();
    }

    /** Your latest boss in the dungeon you are in, if you hit one. */
    private static Boss fight() {
        DungeonVisits.Visit visit = DungeonVisits.get().current();
        Run run = DpsHistory.get().live();
        if (visit == null || run == null || run.guildHall || !run.dungeon.equals(visit.dungeon)) return null;
        for (int i = run.bosses.size() - 1; i >= 0; i--) {
            Boss b = run.bosses.get(i);
            if (b.me() != null) return b;
        }
        return null;
    }

    private void update() {
        Boss b = fight();
        List<Line> now = new ArrayList<>();
        if (b != null) {
            List<PlayerRow> players = new ArrayList<>(b.players);   // in their order under DPS (most damage first)
            int me = players.indexOf(b.me());
            int from = Math.max(0, Math.min(me - 1, players.size() - ROWS));
            for (int i = from; i < Math.min(players.size(), from + ROWS); i++) {
                PlayerRow p = players.get(i);
                now.add(new Line(i + 1, p.name, p.damage, b.share(p), p.me));
            }
            maxShare = players.isEmpty() ? 1 : Math.max(0.001, b.share(players.get(0)));
            boss = b.name;
        }
        String state = boss + now;
        if (!state.equals(shown)) {
            shown = state;
            lines = now;
            repaint();
        }
        boolean on = b != null;
        if (on != isVisible()) {
            setVisible(on);
            if (getParent() != null) getParent().revalidate();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(W, 2 * PAD + HEAD + ROWS * ROW);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth() - 1, h = getHeight() - 1;
        g2.setColor(BOX);
        g2.fillRoundRect(0, 0, w, h, 10, 10);
        g2.setColor(EDGE);
        g2.drawRoundRect(0, 0, w, h, 10, 10);

        Font plain = getFont(), bold = plain.deriveFont(Font.BOLD), small = plain.deriveFont(Font.BOLD, plain.getSize2D() - 1.5f);
        int left = PAD + 4, right = getWidth() - PAD - 4;
        int dmgRight = right - 58, nameX = left + 30;
        // the header: the boss, and the columns
        g2.setFont(small);
        FontMetrics sm = g2.getFontMetrics();
        int hy = PAD + sm.getAscent();
        g2.setColor(MUTED);
        g2.drawString(fitText(boss.toUpperCase(Locale.ROOT), sm, dmgRight - 70 - left), left, hy);
        g2.drawString("DMG", dmgRight - sm.stringWidth("DMG"), hy);
        g2.drawString("%", right - sm.stringWidth("%"), hy);

        int y = PAD + HEAD;
        for (Line l : lines) {
            g2.setColor(BAR);   // the share, as the bars under DPS
            g2.fillRect(left - 4, y + 2, (int) ((right - left + 8) * Math.min(1, l.share / maxShare)), ROW - 4);
            g2.setFont(l.me ? bold : plain);
            FontMetrics fm = g2.getFontMetrics();
            int by = y + (ROW - fm.getHeight()) / 2 + fm.getAscent();
            g2.setColor(l.me ? ME : TEXT);
            g2.drawString("#" + l.place, left, by);
            g2.drawString(fitText(l.name, fm, dmgRight - 80 - nameX), nameX, by);
            String dmg = NUM.format(l.damage), pct = String.format(Locale.ROOT, "%.1f%%", l.share);
            g2.drawString(dmg, dmgRight - fm.stringWidth(dmg), by);
            g2.setColor(l.me ? ME : new Color(0xE8C552));
            g2.drawString(pct, right - fm.stringWidth(pct), by);
            y += ROW;
        }
        g2.dispose();
    }

    private static String fitText(String s, FontMetrics fm, int width) {
        if (fm.stringWidth(s) <= width) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > width) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
