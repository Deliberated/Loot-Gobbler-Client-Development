package lootgobbler.enchant;

import lootgobbler.ItemDb;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Against the game files in assets/xml (not in the repository: skipped without them). */
class WeaponDpsTest {
    private static ItemDb.Item item(String name) {
        assumeTrue(Files.exists(Path.of("assets", "xml", "equip.xml")), "no game files extracted");
        for (int id = 0; id < 0x10000; id++) {
            ItemDb.Item it = ItemDb.get().item(id);
            if (it != null && it.key.equals(name)) return it;
        }
        throw new AssertionError("no item " + name);
    }

    private static Enchantment key(String key) {
        return Enchantments.get().all().stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void morningStarFiresFiveShotsAtStaggeredRates() {
        ItemDb.Item star = item("Morning Star of Mystic Light");
        List<WeaponDps.Line> lines = WeaponDps.lines(star, List.of());
        assertEquals(5, lines.size());
        assertEquals(List.of(0.15, 0.5, 0.9, 0.5, 0.15), lines.stream().map(WeaponDps.Line::rateOfFire).toList());
        // 85 average damage, x0.5 at 0 attack, 1.5 attacks/s at 0 dexterity, 2.2 shots per attack in all
        assertEquals(85 * 0.5 * 1.5 * 2.2, WeaponDps.dps(star, List.of(), 0, 0, 0), 1e-6);
        // defense comes off each shot: 42.5 - 30 = 12.5 a shot
        assertEquals(12.5 * 1.5 * 2.2, WeaponDps.dps(star, List.of(), 0, 0, 30), 1e-6);
        // never below a tenth of a shot
        assertEquals(4.25 * 1.5 * 2.2, WeaponDps.dps(star, List.of(), 0, 0, 1000), 1e-6);
    }

    @Test
    void armorPiercingShotsIgnoreDefense() {
        ItemDb.Item bow = item("Golden Bow");   // one big arrow and two small armor piercing ones
        double open = WeaponDps.dps(bow, List.of(), 50, 50, 0), armored = WeaponDps.dps(bow, List.of(), 50, 50, 40);
        double small = WeaponDps.lines(bow, List.of()).stream().filter(l -> l.shot().armorPiercing())
                .mapToDouble(l -> l.dps(50, 50, 40)).sum();
        assertTrue(small > 0);
        assertEquals(small, WeaponDps.lines(bow, List.of()).stream().filter(l -> l.shot().armorPiercing())
                .mapToDouble(l -> l.dps(50, 50, 0)).sum(), 1e-6);
        assertTrue(armored < open);
    }

    @Test
    void enchantmentsChangeTheShots() {
        ItemDb.Item bow = item("Bramble Bow");
        Enchantment thorns = key("AWAKENED_BRAMBLE_BOW");   // replaces the shot with three armor piercing thorns at 170%
        List<WeaponDps.Line> lines = WeaponDps.lines(bow, List.of(thorns));
        assertTrue(lines.stream().anyMatch(l -> l.from().equals(thorns.name()) && l.count() == 3 && l.shot().armorPiercing()));
        // a rate of fire multiplier raises DPS
        Enchantment rate = Enchantments.get().all().stream().filter(e -> e.effects().stream().anyMatch(f ->
                f instanceof Effect.WeaponMod m && m.what().equals("RateOfFire") && m.factor() > 1 && m.index() < 0))
                .findFirst().orElseThrow();
        assertTrue(WeaponDps.dps(bow, List.of(rate), 50, 50, 0) > WeaponDps.dps(bow, List.of(), 50, 50, 0));
    }

    @Test
    void aimingForDpsPlansWeaponEnchantments() {
        ItemDb.Item bow = item("Bramble Bow");
        EnchantPlanner.Aim aim = new EnchantPlanner.Aim(bow, List.of(), 60, 60, 0, false).onWeapon(bow);
        EnchantPlanner.Plan p = EnchantPlanner.best(bow, 4, Set.of(EnchantPlanner.DPS), new double[8], aim);
        assertFalse(p.picks().isEmpty());
        assertTrue(aim.dps(bow, p.picks(), p.gains()) > aim.dps(bow, List.of(), null), p.picks().toString());
        for (Enchantment a : p.picks()) for (Enchantment b : p.picks()) if (a != b) assertFalse(a.conflicts(b));
    }

    @Test
    void mostWeaponsCanBeWorkedOut() {
        item("Bramble Bow");
        int weapons = 0, known = 0;
        for (int id = 0; id < 0x10000; id++) {
            ItemDb.Item it = ItemDb.get().item(id);
            if (it == null || !it.labels.contains("WEAPON")) continue;
            weapons++;
            if (WeaponDps.dps(it, List.of(), 50, 50, 0) > 0) known++;
        }
        assertTrue(known > weapons * 0.95, known + " of " + weapons);
    }
}
