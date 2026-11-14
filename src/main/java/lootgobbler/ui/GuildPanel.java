package lootgobbler.ui;

import lootgobbler.CharacterStore.Char;
import lootgobbler.ExaltStore;
import lootgobbler.GobblerServer;
import lootgobbler.GobblerServer.GuildDrop;
import lootgobbler.GobblerServer.Leaderboard;
import lootgobbler.GobblerServer.Member;
import lootgobbler.GobblerServer.MemberProfile;
import lootgobbler.ItemDb;
import lootgobbler.PlayerProfile;
import tomato.realmshark.enums.CharacterClass;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Guild tab: your in-game guild on the Loot Gobbler server. Leaderboard, the guild's recent drops, and a member's
 * profile (opened by clicking a player). Data comes from the server, which builds it from what members sync.
 */
public class GuildPanel extends SubTabs {
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    private static final DateTimeFormatter DROP_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);
    static final String CARAVAN = "Loot Caravan", QUEST_MAP = "Quest Map";
    private static final String QUESTS = "Quests", LEADERBOARD = "Leaderboard", DROPS = "Recent Drops", MEMBER = "Member";
    private static final int DROP_LIMIT = 100;

    private final GobblerServer server = GobblerServer.get();
    private final CaravanView caravan = new CaravanView();
    private final QuestMapView questMap = new QuestMapView();
    private final Section leaderboardSection;
    private final JPanel leaderboardBody = column();
    private final JPanel dropsBody = column();
    private final Section memberSection;
    private final JPanel memberBody = column();
    private long shownMember = -1;
    private Leaderboard lastBoard;   // as last shown, to redraw with new score weights
    private List<GuildDrop> lastDrops;
    private boolean loading;

    public GuildPanel() {
        leaderboardSection = new Section("Leaderboard", Palette.GOLD, wrap(FitWidthPanel.scroll(leaderboardBody, 24)))
                .actions(button("Refresh", this::refresh));
        memberSection = new Section("Member", Palette.BLUE, wrap(FitWidthPanel.scroll(memberBody, 24)));
        page(CARAVAN, GuideTargets.mark("guild.caravan", caravan));
        page(QUEST_MAP, questMap);
        page(LEADERBOARD, leaderboardSection);
        page(DROPS, new Section("Recent Drops", Palette.PURPLE, wrap(FitWidthPanel.scroll(dropsBody, 24)))
                .actions(button("Refresh", this::refresh)));
        page(MEMBER, memberSection);
        page(QUESTS, UnderConstruction.wrap(new Section("Quests", Palette.GOLD, new JPanel()),
                "Guild quests are under construction."));
        done();
        memberBody.add(muted("Click a player in the leaderboard or in the recent drops."));

        // load when the tab is opened, and again after logging in or out
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) refresh();
        });
        server.onChange(() -> SwingUtilities.invokeLater(() -> {
            if (isShowing()) refresh();
        }));
        message(leaderboardBody, "Open this tab while logged in to a server to see your guild.");
        lootgobbler.ItemScore.get().onChange(() -> SwingUtilities.invokeLater(() -> {   // an admin changed the weights
            if (lastBoard != null) showLeaderboard(lastBoard);
            if (lastDrops != null) showDrops(lastDrops);
        }));
    }

    private void refresh() {
        lootgobbler.ItemScore.get().reload();
        caravan.load();
        questMap.load();
        if (loading) return;
        if (server.user() == null || server.state() != GobblerServer.State.ONLINE) {
            leaderboardSection.setTitle("Leaderboard");
            message(leaderboardBody, "Log in to a server in the Server tab to see your guild.");
            message(dropsBody, "Log in to a server in the Server tab to see your guild.");
            return;
        }
        loading = true;
        CompletableFuture<Leaderboard> board = server.leaderboard().thenCompose(this::withScores);
        CompletableFuture<GobblerServer.GuildDrops> drops = server.guildDrops(DROP_LIMIT);
        CompletableFuture.allOf(board, drops).whenComplete((v, e) -> SwingUtilities.invokeLater(() -> {
            loading = false;
            if (e != null) {
                String text = loadError(e);
                message(leaderboardBody, text);
                message(dropsBody, text);
                return;
            }
            showLeaderboard(board.join());
            showDrops(drops.join().drops);
            if (shownMember >= 0) openMember(shownMember, false);
        }));
    }

    /**
     * Servers from before item scores send no tally with the leaderboard: then each member's all-time caravan
     * (its tally, or at least the items it lists) stands in for it.
     */
    private CompletableFuture<Leaderboard> withScores(Leaderboard board) {
        if (board.members.stream().anyMatch(m -> !m.tally.isEmpty())) return CompletableFuture.completedFuture(board);
        return server.caravan("all").handle((c, e) -> {
            if (c == null) return board;
            for (Member m : board.members) {
                for (GobblerServer.CaravanMember cm : c.members) {
                    if (cm.id != m.id) continue;
                    if (!cm.tally.isEmpty()) {
                        m.tally = cm.tally;
                        continue;
                    }
                    List<GobblerServer.Tally> tally = new ArrayList<>();
                    for (GobblerServer.CaravanItem it : cm.items) {
                        GobblerServer.Tally t = new GobblerServer.Tally();
                        t.itemId = it.itemId;
                        t.where = it.where;
                        t.enchants = it.enchants;
                        t.count = 1;
                        tally.add(t);
                    }
                    m.tally = tally;
                }
            }
            return board;
        });
    }

    /**
     * What to show when a guild view could not be loaded.
     */
    static String loadError(Throwable e) {
        Throwable cause = GobblerServer.unwrap(e);
        return cause instanceof GobblerServer.ServerException se && "no_guild".equals(se.code)
                ? noGuildText() : "Could not load the guild: " + GobblerServer.message(e);
    }

    private static String noGuildText() {
        String guild = PlayerProfile.get().current().guild;
        return guild.isEmpty() ? "Your guild is not known yet. Enter the game once with Loot Gobbler running; "
                + "it is uploaded on the next sync." : "Your guild (" + guild + ") is not on the server yet. "
                + "It is uploaded on the next sync (Server tab: Sync now).";
    }

    // --- leaderboard ---

    private void showLeaderboard(Leaderboard board) {
        lastBoard = board;
        leaderboardSection.setTitle("Leaderboard - " + board.guild + " (" + board.members.size() + ")");
        leaderboardBody.removeAll();
        String[] columns = {"#", "Player", "Rank", "Score", "Items", "Finds", "Fame", "Exalts", "Chars", "Maxed", "Last active"};
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }

            @Override
            public Class<?> getColumnClass(int c) {   // numbers sort as numbers
                return c == 1 ? String.class : c == 3 ? Double.class : Long.class;
            }
        };
        // highest score first (the # follows it); ties by name
        List<Member> members = new ArrayList<>(board.members);
        members.sort(Comparator.comparingDouble((Member m) -> -lootgobbler.ItemScore.get().total(m.tally))
                .thenComparing(m -> m.ign.toLowerCase(Locale.ROOT)));
        for (int i = 0; i < members.size(); i++) {
            Member m = members.get(i);
            model.addRow(new Object[]{(long) i + 1, m.ign, (long) m.guildRank, lootgobbler.ItemScore.get().total(m.tally), (long) m.items, (long) m.finds, m.fame,
                    (long) m.exalts, (long) m.characters, (long) m.maxed, m.lastActive});
        }
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setRowHeight(28);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new BoardRenderer(PlayerProfile.get().current().ign));
        table.setDefaultRenderer(Long.class, new BoardRenderer(PlayerProfile.get().current().ign));
        table.setDefaultRenderer(Double.class, new BoardRenderer(PlayerProfile.get().current().ign));
        table.getColumnModel().getColumn(0).setMaxWidth(44);
        table.setToolTipText("Click a player to open their profile. Click a column title to sort.");
        table.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(table, e -> {
            int row = table.rowAtPoint(e.getPoint());
            if (row >= 0) openMember(members.get(table.convertRowIndexToModel(row)).id, true);
        });
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(table.getTableHeader(), BorderLayout.NORTH);
        holder.add(table, BorderLayout.CENTER);
        leaderboardBody.add(left(holder));
        leaderboardBody.add(Box.createVerticalStrut(8));
        leaderboardBody.add(muted("Score: the points of all items ever found. Items: different UT/ST items. Finds: all copies. Fame, Chars and Maxed count "
                + "living characters. Exalts over all classes."));
        leaderboardBody.revalidate();
        leaderboardBody.repaint();
    }

    /**
     * Striped rows, your own row in gold; the rank and last-active columns are shown as words.
     */
    private static class BoardRenderer extends DefaultTableCellRenderer {
        private final String me;

        BoardRenderer(String me) {
            this.me = me;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focus, int row, int column) {
            int col = table.convertColumnIndexToModel(column);
            Object shown = value;
            if (col == 2 && value instanceof Long rank) shown = PlayerProfile.rankName(rank.intValue());
            if (col == 10 && value instanceof Long t) shown = t == 0 ? "-" : CharactersView.ago(t);
            if (value instanceof Long n && col >= 4 && col <= 9) shown = NUM.format(n);
            if (value instanceof Double d) shown = lootgobbler.ItemScore.format(d);
            super.getTableCellRendererComponent(table, shown, selected, false, row, column);
            setBorder(new EmptyBorder(0, 8, 0, 8));
            setHorizontalAlignment(col == 1 || col == 2 || col == 10 ? LEFT : RIGHT);
            if (!selected) {
                Color alt = UIManager.getColor("Table.alternateRowColor");
                setBackground(row % 2 == 1 && alt != null ? alt : table.getBackground());
                String ign = String.valueOf(table.getValueAt(row, table.convertColumnIndexToView(1)));
                setForeground(ign.equalsIgnoreCase(me) ? Palette.GOLD : table.getForeground());
            }
            return this;
        }
    }

    // --- recent drops ---

    private void showDrops(List<GuildDrop> drops) {
        lastDrops = drops;
        dropsBody.removeAll();
        if (drops.isEmpty()) dropsBody.add(muted("No drops yet."));
        for (GuildDrop d : drops) dropsBody.add(dropRow(d));
        dropsBody.revalidate();
        dropsBody.repaint();
    }

    private JComponent dropRow(GuildDrop d) {
        ItemDb.Item item = ItemDb.get().item(d.itemId);
        int enchants = d.enchantIds != null ? d.enchantIds.size() : d.enchants;
        Image img = CollectionGrid.icon(d.itemId);
        JLabel name = new JLabel(item != null && !item.name.isEmpty() ? item.name : "Item " + d.itemId,
                img == null ? null : EnchantBadge.icon(img, 40, enchants, 8), SwingConstants.LEFT);   // rarity as its marker
        name.setIconTextGap(10);
        name.setFont(name.getFont().deriveFont(18f));
        String where = d.where == null || d.where.isBlank() ? "" : " - " + d.where;
        JLabel sub = muted(d.ign + where + " - " + dropTime(d.time));
        sub.setBorder(new EmptyBorder(0, 50, 0, 0));
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(name);
        text.add(sub);
        JLabel points = new JLabel(lootgobbler.ItemScore.format(lootgobbler.ItemScore.get().of(d.itemId, d.where, enchants)) + " pts");
        points.setFont(new Font(lootgobbler.Themes.HEAD_FONT, Font.PLAIN, 30));
        points.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        points.setBorder(new EmptyBorder(0, 12, 0, 8));
        points.setToolTipText(lootgobbler.ItemScore.breakdown(d.itemId, d.where, enchants));
        points.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(points, () -> openMember(d.userId, true));   // the tooltip catches the row's clicks here
        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.add(text, BorderLayout.CENTER);
        content.add(points, BorderLayout.EAST);
        JComponent row = card(content);
        row.setToolTipText("Open " + d.ign + "'s profile");
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(row, () -> openMember(d.userId, true));
        return row;
    }

    private static String dropTime(String time) {
        try {
            return DROP_TIME.format(LocalDateTime.parse(time));
        } catch (Exception e) {
            return time == null ? "" : time;
        }
    }

    // --- member profile ---

    private void openMember(long id, boolean switchPage) {
        shownMember = id;
        if (switchPage) {
            show(MEMBER);
            memberBody.removeAll();
            memberBody.add(muted("Loading..."));
            memberBody.revalidate();
            memberBody.repaint();
        }
        server.memberProfile(id).whenComplete((p, e) -> SwingUtilities.invokeLater(() -> {
            if (shownMember != id) return;   // another member was opened meanwhile
            if (e != null) message(memberBody, "Could not load the profile: " + GobblerServer.message(e));
            else showMember(p);
        }));
    }

    private void showMember(MemberProfile p) {
        Member m = p.member;
        memberSection.setTitle("Member - " + m.ign);
        memberBody.removeAll();
        JLabel name = new JLabel(m.ign);
        name.setFont(new Font(lootgobbler.Themes.HEAD_FONT, Font.PLAIN, 40));
        name.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        memberBody.add(left(name));
        memberBody.add(muted(PlayerProfile.rankName(m.guildRank) + " of " + p.guild
                + (m.lastActive > 0 ? " - last active " + CharactersView.ago(m.lastActive) : "")));
        memberBody.add(Box.createVerticalStrut(8));
        int collectible = ItemDb.get().collectible().size();
        memberBody.add(left(new JLabel(NUM.format(m.items) + (collectible > 0 ? " of " + NUM.format(collectible) : "")
                + " UT/ST items (" + NUM.format(m.finds) + " found)   -   " + NUM.format(m.fame) + " fame on "
                + m.characters + " characters (" + m.maxed + " maxed)   -   " + m.exalts + " exaltations")));

        memberBody.add(Box.createVerticalStrut(14));
        memberBody.add(heading("Characters"));
        List<Char> chars = new ArrayList<>(p.characters.values());
        chars.sort(Comparator.comparing((Char c) -> !c.alive).thenComparing(c -> -c.fame));
        JPanel grid = new JPanel(new GridLayout(0, 3, 8, 8));
        grid.setOpaque(false);
        for (Char c : chars) grid.add(characterCard(c));
        if (chars.isEmpty()) memberBody.add(muted("No characters synced yet."));
        else memberBody.add(left(grid));

        memberBody.add(Box.createVerticalStrut(14));
        memberBody.add(heading("Exaltations"));
        JPanel exalts = new JPanel(new GridLayout(0, 4, 16, 2));
        exalts.setOpaque(false);
        p.exalts.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> -ExaltStore.total(e.getValue())))
                .forEach(e -> {
                    String cls = className(e.getKey());
                    JLabel l = new JLabel(cls + "  " + ExaltStore.total(e.getValue()) + "/" + ExaltStore.MAX_PER_CLASS,
                            new Section.Square(Palette.CLASS_COLORS.getOrDefault(cls, Palette.STONE), 8), SwingConstants.LEFT);
                    exalts.add(l);
                });
        if (p.exalts.isEmpty()) memberBody.add(muted("No exaltations synced yet."));
        else memberBody.add(left(exalts));

        memberBody.add(Box.createVerticalStrut(14));
        memberBody.add(heading("Collection (" + NUM.format(p.collection.size()) + " items)"));
        JPanel items = new JPanel(new GridLayout(0, 14, 4, 4));
        items.setOpaque(false);
        p.collection.entrySet().stream()
                .sorted(Comparator.comparing(e -> itemName(e.getKey())))
                .forEach(e -> {
                    JLabel l = new JLabel(itemIcon(parse(e.getKey())));
                    if (l.getIcon() == null) l.setText(e.getKey());
                    l.setToolTipText(itemName(e.getKey()) + " x" + e.getValue());
                    items.add(l);
                });
        if (p.collection.isEmpty()) memberBody.add(muted("Nothing synced yet."));
        else memberBody.add(left(items));
        memberBody.revalidate();
        memberBody.repaint();
    }

    private static JComponent characterCard(Char c) {
        JLabel sprite = new JLabel(CharactersView.skin(c, 48));
        JLabel cls = new JLabel(c.className + (c.seasonal ? "  (seasonal)" : ""));
        cls.setFont(cls.getFont().deriveFont(18f));
        JLabel progress = muted(c.alive ? (c.level < 20 ? "Level " + c.level : NUM.format(c.fame) + " fame")
                + " - " + c.statsMaxed + "/8" : "Dead");
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(cls);
        text.add(progress);
        JPanel gear = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        gear.setOpaque(false);
        if (c.equipment != null) {
            for (int id : c.equipment) {
                if (id <= 0) continue;
                JLabel g = new JLabel(itemIcon(id));
                g.setToolTipText(itemName(String.valueOf(id)));
                gear.add(g);
            }
        }
        JPanel right = new JPanel(new BorderLayout());
        right.setOpaque(false);
        right.add(text, BorderLayout.CENTER);
        right.add(gear, BorderLayout.SOUTH);
        JPanel inner = new JPanel(new BorderLayout(10, 0));
        inner.setOpaque(false);
        inner.add(sprite, BorderLayout.WEST);
        inner.add(right, BorderLayout.CENTER);
        JComponent card = card(inner);
        if (!c.alive) {
            cls.setForeground(Palette.MUTED);
            sprite.setEnabled(false);
        }
        return card;
    }

    // --- building blocks ---

    private static Icon itemIcon(int id) {
        Image img = CollectionGrid.icon(id);
        return img == null ? null : new ImageIcon(img);
    }

    private static String itemName(String id) {
        ItemDb.Item item = ItemDb.get().item(parse(id));
        return item != null && !item.name.isEmpty() ? item.name : "Item " + id;
    }

    private static int parse(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String className(String classType) {
        String n = CharacterClass.getName(parse(classType));
        return n == null ? "Class " + classType : n;
    }

    /**
     * Bordered row in the header color, like the Discord and Server tab rows.
     */
    private static JComponent card(JComponent content) {
        JPanel row = new JPanel(new BorderLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(Section.color("TableHeader.background", getBackground()));
                setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(Section.color("Component.borderColor", Color.GRAY)), new EmptyBorder(6, 10, 6, 8)));
            }
        };
        row.add(content);
        JPanel spaced = new JPanel(new BorderLayout());
        spaced.setOpaque(false);
        spaced.setBorder(new EmptyBorder(0, 0, 6, 0));
        spaced.add(row);
        spaced.setAlignmentX(LEFT_ALIGNMENT);
        return spaced;
    }

    private static void message(JPanel body, String text) {
        body.removeAll();
        JLabel l = muted("<html>" + text + "</html>");
        body.add(l);
        body.revalidate();
        body.repaint();
    }

    private static JComponent heading(String text) {
        JLabel l = new JLabel(text.toUpperCase(Locale.ROOT));
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setBorder(new EmptyBorder(2, 0, 6, 0));
        return left(l);
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(12, 14, 14, 14));
        return p;
    }

    private static JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(c);
        return p;
    }

    private static JButton button(String text, Runnable action) {
        JButton b = new JButton(text.toUpperCase(Locale.ROOT));
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }
}
