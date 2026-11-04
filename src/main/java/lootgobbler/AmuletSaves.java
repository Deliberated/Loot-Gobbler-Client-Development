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
 * The times your Amulet of Restoration saved you, read from what the game reports (not worked out from your life):
 * the moment the game puts Invulnerable on your character while you wear it, and any game notification about your
 * character, or system text, that mentions the Amulet of Restoration. Each save is kept with its time, character,
 * dungeon and message (amulet_saves.json, synced to the server), and on the dungeon visit, so the damage graph marks
 * it. All game notifications are also written to the app log, so the game's wording can be checked.
 */
public final class AmuletSaves implements Syncable {
    public static final class Save {
        public long t;
        public int charId;
        public String amulet = "";
        public String dungeon = "";
        public String message = "";
    }

    private static final long RECENT_MS = 14L * 24 * 60 * 60 * 1000;   // "recently played": the last two weeks
    private boolean wasInvulnerable;

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
        packets.data.StatData ring = data.player.stat.get(packets.data.enums.StatType.INVENTORY_0_STAT.get() + 3);
        packets.data.StatData cond = data.player.stat.get(packets.data.enums.StatType.CONDITION_STAT);
        Amulets.Amulet a = ring == null ? null : Amulets.of(ring.statValue);
        boolean invulnerable = cond != null && (cond.statValue & packets.data.enums.ConditionBits.INVULNERABLE.value()) != 0;
        if (a != null && a.invulnerable() && invulnerable && !wasInvulnerable) {
            record(data, a.name() + ": Invulnerable (" + a.effect() + ")");
        }
        wasInvulnerable = invulnerable;
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
            for (Save s : saves) if (s.t >= since) return true;
        }
        return false;
    }

    /** Saves since a time, e.g. today. */
    public synchronized int countSince(long since) {
        int n = 0;
        for (Save s : saves) if (s.t >= since) n++;
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
            if (!saves.isEmpty() && now - saves.get(saves.size() - 1).t < 3000) return;
            saves.add(s);
            save();
        }
        DungeonVisits.get().amuletSaved(now);
        AmuletSound.play();   // the sound you chose for it, if any
        AppLog.log("game", "Amulet saved you (" + s.dungeon + "): " + message);
        fire();
    }

    /** All saves, oldest first. */
    public synchronized List<Save> all() {
        return new ArrayList<>(saves);
    }

    public synchronized int count() {
        return saves.size();
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
        return saves.size();
    }

    @Override
    public synchronized JsonElement exportSync() {
        return GSON.toJsonTree(saves);
    }

    /** Saves only the other side has are added (a save is identified by its time). */
    @Override
    public boolean mergeSync(JsonElement remote) {
        List<Save> theirs = GSON.fromJson(remote, new TypeToken<ArrayList<Save>>() {
        }.getType());
        if (theirs == null) return false;
        boolean changed = false;
        synchronized (this) {
            Set<Long> known = new HashSet<>();
            for (Save s : saves) known.add(s.t);
            for (Save s : theirs) {
                if (known.add(s.t)) {
                    saves.add(s);
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
