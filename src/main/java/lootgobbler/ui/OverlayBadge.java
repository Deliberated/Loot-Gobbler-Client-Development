package lootgobbler.ui;

import lootgobbler.AmuletSaves;
import lootgobbler.Amulets;
import lootgobbler.DpsHistory;
import lootgobbler.DpsHistory.Boss;
import lootgobbler.DpsHistory.PlayerRow;
import lootgobbler.DpsHistory.Run;
import lootgobbler.DungeonVisits;
import lootgobbler.MoonlightLog;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The overlay's dungeon box, a short version of the title bar's: the portal | your rank "#x/y" (once you hit the
 * main boss) | the amulet (while it charges from a save in this dungeon), and in Moonlight Village | the flames of
 * the fight you are in (the three dancers together, or Umi) with the loot tier they reach (a chest in the
 * enchant rarity color of the tier)
 * and the flames the next tier needs | the latest phase's flames as under DPS ("..." while it runs, kept with "FINAL" when
 * the phase ends, "..." again when the next phase starts). Split by thin lines. One width for the whole dungeon (room
 * for the most it can show), one for normal dungeons and a wider one for Moonlight Village. Hidden outside dungeons.
 */
final class OverlayBadge extends JPanel {
    private static final int ICON = 26, AMULET = 22;
    private static final int FLAME = 20586;   // MV Spirit Projectile: a flame
    private static final int GAP = 10;
    private static final Color TEXT = new Color(0xF2ECDC), HELD = new Color(0xC9BFE6),
            ZERO = new Color(242, 236, 220, 115), LABEL = new Color(242, 236, 220, 155);
    private final Map<String, Icon> icons = new HashMap<>();
    private final Map<String, Color> tints = new HashMap<>();
    private final JLabel portal = new JLabel();
    private final JLabel rank = bold();
    private final JLabel amulet = new JLabel();
    private final JLabel total = new JLabel();
    private final JLabel phase = bold();
    private final JLabel tier = new JLabel();   // the loot tier the total reaches, as a chest in its color
    private final JLabel next = new JLabel();   // the flames the next tier still needs ("+4 / to T3"), or MAX
    private final JLabel fin = new JLabel("FINAL");
    private final Sep rankSep = new Sep(), amuletSep = new Sep(), totalSep = new Sep(), phaseSep = new Sep();
    private final Timer poll = new Timer(300, e -> refresh());   // the lantern count changes without an event
    private Color tint = new Color(0x3A2C08);
    private boolean mv;
    private String shown = "";

    /** The live box (sample false), or one with sample values from Moonlight Village, everything showing. */
    OverlayBadge(boolean sample) {
        this(sample ? MoonlightLog.DUNGEON : null);
    }

    /** sampleDungeon: a box with sample values for that dungeon, everything showing; null: the live box. */
    OverlayBadge(String sampleDungeon) {
        super(new FlowLayout(FlowLayout.LEFT, GAP, 0));
        setOpaque(false);
        setBorder(new EmptyBorder(4, 2, 4, 12));   // with the layout's gap: the portal 12 px from the left edge
        total.setForeground(TEXT);
        fin.setForeground(HELD);
        fin.setFont(fin.getFont().deriveFont(Font.BOLD, fin.getFont().getSize2D() - 2.5f));
        phase.setIconTextGap(4);
        // the rank always as wide as "#50/50", centered, so the amulet after it keeps its place
        FontMetrics rm = rank.getFontMetrics(rank.getFont());
        rank.setPreferredSize(new Dimension(rm.stringWidth("#50/50") + 2, rm.getHeight()));
        rank.setHorizontalAlignment(SwingConstants.CENTER);
        next.setForeground(new Color(0xC8C2B4));
        next.setFont(next.getFont().deriveFont(Font.BOLD, next.getFont().getSize2D() - 2.5f));
        FontMetrics nm = next.getFontMetrics(next.getFont());   // two short lines, always as wide as the widest
        next.setPreferredSize(new Dimension(nm.stringWidth("to T4") + 4, 2 * nm.getHeight()));
        add(portal);
        add(rankSep);
        add(rank);
        add(amuletSep);
        add(amulet);
        add(totalSep);
        add(total);
        add(tier);
        add(next);
        add(phaseSep);
        add(phase);
        add(fin);
        if (sampleDungeon != null) {
            fillSample(sampleDungeon);
            return;
        }
        setVisible(false);
        DpsHistory.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        DungeonVisits.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        AmuletSaves.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    private void fillSample(String dungeon) {
        Icon p = ActiveDungeonBadge.fit(ProfileView.dungeonIcon(dungeon, 64), ICON);
        portal.setIcon(p);
        tint = ActiveDungeonBadge.tint(p);
        mv = MoonlightLog.DUNGEON.equals(dungeon);
        rank.setText(mv ? "#2/6" : "#3/8");
        show(rankSep, rank, true);
        int id = Amulets.restorationId();
        if (id >= 0) amulet.setIcon(ActiveDungeonBadge.fit(CharactersView.sprite(id, 40), AMULET));
        show(amuletSep, amulet, true);
        if (mv) {
            phase.setIcon(ActiveDungeonBadge.fit(CharactersView.sprite(FLAME, 40), phase.getFontMetrics(phase.getFont()).getAscent()));
            total.setText(totalText(54));
            showTier(54, true);
            phase.setText("6");
            phase.setForeground(HELD);
        }
        show(totalSep, total, mv);
        show(phaseSep, phase, mv);
        tier.setVisible(mv);
        next.setVisible(mv);
        fin.setVisible(mv);
        setVisible(true);
    }

    private static JLabel bold() {
        JLabel l = new JLabel();
        l.setForeground(TEXT);
        l.setFont(l.getFont().deriveFont(Font.BOLD, l.getFont().getSize2D() + 1));
        return l;
    }

    /** One width per kind of dungeon: what it is with everything showing at its widest. */
    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        Insets in = getInsets();
        FontMetrics b = getFontMetrics(rank.getFont()), f = getFontMetrics(fin.getFont());
        int sep = 2 * GAP + Sep.W;
        int w = in.left + in.right + GAP + ICON + sep + b.stringWidth("#50/50") + sep + AMULET;
        if (mv) {
            JLabel t = new JLabel(totalText(888));
            t.setFont(total.getFont());
            int flame = phase.getIcon() == null ? 0 : phase.getIcon().getIconWidth() + phase.getIconTextGap();
            w += sep + t.getPreferredSize().width + GAP + Chest.W + GAP + next.getPreferredSize().width
                    + sep + flame + b.stringWidth("88") + GAP + f.stringWidth("FINAL");
        }
        return new Dimension(Math.max(d.width, w), Math.max(d.height, ICON + in.top + in.bottom));
    }

