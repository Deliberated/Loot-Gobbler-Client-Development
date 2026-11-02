package lootgobbler;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Equipment details read from the game XML extracted into assets/xml (name, slot, tier, stats, labels...).
 * Loaded once, lazily; the first call to get() may take a second or two.
 */
public final class ItemDb {
    public record Stat(String stat, int amount) {
    }

    public static final class Item {
        public final int id;
        public String name = "";
        public int slotType;
        public String tier = "";
        public String description = "";
        public int minDamage = -1, maxDamage = -1, mpCost = -1, feedPower = -1;
        public boolean soulbound;
        public boolean chest;   // opens into an item when used (Eerie Music Box, mystery ST chests...)
        public final List<Stat> onEquip = new ArrayList<>();
        public Set<String> labels = Set.of();
        public int collectionIcon = Integer.MAX_VALUE;
        boolean named;   // has a display name (unnamed items are unused test or event leftovers)

        Item(int id) {
            this.id = id;
        }

        public boolean isUT() {
            return labels.contains("UT");
        }

        public boolean isST() {
            return labels.contains("ST");
        }

        public boolean isShiny() {
            return labels.contains("SHINY");
        }

        /**
         * Counts toward the collection (and gets posted to Discord): equipable gear (weapon, ability, armor, ring)
         * from the game's UT and ST collection tabs, plus the top tiers (T14 weapons and armor, T7 and T8 abilities,
         * T7 rings). Consumables and craftables in the UT/ST tabs (shards, orbs, potions...) are left out.
         * Newer items (the Druid's sigils, event weapons, some shinies) lack the tab label, so a named UT or ST
         * item counts too, and so does an unnamed UT with a dungeon of origin (ORG_ label, e.g. Swarmlord's Sigil,
         * Corsair Ring); unnamed UTs without one are test entries and event reskins.
         */
        public boolean isCollectible() {
            boolean dungeonUt = isUT() && !labels.contains("RESKIN") && labels.stream().anyMatch(l -> l.startsWith("ORG_"));
            boolean utSt = labels.contains("TAB_UT") || labels.contains("TAB_ST") || (named && (isUT() || isST())) || dungeonUt;
            return (utSt && slotType != 0 && slotType != 10) || isTopTier();
        }

        public boolean isTopTier() {
            return labels.contains("T14_WEAPON") || labels.contains("T14_ARMOR") || labels.contains("T7_ABILITY")
                    || labels.contains("T7_RING")
                    || (labels.contains("T8") && labels.contains("ABILITY"));   // T8 abilities have no T8_ABILITY label
        }

        public String tierTag() {
            if (isUT()) return "(UT)";
            if (isST()) return "(ST)";
            return tier.isEmpty() ? "" : "Tier " + tier;
        }

        public String slotName() {
            return SLOT_NAMES.getOrDefault(slotType, "Item");
        }

        /**
         * The dungeon the item drops in, from its origin label (ORG_...); null if it has none or the code is not
         * known. Only about a quarter of the items carry one.
         */
        public String origin() {
            for (String l : labels) {
                if (!l.startsWith("ORG_")) continue;
                String code = l.substring(4);
                if (code.endsWith("_S")) code = code.substring(0, code.length() - 2);   // shiny variants
                String name = ORIGINS.get(code);
                if (name != null) return name;
            }
            return null;
        }
    }

    private static final Map<Integer, String> SLOT_NAMES = Map.ofEntries(
            Map.entry(1, "Sword"), Map.entry(2, "Dagger"), Map.entry(3, "Bow"), Map.entry(4, "Tome"),
            Map.entry(5, "Shield"), Map.entry(6, "Leather Armor"), Map.entry(7, "Heavy Armor"), Map.entry(8, "Wand"),
            Map.entry(9, "Ring"), Map.entry(10, "Consumable"), Map.entry(11, "Spell"), Map.entry(12, "Seal"),
            Map.entry(13, "Cloak"), Map.entry(14, "Robe"), Map.entry(15, "Quiver"), Map.entry(16, "Helm"),
            Map.entry(17, "Staff"), Map.entry(18, "Poison"), Map.entry(19, "Skull"), Map.entry(20, "Trap"),
            Map.entry(21, "Orb"), Map.entry(22, "Prism"), Map.entry(23, "Scepter"), Map.entry(24, "Katana"),
            Map.entry(25, "Shuriken"), Map.entry(27, "Wakizashi"), Map.entry(28, "Lute"), Map.entry(29, "Mace"),
            Map.entry(30, "Sheath"), Map.entry(31, "Sigil"));

