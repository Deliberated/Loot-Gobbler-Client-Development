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
    private static final int ICON = 34;
    // ExaltStore's stat order (dex, spd, vit, wis, def, att, mana, life): names, and the potion index ClassesView uses
    private static final String[] EXALT_STATS = {"DEX", "SPD", "VIT", "WIS", "DEF", "ATT", "MP", "HP"};
    private static final int[] EXALT_POTION = {5, 4, 6, 7, 3, 2, 1, 0};

    private final DpsPanel dps;
    private final CardLayout pages = new CardLayout();
    private final JPanel views = new JPanel(pages);
    private final Segmented picker;
    private final JPanel list = new JPanel();
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
        list.setBorder(new EmptyBorder(8, 8, 8, 8));
        JButton clear = new JButton("CLEAR ALL");
        clear.putClientProperty("FlatLaf.styleClass", "danger");
        clear.setFocusable(false);
        clear.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Remove all dungeon visits from the list?\nThe DPS run history stays.",
                    "Clear dungeons", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                DungeonVisits.get().clear();
            }
        });
        Section dungeons = new Section("Dungeons", Palette.BLUE, FitWidthPanel.scroll(list, 24)).actions(clear);
        detail.setLayout(new BoxLayout(detail, BoxLayout.Y_AXIS));
        detail.setOpaque(false);
        SideSplit split = new SideSplit(dungeons, FitWidthPanel.scroll(detail, 24), "dungeonDetail", 420, 300);
        views.setOpaque(false);
        views.add(split, PAGES[0]);
        views.add(dps, PAGES[1]);
        add(views, BorderLayout.CENTER);

        Runnable later = ViewRefresh.of(this, this::refresh);   // only while it can be seen, once per burst
        DungeonVisits.get().onChange(later);
        DpsHistory.get().onChange(later);
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
        if (now != null && !clock.isRunning()) clock.start();
        if (now == null) clock.stop();
        List<Visit> visits = DungeonVisits.get().visits();
        List<Run> runs = DpsHistory.get().runs();
        list.removeAll();
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
        if (now != null) {
            Run live = DpsHistory.get().live();
            boolean fought = live != null && !live.guildHall && live.dungeon.equals(now.dungeon) && live.mainBoss() != null;
            list.add(row(now, fought ? live : null, true, now.start == shownStart));
            list.add(Box.createVerticalStrut(4));
        }
        Run shownRun = null;
        for (Visit v : visits) {
            Run run = null;
            if (v.runStart != 0) for (Run r : runs) if (r.start == v.runStart) run = r;
            if (v == shown) shownRun = run;
            list.add(row(v, run, false, v == shown));
        }
        list.revalidate();
        list.repaint();
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
            for (Exalt e : v.exalts) exalts.add(exaltRow(e));
        }
        detail.add(CharactersView.box("Exaltations", Palette.PURPLE, exalts));
        detail.revalidate();
        detail.repaint();
    }

    /**
     * One reported exaltation progress: the class, the stat's potion, the progress before and after, and in gold if
     * it completed an exaltation (with the stat's count of 5).
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
        JLabel what = new JLabel((n > 0 ? "Exaltation! " + stat + " " + ExaltStore.level(e.after) + " / " + ExaltStore.MAX_PER_STAT
                : stat + " progress") + "   (" + e.before + " \u2192 " + e.after + ")",
                ClassesView.potion(EXALT_POTION[e.stat], 20), SwingConstants.LEFT);
        what.setIconTextGap(8);
        if (n > 0) what.setForeground(Section.color("LootGobbler.gold", Palette.GOLD));
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

    private static String whenText(Visit v, boolean current) {
        return (current ? "NOW  ·  since " : "") + WHEN.format(Instant.ofEpochMilli(v.start).atZone(ZoneId.systemDefault()))
                + "  ·  " + DpsPanel.duration(v.duration());
    }

    private void tick() {
        if (nowWhen != null && nowVisit != null) nowWhen.setText(whenText(nowVisit, true));
    }

    /**
     * One visit: portal, dungeon, when and how long, your rank if you fought a boss and a potion if you made
     * exaltation progress. A click shows it on the right (framed in gold); the one in progress has a gold name.
     */
    private JPanel row(Visit v, Run run, boolean current, boolean isSelected) {
        JPanel row = DpsPanel.row(true);
        if (isSelected && scrollToSelected) {
            scrollToSelected = false;
            SwingUtilities.invokeLater(() -> row.scrollRectToVisible(new Rectangle(0, 0, row.getWidth(), row.getHeight())));
        }
        if (isSelected) {
            row.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Palette.GOLD, 2), new EmptyBorder(4, 8, 4, 6)));
        }
        JLabel icon = new JLabel(icons.computeIfAbsent(v.dungeon, d -> ProfileView.dungeonIcon(d, ICON)));
        icon.setPreferredSize(new Dimension(ICON, ICON));
        row.add(icon, BorderLayout.WEST);

        JLabel name = new JLabel(v.dungeon);
        name.setFont(name.getFont().deriveFont(18f));
        if (current) name.setForeground(Palette.GOLD);
        long gained = fame(v, current);
        JLabel when = new JLabel(whenText(v, current) + (gained > 0 ? "  \u00B7  +" + String.format(Locale.ROOT, "%,d", gained) + " fame" : ""));
        if (current) nowWhen = when;
        when.setForeground(current ? Palette.GOLD : Palette.MUTED);
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(name);
        text.add(when);
        row.add(text, BorderLayout.CENTER);

        JLabel right = run != null ? DpsPanel.runRank(run) : null;
        if (right == null) {
            right = new JLabel("no boss fought");
            right.setForeground(Palette.MUTED);
        }
        right.setBorder(new EmptyBorder(0, 0, 0, 6));
        if (v.exalts != null && !v.exalts.isEmpty()) {   // exaltation progress made here
            right.setIcon(ClassesView.potion(EXALT_POTION[v.exalts.get(0).stat], 20));
            right.setHorizontalTextPosition(SwingConstants.LEFT);
            right.setIconTextGap(10);
        }
        row.add(right, BorderLayout.EAST);

        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Clicks.onClick(row, () -> {
            selected = v.start;
            refresh();
        });
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }
}
