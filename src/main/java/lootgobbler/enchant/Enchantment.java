package lootgobbler.enchant;

import lootgobbler.ItemDb;

import java.util.List;
import java.util.Set;

/**
 * One enchantment from enchantments.xml, e.g. "Attack Bonus IV" (type 0x109).
 *
 * @param type        the number the game sends for it on items
 * @param key         its id in the game data ("Attack_Bonus_4", "SHAITANS_MIGHT")
 * @param family      the enchantment without its level ("Attack_Bonus"); the key itself if it has no levels
 * @param level       1 to 4 (I to IV), 0 if it has no levels
 * @param weight      how often it rolls against the others (0: never rolls, e.g. legacy ones)
 * @param labels      what it is (STAT, SINGLESTAT, ATTACK, ROLLABLE, UNIQUE, AWAKENED...)
 * @param excludes    labels it cannot share an item with (another enchantment having one of them)
 * @param itemNeeds   labels an item must all have to take it (EQUIPMENT; WEAPON,UT,STINGER...)
 * @param itemExcludes labels an item must have none of
 * @param itemIdsExcluded items (by id) that cannot take it
 * @param sheet       sprite sheet of its icon, with index
 */
public record Enchantment(int type, String key, String family, int level, String name, String description,
                          int weight, Set<String> labels, Set<String> excludes, Set<String> itemNeeds,
                          Set<String> itemExcludes, Set<String> itemIdsExcluded, List<Effect> effects,
                          String sheet, int index) {

    /** The groups the enchanter sorts enchantments into; the first that applies wins. */
    public enum Kind {
        AWAKENED("Awakened"), UNIQUE("Unique"), SINGLE_STAT("Single stat"), DUAL_STAT("Dual stat"),
        REWARD("Reward"), WEAPON("Weapon"), ABILITY("Ability"), ARMOR("Armor"), RING("Ring"), OTHER("Other");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public Kind kind() {
        if (labels.contains("AWAKENED")) return Kind.AWAKENED;
        if (labels.contains("UNIQUE")) return Kind.UNIQUE;
        if (labels.contains("SINGLESTAT")) return Kind.SINGLE_STAT;
        if (labels.contains("DUALSTAT")) return Kind.DUAL_STAT;
        if (labels.contains("REWARD")) return Kind.REWARD;
        // the slot it is made for: what an item must be, else what the enchantment says of itself
        for (Set<String> s : List.of(itemNeeds, labels)) {
            if (s.contains("WEAPON")) return Kind.WEAPON;
            if (s.contains("ABILITY")) return Kind.ABILITY;
            if (s.contains("ARMOR")) return Kind.ARMOR;
            if (s.contains("RING")) return Kind.RING;
        }
        return Kind.OTHER;
    }

    /** Whether the enchanter can roll it at all (event and legacy ones only come with their items). */
    public boolean rollable() {
        return labels.contains("ROLLABLE") && weight > 0;
    }

    /** Whether the item may carry it (its labels and id), whatever its pool. */
    public boolean fits(ItemDb.Item item) {
        if (item == null || !item.labels.containsAll(itemNeeds)) return false;
        for (String l : itemExcludes) if (item.labels.contains(l)) return false;
        return itemIdsExcluded.isEmpty()
                || !(itemIdsExcluded.contains(item.name) || itemIdsExcluded.contains(String.valueOf(item.id))
                || itemIdsExcluded.contains(String.format("0x%x", item.id)));
    }

    /** Whether the two cannot be on one item: either excludes a label of the other, or both are the same family. */
    public boolean conflicts(Enchantment o) {
        if (o == this || family.equals(o.family)) return true;
        for (String l : excludes) if (o.labels.contains(l)) return true;
        for (String l : o.excludes) if (labels.contains(l)) return true;
        return false;
    }

    /** "I" to "IV" for its level, "" without one. */
    public String roman() {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            default -> "";
        };
    }
}
