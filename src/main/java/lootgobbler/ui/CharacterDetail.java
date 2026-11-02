package lootgobbler.ui;

import lootgobbler.CharacterStore.Char;
import lootgobbler.ClassSlots;
import lootgobbler.CollectionStore;
import lootgobbler.ItemDb;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.NumberFormat;
import java.util.*;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;

/**
 * The parts of the Characters page's side panel about one character: a few stats next to the header, the loadout,
 * an ideal loadout for the stats the player picks, and the inventory with three highlighted items.
 */
final class CharacterDetail {
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    static final String[] SLOT_NAMES = {"Weapon", "Ability", "Armor", "Ring"};
    // the eight stats, as item bonuses name them, with the names and potion icons of the Classes page
    private static final String[] STATS = {"MAXHP", "MAXMP", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"};
    private static final String[] STAT_LABELS = {"Life", "Mana", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom"};
    private static final String[] STAT_SHORT = {"HP", "MP", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS"};
    private static final String IDEAL_KEY = "idealLoadoutStats";
    private static final int CELL = 52;
    private static final Color UT = new Color(0x8049A8), ST = new Color(0xE07B2A), TIER = new Color(0xB07D0A);

    private CharacterDetail() {
    }

    // ---- loadout ----------------------------------------------------------------------------------------------

    /** Weapon and armor on top, ability and ring below: each item with its enchantments by name. */
    static JComponent loadout(Char c) {
        JPanel grid = new JPanel(new GridLayout(2, 2, 12, 8));
        grid.setOpaque(false);
        for (int slot : new int[]{0, 2, 1, 3}) {
            int id = c.equipment != null && slot < c.equipment.length ? c.equipment[slot] : -1;
            grid.add(equipped(id, CharactersView.enchantIds(c, slot), SLOT_NAMES[slot]));
        }
        return left(grid);
    }

    private static JComponent equipped(int id, int[] enchants, String slotName) {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        JLabel icon = new JLabel(id > 0 ? CharactersView.sprite(id, 44) : null);
        icon.setPreferredSize(new Dimension(48, 48));
        icon.setVerticalAlignment(SwingConstants.TOP);
        icon.setToolTipText(CharactersView.itemTooltip(id, enchants, slotName));
        ItemLinks.link(icon, id);
        p.add(icon, BorderLayout.WEST);
        JPanel text = column();
        JLabel name = new JLabel(id > 0 ? CharactersView.itemName(id) : "No " + slotName.toLowerCase(Locale.ROOT));
        int n = enchants == null ? 0 : enchants.length;
        if (n > 0) name.setForeground(EnchantBadge.COLORS[Math.min(4, n)]);   // the rarity's color
        text.add(name);
        if (enchants != null) {
            for (int e : enchants) {
                Image img = e >= 0 ? lootgobbler.EnchantIcons.icon(e, 16) : null;
                String en = e >= 0 ? lootgobbler.EnchantIcons.name(e) : null;
                JLabel l = new JLabel(e == -1 ? "empty slot" : e < 0 ? "refresh to see" : en != null ? en : "Enchantment " + e,
                        img != null ? new ImageIcon(img) : null, SwingConstants.LEFT);
                l.setIconTextGap(6);
                l.putClientProperty("FlatLaf.styleClass", "small");
                l.setForeground(e < 0 ? Palette.MUTED : Section.color("LootGobbler.ink", Color.DARK_GRAY));
                text.add(l);
            }
        }
        p.add(text, BorderLayout.CENTER);
        return p;
    }

    // ---- ideal loadout ----------------------------------------------------------------------------------------

    /**
     * Stat toggles, then per slot the item that gives the most of the chosen stats: from the items you have of the
     * character's side (seasonal or not) that fit its class, plus what it carries.
     */
    static JComponent ideal(Char c, Runnable rerender) {
        Set<String> chosen = chosenStats();
        JPanel p = column();
        JPanel toggles = new JPanel(new GridLayout(2, 4, 4, 4));   // two rows of four: fits a narrow panel
        toggles.setOpaque(false);
        for (int s = 0; s < STATS.length; s++) {
            String stat = STATS[s];
            JToggleButton b = new JToggleButton(STAT_SHORT[s], ClassesView.potion(s, 16), chosen.contains(stat));
            b.setToolTipText(STAT_LABELS[s]);
            b.setIconTextGap(4);
            b.addActionListener(e -> {
                Set<String> now = chosenStats();
                if (!now.remove(stat)) now.add(stat);
                PropertiesManager.setProperties(IDEAL_KEY, String.join(",", now));
                rerender.run();
            });
            toggles.add(b);
        }
        p.add(left(toggles));
        p.add(Box.createVerticalStrut(8));
        int[] types = ClassSlots.of(c.classType);
        if (chosen.isEmpty() || types == null) {
            p.add(left(muted(types == null ? "Unknown class." : "Pick the stats to make as high as possible.")));
            return p;
        }
        List<Integer> pool = pool(c);
        JPanel grid = new JPanel(new GridLayout(2, 2, 12, 8));
        grid.setOpaque(false);
        int[] suggested = new int[STATS.length], now = new int[STATS.length];   // item bonuses per stat
        for (int slot : new int[]{0, 2, 1, 3}) {
            int best = -1, bestScore = 0, bestAll = 0;
            for (int id : pool) {
                ItemDb.Item it = ItemDb.get().item(id);
                if (it == null || it.slotType != types[slot]) continue;
                int score = 0, all = 0;
                for (ItemDb.Stat st : it.onEquip) {
                    if (chosen.contains(st.stat())) score += st.amount();
                    all += st.amount();
                }
                if (score > bestScore || (score == bestScore && score > 0 && all > bestAll)) {
                    best = id;
                    bestScore = score;
                    bestAll = all;
                }
            }
            int current = c.equipment != null && slot < c.equipment.length ? c.equipment[slot] : -1;
            if (best < 0) best = current;   // nothing gives the chosen stats: keep what it has
            ItemDb.Item it = best > 0 ? ItemDb.get().item(best) : null;
            addBonuses(suggested, it);
            addBonuses(now, current > 0 ? ItemDb.get().item(current) : null);
            grid.add(idealItem(best, it, chosen, best == current, SLOT_NAMES[slot]));
        }
        // the character's stats on the left (as with the suggestion), the four items on the right
        JPanel row = new JPanel(new BorderLayout(18, 0));
        row.setOpaque(false);
        row.add(statTable(ClassSlots.maxStats(c.classType), suggested, now, chosen), BorderLayout.WEST);
        row.add(grid, BorderLayout.CENTER);
        p.add(left(row));
        return p;
    }

    private static void addBonuses(int[] into, ItemDb.Item it) {
        if (it == null) return;
        for (ItemDb.Stat st : it.onEquip) {
            int i = Arrays.asList(STATS).indexOf(st.stat());
            if (i >= 0) into[i] += st.amount();
        }
    }

    /**
     * The eight stats as they would be with the suggested loadout: the class's maximum plus the suggested items'
     * bonuses, and how that differs from what the character wears now (green up, red down). Chosen stats in gold.
     */
    private static JComponent statTable(int[] max, int[] suggested, int[] now, Set<String> chosen) {
        JPanel t = new JPanel(new GridBagLayout());
        t.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(1, 0, 1, 10);
        for (int s = 0; s < STATS.length; s++) {
            g.gridy = s;
            g.gridx = 0;
            g.anchor = GridBagConstraints.WEST;
            JLabel name = new JLabel(STAT_SHORT[s], ClassesView.potion(s, 16), SwingConstants.LEFT);
            name.setIconTextGap(6);
            if (chosen.contains(STATS[s])) name.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
            t.add(name, g);
            g.gridx = 1;
            g.anchor = GridBagConstraints.EAST;
            int base = max == null ? 0 : max[s];
            JLabel value = new JLabel(max == null ? "+" + suggested[s] : NUM.format(base + suggested[s]));
            value.setToolTipText(max == null ? "Item bonuses" : "Maximum " + NUM.format(base) + ", items " + (suggested[s] >= 0 ? "+" : "") + suggested[s]);
            t.add(value, g);
            g.gridx = 2;
            g.anchor = GridBagConstraints.WEST;
            int diff = suggested[s] - now[s];
            JLabel change = new JLabel(diff == 0 ? "" : (diff > 0 ? "+" : "") + diff);
            change.setForeground(diff > 0 ? Palette.GREEN : new Color(0xE5636E));
            change.setToolTipText("Compared with what it wears now");
            t.add(change, g);
        }
        return t;
    }

    private static JComponent idealItem(int id, ItemDb.Item it, Set<String> chosen, boolean equipped, String slotName) {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        JLabel icon = new JLabel(id > 0 ? CharactersView.sprite(id, 40) : null);
        icon.setPreferredSize(new Dimension(44, 44));
        ItemLinks.link(icon, id);
        p.add(icon, BorderLayout.WEST);
        JPanel text = column();
        text.add(new JLabel(id > 0 ? CharactersView.itemName(id) : "No " + slotName.toLowerCase(Locale.ROOT)));
        StringBuilder sb = new StringBuilder();
        if (it != null) {
            for (ItemDb.Stat st : it.onEquip) {
                if (chosen.contains(st.stat())) sb.append(st.amount() > 0 ? "+" : "").append(st.amount()).append(' ').append(shortName(st.stat())).append("  ");
            }
        }
        // no bonus to the chosen stats: nothing better was found, so it is what the character wears
        JLabel bonus = new JLabel(sb.length() == 0 ? "equipped" : sb.toString().trim());
        bonus.putClientProperty("FlatLaf.styleClass", "small");
        bonus.setForeground(sb.length() == 0 ? Palette.MUTED : Palette.GREEN);
        text.add(bonus);
        if (equipped && sb.length() > 0) {
            JLabel eq = new JLabel("equipped");
            eq.putClientProperty("FlatLaf.styleClass", "small");
            eq.setForeground(Palette.MUTED);
            text.add(eq);
        }
        p.add(text, BorderLayout.CENTER);
        return p;
    }

    /** Items the character could wear: what it carries, and what you hold on its side (seasonal or not). */
    private static List<Integer> pool(Char c) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (c.slots != null) for (int id : c.slots) if (id > 0) ids.add(id);
        for (Map.Entry<Integer, CollectionStore.Entry> e : CollectionStore.get().snapshot().entrySet()) {
            if (e.getValue().held() == 0) continue;
            for (CollectionStore.Find f : e.getValue().history()) {
                if (CollectionStore.isRemoval(f)) continue;
                boolean side = f.seasonal == null ? !c.seasonal : f.seasonal == c.seasonal;
                if (side) {
                    ids.add(e.getKey());
                    break;
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private static Set<String> chosenStats() {
        Set<String> s = new LinkedHashSet<>();
        String saved = PropertiesManager.getProperty(IDEAL_KEY);
        if (saved != null) for (String p : saved.split(",")) if (!p.isBlank()) s.add(p.trim());
        return s;
    }

    private static String shortName(String stat) {
        int i = Arrays.asList(STATS).indexOf(stat);
        return i < 0 ? stat : STAT_SHORT[i];
    }

    // ---- inventory --------------------------------------------------------------------------------------------

    /** One way an item can stand out: its title, how to measure it (higher wins; NaN = not measurable), the value shown. */
    private record Fact(String title, ToDoubleFunction<Integer> measure, Function<Integer, String> shown) {
    }

    private static int stat(int id, String stat) {
        ItemDb.Item it = ItemDb.get().item(id);
        if (it == null) return 0;
        int n = 0;
        for (ItemDb.Stat s : it.onEquip) if (s.stat().equals(stat)) n += s.amount();
        return n;
    }

    private static double statFact(int id, String stat) {
        int n = stat(id, stat);
        return n > 0 ? n : Double.NaN;
    }

    /** Facts that can make one of the carried items stand out; three are picked per character. */
    private static List<Fact> facts(Char c) {
        Map<Integer, CollectionStore.Entry> coll = CollectionStore.get().snapshot();
        Function<Integer, ItemDb.Item> item = id -> ItemDb.get().item(id);
        List<Fact> f = new ArrayList<>();
        f.add(new Fact("Highest feed power", id -> item.apply(id).feedPower > 0 ? item.apply(id).feedPower : Double.NaN,
                id -> NUM.format(item.apply(id).feedPower) + " FP"));
        f.add(new Fact("Lowest feed power", id -> item.apply(id).feedPower > 0 ? -item.apply(id).feedPower : Double.NaN,
                id -> NUM.format(item.apply(id).feedPower) + " FP"));
        f.add(new Fact("Highest damage", id -> item.apply(id).maxDamage > 0 ? item.apply(id).maxDamage : Double.NaN,
                id -> item.apply(id).minDamage + "-" + item.apply(id).maxDamage + " damage"));
        String[][] statFacts = {{"WIS", "Most wisdom"}, {"DEF", "Most defense"}, {"ATT", "Most attack"}, {"SPD", "Most speed"},
                {"DEX", "Most dexterity"}, {"VIT", "Most vitality"}, {"MAXHP", "Most life"}, {"MAXMP", "Most mana"}};
        for (String[] s : statFacts) {
            f.add(new Fact(s[1], id -> statFact(id, s[0]), id -> "+" + stat(id, s[0]) + " " + shortName(s[0])));
        }
        f.add(new Fact("Most enchantments", id -> {
            int n = carriedEnchants(c, id);
            return n > 0 ? n : Double.NaN;
        }, id -> carriedEnchants(c, id) + (carriedEnchants(c, id) == 1 ? " enchantment" : " enchantments")));
        f.add(new Fact("Most recently found", id -> time(coll.get(id), true), id -> day(coll.get(id).last)));
        f.add(new Fact("Oldest find", id -> -time(coll.get(id), false), id -> day(coll.get(id).first)));
        f.add(new Fact("Your only copy", id -> coll.get(id) != null && coll.get(id).held() == 1 ? 1 : Double.NaN,
                id -> "1 copy"));
        f.add(new Fact("Most copies", id -> coll.get(id) != null && coll.get(id).held() > 1 ? coll.get(id).held() : Double.NaN,
                id -> coll.get(id).held() + " copies"));
        return f;
    }

    private static int carriedEnchants(Char c, int id) {
        if (c.slots == null || c.slotEnchantIds == null) return 0;
        int best = 0;
        for (int i = 4; i < c.slots.length && i < c.slotEnchantIds.length; i++) {
            if (c.slots[i] == id && c.slotEnchantIds[i] != null) best = Math.max(best, c.slotEnchantIds[i].length);
        }
        return best;
    }

    private static double time(CollectionStore.Entry e, boolean last) {
        String t = e == null ? null : last ? e.last : e.first;
        if (t == null || t.length() < 10) return Double.NaN;
        try {
            return java.time.LocalDateTime.parse(t).toEpochSecond(java.time.ZoneOffset.UTC);
        } catch (Exception ex) {
            return Double.NaN;
        }
    }

    private static String day(String iso) {
        try {
            return java.time.LocalDate.parse(iso.substring(0, 10)).format(
                    java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Three carried items that stand out (the facts picked per character, each a different item), then the slots
     * as in the game: inventory, backpack and extender, eight each, side by side and centered.
     */
    static JComponent inventory(Char c) {
        JPanel p = column();
        if (c.slots == null || c.slots.length <= 4) {
            p.add(left(muted("Log in with this character, or Refresh, to see its inventory.")));
            return p;
        }
        List<Integer> carried = new ArrayList<>();
        for (int i = 4; i < c.slots.length; i++) {
            if (c.slots[i] > 0 && ItemDb.get().item(c.slots[i]) != null && !carried.contains(c.slots[i])) carried.add(c.slots[i]);
        }
        // highlights: the facts in an order fixed per character, the first three that find a new item; first among
        // the items this character's class can wear, then (if fewer than three) among all, so wearables come first
        List<Fact> facts = facts(c);
        Collections.shuffle(facts, new Random(c.charId));
        int[] types = ClassSlots.of(c.classType);
        Set<Integer> wearable = new HashSet<>();
        for (int id : carried) {
            ItemDb.Item it = ItemDb.get().item(id);
            if (types != null && it != null && Arrays.stream(types).anyMatch(t -> t == it.slotType)) wearable.add(id);
        }
        JPanel highlights = new JPanel(new GridLayout(1, 3, 12, 0));
        highlights.setOpaque(false);
        Set<Integer> used = new HashSet<>();
        Set<Fact> shownFacts = new HashSet<>();
        for (int pass = 0; pass < 2; pass++) for (Fact f : facts) {
            if (highlights.getComponentCount() == 3) break;
            if (shownFacts.contains(f)) continue;
            int best = -1;
            double bestValue = Double.NaN;
            for (int id : carried) {
                if (used.contains(id) || (pass == 0 && !wearable.contains(id))) continue;
                double v;
                try {
                    v = f.measure().applyAsDouble(id);
                } catch (Exception e) {
                    v = Double.NaN;
                }
                if (!Double.isNaN(v) && (Double.isNaN(bestValue) || v > bestValue)) {
                    best = id;
                    bestValue = v;
                }
            }
            if (best < 0) continue;
            used.add(best);
            shownFacts.add(f);
            JLabel l = new JLabel("<html>" + f.title() + "<br><font color='#938B79'>" + f.shown().apply(best) + "</font></html>",
                    CharactersView.sprite(best, 40), SwingConstants.LEFT);
            l.setIconTextGap(10);
            l.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
            l.setToolTipText(CharactersView.itemName(best));
            highlights.add(ItemLinks.link(l, best));
        }
        if (highlights.getComponentCount() > 0) {
            p.add(left(highlights));
            p.add(Box.createVerticalStrut(10));
        }
        // the packs: inventory, then backpack and extender if the character has them
        int packs = 1 + (c.backpack || c.slots.length > 12 && hasItems(c, 12) ? 1 : 0) + (c.extender || hasItems(c, 20) ? 1 : 0);
        // all packs in one frame, side by side; each pack fills its 4 x 2 slots row by row, as in the game
        String[] names = {"Inventory", "Backpack", "Extender"};
        PackFrame frame = new PackFrame(Arrays.copyOf(names, packs));
        for (int r = 0; r < 2; r++) {
            for (int pack = 0; pack < packs; pack++) {
                for (int k = 0; k < 4; k++) {
                    int i = 4 + pack * 8 + r * 4 + k;
                    int id = i < c.slots.length ? c.slots[i] : -1;
                    int[] ench = c.slotEnchantIds != null && i < c.slotEnchantIds.length ? c.slotEnchantIds[i] : null;
                    frame.add(cell(id, ench, names[pack]));
                }
            }
        }
        frame.setAlignmentX(Component.LEFT_ALIGNMENT);   // as wide as the box; its height follows (square slots)
        p.add(frame);
        return p;
    }

    private static boolean hasItems(Char c, int from) {
        for (int i = from; i < Math.min(from + 8, c.slots.length); i++) if (c.slots[i] > 0) return true;
        return false;
    }

    /**
     * The packs (inventory, backpack, extender) side by side, eight slots each (4 x 2): every item in its own
     * slightly shaded box, the outer top corners of the top row's leftmost and rightmost boxes cut off, and a vertical line between
     * the packs that reaches a little above and below them. The slots are square and sized as if all three packs
     * were there, so one or two packs keep the same size (centered). The items themselves have no background.
     * Children are added row by row across the packs.
     */
    private static final class PackFrame extends JPanel {
        private static final int PACKS = 3, PAD = 10, GAP = 6, DIVIDER = 22, GROW = 2;   // GROW: box beyond the slot
        private final int packs;

        PackFrame(String[] names) {
            super(null);
            packs = names.length;
            setOpaque(false);
        }

        private int cols() {
            return 4 * packs;
        }

        /** Width of n packs at slot size s. */
        private static int width(int n, int s) {
            return n * (4 * s + 3 * GAP) + (n - 1) * DIVIDER;
        }

        /** The slot size for a width: as if all three packs were shown, at least CELL. */
        private int slot(int w) {
            return Math.max(CELL, (w - 2 * PAD - 3 * PACKS * GAP - (PACKS - 1) * DIVIDER) / (4 * PACKS));
        }

        /** Where the slot in this row and column (over all packs) sits; the packs are centered. */
        private Rectangle rect(int row, int col, int s) {
            int left = (getWidth() - width(packs, s)) / 2, pack = col / 4, k = col % 4;
            return new Rectangle(left + pack * (4 * s + 3 * GAP + DIVIDER) + k * (s + GAP), PAD + row * (s + GAP), s, s);
        }

        /** As wide as the panel gives it, and tall enough for two rows of square slots. */
        @Override
        public Dimension getPreferredSize() {
            int min = 2 * PAD + width(PACKS, CELL);
            int w = Math.max(min, getParent() == null ? 0 : getParent().getWidth() - getParent().getInsets().left - getParent().getInsets().right);
            return new Dimension(w, 2 * PAD + 2 * slot(w) + GAP);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        @Override
        public void doLayout() {
            int s = slot(getWidth());
            for (int i = 0; i < getComponentCount(); i++) getComponent(i).setBounds(rect(i / cols(), i % cols(), s));
        }

        @Override
        public void setBounds(int x, int y, int w, int h) {
            boolean wider = w != getWidth();
            super.setBounds(x, y, w, h);
            if (wider) SwingUtilities.invokeLater(this::revalidate);   // the height follows the new width
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
            int s = slot(getWidth()), cut = Math.max(6, s / 5);
            Color shadow = new Color(0, 0, 0, 70), fill = new Color(0, 0, 0, 55);
            Color edge = new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), 80);
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < cols(); col++) {
                    Rectangle r = rect(row, col, s);
                    r.grow(GROW, GROW);
                    // only the leftmost and rightmost boxes of the top row have their outer top corners cut
                    boolean first = col == 0, last = col == cols() - 1;
                    Polygon box = box(r, cut, first && row == 0, last && row == 0, false, false);
                    Polygon below = new Polygon(box.xpoints, box.ypoints, box.npoints);
                    below.translate(2, 3);
                    g2.setColor(shadow);
                    g2.fillPolygon(below);
                    g2.setColor(fill);
                    g2.fillPolygon(box);
                    g2.setColor(edge);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawPolygon(box);
                }
            }
            // a vertical line between the packs, reaching a little above and below them, with small end caps
            g2.setColor(new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), 170));
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int y0 = PAD - GROW - 7, y1 = PAD + 2 * s + GAP + GROW + 7;
            for (int pack = 1; pack < packs; pack++) {
                int x = rect(0, pack * 4, s).x - DIVIDER / 2;
                g2.drawLine(x, y0, x, y1);
                g2.drawLine(x - 3, y0, x + 3, y0);
                g2.drawLine(x - 3, y1, x + 3, y1);
            }
            g2.dispose();
        }

        /** A box with some of its corners cut (top left, top right, bottom right, bottom left). */
        private static Polygon box(Rectangle r, int c, boolean tl, boolean tr, boolean br, boolean bl) {
            Polygon p = new Polygon();
            int x0 = r.x, y0 = r.y, x1 = r.x + r.width - 1, y1 = r.y + r.height - 1;
            if (tl) {
                p.addPoint(x0, y0 + c);
                p.addPoint(x0 + c, y0);
            } else p.addPoint(x0, y0);
            if (tr) {
                p.addPoint(x1 - c, y0);
                p.addPoint(x1, y0 + c);
            } else p.addPoint(x1, y0);
            if (br) {
                p.addPoint(x1, y1 - c);
                p.addPoint(x1 - c, y1);
            } else p.addPoint(x1, y1);
            if (bl) {
                p.addPoint(x0 + c, y1);
                p.addPoint(x0, y1 - c);
            } else p.addPoint(x0, y1);
            return p;
        }
    }

    /** One slot: the item (no background), its rarity marker and a UT / ST / tier tag. */
    private static JComponent cell(int id, int[] enchants, String pack) {
        ItemDb.Item it = id > 0 ? ItemDb.get().item(id) : null;
        int n = enchants == null ? 0 : enchants.length;
        String tag = it == null ? null : it.isUT() ? "UT" : it.isST() ? "ST" : it.tier.isEmpty() ? null : "T" + it.tier;
        Color tagColor = it == null ? null : it.isUT() ? UT : it.isST() ? ST : TIER;
        // drawn at whatever size the slot has: the frame grows with the panel
        JComponent l = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                int size = Math.min(getWidth(), getHeight()), x = (getWidth() - size) / 2, y = (getHeight() - size) / 2;
                Icon item = it != null ? CharactersView.sprite(id, Math.max(16, size - 10)) : null;
                if (item != null) item.paintIcon(this, g2, x + (size - item.getIconWidth()) / 2, y + (size - item.getIconHeight()) / 2);
                EnchantBadge.draw(g2, x + size - 3, y + size - 3, n, size >= 64 ? 8 : 5);
                if (tag != null) {
                    g2.setFont(new Font(lootgobbler.Themes.BODY_FONT, Font.PLAIN, Math.max(13, size / 4)));
                    g2.setColor(Color.BLACK);
                    g2.drawString(tag, x + 4, y + size - 3);
                    g2.setColor(tagColor);
                    g2.drawString(tag, x + 3, y + size - 4);
                }
                g2.dispose();
            }
        };
        l.setPreferredSize(new Dimension(CELL, CELL));
        if (id > 0) {
            l.setToolTipText(CharactersView.itemTooltip(id, enchants, pack));
            ItemLinks.link(l, id);
        }
        return l;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        return l;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (!(c instanceof JLabel)) c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }
}
