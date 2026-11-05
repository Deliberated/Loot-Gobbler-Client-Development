package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import tomato.backend.data.TomatoData;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The times your Amulet of Restoration saved you. The game data says when it fires: on a hit (of more than 2
 * damage) taken below 20% of your life it gives you Invulnerable for 2 seconds and heals 120, at most once every 30
 * minutes. So a save is the game putting Invulnerable on you while you wear it, only if
 * <ul>
 * <li>you were hit just before,</li>
 * <li>your life was at 20% or below just before (or is now, without the heal), and</li>
 * <li>the amulet's last save is at least 30 minutes ago.</li>
 * </ul>
 * Invulnerable from anything else (a priest's or paladin's buff, a dungeon's shield) misses at least one of these.
 * Game notifications about your character and system texts that mention the amulet count too. Each save is kept with
 * its time, character, dungeon and message (amulet_saves.json, synced to the server), and on the dungeon visit, so
 * the damage graph marks it. All game notifications are also written to the app log, so the game's wording can be
 * checked.
 */
public final class AmuletSaves implements Syncable {
    public static final class Save {
        public long t;
        public int charId;
        public String amulet = "";
        public String dungeon = "";
        public String message = "";
        public boolean removed;   // turned out not to be a save; kept so that syncing does not bring it back
    }

    private static final long RECENT_MS = 14L * 24 * 60 * 60 * 1000;   // "recently played": the last two weeks
    // the Amulet of Restoration's rule (equip.xml): a hit of more than 2 below 20% life, healing 120, every 1800 s
    private static final double LOW_LIFE = 0.2;
    private static final int HEAL = 120, MIN_HIT = 2;
    private static final long COOLDOWN_MS = 1800_000, LOOK_BACK_MS = 1500;
    // what the first version counted: any Invulnerable while wearing it, buffs included
    private static final String OLD_RULE = ": Invulnerable (";
    private boolean wasInvulnerable;
    private final java.util.ArrayDeque<long[]> life = new java.util.ArrayDeque<>();   // {time, hp, max hp}, the last moments

    private static final Path FILE = Path.of("amulet_saves.json");
    private static final Gson GSON = new Gson();
    private static final AmuletSaves INSTANCE = new AmuletSaves();
    private final List<Save> saves = new ArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static AmuletSaves get() {
        return INSTANCE;
    }

    private AmuletSaves() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            List<Save> loaded = GSON.fromJson(r, new TypeToken<ArrayList<Save>>() {
            }.getType());
            if (loaded != null) saves.addAll(loaded);
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
        dropOldRuleSaves();
    }

    /**
     * Saves the first version counted from Invulnerable alone (any buff did it) cannot be told apart from real ones
     * afterwards, so they are marked removed, here and on their dungeon visits.
     */
    private void dropOldRuleSaves() {
        boolean changed = false;
        for (Save s : saves) {
            if (s.removed || s.message == null || !s.message.contains(OLD_RULE)) continue;
            s.removed = true;
            DungeonVisits.get().amuletSaveRemoved(s.t);
            AppLog.log("game", "Amulet save of " + java.time.Instant.ofEpochMilli(s.t) + " removed: counted from Invulnerable alone");
            changed = true;
        }
        if (changed) save();
        // marks on dungeon visits that no save stands behind any more (only the first version made marks)
        DungeonVisits.get().keepAmuletSaves(t -> saves.stream().anyMatch(s -> !s.removed && Math.abs(s.t - t) <= 3000));
    }

    /** A game notification: logged; counted if it is about your character and mentions an amulet. */
    public void notification(TomatoData data, packets.incoming.NotificationPacket p) {
        if (p == null) return;
        String msg = p.message == null ? "" : p.message;
        boolean mine = data != null && data.player != null && (p.objectId == data.player.id || p.senderObjectId == data.player.id);
        if (!msg.isBlank()) AppLog.log("notify", p.effect + (mine ? " (you)" : "") + ": " + msg);
        if (mine && mentionsAmulet(msg)) record(data, msg);
    }

    /**
     * Every game tick: with an amulet that makes you invulnerable equipped, the game putting Invulnerable on your
     * character is the amulet firing (it is what the amulet does; nothing is worked out from your life here).
     */
    public void tick(TomatoData data) {
        if (data == null || data.player == null) return;
        long now = System.currentTimeMillis();
        packets.data.StatData ring = data.player.stat.get(packets.data.enums.StatType.INVENTORY_0_STAT.get() + 3);
        packets.data.StatData cond = data.player.stat.get(packets.data.enums.StatType.CONDITION_STAT);
        Amulets.Amulet a = ring == null ? null : Amulets.of(ring.statValue);
        boolean invulnerable = cond != null && (cond.statValue & packets.data.enums.ConditionBits.INVULNERABLE.value()) != 0;
        int hp = data.player.hp(), max = data.player.maxHp();
        if (a != null && a.invulnerable() && invulnerable && !wasInvulnerable && max > 0) {
            String why = notTheAmulet(data, now, hp, max);
            if (why == null) {
                record(data, String.format(Locale.ROOT, "%s: hit at %d%% life, Invulnerable", a.name(), lowestPercent(now, hp, max)));
            } else {
                AppLog.log("game", "Invulnerable while wearing " + a.name() + ", not counted: " + why);
            }
        }
        wasInvulnerable = invulnerable;
        // your life over the last moments, to see how low it was right before
        if (max > 0) life.addLast(new long[]{now, hp, max});
        while (!life.isEmpty() && now - life.peekFirst()[0] > LOOK_BACK_MS) life.removeFirst();
    }

    /** Why this Invulnerable is not the amulet firing, or null if it is. */
    private String notTheAmulet(TomatoData data, long now, int hp, int max) {
        synchronized (this) {
            for (int i = saves.size() - 1; i >= 0; i--) {
                Save s = saves.get(i);
                if (s.removed || s.charId != data.player.charId()) continue;
                if (now - s.t < COOLDOWN_MS) return "the amulet saved you " + (now - s.t) / 60_000 + " min ago (30 min cooldown)";
                break;
            }
        }
        boolean hit = false;
        for (tomato.backend.data.Damage d : data.player.getDamageList()) {
            if (d != null && d.damage > MIN_HIT && now - d.time <= LOOK_BACK_MS) {
                hit = true;
                break;
            }
        }
        if (!hit) return "no hit just before (a buff)";
        int pct = lowestPercent(now, hp, max);
        if (pct > Math.round(LOW_LIFE * 100)) return "life was " + pct + "%, not below 20%";
        return null;
    }

    /** Your lowest life in the last moments, in % (now counted without the amulet's heal). */
    private int lowestPercent(long now, int hp, int max) {
        double low = (double) Math.max(0, hp - HEAL) / max;
        for (long[] l : life) if (now - l[0] <= LOOK_BACK_MS) low = Math.min(low, (double) l[1] / l[2]);
        return (int) Math.round(low * 100);
    }

    /**
     * Whether amulet information is worth showing: a character played in the last two weeks wears an amulet, or an
     * amulet saved one of your characters in that time.
     */
    public boolean relevant() {
        long since = System.currentTimeMillis() - RECENT_MS;
        for (CharacterStore.Char c : CharacterStore.get().all()) {
            if (c.alive && c.lastPlayed >= since && c.equipment != null && c.equipment.length > 3 && Amulets.isAmulet(c.equipment[3])) {
                return true;
            }
        }
        synchronized (this) {
            for (Save s : saves) if (!s.removed && s.t >= since) return true;
        }
        return false;
    }

    /** Saves since a time, e.g. today. */
    public synchronized int countSince(long since) {
        int n = 0;
        for (Save s : saves) if (!s.removed && s.t >= since) n++;
        return n;
    }

    /** A chat text: counted only if the game sent it (no player name) and it mentions an amulet. */
    public void text(TomatoData data, packets.incoming.TextPacket p) {
        if (p == null || p.text == null) return;
        boolean system = p.name == null || p.name.isBlank() || p.name.startsWith("*");
        if (!system) return;
        if (mentionsAmulet(p.text)) {
            AppLog.log("notify", "text: " + p.text);
            record(data, p.text);
        }
    }

    private static boolean mentionsAmulet(String s) {
        return s.toLowerCase(Locale.ROOT).contains("restoration");
    }

    private void record(TomatoData data, String message) {
        long now = System.currentTimeMillis();
        DungeonVisits.Visit v = DungeonVisits.get().current();
        Save s = new Save();
        s.t = now;
        if (data != null && data.player != null) {
            s.charId = data.player.charId();
            packets.data.StatData ring = data.player.stat.get(packets.data.enums.StatType.INVENTORY_0_STAT.get() + 3);
            Amulets.Amulet a = ring == null ? null : Amulets.of(ring.statValue);
            if (a != null) s.amulet = a.name();
        }
        s.dungeon = v != null ? v.dungeon : data != null && data.map != null ? data.map.name : "";
        s.message = message;
        synchronized (this) {
            // the same save can come as a notification and a text: one per few seconds
            for (int i = saves.size() - 1; i >= 0 && now - saves.get(i).t < 3000; i--) if (!saves.get(i).removed) return;
            saves.add(s);
            save();
        }
        DungeonVisits.get().amuletSaved(now);
        ItemSound.AMULET.play();   // the sound you chose for it, if any
        AppLog.log("game", "Amulet saved you (" + s.dungeon + "): " + message);
        fire();
    }

    /** Whether a save at about this time (within 3 s) was removed. */
    public synchronized boolean removedAt(long t) {
        for (Save s : saves) if (s.removed && Math.abs(s.t - t) <= 3000) return true;
        return false;
    }

    /** All saves, oldest first (not the removed ones). */
    public synchronized List<Save> all() {
        return saves.stream().filter(s -> !s.removed).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public synchronized int count() {
        return (int) saves.stream().filter(s -> !s.removed).count();
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    private void save() {
        try {
            Files.writeString(FILE, GSON.toJson(saves));
        } catch (Exception e) {
            System.err.println("Could not write " + FILE + ": " + e.getMessage());
        }
    }

    @Override
    public String syncKind() {
        return "amulet_saves";
    }

    @Override
    public String syncLabel() {
        return "Amulet saves";
    }

    @Override
    public synchronized int syncCount() {
        return count();
    }

    @Override
    public synchronized JsonElement exportSync() {
        return GSON.toJsonTree(saves);
    }

    /**
     * Saves only the other side has are added (a save is identified by its time); one removed on either side stays
     * removed.
     */
    @Override
    public boolean mergeSync(JsonElement remote) {
        List<Save> theirs = GSON.fromJson(remote, new TypeToken<ArrayList<Save>>() {
        }.getType());
        if (theirs == null) return false;
        boolean changed = false;
        synchronized (this) {
            java.util.Map<Long, Save> known = new java.util.HashMap<>();
            for (Save s : saves) known.put(s.t, s);
            for (Save s : theirs) {
                Save mine = known.get(s.t);
                if (mine == null) {
                    if (s.message != null && s.message.contains(OLD_RULE) && !s.removed) {
                        s.removed = true;   // counted by the first version from another client
                        DungeonVisits.get().amuletSaveRemoved(s.t);
                    }
                    saves.add(s);
                    known.put(s.t, s);
                    changed = true;
                } else if (s.removed && !mine.removed) {
                    mine.removed = true;
                    DungeonVisits.get().amuletSaveRemoved(s.t);
                    changed = true;
                }
            }
            if (changed) {
                saves.sort((a, b) -> Long.compare(a.t, b.t));
                save();
            }
        }
        if (changed) fire();
        return changed;
    }
}
