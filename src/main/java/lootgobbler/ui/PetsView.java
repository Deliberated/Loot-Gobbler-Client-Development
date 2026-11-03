package lootgobbler.ui;

import assets.IdToAsset;
import lootgobbler.PetStore;
import lootgobbler.PetStore.Ability;
import lootgobbler.PetStore.Pet;
import lootgobbler.PetStore.Saddlebag;
import util.PropertiesManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Characters → Pets: every pet seen in your character list or Pet Yard as a grid of tiles (like the Classes page),
 * sortable by rarity, ability levels, name or when it was last seen. Clicking a tile shows the pet on the right: its
 * rarity, abilities and the items in its saddlebags (regular and seasonal characters each have their own).
 */
public class PetsView extends JPanel {
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final Color[] RARITY_COLORS = {Palette.STONE, Palette.GREEN, Palette.BLUE, Palette.PURPLE, Palette.GOLD};
    private static final int TILE_MIN_WIDTH = 300, GAP = 8, SPRITE = 56, BIG_SPRITE = 96;
    private static final String[] SORTS = {"Rarity", "Abilities", "Name", "Last seen"};
    private static final String SORT_KEY = "petSort", REVERSE_KEY = "petSortReverse";

    private final JPanel tiles = new JPanel(new GridLayout(0, 1, GAP, GAP));
    private final JScrollPane tileScroll;
    private final JPanel detail = new JPanel();
    private final JButton direction = new JButton();
    private int sort = Math.max(0, Math.min(SORTS.length - 1, intProperty(SORT_KEY)));
    private boolean reverse = intProperty(REVERSE_KEY) == 1;
    private int selected = -1;   // instance id of the pet shown on the right, -1 = none yet