    /** The lowest total of each loot tier (1 to 4): the three dancers together, and Umi. */
    private static final int[] DANCER_TIERS = {2, 40, 58, 78}, UMI_TIERS = {2, 24, 36, 48};
    /** No tier yet: grey; tiers 1 to 4 in the enchant rarities' colors (uncommon, rare, legendary, divine). */
    private static final Color[] TIER_COLORS = {new Color(0x5D5547), lootgobbler.enchant.Rarity.UNCOMMON.color,
            lootgobbler.enchant.Rarity.RARE.color, lootgobbler.enchant.Rarity.LEGENDARY.color,
            lootgobbler.enchant.Rarity.DIVINE.color};

    /** The tier these flames reach (0: none yet), a total between two ranges counting as the lower tier. */
    static int tierOf(int flames, boolean dancers) {
        int t = 0;
        for (int start : dancers ? DANCER_TIERS : UMI_TIERS) if (flames >= start) t++;
        return t;
    }

    /** The chest in the tier's color and what the next tier still needs. */
    private void showTier(int flames, boolean dancers) {
        int[] starts = dancers ? DANCER_TIERS : UMI_TIERS;
        int t = tierOf(flames, dancers);
        if (!(tier.getIcon() instanceof Chest c) || c.tier != t) tier.setIcon(new Chest(t, TIER_COLORS[t]));
        next.setText(t >= starts.length ? "<html><b style='color:#f2ecdc'>MAX</b></html>"
                : "<html><b style='color:#f2ecdc'>+" + (starts[t] - flames) + "</b><br>to T" + (t + 1) + "</html>");
    }

    /** A loot chest in its tier's color: lid, body, a dark band and the lock. */
    private record Chest(int tier, Color color) implements Icon {
        static final int W = 22, H = 20;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.translate(x, y);
            Color ink = new Color(0x140E08);
            java.awt.geom.Path2D lid = new java.awt.geom.Path2D.Double();
            lid.moveTo(2, 10);
            lid.curveTo(2, 2, 20, 2, 20, 10);
            lid.closePath();
            g2.setColor(color);
            g2.fill(lid);
            g2.fillRect(2, 10, 18, 9);
            g2.setColor(ink);
            g2.setStroke(new BasicStroke(1.2f));
            g2.draw(lid);
            g2.drawRect(2, 10, 18, 9);
            g2.setColor(new Color(20, 14, 8, 115));
            g2.fillRect(2, 9, 18, 2);
            g2.setColor(ink);
            g2.fillRoundRect(9, 9, 4, 5, 2, 2);
            g2.setColor(new Color(0xF2ECDC));
            g2.fillRect(10, 10, 2, 3);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return W;
        }

