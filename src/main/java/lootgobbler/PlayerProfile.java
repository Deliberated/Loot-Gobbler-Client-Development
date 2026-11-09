package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Your name and in-game guild, as last seen while playing (profile.json). Synced to the server, which groups
 * players by guild for the guild views.
 */
public final class PlayerProfile implements Syncable {
    public static final class Data {
        public String ign = "";
        public String guild = "";     // "" = not in a guild (or not seen yet)
        public int guildRank;         // the game's value: 0 initiate, 10 member, 20 officer, 30 leader, 40 founder
        public long updatedAt;        // epoch ms of the last change
        // when each ST set was completed (its last piece first collected, epoch ms); null = not counted yet
        public List<Long> stSetsDone;
        public List<ExaltStore.Change> exaltChanges;   // the last week's exaltation progress, as ExaltStore saw it
    }

    private static final Path FILE = Path.of("profile.json");
    private static final Gson GSON = new Gson();
    private static final PlayerProfile INSTANCE = new PlayerProfile();

    private Data data = new Data();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static PlayerProfile get() {
        return INSTANCE;
    }

    private PlayerProfile() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Data loaded = GSON.fromJson(r, Data.class);
            if (loaded != null) data = loaded;
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    public static String rankName(int rank) {
        return switch (rank) {
            case 40 -> "Founder";
            case 30 -> "Leader";
            case 20 -> "Officer";
            case 10 -> "Member";
            default -> "Initiate";
        };
    }

    /**
     * Copy of the current profile.
     */
    public synchronized Data current() {
        return GSON.fromJson(GSON.toJson(data), Data.class);
    }

    /**
     * Values from the game while you play (any thread). guild null = not known in this update, left as it is.
     */
    public void update(String ign, String guild, Integer guildRank) {
        synchronized (this) {
            String newIgn = ign == null || ign.isBlank() ? data.ign : ign;
            String newGuild = guild == null ? data.guild : guild.trim();
            int newRank = guildRank == null ? data.guildRank : guildRank;
            if (Objects.equals(newIgn, data.ign) && Objects.equals(newGuild, data.guild) && newRank == data.guildRank) return;
            data.ign = newIgn;
            data.guild = newGuild;
            data.guildRank = newRank;
            data.updatedAt = System.currentTimeMillis();
            save();
        }
        fire();
    }

    private final java.util.concurrent.atomic.AtomicBoolean counting = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Keeps stSetsDone up to date with the collection (a set is complete as Collection > ST Sets counts it: all four
     * pieces collected; it was completed when the last of them was first found), so the guild sees how many were
     * completed lately. Counted in the background: the sets come from the game's XML.
     */
    public void trackStSets() {
        Runnable count = () -> {
            if (!counting.compareAndSet(false, true)) return;
            Thread t = new Thread(() -> {
                try {
                    Map<Integer, CollectionStore.Entry> owned = CollectionStore.get().snapshot();
                    List<Long> done = new java.util.ArrayList<>();
                    for (StSets.Set s : StSets.get()) {
                        if (s.owned(owned.keySet()) != s.pieces().length) continue;
                        long at = 0;
                        for (int id : s.pieces()) at = Math.max(at, firstFound(owned.get(id)));
                        done.add(at);
                    }
                    java.util.Collections.sort(done);
                    setStSetsDone(done);
                } catch (Exception e) {
                    System.err.println("Could not count ST sets: " + e);
                } finally {
                    counting.set(false);
                }
            }, "st-set-count");
            t.setDaemon(true);
            t.start();
        };
        CollectionStore.get().onChange(count);
        count.run();
    }

    /** When the item was first collected (epoch ms, from the collection's local time); 0 if unknown. */
    private static long firstFound(CollectionStore.Entry e) {
        if (e == null || e.first == null) return 0;
        try {
            return java.time.LocalDateTime.parse(e.first).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception x) {
            return 0;
        }
    }

    private void setStSetsDone(List<Long> done) {
        synchronized (this) {
            if (done.equals(data.stSetsDone)) return;
            data.stSetsDone = done;
            data.updatedAt = System.currentTimeMillis();
            save();
        }
        fire();
    }

    /** Keeps exaltChanges up to date with ExaltStore, so the guild sees every exaltation of the last days. */
    public void trackExalts() {
        Runnable copy = () -> {
            List<ExaltStore.Change> changes = ExaltStore.get().recentChanges();
            synchronized (this) {
                if (sameChanges(changes, data.exaltChanges)) return;
                data.exaltChanges = changes;
                data.updatedAt = System.currentTimeMillis();
                save();
            }
            fire();
        };
        ExaltStore.get().onChange(copy);
        copy.run();
    }

    private static boolean sameChanges(List<ExaltStore.Change> a, List<ExaltStore.Change> b) {
        return b != null && GSON.toJson(a).equals(GSON.toJson(b));
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public String syncKind() {
        return "profile";
    }

    @Override
    public String syncLabel() {
        return "Profile";
    }

    @Override
    public synchronized int syncCount() {
        return data.ign.isEmpty() ? 0 : 1;
    }

    @Override
    public synchronized JsonElement exportSync() {
        return GSON.toJsonTree(data);
    }

    /**
     * The more recently updated profile wins (it is what the game showed last).
     */
    @Override
    public boolean mergeSync(JsonElement remote) {
        Data theirs = GSON.fromJson(remote, Data.class);
        if (theirs == null) return false;
        synchronized (this) {
            if (theirs.updatedAt <= data.updatedAt) return false;
            if (theirs.ign == null) theirs.ign = "";
            if (theirs.guild == null) theirs.guild = "";
            data = theirs;
            save();
        }
        fire();
        return true;
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    private void save() {
        try {
            Path tmp = Path.of(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save " + FILE + ": " + e.getMessage());
        }
    }
}
