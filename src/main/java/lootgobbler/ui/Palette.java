package lootgobbler.ui;

import java.awt.Color;

/**
 * Accent colors that stay the same in every theme (channel and section markers).
 * Theme-dependent colors come from UIManager instead.
 */
public final class Palette {
    public static final Color BLUE = new Color(0x2D7AB0);
    public static final Color GREEN = new Color(0x3C9347);
    public static final Color GOLD = new Color(0xB07D0A);
    public static final Color GOLD_DARK = new Color(0x856008);
    public static final Color PURPLE = new Color(0x8049A8);
    public static final Color RED = new Color(0xBE332D);
    public static final Color STONE = new Color(0x645D4E);
    public static final Color MUTED = new Color(0x938B79);

    /**
     * Blend of two colors; t = share of a.
     */
    public static Color mix(Color a, Color b, double t) {
        return new Color((int) Math.round(a.getRed() * t + b.getRed() * (1 - t)),
                (int) Math.round(a.getGreen() * t + b.getGreen() * (1 - t)),
                (int) Math.round(a.getBlue() * t + b.getBlue() * (1 - t)));
    }

    public static final java.util.Map<String, Color> CLASS_COLORS = java.util.Map.ofEntries(
            java.util.Map.entry("Rogue", new Color(0x6E7B8B)), java.util.Map.entry("Archer", new Color(0x5FA343)),
            java.util.Map.entry("Wizard", new Color(0xB36BE0)), java.util.Map.entry("Priest", new Color(0xE0C36B)),
            java.util.Map.entry("Warrior", new Color(0xC0503C)), java.util.Map.entry("Knight", new Color(0xE88A3A)),
            java.util.Map.entry("Paladin", new Color(0xF2C230)), java.util.Map.entry("Assassin", new Color(0x49A07A)),
            java.util.Map.entry("Necromancer", new Color(0x8C5AD6)), java.util.Map.entry("Huntress", new Color(0xE0679C)),
            java.util.Map.entry("Mystic", new Color(0x3FC4D6)), java.util.Map.entry("Trickster", new Color(0xE85AB4)),
            java.util.Map.entry("Sorcerer", new Color(0x4C8FE0)), java.util.Map.entry("Ninja", new Color(0x7A8899)),
            java.util.Map.entry("Samurai", new Color(0xD64545)), java.util.Map.entry("Bard", new Color(0xD9B44A)),
            java.util.Map.entry("Summoner", new Color(0x57B894)), java.util.Map.entry("Kensei", new Color(0xB5543A)),
            java.util.Map.entry("Druid", new Color(0x6B8E3A)));

    private Palette() {
    }
}
