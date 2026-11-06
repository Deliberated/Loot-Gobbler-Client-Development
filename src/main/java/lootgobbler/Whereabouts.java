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

/**
 * Where your collectible copies are right now, place by place: every character (from the character list the game
 * sends at login, and the live inventory of the one you play) and your vaults with their gift chests (from the last
 * visit of each, seasonal and not). Once all of them are known, a recorded copy found in none of them is marked
 * missing in the collection (CollectionStore.reconcile): sold, traded, given away or used up without the client
 * seeing it. Kept in whereabouts.json, so the check survives a restart.
 */
public final class Whereabouts {
    private static final Path FILE = Path.of("whereabouts.json");
    private static final Gson GSON = new GsonBuilder().create();
    private static final Whereabouts INSTANCE = new Whereabouts();

    /** One copy in a place: its enchantment slots (null = unknown) and whether it is seasonal. */
    private static final class Copy {
        List<Integer> enchantIds;
        Boolean seasonal;
        String place;   // in a vault: "Vault chest" or "Gift chest"
    }

    private static final class State {
        Map<String, Map<Integer, List<Copy>>> places = new HashMap<>();   // "char:506", "vault", "vault:seasonal"
        Map<String, Long> seenAt = new HashMap<>();                        // place -> last look at it (epoch ms)
        long vaultAt, seasonalVaultAt;                                     // epoch ms of the last vault visit, 0 = never
    }

    private State state = new State();
    private volatile String inVault;   // the vault place while you are in that vault (moves are mirrored), else null

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

    /**
     * The character you play, from its live inventory (equipment, inventory, backpack, quick slots). In the vault,
     * whatever leaves the character is taken to have gone into the vault and whatever arrives to have come out of it,
     * as the game sends the vault's contents only on entering.
     */
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
                putAway(before, now, chest);
                takeOut(now, before, chest);
            }
        }
        changed();
    }

    /** A new map: leaving the vault ends the mirroring of moves (a vault visit starts it again). */
    public void entered(boolean vault) {
        if (!vault) inVault = null;
    }

    /** Copies in from that are not in to (one for one) go into the chest. */
    private static void putAway(Map<Integer, List<Copy>> from, Map<Integer, List<Copy>> to, Map<Integer, List<Copy>> chest) {
        from.forEach((item, copies) -> {
            for (Copy c : minus(copies, to.getOrDefault(item, List.of()))) chest.computeIfAbsent(item, k -> new ArrayList<>()).add(c);
        });
    }

    /** Copies in from that are not in to (one for one) come out of the chest. */
    private static void takeOut(Map<Integer, List<Copy>> from, Map<Integer, List<Copy>> to, Map<Integer, List<Copy>> chest) {
        from.forEach((item, copies) -> {
            List<Copy> there = chest.get(item);
            if (there == null) return;
            for (Copy c : minus(copies, to.getOrDefault(item, List.of()))) {
                there.stream().filter(x -> GSON.toJson(x).equals(GSON.toJson(c))).findFirst().ifPresent(there::remove);
            }
        });
    }

    private static List<Copy> minus(List<Copy> a, List<Copy> b) {
        List<Copy> rest = new ArrayList<>(a);
        for (Copy c : b) rest.stream().filter(x -> GSON.toJson(x).equals(GSON.toJson(c))).findFirst().ifPresent(rest::remove);
        return rest;
    }

    /** A vault visit: the vault chest and gift chest together, seasonal or not. */
    public void vault(Boolean seasonal, Map<Integer, List<CollectionStore.Find>> copies) {
        synchronized (this) {
            boolean s = Boolean.TRUE.equals(seasonal);
            state.places.put(s ? "vault:seasonal" : "vault", slim(copies));
            inVault = s ? "vault:seasonal" : "vault";
            if (s) state.seasonalVaultAt = System.currentTimeMillis();
            else state.vaultAt = System.currentTimeMillis();
            state.seenAt.put(s ? "vault:seasonal" : "vault", System.currentTimeMillis());
        }
        changed();
    }

    private void changed() {
        save();
        reconcile();
    }

    /**
     * Hands the full picture to the collection, once it is complete: every living character looked at (from the
     * character list or played with Loot Gobbler running) and a vault visited.
     */
    public void reconcile() {
        Map<Integer, List<CollectionStore.Find>> where = new HashMap<>();
        long charsAt = Long.MAX_VALUE, vault, seasonalVault;
        synchronized (this) {
            vault = state.vaultAt;
            seasonalVault = state.seasonalVaultAt;
            if (vault == 0 && seasonalVault == 0) return;
            Map<Integer, CharacterStore.Char> chars = new HashMap<>();
            for (CharacterStore.Char c : CharacterStore.get().all()) {
                chars.put(c.charId, c);
                if (!c.alive) continue;
                Long at = state.seenAt.get("char:" + c.charId);
                if (at == null) return;   // a character not looked at yet: its copies would seem gone
                charsAt = Math.min(charsAt, at);
            }
            for (Map.Entry<String, Map<Integer, List<Copy>>> place : state.places.entrySet()) {
                boolean character = place.getKey().startsWith("char:");
                CharacterStore.Char c = character ? chars.get(Integer.parseInt(place.getKey().substring(5))) : null;
                place.getValue().forEach((item, copies) -> {
                    for (Copy copy : copies) {
                        // as a find, in case it turns out new: where it is, as the live scans name it
                        CollectionStore.Find f = character
                                ? CollectionStore.Find.of(CollectionStore.INVENTORY, copy.enchantIds, "", "Inventory")
                                : CollectionStore.Find.of(CollectionStore.VAULT, copy.enchantIds, "Vault", copy.place);
                        f.seasonal = copy.seasonal;
                        f.seen = c != null ? CollectionStore.Seen.character(c) : character ? null : CollectionStore.Seen.vault(copy.place);
                        where.computeIfAbsent(item, k -> new ArrayList<>()).add(f);
                    }
                });
            }
        }
        boolean anySeasonal = CharacterStore.get().all().stream().anyMatch(c -> c.alive && c.seasonal);
        long oldest = charsAt;
        int added = CollectionStore.get().reconcile(where, f -> {
            // judged only if found before both looks it could have shown up in
            long look = Boolean.TRUE.equals(f.seasonal) ? seasonalVault
                    : Boolean.FALSE.equals(f.seasonal) ? vault
                    : anySeasonal ? Math.min(vault, seasonalVault) : vault;
            return look > 0 && f.at < Math.min(oldest, look);
        });
        if (added > 0) {
            AppLog.log("items", added + " copies on your characters and in your vaults were new to the collection");
            ServerSync.get().uploadSoon();
        }
    }

    private static Map<Integer, List<Copy>> slim(Map<Integer, List<CollectionStore.Find>> copies) {
        Map<Integer, List<Copy>> out = new HashMap<>();
        copies.forEach((item, finds) -> {
            List<Copy> list = new ArrayList<>();
            for (CollectionStore.Find f : finds) {
                Copy c = new Copy();
                c.enchantIds = f.enchantIds;
                c.seasonal = f.seasonal;
                if (f.seen != null && f.seen.charId == 0) c.place = f.seen.place;
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
