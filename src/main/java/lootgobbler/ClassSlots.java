package lootgobbler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per class, from assets/xml/players.xml: the equipment slot types (weapon, ability, armor, ring, e.g. a Wizard's
 * staff, spell, robe and ring; item slot types, ItemDb.Item.slotType, must match to be equipped) and the maximum of
 * each of the eight stats.
 */
public final class ClassSlots {
    private static final Pattern CLASS = Pattern.compile(
            "<Object type=\"(0x[0-9A-Fa-f]+)\"[^>]*>(.*?)</Object>", Pattern.DOTALL);
    private static final Pattern SLOTS = Pattern.compile("<SlotTypes>([^<]*)</SlotTypes>");
    // the stats in the order life, mana, attack, defense, speed, dexterity, vitality, wisdom
    private static final String[] STAT_TAGS = {"MaxHitPoints", "MaxMagicPoints", "Attack", "Defense", "Speed", "Dexterity", "HpRegen", "MpRegen"};
    private static volatile Map<Integer, int[]> slots;
    private static final Map<Integer, int[]> maxStats = new HashMap<>();

    private ClassSlots() {
    }

    /** The class's four equipment slot types (weapon, ability, armor, ring), or null if unknown. */
    public static int[] of(int classType) {
        load();
        return slots.get(classType);
    }

    /** The class's maximum life, mana, attack, defense, speed, dexterity, vitality and wisdom, or null if unknown. */
    public static int[] maxStats(int classType) {
        load();
        return maxStats.get(classType);
    }

    private static void load() {
        if (slots == null) {
            synchronized (ClassSlots.class) {
                if (slots == null) slots = load(Path.of("assets", "xml", "players.xml"));
            }
        }
    }

    private static Map<Integer, int[]> load(Path file) {
        Map<Integer, int[]> out = new HashMap<>();
        try {
            Matcher m = CLASS.matcher(Files.readString(file));
            while (m.find()) {
                Matcher s = SLOTS.matcher(m.group(2));
                if (!s.find()) continue;
                String[] parts = s.group(1).split(",");
                if (parts.length < 4) continue;
                int[] four = new int[4];
                for (int i = 0; i < 4; i++) four[i] = Integer.parseInt(parts[i].trim());
                int type = Integer.decode(m.group(1));
                out.put(type, four);
                int[] max = new int[STAT_TAGS.length];
                for (int i = 0; i < STAT_TAGS.length; i++) {
                    Matcher x = Pattern.compile("<" + STAT_TAGS[i] + " max=\"(\\d+)\"").matcher(m.group(2));
                    if (x.find()) max[i] = Integer.parseInt(x.group(1));
                }
                maxStats.put(type, max);
            }
        } catch (Exception e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
        return out;
    }
}
