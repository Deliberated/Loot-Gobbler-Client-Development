package lootgobbler.enchant;

import lootgobbler.ItemDb;

import java.util.*;

/**
 * Finds the enchantments that raise the stats you aim for the most on an item: among what the enchanter can roll on
 * it, as many as its rarity has slots, none of them excluding another.
 * <p>
 * Stats are weighed against each other by what enchanting can do for them: one unit of a stat is the most a single
 * flat enchantment gives it (3.8 attack, 30 life...), so +30 life counts as much as +3.8 attack. Stats you do not aim
 * for count a tenth as much, so a tradeoff still loses a little for what it takes away.
 */
public final class EnchantPlanner {
    /** The stats, in the game's names, in the order of every per-stat array here. */
    public static final List<String> STATS = List.of("MAXHP", "MAXMP", "ATT", "DEF", "SPD", "DEX", "VIT", "WIS");
    /** Aim for the weapon's damage per second (with Aim): attack, dexterity and the weapon's own enchantments. */
    public static final String DPS = "DPS";
    /** Aim for loot bonus (LootBonus enchantments, in percent). */
    public static final String LOOT = "LOOT";
    /** Aim for life / mana regenerated per second (vitality / wisdom, and regeneration enchantments). */
    public static final String HP_REGEN = "HPREGEN", MP_REGEN = "MPREGEN";
    private static final double OTHER_STATS = 0.1;   // what stats you do not aim for count, against those you do
    private static final int CANDIDATES = 40;        // the best single enchantments tried in combinations

    /** Enchantments for an item, what they add to each stat, and how good that is for the chosen stats. */
    public record Plan(List<Enchantment> picks, double[] gains, double score) {
        public static final Plan NONE = new Plan(List.of(), new double[STATS.size()], 0);
    }

    private static volatile double[] units;

    /**
     * What aiming for damage per second starts from: the weapon, its enchantments, attack and dexterity without the
     * slot being planned, and the enemy's defense. With onWeapon, the slot being planned is the weapon: the item and
     * enchantments planned replace weapon and weaponEnchants.
     * <p>
     * DPS counts in the same units as stats: what one unit of attack (the most one enchantment gives) adds to it.
     */
    public record Aim(ItemDb.Item weapon, List<Enchantment> weaponEnchants, double att, double dex, double def,
                      boolean onWeapon, double hp, double mp, double vit, double wis, double hpPercent,
                      double mpPercent) {
        /**
         * Only for DPS. The rest (hp ... mpPercent) is for regeneration: life, mana, vitality and wisdom without the
         * slot being planned, and the percentage regeneration (a fraction of the maximum per second) of the other slots.
         */
        public Aim(ItemDb.Item weapon, List<Enchantment> weaponEnchants, double att, double dex, double def,
                   boolean onWeapon) {
            this(weapon, weaponEnchants, att, dex, def, onWeapon, 0, 0, 0, 0, 0, 0);
        }

        /** The same, planning this item in the weapon slot. */
        public Aim onWeapon(ItemDb.Item item) {
            return new Aim(item, List.of(), att, dex, def, true, hp, mp, vit, wis, hpPercent, mpPercent);
        }

        public double dps(ItemDb.Item w, Collection<Enchantment> e, double[] gains) {
            int a = STATS.indexOf("ATT"), d = STATS.indexOf("DEX");
            return WeaponDps.dps(w, e, att + (gains == null ? 0 : gains[a]), dex + (gains == null ? 0 : gains[d]), def);
        }

        /** What these gains (and, on the weapon, these enchantments) add to DPS, in units of attack. */
        double value(double[] gains, Collection<Enchantment> picks) {
            return valueOf(weapon, onWeapon ? picks : weaponEnchants, gains);
        }

        /**
         * DPS with weapon w (enchantments e) and these gains, against DPS as the aim starts, in units of attack:
         * what one more unit of attack adds to the aim's weapon (or to w, without one).
         */
        public double valueOf(ItemDb.Item w, Collection<Enchantment> e, double[] gains) {
            if (!WeaponDps.isWeapon(w)) return 0;
            ItemDb.Item ref = WeaponDps.isWeapon(weapon) ? weapon : w;
            Collection<Enchantment> refEnchants = ref == weapon ? weaponEnchants : e;
            double base = WeaponDps.isWeapon(weapon) ? WeaponDps.dps(weapon, weaponEnchants, att, dex, def) : 0;
            double unit = WeaponDps.dps(ref, refEnchants, att + units()[STATS.indexOf("ATT")], dex, def)
                    - WeaponDps.dps(ref, refEnchants, att, dex, def);
            if (unit <= 1e-9) return 0;
            return (dps(w, e, gains) - base) / unit;
        }
    }

