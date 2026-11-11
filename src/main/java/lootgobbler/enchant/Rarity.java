package lootgobbler.enchant;

import java.awt.Color;

/**
 * An item's rarity: how many enchantment slots it has (common none, divine four). The marker sprites are in
 * resources /rarity/ (see ui.EnchantBadge).
 */
public enum Rarity {
    COMMON("Common", 0x9CA3AF),
    UNCOMMON("Uncommon", 0x4CD15A),
    RARE("Rare", 0x3FB6F2),
    LEGENDARY("Legendary", 0xB05CF0),
    DIVINE("Divine", 0xF2C232);

    public final String label;
    public final Color color;

    Rarity(String label, int rgb) {
        this.label = label;
        this.color = new Color(rgb);
    }

    /** Enchantment slots of this rarity. */
    public int slots() {
        return ordinal();
    }

    /** The rarity of an item with this many enchantment slots (more than four counts as divine). */
    public static Rarity of(int slots) {
        return values()[Math.max(0, Math.min(DIVINE.ordinal(), slots))];
    }
}
