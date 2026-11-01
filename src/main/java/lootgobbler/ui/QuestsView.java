package lootgobbler.ui;

import lootgobbler.GobblerServer;
import lootgobbler.GobblerServer.Quest;
import lootgobbler.GobblerServer.QuestParams;
import lootgobbler.GobblerServer.Quests;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Guild tab, Quests page: this week's personal and guild quests with their progress, your points this month, and a
 * form to suggest new quests (admins accept or deny them in the Server tab). The server checks the progress from
 * what you sync, so nothing has to be claimed.
 */
public class QuestsView extends JPanel {
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);
    private static final String[] TYPES = {"drops", "new_items", "dungeon_clears", "boss_damage", "fame"};
    private static final String[] TYPE_LABELS = {"Drop UT/ST items", "Find items new to the collection",
            "Clear dungeons", "Deal damage to bosses", "Earn fame"};

    private final GobblerServer server = GobblerServer.get();
    private final Section section;
    private final JPanel body = column();
    private final JPanel quests = column();

    // the suggestion form, kept across reloads
    private final JComboBox<String> type = new JComboBox<>(TYPE_LABELS);
    private final JTextField count = field(7, "e.g. 5");
    private final JTextField dungeon = field(14, "any dungeon");
    private final JComboBox<String> enchants = new JComboBox<>(new String[]{"any", "1+", "2+", "3+", "4"});
    private final JComboBox<String> scope = new JComboBox<>(new String[]{"Personal quest", "Guild quest"});
    private final JTextField title = field(24, "made from the above");
    private final JLabel feedback = new JLabel(" ");

    public QuestsView() {
        super(new BorderLayout());
        setOpaque(false);
        section = new Section("Quests", Palette.GREEN, wrap(FitWidthPanel.scroll(body, 24)))
                .actions(button("Refresh", null, this::load));
        add(section);
        type.addActionListener(e -> updateForm());
        updateForm();
        quests.setBorder(null);   // inside body, which has the padding

        body.add(quests);
        body.add(Box.createVerticalStrut(18));
        body.add(heading("Suggest a quest"));
        body.add(muted("Admins accept suggestions as personal or guild quests. Quests change every Monday."));
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setAlignmentX(LEFT_ALIGNMENT);
        addRow(form, 0, "Goal", type);
        addRow(form, 1, "How many", count);
        addRow(form, 2, "Dungeon", dungeon);
        addRow(form, 3, "Enchantments", enchants);
        addRow(form, 4, "For", scope);
        addRow(form, 5, "Title", title);
        JPanel formHolder = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));   // keeps the form on the left
        formHolder.setOpaque(false);
        formHolder.setAlignmentX(LEFT_ALIGNMENT);
        formHolder.add(form);
        body.add(formHolder);
        JPanel send = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 6));
        send.setOpaque(false);
        send.setAlignmentX(LEFT_ALIGNMENT);
        send.add(button("Suggest", "primary", this::suggest));
        body.add(send);
        feedback.setAlignmentX(LEFT_ALIGNMENT);
        body.add(feedback);
        message("Open this tab while logged in to a server to see your quests.");
    }

    /**
     * Loads this week's quests (also called by the Guild tab's refresh).
     */
    public void load() {
        if (server.user() == null || server.state() != GobblerServer.State.ONLINE) {
            message("Log in to a server in the Server tab to see your quests.");
            return;
        }
        server.quests().whenComplete((q, e) -> SwingUtilities.invokeLater(() -> {
            if (e != null) message("Could not load the quests: " + GobblerServer.message(e));
            else show(q);
        }));
    }

    private void show(Quests q) {
        quests.removeAll();
        String month = YearMonth.parse(q.period).getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        JLabel points = new JLabel(NUM.format(q.points) + " points in " + month);
        points.setFont(new Font(lootgobbler.Themes.HEAD_FONT, Font.PLAIN, 30));
        points.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        quests.add(left(points));
        quests.add(muted("This week's quests end " + DAY.format(Instant.ofEpochMilli(q.weekEnd).atZone(ZoneId.systemDefault()))
                + " (in " + until(q.weekEnd) + "). Progress counts what you synced this week."));

        quests.add(Box.createVerticalStrut(12));
        quests.add(heading("Your quests"));
        if (q.personal.isEmpty()) quests.add(muted("No personal quests this week: none have been accepted yet."));
        for (Quest quest : q.personal) quests.add(questCard(quest, false));

        quests.add(Box.createVerticalStrut(12));
        quests.add(heading(q.guild == null ? "Guild quest" : "Guild quest - " + q.guild));
        if (q.guild == null) {
            quests.add(muted("Your guild is not known yet. Enter the game once with Loot Gobbler running."));
        } else if (q.guildQuests.isEmpty()) {
            quests.add(muted("No guild quest this week: none has been accepted yet."));
        }
        for (Quest quest : q.guildQuests) quests.add(questCard(quest, true));
        quests.revalidate();
        quests.repaint();
    }

    private JComponent questCard(Quest q, boolean guild) {
        boolean done = q.completedAt != null;
        JLabel name = new JLabel(q.title);
        name.setFont(name.getFont().deriveFont(18f));
        JLabel reward = new JLabel(done ? "DONE  +" + q.points : "+" + q.points + " points");
        reward.setForeground(done ? Palette.GREEN : Section.color("LootGobbler.gold", Palette.GOLD));
        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        top.add(name, BorderLayout.CENTER);
        top.add(reward, BorderLayout.EAST);

        JProgressBar bar = new JProgressBar(0, 1000);
        bar.setValue(q.target <= 0 ? 0 : (int) Math.min(1000, q.progress * 1000 / q.target));
        bar.setStringPainted(true);
        bar.setString(NUM.format(q.progress) + " / " + NUM.format(q.target));
        if (done) bar.setForeground(Palette.GREEN);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setOpaque(false);
        content.add(left(top));
        content.add(Box.createVerticalStrut(4));
        content.add(left(bar));
        if (guild) {
            String who = q.contributors.isEmpty() ? "No one has contributed yet."
                    : q.contributors.stream().map(c -> c.ign + " " + NUM.format(c.amount)).collect(Collectors.joining(", "));
            content.add(Box.createVerticalStrut(4));
            content.add(muted(who + (q.contributors.isEmpty() ? "" : "  -  everyone who contributed gets the points")));
        }
        return card(content);
    }

    // --- suggesting ---

    private void updateForm() {
        String t = TYPES[type.getSelectedIndex()];
        dungeon.setEnabled(t.equals("drops") || t.equals("dungeon_clears") || t.equals("boss_damage"));
        enchants.setEnabled(t.equals("drops"));
    }

    private void suggest() {
        long n;
        try {
            n = Long.parseLong(count.getText().trim().replace(",", "").replace(".", ""));
        } catch (NumberFormatException e) {
            feedback.setText("Enter how many, e.g. 5.");
            return;
        }
        if (n < 1) {
            feedback.setText("How many must be at least 1.");
            return;
        }
        QuestParams p = new QuestParams();
        p.count = n;
        if (dungeon.isEnabled() && !dungeon.getText().isBlank()) p.dungeon = dungeon.getText().trim();
        if (enchants.isEnabled()) p.minEnchants = enchants.getSelectedIndex();
        String s = scope.getSelectedIndex() == 0 ? "personal" : "guild";
        feedback.setText("Sending...");
        server.suggestQuest(title.getText(), TYPES[type.getSelectedIndex()], p, s).whenComplete((t, e) ->
                SwingUtilities.invokeLater(() -> {
                    if (e != null) {
                        feedback.setText(GobblerServer.message(e));
                        return;
                    }
                    feedback.setText("Suggested \"" + t.title + "\". An admin decides whether it becomes a quest.");
                    count.setText("");
                    dungeon.setText("");
                    title.setText("");
                }));
    }

    // --- building blocks ---

    private static String until(long epochMs) {
        long min = Math.max(0, (epochMs - System.currentTimeMillis()) / 60_000);
        if (min >= 24 * 60) return (min / (24 * 60)) + " d " + (min / 60 % 24) + " h";
        if (min >= 60) return (min / 60) + " h " + (min % 60) + " min";
        return min + " min";
    }

    private void message(String text) {
        quests.removeAll();
        quests.add(muted("<html>" + text + "</html>"));
        quests.revalidate();
        quests.repaint();
    }

    private static void addRow(JPanel form, int row, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 10);
        form.add(new JLabel(label), c);
        c.gridx = 1;
        form.add(field, c);
    }

    private static JComponent card(JComponent content) {
        JPanel row = new JPanel(new BorderLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(Section.color("TableHeader.background", getBackground()));
                setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(Section.color("Component.borderColor", Color.GRAY)), new EmptyBorder(8, 10, 8, 10)));
            }
        };
        row.add(content);
        JPanel spaced = new JPanel(new BorderLayout());
        spaced.setOpaque(false);
        spaced.setBorder(new EmptyBorder(0, 0, 6, 0));
        spaced.add(row);
        spaced.setAlignmentX(LEFT_ALIGNMENT);
        spaced.setMaximumSize(new Dimension(Integer.MAX_VALUE, spaced.getPreferredSize().height));
        return spaced;
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

    private static JTextField field(int columns, String placeholder) {
        JTextField f = new JTextField(columns);
        f.putClientProperty("JTextField.placeholderText", placeholder);
        return f;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(12, 14, 14, 14));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(c);
        return p;
    }

    private static JButton button(String text, String style, Runnable action) {
        JButton b = new JButton(text.toUpperCase(Locale.ROOT));
        if (style != null) b.putClientProperty("FlatLaf.styleClass", style);
        b.setFocusable(false);
        b.addActionListener(e -> action.run());
        return b;
    }
}
