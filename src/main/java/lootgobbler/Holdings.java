package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import packets.incoming.VaultContentPacket;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Everything you have, place by place and slot by slot: every character (equipment, inventory, backpack, extender,
 * quick slots) and both vaults (vault chest, gift chest, seasonal spoils, potion storage, material chest).
 * <p>
 * A slot only changes when the game shows that slot: the character list or the live stats for a character, the vault
 * packets for a vault. Nothing is guessed from what went missing. A copy is the item in a slot; copies are only told
 * apart by their place, slot and enchantments. What happened to items (finds, forging, feeding, dropping) is a
 * separate record (CollectionStore).
 * <p>
 * Every change is written to the journal (holdings-journal.txt), so whatever appears or disappears can be traced to
 * what the game showed. Kept in holdings.json.
 */
public final class Holdings {
    private static final Path FILE = Path.of("holdings.json");
    private static final Gson GSON = new GsonBuilder().create();

    public static final String VAULT = "vault", SEASONAL_VAULT = "vault:seasonal";
    /** Section of a character place: every slot by its game index (0-3 worn, 4-11 inventory, 12+ packs). */
    static final String SLOTS = "slots", QUICK = "quick";
    /** Sections of a vault place, also shown as where a copy is. */
    public static final String VAULT_CHEST = "Vault chest", GIFT_CHEST = "Gift chest", SPOILS = "Seasonal spoils",
            POTIONS = "Potion storage", MATERIALS = "Material chest";
    /** A live look at a character newer than this wins over the character list (the list can lag behind the game). */
    static final long LIVE_WINS_MS = 60_000;

    /** One slot: the item in it (-1 = empty) and its enchantment ids (-1 = empty enchant slot; null = unknown). */
    static final class Slot {
        int item = -1;
        List<Integer> enchantIds;

        Slot() {
        }

        Slot(int item, List<Integer> enchantIds) {
            this.item = item < 1 ? -1 : item;
            this.enchantIds = this.item < 0 ? null : enchantIds;
        }

        boolean empty() {
            return item < 1;
        }

        boolean same(Slot o) {
            return o != null && item == o.item && java.util.Objects.equals(enchantIds, o.enchantIds);
        }
    }

    /** One place: its sections of slots, when it was last looked at and what it was read from. */
    static final class Place {
        Map<String, List<Slot>> sections = new LinkedHashMap<>();
        long seenAt;
        String source;   // "char list", "live", "vault packet", "saved characters", "earlier version"
    }

    private static final class State {
        int version = 1;
        Map<String, Place> places = new HashMap<>();   // "char:506", "vault", "vault:seasonal"
    }

    /**
     * One copy you have: where (charId 0 for a vault; vault is VAULT or SEASONAL_VAULT), in which slot ("Equipped",
     * "Vault chest"...), its enchantments and season, and when that place was last looked at.
     */
    public record Held(int charId, String vault, String slot, List<Integer> enchantIds, Boolean seasonal, long seenAt) {
    }

    /** What is not known yet: living characters never looked at, and vaults never visited. */
    public record Gaps(int characters, boolean vault, boolean seasonalVault) {
        public boolean none() {
            return characters == 0 && !vault && !seasonalVault;
        }
    }

    /**
     * A live look at a character: per slot index the item (-1 empty, UNKNOWN when the game has not sent that slot)
     * and its enchantment ids (null when not sent; sent alone, they apply to the item already there). quick holds the
     * three quick slots (UNKNOWN each when not sent), or null.
     */
    public record Live(int[] items, List<Integer>[] enchantIds, int[] quick) {
        public static final int UNKNOWN = Integer.MIN_VALUE;
    }

    /** A character as the character list shows it: every slot, their enchantments, the quick slots. */
    public record Listed(int charId, int[] slots, int[][] enchantIds, int[] quick) {
    }

    /** What the item database says about items; replaced in tests. */
    interface Items {
        boolean collectible(int id);

        String name(int id);
    }

    private static final Items GAME_ITEMS = new Items() {
        public boolean collectible(int id) {
            ItemDb.Item i = ItemDb.get().item(id);
            return i != null && i.isCollectible();
        }

        public String name(int id) {
            ItemDb.Item i = ItemDb.get().item(id);
            return i == null ? "#" + id : i.name;
        }
    };

