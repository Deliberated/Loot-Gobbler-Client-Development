package lootgobbler.ui;

import assets.IdToAsset;
import assets.ImageBuffer;
import lootgobbler.DpsHistory;
import tomato.backend.data.DungeonStatData;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.text.NumberFormat;
import java.util.*;
import java.util.List;

/**
 * Statistics > Dungeon Stats: favourite dungeons as cards, then every other dungeon with run count and
 * your peak boss DPS (last run vs. best). Run counts come from Tomato's dungeon.stats; DPS from DpsHistory.
 */
public class DungeonStatsPanel extends JPanel {
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    private static final Color[] MARKERS = {Palette.PURPLE, Palette.BLUE, Palette.RED, Palette.GOLD, Palette.GREEN};
    private static volatile DungeonStatData latest;
    private static DungeonStatsPanel instance;

    private record Row(String name, int runs, double last, double best, int bossId) {
    }

    private final JPanel content = new JPanel();

    public DungeonStatsPanel() {
        super(new BorderLayout());
        instance = this;
        setBorder(new EmptyBorder(8, 8, 8, 8));
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(new EmptyBorder(10, 12, 12, 12));
        JScrollPane sp = FitWidthPanel.scroll(content, 24);
        add(new Section("Dungeon Stats", Palette.BLUE, wrap(sp)));
        DpsHistory.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    /**
     * Called by Tomato's DungeonStats whenever dungeon.stats changes.
     */
    public static void update(DungeonStatData data) {
        latest = data;
        if (instance != null) SwingUtilities.invokeLater(instance::refresh);
    }

    private void refresh() {
        List<Row> rows = rows();
        content.removeAll();
        if (rows.isEmpty()) {
            JLabel empty = new JLabel("Run some dungeons to see your stats here.");
            empty.setForeground(Palette.MUTED);
            content.add(empty);
        } else {
            content.add(card(rows.get(0), "Favourite dungeon", true));
            if (rows.size() > 1) {
                content.add(Box.createVerticalStrut(8));
                JPanel pair = new JPanel(new GridLayout(1, 2, 8, 0));
                pair.setOpaque(false);
                pair.setAlignmentX(LEFT_ALIGNMENT);
                pair.add(card(rows.get(1), "#2", false));
                pair.add(rows.size() > 2 ? card(rows.get(2), "#3", false) : filler());
                pair.setMaximumSize(new Dimension(Integer.MAX_VALUE, pair.getPreferredSize().height));
                content.add(pair);
            }
            if (rows.size() > 3) {
                content.add(Box.createVerticalStrut(14));
                content.add(caption("Other dungeons"));
                content.add(Box.createVerticalStrut(4));
                double maxBest = rows.stream().mapToDouble(Row::best).max().orElse(1);
                content.add(tableHeader());
                for (int i = 3; i < rows.size(); i++) content.add(tableRow(rows.get(i), i, maxBest));
            }
        }
        content.revalidate();
        content.repaint();
    }

    private List<Row> rows() {
        DungeonStatData data = latest;
        Map<String, double[]> peaks = new HashMap<>();   // name -> {last, best}
        for (DpsHistory.Run r : DpsHistory.get().runs()) {   // newest first
            double p = r.myPeakDps();
            double[] v = peaks.computeIfAbsent(r.dungeon, k -> new double[]{p, 0});
            v[1] = Math.max(v[1], p);
        }
        List<Row> rows = new ArrayList<>();
        if (data == null || data.data == null) return rows;
        try {
            for (DungeonStatData.DungeonInfo info : new ArrayList<>(data.data.values())) {
                if (info.getEnteredDungeon() <= 0) continue;
                double[] pk = peaks.getOrDefault(info.getName(), new double[]{0, 0});
                rows.add(new Row(info.getName(), info.getEnteredDungeon(), pk[0], pk[1], topBoss(info)));
            }
        } catch (ConcurrentModificationException e) {
            return rows;   // updated while reading; the next update redraws
        }
        rows.sort(Comparator.comparingInt(Row::runs).reversed());
        return rows;
    }

    /**
     * The boss you hit most often in this dungeon, for the card sprite.
     */
    private static int topBoss(DungeonStatData.DungeonInfo info) {
        int best = 0, bestHits = -1;
        for (Map.Entry<Integer, Integer> e : info.getEntityDamaged().entrySet()) {
            String label = IdToAsset.getIdLabel(e.getKey());
            boolean boss = label != null && Arrays.asList(label.split(",")).contains("BOSS");
            if (boss && e.getValue() > bestHits) {
                best = e.getKey();
                bestHits = e.getValue();
            }
        }
        return best;
    }

    private JPanel card(Row r, String caption, boolean favourite) {
        JLabel icon = new JLabel(sprite(r.bossId(), favourite ? 56 : 40));
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        JLabel cap = caption(caption);
        cap.setForeground(Palette.GOLD);
        JLabel name = new JLabel(favourite ? r.name().toUpperCase(Locale.ROOT) : r.name());
        Font f = UIManager.getFont("TabbedPane.font");
        if (favourite && f != null) name.setFont(f.deriveFont(f.getSize2D() + 3));
        else name.setFont(name.getFont().deriveFont(18f));
        JLabel runs = new JLabel(r.runs() + " runs");
        runs.setForeground(Palette.GOLD);
        JLabel peaks = new JLabel("last peak: " + dps(r.last()) + "   |   current peak: " + dps(r.best()));
        for (JLabel l : new JLabel[]{cap, name, runs, peaks}) {
            l.setAlignmentX(LEFT_ALIGNMENT);
            text.add(l);
            text.add(Box.createVerticalStrut(2));
        }
        JPanel card = new JPanel(new BorderLayout(14, 0)) {
            @Override
            public void updateUI() {
                super.updateUI();
                setBackground(Section.color("TableHeader.background", getBackground()));
                Color border = favourite ? Palette.GOLD : Section.color("Component.borderColor", Color.GRAY);
                setBorder(BorderFactory.createCompoundBorder(new LineBorder(border), new EmptyBorder(10, 14, 10, 14)));
            }
        };
        card.add(icon, BorderLayout.WEST);
        card.add(text, BorderLayout.CENTER);
        card.setAlignmentX(LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        return card;
    }

    private JPanel tableHeader() {
        JPanel p = grid(null);
        p.add(caption("Dungeon"), gc(0));
        p.add(caption("Runs"), gc(1));
        p.add(new JLabel(), gc(2));
        JLabel peak = caption("Your peak DPS");
        peak.setHorizontalAlignment(SwingConstants.RIGHT);
        p.add(peak, gc(3));
        return p;
    }

    private JPanel tableRow(Row r, int index, double maxBest) {
        JPanel p = grid(index % 2 == 1 ? "Table.alternateRowColor" : "Table.background");
        JLabel name = new JLabel(r.name(), new Section.Square(MARKERS[Math.floorMod(r.name().hashCode(), MARKERS.length)], 10),
                SwingConstants.LEFT);
        name.setIconTextGap(8);
        p.add(name, gc(0));
        p.add(new JLabel(String.valueOf(r.runs())), gc(1));
        p.add(new Bars(r.last() / Math.max(1, maxBest), r.best() / Math.max(1, maxBest)), gc(2));
        JLabel peaks = new JLabel("<html><font color='#938B79'>" + dps0(r.last()) + "</font>&nbsp;&nbsp;&nbsp;<b>"
                + dps0(r.best()) + "</b></html>");
        peaks.setHorizontalAlignment(SwingConstants.RIGHT);
        p.add(peaks, gc(3));
        return p;
    }

    private static JPanel grid(String bgKey) {
        JPanel p = new JPanel(new GridBagLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                if (bgKey != null) setBackground(Section.color(bgKey, getBackground()));
            }
        };
        p.setOpaque(bgKey != null);
        p.setBorder(new EmptyBorder(3, 6, 3, 6));
        p.setAlignmentX(LEFT_ALIGNMENT);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        return p;
    }

    private static GridBagConstraints gc(int col) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = col;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 4, 0, 4);
        c.weightx = switch (col) {
            case 0 -> 0.35;
            case 2 -> 0.45;
            default -> 0.1;
        };
        return c;
    }

    private static JLabel caption(String text) {
        JLabel l = new JLabel(text.toUpperCase(Locale.ROOT));
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setAlignmentX(LEFT_ALIGNMENT);
        return l;
    }

    private static Icon sprite(int id, int size) {
        if (id == 0) return null;
        try {
            return ImageBuffer.getOutlinedIcon(id, size);
        } catch (Exception e) {
            return null;
        }
    }

    private static String dps(double v) {
        return v <= 0 ? "-" : NUM.format(Math.round(v)) + " dps";
    }

    private static String dps0(double v) {
        return v <= 0 ? "-" : NUM.format(Math.round(v));
    }

    private static JPanel filler() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        return p;
    }

    private static JPanel wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.add(c);
        return p;
    }

    /**
     * Two stacked bars: best (top, gold) and last run (bottom, dark gold), on a track.
     */
    private static class Bars extends JComponent {
        private final double last, best;

        Bars(double last, double best) {
            this.last = Math.min(1, last);
            this.best = Math.min(1, best);
            setPreferredSize(new Dimension(120, 18));
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth(), h = getHeight(), half = (h - 4) / 2;
            g.setColor(Section.color("Component.borderColor", Color.GRAY));
            g.fillRect(0, 2, w, h - 4);
            g.setColor(Palette.GOLD);
            g.fillRect(0, 2, (int) (w * best), half);
            g.setColor(Palette.GOLD_DARK);
            g.fillRect(0, 2 + half, (int) (w * last), h - 4 - half);
        }
    }
}
