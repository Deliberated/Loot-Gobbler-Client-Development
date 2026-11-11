package lootgobbler.enchant;

import java.util.Map;

/**
 * One effect of an enchantment, from a mutator in enchantments.xml (&lt;ActivateOnEquip amount="1.4"
 * stat="ATT"&gt;IncrementStat&lt;/ActivateOnEquip&gt; and the like). Stats use the game's names: MAXHP, MAXMP, ATT,
 * DEF, SPD, DEX, VIT, WIS.
 * <p>
 * Kinds the app understands get their own type; everything else becomes {@link Other} with its raw attributes, so a
 * new kind of enchantment is still listed (with the game's description) before the app can calculate with it.
 */
public sealed interface Effect {

    /** Adds a fixed amount to a stat (negative for the downside of a tradeoff). */
    record StatBonus(String stat, double amount) implements Effect {
    }

    /**
     * Adds a share of the bonus another stat gets from gear: "+7% ATT of bonus ATT" is stat ATT, of ATT, percent 7;
     * "+0.75% ATT of bonus MP" is stat ATT, of MAXMP, percent 0.75.
     */
    record RelativeStat(String stat, String of, double percent) implements Effect {
        /** What it adds, given the gear's bonus to the stat it is relative to. */
        public double gain(double bonusOf) {
            return bonusOf * percent / 100.0;
        }
    }

    /** Loot, XP or dust: "LootBonus", "XPBonus", "DustBonus" with the amount the game gives. */
    record Reward(String kind, double amount) implements Effect {
    }

    /** Life or mana regeneration: flat per second, or a percentage of the maximum per second. */
    record Regen(String stat, boolean percent, double amount) implements Effect {
    }

    /**
     * Changes the weapon's shots: what ("MinDamage", "MaxDamage", "RateOfFire", "LifetimeMS", "Speed", "Size",
     * "MPCost"), by which factor, and on which projectile or sub-attack (-1 = all).
     */
    record WeaponMod(String what, double factor, int index) implements Effect {
    }

    /** Something that happens on a trigger ("PlayerShoot", "PlayerHit", "PlayerAbility", "EnemyHit"). */
    record Proc(String trigger, String action, Map<String, String> attributes) implements Effect {
    }

    /** A mutator the app has no type for yet. */
    record Other(String mutator, String action, Map<String, String> attributes) implements Effect {
    }
}
