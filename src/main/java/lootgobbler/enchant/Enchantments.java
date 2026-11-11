package lootgobbler.enchant;

import lootgobbler.ItemDb;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Every enchantment in the game and the pools they roll from, read from the game files extracted into assets/xml
 * (enchantments.xml, enchantmentLists.xml). Nothing about single enchantments is written into the app: a game update
 * that adds enchantments (or changes their numbers) is picked up as soon as the files are extracted again.
 */
public final class Enchantments {
    private static final Path DIR = Path.of("assets", "xml");
    private static final String DEFAULT_POOL = "Default Enchantment Pool";
    private static volatile Enchantments instance;

    private final Map<Integer, Enchantment> byType;
    private final Map<String, Pool> pools;
    private final Map<Integer, List<Enchantment>> rollable = new HashMap<>();   // per item id, made when first asked

    Enchantments(Map<Integer, Enchantment> byType, Map<String, Pool> pools) {
        this.byType = byType;
        this.pools = pools;
    }

    public static Enchantments get() {
        Enchantments e = instance;
        if (e == null) {
            synchronized (Enchantments.class) {
                if (instance == null) instance = load(DIR.resolve("enchantments.xml"), DIR.resolve("enchantmentLists.xml"));
                e = instance;
            }
        }
        return e;
    }

    /** All enchantments, in the order of the game file. */
    public Collection<Enchantment> all() {
        return byType.values();
    }

    /** The enchantment with this number (as items carry it), or null if unknown. */
    public Enchantment byType(int type) {
        return byType.get(type);
    }

    public Pool pool(String name) {
        return pools.get(name);
    }

    /**
     * What the enchanter can roll on the item: the enchantments of its pool that it may carry. Empty if it cannot be
     * enchanted.
     */
    public synchronized List<Enchantment> rollableOn(ItemDb.Item item) {
        if (item == null || item.enchantSlots == null) return List.of();
        return rollable.computeIfAbsent(item.id, k -> {
            Pool p = pools.getOrDefault(item.enchantSlots.pool(), pools.get(DEFAULT_POOL));
            List<Enchantment> out = new ArrayList<>();
            if (p == null) return out;
            for (Enchantment e : byType.values()) if (e.rollable() && e.fits(item) && p.contains(e)) out.add(e);
            return Collections.unmodifiableList(out);
        });
    }

    /** Its weight among what the item rolls from (0 if it cannot roll there). */
    public double weightOn(ItemDb.Item item, Enchantment e) {
        if (item == null || item.enchantSlots == null) return 0;
        Pool p = pools.getOrDefault(item.enchantSlots.pool(), pools.get(DEFAULT_POOL));
        return p == null || !e.fits(item) || !e.rollable() ? 0 : p.weight(e);
    }

    // ---- reading the game files ----------------------------------------------------------------------------

    static Enchantments load(Path enchantments, Path lists) {
        Map<Integer, Enchantment> byType = new LinkedHashMap<>();
        Map<String, Pool> pools = new HashMap<>();
        try {
            Element root = parse(enchantments);
            if (root != null) {
                for (Element e : children(root, "Enchantment")) {
                    Enchantment en = enchantment(e);
                    if (en != null) byType.put(en.type(), en);
                }
            }
            Element listRoot = parse(lists);
            if (listRoot != null) for (Element l : children(listRoot, "EnchantmentList")) pools.put(l.getAttribute("id"), pool(l));
        } catch (Exception ex) {
            System.err.println("Enchantments: could not read the game files: " + ex.getMessage());
        }
        if (!pools.containsKey(DEFAULT_POOL)) {   // older files: every rollable enchantment
            pools.put(DEFAULT_POOL, new Pool(DEFAULT_POOL, List.of(new Pool.Rule(Set.of("ROLLABLE"), Set.of())),
                    Set.of(), List.of(), Map.of()));
        }
        return new Enchantments(byType, pools);
    }

    private static Element parse(Path file) throws Exception {
        if (!Files.exists(file)) return null;
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setExpandEntityReferences(false);
        try (InputStream in = Files.newInputStream(file)) {
            Document d = f.newDocumentBuilder().parse(in);
            return d.getDocumentElement();
        }
    }

