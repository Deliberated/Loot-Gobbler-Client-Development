package lootgobbler.ui;

import lootgobbler.DpsHistory;
import lootgobbler.DpsHistory.Boss;
import lootgobbler.DpsHistory.PlayerRow;
import lootgobbler.DpsHistory.Run;
import lootgobbler.DungeonVisits.Exalt;
import lootgobbler.ExaltStore;
import lootgobbler.DungeonVisits;
import lootgobbler.DungeonVisits.Visit;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The Battles tab: Dungeons (every dungeon you entered, the one you are in on top) and DPS (boss damage and the run
 * history). Clicking a dungeon shows it in a column on the right: when and how long, your places on its bosses (with
 * a button to their boss damage under DPS) and the exaltation progress the game reported there.
 */
public class DpsLoggerView extends JPanel {
    private static final String[] PAGES = {"Dungeons", "DPS"};
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);
    private static final int ICON = 64, POTION = 22, MATE = 30, MATES = 4, SINGLES = 4, MULTIS = 2;
    private static final int TILE_MIN_WIDTH = 400, GAP = 8;
    // ExaltStore's stat order (dex, spd, vit, wis, def, att, mana, life): names, and the potion index ClassesView uses
    private static final String[] EXALT_STATS = {"DEX", "SPD", "VIT", "WIS", "DEF", "ATT", "MP", "HP"};
    private static final int[] EXALT_POTION = {5, 4, 6, 7, 3, 2, 1, 0};

    private final DpsPanel dps;
    private final CardLayout pages = new CardLayout();
    private final JPanel views = new JPanel(pages);
    private final Segmented picker;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private final JPanel list = new JPanel();   // per day: its date over a gold line, then its boxes
    private final List<JPanel> dayGrids = new ArrayList<>();
    private JScrollPane listScroll;
    private final Map<String, Icon> icons = new HashMap<>();
    private final Timer clock = new Timer(1000, e -> tick());   // the visit in progress counts up
    private JLabel nowWhen;   // the time line of the visit in progress
    private Visit nowVisit;
    private final JPanel detail = new JPanel();
    private final Map<Long, Long> fame = new HashMap<>();   // fame gained per finished visit (by its start)
    private final List<DamageGraph> graphs = new ArrayList<>();   // of the visit shown on the right (one per slice size)
    private final List<JLabel> graphCaptions = new ArrayList<>();
    private JLabel takenStats;
    private Visit graphVisit;
    private boolean scrollToSelected;   // bring the selected row into view after the next rebuild
    private long selected = -1;
    private long lastNowStart = -1;         // start of the dungeon in progress at the last refresh
    private String detailKey = "";          // what the details were last built from
    private boolean detailPending;          // a rebuild waits for a scrollbar drag to end   // start of the visit shown on the right; -1 = the newest

    public DpsLoggerView(DpsPanel dps) {
        super(new BorderLayout(0, 8));
        setBorder(new EmptyBorder(8, 8, 8, 8));   // the same frame as the Characters page
        this.dps = dps;
        setOpaque(false);
        dps.setBorder(new EmptyBorder(0, 0, 0, 0));   // framed here already
        picker = new Segmented(false, 0, i -> pages.show(views, PAGES[i]), PAGES);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(picker, BorderLayout.WEST);
        add(top, BorderLayout.NORTH);

        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.setBorder(new EmptyBorder(4, 10, 10, 10));
        JButton clear = new JButton("CLEAR ALL");
        clear.putClientProperty("FlatLaf.styleClass", "danger");
        clear.setFocusable(false);
        clear.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Remove all dungeon visits from the list?\nThe DPS run history stays.",
                    "Clear dungeons", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                DungeonVisits.get().clear();
            }
        });
        listScroll = FitWidthPanel.scroll(list, 24);
        listScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                updateColumns();
            }
        });
        Section dungeons = new Section("Dungeons", Palette.BLUE, listScroll).actions(clear);
        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        detail.setOpaque(false);
        SideSplit split = new SideSplit(dungeons, FitWidthPanel.scroll(detail, 24), "dungeonDetail", 420, 300);
        split.setSnap(listScroll.getViewport(), (width, nearest) -> {   // settle where the boxes fill whole columns
            float cols = (width - 20 + GAP) / (float) (TILE_MIN_WIDTH + GAP);
            int n = Math.max(1, nearest ? Math.round(cols) : (int) cols);
            return n * (TILE_MIN_WIDTH + GAP) - GAP + 20;
        });
        views.setOpaque(false);
        views.add(split, PAGES[0]);
        views.add(dps, PAGES[1]);
        add(views, BorderLayout.CENTER);

        Runnable later = ViewRefresh.of(this, this::refresh);   // only while it can be seen, once per burst
        DungeonVisits.get().onChange(later);
        DpsHistory.get().onChange(later);
        Guildmates.get().onChange(later);   // who of your guild was with you
        // new hits in the dungeon you are in: only its graph (if shown) follows, not the whole list
        DungeonVisits.get().onHits(() -> SwingUtilities.invokeLater(() -> {
            if (!graphs.isEmpty() && graphVisit == DungeonVisits.get().current()) {
                takenStats.setText(takenText(graphVisit));
                for (int i = 0; i < graphs.size(); i++) {
                    graphCaptions.get(i).setText(captionText(graphVisit, DamageGraph.WINDOWS_MS[i]));
                    graphs.get(i).update(false);   // longer now; a view at the latest end follows
                }
            }
        }));
        refresh();
    }

    /** The dungeon you are in: its boss damage under DPS once a boss was hit, else the Dungeons list. */
    public void showCurrent() {
        Run live = DpsHistory.get().live();
        if (live != null && !live.guildHall && live.mainBoss() != null) {
            showRun(null);
        } else {
            picker.select(0);
            pages.show(views, PAGES[0]);
        }
    }

    private static java.util.function.Consumer<Visit> visitOpener = v -> {
    };

    /** How to open a dungeon visit from elsewhere (set on start: switches to Battles and shows it). */
    public static void setVisitOpener(java.util.function.Consumer<Visit> opener) {
        visitOpener = opener;
    }

    /** Opens this visit under Battles > Dungeons, e.g. from a dungeon icon on a fame chart. */
    static void openVisit(Visit v) {
        visitOpener.accept(v);
    }

    /** The Dungeons list with this visit selected and shown on the right. */
    public void showVisit(Visit v) {
        selected = v.start;
        scrollToSelected = true;
        picker.select(0);
        pages.show(views, PAGES[0]);
        refresh();
    }

    /** The Dungeons list with the dungeon you are in shown on the right (e.g. from the title bar). */
    public void showDungeons() {
        selected = -1;   // the one in progress, else the newest
        picker.select(0);
        pages.show(views, PAGES[0]);
        refresh();
    }

    /** Opens a run under DPS (null: the one in progress), e.g. from the Profile or the top bar. */
    public void showRun(Run run) {
        dps.showRun(run);
        picker.select(1);
        pages.show(views, PAGES[1]);
    }

    private void refresh() {
        Visit now = DungeonVisits.get().current();
        if (now != null && now.start != lastNowStart) {   // a new dungeon entered: show it, whatever was selected
            selected = -1;
            scrollToSelected = true;
        }
        lastNowStart = now == null ? -1 : now.start;
        if (now != null && !clock.isRunning()) clock.start();
        if (now == null) clock.stop();
        List<Visit> visits = DungeonVisits.get().visits();
        List<Run> runs = DpsHistory.get().runs();
        list.removeAll();
        dayGrids.clear();
        nowWhen = null;
        nowVisit = now;
        // the selected visit, else the one in progress, else the newest
        Visit shown = null;
        if (now != null && now.start == selected) shown = now;
        for (Visit v : visits) if (v.start == selected) shown = v;
        if (shown == null) shown = now != null ? now : visits.isEmpty() ? null : visits.get(0);
        long shownStart = shown == null ? -1 : shown.start;
        if (now == null && visits.isEmpty()) {
            JLabel empty = new JLabel("The dungeons you enter appear here.");
            empty.setForeground(Palette.MUTED);
            list.add(empty);
        }
        java.time.LocalDate day = null;
        JPanel grid = null;
        if (now != null) {
            Run live = DpsHistory.get().live();
            boolean fought = live != null && !live.guildHall && live.dungeon.equals(now.dungeon) && live.mainBoss() != null;
            day = dayOf(now);
            grid = dayGrid(day);
            grid.add(tile(now, fought ? live : null, true, now.start == shownStart));
        }
        Run shownRun = null;
        for (Visit v : visits) {
            Run run = null;
            if (v.runStart != 0) for (Run r : runs) if (r.start == v.runStart) run = r;
            if (v == shown) shownRun = run;
            if (!dayOf(v).equals(day)) {   // newest first: a new day starts its own group
                day = dayOf(v);
                grid = dayGrid(day);
            }
            grid.add(tile(v, run, false, v == shown));
        }
        updateColumns();
        if (shown == now && now != null) {
            Run live = DpsHistory.get().live();
            shownRun = live != null && !live.guildHall && live.dungeon.equals(now.dungeon) && live.mainBoss() != null ? live : null;
        }
        // the details only when something in them changed (a finished dungeon never does), and never while its
        // graph's scrollbar is being dragged: rebuilding would take the bar away from under the mouse
        String key = shown == null ? "" : detailKey(shown, shownRun, shown == now);
        boolean dragging = graphs.stream().anyMatch(DamageGraph::dragging);
        if (dragging) {
            detailPending = true;
        } else if (!key.equals(detailKey)) {
            detailKey = key;
            detailPending = false;
            showDetail(shown, shownRun, shown != null && shown == now);
        }
    }

    /** The column on the right: the visit, its boss fights and the exaltation progress made in it. */
    /** What the details show of a visit: when this is unchanged, they need no rebuild. */
    private static String detailKey(Visit v, Run run, boolean current) {
        int bosses = 0;
        if (run != null) for (Boss b : run.bosses) bosses += b.players.size() + (b.me() != null ? b.rankOf(b.me()) : 0) * 1000;
        // the dungeon in progress: its time in the details moves on, refreshed every 5 seconds
        return v.start + "|" + current + "|" + v.dungeon + "|" + (current ? v.duration() / 5000 : v.durationMs) + "|" + v.hits() + "|"
                + (v.dealt == null ? 0 : v.dealt.length) + "|" + (v.exalts == null ? 0 : v.exalts.size()) + "|"
                + (v.amuletSaves == null ? 0 : v.amuletSaves.length) + "|" + v.runStart + "|" + v.lanternActiveMs / 1000
                + "|" + (run == null ? 0 : run.bosses.size()) + "|" + bosses;
    }

    private void showDetail(Visit v, Run run, boolean current) {
        detail.removeAll();
        if (v == null) {
            detail.revalidate();
            detail.repaint();
            return;
        }
        JPanel head = new JPanel(new BorderLayout(14, 0));
        head.setOpaque(false);
        head.add(new JLabel(ProfileView.dungeonIcon(v.dungeon, 64)), BorderLayout.WEST);
        JPanel text = column();
        JLabel name = new JLabel(v.dungeon);
        name.setFont(name.getFont().deriveFont(26f));
        text.add(name);
        text.add(muted(whenText(v, current)));
        long gained = fame(v, current);
        if (gained > 0) {   // the fame you gained in it, as a number
            JLabel f = new JLabel("+" + String.format(Locale.ROOT, "%,d", gained) + " fame");
            f.setFont(f.getFont().deriveFont(18f));
            f.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
            text.add(f);
        }
        if (v.lanternActiveMs > 0) {   // Moonlight Village: time near its lantern while it was there
            JLabel lantern = muted(String.format(Locale.ROOT, "Lantern: near it %.0f%% of %s (%s)  ·  close %.0f%%",
                    100.0 * v.lanternRangeMs / v.lanternActiveMs, DpsPanel.duration(v.lanternActiveMs),
                    DpsPanel.duration(v.lanternRangeMs), 100.0 * v.lanternNearMs / v.lanternActiveMs));
            lantern.setToolTipText("While the lantern was there: near = within 3.5 tiles, close = within 1.75 tiles");
            text.add(lantern);
        }
        head.add(text, BorderLayout.CENTER);
        detail.add(CharactersView.box("Dungeon", Palette.BLUE, head));

        JPanel bosses = column();
        if (run == null) {
            bosses.add(muted(current ? "No boss hit yet." : "No boss fought."));
        } else {
            for (Boss b : run.bosses) {
                PlayerRow me = b.me();
                if (me == null) continue;
                bosses.add(new JLabel(b.name + ":  #" + b.rankOf(me) + " of " + b.players.size()
                        + "   \u00B7   " + String.format(Locale.ROOT, "%,.0f", b.dps(me)) + " DPS"));
            }
            bosses.add(Box.createVerticalStrut(8));
            Run target = current ? null : run;   // the one in progress is followed live
            JButton open = new JButton("SHOW BOSS DAMAGE");
            open.setFocusable(false);
            open.addActionListener(e -> showRun(target));
            bosses.add(open);
        }
        detail.add(CharactersView.box("Boss Fights", Palette.GOLD, bosses));

        JPanel taken = column();   // every hit you took, the big ones with the enemy behind them
        takenStats = muted(takenText(v));
        takenStats.setAlignmentX(LEFT_ALIGNMENT);
        taken.add(takenStats);
        // the details are rebuilt whenever new data comes in (every second in a fight): keep where the graph was
        // scrolled to for the same dungeon, instead of jumping back to its latest end
        boolean same = graphVisit != null && graphVisit.start == v.start && !graphs.isEmpty();
        int keptValue = same ? graphs.get(0).scrollValue() : 0;
        boolean keptAtEnd = !same || graphs.get(0).atLatestEnd();
        graphs.clear();
        graphCaptions.clear();
        graphVisit = v;
        // the hits added up per slice of 2 seconds (a finished dungeon without hits needs no graph)
        boolean anyDealt = v.dealt != null && java.util.Arrays.stream(v.dealt).anyMatch(d -> d > 0);
        for (int window : v.hits() == 0 && !anyDealt && !current ? new int[0] : DamageGraph.WINDOWS_MS) {
            taken.add(Box.createVerticalStrut(8));
            JLabel caption = new JLabel(captionText(v, window));
            caption.putClientProperty("FlatLaf.styleClass", "small");
            caption.setToolTipText("Damage taken added up per " + (window / 1000) + (window == 1000 ? " second" : " seconds")
                    + "; orange: 30% of your life or 300 damage in one of them");
            caption.setAlignmentX(LEFT_ALIGNMENT);
            taken.add(caption);
            taken.add(Box.createVerticalStrut(2));
            DamageGraph graph = new DamageGraph(v, window);
            graph.restore(keptValue, keptAtEnd);
            graph.onDragEnd(() -> {   // a rebuild that waited for the drag
                if (detailPending) refresh();
            });
            graph.setAlignmentX(LEFT_ALIGNMENT);
            JComponent scrolled = graph.withScrollbar();   // a window of 100 slices, scrolled under it
            scrolled.setAlignmentX(LEFT_ALIGNMENT);
            taken.add(scrolled);
            graphs.add(graph);
            graphCaptions.add(caption);
        }
        detail.add(CharactersView.box("Damage", Palette.RED, taken));   // dealt above the line, taken below

        JPanel exalts = column();
        if (v.exalts == null || v.exalts.isEmpty()) {
            exalts.add(muted(current ? "No exaltation progress yet." : "No exaltation progress in this dungeon."));
        } else {
            for (Exalt e : merged(v)) exalts.add(exaltRow(e));
        }
        detail.add(CharactersView.box("Exaltations", Palette.PURPLE, exalts));
        detail.revalidate();
        detail.repaint();
    }

    /** One per class and stat: several updates in one dungeon are added up. */
    private static java.util.Collection<Exalt> merged(Visit v) {
        java.util.Map<String, Exalt> merged = new java.util.LinkedHashMap<>();
        for (Exalt e : v.exalts) {
            Exalt m = merged.get(e.classType + ":" + e.stat);
            if (m == null) {
                m = new Exalt();
                m.classType = e.classType;
                m.stat = e.stat;
                m.before = e.before;
                m.after = e.after;
                merged.put(e.classType + ":" + e.stat, m);
            } else {
                m.before = Math.min(m.before, e.before);
                m.after = Math.max(m.after, e.after);
            }
        }
        return merged.values();
    }

    /**
     * One reported exaltation progress: the class, the stat's potion and the progress before and after; "Exalted"
     * (in gold) if it reached the full 75 here, "Already exalted" (gold too) if it was there before.
     */
    private static JComponent exaltRow(Exalt e) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        String cls = tomato.realmshark.enums.CharacterClass.getName(e.classType);
        JLabel who = new JLabel(cls == null ? "Class " + e.classType : cls, CharactersView.sprite(e.classType, 32), SwingConstants.LEFT);
        who.setIconTextGap(8);
        who.setPreferredSize(new Dimension(150, 36));
        row.add(who, BorderLayout.WEST);
        int n = e.earned();
        String stat = EXALT_STATS[e.stat];
        // "WIS 73/75 (+1)": the progress now out of the full 75, and how much this dungeon added
        int full = ExaltStore.THRESHOLDS[ExaltStore.THRESHOLDS.length - 1];
        String text = e.before >= full ? "Already exalted" : e.after >= full ? "Exalted"
                : String.format(Locale.ROOT, "%s %d/%d (+%d)%s", stat, e.after, full, e.after - e.before,
                n > 0 ? "  \u00b7  exaltation " + ExaltStore.level(e.after) + "/" + ExaltStore.MAX_PER_STAT + "!" : "");
        JLabel what = new JLabel(text, ClassesView.potion(EXALT_POTION[e.stat], 20), SwingConstants.LEFT);
        what.setToolTipText(e.before + " \u2192 " + e.after + " of " + full + " progress; exaltations come at "
                + java.util.Arrays.toString(ExaltStore.THRESHOLDS));
        what.setIconTextGap(8);
        if (e.after >= full) what.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        row.add(what, BorderLayout.CENTER);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    /** "PER 2 S \u00B7 4 OVER 30% / 300": a graph's slice size and how many of its slices reach the danger mark. */
    private static String captionText(Visit v, int window) {
        int n = DamageGraph.dangerous(v, window);
        return ("Per " + (window / 1000) + " s  \u00B7  " + n + " over 30% / 300").toUpperCase(Locale.ROOT);
    }

    /** "4,320 damage in 152 hits · biggest 410 (Septavius)". */
    private static String takenText(Visit v) {
        int n = v.hits();
        long dealt = 0;
        if (v.dealt != null) for (int d : v.dealt) dealt += d;
        String dealtText = dealt > 0 ? String.format(Locale.ROOT, "Dealt %,d", dealt) + "  ·  " : "";
        if (n == 0) return dealtText + "No damage taken.";
        long total = 0;
        int biggest = 0;
        for (int i = 0; i < n; i++) {
            total += v.hitDamage[i];
            if (v.hitDamage[i] > v.hitDamage[biggest]) biggest = i;
        }
        String who = v.hitEnemy[biggest] > 0 ? " (" + assets.IdToAsset.objectName(v.hitEnemy[biggest]) + ")" : "";
        int saves = v.amuletSaves == null ? 0 : v.amuletSaves.length;
        if (saves > 0) dealtText = (saves == 1 ? "Amulet saved you once" : "Amulet saved you " + saves + " times") + "  \u00B7  " + dealtText;
        return dealtText + String.format(Locale.ROOT, "taken %,d in %,d hits", total, n)
                + "  \u00B7  biggest " + v.hitDamage[biggest] + who;
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        return p;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        return l;
    }

    /** Fame gained in a visit: worked out once for a finished one, live for the one in progress. */
    private long fame(Visit v, boolean current) {
        if (current) return DungeonVisits.fameGained(v);
        return fame.computeIfAbsent(v.start, k -> DungeonVisits.fameGained(v));
    }

    private static java.time.LocalDate dayOf(Visit v) {
        return Instant.ofEpochMilli(v.start).atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /** A day's group in the list: its date over a gold line, and an empty grid for its boxes (returned). */
    private JPanel dayGrid(java.time.LocalDate day) {
        JLabel date = new JLabel(DAY.format(day));
        date.setFont(date.getFont().deriveFont(18f));
        date.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
        date.setBorder(new EmptyBorder(list.getComponentCount() == 0 ? 4 : 18, 4, 4, 0));
        list.add(left(date));
        JComponent line = new JComponent() {   // a gold line that stops short of both edges
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Section.color("LootGobbler.gold", Palette.GOLD));
                g.fillRect(4, 1, getWidth() - 8, 2);
            }
        };
        line.setPreferredSize(new Dimension(10, 4));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
        list.add(left(line));
        list.add(Box.createVerticalStrut(8));
        JPanel grid = new JPanel(new GridLayout(0, 1, GAP, GAP)) {
            @Override
            public Dimension getMaximumSize() {   // never stretched taller than its boxes
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        grid.setOpaque(false);
        list.add(left(grid));
        dayGrids.add(grid);
        return grid;
    }

    /** A box's time: how long the visit took (counting up, with NOW, for the one in progress). */
    private static String durationText(Visit v, boolean current) {
        return (current ? "NOW  ·  " : "") + DpsPanel.duration(v.duration());
    }

    private static String whenText(Visit v, boolean current) {
        return (current ? "NOW  ·  since " : "") + WHEN.format(Instant.ofEpochMilli(v.start).atZone(ZoneId.systemDefault()))
                + "  ·  " + DpsPanel.duration(v.duration());
    }

    private void tick() {   // the same line as the box was built with, so nothing comes and goes
        if (nowWhen != null && nowVisit != null) nowWhen.setText(durationText(nowVisit, true));
    }

    /** As many columns of boxes as fit the list's width. */
    private void updateColumns() {
        int width = listScroll.getViewport().getWidth();
        if (width > 0) {   // 0 while hidden; recomputed when shown
            int cols = Math.max(1, (width - 20 + GAP) / (TILE_MIN_WIDTH + GAP));
            for (JPanel grid : dayGrids) ((GridLayout) grid.getLayout()).setColumns(cols);
        }
        list.revalidate();
        list.repaint();
    }

    /**
     * One visit as a box: the portal on the left and the dungeon's name under it, your rank and how long at the top, the exaltation progress made in it (single steps in the upper row, stats that went up more than
     * once with "3x" in the lower one), the characters of the guildmates who were with you on the right (at most
     * four), and the dungeon's difficulty at the bottom right. A click shows it on the right (the box framed in
     * gold); the one in progress has a gold name.
     */
    private JPanel tile(Visit v, Run run, boolean current, boolean isSelected) {
        boolean notDone = !current && Boolean.FALSE.equals(DungeonVisits.completed(v));
        JPanel tile = new JPanel(new BorderLayout(10, 6)) {
            @Override
            protected void paintChildren(Graphics g) {
                super.paintChildren(g);
                if (!notDone) return;
                // "Not completed" over the middle of the box, on a dark band so it reads over the portal and potions
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont().deriveFont(Font.BOLD, 18f));
                FontMetrics fm = g2.getFontMetrics();
                String text = "NOT COMPLETED";
                int w = fm.stringWidth(text) + 24, h = fm.getHeight() + 6;
                int x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                g2.setColor(new Color(0, 0, 0, 150));
                g2.fillRect(x, y, w, h);
                g2.setColor(new Color(0xD8, 0x5A, 0x4A));
                g2.drawRect(x, y, w - 1, h - 1);
                g2.drawString(text, x + 12, y + 3 + fm.getAscent());
                g2.dispose();
            }

            @Override
            protected void paintComponent(Graphics g) {
                Color accent = Section.color("LootGobbler.gold", Palette.GOLD);
                Color header = Section.color("LootGobbler.header", Color.LIGHT_GRAY);
                g.setColor(isSelected ? Palette.mix(accent, header, 0.3) : header);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(isSelected ? accent : Color.BLACK);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                if (isSelected) g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
            }
        };
        tile.setOpaque(false);
        tile.setBorder(new EmptyBorder(8, 8, 8, 10));
        if (isSelected && scrollToSelected) {
            scrollToSelected = false;
            SwingUtilities.invokeLater(() -> tile.scrollRectToVisible(new Rectangle(0, 0, tile.getWidth(), tile.getHeight())));
        }

        JPanel top = new JPanel(new BorderLayout(10, 0));
        top.setOpaque(false);
        JLabel icon = new JLabel(icons.computeIfAbsent(v.dungeon,
                d -> ActiveDungeonBadge.fit(ProfileView.dungeonIcon(d, 64), ICON)));
        icon.setPreferredSize(new Dimension(ICON, ICON));
        icon.setVerticalAlignment(SwingConstants.TOP);
        top.add(icon, BorderLayout.WEST);

        JPanel middle = column();
        JPanel head = new JPanel(new BorderLayout(12, 0));   // the time gets "..." rather than a line of its own
        head.setOpaque(false);
        JLabel rank = run != null ? DpsPanel.runRank(run) : null;
        if (rank == null) rank = muted("no boss");
        rank.setFont(rank.getFont().deriveFont(16f));
        head.add(rank, BorderLayout.WEST);
        JLabel when = new JLabel(durationText(v, current));   // the day stands over the group
        if (current) nowWhen = when;
        when.setForeground(current ? Palette.GOLD : Palette.MUTED);
        head.add(when, BorderLayout.CENTER);
        head.setMaximumSize(new Dimension(Integer.MAX_VALUE, head.getPreferredSize().height));
        middle.add(left(head));
        middle.add(Box.createVerticalStrut(6));
        middle.add(left(potions(v)));
        top.add(middle, BorderLayout.CENTER);
        top.add(mates(v), BorderLayout.EAST);
        tile.add(top, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setOpaque(false);
        JLabel name = new JLabel(v.dungeon);
        name.setFont(name.getFont().deriveFont(20f));
        if (current) name.setForeground(Palette.GOLD);
        bottom.add(name, BorderLayout.CENTER);
        Double d = lootgobbler.DungeonDifficulty.of(v.dungeon);
        if (d != null) {
            ActiveDungeonBadge.DifficultyMark mark = new ActiveDungeonBadge.DifficultyMark();
            mark.set(d % 1 == 0 ? String.valueOf(d.intValue()) : String.valueOf(d), name.getFont().deriveFont(15f));
            mark.lineOf = name;   // standing on the name's line
            bottom.add(mark, BorderLayout.EAST);
        }
        tile.add(bottom, BorderLayout.SOUTH);

        clickable(tile, () -> {
            selected = v.start;
            refresh();
        });
        return tile;
    }

    /**
     * The exaltation progress made in a visit, per stat over all classes: the stats that went up once in the upper
     * row (at most four), the ones that went up more often below with "3x" in front (at most two).
     */
    private static JComponent potions(Visit v) {
        JPanel rows = column();
        if (v.exalts == null || v.exalts.isEmpty()) return rows;
        Map<Integer, Integer> perStat = new java.util.LinkedHashMap<>();
        for (Exalt e : merged(v)) perStat.merge(e.stat, Math.max(1, e.after - e.before), Integer::sum);
        JPanel singles = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)), multis = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        singles.setOpaque(false);
        multis.setOpaque(false);
        StringBuilder tip = new StringBuilder("<html>Exaltation progress here:");
        perStat.forEach((stat, count) -> {
            tip.append("<br>").append(EXALT_STATS[stat]).append(" +").append(count);
            JPanel row = count > 1 ? multis : singles;
            if (row.getComponentCount() >= (count > 1 ? MULTIS : SINGLES)) return;
            JLabel potion = new JLabel(count > 1 ? count + "x" : null, ClassesView.potion(EXALT_POTION[stat], POTION), SwingConstants.LEFT);
            potion.setHorizontalTextPosition(SwingConstants.LEFT);
            potion.setIconTextGap(3);
            if (row.getComponentCount() > 0) potion.setBorder(new EmptyBorder(0, count > 1 ? 12 : 6, 0, 0));   // both rows start flush
            row.add(potion);
        });
        if (singles.getComponentCount() > 0) rows.add(left(singles));
        if (singles.getComponentCount() > 0 && multis.getComponentCount() > 0) rows.add(Box.createVerticalStrut(4));
        if (multis.getComponentCount() > 0) rows.add(left(multis));
        rows.setToolTipText(tip + "</html>");
        return rows;
    }

    /** The characters of the guildmates who were with you, two by two (at most four; the tooltip names all). */
    private static JComponent mates(Visit v) {
        List<lootgobbler.DpsHistory.PlayerRow> mates = Guildmates.get().with(v);
        JPanel out = column();   // rows of two, each against the box's right edge (a lone one too)
        if (mates.isEmpty()) return out;
        StringBuilder tip = new StringBuilder("<html>With you:");
        JPanel row = null;
        for (int i = 0; i < mates.size(); i++) {
            lootgobbler.DpsHistory.PlayerRow p = mates.get(i);
            String cls = tomato.realmshark.enums.CharacterClass.getName(p.classType);
            tip.append("<br>").append(p.name).append(cls == null ? "" : " (" + cls + ")");
            if (i >= MATES) continue;
            if (i % 2 == 0) {
                if (row != null) out.add(Box.createVerticalStrut(2));
                row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
                row.setOpaque(false);
                row.setAlignmentX(RIGHT_ALIGNMENT);
                out.add(row);
            }
            JLabel sprite = new JLabel(Guildmates.get().look(p, MATE));   // their skin and dyes then
            if (i % 2 == 1) sprite.setBorder(new EmptyBorder(0, 2, 0, 0));
            row.add(sprite);
        }        out.setToolTipText(tip + "</html>");   // all of them, also the ones past the first four
        return out;
    }

    private static <T extends JComponent> T left(T c) {
        c.setAlignmentX(LEFT_ALIGNMENT);
        return c;
    }

    /** Clicks anywhere on c (also on its labels, which would otherwise take the click) run action. */
    private static void clickable(JComponent c, Runnable action) {
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(c, action);
        for (Component child : c.getComponents()) if (child instanceof JComponent j) clickable(j, action);
    }
}
