package lootgobbler.ui;

import lootgobbler.CharacterStore;
import lootgobbler.CharacterStore.Char;
import lootgobbler.Upe;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Game Modes tab: the challenge modes a character can be played in, in two categories (PPE, UPE), each also without
 * a pet (NPE) if wished. On the left
 * the categories; on the right the chosen one: for a UPE its variant (UPE, pure, ST, shiny, divine), the guild
 * version (gPPE, gUPE...), what it is, its rules, and your characters in it: playing a run, able to start one
 * (fresh, upgrades disabled, nothing against the rules), and lost runs with why. Each character starts or ends its
 * run here, opens in the Characters tab, or shows its own finds in Inventory.
 */
public class GameModesView extends JPanel {
    private final IntConsumer openCharacter, openFinds;
    private final JPanel modeList = new JPanel();
    private final JPanel detail = new JPanel();
    private final Section detailSection;
    private String category = Upe.UPE, variant = Upe.UPE;
    private boolean guild, noPet;
    private String shown;

    public GameModesView(IntConsumer openCharacter, IntConsumer openFinds) {
        super(new BorderLayout(8, 0));
        this.openCharacter = openCharacter;
        this.openFinds = openFinds;
        setBorder(new EmptyBorder(8, 8, 8, 8));

        modeList.setLayout(new BoxLayout(modeList, BoxLayout.Y_AXIS));
        modeList.setBorder(new EmptyBorder(8, 8, 8, 8));
        JButton plus = GuideTargets.mark("gamemodes.plus", GameModeWizard.plusButton(() -> GameModeWizard.open(this, openCharacter)));
        Section modes = new Section("Game Modes", Palette.GOLD, FitWidthPanel.scroll(modeList, 16)).actions(plus);
        // as tall as the header strip (its actions sit at the top): the plus, drawn centered, is then in its middle
        int strip = modes.getComponent(0).getPreferredSize().height - 4;   // less the actions' 2 px above and below
        plus.setPreferredSize(new Dimension(26, Math.max(26, strip)));
        modes.setPreferredSize(new Dimension(290, 10));
        add(GuideTargets.mark("gamemodes.list", modes), BorderLayout.WEST);

        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        detail.setBorder(new EmptyBorder(12, 16, 16, 16));
        detailSection = new Section(Upe.UPE, Palette.PURPLE, FitWidthPanel.scroll(detail, 16));
        add(GuideTargets.mark("gamemodes.detail", detailSection), BorderLayout.CENTER);

        Runnable later = ViewRefresh.of(this, this::refresh);   // only while it can be seen
        Upe.get().onChange(later);
        CharacterStore.get().onChange(later);
        refresh();
    }

    /** The mode picked: the category, for a UPE its variant, and the guild version if on. */
    private String mode() {
        return Upe.withGuild(Upe.UPE.equals(category) ? variant : category, guild);
    }

