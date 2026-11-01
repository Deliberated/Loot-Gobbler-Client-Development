package lootgobbler.ui;

import assets.ImageBuffer;
import lootgobbler.DpsHistory;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * DPS Logger tab: live or logged run on the left (one collapsible damage table per boss),
 * past dungeons on the right. "Detail" opens Tomato's original DPS view.
 */
public class DpsPanel extends JPanel {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);
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

    private final Runnable openDetail;
    private final JLabel title = new JLabel("NO DUNGEON");
    private final JLabel yourDps = big("0"), rank = big("-");
    private final JToggleButton liveButton = new JToggleButton("LIVE", true);
    private final JPanel bosses = column();
    private final JPanel past = column();
    private final Set<String> expanded = new HashSet<>();   // boss blocks start collapsed
    private Run selected;   // null = live

    public DpsPanel(Runnable openDetail) {
        super(new BorderLayout(0, 8));
        this.openDetail = openDetail;
        setBorder(new EmptyBorder(8, 8, 8, 8));

        liveButton.setFocusable(false);
        liveButton.addActionListener(e -> {
            selected = null;
            liveButton.setSelected(true);
            refresh();
        });
        add(header(), BorderLayout.NORTH);

        Section bossSection = new Section("Boss Damage", Palette.GOLD, scroll(bosses))
                .actions(button("Detail", null, openDetail), button("Copy all", null, this::copyAll));
        Section pastSection = new Section("Past Dungeons", Palette.BLUE, scroll(past))
                .actions(button("Clear all", "danger", this::clearAll));
        add(new SideSplit(bossSection, pastSection, "dps", 320, 220), BorderLayout.CENTER);   // drag the gap to resize

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
        Run run = selected != null ? selected : DpsHistory.get().live();
        renderRun(run);
        renderPast(runs);
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
            Boss main = run.mainBoss();
            PlayerRow me = main == null ? null : main.me();
            yourDps.setText(me == null ? "0" : NUM.format(Math.round(main.dps(me))));
            rank.setText(me == null ? "-" : String.valueOf(main.rankOf(me)));
            for (int i = 0; i < run.bosses.size(); i++) {
                bosses.add(bossBlock(run, run.bosses.get(i), i));
                bosses.add(Box.createVerticalStrut(6));
            }
        }
        bosses.revalidate();
        bosses.repaint();
    }

    private JPanel bossBlock(Run run, Boss boss, int index) {
        String key = run.start + "/" + index;
        boolean open = expanded.contains(key);

        JLabel name = new JLabel((open ? "[-]  " : "[+]  ") + boss.name + "     " + duration(boss.fightMs)
                + "  ·  " + boss.players.size() + " players", bossIcon(boss.objectType), SwingConstants.LEFT);
        name.setIconTextGap(10);
        name.setFont(name.getFont().deriveFont(18f));
        name.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        name.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (!expanded.remove(key)) expanded.add(key);
                refresh();
            }
        });

        JPanel head = row(true);
        head.add(name, BorderLayout.CENTER);
        head.add(button("Copy log", null, () -> copy(bossText(run, boss))), BorderLayout.EAST);

        JPanel block = new JPanel(new BorderLayout());
        block.setOpaque(false);
        block.setAlignmentX(LEFT_ALIGNMENT);
        block.add(head, BorderLayout.NORTH);
        if (open) block.add(bossTable(boss), BorderLayout.CENTER);
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
        table.setDefaultRenderer(Object.class, new PlayerRenderer(boss, maxShare));
        table.getColumnModel().getColumn(0).setMaxWidth(40);
        table.getColumnModel().getColumn(6).setMaxWidth(70);

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(table.getTableHeader(), BorderLayout.NORTH);
        p.add(table, BorderLayout.CENTER);
        return p;
    }

    private void renderPast(List<Run> runs) {
        past.removeAll();
        if (runs.isEmpty()) {
            JLabel empty = new JLabel("Finished dungeons appear here.");
            empty.setForeground(Palette.MUTED);
            empty.setBorder(new EmptyBorder(10, 10, 10, 10));
            past.add(empty);
        }
        for (Run r : runs) {
            boolean isSelected = r == selected;
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

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            buttons.setOpaque(false);
            buttons.add(button("Detail", null, () -> select(r)));
            buttons.add(button("x", "danger", () -> DpsHistory.get().remove(r)));

            JPanel row = row(true);
            if (isSelected) {
                row.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Palette.GOLD, 2), new EmptyBorder(4, 8, 4, 6)));
            }
            row.add(text, BorderLayout.CENTER);
            row.add(buttons, BorderLayout.EAST);
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    select(r);
                }
            });
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            past.add(row);
        }
        past.revalidate();
        past.repaint();
    }

    private void select(Run run) {
        selected = run;
        refresh();
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
        try {
            return ImageBuffer.getOutlinedIcon(objectType, 20);
        } catch (Exception e) {
            return null;
        }
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

    private static JPanel row(boolean filled) {
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
        private double bar;

        PlayerRenderer(Boss boss, double maxShare) {
            this.boss = boss;
            this.maxShare = maxShare <= 0 ? 1 : maxShare;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, false, row, column);
            setBorder(new EmptyBorder(0, 8, 0, 8));
            setIcon(null);
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
            } else if (column == 6 && !selected) {
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
