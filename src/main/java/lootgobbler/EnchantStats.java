package lootgobbler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The flat stat bonuses of enchantments, from assets/xml/enchantments.xml: e.g. Attack Bonus I adds 1.4 attack
 * (stat names as in the game data: MAXHP, MAXMP, ATT, DEF, SPD, DEX, VIT, WIS). Only plain increments count; bonuses
 * relative to another stat, multipliers and regeneration are left out.
 */
public final class EnchantStats {
    private static final Pattern ENCHANT = Pattern.compile(
            "<Enchantment [^>]*type=\"(0x[0-9A-Fa-f]+)\"[^>]*>(.*?)</Enchantment>", Pattern.DOTALL);
    private static final Pattern INCREMENT = Pattern.compile(
            "<ActivateOnEquip amount=\"([-0-9.]+)\" stat=\"([A-Z]+)\">IncrementStat</ActivateOnEquip>");
    private static volatile Map<Integer, Map<String, Double>> bonuses;

    private EnchantStats() {
    }

    /** The enchantment's flat stat bonuses (stat name -> amount); empty if it has none or is unknown. */
    public static Map<String, Double> of(int enchantId) {
        if (bonuses == null) {
            synchronized (EnchantStats.class) {
                if (bonuses == null) bonuses = load(Path.of("assets", "xml", "enchantments.xml"));
            }
        }
        return bonuses.getOrDefault(enchantId, Map.of());
    }

    private static Map<Integer, Map<String, Double>> load(Path file) {
        Map<Integer, Map<String, Double>> out = new HashMap<>();
        try {
            Matcher m = ENCHANT.matcher(Files.readString(file));
            while (m.find()) {
                Map<String, Double> stats = new HashMap<>();
                Matcher i = INCREMENT.matcher(m.group(2));
                while (i.find()) stats.merge(i.group(2), Double.parseDouble(i.group(1)), Double::sum);
                if (!stats.isEmpty()) out.put(Integer.decode(m.group(1)), stats);
            }
        } catch (Exception e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
        return out;
    }
}
