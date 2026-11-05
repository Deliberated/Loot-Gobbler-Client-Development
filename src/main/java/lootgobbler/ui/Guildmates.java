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
        GobblerServer.User me = server.user();
        if (me == null || server.state() != GobblerServer.State.ONLINE) {
            set(Set.of());
            return;
        }
        server.leaderboard().whenComplete((b, e) -> SwingUtilities.invokeLater(() -> {
            if (e != null || b == null) return;   // keep what was known
            Set<String> mates = new HashSet<>();
            for (GobblerServer.Member m : b.members) if (m.id != me.id) mates.add(m.ign.toLowerCase(Locale.ROOT));
            set(mates);
        }));
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
        if (names.isEmpty() || v == null) return List.of();
        DpsHistory.Run run = v == DungeonVisits.get().current() ? DpsHistory.get().live() : DungeonVisits.run(v);
        if (run == null || run.guildHall || !run.dungeon.equals(v.dungeon)) return List.of();
        Map<String, DpsHistory.PlayerRow> out = new LinkedHashMap<>();
        for (DpsHistory.Boss b : run.bosses) {
            for (DpsHistory.PlayerRow p : b.players) {
                String key = p.name.toLowerCase(Locale.ROOT);
                if (!p.me && names.contains(key)) out.putIfAbsent(key, p);
            }
        }
        return out.isEmpty() ? List.of() : withLooks(v, new ArrayList<>(out.values()));
    }

    // per finished visit (dungeon/start): the guildmates' looks the server has for it, by lower-case name; an empty
    // map while asking or when it has none
    private final Map<String, Map<String, List<GobblerServer.RunLook>>> runLooks = new HashMap<>();

    /**
     * The rows in the look the server has for them: what their own client saved with their visit of this run (sure,
     * even if an ability transformed them), else, if this client saw no look of theirs, their character of that
     * class as it looks now (living and last played first). Asked once per visit; the views redraw when it comes.
     */
    private List<DpsHistory.PlayerRow> withLooks(DungeonVisits.Visit v, List<DpsHistory.PlayerRow> rows) {
        if (v.durationMs <= 0 || server.state() != GobblerServer.State.ONLINE) return rows;   // in progress: not saved yet
        String key = v.dungeon + "/" + v.start;
        Map<String, List<GobblerServer.RunLook>> looks = runLooks.get(key);
        if (looks == null) {
            runLooks.put(key, Map.of());
            server.runLooks(v.dungeon, v.start, v.start + v.durationMs).whenComplete((r, e) -> SwingUtilities.invokeLater(() -> {
                if (e != null || r == null) return;   // stays empty until the server changes
                Map<String, List<GobblerServer.RunLook>> byName = new HashMap<>();
                for (GobblerServer.RunLook l : r.looks)
                    byName.computeIfAbsent(l.player.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(l);
                runLooks.put(key, byName);
                if (!byName.isEmpty()) for (Runnable listener : listeners) listener.run();
            }));
            return rows;
        }
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
