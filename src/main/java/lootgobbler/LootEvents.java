package lootgobbler;

import packets.data.StatData;
import packets.data.enums.StatType;
import packets.incoming.MapInfoPacket;
import packets.incoming.VaultContentPacket;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.realmshark.ParseEnchants;
import tomato.realmshark.enums.LootBags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feeds the collection from what the sniffer sees: loot bags (Tomato's LootGUI), vault contents and
 * your own inventory. Also triggers Discord alerts for drops. All methods run on the packet thread.
 */
public final class LootEvents {
    /**
     * One item in a bag, with the number of enchantments it carries (used as its rarity).
     */
    public record Drop(int id, int enchants, List<Integer> enchantIds) {
        public Drop(int id, int enchants) {
            this(id, enchants, null);
        }
    }

    private static final String EMPTY_ENCHANT = "AAIE_f_9__3__f8=";
    private static final Map<Integer, List<CollectionStore.Find>> vaultVisit = new LinkedHashMap<>();
    private static final long INVENTORY_INTERVAL_MS = 5000;
    private static long lastInventoryScan;

    public static void bagDropped(MapInfoPacket map, Entity bag, Entity player) {
        try {
            List<Drop> drops = readBag(bag);
            if (drops.isEmpty()) return;
            ItemDb db = ItemDb.get();
            String where = mapName(map), bagName = bagName(bag.objectType);
            boolean recorded = false;
            for (Drop d : drops) {
                ItemDb.Item item = db.item(d.id());
                if (item != null && item.isCollectible()) {
                    CollectionStore.Find f = d.enchantIds() != null
                            ? CollectionStore.Find.of(CollectionStore.DROP, d.enchantIds(), where, bagName)
                            : CollectionStore.Find.of(CollectionStore.DROP, d.enchants(), where, bagName);
                    f.seasonal = seasonal(player);
                    CollectionStore.get().record(d.id(), f);
                    recorded = true;
                }
            }
            if (recorded) ServerSync.get().uploadSoon();   // shows up in the guild's caravan
            DiscordAlerts.bagDropped(map, bag, player, drops);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Every vault packet (there can be several per visit). Recorded directly, independent of Tomato's own
     * vault handling, which skips packets that arrive before your character is known.
     */
    public static void vaultPacket(VaultContentPacket p, Entity player) {
        try {
            writeVaultDebug(p);
            // what you have: the vault slot by slot, saved once the visit is complete and its season known
            if (Holdings.get().vaultPacket(p)) saveVault(player);
            // a vault can arrive as several packets; collect copies until the one marked last, then record once
            Boolean seasonal = seasonal(player);
            int vault = addContainer(vaultVisit, p.vaultContents, p.vaultChestEnchants, "Vault chest", seasonal);
            int gift = addContainer(vaultVisit, p.giftContents, p.giftChestEnchants, "Gift chest", seasonal);
            System.out.println("Vault: " + vault + " vault + " + gift + " gift items"
                    + (p.partial ? "  (packet only partly decoded)" : ""));
            if (p.lastVaultPacket) {
                // the vault usually arrives before your character does (it is forgotten on every map change), and
                // which vault it is (seasonal or not) is only known from the character: until then it waits
                if (player != null) vaultDone(player);
                else vaultPending = true;
            } else if (player != null) {
                scanInventory(player);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** One of your characters by id, from the character store; null if not known. */
    private static CharacterStore.Char character(int charId) {
        return CharacterStore.get().all().stream().filter(c -> c.charId == charId).findFirst().orElse(null);
    }

    /** Where on the character a copy is, by its slot index: 0-3 worn, anything else (inventory, backpack) inventory. */
    public static String slotName(int i) {
        return i < 4 ? "Equipped" : "Inventory";
    }

    private static boolean vaultPending;   // a whole vault came in before your character: recorded once it is known

    /** Records a vault visit with the character in it: the vault's seasonality, and your inventory beside it. */
    private static void vaultDone(Entity player) {
        vaultPending = false;
        CharacterStore.Char me = character(player.charId());
        boolean seasonal = me != null ? me.seasonal : player.isSeasonal();   // the stat can still be missing on the first tick
        for (List<CollectionStore.Find> finds : vaultVisit.values()) for (CollectionStore.Find f : finds) f.seasonal = seasonal;
        // vault and inventory together: an item can't be in both, so this is everything you hold right now
        inventoryFinds(player).forEach((id, finds) -> vaultVisit.computeIfAbsent(id, k -> new ArrayList<>()).addAll(finds));
        int added = CollectionStore.get().recordHeld(vaultVisit);
        System.out.println("Vault (" + (seasonal ? "seasonal" : "regular") + "): " + added + " copies new to the collection");
        vaultVisit.clear();
    }

    /**
     * Called every game tick; checks your equipment, inventory and backpack every few seconds.
     */
    public static void inventoryTick(TomatoData data) {
        if (data.player != null && Holdings.get().vaultWaiting()) saveVault(data.player);
        if (vaultPending && data.player != null) {
            try {
                vaultDone(data.player);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        long now = System.currentTimeMillis();
        if (data.player == null || now - lastInventoryScan < INVENTORY_INTERVAL_MS) return;
        lastInventoryScan = now;
        try {
            scanInventory(data.player);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void scanInventory(Entity player) {
        Map<Integer, Integer> chestsBefore = chestSlots;
        int charBefore = chestChar;
        Map<Integer, List<CollectionStore.Find>> held = inventoryFinds(player);
        if (charBefore == player.charId()) openedChests(chestsBefore);   // first, so the copy is not counted twice
        int added = held.isEmpty() ? 0 : CollectionStore.get().recordHeld(held);
        if (added > 0) System.out.println("Inventory: " + added + " copies new to the collection");
    }

    /**
     * What the game sent of the character you play: on entering a map (every stat) and in each tick (only the stats
     * that changed). Only the slots sent change what you have.
     */
    public static void playerStatus(int charId, Entity player, packets.data.ObjectStatusData status) {
        if (charId <= 0 || status == null || status.stats == null) return;
        try {
            StatData pack = player == null ? null : player.stat.get(StatType.BACKPACK_SLOTS_STAT);
            java.util.List<Holdings.Slot> before = Holdings.get().slots("char:" + charId, Holdings.SLOTS);
            int packSlots = pack != null ? pack.statValue : before != null ? Math.max(0, before.size() - 12) : 0;
            Holdings.Live seen = liveSlots(status.stats, 12 + Math.max(0, Math.min(16, packSlots)));
            if (seen != null) Holdings.get().live(charId, seen);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Saves a completed vault visit as the seasonal or regular vault. The season comes from the game's own stat
     * for this character, else from the character list; until one of them is known, the visit waits.
     */
    private static void saveVault(Entity player) {
        if (player == null) return;
        StatData stat = player.stat.get(StatType.SEASONAL);
        CharacterStore.Char me = character(player.charId());
        Boolean seasonal = stat != null ? (Boolean) (stat.statValue == 1) : me != null ? (Boolean) me.seasonal : null;
        String how = stat != null ? "from the character's stat" : "from the character list";
        if (stat != null && me != null && me.seasonal != (stat.statValue == 1)) how += " (the character list said otherwise)";
        Holdings.get().vaultSeason(seasonal, how);
    }

    /**
     * The slots in one status of your character (n slots: equipment, inventory, then the packs; and the quick slots):
     * UNKNOWN where the status does not carry that slot. Null if it carries no slot and no enchantments at all.
     */
    @SuppressWarnings("unchecked")
    static Holdings.Live liveSlots(StatData[] stats, int n) {
        int[] items = new int[n];
        java.util.Arrays.fill(items, Holdings.Live.UNKNOWN);
        int[] quick = new int[QUICKSLOTS.length];
        java.util.Arrays.fill(quick, Holdings.Live.UNKNOWN);
        String codes = null;
        boolean any = false, anyQuick = false;
        int inv0 = StatType.INVENTORY_0_STAT.get(), pack0 = StatType.BACKPACK_0_STAT.get();
        for (StatData sd : stats) {
            int t = sd.statTypeNum, i = -1;
            if (t >= inv0 && t < inv0 + 12) i = t - inv0;
            else if (t >= pack0 && t < pack0 + 16) i = 12 + t - pack0;
            if (i >= 0 && i < n) {
                items[i] = sd.statValue < 1 ? -1 : sd.statValue;
                any = true;
                continue;
            }
            if (t == StatType.UNIQUE_DATA_STRING.get()) {
                codes = sd.stringStatValue;
                continue;
            }
            for (int k = 0; k < QUICKSLOTS.length; k++) {
                if (t == QUICKSLOTS[k].get()) {
                    quick[k] = sd.statValue < 1 ? -1 : sd.statValue;
                    anyQuick = true;
                }
            }
        }
        List<Integer>[] ench = new List[n];
        if (codes != null) {   // one code per slot, every slot
            String[] c = codes.split(",", -1);
            for (int i = 0; i < n && i < c.length; i++) ench[i] = enchantIds(c[i]);
        }
        if (!any && !anyQuick && codes == null) return null;
        return new Holdings.Live(items, ench, anyQuick ? quick : null);
    }

    /**
     * A chest item (Eerie Music Box, mystery ST chests...) turns into the item it grants, in the same slot. Such an
     * item is a new copy, counted like a drop ("Drop · Eerie Music Box"); without this, a second copy of an item
     * you already have would only be counted once both are seen at the vault.
     */
    private static void openedChests(Map<Integer, Integer> chestsBefore) {
        // only as many as chests of that kind went away: moving a chest and putting an item in its slot is no opening
        Map<Integer, Integer> gone = new HashMap<>();
        for (int chest : chestsBefore.values()) gone.merge(chest, 1, Integer::sum);
        for (int chest : chestSlots.values()) gone.merge(chest, -1, Integer::sum);
        boolean any = false;
        for (Map.Entry<Integer, Integer> c : chestsBefore.entrySet()) {
            Integer now = slotItems.get(c.getKey());
            CollectionStore.Find copy = slotCopies.get(c.getKey());
            if (now == null || copy == null) continue;   // still the chest, emptied, or not a collectible
            if (gone.getOrDefault(c.getValue(), 0) <= 0) continue;
            gone.merge(c.getValue(), -1, Integer::sum);
            ItemDb.Item chest = ItemDb.get().item(c.getValue());
            String from = chest == null ? "Chest" : chest.name;
            CollectionStore.Find f = copy.enchantIds != null
                    ? CollectionStore.Find.of(CollectionStore.DROP, copy.enchantIds, from, "")
                    : CollectionStore.Find.of(CollectionStore.DROP, -1, from, "");
            f.seasonal = copy.seasonal;
            CollectionStore.get().record(now, f);
            AppLog.log("items", "Opened " + from + ": " + ItemDb.get().item(now).name);
            any = true;
        }
        if (any) ServerSync.get().uploadSoon();
    }

    private static volatile Map<Integer, Integer> chestSlots = Map.of();   // slot -> chest item, as of the last scan
    private static volatile int chestChar;                                 // the character those slots belong to

    /** The collectible copy in each slot (equipment, inventory, backpack) as of the last scan, by slot index. */
    private static volatile Map<Integer, CollectionStore.Find> slotCopies = Map.of();
    private static volatile Map<Integer, Integer> slotItems = Map.of();
    /** Your three quick slots' item stats, and the slot numbers they get here (after every real slot). */
    public static final StatType[] QUICKSLOTS = {StatType.POTION_ONE_TYPE, StatType.POTION_TWO_TYPE, StatType.POTION_THREE_TYPE};
    private static final int QUICKSLOT_FIRST = 1000;

    /**
     * The copy of the item in a slot of yours as of the last inventory scan (its enchantments and whether the
     * character is seasonal), or null if that slot held something else. For forging and feeding, which name slots.
     */
    public static CollectionStore.Find copyInSlot(int slot, int itemId) {
        Integer there = slotItems.get(slot);
        return there != null && there == itemId ? slotCopies.get(slot) : null;
    }

    /**
     * Collectible items in your equipment, inventory and backpack: item id to one Find per copy.
     */
    private static Map<Integer, List<CollectionStore.Find>> inventoryFinds(Entity player) {
        ItemDb db = ItemDb.get();
        Map<Integer, List<CollectionStore.Find>> held = new LinkedHashMap<>();
        Map<Integer, CollectionStore.Find> copies = new HashMap<>();
        Map<Integer, Integer> items = new HashMap<>();
        Map<Integer, Integer> chests = new HashMap<>();
        // enchant codes for every slot (equipment, inventory, backpack), same per-slot list loot bags use
        StatData udata = player.stat.get(StatType.UNIQUE_DATA_STRING);
        String[] codes = udata == null || udata.stringStatValue == null ? new String[0] : udata.stringStatValue.split(",", -1);
        // this very character (not "the one played", which lags behind a switch), as it looks now; its season from
        // the character list, as the entity's stat can still be missing on the first tick
        CharacterStore.Char me = character(player.charId());
        CollectionStore.Seen here = me != null ? CollectionStore.Seen.character(me) : null;
        boolean seasonal = me != null ? me.seasonal : player.isSeasonal();
        for (int i = 0; i < 28; i++) {
            boolean backpack = i >= 12;
            int stat = backpack ? StatType.BACKPACK_0_STAT.get() + (i - 12) : StatType.INVENTORY_0_STAT.get() + i;
            StatData sd = player.stat.get(stat);
            if (sd == null || sd.statValue < 1) continue;
            ItemDb.Item item = db.item(sd.statValue);
            if (item != null && item.chest) chests.put(i, sd.statValue);
            if (item == null || !item.isCollectible()) continue;
            // live enchant codes: one per slot, equipment, inventory and backpack alike; when they are missing, use
            // the character list's data for that slot, if the same item is still there
            List<Integer> ids = i < codes.length ? enchantIds(codes[i]) : null;
            if (ids == null) {
                int[] known = CharacterStore.get().slotEnchants(player.charId(), i, sd.statValue);
                if (known != null) ids = Arrays.stream(known).boxed().toList();
            }
            String slot = slotName(i);
            CollectionStore.Find f = ids != null
                    ? CollectionStore.Find.of(CollectionStore.INVENTORY, ids, "", slot)
                    : CollectionStore.Find.of(CollectionStore.INVENTORY, -1, "", slot);
            f.seasonal = seasonal;
            f.seen = here;
            held.computeIfAbsent(sd.statValue, k -> new ArrayList<>()).add(f);
            copies.put(i, f);
            items.put(i, sd.statValue);
        }
        // the three quick slots (their own stats, not inventory slots): quick-slot gear such as the Coral Juice Refinery
        for (int k = 0; k < QUICKSLOTS.length; k++) {
            StatData sd = player.stat.get(QUICKSLOTS[k]);
            ItemDb.Item item = sd == null || sd.statValue < 1 ? null : db.item(sd.statValue);
            if (item == null || !item.isCollectible()) continue;
            CollectionStore.Find f = CollectionStore.Find.of(CollectionStore.INVENTORY, -1, "", "Quick slot");
            f.seasonal = seasonal;
            f.seen = here;
            held.computeIfAbsent(sd.statValue, x -> new ArrayList<>()).add(f);
            copies.put(QUICKSLOT_FIRST + k, f);
            items.put(QUICKSLOT_FIRST + k, sd.statValue);
        }
        slotCopies = copies;
        slotItems = items;
        chestSlots = chests;
        chestChar = player.charId();
        writeInventoryDebug(player, codes.length);
        return held;
    }

    private static String lastInventoryDebug = "";

    /**
     * Diagnostics, in inventory-debug.txt next to the app (rewritten only when it changes): every text stat the game
     * sends for your character, and for each backpack slot whether its enchantments are known.
     */
    private static void writeInventoryDebug(Entity player, int liveCodes) {
        StringBuilder sb = new StringBuilder("Character ").append(player.charId()).append(": live enchant codes for ")
                .append(liveCodes).append(" slots\n\nText stats:\n");
        for (int id = 0; id < 200; id++) {
            StatData s = player.stat.get(id);
            if (s != null && s.stringStatValue != null && !s.stringStatValue.isEmpty()) {
                String v = s.stringStatValue;
                sb.append(String.format("  %3d %-24s %s%n", id, StatType.byOrdinal(id), v.length() > 300 ? v.substring(0, 300) + "..." : v));
            }
        }
        sb.append("\nBackpack:\n");
        for (int i = 12; i < 28; i++) {
            StatData sd = player.stat.get(StatType.BACKPACK_0_STAT.get() + (i - 12));
            if (sd == null || sd.statValue < 1) continue;
            int[] known = CharacterStore.get().slotEnchants(player.charId(), i, sd.statValue);
            sb.append(String.format("  slot %2d  item %6d  enchants from char list: %s%n", i, sd.statValue,
                    known == null ? "unknown" : Arrays.toString(known)));
        }
        String text = sb.toString();
        if (text.equals(lastInventoryDebug)) return;
        lastInventoryDebug = text;
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("inventory-debug.txt"), text);
        } catch (Exception ignore) {
        }
    }

    /**
     * True if (nearly) every non-empty slot holds a known equipment item. Guards against reading garbage when the
     * vault packet only partly decodes.
     */
    private static boolean plausible(int[] ids) {
        if (ids == null) return false;
        int used = 0, known = 0;
        ItemDb db = ItemDb.get();
        for (int id : ids) {
            if (id < 1) continue;
            used++;
            if (db.item(id) != null) known++;
        }
        return known >= used * 0.9;
    }

    /**
     * Whether the character is seasonal, or null when it is not known yet (no player entity).
     */
    private static Boolean seasonal(Entity player) {
        return player == null ? null : player.isSeasonal();
    }

    private static int addContainer(Map<Integer, List<CollectionStore.Find>> finds, int[] ids, String enchantData, String label,
                                    Boolean seasonal) {
        if (ids == null) return 0;
        if (!plausible(ids)) {
            System.out.println("Vault: skipped " + label + " (" + ids.length + " slots did not decode as items)");
            return 0;
        }
        // limit -1 keeps trailing empty entries, so one entry per slot even when the last slots have no enchant
        String[] enchants = enchantData == null ? null : enchantData.split(",", -1);
        boolean aligned = enchants != null && enchants.length == ids.length;   // only trust it if it lines up
        ItemDb db = ItemDb.get();
        int count = 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 1) continue;
            count++;
            ItemDb.Item item = db.item(ids[i]);
            if (item == null || !item.isCollectible()) continue;
            CollectionStore.Find f = aligned
                    ? CollectionStore.Find.of(CollectionStore.VAULT, enchantIds(enchants[i]), "Vault", label)
                    : CollectionStore.Find.of(CollectionStore.VAULT, -1, "Vault", label);
            f.seasonal = seasonal;
            f.seen = CollectionStore.Seen.vault(label);
            finds.computeIfAbsent(ids[i], k -> new ArrayList<>()).add(f);
        }
        return count;
    }

    private static final java.nio.file.Path VAULT_DEBUG = java.nio.file.Path.of("vault-contents.txt");
    private static long lastVaultPacket;

    /**
     * Writes what each vault packet contained to vault-contents.txt (a new file per vault visit), so missing
     * items can be traced to the packet itself or to the collection filter.
     */
    private static void writeVaultDebug(VaultContentPacket p) {
        long now = System.currentTimeMillis();
        boolean newVisit = now - lastVaultPacket > 10_000;
        lastVaultPacket = now;
        StringBuilder sb = new StringBuilder();
        sb.append("=== vault packet ").append(java.time.LocalDateTime.now().withNano(0))
                .append("  last=").append(p.lastVaultPacket).append('\n');
        debugList(sb, "vault chest", p.vaultContents, p.vaultChestEnchants);
        debugList(sb, "gift chest", p.giftContents, p.giftChestEnchants);
        try {
            if (newVisit) java.nio.file.Files.writeString(VAULT_DEBUG, sb.toString());
            else java.nio.file.Files.writeString(VAULT_DEBUG, sb.toString(), java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("Could not write " + VAULT_DEBUG + ": " + e.getMessage());
        }
    }

    private static void debugList(StringBuilder sb, String label, int[] ids, String enchants) {
        sb.append("--- ").append(label).append(": ").append(ids == null ? "null" : ids.length + " slots").append('\n');
        if (enchants != null) {
            int entries = enchants.split(",", -1).length;
            sb.append("    enchant entries: ").append(entries)
                    .append(ids != null && entries == ids.length ? " (one per slot - used)" : " (does not match the slots - ignored)").append('\n');
            sb.append("    enchant data: ").append(enchants.length() > 300 ? enchants.substring(0, 300) + "..." : enchants).append('\n');
        }
        if (ids == null) return;
        ItemDb db = ItemDb.get();
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 1) continue;
            ItemDb.Item item = db.item(ids[i]);
            String status = item == null ? "not equipment" : !item.isCollectible() ? "not collectible"
                    : CollectionStore.get().contains(ids[i]) ? "collected" : "NEW";
            sb.append(String.format("    [%3d] %6d  %-40s %s%n", i, ids[i], String.valueOf(assets.IdToAsset.objectName(ids[i])), status));
        }
    }

    static List<Drop> readBag(Entity bag) {
        String[] enchants = null;
        StatData udata = bag.stat.get(StatType.UNIQUE_DATA_STRING);
        if (udata != null && udata.stringStatValue != null) enchants = udata.stringStatValue.split(",");

        List<Drop> drops = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            StatData sd = bag.stat.get(StatType.INVENTORY_0_STAT.get() + i);
            if (sd == null || sd.statValue < 1) continue;
            List<Integer> ids = enchantIds(enchants != null && i < enchants.length ? enchants[i] : null);
            drops.add(new Drop(sd.statValue, ids.size(), ids));
        }
        return drops;
    }

    /**
     * Enchantment slots in an item's enchant code: the enchantment id per filled slot and -1 per empty
     * (unlocked) slot. The list size is the item's rarity (1 uncommon .. 4 divine).
     * Layout (URL-safe base64): header byte, short 1026, exactly four slot shorts, then an optional trailer.
     * A slot is an enchantment id, -1 (empty), -2 (locked) or -3 (no slot); -3 can sit between enchantments,
     * so all four slots are always read.
     */
    static List<Integer> enchantIds(String code) {
        List<Integer> ids = new ArrayList<>();
        if (code == null || code.isEmpty() || code.equals(EMPTY_ENCHANT)) return ids;
        try {
            byte[] raw = tomato.realmshark.PcStatsDecoder.sixBitStringToBytes(code);
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            if (b.remaining() < 3) return ids;
            b.get();   // header
            if (b.getShort() != 1026) return ids;
            for (int slot = 0; slot < 4 && b.remaining() >= 2; slot++) {
                short id = b.getShort();
                if (id == -3 || id == -2) continue;   // no slot / locked: does not count
                ids.add((int) id);                    // enchantment, or -1 for an empty slot
            }
        } catch (Exception ignored) {
            // malformed code: treat as no enchantments
        }
        return ids;
    }

    static String bagName(int objectType) {
        String n = LootBags.lootBagName(objectType);
        if (n == null) return "Loot";
        return n.startsWith("B.") ? "Boosted " + n.substring(2) : n;
    }

    static String mapName(MapInfoPacket map) {
        if (map == null) return "Unknown";
        return map.displayName != null && !map.displayName.isEmpty() && !map.displayName.startsWith("{") ? map.displayName : map.name;
    }

    private LootEvents() {
    }
}
