package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.reflect.TypeToken;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.enums.CharacterClass;
import util.PropertiesManager;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Every character you have, from the character list sent at login plus live data while you play: class, skin,
 * level, fame, stats maxed, flags, when it was last played and its fame over time. Saved to characters.json.
 * importList() and live() run on the packet/network threads.
 */
public final class CharacterStore implements Syncable {
    public static final class Sample {
        public long t;
        public long fame;
    }

    public static final class Char {
        public int charId;
        public int classType;
        public String className = "";
        public int skin;
        public int tex1, tex2;         // clothing / accessory dye texture codes (see Dyes), 0 = none
        public int petSkin;            // sprite of the pet following this character, 0 = none / unknown
        public String petName;
        public int level;
        public int maxHp;              // maximum life as the game last reported it (with gear); 0 = unknown
        public long fame;
        public int statsMaxed;
        public boolean seasonal, crucible, ritual, favorite;
        public long favoriteAt;        // when favorite last changed (epoch ms), so syncing keeps the latest choice
        public boolean backpack, extender;
        public boolean alive = true;
        public String diedBy;          // "game" (Dead tag in the char list) or "user" (gravestone clicked); null while alive
        public long lastPlayed;        // epoch ms, 0 = not played since tracking started
        public int[] equipment;        // weapon, ability, armor, ring item ids (-1 = empty); null = unknown
        public int[] enchants;         // enchantment slots (rarity) per equipment slot; null = unknown
        public int[][] enchantIds;     // per equipment slot: enchantment ids, -1 = empty slot; null = unknown
        public int[] slots;            // every slot from the last char list: equipment, inventory, backpack; null = unknown
        public int[] quickslots;       // the three quick slots' items (-1 = empty), seen while playing; null = unknown
        public int[][] slotEnchantIds; // per slot of "slots": enchantment ids
        public List<Sample> samples = new ArrayList<>();

        /**
         * Sprite object id: the equipped skin, or the class itself.
         */
        public int spriteId() {
            return skin > 0 ? skin : classType;
        }
    }

    /**
     * A stretch of play: fame samples less than SESSION_GAP apart.
     */
    public record Session(long start, long end, long gained) {
        public long durationMs() {
            return Math.max(0, end - start);
        }
    }

    private static final Path FILE = Path.of("characters.json");
    private static final Path TAG_FILE = Path.of("character-tags.txt");
    private static final long LIVE_INTERVAL_MS = 5_000, HEARTBEAT_MS = 5 * 60_000, SESSION_GAP_MS = 20 * 60_000;
    private static final int MAX_SAMPLES = 5_000;
    private static final String[] FAVORITE_TAGS = {"Favorite", "Favourite", "IsFavorite", "Favorited", "Locked", "IsLocked"};
    private static final Gson GSON = new Gson();
    private static final CharacterStore INSTANCE = new CharacterStore();

    private static final long MIN_FETCH_MS = 10_000;   // be gentle with the game servers (shared with the game's own login)
    private static final String KEY_PLAYER_NAME = "playerName";
    private static final long DAY_MS = 24 * 3_600_000L;

    private final Map<Integer, Char> chars = new LinkedHashMap<>();
    private volatile int currentCharId;      // character you are playing (or last played this run), 0 = none yet
    private volatile String playerName = PropertiesManager.getProperty(KEY_PLAYER_NAME);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long lastLive, lastSave;
    private volatile IntSupplier fetcher;    // requests the char list; returns characters received, 0 = rejected
    private volatile String fetchedToken;    // login the char list was last fetched for
    private volatile String listToken;       // login a char list actually came in for (null = none yet)
    private volatile long lastFetch;
    private volatile boolean fetching;
    private boolean savedListRead;           // char-list.xml from the last login was read for slot enchantments

    public static CharacterStore get() {
        return INSTANCE;
    }

    private CharacterStore() {
        if (!Files.exists(FILE)) return;
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Map<Integer, Char> loaded = GSON.fromJson(r, new TypeToken<LinkedHashMap<Integer, Char>>() {
            }.getType());
            if (loaded != null) chars.putAll(loaded);
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
        // Repair: an earlier version buried every character missing from a (partial) char list. Deaths without a
        // recorded cause came from that, so bring those characters back.
        for (Char c : chars.values()) {
            if (!c.alive && c.diedBy == null) c.alive = true;
        }
    }

