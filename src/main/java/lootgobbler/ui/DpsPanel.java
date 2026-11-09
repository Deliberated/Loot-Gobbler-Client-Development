package lootgobbler.ui;

import assets.ImageBuffer;
import lootgobbler.DpsHistory;
import lootgobbler.DungeonVisits;
import lootgobbler.ItemDb;
import lootgobbler.MoonlightLog;
import lootgobbler.DpsHistory.Boss;
import lootgobbler.DpsHistory.PlayerRow;
import lootgobbler.DpsHistory.Run;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * DPS Logger tab: live or logged run on the left (one collapsible damage table per boss),
 * past dungeons on the right.
 */
public class DpsPanel extends JPanel {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);
    private static final int DUNGEON_ICON = 34;
    private static final Map<String, Icon> DUNGEON_ICONS = new HashMap<>();   // dungeon name ("" = guild hall) -> portal
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    private static final Map<String, Color> CLASS_COLORS = Map.ofEntries(
            Map.entry("Rogue", new Color(0x6E7B8B)), Map.entry("Archer", new Color(0x5FA343)),
            Map.entry("Wizard", new Color(0xB36BE0)), Map.entry("Priest", new Color(0xE0C36B)),
            Map.entry("Warrior", new Color(0xC0503C)), Map.entry("Knight", new Color(0xE88A3A)),
            Map.entry("Paladin", new Color(0xF2C230)), Map.entry("Assassin", new Color(0x49A07A)),
            Map.entry("Necromancer", new Color(0x8C5AD6)), Map.entry("Huntress", new Color(0xE0679C)),
            Map.entry("Mystic", new Color(0x3FC4D6)), Map.entry("Trickster", new Color(0xE85AB4)),
            Map.entry("Sorcerer", new Color(0x4C8FE0)), Map.entry("Ninja", new Color(0x7A8899)),
            Map.entry("Samurai", new Color(0xD64545)), Map.entry("Bard", new Color(0xD9B44A)),
            Map.entry("Summoner", new Color(0x57B894)), Map.entry("Kensei", new Color(0xB5543A)),
            Map.entry("Druid", new Color(0x6B6B48)));

    private final JLabel title = new JLabel("NO DUNGEON");
    private final JLabel yourDps = big("0"), rank = big("-");
    private final JToggleButton liveButton = new JToggleButton("LIVE", true);
    private final JPanel bosses = column();
    private final JPanel past = column();
    private final Set<String> expanded = new HashSet<>();   // toggled boss blocks (see bossBlock)
    private Run selected;   // null = live
    // LIVE on: between runs the last one stays in view (its last boss open) until the next run's first damage
    private boolean hold = !"false".equals(util.PropertiesManager.getProperty("dpsHoldLast"));
    private Run held;   // the run held in view now, or null
    private long heldOpened;   // the held run whose last boss was opened
    private static final String[] SLOT_NAMES = {"Weapon", "Ability", "Armor", "Ring"};

    public DpsPanel() {
        super(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        liveButton.setFocusable(false);
        liveButton.setToolTipText("<html>On: follow the dungeon you are in, and keep the last run open until "
                + "the next one deals damage.<br>Off: show only what you click.</html>");
        liveButton.addActionListener(e -> {
            // on (or back from a past run): follow live and hold; on -> off: no holding, the view stays where it is
            boolean on = selected != null || !hold;
            if (!on && held != null) selected = held;   // what was held stays, as if clicked
            else if (on) selected = null;
            hold = on;
            util.PropertiesManager.setProperties("dpsHoldLast", String.valueOf(on));
            refresh();
        });
        add(header(), BorderLayout.NORTH);

        Section bossSection = new Section("Boss Damage", Palette.GOLD, scroll(bosses))
                .actions(button("Copy all", null, this::copyAll));
        Section pastSection = new Section("Run History", Palette.BLUE, scroll(past))
                .actions(button("Clear all", "danger", this::clearAll));
        add(new SideSplit(bossSection, pastSection, "dps", 400, 220), BorderLayout.CENTER);   // drag the gap to resize

        DpsHistory.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        lootgobbler.Friends.get().onChange(() -> SwingUtilities.invokeLater(this::repaint));   // their labels
        Guildmates.get().onChange(() -> SwingUtilities.invokeLater(this::repaint));
        refresh();
    }

    private JPanel header() {
        title.putClientProperty("FlatLaf.styleClass", "small");
        title.setForeground(Palette.GOLD);
        JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 18, 0));
        stats.setOpaque(false);
        stats.add(stat("Your DPS", yourDps));
        stats.add(stat("Rank", rank));
        stats.add(liveButton);

        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(Section.color("TextArea.background", getBackground()));
                setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(Section.color("Component.borderColor", Color.GRAY)), new EmptyBorder(8, 12, 8, 12)));
            }
        };
        p.add(title, BorderLayout.WEST);
        p.add(stats, BorderLayout.EAST);
        return p;
    }

    private void refresh() {
        List<Run> runs = DpsHistory.get().runs();
        if (selected != null && !runs.contains(selected)) selected = null;
        liveButton.setSelected(selected == null && hold);
        Run live = DpsHistory.get().live();
        // holding: no damage in the new run yet, so the last run stays
        held = selected == null && hold && (live == null || live.bosses.isEmpty()) && !runs.isEmpty() ? runs.get(0) : null;
        if (held != null && heldOpened != held.start) {
            heldOpened = held.start;
            openLatest(held);
        }
        renderRun(selected != null ? selected : held != null ? held : live);
        renderPast(live, runs);
    }

    private void renderRun(Run run) {
        bosses.removeAll();
        if (run == null || run.bosses.isEmpty()) {
            title.setText(selected == null ? "NO DUNGEON" : "NO BOSSES");
            yourDps.setText("0");
            rank.setText("-");
            JLabel empty = new JLabel("No bosses fought yet.");
            empty.setForeground(Palette.MUTED);
            empty.setBorder(new EmptyBorder(14, 14, 14, 14));
            bosses.add(empty);
        } else {
            title.setText(run.dungeon.toUpperCase(Locale.ROOT) + (run == held ? "    (LAST RUN)"
                    : selected == null ? "    (LIVE)" : "    (LOGGED RUN)"));
            // guild hall: your latest loadout, ranked against your other loadouts
            Boss main = run.guildHall ? run.bosses.get(run.bosses.size() - 1) : run.mainBoss();
            PlayerRow me = main == null ? null : main.me();
            yourDps.setText(me == null ? "0" : NUM.format(Math.round(main.dps(me))));
            rank.setText(me == null ? "-" : String.valueOf(run.guildHall ? loadoutRank(run, main) : main.rankOf(me)));
            JComponent interim = run.guildHall ? null : dancersBar(visitOf(run, run == DpsHistory.get().live()));
            for (int i = run.bosses.size() - 1; i >= 0; i--) {   // the latest boss on top
                // Moonlight Village: the three dancers' subtotal above them (under Umi)
                if (interim != null && MoonlightLog.DANCERS.contains(run.bosses.get(i).objectType)) {
                    bosses.add(interim);
                    bosses.add(Box.createVerticalStrut(6));
                    interim = null;
                }
                bosses.add(bossBlock(run, run.bosses.get(i), i));
                bosses.add(Box.createVerticalStrut(6));
            }
        }
        Clicks.layoutNow(bosses);   // the new blocks take clicks at once
        bosses.repaint();
    }

    private JPanel bossBlock(Run run, Boss boss, int index) {
        // The live run's start is recomputed every second, so live blocks are keyed by the boss alone. Live bosses
        // start open, so their damage shows from the first hit; logged runs start collapsed.
        boolean live = run == DpsHistory.get().live();
        String key = live ? "live/" + boss.id + "/" + boss.objectType
                : run.start + "/" + (boss.id != 0 ? boss.id : index);
        boolean open = live != expanded.contains(key);   // for live blocks the set holds the collapsed ones

        JLabel name = new JLabel((open ? "[-]  " : "[+]  ") + boss.name + "     " + duration(boss.fightMs)
                + (run.guildHall ? "" : "  ·  " + boss.players.size() + " players"), bossIcon(boss.objectType),
                SwingConstants.LEFT);
        name.setIconTextGap(10);
        name.setFont(name.getFont().deriveFont(18f));
        name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(name, () -> {
            if (!expanded.remove(key)) expanded.add(key);
            refresh();
        });

        JPanel head = row(true);
        if (run.guildHall) {   // the loadout of this block, right after its name
            JLabel items = new JLabel(loadoutIcon(boss.loadout));
            items.setToolTipText("<html>" + PlayerRenderer.escape(loadoutText(boss.loadout, "\n")).replace("\n", "<br>") + "</html>");
            JPanel title = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            title.setOpaque(false);
            title.add(name);
            title.add(Box.createHorizontalStrut(16));
            title.add(items);
            JLabel total = new JLabel(NUM.format(boss.totalDamage()) + " total damage");
            total.setFont(total.getFont().deriveFont(18f));
            total.setForeground(Palette.GOLD);
            total.setToolTipText("Your damage with this loadout");
            title.add(Box.createHorizontalStrut(16));
            title.add(total);
            head.add(title, BorderLayout.CENTER);
        } else {
            head.add(name, BorderLayout.CENTER);
        }
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setOpaque(false);
        JLabel place = placeLabel(run, boss);
        if (place != null) {
            right.add(place);
            right.add(Box.createHorizontalStrut(16));
        }
        right.add(button("Copy log", null, () -> copy(bossText(run, boss))));
        head.add(right, BorderLayout.EAST);

        JPanel block = new JPanel(new BorderLayout());
        block.setOpaque(false);
        block.setAlignmentX(LEFT_ALIGNMENT);
        block.add(head, BorderLayout.NORTH);
        if (open) {
            JComponent table = run.guildHall ? rangeTable(boss) : bossTable(boss);
            DungeonVisits.Visit visit = run.guildHall ? null : visitOf(run, live);
            List<DungeonVisits.MvPhase> phases = phasesOf(visit, boss.objectType);
            if (!phases.isEmpty()) {   // Moonlight Village: the boss's phases below its damage table
                JPanel both = new JPanel(new BorderLayout(0, 6));
                both.setOpaque(false);
                both.add(table, BorderLayout.NORTH);
                both.add(phaseTable(visit, phases), BorderLayout.CENTER);
                table = both;
            }
            block.add(table, BorderLayout.CENTER);
        }
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, block.getPreferredSize().height));
        return block;
    }

    private JComponent bossTable(Boss boss) {
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"#", "Player", "Class", "DPS", "DMG", "DMG Taken", "%"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        double maxShare = boss.players.isEmpty() ? 1 : boss.share(boss.players.get(0));
        for (int i = 0; i < boss.players.size(); i++) {
            PlayerRow p = boss.players.get(i);
            model.addRow(new Object[]{i + 1, p, p, NUM.format(Math.round(boss.dps(p))), NUM.format(p.damage),
                    NUM.format(p.taken), String.format("%.1f%%", boss.share(p))});
        }
        JTable table = new JTable(model);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new PlayerRenderer(boss, maxShare, 6));
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(6).setMaxWidth(70);
        return tablePanel(table);
    }

    /** The dungeon visit a run belongs to: the one in progress for the live run, else the one linked to it. */
    private static DungeonVisits.Visit visitOf(Run run, boolean live) {
        if (live) {
            DungeonVisits.Visit v = DungeonVisits.get().current();
            return v != null && run.dungeon.equals(v.dungeon) ? v : null;
        }
        for (DungeonVisits.Visit v : DungeonVisits.get().allVisits()) if (v.runStart == run.start) return v;
        return null;
    }

    /** A Moonlight Village boss's phases in this visit, in order; empty for any other boss or dungeon. */
    private static List<DungeonVisits.MvPhase> phasesOf(DungeonVisits.Visit v, int boss) {
        if (v == null || v.mvPhases == null) return List.of();
        return v.mvPhases.stream().filter(p -> p.boss == boss).toList();
    }

    /**
     * Moonlight Village: the interim result of the three dancers (Kaguya, Genji, Miko) before Umi, their phases
     * summed up: time, your damage and DPS, your share at the lanterns and the flames, also per dancer. Null when
     * no dancer phase was seen.
     */
    private static JComponent dancersBar(DungeonVisits.Visit v) {
        if (v == null || v.mvPhases == null) return null;
        List<DungeonVisits.MvPhase> phases = v.mvPhases.stream().filter(p -> MoonlightLog.DANCERS.contains(p.boss)).toList();
        if (phases.isEmpty()) return null;
        long now = System.currentTimeMillis(), totalMs = 0, totalDmg = 0, lanternMs = 0, rangeMs = 0;
        int flames = 0;
        Map<Integer, Integer> perBoss = new LinkedHashMap<>();   // in the order fought
        for (DungeonVisits.MvPhase p : phases) {
            long ms = p.duration(now, v);
            totalMs += ms;
            totalDmg += dealt(v, p.startMs, p.startMs + ms);
            lanternMs += p.lanternMs;
            rangeMs += p.rangeMs;
            flames += p.flames;
            perBoss.merge(p.boss, p.flames, Integer::sum);
        }
        JLabel title = new JLabel("Dancers interim");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        title.setForeground(Palette.GOLD);
        String lantern = lanternMs > 0 ? Math.round(100.0 * rangeMs / lanternMs) + "%" : "-";
        JLabel stats = new JLabel(duration(totalMs) + "   ·   " + NUM.format(totalDmg) + " DMG   ·   "
                + NUM.format(Math.round(totalDmg * 1000.0 / Math.max(1000, totalMs))) + " DPS   ·   at lantern "
                + lantern + "   ·   " + flames + " flames");
        stats.setFont(stats.getFont().deriveFont(15f));
        StringBuilder tip = new StringBuilder("<html>Kaguya, Genji and Miko together, before Umi");
        for (Map.Entry<Integer, Integer> e : perBoss.entrySet())
            tip.append("<br>").append(PlayerRenderer.escape(bossName(e.getKey()))).append(": ").append(e.getValue()).append(" flames");
        JPanel bar = new JPanel(new BorderLayout(16, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(Palette.GOLD);
                g.fillRect(0, 0, 3, getHeight());   // a gold edge, set apart from the boss blocks
            }
        };
        bar.setBackground(UIManager.getColor("Panel.background").brighter());
        bar.setBorder(new EmptyBorder(8, 14, 8, 14));
        bar.setAlignmentX(LEFT_ALIGNMENT);
        bar.setToolTipText(tip.append("</html>").toString());
        bar.add(title, BorderLayout.WEST);
        bar.add(stats, BorderLayout.EAST);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, bar.getPreferredSize().height));
        return bar;
    }

    private static String bossName(int type) {
        return switch (type) {
            case 20450 -> "Sage Genji";
            case 20451 -> "Dancer Miko";
            case 20452 -> "Drummer Kaguya";
            default -> String.valueOf(type);
        };
    }

    private static final int PHASE_LANTERN = 6, PHASE_FLAMES = 7;   // columns of the phase table

    /**
     * Moonlight Village: one row per phase of the boss (its song, damage or lantern, how long, your damage and DPS
     * in it, how long you stayed within 3.5 tiles of the lantern, and the flames it scored), then the totals.
     */
    private static JComponent phaseTable(DungeonVisits.Visit v, List<DungeonVisits.MvPhase> phases) {
        DefaultTableModel model = new DefaultTableModel(
                new Object[]{"Phase", "Song", "Type", "Time", "Your DMG", "Your DPS", "At lantern", "Flames"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        long now = System.currentTimeMillis(), totalMs = 0, totalDmg = 0, lanternMs = 0, rangeMs = 0;
        int flames = 0;
        for (int i = 0; i < phases.size(); i++) {
            DungeonVisits.MvPhase p = phases.get(i);
            long ms = p.duration(now, v), dmg = dealt(v, p.startMs, p.startMs + ms);
            boolean last = p.lantern && i > 0 && phases.get(i - 1).lantern;   // two lanterns in a row: the final one
            model.addRow(new Object[]{i + 1, p.title, p.lantern ? (last ? "Lantern (final)" : "Lantern") : "Damage",
                    duration(ms), NUM.format(dmg), NUM.format(Math.round(dmg * 1000.0 / Math.max(1000, ms))),
                    p.lantern && p.lanternMs > 0 ? Math.round(100.0 * p.rangeMs / p.lanternMs) + "%" : "-",
                    p.durationMs > 0 ? String.valueOf(p.flames) : "..."});
            totalMs += ms;
            totalDmg += dmg;
            lanternMs += p.lanternMs;
            rangeMs += p.rangeMs;
            flames += p.flames;
        }
        model.addRow(new Object[]{"Total", "", "", duration(totalMs), NUM.format(totalDmg),
                NUM.format(Math.round(totalDmg * 1000.0 / Math.max(1000, totalMs))),
                lanternMs > 0 ? Math.round(100.0 * rangeMs / lanternMs) + "%" : "-", String.valueOf(flames)});
        int totalRow = phases.size();

        JTable table = new JTable(model);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(24);
        table.getTableHeader().setReorderingAllowed(false);
        table.setToolTipText("At lantern: your time within 3.5 tiles of the lantern while it was there. "
                + "Flames: the score the game gave for the phase.");
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, value, sel, false, row, column);
                setBorder(new EmptyBorder(0, 8, 0, 8));
                if (!sel) {
                    setBackground(row % 2 == 1 && UIManager.getColor("Table.alternateRowColor") != null
                            ? UIManager.getColor("Table.alternateRowColor") : t.getBackground());
                    setForeground(t.getForeground());
                    if (column == PHASE_FLAMES) setForeground(Palette.GOLD);
                    if (column == PHASE_LANTERN && value instanceof String s && s.endsWith("%")) {
                        int pct = Integer.parseInt(s.substring(0, s.length() - 1));
                        setForeground(pct >= 90 ? Palette.GREEN : pct < 60 ? new Color(0xBE332D) : t.getForeground());
                    }
                    if (row == totalRow) setForeground(column == PHASE_FLAMES ? Palette.GOLD : Section.color("LootGobbler.inkSoft", Palette.MUTED));
                }
                setFont(t.getFont().deriveFont(row == totalRow ? Font.BOLD : Font.PLAIN));
                return this;
            }
        });
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(PHASE_FLAMES).setMaxWidth(70);
        return tablePanel(table);
    }

    /** Your damage to enemies between two times after entering (the visit keeps it per second). */
    private static long dealt(DungeonVisits.Visit v, long fromMs, long toMs) {
        if (v.dealt == null) return 0;
        long sum = 0;
        for (int s = (int) (fromMs / 1000); s < Math.min(v.dealt.length, (toMs + 999) / 1000); s++) sum += v.dealt[s];
        return sum;
    }

    /**
     * Guild hall: one loadout's block with your damage; DPS over the time that loadout was used.
     */
    private JComponent rangeTable(Boss block) {
        DefaultTableModel model = new DefaultTableModel(new Object[]{"#", "Name", "Class", "DPS", "DMG", "%"}, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
        double maxShare = block.players.stream().mapToDouble(block::share).max().orElse(1);
        for (int i = 0; i < block.players.size(); i++) {
            PlayerRow p = block.players.get(i);
            model.addRow(new Object[]{i + 1, p, p, NUM.format(Math.round(block.dps(p))), NUM.format(p.damage),
                    String.format("%.1f%%", block.share(p))});
        }
        JTable table = new JTable(model);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(26);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new PlayerRenderer(block, maxShare, 5));
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(5).setMaxWidth(70);
        return tablePanel(table);
    }

    /**
     * "#x of y": your damage rank on this boss among its players (guild hall: this loadout among your loadouts).
     * null if you did no damage to it.
     */
    private static JLabel placeLabel(Run run, Boss boss) {
        PlayerRow me = boss.me();
        if (me == null) return null;
        int place = run.guildHall ? loadoutRank(run, boss) : boss.rankOf(me);
        int of = run.guildHall ? (int) run.bosses.stream().filter(b -> b.me() != null).count() : boss.players.size();
        JLabel l = new JLabel("#" + place + " of " + of);
        l.setFont(l.getFont().deriveFont(18f));
        l.setForeground(place == 1 ? Palette.GOLD : Section.color("LootGobbler.inkSoft", Palette.MUTED));
        l.setToolTipText(run.guildHall ? "This loadout's place among your loadouts, by DPS" : "Your place by damage on this boss");
        return l;
    }

    /**
     * Place of this loadout among the run's loadouts by your DPS.
     */
    private static int loadoutRank(Run run, Boss block) {
        double dps = block.dps(block.me());
        return 1 + (int) run.bosses.stream().filter(b -> b.me() != null && b.dps(b.me()) > dps).count();
    }

    private static JPanel tablePanel(JTable table) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(table.getTableHeader(), BorderLayout.NORTH);
        p.add(table, BorderLayout.CENTER);
        return p;
    }

    /**
     * The run in progress on top (from the first boss hit on), then the saved runs.
     */
    private void renderPast(Run live, List<Run> runs) {
        past.removeAll();
        if (live != null) past.add(historyRow(live, true));
        if (live == null && runs.isEmpty()) {
            JLabel empty = new JLabel("Your dungeon runs appear here.");
            empty.setForeground(Palette.MUTED);
            empty.setBorder(new EmptyBorder(10, 10, 10, 10));
            past.add(empty);
        }
        for (Run r : runs) past.add(historyRow(r, false));
        Clicks.layoutNow(past);   // the new rows take clicks at once
        past.repaint();
    }

    private JPanel historyRow(Run r, boolean current) {
        boolean isSelected = current ? selected == null && held == null : r == selected || r == held;
        Run target = current ? null : r;   // selecting the current run means following it live
        JLabel name = new JLabel(r.dungeon);
        name.setFont(name.getFont().deriveFont(18f));
        if (isSelected) name.setForeground(Palette.GOLD);
        JLabel when = new JLabel(WHEN.format(Instant.ofEpochMilli(r.start).atZone(ZoneId.systemDefault()))
                + "  ·  " + duration(r.durationMs));
        when.setForeground(isSelected ? Palette.GOLD : Palette.MUTED);
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(name);
        text.add(when);

        JPanel row = row(true);
        if (isSelected) {
            row.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Palette.GOLD, 2), new EmptyBorder(4, 8, 4, 6)));
        }
        JLabel icon = new JLabel(dungeonIcon(r));
        icon.setPreferredSize(new Dimension(DUNGEON_ICON, DUNGEON_ICON));
        row.add(icon, BorderLayout.WEST);
        row.add(text, BorderLayout.CENTER);
        // your rank (the whole row opens the run), and delete once the run is finished and saved
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        JLabel rank = runRank(r);
        if (rank != null) buttons.add(rank);
        if (!current) buttons.add(button("x", "danger", () -> {   // only marked as removed: no longer shown
            if (selected == r) selected = null;
            DpsHistory.get().remove(r);
        }));
        if (buttons.getComponentCount() > 0) row.add(buttons, BorderLayout.EAST);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onPress(row, () -> select(target));   // on press: the list is rebuilt every second during a run
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /**
     * Your place by damage on the run's main boss (the one with the most life): "#x of y"; "no boss fought" if
     * there is none or you did not hit it. Null for guild hall visits.
     */
    static JLabel runRank(Run r) {
        if (r.guildHall) return null;
        Boss boss = r.mainBoss();
        PlayerRow me = boss == null ? null : boss.me();
        if (me == null) {
            JLabel none = new JLabel("no boss fought");
            none.setForeground(Palette.MUTED);
            return none;
        }
        int place = boss.rankOf(me);
        JLabel l = new JLabel("#" + place + " of " + boss.players.size());
        l.setFont(l.getFont().deriveFont(18f));
        l.setForeground(place == 1 ? Palette.GOLD : Section.color("LootGobbler.inkSoft", Palette.MUTED));
        l.setToolTipText("Your place by damage on " + boss.name);
        return l;
    }

    /**
     * The dungeon's portal (the weapon rack for the guild hall). Cached: the list is rebuilt every second during a
     * run. null (an empty space) for dungeons without a known portal.
     */
    static Icon dungeonIcon(Run r) {
        String key = r.guildHall ? "" : r.dungeon;
        if (DUNGEON_ICONS.containsKey(key)) return DUNGEON_ICONS.get(key);
        Icon i = r.guildHall ? icon(DpsHistory.WEAPON_RACK, DUNGEON_ICON) : ProfileView.dungeonIcon(r.dungeon, DUNGEON_ICON);
        DUNGEON_ICONS.put(key, i);
        return i;
    }

    /** Shows a saved run (null: the live one), with its latest boss opened. */
    private void select(Run run) {
        selected = run;
        if (run != null) openLatest(run);
        refresh();
    }

    /** Opens a saved run's latest boss. */
    private void openLatest(Run run) {
        if (run.bosses.isEmpty()) return;
        int last = run.bosses.size() - 1;
        Boss boss = run.bosses.get(last);
        expanded.add(run.start + "/" + (boss.id != 0 ? boss.id : last));   // the key bossBlock uses for it
    }

    /** Shows a saved run, as if picked in the Run History (e.g. from the Profile's recent runs). */
    public void showRun(Run run) {
        select(run);
    }

    private void clearAll() {
        int ok = JOptionPane.showConfirmDialog(this, "Delete all past dungeons?", "Clear all", JOptionPane.YES_NO_OPTION);
        if (ok == JOptionPane.YES_OPTION) DpsHistory.get().clear();
    }

    private void copyAll() {
        Run run = selected != null ? selected : held != null ? held : DpsHistory.get().live();
        if (run == null) return;
        StringBuilder sb = new StringBuilder();
        for (Boss b : run.bosses) sb.append(bossText(run, b)).append('\n');
        copy(sb.toString());
    }

    private static String bossText(Run run, Boss boss) {
        StringBuilder sb = new StringBuilder();
        sb.append(boss.name).append(" - ").append(run.dungeon).append(" (")
                .append(WHEN.format(Instant.ofEpochMilli(run.start).atZone(ZoneId.systemDefault()))).append(")  ")
                .append(duration(boss.fightMs)).append('\n');
        if (run.guildHall) {
            sb.append(loadoutText(boss.loadout, ", ")).append('\n');
            sb.append(String.format("%-3s %-14s %-12s %8s %10s %7s%n", "#", "Name", "Class", "DPS", "DMG", "%"));
            for (int i = 0; i < boss.players.size(); i++) {
                PlayerRow p = boss.players.get(i);
                sb.append(String.format("%-3d %-14s %-12s %8s %10s %6.1f%%%n", i + 1, p.name, p.className(),
                        NUM.format(Math.round(boss.dps(p))), NUM.format(p.damage), boss.share(p)));
            }
            return sb.toString();
        }
        sb.append(String.format("%-3s %-14s %-12s %8s %10s %8s %7s%n", "#", "Player", "Class", "DPS", "DMG", "Taken", "%"));
        for (int i = 0; i < boss.players.size(); i++) {
            PlayerRow p = boss.players.get(i);
            sb.append(String.format("%-3d %-14s %-12s %8s %10s %8s %6.1f%%%s%n", i + 1, p.name, p.className(),
                    NUM.format(Math.round(boss.dps(p))), NUM.format(p.damage), NUM.format(p.taken), boss.share(p),
                    p.died ? "  died" : ""));
        }
        DungeonVisits.Visit v = visitOf(run, run == DpsHistory.get().live());
        List<DungeonVisits.MvPhase> phases = phasesOf(v, boss.objectType);
        if (!phases.isEmpty()) {   // Moonlight Village
            sb.append(String.format("%-3s %-30s %-16s %7s %10s %11s %7s%n", "#", "Song", "Type", "Time", "Your DMG", "At lantern", "Flames"));
            long now = System.currentTimeMillis();
            for (int i = 0; i < phases.size(); i++) {
                DungeonVisits.MvPhase p = phases.get(i);
                long ms = p.duration(now, v);
                boolean last = p.lantern && i > 0 && phases.get(i - 1).lantern;
                sb.append(String.format("%-3d %-30s %-16s %7s %10s %11s %7d%n", i + 1, p.title,
                        p.lantern ? (last ? "Lantern (final)" : "Lantern") : "Damage", duration(ms),
                        NUM.format(dealt(v, p.startMs, p.startMs + ms)),
                        p.lantern && p.lanternMs > 0 ? Math.round(100.0 * p.rangeMs / p.lanternMs) + "%" : "-", p.flames));
            }
        }
        return sb.toString();
    }

    private void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    static String duration(long ms) {
        long s = ms / 1000;
        // as a clock: 4:28, and 1:02:05 past an hour
        return s >= 3600 ? String.format("%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format("%d:%02d", s / 60, s % 60);
    }

    private static Icon bossIcon(int objectType) {
        if (objectType > 0 && EnemySprites.invisible(objectType)) {   // an invisible boss part: the boss itself
            List<Integer> enemies = EnemySprites.candidates(objectType);
            if (!enemies.isEmpty()) objectType = enemies.get(0);
        }
        return icon(objectType, 20);
    }

    private static Icon icon(int objectType, int size) {
        if (objectType <= 0) return null;
        try {
            return ImageBuffer.getOutlinedIcon(objectType, size);
        } catch (Exception e) {
            return null;
        }
    }

    private static final int LOADOUT_ICON = 22;

    /**
     * The four equipped items side by side; an empty slot keeps its place.
     */
    private static Icon loadoutIcon(int[] loadout) {
        Icon[] items = new Icon[4];
        for (int i = 0; i < 4; i++) items[i] = loadout != null && i < loadout.length ? icon(loadout[i], LOADOUT_ICON) : null;
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                for (int i = 0; i < 4; i++) if (items[i] != null) items[i].paintIcon(c, g, x + i * (LOADOUT_ICON + 4), y);
            }

            @Override
            public int getIconWidth() {
                return 4 * LOADOUT_ICON + 12;
            }

            @Override
            public int getIconHeight() {
                return LOADOUT_ICON;
            }
        };
    }

    private static String itemName(int id) {
        ItemDb.Item info = id > 0 ? ItemDb.get().item(id) : null;
        return id <= 0 ? "empty" : info != null ? info.name : "Item " + id;
    }

    private static String loadoutText(int[] loadout, String separator) {
        if (loadout == null) return "unknown";
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < Math.min(4, loadout.length); i++) parts.add(SLOT_NAMES[i] + ": " + itemName(loadout[i]));
        return String.join(separator, parts);
    }

    private static JComponent stat(String caption, JLabel value) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JLabel c = new JLabel(caption.toUpperCase(Locale.ROOT), SwingConstants.CENTER);
        c.putClientProperty("FlatLaf.styleClass", "small");
        value.setHorizontalAlignment(SwingConstants.CENTER);
        p.add(c, BorderLayout.NORTH);
        p.add(value, BorderLayout.CENTER);
        return p;
    }

    private static JLabel big(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(26f));
        l.setForeground(Palette.GOLD);
        return l;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new EmptyBorder(8, 8, 8, 8));
        return p;
    }

    private static JScrollPane scroll(JPanel content) {
        return FitWidthPanel.scroll(content, 24);
    }

    static JPanel row(boolean filled) {
        JPanel row = new JPanel(new BorderLayout(10, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(Section.color(filled ? "TableHeader.background" : "TextArea.background", getBackground()));
                setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(Section.color("Component.borderColor", Color.GRAY)), new EmptyBorder(4, 10, 4, 6)));
            }
        };
        row.setAlignmentX(LEFT_ALIGNMENT);
        return row;
    }

    private static JButton button(String text, String style, Runnable action) {
        JButton b = new JButton(text.toUpperCase(Locale.ROOT));
        if (style != null) b.putClientProperty("FlatLaf.styleClass", style);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }

    /**
     * Striped rows; the player cell shows a bar for the damage share, the class cell a colored marker.
     */
    private record Mark(String text, Color color, boolean boxed) {
    }

    /** The marks after a player's name in a row: text in its color, in a slightly rounded outline if boxed. */
    private static final class Marks implements Icon {
        private static final int PAD_X = 4, PAD_Y = 1, GAP = 6, ARC = 6;
        private final List<Mark> marks;
        private final Font font;
        private final FontMetrics fm;

        Marks(List<Mark> marks, Font font) {
            this.marks = marks;
            this.font = font;
            this.fm = new JLabel().getFontMetrics(font);
        }

        private int width(Mark m) {
            return fm.stringWidth(m.text()) + (m.boxed() ? 2 * PAD_X : 0);
        }

        @Override
        public int getIconWidth() {
            int w = 0;
            for (Mark m : marks) w += width(m) + GAP;
            return w - GAP;
        }

        @Override
        public int getIconHeight() {
            return fm.getHeight() + 2 * PAD_Y;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(font);
            g2.setStroke(new BasicStroke(1.2f));
            int h = getIconHeight(), base = y + PAD_Y + fm.getAscent();
            for (Mark m : marks) {
                int w = width(m);
                g2.setColor(m.color());
                if (m.boxed()) g2.drawRoundRect(x, y, w - 1, h - 1, ARC, ARC);
                g2.drawString(m.text(), x + (m.boxed() ? PAD_X : 0), base);
                x += w + GAP;
            }
            g2.dispose();
        }
    }

    private static class PlayerRenderer extends DefaultTableCellRenderer {
        private final Boss boss;
        private final double maxShare;
        private final int goldColumn;   // the % column, -1 = none
        private double bar;

        PlayerRenderer(Boss boss, double maxShare, int goldColumn) {
            this.boss = boss;
            this.maxShare = maxShare <= 0 ? 1 : maxShare;
            this.goldColumn = goldColumn;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            setBorder(new EmptyBorder(0, 8, 0, 8));
            setIcon(null);
            setToolTipText(null);
            bar = 0;
            Color bg = row % 2 == 1 && UIManager.getColor("Table.alternateRowColor") != null
                    ? UIManager.getColor("Table.alternateRowColor") : table.getBackground();
            if (row < boss.players.size() && boss.players.get(row).me) {   // your own row stands out
                bg = Palette.mix(new Color(0xE8C552), bg, 0.22);
            }
            if (!selected) {
                setBackground(bg);
                setForeground(table.getForeground());
            }
            if (column == 1) {
                PlayerRow p = (PlayerRow) value;
                setText(p.name);
                Icon marks = marks(p, table.getFont());   // the labels and DIED, after the name
                if (marks != null) {
                    setIcon(marks);
                    setHorizontalTextPosition(SwingConstants.LEADING);
                    setIconTextGap(8);
                }
                if (p.me && !selected) setForeground(Palette.GOLD);
                bar = boss.share(p) / maxShare;
            } else if (column == 2) {
                setHorizontalTextPosition(SwingConstants.TRAILING);
                PlayerRow p = (PlayerRow) value;
                Color c = CLASS_COLORS.getOrDefault(p.className(), Palette.STONE);
                setText(p.className());
                setIcon(new Section.Square(c, 8));
                setIconTextGap(8);
                if (!selected) setForeground(c);
            } else if (column == goldColumn && !selected) {
                setForeground(Palette.GOLD);
            }
            setOpaque(bar == 0);
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (bar > 0) {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(Palette.GOLD.getRed(), Palette.GOLD.getGreen(), Palette.GOLD.getBlue(), 48));
                g.fillRect(0, 2, (int) (getWidth() * bar), getHeight() - 4);
            }
            super.paintComponent(g);
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        /**
         * After the name: an outlined box per thing they are to you, GUILD (green) when they were in your guild then
         * (the same guild as yours in this fight, or a member of it on the Loot Gobbler server) and FRIEND (blue) when
         * they are on your in-game friend list; then DIED (red, no box) if they died. null when there is none.
         */
        private Icon marks(PlayerRow p, Font font) {
            List<Mark> marks = new ArrayList<>();
            if (!p.me && isGuildmate(p)) marks.add(new Mark("GUILD", new Color(0x4CAF50), true));
            if (!p.me && lootgobbler.Friends.get().isFriend(p.name)) marks.add(new Mark("FRIEND", new Color(0x4A90E2), true));
            if (p.died) marks.add(new Mark("DIED", new Color(0xD8463F), false));
            return marks.isEmpty() ? null : new Marks(marks, font.deriveFont(Font.BOLD, font.getSize2D() - 2f));
        }

        private boolean isGuildmate(PlayerRow p) {
            PlayerRow me = boss.me();
            String mine = me == null ? null : me.guild;
            if (mine != null && !mine.isEmpty() && mine.equals(p.guild)) return true;
            return p.name != null && Guildmates.get().names().contains(p.name.toLowerCase(java.util.Locale.ROOT));
        }
    }
}
