package lootgobbler.enchant;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An enchantment list from enchantmentLists.xml: which enchantments an item (or an artifact used at the enchanter)
 * rolls from, and how it changes their weights. Items name theirs in &lt;EnchantmentSlots enchantmentList=...&gt;;
 * almost all use "Default Enchantment Pool" (every ROLLABLE enchantment).
 */
public record Pool(String name, List<Rule> entries, Set<String> ids, List<Mod> labelMods, Map<String, Double> idMods) {

    /** Takes in enchantments having any of include and none of exclude. */
    public record Rule(Set<String> include, Set<String> exclude) {
        boolean matches(Set<String> labels) {
            boolean in = false;
            for (String l : include) in |= labels.contains(l);
            if (!in) return false;
            for (String l : exclude) if (labels.contains(l)) return false;
            return true;
        }
    }

    /** Multiplies the weight of enchantments a rule matches. */
    public record Mod(Rule rule, double mult) {
    }

    public boolean contains(Enchantment e) {
        if (ids.contains(e.key())) return true;
        for (Rule r : entries) if (r.matches(e.labels())) return true;
        return false;
    }

    /** Its weight in this pool (0 if it is not in it). */
    public double weight(Enchantment e) {
        if (!contains(e)) return 0;
        double w = e.weight();
        for (Mod m : labelMods) if (m.rule().matches(e.labels())) w *= m.mult();
        return w * idMods.getOrDefault(e.key(), 1.0);
    }
}
