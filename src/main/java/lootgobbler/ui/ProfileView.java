package lootgobbler.ui;

import lootgobbler.CharacterStore;
import lootgobbler.CharacterStore.Char;
import lootgobbler.CharacterStore.Sample;
import lootgobbler.CollectionStore;
import lootgobbler.DpsHistory;
import lootgobbler.DungeonVisits;
import lootgobbler.ExaltStore;
import lootgobbler.GobblerServer;
import lootgobbler.GobblerServer.CaravanMember;
import lootgobbler.GobblerServer.QuestMap;
import lootgobbler.ItemDb;
import lootgobbler.PetStore;
import lootgobbler.ServerSync;
import lootgobbler.Themes;
import tomato.realmshark.enums.CharacterClass;
import tomato.realmshark.enums.LootBags;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Profile tab. Overview: your player name and account summary, your recently most played character with its fame
 * graph, your guild (your caravan wagon and the next quest map dungeon, both opening the Guild tab), the last 24
 * hours, recent drops and runs, top dungeons, exaltation and (later, from the server) daily quests. Plus the
 * Dungeon Stats page passed in.
 */
public class ProfileView extends SubTabs {
    private static final NumberFormat NUM = NumberFormat.getIntegerInstance();
    private static final long DAY_MS = 24 * 3_600_000L;
    private static final int RECENT = 5;
    private static final Font HEAD = new Font(Themes.HEAD_FONT, Font.PLAIN, 30);
    private static final Font BIG = new Font(Themes.HEAD_FONT, Font.PLAIN, 42);

    private record DropRow(int item, String bag, String where, long time) {
    }

    private final JPanel body = new JPanel();
    private String shown;   // what is on screen, so the frequent store updates only rebuild on a real change

    private final IntConsumer openCharacter;   // shows a character in the Characters tab
    private final Consumer<String> openGuild;  // shows a page of the Guild tab
    private final Consumer<DpsHistory.Run> openRun;   // shows a run in the Battles tab
    private final IntConsumer openClass;   // shows a class (-1: just the page) on the Classes page

    // the guild row, from the server
    private final GobblerServer server = GobblerServer.get();
    private final CaravanView.Scene wagon;
    private String guildName, guildNote = "";   // guildNote: why there is nothing to show; "" while loading
    private CaravanMember myWagon;
    private QuestMap questMap;
    private List<GobblerServer.MemberFacts> guildFacts = List.of();   // numbers about every guildmate, from the server
    private GobblerServer.Friends guildFriends;                        // the two who ran the most dungeons together
    private String loverKey = "-";
    private int guildVersion;
    private boolean guildLoading, guildAgain, guildStale;

