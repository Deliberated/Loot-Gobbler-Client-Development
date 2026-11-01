package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import packets.incoming.MapInfoPacket;
import packets.incoming.NotificationPacket;
import tomato.backend.data.Damage;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.gui.dps.shared.DeathParser;
import tomato.realmshark.enums.CharacterClass;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Boss damage per dungeon run, kept across restarts (dps_history.json). Tomato keeps full fight data only
 * in memory; this stores a small summary of each logged run plus a once-per-second snapshot of the live run.
 * record() and liveTick() are called on the packet thread, where Tomato's fight data is safe to read.
 */
public final class DpsHistory {
    public static final class PlayerRow {
        public String name = "";
        public int classType;
        public int damage;
        public int taken;
        public boolean died;
        public boolean me;

        public String className() {
            String n = CharacterClass.getName(classType);
            return n == null ? "?" : n;
        }
    }

    public static final class Boss {
        public String name = "";
        public int objectType;
        public int maxHp;
        public long fightMs;
        public List<PlayerRow> players = new ArrayList<>();

        public long totalDamage() {
            long t = 0;
            for (PlayerRow p : players) t += p.damage;
            return t;
        }

        public double dps(PlayerRow p) {
            return p.damage / Math.max(1.0, fightMs / 1000.0);
        }

        public double share(PlayerRow p) {
            long t = totalDamage();
            return t == 0 ? 0 : 100.0 * p.damage / t;
        }

        public PlayerRow me() {
            for (PlayerRow p : players) if (p.me) return p;
            return null;
        }

        public int rankOf(PlayerRow p) {
            return players.indexOf(p) + 1;
        }
    }

    public static final class Run {
        public String dungeon = "";
        public long start;
        public long durationMs;
        public List<Boss> bosses = new ArrayList<>();

        /**
         * The boss with the most HP, usually the dungeon's final boss.
         */
        public Boss mainBoss() {
            return bosses.stream().max(Comparator.comparingInt(b -> b.maxHp)).orElse(null);
        }

        /**
         * Your best boss DPS in this run, or 0 if you did not damage a boss.
         */
        public double myPeakDps() {
            double best = 0;
            for (Boss b : bosses) {
                PlayerRow me = b.me();
                if (me != null) best = Math.max(best, b.dps(me));
            }
            return best;
        }
    }

    private static final Path FILE = Path.of("dps_history.json");
    private static final int MAX_RUNS = 300;
    private static final long LIVE_INTERVAL_MS = 1000;
    private static final Gson GSON = new Gson();
    private static final DpsHistory INSTANCE = new DpsHistory();

    private final List<Run> runs = new ArrayList<>();   // newest first
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Run live;
    private long lastLive;

    public static DpsHistory get() {
        return INSTANCE;
    }

    private DpsHistory() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            List<Run> loaded = GSON.fromJson(r, new TypeToken<ArrayList<Run>>() {
            }.getType());
            if (loaded != null) runs.addAll(loaded);
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    /**
     * Called when a logged dungeon ends. Runs without boss damage are not kept.
     */
    public void record(MapInfoPacket map, Collection<Entity> hitList, Collection<NotificationPacket> deaths, long durationMs) {
        Run run = summarize(map, hitList, deaths, durationMs);
        live = null;
        if (run != null) {
            synchronized (this) {
                runs.add(0, run);
                while (runs.size() > MAX_RUNS) runs.remove(runs.size() - 1);
                save();
            }
        }
        fire();
    }

    /**
     * Called every game tick; refreshes the live snapshot at most once per second.
     */
    public void liveTick(TomatoData data) {
        long now = System.currentTimeMillis();
        if (now - lastLive < LIVE_INTERVAL_MS) return;
        lastLive = now;
        live = summarize(data.map, Arrays.asList(data.getEntityHitList()), data.getDeathNotifications(), data.dungeonTime());
        fire();
    }

    public Run live() {
        return live;
    }

    public synchronized List<Run> runs() {
        return new ArrayList<>(runs);
    }

    public void remove(Run run) {
        synchronized (this) {
            runs.remove(run);
            save();
        }
        fire();
    }

    public void clear() {
        synchronized (this) {
            runs.clear();
            save();
        }
        fire();
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    static Run summarize(MapInfoPacket map, Collection<Entity> hitList, Collection<NotificationPacket> deaths, long durationMs) {
        if (map == null || hitList == null) return null;
        try {
            Map<String, Integer> deathMap = DeathParser.parseDeathsToMap(deaths);
            List<Entity> bosses = new ArrayList<>();
            for (Entity e : hitList) {
                if (e != null && e.maxHp() > 0 && !CharacterClass.isPlayerCharacter(e.objectType) && e.isBossMob()) {
                    bosses.add(e);
                }
            }
            if (bosses.isEmpty()) return null;
            bosses.sort(Comparator.comparingLong(Entity::getFirstDamageTaken));

            Run run = new Run();
            run.dungeon = map.name;
            run.durationMs = Math.max(0, durationMs);
            run.start = System.currentTimeMillis() - run.durationMs;
            for (Entity e : bosses) {
                Boss b = new Boss();
                b.name = e.name();
                b.objectType = e.objectType;
                b.maxHp = e.maxHp();
                b.fightMs = Math.max(0, e.getFightDuration());
                for (Damage d : e.getPlayerDamageList()) {
                    if (d == null || d.owner == null || d.damage <= 0) continue;
                    PlayerRow p = new PlayerRow();
                    p.name = d.owner.name() == null ? "?" : d.owner.name();
                    p.classType = d.owner.objectType;
                    p.damage = d.damage;
                    p.taken = d.owner.damageTaken(e)[0];
                    p.died = DeathParser.isDead(deathMap, p.name);
                    p.me = d.owner.isUser();
                    b.players.add(p);
                }
                if (!b.players.isEmpty()) run.bosses.add(b);
            }
            return run.bosses.isEmpty() ? null : run;
        } catch (Exception e) {
            // fight data changes while being read; skip this snapshot
            return null;
        }
    }

    private void save() {
        try {
            Path tmp = Path.of(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(runs, w);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save " + FILE + ": " + e.getMessage());
        }
    }
}