    /** Created after GAME_ITEMS above, which its fields need. */
    private static final Holdings INSTANCE = new Holdings(FILE, true);

    private final Path file;
    private final boolean persist;
    Items items = GAME_ITEMS;
    Journal journal;
    /** Living characters and their season; replaced in tests. */
    java.util.function.Supplier<Map<Integer, Boolean>> living = Holdings::livingFromStore;

    private State state = new State();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Map<Integer, Integer> counts;   // item -> copies, null after a change

    /** The vault visit being read: its sections so far, until the packet marked last (and its season) is known. */
    private Visit visit;

    private static final class Visit {
        final Map<String, List<Slot>> sections = new LinkedHashMap<>();
        boolean complete, broken;
        String why;   // why it is broken
    }

    private static volatile boolean seeded;

    public static Holdings get() {
        if (!seeded) {
            seeded = true;
            try {
                seedFromSavedData();
            } catch (Exception e) {
                System.err.println("Could not seed " + FILE + ": " + e);
            }
        }
        return INSTANCE;
    }

    /** First start with this store: the saved characters' slots, and the vaults from whereabouts.json. */
    private static void seedFromSavedData() {
        if (INSTANCE.known()) return;
        List<Listed> saved = new ArrayList<>();
        for (CharacterStore.Char c : CharacterStore.get().all()) {
            if (c.alive && c.slots != null) saved.add(new Listed(c.charId, c.slots, c.slotEnchantIds, c.quickslots));
        }
        Map<String, Map<Integer, Integer>> vaults = new HashMap<>();
        Path old = Path.of("whereabouts.json");
        if (Files.exists(old)) {
            try (Reader r = Files.newBufferedReader(old, StandardCharsets.UTF_8)) {
                com.google.gson.JsonObject places = com.google.gson.JsonParser.parseReader(r).getAsJsonObject()
                        .getAsJsonObject("places");
                for (String key : List.of(VAULT, SEASONAL_VAULT)) {
                    if (places == null || !places.has(key)) continue;
                    Map<Integer, Integer> byItem = new HashMap<>();
                    for (Map.Entry<String, com.google.gson.JsonElement> e : places.getAsJsonObject(key).entrySet()) {
                        byItem.put(Integer.parseInt(e.getKey()), e.getValue().getAsJsonArray().size());
                    }
                    vaults.put(key, byItem);
                }
            } catch (Exception e) {
                System.err.println("Could not read " + old + ": " + e.getMessage());
            }
        }
        INSTANCE.seed(saved, vaults);
    }

    Holdings(Path file, boolean persist) {
        this.file = file;
        this.persist = persist;
        this.journal = persist ? new Journal(Path.of("holdings-journal.txt")) : new Journal(null);
        if (!persist || !Files.exists(file)) return;
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            State s = GSON.fromJson(r, State.class);
            if (s != null && s.places != null) state = s;
        } catch (Exception e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    // ---- what the game shows: characters ------------------------------------------------------------------------

    /**
     * The character list: every living character's slots. A character looked at live in the last minute keeps what
     * was seen live (the list can lag behind the game). Characters missing from the list are gone (died or deleted).
     */
    public void characters(Collection<Listed> listed) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        synchronized (this) {
            Set<String> keep = new HashSet<>();
            for (Listed l : listed) if (l.slots() != null) keep.add("char:" + l.charId());
            // a list that shares no character with what is known is most likely another account's: keep everything
            boolean known = state.places.keySet().stream().anyMatch(k -> k.startsWith("char:"));
            if (keep.isEmpty() || known && keep.stream().noneMatch(state.places::containsKey)) {
                journal.line("chars", "character list ignored: " + (keep.isEmpty() ? "it is empty"
                        : "it shares no character with the known ones (another account?)"));
                return;
            }
            for (Listed l : listed) {
                if (l.slots() == null) continue;
                String key = "char:" + l.charId();
                Place before = state.places.get(key);
                if (before != null && "live".equals(before.source) && now - before.seenAt < LIVE_WINS_MS) {
                    journal.line(key, "character list ignored: seen live " + (now - before.seenAt) / 1000 + " s ago");
                    continue;
                }
                Place p = new Place();
                List<Slot> slots = new ArrayList<>();
                for (int i = 0; i < l.slots().length; i++) {
                    int[] e = l.enchantIds() != null && i < l.enchantIds().length ? l.enchantIds()[i] : null;
                    slots.add(new Slot(l.slots()[i], e == null ? null : toList(e)));
                }
                p.sections.put(SLOTS, slots);
                if (l.quick() != null) p.sections.put(QUICK, quickSlots(l.quick(), null));
                else if (before != null && before.sections.containsKey(QUICK)) p.sections.put(QUICK, before.sections.get(QUICK));
                p.seenAt = now;
                p.source = "char list";
                changed |= replace(key, before, p, "character list");
            }
            for (String key : new ArrayList<>(state.places.keySet())) {
                if (!key.startsWith("char:") || keep.contains(key)) continue;
                journal.line(key, "not in the character list: removed (" + describe(state.places.get(key)) + ")");
                state.places.remove(key);
                changed = true;
            }
        }
        if (changed) changed();
    }

