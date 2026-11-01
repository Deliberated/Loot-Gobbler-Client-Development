package lootgobbler;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The game's 4-piece ST sets (assets/xml/equipmentsets.xml): pieces per slot, class, display title,
 * the skin the full set unlocks and the set bonuses. UT item combos that use the same mechanism are left out.
 */
public final class StSets {
    public static final String[] SLOTS = {"Weapon", "Ability", "Armor", "Ring"};

    public record Set(String name, String title, String className, int skin, int[] pieces, String bonus) {
        public int owned(java.util.Set<Integer> collected) {
            int n = 0;
            for (int id : pieces) if (collected.contains(id)) n++;
            return n;
        }
    }

    private static final Map<Integer, String> CLASS_BY_ABILITY = Map.ofEntries(
            Map.entry(4, "Priest"), Map.entry(5, "Knight"), Map.entry(11, "Wizard"), Map.entry(12, "Paladin"),
            Map.entry(13, "Rogue"), Map.entry(15, "Archer"), Map.entry(16, "Warrior"), Map.entry(18, "Assassin"),
            Map.entry(19, "Necromancer"), Map.entry(20, "Huntress"), Map.entry(21, "Mystic"), Map.entry(22, "Trickster"),
            Map.entry(23, "Sorcerer"), Map.entry(25, "Ninja"), Map.entry(27, "Samurai"), Map.entry(28, "Bard"),
            Map.entry(29, "Summoner"), Map.entry(30, "Kensei"), Map.entry(31, "Druid"));

    private static volatile List<Set> sets;

    /**
     * All ST sets. The first call parses the XML (needs ItemDb, so call it off the EDT).
     */
    public static List<Set> get() {
        List<Set> s = sets;
        if (s == null) {
            synchronized (StSets.class) {
                if (sets == null) sets = load(new File("assets/xml/equipmentsets.xml"));
                s = sets;
            }
        }
        return s;
    }

    private static List<Set> load(File file) {
        List<Set> out = new ArrayList<>();
        if (!file.exists()) {
            System.err.println("StSets: " + file + " not found - extract assets first");
            return out;
        }
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = f.newDocumentBuilder().parse(file);
            ItemDb db = ItemDb.get();
            NodeList list = doc.getElementsByTagName("EquipmentSet");
            for (int i = 0; i < list.getLength(); i++) {
                Set set = parse((Element) list.item(i), db);
                if (set != null) out.add(set);
            }
        } catch (Exception e) {
            System.err.println("StSets: could not read " + file + ": " + e.getMessage());
        }
        return Collections.unmodifiableList(out);
    }

    private static Set parse(Element s, ItemDb db) {
        NodeList pieces = s.getElementsByTagName("Setpiece");
        if (pieces.getLength() < 4) return null;
        int[] ids = new int[4];
        for (int p = 0; p < pieces.getLength(); p++) {   // some sets list an alternate item for a slot; keep the first
            Element pe = (Element) pieces.item(p);
            int slot = Integer.parseInt(pe.getAttribute("slot"));
            if (slot < 0 || slot > 3) return null;
            if (ids[slot] == 0) ids[slot] = Integer.decode(pe.getAttribute("itemtype"));
        }
        for (int id : ids) if (id == 0) return null;   // all four slots needed
        boolean st = false;
        for (int id : ids) st |= db.item(id) != null && db.item(id).isST();
        if (!st) return null;   // UT item combos (Alien Core, Loaded Core...) are not ST sets

        ItemDb.Item ability = db.item(ids[1]);
        String cls = ability == null ? "Other" : CLASS_BY_ABILITY.getOrDefault(ability.slotType, "Other");
        String name = s.getAttribute("id");
        String title = name.replaceAll("\\s+Set$", "");
        if (!cls.equals("Other") && !title.contains(cls)) title += " " + cls;

        int skin = 0;
        NodeList all = s.getElementsByTagName("ActivateOnEquipAll");
        for (int k = 0; k < all.getLength(); k++) {
            Element e = (Element) all.item(k);
            if (e.getTextContent().trim().equals("ChangeSkin") && !e.getAttribute("skinType").isEmpty()) {
                skin = Integer.decode(e.getAttribute("skinType"));
            }
        }
        return new Set(name, title, cls, skin, ids, bonus(s));
    }

    private static String bonus(Element s) {
        StringBuilder sb = new StringBuilder();
        for (String tag : new String[]{"ActivateOnEquip2", "ActivateOnEquip3", "ActivateOnEquipAll"}) {
            NodeList l = s.getElementsByTagName(tag);
            StringBuilder part = new StringBuilder();
            for (int i = 0; i < l.getLength(); i++) {
                Element e = (Element) l.item(i);
                if (!e.getTextContent().trim().equals("IncrementStat")) continue;
                int amt = Integer.parseInt(e.getAttribute("amount"));
                part.append(amt > 0 ? " +" : " ").append(amt).append(' ')
                        .append(e.getAttribute("stat").replace("MAXHP", "HP").replace("MAXMP", "MP"));
            }
            if (part.length() > 0) {
                if (sb.length() > 0) sb.append("   ");
                sb.append(tag.endsWith("2") ? "2pc:" : tag.endsWith("3") ? "3pc:" : "4pc:").append(part);
            }
        }
        return sb.toString();
    }

    private StSets() {
    }
}
