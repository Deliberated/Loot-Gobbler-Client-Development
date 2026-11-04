package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import packets.incoming.MapInfoPacket;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every dungeon you enter, from the moment you walk in (the DPS Logger's run history only keeps runs with boss
 * damage): the dungeon in progress, and the finished visits, newest first. A visit where you fought a boss is linked
 * to its run in {@link DpsHistory}. Kept in dungeon_visits.json next to the app.
 */
public final class DungeonVisits implements Syncable {
    /**
     * Exaltation progress the game reported during a visit: one stat of one class (stat index in ExaltStore's order,
     * e.g. ExaltStore.DEX), from before to after. An exaltation was earned if a threshold lies in between.
     */
    public static final class Exalt {
        public int classType;
        public int stat;
        public int before, after;

        public int earned() {   // exaltations gained (0 if only progress)
            return ExaltStore.level(after) - ExaltStore.level(before);
        }
    }

    public static final class Visit {
        public String dungeon = "";
        public long start;
        public long durationMs;
        public long runStart;   // DpsHistory.Run.start of the run with your boss damage; 0 = no boss fought
        public List<Exalt> exalts = new ArrayList<>();   // exaltation progress the game reported in this dungeon
        // every hit you took, oldest first: ms after entering, damage (after your defense), and the enemy's object
        // type (0 = no enemy, e.g. ground or an area effect); maxHp is your maximum life then
        public int[] hitTime = new int[0], hitDamage = new int[0], hitEnemy = new int[0];
        public int maxHp;
        public int[] dealt = new int[0];   // the damage you dealt to enemies, per second after entering
        // Moonlight Village: how long its lantern was there, and you within 3.5 / 1.75 tiles of it (MoonlightLog)
        public long lanternActiveMs, lanternRangeMs, lanternNearMs;
        public int[] amuletSaves = new int[0];   // when your amulet saved you, ms after entering (AmuletSaves)
        public List<MvPhase> mvPhases = new ArrayList<>();   // Moonlight Village: its boss phases in order (MoonlightLog)

        public int hits() {
            return hitTime == null ? 0 : hitTime.length;
        }

        public long duration() {   // a visit in progress counts up to now
            return durationMs > 0 ? durationMs : System.currentTimeMillis() - start;
        }
    }

    /**
     * One Moonlight Village phase: a dance (damage) or a lantern phase of one boss, named after its song. Times are
     * ms after entering; lanternMs is how long the lantern was there, rangeMs / nearMs how long of that you were
     * within 3.5 / 1.75 tiles of it; flames are the spirits the game sends when the phase ends (its score).
     */
    public static final class MvPhase {
        public int boss;   // the boss's object type
        public String title = "";
        public long startMs, durationMs;
        public boolean lantern;
        public long lanternMs, rangeMs, nearMs;
        public int flames;

        public long duration(long now, Visit v) {   // a phase in progress counts up to now
            return durationMs > 0 ? durationMs : Math.max(0, now - v.start - startMs);
        }
    }

    private static final Path FILE = Path.of("dungeon_visits.json");
    private static final int MAX_VISITS = 500;
    private static final long LINK_SLACK_MS = 60_000;   // a run's start may be a little before the visit's
    private static final long EXALT_LATE_MS = 120_000;   // progress reported just after leaving still counts
    private static final long EXALT_ENTRY_MS = 90_000;   // ... also as the next map loads, within this time
    private static final long HITS_INTERVAL_MS = 1000;
    private static final int MAX_HITS = 5000;   // per visit
    private long lastHits;
    // maps that are never dungeons (display names as the game sends them; {s.rotmg} is the realm)
    private static final Set<String> NOT_DUNGEONS = Set.of("{s.vault}", "Daily Quest Room", "Pet Yard", "{s.guildhall}",
            "{s.nexus}", "Grand Bazaar", "{s.rotmg}");
    private static final Gson GSON = new Gson();
    private static final DungeonVisits INSTANCE = new DungeonVisits();

    private final List<Visit> visits = new ArrayList<>();   // finished, newest first
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<Runnable> hitListeners = new CopyOnWriteArrayList<>();
    private volatile Visit current;

    public static DungeonVisits get() {
        return INSTANCE;
    }

