package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lifetime collection: for each item id, when it was first and last obtained, how often, and a history of
 * every find (drop, vault, inventory) with its enchantment count. Saved to collection.json after every change.
 */
public final class CollectionStore {
    public static final String DROP = "drop", VAULT = "vault", INVENTORY = "inventory";

    /**
     * One time an item was found.
     */
    public static final class Find {
        public String time;
        public String source = DROP;
        public int enchants = -1;   // -1 = unknown
        public String where = "";
        public String bag = "";
        public List<Integer> enchantIds;   // null if unknown (older finds, inventory)

        public static Find of(String source, int enchants, String where, String bag) {
            Find f = new Find();
            f.time = now();
            f.source = source;
            f.enchants = enchants;
            f.where = where == null ? "" : where;
            f.bag = bag == null ? "" : bag;
            return f;
        }

        public static Find of(String source, List<Integer> enchantIds, String where, String bag) {
            Find f = of(source, enchantIds == null ? -1 : enchantIds.size(), where, bag);
            f.enchantIds = enchantIds;
            return f;
        }
    }

    public static final class Entry {
        public String first;
        public String last;
        public int count;
        public List<Find> history;   // null for items collected before history was tracked

        public String firstDate() {
            return first == null ? "" : first.substring(0, Math.min(10, first.length()));
        }

        public String lastDate() {
            return last == null ? "" : last.substring(0, Math.min(10, last.length()));
        }

        public List<Find> history() {
            return history == null ? List.of() : history;
        }

        /**
         * Most enchantments any recorded copy had (0 if none or unknown).
         */
        public int maxEnchants() {
            int max = 0;
            for (Find f : history()) max = Math.max(max, f.enchantIds != null ? f.enchantIds.size() : f.enchants);
            return max;
        }
    }

    private static final Path FILE = Path.of("collection.json");
    private static final int MAX_HISTORY = 200;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final CollectionStore INSTANCE = new CollectionStore();

    private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static CollectionStore get() {
        return INSTANCE;
    }

    private CollectionStore() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Map<Integer, Entry> loaded = GSON.fromJson(r, new TypeToken<LinkedHashMap<Integer, Entry>>() {
            }.getType());
            if (loaded != null) entries.putAll(loaded);
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    /**
     * A drop: counts as one more find.
     */
    public void record(int itemId, Find find) {
        synchronized (this) {
            Entry e = entries.computeIfAbsent(itemId, k -> {
                Entry n = new Entry();
                n.first = find.time;
                return n;
            });
            e.last = find.time;
            e.count++;
            addHistory(e, find);
            save();
        }
        fire();
    }

    /**
     * Items you hold right now (vault, inventory), one Find per copy. An item's count is raised to the number of
     * copies held if it is lower; seeing the same copies again changes nothing, so re-checking never inflates it.
     *
     * @param held item id to one Find per copy currently held
     * @return How many copies were new to the collection.
     */
    public int recordHeld(Map<Integer, List<Find>> held) {
        int added = 0;
        boolean changed = false;
        synchronized (this) {
            for (Map.Entry<Integer, List<Find>> h : held.entrySet()) {
                List<Find> copies = h.getValue();
                Entry e = entries.get(h.getKey());
                int known = e == null ? 0 : e.count;
                if (e != null && fillEnchants(e, copies)) changed = true;
                if (copies.size() <= known) continue;
                if (e == null) {
                    e = new Entry();
                    e.first = copies.get(0).time;
                    entries.put(h.getKey(), e);
                }
                // the new copies are the ones the history does not show yet, so each keeps its own place and enchantments
                List<Find> fresh = notInHistory(e, copies);
                int n = copies.size() - known;
                for (int i = 0; i < n; i++) addHistory(e, i < fresh.size() ? fresh.get(i) : copies.get(copies.size() - n + i));
                e.last = copies.get(copies.size() - 1).time;
                added += n;
                e.count = copies.size();
            }
            if (added > 0 || changed) save();
        }
        if (added > 0 || changed) fire();
        return added;
    }

    /**
     * Held copies that the history does not account for yet: copies are paired with history finds of the same
     * enchantments first, then with finds of the same source whose enchantments are unknown.
     */
    private static List<Find> notInHistory(Entry e, List<Find> copies) {
        List<Find> rest = new ArrayList<>(copies);
        List<Find> unknown = new ArrayList<>();
        for (Find f : e.history()) {
            if (f.enchantIds == null) {
                unknown.add(f);
                continue;
            }
            rest.stream().filter(c -> f.enchantIds.equals(c.enchantIds)).findFirst().ifPresent(rest::remove);
        }
        for (Find f : unknown) {
            rest.stream().filter(c -> c.source.equals(f.source)).findFirst().ifPresent(rest::remove);
        }
        return rest;
    }

    /**
     * Copies seen again with known enchantments complete earlier vault/inventory finds that lacked them. A set of
     * enchantments the history already shows belongs to that find, so it is never copied onto another one.
     *
     * @return True if any find was updated.
     */
    private static boolean fillEnchants(Entry e, List<Find> seen) {
        boolean changed = false;
        List<Find> unknown = new ArrayList<>();
        List<List<Integer>> shown = new ArrayList<>();   // enchantment sets already in the history, one per find
        for (Find f : e.history()) {
            if (f.enchantIds == null && !DROP.equals(f.source)) unknown.add(f);
            if (f.enchantIds != null) shown.add(f.enchantIds);
        }
        for (Find s : seen) {
            if (s.enchantIds == null || shown.remove(s.enchantIds)) continue;   // that copy is already in the history
            // older versions did not count empty slots: upgrade a find with the same enchantments but fewer slots
            Find upgrade = null;
            for (Find f : e.history()) {
                if (f.enchantIds != null && !DROP.equals(f.source) && f.enchantIds.size() < s.enchantIds.size()
                        && s.enchantIds.containsAll(f.enchantIds)) {
                    upgrade = f;
                    break;
                }
            }
            Find f = upgrade;
            if (f == null) {   // an unknown find from the same place (vault / inventory) first
                f = unknown.stream().filter(u -> u.source.equals(s.source)).findFirst()
                        .orElse(unknown.isEmpty() ? null : unknown.get(0));
                unknown.remove(f);
            }
            if (f == null) continue;
            f.enchantIds = s.enchantIds;
            f.enchants = s.enchantIds.size();
            changed = true;
        }
        return changed;
    }

    public void clear() {
        synchronized (this) {
            entries.clear();
            save();
        }
        fire();
    }

    /**
     * Copy of all entries (the Entry objects are shared; treat them as read-only).
     */
    public synchronized Map<Integer, Entry> snapshot() {
        return new LinkedHashMap<>(entries);
    }

    public synchronized boolean contains(int itemId) {
        return entries.containsKey(itemId);
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    private static void addHistory(Entry e, Find f) {
        if (e.history == null) e.history = new ArrayList<>();
        e.history.add(f);
        while (e.history.size() > MAX_HISTORY) e.history.remove(0);
    }

    private static String now() {
        return LocalDateTime.now().withNano(0).toString();
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    private void save() {
        try {
            Path tmp = Path.of(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(entries, w);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save " + FILE + ": " + e.getMessage());
        }
    }
}