    /** Origin label codes (ORG_<code>) and their dungeons; codes whose dungeon is unsure are left out. */
    private static final Map<String, String> ORIGINS = Map.ofEntries(
            Map.entry("OSANC", "Oryx's Sanctuary"), Map.entry("MOONLIGHT", "Moonlight Village"),
            Map.entry("SHTT", "The Shatters"), Map.entry("NEST", "The Nest"),
            Map.entry("SPECTRAL", "Spectral Penitentiary"), Map.entry("LOD", "Lair of Draconis"),
            Map.entry("PARASITE", "Parasite Chambers"), Map.entry("3D", "The Third Dimension"),
            Map.entry("MANOR", "Manor of the Immortals"), Map.entry("KOGBOLD", "Advanced Kogbold Steamworks"),
            Map.entry("FUNGAL", "Fungal Cavern"), Map.entry("CEM", "Haunted Cemetery"),
            Map.entry("VOID", "The Void"), Map.entry("UDL", "Undead Lair"), Map.entry("TOMB", "Tomb of the Ancients"),
            Map.entry("SEWER", "Toxic Sewers"), Map.entry("OTRENCH", "Ocean Trench"), Map.entry("MWOODS", "Magic Woods"),
            Map.entry("LIBRARY", "Cursed Library"), Map.entry("LH", "Lost Halls"), Map.entry("LAB", "Mad Lab"),
            Map.entry("DAVY", "Davy Jones' Locker"), Map.entry("CULT", "Cultist Hideout"),
            Map.entry("THICKET", "Secluded Thicket"), Map.entry("SPRITE", "Sprite World"),
            Map.entry("SHAITAN", "Lair of Shaitan"), Map.entry("MTEMPLE", "Mountain Temple"),
            Map.entry("ICECAVE", "Ice Cave"), Map.entry("HIVE", "The Hive"),
            Map.entry("CLAND", "Candyland Hunting Grounds"), Map.entry("ABYSS", "Abyss of Demons"),
            Map.entry("SPIDER", "Spider Den"), Map.entry("RUINS", "Ancient Ruins"),
            Map.entry("PUPPET", "Puppet Master's Theatre"), Map.entry("ENCORE", "Puppet Master's Encore"),
            Map.entry("JUNGLE", "Forbidden Jungle"), Map.entry("CASTLE", "Oryx's Castle"),
            Map.entry("DDOCKS", "Deadwater Docks"), Map.entry("FMAZE", "Forest Maze"), Map.entry("PCAVE", "Pirate Cave"),
            Map.entry("CDEPTHS", "The Crawling Depths"));

    private static volatile ItemDb instance;

    private final Map<Integer, Item> items = new HashMap<>();
    private final List<Item> collectible;

    public static ItemDb get() {
        ItemDb db = instance;
        if (db == null) {
            synchronized (ItemDb.class) {
                if (instance == null) instance = new ItemDb(Path.of("assets", "xml"));
                db = instance;
            }
        }
        return db;
    }

    public Item item(int id) {
        return items.get(id);
    }

    /**
     * All collectible items, grouped weapons / abilities / armors / rings, then by slot and id.
     */
    public List<Item> collectible() {
        return collectible;
    }

