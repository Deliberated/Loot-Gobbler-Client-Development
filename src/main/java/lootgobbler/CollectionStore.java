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
 * Copies that leave you (forged into something else, fed to a pet, dropped on the ground) stay in the history as
 * removal events and are
 * taken off what you hold: count is every copy ever obtained, held() what is left. An item with nothing held shows
 * as not collected. Removals are events like drops, so syncing keeps them instead of bringing the copy back.
 */
public final class CollectionStore implements Syncable {
    public static final String DROP = "drop", VAULT = "vault", INVENTORY = "inventory";
    public static final String FORGED = "forged", FED = "fed", DROPPED = "dropped";   // removal events
    public static final String CONSUMED = "consumed";   // used up in the forge or by a pet (the game does not say which)

    /** A copy that left you (forged, fed, dropped on the ground), not one you found. */
    public static boolean isRemoval(Find f) {
        return FORGED.equals(f.source) || FED.equals(f.source) || DROPPED.equals(f.source) || CONSUMED.equals(f.source);
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
        public Seen seen;          // where this copy was seen last (a character or a vault chest); null if not yet

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

    /**
     * Where a copy was seen last: on a character (its id and look then, so it still shows after the character is
     * gone) or in a container of the vault (charId 0, place "Vault chest", "Gift chest", ...).
     */
    public static final class Seen {
        public int charId;
        public String place = "";
        public int classType, skin, tex1, tex2;
        public boolean seasonal;

        public static Seen vault(String place) {
            Seen s = new Seen();
            s.place = place == null || place.isBlank() ? "Vault" : place;
            return s;
        }

        public static Seen character(CharacterStore.Char c) {
            Seen s = new Seen();
            s.charId = c.charId;
            s.place = c.className;
            s.classType = c.classType;
            s.skin = c.skin;
            s.tex1 = c.tex1;
            s.tex2 = c.tex2;
            s.seasonal = c.seasonal;
            return s;
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

        /**
         * Copies obtained minus those forged or fed away, by the history alone; what you have is
         * CollectionStore.held(itemId), which counts the copies where they are.
         */
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
     * Items you hold right now (a character or the vault), one Find per copy: an item seen for the first time (not
     * dropped while Loot Gobbler ran) joins the collection, as first seen there. Nothing else happens: what you have
     * is kept by Holdings, place by place, and the history only records what happened (finds, removals).
     *
     * @param held item id to one Find per copy currently held
     * @return How many copies were new to the collection.
     */
    public int recordHeld(Map<Integer, List<Find>> held) {
        int added = 0;
        synchronized (this) {
            for (Map.Entry<Integer, List<Find>> h : held.entrySet()) {
                List<Find> copies = h.getValue();
                if (copies.isEmpty() || entries.containsKey(h.getKey())) continue;
                Entry e = new Entry();
                e.first = copies.get(0).time;
                e.last = copies.get(copies.size() - 1).time;
                for (Find f : copies) addHistory(e, f);
                e.count = copies.size();
                entries.put(h.getKey(), e);
                added += copies.size();
            }
            if (added > 0) save();
        }
        if (added > 0) fire();
        return added;
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

    /** True if you hold at least one copy. */
    public boolean contains(int itemId) {
        return held(itemId) > 0;
    }

    /**
     * How many copies of the item you have: counted where they are (every character and vault, see Holdings),
     * or from the history while no place has been looked at yet.
     */
    public int held(int itemId) {
        if (Holdings.get().known()) return Holdings.get().count(itemId);
        Entry e;
        synchronized (this) {
            e = entries.get(itemId);
        }
        return e == null ? 0 : e.held();
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
            if (e == null) return false;
            Find f = copy != null && copy.enchantIds != null ? Find.of(source, copy.enchantIds, where, "")
                    : Find.of(source, -1, where, "");
            if (copy != null) {
                f.seasonal = copy.seasonal;
                f.seen = copy.seen;   // where it was used up from
            }
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