    private static Enchantment enchantment(Element e) {
        Integer type = decode(e.getAttribute("type"));
        if (type == null) return null;
        String key = e.getAttribute("id");
        Set<String> labels = set(text(e, "EnchantmentLabels"));
        int level = 0;
        for (int i = 1; i <= 4; i++) if (labels.contains("TIER" + i)) level = i;
        String family = level > 0 ? key.replaceFirst("_\\d+$", "") : key;
        String name = text(e, "DisplayId");
        String sheet = null;
        int index = 0;
        Element tex = child(e, "Texture");
        if (tex != null) {
            sheet = text(tex, "File");
            Integer i = decode(text(tex, "Index"));
            index = i == null ? 0 : i;
        }
        List<Effect> effects = new ArrayList<>();
        Element mut = child(e, "Mutators");
        if (mut != null) for (Element m : children(mut, null)) effects.add(effect(m));
        Integer weight = decode(text(e, "Weight"));
        return new Enchantment(type, key, family, level, name.isEmpty() ? key : name, text(e, "Description"),
                weight == null ? 0 : weight, labels, set(text(e, "IncompatibleWithEnchantmentLabels")),
                set(text(e, "CompatibleWithItemLabels")), set(text(e, "IncompatibleWithItemLabels")),
                set(text(e, "IncompatibleWithItemIds")), List.copyOf(effects), sheet, index);
    }

    /** One mutator as an effect: the element's name says when, its text what, its attributes how much. */
    static Effect effect(Element m) {
        String tag = m.getTagName(), action = m.getTextContent().trim();
        Map<String, String> a = new TreeMap<>();
        for (int i = 0; i < m.getAttributes().getLength(); i++) {
            Node n = m.getAttributes().item(i);
            a.put(n.getNodeName(), n.getNodeValue());
        }
        double amount = number(a.get("amount"));
        if (tag.equals("ActivateOnEquip")) {
            switch (action) {
                case "IncrementStat" -> {
                    return new Effect.StatBonus(a.get("stat"), amount);
                }
                case "BonusStatRelative" -> {
                    return new Effect.RelativeStat(a.get("stat"), a.get("statRelativeTo"), amount);
                }
                case "LootBonus", "XPBonus", "DustBonus" -> {
                    return new Effect.Reward(action, amount);
                }
                case "FlatRegen", "PercentageRegen" -> {
                    return new Effect.Regen(a.get("stat"), action.startsWith("Percentage"), amount);
                }
                default -> {
                }
            }
        } else if (tag.startsWith("Multiply")) {
            String index = a.getOrDefault("projectileId", a.get("subAttackIndex"));
            return new Effect.WeaponMod(tag.substring("Multiply".length()), number(action),
                    index == null ? -1 : (int) number(index));
        } else if (tag.startsWith("AddOn") && tag.endsWith("Activate")) {
            return new Effect.Proc(tag.substring("AddOn".length(), tag.length() - "Activate".length()), action, a);
        }
        return new Effect.Other(tag, action, a);
    }

    private static Pool pool(Element l) {
        List<Pool.Rule> entries = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        List<Pool.Mod> labelMods = new ArrayList<>();
        Map<String, Double> idMods = new HashMap<>();
        for (Element c : children(l, null)) {
            switch (c.getTagName()) {
                case "EnchantmentEntryLabel" -> entries.add(rule(c));
                case "EnchantmentEntry" -> ids.add(c.getAttribute("id"));
                case "ModifyEnchantmentWeightLabel" -> labelMods.add(new Pool.Mod(rule(c), number(c.getAttribute("mult"))));
                case "ModifyEnchantmentWeight" -> idMods.put(c.getAttribute("id"), number(c.getAttribute("mult")));
                default -> {
                }
            }
        }
        return new Pool(l.getAttribute("id"), List.copyOf(entries), Set.copyOf(ids), List.copyOf(labelMods), Map.copyOf(idMods));
    }

    private static Pool.Rule rule(Element c) {
        return new Pool.Rule(set(c.getAttribute("includeLabelsOR")), set(c.getAttribute("excludeLabelsOR")));
    }

    // ---- small helpers -------------------------------------------------------------------------------------

    private static List<Element> children(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            if (nl.item(i) instanceof Element el && (tag == null || el.getTagName().equals(tag))) out.add(el);
        }
        return out;
    }

    private static Element child(Element parent, String tag) {
        List<Element> c = children(parent, tag);
        return c.isEmpty() ? null : c.get(0);
    }

    private static String text(Element parent, String tag) {
        Element c = child(parent, tag);
        return c == null ? "" : c.getTextContent().trim();
    }

    private static Set<String> set(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> s = new LinkedHashSet<>();
        for (String p : csv.split(",")) if (!p.isBlank()) s.add(p.trim());
        return Collections.unmodifiableSet(s);
    }

    private static Integer decode(String s) {
        try {
            return s == null || s.isBlank() ? null : Integer.decode(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double number(String s) {
        try {
            return s == null || s.isBlank() ? 0 : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
