package lootgobbler;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import packets.data.ObjectData;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Stat;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your pets, kept across restarts in pets.json. The character list (at login) names the pet each character has,
 * with its abilities and saddlebag; regular and seasonal characters each see their own saddlebag. Pets seen in the
 * Pet Yard add their stats (no saddlebag is sent there).
 */
public final class PetStore {
    public static final class Ability {
        public int type;
        public int power;     // ability level
        public long points;   // feed points towards the next level
    }

    public static final class Saddlebag {
        public int[] items = new int[0];       // item ids, -1 = empty slot
        public int[][] enchantIds = new int[0][];   // per slot, as in CharacterStore.Char.enchantIds
    }

    public static final class Pet {
        public int instanceId;
        public String name = "";
        public int type;           // pet object type (its kind)
        public int skin;
        public int rarity;         // 0 common .. 4 divine
        public int maxPower;       // highest ability level its rarity allows
        public List<Ability> abilities = new ArrayList<>();
        public Saddlebag saddlebag;          // seen on regular characters; null = unknown
        public Saddlebag seasonalSaddlebag;  // seen on seasonal characters; null = unknown
        public long lastSeen;
    }

    public static final String[] RARITY = {"Common", "Uncommon", "Rare", "Legendary", "Divine"};
    private static final Map<Integer, String> ABILITY_NAMES = Map.of(402, "Attack Close", 404, "Attack Mid",
            405, "Attack Far", 406, "Electric", 407, "Heal", 408, "Magic Heal", 409, "Savage", 410, "Decoy",
            411, "Rising Fury");

    private static final Path FILE = Path.of("pets.json");
    private static final Gson GSON = new Gson();
    private static final Pattern CHAR = Pattern.compile("<Char id=\"(\\d+)\">(.*?)</Char>", Pattern.DOTALL);
    private static final Pattern PET = Pattern.compile("<Pet ([^>]*)>(.*?)</Pet>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern ABILITY = Pattern.compile("<Ability ([^/>]*)/?>");
    private static final Pattern ITEM_DATA = Pattern.compile("<ItemData id=\"(\\d+)\"[^>]*>([^<]*)</ItemData>");
    private static final PetStore INSTANCE = new PetStore();   // after the patterns: the constructor may parse

    private final Map<Integer, Pet> pets = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public static PetStore get() {
        return INSTANCE;
    }

    public static String abilityName(int type) {
        return ABILITY_NAMES.getOrDefault(type, "Ability " + type);
    }

    private PetStore() {
        if (!Files.exists(FILE)) {
            Path lastList = Path.of("char-list.xml");   // saved at the last login, before pets were tracked
            try {
                if (Files.exists(lastList)) importCharList(Files.readString(lastList));
            } catch (Exception e) {
                System.err.println("Could not read pets from " + lastList + ": " + e.getMessage());
            }
            return;
        }
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Map<Integer, Pet> loaded = GSON.fromJson(r, new TypeToken<LinkedHashMap<Integer, Pet>>() {
            }.getType());
            if (loaded != null) pets.putAll(loaded);
        } catch (Exception e) {
            System.err.println("Could not read " + FILE + ": " + e.getMessage());
        }
    }

    /**
     * The raw character list: every character's pet, its abilities and the saddlebag that character sees.
     */
    public void importCharList(String xml) {
        synchronized (this) {
            Matcher ch = CHAR.matcher(xml);
            while (ch.find()) {
                String body = ch.group(2);
                Matcher pm = PET.matcher(body);
                if (!pm.find()) continue;
                boolean seasonal = body.contains("<Seasonal>True</Seasonal>");
                Map<String, String> a = attributes(pm.group(1));
                int id = intOf(a.get("instanceId"));
                if (id <= 0) continue;
                Pet p = pets.computeIfAbsent(id, k -> new Pet());
                p.instanceId = id;
                p.name = a.getOrDefault("name", p.name);
                p.type = intOf(a.get("type"));
                p.skin = intOf(a.get("skin"));
                p.rarity = intOf(a.get("rarity"));
                p.maxPower = intOf(a.get("maxAbilityPower"));
                p.lastSeen = System.currentTimeMillis();
                List<Ability> abilities = new ArrayList<>();
                Matcher am = ABILITY.matcher(pm.group(2));
                while (am.find()) {
                    Map<String, String> aa = attributes(am.group(1));
                    Ability ab = new Ability();
                    ab.type = intOf(aa.get("type"));
                    ab.power = intOf(aa.get("power"));
                    ab.points = longOf(aa.get("points"));
                    abilities.add(ab);
                }
                if (!abilities.isEmpty()) p.abilities = abilities;
                Saddlebag bag = saddlebag(a.get("inv"), pm.group(2));
                if (bag != null) {
                    if (seasonal) p.seasonalSaddlebag = bag;
                    else p.saddlebag = bag;
                }
            }
            save();
        }
        fire();
    }

