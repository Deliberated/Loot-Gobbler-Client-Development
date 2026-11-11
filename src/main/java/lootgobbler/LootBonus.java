package lootgobbler;

import lootgobbler.enchant.Effect;
import lootgobbler.enchant.Enchantment;
import lootgobbler.enchant.Enchantments;

import java.util.ArrayList;
import java.util.List;

/**
 * A character's loot bonus: what its sources add up to, in percent.
 * <ul>
 * <li>enchantments on the equipped weapon, ability, armor and ring (Loot Bonus, from the game data)</li>
 * <li>its crucible and blood ritual (their loot bonus, from the settings the game sends)</li>
 * <li>a loot drop boost while one runs: Loot Drop Potion, Lucky Clover, Golden Lucky Clover</li>
 * </ul>
 */
public final class LootBonus {
    /** What a running loot drop boost adds (the potion and the clovers all start the same boost). */
    public static final double LOOT_DROP_BOOST = 50;

    /** One source: its name, what it adds in percent, and whether that is known (false: active, amount unknown). */
    public record Part(String source, double percent, boolean known) {
    }

    private LootBonus() {
    }

    /** The sources that apply to the character now. */
    public static List<Part> parts(CharacterStore.Char c, long now) {
        List<Part> out = new ArrayList<>();
        double enchants = 0;
        if (c.enchantIds != null) {
            for (int slot = 0; slot < Math.min(4, c.enchantIds.length); slot++) {
                if (c.enchantIds[slot] == null) continue;
                for (int id : c.enchantIds[slot]) enchants += loot(id);
            }
        }
        if (enchants > 0) out.add(new Part("Enchantments", enchants, true));
        if (c.crucible) out.add(multiplier("Crucible", c.crucibleLoot));
        if (c.ritual) out.add(multiplier("Blood ritual", c.ritualLoot));
        if (c.lootDropUntil > now) out.add(new Part("Loot drop boost", LOOT_DROP_BOOST, true));
        return out;
    }

    /** All sources added up, in percent. */
    public static double total(List<Part> parts) {
        double t = 0;
        for (Part p : parts) if (p.known()) t += p.percent();
        return t;
    }

    /** What an enchantment adds to loot, in percent (0 for none or an unknown one). */
    public static double loot(int enchantId) {
        Enchantment e = enchantId < 0 ? null : Enchantments.get().byType(enchantId);
        if (e == null) return 0;
        double sum = 0;
        for (Effect f : e.effects()) if (f instanceof Effect.Reward r && r.kind().equals("LootBonus")) sum += r.amount();
        return sum;
    }

    private static Part multiplier(String source, Double mult) {
        return mult == null ? new Part(source, 0, false) : new Part(source, (mult - 1) * 100, true);
    }
}
