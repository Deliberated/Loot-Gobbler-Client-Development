package lootgobbler.ui;

import lootgobbler.DpsHistory;
import lootgobbler.DungeonVisits;
import lootgobbler.GobblerServer;

import javax.swing.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The other members of your guild on the Loot Gobbler server (their names, lower case), loaded when you log in or
 * the server changes, and who of them were with you in a dungeon: the ones that fought its bosses with you, as the
 * run's damage table names them, in the look the server has for them (see withLooks). Used on the Swing thread.
 */
final class Guildmates {
    private static final Guildmates INSTANCE = new Guildmates();
    private final GobblerServer server = GobblerServer.get();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private Set<String> names = Set.of();
    private GobblerServer.GuildSettings settings = new GobblerServer.GuildSettings();

    static Guildmates get() {
        return INSTANCE;
    }

    private Guildmates() {
        server.onChange(() -> SwingUtilities.invokeLater(this::load));
        load();
    }

    /** Runs (on the Swing thread) when the list of names changed. */
    void onChange(Runnable listener) {
        listeners.add(listener);
    }

    Set<String> names() {
        return names;
    }

    private void load() {
        runLooks.clear();   // another server or account: ask again
        generation++;   // retries still pending belong to the old server
        GobblerServer.User me = server.user();
        if (me == null || server.state() != GobblerServer.State.ONLINE) {
            set(Set.of());
            return;
        }
        reloadSettings();
        server.leaderboard().whenComplete((b, e) -> SwingUtilities.invokeLater(() -> {
            if (e != null || b == null) return;   // keep what was known
            Set<String> mates = new HashSet<>();
            for (GobblerServer.Member m : b.members) if (m.id != me.id) mates.add(m.ign.toLowerCase(Locale.ROOT));
            set(mates);
        }));
    }

    /** The server's guild settings (the defaults until it answered). */
    GobblerServer.GuildSettings settings() {
        return settings;
    }

    /** Asks the server for the guild settings again; the views redraw if they changed. */
    void reloadSettings() {
        int asked = generation;
        server.guildSettings().whenComplete((g, e) -> SwingUtilities.invokeLater(() -> {
            if (g != null && asked == generation) setSettings(g);
        }));
    }

    /** Takes new guild settings (from the server, or after an admin saved them). */
    void setSettings(GobblerServer.GuildSettings g) {
        if (g.baselineHpm == settings.baselineHpm) return;
        settings = g;
        version++;
        for (Runnable r : listeners) r.run();
    }

    private void set(Set<String> mates) {
        if (mates.equals(names)) return;
        names = Set.copyOf(mates);
        for (Runnable r : listeners) r.run();
    }

    private final Map<String, Icon> looks = new HashMap<>();

    /**
     * A guildmate's character as it looked in that run: its skin with its dyes and cloths (runs logged before this
     * was kept show the plain class).
     */
    Icon look(DpsHistory.PlayerRow p, int size) {
        return looks.computeIfAbsent(p.spriteId() + ":" + p.tex1 + ":" + p.tex2 + ":" + size, k -> {
            try {
                return lootgobbler.Dyes.icon(p.spriteId(), p.tex1, p.tex2, size);
            } catch (Exception e) {
                return CharactersView.sprite(p.spriteId(), size);
            }
        });
    }

    /** The guildmates who fought the visit's bosses with you, one row each (first character seen). */
    List<DpsHistory.PlayerRow> with(DungeonVisits.Visit v) {
        Map<String, DpsHistory.PlayerRow> out = fought(v);
        return out.isEmpty() ? List.of() : withLooks(v, new ArrayList<>(out.values()));
    }

    /** The guildmates in the visit's damage table, by lower-case name, as the run saw them. */
    private Map<String, DpsHistory.PlayerRow> fought(DungeonVisits.Visit v) {
        if (names.isEmpty() || v == null) return Map.of();
        DpsHistory.Run run = v == DungeonVisits.get().current() ? DpsHistory.get().live() : DungeonVisits.run(v);
        if (run == null || run.guildHall || !run.dungeon.equals(v.dungeon)) return Map.of();
        Map<String, DpsHistory.PlayerRow> out = new LinkedHashMap<>();
        for (DpsHistory.Boss b : run.bosses) {
            for (DpsHistory.PlayerRow p : b.players) {
                String key = p.name.toLowerCase(Locale.ROOT);
                if (!p.me && names.contains(key)) out.putIfAbsent(key, p);
            }
        }
        return out;
    }

