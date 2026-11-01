package lootgobbler.ui;

import assets.ImageBuffer;
import lootgobbler.CharacterStore;
import lootgobbler.ExaltStore;
import tomato.realmshark.enums.CharacterClass;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.text.NumberFormat;
import java.util.*;
import java.util.List;

/**
 * Characters > Classes: one card per class with its exaltations (per stat and in total) and your characters of
 * that class, most exalted classes first; a side column sums up the account's exaltation progress.
 */
public class ClassesView extends JPanel {
    private static final int CARD_MIN_WIDTH = 400, GAP = 8, PORTRAIT = 72, ICON = 24;
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    // stats in the in-game order, with their index in the exalt data and the potion that stands for them
    private static final String[] STAT_NAMES = {"Life", "Mana", "Attack", "Defense", "Speed", "Dexterity", "Vitality", "Wisdom"};
    private static final int[] STAT_INDEX = {ExaltStore.LIFE, ExaltStore.MANA, ExaltStore.ATT, ExaltStore.DEF,
            ExaltStore.SPD, ExaltStore.DEX, ExaltStore.VIT, ExaltStore.WIS};
    private static final int[] STAT_POTION = {0xae9, 0xaea, 0xa1f, 0xa20, 0xa21, 0xa4c, 0xa34, 0xa35};
    private static final Color PIP_ON = new Color(0xFFD21F);
    private static final int PIP_W = 8, PIP_H = 11, PIP_STEP = 10;

    private final JPanel cards = new JPanel(new GridLayout(0, 1, GAP, GAP));
    private final JScrollPane cardScroll;
    private final JProgressBar totalBar = bar(null);
    private final JLabel totalText = new JLabel(" "), classesText = new JLabel(" ");
    private final JPanel perStat = column();

