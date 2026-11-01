package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;
import tomato.realmshark.RealmCharacter;
import tomato.realmshark.enums.CharacterClass;

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
public final class CharacterStore {
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
        public long fame;
        public int statsMaxed;
        public boolean seasonal, crucible, ritual, favorite;
        public boolean backpack, extender;
        public boolean alive = true;
        public String diedBy;          // "game" (Dead tag in the char list) or "user" (gravestone clicked); null while alive
        public long lastPlayed;        // epoch ms, 0 = not played since tracking started
        public int[] equipment;        // weapon, ability, armor, ring item ids (-1 = empty); null = unknown
        public int[] enchants;         // enchantment slots (rarity) per equipment slot; null = unknown
        public int[][] enchantIds;     // per equipment slot: enchantment ids, -1 = empty slot; null = unknown
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

    private final Map<Integer, Char> chars = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private long lastLive, lastSave;
    private volatile IntSupplier fetcher;    // requests the char list; returns characters received, 0 = rejected
    private volatile String fetchedToken;    // login the char list was last fetched for
    private volatile long lastFetch;
    private volatile boolean fetching;

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
                if (fav != null) c.favorite = fav;   // only when the game actually sends the flag
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
        Thread t = new Thread(() -> {
            int n = 0;
            try {
                n = f.getAsInt();
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
        try {
            boolean sampled;
            synchronized (this) {
                Char c = chars.computeIfAbsent(id, k -> new Char());
                c.charId = id;
                c.classType = p.objectType;
                c.className = className(p.objectType);
                int skin = intStat(p, StatType.SKIN_ID);
                if (skin > 0) c.skin = skin;
                StatData tex1 = p.stat.get(StatType.TEX1_STAT), tex2 = p.stat.get(StatType.TEX2_STAT);
                if (tex1 != null) c.tex1 = liveTexture(tex1.statValue);
                if (tex2 != null) c.tex2 = liveTexture(tex2.statValue);
                int level = intStat(p, StatType.LEVEL_STAT);
                if (level > 0) c.level = level;
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
            }
            fire();
        } catch (Exception e) {
            // stats can change while being read; try again next tick
        }
    }

    /**
     * A dye stat from the game: a texture code (type in the high byte), or else a dye/cloth item type.
     */
    private static int liveTexture(int value) {
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
    private static final java.util.regex.Pattern ITEM_DATA = java.util.regex.Pattern.compile(
            "<ItemData[^>]*\\btype=\"(\\d+)\"[^>]*>([^<]*)</ItemData>");

    /**
     * Enchantments of the equipped items from the raw char list: each character's UniqueItemInfo has an ItemData
     * (item type + enchant code) per item, matched to the equipment slots by item type in order.
     */
    private void importItemData(String xml) {
        synchronized (this) {
            java.util.regex.Matcher ch = CHAR_XML.matcher(xml);
            while (ch.find()) {
                Char c = chars.get(Integer.parseInt(ch.group(1)));
                if (c == null || c.equipment == null) continue;
                String body = PET_XML.matcher(ch.group(2)).replaceAll("");   // the pet has a UniqueItemInfo of its own
                int start = body.indexOf("<UniqueItemInfo>"), end = body.indexOf("</UniqueItemInfo>");
                List<Map.Entry<Integer, int[]>> data = new ArrayList<>();   // item type -> enchantment ids
                if (start >= 0 && end > start) {
                    java.util.regex.Matcher m = ITEM_DATA.matcher(body.substring(start, end));
                    while (m.find()) {
                        data.add(Map.entry(Integer.parseInt(m.group(1)), toArray(LootEvents.enchantIds(m.group(2).trim()))));
                    }
                }
                int[][] ids = new int[4][];
                for (int i = 0; i < 4; i++) {
                    ids[i] = new int[0];
                    for (Iterator<Map.Entry<Integer, int[]>> it = data.iterator(); it.hasNext(); ) {
                        Map.Entry<Integer, int[]> d = it.next();
                        if (d.getKey() == c.equipment[i]) {
                            ids[i] = d.getValue();
                            it.remove();
                            break;
                        }
                    }
                }
                setEnchants(c, ids);
            }
            save();
        }
        fire();
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

    public enum Flag {SEASONAL, CRUCIBLE, RITUAL, FAVORITE, DEAD}

    /**
     * Manual toggle from the UI. Game data overwrites it again whenever that information is available.
     */
    public void toggle(int charId, Flag flag) {
        synchronized (this) {
            Char c = chars.get(charId);
            if (c == null) return;
            switch (flag) {
                case SEASONAL -> c.seasonal = !c.seasonal;
                case CRUCIBLE -> c.crucible = !c.crucible;
                case RITUAL -> c.ritual = !c.ritual;
                case FAVORITE -> c.favorite = !c.favorite;
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
            importItemData(xml);
        } catch (Exception e) {
            System.err.println("Could not read equipment enchantments: " + e);
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
