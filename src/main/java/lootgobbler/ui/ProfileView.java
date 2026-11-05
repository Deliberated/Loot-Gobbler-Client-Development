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

    // the guild row, from the server
    private final GobblerServer server = GobblerServer.get();
    private final CaravanView.Scene wagon;
    private String guildName, guildNote = "";   // guildNote: why there is nothing to show; "" while loading
    private CaravanMember myWagon;
    private QuestMap questMap;
    private int guildVersion;
    private boolean guildLoading, guildAgain, guildStale;

    public ProfileView(JComponent dungeonStats, IntConsumer openCharacter, Consumer<String> openGuild,
                       Consumer<DpsHistory.Run> openRun) {
        this.openCharacter = openCharacter;
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
        server.caravan("week").thenCombine(server.questMaps(1), (c, q) -> {
            CaravanMember mine = c.members.stream().filter(m -> m.id == me.id).findFirst().orElseGet(() -> {
                CaravanMember m = new CaravanMember();   // nothing found this week yet
                m.id = me.id;
                m.ign = me.ign;
                return m;
            });
            return new Object[]{c.guild, mine, q.current};
        }).whenComplete((r, e) -> SwingUtilities.invokeLater(() -> {
            guildLoading = false;
            if (e != null) setGuild(null, null, null, GuildPanel.loadError(e));
            else setGuild((String) r[0], (CaravanMember) r[1], (QuestMap) r[2], null);
            if (guildAgain) {
                guildAgain = false;
                loadGuild();
            }
        }));
    }

    private void setGuild(String name, CaravanMember mine, QuestMap map, String note) {
        String before = guildName + "|" + guildNote + "|" + key(myWagon) + "|" + key(questMap);
        guildName = name;
        myWagon = mine;
        questMap = map;
        guildNote = note;
        if (before.equals(guildName + "|" + guildNote + "|" + key(myWagon) + "|" + key(questMap))) return;
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
        for (Char c : chars) parts.add(List.of(c.charId, c.alive, c.fame, c.level, c.skin, c.samples.size()));
        for (int i = 0; i < Math.min(RECENT, runs.size()); i++) parts.add(runs.get(i).start);
        for (CharacterClass cls : CharacterClass.CHAR_CLASS_LIST)
            parts.add(ExaltStore.total(ExaltStore.get().progress(cls.getId())));
        parts.add(amuletSaves());
        String state = parts.hashCode() + "";
        if (state.equals(shown)) return;
        shown = state;

        body.removeAll();
        body.add(row(2, playerCard(name, chars, pets), mostPlayedCard(store, most, chars, since)));
        body.add(Box.createVerticalStrut(20));
        body.add(row(1, guildCard()));
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

    private JComponent playerCard(String name, List<Char> chars, int pets) {
        JPanel p = column();
        p.add(left(caption("PLAYER")));
        JLabel player = new JLabel(name != null ? name : "Unknown player");
        player.setFont(new Font(Themes.HEAD_FONT, Font.PLAIN, 56));
        player.setForeground(gold());
        p.add(left(player));
        if (name == null) p.add(left(muted("Start the sniffer and enter the game once to show your name.")));

        List<Char> alive = chars.stream().filter(c -> c.alive).toList();
        long fame = alive.stream().mapToLong(c -> c.fame).sum();
        int classes = CharacterClass.CHAR_CLASS_LIST.length;
        long exalted = Arrays.stream(CharacterClass.CHAR_CLASS_LIST)
                .filter(c -> ExaltStore.total(ExaltStore.get().progress(c.getId())) == ExaltStore.MAX_PER_CLASS).count();
        p.add(Box.createVerticalStrut(8));
        p.add(left(soft(alive.size() + " characters   ·   " + NUM.format(fame) + " alive fame")));
        p.add(left(soft(exalted + " / " + classes + " classes exalted   ·   " + pets + " pets")));
        long last = chars.stream().mapToLong(c -> c.lastPlayed).max().orElse(0);
        if (last > 0) p.add(left(muted("Last played " + CharactersView.ago(last))));
        JPanel both = new JPanel(new BorderLayout(16, 0));
        both.setOpaque(false);
        both.add(p, BorderLayout.CENTER);
        both.add(amuletBadge(), BorderLayout.EAST);
        return card(both);
    }

    private static final int AMULET = 23624;   // Amulet of Restoration (5337 is its shiny version)
    private static final long AMULET_DAYS = 3;

    private static int amuletSaves() {
        return lootgobbler.AmuletSaves.get().countSince(System.currentTimeMillis() - AMULET_DAYS * 24 * 3600_000L);
    }

    /** The Amulet of Restoration and how often it saved you lately, at the right of the player card. */
    private JComponent amuletBadge() {
        int n = amuletSaves();
        JLabel icon = new JLabel(CharactersView.sprite(AMULET, 56));
        icon.setAlignmentX(CENTER_ALIGNMENT);
        JLabel text = new JLabel("<html><div style='text-align:center'>saved you <b>" + n + "</b> time" + (n == 1 ? "" : "s")
                + "<br>in the last " + AMULET_DAYS + " days</div></html>", SwingConstants.CENTER);
        text.setForeground(Section.color("LootGobbler.inkSoft", Palette.MUTED));
        text.setAlignmentX(CENTER_ALIGNMENT);
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setToolTipText("Your Amulet of Restoration kept you alive " + n + " time" + (n == 1 ? "" : "s")
                + " in the last " + AMULET_DAYS + " days");
        col.add(icon);
        col.add(Box.createVerticalStrut(4));
        col.add(text);
        JPanel holder = new JPanel(new GridBagLayout());   // vertically centered beside the name
        holder.setOpaque(false);
        holder.add(col);
        return holder;
    }

    /**
     * The character's skin, class and progress, why it was picked (fame gained today, or played most recently),
     * and the fame all your characters gained over the last 24 hours as one graph, with every dungeon on it and
     * the guildmates who joined you above each. Clicking it opens the character in the Characters tab.
     */
    private JComponent mostPlayedCard(CharacterStore store, Char c, List<Char> chars, long since) {
        if (c == null) {
            JPanel p = column();
            p.add(left(caption("MOST PLAYED · 24H")));
            p.add(left(muted("Log in once so your character list is loaded.")));
            return card(p);
        }
        List<Char> gainers = chars.stream().filter(o -> store.gainedToday(o) > 0)
                .sorted(Comparator.comparingLong((Char o) -> -store.gainedToday(o))).toList();
        long gain = gainers.stream().mapToLong(store::gainedToday).sum();   // all characters, not just this one
        JLabel sprite = new JLabel(CharactersView.skin(c, 96));
        sprite.setVerticalAlignment(SwingConstants.TOP);

        JPanel about = column();
        about.add(left(caption("MOST PLAYED · 24H")));
        JLabel cls = new JLabel(c.className + "   " + c.statsMaxed + "/8");
        cls.setFont(cls.getFont().deriveFont(30f));
        about.add(left(cls));
        JLabel progress = new JLabel(c.level < 20 ? "Level " + c.level : NUM.format(c.fame) + " Fame");
        progress.setFont(progress.getFont().deriveFont(22f));
        progress.setForeground(gold());
        about.add(left(progress));
        if (store.gainedToday(c) == 0) about.add(left(muted(c.lastPlayed == 0 ? "Not played yet" : "Last played " + CharactersView.ago(c.lastPlayed))));

        // beside it, a bit in from the right: the fame gained in the last 24h (always, also +0) and, below in
        // gold, in the last hour (only when there is some)
        JPanel gains = column();
        JLabel gained = new JLabel("+" + NUM.format(gain) + " fame");
        gained.setFont(gained.getFont().deriveFont(20f));
        gained.setForeground(gain > 0 ? Palette.GREEN : Palette.MUTED);
        gained.setToolTipText("Fame gained by all your characters in the last 24 hours");
        gains.add(right(gained));
        long hour = gainers.stream().flatMap(o -> store.sessions(o, System.currentTimeMillis() - 3_600_000L).stream())
                .mapToLong(CharacterStore.Session::gained).sum();
        if (hour > 0) {
            JLabel lastHour = new JLabel("+" + NUM.format(hour) + " last hour");
            lastHour.setForeground(gold());
            lastHour.setToolTipText("Fame gained in the last hour");
            gains.add(right(lastHour));
        }
        JPanel gainsAtBottom = new JPanel(new BorderLayout());
        gainsAtBottom.setOpaque(false);
        gainsAtBottom.setBorder(new EmptyBorder(0, 12, 0, 24));
        gainsAtBottom.add(gains, BorderLayout.SOUTH);

        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(about, BorderLayout.CENTER);
        head.add(gainsAtBottom, BorderLayout.EAST);
        JPanel text = column();
        text.add(left(head));

        List<Sample> total = total(gainers.isEmpty() ? List.of(c) : gainers, since);
        if (total.size() > 1) {
            text.add(Box.createVerticalStrut(6));
            text.add(left(new FameGraph(List.of(new FameGraph.Series(total, gold(), 3f)))));
            if (gainers.size() > 1) {   // which characters the total is made of
                JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                legend.setOpaque(false);
                for (int i = 0; i < gainers.size(); i++) {
                    Char o = gainers.get(i);
                    legend.add(legendLabel((i == 0 ? "" : "     ") + o.className + " +" + NUM.format(store.gainedToday(o)),
                            i == 0 ? gold() : Palette.MUTED));
                }
                text.add(left(legend));
            }
        }

        JPanel inner = new JPanel(new BorderLayout(18, 0));
        inner.setOpaque(false);
        inner.add(sprite, BorderLayout.WEST);
        inner.add(text, BorderLayout.CENTER);
        JComponent card = card(inner);
        card.setToolTipText("Open in Characters");
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        int id = c.charId;
        Clicks.onClick(card, () -> openCharacter.accept(id));
        return card;
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
        p.add(left(heading(guildName == null ? "Guild" : "Guild - " + guildName)));
        p.add(Box.createVerticalStrut(8));
        if (guildNote != null || myWagon == null) {
            p.add(left(muted(guildNote == null || guildNote.isEmpty() ? "Loading..." : guildNote)));
            return card(p);
        }
        wagon.setMembers(List.of(myWagon));
        JPanel caravan = column();
        caravan.add(center(caption("YOUR WAGON · THIS WEEK")));
        caravan.add(Box.createVerticalStrut(4));
        caravan.add(center(wagon));
        caravan.add(Box.createVerticalStrut(6));
        caravan.add(center(link("To the caravan")));
        clickable(caravan, () -> openGuild.accept(GuildPanel.CARAVAN));

        JPanel quest = column();
        quest.add(center(caption("QUEST MAP")));
        quest.add(Box.createVerticalStrut(10));
        if (questMap == null || questMap.dungeons.isEmpty()) {
            quest.add(center(muted("No quest map yet")));
        } else {
            int n = questMap.dungeons.size(), done = Math.min(questMap.clears.size(), n);
            boolean complete = questMap.completedAt != null || done == n;
            String next = questMap.dungeons.get(complete ? n - 1 : done);
            quest.add(center(new JLabel(dungeonIcon(next, 80))));
            quest.add(Box.createVerticalStrut(6));
            quest.add(center(new JLabel(complete ? "All done!" : "Next: " + next)));
            JLabel progress = new JLabel(done + "/" + n + " dungeons completed");
            progress.setFont(progress.getFont().deriveFont(20f));
            progress.setForeground(complete ? Palette.GREEN : gold());
            quest.add(center(progress));
            quest.add(Box.createVerticalStrut(6));
            quest.add(center(link("To the quest map")));
        }
        clickable(quest, () -> openGuild.accept(GuildPanel.QUEST_MAP));

        JPanel items = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        items.setOpaque(false);
        items.add(caravan);
        items.add(Box.createHorizontalStrut(64));
        items.add(quest);
        p.add(left(items));
        return card(p);
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

    /** A dungeon's portal sprite, by its name as the Run History records it. */
    static Icon dungeonIcon(String name, int size) {
        int id = tomato.realmshark.ParseDungeon.getPortalId(name);
        if (id == -1) {
            tomato.realmshark.enums.CharacterStatistics cs = tomato.realmshark.enums.CharacterStatistics.statByName(name);
            if (cs != null) id = cs.getSpriteId();
        }
        return CharactersView.sprite(id, size);
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
                int y = h - (int) (Math.max(0, (at == null ? base : at) - base) * (h - 4 - TOP) / hi) - 2 - ICON - GAP;
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