    private void refresh() {
        List<Upe.Run> runs = Upe.get().runs();
        Map<Integer, Char> chars = new HashMap<>();
        for (Char c : CharacterStore.get().all()) chars.put(c.charId, c);
        String mode = mode();
        StringBuilder key = new StringBuilder(mode).append(noPet);
        for (Upe.Run r : runs) {
            Char c = chars.get(r.charId);
            key.append('|').append(r.charId).append(r.mode).append(r.marked).append(r.broken).append(r.gathered.size())
                    .append(r.allowed.size()).append(r.notes.size()).append(r.pet).append(r.noPet).append(r.guildLocked).append(r.trades.size())
                    .append(c == null ? "" : c.level + ":" + c.fame + ":" + c.alive + ":" + c.skin);
        }
        if (key.toString().equals(shown)) return;
        shown = key.toString();

        modeList.removeAll();
        for (Upe.ModeInfo m : Upe.CATEGORY_INFO) {
            long playing = runs.stream().filter(r -> m.code().equals(Upe.category(r.mode)) && r.marked && r.broken == null
                    && chars.containsKey(r.charId) && chars.get(r.charId).alive).count();
            modeList.add(modeEntry(m, playing));
            modeList.add(Box.createVerticalStrut(6));
        }
        modeList.revalidate();
        modeList.repaint();

        Upe.ModeInfo cat = Upe.CATEGORY_INFO.stream().filter(m -> m.code().equals(category)).findFirst().orElse(Upe.CATEGORY_INFO.get(1));
        Upe.ModeInfo info = Upe.info(mode);
        detailSection.setTitle(Upe.label(mode) + " · " + (guild ? "Guild " : "") + info.name() + (noPet ? " · no pet" : ""));
        detail.removeAll();
        detail.add(left(text(cat.summary(), 18f, null)));
        if (Upe.UPE.equals(category)) {   // the variant
            detail.add(Box.createVerticalStrut(12));
            detail.add(left(heading("Variant")));
            detail.add(Box.createVerticalStrut(4));
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row.setOpaque(false);
            ButtonGroup group = new ButtonGroup();
            for (String v : Upe.UPE_VARIANTS) {
                JToggleButton b = new JToggleButton(Upe.label(v), v.equals(variant));
                b.setToolTipText(Upe.info(v).name() + ": " + Upe.info(v).summary());
                b.addActionListener(e -> {
                    variant = v;
                    shown = null;
                    refresh();
                });
                group.add(b);
                row.add(b);
            }
            detail.add(left(row));
        }
        detail.add(Box.createVerticalStrut(10));   // the guild version
        JCheckBox g = new JCheckBox("Guild version (" + Upe.label(Upe.withGuild(mode, true)) + "): trade with Loot Gobbler guild mates on a guild run", guild);
        g.setFont(g.getFont().deriveFont(15f));
        g.setOpaque(false);
        g.addActionListener(e -> {
            guild = g.isSelected();
            shown = null;
            refresh();
        });
        detail.add(left(g));
        JCheckBox np = new JCheckBox("No pet (NPE): the whole run without a pet", noPet);   // the extra setting
        np.setFont(np.getFont().deriveFont(15f));
        np.setOpaque(false);
        np.addActionListener(e -> {
            noPet = np.isSelected();
            shown = null;
            refresh();
        });
        detail.add(left(np));
        detail.add(Box.createVerticalStrut(8));   // the rules at a glance
        detail.add(left(RuleChips.row("Loadout", RuleChips.loadout(mode))));
        detail.add(left(RuleChips.row("From", RuleChips.sources(guild))));
        detail.add(left(rulesBlock(mode, noPet)));

        List<Upe.Run> mine = runs.stream().filter(r -> category.equals(Upe.category(r.mode)) && chars.containsKey(r.charId)).toList();
        List<Upe.Run> playing = mine.stream().filter(r -> r.marked && r.broken == null && chars.get(r.charId).alive).toList();
        // a character that could start a run could start one in any mode: it is shown under each
        List<Upe.Run> could = runs.stream().filter(r -> chars.containsKey(r.charId) && r.potential() && chars.get(r.charId).alive).toList();
        List<Upe.Run> lost = mine.stream().filter(r -> r.marked && (r.broken != null || !chars.get(r.charId).alive)).toList();
        group("Playing", playing, chars, "No character is playing a " + category + " run.");
        group("Could start", could, chars, "Create a character with the sniffer running and upgrades disabled: it can start a run until it breaks a rule.");
        if (!lost.isEmpty()) group("Lost", lost, chars, null);
        detail.add(Box.createVerticalGlue());
        if (cards.size() > 300) cards = new HashMap<>(cardsNow);   // drop the stale ones now and then
        cards.putAll(cardsNow);
        cardsNow = new HashMap<>();
        detail.revalidate();
        detail.repaint();
    }

    /** Built once per mode: its HTML texts take long to lay out, and switching modes should be quick. */
    private final Map<String, JComponent> rulesBlocks = new HashMap<>();