    /**
     * A live look at the character you play: what the game sent of it, on entering a map (all of it) or in a tick
     * (only what changed). Slots the game has not sent keep what they held.
     */
    public void live(int charId, Live seen) {
        String key = "char:" + charId;
        boolean changed = false;
        synchronized (this) {
            Place p = state.places.get(key);
            boolean fresh = p == null;
            if (fresh) {
                p = new Place();
                p.sections.put(SLOTS, new ArrayList<>());
                state.places.put(key, p);
            }
            List<Slot> slots = p.sections.computeIfAbsent(SLOTS, k -> new ArrayList<>());
            int n = seen.items().length;
            while (slots.size() < n) slots.add(new Slot());
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int item = seen.items()[i];
                Slot old = slots.get(i);
                List<Integer> ench = seen.enchantIds() == null ? null : seen.enchantIds()[i];
                if (item == Live.UNKNOWN) {   // the item was not sent: only its enchantments may have been
                    if (ench == null || old.empty()) continue;
                    item = old.item;
                }
                if (ench == null && old.item == item) ench = old.enchantIds;   // the same item, its codes not sent
                Slot now = new Slot(item, ench);
                if (now.same(old)) continue;
                slots.set(i, now);
                note(lines, slotName(i) + " " + i, old, now);
                changed = true;
            }
            while (slots.size() > n) {   // a pack is gone (it can only shrink by losing the backpack or extender)
                Slot old = slots.remove(slots.size() - 1);
                note(lines, slotName(slots.size()) + " " + slots.size(), old, new Slot());
                changed = true;
            }
            if (seen.quick() != null) {
                List<Slot> before = p.sections.get(QUICK);
                List<Slot> quick = quickSlots(seen.quick(), before);
                for (int k = 0; k < quick.size(); k++) {
                    Slot old = before != null && k < before.size() ? before.get(k) : new Slot();
                    if (!quick.get(k).same(old)) {
                        note(lines, "Quick slot " + k, old, quick.get(k));
                        changed = true;
                    }
                }
                p.sections.put(QUICK, quick);
            }
            p.seenAt = System.currentTimeMillis();   // saved with the next change
            if (fresh || !"live".equals(p.source)) changed = true;   // from now on, what is seen live
            p.source = "live";
            if (fresh) journal.line(key, "first live look: " + describe(p));
            else for (String l : lines) journal.line(key, l);
        }
        if (changed) changed();
    }

    // ---- what the game shows: vaults ----------------------------------------------------------------------------

    /**
     * One vault packet. A visit can come as several packets; the first one after a map change or a finished visit
     * starts it, the one marked last completes it. A packet that did not decode spoils the whole visit, so a vault is
     * never saved half read. Returns true when the visit is complete (see {@link #vaultSeason}).
     */
    public synchronized boolean vaultPacket(VaultContentPacket p) {
        if (visit == null || visit.complete) visit = new Visit();
        Visit v = visit;
        if (p.partial && !v.broken) {
            v.broken = true;
            v.why = "a packet did not decode";
        }
        add(v, VAULT_CHEST, p.vaultContents, p.vaultChestEnchants);
        add(v, GIFT_CHEST, p.giftContents, p.giftChestEnchants);
        add(v, SPOILS, p.seasonalSpoilContent, p.spoilsChestEnchants);
        add(v, POTIONS, p.potionContents, null);
        add(v, MATERIALS, p.materialContents, null);
        if (p.lastVaultPacket) v.complete = true;
        return v.complete;
    }

    /** Whether a complete vault visit waits for its season to be known. */
    public synchronized boolean vaultWaiting() {
        return visit != null && visit.complete;
    }

    /**
     * Saves the completed vault visit as the seasonal or the regular vault, once that is known (null: not yet, keep
     * waiting). A visit that looks far more like the other vault than like its own is refused: the season was wrong.
     * Returns true if the visit was dealt with (saved or refused).
     */
    public boolean vaultSeason(Boolean seasonal, String how) {
        boolean changed;
        synchronized (this) {
            Visit v = visit;
            if (v == null || !v.complete || seasonal == null) return false;
            visit = null;
            String key = seasonal ? SEASONAL_VAULT : VAULT, other = seasonal ? VAULT : SEASONAL_VAULT;
            Place p = new Place();
            p.sections.putAll(v.sections);
            p.seenAt = System.currentTimeMillis();
            p.source = "vault packet";
            if (v.broken) {
                journal.line(key, "vault visit not saved: " + v.why + " (" + describe(p) + ")");
                return true;
            }
            Place mine = state.places.get(key), theirs = state.places.get(other);
            int likeMine = overlap(p, mine), likeTheirs = overlap(p, theirs), size = collectibles(p).values().stream().mapToInt(Integer::intValue).sum();
            if (theirs != null && size >= 10 && likeTheirs > size * 0.8 && likeTheirs > likeMine * 2) {
                journal.line(key, "vault visit not saved: it matches the " + (seasonal ? "regular" : "seasonal")
                        + " vault (" + likeTheirs + " of " + size + " copies) far more than this one (" + likeMine
                        + "); season " + how);
                return true;
            }
            changed = replace(key, mine, p, "vault visit, season " + how);
        }
        if (changed) changed();
        return true;
    }

    /** A new map: a vault visit not completed by now is dropped. */
    public void entered(boolean vault) {
        synchronized (this) {
            if (visit != null) {
                journal.line(visit.complete ? "vault" : "vault?", "vault visit dropped on leaving: "
                        + (visit.complete ? "its season was never known" : "the last packet never came"));
                visit = null;
            }
        }
    }

    // ---- what you have ------------------------------------------------------------------------------------------

    /** Every copy of the item you have, characters first, then the vaults. */
    public List<Held> copies(int item) {
        List<Held> out = new ArrayList<>();
        Map<Integer, Boolean> chars = living.get();
        synchronized (this) {
            for (Map.Entry<String, Place> e : state.places.entrySet()) {
                String key = e.getKey();
                Place p = e.getValue();
                boolean character = key.startsWith("char:");
                int charId = character ? Integer.parseInt(key.substring(5)) : 0;
                if (character && !chars.containsKey(charId)) continue;   // died since
                Boolean seasonal = character ? chars.get(charId) : (Boolean) SEASONAL_VAULT.equals(key);
                for (Map.Entry<String, List<Slot>> s : p.sections.entrySet()) {
                    List<Slot> slots = s.getValue();
                    for (int i = 0; i < slots.size(); i++) {
                        Slot slot = slots.get(i);
                        if (slot.item != item) continue;
                        String where = character ? (QUICK.equals(s.getKey()) ? "Quick slot" : slotName(i)) : s.getKey();
                        out.add(new Held(charId, character ? null : key, where, slot.enchantIds, seasonal, p.seenAt));
                    }
                }
            }
        }
        out.sort(Comparator.comparing((Held h) -> h.charId() == 0).thenComparingInt(Held::charId)
                .thenComparing(h -> h.vault() == null ? "" : h.vault()));
        return items.collectible(item) ? out : List.of();
    }

    /** How many copies of the item you have (kept until the next change, as the grid asks for every item). */
    public int count(int item) {
        Map<Integer, Integer> c = counts;
        if (c == null) {
            c = new HashMap<>();
            Map<Integer, Boolean> chars = living.get();
            synchronized (this) {
                for (Map.Entry<String, Place> e : state.places.entrySet()) {
                    String key = e.getKey();
                    if (key.startsWith("char:") && !chars.containsKey(Integer.parseInt(key.substring(5)))) continue;
                    for (List<Slot> slots : e.getValue().sections.values()) {
                        for (Slot s : slots) if (!s.empty() && items.collectible(s.item)) c.merge(s.item, 1, Integer::sum);
                    }
                }
            }
            counts = c;
        }
        return c.getOrDefault(item, 0);
    }

    /** Whether any place has been looked at yet (until then, the counts come from the finds). */
    public synchronized boolean known() {
        return !state.places.isEmpty();
    }

    /** Places not looked at yet, whose copies are missing from the counts. */
    public Gaps gaps() {
        Map<Integer, Boolean> chars = living.get();
        synchronized (this) {
            int unseen = 0;
            boolean seasonal = false;
            for (Map.Entry<Integer, Boolean> c : chars.entrySet()) {
                if (!state.places.containsKey("char:" + c.getKey())) unseen++;
                seasonal |= Boolean.TRUE.equals(c.getValue());
            }
            return new Gaps(unseen, !state.places.containsKey(VAULT), seasonal && !state.places.containsKey(SEASONAL_VAULT));
        }
    }

    // ---- first start --------------------------------------------------------------------------------------------

    /**
     * On the first start with this store: characters from their saved slots, and the vaults as an earlier version
     * last saw them (without their slot positions), until the character list and the next vault visits replace them.
     */
    public void seed(Collection<Listed> saved, Map<String, Map<Integer, Integer>> earlierVaults) {
        synchronized (this) {
            if (!state.places.isEmpty()) return;
            long now = System.currentTimeMillis();
            for (Listed l : saved) {
                if (l.slots() == null) continue;
                Place p = new Place();
                List<Slot> slots = new ArrayList<>();
                for (int i = 0; i < l.slots().length; i++) {
                    int[] e = l.enchantIds() != null && i < l.enchantIds().length ? l.enchantIds()[i] : null;
                    slots.add(new Slot(l.slots()[i], e == null ? null : toList(e)));
                }
                p.sections.put(SLOTS, slots);
                if (l.quick() != null) p.sections.put(QUICK, quickSlots(l.quick(), null));
                p.seenAt = 0;
                p.source = "saved characters";
                state.places.put("char:" + l.charId(), p);
            }
            earlierVaults.forEach((key, byItem) -> {
                Place p = new Place();
                List<Slot> slots = new ArrayList<>();
                byItem.forEach((item, n) -> {
                    for (int i = 0; i < n; i++) slots.add(new Slot(item, null));
                });
                p.sections.put(VAULT_CHEST, slots);
                p.seenAt = 0;
                p.source = "earlier version";
                state.places.put(key, p);
            });
            if (state.places.isEmpty()) return;
            journal.line("start", "seeded " + state.places.size() + " places from saved data (until the game shows them)");
        }
        changed();
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** Where on a character a slot is, by its game index. */
    static String slotName(int i) {
        return i < 4 ? "Equipped" : i < 12 ? "Inventory" : i < 20 ? "Backpack" : "Extender";
    }

    private void add(Visit v, String section, int[] ids, String enchants) {
        if (ids == null || ids.length == 0) return;
        if (!plausible(ids) && !v.broken) {
            v.broken = true;
            v.why = section + " did not decode as items";
        }
        String[] codes = enchants == null ? null : enchants.split(",", -1);
        boolean aligned = codes != null && codes.length == ids.length;   // only trusted when one per slot
        List<Slot> slots = v.sections.computeIfAbsent(section, k -> new ArrayList<>());
        for (int i = 0; i < ids.length; i++) {
            slots.add(new Slot(ids[i], aligned ? LootEvents.enchantIds(codes[i]) : null));
        }
    }

    /** True if (nearly) every filled slot holds an item the game knows: guards against reading garbage. */
    private boolean plausible(int[] ids) {
        int used = 0, known = 0;
        for (int id : ids) {
            if (id < 1) continue;
            used++;
            if (!items.name(id).startsWith("#")) known++;
        }
        return known >= used * 0.9;
    }

    private static List<Slot> quickSlots(int[] quick, List<Slot> before) {
        List<Slot> out = new ArrayList<>();
        for (int k = 0; k < quick.length; k++) {
            if (quick[k] == Live.UNKNOWN) {
                out.add(before != null && k < before.size() ? before.get(k) : new Slot());
            } else {
                out.add(new Slot(quick[k], null));
            }
        }
        return out;
    }

    /** Replaces a place whole, journaling what came and went. True if anything changed. */
    private boolean replace(String key, Place before, Place now, String why) {
        state.places.put(key, now);
        Map<Integer, Integer> was = collectibles(before), is = collectibles(now);
        List<String> parts = new ArrayList<>();
        Set<Integer> ids = new HashSet<>(was.keySet());
        ids.addAll(is.keySet());
        int gained = 0, lost = 0;
        for (int id : ids) {
            int d = is.getOrDefault(id, 0) - was.getOrDefault(id, 0);
            if (d == 0) continue;
            if (d > 0) gained += d;
            else lost -= d;
            parts.add((d > 0 ? "+" : "") + d + " " + items.name(id));
        }
        parts.sort(String::compareTo);
        int total = is.values().stream().mapToInt(Integer::intValue).sum();
        journal.line(key, why + ": " + total + " copies (" + (before == null ? "new" : "+" + gained + " / -" + lost) + ")"
                + (parts.isEmpty() ? "" : "  " + String.join(", ", parts)));
        return true;
    }

    /** Copies of collectibles in a place, by item. */
    private Map<Integer, Integer> collectibles(Place p) {
        Map<Integer, Integer> out = new HashMap<>();
        if (p == null) return out;
        for (List<Slot> slots : p.sections.values()) {
            for (Slot s : slots) if (!s.empty() && items.collectible(s.item)) out.merge(s.item, 1, Integer::sum);
        }
        return out;
    }

    /** How many collectible copies two places share, one for one. */
    private int overlap(Place a, Place b) {
        if (a == null || b == null) return 0;
        Map<Integer, Integer> x = collectibles(a), y = collectibles(b);
        int n = 0;
        for (Map.Entry<Integer, Integer> e : x.entrySet()) n += Math.min(e.getValue(), y.getOrDefault(e.getKey(), 0));
        return n;
    }

    private String describe(Place p) {
        if (p == null) return "nothing";
        int n = collectibles(p).values().stream().mapToInt(Integer::intValue).sum();
        return n + " copies";
    }

    private void note(List<String> lines, String where, Slot old, Slot now) {
        boolean was = !old.empty() && items.collectible(old.item), is = !now.empty() && items.collectible(now.item);
        if (!was && !is) return;   // potions, keys and the like come and go all the time
        lines.add(where + ": " + label(old) + " -> " + label(now));
    }

    private String label(Slot s) {
        if (s.empty()) return "empty";
        return items.name(s.item) + (s.enchantIds == null ? "" : " " + s.enchantIds);
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> out = new ArrayList<>(a.length);
        for (int x : a) out.add(x);
        return out;
    }

    private static Map<Integer, Boolean> livingFromStore() {
        Map<Integer, Boolean> out = new HashMap<>();
        for (CharacterStore.Char c : CharacterStore.get().all()) if (c.alive) out.put(c.charId, c.seasonal);
        return out;
    }

    private void changed() {
        counts = null;
        save();
        for (Runnable r : listeners) r.run();
    }

    private synchronized void save() {
        if (!persist) return;
        try {
            Path tmp = Path.of(file + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(state, w);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save " + file + ": " + e.getMessage());
        }
    }

    /** Test access: the slots of a place's section (null if there is none). */
    synchronized List<Slot> slots(String place, String section) {
        Place p = state.places.get(place);
        return p == null ? null : p.sections.get(section);
    }

    /**
     * The journal: one line per change, always written (independent of the log setting). Rolled over to
     * holdings-journal.old.txt at 2 MB. Lines are also kept in memory for tests when there is no file.
     */
    static final class Journal {
        private static final long MAX = 2_000_000;
        private final Path file;
        final List<String> lines = new ArrayList<>();

        Journal(Path file) {
            this.file = file;
        }

        synchronized void line(String place, String text) {
            String l = java.time.LocalDateTime.now().withNano(0) + "  " + String.format("%-15s", place) + " " + text;
            if (file == null) {
                lines.add(l);
                return;
            }
            try {
                if (Files.exists(file) && Files.size(file) > MAX) {
                    Files.move(file, Path.of(file.toString().replace(".txt", ".old.txt")), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.writeString(file, l + System.lineSeparator(), StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception e) {
                System.err.println("Could not write " + file + ": " + e.getMessage());
            }
        }
    }
}
