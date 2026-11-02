package lootgobbler.ui;

import assets.ImageBuffer;
import lootgobbler.CharacterStore;
import lootgobbler.CharacterStore.Char;
import lootgobbler.CharacterStore.Session;
import lootgobbler.Dyes;
import lootgobbler.Themes;
import lootgobbler.ItemDb;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.geom.Path2D;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Characters tab: every character you have, favourites first then most recently played, with fame, stats
 * maxed and flags; clicking one shows its fame gain over the chosen time range.
 */
public class CharactersView extends JPanel {
    private static final long HOUR = 3_600_000L;
    private static final String[] RANGE_LABELS = {"1H", "1D", "1W", "1M", "All"};
    private static final long[] RANGES = {HOUR, 24 * HOUR, 168 * HOUR, 720 * HOUR, Long.MAX_VALUE};
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private int range = 1;   // 1D
    private static final int TOTAL = 0;   // "All characters" (character ids are positive)
    private int selected = TOTAL;
    private boolean graveyard = false;
    private final JPanel list = column();
    private final JPanel panel = column();
    private static final String[] PAGES = {"characters", "classes", "pets"};
    private final Map<Integer, Long> gains = new HashMap<>();   // charId -> fame gained in the selected range
    private final CardLayout pages;
    private final JPanel views;
    private final Segmented viewPicker, alivePicker;

    public CharactersView() {
        super(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));

        // top bar: Characters / Classes on the left, Refresh on the right
        pages = new CardLayout();
        views = new JPanel(pages);
        views.setOpaque(false);
        viewPicker = new Segmented(false, 0, i -> pages.show(views, PAGES[i]), "Characters", "Classes", "Pets");