    public ClassesView() {
        super(new BorderLayout());
        setOpaque(false);

        cards.setOpaque(false);
        cards.setBorder(new EmptyBorder(10, 10, 10, 10));
        cardScroll = FitWidthPanel.scroll(cards, 24);
        cardScroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateColumns();
            }
        });
        Section main = new Section("Classes", Palette.GOLD, wrap(cardScroll));

        JPanel progress = column();
        progress.setBorder(new EmptyBorder(10, 10, 10, 10));
        progress.add(caption("Exaltations"));
        progress.add(totalBar);
        progress.add(totalText);
        progress.add(Box.createVerticalStrut(6));
        progress.add(classesText);
        perStat.setBorder(new EmptyBorder(8, 10, 8, 10));
        JPanel side = new JPanel(new BorderLayout(0, GAP));
        side.setOpaque(false);
        side.add(new Section("Exaltation Progress", Palette.GREEN, progress), BorderLayout.NORTH);
        side.add(new Section("Per Stat", Palette.PURPLE, wrap(FitWidthPanel.scroll(perStat, 16))), BorderLayout.CENTER);

        SideSplit split = new SideSplit(main, side, "classes", 280, 220);   // drag the gap to resize
        split.setSnap(cardScroll.getViewport(), (width, nearest) -> {   // settle where the cards fill whole columns
            float cols = (width - 20 + GAP) / (float) (CARD_MIN_WIDTH + GAP);
            int n = Math.max(1, nearest ? Math.round(cols) : (int) cols);
            return n * (CARD_MIN_WIDTH + GAP) - GAP + 20;
        });
        add(split, BorderLayout.CENTER);

        ExaltStore.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        CharacterStore.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    private record ClassInfo(CharacterClass cls, int[] progress, int total, int sum, List<CharacterStore.Char> chars) {
    }

    private void refresh() {
        Map<Integer, List<CharacterStore.Char>> byClass = new HashMap<>();
        for (CharacterStore.Char c : CharacterStore.get().all()) {
            if (c.alive) byClass.computeIfAbsent(c.classType, k -> new ArrayList<>()).add(c);
        }
        List<ClassInfo> infos = new ArrayList<>();
        for (CharacterClass cls : CharacterClass.CHAR_CLASS_LIST) {
            int[] p = ExaltStore.get().progress(cls.getId());
            int sum = p == null ? 0 : Arrays.stream(p).limit(8).sum();
            List<CharacterStore.Char> chars = byClass.getOrDefault(cls.getId(), List.of());
            infos.add(new ClassInfo(cls, p, ExaltStore.total(p), sum, chars));
        }
        // most exalted first; ties by progress towards the next exaltations, then name
        infos.sort(Comparator.comparingInt((ClassInfo i) -> -i.total).thenComparingInt(i -> -i.sum)
                .thenComparing(i -> i.cls.toString()));

        cards.removeAll();
        for (ClassInfo i : infos) cards.add(new Card(i));
        updateColumns();
        updateSide(infos);
    }

    private void updateColumns() {
        int width = cardScroll.getViewport().getWidth();
        if (width > 0) {   // 0 while the view is hidden; it is recomputed when shown
            ((GridLayout) cards.getLayout()).setColumns(Math.max(1, (width - 20 + GAP) / (CARD_MIN_WIDTH + GAP)));
        }
        cards.revalidate();
        cards.repaint();
    }

    private void updateSide(List<ClassInfo> infos) {
        int total = infos.stream().mapToInt(ClassInfo::total).sum(), max = infos.size() * ExaltStore.MAX_PER_CLASS;
        double pct = max == 0 ? 0 : 100.0 * total / max;
        totalBar.setValue((int) Math.round(pct * 10));
        totalText.setText(total + " / " + max + "   " + String.format("%.1f%%", pct));
        long full = infos.stream().filter(i -> i.total == ExaltStore.MAX_PER_CLASS).count();
        classesText.setText(ExaltStore.get().isEmpty()
                ? "Exaltations load when you log in with the sniffer running."
                : full + " of " + infos.size() + " classes fully exalted");
        classesText.setForeground(Palette.MUTED);

        perStat.removeAll();
        int statMax = infos.size() * ExaltStore.MAX_PER_STAT;
        for (int s = 0; s < 8; s++) {
            int stat = STAT_INDEX[s], have = 0;
            for (ClassInfo i : infos) if (i.progress != null) have += ExaltStore.level(i.progress[stat]);
            JPanel row = new JPanel(new BorderLayout(6, 2));
            row.setOpaque(false);
            row.setAlignmentX(LEFT_ALIGNMENT);
            JLabel name = new JLabel(STAT_NAMES[s], potion(s, 18), SwingConstants.LEFT);
            name.setIconTextGap(8);
            row.add(name, BorderLayout.CENTER);
            row.add(new JLabel(have + " / " + statMax), BorderLayout.EAST);
            JProgressBar b = bar(null);
            b.setValue(statMax == 0 ? 0 : 1000 * have / statMax);
            b.setPreferredSize(new Dimension(100, 5));
            row.add(b, BorderLayout.SOUTH);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            perStat.add(row);
            perStat.add(Box.createVerticalStrut(5));
        }
        perStat.revalidate();
        repaint();
    }

    /**
     * One class: sprite on the left, then name with total exaltations, the 8 stats with their exaltation pips,
     * and your characters of that class.
     */
    private static class Card extends JPanel {
        private final boolean done;

        Card(ClassInfo info) {
            super(new BorderLayout(10, 0));
            done = info.total == ExaltStore.MAX_PER_CLASS;
            setOpaque(false);
            setBorder(new EmptyBorder(6, 6, 6, 10));
            Color classColor = Palette.CLASS_COLORS.getOrDefault(info.cls.toString(), Palette.STONE);

            int skin = done ? exaltedSkin(info.cls.toString()) : -1;   // fully exalted: the class's Exalted skin
            Icon look = skin > 0 ? sprite(skin, PORTRAIT - 8) : null;
            JLabel sprite = new JLabel(portrait(look != null ? look : sprite(info.cls.getId(), PORTRAIT - 8)));
            sprite.setPreferredSize(new Dimension(PORTRAIT, PORTRAIT));
            add(sprite, BorderLayout.WEST);

            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setOpaque(false);

            JPanel head = new JPanel(new BorderLayout(8, 0));
            head.setOpaque(false);
            head.setAlignmentX(LEFT_ALIGNMENT);
            JLabel name = new JLabel(info.cls.toString(), new Section.Square(classColor, 8), SwingConstants.LEFT);
            name.setIconTextGap(8);
            name.setFont(name.getFont().deriveFont(22f));
            head.add(name, BorderLayout.WEST);
            JLabel total = new JLabel(info.progress == null ? "-" : info.total + " / " + ExaltStore.MAX_PER_CLASS);
            total.setFont(total.getFont().deriveFont(20f));
            total.setForeground(done ? Section.color("LootGobbler.gold", Palette.GOLD) : Section.color("LootGobbler.ink", Color.DARK_GRAY));
            total.setToolTipText("Exaltations of this class");
            head.add(total, BorderLayout.EAST);
            head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
            body.add(head);
            body.add(Box.createVerticalStrut(4));

            JPanel stats = new JPanel(new GridLayout(2, 4, 8, 4));
            stats.setOpaque(false);
            stats.setAlignmentX(LEFT_ALIGNMENT);
            for (int s = 0; s < 8; s++) {
                int progress = info.progress == null ? 0 : info.progress[STAT_INDEX[s]];
                stats.add(new StatCell(s, progress, info.progress != null));
            }
            stats.setMaximumSize(stats.getPreferredSize());
            body.add(stats);
            body.add(Box.createVerticalStrut(4));

            String chars;
            if (info.chars.isEmpty()) {
                chars = "No characters";
            } else {
                long best = info.chars.stream().mapToLong(c -> c.fame).max().orElse(0);
                chars = info.chars.size() + (info.chars.size() == 1 ? " character" : " characters")
                        + "  ·  best " + NUM.format(best) + " fame";
            }
            JLabel who = new JLabel(chars);
            who.setForeground(Palette.MUTED);
            who.setAlignmentX(LEFT_ALIGNMENT);
            body.add(who);
            add(body, BorderLayout.CENTER);
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
    }

    /**
     * A stat: its potion and five pips, one per exaltation earned.
     */
    private static class StatCell extends JComponent {
        private final Icon icon;
        private final int level;

        StatCell(int stat, int progress, boolean known) {
            icon = potion(stat, ICON);
            level = ExaltStore.level(progress);
            String next = level < ExaltStore.MAX_PER_STAT
                    ? ", " + progress + " / " + ExaltStore.THRESHOLDS[level] + " to the next" : ", maxed";
            setToolTipText(known ? STAT_NAMES[stat] + ": " + level + " / " + ExaltStore.MAX_PER_STAT + next : STAT_NAMES[stat]);
            setPreferredSize(new Dimension(ICON + 6 + ExaltStore.MAX_PER_STAT * PIP_STEP, ICON));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            if (icon != null) icon.paintIcon(this, g2, 0, (getHeight() - icon.getIconHeight()) / 2);
            int x = ICON + 6, y = (getHeight() - PIP_H) / 2;
            // narrow cards give the cell less than its preferred width: shrink the pips so all five stay visible
            int step = Math.max(3, Math.min(PIP_STEP, (getWidth() - x) / ExaltStore.MAX_PER_STAT));
            int w = Math.max(2, step - (PIP_STEP - PIP_W));
            for (int i = 0; i < ExaltStore.MAX_PER_STAT; i++) {
                int px = x + i * step;
                if (i < level) {   // earned: gold with a black frame
                    g2.setColor(Color.BLACK);
                    g2.fillRect(px, y, w, PIP_H);
                    g2.setColor(PIP_ON);
                    g2.fillRect(px + 1, y + 1, w - 2, PIP_H - 2);
                } else {
                    g2.setColor(new Color(0, 0, 0, 50));
                    g2.fillRect(px + 1, y + 1, w - 2, PIP_H - 2);
                }
            }
            g2.dispose();
        }
    }

    private static Icon potion(int stat, int size) {
        return sprite(STAT_POTION[stat], size);
    }

    private static Map<String, Integer> exaltedSkins;

    /**
     * Object id of the "Exalted <Class>" skin from the game data, or -1. The ids are looked up once by name, since
     * newer classes don't follow the numbering of the older ones.
     */
    private static synchronized int exaltedSkin(String className) {
        if (exaltedSkins == null) {
            exaltedSkins = new HashMap<>();
            for (int id = 0; id <= 0xFFFF; id++) {
                String name = assets.IdToAsset.getObjectIdName(id);
                if (name != null && name.startsWith("Exalted ") && "Skin".equals(assets.IdToAsset.getClazz(id))) {
                    exaltedSkins.put(name.substring("Exalted ".length()), id);
                }
            }
        }
        return exaltedSkins.getOrDefault(className, -1);
    }

    private static Icon sprite(int id, int size) {
        try {
            return ImageBuffer.getOutlinedIcon(id, size);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Dark tile behind the class sprite, as on the character cards.
     */
    private static Icon portrait(Icon sprite) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                g.setColor(new Color(0x15141A));
                g.fillRect(x, y, PORTRAIT, PORTRAIT);
                g.setColor(Color.BLACK);
                g.drawRect(x, y, PORTRAIT - 1, PORTRAIT - 1);
                if (sprite != null) {
                    sprite.paintIcon(c, g, x + (PORTRAIT - sprite.getIconWidth()) / 2, y + (PORTRAIT - sprite.getIconHeight()) / 2);
                }
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
}
