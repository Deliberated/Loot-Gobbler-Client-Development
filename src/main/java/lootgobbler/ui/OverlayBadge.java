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
 * the run so far | the current lantern phase's beats in the lantern's range (counting up live, kept with "FINAL" when
 * the phase ends, 0 again when the next phase starts). Split by thin lines. One width for the whole dungeon (room
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
        add(portal);
        add(rankSep);
        add(rank);
        add(amuletSep);
        add(amulet);
        add(totalSep);
        add(total);
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
            phase.setText("14");
            phase.setForeground(HELD);
        }
        show(totalSep, total, mv);
        show(phaseSep, phase, mv);
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
        int w = in.left + in.right + GAP + ICON + sep + b.stringWidth("#00/00") + sep + AMULET;
        if (mv) {
            JLabel t = new JLabel(totalText(888));
            t.setFont(total.getFont());
            int flame = phase.getIcon() == null ? 0 : phase.getIcon().getIconWidth() + phase.getIconTextGap();
            w += sep + t.getPreferredSize().width + sep + flame + b.stringWidth("88") + GAP + f.stringWidth("FINAL");
        }
        return new Dimension(Math.max(d.width, w), Math.max(d.height, ICON + in.top + in.bottom));
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
        boolean held = false;
        if (mv) {
            List<DungeonVisits.MvPhase> phases = visit.mvPhases == null ? List.of() : visit.mvPhases;
            int flames = 0;
            for (DungeonVisits.MvPhase ph : phases) flames += ph.flames;
            total.setText(totalText(flames));
            DungeonVisits.MvPhase now = phases.isEmpty() ? null : phases.get(phases.size() - 1);
            int beats = now != null && now.lantern ? now.beats : 0;
            held = now != null && now.lantern && now.durationMs > 0;   // the phase is over: its number stays
            phase.setText(String.valueOf(beats));
            phase.setForeground(beats == 0 && !held ? ZERO : held ? HELD : TEXT);
        }
        fin.setVisible(held);

        String state = visit.dungeon + '|' + r + '|' + ami + '|' + total.getText() + '|' + phase.getText() + '|' + held;
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
