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
 * portal, "Duration: m:ss" (time in the dungeon), once you hit its main boss "Rank #x / y" (your place among everyone
 * who hit it), and at the right edge the game's difficulty rating (number and gravestone), in Moonlight Village with
 * the flames of the current phase left of it. Tinted in a darkened
 * version of the portal's colors; hidden outside dungeons. A click opens the dungeon under Battles > Dungeons.
 */
public final class ActiveDungeonBadge extends JPanel {
    private static final int ICON = 26;   // the portal, cut to its pixels so it fills this
    private static final int GRAVESTONE = 1282;   // the game's plain gravestone: the difficulty's icon
    private static final int FLAME = 20586;   // MV Spirit Projectile: a flame, the icon of a phase's flames
    private static final Color FALLBACK = new Color(0x3A2C08);
    private final Map<String, Icon> icons = new HashMap<>();
    private final Map<String, Color> tints = new HashMap<>();
    private final JLabel icon = new JLabel();
    private final JLabel since = new JLabel();
    private final DifficultyMark difficulty = new DifficultyMark();
    private final JLabel rank = new JLabel();
    private final JLabel flames = new JLabel();
    private final Timer clock = new Timer(1000, e -> updateSince());
    private long start;
    private String dungeon = "";
    private Color tint = FALLBACK;

    public ActiveDungeonBadge(Runnable open) {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(3, 12, 3, 10));
        for (JLabel l : new JLabel[]{since, rank, flames}) l.setForeground(new Color(0xF2ECDC));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        left.add(icon);
        left.add(since);
        left.add(rank);
        add(left, BorderLayout.CENTER);
        difficulty.setBorder(new EmptyBorder(0, 12, 0, 0));
        difficulty.lineOf = since;
        // Moonlight Village: "<flames><flame>" of the current phase, just left of the difficulty
        flames.setHorizontalTextPosition(SwingConstants.LEFT);
        flames.setIconTextGap(3);
        flames.setToolTipText("Flames of the current phase");
        flames.setVisible(false);
        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(flames, BorderLayout.WEST);
        right.add(difficulty, BorderLayout.EAST);   // at the right edge, as the game shows it: a big number and the gravestone
        add(right, BorderLayout.EAST);
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