    /**
     * The character list from the game servers (sent at login): adds/updates every living character.
     */
    public void importList(List<RealmCharacter> list) {
        if (list == null) return;
        if (!list.isEmpty() && fetchedToken != null) listToken = fetchedToken;   // this login has its list
        writeTagsOnce(list);
        synchronized (this) {
            for (RealmCharacter r : list) {
                Char c = chars.computeIfAbsent(r.charId, k -> new Char());
                c.charId = r.charId;
                c.classType = r.classNum;
                c.className = className(r.classNum);
                int shown = intTag(r.rawTags, "TextureOverride");   // the skin actually displayed, if overridden
                if (shown > 0) c.skin = shown;
                else if (r.skin > 0) c.skin = r.skin;
                c.tex1 = Dyes.texture(intTag(r.rawTags, "Tex1"));   // the char list names the dye/cloth item
                c.tex2 = Dyes.texture(intTag(r.rawTags, "Tex2"));
                if (r.petSkin > 0 || r.petType > 0) {
                    c.petSkin = r.petSkin > 0 ? r.petSkin : r.petType;
                    c.petName = r.petName;
                }
                c.level = r.level;
                if (r.fame > 0) c.fame = r.fame;
                c.seasonal = r.seasonal;
                c.statsMaxed = maxed(r.classNum, new int[]{r.hp, r.mp, r.atk, r.def, r.spd, r.dex, r.vit, r.wis});
                int slots = intTag(r.rawTags, "BackpackSlots");   // 8 = backpack, more = backpack extender
                c.backpack = r.backpack || slots > 0;
                c.extender = slots > 8 || extender(r);
                if (r.equipment != null && r.equipment.length >= 4) {
                    int[] equip = Arrays.copyOf(r.equipment, 4);
                    if (!Arrays.equals(equip, c.equipment)) {   // filled in by importItemData
                        c.enchants = null;
                        c.enchantIds = null;
                    }
                    c.equipment = equip;
                }
                Boolean fav = favorite(r.rawTags);
                if (fav != null && fav != c.favorite) {   // only when the game actually sends the flag
                    c.favorite = fav;
                    c.favoriteAt = System.currentTimeMillis();
                }
                String dead = r.rawTags.get("Dead");
                if ("true".equalsIgnoreCase(dead)) {
                    c.alive = false;
                    c.diedBy = "game";
                } else if ("false".equalsIgnoreCase(dead) && "game".equals(c.diedBy)) {
                    c.alive = true;
                    c.diedBy = null;
                }
            }
            // Characters missing from the list are left alone: the game sometimes sends only part of the list.
            save();
        }
        fire();
    }

    /**
     * You entered the nexus: fetch the char list (as the Refresh button does) unless one already came in for this
     * login. A refused fetch is tried again on the next nexus visit.
     */
    public void enteredNexus() {
        if (fetcher == null || fetchedToken == null || fetchedToken.equals(listToken)) return;
        fetchAll(null);
    }

    /**
     * The game sent your login token (on every map change). The first time per login, fetch the whole char list.
     */
    public void tokenReady(String token, IntSupplier fetcher) {
        this.fetcher = fetcher;
        if (token == null || token.equals(fetchedToken)) return;
        fetchedToken = token;
        lastFetch = 0;   // new login: don't let the rate limit block the first fetch
        fetchAll(null);
    }