    /**
     * The guildmates who fought with you in the visit but whose own visit of it is not on the server yet (their
     * client may not have synced it); asked again for a while, see RETRY_MS.
     */
    List<String> awaited(DungeonVisits.Visit v) {
        Map<String, List<GobblerServer.RunLook>> looks = runLooks.get(v.dungeon + "/" + v.start);
        if (looks == null || looks == ASKING || looks == FAILED) return List.of();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, DpsHistory.PlayerRow> e : fought(v).entrySet())
            if (looks.getOrDefault(e.getKey(), List.of()).stream().noneMatch(l -> "visit".equals(l.source))) out.add(e.getValue().name);
        return out;
    }

    /**
     * Whether the server will still be asked again about the visit for the guildmates it is missing: only within
     * RETRY_FOR_MS of leaving it, while their clients may still be syncing it. Older runs are asked once.
     */
    boolean retrying(DungeonVisits.Visit v) {
        long left = v.start + v.durationMs;
        return System.currentTimeMillis() - left + RETRY_MS <= RETRY_FOR_MS && !awaited(v).isEmpty();
    }

    // per finished visit (dungeon/start): the guildmates' looks and hits the server has for it, by lower-case name;
    // ASKING while asking, FAILED if the server could not answer
    private final Map<String, Map<String, List<GobblerServer.RunLook>>> runLooks = new HashMap<>();
    private static final Map<String, List<GobblerServer.RunLook>> ASKING = new HashMap<>(), FAILED = new HashMap<>();
    private int version;   // goes up with every answer, so views know to rebuild
    // a guildmate's client syncs their visit a little after they left: until all who fought with you are there,
    // the server is asked again every RETRY_MS, until RETRY_FOR_MS after you left the dungeon
    private static final int RETRY_MS = 20_000, RETRY_FOR_MS = 180_000;
    private int generation;

    int version() {
        return version;
    }

    /** What the server said about a run: null while asking (or offline, or still in it); empty if it had nothing. */
    Map<String, List<GobblerServer.RunLook>> runLooks(DungeonVisits.Visit v) {
        if (v.durationMs <= 0 || server.state() != GobblerServer.State.ONLINE) return null;   // in progress: not saved yet
        String key = v.dungeon + "/" + v.start;
        Map<String, List<GobblerServer.RunLook>> looks = runLooks.get(key);
        if (looks == null) {
            runLooks.put(key, ASKING);
            ask(v, key);
            return null;
        }
        return looks == ASKING ? null : looks;
    }

    private void ask(DungeonVisits.Visit v, String key) {
        int asked = generation;
        server.runLooks(v.dungeon, v.start, v.start + v.durationMs).whenComplete((r, e) -> SwingUtilities.invokeLater(() -> {
            if (asked != generation) return;   // the server changed meanwhile
            boolean again = runLooks.get(key) != ASKING;
            Map<String, List<GobblerServer.RunLook>> byName = new HashMap<>();
            if (e != null || r == null) {
                if (again) byName = runLooks.get(key);   // a retry that failed: keep the last answer
                else byName = FAILED;   // stays so until the server changes
            } else {
                for (GobblerServer.RunLook l : r.looks)
                    byName.computeIfAbsent(l.player.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(l);
            }
            runLooks.put(key, byName);
            version++;
            for (Runnable listener : listeners) listener.run();
            if (byName != FAILED && retrying(v)) {
                javax.swing.Timer t = new javax.swing.Timer(RETRY_MS, x -> {
                    if (asked == generation) ask(v, key);
                });
                t.setRepeats(false);
                t.start();
            }
        }));
    }

    /** Whether asking the server about this run failed. */
    boolean failed(DungeonVisits.Visit v) {
        return runLooks.get(v.dungeon + "/" + v.start) == FAILED;
    }

    /**
     * The rows in the look the server has for them: what their own client saved with their visit of this run (sure,
     * even if an ability transformed them), else, if this client saw no look of theirs, their character of that
     * class as it looks now (living and last played first). Asked once per visit; the views redraw when it comes.
     */
    private List<DpsHistory.PlayerRow> withLooks(DungeonVisits.Visit v, List<DpsHistory.PlayerRow> rows) {
        Map<String, List<GobblerServer.RunLook>> looks = runLooks(v);
        if (looks == null) return rows;
        List<DpsHistory.PlayerRow> out = new ArrayList<>(rows.size());
        for (DpsHistory.PlayerRow p : rows) {
            GobblerServer.RunLook l = pick(looks.getOrDefault(p.name.toLowerCase(Locale.ROOT), List.of()), p);
            if (l == null) {
                out.add(p);
                continue;
            }
            DpsHistory.PlayerRow c = new DpsHistory.PlayerRow();   // a copy: the run's own record stays as seen
            c.name = p.name;
            c.classType = p.classType;
            c.damage = p.damage;
            c.taken = p.taken;
            c.died = p.died;
            c.skin = l.skin;
            c.tex1 = l.tex1;
            c.tex2 = l.tex2;
            out.add(c);
        }
        return out;
    }

    /** The look to show for a row: their visit's, else (only if none was seen here) a character of that class. */
    private static GobblerServer.RunLook pick(List<GobblerServer.RunLook> looks, DpsHistory.PlayerRow p) {
        for (GobblerServer.RunLook l : looks) if ("visit".equals(l.source) && l.classType == p.classType) return l;
        boolean seen = p.skin > 0 && !lootgobbler.ItemDb.get().isTransformSkin(p.skin) || p.tex1 != 0 || p.tex2 != 0;
        if (seen) return null;
        return looks.stream().filter(l -> "character".equals(l.source) && l.classType == p.classType)
                .max(Comparator.comparing((GobblerServer.RunLook l) -> l.alive).thenComparingLong(l -> l.lastPlayed))
                .orElse(null);
    }
}