    public ProfileView(JComponent dungeonStats, IntConsumer openCharacter, Consumer<String> openGuild,
                       Consumer<DpsHistory.Run> openRun, IntConsumer openClass) {
        this.openCharacter = openCharacter;
        this.openClass = openClass;
        this.openGuild = openGuild;
        this.openRun = openRun;
        wagon = new CaravanView.Scene(m -> openGuild.accept(GuildPanel.CARAVAN)).small(0.5);
        wagon.setToolTipText("To the caravan");
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(new EmptyBorder(16, 20, 20, 20));
        JPanel holder = new JPanel(new BorderLayout());   // keeps the content at its natural height
        holder.setOpaque(false);
        holder.add(body, BorderLayout.NORTH);
        page("Overview", FitWidthPanel.scroll(holder, 24));
        page("Dungeon Stats", dungeonStats);
        done();

        Runnable later = ViewRefresh.of(this, this::refresh);   // only while it can be seen, once per burst
        CharacterStore.get().onChange(later);
        DpsHistory.get().onChange(later);
        CollectionStore.get().onChange(later);
        ExaltStore.get().onChange(later);
        PetStore.get().onChange(later);
        lootgobbler.AmuletSaves.get().onChange(later);
        DungeonStatsPanel.onUpdate(later);
        new javax.swing.Timer(60_000, e -> later.run()).start();   // "x min ago" and the 24 hour window move on by themselves

        server.onChange(() -> SwingUtilities.invokeLater(this::loadGuild));
        Guildmates.get().onChange(() -> {   // the fame graph shows who joined you
            guildVersion++;
            refresh();
        });
        ServerSync.get().onUploaded(kind -> {   // your run or drops reached the server
            if (kind.equals("dps_history") || kind.equals("collection")) SwingUtilities.invokeLater(() -> {
                if (isShowing()) loadGuild();
                else guildStale = true;
            });
        });
        new javax.swing.Timer(QuestMapView.POLL_MS, e -> {   // guildies' clears
            if (isShowing()) loadGuild();
        }).start();
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && guildStale) loadGuild();
        });
        refresh();
        loadGuild();
    }

    /**
     * Your wagon of this week's caravan and the guild's current quest map.
     */
    private void loadGuild() {
        if (guildLoading) {
            guildAgain = true;
            return;
        }
        guildStale = false;
        GobblerServer.User me = server.user();
        if (me == null || server.state() != GobblerServer.State.ONLINE) {
            setGuild(null, null, null, "Log in to a server in the Server tab to see your guild here.");
            return;
        }
        guildLoading = true;
        // facts about the guildmates: optional (older servers do not know them)
        java.util.concurrent.CompletableFuture<GobblerServer.Facts> facts = server.guildFacts(FACT_DAYS).exceptionally(x -> null);
        server.caravan("week").thenCombine(server.questMaps(1), (c, q) -> {
            CaravanMember mine = c.members.stream().filter(m -> m.id == me.id).findFirst().orElseGet(() -> {
                CaravanMember m = new CaravanMember();   // nothing found this week yet
                m.id = me.id;
                m.ign = me.ign;
                return m;
            });
            return new Object[]{c.guild, mine, q.current, null};
        }).thenCombine(facts, (r, f) -> {
            r[3] = f;
            return r;
        }).whenComplete((r, e) -> SwingUtilities.invokeLater(() -> {
            guildLoading = false;
            GobblerServer.Facts got = e == null ? (GobblerServer.Facts) r[3] : null;
            guildFacts = got == null || got.members == null ? List.of() : got.members;
            guildFriends = got == null ? null : got.friends;
            if (e != null) setGuild(null, null, null, GuildPanel.loadError(e));
            else setGuild((String) r[0], (CaravanMember) r[1], (QuestMap) r[2], null);
            if (guildAgain) {
                guildAgain = false;
                loadGuild();
            }
        }));
    }

    private void setGuild(String name, CaravanMember mine, QuestMap map, String note) {
        String before = guildName + "|" + guildNote + "|" + key(myWagon) + "|" + key(questMap) + "|" + loverKey;
        loverKey = factsKey(guildFacts) + (guildFriends == null ? "" : guildFriends.a + "+" + guildFriends.b + "=" + guildFriends.count);
        guildName = name;
        myWagon = mine;
        questMap = map;
        guildNote = note;
        if (before.equals(guildName + "|" + guildNote + "|" + key(myWagon) + "|" + key(questMap) + "|" + loverKey)) return;
        guildVersion++;
        refresh();
    }

    private static String key(CaravanMember m) {
        return m == null ? "-" : m.id + ":" + m.total + ":" + m.lastDrop + ":" + (m.character == null ? 0 : m.character.classType);
    }

    private static String key(QuestMap m) {
        return m == null ? "-" : m.id + ":" + m.clears.size() + ":" + m.completedAt;
    }

    @Override
    public void updateUI() {
        super.updateUI();
        shown = null;   // theme colors are read while building: rebuild with the new theme
        if (body != null) SwingUtilities.invokeLater(this::refresh);
    }

    private void refresh() {
        long now = System.currentTimeMillis(), since = now - DAY_MS;
        CharacterStore store = CharacterStore.get();
        String name = store.playerName();
        List<Char> chars = store.all();
        Char most = store.mostPlayed();
        List<DpsHistory.Run> runs = DpsHistory.get().runs();
        List<DropRow> allDrops = allDrops();
        List<DropRow> drops = new ArrayList<>(allDrops.subList(0, Math.min(RECENT, allDrops.size())));
        List<Map.Entry<String, Integer>> dungeons = DungeonStatsPanel.topDungeons(RECENT);
        int pets = PetStore.get().all().size();

        List<Object> parts = new ArrayList<>(List.of(name == null ? "" : name, pets, drops, dungeons, now / 60_000, guildVersion));
        for (Char c : chars) parts.add(List.of(c.charId, c.alive, c.fame, c.level, c.skin, c.samples.size(), c.lastPlayed));
        for (int i = 0; i < Math.min(RECENT, runs.size()); i++) parts.add(runs.get(i).start);
        for (CharacterClass cls : CharacterClass.CHAR_CLASS_LIST)
            parts.add(java.util.Arrays.toString(ExaltStore.get().progress(cls.getId())));   // every point, for the exaltations
        parts.add(amuletSaves());
        parts.add(lootgobbler.AmuletSaves.get().wornRecently());
        String state = parts.hashCode() + "";
        if (state.equals(shown)) return;
        shown = state;

        body.removeAll();
        body.add(row(2, playerCard(name, store, most, chars, since), guildCard()));
        body.add(Box.createVerticalStrut(20));
        body.add(left(heading("Last 24 hours")));
        body.add(Box.createVerticalStrut(8));
        body.add(todayTiles(store, chars, runs, allDrops, since));
        body.add(Box.createVerticalStrut(20));
        body.add(row(2, recentDrops(drops), recentRuns(runs, allDrops)));
        body.add(Box.createVerticalStrut(20));
        body.add(row(3, topDungeons(dungeons), exaltation(), dailyQuests()));
        body.revalidate();
        body.repaint();
    }

    // ---- cards -----------------------------------------------------------------------------------------------

    /**
     * You: a greeting with your name, your most played character of the last 24 hours (click: it in the Characters
     * tab), the maybe-showables that fit you, and the fame all your characters gained over the last 24 hours as one
     * graph, with every dungeon on it and the guildmates who joined you above each; each character under the graph
     * opens in the Characters tab.
     */
    private JComponent playerCard(String name, CharacterStore store, Char most, List<Char> chars, long since) {
        JPanel p = column();
        // a greeting (in the soft ink) before the name (in gold)
        JLabel player = new JLabel("<html><span style='color:" + hex(soft("").getForeground()) + "'>" + GREETING
                + "</span> " + (name != null ? escape(name) : "stranger") + "</html>");
        player.setForeground(gold());
        p.add(left(title(player, p)));
        if (name == null) p.add(left(muted("Start the sniffer and enter the game once to show your name.")));
        p.add(Box.createVerticalStrut(14));

        // the character, the exaltation progress of the classes that got some lately beside it, and at the very right
        // the maybe-showables that fit you
        JPanel extras = column();
        for (MaybeShowable m : maybeShowables) {   // only those whose condition fits you
            if (!m.when().getAsBoolean()) continue;
            if (extras.getComponentCount() > 0) extras.add(Box.createVerticalStrut(14));
            JComponent c = m.show().get();
            c.setAlignmentX(RIGHT_ALIGNMENT);
            extras.add(c);
        }
        JPanel top = new JPanel(new BorderLayout(32, 0));
        top.setOpaque(false);
        JPanel mostPlayed = column();
        mostPlayed.add(left(caption("MOST PLAYED CHARACTER")));
        mostPlayed.add(Box.createVerticalStrut(6));
        if (most == null) {
            mostPlayed.add(left(muted("Log in once so your character list is loaded.")));
        } else {
            mostPlayed.add(left(characterBlock(store, most)));
            JComponent exalts = recentExalts(most);   // a maybe-showable: only with exaltation progress lately
            if (exalts != null) top.add(exalts, BorderLayout.CENTER);
        }
        top.add(mostPlayed, BorderLayout.WEST);
        if (extras.getComponentCount() > 0) top.add(extras, BorderLayout.EAST);
        top.setMaximumSize(new Dimension(Integer.MAX_VALUE, top.getPreferredSize().height));
        p.add(left(top));

        if (most != null) {
            p.add(Box.createVerticalStrut(16));
            p.add(left(fameGain(store, most, chars, since)));
        }
        return card(p);
    }

    /** The character's skin, class, stats maxed and fame (level below 20); click: it in the Characters tab. */
    private JComponent characterBlock(CharacterStore store, Char c) {
        JLabel sprite = new JLabel(CharactersView.skin(c, 96));
        sprite.setVerticalAlignment(SwingConstants.TOP);
        JPanel about = column();
        JLabel cls = new JLabel(c.className + "   " + c.statsMaxed + "/8");
        cls.setFont(cls.getFont().deriveFont(30f));
        about.add(left(cls));
        JLabel progress = new JLabel(c.level < 20 ? "Level " + c.level : NUM.format(c.fame) + " Fame");
        progress.setFont(progress.getFont().deriveFont(22f));
        progress.setForeground(gold());
        about.add(left(progress));
        if (store.gainedToday(c) == 0) about.add(left(muted(c.lastPlayed == 0 ? "Not played yet" : "Last played " + CharactersView.ago(c.lastPlayed))));
        JPanel block = new JPanel(new BorderLayout(18, 0));
        block.setOpaque(false);
        block.add(sprite, BorderLayout.WEST);
        block.add(about, BorderLayout.CENTER);
        block.setMaximumSize(new Dimension(Integer.MAX_VALUE, block.getPreferredSize().height));
        block.setToolTipText("Open in Characters");
        int id = c.charId;
        clickable(block, () -> openCharacter.accept(id));
        return block;
    }

    private static final long EXALT_DAYS = 7;   // as far back as the exaltation changes are kept
    private static final int EXALT_CLASSES = 8, EXALT_SKIN = 32, EXALT_POTION = 20;

    /**
     * A maybe-showable beside the most played character, only there when a class got exaltation progress lately: one
     * row per such class, its sprite and, beside it, the progress points each stat got in the last days, like "2x"
     * and the stat's potion (progress past the last exaltation at 75 does not count). The most played character's
     * class comes first, then the most progress. Null when no class got any.
     */
    private JComponent recentExalts(Char most) {
        long since = System.currentTimeMillis() - EXALT_DAYS * DAY_MS;
        ExaltStore exalts = ExaltStore.get();
        int cap = ExaltStore.THRESHOLDS[ExaltStore.THRESHOLDS.length - 1];
        // per class with any: the progress points each stat got since then
        java.util.Map<Integer, int[]> gained = new java.util.HashMap<>();
        for (CharacterClass cls : CharacterClass.CHAR_CLASS_LIST) {
            int id = cls.getId();
            int[] now = exalts.progress(id);
            int[] got = new int[8];
            int sum = 0;
            for (int stat = 0; now != null && stat < Math.min(8, now.length); stat++) {
                got[stat] = Math.max(0, Math.min(now[stat], cap) - Math.min(exalts.progressAt(id, stat, since), cap));
                sum += got[stat];
            }
            if (sum > 0) gained.put(id, got);
        }
        if (gained.isEmpty()) return null;
        List<Integer> classes = gained.keySet().stream().sorted(Comparator
                .comparing((Integer id) -> id != most.classType)
                .thenComparingInt(id -> -java.util.Arrays.stream(gained.get(id)).sum())).limit(EXALT_CLASSES).toList();

        JPanel p = column();
        JLabel head = caption("EXALTATIONS (" + EXALT_DAYS + " DAYS)");
        head.setToolTipText("Open the Classes page");
        clickable(head, () -> openClass.accept(-1));
        p.add(left(head));
        p.add(Box.createVerticalStrut(4));
        int[] order = {ExaltStore.LIFE, ExaltStore.MANA, ExaltStore.ATT, ExaltStore.DEF, ExaltStore.SPD,
                ExaltStore.DEX, ExaltStore.VIT, ExaltStore.WIS};
        for (int id : classes) {
            int[] got = gained.get(id);
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
            row.setOpaque(false);
            JLabel sprite = new JLabel(CharactersView.sprite(id, EXALT_SKIN));
            sprite.setBorder(new EmptyBorder(0, 0, 0, 10));
            row.add(sprite);
            StringBuilder tip = new StringBuilder("<html>" + CharacterClass.getName(id) + "  ·  exaltation progress in the last "
                    + EXALT_DAYS + " days:");
            int shown = 0;
            for (int stat : order) {
                if (got[stat] <= 0) continue;
                JLabel potion = new JLabel(got[stat] + "x", ClassesView.potion(DpsLoggerView.EXALT_POTION[stat], EXALT_POTION),
                        SwingConstants.LEFT);
                potion.setHorizontalTextPosition(SwingConstants.LEFT);
                potion.setIconTextGap(3);
                if (shown > 0) potion.setBorder(new EmptyBorder(0, 12, 0, 0));
                row.add(potion);
                tip.append("<br>").append(DpsLoggerView.EXALT_STATS[stat]).append(" +").append(got[stat]);
                shown++;
            }
            row.setToolTipText(tip + "<br><i>click to open it in Classes</i></html>");
            clickable(row, () -> openClass.accept(id));
            p.add(left(row));
        }
        return p;
    }

    /**
     * The fame all your characters gained in the last 24 hours (always, also +0) and in the last hour (only when there
     * is some), the graph of it, and which characters it is made of (each opens in the Characters tab).
     */
    private JComponent fameGain(CharacterStore store, Char most, List<Char> chars, long since) {
        List<Char> gainers = chars.stream().filter(o -> store.gainedToday(o) > 0)
                .sorted(Comparator.comparingLong((Char o) -> -store.gainedToday(o))).toList();
        long gain = gainers.stream().mapToLong(store::gainedToday).sum();
        JPanel p = column();
        // at the right, a bit in from the edge: the fame gained in the last 24h and, below in gold, in the last hour
        JPanel head = column();
        head.setBorder(new EmptyBorder(0, 0, 0, 24));
        JLabel gained = new JLabel("+" + NUM.format(gain) + " fame");
        gained.setFont(gained.getFont().deriveFont(20f));
        gained.setForeground(gain > 0 ? Palette.GREEN : Palette.MUTED);
        gained.setToolTipText("Fame gained by all your characters in the last 24 hours");
        head.add(right(gained));
        long hour = gainers.stream().flatMap(o -> store.sessions(o, System.currentTimeMillis() - 3_600_000L).stream())
                .mapToLong(CharacterStore.Session::gained).sum();
        if (hour > 0) {
            JLabel lastHour = new JLabel("+" + NUM.format(hour) + " last hour");
            lastHour.setForeground(gold());
            lastHour.setToolTipText("Fame gained in the last hour");
            head.add(right(lastHour));
        }
        JPanel atRight = new JPanel(new BorderLayout());
        atRight.setOpaque(false);
        atRight.add(head, BorderLayout.EAST);
        atRight.setMaximumSize(new Dimension(Integer.MAX_VALUE, atRight.getPreferredSize().height));
        p.add(left(atRight));

        List<Sample> total = total(gainers.isEmpty() ? List.of(most) : gainers, since);
        if (total.size() > 1) {
            p.add(Box.createVerticalStrut(6));
            p.add(left(new FameGraph(List.of(new FameGraph.Series(total, gold(), 3f)))));
        }
        if (!gainers.isEmpty()) {   // which characters the total is made of
            JPanel legend = new JPanel(new WrapLayout(0, 2));
            legend.setOpaque(false);
            for (int i = 0; i < gainers.size(); i++) {
                Char o = gainers.get(i);
                JLabel l = legendLabel((i == 0 ? "" : "     ") + o.className + " +" + NUM.format(store.gainedToday(o)),
                        i == 0 ? gold() : Palette.MUTED);
                l.setToolTipText("Open this " + o.className + " in Characters");
                int id = o.charId;
                clickable(l, () -> openCharacter.accept(id));
                legend.add(l);
            }
            p.add(left(legend));
        }
        return p;
    }

    /** Maybe-showables: parts of the player card shown only to players they fit (when), in this order. */
    private record MaybeShowable(java.util.function.BooleanSupplier when, java.util.function.Supplier<JComponent> show) {
    }

    private final List<MaybeShowable> maybeShowables = List.of(
            // the Amulet of Restoration: worn by a character played lately, or it saved you in the last 3 days
            new MaybeShowable(() -> lootgobbler.AmuletSaves.get().wornRecently() || amuletSaves() > 0, this::amuletBadge));

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final int TITLE_SIZE = 56, TITLE_MIN = 28;

    /**
     * A card's big title (the greeting, the guild): one line, in the head font at 56, smaller (down to 28) when the
     * card is too narrow for it, so it is never cut or wrapped. Follows the width of its column.
     */
    private static JLabel title(JLabel l, JComponent column) {
        l.putClientProperty(LabelFit.SKIP, Boolean.TRUE);   // sized here
        l.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, TITLE_SIZE));
        Runnable fit = () -> {
            int room = column.getWidth() - column.getInsets().left - column.getInsets().right;
            if (room <= 0) return;
            JLabel probe = new JLabel(l.getText());   // its width at the full size
            probe.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, TITLE_SIZE));
            int natural = probe.getPreferredSize().width;
            int size = natural <= room ? TITLE_SIZE : Math.max(TITLE_MIN, (int) Math.floor(TITLE_SIZE * (double) room / natural));
            if (l.getFont().getSize() != size) {
                l.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, size));
                l.revalidate();
            }
        };
        column.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                fit.run();
            }
        });
        return l;
    }

    // above the guild facts, one picked each time the app starts
    private static final String STATS_CAPTION = pick("GUILD STATS OF THE PAST 3 DAYS", "THE GUILD'S LAST 3 DAYS",
            "LAST 3 DAYS IN THE GUILD");

    /**
     * Your wagon of this week's caravan (cut down to the caravan, without the empty sky above it) and the quest map's
     * next dungeon, side by side: their titles on one line, their links ("To the ...") on another. Each opens its page
     * in the Guild tab.
     */
    private JComponent wagonAndQuests() {
        wagon.setMembers(List.of(myWagon));
        JPanel scene = new JPanel(null) {   // shows the wagon from just above its pile down
            @Override
            public Dimension getPreferredSize() {
                Dimension d = wagon.getPreferredSize();
                return new Dimension(d.width, d.height - Math.max(0, wagon.contentTop() - WAGON_SKY));
            }

            @Override
            public void doLayout() {
                Dimension d = wagon.getPreferredSize();
                wagon.setBounds(0, getHeight() - d.height, d.width, d.height);
            }
        };
        scene.setOpaque(false);
        scene.add(wagon);

        JComponent questBody;
        if (questMap == null || questMap.dungeons.isEmpty()) {
            questBody = muted("No quest map yet");
        } else {
            int n = questMap.dungeons.size(), done = Math.min(questMap.clears.size(), n);
            boolean complete = questMap.completedAt != null || done == n;
            String next = questMap.dungeons.get(complete ? n - 1 : done);
            JPanel q = column();
            q.add(center(new JLabel(dungeonIcon(next, 80))));
            q.add(Box.createVerticalStrut(6));
            q.add(center(new JLabel(complete ? "All done!" : "Next: " + next)));
            JLabel progress = new JLabel(done + "/" + n + " dungeons completed");
            progress.setFont(progress.getFont().deriveFont(20f));
            progress.setForeground(complete ? Palette.GREEN : gold());
            q.add(center(progress));
            questBody = q;
        }

        JPanel grid = new JPanel(new GridBagLayout());
        grid.setOpaque(false);
        JComponent[][] cells = {
                {caption("YOUR WAGON"), scene, link("To the caravan")},
                {caption("QUEST MAP"), questBody, link("To the quest map")}};
        Runnable[] open = {() -> openGuild.accept(GuildPanel.CARAVAN), () -> openGuild.accept(GuildPanel.QUEST_MAP)};
        for (int col = 0; col < cells.length; col++) {
            for (int row = 0; row < 3; row++) {
                GridBagConstraints c = new GridBagConstraints();
                c.gridx = col;
                c.gridy = row;
                c.anchor = row == 1 ? GridBagConstraints.CENTER : GridBagConstraints.PAGE_START;   // titles and links in line
                c.insets = new Insets(row == 0 ? 0 : 6, col == 0 ? 0 : 48, 0, 0);
                grid.add(cells[col][row], c);
                clickable(cells[col][row], open[col]);
            }
        }
        JPanel holder = new JPanel(new BorderLayout());   // at the left, as wide as it needs
        holder.setOpaque(false);
        holder.add(grid, BorderLayout.WEST);
        return holder;
    }

    private static final int WAGON_SKY = 10;   // sky kept above the wagon's pile

    // one of these greets you on the overview, picked once each time the app starts
    private static final String[] GREETINGS = {"Hello", "Hi", "Hey", "Howdy", "Aye", "Ahoy", "Yo", "Greetings",
            "Welcome back", "Good to see you", "Hail", "Salutations", "Well met", "Heyo", "Oi", "Ello",
            "Look who's back", "Ready to gobble"};
    private static final String GREETING = GREETINGS[new java.util.Random().nextInt(GREETINGS.length)] + ",";

    // and one of these comes before your guild's name, also picked once each start
    private static final String[] GUILD_PREFIXES = {"Your guild", "The insane players of", "The place of devastation:",
            "The worst guild:", "Proud member of", "Home sweet", "The legends of", "Behold,", "The mighty",
            "The loot goblins of", "Somehow still in", "The finest of", "Sworn to", "Carried by", "The unstoppable"};
    private static final String GUILD_PREFIX = GUILD_PREFIXES[new java.util.Random().nextInt(GUILD_PREFIXES.length)];

    private static String hex(Color c) {
        return String.format("#%06x", c.getRGB() & 0xFFFFFF);
    }

    private static final int AMULET = 23624;  // Amulet of Restoration (5337 is its shiny version)
    private static final long AMULET_DAYS = 3;

    private static int amuletSaves() {
        return lootgobbler.AmuletSaves.get().countSince(System.currentTimeMillis() - AMULET_DAYS * 24 * 3600_000L);
    }

    /** The Amulet of Restoration and how often it saved you lately, a maybe-showable of the player card. */
    private JComponent amuletBadge() {
        int n = amuletSaves();
        JLabel icon = new JLabel(CharactersView.sprite(AMULET, 56));
        icon.setAlignmentX(CENTER_ALIGNMENT);
        JLabel text = new JLabel("<html><div style='text-align:center'>saved you <b>" + n + "</b> time" + (n == 1 ? "" : "s")
                + "<br>in the last " + AMULET_DAYS + " days</div></html>", SwingConstants.CENTER);
        text.setForeground(Section.color("LootGobbler.inkSoft", Palette.MUTED));
        text.setAlignmentX(CENTER_ALIGNMENT);
        JPanel col = new JPanel();   // the amulet above, the text centered below it
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setToolTipText("Your Amulet of Restoration kept you alive " + n + " time" + (n == 1 ? "" : "s")
                + " in the last " + AMULET_DAYS + " days");
        col.add(icon);
        col.add(Box.createVerticalStrut(4));
        col.add(text);
        col.setMaximumSize(col.getPreferredSize());
        return col;
    }

    private static List<Sample> window(Char c, long since) {
        return c.samples.stream().filter(s -> s.t >= since).toList();
    }

    /**
     * The fame all the given characters gained together since then, as one series: at every sample of any of
     * them, the sum of what each had gained by that time (counted from its first sample in the window).
     */
    private static List<Sample> total(List<Char> chars, long since) {
        List<List<Sample>> windows = chars.stream().map(o -> window(o, since)).filter(w -> !w.isEmpty()).toList();
        List<long[]> events = new ArrayList<>();   // {time, character, fame}
        for (int k = 0; k < windows.size(); k++) {
            for (Sample s : windows.get(k)) events.add(new long[]{s.t, k, s.fame});
        }
        events.sort(Comparator.comparingLong(e -> e[0]));
        long[] gained = new long[windows.size()];
        List<Sample> out = new ArrayList<>();
        for (long[] e : events) {
            int k = (int) e[1];
            gained[k] = Math.max(0, e[2] - windows.get(k).get(0).fame);
            Sample s = new Sample();
            s.t = e[0];
            s.fame = Arrays.stream(gained).sum();
            if (!out.isEmpty() && out.get(out.size() - 1).t == s.t) out.set(out.size() - 1, s);
            else out.add(s);
        }
        return out;
    }

    private static JLabel legendLabel(String text, Color color) {
        JLabel l = new JLabel(text);
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setForeground(color);
        return l;
    }

    /**
     * Your guild at a glance: your wagon of this week's loot caravan, and the next dungeon of the quest map with how
     * far the guild got. Each opens its page in the Guild tab.
     */
    private JComponent guildCard() {
        JPanel p = column();
        // like the greeting: a prefix (in the soft ink) before the guild's name (in gold)
        JLabel head = new JLabel(guildName == null ? "Guild" : "<html><span style='color:" + hex(soft("").getForeground())
                + "'>" + GUILD_PREFIX + "</span> " + escape(guildName) + "</html>");
        head.setForeground(gold());
        p.add(left(title(head, p)));
        p.add(Box.createVerticalStrut(14));
        if (guildNote != null || myWagon == null) {
            p.add(left(muted(guildNote == null || guildNote.isEmpty() ? "Loading..." : guildNote)));
            return card(p);
        }
        // the facts of the last days first, then your wagon and the quest map side by side
        JComponent facts = guildFacts();
        if (facts != null) {
            p.add(left(caption(STATS_CAPTION)));
            p.add(Box.createVerticalStrut(4));
            p.add(left(goldLine()));
            p.add(Box.createVerticalStrut(8));
            p.add(left(facts));
            p.add(Box.createVerticalStrut(14));
        }
        p.add(left(caption("GUILD PROGRESS")));
        p.add(Box.createVerticalStrut(4));
        p.add(left(goldLine()));
        p.add(Box.createVerticalStrut(8));
        p.add(left(wagonAndQuests()));
        return card(p);
    }

    private static final int FACT_DAYS = 3;   // the facts besides the favourite dungeon cover these last days
    private static final int GRAVESTONE = 1282, FAME = 24102, SWORD = 2827, HEAVY_ARMOR = 2812;   // their icons
    private static final int FACT_ICON = 72, LOVER_PORTAL = 80, LOVER_CHAR = 52;

    // how some facts are told, one picked each time the app starts
    private static final String LOVER_VERB = pick("completed", "done", "cleared", "conquered", "beaten", "run",
            "gobbled through", "survived");
    private static final String TANK_VERB = pick("ate", "tanked", "took", "absorbed", "soaked up", "shrugged off");

    private static String pick(String... words) {
        return words[java.util.concurrent.ThreadLocalRandom.current().nextInt(words.length)];   // safe in any static field
    }

    private static String factsKey(List<GobblerServer.MemberFacts> facts) {
        StringBuilder sb = new StringBuilder();
        for (GobblerServer.MemberFacts f : facts) {
            sb.append(f.userId).append(':').append(f.deaths).append(':').append(f.fame).append(':').append(f.dealt)
                    .append(':').append(f.taken).append(':').append(f.hits).append(':').append(f.inMs).append(':').append(f.exalts)
                    .append(':').append(f.loot).append(':').append(f.played).append(':').append(f.stSets);
            if (f.look != null) sb.append(':').append(f.look.classType).append(':').append(f.look.skin);
            if (f.favorite != null) sb.append(':').append(f.favorite.dungeon).append(':').append(f.favorite.count)
                    .append(':').append(f.favorite.classType).append(':').append(f.favorite.skin);
            sb.append('|');
        }
        return sb.toString();
    }

    /** The guildmate with the most of something (ties: the first, by name as the server sends them); null if none has any. */
    private static GobblerServer.MemberFacts most(List<GobblerServer.MemberFacts> all, java.util.function.ToLongFunction<GobblerServer.MemberFacts> value) {
        GobblerServer.MemberFacts best = null;
        for (GobblerServer.MemberFacts f : all) if (value.applyAsLong(f) > 0 && (best == null || value.applyAsLong(f) > value.applyAsLong(best))) best = f;
        return best;
    }

    /**
     * Who of the guild stands out over the last 3 days, each as a caption with an icon and a line below: Dungeon Lover
     * (the most completed runs of one dungeon), Death Lover (most characters died), Fame Producer (most fame gained),
     * Punisher (the highest average of their best boss DPS per run, as the DPS logger shows it), Panzer (most damage
     * taken a minute), Exalter! (most exaltations earned), Just Dodge (the fewest hits for the time in dungeons, against
     * the guild's baseline), Looter (most items found), Diversifier (most characters that gained fame) and ST Collector
     * (most ST sets completed). The averages only count guildmates with at least 10 minutes in dungeons. Only those
     * someone has; null if none.
     */
    private JComponent guildFacts() {
        List<GobblerServer.MemberFacts> all = guildFacts;
        List<JComponent> blocks = new ArrayList<>();
        GobblerServer.MemberFacts lover = most(all, f -> f.favorite == null ? 0 : f.favorite.count);
        if (lover != null) blocks.add(dungeonLover(lover));
        GobblerServer.MemberFacts deaths = most(all, f -> f.deaths);
        if (deaths != null) blocks.add(fact("DEATH LOVER", CharactersView.sprite(GRAVESTONE, FACT_ICON),
                name(deaths) + " died most times: <b>" + NUM.format(deaths.deaths) + "x</b>",
                deaths.ign + ": " + deaths.deaths + " characters died in the last " + FACT_DAYS + " days"));
        GobblerServer.MemberFacts fame = most(all, f -> f.fame);
        if (fame != null) blocks.add(fact("FAME PRODUCER", CharactersView.sprite(FAME, FACT_ICON),
                "<b>" + NUM.format(fame.fame) + " fame</b> gained by " + name(fame),
                "Fame gained by all of " + fame.ign + "'s characters in the last " + FACT_DAYS + " days"));
        // each run's best boss DPS (what the DPS logger shows), on average over their runs
        GobblerServer.MemberFacts dealt = null;
        for (GobblerServer.MemberFacts f : all) if (f.runs > 0 && (dealt == null || f.peakDps > dealt.peakDps)) dealt = f;
        if (dealt != null) blocks.add(fact("PUNISHER", CharactersView.sprite(SWORD, FACT_ICON),
                name(dealt) + " has an average of <b>" + NUM.format(Math.round(dealt.peakDps)) + " DPS</b>",
                String.format(java.util.Locale.ROOT, "%s: their best boss DPS, on average over %d run%s in the last %d days",
                        dealt.ign, dealt.runs, dealt.runs == 1 ? "" : "s", FACT_DAYS)));
        java.util.function.ToDoubleFunction<GobblerServer.MemberFacts> perMinute = f -> f.taken / (f.inMs / 60_000.0);
        GobblerServer.MemberFacts taken = highest(all, perMinute);
        if (taken != null && taken.taken > 0) blocks.add(fact("PANZER", CharactersView.sprite(HEAVY_ARMOR, FACT_ICON),
                name(taken) + " " + TANK_VERB + " <b>" + NUM.format(Math.round(perMinute.applyAsDouble(taken))) + " HP</b> a minute",
                String.format(java.util.Locale.ROOT, "%s: %,d HP taken in %s in dungeons in the last %d days",
                        taken.ign, taken.taken, duration(taken.inMs), FACT_DAYS)));
        GobblerServer.MemberFacts exalter = most(all, f -> f.exalts);
        if (exalter != null) blocks.add(fact("EXALTER!", new ExaltIcon(FACT_ICON),
                "<b>" + NUM.format(exalter.exalts) + "</b> exalt" + (exalter.exalts == 1 ? "" : "s") + " done by " + name(exalter),
                "Exaltations " + exalter.ign + " earned in the last " + FACT_DAYS + " days"));
        double baseline = Guildmates.get().settings().baselineHpm;
        java.util.function.ToDoubleFunction<GobblerServer.MemberFacts> dodged = f -> {   // in %, as Battles counts hits
            double hpm = f.hits / (f.inMs / 60_000.0);
            return 100 * baseline / (hpm + baseline);
        };
        GobblerServer.MemberFacts dodger = null;
        for (GobblerServer.MemberFacts f : all) {
            if (f.inMs < DODGE_MIN_MS || baseline <= 0) continue;
            if (dodger == null || dodged.applyAsDouble(f) > dodged.applyAsDouble(dodger)) dodger = f;
        }
        if (dodger != null) blocks.add(fact("JUST DODGE", new DodgeIcon(lookOf(dodger, FACT_ICON), FACT_ICON),
                name(dodger) + " just dodged <b>" + String.format(java.util.Locale.ROOT, "%.0f", dodged.applyAsDouble(dodger)) + " %</b> of bullets",
                String.format(java.util.Locale.ROOT, "%s: %,d hits in %s in dungeons in the last %d days (%.1f a minute; the guild counts %.0f as average)",
                        dodger.ign, dodger.hits, duration(dodger.inMs), FACT_DAYS, dodger.hits / (dodger.inMs / 60_000.0), baseline)));
        GobblerServer.MemberFacts looter = most(all, f -> f.loot);
        if (looter != null) blocks.add(fact("LOOTER", divineShiny(FACT_ICON),
                name(looter) + " gathered the most items: <b>" + NUM.format(looter.loot) + "</b>",
                "Items " + looter.ign + " found in the last " + FACT_DAYS + " days"));
        GobblerServer.MemberFacts diverse = most(all, f -> f.played);
        if (diverse != null) {
            Icon look = lookOf(diverse, FACT_ICON);
            blocks.add(fact("DIVERSIFIER", look != null ? look : CharactersView.sprite(CharacterClass.CHAR_CLASS_LIST[0].getId(), FACT_ICON),
                    "Most different characters actively played by " + name(diverse) + ": <b>" + NUM.format(diverse.played) + "</b>",
                    "Characters of " + diverse.ign + " that gained fame in the last " + FACT_DAYS + " days"));
        }
        GobblerServer.MemberFacts collector = most(all, f -> f.stSets);
        if (collector != null) blocks.add(fact("ST COLLECTOR", stSetIcon(collector, FACT_ICON),
                name(collector) + " has <b>" + NUM.format(collector.stSets) + "</b> ST set" + (collector.stSets == 1 ? "" : "s") + " ready",
                "ST sets " + collector.ign + " completed in the last " + FACT_DAYS + " days (the last piece found)"));
        GobblerServer.MemberFacts friendA = byId(all, guildFriends == null ? 0 : guildFriends.a);
        GobblerServer.MemberFacts friendB = byId(all, guildFriends == null ? 0 : guildFriends.b);
        if (friendA != null && friendB != null) blocks.add(fact("BEST FRIENDS",
                new FacingIcon(lookOf(friendA, FACT_ICON * 3 / 4), lookOf(friendB, FACT_ICON * 3 / 4)),
                name(friendA) + " and " + name(friendB) + " played the most dungeons together: <b>" + NUM.format(guildFriends.count) + "</b>",
                friendA.ign + " and " + friendB.ign + " were in " + guildFriends.count + " dungeon"
                        + (guildFriends.count == 1 ? "" : "s") + " together in the last " + FACT_DAYS + " days"));
        if (blocks.isEmpty()) return null;
        // five of them, picked anew each day and each time the app starts (the same in between, so the card does
        // not change with every refresh), those not shown the last time first
        List<String> order = factOrder();
        blocks.sort(java.util.Comparator.comparingInt(b -> {
            int i = order.indexOf(String.valueOf(b.getClientProperty(FACT_NAME)));
            return i < 0 ? Integer.MAX_VALUE : i;
        }));
        if (blocks.size() > FACTS_SHOWN) blocks = new ArrayList<>(blocks.subList(0, FACTS_SHOWN));
        rememberShown(blocks);
        JPanel grid = new JPanel(new WrapLayout(36, 18));   // side by side, wrapping when the card is narrow
        grid.setOpaque(false);
        // the icons all as tall as the tallest of those shown, standing on one line, so the texts of a row line up
        int tallest = 0;
        for (JComponent b : blocks) {
            Object pic = b.getClientProperty(FACT_PICTURE);
            if (pic instanceof JLabel l && l.getIcon() != null) tallest = Math.max(tallest, l.getIcon().getIconHeight());
        }
        for (JComponent b : blocks) {
            if (b.getClientProperty(FACT_PICTURE) instanceof JLabel l) {
                l.setPreferredSize(new Dimension(l.getPreferredSize().width, tallest));
                l.setMaximumSize(l.getPreferredSize());
            }
            grid.add(b);
        }
        grid.addComponentListener(new java.awt.event.ComponentAdapter() {
            private int height = -1;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {   // a new width may wrap into more rows
                int h = grid.getPreferredSize().height;
                if (h != height) {
                    height = h;
                    grid.revalidate();
                }
            }
        });
        return grid;
    }

    private static final int FACTS_SHOWN = 5;
    private static final String FACTS_SHOWN_KEY = "guildFactsShown";   // the captions shown the last time, | between
    private static final List<String> FACT_NAMES = List.of("DUNGEON LOVER", "DEATH LOVER", "FAME PRODUCER", "PUNISHER",
            "PANZER", "EXALTER!", "JUST DODGE", "LOOTER", "DIVERSIFIER", "ST COLLECTOR", "BEST FRIENDS");
    private static List<String> factOrder;      // this run's order of the facts (made once a start and a day)
    private static long factOrderDay = Long.MIN_VALUE;
    private static String factsSaved;           // what was saved last, so an unchanged pick is not written again

    /**
     * The order the facts are picked in: shuffled, those shown the last time (in an earlier start, or yesterday) at the
     * end, so each start tries to show different ones. Made once a start and a day, so refreshes keep the same ones.
     */
    private static synchronized List<String> factOrder() {
        long today = java.time.LocalDate.now().toEpochDay();
        if (factOrder != null && factOrderDay == today) return factOrder;
        java.util.Set<String> last = new java.util.HashSet<>();
        String saved = util.PropertiesManager.getProperty(FACTS_SHOWN_KEY);
        if (saved != null) last.addAll(java.util.Arrays.asList(saved.split("\\|")));
        List<String> order = new ArrayList<>(FACT_NAMES);
        java.util.Collections.shuffle(order);
        order.sort(java.util.Comparator.comparing(last::contains));   // stable: the new ones first, still shuffled
        factOrder = order;
        factOrderDay = today;
        return order;
    }

    /** Saves which facts are shown, for the next start to show others. */
    private static synchronized void rememberShown(List<JComponent> shown) {
        StringBuilder sb = new StringBuilder();
        for (JComponent b : shown) sb.append(sb.length() == 0 ? "" : "|").append(b.getClientProperty(FACT_NAME));
        String s = sb.toString();
        if (s.equals(factsSaved)) return;
        factsSaved = s;
        util.PropertiesManager.setProperties(FACTS_SHOWN_KEY, s);
    }

    private static final long DODGE_MIN_MS = 10 * 60_000L;   // less time in dungeons says too little about an average

    /** The guildmate with the highest average (ties: the first); only those with at least 10 minutes in dungeons. */
    private static GobblerServer.MemberFacts highest(List<GobblerServer.MemberFacts> all,
                                                     java.util.function.ToDoubleFunction<GobblerServer.MemberFacts> value) {
        GobblerServer.MemberFacts best = null;
        for (GobblerServer.MemberFacts f : all) {
            if (f.inMs < DODGE_MIN_MS) continue;
            if (best == null || value.applyAsDouble(f) > value.applyAsDouble(best)) best = f;
        }
        return best;
    }

    /** The guildmate's character as they played it last; their class sprite, or null, when only that is known. */
    private static Icon lookOf(GobblerServer.MemberFacts m, int size) {
        if (m.look == null || m.look.classType <= 0) return null;
        DpsHistory.PlayerRow p = new DpsHistory.PlayerRow();
        p.name = m.ign;
        p.classType = m.look.classType;
        p.skin = m.look.skin;
        p.tex1 = m.look.tex1;
        p.tex2 = m.look.tex2;
        Icon look = Guildmates.get().look(p, size);
        return look != null ? look : CharactersView.sprite(m.look.classType, size);
    }

    /**
     * The four pieces of an ST set in a square: a set of the class the guildmate played last if there is one (else the
     * first set), as the ST Sets page knows them. Just the gold frame while the sets are not loaded yet.
     */
    private static Icon stSetIcon(GobblerServer.MemberFacts m, int size) {
        List<lootgobbler.StSets.Set> sets = lootgobbler.StSets.ifLoaded();
        lootgobbler.StSets.Set set = null;
        if (sets != null && !sets.isEmpty()) {
            String cls = m.look == null ? null : CharacterClass.getName(m.look.classType);
            set = sets.stream().filter(s -> s.className() != null && s.className().equals(cls)).findFirst().orElse(sets.get(0));
        }
        int half = size / 2;
        Icon[] pieces = new Icon[4];
        for (int i = 0; set != null && i < Math.min(4, set.pieces().length); i++) pieces[i] = CharactersView.sprite(set.pieces()[i], half);
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                for (int i = 0; i < 4; i++) if (pieces[i] != null) pieces[i].paintIcon(c, g, x + (i % 2) * half, y + (i / 2) * half);
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    private static final int SHINY_SWORD = 1230;   // Sword of the Colossus (Shiny)

    private static GobblerServer.MemberFacts byId(List<GobblerServer.MemberFacts> all, long id) {
        for (GobblerServer.MemberFacts f : all) if (f.userId == id && id != 0) return f;
        return null;
    }

    /** Two characters facing each other: the first as it is (facing right), the second mirrored (facing left). */
    static final class FacingIcon implements Icon {
        private final Icon left, right;

        FacingIcon(Icon left, Icon right) {
            this.left = left;
            this.right = right;
        }

        private int part(Icon i) {
            return i == null ? FACT_ICON * 3 / 4 : i.getIconWidth();
        }

        @Override
        public int getIconWidth() {
            return part(left) + part(right) - FACT_ICON / 8;   // close together
        }

        @Override
        public int getIconHeight() {
            return Math.max(left == null ? 0 : left.getIconHeight(), right == null ? 0 : right.getIconHeight());
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            int h = getIconHeight();
            if (left != null) left.paintIcon(c, g, x, y + h - left.getIconHeight());
            if (right != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                int rx = x + getIconWidth() - right.getIconWidth();
                g2.translate(rx + right.getIconWidth(), y + h - right.getIconHeight());
                g2.scale(-1, 1);   // mirrored: facing the first
                right.paintIcon(c, g2, 0, 0);
                g2.dispose();
            }
        }
    }

    /** A shiny item with the divine rarity marker, as the collection shows a divine copy. */
    private static Icon divineShiny(int size) {
        Icon sprite = CharactersView.sprite(SHINY_SWORD, size);
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                if (sprite != null) sprite.paintIcon(c, g, x, y);
                EnchantBadge.draw((Graphics2D) g, x + size - 1, y + size - 1, 4, 5);
            }

            @Override
            public int getIconWidth() {
                return size;
            }

            @Override
            public int getIconHeight() {
                return size;
            }
        };
    }

    /** Exaltation: a Potion of Life with golden sparkles and an arrow up. */
    static final class ExaltIcon implements Icon {
        private final int size;
        private final Icon potion;

        ExaltIcon(int size) {
            this.size = size;
            this.potion = ClassesView.potion(DpsLoggerView.EXALT_POTION[ExaltStore.LIFE], size * 3 / 4);
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (potion != null) potion.paintIcon(c, g, x, y + size - potion.getIconHeight());
            int px = Math.max(2, size / 14);
            Color gold = Section.color("LootGobbler.gold", Palette.GOLD);
            // the arrow up, at the top right
            String[] arrow = {"..X..", ".XXX.", "XXXXX", ".XXX.", ".XXX."};
            pixels(g, arrow, x + size - 5 * px, y, px, gold);
            // sparkles
            String[] spark = {".X.", "XXX", ".X."};
            pixels(g, spark, x + px, y + px, px, gold);
            pixels(g, spark, x + size - 4 * px, y + size / 2 + px, px, new Color(0xFFF2B0));
        }
    }

    /** A character curving around a bullet: the bullet flying past, and a dotted arc of the path around it. */
    static final class DodgeIcon implements Icon {
        private final Icon character;
        private final int size;

        DodgeIcon(Icon character, int size) {
            this.character = character;
            this.size = size;
        }

        private static final int BULLET = 15;   // the game's Fire Bolt

        @Override
        public int getIconWidth() {
            return size * 2;
        }

        @Override
        public int getIconHeight() {
            return size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getIconWidth(), h = size, px = Math.max(2, size / 14);
            int charW = character == null ? 0 : character.getIconWidth();
            // the path the character took: from the lower left, up over the bullet and down to where it stands now
            g2.setColor(new Color(0xF2E6C8));
            g2.setStroke(new BasicStroke(px * 0.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{px * 1.2f, px}, 0));
            java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
            path.moveTo(x + px, y + h - px * 2);
            path.curveTo(x + w * 0.15, y + px, x + w * 0.45, y + px, x + w - charW * 0.9, y + h * 0.45);
            g2.draw(path);
            g2.dispose();
            // the bullet, flying up right under the arc, where the character was
            Icon bullet = CharactersView.sprite(BULLET, size * 3 / 4);
            if (bullet != null) bullet.paintIcon(c, g, x + w * 3 / 10 - bullet.getIconWidth() / 3, y + h - bullet.getIconHeight());
            if (character != null) character.paintIcon(c, g, x + w - charW, y + h - character.getIconHeight());
        }
    }

    /** Pixel art from rows of "X" (filled) and "." (empty), each pixel px square, at x, y. */
    private static void pixels(Graphics g, String[] rows, int x, int y, int px, Color color) {
        g.setColor(color);
        for (int r = 0; r < rows.length; r++)
            for (int k = 0; k < rows[r].length(); k++)
                if (rows[r].charAt(k) == 'X') g.fillRect(x + k * px, y + r * px, px, px);
    }

    private static String name(GobblerServer.MemberFacts f) {
        return "<span style='color:" + hex(gold()) + "'>" + escape(f.ign) + "</span>";
    }

    private static final String FACT_PICTURE = "fact.picture";   // a fact's icon label, on the fact
    private static final String FACT_NAME = "fact.name";         // a fact's caption, on the fact

    /** A thin gold line across the column, stopping short of both edges, as under the day headers in Battles. */
    private static JComponent goldLine() {
        JComponent line = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(gold());
                g.fillRect(0, 1, getWidth() - 4, 2);
            }
        };
        line.setPreferredSize(new Dimension(10, 4));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
        return line;
    }

    private static final int FACT_WIDTH = 130;   // every fact as wide, so they line up in columns

    /** One fact: its caption, the icon below it and the line (HTML) below that, wrapped to the fact's width, all centered. */
    private static JComponent fact(String caption, Icon icon, String html, String tip) {
        JLabel text = new JLabel("<html><div style='width:" + FACT_WIDTH + "px; text-align:center'>" + html + "</div></html>");
        text.setFont(text.getFont().deriveFont(20f));
        text.setToolTipText(tip);
        int width = text.getPreferredSize().width;   // the caption and the icon centered over the text's width
        JPanel p = column();
        JLabel title = caption(caption);
        JPanel titleBox = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));   // the caption centered over the text
        titleBox.setOpaque(false);
        titleBox.add(title);
        Dimension t = title.getPreferredSize();
        titleBox.setPreferredSize(new Dimension(Math.max(width, t.width), t.height));
        titleBox.setMaximumSize(titleBox.getPreferredSize());
        p.add(left(titleBox));
        p.add(Box.createVerticalStrut(2));
        JLabel picture = new JLabel(icon);
        picture.setToolTipText(tip);
        // standing on its bottom: guildFacts makes the icons shown together equally tall
        picture.setVerticalAlignment(SwingConstants.BOTTOM);
        picture.setHorizontalAlignment(SwingConstants.CENTER);
        picture.setPreferredSize(new Dimension(Math.max(width, icon == null ? 1 : icon.getIconWidth()),
                icon == null ? 1 : icon.getIconHeight()));
        picture.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        p.putClientProperty(FACT_PICTURE, picture);
        p.putClientProperty(FACT_NAME, caption);
        p.add(left(picture));
        p.add(Box.createVerticalStrut(6));
        p.add(left(text));
        return p;
    }

    /**
     * The guildmate who completed runs of one dungeon the most: its portal with their character (as in the latest of
     * those runs) in front of it, heart-eyed, and how often.
     */
    private JComponent dungeonLover(GobblerServer.MemberFacts m) {
        GobblerServer.Favorite f = m.favorite;
        Icon character;
        if (f.classType > 0) {
            DpsHistory.PlayerRow look = new DpsHistory.PlayerRow();
            look.name = m.ign;
            look.classType = f.classType;
            look.skin = f.skin;
            look.tex1 = f.tex1;
            look.tex2 = f.tex2;
            character = Guildmates.get().look(look, LOVER_CHAR);
            if (character == null) character = CharactersView.sprite(f.classType, LOVER_CHAR);
        } else {
            character = lookOf(m, LOVER_CHAR);   // visits from before looks were kept: as they played last
        }
        String dungeon = f.count == 1 ? (f.dungeon.startsWith("The ") ? f.dungeon.substring(4) : f.dungeon) : plural(f.dungeon);
        return fact("DUNGEON LOVER", new LoverIcon(dungeonIcon(f.dungeon, LOVER_PORTAL), character),
                name(m) + " has " + LOVER_VERB + " <b>" + NUM.format(f.count) + "</b> " + escape(dungeon),
                m.ign + ": " + NUM.format(f.count) + "x " + f.dungeon + " completed in the last " + FACT_DAYS + " days");
    }

    /** A dungeon's name for many of them: "The Nest" -> "Nests", "Tomb of the Ancients" -> "Tombs of the Ancients". */
    static String plural(String dungeon) {
        String name = dungeon.startsWith("The ") ? dungeon.substring(4) : dungeon;
        if (name.matches(".* I{1,3}")) return name;   // White Snake Invasion II: as it is
        int of = name.indexOf(" of ");
        if (of < 0) of = name.indexOf(" for ");   // Battle for the Nexus -> Battles for the Nexus
        String head = of > 0 ? name.substring(0, of) : name, tail = of > 0 ? name.substring(of) : "";
        String lower = head.toLowerCase(java.util.Locale.ROOT);
        // already plural (Lost Halls, Deadwater Docks, Trials of Cronus) or a name in s (Untaris)
        if (lower.endsWith("s") && !lower.endsWith("ss")) return name;
        if (lower.endsWith("y") && !lower.matches(".*[aeiou]y")) head = head.substring(0, head.length() - 1) + "ies";
        else if (lower.matches(".*(s|x|z|ch|sh)")) head += "es";
        else head += "s";
        return head + tail;
    }

    /** A dungeon's portal with a character standing in front of it, at its lower right, with hearts for eyes. */
    static final class LoverIcon implements Icon {
        static int HEART_X1 = 38, HEART_X2 = 62, HEART_Y = 28;   // where the eyes are, in % of the character
        private final Icon portal, character;

        LoverIcon(Icon portal, Icon character) {
            this.portal = portal;
            this.character = character;
        }

        @Override
        public int getIconWidth() {
            return LOVER_PORTAL + LOVER_CHAR / 2;
        }

        @Override
        public int getIconHeight() {
            return LOVER_PORTAL + LOVER_CHAR / 6;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (portal != null) portal.paintIcon(c, g, x, y);
            if (character == null) return;
            int cx = x + getIconWidth() - character.getIconWidth(), cy = y + getIconHeight() - character.getIconHeight();
            character.paintIcon(c, g, cx, cy);
            // the eyes of a class sprite (8 x 8, facing right) sit in its upper middle: a heart on each
            int px = Math.max(1, character.getIconWidth() / 16);
            int ey = cy + character.getIconHeight() * HEART_Y / 100;
            heart(g, cx + character.getIconWidth() * HEART_X1 / 100, ey, px);
            heart(g, cx + character.getIconWidth() * HEART_X2 / 100, ey, px);
        }

        /** A pixel heart, 5 x 4 pixels of size px, centered on (x, y), with a dark rim so it reads on any skin. */
        private static void heart(Graphics g, int x, int y, int px) {
            String[] rows = {".X.X.", "XXXXX", ".XXX.", "..X.."};
            int left = x - 5 * px / 2, top = y - 2 * px;
            g.setColor(new Color(0x3A0A12));
            for (int r = 0; r < rows.length; r++)
                for (int k = 0; k < 5; k++)
                    if (rows[r].charAt(k) == 'X') g.fillRect(left + k * px - 1, top + r * px - 1, px + 2, px + 2);
            g.setColor(new Color(0xFF3B6B));
            for (int r = 0; r < rows.length; r++)
                for (int k = 0; k < 5; k++)
                    if (rows[r].charAt(k) == 'X') g.fillRect(left + k * px, top + r * px, px, px);
        }
    }

    private static JLabel link(String text) {
        JLabel l = new JLabel(text + "  ›");
        l.setForeground(gold());
        return l;
    }

    /** Clicks anywhere on it (also on its labels, which would otherwise take the click) run action. */
    private static void clickable(JComponent c, Runnable action) {
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (!(c instanceof CaravanView.Scene)) Clicks.onClick(c, action);   // the wagon handles its own clicks
        for (Component child : c.getComponents()) {
            if (child instanceof JComponent j) clickable(j, action);
        }
    }

    private static <T extends JComponent> T center(T c) {
        c.setAlignmentX(CENTER_ALIGNMENT);
        return c;
    }

    // portals that look small beside the others (low, or mostly smoke), drawn this much bigger everywhere
    private static final java.util.Set<String> SMALL_PORTALS = java.util.Set.of("Plagued Nest", "Fungal Cavern",
            "Kogbold Steamworks", "Advanced Kogbold Steamworks", "Hidden Interregnum", "Stromwell's Rift II");
    private static final double SMALL_PORTAL_SCALE = 1.35;

    /** A dungeon's portal sprite, by its name as the Run History records it. */
    static Icon dungeonIcon(String name, int size) {
        int id = tomato.realmshark.ParseDungeon.getPortalId(name);
        if (id == -1) {
            tomato.realmshark.enums.CharacterStatistics cs = tomato.realmshark.enums.CharacterStatistics.statByName(name);
            if (cs != null) id = cs.getSpriteId();
        }
        Icon icon = CharactersView.sprite(id, size);
        return icon != null && SMALL_PORTALS.contains(name) ? new Enlarged(icon, SMALL_PORTAL_SCALE, false) : icon;
    }

    /**
     * An icon drawn bigger than the room it takes (crisp), growing from its centre, or from its bottom edge so it
     * still stands on the same line; layouts keep the size they had. ActiveDungeonBadge.fit keeps it enlarged.
     */
    static final class Enlarged implements Icon {
        final Icon inner;
        final double scale;
        final boolean bottom;

        Enlarged(Icon inner, double scale, boolean bottom) {
            this.inner = inner;
            this.scale = scale;
            this.bottom = bottom;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double ax = x + getIconWidth() / 2.0, ay = y + (bottom ? getIconHeight() : getIconHeight() / 2.0);
            g2.translate(ax, ay);
            g2.scale(scale, scale);
            g2.translate(-ax, -ay);
            inner.paintIcon(c, g2, x, y);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return inner.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return inner.getIconHeight();
        }
    }

    private JComponent todayTiles(CharacterStore store, List<Char> chars, List<DpsHistory.Run> runs,
                                  List<DropRow> drops, long since) {
        long playMs = 0;
        int sessions = 0;
        for (Char c : chars) {
            for (CharacterStore.Session s : store.sessions(c, since)) {
                playMs += s.durationMs();
                sessions++;
            }
        }
        List<DpsHistory.Run> today = runs.stream().filter(r -> r.start >= since && !r.guildHall).toList();
        long kinds = today.stream().map(r -> r.dungeon).distinct().count();

        // a fifth tile for amulet saves, only while an amulet is worn or saved you recently
        boolean amulet = lootgobbler.AmuletSaves.get().relevant();
        JPanel tiles = new JPanel(new GridLayout(1, amulet ? 5 : 4, 12, 0));   // fame gained is on the most played card
        tiles.setOpaque(false);
        tiles.add(tile("DUNGEONS", NUM.format(today.size()), kinds == 0 ? "none yet" : kinds + " different"));
        tiles.add(bagTile("WHITE BAGS", "White", drops, since));
        tiles.add(bagTile("ORANGE BAGS", "Orange", drops, since));
        tiles.add(tile("PLAYTIME", duration(playMs),
                sessions == 0 ? "not played" : sessions + (sessions == 1 ? " session" : " sessions")));
        if (amulet) {
            int all = lootgobbler.AmuletSaves.get().count(), todayN = lootgobbler.AmuletSaves.get().countSince(since);
            tiles.add(tile("AMULET SAVES", NUM.format(all), todayN == 0 ? "none today" : todayN + " today"));
        }
        return left(tiles);
    }

    /**
     * Bags of one color dropped in the last 24 hours (items found in the same second and place count as one
     * bag), and the dungeon most of them came from.
     */
    private JComponent bagTile(String label, String color, List<DropRow> all, long since) {
        Map<String, String> bags = new LinkedHashMap<>();   // bag key -> where
        for (DropRow d : all) {
            if (d.time() >= since && color.equals(baseBag(d.bag()))) bags.put(d.time() / 1000 + "|" + d.where(), d.where());
        }
        Map<String, Long> byPlace = new HashMap<>();
        for (String w : bags.values()) byPlace.merge(w, 1L, Long::sum);
        String best = byPlace.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return tile(label, NUM.format(bags.size()), best == null ? "none yet" : "most in " + best);
    }

    private JComponent recentDrops(List<DropRow> drops) {
        JPanel p = column();
        p.add(left(heading("Recent drops")));
        p.add(Box.createVerticalStrut(6));
        if (drops.isEmpty()) p.add(left(muted("Drops of collectible items show up here.")));
        for (DropRow d : drops) {
            JLabel icon = new JLabel(CharactersView.sprite(d.item(), 40));
            icon.setPreferredSize(new Dimension(44, 44));
            JPanel text = column();
            text.add(left(new JLabel(itemName(d.item()))));
            JLabel where = muted((d.bag().isEmpty() ? "Drop" : d.bag() + " bag") + (d.where().isEmpty() ? "" : "  ·  " + d.where()));
            where.setIcon(bagIcon(d.bag(), 18));
            text.add(left(where));
            JLabel ago = soft(CharactersView.ago(d.time()));
            JPanel line = new JPanel(new BorderLayout(12, 0));
            line.setOpaque(false);
            line.setBorder(new EmptyBorder(6, 0, 6, 0));
            line.add(icon, BorderLayout.WEST);
            line.add(text, BorderLayout.CENTER);
            line.add(ago, BorderLayout.EAST);
            p.add(left(ItemLinks.link(line, d.item())));   // click: the item in Inventory
        }
        return card(p);
    }

    /**
     * The last runs: clear time, your best boss DPS, your damage rank on the main boss, and the bags that dropped
     * while you were in there.
     */
    private JComponent recentRuns(List<DpsHistory.Run> runs, List<DropRow> drops) {
        JPanel p = column();
        p.add(left(heading("Recent runs")));
        p.add(Box.createVerticalStrut(6));
        if (runs.isEmpty()) {
            p.add(left(muted("Dungeons you clear show up here.")));
            return card(p);
        }
        JPanel table = new JPanel(new GridBagLayout());
        table.setOpaque(false);
        GridBagConstraints g = new GridBagConstraints();
        g.anchor = GridBagConstraints.WEST;
        g.insets = new Insets(4, 0, 4, 14);
        String[] head = {"DUNGEON", "TIME", "BOSS DPS", "RANK", "LOOT"};
        for (int i = 0; i < head.length; i++) {
            g.gridx = i;
            g.weightx = i == 0 ? 1 : 0;
            g.fill = i == 0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
            table.add(caption(head[i]), g);
        }
        for (int r = 0; r < Math.min(RECENT, runs.size()); r++) {
            DpsHistory.Run run = runs.get(r);
            g.gridy = r + 1;
            g.gridx = 0;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            JLabel dungeon = new JLabel(run.dungeon, DpsPanel.dungeonIcon(run), SwingConstants.LEFT);
            dungeon.setIconTextGap(8);
            dungeon.setToolTipText("Open this run in Battles");
            dungeon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Clicks.onClick(dungeon, () -> openRun.accept(run));
            table.add(dungeon, g);
            g.weightx = 0;
            g.fill = GridBagConstraints.NONE;
            g.gridx = 1;
            table.add(soft(clock(run.durationMs)), g);
            g.gridx = 2;
            table.add(new JLabel(shortDps(run.myPeakDps())), g);
            g.gridx = 3;
            table.add(rank(run), g);
            g.gridx = 4;
            table.add(runLoot(run, drops), g);
        }
        p.add(left(table));
        return card(p);
    }

    private static JLabel rank(DpsHistory.Run run) {
        if (run.guildHall) return soft(run.bosses.size() + (run.bosses.size() == 1 ? " loadout" : " loadouts"));
        DpsHistory.Boss boss = run.mainBoss();
        DpsHistory.PlayerRow me = boss == null ? null : boss.me();
        if (me == null) return muted("-");
        int rank = boss.rankOf(me);
        JLabel l = new JLabel("#" + rank + " of " + boss.players.size());
        l.setForeground(rank == 1 ? Palette.GREEN : rank <= 3 ? gold() : soft("").getForeground());
        return l;
    }

    private static JComponent runLoot(DpsHistory.Run run, List<DropRow> drops) {
        JPanel bags = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        bags.setOpaque(false);
        long end = run.start + run.durationMs + 5_000;   // the run is saved a moment after the last bag dropped
        Set<String> seen = new HashSet<>();
        for (DropRow d : drops) {
            if (d.time() < run.start || d.time() > end) continue;
            if (seen.add(d.time() / 1000 + "|" + d.bag())) {
                Icon icon = bagIcon(d.bag(), 22);
                if (icon != null) bags.add(new JLabel(icon));
            }
        }
        if (bags.getComponentCount() == 0) bags.add(muted("-"));
        return bags;
    }

    private JComponent topDungeons(List<Map.Entry<String, Integer>> dungeons) {
        JPanel p = column();
        p.add(left(heading("Top dungeons")));
        p.add(Box.createVerticalStrut(6));
        if (dungeons.isEmpty()) p.add(left(muted("Run some dungeons to see them here.")));
        int max = dungeons.isEmpty() ? 1 : Math.max(1, dungeons.get(0).getValue());
        for (Map.Entry<String, Integer> d : dungeons) {
            JPanel line = new JPanel(new BorderLayout());
            line.setOpaque(false);
            line.add(new JLabel(d.getKey()), BorderLayout.WEST);
            line.add(soft(NUM.format(d.getValue())), BorderLayout.EAST);
            p.add(left(line));
            p.add(Box.createVerticalStrut(2));
            p.add(left(new Bar(d.getValue() / (double) max)));
            p.add(Box.createVerticalStrut(8));
        }
        return card(p);
    }

    /**
     * Classes with every exaltation, a strip with one cell per class (full, started, none), and the class closest
     * to being fully exalted.
     */
    private JComponent exaltation() {
        JPanel p = column();
        p.add(left(heading("Exaltation")));
        p.add(Box.createVerticalStrut(6));
        if (ExaltStore.get().isEmpty()) {
            p.add(left(muted("Visit the nexus once so your exaltations are loaded.")));
            return card(p);
        }
        CharacterClass[] classes = CharacterClass.CHAR_CLASS_LIST;
        int[] totals = new int[classes.length];
        int full = 0, closest = -1;
        for (int i = 0; i < classes.length; i++) {
            totals[i] = ExaltStore.total(ExaltStore.get().progress(classes[i].getId()));
            if (totals[i] == ExaltStore.MAX_PER_CLASS) full++;
            else if (closest < 0 || totals[i] > totals[closest]) closest = i;
        }
        JLabel count = new JLabel(full + "");
        count.setFont(BIG);
        JLabel of = soft("/ " + classes.length + " classes maxed");
        of.setFont(of.getFont().deriveFont(24f));
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        line.setOpaque(false);
        line.add(count);
        line.add(of);
        p.add(left(line));
        p.add(Box.createVerticalStrut(6));
        p.add(left(new ExaltStrip(totals)));
        p.add(Box.createVerticalStrut(10));
        if (closest >= 0) {
            int left = ExaltStore.MAX_PER_CLASS - totals[closest];
            p.add(left(soft("Closest next: " + CharacterClass.getName(classes[closest].getId()) + ", "
                    + left + (left == 1 ? " exaltation" : " exaltations") + " left")));
        } else {
            p.add(left(soft("Every class is fully exalted!")));
        }
        return card(p);
    }

    private JComponent dailyQuests() {
        JPanel p = column();
        p.add(left(heading("Daily quests")));
        p.add(Box.createVerticalStrut(6));
        p.add(left(muted("Coming soon from the Loot Gobbler server.")));
        return card(p);
    }

    // ---- data ------------------------------------------------------------------------------------------------

    /**
     * Every recorded drop, newest first.
     */
    private static List<DropRow> allDrops() {
        List<DropRow> out = new ArrayList<>();
        ZoneId zone = ZoneId.systemDefault();
        for (Map.Entry<Integer, CollectionStore.Entry> e : CollectionStore.get().snapshot().entrySet()) {
            for (CollectionStore.Find f : e.getValue().history()) {
                if (!CollectionStore.DROP.equals(f.source) || f.time == null) continue;
                try {
                    long t = LocalDateTime.parse(f.time).atZone(zone).toInstant().toEpochMilli();
                    out.add(new DropRow(e.getKey(), f.bag, f.where, t));
                } catch (Exception ignore) {
                }
            }
        }
        out.sort(Comparator.comparingLong(DropRow::time).reversed());
        return out;
    }

    /**
     * "Boosted White" -> "White".
     */
    private static String baseBag(String bag) {
        return bag == null ? "" : bag.startsWith("Boosted ") ? bag.substring(8) : bag;
    }

    /**
     * The bag's sprite, from its name as stored with a find ("White", "Boosted Orange"); null if unknown.
     */
    static Icon bagIcon(String bag, int size) {
        if (bag == null || bag.isEmpty()) return null;
        String name = bag.startsWith("Boosted ") ? "B." + bag.substring(8) : bag;
        for (LootBags b : LootBags.values()) {
            if (name.equals(LootBags.lootBagName(b.getId()))) return CharactersView.sprite(b.getId(), size);
        }
        return null;
    }

    private static String itemName(int id) {
        ItemDb.Item info = ItemDb.get().item(id);
        return info != null ? info.name : "Item " + id;
    }

    private static String duration(long ms) {
        long m = ms / 60_000;
        return m < 60 ? m + "m" : m / 60 + "h " + m % 60 + "m";
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    private static String shortDps(double v) {
        if (v <= 0) return "-";
        return v >= 1000 ? String.format(Locale.ENGLISH, "%.1fk", v / 1000) : NUM.format(Math.round(v));
    }

    // ---- building blocks -------------------------------------------------------------------------------------

    /**
     * Equal-width cards side by side, as tall as the tallest.
     */
    private static JComponent row(int columns, JComponent... cards) {
        JPanel p = new JPanel(new GridLayout(1, columns, 16, 0));
        p.setOpaque(false);
        for (JComponent c : cards) p.add(c);
        return left(p);
    }

    /**
     * A panel in the theme's paper color with a line border; its content sits at the top.
     */
    private static JComponent card(JComponent content) {
        JPanel p = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Section.color("LootGobbler.paper", Color.WHITE));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Section.color("LootGobbler.line", Color.GRAY));
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
            }
        };
        p.setBorder(new EmptyBorder(14, 18, 14, 18));
        p.add(content, BorderLayout.NORTH);
        return p;
    }

    private static JComponent tile(String label, String value, String sub) {
        JPanel p = column();
        p.add(left(caption(label)));
        JLabel v = new JLabel(value);
        v.setFont(BIG);
        p.add(left(v));
        p.add(left(soft(sub)));
        return card(p);
    }

    private static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(HEAD);
        l.setForeground(gold());
        return l;
    }

    private static JLabel caption(String text) {
        JLabel l = new JLabel(text);
        l.putClientProperty("FlatLaf.styleClass", "small");
        l.setForeground(Section.color("LootGobbler.muted", Palette.MUTED));
        return l;
    }

    private static JLabel soft(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Section.color("LootGobbler.inkSoft", Palette.MUTED));
        return l;
    }

    private static JLabel muted(String text) {
        JLabel l = new JLabel(text);
        l.setForeground(Palette.MUTED);
        return l;
    }

    private static Color gold() {
        return Section.color("LootGobbler.gold", Palette.GOLD);
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

    private static <T extends JComponent> T right(T c) {
        c.setAlignmentX(RIGHT_ALIGNMENT);
        return c;
    }

    /**
     * Fame over the last 24 hours as small step lines (the main character's on top, others tinted behind). Like the
     * Characters page's chart, stretches of an hour or more without any fame change are cut out to a narrow break,
     * and every dungeon sits above the main line where you left it (click: that dungeon under Battles), with the
     * characters of guildmates who fought its bosses with you above it.
     */
    private static class FameGraph extends JComponent {
        // GAP: the room between a dungeon icon and the line below it; AXIS: room for the break times
        private static final int BREAK = 26, ICON = 24, MATE = 16, GAP = 7, TOP = ICON + MATE + GAP + 4, AXIS = 15;

        record Series(List<Sample> samples, Color color, float width) {
        }

        private final List<Series> series;   // the main one first
        private final IdleTimeline timeline;
        private final List<Object[]> dungeons = new ArrayList<>();   // {Visit, gained, Rectangle, mates} as last painted
        private final Map<String, Icon> icons = new HashMap<>();

        FameGraph(List<Series> series) {
            this.series = series.stream().filter(s -> s.samples().size() > 1).toList();
            long t0 = Long.MAX_VALUE, t1 = Long.MIN_VALUE;
            List<Long> changes = new ArrayList<>();
            for (Series s : this.series) {
                List<Sample> ss = s.samples();
                t0 = Math.min(t0, ss.get(0).t);
                t1 = Math.max(t1, ss.get(ss.size() - 1).t);
                changes.addAll(IdleTimeline.changes(points(ss)));
            }
            Collections.sort(changes);
            timeline = this.series.isEmpty() ? null : new IdleTimeline(changes, t0, t1, BREAK);
            setPreferredSize(new Dimension(240, 64 + TOP + AXIS));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 64 + TOP + AXIS));
            setToolTipText("Fame gained over the last 24 hours");
            Clicks.onClick(this, e -> {
                DungeonVisits.Visit v = dungeonAt(e.getPoint());
                if (v != null) DpsLoggerView.openVisit(v);
            });
            addMouseMotionListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseMoved(java.awt.event.MouseEvent e) {
                    setCursor(Cursor.getPredefinedCursor(dungeonAt(e.getPoint()) != null ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                }
            });
        }

        private static List<long[]> points(List<Sample> ss) {
            List<long[]> out = new ArrayList<>(ss.size());
            for (Sample x : ss) out.add(new long[]{x.t, x.fame});
            return out;
        }

        private DungeonVisits.Visit dungeonAt(Point p) {
            for (Object[] d : dungeons) if (((Rectangle) d[2]).contains(p)) return (DungeonVisits.Visit) d[0];
            return null;
        }

        @Override
        public String getToolTipText(java.awt.event.MouseEvent e) {
            for (Object[] d : dungeons) {
                if (!((Rectangle) d[2]).contains(e.getPoint())) continue;
                DungeonVisits.Visit v = (DungeonVisits.Visit) d[0];
                String with = (String) d[3];
                int n = (int) d[4];
                return (n > 1 ? n + "x " : "") + v.dungeon + ":  +" + NUM.format((long) d[1]) + " fame"
                        + (with.isEmpty() ? "" : "  ·  with " + with)
                        + (n > 1 ? "  ·  click to open the last one in Battles" : "  ·  click to open it in Battles");
            }
            if (timeline != null) {
                for (double[] b : timeline.breaks(2, getWidth() - 2)) {
                    if (Math.abs(e.getX() - b[2]) <= BREAK / 2.0) return "No fame for " + IdleTimeline.gap((long) (b[1] - b[0])) + " (left out)";
                }
            }
            return super.getToolTipText(e);
        }

        @Override
        protected void paintComponent(Graphics g) {
            dungeons.clear();
            if (series.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight() - 4 - AXIS, left = 2, right = w - 2;
            long hi = 1;
            for (Series s : series) {
                List<Sample> ss = s.samples();
                long base = ss.get(0).fame;
                for (Sample x : ss) hi = Math.max(hi, x.fame - base);
            }
            // the lines leave the cut-out stretches empty, so the card's own background shows there
            java.awt.geom.Area drawn = new java.awt.geom.Area(new Rectangle(0, 0, w, getHeight()));
            for (double[] b : timeline.breaks(left, right)) {
                drawn.subtract(new java.awt.geom.Area(new Rectangle((int) Math.round(b[2]) - BREAK / 2 + 3, 0, BREAK - 6, getHeight())));
            }
            Shape oldClip = g2.getClip();
            g2.clip(drawn);
            g2.setColor(Section.color("LootGobbler.line", Color.GRAY));
            g2.fillRect(0, h + 2, w, 2);
            // the main line's highest point (smallest y) in every pixel column, so the dungeons can sit evenly above it
            int[] lineTop = new int[w];
            java.util.Arrays.fill(lineTop, Integer.MAX_VALUE);
            for (int k = series.size() - 1; k >= 0; k--) {   // the main line last, on top
                Series s = series.get(k);
                List<Sample> ss = s.samples();
                long base = ss.get(0).fame;
                g2.setColor(s.color());
                g2.setStroke(new BasicStroke(s.width(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int px = 0, py = 0;
                for (int i = 0; i < ss.size(); i++) {
                    Sample x = ss.get(i);
                    int sx = (int) Math.round(timeline.x(x.t, left, right));
                    int sy = h - (int) (Math.max(0, x.fame - base) * (h - 4 - TOP) / hi) - 2;
                    if (i > 0) {
                        g2.drawLine(px, py, sx, py);   // stepped: fame stays until the next sample
                        g2.drawLine(sx, py, sx, sy);
                        if (k == 0) {
                            for (int c = Math.max(0, px); c <= Math.min(w - 1, sx); c++) lineTop[c] = Math.min(lineTop[c], py);
                            if (sx >= 0 && sx < w) lineTop[sx] = Math.min(lineTop[sx], sy);
                        }
                    }
                    px = sx;
                    py = sy;
                }
            }
            g2.setClip(oldClip);
            // the cut-out stretches: two slashes on the base line and how long it was
            g2.setStroke(new BasicStroke(1.3f));
            g2.setFont(getFont().deriveFont(Math.max(9f, getFont().getSize2D() - 3)));
            FontMetrics fm = g2.getFontMetrics();
            for (double[] b : timeline.breaks(left, right)) {
                int cx = (int) Math.round(b[2]);
                g2.setColor(Palette.MUTED);
                for (int dx : new int[]{-3, 2}) g2.drawLine(cx + dx - 2, h + 5, cx + dx + 2, h - 3);
                String gap = IdleTimeline.gap((long) (b[1] - b[0]));   // under the axis, below the slashes
                g2.drawString(gap, cx - fm.stringWidth(gap) / 2, getHeight() - 2);
            }
            // every dungeon above the line where you left it; the same dungeon several times in a row is one icon with
            // "3x" before it, at the last one. A group that would touch the one before it moves right.
            List<long[]> main = points(series.get(0).samples());
            long base = series.get(0).samples().get(0).fame;
            List<DungeonVisits.Visit> visits = new ArrayList<>(DungeonVisits.get().visits());
            if (DungeonVisits.get().current() != null) visits.add(DungeonVisits.get().current());
            visits.sort(Comparator.comparingLong(v -> v.start));
            List<List<DungeonVisits.Visit>> groups = new ArrayList<>();
            for (DungeonVisits.Visit v : visits) {
                if (DungeonVisits.end(v) < timeline.from || v.start > System.currentTimeMillis()) continue;
                List<DungeonVisits.Visit> last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
                if (last != null && last.get(0).dungeon.equals(v.dungeon)) last.add(v);
                else groups.add(new ArrayList<>(List.of(v)));
            }
            g2.setFont(getFont().deriveFont(Font.BOLD, Math.max(10f, getFont().getSize2D() - 1)));
            fm = g2.getFontMetrics();
            int lastRight = -10_000;
            for (List<DungeonVisits.Visit> group : groups) {
                DungeonVisits.Visit first = group.get(0), lastVisit = group.get(group.size() - 1);
                Icon icon = icons.computeIfAbsent(first.dungeon, d -> ActiveDungeonBadge.fit(ProfileView.dungeonIcon(d, 64), ICON, true));
                if (icon == null) continue;
                long end = Math.min(DungeonVisits.end(lastVisit), timeline.to);
                long gained = 0;
                for (DungeonVisits.Visit v : group) {
                    Long a = DungeonVisits.fameAt(main, v.start), b = DungeonVisits.fameAt(main, DungeonVisits.end(v));
                    if (a != null && b != null) gained += Math.max(0, b - a);
                }
                Long at = DungeonVisits.fameAt(main, end);
                String count = group.size() > 1 ? group.size() + "x" : "";
                int countW = count.isEmpty() ? 0 : fm.stringWidth(count) + 2, width = countW + ICON;
                // the icon's centre where you left, kept clear of the group before it and inside the graph
                int x = (int) Math.round(timeline.x(end, left, right));
                x = Math.max(x, lastRight + 6 + countW + ICON / 2);
                x = Math.min(x, right - ICON / 2);
                // the same gap above the highest point of the line under the icon (and its count)
                int under = Integer.MAX_VALUE;
                for (int c = Math.max(0, x - ICON / 2 - countW); c <= Math.min(w - 1, x + ICON / 2); c++) under = Math.min(under, lineTop[c]);
                if (under == Integer.MAX_VALUE) under = h - (int) (Math.max(0, (at == null ? base : at) - base) * (h - 4 - TOP) / hi) - 2;
                int y = under - ICON - GAP;
                Rectangle r = new Rectangle(x - ICON / 2, Math.max(MATE + 2, y), ICON, ICON);
                icon.paintIcon(this, g2, r.x, r.y);
                if (!count.isEmpty()) {
                    g2.setColor(gold());
                    g2.drawString(count, r.x - countW, r.y + ICON - (ICON - fm.getAscent()) / 2 - 2);
                    r.add(new Rectangle(r.x - countW, r.y, countW, ICON));
                }
                // the guildmates' characters (of all the group's runs) side by side right above it
                Map<String, DpsHistory.PlayerRow> mates = new LinkedHashMap<>();
                for (DungeonVisits.Visit v : group) for (DpsHistory.PlayerRow p : Guildmates.get().with(v)) mates.putIfAbsent(p.name.toLowerCase(Locale.ROOT), p);
                StringBuilder with = new StringBuilder();
                int mx = x - mates.size() * MATE / 2;
                for (DpsHistory.PlayerRow p : mates.values()) {
                    Icon mate = Guildmates.get().look(p, MATE);   // their skin and dyes then
                    if (mate != null) mate.paintIcon(this, g2, mx, r.y - MATE - 1);
                    mx += MATE;
                    String cls = CharacterClass.getName(p.classType);
                    with.append(with.isEmpty() ? "" : ", ").append(p.name).append(cls == null ? "" : " (" + cls + ")");
                }
                if (!mates.isEmpty()) r.add(new Rectangle(x - mates.size() * MATE / 2, r.y - MATE - 1, mates.size() * MATE, MATE));
                dungeons.add(new Object[]{lastVisit, gained, r, with.toString(), group.size()});
                lastRight = x + ICON / 2;
            }
            g2.dispose();
        }
    }

    /**
     * A thin horizontal bar, filled to the given share.
     */
    private static class Bar extends JComponent {
        private final double share;

        Bar(double share) {
            this.share = share;
            setPreferredSize(new Dimension(100, 10));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 10));
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(Section.color("LootGobbler.header", Color.LIGHT_GRAY));
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(gold());
            g.fillRect(0, 0, (int) Math.round(getWidth() * share), getHeight());
        }
    }

    /**
     * One cell per class: gold when fully exalted, darker gold when started, empty otherwise.
     */
    private static class ExaltStrip extends JComponent {
        private final int[] totals;

        ExaltStrip(int[] totals) {
            this.totals = totals;
            setPreferredSize(new Dimension(200, 18));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
        }

        @Override
        protected void paintComponent(Graphics g) {
            int n = totals.length, gap = 3;
            if (n == 0) return;
            double cell = (getWidth() - gap * (n - 1)) / (double) n;
            Color full = gold(), started = Section.color("LootGobbler.goldDark", Palette.GOLD_DARK),
                    none = Section.color("LootGobbler.header", Color.LIGHT_GRAY);
            for (int i = 0; i < n; i++) {
                g.setColor(totals[i] == ExaltStore.MAX_PER_CLASS ? full : totals[i] > 0 ? started : none);
                int x = (int) Math.round(i * (cell + gap));
                g.fillRect(x, 0, (int) Math.round(cell), getHeight());
            }
        }
    }
}