        JLabel status = new JLabel(" ");
        status.putClientProperty("FlatLaf.styleClass", "small");
        JButton fetch = new JButton("REFRESH");
        fetch.setToolTipText("Fetch all your characters from the game servers");
        fetch.addActionListener(e -> {
            status.setText("Fetching...");
            CharacterStore.get().fetchAll(s -> SwingUtilities.invokeLater(() -> status.setText(s)));
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(status);
        actions.add(fetch);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(viewPicker, BorderLayout.WEST);
        top.add(actions, BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

        // the Characters panel's header holds the time range and Alive / Graveyard
        JLabel played = new JLabel("FAME GAINED IN THE LAST");
        played.putClientProperty("FlatLaf.styleClass", "small");
        Segmented rangePicker = new Segmented(true, range, i -> {
            range = i;
            refresh();
        }, RANGE_LABELS);
        alivePicker = new Segmented(true, 0, i -> {
            graveyard = i == 1;
            selected = TOTAL;
            refresh();
        }, "Alive", "Graveyard");
        list.setBorder(new EmptyBorder(8, 8, 8, 8));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        Section left = new Section("Characters", Palette.GOLD, wrap(scroll(list)))
                .actions(played, rangePicker, (JComponent) Box.createHorizontalStrut(24), alivePicker);
        Section right = new Section("Fame Gain", Palette.GREEN, wrap(scroll(panel)));
        SideSplit characters = new SideSplit(left, right, "characters", 440, 300);   // drag the gap to resize
        characters.minMain(left.getPreferredSize().width + 24);   // keep the header readable

        views.add(characters, PAGES[0]);
        views.add(new ClassesView(), PAGES[1]);
        views.add(new PetsView(), PAGES[2]);
        add(views, BorderLayout.CENTER);

        CharacterStore.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    /**
     * Opens the Characters page with this character selected (in the graveyard if it is dead).
     */
    public void showCharacter(int charId) {
        Char c = CharacterStore.get().all().stream().filter(x -> x.charId == charId).findFirst().orElse(null);
        if (c == null) return;
        pages.show(views, PAGES[0]);
        viewPicker.select(0);
        graveyard = !c.alive;
        alivePicker.select(graveyard ? 1 : 0);
        selected = charId;
        refresh();
    }

    private void refresh() {
        long now = System.currentTimeMillis(), window = RANGES[range];
        List<Char> all = CharacterStore.get().all();
        List<Char> alive = all.stream().filter(c -> c.alive != graveyard).toList();   // graveyard: dead ones
        // Every character is listed, most recently played first; the range sets the fame gain shown on each card.
        long since = window == Long.MAX_VALUE ? 0 : now - window;
        gains.clear();
        for (Char c : alive) {
            gains.put(c.charId, CharacterStore.get().sessions(c, since).stream().mapToLong(Session::gained).sum());
        }
        Comparator<Char> recent = Comparator.comparingLong((Char c) -> -c.lastPlayed).thenComparingLong(c -> -c.fame);
        List<Char> favs = alive.stream().filter(c -> c.favorite).sorted(recent).toList();
        List<Char> played = alive.stream().filter(c -> !c.favorite && c.lastPlayed > 0).sorted(recent).toList();
        List<Char> notYet = alive.stream().filter(c -> !c.favorite && c.lastPlayed == 0).sorted(recent).toList();
        if (selected != TOTAL && alive.stream().noneMatch(c -> c.charId == selected)) selected = TOTAL;

        list.removeAll();
        if (!alive.isEmpty()) {
            list.add(totalCard(alive));
            list.add(Box.createVerticalStrut(6));
        }
        group("Favourites", favs);
        group(graveyard ? "Fallen" : "Recently played", played);
        group("Not played since tracking started", notYet);
        if (alive.isEmpty()) {
            list.add(muted(graveyard ? "No dead characters - long may they live."
                    : "Log in once so your character list is loaded."));
        }
        list.revalidate();
        list.repaint();

        if (selected == TOTAL) renderTotal(alive);
        else renderPanel(all.stream().filter(c -> c.charId == selected).findFirst().orElse(null));
    }

    /**
     * The "All characters" card at the top of the list: combined fame gain in the range.
     */
    private JPanel totalCard(List<Char> chars) {
        long total = gains.values().stream().mapToLong(Long::longValue).sum();
        JLabel name = new JLabel(graveyard ? "All fallen characters" : "All characters");
        name.setFont(name.getFont().deriveFont(25f));
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        line.setOpaque(false);
        line.add(muted(chars.size() + " characters.   "));
        line.add(gainLabel(total));
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(name);
        text.add(line);
        JLabel big = new JLabel((total > 0 ? "+" : "") + NUM.format(total));
        big.setFont(big.getFont().deriveFont(32f));
        big.setForeground(total > 0 ? Palette.GREEN : Palette.MUTED);
        big.setToolTipText("Fame gained by all characters in the selected time range");
        big.setBorder(new EmptyBorder(0, 0, 0, 14));
        return selectableCard(TOTAL, null, text, big);
    }

    private JLabel gainLabel(long gain) {
        JLabel l = new JLabel((gain > 0 ? "+" : "") + NUM.format(gain) + " in " + RANGE_LABELS[range] + "   ");
        l.setFont(l.getFont().deriveFont(gain > 0 ? Font.BOLD : Font.PLAIN));
        l.setForeground(gain > 0 ? Palette.GREEN : Palette.MUTED);
        l.setToolTipText("Fame gained in the selected time range");
        return l;
    }

    /**
     * Right panel for "All characters": combined KPIs and chart, then each character's share of the gain.
     */
    private void renderTotal(List<Char> chars) {
        panel.removeAll();
        long now = System.currentTimeMillis();
        long window = RANGES[range];
        long since = window == Long.MAX_VALUE ? 0 : now - window;
        long gained = 0, played = 0;
        for (Char c : chars) {
            for (Session s : CharacterStore.get().sessions(c, since)) {
                gained += s.gained();
                played += s.durationMs();
            }
        }

        JLabel who = new JLabel(graveyard ? "All fallen characters" : "All characters");
        who.setFont(who.getFont().deriveFont(20f));
        who.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(who);
        JLabel meta = muted("★ " + NUM.format(chars.stream().mapToLong(c -> c.fame).sum()) + " fame in total   ·   "
                + chars.size() + " characters");
        meta.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(meta);
        panel.add(Box.createVerticalStrut(8));

        JPanel kpis = new JPanel(new GridLayout(1, 3, 8, 0));
        kpis.setOpaque(false);
        kpis.setAlignmentX(LEFT_ALIGNMENT);
        kpis.add(kpi("Fame gained", "+" + NUM.format(gained)));
        kpis.add(kpi("Fame / hour", played > 60_000 ? NUM.format(Math.round(gained / (played / (double) HOUR))) : "-"));
        kpis.add(kpi("Time played", String.format("%.1f h", played / (double) HOUR)));
        kpis.setMaximumSize(new Dimension(Integer.MAX_VALUE, kpis.getPreferredSize().height));
        panel.add(kpis);
        panel.add(Box.createVerticalStrut(8));

        FameChart chart = new FameChart(CharacterStore.get().combinedGain(chars, since), since, now);
        chart.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(chart);
        panel.add(Box.createVerticalStrut(8));

        JLabel title = new JLabel("CONTRIBUTION");
        title.putClientProperty("FlatLaf.styleClass", "small");
        title.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(4));
        List<Char> contributors = chars.stream().filter(c -> gains.getOrDefault(c.charId, 0L) != 0)
                .sorted(Comparator.comparingLong((Char c) -> -gains.get(c.charId))).toList();
        if (contributors.isEmpty()) panel.add(muted("No fame gained in this range."));
        long max = contributors.stream().mapToLong(c -> Math.abs(gains.get(c.charId))).max().orElse(1);
        for (int i = 0; i < contributors.size(); i++) {
            Char c = contributors.get(i);
            panel.add(contributionRow(c, gains.get(c.charId), gained, max, i));
        }
        panel.revalidate();
        panel.repaint();
    }

    /**
     * One character's part of the total: sprite, name, a bar sized to its gain, "+N  (x%)". Click to open it.
     */
    private JPanel contributionRow(Char c, long gain, long total, long max, int index) {
        JLabel name = new JLabel(c.className + " " + c.level, skin(c, 24), SwingConstants.LEFT);
        name.setIconTextGap(6);
        if (c.seasonal) name.setForeground(SEASONAL_TEXT);
        name.setPreferredSize(new Dimension(130, 28));
        String pct = total > 0 ? String.format("  (%d%%)", Math.round(100.0 * gain / total)) : "";
        JLabel value = new JLabel((gain > 0 ? "+" : "") + NUM.format(gain) + pct);
        value.setForeground(gain > 0 ? Palette.GREEN : Palette.RED);
        double share = Math.abs(gain) / (double) max;
        JComponent bar = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                int h = 10, y = (getHeight() - h) / 2, w = (int) Math.max(2, Math.round((getWidth() - 4) * share));
                g.setColor(Section.color("LootGobbler.sand", Color.LIGHT_GRAY));
                g.fillRect(0, y, getWidth() - 4, h);
                g.setColor(gain > 0 ? Palette.GREEN : Palette.RED);
                g.fillRect(0, y, w, h);
                g.setColor(Color.BLACK);
                g.drawRect(0, y, getWidth() - 5, h - 1);
            }
        };
        JPanel row = new JPanel(new BorderLayout(8, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Section.color(index % 2 == 0 ? "LootGobbler.header" : "LootGobbler.sand", Color.LIGHT_GRAY));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        row.setBorder(new EmptyBorder(2, 6, 2, 8));
        row.add(name, BorderLayout.WEST);
        row.add(bar, BorderLayout.CENTER);
        row.add(value, BorderLayout.EAST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        row.setToolTipText("Open " + c.className + " " + c.level);
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(row, () -> {
            selected = c.charId;
            refresh();
        });
        return row;
    }

    private void group(String title, List<Char> chars) {
        if (chars.isEmpty()) return;
        JLabel l = new JLabel(title.toUpperCase(Locale.ROOT));
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setBorder(new EmptyBorder(6, 2, 4, 0));
        l.setAlignmentX(LEFT_ALIGNMENT);
        list.add(l);
        for (Char c : chars) {
            list.add(card(c));
            list.add(Box.createVerticalStrut(6));
        }
    }

    private JPanel card(Char c) {
        // skin, with a little flame in the corner while blood ritual is active (as in-game)
        JLabel sprite = new JLabel(withFlame(portrait(skin(c, 74)), c.ritual));
        sprite.setPreferredSize(new Dimension(PORTRAIT + 6, PORTRAIT + 6));
        sprite.setHorizontalAlignment(SwingConstants.CENTER);

        // name with the time right-aligned above the end of the line below: level until 20, then fame, and the gain
        JLabel name = new JLabel(c.className);
        name.setFont(name.getFont().deriveFont(30f));
        if (c.seasonal) name.setForeground(SEASONAL_TEXT);   // seasonal characters are teal in-game
        JLabel when = muted(c.lastPlayed == 0 ? "not played yet" : ago(c.lastPlayed));
        when.setFont(when.getFont().deriveFont(15f));
        JPanel top = new JPanel(new GridBagLayout());   // both on the name's baseline, the time at the right end
        top.setOpaque(false);
        GridBagConstraints line = new GridBagConstraints();
        line.anchor = GridBagConstraints.BASELINE_LEADING;
        line.weightx = 1;
        top.add(name, line);
        line.anchor = GridBagConstraints.BASELINE_TRAILING;
        line.weightx = 0;
        line.insets = new Insets(0, 16, 0, 0);
        top.add(when, line);
        JLabel progress = new JLabel(c.level < 20 ? "Level " + c.level : NUM.format(c.fame) + " Fame");
        progress.setFont(progress.getFont().deriveFont(20f));
        progress.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        long gain = gains.getOrDefault(c.charId, 0L);
        JLabel gained = new JLabel("(" + (gain > 0 ? "+" : "") + NUM.format(gain) + " in " + RANGE_LABELS[range] + ")");
        gained.setFont(gained.getFont().deriveFont(gain > 0 ? Font.BOLD : Font.PLAIN, 15f));
        gained.setForeground(gain > 0 ? Palette.GREEN : Palette.MUTED);
        gained.setToolTipText("Fame gained in the selected time range");
        JPanel bottom = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        bottom.setOpaque(false);
        bottom.add(progress);
        bottom.add(Box.createHorizontalStrut(10));
        bottom.add(gained);
        JPanel text = new JPanel(new GridLayout(2, 1));   // both lines as wide as the wider one
        text.setOpaque(false);
        text.add(top);
        text.add(bottom);
        JPanel textHolder = new JPanel(new GridBagLayout());   // vertically centered, left-aligned
        textHolder.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.anchor = GridBagConstraints.WEST;
        gc.weightx = 1;
        textHolder.add(text, gc);

        // right side: banners on top; pet, stats, gravestone and star below
        JPanel banners = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        banners.setOpaque(false);
        banners.add(new Banner("Seasonal", SEASONAL_BG, c.seasonal));
        banners.add(new Banner("Crucible", CRUCIBLE_BG, c.crucible));

        OutlinedText stats = new OutlinedText(c.statsMaxed + "/8", c.statsMaxed == 8 ? STATS_MAXED : STATS_OPEN, 34f);
        stats.setToolTipText(c.statsMaxed + " of 8 stats maxed");
        JPanel toggles = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        toggles.setOpaque(false);
        if (c.petSkin > 0) {
            JLabel pet = new JLabel(sprite(c.petSkin, 36));
            pet.setToolTipText(c.petName == null || c.petName.isBlank() ? "Pet" : "Pet: " + c.petName);
            toggles.add(pet);
        }
        toggles.add(stats);
        toggles.add(toggleIcon(GRAVE, !c.alive, c.alive ? "Mark as dead" : "Mark as alive", () -> toggle(c, CharacterStore.Flag.DEAD)));
        toggles.add(toggleIcon(c.favorite ? STAR_ON : STAR_OFF, true, c.favorite ? "Favourite" : "Mark as favourite",
                () -> toggle(c, CharacterStore.Flag.FAVORITE)));

        JPanel right = new JPanel(new GridLayout(2, 1, 0, 4));
        right.setOpaque(false);
        right.setBorder(new EmptyBorder(8, 14, 8, 12));
        right.add(banners);
        right.add(toggles);

        // middle: the loadout at a fixed place, then name and progress
        JPanel middle = new JPanel(new BorderLayout(16, 0));
        middle.setOpaque(false);
        middle.add(loadout(c), BorderLayout.WEST);
        middle.add(textHolder, BorderLayout.CENTER);
        return selectableCard(c.charId, sprite, middle, right);
    }

    private static final Color STATS_MAXED = new Color(0xFFD21F), STATS_OPEN = new Color(0xE6E2D8);
    private static final int SLOT = 54;
    private static final String[] SLOT_NAMES = {"Weapon", "Ability", "Armor", "Ring"};

    /**
     * Five fixed slots: weapon, ability, armor, ring and backpack / extender. A missing item leaves its slot empty,
     * so every category is at the same place on every card.
     */
    private static JComponent loadout(Char c) {
        JPanel row = new JPanel(new GridLayout(1, 5, 4, 0));
        row.setOpaque(false);
        for (int i = 0; i < 4; i++) {
            int id = c.equipment != null && i < c.equipment.length ? c.equipment[i] : -1;
            row.add(slot(id, enchantIds(c, i), SLOT_NAMES[i], true));
        }
        if (c.extender) row.add(slot(CharacterStore.EXTENDER_ITEM, null, "Backpack Extender", false));
        else if (c.backpack) row.add(slot(CharacterStore.BACKPACK_ITEM, null, "Backpack", false));
        else row.add(slot(-1, null, "Backpack", false));
        JPanel center = new JPanel(new GridBagLayout());   // vertically centered
        center.setOpaque(false);
        center.add(row);
        return center;
    }

    /**
     * @param showEmpty draw a faint square when there is no item (equipment slots; the backpack slot stays blank)
     */
    static JLabel slot(int id, int[] enchantIds, String slotName, boolean showEmpty) {
        Icon item = id > 0 ? sprite(id, 48) : null;
        int enchants = enchantIds == null ? 0 : enchantIds.length;
        JLabel l = new JLabel(new Icon() {
            @Override
            public void paintIcon(Component comp, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                if (item != null) {
                    item.paintIcon(comp, g2, x + (SLOT - item.getIconWidth()) / 2, y + (SLOT - item.getIconHeight()) / 2);
                } else if (showEmpty) {
                    g2.setColor(new Color(0, 0, 0, 40));
                    g2.fillRect(x + 6, y + 6, SLOT - 12, SLOT - 12);
                }
                EnchantBadge.draw(g2, x + SLOT - 1, y + SLOT - 1, enchants, 8);   // rarity marker at 1.6x
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return SLOT;
            }

            @Override
            public int getIconHeight() {
                return SLOT;
            }
        });
        l.setToolTipText(itemTooltip(id, enchantIds, slotName));
        return l;
    }

    private static int[] enchantIds(Char c, int slot) {
        if (c.enchantIds != null && slot < c.enchantIds.length && c.enchantIds[slot] != null) return c.enchantIds[slot];
        if (c.enchants != null && slot < c.enchants.length && c.enchants[slot] > 0) {   // saved before ids were kept
            int[] unknown = new int[c.enchants[slot]];
            Arrays.fill(unknown, -2);
            return unknown;
        }
        return null;
    }

    private static String itemName(int id) {
        ItemDb.Item info = id > 0 ? ItemDb.get().item(id) : null;
        return id <= 0 ? "none" : info != null ? info.name : "Item " + id;
    }

    /**
     * Slot, item name and rarity, then every enchantment by name (empty slots as "empty slot").
     */
    private static String itemTooltip(int id, int[] enchantIds, String slotName) {
        StringBuilder sb = new StringBuilder("<html><b>").append(escape(slotName)).append(":</b> ").append(escape(itemName(id)));
        int n = enchantIds == null ? 0 : enchantIds.length;
        if (n > 0) {
            Color rarity = EnchantBadge.COLORS[Math.min(4, n)];
            sb.append(String.format("  <font color='#%06X'>(%s)</font>", rarity.getRGB() & 0xFFFFFF, EnchantBadge.RARITY[Math.min(4, n)]));
            for (int e : enchantIds) {
                String name = e >= 0 ? lootgobbler.EnchantIcons.name(e) : null;
                sb.append("<br>&nbsp;• ").append(e == -1 ? "<i>empty slot</i>" : e < 0 ? "<i>refresh to see</i>"
                        : escape(name != null ? name : "Enchantment " + e));
            }
        }
        return sb.append("</html>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * A list card (character or the "All characters" total) that highlights when selected and selects on click.
     */
    private JPanel selectableCard(int id, JComponent west, JComponent center, JComponent east) {
        boolean sel = id == selected;
        Color accent = Section.color("LootGobbler.gold", Palette.GOLD);
        JPanel card = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                Color header = Section.color("LootGobbler.header", Color.LIGHT_GRAY);
                g.setColor(sel ? Palette.mix(accent, header, 0.3) : header);
                g.fillRect(0, 0, getWidth(), getHeight());
            }

            @Override
            public void paint(Graphics g) {
                super.paint(g);   // frame on top, so the right side's plate can reach the edge
                g.setColor(sel ? accent : Color.BLACK);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                if (sel) g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
            }
        };
        card.setOpaque(false);
        JPanel body = new JPanel(new BorderLayout(14, 0));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(4, west == null ? 12 : 4, 4, 10));
        if (west != null) body.add(west, BorderLayout.WEST);
        body.add(center, BorderLayout.CENTER);
        card.add(body, BorderLayout.CENTER);
        card.add(east, BorderLayout.EAST);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(card, () -> {
            selected = id;
            refresh();
        });
        return card;
    }

    private void renderPanel(Char c) {
        panel.removeAll();
        if (c == null) {
            panel.add(muted("Select a character."));
            panel.revalidate();
            panel.repaint();
            return;
        }
        long now = System.currentTimeMillis();
        long window = RANGES[range];
        long since = window == Long.MAX_VALUE ? 0 : now - window;
        List<Session> sessions = CharacterStore.get().sessions(c, since);
        long gained = sessions.stream().mapToLong(Session::gained).sum();
        long played = sessions.stream().mapToLong(Session::durationMs).sum();

        JLabel who = new JLabel(c.className + "  lvl " + c.level + (c.favorite ? "  (favourite)" : ""), skin(c, 64),
                SwingConstants.LEFT);
        who.setIconTextGap(12);
        who.setFont(who.getFont().deriveFont(20f));
        who.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(who);
        long allGained = gains.values().stream().mapToLong(Long::longValue).sum();
        String share = allGained > 0 && gained > 0
                ? "   ·   " + Math.round(100.0 * gained / allGained) + "% of all fame gained" : "";
        JLabel meta = muted("★ " + NUM.format(c.fame) + " fame   ·   " + c.statsMaxed + "/8 stats");
        meta.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(meta);
        JLabel meta2 = muted((c.lastPlayed == 0 ? "not played yet" : "last played " + ago(c.lastPlayed)) + share);
        meta2.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(meta2);
        panel.add(Box.createVerticalStrut(8));

        if (c.equipment != null) {
            JLabel loadTitle = new JLabel("LOADOUT");
            loadTitle.putClientProperty("FlatLaf.styleClass", "small");
            loadTitle.setAlignmentX(LEFT_ALIGNMENT);
            panel.add(loadTitle);
            panel.add(Box.createVerticalStrut(4));
            for (int i = 0; i < Math.min(4, c.equipment.length); i++) panel.add(loadoutRow(c.equipment[i], enchantIds(c, i), SLOT_NAMES[i], i));
            panel.add(Box.createVerticalStrut(8));
        }

        JPanel kpis = new JPanel(new GridLayout(1, 3, 8, 0));
        kpis.setOpaque(false);
        kpis.setAlignmentX(LEFT_ALIGNMENT);
        kpis.add(kpi("Fame gained", "+" + NUM.format(gained)));
        kpis.add(kpi("Fame / hour", played > 60_000 ? NUM.format(Math.round(gained / (played / (double) HOUR))) : "-"));
        kpis.add(kpi("Time played", String.format("%.1f h", played / (double) HOUR)));
        kpis.setMaximumSize(new Dimension(Integer.MAX_VALUE, kpis.getPreferredSize().height));
        panel.add(kpis);
        panel.add(Box.createVerticalStrut(8));

        FameChart chart = new FameChart(c.samples, since, now);
        chart.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(chart);
        panel.add(Box.createVerticalStrut(8));

        JLabel sessTitle = new JLabel("SESSIONS");
        sessTitle.putClientProperty("FlatLaf.styleClass", "small");
        sessTitle.setAlignmentX(LEFT_ALIGNMENT);
        panel.add(sessTitle);
        panel.add(Box.createVerticalStrut(4));
        if (sessions.isEmpty()) panel.add(muted("No sessions recorded in this range."));
        for (int i = 0; i < sessions.size(); i++) panel.add(sessionRow(sessions.get(i), i));
        panel.revalidate();
        panel.repaint();
    }

    /**
     * An equipped item in the side panel: icon, name in its rarity color, and one icon per enchantment.
     */
    private static JPanel loadoutRow(int id, int[] enchantIds, String slotName, int index) {
        int n = enchantIds == null ? 0 : enchantIds.length;
        JLabel name = new JLabel(itemName(id), id > 0 ? EnchantBadge.icon(iconImage(id), 32, n) : null, SwingConstants.LEFT);
        name.setIconTextGap(8);
        if (n > 0) name.setForeground(Palette.mix(EnchantBadge.COLORS[Math.min(4, n)], Color.BLACK, 0.35));
        name.setToolTipText(itemTooltip(id, enchantIds, slotName));

        JPanel icons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        icons.setOpaque(false);
        if (enchantIds != null) {
            Color frame = EnchantBadge.COLORS[Math.min(4, n)];
            for (int e : enchantIds) {
                JLabel l;
                if (e >= 0) {
                    Image img = lootgobbler.EnchantIcons.icon(e, 22);
                    l = img != null ? new JLabel(new ImageIcon(img)) : new JLabel("?");
                    String en = lootgobbler.EnchantIcons.name(e);
                    l.setToolTipText(en != null ? en : "Enchantment " + e);
                } else {   // empty slot, or not known yet
                    l = new JLabel();
                    l.setPreferredSize(new Dimension(22, 22));
                    l.setToolTipText(e == -1 ? "Empty enchantment slot" : "Refresh to see this enchantment");
                }
                l.setBorder(BorderFactory.createLineBorder(frame));
                icons.add(l);
            }
        }
        JPanel row = new JPanel(new BorderLayout(8, 0)) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Section.color(index % 2 == 0 ? "LootGobbler.header" : "LootGobbler.sand", Color.LIGHT_GRAY));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        row.setBorder(new EmptyBorder(3, 6, 3, 8));
        row.add(name, BorderLayout.CENTER);
        row.add(icons, BorderLayout.EAST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static Image iconImage(int id) {
        Icon i = sprite(id, 32);
        return i instanceof ImageIcon ii ? ii.getImage() : null;
    }

    private JPanel sessionRow(Session s, int index) {
        ZoneId zone = ZoneId.systemDefault();
        JLabel when = new JLabel(DAY_TIME.format(Instant.ofEpochMilli(s.start()).atZone(zone)) + " – "
                + TIME.format(Instant.ofEpochMilli(s.end()).atZone(zone)));
        JLabel gain = new JLabel((s.gained() >= 0 ? "+" : "") + NUM.format(s.gained()) + " fame");
        gain.setForeground(Palette.GREEN);
        JPanel row = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Section.color(index % 2 == 0 ? "LootGobbler.header" : "LootGobbler.sand", Color.LIGHT_GRAY));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        row.setBorder(new EmptyBorder(3, 8, 3, 8));
        row.add(when, BorderLayout.WEST);
        row.add(gain, BorderLayout.EAST);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /**
     * Fame over the chosen range: an area chart that steps with the recorded samples.
     */
    private static class FameChart extends JComponent {
        private final List<long[]> points = new ArrayList<>();   // {time, fame}
        private final long from, to;

        FameChart(List<CharacterStore.Sample> samples, long since, long now) {
            long first = Long.MAX_VALUE;
            for (CharacterStore.Sample s : samples) {
                if (s.t >= since) {
                    points.add(new long[]{s.t, s.fame});
                    first = Math.min(first, s.t);
                }
            }
            this.to = now;
            this.from = since > 0 ? since : (first == Long.MAX_VALUE ? now - HOUR : first);
            setPreferredSize(new Dimension(380, 200));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 200));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), left = 8, right = w - 8, top = 18, bottom = h - 24;
            g2.setColor(Section.color("LootGobbler.sand", Color.LIGHT_GRAY));
            g2.fillRect(0, 0, w, h);
            g2.setColor(Section.color("LootGobbler.line", Color.GRAY));
            g2.drawRect(0, 0, w - 1, h - 1);
            Color accent = Section.color("LootGobbler.gold", Palette.GOLD);
            g2.setFont(getFont().deriveFont(13f));
            timeAxis(g2, left, right, top, bottom, h);
            if (points.isEmpty()) {
                g2.setColor(Palette.MUTED);
                g2.drawString("No fame recorded in this range yet.", left + 6, h / 2);
                g2.dispose();
                return;
            }
            long min = points.stream().mapToLong(p -> p[1]).min().orElse(0);
            long max = points.stream().mapToLong(p -> p[1]).max().orElse(1);
            double span = Math.max(1, max - min), tspan = Math.max(1, to - from);
            Path2D line = new Path2D.Double();
            Path2D area = new Path2D.Double();
            double lastY = 0;
            for (int i = 0; i < points.size(); i++) {
                double x = left + (points.get(i)[0] - from) / tspan * (right - left);
                double y = bottom - (points.get(i)[1] - min) / span * (bottom - top);
                if (i == 0) {
                    line.moveTo(x, y);
                    area.moveTo(x, bottom);
                    area.lineTo(x, y);
                } else {
                    line.lineTo(x, lastY);   // fame steps: flat until the next sample
                    line.lineTo(x, y);
                    area.lineTo(x, lastY);
                    area.lineTo(x, y);
                }
                lastY = y;
            }
            line.lineTo(right, lastY);
            area.lineTo(right, lastY);
            area.lineTo(right, bottom);
            area.closePath();
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 50));
            g2.fill(area);
            g2.setColor(accent);
            g2.setStroke(new BasicStroke(2f));
            g2.draw(line);
            g2.setColor(Palette.MUTED);
            g2.drawString(NUM.format(max), left + 4, top - 4);
            g2.drawString(NUM.format(min), left + 4, bottom - 4);
            g2.dispose();
        }

        private static final long MIN = 60_000L;
        private static final long[] STEPS = {5 * MIN, 10 * MIN, 15 * MIN, 30 * MIN, HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR,
                12 * HOUR, 24 * HOUR, 48 * HOUR, 7 * 24 * HOUR, 14 * 24 * HOUR, 30 * 24 * HOUR, 90 * 24 * HOUR};
        private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

        /**
         * Time scale under the chart: faint gridlines with clock times (or dates for long ranges) at round local
         * times, about one label per 80 px, and "now" at the right end.
         */
        private void timeAxis(Graphics2D g2, int left, int right, int top, int bottom, int h) {
            FontMetrics fm = g2.getFontMetrics();
            double tspan = Math.max(1, to - from);
            int maxLabels = Math.max(2, (right - left) / 80);
            long step = STEPS[STEPS.length - 1];
            for (long s : STEPS) {
                if (tspan / s <= maxLabels) {
                    step = s;
                    break;
                }
            }
            boolean days = step >= 24 * HOUR;
            ZoneId zone = ZoneId.systemDefault();
            long offset = zone.getRules().getOffset(Instant.ofEpochMilli(from)).getTotalSeconds() * 1000L;
            long tick = Math.floorDiv(from + offset + step - 1, step) * step - offset;   // first round local time
            String nowLabel = "now";
            int nowLeft = right - fm.stringWidth(nowLabel);
            Color grid = Section.color("LootGobbler.line", Color.GRAY);
            g2.setColor(Palette.MUTED);
            g2.drawLine(left, bottom, right, bottom);
            for (; tick < to; tick += step) {
                int x = (int) Math.round(left + (tick - from) / tspan * (right - left));
                g2.setColor(new Color(grid.getRed(), grid.getGreen(), grid.getBlue(), 70));
                g2.drawLine(x, top, x, bottom);
                g2.setColor(Palette.MUTED);
                g2.drawLine(x, bottom, x, bottom + 3);
                String label = (days ? DAY : TIME).format(Instant.ofEpochMilli(tick).atZone(zone));
                int lx = x - fm.stringWidth(label) / 2;
                if (lx >= 2 && lx + fm.stringWidth(label) < nowLeft - 6) g2.drawString(label, lx, h - 5);
            }
            g2.setColor(Palette.MUTED);
            g2.drawString(nowLabel, nowLeft, h - 5);
        }
    }

    private static final Color SEASONAL_BG = new Color(0x15DCA6), SEASONAL_TEXT = new Color(0x2BD8B0);
    private static final Color CRUCIBLE_BG = new Color(0xD8302C);

    private void toggle(Char c, CharacterStore.Flag flag) {
        CharacterStore.get().toggle(c.charId, flag);
    }

    /**
     * In-game style banner (SEASONAL / CRUCIBLE): colored with white text when set, faded when not. Display only.
     */
    private static class Banner extends JComponent {
        private final String text;
        private final Color bg;
        private final boolean active;

        /**
         * Shows a flag from the game data; it can not be changed by hand.
         */
        Banner(String text, Color bg, boolean active) {
            this.text = text.toUpperCase(Locale.ROOT);
            this.bg = bg;
            this.active = active;
            setToolTipText(active ? text + " character" : "Not " + text.toLowerCase(Locale.ROOT));
        }

        private static final Font FONT = new Font(Themes.HEAD_FONT, Font.PLAIN, 24);
        private static final Color TEXT_OUTLINE = new Color(0x5C5C5C);

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(FONT);
            int h = fm.getAscent() + 18;
            return new Dimension(fm.stringWidth(text) + 2 * unit(h) + 12, h);
        }

        /**
         * Size of one art pixel: the corner notch and the depth of the dark bottom side.
         */
        private static int unit(int h) {
            return Math.max(3, Math.round(h / 5.6f));
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            // drawn whole first, then faded as one piece when off (overlapping parts would show through otherwise)
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            int u = unit(h);
            // a slab with notched corners: thin black outline, flat face, dark side along the bottom
            g2.setColor(Color.BLACK);
            g2.fillRect(u, 0, w - 2 * u, h);
            g2.fillRect(0, u, w, h - 2 * u);
            g2.setColor(Palette.mix(bg, Color.BLACK, 0.76));
            g2.fillRect(u + 1, 1, w - 2 * u - 2, h - 2);
            g2.fillRect(1, u + 1, w - 2, h - 2 * u - 2);
            g2.setColor(bg);
            g2.fillRect(u + 1, 1, w - 2 * u - 2, h - 2 - u);
            g2.fillRect(1, u + 1, w - 2, h - 2 * u - 2 - (u + 1) / 2);
            g2.setFont(FONT);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            FontMetrics fm = g2.getFontMetrics();
            int face = h - u;   // center the text on the face, above the dark side
            int tx = (w - fm.stringWidth(text)) / 2, ty = (face + fm.getAscent()) / 2 - 1;
            g2.setColor(TEXT_OUTLINE);   // thin 1 px grey outline
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx != 0 || dy != 0) g2.drawString(text, tx + dx, ty + dy);
                }
            }
            g2.setColor(Color.WHITE);
            g2.drawString(text, tx, ty);
            g2.dispose();
            Graphics2D out = (Graphics2D) g.create();
            if (!active) out.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
            out.drawImage(img, 0, 0, null);
            out.dispose();
        }
    }

    /**
     * Pixel-font text with a black outline and drop shadow (e.g. the stats-maxed count), as in-game.
     */
    private static class OutlinedText extends JComponent {
        private final String text;
        private final Color color;
        private final Font font;

        OutlinedText(String text, Color color, float size) {
            this.text = text;
            this.color = color;
            this.font = new Font(Themes.HEAD_FONT, Font.PLAIN, Math.round(size));
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(font);
            return new Dimension(fm.stringWidth(text) + 6, fm.getAscent() + 6);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setFont(font);
            FontMetrics fm = g2.getFontMetrics();
            drawOutlined(g2, text, 2, (getHeight() + fm.getAscent()) / 2 - 1, color);
            g2.dispose();
        }
    }

    /**
     * Text with a 2 px black outline and a shadow below it.
     */
    private static void drawOutlined(Graphics2D g2, String text, int x, int y, Color color) {
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2.setColor(Color.BLACK);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                if (dx != 0 || dy != 0) g2.drawString(text, x + dx, y + dy);
            }
        }
        g2.setColor(color);
        g2.drawString(text, x, y);
    }

    /**
     * Icon that toggles a flag; faded while the flag is off.
     */
    private static JLabel toggleIcon(Icon icon, boolean on, String tip, Runnable onClick) {
        JLabel l = new JLabel(on ? icon : faded(icon));
        l.setToolTipText(tip);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(l, onClick);
        return l;
    }

    private static Icon faded(Icon icon) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
                icon.paintIcon(c, g2, x, y);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return icon.getIconWidth();
            }

            @Override
            public int getIconHeight() {
                return icon.getIconHeight();
            }
        };
    }

    private static final int PORTRAIT = 84;

    /**
     * Dark portrait tile behind the skin so it pops: soft glow in the theme accent, light inner rim, black frame.
     */
    private static Icon portrait(Icon skin) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                Color accent = Section.color("LootGobbler.gold", Palette.GOLD);
                g2.setColor(new Color(0x15141A));
                g2.fillRect(x, y, PORTRAIT, PORTRAIT);
                g2.setPaint(new RadialGradientPaint(new Point(x + PORTRAIT / 2, y + PORTRAIT / 2), PORTRAIT * 0.55f,
                        new float[]{0f, 1f}, new Color[]{new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90),
                        new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0)}));
                g2.fillRect(x, y, PORTRAIT, PORTRAIT);
                g2.setColor(new Color(255, 255, 255, 40));
                g2.drawRect(x + 1, y + 1, PORTRAIT - 3, PORTRAIT - 3);
                g2.setColor(Color.BLACK);
                g2.drawRect(x, y, PORTRAIT - 1, PORTRAIT - 1);
                if (skin != null) {
                    skin.paintIcon(c, g2, x + (PORTRAIT - skin.getIconWidth()) / 2, y + (PORTRAIT - skin.getIconHeight()) / 2);
                }
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return PORTRAIT;
            }

            @Override
            public int getIconHeight() {
                return PORTRAIT;
            }
        };
    }

    /**
     * The skin sprite with a small flame drawn in its bottom-right corner (blood ritual).
     */
    private static Icon withFlame(Icon skin, boolean ritual) {
        if (!ritual || skin == null) return skin;
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                skin.paintIcon(c, g, x, y);
                FLAME.paintIcon(c, g, x + skin.getIconWidth() - FLAME.getIconWidth(), y + skin.getIconHeight() - FLAME.getIconHeight());
            }

            @Override
            public int getIconWidth() {
                return skin.getIconWidth();
            }

            @Override
            public int getIconHeight() {
                return skin.getIconHeight();
            }
        };
    }

    private static final String[] FLAME_PIXELS = {
            "...r...",
            "..ro...",
            "..roo..",
            ".rooor.",
            ".royor.",
            "royyyor",
            "royyyor",
            ".ryyyr.",
            "..rrr.."};

    private static final int FLAME_PX = 3;

    /**
     * Pixel-art flame (blood ritual), FLAME_PX scale.
     */
    private static final Icon FLAME = new Icon() {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            for (int r = 0; r < FLAME_PIXELS.length; r++) {
                for (int col = 0; col < FLAME_PIXELS[r].length(); col++) {
                    char ch = FLAME_PIXELS[r].charAt(col);
                    if (ch == '.') continue;
                    g.setColor(ch == 'r' ? new Color(0xC2341B) : ch == 'o' ? new Color(0xF57E1E) : new Color(0xFFD23F));
                    g.fillRect(x + col * FLAME_PX, y + r * FLAME_PX, FLAME_PX, FLAME_PX);
                }
            }
        }

        @Override
        public int getIconWidth() {
            return 7 * FLAME_PX;
        }

        @Override
        public int getIconHeight() {
            return 9 * FLAME_PX;
        }
    };

    /**
     * The gravestone sprite (resources/icons/gravestone.png), pixel-sharp at 22 px.
     */
    private static final Icon GRAVE = loadIcon("/icons/gravestone.png", 30);

    private static Icon loadIcon(String path, int size) {
        try (java.io.InputStream in = CharactersView.class.getResourceAsStream(path)) {
            if (in == null) return new ImageIcon();
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(in);
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(src, 0, 0, size, size, null);
            g.dispose();
            return new ImageIcon(out);
        } catch (Exception e) {
            return new ImageIcon();
        }
    }

    private static final Icon STAR_ON = star(true), STAR_OFF = star(false);

    private static Icon star(boolean filled) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Polygon p = new Polygon();
                for (int i = 0; i < 10; i++) {
                    double a = Math.PI / 2 + i * Math.PI / 5, r = i % 2 == 0 ? 15 : 6.3;
                    p.addPoint(x + 17 + (int) Math.round(r * Math.cos(a)), y + 17 - (int) Math.round(r * Math.sin(a)));
                }
                // white star with a black outline (favourite), or just the outline in the text color
                if (filled) {
                    g2.setColor(Color.WHITE);
                    g2.fill(p);
                }
                g2.setColor(filled ? Color.BLACK : Section.color("LootGobbler.ink", Color.DARK_GRAY));
                g2.setStroke(new BasicStroke(filled ? 2.4f : 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(p);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 34;
            }

            @Override
            public int getIconHeight() {
                return 34;
            }
        };
    }

    /**
     * Small gold padlock for characters favourited (locked) in-game.
     */
    private static final Icon LOCK = new Icon() {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Palette.GOLD_DARK);
            g2.setStroke(new BasicStroke(2f));
            g2.drawArc(x + 3, y + 1, 8, 9, 0, 180);
            g2.drawLine(x + 3, y + 5, x + 3, y + 7);
            g2.drawLine(x + 11, y + 5, x + 11, y + 7);
            g2.setColor(Palette.GOLD);
            g2.fillRect(x + 1, y + 7, 12, 8);
            g2.setColor(Palette.GOLD_DARK);
            g2.drawRect(x + 1, y + 7, 12, 8);
            g2.fillRect(x + 6, y + 10, 2, 3);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 15;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    };

    static String ago(long t) {
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60_000);
        if (m < 60) return m + " min ago";
        if (m < 60 * 24) return (m / 60) + " h ago";
        return (m / 60 / 24) + " d ago";
    }

    /**
     * The character's skin with its applied dyes and cloths.
     */
    static Icon skin(Char c, int size) {
        try {
            return Dyes.icon(c.spriteId(), c.tex1, c.tex2, size);
        } catch (Exception e) {
            return sprite(c.spriteId(), size);
        }
    }

    static Icon sprite(int id, int size) {
        if (id <= 0) return null;
        try {
            return ImageBuffer.getOutlinedIcon(id, size);
        } catch (Exception e) {
            return null;
        }
    }

    private static JLabel tag(String text, Color bg, Color fg) {
        JLabel l = new JLabel(text.toUpperCase(Locale.ROOT));
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setOpaque(true);
        l.setBackground(bg);
        l.setForeground(fg);
        l.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Color.BLACK), new EmptyBorder(0, 4, 0, 4)));
        return l;
    }

    private static JPanel kpi(String caption, String value) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Section.color("LootGobbler.header", Color.LIGHT_GRAY));
                g.fillRect(0, 0, getWidth(), getHeight());
            }
        };
        p.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Section.color("LootGobbler.line", Color.GRAY)),
                new EmptyBorder(4, 8, 4, 8)));
        JLabel c = new JLabel(caption.toUpperCase(Locale.ROOT));
        c.putClientProperty("FlatLaf.styleClass", "small");
        JLabel v = new JLabel(value);
        v.setFont(v.getFont().deriveFont(24f));
        v.setForeground(Palette.GOLD);
        p.add(c, BorderLayout.NORTH);
        p.add(v, BorderLayout.CENTER);
        return p;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    private static JScrollPane scroll(JPanel content) {
        return FitWidthPanel.scroll(content, 24);
    }

    private static JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(c);
        return p;
    }
}
