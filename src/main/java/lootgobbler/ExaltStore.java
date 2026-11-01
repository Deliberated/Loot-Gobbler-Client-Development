package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Exaltation progress per class, kept across restarts in exalts.json. Fed from Tomato's exalt data
 * (RealmCharacter.exalts: fetched once per login, then updated live from exaltation packets).
 * <p>
 * Each class has 8 progress values in Tomato's order: dex, spd, vit, wis, def, att, mana, life. A stat gains an
 * exaltation at 5, 15, 30, 50 and 75 progress, so 5 per stat and 40 per class at most.
 */
public final class ExaltStore implements Syncable {
    public static final int DEX = 0, SPD = 1, VIT = 2, WIS = 3, DEF = 4, ATT = 5, MANA = 6, LIFE = 7;
    public static final int[] THRESHOLDS = {5, 15, 30, 50, 75};
    public static final int MAX_PER_STAT = THRESHOLDS.length, MAX_PER_CLASS = 8 * MAX_PER_STAT;

    private static final Path FILE = Path.of("exalts.json");
    private static final Gson GSON = new Gson();
    private static final ExaltStore INSTANCE = new ExaltStore();

    private final Map<Integer, int[]> progress = new TreeMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static ExaltStore get() {
        return INSTANCE;
    }

    private ExaltStore() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Map<Integer, int[]> loaded = GSON.fromJson(r, new TypeToken<TreeMap<Integer, int[]>>() {
            }.getType());
            if (loaded != null) progress.putAll(loaded);
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    /**
     * New exalt data from the game (any thread): replaces the stored values of every class it contains.
     */
    public void update(Map<Integer, int[]> exalts) {
        if (exalts == null || exalts.isEmpty()) return;
        synchronized (this) {
            exalts.forEach((cls, values) -> {
                if (values != null && values.length >= 8) progress.put(cls, values.clone());
            });
            save();
        }
        for (Runnable l : listeners) l.run();
    }

    /**
     * Progress values of a class, or null if unknown.
     */
    public synchronized int[] progress(int classId) {
        int[] p = progress.get(classId);
        return p == null ? null : p.clone();
    }

    public synchronized boolean isEmpty() {
        return progress.isEmpty();
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public String syncKind() {
        return "exalts";
    }

    @Override
    public String syncLabel() {
        return "Exaltations";
    }

    @Override
    public synchronized int syncCount() {
        return progress.size();
    }

    @Override
    public synchronized JsonElement exportSync() {
        return GSON.toJsonTree(progress);
    }

    /**
     * Exaltation progress only grows, so the higher value of each stat wins.
     */
    @Override
    public boolean mergeSync(JsonElement remote) {
        Map<Integer, int[]> theirs = GSON.fromJson(remote, new TypeToken<TreeMap<Integer, int[]>>() {
        }.getType());
        if (theirs == null) return false;
        boolean changed = false;
        synchronized (this) {
            for (Map.Entry<Integer, int[]> t : theirs.entrySet()) {
                int[] other = t.getValue();
                if (other == null || other.length < 8) continue;
                int[] mine = progress.get(t.getKey());
                if (mine == null) {
                    progress.put(t.getKey(), other.clone());
                    changed = true;
                    continue;
                }
                for (int i = 0; i < Math.min(mine.length, other.length); i++) {
                    if (other[i] > mine[i]) {
                        mine[i] = other[i];
                        changed = true;
                    }
                }
            }
            if (changed) save();
        }
        if (changed) for (Runnable l : listeners) l.run();
        return changed;
    }

    /**
     * Exaltations earned from a stat's progress (0..5).
     */
    public static int level(int progress) {
        int n = 0;
        for (int t : THRESHOLDS) if (progress >= t) n++;
        return n;
    }

    /**
     * Total exaltations of a class (0..40), 0 if unknown.
     */
    public static int total(int[] progress) {
        if (progress == null) return 0;
        int n = 0;
        for (int i = 0; i < Math.min(8, progress.length); i++) n += level(progress[i]);
        return n;
    }

    private void save() {
        try {
            Path tmp = Path.of(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(progress, w);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save " + FILE + ": " + e.getMessage());
        }
    }
}