    private EnchantPlanner() {
    }

    /**
     * What the enchantments add to each stat. Relative ones count from gearBonus (per stat, what the gear adds on
     * top of the class: they give a share of that bonus).
     */
    public static double[] gains(Collection<Enchantment> enchants, double[] gearBonus) {
        double[] g = new double[STATS.size()];
        for (Enchantment e : enchants) {
            if (e == null) continue;
            for (Effect f : e.effects()) {
                if (f instanceof Effect.StatBonus b) {
                    int i = STATS.indexOf(b.stat());
                    if (i >= 0) g[i] += b.amount();
                } else if (f instanceof Effect.RelativeStat r) {
                    int i = STATS.indexOf(r.stat()), of = STATS.indexOf(r.of());
                    if (i >= 0 && of >= 0 && gearBonus != null) g[i] += r.gain(gearBonus[of]);
                }
            }
        }
        return g;
    }

    /** The enchantments with these numbers (as items carry them; -1 and unknown ones are left out). */
    public static List<Enchantment> of(Collection<Integer> types) {
        List<Enchantment> out = new ArrayList<>();
        if (types == null) return out;
        for (Integer t : types) {
            Enchantment e = t == null || t < 0 ? null : Enchantments.get().byType(t);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** How good these gains are for the chosen stats (see the class comment). */
    public static double score(double[] gains, Set<String> chosen) {
        double[] u = units();
        double s = 0;
        for (int i = 0; i < gains.length; i++) {
            double v = gains[i] / u[i];
            s += chosen.contains(STATS.get(i)) ? v : OTHER_STATS * v;
        }
        return s;
    }

    /**
     * The same, with damage per second when it is chosen: picks are the enchantments planned (they count for DPS
     * when the aim is on the weapon).
     */
    public static double score(double[] gains, Collection<Enchantment> picks, Set<String> chosen, Aim aim) {
        double s = score(gains, chosen);
        if (aim != null && chosen.contains(DPS)) s += aim.value(gains, picks == null ? List.of() : picks);
        return s + extras(gains, picks, chosen, aim);
    }

    /**
     * What loot and regeneration add, when chosen, in units of one enchantment's worth (the most a single flat one
     * gives: 2.5% loot, 4 life or 2 mana a second...). Stats and DPS are left out.
     */
    public static double extras(double[] gains, Collection<Enchantment> picks, Set<String> chosen, Aim aim) {
        if (picks == null) picks = List.of();
        double s = 0;
        if (chosen.contains(LOOT)) s += loot(picks) / extraUnit("LOOT");
        if (chosen.contains(HP_REGEN) || chosen.contains(MP_REGEN)) {
            double hp = aim == null ? 0 : aim.hp(), mp = aim == null ? 0 : aim.mp();
            int iHp = STATS.indexOf("MAXHP"), iMp = STATS.indexOf("MAXMP"), iVit = STATS.indexOf("VIT"), iWis = STATS.indexOf("WIS");
            if (chosen.contains(HP_REGEN)) {
                double gained = HP_PER_VIT * gains[iVit] + flatRegen(picks, "HP") + percentRegen(picks, "HP") * (hp + gains[iHp])
                        + (aim == null ? 0 : aim.hpPercent()) * gains[iHp];
                s += gained / extraUnit("HP");
            }
            if (chosen.contains(MP_REGEN)) {
                double gained = MP_PER_WIS * gains[iWis] + flatRegen(picks, "MP") + percentRegen(picks, "MP") * (mp + gains[iMp])
                        + (aim == null ? 0 : aim.mpPercent()) * gains[iMp];
                s += gained / extraUnit("MP");
            }
        }
        return s;
    }

    /** Life / mana regenerated per second in combat: 1 + 0.24 x VIT / 0.5 + 0.12 x WIS, plus the enchantments'. */
    public static final double HP_PER_VIT = 0.24, MP_PER_WIS = 0.12;

    /** Regeneration per second of "HP" or "MP" with this maximum, vitality or wisdom, and these enchantments. */
    public static double regen(String stat, double max, double attribute, Collection<Enchantment> enchants) {
        double base = stat.equals("HP") ? 1 + HP_PER_VIT * attribute : 0.5 + MP_PER_WIS * attribute;
        return base + flatRegen(enchants, stat) + percentRegen(enchants, stat) * max;
    }

    /** Loot bonus of these enchantments, in percent. */
    public static double loot(Collection<Enchantment> enchants) {
        double sum = 0;
        for (Enchantment e : enchants) {
            if (e == null) continue;
            for (Effect f : e.effects()) if (f instanceof Effect.Reward r && r.kind().equals("LootBonus")) sum += r.amount();
        }
        return sum;
    }

    /** Flat regeneration of "HP" or "MP" per second. */
    public static double flatRegen(Collection<Enchantment> enchants, String stat) {
        return regenOf(enchants, stat, false);
    }

    /** Percentage regeneration of "HP" or "MP": the fraction of the maximum regenerated per second. */
    public static double percentRegen(Collection<Enchantment> enchants, String stat) {
        return regenOf(enchants, stat, true);
    }

    private static double regenOf(Collection<Enchantment> enchants, String stat, boolean percent) {
        double sum = 0;
        for (Enchantment e : enchants) {
            if (e == null) continue;
            for (Effect f : e.effects()) {
                if (f instanceof Effect.Regen r && r.stat().equals(stat) && r.percent() == percent) sum += r.amount();
            }
        }
        return sum;
    }

    private static volatile Map<String, Double> extraUnits;

    /** The most one rollable, not unique enchantment gives: "LOOT" (percent), "HP" / "MP" (flat regeneration). */
    private static double extraUnit(String what) {
        Map<String, Double> u = extraUnits;
        if (u == null) {
            u = new HashMap<>();
            for (Enchantment e : Enchantments.get().all()) {
                if (!e.rollable() || e.labels().contains("UNIQUE")) continue;
                for (Effect f : e.effects()) {
                    if (f instanceof Effect.Reward r && r.kind().equals("LootBonus")) u.merge("LOOT", r.amount(), Math::max);
                    if (f instanceof Effect.Regen r && !r.percent()) u.merge(r.stat(), r.amount(), Math::max);
                }
            }
            extraUnits = u;
        }
        double v = u.getOrDefault(what, 1.0);
        return v > 0 ? v : 1;
    }

    /** The best enchantments for the item with this many slots, or an empty plan if nothing helps the chosen stats. */
    public static Plan best(ItemDb.Item item, int slots, Set<String> chosen, double[] gearBonus) {
        return best(item, slots, chosen, gearBonus, null);
    }

    /**
     * The same, with damage per second when chosen (aim: what it starts from; null leaves DPS out). On the weapon,
     * scores count the item's own stats too, so they compare with the weapon as it is (see Aim).
     */
    public static Plan best(ItemDb.Item item, int slots, Set<String> chosen, double[] gearBonus, Aim aim) {
        return best(item, slots, chosen, gearBonus, aim, List.of());
    }

    /**
     * The same, keeping these enchantments (locked by the player): every plan has them, the rest of the slots are
     * planned around them. Locked ones alone make the plan if nothing else helps.
     */
    public static Plan best(ItemDb.Item item, int slots, Set<String> chosen, double[] gearBonus, Aim aim,
                            List<Enchantment> locked) {
        if (slots <= 0 || chosen.isEmpty()) return Plan.NONE;
        List<Enchantment> keep = new ArrayList<>();
        for (Enchantment e : locked) if (e != null && !keep.contains(e) && keep.size() < slots) keep.add(e);
        record Scored(Enchantment e, double score) {
        }
        double none = score(new double[STATS.size()], List.of(), chosen, aim);   // the item without enchantments
        List<Scored> single = new ArrayList<>();
        for (Enchantment e : Enchantments.get().rollableOn(item)) {
            double s = score(gains(List.of(e), gearBonus), List.of(e), chosen, aim) - none;
            if (s > 1e-9) single.add(new Scored(e, s));
        }
        single.sort(Comparator.comparingDouble(Scored::score).reversed());
        // the best level of each family only (levels of one family exclude each other): otherwise four levels of a
        // few strong families fill the list and push out whole kinds of enchantment (regeneration behind attack...)
        List<Enchantment> cand = new ArrayList<>();
        Set<String> families = new HashSet<>();
        for (Scored sc : single) {
            if (cand.size() >= CANDIDATES) break;
            if (keep.contains(sc.e()) || keep.stream().anyMatch(k -> k.conflicts(sc.e()))) continue;
            if (families.add(sc.e().family())) cand.add(sc.e());
        }
        Plan[] best = {Plan.NONE};
        if (!keep.isEmpty()) {   // the locked ones are the plan to beat, whatever they score
            double[] g = gains(keep, gearBonus);
            best[0] = new Plan(List.copyOf(keep), g, score(g, keep, chosen, aim) - none);
        }
        search(cand, 0, new ArrayList<>(keep), Math.min(4, slots), chosen, gearBonus, aim, none, best);
        return best[0];
    }

    private static void search(List<Enchantment> cand, int from, List<Enchantment> picked, int slots,
                               Set<String> chosen, double[] gearBonus, Aim aim, double none, Plan[] best) {
        if (!picked.isEmpty()) {
            double[] g = gains(picked, gearBonus);
            double s = score(g, picked, chosen, aim) - none;
            if (s > best[0].score() + 1e-9) best[0] = new Plan(List.copyOf(picked), g, s);
        }
        if (picked.size() == slots) return;
        outer:
        for (int i = from; i < cand.size(); i++) {
            Enchantment e = cand.get(i);
            for (Enchantment p : picked) if (p.conflicts(e)) continue outer;
            picked.add(e);
            search(cand, i + 1, picked, slots, chosen, gearBonus, aim, none, best);
            picked.remove(picked.size() - 1);
        }
    }

    /** Per stat, the most one rollable flat single-stat enchantment gives it (1 where there is none). */
    static double[] units() {
        double[] u = units;
        if (u == null) {
            u = new double[STATS.size()];
            for (Enchantment e : Enchantments.get().all()) {
                if (!e.rollable() || !e.labels().contains("SINGLESTAT") || !e.labels().contains("FLATBONUS")
                        || e.labels().contains("UNIQUE")) continue;
                for (Effect f : e.effects()) {
                    if (f instanceof Effect.StatBonus b) {
                        int i = STATS.indexOf(b.stat());
                        if (i >= 0) u[i] = Math.max(u[i], b.amount());
                    }
                }
            }
            for (int i = 0; i < u.length; i++) if (u[i] <= 0) u[i] = 1;
            units = u;
        }
        return u;
    }
}
