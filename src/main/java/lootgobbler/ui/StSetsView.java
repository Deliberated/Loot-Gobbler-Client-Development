package lootgobbler.ui;

import assets.ImageBuffer;
import lootgobbler.CollectionStore;
import lootgobbler.ItemDb;
import lootgobbler.StSets;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * Collection > ST Sets: one card per ST set showing which pieces you have collected, with filters and
 * progress panels. Pieces count as owned when they are in your collection.
 */
public class StSetsView extends JPanel {
    private static final int CARD_MIN_WIDTH = 330, GAP = 8, TILE = 44;
    private static final Color EMPTY_TILE = new Color(0x050505), OWNED_TINT = new Color(0xFFF6C0);
    private static final String ALL_CLASSES = "All classes";

    private final Consumer<ItemDb.Item> onPieceClick;
    private final JPanel cards = new JPanel(new GridLayout(0, 1, GAP, GAP));
    private final JScrollPane cardScroll;
    private final JComboBox<String> classBox = new JComboBox<>();
    private final JProgressBar setsBar = bar(null), piecesBar = bar(Palette.GREEN);
    private final JLabel setsText = new JLabel(" "), piecesText = new JLabel(" ");
    private final JPanel almost = column(), byClass = column();
    private List<StSets.Set> sets = List.of();
    private Set<Integer> owned = Set.of();
    private Map<Integer, CollectionStore.Entry> entries = Map.of();
    private int filter = 0;   // 0 all, 1 complete, 2 in progress (not complete), 3 craftable from set tokens

