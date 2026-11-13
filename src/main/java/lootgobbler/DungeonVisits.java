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

    /** A character's look in a visit: player name, class, skin (0 = the class's own) and dyes (see Dyes). */
    public static final class Look {
        public String player = "";
        public int classType, skin, tex1, tex2;
    }

    public static final class Visit {
        public String dungeon = "";
        public long start;
        public long durationMs;
        public long runStart;   // DpsHistory.Run.start of the run with your boss damage; 0 = no boss fought
        public boolean late;    // the sniffer started inside it: what happened before is missing
        public boolean hidden;  // removed by you: kept (and synced) but no longer shown
        public Boolean completed;   // only in what is synced: whether you completed it (see completed()), null if unknown
        public List<Exalt> exalts = new ArrayList<>();   // exaltation progress the game reported in this dungeon
        // every hit you took, oldest first: ms after entering, damage (after your defense), and the enemy's object
        // type (0 = no enemy, e.g. ground or an area effect); maxHp is your maximum life then
        public int[] hitTime = new int[0], hitDamage = new int[0], hitEnemy = new int[0];
        public int maxHp;
        public int[] dealt = new int[0];   // the damage you dealt to enemies, per second after entering
        // Moonlight Village: how long its lantern was there, and you within 3.5 / 1.75 tiles of it (MoonlightLog)
        public long lanternActiveMs, lanternRangeMs, lanternNearMs;
        public int[] amuletSaves = new int[0];   // when your amulet saved you, ms after entering (AmuletSaves)
        // the consumables you used from a quick slot: item type, and when (ms after entering)
        public int[] quickItem = new int[0], quickTime = new int[0];
        // your death in it: when (ms after entering) and what the game says killed you; -1 / null = you survived
        public int diedAt = -1;
        public String killedBy;
        public int[] moved = new int[0];   // how far you walked, in tenths of a tile, per second after entering
        public List<MvPhase> mvPhases = new ArrayList<>();   // Moonlight Village: its boss phases in order (MoonlightLog)
        // whether it was completed can be told (visits from before this was kept cannot), and the game's "Victory"
        public boolean judged, victory;
        // how your character looked in it (your guildmates' clients show you so): name, class, skin, dyes; null
        // in visits from before this was kept
        public Look look;

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
        /** Lantern phases: the song's beats (its progress bar's steps) you were within RANGE of the lantern. */
        public int beats;
        /** The song's length in beats (its progress bar's maximum), 0 if not known. */
        public int beatsMax;
        /** The most flames a lantern phase scores. */
        public static final int MAX_FLAMES = 8;

        /**
         * A lantern phase in progress: the flames earned so far, the beats you were in range as a share of the
         * song (all of it: 8). The game's own count replaces it when the phase ends.
         */
        public int flamesSoFar() {
            if (!lantern || beatsMax <= 0) return 0;
            return Math.min(MAX_FLAMES, beats * MAX_FLAMES / beatsMax);
        }

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
            boolean linked = linkLateRuns(), exalts = lateExalts();
            if (linked || exalts) save();
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

    /**
     * Visits joined late without a boss fight linked: the run of that dungeon that started during the visit, if one
     * is not linked to another visit (runs repaired by DpsHistory.repair start at their first boss hit). True if any
     * was linked.
     */
    private boolean linkLateRuns() {
        Set<Long> linked = new java.util.HashSet<>();
        for (Visit v : visits) if (v.runStart != 0) linked.add(v.runStart);
        boolean changed = false;
        for (Visit v : visits) {
            if (!v.late || v.runStart != 0) continue;
            for (DpsHistory.Run r : DpsHistory.get().allRuns()) {
                if (r.guildHall || !r.dungeon.equals(v.dungeon) || linked.contains(r.start)) continue;
                if (r.start < v.start - LINK_SLACK_MS || r.start > v.start + v.durationMs) continue;
                v.runStart = r.start;
                linked.add(r.start);
                changed = true;
                break;
            }
        }
        return changed;
    }

    /**
     * Visits joined late without exaltations (up to v0.7.16 their progress was missed): the progress ExaltStore saw
     * go up as the visit ended (the reconnect on leaving brings it), unless a visit already has it. True if any was
     * added.
     */
    private boolean lateExalts() {
        List<ExaltStore.Change> changes = ExaltStore.get().recentChanges();
        if (changes.isEmpty()) return false;
        Set<String> credited = new java.util.HashSet<>();
        for (Visit v : visits) {
            if (v.exalts != null) for (Exalt e : v.exalts) credited.add(e.classType + ":" + e.stat + ":" + e.before + ":" + e.after);
        }
        boolean changed = false;
        for (Visit v : visits) {
            if (!v.late || (v.exalts != null && !v.exalts.isEmpty())) continue;
            long end = v.start + v.durationMs;
            for (ExaltStore.Change c : changes) {
                if (c.t < end - EXALT_EARLY_MS || c.t > end + EXALT_ENTRY_MS) continue;
                if (!credited.add(c.classType + ":" + c.stat + ":" + c.before + ":" + c.after)) continue;
                Exalt e = new Exalt();
                e.classType = c.classType;
                e.stat = c.stat;
                e.before = c.before;
                e.after = c.after;
                if (v.exalts == null) v.exalts = new ArrayList<>();
                v.exalts.add(e);
                changed = true;
                AppLog.log("game", "Exaltation progress recovered for " + v.dungeon + ": class " + c.classType + " stat "
                        + c.stat + " " + c.before + " -> " + c.after);
            }
        }
        return changed;
    }

    private static final long EXALT_EARLY_MS = 5_000;   // the reconnect's data may come just before the visit ends

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
    /**
     * The sniffer started inside a dungeon, recognised by its enemies (the game only names it on entering): its visit
     * starts now, marked as joined late (what happened before is not in it).
     */
    public void enteredLate(MapInfoPacket map) {
        entered(map);
        Visit v = current;
        if (v != null) {
            v.late = true;
            AppLog.log("game", "Dungeon visit joined late: " + map.name);
            fire();
        }
    }

    public void entered(MapInfoPacket map) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            finishCurrent(now);
            if (isDungeon(map)) {
                Visit v = new Visit();
                v.dungeon = map.name;
                v.start = now;
                v.judged = true;
                current = v;
                lastX = Float.NaN;   // the way walked starts anew
                unsaved = 0;
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

    /** Your character's look now (CharacterStore keeps it, not while transformed), or null before you played. */
    public static Look lookNow() {
        CharacterStore.Char c = CharacterStore.get().playing();
        if (c == null) c = CharacterStore.get().lastPlayedChar();   // started mid-map: the game did not say yet
        String name = CharacterStore.get().playerName();
        if (c == null || name == null || c.classType <= 0) return null;
        Look l = new Look();
        l.player = name;
        l.classType = c.classType;
        l.skin = c.skin;
        l.tex1 = c.tex1;
        l.tex2 = c.tex2;
        return l;
    }

    /** Ends the visit in progress (if any): into the list, linked to its run if you fought a boss, and saved. */
    private void finishCurrent(long now) {
        Visit left = current;
        current = null;
        if (left == null) return;
        left.durationMs = Math.max(1, now - left.start);
        left.look = lookNow();
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
        if (max <= 0 && v.maxHp <= 0) {   // started mid-map: the game only sends your life when it changes
            CharacterStore.Char c = CharacterStore.get().playing();
            if (c == null) c = CharacterStore.get().lastPlayedChar();
            if (c != null) max = c.maxHp;
        }
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

    /**
     * You used an item (the use packet, on the packet thread): one in a quick slot, used up when used, counts for the
     * visit in progress. A quick slot's item is told by the slot stats; the same item used from an inventory slot
     * (where the packet's slot holds it) does not count. Reusable quick-slot gear (Coral Juice Refinery...) counts every use.
     */
    private static final int QUICK_SLOT_ID = 1_000_000;

    public void itemUsed(tomato.backend.data.TomatoData data, packets.outgoing.UseItemPacket u) {
        tomato.backend.data.Entity player = data == null ? null : data.player;
        if (current == null || u == null || u.slotObject == null) return;
        int type = u.slotObject.objectType, slot = u.slotObject.slotId;
        // the game sends a quick slot's use with slot 1000000 + its index (seen November 2026)
        boolean quickSlotId = slot >= QUICK_SLOT_ID && slot < QUICK_SLOT_ID + LootEvents.QUICKSLOTS.length;
        if (!quickSlotId && (player == null || u.slotObject.objectId != player.id)) return;
        boolean quick = quickSlotId;
        if (player != null) {
            for (packets.data.enums.StatType q : LootEvents.QUICKSLOTS) {
                packets.data.StatData sd = player.stat.get(q);
                if (sd != null && sd.statValue == type) quick = true;
            }
        }
        if (!quick || type <= 0) {
            if (quickSlotId) AppLog.log("game", "Quick slot use not counted: item " + type + " (slot " + slot + ")");
            return;
        }
        if (player != null && slot >= 0 && slot < 28) {
            int stat = slot >= 12 ? packets.data.enums.StatType.BACKPACK_0_STAT.get() + (slot - 12)
                    : packets.data.enums.StatType.INVENTORY_0_STAT.get() + slot;
            packets.data.StatData sd = player.stat.get(stat);
            if (sd != null && sd.statValue == type) return;   // the inventory's copy
        }
        ItemDb.Item item = ItemDb.get().item(type);
        synchronized (this) {
            Visit v = current;
            if (v == null) return;
            int n = v.quickItem == null ? 0 : v.quickItem.length;
            v.quickItem = java.util.Arrays.copyOf(v.quickItem == null ? new int[0] : v.quickItem, n + 1);
            v.quickTime = java.util.Arrays.copyOf(v.quickTime == null ? new int[0] : v.quickTime, n + 1);
            v.quickItem[n] = type;
            v.quickTime[n] = (int) (System.currentTimeMillis() - v.start);
            AppLog.log("game", "Quick slot used in " + v.dungeon + ": " + (item == null ? type : item.name) + " (slot " + slot + ")");
        }
    }

    /**
     * You died (the game's death packet, packet thread): the hits up to now are copied into the visit in progress
     * first, then the moment and the killer are kept, so the visit shows the hits that led to it (see recap).
     */
    public void died(tomato.backend.data.TomatoData data, packets.incoming.DeathPacket p) {
        if (p == null) return;
        hits(data, true);
        synchronized (this) {
            Visit v = current;
            if (v == null) {
                AppLog.log("game", "Died outside a dungeon, killed by " + p.killedBy);
                return;
            }
            v.diedAt = (int) (System.currentTimeMillis() - v.start);
            v.killedBy = p.killedBy;
            AppLog.log("game", "Died in " + v.dungeon + " after " + v.diedAt / 1000 + "s, killed by " + p.killedBy
                    + "; " + recap(v, RECAP_MS).size() + " hits in the last " + RECAP_MS / 1000 + "s");
        }
        for (Runnable r : hitListeners) r.run();
    }

    /**
     * What a hit's enemy entry stands for: an object type (above 0), a damaging ground tile (its tile type,
     * negated) or unknown (0: an area effect without source, or ground damage saved before v0.7.17).
     */
    public static String hitSource(int enemy) {
        if (enemy > 0) return assets.IdToAsset.objectName(enemy);
        if (enemy < 0) {
            String tile = assets.IdToAsset.tileName(-enemy);
            return tile == null || tile.isBlank() ? "Damaging ground" : "Ground: " + tile;
        }
        return "Ground or area effect";
    }

    /** How far back the hits that led to a death go. */
    public static final int RECAP_MS = 10_000;

    /**
     * The hits you took within windowMs before your death in the visit, oldest first, as {ms before the death,
     * damage, enemy type}; empty if you did not die in it.
     */
    public static List<int[]> recap(Visit v, int windowMs) {
        List<int[]> out = new ArrayList<>();
        if (v == null || v.diedAt < 0 || v.hitTime == null) return out;
        for (int i = 0; i < v.hits(); i++) {
            int before = v.diedAt - v.hitTime[i];
            if (before >= 0 && before <= windowMs) out.add(new int[]{before, v.hitDamage[i], v.hitEnemy[i]});
        }
        return out;
    }

    private float lastX = Float.NaN, lastY;   // your last position seen in this visit
    private double unsaved;                    // tiles walked not yet added to the visit (less than a tenth)

    /**
     * Your positions from a move packet (packet thread): the way walked between them is added to the visit in
     * progress, per second. A jump of more than MAX_STEP tiles is a teleport, not walked.
     */
    public void moved(packets.data.WorldPosData[] positions) {
        if (positions == null) return;
        synchronized (this) {
            Visit v = current;
            if (v == null) return;
            for (packets.data.WorldPosData p : positions) {
                if (p == null) continue;
                if (!Float.isNaN(lastX)) {
                    double d = Math.hypot(p.x - lastX, p.y - lastY);
                    if (d <= MAX_STEP) unsaved += d;
                }
                lastX = p.x;
                lastY = p.y;
            }
            int tenths = (int) (unsaved * 10);
            if (tenths <= 0) return;
            unsaved -= tenths / 10.0;
            int s = (int) Math.max(0, (System.currentTimeMillis() - v.start) / 1000);
            if (v.moved == null) v.moved = new int[0];
            if (s >= v.moved.length) v.moved = java.util.Arrays.copyOf(v.moved, s + 1);
            v.moved[s] += tenths;
        }
    }

    private static final double MAX_STEP = 2.5;

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

    /** Amulet marks no save stands behind (e.g. one whose save got lost) are dropped from the finished visits. */
    public void keepAmuletSaves(java.util.function.LongPredicate isSave) {
        boolean changed = false;
        synchronized (this) {
            for (Visit v : visits) {
                if (v.amuletSaves == null || v.amuletSaves.length == 0) continue;
                int[] kept = java.util.Arrays.stream(v.amuletSaves).filter(ms -> isSave.test(v.start + ms)).toArray();
                if (kept.length == v.amuletSaves.length) continue;
                v.amuletSaves = kept;
                changed = true;
            }
            if (changed) save();
        }
        if (changed) fire();
    }

    /** An amulet save at this time turned out not to be one: its mark goes from the visit it happened in. */
    public void amuletSaveRemoved(long t) {
        boolean changed = false;
        List<Visit> all;
        synchronized (this) {
            all = new ArrayList<>(visits);
        }
        if (current != null) all.add(current);
        for (Visit v : all) {
            if (v.amuletSaves == null || t < v.start || t > end(v) + 5000) continue;
            int[] kept = java.util.Arrays.stream(v.amuletSaves).filter(ms -> Math.abs(v.start + ms - t) > 3000).toArray();
            if (kept.length == v.amuletSaves.length) continue;
            v.amuletSaves = kept;
            changed = true;
        }
        if (!changed) return;
        synchronized (this) {
            save();
        }
        fire();
    }

    /** A game notification: its "Victory" (shown when a dungeon is done, e.g. Moonlight Village) marks the visit. */
    public void notification(packets.incoming.NotificationPacket p) {
        Visit v = current;
        if (v != null && p != null && p.effect == packets.data.enums.NotificationEffectType.Victory && !v.victory) {
            v.victory = true;
            AppLog.log("game", "Dungeon completed (Victory): " + v.dungeon);
        }
    }

    /**
     * Whether you completed a finished visit: the game said "Victory", or the run's main boss was left at 8% of its
     * life or less (it died; when you leave a boss alive it keeps most of its life). null when it cannot be told.
     * <p>
     * Older visits did not keep the boss's last life; for them the damage everyone did to it stands in: at 88% of its
     * life or more it died (finished runs come to 90-180%, others' heals and phases included). Only in groups of up to
     * 15, where your client sees nearly all of the others' damage (in Oryx's Chamber with 25 it saw a fifth), and not
     * for Moonlight Village, whose bosses never lose their life.
     */
    public static Boolean completed(Visit v) {
        if (v == null) return null;
        if (v.victory) return true;
        DpsHistory.Run run = run(v);
        DpsHistory.Boss main = run == null ? null : run.mainBoss();
        if (main == null) return v.runStart == 0 ? false : null;   // no boss fought; null: its run is gone from the history
        if (main.maxHp <= 0) return null;
        if (main.endHp >= 0) return main.endHp <= main.maxHp * 0.08;
        if (MoonlightLog.DUNGEON.equals(v.dungeon) || main.players.size() > 15) return null;
        long damage = 0;
        for (DpsHistory.PlayerRow p : main.players) damage += p.damage;
        return damage >= main.maxHp * 0.88;
    }

    /** The dungeon you are in now, or null. */
    public Visit current() {
        return current;
    }

    /** The finished visits you have not removed, newest first. */
    public synchronized List<Visit> visits() {
        List<Visit> shown = new ArrayList<>();
        for (Visit v : visits) if (!v.hidden) shown.add(v);
        return shown;
    }

    /** Every finished visit, the removed ones too, newest first. */
    public synchronized List<Visit> allVisits() {
        return new ArrayList<>(visits);
    }

    /** Removes a finished visit from what is shown; it is only marked (and the mark synced), not deleted. */
    public void remove(Visit v) {
        synchronized (this) {
            if (v.hidden || !visits.contains(v)) return;
            v.hidden = true;
            save();
        }
        fire();
        ServerSync.get().uploadSoon();
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

    /** How old a sample from before a visit may be to still count as the fame you entered with. */
    private static final long ENTRY_SAMPLE_MS = 15_000;

    /**
     * The fame gained between start and end in a series of {time, fame} points, or null if they do not show it. It
     * starts from the last point before start only if that was taken just before (samples come every few seconds
     * while fame changes); otherwise from the first point inside. Fame that went up while nothing was sampled (e.g.
     * a dungeon without samples just before) shows up in the next sample and must not count for this one.
     */
    public static Long fameIn(List<long[]> points, long start, long end) {
        Long a = null, b = null;
        for (long[] p : points) {   // in time order
            if (p[0] <= start) {
                a = start - p[0] <= ENTRY_SAMPLE_MS ? p[1] : null;
            } else if (p[0] <= end) {
                if (a == null) a = p[1];
                b = p[1];
            } else {
                break;
            }
        }
        if (a == null) return null;
        return b == null ? 0L : Math.max(0, b - a);
    }

    /** The end of a visit: when you left it, or now for the one in progress. */
    public static long end(Visit v) {
        return v.start + v.duration();
    }

    /**
     * The fame you gained in a dungeon: the character whose fame went up between entering and leaving it (from
     * CharacterStore's fame samples, see fameIn), 0 if none did.
     */
    public static long fameGained(Visit v) {
        long best = 0, end = end(v);
        for (CharacterStore.Char c : CharacterStore.get().all()) {
            if (c.samples == null || c.samples.isEmpty()) continue;
            CharacterStore.Sample last = c.samples.get(c.samples.size() - 1), first = c.samples.get(0);
            if (last.t < v.start || first.t > end) continue;
            List<long[]> points = new ArrayList<>();
            for (CharacterStore.Sample s : c.samples) points.add(new long[]{s.t, s.fame});
            Long gained = fameIn(points, v.start, end);
            if (gained != null) best = Math.max(best, gained);
        }
        return best;
    }

    /** The run with your boss damage of a finished visit, or null if you fought no boss (or it was deleted). */
    public static DpsHistory.Run run(Visit v) {
        if (v.runStart == 0) return null;
        for (DpsHistory.Run r : DpsHistory.get().allRuns()) if (r.start == v.runStart) return r;
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
    public JsonElement exportSync() {
        List<Visit> trimmed = new ArrayList<>();
        synchronized (this) {
            for (Visit v : visits) trimmed.add(GSON.fromJson(GSON.toJson(v), Visit.class));
        }
        // the guild counts completed runs: judged here, where the run's boss is known (outside the lock: it asks
        // the DPS history)
        for (Visit v : trimmed) v.completed = completed(v);
        JsonElement all = GSON.toJsonTree(trimmed);
        if (all.toString().length() <= SYNC_LIMIT) return all;
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
                if (t.amuletSaves != null) {   // no marks of saves that turned out not to be ones
                    t.amuletSaves = java.util.Arrays.stream(t.amuletSaves)
                            .filter(ms -> !AmuletSaves.get().removedAt(t.start + ms)).toArray();
                }
                Integer i = index.get(t.start + "|" + t.dungeon);
                if (i == null) {
                    visits.add(t);
                    changed = true;
                } else {
                    Visit mine = visits.get(i);
                    boolean hidden = mine.hidden || t.hidden;   // removed on either side stays removed
                    if (detail(t) > detail(mine)) {
                        t.hidden = hidden;
                        visits.set(i, t);
                        changed = true;
                    } else if (hidden && !mine.hidden) {
                        mine.hidden = true;
                        changed = true;
                    }
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
                + (v.amuletSaves == null ? 0 : v.amuletSaves.length) + (v.runStart != 0 ? 1 : 0)
                + (v.quickItem == null ? 0 : v.quickItem.length) + (v.moved == null ? 0 : v.moved.length) + (v.diedAt >= 0 ? 1 : 0);
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
