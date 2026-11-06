package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What you have right now, place by place: every character (from the character list the game sends, and the live
 * inventory of the one you play) and your vaults with their gift chests (from the last visit of each, seasonal and
 * not). Each place's list is replaced whole whenever the game shows that place, so nothing is guessed: a copy is
 * where it was seen. Items have no ids of their own, so copies are only told apart by what the game shows of them
 * (enchantments, season). The finds and removals (CollectionStore) are a separate record of what happened.
 * <p>
 * In the vault, whatever leaves your character goes into the vault and whatever arrives comes out of it (the game
 * sends the vault's contents only on entering), except what was just forged, fed, used up or dropped.
 * Kept in whereabouts.json.
 */
public final class Whereabouts {
    private static final Path FILE = Path.of("whereabouts.json");
    private static final Gson GSON = new GsonBuilder().create();
    private static final Whereabouts INSTANCE = new Whereabouts();
    public static final String VAULT = "vault", SEASONAL_VAULT = "vault:seasonal";

    /** One copy in a place: its enchantment slots (null = unknown), season and slot ("Equipped", "Vault chest"...). */
    private static final class Copy {
        List<Integer> enchantIds;
        Boolean seasonal;
        String place;
    }

    private static final class State {
        Map<String, Map<Integer, List<Copy>>> places = new HashMap<>();   // "char:506", "vault", "vault:seasonal"
        Map<String, Long> seenAt = new HashMap<>();                        // place -> last look at it (epoch ms)
    }

    /**
     * One copy you have: where (charId 0 for a vault; vault is VAULT or SEASONAL_VAULT), in which slot, its
     * enchantments and season, and when that place was last looked at.
     */
    public record Held(int charId, String vault, String slot, List<Integer> enchantIds, Boolean seasonal, long seenAt) {
    }

    /** What is not known yet: living characters never looked at, and vaults never visited. */
    public record Gaps(int characters, boolean vault, boolean seasonalVault) {
        public boolean none() {
            return characters == 0 && !vault && !seasonalVault;
        }
    }

    private State state = new State();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile Map<Integer, Integer> counts;   // item -> copies, null after a change
    private volatile String inVault;   // the vault place while you are in that vault (moves are mirrored), else null
    /** Copies forged, fed, used up or dropped in the last moments: they left the character, but not into the vault. */
    private final List<Gone> gone = new ArrayList<>();
    /** Copies just taken to have gone into the vault, in case a removal for them comes in right after. */
    private final List<Gone> putAway = new ArrayList<>();
    private static final long GONE_MS = 30_000;

    private record Gone(int item, Copy copy, String place, long at) {
    }

    public static Whereabouts get() {
        return INSTANCE;
    }

    private Whereabouts() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            State s = GSON.fromJson(r, State.class);
            if (s != null && s.places != null) state = s;
            if (state.seenAt == null) state.seenAt = new HashMap<>();
            long saved = Files.getLastModifiedTime(FILE).toMillis();   // places saved before looks were timed: as of the file
            for (String place : state.places.keySet()) state.seenAt.putIfAbsent(place, saved);
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    // ---- what the game shows -----------------------------------------------------------------------------------

    /** Every living character from the character list: replaces all characters (dead ones drop out). */
    public void characters(Map<Integer, Map<Integer, List<CollectionStore.Find>>> byChar) {
        synchronized (this) {
            state.places.keySet().removeIf(k -> k.startsWith("char:"));
            state.seenAt.keySet().removeIf(k -> k.startsWith("char:"));
            long now = System.currentTimeMillis();
            byChar.forEach((id, copies) -> {
                state.places.put("char:" + id, slim(copies));
                state.seenAt.put("char:" + id, now);
            });
        }
        changed();
    }

    /** The character you play, from its live inventory (equipment, inventory, backpack, quick slots). */
    public void character(int charId, Map<Integer, List<CollectionStore.Find>> copies) {
        Map<Integer, List<Copy>> now = slim(copies);
        synchronized (this) {
            Map<Integer, List<Copy>> before = state.places.get("char:" + charId);
            boolean first = !state.seenAt.containsKey("char:" + charId);
            state.seenAt.put("char:" + charId, System.currentTimeMillis());   // saved with the next change
            if (same(before, now) && !first) return;   // the scan runs every few seconds
            state.places.put("char:" + charId, now);
            String vault = inVault;
            if (vault != null && before != null) {
                Map<Integer, List<Copy>> chest = state.places.computeIfAbsent(vault, k -> new HashMap<>());
                putAway(before, now, chest, vault);
                takeOut(now, before, chest);
            }
        }
        changed();
    }

    /** A vault visit: the vault chest and gift chest together, seasonal or not. */
    public void vault(boolean seasonal, Map<Integer, List<CollectionStore.Find>> copies) {
        String place = seasonal ? SEASONAL_VAULT : VAULT;
        synchronized (this) {
            state.places.put(place, slim(copies));
            state.seenAt.put(place, System.currentTimeMillis());
            inVault = place;
        }
        changed();
    }

    /** A new map: leaving the vault ends the mirroring of moves (a vault visit starts it again). */
    public void entered(boolean vault) {
        if (!vault) inVault = null;
    }

    /**
     * A copy left you for good (forged, fed, used up, dropped): it did not go into the vault. Taken off its
     * character now, so the next scan does not see it leave; if a scan already took it into the vault, it comes
     * back out.
     */
    public void removed(int item, CollectionStore.Find copy) {
        Copy c = new Copy();
        c.enchantIds = copy == null ? null : copy.enchantIds;
        c.seasonal = copy == null ? null : copy.seasonal;
        CharacterStore.Char me = CharacterStore.get().playing();
        int charId = copy != null && copy.seen != null && copy.seen.charId > 0 ? copy.seen.charId : me != null ? me.charId : 0;
        long now = System.currentTimeMillis();
        synchronized (this) {
            Gone put = putAway.stream().filter(g -> g.item() == item && now - g.at() <= GONE_MS && like(g.copy(), c))
                    .findFirst().orElse(null);
            if (put != null) {   // a scan already counted it as put away
                putAway.remove(put);
                List<Copy> there = state.places.getOrDefault(put.place(), Map.of()).get(item);
                if (there != null) there.remove(put.copy());
            } else {
                List<Copy> held = state.places.getOrDefault("char:" + charId, Map.of()).get(item);
                Copy mine = held == null ? null : held.stream().filter(x -> like(x, c)).findFirst().orElse(null);
                if (mine != null) held.remove(mine);
                else gone.add(new Gone(item, c, null, now));   // the scan has not seen it leave yet
            }
        }
        changed();
    }

    // ---- what you have -----------------------------------------------------------------------------------------

    /** Every copy of the item you have, characters first (as the game lists them), then the vaults. */
    public List<Held> copies(int item) {
        List<Held> out = new ArrayList<>();
        Map<Integer, CharacterStore.Char> chars = living();
        synchronized (this) {
            for (Map.Entry<String, Map<Integer, List<Copy>>> place : state.places.entrySet()) {
                List<Copy> copies = place.getValue().get(item);
                if (copies == null || copies.isEmpty()) continue;
                String key = place.getKey();
                boolean character = key.startsWith("char:");
                int charId = character ? Integer.parseInt(key.substring(5)) : 0;
                if (character && !chars.containsKey(charId)) continue;   // died since
                long at = state.seenAt.getOrDefault(key, 0L);
                for (Copy c : copies) {
                    String slot = c.place != null ? c.place : character ? "Inventory" : "Vault chest";
                    out.add(new Held(charId, character ? null : key, slot, c.enchantIds, c.seasonal, at));
                }
            }
        }
        out.sort(java.util.Comparator.comparing((Held h) -> h.charId() == 0).thenComparingInt(Held::charId));
        return out;
    }

    /** How many copies of the item you have (kept until the next change, as the grid asks for every item). */
    public int count(int item) {
        Map<Integer, Integer> c = counts;
        if (c == null) {
            c = new HashMap<>();
            Map<Integer, CharacterStore.Char> chars = living();
            synchronized (this) {
                for (Map.Entry<String, Map<Integer, List<Copy>>> place : state.places.entrySet()) {
                    String key = place.getKey();
                    if (key.startsWith("char:") && !chars.containsKey(Integer.parseInt(key.substring(5)))) continue;
                    for (Map.Entry<Integer, List<Copy>> e : place.getValue().entrySet()) c.merge(e.getKey(), e.getValue().size(), Integer::sum);
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
        Map<Integer, CharacterStore.Char> chars = living();
        synchronized (this) {
            int unseen = 0;
            boolean seasonal = false;
            for (CharacterStore.Char c : chars.values()) {
                if (!state.seenAt.containsKey("char:" + c.charId)) unseen++;
                seasonal |= c.seasonal;
            }
            return new Gaps(unseen, !state.seenAt.containsKey(VAULT), seasonal && !state.seenAt.containsKey(SEASONAL_VAULT));
        }
    }

    private static Map<Integer, CharacterStore.Char> living() {
        Map<Integer, CharacterStore.Char> out = new HashMap<>();
        for (CharacterStore.Char c : CharacterStore.get().all()) if (c.alive) out.put(c.charId, c);
        return out;
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    /**
     * Copies in from that are not in to (one for one) go into the chest, except those just forged, fed, used up or
     * dropped (in the vault the forge is at hand).
     */
    private void putAway(Map<Integer, List<Copy>> from, Map<Integer, List<Copy>> to, Map<Integer, List<Copy>> chest, String place) {
        long now = System.currentTimeMillis();
        gone.removeIf(g -> now - g.at() > GONE_MS);
        putAway.removeIf(g -> now - g.at() > GONE_MS);
        from.forEach((item, copies) -> {
            for (Copy c : minus(copies, to.getOrDefault(item, List.of()))) {
                Gone used = gone.stream().filter(g -> g.item() == item && like(g.copy(), c)).findFirst().orElse(null);
                if (used != null) {
                    gone.remove(used);
                    continue;
                }
                Copy stored = new Copy();
                stored.enchantIds = c.enchantIds;
                stored.seasonal = c.seasonal;
                stored.place = "Vault chest";
                chest.computeIfAbsent(item, k -> new ArrayList<>()).add(stored);
                putAway.add(new Gone(item, stored, place, now));
            }
        });
    }

    /** Copies in from that are not in to (one for one) come out of the chest. */
    private static void takeOut(Map<Integer, List<Copy>> from, Map<Integer, List<Copy>> to, Map<Integer, List<Copy>> chest) {
        from.forEach((item, copies) -> {
            List<Copy> there = chest.get(item);
            if (there == null) return;
            for (Copy c : minus(copies, to.getOrDefault(item, List.of()))) {
                there.stream().filter(x -> like(x, c)).findFirst().ifPresent(there::remove);
            }
        });
    }

    /** The copies of a not in b, one for one (by enchantments and season: a move between slots is no move). */
    private static List<Copy> minus(List<Copy> a, List<Copy> b) {
        List<Copy> rest = new ArrayList<>(a);
        for (Copy c : b) {
            rest.stream().filter(x -> java.util.Objects.equals(x.enchantIds, c.enchantIds)
                    && java.util.Objects.equals(x.seasonal, c.seasonal)).findFirst().ifPresent(rest::remove);
        }
        return rest;
    }

    /** The same copy as far as can be told: the same enchantments (or unknown on either side) and season. */
    private static boolean like(Copy a, Copy b) {
        boolean ench = a.enchantIds == null || b.enchantIds == null || a.enchantIds.equals(b.enchantIds);
        return ench && (a.seasonal == null || b.seasonal == null || a.seasonal.equals(b.seasonal));
    }

    private void changed() {
        counts = null;
        save();
        for (Runnable r : listeners) r.run();
    }

    /** The finds as the scans make them: enchantments, season, and the slot in bag ("Equipped", "Gift chest"...). */
    private static Map<Integer, List<Copy>> slim(Map<Integer, List<CollectionStore.Find>> copies) {
        Map<Integer, List<Copy>> out = new HashMap<>();
        copies.forEach((item, finds) -> {
            List<Copy> list = new ArrayList<>();
            for (CollectionStore.Find f : finds) {
                Copy c = new Copy();
                c.enchantIds = f.enchantIds;
                c.seasonal = f.seasonal;
                c.place = f.bag == null || f.bag.isBlank() ? null : f.bag;
                list.add(c);
            }
            out.put(item, list);
        });
        return out;
    }

    private static boolean same(Map<Integer, List<Copy>> a, Map<Integer, List<Copy>> b) {
        return a != null && GSON.toJson(a).equals(GSON.toJson(b));
    }

    private synchronized void save() {
        try {
            Path tmp = Path.of(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(state, w);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save " + FILE + ": " + e.getMessage());
        }
    }
}
