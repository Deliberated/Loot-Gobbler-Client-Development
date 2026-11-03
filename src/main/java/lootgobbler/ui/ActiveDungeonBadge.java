package lootgobbler.ui;

import lootgobbler.DpsHistory;
import lootgobbler.DpsHistory.Boss;
import lootgobbler.DpsHistory.PlayerRow;
import lootgobbler.DpsHistory.Run;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * A box in the title bar (after the menus) while you are in a dungeon, from the moment you enter it: the dungeon's
 * portal, "Duration: m:ss" (time in the dungeon), the game's difficulty rating (number and gravestone) and, once you hit its
 * main boss, "Active Dungeon: Rank #x / y" (your place among everyone who hit it). Tinted in a darkened version of
 * the portal's colors; hidden outside dungeons. A click opens the dungeon under Battles > Dungeons.
 */
public final class ActiveDungeonBadge extends JPanel {
    private static final int ICON = 22;
    private static final int GRAVESTONE = 1282;   // the game's plain gravestone: the difficulty's icon
    private static final Color FALLBACK = new Color(0x3A2C08);
    private final Map<String, Icon> icons = new HashMap<>();
    private final Map<String, Color> tints = new HashMap<>();
    private final JLabel icon = new JLabel();
    private final JLabel since = new JLabel();
    private final JLabel difficulty = new JLabel();
    private final JLabel rank = new JLabel();
    private final Timer clock = new Timer(1000, e -> updateSince());
    private long start;
    private String dungeon = "";
    private Color tint = FALLBACK;

    public ActiveDungeonBadge(Runnable open) {
        super(new FlowLayout(FlowLayout.LEFT, 10, 0));
        setOpaque(false);
        setBorder(new EmptyBorder(4, 14, 4, 14));
        for (JLabel l : new JLabel[]{since, difficulty, rank}) l.setForeground(new Color(0xF2ECDC));
        add(icon);
        add(since);
        add(difficulty);
        add(rank);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText("Open this dungeon in Battles");
        Clicks.onClick(this, open);
        setVisible(false);
        DpsHistory.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        lootgobbler.DungeonVisits.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        refresh();   // already in a dungeon
    }

    /** Only as wide as its content (the title bar's menu bar would otherwise stretch it). */
    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    private void refresh() {
        lootgobbler.DungeonVisits.Visit visit = lootgobbler.DungeonVisits.get().current();
        if (visit == null) {
            clock.stop();
            if (isVisible()) {
                setVisible(false);
                revalidateParent();
            }
            return;
        }
        Run run = DpsHistory.get().live();
        Boss boss = run == null || run.guildHall || !run.dungeon.equals(visit.dungeon) ? null : run.mainBoss();
        PlayerRow me = boss == null ? null : boss.me();
        Icon portal = icons.computeIfAbsent(visit.dungeon, d -> ProfileView.dungeonIcon(d, ICON));
        icon.setIcon(portal);
        icon.setToolTipText(visit.dungeon);
        tint = tints.computeIfAbsent(visit.dungeon, d -> tint(portal));
        // the rank only once you have one (you hit the main boss)
        rank.setVisible(me != null);
        if (me != null) rank.setText("<html>Active Dungeon: <b>Rank #" + boss.rankOf(me) + " / " + boss.players.size() + "</b></html>");
        dungeon = visit.dungeon;
        start = visit.start;
        updateSince();
        if (!clock.isRunning()) clock.start();
        if (!isVisible()) {
            setVisible(true);
            revalidateParent();
        }
        repaint();
    }

    private void updateSince() {
        long s = Math.max(0, (System.currentTimeMillis() - start) / 1000);
        String time = s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
        since.setText("<html>Duration: <b>" + time + "</b></html>");
        // the game's rating of the dungeon (loaded in the background, so it may come a moment later)
        Double d = lootgobbler.DungeonDifficulty.of(dungeon);
        difficulty.setVisible(d != null);
        if (d != null) {
            // the number with the game's gravestone, as the game shows a dungeon's difficulty
            String text = "<html><b>" + (d % 1 == 0 ? String.valueOf(d.intValue()) : String.valueOf(d)) + "</b></html>";
            if (difficulty.getIcon() == null) {
                difficulty.setIcon(CharactersView.sprite(GRAVESTONE, 16));
                difficulty.setHorizontalTextPosition(SwingConstants.LEFT);
                difficulty.setIconTextGap(4);
                difficulty.setToolTipText("Dungeon difficulty");
            }
            if (!text.equals(difficulty.getText())) {
                difficulty.setText(text);
                revalidateParent();
            }
        }
    }

    /**
     * The portal's main color, darkened: the average of its opaque pixels, weighted by how colorful they are (so
     * the black outline and grey stone count little), at a low brightness so light text stays readable.
     */
    private static Color tint(Icon portal) {
        if (portal == null || portal.getIconWidth() <= 0) return FALLBACK;
        BufferedImage img = new BufferedImage(portal.getIconWidth(), portal.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        portal.paintIcon(null, g, 0, 0);
        g.dispose();
        double r = 0, gr = 0, b = 0, weight = 0;
        float[] hsb = new float[3];
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                if ((argb >>> 24) < 200) continue;
                int pr = argb >> 16 & 0xFF, pg = argb >> 8 & 0xFF, pb = argb & 0xFF;
                Color.RGBtoHSB(pr, pg, pb, hsb);
                double w = 0.05 + hsb[1] * hsb[2];
                r += pr * w;
                gr += pg * w;
                b += pb * w;
                weight += w;
            }
        }
        if (weight == 0) return FALLBACK;
        Color.RGBtoHSB((int) (r / weight), (int) (gr / weight), (int) (b / weight), hsb);
        return Color.getHSBColor(hsb[0], Math.min(1f, hsb[1] * 1.15f), 0.28f);
    }

    /** The color with its brightness scaled. */
    private static Color shade(Color c, float factor) {
        float[] hsb = Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), null);
        return Color.getHSBColor(hsb[0], hsb[1], Math.min(1f, hsb[2] * factor));
    }

    private void revalidateParent() {
        if (getParent() != null) getParent().revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth() - 1, h = getHeight() - 5;
        g2.setPaint(new GradientPaint(0, 2, shade(tint, 1.2f), 0, 2 + h, shade(tint, 0.8f)));
        g2.fillRoundRect(4, 2, w - 8, h, 10, 10);
        g2.setColor(shade(tint, 1.9f));
        g2.drawRoundRect(4, 2, w - 8, h, 10, 10);
        g2.dispose();
    }
}