    /**
     * Always the same width: enough for the longest it shows (duration over an hour, a rank, a difficulty), so the
     * box does not grow and shrink as the time runs and the rank comes and goes.
     */
    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return new Dimension(Math.max(d.width, fixedWidth()), d.height);
    }

    private int fixedWidth() {
        Insets in = getInsets();
        JLabel t = new JLabel("<html>Duration: <b>0:00:00</b></html>"), r = new JLabel("<html><b>Rank #00 / 00</b></html>");
        t.setFont(since.getFont());
        r.setFont(rank.getFont());
        FontMetrics big = getFontMetrics(since.getFont().deriveFont(Font.BOLD, since.getFont().getSize2D() * 1.3f));
        int hgap = 10;
        return in.left + in.right + ICON + 3 * hgap + t.getPreferredSize().width + r.getPreferredSize().width
                + 12 + big.stringWidth("10.5") + 5 + big.getAscent() + 4   // the difficulty: number, gap, gravestone
                + (flames.isVisible() ? flames.getPreferredSize().width : 0);
    }

    /** A portal sprite cut to its visible pixels and scaled (crisp) to fit ICON, so it fills the box's height. */
    private static Icon fit(Icon src) {
        return fit(src, ICON);
    }

    /** A sprite cut to its visible pixels and scaled (crisp) into a size x size square, so all portals look alike. */
    static Icon fit(Icon src, int size) {
        return fit(src, size, false);
    }

    /** As above; bottom: standing on the square's lower edge instead of centred (e.g. to sit above a line). */
    static Icon fit(Icon src, int size, boolean bottom) {
        if (src == null || src.getIconWidth() <= 0) return src;
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(src.getIconWidth(), src.getIconHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        src.paintIcon(null, g, 0, 0);
        g.dispose();
        int x0 = img.getWidth(), y0 = img.getHeight(), x1 = -1, y1 = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) < 30) continue;
                x0 = Math.min(x0, x);
                y0 = Math.min(y0, y);
                x1 = Math.max(x1, x);
                y1 = Math.max(y1, y);
            }
        }
        if (x1 < 0) return src;
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        double scale = (double) size / Math.max(w, h);
        int dw = Math.max(1, (int) Math.round(w * scale)), dh = Math.max(1, (int) Math.round(h * scale));
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img.getSubimage(x0, y0, w, h), (size - dw) / 2, bottom ? size - dh : (size - dh) / 2, dw, dh, null);
        g.dispose();
        return new ImageIcon(out);
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
        Icon portal = icons.computeIfAbsent(visit.dungeon, d -> fit(ProfileView.dungeonIcon(d, 64)));
        icon.setIcon(portal);
        icon.setToolTipText(visit.dungeon);
        tint = tints.computeIfAbsent(visit.dungeon, d -> tint(portal));
        // the rank only once you have one (you hit the main boss)
        rank.setVisible(me != null);
        if (me != null) rank.setText("<html><b>Rank #" + boss.rankOf(me) + " / " + boss.players.size() + "</b></html>");
        dungeon = visit.dungeon;
        start = visit.start;
        updateFlames(visit);
        updateSince();
        if (!clock.isRunning()) clock.start();
        if (!isVisible()) {
            setVisible(true);
            revalidateParent();
        }
        repaint();
    }

    /** Moonlight Village: the flames the latest phase scored so far (they come in as it ends); hidden before one. */
    private void updateFlames(lootgobbler.DungeonVisits.Visit visit) {
        java.util.List<lootgobbler.DungeonVisits.MvPhase> phases = visit == null ? null : visit.mvPhases;
        boolean show = visit != null && lootgobbler.MoonlightLog.DUNGEON.equals(visit.dungeon) && phases != null && !phases.isEmpty();
        if (show) {
            if (flames.getIcon() == null) {
                flames.setFont(since.getFont().deriveFont(Font.BOLD, since.getFont().getSize2D() * 1.3f));
                flames.setIcon(fit(CharactersView.sprite(FLAME, 40), flames.getFontMetrics(flames.getFont()).getAscent()));
            }
            String n = String.valueOf(phases.get(phases.size() - 1).flames);
            if (!n.equals(flames.getText())) {
                flames.setText(n);
                revalidateParent();
            }
        }
        if (show != flames.isVisible()) {
            flames.setVisible(show);
            revalidateParent();
        }
    }

    private void updateSince() {
        long s = Math.max(0, (System.currentTimeMillis() - start) / 1000);
        String time = s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format("%d:%02d", s / 60, s % 60);
        since.setText("<html>Duration: <b>" + time + "</b></html>");
        updateFlames(lootgobbler.DungeonVisits.get().current());   // flames are counted without a change event
        // the game's rating of the dungeon (loaded in the background, so it may come a moment later)
        Double d = lootgobbler.DungeonDifficulty.of(dungeon);
        difficulty.setVisible(d != null);
        if (d != null && difficulty.set(d % 1 == 0 ? String.valueOf(d.intValue()) : String.valueOf(d), since.getFont())) {
            revalidateParent();
        }
    }

    /**
     * A dungeon's difficulty as the game shows it: a big bold number and the gravestone right after it, both
     * standing on the same line (the gravestone's visible bottom on the number's baseline, not its padded box).
     */
    static final class DifficultyMark extends JComponent {
        private static final int GAP = 5;   // as in the game
        private String text = "";
        private Icon grave;
        private Rectangle visible;   // the gravestone's opaque pixels within its icon
        JComponent lineOf;   // text whose baseline the number stands on

        DifficultyMark() {
            setToolTipText("Dungeon difficulty");
        }

        /** Shows this number (font: the badge's text font, made bigger); true if anything changed. */
        boolean set(String number, Font base) {
            if (grave == null) {
                Font big = base.deriveFont(Font.BOLD, base.getSize2D() * 1.3f);
                setFont(big);
                // the gravestone as tall as the digits: measure how much of its icon it fills, then size it to them
                FontMetrics fm = getFontMetrics(big);
                double digits = -big.createGlyphVector(fm.getFontRenderContext(), "0123456789").getVisualBounds().getY();
                grave = plainGravestone(digits);
                visible = new Rectangle(0, 0, grave == null ? 0 : grave.getIconWidth(), grave == null ? 0 : grave.getIconHeight());
            }
            if (number.equals(text)) return false;
            text = number;
            return true;
        }

        /**
         * The game's gravestone as the game draws it next to a difficulty: the plain sprite (no outline), cut to its
         * pixels and blown up (nearest neighbour, so it stays crisp) to exactly the digits' height.
         */
        private static Icon plainGravestone(double digits) {
            try {
                java.awt.image.BufferedImage sprite = assets.ImageBuffer.getImage(GRAVESTONE);
                if (sprite == null) return null;
                Rectangle r = opaqueBounds(new ImageIcon(sprite));
                java.awt.image.BufferedImage cut = sprite.getSubimage(r.x, r.y, r.width, r.height);
                // exactly as tall as the digits (pixel-sharp, but not only by whole factors, which came out too small)
                int height = Math.max(r.height, (int) Math.ceil(digits) + 1);   // the measured glyphs come out a pixel short
                int width = Math.max(1, (int) Math.round(r.width * (double) height / r.height));
                java.awt.image.BufferedImage big = new java.awt.image.BufferedImage(width, height,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = big.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g.drawImage(cut, 0, 0, big.getWidth(), big.getHeight(), null);
                g.dispose();
                return new ImageIcon(big);
            } catch (Exception e) {
                return CharactersView.sprite(GRAVESTONE, Math.max(8, (int) Math.round(digits)));
            }
        }

        private static Rectangle opaqueBounds(Icon icon) {
            if (icon == null) return new Rectangle();
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(Math.max(1, icon.getIconWidth()),
                    Math.max(1, icon.getIconHeight()), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            icon.paintIcon(null, g, 0, 0);
            g.dispose();
            int x0 = img.getWidth(), y0 = img.getHeight(), x1 = -1, y1 = -1;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    if ((img.getRGB(x, y) >>> 24) < 40) continue;
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x);
                    y1 = Math.max(y1, y);
                }
            }
            return x1 < 0 ? new Rectangle(0, 0, icon.getIconWidth(), icon.getIconHeight()) : new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            Insets in = getInsets();
            int w = fm.stringWidth(text) + GAP + (visible == null ? 0 : visible.width);
            int h = Math.max(fm.getAscent(), visible == null ? 0 : visible.height);
            return new Dimension(in.left + in.right + w, in.top + in.bottom + h);
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (grave == null) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Insets in = getInsets();
            FontMetrics fm = g2.getFontMetrics(getFont());
            // where both stand: on the same line as the box's other text, else centered on what is drawn
            int floor = -1;
            if (lineOf != null && lineOf.isShowing()) {
                int b = lineOf.getBaseline(lineOf.getWidth(), lineOf.getHeight());
                if (b >= 0) floor = SwingUtilities.convertPoint(lineOf, 0, b, this).y;
            }
            if (floor < 0) {
                double digits = -getFont().createGlyphVector(g2.getFontRenderContext(), text).getVisualBounds().getY();
                int h = (int) Math.round(Math.max(digits, visible.height));
                floor = (getHeight() - h) / 2 + h;
            }
            g2.setFont(getFont());
            g2.setColor(new Color(0xF2ECDC));
            g2.drawString(text, in.left, floor);
            int x = in.left + fm.stringWidth(text) + GAP - visible.x;
            grave.paintIcon(this, g2, x, floor - (visible.y + visible.height));
            g2.dispose();
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
