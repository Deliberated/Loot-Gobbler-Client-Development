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
 * run's damage table names them. Used on the Swing thread.
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
        return new ArrayList<>(out.values());
    }
}
