package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
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
 * <p>
 * Copies that leave you (forged into something else, fed to a pet) stay in the history as removal events and are
 * taken off what you hold: count is every copy ever obtained, held() what is left. An item with nothing held shows
 * as not collected. Removals are events like drops, so syncing keeps them instead of bringing the copy back.
 */
public final class CollectionStore implements Syncable {
    public static final String DROP = "drop", VAULT = "vault", INVENTORY = "inventory";
    public static final String FORGED = "forged", FED = "fed";   // removal events

    /** A copy that left you (forged, fed), not one you found. */
    public static boolean isRemoval(Find f) {
        return FORGED.equals(f.source) || FED.equals(f.source);
    }

    /**
     * One time an item was found.
     */
    public static final class Find {
        public String time;
        public long at;   // exact time (epoch ms), so the server can tell which week a find belongs to; 0 in older finds
        public String source = DROP;
        public int enchants = -1;   // -1 = unknown
        public String where = "";
        public String bag = "";
        public List<Integer> enchantIds;   // null if unknown (older finds, inventory)
        public Boolean seasonal;   // found on a seasonal character; null if unknown (older finds)

        public static Find of(String source, int enchants, String where, String bag) {
            Find f = new Find();
            f.time = now();
            f.at = System.currentTimeMillis();
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

        /** Copies forged or fed away. */
        public int removed() {
            return (int) history().stream().filter(CollectionStore::isRemoval).count();
        }

        /** Copies you still hold: every copy obtained minus those forged or fed away. */
        public int held() {
            return Math.max(0, count - removed());
        }

        /**
         * Most enchantments any recorded copy had (0 if none or unknown).
         */
        public int maxEnchants() {
            int max = 0;
            for (Find f : history()) {
                if (!isRemoval(f)) max = Math.max(max, f.enchantIds != null ? f.enchantIds.size() : f.enchants);
            }
            return max;
        }
    }

    private static final Path FILE = Path.of("collection.json");
    private static final int MAX_HISTORY = 200;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final CollectionStore INSTANCE = new CollectionStore();

    private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<java.util.function.BiConsumer<Integer, Find>> dropListeners = new CopyOnWriteArrayList<>();

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
        for (java.util.function.BiConsumer<Integer, Find> l : dropListeners) l.accept(itemId, find);
    }

