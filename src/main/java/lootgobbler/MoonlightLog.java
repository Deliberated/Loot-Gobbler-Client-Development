package lootgobbler;

import assets.IdToAsset;
import packets.data.ObjectData;
import packets.data.ObjectStatusData;
import packets.data.StatData;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Groundwork for Moonlight Village statistics. While you are in the dungeon it writes a research log
 * (logs/MoonlightVillage-<date>.log) of what may carry its mechanics, so they can be worked out from real runs:
 * <ul>
 * <li>every dungeon object ("MV ...", the flames, the exaltation banners) appearing and disappearing, with its
 * position and values, and every change of those values (e.g. a lantern's life as the level progresses);</li>
 * <li>changes of your own values other than the everyday ones (life, mana, position...), in case flames are counted
 * on the player;</li>
 * <li>game notifications and texts (boss kills, flames, deaths).</li>
 * </ul>
 * It also measures the lantern (MV Lantern System, the one that moves around): how long it is there, and how long
 * you are within 1.75 and 3.5 tiles of it (the two rings the tutorial lantern draws), kept with the dungeon visit.
 * <p>
 * And it splits the boss fights into their phases (DungeonVisits.MvPhase), from what a logged run showed:
 * <ul>
 * <li>each phase starts with a progress bar named after its song (the tutorial's is skipped);</li>
 * <li>the boss whose condition turns active owns it (damage: 67108864, lantern: 16777216 alongside invulnerable);</li>
 * <li>it is a lantern phase if the lantern is there during it;</li>
 * <li>it ends when its flames (MV Total Counter, the score) appear, or when its boss turns idle.</li>
 * </ul>
 * Called on the packet thread.
 */
public final class MoonlightLog {
    public static final String DUNGEON = "Moonlight Village";
    public static final int LANTERN = 20454;   // MV Lantern System
    // the dancers and Umi: Sage Genji, Dancer Miko, Drummer Kaguya, Kitsune Umi (no BOSS label in the game data)
    private static final Set<Integer> BOSSES = Set.of(20450, 20451, 20452, 20493);
    public static final Set<Integer> DANCERS = Set.of(20450, 20451, 20452);   // the three before Umi
    public static final double NEAR = 1.75, RANGE = 3.5;   // tiles: the tutorial lantern's inner and outer ring
    private static final int FLAME = 20518;   // MV Total Counter: the flames a finished phase scores
    private static final int DANCING = 67108864, LANTERN_UP = 16777216;   // a boss's condition in its phases
    private static final long LATE_BOSS_MS = 2000;   // a boss may turn active just after its phase's bar
    private static final long TICK_MS = 1000, SAMPLE_MS = 5000;
    private static final MoonlightLog INSTANCE = new MoonlightLog();
    // your own values that change all the time and say nothing about the dungeon's mechanics
    private static final Set<String> EVERYDAY = Set.of("HP_STAT", "MP_STAT", "MAX_HP_STAT", "MAX_MP_STAT", "EXP_STAT",
            "CONDITION_STAT", "NEW_CON_STAT", "SIZE_STAT", "TEX1_STAT", "TEX2_STAT");

    private final Map<Integer, String> tracked = new HashMap<>();    // object id -> its name, for MV objects
    private final Map<String, String> values = new HashMap<>();      // "id:stat" -> last value written
    private final Set<String> unknownStats = new HashSet<>();
    private final Map<String, Integer> seen = new java.util.TreeMap<>();   // object name -> how often it appeared
    private final Set<Integer> flames = new HashSet<>();   // flame objects counted already
    // lanterns that are there now: the game state keeps removed objects around, so the old lantern would count on
    private final Set<Integer> lanterns = new HashSet<>();
    private final Map<Integer, Integer> types = new HashMap<>();   // object id -> type, for MV objects
    private boolean active;
    private long lastTick, lastSample;
    private DungeonVisits.MvPhase phase;   // the latest phase; open while phaseOpen
    private boolean phaseOpen;
    private int currentBoss;   // the boss that turned active last

    public static MoonlightLog get() {
        return INSTANCE;
    }

    private MoonlightLog() {
    }

    /** The Moonlight Village bosses, which the game data does not label as bosses. */
    public static boolean isBoss(int objectType) {
        return BOSSES.contains(objectType);
    }

    private static boolean inDungeon(TomatoData data) {
        return data != null && data.map != null && DUNGEON.equals(data.map.name);
    }

    /** A map change: closes the log of a visit that ended and starts one when entering the dungeon. */
    public synchronized void entered(String mapName) {
        if (active) {   // how often each object appeared: the spirits (flames) show up in pairs after each phase
            StringBuilder sb = new StringBuilder("SUMMARY objects seen:");
            seen.forEach((name, n) -> sb.append("  ").append(n).append("x ").append(name));
            if (!unknownStats.isEmpty()) sb.append("  | unknown stats: ").append(unknownStats);
            write(sb.toString());
            DungeonVisits.Visit v = DungeonVisits.get().current();
            if (v != null && v.lanternActiveMs > 0) {
                write(String.format(java.util.Locale.ROOT, "SUMMARY lantern active %ds, you within 3.5 tiles %ds (%.0f%%), within 1.75 tiles %ds (%.0f%%)",
                        v.lanternActiveMs / 1000, v.lanternRangeMs / 1000, 100.0 * v.lanternRangeMs / v.lanternActiveMs,
                        v.lanternNearMs / 1000, 100.0 * v.lanternNearMs / v.lanternActiveMs));
            }
            write("LEFT the dungeon");
        }
        active = DUNGEON.equals(mapName);
        seen.clear();
        unknownStats.clear();
        tracked.clear();
        values.clear();
        flames.clear();
        lanterns.clear();
        types.clear();
        phase = null;
        phaseOpen = false;
        currentBoss = 0;
        lastTick = lastSample = 0;
        if (active) write("=== ENTERED " + DUNGEON + " ===");
    }

    /** A new object in the dungeon: MV objects (bosses, lanterns, flames, helpers, loot) are logged and followed. */
    public synchronized void added(TomatoData data, ObjectData o) {
        if (!active || !inDungeon(data) || o == null || o.status == null) return;
        // the internal name ("MV Sage Genji"), not the display name ("Sage Genji"), to know it belongs to the dungeon
        String name = IdToAsset.getObjectIdName(o.objectType);
        String key = name == null ? "type " + o.objectType : name;
        if (!relevant(key)) return;
        tracked.put(o.status.objectId, key);
        types.put(o.status.objectId, o.objectType);
        if (o.objectType == LANTERN) lanterns.add(o.status.objectId);
        seen.merge(key, 1, Integer::sum);
        if (o.objectType == FLAME && phase != null && flames.add(o.status.objectId)) {   // the phase's score
            phase.flames++;
            endPhase();
        }
        write("ADD    " + label(o.objectType, key, o.status.objectId) + " at " + pos(o.status) + "  " + stats(o.status));
    }

    /** An object left (killed, picked up, out of sight). */
    public synchronized void removed(TomatoData data, int objectId) {
        if (!active) return;
        lanterns.remove(objectId);
        String name = tracked.remove(objectId);
        if (name != null) write("REMOVE " + name + " #" + objectId);
    }

    /** Changed values of an object (or of you): written for the followed objects and your unusual values. */
    public synchronized void changed(TomatoData data, ObjectStatusData s) {
        if (!active || s == null || s.stats == null || !inDungeon(data)) return;
        boolean me = data.player != null && data.player.id == s.objectId;
        String name = me ? "YOU" : tracked.get(s.objectId);
        if (name == null) return;
        StringBuilder sb = new StringBuilder();
        for (StatData d : s.stats) {
            if (d == null) continue;
            String stat = d.statType != null ? d.statType.name() : "stat" + d.statTypeNum;
            if (me && EVERYDAY.contains(stat)) continue;
            String v = d.stringStatValue != null && !d.stringStatValue.isEmpty() ? '"' + d.stringStatValue + '"' : String.valueOf(d.statValue);
            String k = s.objectId + ":" + stat;
            if (v.equals(values.put(k, v))) continue;   // unchanged
            if (d.statType == null) unknownStats.add(stat);
            if ("CONDITION_STAT".equals(stat) && !me) bossCondition(types.getOrDefault(s.objectId, 0), d.statValue);
            sb.append(' ').append(stat).append('=').append(v);
        }
        if (sb.length() > 0) write("CHANGE " + name + " #" + s.objectId + sb);
    }

    /** A notification or text from the game while in the dungeon (boss kills, flames, deaths...). */
    public synchronized void message(TomatoData data, String kind, Object packet) {
        if (!active || !inDungeon(data) || packet == null) return;
        write(kind.toUpperCase() + " " + packet.toString().replace('\n', ' '));
        if (packet instanceof packets.incoming.NotificationPacket n
                && n.effect == packets.data.enums.NotificationEffectType.ProgressBar && n.extra == 42) {
            startPhase(n.message);
        }
    }

    /** A named progress bar: a new phase of the active boss (not the tutorial's). */
    private void startPhase(String title) {
        DungeonVisits.Visit v = DungeonVisits.get().current();
        if (v == null || title == null || title.contains("Tutorial")) return;
        endPhase();
        phase = new DungeonVisits.MvPhase();
        phase.boss = currentBoss;
        phase.title = title;
        phase.startMs = System.currentTimeMillis() - v.start;
        // a new list rather than adding to it: the window reads it at the same time
        java.util.List<DungeonVisits.MvPhase> phases = v.mvPhases == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(v.mvPhases);
        phases.add(phase);
        v.mvPhases = phases;
        phaseOpen = true;
        write("PHASE " + v.mvPhases.size() + " started: " + title);
    }

    private void endPhase() {
        DungeonVisits.Visit v = DungeonVisits.get().current();
        if (!phaseOpen || v == null) return;
        phaseOpen = false;
        phase.durationMs = Math.max(1, System.currentTimeMillis() - v.start - phase.startMs);
        write(String.format(java.util.Locale.ROOT, "PHASE ended: %s (%s) %.1fs%s", phase.title, phase.lantern ? "lantern" : "damage",
                phase.durationMs / 1000.0, phase.lanternMs > 0 ? ", in range " + 100 * phase.rangeMs / phase.lanternMs + "%" : ""));
    }

    /**
     * A boss's condition changed: dancing or at the lantern makes it the active boss (also of a phase whose bar came
     * a moment before); idle again ends its phase.
     */
    private void bossCondition(int type, int condition) {
        if (!BOSSES.contains(type)) return;
        if ((condition & (DANCING | LANTERN_UP)) != 0) {
            currentBoss = type;
            DungeonVisits.Visit v = DungeonVisits.get().current();
            if (phaseOpen && phase.boss != type && v != null
                    && System.currentTimeMillis() - v.start - phase.startMs <= LATE_BOSS_MS) {
                phase.boss = type;
            }
            if ((condition & LANTERN_UP) != 0 && phaseOpen && phase.boss == type) phase.lantern = true;
        } else if (phaseOpen && phase.boss == type) {
            endPhase();
        }
    }

    /**
     * Once a second: the lantern's presence and your distance to it, added up on the visit in progress; every five
     * seconds the distance is also written, so the "sitting in the lantern" radius can be checked against runs.
     */
    public synchronized void tick(TomatoData data) {
        long now = System.currentTimeMillis();
        if (!active || !inDungeon(data) || data.player == null || data.player.pos == null) return;
        long dt = lastTick == 0 ? 0 : Math.min(5000, now - lastTick);
        if (lastTick != 0 && dt < TICK_MS) return;
        lastTick = now;
        Entity lantern = null;
        double best = Double.MAX_VALUE;
        for (Entity e : data.entityList.values()) {
            if (e == null || e.objectType != LANTERN || e.pos == null || !lanterns.contains(e.id)) continue;
            double d = Math.sqrt(data.player.distSqrd(e.pos));
            if (d < best) {
                best = d;
                lantern = e;
            }
        }
        DungeonVisits.Visit v = DungeonVisits.get().current();
        if (lantern == null || v == null || dt == 0) return;
        v.lanternActiveMs += dt;
        if (best <= RANGE) v.lanternRangeMs += dt;
        if (best <= NEAR) v.lanternNearMs += dt;
        if (phaseOpen) {   // the same for the phase in progress, which makes it a lantern phase
            phase.lantern = true;
            phase.lanternMs += dt;
            if (best <= RANGE) phase.rangeMs += dt;
            if (best <= NEAR) phase.nearMs += dt;
        }
        if (now - lastSample >= SAMPLE_MS) {
            lastSample = now;
            write(String.format(java.util.Locale.ROOT, "LANTERN #%d at %s, you %.2f tiles away (%s)  active %ds, in 3.5: %ds, in 1.75: %ds",
                    lantern.id, String.format(java.util.Locale.ROOT, "%.1f/%.1f", lantern.pos.x, lantern.pos.y), best,
                    best <= NEAR ? "near" : best <= RANGE ? "in range" : "outside",
                    v.lanternActiveMs / 1000, v.lanternRangeMs / 1000, v.lanternNearMs / 1000));
        }
    }

    /** Dungeon objects worth following: the MV ones, flames, Umi and the exaltation banners. */
    private static boolean relevant(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("mv ") || n.contains("flame") || n.contains("umi") || n.contains("lantern") || n.startsWith("moonlight");
    }

    private static String label(int type, String name, int id) {
        return name + " (type " + type + ") #" + id;
    }

    private static String pos(ObjectStatusData s) {
        return s.pos == null ? "?" : String.format(java.util.Locale.ROOT, "%.1f/%.1f", s.pos.x, s.pos.y);
    }

    private static String stats(ObjectStatusData s) {
        if (s.stats == null) return "";
        StringBuilder sb = new StringBuilder();
        for (StatData d : s.stats) {
            if (d == null) continue;
            String stat = d.statType != null ? d.statType.name() : "stat" + d.statTypeNum;
            String v = d.stringStatValue != null && !d.stringStatValue.isEmpty() ? '"' + d.stringStatValue + '"' : String.valueOf(d.statValue);
            sb.append(' ').append(stat).append('=').append(v);
        }
        return sb.toString().trim();
    }

    private void write(String line) {
        try {
            Path dir = Path.of("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("MoonlightVillage-" + LocalDate.now() + ".log");
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            Files.writeString(file, time + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignore) {
        }
    }
}
