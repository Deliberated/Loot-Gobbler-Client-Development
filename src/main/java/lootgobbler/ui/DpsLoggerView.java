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
    private DamageGraph graph;   // of the visit shown on the right
    private JLabel takenStats;
    private Visit graphVisit;
    private long selected = -1;   // start of the visit shown on the right; -1 = the newest

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

        DungeonVisits.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        DpsHistory.get().onChange(() -> SwingUtilities.invokeLater(this::refresh));
        // new hits in the dungeon you are in: only its graph (if shown) follows, not the whole list
        DungeonVisits.get().onHits(() -> SwingUtilities.invokeLater(() -> {
            if (graph != null && graphVisit == DungeonVisits.get().current()) {
                takenStats.setText(takenText(graphVisit));
                graph.repaint();
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
        showDetail(shown, shownRun, shown != null && shown == now);
    }

    /** The column on the right: the visit, its boss fights and the exaltation progress made in it. */
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
        taken.add(Box.createVerticalStrut(6));
        graph = new DamageGraph(v);
        graphVisit = v;
        graph.setAlignmentX(LEFT_ALIGNMENT);
        taken.add(graph);
        detail.add(CharactersView.box("Damage Taken", Palette.RED, taken));

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

    /** "4,320 damage in 152 hits · 3 hits over 30% of your life · biggest 410 (Septavius)". */
    private static String takenText(Visit v) {
        int n = v.hits();
        if (n == 0) return "No damage taken.";
        long total = 0;
        int big = 0, biggest = 0;
        for (int i = 0; i < n; i++) {
            total += v.hitDamage[i];
            if (v.maxHp > 0 && v.hitDamage[i] >= v.maxHp * DamageGraph.BIG_HIT) big++;
            if (v.hitDamage[i] > v.hitDamage[biggest]) biggest = i;
        }
        String who = v.hitEnemy[biggest] > 0 ? " (" + assets.IdToAsset.objectName(v.hitEnemy[biggest]) + ")" : "";
        return String.format(Locale.ROOT, "%,d damage in %,d hits", total, n)
                + (v.maxHp > 0 ? "  \u00B7  " + big + (big == 1 ? " hit" : " hits") + " over 30% of your life" : "")
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
        if (isSelected) {
            row.setBorder(BorderFactory.createCompoundBorder(new LineBorder(Palette.GOLD, 2), new EmptyBorder(4, 8, 4, 6)));
        }
        JLabel icon = new JLabel(icons.computeIfAbsent(v.dungeon, d -> ProfileView.dungeonIcon(d, ICON)));
        icon.setPreferredSize(new Dimension(ICON, ICON));
        row.add(icon, BorderLayout.WEST);

        JLabel name = new JLabel(v.dungeon);
        name.setFont(name.getFont().deriveFont(18f));
        if (current) name.setForeground(Palette.GOLD);
        JLabel when = new JLabel(whenText(v, current));
        if (current) nowWhen = when;
        when.setForeground(current ? Palette.GOLD : Palette.MUTED);
        JPanel text = new JPanel(new GridLayout(2, 1));
        text.setOpaque(false);
        text.add(name);
        text.add(when);
        row.add(text, BorderLayout.CENTER);

        JLabel right = run != null ? DpsPanel.runRank(run) : null;
        if (right == null) {
            right = new JLabel(run != null ? "" : "no boss fought");
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