    private ItemDb(Path dir) {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.xml")) {
                for (Path p : files) parse(f, p);
            } catch (Exception e) {
                System.err.println("ItemDb: could not list " + dir + ": " + e.getMessage());
            }
        } else {
            System.err.println("ItemDb: " + dir + " not found - extract assets first");
        }
        List<Item> c = new ArrayList<>();
        for (Item i : items.values()) if (i.isCollectible()) c.add(i);
        // per category: the top-tier items first, then UT/ST by slot and id
        c.sort(Comparator.comparingInt((Item i) -> category(i.slotType)).thenComparing(i -> !i.isTopTier())
                .thenComparingInt(i -> i.slotType).thenComparingInt(i -> i.id));
        collectible = Collections.unmodifiableList(c);
    }

    private void parse(XMLInputFactory f, Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader r = f.createXMLStreamReader(in);
            Item cur = null;
            String clazz = null;
            boolean inProjectile = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("Object")) {
                        cur = null;
                        clazz = null;
                        inProjectile = false;
                        String type = r.getAttributeValue(null, "type");
                        if (type == null) continue;
                        try {
                            cur = new Item(Integer.decode(type.trim()));
                        } catch (NumberFormatException e) {
                            continue;
                        }
                        String id = r.getAttributeValue(null, "id");
                        cur.name = id == null ? "" : id;
                        cur.collectionIcon = parseInt(r.getAttributeValue(null, "collectionIcon"), Integer.MAX_VALUE);
                        continue;
                    }
                    if (cur == null) continue;
                    switch (n) {
                        case "Projectile" -> inProjectile = true;
                        case "Soulbound" -> cur.soulbound = true;
                        case "Chest" -> cur.chest = true;
                        case "ActivateOnEquip" -> {
                            String stat = r.getAttributeValue(null, "stat");
                            int amount = parseInt(r.getAttributeValue(null, "amount"), 0);
                            String kind = r.getElementText().trim();
                            if (kind.equals("IncrementStat") && stat != null && amount != 0) {
                                cur.onEquip.add(new Stat(stat, amount));
                            }
                        }
                        case "Class" -> clazz = r.getElementText().trim();
                        case "DisplayId" -> {
                            cur.name = r.getElementText().trim();
                            cur.named = !cur.name.isEmpty();
                        }
                        case "SlotType" -> cur.slotType = parseInt(r.getElementText(), 0);
                        case "Tier" -> cur.tier = r.getElementText().trim();
                        case "Description" -> cur.description = r.getElementText().trim();
                        case "MpCost" -> cur.mpCost = parseInt(r.getElementText(), -1);
                        case "feedPower" -> cur.feedPower = parseInt(r.getElementText(), -1);
                        case "Labels" -> cur.labels = new HashSet<>(Arrays.asList(r.getElementText().trim().split(",")));
                        case "MinDamage" -> {
                            int v = parseInt(r.getElementText(), -1);
                            if (inProjectile && cur.minDamage < 0) cur.minDamage = v;
                        }
                        case "MaxDamage" -> {
                            int v = parseInt(r.getElementText(), -1);
                            if (inProjectile && cur.maxDamage < 0) cur.maxDamage = v;
                        }
                        default -> {
                        }
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String n = r.getLocalName();
                    if (n.equals("Object")) {
                        if (cur != null && "Equipment".equals(clazz)) items.put(cur.id, cur);
                        cur = null;
                    } else if (n.equals("Projectile")) {
                        inProjectile = false;
                    }
                }
            }
            r.close();
        } catch (Exception e) {
            // a few files in assets/xml are not game data (e.g. test-run settings); ignore those quietly
            if (!String.valueOf(e.getMessage()).contains("prolog")) {
                System.err.println("ItemDb: skipped " + file.getFileName() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Collection order: weapons, abilities, armors, rings, everything else.
     */
    private static int category(int slotType) {
        return switch (slotType) {
            case 1, 2, 3, 8, 17, 24 -> 0;
            case 6, 7, 14 -> 2;
            case 9 -> 3;
            case 0, 10 -> 4;
            default -> 1;
        };
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.decode(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