    /** What a mode is, what it asks before a run starts, its rules and (guild version) the guild's. */
    private JComponent rulesBlock(String mode, boolean noPet) {
        return rulesBlocks.computeIfAbsent(mode + (noPet ? "|noPet" : ""), k -> {
            String m = mode;
            Upe.ModeInfo info = Upe.info(m);
            JPanel b = new JPanel();
            b.setLayout(new BoxLayout(b, BoxLayout.Y_AXIS));
            b.setOpaque(false);
            b.add(Box.createVerticalStrut(8));
            b.add(left(text(info.summary(), 15f, Palette.MUTED)));
            b.add(Box.createVerticalStrut(12));
            b.add(left(heading("Before a run starts")));
            b.add(left(text("•  " + Upe.PREREQUISITE, 15f, null)));
            if (noPet) b.add(left(text("•  It has no pet: the character list shows none following it.", 15f, null)));
            b.add(Box.createVerticalStrut(12));
            b.add(left(heading("Rules")));
            for (String rule : info.rules()) b.add(left(text("•  " + rule, 15f, null)));
            if (Upe.guild(m)) {
                b.add(Box.createVerticalStrut(8));
                b.add(left(heading("Guild version")));
                for (String rule : Upe.GUILD_RULES) b.add(left(text("•  " + rule, 15f, null)));
            }
            if (noPet) {
                b.add(Box.createVerticalStrut(8));
                b.add(left(heading("No pet")));
                for (String rule : Upe.NO_PET_RULES) b.add(left(text("•  " + rule, 15f, null)));
            }
            b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height));
            return b;
        });
    }

    /** The character cards as last built, by what they show: rebuilt only when that changes. */
    private Map<String, JComponent> cards = new HashMap<>();
    private Map<String, JComponent> cardsNow = new HashMap<>();

    private JComponent cachedCard(Upe.Run r, Char c) {
        StringBuilder k = new StringBuilder(mode()).append(noPet).append(r.noPet).append('|').append(r.charId).append(r.mode).append(r.marked).append(r.markedAt)
                .append(r.broken).append(r.gathered.size()).append(r.allowed.size()).append(r.notes).append(r.pet).append(r.guildLocked)
                .append('|').append(c.className).append(c.level).append(':').append(c.fame).append(c.alive).append(c.skin);
        for (Upe.Trade t : r.trades) k.append(t.at).append(t.status);
        String key = k.toString();
        JComponent card = cards.get(key);
        if (card == null) card = left(card(r, c));
        cardsNow.put(key, card);
        return card;
    }

    /** A category in the list on the left: its short name, full name, and how many characters play it. */
    private JComponent modeEntry(Upe.ModeInfo m, long playing) {
        boolean selected = m.code().equals(category);
        JPanel p = new JPanel(new BorderLayout(8, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                if (selected) {
                    g.setColor(Palette.mix(gold(), Section.color("LootGobbler.paper", Color.DARK_GRAY), 0.25));
                    g.fillRect(0, 0, getWidth(), getHeight());
                    g.setColor(gold());
                    g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                    g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
                }
            }
        };
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(8, 10, 8, 10));
        JLabel code = new JLabel(m.code());
        code.setFont(code.getFont().deriveFont(24f));
        code.setForeground(gold());
        JLabel name = new JLabel(m.name());
        name.setFont(name.getFont().deriveFont(14f));
        name.setToolTipText(m.summary());
        JPanel words = new JPanel(new GridLayout(2, 1));
        words.setOpaque(false);
        words.add(code);
        words.add(name);
        p.add(words, BorderLayout.CENTER);
        JLabel count = new JLabel(playing > 0 ? String.valueOf(playing) : "");
        count.putClientProperty("FlatLaf.styleClass", "small");
        count.setForeground(Palette.GREEN);
        count.setToolTipText(playing + " character" + (playing == 1 ? "" : "s") + " playing it");
        p.add(count, BorderLayout.EAST);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(p, () -> {
            category = m.code();
            if (!Upe.guildable(category)) guild = false;
            shown = null;
            refresh();
        });
        return p;
    }

    private void group(String title, List<Upe.Run> runs, Map<Integer, Char> chars, String empty) {
        detail.add(Box.createVerticalStrut(18));
        JComponent head = left(heading(title + (runs.isEmpty() ? "" : "  (" + runs.size() + ")")));
        if ("Could start".equals(title)) GuideTargets.mark("gamemodes.could", head);   // for the What's New tour
        detail.add(head);
        detail.add(Box.createVerticalStrut(6));
        if (runs.isEmpty() && empty != null) detail.add(left(text(empty, 15f, Palette.MUTED)));
        for (Upe.Run r : runs) {
            detail.add(cachedCard(r, chars.get(r.charId)));
            detail.add(Box.createVerticalStrut(6));
        }
    }

    /** One character in a mode: its look, where it stands, and what can be done with it. */
    private JComponent card(Upe.Run r, Char c) {
        JPanel card = new JPanel(new BorderLayout(14, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Section.color("LootGobbler.paper", Color.DARK_GRAY));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Section.color("LootGobbler.line", Color.GRAY));
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            }
        };
        card.setOpaque(false);
        card.setBorder(new EmptyBorder(8, 10, 8, 10));
        JLabel look = new JLabel(CharactersView.skin(c, 56));
        if (!c.alive) look.setEnabled(false);
        card.add(look, BorderLayout.WEST);

        JPanel about = new JPanel();
        about.setLayout(new BoxLayout(about, BoxLayout.Y_AXIS));
        about.setOpaque(false);
        String lvl = c.level < 20 ? "Level " + Math.max(1, c.level) : String.format(Locale.ENGLISH, "%,d Fame", c.fame);
        JLabel name = new JLabel(c.className + "   " + lvl + (r.marked ? "   ·   " + Upe.label(r.mode) + (r.noPet ? " · no pet" : "") : ""));
        name.setFont(name.getFont().deriveFont(22f));
        about.add(left(name));
        String status;
        if (!c.alive) status = "Died" + (r.broken != null ? " (the run was lost before: " + r.broken + ")" : "");
        else if (r.broken != null) status = "Lost " + date(r.brokenAt) + ": " + r.broken;
        else if (r.marked) status = "Playing since " + date(r.markedAt);
        else status = "Seen from its creation, nothing against the rules yet";
        about.add(left(text(status, 15f, r.broken != null || !c.alive ? Palette.MUTED : null)));
        StringBuilder more = new StringBuilder(r.gathered.size() + " item" + (r.gathered.size() == 1 ? "" : "s") + " gathered");
        if (!r.allowed.isEmpty()) more.append("  ·  ").append(r.allowed.size()).append(" quest/mission reward")
                .append(r.allowed.size() == 1 ? "" : "s").append(" it may take from the gift chest");
        about.add(left(text(more.toString(), 13f, Palette.MUTED)));
        for (String note : r.notes) about.add(left(text("Worth a look: " + note, 13f, Palette.MUTED)));
        for (Upe.Trade t : Upe.get().trades(r.charId).stream().limit(5).toList()) {   // its latest gUPE trades
            String verdict = Upe.CHECKING.equals(t.status) ? "checking " + t.partner + "'s report..."
                    : "ok".equals(t.status) ? "confirmed" : "illegal" + (t.why == null ? "" : ": " + t.why);
            about.add(left(text("Trade with " + t.partner + " " + date(t.at) + ": gave " + t.gave.size() + ", got "
                    + t.got.size() + " - " + verdict, 13f, "illegal".equals(t.status) ? Palette.MUTED : null)));
        }
        card.add(about, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttons.setOpaque(false);
        String mode = mode();
        if (c.alive && r.potential()) {
            boolean np = noPet;
            JButton start = button("Start " + Upe.label(mode) + (np ? " without pet" : ""), "primary", () -> Upe.get().mark(r.charId, true, mode, np));
            if (!r.canStart(mode, np)) {
                start.setEnabled(false);
                start.setToolTipText("It has a pet: a run without a pet cannot start while one follows it");
            }
            buttons.add(start);
        }
        if (c.alive && r.marked && r.broken == null && Upe.guildable(r.mode)) {   // the guild version on or off
            boolean on = Upe.guild(r.mode);
            String other = Upe.withGuild(r.mode, !on);
            JButton sw = button((on ? "Guild off: " : "Guild on: ") + Upe.label(other), null, () -> Upe.get().setMode(r.charId, other));
            if (on && r.guildLocked) {
                sw.setEnabled(false);
                sw.setToolTipText("It equipped or used an item traded from a guild mate: it stays " + Upe.label(r.mode));
            }
            buttons.add(sw);
        }
        if (c.alive && r.marked && r.broken == null && r.noPet) {   // no pet: can be given up (not taken up again)
            JButton pet = button("Allow a pet", null, () -> Upe.get().setNoPet(r.charId, false));
            pet.setToolTipText("Ends the no-pet part of the run: it cannot be turned on again");
            buttons.add(pet);
        }
        if (c.alive && r.marked && r.broken == null) buttons.add(button("End run", null, () -> Upe.get().mark(r.charId, false)));
        if (r.marked) buttons.add(button("Finds", null, () -> openFinds.accept(r.charId)));
        buttons.add(button("Open", null, () -> openCharacter.accept(r.charId)));
        JPanel right = new JPanel(new GridBagLayout());   // the buttons centered vertically
        right.setOpaque(false);
        right.add(buttons);
        card.add(right, BorderLayout.EAST);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private static JButton button(String label, String style, Runnable action) {
        JButton b = new JButton(label.toUpperCase(Locale.ROOT));
        if (style != null) b.putClientProperty("FlatLaf.styleClass", style);
        b.addActionListener(e -> action.run());
        return b;
    }

    /**
     * Wrapped text, 640 px wide. A plain text area, not an HTML label: Swing rebuilds an HTML label's view each time
     * it is added again, which made switching between modes slow.
     */
    private static JComponent text(String s, float size, Color color) {
        JTextArea a = new JTextArea(s);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setEditable(false);
        a.setFocusable(false);
        a.setHighlighter(null);
        a.setOpaque(false);
        a.setFont(UIManager.getFont("Label.font").deriveFont(size));
        a.setForeground(color != null ? color : UIManager.getColor("Label.foreground"));
        a.setBorder(new EmptyBorder(2, 0, 2, 0));
        a.setSize(new Dimension(640, Short.MAX_VALUE));
        Dimension d = new Dimension(640, a.getPreferredSize().height);
        a.setPreferredSize(d);
        a.setMaximumSize(d);
        return a;
    }

    private static JLabel heading(String s) {
        JLabel l = new JLabel(s.toUpperCase(Locale.ROOT));
        l.setFont(l.getFont().deriveFont(18f));
        l.setForeground(gold());
        return l;
    }

    private static Color gold() {
        return Section.color("LootGobbler.gold", Palette.GOLD);
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private static String date(long t) {
        return t <= 0 ? "?" : new SimpleDateFormat("d MMM HH:mm", Locale.ENGLISH).format(new Date(t));
    }
}