    private DungeonVisits() {
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                List<Visit> loaded = GSON.fromJson(r, new TypeToken<ArrayList<Visit>>() {
                }.getType());
                if (loaded != null) visits.addAll(loaded);
            } catch (Exception e) {
                System.err.println("Could not read " + FILE + ": " + e.getMessage());
            }
        } else {
            // first start: the runs logged so far, so the list does not begin empty
            for (DpsHistory.Run run : DpsHistory.get().runs()) {
                if (run.guildHall) continue;
                Visit v = new Visit();
                v.dungeon = run.dungeon;
                v.start = run.start;
                v.durationMs = Math.max(1, run.durationMs);
                v.runStart = run.start;
                visits.add(v);
            }
            save();
        }
    }

    /** Whether a map is a dungeon: one with a portal (or a dungeon statistic) that is not the realm or a hub. */
    public static boolean isDungeon(MapInfoPacket map) {
        if (map == null || NOT_DUNGEONS.contains(map.displayName) || "Realm of the Mad God".equals(map.name)) return false;
        return tomato.realmshark.ParseDungeon.getPortalId(map.name) != -1
                || tomato.realmshark.enums.CharacterStatistics.statByName(map.name) != null;
    }

    /**
     * Called on every map change, after the DPS Logger has saved the run of the map left: ends the visit in
     * progress (linking it to that run if you fought a boss) and starts one if the new map is a dungeon.
     */
    public void entered(MapInfoPacket map) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            finishCurrent(now);
            if (isDungeon(map)) {
                Visit v = new Visit();
                v.dungeon = map.name;
                v.start = now;
                current = v;
                AppLog.log("game", "Dungeon visit started: " + map.name);
            }
        }
        fire();
    }

    /**
     * The app is closing: the dungeon you are in ends here and is saved, so it is not lost (it was only in memory)
     * and can still be synced to your other clients.
     */
    public void close() {
        boolean ended;
        synchronized (this) {
            ended = current != null;
            if (ended) AppLog.log("game", "Dungeon visit ended by closing the app: " + current.dungeon);
            finishCurrent(System.currentTimeMillis());
        }
        if (ended) fire();
    }

    /** Ends the visit in progress (if any): into the list, linked to its run if you fought a boss, and saved. */
    private void finishCurrent(long now) {
        Visit left = current;
        current = null;
        if (left == null) return;
        left.durationMs = Math.max(1, now - left.start);
        DpsHistory.Run run = DpsHistory.get().latest();
        if (run != null && !run.guildHall && run.dungeon.equals(left.dungeon) && run.start >= left.start - LINK_SLACK_MS) {
            left.runStart = run.start;
        }
        visits.add(0, left);
        while (visits.size() > MAX_VISITS) visits.remove(visits.size() - 1);
        save();
    }

    /**
     * The game reported new exaltation progress for a class (the values before and after, in ExaltStore's order).
     * The game sends it right after a dungeon is left, i.e. while the next map loads: within the first
     * EXALT_ENTRY_MS of a map entered straight after a dungeon, it belongs to that dungeon (e.g. Fungal Cavern's
     * arrives as Crystal Cavern loads); later in a dungeon, to that one; outside dungeons, to the one left at most
     * EXALT_LATE_MS ago.
     */
    public void exaltProgress(int classType, int[] before, int[] after) {
        if (before == null || after == null) return;
        long now = System.currentTimeMillis();
        synchronized (this) {
            Visit last = visits.isEmpty() ? null : visits.get(0);
            long sinceLeft = last == null ? Long.MAX_VALUE : now - (last.start + last.durationMs);
            Visit v;
            if (current != null) {
                boolean justEntered = now - current.start < EXALT_ENTRY_MS;
                boolean straightFromDungeon = last != null && current.start - (last.start + last.durationMs) < EXALT_ENTRY_MS;
                v = justEntered && straightFromDungeon ? last : current;
            } else {
                v = sinceLeft < EXALT_LATE_MS ? last : null;
            }
            AppLog.log("game", "Exaltation update: class " + classType + " " + java.util.Arrays.toString(before) + " -> "
                    + java.util.Arrays.toString(after) + "; in " + (current == null ? "no dungeon" : current.dungeon + " for "
                    + (now - current.start) / 1000 + "s") + (last == null ? "" : "; left " + last.dungeon + " " + sinceLeft / 1000
                    + "s ago") + " -> credited to " + (v == null ? "nothing" : v.dungeon));
            if (v == null) return;
            if (v.exalts == null) v.exalts = new ArrayList<>();
            for (int i = 0; i < Math.min(before.length, after.length); i++) {
                if (after[i] <= before[i]) continue;
                Exalt e = new Exalt();
                e.classType = classType;
                e.stat = i;
                e.before = before[i];
                e.after = after[i];
                v.exalts.add(e);
                AppLog.log("game", "Exaltation progress in " + v.dungeon + ": class " + classType + " stat " + i
                        + " " + before[i] + " -> " + after[i]);
            }
            if (v != current) save();
        }
        fire();
    }

    /**
     * Copies the hits you took, and the damage you dealt per second, into the visit in progress: once a second from the game tick, and once more right
     * before a map change (force), while the fight data is still there. Called on the packet thread.
     */
    public void hits(tomato.backend.data.TomatoData data, boolean force) {
        Visit v = current;
        long now = System.currentTimeMillis();
        tomato.backend.data.Entity player = data == null ? null : data.player;
        if (v == null || player == null || (!force && now - lastHits < HITS_INTERVAL_MS)) return;
        lastHits = now;
        List<tomato.backend.data.Damage> list = player.getDamageList();
        int n = 0;
        int[] time = new int[Math.min(list.size(), MAX_HITS)], dmg = new int[time.length], enemy = new int[time.length];
        for (tomato.backend.data.Damage d : list) {
            if (n >= time.length) break;
            if (d == null || d.time < v.start || d.damage <= 0) continue;
            time[n] = (int) (d.time - v.start);
            dmg[n] = d.damage;
            enemy[n] = d.owner != null ? d.owner.objectType : d.sourceType;   // area attacks: the object behind them
            n++;
        }
        boolean changed = n != v.hits();
        v.hitTime = java.util.Arrays.copyOf(time, n);
        v.hitDamage = java.util.Arrays.copyOf(dmg, n);
        v.hitEnemy = java.util.Arrays.copyOf(enemy, n);
        int max = player.maxHp();
        if (max > 0) v.maxHp = max;
        // your damage to every enemy hit in this map, per second
        int[] dealt = new int[(int) Math.max(1, (now - v.start) / 1000 + 1)];
        boolean any = false;
        for (tomato.backend.data.Entity e : data.getEntityHitList()) {
            if (e == null) continue;
            for (tomato.backend.data.Damage d : e.getDamageList()) {
                if (d == null || d.owner == null || !d.owner.isUser() || d.time < v.start || d.damage <= 0) continue;
                int s = (int) ((d.time - v.start) / 1000);
                if (s >= dealt.length) dealt = java.util.Arrays.copyOf(dealt, s + 1);
                dealt[s] += d.damage;
                any = true;
            }
        }
        if (any || v.dealt == null) {
            changed |= !java.util.Arrays.equals(dealt, v.dealt);
            v.dealt = dealt;
        }
        if (changed) for (Runnable r : hitListeners) r.run();
    }

    /** Runs (on the packet thread) when the visit in progress has new hits. */
    public void onHits(Runnable listener) {
        hitListeners.add(listener);
    }

    /** Your amulet saved you now: marked on the visit in progress (for its damage graph). */
    public void amuletSaved(long now) {
        Visit v = current;
        if (v == null) return;
        int[] old = v.amuletSaves == null ? new int[0] : v.amuletSaves;
        int[] n = java.util.Arrays.copyOf(old, old.length + 1);
        n[old.length] = (int) Math.max(0, now - v.start);
        v.amuletSaves = n;
        for (Runnable r : hitListeners) r.run();
    }

    /** The dungeon you are in now, or null. */
    public Visit current() {
        return current;
    }

    /** The finished visits, newest first. */
    public synchronized List<Visit> visits() {
        return new ArrayList<>(visits);
    }

    /**
     * The fame a series of {time, fame} points (or CharacterStore samples) shows at a time: the last value at or
     * before it, else the first one after it; null if there are none.
     */
    public static Long fameAt(List<long[]> points, long t) {
        Long at = null;
        for (long[] p : points) {
            if (p[0] <= t) at = p[1];
            else return at != null ? at : p[1];
        }
        return at;
    }

    /** The end of a visit: when you left it, or now for the one in progress. */
    public static long end(Visit v) {
        return v.start + v.duration();
    }

    /**
     * The fame you gained in a dungeon: the character whose fame went up between entering and leaving it (from
     * CharacterStore's fame samples), 0 if none did.
     */
    public static long fameGained(Visit v) {
        long best = 0, end = end(v);
        for (CharacterStore.Char c : CharacterStore.get().all()) {
            if (c.samples == null || c.samples.isEmpty()) continue;
            CharacterStore.Sample last = c.samples.get(c.samples.size() - 1), first = c.samples.get(0);
            if (last.t < v.start || first.t > end) continue;
            List<long[]> points = new ArrayList<>();
            for (CharacterStore.Sample s : c.samples) points.add(new long[]{s.t, s.fame});
            Long a = fameAt(points, v.start), b = fameAt(points, end);
            if (a != null && b != null) best = Math.max(best, b - a);
        }
        return best;
    }

    /** The run with your boss damage of a finished visit, or null if you fought no boss (or it was deleted). */
    public static DpsHistory.Run run(Visit v) {
        if (v.runStart == 0) return null;
        for (DpsHistory.Run r : DpsHistory.get().runs()) if (r.start == v.runStart) return r;
        return null;
    }

    public void clear() {
        synchronized (this) {
            visits.clear();
            save();
        }
        fire();
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    // --- sync: the visits with everything recorded in them (hits, damage dealt, lantern, amulet saves) -----------

    private static final int SYNC_LIMIT = 12 << 20;   // stay well under the server's 16 MB per upload

    @Override
    public String syncKind() {
        return "dungeon_visits";
    }

    @Override
    public String syncLabel() {
        return "Dungeon visits";
    }

    @Override
    public synchronized int syncCount() {
        return visits.size();
    }

    /** The finished visits; if that gets very large, the hit details of the oldest ones are left out. */
    @Override
    public synchronized JsonElement exportSync() {
        JsonElement all = GSON.toJsonTree(visits);
        if (all.toString().length() <= SYNC_LIMIT) return all;
        List<Visit> trimmed = new ArrayList<>();
        for (Visit v : visits) trimmed.add(GSON.fromJson(GSON.toJson(v), Visit.class));
        for (int i = trimmed.size() - 1; i >= 0 && GSON.toJson(trimmed).length() > SYNC_LIMIT; i--) {
            Visit v = trimmed.get(i);   // oldest first
            v.hitTime = v.hitDamage = v.hitEnemy = new int[0];
            v.dealt = new int[0];
        }
        return GSON.toJsonTree(trimmed);
    }

    /**
     * Visits only the other side has are added (a visit is identified by its start and dungeon); for one both have,
     * the one with more recorded (hits, damage dealt) is kept.
     */
    @Override
    public boolean mergeSync(JsonElement remote) {
        List<Visit> theirs = GSON.fromJson(remote, new TypeToken<ArrayList<Visit>>() {
        }.getType());
        if (theirs == null) return false;
        boolean changed = false;
        synchronized (this) {
            java.util.Map<String, Integer> index = new java.util.HashMap<>();
            for (int i = 0; i < visits.size(); i++) index.put(visits.get(i).start + "|" + visits.get(i).dungeon, i);
            for (Visit t : theirs) {
                Integer i = index.get(t.start + "|" + t.dungeon);
                if (i == null) {
                    visits.add(t);
                    changed = true;
                } else if (detail(t) > detail(visits.get(i))) {
                    visits.set(i, t);
                    changed = true;
                }
            }
            if (changed) {
                visits.sort((a, b) -> Long.compare(b.start, a.start));   // newest first
                while (visits.size() > MAX_VISITS) visits.remove(visits.size() - 1);
                save();
            }
        }
        if (changed) fire();
        return changed;
    }

    private static int detail(Visit v) {
        return v.hits() + (v.dealt == null ? 0 : v.dealt.length) + (v.exalts == null ? 0 : v.exalts.size())
                + (v.amuletSaves == null ? 0 : v.amuletSaves.length) + (v.runStart != 0 ? 1 : 0);
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    private void save() {
        try {
            Files.writeString(FILE, GSON.toJson(visits));
        } catch (Exception e) {
            System.err.println("Could not write " + FILE + ": " + e.getMessage());
        }
    }
}
