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
            grid.add(equipped(id, CharactersView.enchantIds(c, slot), SLOT_NAMES[slot], slot == 3 ? amuletNote(c) : null));
        }
        return left(grid);
    }

    /**
     * With an Amulet of Restoration in the ring slot: "at 182 HP", the life below which it fires (its share of the
     * character's maximum life as the game last reported it); the details are in its tooltip.
     */
    private static JLabel amuletNote(Char c) {
        int ring = c.equipment != null && c.equipment.length > 3 ? c.equipment[3] : -1;
        lootgobbler.Amulets.Amulet a = lootgobbler.Amulets.of(ring);
        if (a == null) return null;
        int life = c.maxHp;
        boolean estimated = life <= 0;
        if (estimated) {   // not seen live yet: the class's maximum plus what the gear adds
            int[] max = ClassSlots.maxStats(c.classType);
            int[] bonus = new int[STATS.length];
            if (c.equipment != null) for (int id : c.equipment) if (id > 0) addBonuses(bonus, ItemDb.get().item(id));
            life = (max == null ? 0 : max[0]) + bonus[0];
        }
        int at = (int) Math.round(life * a.threshold());
        long saved = lootgobbler.AmuletSaves.get().all().stream().filter(s -> s.charId == c.charId).count();
        JLabel l = new JLabel(String.format(Locale.ROOT, "at %dHP", at));   // an estimate says so in the tooltip
        l.setFont(l.getFont().deriveFont(Math.max(10f, l.getFont().getSize2D() - 3)));   // about as wide as the icon
        l.setForeground(new Color(0x3A9BFF));   // the amulet's blue, as on the damage graph
        l.setHorizontalAlignment(SwingConstants.CENTER);
        l.setToolTipText(String.format(Locale.ROOT, "<html>%s fires when you take damage below %,d life (%d%% of %,d%s)<br>"
                        + "2 seconds invulnerable, every %d minutes%s</html>", a.name(), at, Math.round(a.threshold() * 100), life,
                estimated ? ", estimated until this character is played with Loot Gobbler running" : "", a.cooldownS() / 60,
                saved > 0 ? "<br>Saved this character " + saved + (saved == 1 ? " time" : " times") : ""));
        return l;
    }

    private static JComponent equipped(int id, int[] enchants, String slotName, JComponent underIcon) {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        JLabel icon = new JLabel(id > 0 ? CharactersView.sprite(id, 44) : null);
        icon.setPreferredSize(new Dimension(48, 48));
        icon.setVerticalAlignment(SwingConstants.TOP);
        icon.setToolTipText(CharactersView.itemTooltip(id, enchants, slotName));
        ItemLinks.link(icon, id);
        if (underIcon == null) {
            p.add(icon, BorderLayout.WEST);
        } else {   // e.g. the amulet's "at 182HP" right below its icon
            JPanel west = new JPanel(new BorderLayout());
            west.setOpaque(false);
            icon.setHorizontalAlignment(SwingConstants.CENTER);
            west.add(icon, BorderLayout.NORTH);
            west.add(underIcon, BorderLayout.CENTER);
            p.add(west, BorderLayout.WEST);
        }
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
    /** Whether any stat is picked for the suggested loadout. */
    static boolean anyStatChosen() {
        return !chosenStats().isEmpty();
    }

    static JComponent ideal(Char c, Runnable rerender) {
        Set<String> chosen = chosenStats();
        JPanel p = column();
        int[] types = ClassSlots.of(c.classType);
        if (types == null) {
            p.add(left(muted("Unknown class.")));
            return p;
        }
        // the stat boxes are the toggles (gold when chosen); with none chosen, the box's header says so
        List<Integer> pool = pool(c);
        // the four items in one column (weapon, ability, armor, ring), next to the four rows of stats
        // a plain column (a GridBag drops every item to its minimum size, wrapping all names, once one is too wide)
        JPanel grid = new FitPanel(null, false);
        grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
        int[] suggested = new int[STATS.length], now = new int[STATS.length];   // item bonuses per stat
        double[] enchanted = new double[STATS.length];   // enchantment bonuses of the suggested items, where known
        for (int slot : new int[]{0, 1, 2, 3}) {
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
            // the enchantments are only known for what the character wears, so only a kept item brings its own
            if (best == current && c.enchantIds != null && slot < c.enchantIds.length && c.enchantIds[slot] != null) {
                for (int e : c.enchantIds[slot]) {
                    if (e < 0) continue;
                    for (Map.Entry<String, Double> b : lootgobbler.EnchantStats.of(e).entrySet()) {
                        int i = Arrays.asList(STATS).indexOf(b.getKey());
                        if (i >= 0) enchanted[i] += b.getValue();
                    }
                }
            }
            if (grid.getComponentCount() > 0) grid.add(Box.createVerticalStrut(6));
            JComponent item = idealItem(best, it, chosen, best == current, SLOT_NAMES[slot]);
            item.setAlignmentX(Component.LEFT_ALIGNMENT);
            grid.add(item);
        }
        // the character's stats on the left (as with the suggestion), the four items on the right
        JPanel row = new JPanel() {
            @Override
            public Dimension getMaximumSize() {   // as tall as it is now, not as when it was built
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        JComponent stats = statTable(ClassSlots.maxStats(c.classType), suggested, now, enchanted, chosen, rerender);
        stats.setAlignmentY(Component.TOP_ALIGNMENT);
        grid.setAlignmentY(Component.TOP_ALIGNMENT);
        row.add(stats);
        row.add(Box.createHorizontalStrut(12));
        row.add(grid);
        row.add(Box.createHorizontalGlue());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        // the item stat lines use the width there is: one line if it fits, else wrapped to it (redone on resize)
        row.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                int room = row.getWidth() - stats.getPreferredSize().width - 12 - ITEM_ICON;
                if (room > 0 && fitStatLines(grid, room)) {
                    row.revalidate();
                    row.repaint();
                }
            }
        });
        p.add(row);
        return p;
    }

    /** Re-wraps the item stat lines under c to this width (one line where they fit); true if any changed. */
    private static boolean fitStatLines(Container c, int width) {
        boolean changed = false;
        for (Component k : c.getComponents()) {
            if (k instanceof JLabel l && l.getClientProperty(STATS_HTML) instanceof String stats) {
                JLabel probe = new JLabel("<html>" + stats + "</html>");
                probe.setFont(l.getFont());
                String text = "<html>" + stats + "</html>";
                if (probe.getPreferredSize().width > width) {
                    // Swing's HTML draws a CSS width wider than asked for: measure by how much, then ask for less
                    JLabel ruler = new JLabel("<html><div style='width:100px'>" + stats + "</div></html>");
                    ruler.setFont(l.getFont());
                    double scale = ruler.getPreferredSize().width / 100.0;
                    int css = (int) Math.max(80, (width - 4) / Math.max(1.0, scale));
                    text = "<html><div style='width:" + css + "px'>" + stats + "</div></html>";
                }
                if (!text.equals(l.getText())) {
                    l.setText(text);
                    changed = true;
                }
            } else if (k instanceof Container sub) {
                changed |= fitStatLines(sub, width);
            }
        }
        return changed;
    }

    private static void addBonuses(int[] into, ItemDb.Item it) {
        if (it == null) return;
        for (ItemDb.Stat st : it.onEquip) {
            int i = Arrays.asList(STATS).indexOf(st.stat());
            if (i >= 0) into[i] += st.amount();
        }
    }

    /**
     * The eight stats as they would be with the suggested loadout, a box each in two columns of four (life | mana,
     * attack | defense, ...): the stat's name, then "x (+y) (+z)": x the total (the class's maximum plus both
     * bonuses), y what the items add, z (blue) what their enchantments add. Chosen stats have a gold name; the
     * tooltip compares the items with what the character wears now.
     */
    private static JComponent statTable(int[] max, int[] suggested, int[] now, double[] enchanted, Set<String> chosen,
                                        Runnable rerender) {
        JPanel t = new FitPanel(new GridLayout(4, 2, 6, 6), true);   // never squeezed: the item names give way first
        t.setOpaque(false);
        for (int s = 0; s < STATS.length; s++) {
            int base = max == null ? 0 : max[s], items = suggested[s];
            int ench = (int) Math.floor(enchanted[s] + 1e-9);   // fractions (e.g. 1.4) count only once whole
            int diff = items - now[s];
            StringBuilder tip = new StringBuilder("<html>" + STAT_LABELS[s] + "<br>");
            if (max != null) tip.append("Class maximum ").append(NUM.format(base)).append("<br>");
            tip.append("Items ").append(signed(items)).append("<br>Enchantments ").append(signed(ench));
            if (enchanted[s] != ench) tip.append(String.format(Locale.ROOT, " (%.1f)", enchanted[s]));
            if (diff != 0) tip.append("<br>").append(signed(diff)).append(" compared with what it wears now");
            tip.append("<br><i>Click to ").append(chosen.contains(STATS[s]) ? "stop aiming for it" : "aim for it").append("</i>");
            // the enchantments are not counted in yet (only in the tooltip), so x is the maximum plus the items
            StatBox box = new StatBox(STAT_SHORT[s], base + items, items, chosen.contains(STATS[s]), tip + "</html>");
            String stat = STATS[s];
            box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Clicks.onClick(box, () -> {   // toggles the stat; the suggestion follows
                Set<String> picked = chosenStats();
                if (!picked.remove(stat)) picked.add(stat);
                PropertiesManager.setProperties(IDEAL_KEY, String.join(",", picked));
                rerender.run();
            });
            t.add(box);
        }
        return t;
    }

    /**
     * A panel that is never stretched beyond its current preferred size (and, if fixed, never squeezed below it).
     * Asked each layout, not frozen when built: HTML labels can report another size once they have their font.
     */
    private static final class FitPanel extends JPanel {
        private final boolean fixed;

        FitPanel(LayoutManager layout, boolean fixed) {
            super(layout);
            this.fixed = fixed;
            setOpaque(false);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return fixed ? getPreferredSize() : super.getMinimumSize();
        }
    }

    private static String signed(int n) {
        return (n >= 0 ? "+" : "") + n;
    }

    /**
     * One stat: a framed dark box with the name on top and "x (+y)" below: white at the class's maximum, green when
     * the items raise it, orange when they lower it. All boxes are as wide as the widest possible value needs.
     */
    private static final class StatBox extends JComponent {
        private static final Color UP = Palette.GREEN, DOWN = new Color(0xFF8A2A);
        private static final String WIDEST = "9,999 (+999)";
        private final String name;
        private final String text;
        private final int items;
        private final boolean chosen;

        StatBox(String name, int total, int items, boolean chosen, String tooltip) {
            this.name = name;
            this.text = NUM.format(total) + " (" + signed(items) + ")";
            this.items = items;
            this.chosen = chosen;
            setToolTipText(tooltip);
            setFont(new JLabel().getFont());
        }

        private Font big() {
            return getFont().deriveFont(getFont().getSize2D() * 1.15f);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics small = getFontMetrics(getFont()), large = getFontMetrics(big());
            return new Dimension(large.stringWidth(WIDEST) + 30, small.getHeight() + large.getHeight() + 14);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color accent = Section.color("LootGobbler.gold", Palette.GOLD);
            // the inside: dark, or gold-tinted when the stat is chosen
            g2.setColor(chosen ? new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 55) : new Color(0, 0, 0, 70));
            g2.fillRect(3, 3, w - 6, h - 6);
            g2.setColor(chosen ? accent : new Color(255, 255, 255, 40));   // a frame with open corners
            int c = 8;
            g2.fillRect(c, 0, w - 2 * c, 3);
            g2.fillRect(c, h - 3, w - 2 * c, 3);
            g2.fillRect(0, c, 3, h - 2 * c);
            g2.fillRect(w - 3, c, 3, h - 2 * c);

            FontMetrics fm = g2.getFontMetrics(getFont());
            g2.setFont(getFont());
            g2.setColor(chosen ? Section.color("LootGobbler.gold", Palette.GOLD) : Palette.MUTED);
            g2.drawString(name, (w - fm.stringWidth(name)) / 2, 6 + fm.getAscent());

            Font big = big();
            FontMetrics lm = g2.getFontMetrics(big);
            g2.setFont(big);
            int x = (w - lm.stringWidth(text)) / 2, y = 6 + fm.getHeight() + lm.getAscent();
            g2.setColor(items > 0 ? UP : items < 0 ? DOWN : Color.WHITE);
            g2.drawString(text, x, y);
            g2.dispose();
        }
    }

    private static final Color OTHER_STAT = new Color(0x3A9BFF);   // item stats that are not among the chosen
    private static final String STATS_HTML = "lootgobbler.statsHtml";   // an item's stat line, kept to re-wrap it
    private static final int ITEM_ICON = 70;   // an item's icon and the gap after it, left of its text

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String hex(Color c) {
        return String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }

    private static JComponent idealItem(int id, ItemDb.Item it, Set<String> chosen, boolean equipped, String slotName) {
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        JLabel icon = new JLabel(id > 0 ? CharactersView.sprite(id, 48) : null);
        icon.setPreferredSize(new Dimension(60, 52));   // room on the left; centered beside the text
        icon.setHorizontalAlignment(SwingConstants.RIGHT);
        icon.setVerticalAlignment(SwingConstants.CENTER);
        ItemLinks.link(icon, id);
        p.add(icon, BorderLayout.WEST);
        JPanel text = column();
        String name = id > 0 ? esc(CharactersView.itemName(id)) : "No " + slotName.toLowerCase(Locale.ROOT);
        // what it wears already (also when nothing gives the chosen stats: then it keeps its item), after the name
        text.add(new JLabel(equipped && id > 0 ? "<html>" + name + " <span style='color:" + hex(Palette.MUTED)
                + "'>(equipped)</span></html>" : "<html>" + name + "</html>"));
        // every stat the item gives: the chosen ones first in green, the others after them in blue
        StringBuilder picked = new StringBuilder(), other = new StringBuilder();
        if (it != null) {
            for (ItemDb.Stat st : it.onEquip) {
                if (Arrays.asList(STATS).indexOf(st.stat()) < 0) continue;
                StringBuilder to = chosen.contains(st.stat()) ? picked : other;
                // "+3&nbsp;WIS": a stat never splits across lines, only the gaps between stats wrap
                to.append(st.amount() > 0 ? "+" : "").append(st.amount()).append("&nbsp;").append(shortName(st.stat())).append("&nbsp; ");
            }
        }
        if (picked.length() + other.length() > 0) {
            // items with many stats wrap onto a second line instead of making the column very wide
            String stats = "<span style='color:" + hex(Palette.GREEN) + "'>" + picked + "</span><span style='color:"
                    + hex(OTHER_STAT) + "'>" + other + "</span>";
            JLabel bonus = new JLabel("<html>" + stats + "</html>");   // one line; wraps only if the panel is too narrow
            bonus.putClientProperty(STATS_HTML, stats);
            bonus.putClientProperty("FlatLaf.styleClass", "small");
            text.add(bonus);
        }
        JPanel middle = new JPanel(new GridBagLayout());   // the text centered on the icon's height
        middle.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1;
        middle.add(text, c);
        p.add(middle, BorderLayout.CENTER);
        return p;
    }

    /** Items the character could wear: what it carries, and what you hold on its side (seasonal or not). */
    private static List<Integer> pool(Char c) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (c.slots != null) for (int id : c.slots) if (id > 0) ids.add(id);
        for (Map.Entry<Integer, CollectionStore.Entry> e : CollectionStore.get().snapshot().entrySet()) {
            if (CollectionStore.get().held(e.getKey()) == 0) continue;
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
        f.add(new Fact("Your only copy", id -> CollectionStore.get().held(id) == 1 ? 1 : Double.NaN,
                id -> "1 copy"));
        f.add(new Fact("Most copies", id -> CollectionStore.get().held(id) > 1 ? CollectionStore.get().held(id) : Double.NaN,
                id -> CollectionStore.get().held(id) + " copies"));
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
        // highlights: the facts in an order fixed per character, the first three whose item is not shown yet. A fact
        // is always true over everything carried ("most enchantments" is the item with the most, or one of those tied
        // for it); the facts won by an item this character's class can wear come first, the others fill up
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
            Map<Integer, Double> values = new HashMap<>();
            double bestValue = Double.NaN;
            for (int id : carried) {
                double v;
                try {
                    v = f.measure().applyAsDouble(id);
                } catch (Exception e) {
                    v = Double.NaN;
                }
                if (Double.isNaN(v)) continue;
                values.put(id, v);
                if (Double.isNaN(bestValue) || v > bestValue) bestValue = v;
            }
            if (Double.isNaN(bestValue)) continue;
            int best = -1;   // among the items tied for the top value: one not shown yet (a wearable one first)
            for (int id : carried) {
                Double v = values.get(id);
                if (v == null || v != bestValue || used.contains(id) || (pass == 0 && !wearable.contains(id))) continue;
                best = id;
                break;
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