    /** Called (on the packet thread) with each drop right after it was recorded. */
    public void onDrop(java.util.function.BiConsumer<Integer, Find> listener) {
        dropListeners.add(listener);
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
                int known = e == null ? 0 : e.held();   // copies forged or fed away are not in the vault any more
                if (e != null && fillEnchants(e, copies)) changed = true;
                if (e != null && fillSeasonal(e, copies)) changed = true;
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
                e.count += n;
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
            if (isRemoval(f)) continue;
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
            if (isRemoval(f)) continue;
            if (f.enchantIds == null && !DROP.equals(f.source)) unknown.add(f);
            if (f.enchantIds != null) shown.add(f.enchantIds);
        }
        for (Find s : seen) {
            if (s.enchantIds == null || shown.remove(s.enchantIds)) continue;   // that copy is already in the history
            // older versions did not count empty slots: upgrade a find with the same enchantments but fewer slots
            Find upgrade = null;
            for (Find f : e.history()) {
                if (f.enchantIds != null && !DROP.equals(f.source) && !isRemoval(f) && f.enchantIds.size() < s.enchantIds.size()
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

    /**
     * Older vault/inventory finds did not record whether the character was seasonal: a copy seen again with the
     * same enchantments fills it in.
     *
     * @return True if any find was updated.
     */
    private static boolean fillSeasonal(Entry e, List<Find> seen) {
        boolean changed = false;
        List<Find> rest = new ArrayList<>(seen);
        for (Find f : e.history()) {
            if (f.seasonal != null || f.enchantIds == null || DROP.equals(f.source) || isRemoval(f)) continue;
            Find s = rest.stream().filter(c -> c.seasonal != null && f.enchantIds.equals(c.enchantIds)).findFirst().orElse(null);
            if (s == null) continue;
            rest.remove(s);
            f.seasonal = s.seasonal;
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

    /** True if you hold at least one copy (found, and not all forged or fed away). */
    public synchronized boolean contains(int itemId) {
        Entry e = entries.get(itemId);
        return e != null && e.held() > 0;
    }

    /**
     * A copy left you: forged into something else or fed to a pet (source FORGED or FED). Recorded in the history,
     * and taken off what you hold. Nothing happens for an item you hold no copy of.
     *
     * @param where e.g. "Forge", "Pet"
     * @param copy  the copy that left (its enchantments and seasonal flag, from the slot it was in), or null if
     *              unknown
     * @return True if it was recorded.
     */
    public boolean recordRemoved(int itemId, String source, String where, Find copy) {
        synchronized (this) {
            Entry e = entries.get(itemId);
            if (e == null || e.held() == 0) return false;
            Find f = copy != null && copy.enchantIds != null ? Find.of(source, copy.enchantIds, where, "")
                    : Find.of(source, -1, where, "");
            if (copy != null) f.seasonal = copy.seasonal;
            addHistory(e, f);
            save();
        }
        fire();
        return true;
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    @Override
    public String syncKind() {
        return "collection";
    }

    @Override
    public String syncLabel() {
        return "Collection";
    }

    @Override
    public synchronized int syncCount() {
        return entries.size();
    }

    @Override
    public synchronized JsonElement exportSync() {
        return GSON.toJsonTree(entries);
    }

    @Override
    public boolean mergeSync(JsonElement remote) {
        Map<Integer, Entry> theirs = GSON.fromJson(remote, new TypeToken<LinkedHashMap<Integer, Entry>>() {
        }.getType());
        if (theirs == null) return false;
        boolean changed = false;
        synchronized (this) {
            for (Map.Entry<Integer, Entry> t : theirs.entrySet()) {
                Entry mine = entries.get(t.getKey());
                if (mine == null) {
                    entries.put(t.getKey(), t.getValue());
                    changed = true;
                } else if (mergeEntry(mine, t.getValue())) {
                    changed = true;
                }
            }
            if (changed) save();
        }
        if (changed) fire();
        return changed;
    }

    /**
     * Two PCs' records of one item, counted the way a single PC counts:
     * <ul>
     * <li>Drops are events: a drop only the other side saw is one more copy.</li>
     * <li>Vault and inventory finds are sightings of copies you hold. Two PCs looking at the vault see the same
     * copies, so sightings are paired up across the PCs. A fresh install that opens the vault before its first sync
     * sees your vault again, with today's date.</li>
     * <li>Sightings show at least that many copies. Drops before the last sighting may be among them; drops after
     * it are new copies on top.</li>
     * </ul>
     * Whatever a side's count has beyond what its history explains (finds from before history was kept, or cut off
     * the history) is kept as well.
     *
     * @return True if mine changed.
     */
    private static boolean mergeEntry(Entry mine, Entry theirs) {
        List<Find> myDrops = new ArrayList<>(), myHeld = new ArrayList<>();
        List<Find> theirDrops = new ArrayList<>(), theirHeld = new ArrayList<>();
        for (Find f : mine.history()) if (!isRemoval(f)) (DROP.equals(f.source) ? myDrops : myHeld).add(f);
        for (Find f : theirs.history()) if (!isRemoval(f)) (DROP.equals(f.source) ? theirDrops : theirHeld).add(f);
        // removals are events: one only the other side saw is added (the count of obtained copies stays as is)
        // (counted per key: two copies fed at once are two events with the same time and place)
        Map<String, Integer> myRemovals = new HashMap<>();
        for (Find f : mine.history()) if (isRemoval(f)) myRemovals.merge(removalKey(f), 1, Integer::sum);
        List<Find> theirRemovalsOnly = new ArrayList<>();
        for (Find f : theirs.history()) {
            if (!isRemoval(f)) continue;
            String key = removalKey(f);
            if (myRemovals.getOrDefault(key, 0) > 0) myRemovals.merge(key, -1, Integer::sum);
            else theirRemovalsOnly.add(f);
        }

        // match drops by time and place, pair sightings by enchantments
        Map<String, List<Find>> myDropsByKey = new HashMap<>();
        for (Find f : myDrops) myDropsByKey.computeIfAbsent(dropKey(f), k -> new ArrayList<>()).add(f);
        List<Find> theirDropsOnly = new ArrayList<>();
        List<Find[]> sameDrops = new ArrayList<>();
        for (Find f : theirDrops) {
            List<Find> same = myDropsByKey.get(dropKey(f));
            if (same == null || same.isEmpty()) theirDropsOnly.add(f);
            else sameDrops.add(new Find[]{same.remove(0), f});
        }
        List<Find> unpaired = new ArrayList<>(myHeld);
        List<Find> theirHeldOnly = new ArrayList<>();
        List<Find[]> sameCopies = new ArrayList<>();
        for (Find f : theirHeld) {
            Find pair = pairHeld(unpaired, f);
            if (pair == null) {
                theirHeldOnly.add(f);
            } else {
                unpaired.remove(pair);
                sameCopies.add(new Find[]{pair, f});
            }
        }

        List<Find> allDrops = new ArrayList<>(myDrops);
        allDrops.addAll(theirDropsOnly);
        List<Find> allHeld = new ArrayList<>(myHeld);
        allHeld.addAll(theirHeldOnly);
        String lastSighting = latest(latest(null, myHeld), theirHeld);   // before the pairs get their first time
        int mergedExplained = explained(allDrops, allHeld.size(), lastSighting);
        int unexplained = Math.max(0, Math.max(mine.count - explained(myDrops, myHeld.size(), latest(null, myHeld)),
                theirs.count - explained(theirDrops, theirHeld.size(), latest(null, theirHeld))));
        int count = Math.max(Math.max(mine.count, theirs.count), mergedExplained + unexplained);

        boolean changed = false;
        for (Find[] p : sameDrops) changed |= fillEnchants(p[0], p[1]);
        for (Find[] p : sameCopies) {
            Find m = p[0], f = p[1];
            if (f.time != null && (m.time == null || f.time.compareTo(m.time) < 0)) {   // keep the first sighting
                m.time = f.time;
                m.where = f.where;
                m.bag = f.bag;
                changed = true;
            }
            changed |= fillEnchants(m, f);
        }
        List<Find> added = new ArrayList<>(theirDropsOnly);
        added.addAll(theirHeldOnly);
        added.addAll(theirRemovalsOnly);
        String first = earlier(mine.first, theirs.first);
        String last = later(mine.last, theirs.last);
        changed |= !added.isEmpty() || count != mine.count
                || !Objects.equals(first, mine.first) || !Objects.equals(last, mine.last);
        if (changed) {
            List<Find> history = new ArrayList<>(mine.history());
            history.addAll(added);
            history.sort(Comparator.comparing(f -> f.time == null ? "" : f.time));
            trim(history);
            mine.history = history.isEmpty() && mine.history == null ? null : history;
        }
        mine.count = count;
        mine.first = first;
        mine.last = last;
        return changed;
    }

    /**
     * Copies a history accounts for: at least the sighted copies plus the drops after the last sighting, and at
     * least the number of drops.
     */
    private static int explained(List<Find> drops, int sightings, String lastSighting) {
        int dropsAfter = 0;
        for (Find d : drops) {
            if (lastSighting == null || (d.time != null && d.time.compareTo(lastSighting) > 0)) dropsAfter++;
        }
        return Math.max(drops.size(), sightings + dropsAfter);
    }

    private static String latest(String current, List<Find> finds) {
        for (Find f : finds) {
            if (f.time != null && (current == null || f.time.compareTo(current) > 0)) current = f.time;
        }
        return current;
    }

    /**
     * Gives m the enchantments f knows, if m lacks them.
     */
    private static boolean fillEnchants(Find m, Find f) {
        if (m.enchantIds != null || f.enchantIds == null) return false;
        m.enchantIds = f.enchantIds;
        m.enchants = f.enchants;
        return true;
    }

    /** Identifies a removal (forge, feed) across PCs. */
    private static String removalKey(Find f) {
        return f.source + "|" + f.time + "|" + f.where;
    }

    /**
     * Identifies a drop across PCs. Enchantments are left out: they can be filled in later on one side only.
     */
    private static String dropKey(Find f) {
        return f.time + "|" + f.where + "|" + f.bag;
    }

    /**
     * The sighting in mine that is the same copy as f: same enchantments first, then one whose enchantments are
     * unknown on either side (same place first).
     */
    private static Find pairHeld(List<Find> mine, Find f) {
        if (f.enchantIds != null) {
            for (Find m : mine) if (f.enchantIds.equals(m.enchantIds)) return m;
        }
        for (Find m : mine) {
            if ((m.enchantIds == null || f.enchantIds == null) && Objects.equals(m.source, f.source)) return m;
        }
        for (Find m : mine) if (m.enchantIds == null || f.enchantIds == null) return m;
        return null;
    }

    private static String earlier(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static String later(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static void addHistory(Entry e, Find f) {
        if (e.history == null) e.history = new ArrayList<>();
        e.history.add(f);
        trim(e.history);
    }

    /**
     * Cuts a too long history, oldest finds first. Removals stay: without them, forged or fed copies would count
     * as held again.
     */
    private static void trim(List<Find> history) {
        for (int i = 0; history.size() > MAX_HISTORY && i < history.size(); ) {
            if (isRemoval(history.get(i))) i++;
            else history.remove(i);
        }
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