    public StSetsView(Consumer<ItemDb.Item> onPieceClick) {
        super(new BorderLayout(GAP, 0));
        this.onPieceClick = onPieceClick;
        setOpaque(false);

        cards.setOpaque(false);
        cards.setBorder(new EmptyBorder(10, 10, 10, 10));
        // the cards always take the viewport's width (like the item grid), so the snapped divider lines up with them
        cardScroll = FitWidthPanel.scroll(cards, 24);
        cardScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateColumns();
            }
        });

        classBox.addActionListener(e -> rebuild());
        Section main = new Section("Collection · ST Sets", Palette.GOLD, wrap(cardScroll))
                .actions(classBox, new Segmented(true, 0, i -> {
                    filter = i;
                    rebuild();
                }, "All", "Complete", "In progress", "Tokens"));

        JPanel progress = column();
        progress.setBorder(new EmptyBorder(10, 10, 10, 10));
        progress.add(caption("Sets complete"));
        progress.add(setsBar);
        progress.add(setsText);
        progress.add(Box.createVerticalStrut(6));
        progress.add(caption("Pieces collected"));
        progress.add(piecesBar);
        progress.add(piecesText);
        almost.setBorder(new EmptyBorder(8, 10, 8, 10));
        byClass.setBorder(new EmptyBorder(8, 10, 8, 10));

        JPanel sideTop = column();
        sideTop.add(new Section("Set Progress", Palette.GREEN, progress));
        sideTop.add(Box.createVerticalStrut(GAP));
        sideTop.add(new Section("Almost Complete", Palette.BLUE, almost));
        JScrollPane byClassScroll = FitWidthPanel.scroll(byClass, 16);

        JPanel side = new JPanel(new BorderLayout(0, GAP));
        side.setOpaque(false);
        side.add(sideTop, BorderLayout.NORTH);
        side.add(new Section("By Class", Palette.PURPLE, wrap(byClassScroll)), BorderLayout.CENTER);

        SideSplit split = new SideSplit(main, side, "stSets", 280, 220);   // drag the gap to resize
        // the divider settles where the set cards fill whole columns (cards at their minimum width, no stretch gap)
        split.setSnap(cardScroll.getViewport(), (width, nearest) -> {
            float cols = (width - 20 + GAP) / (float) (CARD_MIN_WIDTH + GAP);
            int n = Math.max(1, nearest ? Math.round(cols) : (int) cols);
            return n * (CARD_MIN_WIDTH + GAP) - GAP + 20;
        });
        split.minMain(main.getPreferredSize().width + 24);   // keep the header (class box, filters) readable
        add(split, BorderLayout.CENTER);
    }

    public void setData(List<StSets.Set> sets, Map<Integer, CollectionStore.Entry> collected) {
        this.sets = sets;
        this.entries = collected;
        this.owned = collected.keySet();
        if (classBox.getItemCount() == 0 && !sets.isEmpty()) {
            classBox.addItem(ALL_CLASSES);
            new TreeSet<>(sets.stream().map(StSets.Set::className).toList()).forEach(classBox::addItem);
        }
        rebuild();
    }

    private void rebuild() {
        String cls = classBox.getSelectedItem() == null ? ALL_CLASSES : (String) classBox.getSelectedItem();
        List<StSets.Set> shown = new ArrayList<>();
        for (StSets.Set s : sets) {
            int n = s.owned(owned);
            boolean match = switch (filter) {
                case 1 -> n == 4;
                case 2 -> n < 4;
                case 3 -> token(s) != 0;
                default -> true;
            };
            if (match && (cls.equals(ALL_CLASSES) || s.className().equals(cls))) shown.add(s);
        }
        shown.sort(Comparator.comparingInt((StSets.Set s) -> -s.owned(owned)).thenComparing(StSets.Set::title));

        cards.removeAll();
        for (StSets.Set s : shown) cards.add(new Card(s));
        if (shown.isEmpty()) {
            JLabel none = new JLabel(sets.isEmpty() ? "Loading sets..." : "No sets match.");
            none.setForeground(Palette.MUTED);
            cards.add(none);
        }
        updateColumns();
        updateSide();
    }

    private void updateColumns() {
        int width = cardScroll.getViewport().getWidth();
        if (width > 0) {   // 0 while the view is hidden; it is recomputed when shown
            ((GridLayout) cards.getLayout()).setColumns(Math.max(1, (width - 20 + GAP) / (CARD_MIN_WIDTH + GAP)));
        }
        cards.revalidate();
        cards.repaint();
    }

    private void updateSide() {
        int complete = 0, pieces = 0;
        for (StSets.Set s : sets) {
            int n = s.owned(owned);
            pieces += n;
            if (n == 4) complete++;
        }
        setBar(setsBar, setsText, complete, sets.size());
        setBar(piecesBar, piecesText, pieces, sets.size() * 4);

        almost.removeAll();
        List<StSets.Set> close = sets.stream().filter(s -> s.owned(owned) == 3).sorted(Comparator.comparing(StSets.Set::title)).toList();
        if (close.isEmpty()) almost.add(muted("No set is one piece away."));
        for (StSets.Set s : close.subList(0, Math.min(8, close.size()))) {
            int missing = Arrays.stream(s.pieces()).filter(id -> !owned.contains(id)).findFirst().orElse(0);
            Image icon = CollectionGrid.icon(missing);
            ItemDb.Item item = ItemDb.get().item(missing);
            JLabel l = new JLabel(s.title(), icon == null ? null : new ImageIcon(icon.getScaledInstance(22, 22, Image.SCALE_REPLICATE)),
                    SwingConstants.LEFT);
            l.setIconTextGap(8);
            l.setToolTipText("Missing: " + (item == null ? "?" : item.name));
            almost.add(l);
        }

        byClass.removeAll();
        Map<String, int[]> perClass = new TreeMap<>();
        for (StSets.Set s : sets) {
            int[] v = perClass.computeIfAbsent(s.className(), k -> new int[2]);
            v[1]++;
            if (s.owned(owned) == 4) v[0]++;
        }
        perClass.forEach((c, v) -> {
            JPanel row = new JPanel(new BorderLayout(6, 2));
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);
            JLabel name = new JLabel(c, new Section.Square(Palette.CLASS_COLORS.getOrDefault(c, Palette.STONE), 8), SwingConstants.LEFT);
            name.setIconTextGap(8);
            row.add(name, BorderLayout.CENTER);
            row.add(new JLabel(v[0] + " / " + v[1]), BorderLayout.EAST);
            JProgressBar b = bar(null);
            b.setValue(v[1] == 0 ? 0 : 1000 * v[0] / v[1]);
            b.setPreferredSize(new Dimension(100, 5));
            row.add(b, BorderLayout.SOUTH);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            byClass.add(row);
            byClass.add(Box.createVerticalStrut(5));
        });
        almost.revalidate();
        byClass.revalidate();
        repaint();
    }

    /** The set's token (crafts its pieces in the forge), or 0 if it has none. */
    private static int token(StSets.Set s) {
        for (int id : s.pieces()) {
            lootgobbler.SetTokens.Cost c = lootgobbler.SetTokens.of(id);
            if (c != null) return c.token();
        }
        return 0;
    }

    /**
     * One set: skin on the left, then title, the four pieces and class / progress. A set the forge crafts from a
     * set token has that token in its top-right corner. A complete set is drawn in gold.
     */
    private class Card extends JPanel {
        private final boolean done;
        private final int token;

        Card(StSets.Set s) {
            super(new BorderLayout(10, 2));
            int n = s.owned(owned);
            done = n == 4;
            token = token(s);
            setOpaque(false);
            setBorder(new EmptyBorder(6, 6, 6, 10));
            ItemDb.Item t = token == 0 ? null : ItemDb.get().item(token);
            setToolTipText("<html><b>" + escape(s.name()) + "</b><br>" + escape(s.bonus())
                    + (t == null ? "" : "<br><i>Craftable in the forge with the " + escape(t.name) + "</i>") + "</html>");

            JLabel skin = new JLabel(skinIcon(s.skin()));
            skin.setPreferredSize(new Dimension(72, 72));
            skin.setHorizontalAlignment(SwingConstants.CENTER);
            add(skin, BorderLayout.WEST);

            // the pieces beside the skin, vertically centered on it
            JPanel tiles = new JPanel(new GridLayout(1, 4, 6, 0));
            tiles.setOpaque(false);
            for (int i = 0; i < 4; i++) tiles.add(new Tile(s.pieces()[i], StSets.SLOTS[i]));   // slot in the tooltip
            JPanel middle = new JPanel(new GridBagLayout());
            middle.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.weightx = 1;
            c.anchor = GridBagConstraints.WEST;
            middle.add(tiles, c);
            add(middle, BorderLayout.CENTER);

            // along the bottom: the set name from under the skin (long names shrink to fit), the class and count
            // on the right (complete sets have no count: the gold card says it)
            JPanel bottom = new JPanel(new BorderLayout(10, 0));
            bottom.setOpaque(false);
            bottom.add(new FitTitle(s.title()), BorderLayout.CENTER);
            JLabel cls = new JLabel(s.className() + (done ? "" : "   " + n + " / 4"),
                    new Section.Square(Palette.CLASS_COLORS.getOrDefault(s.className(), Palette.STONE), 8), SwingConstants.LEFT);
            cls.setIconTextGap(6);
            bottom.add(cls, BorderLayout.EAST);
            add(bottom, BorderLayout.SOUTH);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Color accent = Section.color("LootGobbler.gold", Palette.GOLD);
            Color bg = done ? Palette.mix(accent, Section.color("LootGobbler.paper", Color.WHITE), 0.45)
                    : Section.color("LootGobbler.header", Color.LIGHT_GRAY);
            g.setColor(bg);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(done ? accent : Color.BLACK);
            g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
            if (done) g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
        }

        @Override
        protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            if (token != 0) {   // the set token near the top-right corner, on the card itself
                Icon icon = CharactersView.sprite(token, 30);
                if (icon != null) icon.paintIcon(this, g, getWidth() - icon.getIconWidth() - 10, 8);
            }
        }
    }

    /**
     * A set's name, underlined, from the left; the font gets smaller (down to 12) until the name fits the width, and
     * only beyond that is it cut with "...".
     */
    private static class FitTitle extends JComponent {
        private static final float MAX = 18f, MIN = 12f;
        private final String text;

        FitTitle(String text) {
            this.text = text;
            setFont(UIManager.getFont("Label.font").deriveFont(MAX));
            setForeground(UIManager.getColor("Label.foreground"));
            setToolTipText(text);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            return new Dimension(40, fm.getHeight() + 2);   // as wide as it gets, sized to the full font
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font f = getFont();
            FontMetrics fm = g2.getFontMetrics(f);
            while (fm.stringWidth(text) > getWidth() && f.getSize2D() > MIN) {
                f = f.deriveFont(f.getSize2D() - 1f);
                fm = g2.getFontMetrics(f);
            }
            String shown = text;
            while (fm.stringWidth(shown) > getWidth() && shown.length() > 3) shown = shown.substring(0, shown.length() - 4) + "...";
            int w = fm.stringWidth(shown), x = 0;   // from the left edge
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.setFont(f);
            g2.setColor(getForeground());
            g2.drawString(shown, x, y);
            g2.fillRect(x, y + 2, w, 1);   // underline
            g2.dispose();
        }
    }

    /**
     * A piece's tooltip with its forge recipe: the tooltip text, then e.g. "2× [token] + 1× [any ST weapon]".
     */
    private static class RecipeTip extends JToolTip {
        private final JLabel text = new JLabel();

        RecipeTip(lootgobbler.SetTokens.Cost cost) {
            setLayout(new BorderLayout(0, 4));
            setBorder(BorderFactory.createCompoundBorder(getBorder(), new EmptyBorder(3, 4, 4, 4)));
            add(text, BorderLayout.NORTH);
            JPanel recipe = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            recipe.setOpaque(false);
            recipe.add(new JLabel("Forge:"));
            JLabel tokens = new JLabel(cost.amount() + "×", CharactersView.sprite(cost.token(), 28), SwingConstants.LEFT);
            tokens.setHorizontalTextPosition(SwingConstants.LEFT);   // "2×" then the token
            recipe.add(tokens);
            if (cost.stSlot() != null) {
                recipe.add(new JLabel("+"));
                JLabel any = new JLabel("1×", SlotIcons.anySt(cost.stSlot(), 28), SwingConstants.LEFT);
                any.setHorizontalTextPosition(SwingConstants.LEFT);
                recipe.add(any);
            }
            add(recipe, BorderLayout.CENTER);
        }

        @Override
        public void setTipText(String tip) {
            super.setTipText(tip);
            text.setText(tip);
        }

        @Override
        public Dimension getPreferredSize() {
            return getLayout().preferredLayoutSize(this);
        }

        @Override
        protected void paintComponent(Graphics g) {   // the background only: the text is the label's
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }

    /**
     * A piece: the item with its outline (like the Characters view's equipment) and best enchantments in a box,
     * golden when you have it, dark with the item faded when missing; a gold frame on hover.
     */
    private class Tile extends JComponent {
        private final int id;
        private boolean hover;

        Tile(int id, String slot) {
            this.id = id;
            ItemDb.Item item = ItemDb.get().item(id);
            boolean have = owned.contains(id);
            setPreferredSize(new Dimension(TILE, TILE));
            setToolTipText((item == null ? "Item " + id : item.name) + "  (" + slot + (have ? ")" : " - missing)"));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
            Clicks.onClick(this, () -> {
                if (item != null) onPieceClick.accept(item);
            });
        }

        /** Pieces the forge crafts from set tokens show the recipe in their tooltip, with icons. */
        @Override
        public JToolTip createToolTip() {
            lootgobbler.SetTokens.Cost cost = lootgobbler.SetTokens.of(id);
            if (cost == null) return super.createToolTip();
            JToolTip tip = new RecipeTip(cost);
            tip.setComponent(this);
            return tip;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            boolean have = owned.contains(id);
            int x = (getWidth() - TILE) / 2;
            Color accent = Section.color("LootGobbler.gold", Palette.GOLD);
            g2.setColor(have ? Palette.mix(accent, OWNED_TINT, 0.7) : EMPTY_TILE);   // golden box: you have it
            g2.fillRect(x, 0, TILE, TILE);
            Icon icon = CharactersView.sprite(id, TILE - 10);   // outlined, like the character equipment
            if (icon != null) {
                if (!have) g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.25f));
                icon.paintIcon(this, g2, x + (TILE - icon.getIconWidth()) / 2, (TILE - icon.getIconHeight()) / 2);
                g2.setComposite(AlphaComposite.SrcOver);
            }
            if (have && entries.get(id) != null) {
                EnchantBadge.draw(g2, x + TILE - 3, TILE - 3, entries.get(id).maxEnchants(), 5);   // best copy
            }
            g2.setColor(hover ? accent : Color.BLACK);
            g2.setStroke(new BasicStroke(hover ? 2f : 1f));
            g2.drawRect(x, 0, TILE - 1, TILE - 1);
            g2.dispose();
        }
    }

    private static Icon skinIcon(int skin) {
        if (skin == 0) return null;
        try {
            return ImageBuffer.getOutlinedIcon(skin, 64);
        } catch (Exception e) {
            return null;
        }
    }

    private static void setBar(JProgressBar bar, JLabel text, int have, int total) {
        double pct = total == 0 ? 0 : 100.0 * have / total;
        bar.setValue((int) Math.round(pct * 10));
        text.setText(have + " / " + total + "   " + String.format("%.1f%%", pct));
    }

    private static JProgressBar bar(Color color) {
        JProgressBar b = new JProgressBar(0, 1000);
        if (color != null) b.setForeground(color);
        b.setAlignmentX(LEFT_ALIGNMENT);
        b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
        b.setPreferredSize(new Dimension(200, 10));
        return b;
    }

    private static JLabel caption(String text) {
        JLabel l = new JLabel(text.toUpperCase(Locale.ROOT));
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

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

    private static JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(c);
        return p;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
