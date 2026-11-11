package lootgobbler.ui;

import lootgobbler.CharacterStore.Char;
import lootgobbler.ClassSlots;
import lootgobbler.CollectionStore;
import lootgobbler.EnchantIcons;
import lootgobbler.Holdings;
import lootgobbler.ItemDb;
import lootgobbler.enchant.Effect;
import lootgobbler.enchant.EnchantPlanner;
import lootgobbler.enchant.Enchantment;
import lootgobbler.enchant.Enchantments;
import lootgobbler.enchant.Rarity;
import lootgobbler.enchant.WeaponDps;
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
    private static final String DEF_KEY = "idealEnemyDef";
    private static final String SCOPE_KEY = "idealScope";
    private static final String REPLACED_KEY = "idealShowReplaced";   // show the enchantments a suggestion replaces
    private static final String LOCK_KEY = "idealLocks";   // "charId:slot:enchantment;..." kept when planning
    /**
     * Where the suggestion takes its items from: what the character wears (only its enchantments are planned), also
     * its inventory and backpack, or everything you have on its side (vaults, gift chest, other characters).
     */
    private static final String[] SCOPES = {"current", "local", "global"};
    private static final String[] SCOPE_TIPS = {
            "Current: the items the character wears, with the best enchantments for them",
            "Local: the items the character wears or carries (inventory, backpack)",
            "Global: everything on its side (seasonal or not): vaults, gift chest, other characters"};
    private static final int MAX_ENEMY_DEF = 100;   // the slider's range for the enemy defense DPS counts against
    private static final int CELL = 52;
    private static final Color UT = new Color(0x8049A8), ST = new Color(0xE07B2A), TIER = new Color(0xB07D0A);

    private CharacterDetail() {
    }

    // ---- loadout ----------------------------------------------------------------------------------------------

    /**
     * Weapon and armor on top, ability and ring below: each item with its enchantments by name. Beside them, past a
     * gold line, the reusables in the quick slots, one below the other over the full height.
     */
    static JComponent loadout(Char c) {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.NORTHWEST;
        int[][] at = {{0, 0}, {0, 1}, {1, 0}, {1, 1}};   // weapon, ability, armor, ring: column, row
        for (int slot = 0; slot < 4; slot++) {
            int id = c.equipment != null && slot < c.equipment.length ? c.equipment[slot] : -1;
            g.gridx = at[slot][0];
            g.gridy = at[slot][1];
            g.insets = new Insets(g.gridy == 0 ? 0 : 8, g.gridx == 0 ? 0 : 20, 0, 0);
            grid.add(equipped(id, CharactersView.enchantIds(c, slot), SLOT_NAMES[slot], slot == 3 ? amuletNote(c) : null), g);
        }
        g.gridx = 2;
        g.gridy = 0;
        g.gridheight = 2;
        g.fill = GridBagConstraints.VERTICAL;
        g.insets = new Insets(0, 18, 0, 16);
        grid.add(new SectionLine(), g);
        g.gridx = 3;
        g.fill = GridBagConstraints.BOTH;
        g.insets = new Insets(0, 0, 0, 0);
        grid.add(reusables(c), g);
        g.gridx = 4;   // the space left over stays on the right
        g.weightx = 1;
        g.fill = GridBagConstraints.NONE;
        grid.add(Box.createHorizontalStrut(0), g);
        return left(grid);
    }

    private static final int REUSABLE_ICON = 32;

    /** The quick slots holding reusables (items used from there again and again), spread over the height. */
    private static JComponent reusables(Char c) {
        List<Integer> ids = new ArrayList<>();
        if (c.quickslots != null) {
            for (int id : c.quickslots) {
                ItemDb.Item it = id > 0 ? ItemDb.get().item(id) : null;
                if (it != null && it.quickslotOnly) ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            JLabel none = muted(c.quickslots == null ? "Quick slots not seen yet" : "No reusables in quick slots");
            none.putClientProperty("FlatLaf.styleClass", "small");
            none.setVerticalAlignment(SwingConstants.CENTER);
            return none;
        }
        // always three rows of a third of the height: the first at the top, the others below only if there
        JPanel p = new JPanel(new GridLayout(lootgobbler.LootEvents.QUICKSLOTS.length, 1, 0, 4));
        p.setOpaque(false);
        for (int id : ids) {
            JLabel l = new JLabel(CharactersView.itemName(id), CharactersView.sprite(id, REUSABLE_ICON), SwingConstants.LEFT);
            l.setIconTextGap(8);
            l.setForeground(Section.color("LootGobbler.ink", Color.DARK_GRAY));
            l.setToolTipText(CharactersView.itemTooltip(id, null, "Quick slot"));
            ItemLinks.link(l, id);
            p.add(l);
        }
        for (int k = ids.size(); k < lootgobbler.LootEvents.QUICKSLOTS.length; k++) p.add(Box.createGlue());   // empty rows
        return p;
    }

    /** A vertical gold line with short caps at both ends, setting off a new part of a box. */
    private static final class SectionLine extends JComponent {
        private static final int W = 7, CAP = 2;

        SectionLine() {
            setPreferredSize(new Dimension(W, 40));
            setMinimumSize(new Dimension(W, 0));
        }

        @Override
        protected void paintComponent(Graphics g) {
            int h = getHeight(), mid = W / 2;
            g.setColor(Palette.GOLD_DARK);
            g.fillRect(mid - 1, 0, 3, h);   // the line with a dark edge
            g.fillRect(0, 0, W, CAP + 1);   // the caps
            g.fillRect(0, h - CAP - 1, W, CAP + 1);
            g.setColor(Palette.GOLD);
            g.fillRect(mid, 0, 1, h);
            g.fillRect(1, 0, W - 2, CAP);
            g.fillRect(1, h - CAP, W - 2, CAP);
        }
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
        return ideal(c, chosenStats(), rerender);
    }

    /** The suggested loadout for these chosen stats (game names: MAXHP, ATT...). */
    static JComponent ideal(Char c, Set<String> chosen, Runnable rerender) {
        JPanel p = column();
        int[] types = ClassSlots.of(c.classType);
        if (types == null) {
            p.add(left(muted("Unknown class.")));
            return p;
        }
        // the stat boxes are the toggles (gold when chosen); with none chosen, the box's header says so
        List<Integer> pool = pool(c);
        int scope = scope();
        // the four items in one column (weapon, ability, armor, ring), next to the four rows of stats
        // a plain column (a GridBag drops every item to its minimum size, wrapping all names, once one is too wide)
        JPanel grid = new FitPanel(null, false);
        grid.setLayout(new BoxLayout(grid, BoxLayout.Y_AXIS));
        int[] suggested = new int[STATS.length], now = new int[STATS.length];   // item bonuses per stat
        int[] max = ClassSlots.maxStats(c.classType);
        double def = enemyDef();
        // per slot, what it adds as worn now: the item's stats and its enchantments' (for the DPS aims)
        double[][] wornItems = new double[4][], wornEnch = new double[4][];
        ItemDb.Item[] worn = new ItemDb.Item[4];
        for (int slot : new int[]{0, 1, 2, 3}) {
            int current = c.equipment != null && slot < c.equipment.length ? c.equipment[slot] : -1;
            worn[slot] = current > 0 ? ItemDb.get().item(current) : null;
            addBonuses(now, worn[slot]);
            int[] one = new int[STATS.length];
            addBonuses(one, worn[slot]);
            wornItems[slot] = toDouble(one);
        }
        List<Enchantment> wornWeaponEnch = EnchantPlanner.of(wornEnchants(c, 0));
        List<List<Enchantment>> wornLists = new ArrayList<>();
        for (int slot : new int[]{0, 1, 2, 3}) {
            wornLists.add(EnchantPlanner.of(wornEnchants(c, slot)));
            wornEnch[slot] = EnchantPlanner.gains(wornLists.get(slot), toDouble(now));
        }
        double dpsNow = WeaponDps.dps(worn[0], wornWeaponEnch, total(max, wornItems, wornEnch, -1, "ATT"),
                total(max, wornItems, wornEnch, -1, "DEX"), def);
        // first the best item and copy per slot (relative enchantments counted from what it wears now), then their
        // enchantments again from what the suggested items add together
        Pick[] picks = new Pick[4];
        for (int slot : new int[]{0, 1, 2, 3}) {
            EnchantPlanner.Aim aim = aim(max, wornItems, wornEnch, wornLists, slot, worn[0], wornWeaponEnch, def);
            picks[slot] = bestPick(c, slot, types[slot], pool, chosen, toDouble(now), aim, scope);
            addBonuses(suggested, picks[slot].item());
        }
        // the aims again from the suggestion: its items, and the enchantments planned for them so far
        double[][] sugItems = new double[4][], sugEnch = new double[4][];
        List<List<Enchantment>> sugLists = new ArrayList<>();
        for (int slot : new int[]{0, 1, 2, 3}) {
            sugLists.add(picks[slot].plan().picks().isEmpty() ? EnchantPlanner.of(picks[slot].enchants()) : picks[slot].plan().picks());
            int[] one = new int[STATS.length];
            addBonuses(one, picks[slot].item());
            sugItems[slot] = toDouble(one);
            sugEnch[slot] = picks[slot].plan().picks().isEmpty()
                    ? EnchantPlanner.gains(EnchantPlanner.of(picks[slot].enchants()), toDouble(suggested))
                    : picks[slot].plan().gains();
        }
        List<Enchantment> sugWeaponEnch = picks[0].plan().picks().isEmpty()
                ? EnchantPlanner.of(picks[0].enchants()) : picks[0].plan().picks();
        double[] have = new double[STATS.length], planned = new double[STATS.length];   // enchantments now / suggested
        for (int slot : new int[]{0, 1, 2, 3}) {
            Pick pk = picks[slot];
            EnchantPlanner.Aim aim = aim(max, sugItems, sugEnch, sugLists, slot, picks[0].item(), sugWeaponEnch, def);
            List<Enchantment> locked = scope == 0 && pk.equipped() ? locked(c.charId, slot, pk) : List.of();
            EnchantPlanner.Plan plan = pk.slots() > 0
                    ? EnchantPlanner.best(pk.item(), pk.slots(), chosen, toDouble(suggested),
                    slot == 0 ? aim.onWeapon(pk.item()) : aim, locked) : EnchantPlanner.Plan.NONE;
            picks[slot] = pk = pk.with(plan);
            double[] mine = EnchantPlanner.gains(EnchantPlanner.of(pk.enchants()), toDouble(suggested));
            double[] finalGains = EnchantPlanner.gains(finalEnchants(pk), toDouble(suggested));
            for (int i = 0; i < STATS.length; i++) {
                have[i] += mine[i];
                // what the suggested enchantments add; slots they leave free keep what is there (if it does not clash)
                planned[i] += finalGains[i];
            }
            if (grid.getComponentCount() > 0) grid.add(Box.createVerticalStrut(6));
            Locks locks = scope == 0 && pk.equipped() ? new Locks(c.charId, slot, rerender) : null;
            JComponent item = idealItem(pk, chosen, SLOT_NAMES[slot], locks);
            item.setAlignmentX(Component.LEFT_ALIGNMENT);
            grid.add(item);
        }
        double[] enchanted = chosen.isEmpty() ? have : planned;
        List<Enchantment> weaponEnch = finalEnchants(picks[0]);
        double att = (max == null ? 0 : max[Arrays.asList(STATS).indexOf("ATT")]) + suggested[Arrays.asList(STATS).indexOf("ATT")]
                + enchanted[Arrays.asList(STATS).indexOf("ATT")];
        double dex = (max == null ? 0 : max[Arrays.asList(STATS).indexOf("DEX")]) + suggested[Arrays.asList(STATS).indexOf("DEX")]
                + enchanted[Arrays.asList(STATS).indexOf("DEX")];
        // the character's stats on the left (as with the suggestion), the four items on the right
        JPanel row = new JPanel() {
            @Override
            public Dimension getMaximumSize() {   // as tall as it is now, not as when it was built
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        JPanel stats = new FitPanel(null, true) {
            @Override
            public Component add(Component comp) {   // everything as wide as the column, from its left edge
                if (comp instanceof JComponent j) j.setAlignmentX(Component.LEFT_ALIGNMENT);
                return super.add(comp);
            }
        };
        stats.setLayout(new BoxLayout(stats, BoxLayout.Y_AXIS));
        boolean hasWeapon = WeaponDps.isWeapon(picks[0].item()) || WeaponDps.isWeapon(worn[0]);
        stats.add(scopeToggle(scope, rerender));
        stats.add(Box.createVerticalStrut(4));
        stats.add(showReplacedToggle(rerender));
        if (hasWeapon) {
            stats.add(Box.createVerticalStrut(4));
            stats.add(enemyDefSlider((int) def, rerender));
        }
        stats.add(Box.createVerticalStrut(6));
        stats.add(goldLine());
        stats.add(Box.createVerticalStrut(18));
        // loot and regeneration: as suggested (the planned enchantments, else what the copies have) and as worn now
        List<Enchantment> sugAll = new ArrayList<>(), nowAll = new ArrayList<>();
        for (int slot : new int[]{0, 1, 2, 3}) {
            sugAll.addAll(finalEnchants(picks[slot]));
            nowAll.addAll(wornLists.get(slot));
        }
        double others = 0;   // crucible, blood ritual, loot drop boost
        for (lootgobbler.LootBonus.Part part : lootgobbler.LootBonus.parts(c, System.currentTimeMillis())) {
            if (part.known() && !part.source().equals("Enchantments")) others += part.percent();
        }
        double lootNow = others + EnchantPlanner.loot(nowAll), lootSug = others + EnchantPlanner.loot(sugAll);
        double[] regenNow = new double[2], regenSug = new double[2];
        String[][] regenStats = {{"HP", "MAXHP", "VIT"}, {"MP", "MAXMP", "WIS"}};
        for (int k = 0; k < 2; k++) {
            int iMax = Arrays.asList(STATS).indexOf(regenStats[k][1]), iAttr = Arrays.asList(STATS).indexOf(regenStats[k][2]);
            regenNow[k] = EnchantPlanner.regen(regenStats[k][0], total(max, wornItems, wornEnch, -1, regenStats[k][1]),
                    total(max, wornItems, wornEnch, -1, regenStats[k][2]), nowAll);
            regenSug[k] = EnchantPlanner.regen(regenStats[k][0],
                    (max == null ? 0 : max[iMax]) + suggested[iMax] + enchanted[iMax],
                    (max == null ? 0 : max[iAttr]) + suggested[iAttr] + enchanted[iAttr], sugAll);
        }
        JPanel extras = new JPanel(new GridLayout(0, 2, 6, 6));   // one cell each, as the stat boxes
        extras.setOpaque(false);
        JPanel lootRow = new JPanel(new GridLayout(0, 2, 6, 6));
        lootRow.setOpaque(false);
        extras.add(aimBox("HP/s", EnchantPlanner.HP_REGEN, oneDecimal(regenSug[0]), regenSug[0] - regenNow[0],
                "<html><b>Life regenerated per second</b> in combat<br>1 + 0.24 &times; VIT, plus flat and percentage"
                        + " regeneration enchantments (doubled out of combat)<br>Now: " + oneDecimal(regenNow[0]), chosen, rerender));
        extras.add(aimBox("MP/s", EnchantPlanner.MP_REGEN, oneDecimal(regenSug[1]), regenSug[1] - regenNow[1],
                "<html><b>Mana regenerated per second</b> in combat<br>0.5 + 0.12 &times; WIS, plus flat and percentage"
                        + " regeneration enchantments (doubled out of combat)<br>Now: " + oneDecimal(regenNow[1]), chosen, rerender));
        lootRow.add(aimBox("LOOT", EnchantPlanner.LOOT, "+" + oneDecimal(lootSug) + "%", lootSug - lootNow,
                "<html><b>Loot bonus</b><br>Enchantments " + oneDecimal(lootSug - others) + "%, crucible / ritual / boosts "
                        + oneDecimal(others) + "%<br>Now: " + oneDecimal(lootNow) + "%", chosen, rerender));
        // HP/s | MP/s, DPS, then the eight stats (HP | MP first), then loot
        stats.add(extras);
        if (hasWeapon) {
            stats.add(Box.createVerticalStrut(6));
            stats.add(dpsBox(picks[0].item(), weaponEnch, att, dex, def, dpsNow, chosen, rerender));
        }
        stats.add(Box.createVerticalStrut(14));
        stats.add(statTable(new int[]{0, 1, 2, 3, 4, 5, 6, 7}, max, suggested, now, enchanted, have, chosen, rerender));
        stats.add(Box.createVerticalStrut(14));
        stats.add(lootRow);
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

    /**
     * The item and copy suggested for a slot: id (-1 none), its enchantments (as the copy carries them, -1 = empty
     * slot; null = not known), where it is ("equipped", "Vault chest", "on Priest"...), and the enchantments
     * suggested for it.
     */
    record Pick(int id, ItemDb.Item item, List<Integer> enchants, String where, EnchantPlanner.Plan plan) {
        boolean equipped() {
            return "equipped".equals(where);
        }

        /** Enchantment slots (its rarity); 0 if not known. */
        int slots() {
            return enchants == null || item == null || item.enchantSlots == null ? 0 : enchants.size();
        }

        Pick with(EnchantPlanner.Plan p) {
            return new Pick(id, item, enchants, where, p);
        }
    }

    /**
     * The best item for a slot with the chosen stats: what the item gives, plus what enchanting the copy could add
     * (as many enchantments as its rarity has slots), weighed as EnchantPlanner weighs stats. Every copy you have on
     * the character's side counts on its own, as each has its own rarity. Ties: the one giving the most of all stats,
     * then what it wears. Nothing chosen (or nothing helping): what it wears.
     */
    private static Pick bestPick(Char c, int slot, int slotType, List<Integer> pool, Set<String> chosen, double[] gear,
                                 EnchantPlanner.Aim aim, int scope) {
        int current = c.equipment != null && slot < c.equipment.length ? c.equipment[slot] : -1;
        List<Integer> worn = wornEnchants(c, slot);
        boolean dps = chosen.contains(EnchantPlanner.DPS), weaponSlot = slot == 0;
        Pick keep = new Pick(current, current > 0 ? ItemDb.get().item(current) : null, worn, "equipped", EnchantPlanner.Plan.NONE);
        if (chosen.isEmpty() || scope == 0) return keep;   // current: only its enchantments are planned (later)
        Pick best = null;
        double bestScore = 0, bestAll = 0;
        Map<String, EnchantPlanner.Plan> planned = new HashMap<>();   // item/slots -> its best enchantments
        for (int id : pool) {
            ItemDb.Item it = ItemDb.get().item(id);
            if (it == null || it.slotType != slotType) continue;
            double[] own = new double[STATS.length];
            for (ItemDb.Stat st : it.onEquip) {
                int i = Arrays.asList(STATS).indexOf(st.stat());
                if (i >= 0) own[i] += st.amount();
            }
            List<Pick> copies = new ArrayList<>();
            if (id == current) copies.add(keep);
            // what the character carries, as last seen (inventory, backpack): every copy, tiered ones too
            if (c.slots != null) {
                for (int i = 4; i < c.slots.length; i++) {
                    if (c.slots[i] != id) continue;
                    int[] e = c.slotEnchantIds != null && i < c.slotEnchantIds.length ? c.slotEnchantIds[i] : null;
                    List<Integer> ench = null;
                    if (e != null) {
                        ench = new ArrayList<>();
                        for (int x : e) ench.add(x);
                    }
                    copies.add(new Pick(id, it, ench, i < 12 ? "inventory" : "backpack", EnchantPlanner.Plan.NONE));
                }
            }
            for (Holdings.Held h : Holdings.get().everyCopy(id)) {
                if (h.charId() == c.charId) continue;   // the character's own: worn and carried, added above
                if (scope == 1) continue;   // local: only what the character carries
                if (h.seasonal() != null && h.seasonal() != c.seasonal) continue;
                copies.add(new Pick(id, it, h.enchantIds(), where(c, h), EnchantPlanner.Plan.NONE));
            }
            if (copies.isEmpty() && scope == 2) copies.add(new Pick(id, it, null, "", EnchantPlanner.Plan.NONE));   // only its finds
            for (Pick p : copies) {
                double[] total = own.clone();
                EnchantPlanner.Plan plan = EnchantPlanner.Plan.NONE;
                if (p.slots() > 0) {
                    plan = planned.computeIfAbsent(id + "/" + p.slots(),
                            k -> EnchantPlanner.best(it, p.slots(), chosen, gear, weaponSlot ? aim.onWeapon(it) : aim));
                    for (int i = 0; i < total.length; i++) total[i] += plan.gains()[i];
                }
                double score = EnchantPlanner.score(total, chosen), all = EnchantPlanner.score(total, Set.of(STATS));
                score += EnchantPlanner.extras(total, plan.picks().isEmpty() ? EnchantPlanner.of(p.enchants()) : plan.picks(),
                        chosen, aim);   // loot and regeneration, when chosen
                if (dps) {   // the weapon itself (with what it could be enchanted with), or what attack and dexterity add
                    score += weaponSlot
                            ? aim.valueOf(it, plan.picks().isEmpty() ? EnchantPlanner.of(p.enchants()) : plan.picks(), total)
                            : aim.valueOf(aim.weapon(), aim.weaponEnchants(), total);
                }
                // ties: more enchantment slots (room for more later), then the most of all stats, then what it wears
                boolean tie = Math.abs(score - bestScore) <= 1e-9;
                boolean better = best == null || score > bestScore + 1e-9
                        || (tie && p.slots() > best.slots())
                        || (tie && p.slots() == best.slots() && (all > bestAll + 1e-9
                        || (Math.abs(all - bestAll) <= 1e-9 && p.equipped())));
                if (better) {
                    best = p.with(plan);
                    bestScore = score;
                    bestAll = all;
                }
            }
        }
        return best == null || bestScore <= 0 ? keep : best;
    }

    /**
     * The enchantments the copy ends up with: the suggested ones, and in the slots they leave free what the copy has
     * now (if it does not clash with them); with nothing suggested, what it has.
     */
    private static List<Enchantment> finalEnchants(Pick pk) {
        List<Enchantment> has = EnchantPlanner.of(pk.enchants());
        List<Enchantment> out = new ArrayList<>(pk.plan().picks());
        if (out.isEmpty()) return has;
        for (Enchantment e : has) {
            if (out.size() >= pk.slots()) break;
            if (out.contains(e) || out.stream().anyMatch(o -> o.conflicts(e))) continue;
            out.add(e);
        }
        return out;
    }

    /** The enchantments on what the character wears in a slot (-1 = empty slot); null if not known. */
    private static List<Integer> wornEnchants(Char c, int slot) {
        if (c.enchantIds == null || slot >= c.enchantIds.length || c.enchantIds[slot] == null) return null;
        List<Integer> out = new ArrayList<>();
        for (int e : c.enchantIds[slot]) out.add(e);
        return out;
    }

    /** A stat in total: the class's maximum plus what every slot but one (skip; -1 none) adds with items and enchantments. */
    private static double total(int[] max, double[][] items, double[][] enchants, int skip, String stat) {
        int i = Arrays.asList(STATS).indexOf(stat);
        double t = max == null ? 0 : max[i];
        for (int slot = 0; slot < items.length; slot++) {
            if (slot == skip) continue;
            if (items[slot] != null) t += items[slot][i];
            if (enchants[slot] != null) t += enchants[slot][i];
        }
        return t;
    }

    /** What aiming for DPS and regeneration starts from when choosing for a slot: everything but that slot. */
    private static EnchantPlanner.Aim aim(int[] max, double[][] items, double[][] enchants, List<List<Enchantment>> lists,
                                          int slot, ItemDb.Item weapon, List<Enchantment> weaponEnchants, double def) {
        List<Enchantment> others = new ArrayList<>();
        for (int i = 0; i < lists.size(); i++) if (i != slot) others.addAll(lists.get(i));
        return new EnchantPlanner.Aim(weapon, weaponEnchants == null ? List.of() : weaponEnchants,
                total(max, items, enchants, slot, "ATT"), total(max, items, enchants, slot, "DEX"), def, false,
                total(max, items, enchants, slot, "MAXHP"), total(max, items, enchants, slot, "MAXMP"),
                total(max, items, enchants, slot, "VIT"), total(max, items, enchants, slot, "WIS"),
                EnchantPlanner.percentRegen(others, "HP"), EnchantPlanner.percentRegen(others, "MP"));
    }

    private static String oneDecimal(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    /** A box for an aim that is not a stat (loot, regeneration): the value, the change from now, a toggle. */
    private static JComponent aimBox(String name, String key, String value, double change, String tip, Set<String> chosen,
                                     Runnable rerender) {
        String text = value + " (" + (change >= 0 ? "+" : "") + oneDecimal(change) + ")";
        int trend = change > 1e-9 ? 1 : change < -1e-9 ? -1 : 0;
        StatBox box = new StatBox(name, text, trend, chosen.contains(key),
                tip + "<br><i>Click to " + (chosen.contains(key) ? "stop aiming for it" : "aim for it") + "</i></html>");
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(box, () -> {
            Set<String> picked = chosenStats();
            if (!picked.remove(key)) picked.add(key);
            PropertiesManager.setProperties(IDEAL_KEY, String.join(",", picked));
            rerender.run();
        });
        return box;
    }

    private static int scope() {
        String v = PropertiesManager.getProperty(SCOPE_KEY);
        for (int i = 0; i < SCOPES.length; i++) if (SCOPES[i].equals(v)) return i;
        return 2;   // global, as before there was a choice
    }

    /** "Scope: local [toggle]": a click moves it on, current - local - global - current. */
    private static JComponent scopeToggle(int scope, Runnable rerender) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        row.setOpaque(false);
        row.setBorder(new EmptyBorder(0, -6, 0, 0));
        JLabel label = new JLabel("Scope: " + SCOPES[scope]);
        ThreeWaySwitch toggle = new ThreeWaySwitch(scope, to -> {
            label.setText("Scope: " + SCOPES[to]);
            PropertiesManager.setProperties(SCOPE_KEY, SCOPES[to]);
            SwingUtilities.invokeLater(rerender);   // after the knob is drawn where it stopped
        }, SCOPES);
        String tip = "<html>" + String.join("<br>", SCOPE_TIPS) + "<br><i>Click to switch to "
                + SCOPES[(scope + 1) % 3] + "</i></html>";
        toggle.setToolTipText(tip);
        label.setToolTipText(tip);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(label, toggle::advance);
        int widest = 0;   // as wide as the longest scope name, so the switch stays put when it changes
        for (String sc : SCOPES) widest = Math.max(widest, label.getFontMetrics(label.getFont()).stringWidth("Scope: " + sc));
        label.setPreferredSize(new Dimension(widest + 4, label.getPreferredSize().height));
        row.setLayout(new BorderLayout(8, 0));
        row.setBorder(new EmptyBorder(0, 0, 0, 0));
        row.add(label, BorderLayout.CENTER);   // the name on the left, the switch at the right edge
        JPanel right = new JPanel(new GridBagLayout());
        right.setOpaque(false);
        right.add(toggle);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    /**
     * The reset box for the Suggested Loadout's header: turns off every stat and aim, sets the scope to current and the
     * enemy DEF to 0.
     */
    static JComponent resetButton(Runnable rerender) {
        JComponent reset = resetBox(() -> {
            PropertiesManager.setProperties(IDEAL_KEY, "");
            PropertiesManager.setProperties(SCOPE_KEY, SCOPES[0]);
            PropertiesManager.setProperties(DEF_KEY, "0");
            rerender.run();
        });
        reset.setToolTipText("Reset: turn off every stat and aim, set the scope to current and the enemy DEF to 0");
        return reset;
    }

    /** A small framed box with a reset arrow (a circle with an arrow head), lit while the mouse is over it. */
    private static JComponent resetBox(Runnable onClick) {
        final int size = 26;
        JComponent box = new JComponent() {
            boolean hover;

            {
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseEntered(java.awt.event.MouseEvent e) {
                        hover = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e) {
                        hover = false;
                        repaint();
                    }
                });
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(size, size);
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
                int w = getWidth() - 1, h = getHeight() - 1;
                g2.setColor(hover ? new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), 55) : new Color(0, 0, 0, 70));
                g2.fillRoundRect(0, 0, w, h, 6, 6);
                g2.setColor(hover ? gold : new Color(255, 255, 255, 60));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(0, 0, w, h, 6, 6);
                // the arrow: three quarters of a circle, open at the top right, with its head there
                Color ink = hover ? gold : Palette.MUTED;
                g2.setColor(ink);
                double cx = w / 2.0, cy = h / 2.0, r = size * 0.26;
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(new java.awt.geom.Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 60, 290, java.awt.geom.Arc2D.OPEN));
                double ax = cx + r * Math.cos(Math.toRadians(60)), ay = cy - r * Math.sin(Math.toRadians(60));
                java.awt.geom.Path2D head = new java.awt.geom.Path2D.Double();
                head.moveTo(ax + 4.5, ay - 1.5);
                head.lineTo(ax - 1, ay - 4);
                head.lineTo(ax + 0.5, ay + 3);
                head.closePath();
                g2.fill(head);
                g2.dispose();
            }
        };
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(box, onClick);
        return box;
    }

    private static boolean showReplaced() {
        return "true".equals(PropertiesManager.getProperty(REPLACED_KEY));
    }

    /** "Show deleted enchants" with an on / off switch at the right end. */
    private static JComponent showReplacedToggle(Runnable rerender) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JLabel label = new JLabel("Show deleted enchants");
        ThreeWaySwitch toggle = ThreeWaySwitch.onOff(showReplaced(), to -> {
            PropertiesManager.setProperties(REPLACED_KEY, String.valueOf(to == 1));
            SwingUtilities.invokeLater(rerender);
        });
        String tip = "<html>In scope current: also list the enchantments the suggestion would replace (struck through),"
                + "<br>so you can lock them to keep them</html>";
        label.setToolTipText(tip);
        toggle.setToolTipText(tip);
        label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(label, toggle::advance);
        row.add(label, BorderLayout.CENTER);
        JPanel right = new JPanel(new GridBagLayout());
        right.setOpaque(false);
        right.add(toggle);
        row.add(right, BorderLayout.EAST);
        return row;
    }

    /** A thin gold line across the column. */
    private static JComponent goldLine() {
        JComponent line = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
                g.setColor(new Color(gold.getRed(), gold.getGreen(), gold.getBlue(), 170));
                g.fillRect(0, 0, getWidth(), 2);
            }

            @Override
            public Dimension getPreferredSize() {
                return new Dimension(10, 2);
            }

            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, 2);
            }
        };
        return line;
    }

    private static double enemyDef() {
        try {
            String v = PropertiesManager.getProperty(DEF_KEY);
            return v == null ? 0 : Math.max(0, Math.min(MAX_ENEMY_DEF, Integer.parseInt(v.trim())));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * "Enemy DEF [slider] 35": the defense DPS counts against. The number follows while dragging; the suggestion is
     * worked out again once it is let go.
     */
    private static JComponent enemyDefSlider(int def, Runnable rerender) {
        JSlider slider = new JSlider(0, MAX_ENEMY_DEF, def);
        slider.setOpaque(false);
        String gold = hex(Section.color("LootGobbler.gold", Palette.GOLD));
        slider.setPreferredSize(new Dimension(80, slider.getPreferredSize().height));   // the column sets the width
        slider.putClientProperty("FlatLaf.style", "trackValueColor: " + gold + "; trackColor: " + gold + "80; thumbColor: " + gold);
        slider.setMajorTickSpacing(25);
        slider.setMinorTickSpacing(5);
        slider.setSnapToTicks(true);
        slider.setFocusable(false);
        String tip = "<html>The enemy's defense: it comes off every shot (armor piercing ones ignore it, and at least"
                + " 10% of a shot always gets through).<br>Many small shots lose more to it than a few big ones.</html>";
        slider.setToolTipText(tip);
        JLabel value = new JLabel(String.valueOf(def));
        value.setPreferredSize(new Dimension(new JLabel("100").getPreferredSize().width + 2, value.getPreferredSize().height));
        slider.addChangeListener(e -> {
            value.setText(String.valueOf(slider.getValue()));
            if (!slider.getValueIsAdjusting() && slider.getValue() != def) {
                PropertiesManager.setProperties(DEF_KEY, String.valueOf(slider.getValue()));
                rerender.run();
            }
        });
        JLabel name = small(muted("Enemy DEF"));
        name.setToolTipText(tip);
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(name, BorderLayout.WEST);
        row.add(slider, BorderLayout.CENTER);
        row.add(value, BorderLayout.EAST);
        return row;
    }

    /**
     * Damage per second of the suggested weapon with its enchantments, attack and dexterity, against the enemy
     * defense set: "x (+y)", y compared with what the character wears now. A toggle like the stats; the tooltip shows
     * every subattack, and the DPS without defense.
     */
    private static JComponent dpsBox(ItemDb.Item weapon, List<Enchantment> enchants, double att, double dex, double def,
                                     double dpsNow, Set<String> chosen, Runnable rerender) {
        List<WeaponDps.Line> lines = WeaponDps.lines(weapon, enchants);
        double dps = 0;
        for (WeaponDps.Line l : lines) dps += l.dps(att, dex, def);
        StringBuilder tip = new StringBuilder("<html><b>Damage per second</b> against " + (int) def + " DEF<br>");
        tip.append("Attack ").append(Math.round(att)).append(": damage &times;")
                .append(String.format(Locale.ROOT, "%.2f", WeaponDps.damageMultiplier(att)))
                .append(" &nbsp; Dexterity ").append(Math.round(dex)).append(": ")
                .append(String.format(Locale.ROOT, "%.2f", WeaponDps.attacksPerSecond(dex))).append(" attacks/s<br>");
        String name = weapon == null ? "" : weapon.name;
        for (WeaponDps.Line l : lines) {
            tip.append(esc(l.from().equals(name) ? "Shot " + (l.index() + 1) : l.from())).append(": ")
                    .append(l.count() > 1 ? l.count() + " &times; " : "")
                    .append(NUM.format(Math.round(l.perShot(att, def)))).append(l.shot().armorPiercing() ? " (armor piercing)" : "")
                    .append(", ").append(String.format(Locale.ROOT, "%.2f", l.shotsPerSecond(dex))).append(" shots/s");
            if (l.burst() > 0) tip.append(" in bursts of ").append(l.burst());
            else if (Math.abs(l.rateOfFire() - 1) > 1e-9) tip.append(" (").append(Math.round(l.rateOfFire() * 100)).append("% rate)");
            tip.append(" = ").append(NUM.format(Math.round(l.dps(att, dex, def)))).append("<br>");
        }
        if (def > 0) {
            double open = 0;
            for (WeaponDps.Line l : lines) open += l.dps(att, dex, 0);
            tip.append("Without defense: ").append(NUM.format(Math.round(open))).append("<br>");
        }
        tip.append("Now: ").append(NUM.format(Math.round(dpsNow)))
                .append("<br><i>Click to ").append(chosen.contains(EnchantPlanner.DPS) ? "stop aiming for it" : "aim for it")
                .append("</i></html>");
        StatBox box = new StatBox("DPS", (int) Math.round(dps), (int) Math.round(dps - dpsNow), 0,
                chosen.contains(EnchantPlanner.DPS), tip.toString());
        box.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(box, () -> {
            Set<String> picked = chosenStats();
            if (!picked.remove(EnchantPlanner.DPS)) picked.add(EnchantPlanner.DPS);
            PropertiesManager.setProperties(IDEAL_KEY, String.join(",", picked));
            rerender.run();
        });
        return box;
    }

    /** Where a copy is, for the line after its name. */
    private static String where(Char c, Holdings.Held h) {
        if (h.charId() == 0) {   // short: "vault", "seasonal vault", "gift chest"...
            String place = Holdings.VAULT_CHEST.equals(h.slot()) ? "vault" : h.slot().toLowerCase(Locale.ROOT);
            boolean seasonal = Holdings.SEASONAL_VAULT.equals(h.vault()) && !place.startsWith("seasonal");
            return seasonal ? "seasonal " + place : place;
        }
        if (h.charId() == c.charId) return h.slot().toLowerCase(Locale.ROOT);
        for (Char o : lootgobbler.CharacterStore.get().all()) {
            if (o.charId == h.charId()) return "on " + (o.className == null ? "another character" : o.className);
        }
        return "on another character";
    }

    private static double[] toDouble(int[] a) {
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = a[i];
        return d;
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
    private static JComponent statTable(int[] which, int[] max, int[] suggested, int[] now, double[] enchanted,
                                        double[] have, Set<String> chosen, Runnable rerender) {
        JPanel t = new FitPanel(new GridLayout(0, 2, 6, 6), true);   // never squeezed: the item names give way first
        t.setOpaque(false);
        for (int s : which) {
            int base = max == null ? 0 : max[s], items = suggested[s];
            int ench = (int) Math.floor(enchanted[s] + 1e-9);   // fractions (e.g. 1.4) count only once whole
            int diff = items - now[s];
            StringBuilder tip = new StringBuilder("<html>" + STAT_LABELS[s] + "<br>");
            if (max != null) tip.append("Class maximum ").append(NUM.format(base)).append("<br>");
            tip.append("Items ").append(signed(items)).append("<br>Enchantments ")
                    .append(String.format(Locale.ROOT, "%+.1f", enchanted[s]));
            if (!chosen.isEmpty()) {
                tip.append(" with the suggested ones (").append(String.format(Locale.ROOT, "%+.1f", have[s]))
                        .append(" as these copies are now)");
            }
            if (diff != 0) tip.append("<br>").append(signed(diff)).append(" from items, compared with what it wears now");
            tip.append("<br><i>Click to ").append(chosen.contains(STATS[s]) ? "stop aiming for it" : "aim for it").append("</i>");
            StatBox box = new StatBox(STAT_SHORT[s], base + items + ench, items, ench, chosen.contains(STATS[s]), tip + "</html>");
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
    private static class FitPanel extends JPanel {
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
        private static final String WIDEST = "9,999 (+999) (+99)";
        private final String name;
        private final String text, enchText;   // "total (+items)", then " (+enchantments)" in blue (or "")
        private final int items;
        private final boolean chosen;

        /** A box with its text as given; trend colors it (above 0 green, below orange). */
        StatBox(String name, String text, int trend, boolean chosen, String tooltip) {
            this.name = name;
            this.text = text;
            this.enchText = "";
            this.items = trend;
            this.chosen = chosen;
            setToolTipText(tooltip);
            setFont(new JLabel().getFont());
        }

        StatBox(String name, int total, int items, int enchants, boolean chosen, String tooltip) {
            this.name = name;
            this.text = NUM.format(total) + " (" + signed(items) + ")";
            this.enchText = enchants == 0 ? "" : " (" + signed(enchants) + ")";
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
            int x = (w - lm.stringWidth(text + enchText)) / 2, y = 6 + fm.getHeight() + lm.getAscent();
            g2.setColor(items > 0 ? UP : items < 0 ? DOWN : Color.WHITE);
            g2.drawString(text, x, y);
            if (!enchText.isEmpty()) {
                g2.setColor(OTHER_STAT);
                g2.drawString(enchText, x + lm.stringWidth(text), y);
            }
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

    private static JComponent idealItem(Pick pick, Set<String> chosen, String slotName, Locks locks) {
        int id = pick.id();
        ItemDb.Item it = pick.item();
        JPanel p = new JPanel(new BorderLayout(10, 0));
        p.setOpaque(false);
        int[] enchants = pick.enchants() == null ? null : pick.enchants().stream().mapToInt(Integer::intValue).toArray();
        Icon sprite = id > 0 ? CharactersView.sprite(id, 48) : null;
        JLabel icon = new JLabel(sprite == null ? null : withRarity(sprite, enchants == null ? 0 : enchants.length));
        icon.setPreferredSize(new Dimension(60, 52));   // room on the left; centered beside the text
        icon.setHorizontalAlignment(SwingConstants.RIGHT);
        icon.setVerticalAlignment(SwingConstants.CENTER);
        if (id > 0) icon.setToolTipText(CharactersView.itemTooltip(id, enchants, slotName));
        ItemLinks.link(icon, id);
        p.add(icon, BorderLayout.WEST);
        JPanel text = column();
        String name = id > 0 ? esc(CharactersView.itemName(id)) : "No " + slotName.toLowerCase(Locale.ROOT);
        // where the copy is (equipped, Vault chest, on Priest...), after the name
        text.add(new JLabel(!pick.where().isEmpty() && id > 0 ? "<html>" + name + " <span style='color:" + hex(Palette.MUTED)
                + "'>(" + esc(pick.where()).replace(" ", "&nbsp;") + ")</span></html>" : "<html>" + name + "</html>"));
        // every stat the item gives: the chosen ones first in green, the others after them in blue
        StringBuilder picked = new StringBuilder(), other = new StringBuilder();
        if (it != null) {
            for (ItemDb.Stat st : it.onEquip) {
                if (Arrays.asList(STATS).indexOf(st.stat()) < 0) continue;
                boolean wanted = chosen.contains(st.stat())   // aiming for DPS wants attack and dexterity
                        || (chosen.contains(EnchantPlanner.DPS) && (st.stat().equals("ATT") || st.stat().equals("DEX")));
                StringBuilder to = wanted ? picked : other;
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
        JComponent ench = enchantRow(pick, chosen, locks);
        if (ench != null) text.add(ench);
        JPanel middle = new JPanel(new GridBagLayout());   // the text centered on the icon's height
        middle.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.WEST;
        c.weightx = 1;
        middle.add(text, c);
        p.add(middle, BorderLayout.CENTER);
        return p;
    }

    /** The item's icon with its rarity marker in the bottom-right corner (none for common). */
    private static Icon withRarity(Icon sprite, int enchantSlots) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                sprite.paintIcon(c, g, x, y);
                EnchantBadge.draw((Graphics2D) g, x + getIconWidth() - 1, y + getIconHeight() - 1, enchantSlots, 6);
            }

            @Override
            public int getIconWidth() {
                return sprite.getIconWidth();
            }

            @Override
            public int getIconHeight() {
                return sprite.getIconHeight();
            }
        };
    }

    private static final Color HAS_IT = Palette.GREEN;   // an enchantment the copy already has
    private static final int CHIP_ICON = 16;

    /**
     * The enchantments under an item: with stats chosen, the ones suggested for this copy (green: it has it
     * already, gold: reroll for it), then how many slots are left free; without, what the copy carries now. Null if
     * there is nothing to show.
     */
    private static JComponent enchantRow(Pick pick, Set<String> chosen, Locks locks) {
        ItemDb.Item it = pick.item();
        if (it == null || it.enchantSlots == null) return null;
        // one enchantment per line, so none is cut off however narrow the column
        JPanel row = new JPanel() {
            @Override
            public Component add(Component comp) {
                if (comp instanceof JComponent j) j.setAlignmentX(Component.LEFT_ALIGNMENT);
                return super.add(comp);
            }
        };
        row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);   // as the labels above it (mixed alignments squeeze them)
        row.setBorder(new EmptyBorder(1, 0, 1, 0));
        Rarity rarity = Rarity.of(pick.slots());
        if (pick.enchants() == null) {
            if (chosen.isEmpty()) return null;
            row.add(small(muted("Rarity not known yet: open the vault or play the character that has it")));
            return row;
        }
        if (pick.slots() == 0) {
            if (chosen.isEmpty()) return null;
            row.add(small(muted("Common: no enchantment slots")));
            return row;
        }
        Set<Integer> has = new HashSet<>(pick.enchants());
        List<Enchantment> shown = chosen.isEmpty() ? EnchantPlanner.of(pick.enchants()) : pick.plan().picks();
        Set<Integer> lockedNow = locks == null ? Set.of() : lockSet(locks.charId(), locks.slot());
        for (Enchantment e : shown) {
            boolean owned = has.contains(e.type());
            Color color = chosen.isEmpty() ? Palette.MUTED : owned ? HAS_IT : Section.color("LootGobbler.gold", Palette.GOLD);
            row.add(chip(e, color, enchantTip(e, it, chosen.isEmpty() ? null : owned), false, owned ? locks : null, lockedNow));
        }
        if (!chosen.isEmpty()) {   // what the copy has and keeps in the slots left free
            for (Enchantment e : finalEnchants(pick)) {
                if (shown.contains(e)) continue;
                row.add(chip(e, Palette.MUTED, enchantTip(e, it, null).replace("</html>",
                        "<br><i>Already on this copy: it stays in a slot the suggestion leaves free</i></html>"), false, locks, lockedNow));
            }
            if (locks != null && showReplaced()) {   // what the copy has and the suggestion replaces: struck through, can be locked
                List<Enchantment> ends = finalEnchants(pick);
                for (Enchantment e : EnchantPlanner.of(pick.enchants())) {
                    if (ends.contains(e)) continue;
                    row.add(chip(e, Palette.MUTED, enchantTip(e, it, null).replace("</html>",
                            "<br><i>On this copy now: the suggestion replaces it</i></html>"), true, locks, lockedNow));
                }
            }
        }
        int free = pick.slots() - (chosen.isEmpty() ? shown.size() : finalEnchants(pick).size());
        if (!chosen.isEmpty() && free > 0) {
            JLabel f = muted(free + " free slot" + (free == 1 ? "" : "s"));
            f.setToolTipText("<html>Nothing more raises the chosen stats on this " + rarity.label.toLowerCase(Locale.ROOT)
                    + " item: whatever these slots hold can stay</html>");
            row.add(small(f));
        }
        return row;
    }

    /** Locking in scope current: the character and slot the enchantments are on, and what redraws after a click. */
    record Locks(int charId, int slot, Runnable rerender) {
    }

    /**
     * One enchantment line: its icon and what it does, in this color, struck through if the suggestion replaces it.
     * With locks (an enchantment the worn copy has, in scope current) a click locks or unlocks it; locked ones show a
     * padlock and are kept whatever is aimed for.
     */
    private static JLabel chip(Enchantment e, Color color, String tip, boolean struck, Locks locks, Set<Integer> lockedNow) {
        boolean locked = locks != null && lockedNow.contains(e.type());
        String text = esc(effectText(e));
        JLabel chip = new JLabel(struck ? "<html><s>" + text + "</s></html>" : text);
        Image img = EnchantIcons.icon(e.type(), CHIP_ICON);
        Icon icon = img == null ? null : new ImageIcon(img);
        chip.setIcon(locked ? withLock(icon) : icon);
        chip.setIconTextGap(3);
        chip.setForeground(locked ? HAS_IT : color);
        if (locks != null) {
            tip = tip.replace("</html>", locked ? "<br><b>Locked:</b> kept whatever you aim for. <i>Click to unlock</i></html>"
                    : "<br><i>Click to lock it: the suggestion keeps it</i></html>");
            chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Clicks.onClick(chip, () -> {
                toggleLock(locks.charId(), locks.slot(), e.type());
                locks.rerender().run();
            });
        }
        chip.setToolTipText(tip);
        return small(chip);
    }

    /** A small padlock before the icon. */
    private static Icon withLock(Icon icon) {
        int lockW = 10, gap = 3;
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int h = getIconHeight(), by = y + h / 2 - 1;
                g2.setColor(HAS_IT);
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawArc(x + 2, by - 6, lockW - 4, 9, 0, 180);   // the shackle
                g2.fillRoundRect(x, by, lockW, 7, 2, 2);            // the body
                g2.dispose();
                if (icon != null) icon.paintIcon(c, g, x + lockW + gap, y);
            }

            @Override
            public int getIconWidth() {
                return lockW + gap + (icon == null ? 0 : icon.getIconWidth());
            }

            @Override
            public int getIconHeight() {
                return Math.max(14, icon == null ? 0 : icon.getIconHeight());
            }
        };
    }

    /** The enchantments locked on this character's slot. */
    private static Set<Integer> lockSet(int charId, int slot) {
        Set<Integer> out = new HashSet<>();
        String v = PropertiesManager.getProperty(LOCK_KEY);
        if (v == null) return out;
        for (String part : v.split(";")) {
            String[] f = part.split(":");
            if (f.length != 3) continue;
            try {
                if (Integer.parseInt(f[0]) == charId && Integer.parseInt(f[1]) == slot) out.add(Integer.parseInt(f[2]));
            } catch (NumberFormatException ignored) {
                // a broken entry: skip it
            }
        }
        return out;
    }

    private static void toggleLock(int charId, int slot, int enchant) {
        String entry = charId + ":" + slot + ":" + enchant;
        String v = PropertiesManager.getProperty(LOCK_KEY);
        List<String> parts = new ArrayList<>();
        if (v != null) for (String part : v.split(";")) if (!part.isBlank()) parts.add(part);
        if (!parts.remove(entry)) parts.add(entry);
        PropertiesManager.setProperties(LOCK_KEY, String.join(";", parts));
    }

    /** The locked enchantments the copy still has (locks of enchantments it no longer has are left out). */
    private static List<Enchantment> locked(int charId, int slot, Pick pk) {
        Set<Integer> set = lockSet(charId, slot);
        List<Enchantment> out = new ArrayList<>();
        for (Enchantment e : EnchantPlanner.of(pk.enchants())) if (set.contains(e.type())) out.add(e);
        return out;
    }

    private static <T extends JComponent> T small(T c) {
        c.putClientProperty("FlatLaf.styleClass", "small");
        return c;
    }

    /** What an enchantment does to stats, short ("+5 ATT -3.8 DEF", "+7% of bonus ATT"); its name if nothing. */
    private static String effectText(Enchantment e) {
        StringBuilder sb = new StringBuilder();
        for (Effect f : e.effects()) {
            if (f instanceof Effect.StatBonus b && Arrays.asList(STATS).contains(b.stat())) {
                sb.append(sb.isEmpty() ? "" : " ").append(b.amount() > 0 ? "+" : "").append(number(b.amount()))
                        .append(' ').append(shortName(b.stat()));
            } else if (f instanceof Effect.RelativeStat r && Arrays.asList(STATS).contains(r.stat())) {
                sb.append(sb.isEmpty() ? "" : " ").append('+').append(number(r.percent())).append("% of bonus ")
                        .append(shortName(r.of())).append(" as ").append(shortName(r.stat()));
            }
        }
        return sb.isEmpty() ? e.name() : sb.toString();
    }

    private static String number(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.2f", d).replaceAll("0+$", "");
    }

    /** Name, kind, the game's description, and whether the copy has it or how rarely a reroll gives it. */
    private static String enchantTip(Enchantment e, ItemDb.Item item, Boolean owned) {
        StringBuilder sb = new StringBuilder("<html><b>").append(esc(e.name())).append("</b> <span style='color:")
                .append(hex(Palette.MUTED)).append("'>").append(e.kind().label).append("</span><br>")
                .append(esc(e.description()));
        if (owned != null) {
            if (owned) {
                sb.append("<br><span style='color:").append(hex(HAS_IT)).append("'>This copy has it: lock it when rerolling</span>");
            } else {
                double total = 0;
                for (Enchantment o : Enchantments.get().rollableOn(item)) total += Enchantments.get().weightOn(item, o);
                double w = Enchantments.get().weightOn(item, e);
                if (w > 0 && total > 0) {
                    sb.append("<br>Reroll for it: about 1 in ").append(NUM.format(Math.round(total / w)))
                            .append(" rerolls of a slot give exactly this");
                }
            }
        }
        return sb.append("</html>").toString();
    }

    /** Items the character could wear: what it carries, and what you hold on its side (seasonal or not). */
    private static List<Integer> pool(Char c) {
        Set<Integer> ids = new LinkedHashSet<>();
        if (c.slots != null) for (int id : c.slots) if (id > 0) ids.add(id);
        ids.addAll(Holdings.get().itemsHeld(c.seasonal));   // everything on its side, tiered items too
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
        private static final int SLOT = 64;   // every slot this size, however wide the panel (centered in it)
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

        /** The slot size: fixed, so resizing the windows does not change the inventory. */
        private int slot(int w) {
            return SLOT;
        }

        /** Where the slot in this row and column (over all packs) sits; the packs are centered. */
        private Rectangle rect(int row, int col, int s) {
            int left = (getWidth() - width(packs, s)) / 2, pack = col / 4, k = col % 4;
            return new Rectangle(left + pack * (4 * s + 3 * GAP + DIVIDER) + k * (s + GAP), PAD + row * (s + GAP), s, s);
        }

        /** As wide as the panel gives it, and tall enough for two rows of square slots. */
        @Override
        public Dimension getPreferredSize() {
            int min = 2 * PAD + width(PACKS, SLOT);
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