    /**
     * A pet object in the Pet Yard: updates its name, kind, rarity and abilities (the saddlebag is not sent there).
     */
    public void importYardPet(ObjectData object) {
        Stat stat = new Stat(object.status.stats);
        int id = intStat(stat, StatType.PET_INSTANCE_ID_STAT);
        if (id <= 0) return;
        synchronized (this) {
            Pet p = pets.computeIfAbsent(id, k -> new Pet());
            p.instanceId = id;
            StatData name = stat.get(StatType.PET_NAME_STAT);
            if (name != null && name.stringStatValue != null && !name.stringStatValue.isBlank()) p.name = name.stringStatValue;
            p.type = object.objectType;
            int skin = intStat(stat, StatType.SKIN_ID);
            if (skin > 0) p.skin = skin;
            p.rarity = intStat(stat, StatType.PET_RARITY_STAT);
            p.maxPower = intStat(stat, StatType.PET_MAX_ABILITY_POWER_STAT);
            StatType[][] slots = {
                    {StatType.PET_FIRST_ABILITY_TYPE_STAT, StatType.PET_FIRST_ABILITY_POWER_STAT, StatType.PET_FIRST_ABILITY_POINT_STAT},
                    {StatType.PET_SECOND_ABILITY_TYPE_STAT, StatType.PET_SECOND_ABILITY_POWER_STAT, StatType.PET_SECOND_ABILITY_POINT_STAT},
                    {StatType.PET_THIRD_ABILITY_TYPE_STAT, StatType.PET_THIRD_ABILITY_POWER_STAT, StatType.PET_THIRD_ABILITY_POINT_STAT}};
            List<Ability> abilities = new ArrayList<>();
            for (StatType[] s : slots) {
                if (stat.get(s[0]) == null) continue;
                Ability ab = new Ability();
                ab.type = intStat(stat, s[0]);
                ab.power = intStat(stat, s[1]);
                ab.points = intStat(stat, s[2]);
                abilities.add(ab);
            }
            if (!abilities.isEmpty()) p.abilities = abilities;
            p.lastSeen = System.currentTimeMillis();
            save();
        }
        fire();
    }

    /**
     * The saddlebag from the pet's inv attribute ("0,6;X;10022#uid,583,-1,..."): item ids, unique ones with "#id"
     * whose enchantments are in the pet's UniqueItemInfo.
     */
    private static Saddlebag saddlebag(String inv, String petBody) {
        if (inv == null || inv.isBlank()) return null;
        String[] parts = inv.split(";");
        String list = parts[parts.length - 1];
        Map<String, String> codes = new HashMap<>();   // unique id -> enchant code
        Matcher m = ITEM_DATA.matcher(petBody);
        while (m.find()) codes.put(m.group(1), m.group(2).trim());
        String[] slots = list.isBlank() ? new String[0] : list.split(",");
        Saddlebag bag = new Saddlebag();
        bag.items = new int[slots.length];
        bag.enchantIds = new int[slots.length][];
        for (int i = 0; i < slots.length; i++) {
            String[] item = slots[i].trim().split("#");
            bag.items[i] = item[0].isEmpty() ? -1 : intOf(item[0]);
            String code = item.length > 1 ? codes.get(item[1]) : null;
            bag.enchantIds[i] = LootEvents.enchantIds(code).stream().mapToInt(Integer::intValue).toArray();
        }
        return bag;
    }

    public synchronized List<Pet> all() {
        return new ArrayList<>(pets.values());
    }

    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    private void fire() {
        for (Runnable r : listeners) r.run();
    }

    private static Map<String, String> attributes(String s) {
        Map<String, String> map = new HashMap<>();
        Matcher m = ATTR.matcher(s);
        while (m.find()) map.put(m.group(1), m.group(2));
        return map;
    }

    private static int intStat(Stat stat, StatType type) {
        StatData s = stat.get(type);
        return s == null ? 0 : s.statValue;
    }

    private static int intOf(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long longOf(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void save() {
        try {
            Path tmp = Path.of(FILE + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(pets, w);
            }
            Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("Could not save " + FILE + ": " + e.getMessage());
        }
    }
}