    /**
     * Requests the full character list in the background. done (may be null) gets a short status line.
     * The servers may refuse this outside the Daily Quest Room / Pet Yard; then nothing changes.
     */
    public void fetchAll(Consumer<String> done) {
        Consumer<String> report = done != null ? done : s -> {
        };
        IntSupplier f = fetcher;
        long now = System.currentTimeMillis();
        if (f == null) {
            // the login token is only seen when the client connects to a map, so a game started before the
            // sniffer has to change map once
            report.accept(tomato.gui.TomatoGUI.isSnifferRunning()
                    ? "Open your character screen in-game (or change map), then press Refresh again"
                    : "Start the sniffer first");
            return;
        }
        if (fetching) {
            report.accept("Already fetching...");
            return;
        }
        long wait = lastFetch + MIN_FETCH_MS - now;
        if (wait > 0) {
            report.accept("Try again in " + (wait / 1000 + 1) + "s");
            return;
        }
        fetching = true;
        lastFetch = now;
        String token = fetchedToken;
        Thread t = new Thread(() -> {
            int n = 0;
            try {
                n = f.getAsInt();
                if (n > 0) listToken = token;
            } catch (Exception e) {
                System.err.println("Char list request failed: " + e);
            } finally {
                fetching = false;
            }
            report.accept(n > 0 ? "Got " + n + " characters"
                    : "Refused - open your character screen in-game, or go to the Daily Quest Room / Pet Yard, then press Refresh again");
        }, "char-list-fetch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Called every game tick; updates the character you are playing every few seconds.
     */
    public void live(TomatoData data) {
        long now = System.currentTimeMillis();
        Entity p = data.player;
        if (p == null || now - lastLive < LIVE_INTERVAL_MS) return;
        int id = p.charId();
        if (id <= 0) return;
        lastLive = now;
        currentCharId = id;
        String name = p.name();
        if (name != null && !name.isBlank() && !name.equals(playerName)) {
            playerName = name;
            PropertiesManager.setProperties(KEY_PLAYER_NAME, name);   // still shown after a restart, before login
        }
        StatData rank = p.stat.get(StatType.GUILD_RANK_STAT);
        PlayerProfile.get().update(name, p.getStatGuild(), rank == null ? null : rank.statValue);
        try {
            boolean sampled, changed;
            synchronized (this) {
                Char c = chars.computeIfAbsent(id, k -> new Char());
                String before = shown(c);
                c.charId = id;
                c.classType = p.objectType;
                c.className = className(p.objectType);
                int skin = intStat(p, StatType.SKIN_ID);
                if (!ItemDb.get().isTransformSkin(skin)) {   // not while an ability transforms you (no dyes then)
                    if (skin > 0) c.skin = skin;
                    StatData tex1 = p.stat.get(StatType.TEX1_STAT), tex2 = p.stat.get(StatType.TEX2_STAT);
                    if (tex1 != null) c.tex1 = liveTexture(tex1.statValue);
                    if (tex2 != null) c.tex2 = liveTexture(tex2.statValue);
                }
                int level = intStat(p, StatType.LEVEL_STAT);
                if (level > 0) c.level = level;
                int maxHp = intStat(p, StatType.MAX_HP_STAT);
                if (maxHp > 0) c.maxHp = maxHp;
                int fame = intStat(p, StatType.CURR_FAME_STAT);
                if (fame > 0) c.fame = fame;
                c.seasonal = p.isSeasonal();
                c.crucible = p.isCrucible();
                StatData ritual = p.stat.get(StatType.BloodRitualStat);
                c.ritual = ritual != null && ritual.stringStatValue != null && !ritual.stringStatValue.isBlank()
                        && !ritual.stringStatValue.equals("0");
                if (p.baseStats != null) c.statsMaxed = maxed(p.objectType, p.baseStats);
                StatData pack = p.stat.get(StatType.BACKPACK_SLOTS_STAT);
                if (pack != null) {   // 8 slots = backpack, more = backpack extender
                    c.backpack = pack.statValue > 0;
                    c.extender = pack.statValue > 8;
                }
                readEquipment(p, c);
                int[] quick = new int[LootEvents.QUICKSLOTS.length];
                boolean seen = false;
                for (int k = 0; k < quick.length; k++) {
                    StatData q = p.stat.get(LootEvents.QUICKSLOTS[k]);
                    quick[k] = q == null || q.statValue < 1 ? -1 : q.statValue;
                    seen |= q != null;
                }
                if (seen) c.quickslots = quick;
                if (readSlots(p, c, pack == null ? -1 : pack.statValue)) lastSave = 0;   // save the new packs soon
                c.alive = true;
                c.diedBy = null;
                c.lastPlayed = now;

                Sample last = c.samples.isEmpty() ? null : c.samples.get(c.samples.size() - 1);
                sampled = last == null || last.fame != c.fame || now - last.t >= HEARTBEAT_MS;
                if (sampled) {
                    Sample s = new Sample();
                    s.t = now;
                    s.fame = c.fame;
                    c.samples.add(s);
                    if (c.samples.size() > MAX_SAMPLES) c.samples.remove(0);
                }
                if (sampled || now - lastSave > 60_000) save();
                changed = sampled || !before.equals(shown(c));
            }
            if (changed) fire();   // every 5 s during play: only tell the pages when something they show changed
        } catch (Exception e) {
            // stats can change while being read; try again next tick
        }
    }

    /**
     * Every slot from the live player stats (equipment, inventory, then the backpack and extender), with their
     * enchantments, so the packs shown follow what you pick up, drop or swap instead of the last character list.
     * Enchantments come from the live per-slot codes, else from what was known for the same item in that slot.
     * True if anything changed.
     */
    private static boolean readSlots(Entity p, Char c, int packSlots) {
        if (packSlots < 0) packSlots = c.slots != null ? Math.max(0, c.slots.length - 12) : 0;   // not sent: as before
        packSlots = Math.max(0, Math.min(16, packSlots));
        int[] slots = new int[12 + packSlots];
        for (int i = 0; i < slots.length; i++) {
            int stat = i < 12 ? StatType.INVENTORY_0_STAT.get() + i : StatType.BACKPACK_0_STAT.get() + (i - 12);
            StatData sd = p.stat.get(stat);
            if (sd == null) return false;   // the stats are not all there yet: keep what we have
            slots[i] = sd.statValue;
        }
        StatData udata = p.stat.get(StatType.UNIQUE_DATA_STRING);
        String[] codes = udata == null || udata.stringStatValue == null ? new String[0] : udata.stringStatValue.split(",", -1);
        int[][] ids = new int[slots.length][];
        for (int i = 0; i < slots.length; i++) {
            List<Integer> live = i < codes.length ? LootEvents.enchantIds(codes[i]) : null;
            if (live != null) ids[i] = toArray(live);
            else if (c.slots != null && i < c.slots.length && c.slots[i] == slots[i] && c.slotEnchantIds != null
                    && i < c.slotEnchantIds.length && c.slotEnchantIds[i] != null) ids[i] = c.slotEnchantIds[i];
            else ids[i] = new int[0];
        }
        boolean changed = !Arrays.equals(slots, c.slots) || !Arrays.deepEquals(ids, c.slotEnchantIds);
        c.slots = slots;
        c.slotEnchantIds = ids;
        return changed;
    }

    /** What the pages show of a character, to tell whether a live update changed anything. */
    private static String shown(Char c) {
        return c.classType + "|" + c.level + "|" + c.fame + "|" + c.skin + "|" + c.tex1 + "|" + c.tex2 + "|" + c.seasonal
                + "|" + c.crucible + "|" + c.ritual + "|" + c.statsMaxed + "|" + c.backpack + "|" + c.extender + "|" + c.alive
                + "|" + Arrays.toString(c.equipment) + "|" + Arrays.deepToString(c.enchantIds) + "|" + Arrays.toString(c.slots)
                + "|" + Arrays.toString(c.quickslots)
                + "|" + Arrays.deepToString(c.slotEnchantIds);
    }

    /**
     * A dye stat from the game: a texture code (type in the high byte), or else a dye/cloth item type.
     */
    static int liveTexture(int value) {
        return value == 0 || (value >>> 24) != 0 ? value : Dyes.texture(value);
    }

    /**
     * The four equipped items and their enchantment slots from the live player stats.
     */
    private static void readEquipment(Entity p, Char c) {
        int[] equip = new int[4];
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            StatData sd = p.stat.get(StatType.INVENTORY_0_STAT.get() + i);
            equip[i] = sd == null ? -1 : sd.statValue;
            any |= sd != null;
        }
        if (!any) return;
        // one enchant code per equipment/inventory slot, as in loot bags
        StatData udata = p.stat.get(StatType.UNIQUE_DATA_STRING);
        if (udata != null && udata.stringStatValue != null) {
            String[] codes = udata.stringStatValue.split(",", -1);
            int[][] ids = new int[4][];
            for (int i = 0; i < 4; i++) ids[i] = toArray(LootEvents.enchantIds(i < codes.length ? codes[i] : null));
            setEnchants(c, ids);
        } else if (!Arrays.equals(equip, c.equipment)) {
            c.enchants = null;
            c.enchantIds = null;
        }
        c.equipment = equip;
    }

    /**
     * Enchantment ids per equipment slot; the number of slots (filled or empty) is the item's rarity.
     */
    private static void setEnchants(Char c, int[][] ids) {
        int[] counts = new int[ids.length];
        for (int i = 0; i < ids.length; i++) counts[i] = ids[i].length;
        c.enchantIds = ids;
        c.enchants = counts;
    }

    private static int[] toArray(List<Integer> list) {
        return list.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final java.util.regex.Pattern CHAR_XML = java.util.regex.Pattern.compile("<Char id=\"(\\d+)\">(.*?)</Char>",
            java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern PET_XML = java.util.regex.Pattern.compile("<Pet[ >].*?</Pet>",
            java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern EQUIPMENT_XML = java.util.regex.Pattern.compile("<Equipment>([^<]*)</Equipment>");
    private static final java.util.regex.Pattern ITEM_DATA = java.util.regex.Pattern.compile(
            "<ItemData[^>]*\\btype=\"(\\d+)\"[^>]*>([^<]*)</ItemData>");

    /**
     * Enchantments of every item a character holds, from the raw char list: each character's UniqueItemInfo has an
     * ItemData (item type + enchant code) per item, matched to the slots of its Equipment list (equipment,
     * inventory, backpack) by item type in order. The equipped four also go to the loadout.
     */
    private void importItemData(String xml, boolean live) {
        Map<Integer, Map<Integer, List<CollectionStore.Find>>> held = new HashMap<>();   // char id -> item -> copies
        Map<Integer, CollectionStore.Seen> seen = new HashMap<>();
        synchronized (this) {
            java.util.regex.Matcher ch = CHAR_XML.matcher(xml);
            while (ch.find()) {
                Char c = chars.get(Integer.parseInt(ch.group(1)));
                if (c == null) continue;
                String body = PET_XML.matcher(ch.group(2)).replaceAll("");   // the pet has a UniqueItemInfo of its own
                java.util.regex.Matcher eq = EQUIPMENT_XML.matcher(body);
                int[] slots = eq.find() ? Arrays.stream(eq.group(1).split(",")).mapToInt(s -> intOr(s, -1)).toArray() : null;
                int start = body.indexOf("<UniqueItemInfo>"), end = body.indexOf("</UniqueItemInfo>");
                List<Map.Entry<Integer, int[]>> data = new ArrayList<>();   // item type -> enchantment ids
                if (start >= 0 && end > start) {
                    java.util.regex.Matcher m = ITEM_DATA.matcher(body.substring(start, end));
                    while (m.find()) {
                        data.add(Map.entry(Integer.parseInt(m.group(1)), toArray(LootEvents.enchantIds(m.group(2).trim()))));
                    }
                }
                if (slots == null) continue;
                int[][] ids = new int[slots.length][];
                for (int i = 0; i < slots.length; i++) {
                    ids[i] = new int[0];
                    for (Iterator<Map.Entry<Integer, int[]>> it = data.iterator(); it.hasNext(); ) {
                        Map.Entry<Integer, int[]> d = it.next();
                        if (d.getKey() == slots[i]) {
                            ids[i] = d.getValue();
                            it.remove();
                            break;
                        }
                    }
                }
                c.slots = slots;
                c.slotEnchantIds = ids;
                if (c.alive) held.put(c.charId, collectibles(c));
                seen.put(c.charId, CollectionStore.Seen.character(c));
                // an older saved list must not overwrite what you wear now
                if (c.equipment != null && slots.length >= 4 && Arrays.equals(c.equipment, Arrays.copyOf(slots, 4))) {
                    setEnchants(c, Arrays.copyOf(ids, 4));
                }
            }
            save();
        }
        fire();
        if (!live) return;
        // every character's copies count like a live scan of it (also those you have not played since), and they
        // tell where each copy is now
        held.forEach((id, copies) -> {
            for (List<CollectionStore.Find> list : copies.values()) for (CollectionStore.Find f : list) f.seen = seen.get(id);
            if (!copies.isEmpty()) CollectionStore.get().recordHeld(copies);
        });
        Whereabouts.get().characters(held);
    }

    /** A character's collectible copies from its last list (equipment, inventory, backpack) and its quick slots. */
    private static Map<Integer, List<CollectionStore.Find>> collectibles(Char c) {
        Map<Integer, List<CollectionStore.Find>> out = new HashMap<>();
        ItemDb db = ItemDb.get();
        for (int i = 0; i < c.slots.length; i++) {
            ItemDb.Item item = c.slots[i] > 0 ? db.item(c.slots[i]) : null;
            if (item == null || !item.isCollectible()) continue;
            List<Integer> ids = c.slotEnchantIds != null && i < c.slotEnchantIds.length && c.slotEnchantIds[i] != null
                    ? Arrays.stream(c.slotEnchantIds[i]).boxed().toList() : null;
            CollectionStore.Find f = CollectionStore.Find.of(CollectionStore.INVENTORY, ids, "", LootEvents.slotName(i));
            f.seasonal = c.seasonal;
            out.computeIfAbsent(c.slots[i], k -> new ArrayList<>()).add(f);
        }
        if (c.quickslots != null) {
            for (int q : c.quickslots) {
                ItemDb.Item item = q > 0 ? db.item(q) : null;
                if (item == null || !item.isCollectible()) continue;
                CollectionStore.Find f = CollectionStore.Find.of(CollectionStore.INVENTORY, -1, "", "Quick slot");
                f.seasonal = c.seasonal;
                out.computeIfAbsent(q, k -> new ArrayList<>()).add(f);
            }
        }
        return out;
    }

    /**
     * Enchantment ids of the item in a character's slot (0-3 equipment, 4-11 inventory, 12+ backpack), as of the
     * last character list; null if unknown or that slot held a different item then.
     */
    public synchronized int[] slotEnchants(int charId, int slot, int itemId) {
        if (!savedListRead) {   // until the game sends a new char list, use the one saved at the last login
            savedListRead = true;
            Path saved = Path.of("char-list.xml");
            try {
                if (Files.exists(saved)) importItemData(Files.readString(saved), false);
            } catch (Exception e) {
                System.err.println("Could not read " + saved + ": " + e.getMessage());
            }
        }
        Char c = chars.get(charId);
        if (c == null || c.slots == null || c.slotEnchantIds == null || slot < 0 || slot >= c.slots.length) return null;
        return c.slots[slot] == itemId && slot < c.slotEnchantIds.length ? c.slotEnchantIds[slot] : null;
    }

    private static int intOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public synchronized List<Char> all() {
        List<Char> copy = new ArrayList<>();
        for (Char c : chars.values()) copy.add(c);
        return copy;
    }

    /**
     * Play sessions of a character since the given time, newest first.
     */
    public synchronized List<Session> sessions(Char c, long since) {
        List<Session> out = new ArrayList<>();
        Sample start = null, prev = null;
        for (Sample s : c.samples) {
            if (s.t < since) continue;
            if (start == null) {
                start = prev = s;
                continue;
            }
            if (s.t - prev.t > SESSION_GAP_MS) {
                out.add(new Session(start.t, prev.t, prev.fame - start.fame));
                start = s;
            }
            prev = s;
        }
        if (start != null) out.add(new Session(start.t, prev.t, prev.fame - start.fame));
        Collections.reverse(out);
        return out;
    }

    /**
     * Combined fame gain of the given characters since the given time, as cumulative samples (starting at 0)
     * in time order. Counts the same steps as sessions(), so the end value equals the sum of their gains.
     */
    public synchronized List<Sample> combinedGain(Collection<Char> cs, long since) {
        List<long[]> steps = new ArrayList<>();   // {time, delta}
        for (Char c : cs) {
            Sample prev = null;
            for (Sample s : c.samples) {
                if (s.t < since) continue;
                if (prev != null && s.t - prev.t <= SESSION_GAP_MS && s.fame != prev.fame) {
                    steps.add(new long[]{s.t, s.fame - prev.fame});
                }
                prev = s;
            }
        }
        steps.sort(Comparator.comparingLong(a -> a[0]));
        List<Sample> out = new ArrayList<>();
        long total = 0;
        for (long[] st : steps) {
            if (out.isEmpty()) {
                Sample zero = new Sample();
                zero.t = st[0] - 1;
                out.add(zero);
            }
            total += st[1];
            Sample s = new Sample();
            s.t = st[0];
            s.fame = total;
            out.add(s);
        }
        return out;
    }

    /**
     * Your in-game name, as last seen while playing; null if you have not played with the sniffer on yet.
     */
    public String playerName() {
        return playerName;
    }

    /** The character you are playing (or last played), or null. */
    public synchronized Char playing() {
        return chars.get(currentCharId);
    }

    /**
     * Fame gained by a character in the last 24 hours.
     */
    public synchronized long gainedToday(Char c) {
        long since = System.currentTimeMillis() - DAY_MS;
        return sessions(c, since).stream().mapToLong(Session::gained).sum();
    }

    /**
     * Your recently most played character: the living one with the most fame gained in the last 24 hours. If none
     * gained any, the one you are playing now (or last played); null if no character is known.
     */
    public synchronized Char mostPlayed() {
        Char best = null;
        long bestGain = 0;
        for (Char c : chars.values()) {
            if (!c.alive) continue;
            long gain = gainedToday(c);
            if (gain > bestGain) {
                best = c;
                bestGain = gain;
            }
        }
        if (best != null) return best;
        Char current = chars.get(currentCharId);
        if (current != null) return current;
        return chars.values().stream().filter(c -> c.alive && c.lastPlayed > 0)
                .max(Comparator.comparingLong(c -> c.lastPlayed)).orElse(null);
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Favourite flag from the char list, or null if the game sent no such tag.
     */
    private static Boolean favorite(Map<String, String> tags) {
        for (String t : FAVORITE_TAGS) {
            String v = tags.get(t);
            if (v != null) return v.equals("1") || v.equalsIgnoreCase("true");
        }
        return null;
    }

    public static final int BACKPACK_ITEM = 3180, EXTENDER_ITEM = 65280;

    /**
     * Backpack extender from the char list: a tag mentioning it, or the extender item among the equipment.
     */
    private static boolean extender(RealmCharacter r) {
        for (Map.Entry<String, String> t : r.rawTags.entrySet()) {
            if (t.getKey().toLowerCase(Locale.ROOT).contains("extender")) {
                String v = t.getValue();
                if (v != null && (v.equals("1") || v.equalsIgnoreCase("true"))) return true;
            }
        }
        if (r.equipment != null) for (int id : r.equipment) if (id == EXTENDER_ITEM) return true;
        return false;
    }

    public enum Flag {FAVORITE, DEAD}

    /**
     * Manual toggle from the UI. Game data overwrites it again whenever that information is available.
     */
    public void toggle(int charId, Flag flag) {
        synchronized (this) {
            Char c = chars.get(charId);
            if (c == null) return;
            switch (flag) {
                case FAVORITE -> {
                    c.favorite = !c.favorite;
                    c.favoriteAt = System.currentTimeMillis();
                }
                case DEAD -> {
                    c.alive = !c.alive;
                    c.diedBy = c.alive ? null : "user";
                }
            }
            save();
        }
        fire();
    }

    private static int maxed(int classType, int[] stats) {
        int[] max = CharacterClass.getStats(classType);
        if (max == null || stats == null) return 0;
        int n = 0;
        for (int i = 0; i < Math.min(8, Math.min(max.length, stats.length)); i++) if (stats[i] >= max[i]) n++;
        return n;
    }

    private static String className(int classType) {
        String n = CharacterClass.getName(classType);
        return n == null ? "Character" : n;
    }

    private static int intStat(Entity p, StatType type) {
        StatData s = p.stat.get(type);
        return s == null ? 0 : s.statValue;
    }

    private static int intTag(Map<String, String> tags, String name) {
        try {
            return Integer.parseInt(tags.getOrDefault(name, "0").trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Lists every tag the game sent across all characters (with how many characters have it).
     */
    private static void writeTagsOnce(List<RealmCharacter> list) {
        if (list.isEmpty()) return;
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> example = new HashMap<>();
        for (RealmCharacter r : list) {
            r.rawTags.forEach((k, v) -> {
                counts.merge(k, 1, Integer::sum);
                example.putIfAbsent(k, v);
            });
        }
        StringBuilder sb = new StringBuilder("Character list tags sent by the game (tag: characters having it, example value)\n");
        counts.forEach((k, n) -> {
            String v = example.get(k);
            sb.append(String.format("  %-22s %2d/%d  %s%n", k, n, list.size(), v == null ? "" : v.length() > 60 ? v.substring(0, 60) + "..." : v));
        });
        try {
            Files.writeString(TAG_FILE, sb.toString());
        } catch (Exception e) {
            System.err.println("Could not write " + TAG_FILE + ": " + e.getMessage());
        }
    }

    /**
     * Saves the complete character list response, reads the equipment enchantments from it and reports anything
     * that looks like a favourite/star flag, wherever it is in the XML (per character or account level).
     */
    public void saveRawList(String xml) {
        if (xml == null || xml.isBlank()) return;
        try {
            importItemData(xml, true);
        } catch (Exception e) {
            System.err.println("Could not read equipment enchantments: " + e);
        }
        try {
            PetStore.get().importCharList(xml);   // pets, their abilities and saddlebags
        } catch (Exception e) {
            System.err.println("Could not read pets: " + e);
        }
        try {
            Files.writeString(Path.of("char-list.xml"), xml);
            StringBuilder hits = new StringBuilder();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<([A-Za-z]*(?:[Ff]avo|[Ss]tar|[Ll]ock|[Pp]in)[A-Za-z]*)[^>]*>([^<]{0,80})")
                    .matcher(xml);
            Set<String> seen = new LinkedHashSet<>();
            while (m.find()) {
                if (seen.add(m.group(1) + "=" + m.group(2))) hits.append("  <").append(m.group(1)).append("> ").append(m.group(2)).append('\n');
            }
            Files.writeString(TAG_FILE, (Files.exists(TAG_FILE) ? Files.readString(TAG_FILE) : "")
                    + "\nTags in the full list that may mark favourites (favo/star/lock/pin):\n"
                    + (hits.length() == 0 ? "  none found\n" : hits.toString()));
        } catch (Exception e) {
            System.err.println("Could not save char-list.xml: " + e.getMessage());
        }
    }

    @Override
    public String syncKind() {
        return "characters";
    }

    @Override
    public String syncLabel() {
        return "Characters";
    }

    @Override
    public synchronized int syncCount() {
        return chars.size();
    }

    @Override
    public synchronized JsonElement exportSync() {
        return GSON.toJsonTree(chars);
    }

    /**
     * Characters only one side knows are added. For characters both know, the more recently played version wins,
     * but whatever it does not know yet (a fresh install that played before its first sync: equipment
     * enchantments, pet, slots, ...) is taken from the other one. The fame samples of both are combined, so the
     * fame history survives a reinstall.
     */
    @Override
    public boolean mergeSync(JsonElement remote) {
        Map<Integer, Char> theirs = GSON.fromJson(remote, new TypeToken<LinkedHashMap<Integer, Char>>() {
        }.getType());
        if (theirs == null) return false;
        boolean changed = false;
        synchronized (this) {
            for (Map.Entry<Integer, Char> t : theirs.entrySet()) {
                Char other = t.getValue();
                if (other.samples == null) other.samples = new ArrayList<>();
                Char mine = chars.get(t.getKey());
                if (mine == null) {
                    chars.put(t.getKey(), other);
                    changed = true;
                    continue;
                }
                List<Sample> samples = mergeSamples(mine.samples, other.samples);
                Char winner = other.lastPlayed > mine.lastPlayed ? other : mine;
                Char loser = winner == mine ? other : mine;
                if (fillUnknown(winner, loser) && winner == mine) changed = true;
                if (winner != mine || mine.samples == null || samples.size() != mine.samples.size()) changed = true;
                winner.samples = samples;
                chars.put(t.getKey(), winner);
            }
            if (changed) save();
        }
        if (changed) fire();
        return changed;
    }

    /**
     * Fills what n does not know (null / 0 / empty) from o.
     *
     * @return True if n changed.
     */
    private static boolean fillUnknown(Char n, Char o) {
        boolean c = false;
        if (n.equipment == null && o.equipment != null) {
            n.equipment = o.equipment;
            n.enchants = o.enchants;
            n.enchantIds = o.enchantIds;
            c = true;
        } else if (n.equipment != null && Arrays.equals(n.equipment, o.equipment)) {   // same gear: same enchantments
            if (n.enchants == null && o.enchants != null) {
                n.enchants = o.enchants;
                c = true;
            }
            if (n.enchantIds == null && o.enchantIds != null) {
                n.enchantIds = o.enchantIds;
                c = true;
            }
        }
        if (n.slots == null && o.slots != null) {
            n.slots = o.slots;
            n.slotEnchantIds = o.slotEnchantIds;
            c = true;
        }
        if (n.petSkin == 0 && o.petSkin != 0) {
            n.petSkin = o.petSkin;
            n.petName = o.petName;
            c = true;
        }
        if (n.skin == 0 && o.skin != 0) {
            n.skin = o.skin;
            c = true;
        }
        if ((n.className == null || n.className.isEmpty()) && o.className != null && !o.className.isEmpty()) {
            n.className = o.className;
            c = true;
        }
        if (n.level == 0 && o.level > 0) {
            n.level = o.level;
            c = true;
        }
        if (n.maxHp == 0 && o.maxHp > 0) {   // only known from live play
            n.maxHp = o.maxHp;
            c = true;
        }
        if (n.fame == 0 && o.fame > 0) {
            n.fame = o.fame;
            c = true;
        }
        if (n.statsMaxed == 0 && o.statsMaxed > 0) {
            n.statsMaxed = o.statsMaxed;
            c = true;
        }
        if (!n.backpack && o.backpack) {   // a backpack or extender cannot be lost
            n.backpack = true;
            c = true;
        }
        if (!n.extender && o.extender) {
            n.extender = true;
            c = true;
        }
        // The favorite flag goes by when it was set, not by when the character was played. Data from before
        // favoriteAt existed has 0 on both sides: then a set flag is kept.
        boolean takeTheirs = o.favoriteAt > n.favoriteAt || (o.favoriteAt == 0 && n.favoriteAt == 0 && o.favorite);
        if (takeTheirs && (n.favorite != o.favorite || n.favoriteAt != o.favoriteAt)) {
            n.favorite = o.favorite;
            n.favoriteAt = o.favoriteAt;
            c = true;
        }
        return c;
    }

    private static List<Sample> mergeSamples(List<Sample> a, List<Sample> b) {
        TreeMap<Long, Sample> byTime = new TreeMap<>();
        if (a != null) for (Sample s : a) byTime.put(s.t, s);
        if (b != null) for (Sample s : b) byTime.putIfAbsent(s.t, s);
        List<Sample> merged = new ArrayList<>(byTime.values());
        return merged.size() > MAX_SAMPLES ? new ArrayList<>(merged.subList(merged.size() - MAX_SAMPLES, merged.size()))
                : merged;
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    private void save() {
        lastSave = System.currentTimeMillis();
        try {
            Path tmp = Path.of(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(chars, w);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save " + FILE + ": " + e.getMessage());
        }
    }
}
