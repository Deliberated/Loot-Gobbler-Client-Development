package lootgobbler.enchant;

import lootgobbler.ItemDb;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Against the game files in assets/xml (extracted from the game, not in the repository: skipped without them). */
class EnchantmentsTest {
    private static Enchantments all() {
        assumeTrue(Files.exists(Path.of("assets", "xml", "enchantments.xml")), "no game files extracted");
        return Enchantments.get();
    }

    private static Enchantment key(Enchantments all, String key) {
        return all.all().stream().filter(e -> e.key().equals(key)).findFirst().orElseThrow();
    }

    private static ItemDb.Item item(String name) {
        for (int id = 0; id < 0x10000; id++) {
            ItemDb.Item it = ItemDb.get().item(id);
            if (it != null && it.name.equals(name)) return it;
        }
        throw new AssertionError("no item " + name);
    }

    @Test
    void readsEnchantmentsWithLevelsAndEffects() {
        Enchantments all = all();
        assertTrue(all.all().size() > 900);
        Enchantment atk = all.byType(0x109);
        assertEquals("Attack_Bonus_4", atk.key());
        assertEquals("Attack_Bonus", atk.family());
        assertEquals(4, atk.level());
        assertEquals(Enchantment.Kind.SINGLE_STAT, atk.kind());
        assertEquals(List.of(new Effect.StatBonus("ATT", 3.8)), atk.effects());
        assertEquals(Enchantment.Kind.AWAKENED, key(all, "AWAKENED_DOOM_BOW").kind());
        assertEquals(Enchantment.Kind.UNIQUE, key(all, "SHAITANS_MIGHT").kind());
    }

    @Test
    void sameLabelsExcludeEachOther() {
        Enchantments all = all();
        Enchantment atk = key(all, "Attack_Bonus_4"), def = key(all, "Defense_Bonus_4"), atk1 = key(all, "Attack_Bonus_1");
        assertTrue(atk.conflicts(def), "two single-stat enchantments");
        assertTrue(atk.conflicts(atk1), "two levels of one enchantment");
        Enchantment dual = all.all().stream().filter(e -> e.kind() == Enchantment.Kind.DUAL_STAT && e.rollable()
                && !e.labels().contains("UNIQUE")).findFirst().orElseThrow();
        assertFalse(atk.conflicts(dual), "single and dual stat go together");
        assertTrue(key(all, "SHAITANS_MIGHT").conflicts(key(all, "JESTERS_TRICK")), "one unique per item");
    }

    @Test
    void itemsRollOnlyWhatFitsThem() {
        Enchantments all = all();
        ItemDb.Item doomBow = item("Doom Bow"), dagger = item("Dagger of Foul Malevolence");
        List<Enchantment> onBow = all.rollableOn(doomBow), onDagger = all.rollableOn(dagger);
        assertTrue(onBow.contains(key(all, "AWAKENED_DOOM_BOW")));
        assertFalse(onDagger.contains(key(all, "AWAKENED_DOOM_BOW")));
        assertTrue(onDagger.contains(key(all, "Attack_Bonus_4")));
        assertTrue(onDagger.stream().noneMatch(e -> e.itemNeeds().contains("ARMOR")), "no armor enchantments on a weapon");
    }

    @Test
    void plansTheMostAttackWithoutConflicts() {
        all();
        ItemDb.Item dagger = item("Dagger of Foul Malevolence");
        EnchantPlanner.Plan p = EnchantPlanner.best(dagger, 4, Set.of("ATT"), new double[8]);
        // only one single-stat and one dual-stat enchantment can raise attack: the other slots are free
        assertEquals(2, p.picks().size(), p.picks().toString());
        assertTrue(p.picks().stream().anyMatch(e -> e.labels().contains("SINGLESTAT")));
        assertTrue(p.picks().stream().anyMatch(e -> e.labels().contains("DUALSTAT")));
        for (Enchantment a : p.picks()) for (Enchantment b : p.picks()) if (a != b) assertFalse(a.conflicts(b), a.key() + " / " + b.key());
        assertTrue(p.gains()[EnchantPlanner.STATS.indexOf("ATT")] > 3.8, "more than one enchantment's worth");
        // fewer slots never do better
        assertTrue(EnchantPlanner.best(dagger, 2, Set.of("ATT"), new double[8]).score() <= p.score());
    }

    @Test
    void lockedEnchantmentsStayAndTheRestIsPlannedAroundThem() {
        Enchantments all = all();
        ItemDb.Item dagger = item("Dagger of Foul Malevolence");
        Enchantment def = key(all, "Defense_Bonus_4");   // single stat: takes the place attack would have had
        EnchantPlanner.Plan p = EnchantPlanner.best(dagger, 4, Set.of("ATT"), new double[8], null, List.of(def));
        assertTrue(p.picks().contains(def), p.picks().toString());
        assertTrue(p.picks().stream().noneMatch(e -> e != def && e.conflicts(def)), p.picks().toString());
        assertTrue(p.picks().stream().anyMatch(e -> e.labels().contains("DUALSTAT")), "attack still planned where it fits");
        // locked ones stay even when nothing helps the aim
        EnchantPlanner.Plan only = EnchantPlanner.best(dagger, 1, Set.of("ATT"), new double[8], null, List.of(def));
        assertEquals(List.of(def), only.picks());
    }
}
