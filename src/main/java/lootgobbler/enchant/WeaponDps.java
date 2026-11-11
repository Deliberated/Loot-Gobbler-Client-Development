package lootgobbler.enchant;

import lootgobbler.ItemDb;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Damage per second of a weapon, shot by shot, from the game data: every subattack fires its own projectile at its own
 * rate (a morning star's five shots at 15%, 50%, 90%, 50% and 15% rate of fire), and enemy defense is taken off each
 * shot on its own (armor piercing shots ignore it). Enchantments change it: damage and rate of fire multipliers on a
 * projectile or subattack, and awakened ones that replace or add subattacks.
 * <p>
 * The game's formulas: attacks per second 1.5 + 6.5 × DEX / 75 (times the subattack's rate of fire), damage
 * × (0.5 + ATT / 50), and defense never takes more than 90% of a shot. Single target: shots that pierce or hit several
 * enemies count once. Burst subattacks (bees, ...) fire their burst once per burst delay, which shortens with
 * dexterity from the longest to the shortest delay at 75 DEX (an approximation).
 */
public final class WeaponDps {
    /** The share of a shot that always gets through defense. */
    public static final double MIN_DAMAGE_SHARE = 0.1;

    private WeaponDps() {
    }

    /**
     * One subattack as fired: what it is (the weapon's or an enchantment's), its projectile (id within its source),
     * how many per attack, its rate of fire, and bursts (0 = none).
     */
    public record Line(String from, int index, int projectile, ItemDb.Shot shot, int count, double rateOfFire,
                       int burst, double burstDelay, double burstMinDelay) {
        /** Shots per second of this subattack. */
        public double shotsPerSecond(double dex) {
            if (burst > 0 && burstDelay > 0) {
                double t = Math.max(0, Math.min(1, dex / 75));
                double period = burstDelay + (burstMinDelay - burstDelay) * t;
                return period <= 0 ? 0 : count * burst * rateOfFire / period;
            }
            return count * rateOfFire * attacksPerSecond(dex);
        }

        /** Average damage of one shot against this defense. */
        public double perShot(double att, double def) {
            double hit = shot.average() * damageMultiplier(att);
            return shot.armorPiercing() ? hit : Math.max(hit - def, hit * MIN_DAMAGE_SHARE);
        }

        public double dps(double att, double dex, double def) {
            return perShot(att, def) * shotsPerSecond(dex);
        }

        Line times(double damage, double rate) {
            return new Line(from, index, projectile, new ItemDb.Shot(shot.min() * damage, shot.max() * damage,
                    shot.armorPiercing()), count, rateOfFire * rate, burst, burstDelay, burstMinDelay);
        }
    }

    public static double attacksPerSecond(double dex) {
        return 1.5 + 6.5 * dex / 75;
    }

    public static double damageMultiplier(double att) {
        return 0.5 + att / 50;
    }

    /** Whether the item is a weapon this can work out (it shoots something). */
    public static boolean isWeapon(ItemDb.Item item) {
        return item != null && item.labels.contains("WEAPON") && item.weapon != null && !item.weapon.shots.isEmpty();
    }

    /** The weapon's subattacks with these enchantments on it (empty if it is not a weapon). */
    public static List<Line> lines(ItemDb.Item weapon, Collection<Enchantment> enchants) {
        List<Line> out = new ArrayList<>();
        if (!isWeapon(weapon)) return out;
        List<ItemDb.Volley> attacks = weapon.weapon.attacks();
        for (int i = 0; i < attacks.size(); i++) {
            Line l = line(weapon, attacks.get(i), i);
            out.add(l);   // null keeps the index of a subattack whose projectile is unknown
        }
        if (enchants != null) {
            // awakened ones first: they replace or add subattacks, which the multipliers then apply to
            for (Enchantment e : enchants) {
                if (e == null) continue;
                for (Effect f : e.effects()) {
                    if (!(f instanceof Effect.Other o)) continue;
                    boolean set = o.mutator().equals("SetSubAttack"), add = o.mutator().equals("AddSubAttack");
                    if (!set && !add) continue;
                    ItemDb.Item box = ItemDb.get().byKey(o.attributes().get("subAttackObject"));
                    int at = parse(o.attributes().get("subAttackIndex"), -1), which = parse(o.action(), 0);
                    if (box == null || box.weapon == null || at < 0) continue;
                    List<ItemDb.Volley> theirs = box.weapon.attacks();
                    if (which < 0 || which >= theirs.size()) continue;
                    Line l = line(box, theirs.get(which), at);
                    l = l == null ? null : new Line(e.name(), at, l.projectile(), l.shot(), l.count(), l.rateOfFire(),
                            l.burst(), l.burstDelay(), l.burstMinDelay());
                    while (out.size() < at) out.add(null);
                    if (set && at < out.size()) out.set(at, l);
                    else out.add(Math.min(at, out.size()), l);
                }
            }
            for (Enchantment e : enchants) {
                if (e == null) continue;
                for (Effect f : e.effects()) {
                    if (!(f instanceof Effect.WeaponMod m)) continue;
                    for (int i = 0; i < out.size(); i++) {
                        Line l = out.get(i);
                        if (l == null) continue;
                        switch (m.what()) {
                            case "MinDamage", "MaxDamage" -> {
                                if (m.index() >= 0 && m.index() != l.projectile()) continue;
                                // both multipliers come together; each moves the average by half
                                ItemDb.Shot s = l.shot();
                                ItemDb.Shot changed = m.what().equals("MinDamage")
                                        ? new ItemDb.Shot(s.min() * m.factor(), s.max(), s.armorPiercing())
                                        : new ItemDb.Shot(s.min(), s.max() * m.factor(), s.armorPiercing());
                                out.set(i, new Line(l.from(), l.index(), l.projectile(), changed, l.count(),
                                        l.rateOfFire(), l.burst(), l.burstDelay(), l.burstMinDelay()));
                            }
                            case "RateOfFire" -> {
                                if (m.index() >= 0 && m.index() != i) continue;
                                out.set(i, l.times(1, m.factor()));
                            }
                            default -> {
                            }
                        }
                    }
                }
            }
        }
        out.removeIf(l -> l == null);
        return out;
    }

    /** Damage per second against this defense. */
    public static double dps(ItemDb.Item weapon, Collection<Enchantment> enchants, double att, double dex, double def) {
        double sum = 0;
        for (Line l : lines(weapon, enchants)) sum += l.dps(att, dex, def);
        return sum;
    }

    private static Line line(ItemDb.Item from, ItemDb.Volley v, int index) {
        ItemDb.Shot shot = from.weapon.shots.get(v.projectile());
        if (shot == null) return null;
        return new Line(from.name, index, v.projectile(), shot, v.count(), v.rateOfFire(), v.burst(), v.burstDelay(),
                v.burstMinDelay());
    }

    private static int parse(String s, int fallback) {
        try {
            return s == null ? fallback : (int) Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
