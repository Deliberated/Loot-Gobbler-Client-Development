package lootgobbler.ui;

import assets.IdToAsset;
import lootgobbler.PetStore;
import lootgobbler.PetStore.Ability;
import lootgobbler.PetStore.Pet;
import lootgobbler.PetStore.Saddlebag;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.List;

/**
 * Characters → Pets: every pet seen in your character list or Pet Yard, with its rarity, abilities and the items
 * in its saddlebag (regular and seasonal characters each have their own).
 */
public class PetsView extends JPanel {
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    private static final Color[] RARITY_COLORS = {Palette.STONE, Palette.GREEN, Palette.BLUE, Palette.PURPLE, Palette.GOLD};
    private static final int SPRITE = 64;

    private final JPanel list = new JPanel();

    public PetsView() {
        super(new BorderLayout());
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.setBorder(new EmptyBorder(8, 8, 8, 8));
        add(new Section("Pets", Palette.GOLD, FitWidthPanel.scroll(list, 24)));
        PetStore.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    private void refresh() {
        List<Pet> pets = PetStore.get().all();
        pets.sort(Comparator.comparingInt((Pet p) -> -p.rarity).thenComparingLong(p -> -p.lastSeen));
        list.removeAll();
        if (pets.isEmpty()) {
            JLabel empty = new JLabel("Log in once so your character list is loaded, or visit your Pet Yard.");
            empty.setForeground(Palette.MUTED);
            list.add(empty);
        }
        for (Pet p : pets) {
            Card card = new Card(p);
            card.setAlignmentX(LEFT_ALIGNMENT);
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            list.add(card);
            list.add(Box.createVerticalStrut(8));
        }
        list.revalidate();
        list.repaint();
    }

    /**
     * One pet: sprite, name with rarity and kind, its abilities, then each saddlebag with its items.
     */
    private static class Card extends JPanel {
        Card(Pet p) {
            super(new BorderLayout(12, 0));
            setOpaque(false);
            setBorder(new EmptyBorder(8, 8, 8, 12));

            JLabel sprite = new JLabel(CharactersView.sprite(p.skin > 0 ? p.skin : p.type, SPRITE));
            sprite.setPreferredSize(new Dimension(SPRITE + 8, SPRITE + 8));
            sprite.setVerticalAlignment(SwingConstants.TOP);
            add(sprite, BorderLayout.WEST);

            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);

            JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            head.setOpaque(false);
            JLabel name = new JLabel(p.name == null || p.name.isBlank() ? "Pet" : p.name);
            name.setFont(name.getFont().deriveFont(24f));
            head.add(name);
            head.add(Box.createHorizontalStrut(12));
            int r = Math.max(0, Math.min(PetStore.RARITY.length - 1, p.rarity));
            JLabel rarity = new JLabel(PetStore.RARITY[r].toUpperCase());
            rarity.setForeground(RARITY_COLORS[r]);
            rarity.setFont(rarity.getFont().deriveFont(Font.BOLD, 15f));
            head.add(rarity);
            String kind = p.type > 0 ? IdToAsset.objectName(p.type) : null;
            if (kind != null && !kind.equals(p.name)) {
                head.add(Box.createHorizontalStrut(12));
                JLabel k = new JLabel(kind);
                k.setForeground(Palette.MUTED);
                head.add(k);
            }
            body.add(left(head));
            body.add(Box.createVerticalStrut(6));

            for (Ability a : p.abilities) body.add(left(abilityRow(a, p.maxPower)));
            if (p.abilities.isEmpty()) body.add(left(muted("Abilities unknown - visit your Pet Yard.")));

            body.add(Box.createVerticalStrut(8));
            boolean any = false;
            if (p.saddlebag != null) {
                body.add(left(bagRow("Saddlebag", p.saddlebag)));
                any = true;
            }
            if (p.seasonalSaddlebag != null) {
                body.add(Box.createVerticalStrut(4));
                body.add(left(bagRow("Seasonal saddlebag", p.seasonalSaddlebag)));
                any = true;
            }
            if (!any) body.add(left(muted("No saddlebag seen - it is read from your character list at login.")));
            add(body, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Section.color("LootGobbler.header", Color.LIGHT_GRAY));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.BLACK);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
        }
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

    private static JComponent left(JComponent c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        return l;
    }
}