        @Override
        public int getIconHeight() {
            return H;
        }
    }

    private static String totalText(int flames) {
        return "<html><span style='font-size:85%;color:#c8c2b4'>TOTAL</span>&nbsp;<b>" + flames + "</b></html>";
    }

    void refresh() {
        DungeonVisits.Visit visit = DungeonVisits.get().current();
        if (visit == null) {
            poll.stop();
            if (isVisible()) {
                setVisible(false);
                revalidateParent();
            }
            return;
        }
        Run run = DpsHistory.get().live();
        Boss boss = run == null || run.guildHall || !run.dungeon.equals(visit.dungeon) ? null : run.mainBoss();
        PlayerRow me = boss == null ? null : boss.me();
        Icon p = icons.computeIfAbsent(visit.dungeon, d -> ActiveDungeonBadge.fit(ProfileView.dungeonIcon(d, 64), ICON));
        portal.setIcon(p);
        tint = tints.computeIfAbsent(visit.dungeon, d -> ActiveDungeonBadge.tint(p));
        mv = MoonlightLog.DUNGEON.equals(visit.dungeon);
        if (mv && phase.getIcon() == null) {
            phase.setIcon(ActiveDungeonBadge.fit(CharactersView.sprite(FLAME, 40), phase.getFontMetrics(phase.getFont()).getAscent()));
        }

        String r = me == null ? null : "#" + boss.rankOf(me) + "/" + boss.players.size();
        show(rankSep, rank, r != null);
        if (r != null) rank.setText(r);

        // the amulet: saved you in this dungeon and still charging
        long last = AmuletSaves.get().lastSave();
        boolean ami = last >= visit.start && AmuletSaves.get().charging();
        if (ami && amulet.getIcon() == null) {
            int id = Amulets.restorationId();
            if (id >= 0) amulet.setIcon(ActiveDungeonBadge.fit(CharactersView.sprite(id, 40), AMULET));
        }
        show(amuletSep, amulet, ami);

        show(totalSep, total, mv);
        show(phaseSep, phase, mv);
        tier.setVisible(mv);
        next.setVisible(mv);
        boolean held = false;
        if (mv) {
            List<DungeonVisits.MvPhase> phases = visit.mvPhases == null ? List.of() : visit.mvPhases;
            // the total of the fight you are in: the three dancers' phases (88 flames at most), or once Umi starts,
            // only hers (56 at most); a phase whose boss is not known yet goes with the fight before it
            Boolean dancers = null;
            for (int i = phases.size() - 1; i >= 0 && dancers == null; i--) {
                if (phases.get(i).boss != 0) dancers = MoonlightLog.DANCERS.contains(phases.get(i).boss);
            }
            int flames = 0;
            for (DungeonVisits.MvPhase ph : phases) {
                boolean ofDancers = ph.boss == 0 ? dancers == null || dancers : MoonlightLog.DANCERS.contains(ph.boss);
                if (dancers == null || ofDancers == dancers) flames += ph.flames;
            }
            total.setText(totalText(flames));
            showTier(flames, dancers == null || dancers);
            DungeonVisits.MvPhase now = phases.isEmpty() ? null : phases.get(phases.size() - 1);
            // as under DPS: a phase's flames are the game's, given when it ends ("..." until then); they stay
            // until the next phase starts
            held = now != null && now.durationMs > 0;
            phase.setText(now == null ? "0" : held ? String.valueOf(now.flames) : "...");
            phase.setForeground(held ? HELD : ZERO);
        }
        fin.setVisible(held);

        String state = visit.dungeon + '|' + r + '|' + ami + '|' + total.getText() + '|' + next.getText() + '|' + phase.getText() + '|' + held;
        if (!poll.isRunning()) poll.start();
        if (!isVisible()) {
            setVisible(true);
            shown = "";
        }
        if (!state.equals(shown)) {
            shown = state;
            revalidateParent();
        }
    }

    private static void show(Sep sep, JComponent item, boolean on) {
        sep.setVisible(on);
        item.setVisible(on);
    }

    private void revalidateParent() {
        revalidate();
        if (getParent() != null) getParent().revalidate();
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth() - 1, h = getHeight() - 1;
        g2.setPaint(new GradientPaint(0, 0, ActiveDungeonBadge.shade(tint, 1.2f), 0, h, ActiveDungeonBadge.shade(tint, 0.8f)));
        g2.fillRoundRect(0, 0, w, h, 10, 10);
        g2.setColor(ActiveDungeonBadge.shade(tint, 1.9f));
        g2.drawRoundRect(0, 0, w, h, 10, 10);
        g2.dispose();
    }

    /** A thin upright line between two items. */
    private static final class Sep extends JComponent {
        static final int W = 1, H = 18;

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(W, H);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(new Color(242, 236, 220, 64));
            g.fillRect(0, (getHeight() - H) / 2, W, H);
        }
    }
}
