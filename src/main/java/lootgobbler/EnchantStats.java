package lootgobbler;

import lootgobbler.enchant.Effect;
import lootgobbler.enchant.Enchantment;
import lootgobbler.enchant.Enchantments;

import java.util.HashMap;
import java.util.Map;

/**
 * The flat stat bonuses of an enchantment: e.g. Attack Bonus I adds 1.4 attack (stat names as in the game data:
 * MAXHP, MAXMP, ATT, DEF, SPD, DEX, VIT, WIS). Only plain increments count; bonuses relative to another stat,
 * multipliers and regeneration are left out (see enchant.EnchantPlanner for those).
 */
public final class EnchantStats {
    private EnchantStats() {
    }

    /** The enchantment's flat stat bonuses (stat name -> amount); empty if it has none or is unknown. */
    public static Map<String, Double> of(int enchantId) {
        Enchantment e = Enchantments.get().byType(enchantId);
        if (e == null) return Map.of();
        Map<String, Double> out = new HashMap<>();
        for (Effect f : e.effects()) if (f instanceof Effect.StatBonus b) out.merge(b.stat(), b.amount(), Double::sum);
        return out;
    }
}
