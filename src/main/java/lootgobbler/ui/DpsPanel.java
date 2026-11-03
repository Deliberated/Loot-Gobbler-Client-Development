package lootgobbler.ui;

import assets.ImageBuffer;
import lootgobbler.DpsHistory;
import lootgobbler.ItemDb;
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
    private static final String[] SLOT_NAMES = {"Weapon", "Ability", "Armor", "Ring"};

    public DpsPanel() {
        super(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        liveButton.setFocusable(false);
        liveButton.addActionListener(e -> {
            selected = null;
            liveButton.setSelected(true);
            refresh();
        });
        add(header(), BorderLayout.NORTH);

        Section bossSection = new Section("Boss Damage", Palette.GOLD, scroll(bosses))
                .actions(button("Copy all", null, this::copyAll));
        Section pastSection = new Section("Run History", Palette.BLUE, scroll(past))
                .actions(button("Clear all", "danger", this::clearAll));
        add(new SideSplit(bossSection, pastSection, "dps", 400, 220), BorderLayout.CENTER);   // drag the gap to resize

        DpsHistory.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
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
        liveButton.setSelected(selected == null);
        Run live = DpsHistory.get().live();
        renderRun(selected != null ? selected : live);
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
            title.setText(run.dungeon.toUpperCase(Locale.ROOT) + (selected == null ? "    (LIVE)" : "    (LOGGED RUN)"));
            // guild hall: your latest loadout, ranked against your other loadouts
            Boss main = run.guildHall ? run.bosses.get(run.bosses.size() - 1) : run.mainBoss();
            PlayerRow me = main == null ? null : main.me();
            yourDps.setText(me == null ? "0" : NUM.format(Math.round(main.dps(me))));
            rank.setText(me == null ? "-" : String.valueOf(run.guildHall ? loadoutRank(run, main) : main.rankOf(me)));
            for (int i = run.bosses.size() - 1; i >= 0; i--) {   // the latest boss on top
                bosses.add(bossBlock(run, run.bosses.get(i), i));
                bosses.add(Box.createVerticalStrut(6));
            }
        }
        bosses.revalidate();
        bosses.repaint();
    }

    private JPanel bossBlock(Run run, Boss boss, int index) {
        // The live run's start is recomputed every second, so live blocks are keyed by the boss alone. Live bosses
        // start open, so their damage shows from the first hit; logged runs start collapsed.
        boolean live = selected == null;
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
        if (open) block.add(run.guildHall ? rangeTable(boss) : bossTable(boss), BorderLayout.CENTER);
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
        past.revalidate();
        past.repaint();
    }

    private JPanel historyRow(Run r, boolean current) {
        boolean isSelected = current ? selected == null : r == selected;
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
        if (!current) buttons.add(button("x", "danger", () -> DpsHistory.get().remove(r)));
        if (buttons.getComponentCount() > 0) row.add(buttons, BorderLayout.EAST);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(row, () -> select(target));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /**
     * Your place by damage in a run: "#x of y" on its one boss, or with several bosses the average of your places
     * (and of the group sizes), e.g. "avg #1.5 of 6". Null for guild hall visits and runs where you hit no boss.
     */
    static JLabel runRank(Run r) {
        if (r.guildHall) return null;
        double places = 0, players = 0;
        int n = 0;
        StringBuilder tip = new StringBuilder("<html>Your place by damage");
        for (Boss b : r.bosses) {
            PlayerRow me = b.me();
            if (me == null) continue;
            places += b.rankOf(me);
            players += b.players.size();
            n++;
            tip.append("<br>").append(b.name).append(": #").append(b.rankOf(me)).append(" of ").append(b.players.size());
        }
        if (n == 0) return null;
        double place = places / n;
        String x = n == 1 ? String.valueOf((int) place) : String.format(Locale.ROOT, place % 1 == 0 ? "%.0f" : "%.1f", place);
        JLabel l = new JLabel((n > 1 ? "avg #" : "#") + x + " of " + Math.round(players / n));
        l.setFont(l.getFont().deriveFont(18f));
        l.setForeground(place == 1 ? Palette.GOLD : Section.color("LootGobbler.inkSoft", Palette.MUTED));
        l.setToolTipText(tip.append(n > 1 ? "<br>(average over " + n + " bosses)" : "").append("</html>").toString());
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
        if (run != null && !run.bosses.isEmpty()) {
            int last = run.bosses.size() - 1;
            Boss boss = run.bosses.get(last);
            expanded.add(run.start + "/" + (boss.id != 0 ? boss.id : last));   // the key bossBlock uses for it
        }
        refresh();
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
        Run run = selected != null ? selected : DpsHistory.get().live();
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
        return sb.toString();
    }

    private void copy(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    static String duration(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    private static Icon bossIcon(int objectType) {
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
                setText(p.died ? "<html>" + escape(p.name) + " <font color='#BE332D' size='2'>DIED</font></html>" : p.name);
                if (p.me && !selected) setForeground(Palette.GOLD);
                bar = boss.share(p) / maxShare;
            } else if (column == 2) {
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
    }
}