    public PetsView() {
        super(new BorderLayout());
        setOpaque(false);

        tiles.setOpaque(false);
        tiles.setBorder(new EmptyBorder(10, 10, 10, 10));
        tileScroll = FitWidthPanel.scroll(tiles, 24);
        tileScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateColumns();
            }
        });

        JLabel sortLabel = new JLabel("SORT BY");
        sortLabel.putClientProperty("FlatLaf.styleClass", "small");
        Segmented sorter = new Segmented(true, sort, i -> {
            sort = i;
            reverse = false;   // each sort starts in its natural direction
            PropertiesManager.setProperties(SORT_KEY, String.valueOf(i));
            PropertiesManager.setProperties(REVERSE_KEY, "0");
            refresh();
        }, SORTS);
        direction.setFocusable(false);
        direction.addActionListener(e -> {
            reverse = !reverse;
            PropertiesManager.setProperties(REVERSE_KEY, reverse ? "1" : "0");
            refresh();
        });
        JPanel main = new JPanel(new BorderLayout());
        main.add(tileScroll);
        Section pets = new Section("Pets", Palette.GOLD, main).actions(sortLabel, sorter, direction);

        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        detail.setOpaque(false);
        detail.setBorder(new EmptyBorder(0, 0, 0, 0));
        SideSplit split = new SideSplit(pets, FitWidthPanel.scroll(detail, 24), "petDetail", 460, 300);
        split.setSnap(tileScroll.getViewport(), (width, nearest) -> {   // settle where the tiles fill whole columns
            float cols = (width - 20 + GAP) / (float) (TILE_MIN_WIDTH + GAP);
            int n = Math.max(1, nearest ? Math.round(cols) : (int) cols);
            return n * (TILE_MIN_WIDTH + GAP) - GAP + 20;
        });
        add(split, BorderLayout.CENTER);

        PetStore.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    private static int intProperty(String key) {
        try {
            return Integer.parseInt(PropertiesManager.getProperty(key));
        } catch (Exception e) {
            return 0;
        }
    }

    /** The pets in the chosen order; each sort's natural direction puts the best (or newest, or A) first. */
    private List<Pet> sorted() {
        List<Pet> pets = PetStore.get().all();
        Comparator<Pet> byName = Comparator.comparing(p -> name(p).toLowerCase(Locale.ROOT));
        Comparator<Pet> order = switch (sort) {
            case 1 -> Comparator.comparingInt((Pet p) -> -levels(p)).thenComparingInt(p -> -p.rarity);
            case 2 -> byName.thenComparingInt(p -> -p.rarity);
            case 3 -> Comparator.comparingLong((Pet p) -> -p.lastSeen);
            default -> Comparator.comparingInt((Pet p) -> -p.rarity).thenComparingInt(p -> -levels(p));
        };
        pets.sort(order.thenComparing(byName));
        if (reverse) java.util.Collections.reverse(pets);
        return pets;
    }

    private void refresh() {
        direction.setText(reverse ? "↑" : "↓");
        direction.setToolTipText(reverse ? "Reversed order - click for the usual one" : "Click to reverse the order");
        List<Pet> pets = sorted();
        if (pets.stream().noneMatch(p -> p.instanceId == selected)) selected = pets.isEmpty() ? -1 : pets.get(0).instanceId;

        tiles.removeAll();
        if (pets.isEmpty()) tiles.add(muted("Log in once so your character list is loaded, or visit your Pet Yard."));
        for (Pet p : pets) {
            tiles.add(new Tile(p, p.instanceId == selected, () -> {
                selected = p.instanceId;
                refresh();
            }));
        }
        updateColumns();
        showPet(pets.stream().filter(p -> p.instanceId == selected).findFirst().orElse(null));
    }

    private void updateColumns() {
        int width = tileScroll.getViewport().getWidth();
        if (width > 0) {   // 0 while the view is hidden; it is recomputed when shown
            ((GridLayout) tiles.getLayout()).setColumns(Math.max(1, (width - 20 + GAP) / (TILE_MIN_WIDTH + GAP)));
        }
        tiles.revalidate();
        tiles.repaint();
    }

    /** The selected pet on the right: who it is, its abilities, its saddlebags. */
    private void showPet(Pet p) {
        detail.removeAll();
        if (p == null) {
            detail.revalidate();
            detail.repaint();
            return;
        }
        JPanel who = column();
        JPanel head = new JPanel(new BorderLayout(14, 0));
        head.setOpaque(false);
        head.add(new JLabel(CharactersView.sprite(look(p), BIG_SPRITE)), BorderLayout.WEST);
        JPanel text = column();
        JLabel name = new JLabel(name(p));
        name.setFont(name.getFont().deriveFont(32f));
        text.add(left(name));
        text.add(left(rarityLabel(p, 18f)));
        String kind = kind(p);
        if (kind != null) text.add(left(muted(kind)));
        if (p.lastSeen > 0) {
            text.add(Box.createVerticalStrut(4));
            text.add(left(muted("last seen " + DATE.format(Instant.ofEpochMilli(p.lastSeen).atZone(ZoneId.systemDefault())))));
        }
        head.add(text, BorderLayout.CENTER);
        who.add(left(head));
        detail.add(CharactersView.box("Pet", Palette.GOLD, who));

        JPanel abilities = column();
        for (Ability a : p.abilities) abilities.add(left(abilityRow(a, p.maxPower)));
        if (p.abilities.isEmpty()) abilities.add(left(muted("Abilities unknown - visit your Pet Yard.")));
        else {
            abilities.add(Box.createVerticalStrut(6));
            abilities.add(left(muted("Total level " + levels(p) + " / " + p.abilities.size() * maxPower(p))));
        }
        detail.add(CharactersView.box("Abilities", Palette.GREEN, abilities));

        JPanel bags = column();
        if (p.saddlebag != null) bags.add(left(bagRow("Saddlebag", p.saddlebag)));
        if (p.seasonalSaddlebag != null) {
            if (p.saddlebag != null) bags.add(Box.createVerticalStrut(8));
            bags.add(left(bagRow("Seasonal saddlebag", p.seasonalSaddlebag)));
        }
        if (p.saddlebag == null && p.seasonalSaddlebag == null) {
            bags.add(left(muted("No saddlebag seen - it is read from your character list at login.")));
        }
        detail.add(CharactersView.box("Saddlebags", Palette.PURPLE, bags));
        detail.revalidate();
        detail.repaint();
    }

    /**
     * One pet in the grid: sprite, name and rarity, and its abilities as small bars. Highlighted when it is the one
     * shown on the right; a click anywhere on it shows it.
     */
    private static class Tile extends JPanel {
        private final boolean selected;

        Tile(Pet p, boolean selected, Runnable onClick) {
            super(new BorderLayout(10, 0));
            this.selected = selected;
            setOpaque(false);
            setBorder(new EmptyBorder(6, 6, 6, 10));

            JLabel sprite = new JLabel(CharactersView.sprite(look(p), SPRITE));
            sprite.setPreferredSize(new Dimension(SPRITE + 8, SPRITE + 8));
            sprite.setVerticalAlignment(SwingConstants.TOP);
            add(sprite, BorderLayout.WEST);

            JPanel body = column();
            JPanel head = new JPanel(new BorderLayout(8, 0));
            head.setOpaque(false);
            JLabel name = new JLabel(name(p));
            name.setFont(name.getFont().deriveFont(24f));
            head.add(name, BorderLayout.CENTER);
            head.add(rarityLabel(p, 15f), BorderLayout.EAST);
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
            body.add(left(head));
            body.add(Box.createVerticalStrut(4));
            for (Ability a : p.abilities) body.add(left(miniAbility(a, p.maxPower)));
            if (p.abilities.isEmpty()) body.add(left(muted("Abilities unknown")));
            add(body, BorderLayout.CENTER);
            clickable(this, onClick);
        }

        /** Clicks anywhere on c (also on its labels, which would otherwise take the click) run action. */
        private static void clickable(JComponent c, Runnable action) {
            c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Clicks.onClick(c, action);
            for (Component child : c.getComponents()) if (child instanceof JComponent j) clickable(j, action);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Color accent = Section.color("LootGobbler.gold", Palette.GOLD);
            Color header = Section.color("LootGobbler.header", Color.LIGHT_GRAY);
            g.setColor(selected ? Palette.mix(accent, header, 0.3) : header);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(selected ? accent : Color.BLACK);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            if (selected) g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
        }
    }

    /** A compact ability line for the tiles: name, a thin bar and the level. */
    private static JComponent miniAbility(Ability a, int maxPower) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JLabel name = new JLabel(PetStore.abilityName(a.type));
        name.setPreferredSize(new Dimension(100, name.getPreferredSize().height));
        row.add(name, BorderLayout.WEST);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(Math.min(100, a.power));
        bar.setPreferredSize(new Dimension(60, 5));
        bar.setForeground(a.power >= maxPower && maxPower > 0 ? Palette.GOLD : Palette.GREEN);
        JPanel barHolder = new JPanel(new GridBagLayout());   // vertically centered, not stretched
        barHolder.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        barHolder.add(bar, c);
        row.add(barHolder, BorderLayout.CENTER);
        JLabel level = new JLabel(String.valueOf(a.power));
        level.setHorizontalAlignment(SwingConstants.RIGHT);
        level.setPreferredSize(new Dimension(28, level.getPreferredSize().height));
        row.add(level, BorderLayout.EAST);
        row.setToolTipText(PetStore.abilityName(a.type) + ": level " + a.power + " / " + (maxPower > 0 ? maxPower : 100));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /**
     * Ability name, its level against the rarity's maximum as a bar, and the level as text.
     */
    private static JComponent abilityRow(Ability a, int maxPower) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        JLabel name = new JLabel(PetStore.abilityName(a.type));
        name.setPreferredSize(new Dimension(130, name.getPreferredSize().height));
        row.add(name, BorderLayout.WEST);
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(Math.min(100, a.power));
        bar.setPreferredSize(new Dimension(200, 8));
        bar.setMaximumSize(new Dimension(320, 8));
        bar.setForeground(a.power >= maxPower && maxPower > 0 ? Palette.GOLD : Palette.GREEN);
        JPanel barHolder = new JPanel(new GridBagLayout());   // vertically centered, not stretched
        barHolder.setOpaque(false);
        barHolder.add(bar);
        row.add(barHolder, BorderLayout.CENTER);
        JLabel level = new JLabel(a.power + " / " + (maxPower > 0 ? maxPower : 100));
        level.setPreferredSize(new Dimension(70, level.getPreferredSize().height));
        row.add(level, BorderLayout.EAST);
        row.setToolTipText(PetStore.abilityName(a.type) + ": level " + a.power + ", " + NUM.format(a.points) + " feed points");
        row.setMaximumSize(new Dimension(560, row.getPreferredSize().height));
        return row;
    }

    private static JComponent bagRow(String title, Saddlebag bag) {
        JPanel row = new JPanel(new BorderLayout(0, 2));
        row.setOpaque(false);
        JLabel caption = new JLabel((title + "  ·  " + bag.items.length + (bag.items.length == 1 ? " slot" : " slots"))
                .toUpperCase());
        caption.putClientProperty("FlatLaf.styleClass", "small");
        row.add(caption, BorderLayout.NORTH);
        JPanel slots = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        slots.setOpaque(false);
        for (int i = 0; i < bag.items.length; i++) {
            int[] enchants = i < bag.enchantIds.length ? bag.enchantIds[i] : null;
            slots.add(CharactersView.slot(bag.items[i], enchants, "Slot " + (i + 1), true));
        }
        if (bag.items.length == 0) slots.add(muted("No slots"));
        row.add(slots, BorderLayout.CENTER);
        return row;
    }

    private static JLabel rarityLabel(Pet p, float size) {
        int r = Math.max(0, Math.min(PetStore.RARITY.length - 1, p.rarity));
        JLabel rarity = new JLabel(PetStore.RARITY[r].toUpperCase());
        rarity.setForeground(RARITY_COLORS[r]);
        rarity.setFont(rarity.getFont().deriveFont(Font.BOLD, size));
        return rarity;
    }

    private static int look(Pet p) {
        return p.skin > 0 ? p.skin : p.type;
    }

    private static String name(Pet p) {
        return p.name == null || p.name.isBlank() ? "Pet" : p.name;
    }

    /** The pet's kind (e.g. its family's name), if it differs from its name. */
    private static String kind(Pet p) {
        String kind = p.type > 0 ? IdToAsset.objectName(p.type) : null;
        return kind != null && !kind.equals(p.name) ? kind : null;
    }

    /** All ability levels added up. */
    private static int levels(Pet p) {
        return p.abilities.stream().mapToInt(a -> a.power).sum();
    }

    private static int maxPower(Pet p) {
        return p.maxPower > 0 ? p.maxPower : 100;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        return l;
    }
}
