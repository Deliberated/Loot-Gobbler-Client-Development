package lootgobbler;

import com.formdev.flatlaf.FlatLaf;
import lootgobbler.themes.*;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Theme switching and bundled pixel fonts. Each theme is a palette file (themes/<Name>Laf.properties);
 * the shared styling in themes/common.properties is applied on top of every palette.
 * Theme keys are what gets saved under "theme" in the settings file.
 */
public class Themes {
    public static final String GOBBLER_GOLD = "gobblerGold";
    public static final String GOBLIN_GREEN = "goblinGreen";
    public static final String TREASURE_HOARD = "treasureHoard";
    public static final String BURLAP_SACK = "burlapSack";
    public static final String GEM_VAULT = "gemVault";
    public static final String AMETHYST_MOSS = "amethystMoss";

    /**
     * Theme keys in picker order, with their labels.
     */
    public static final String[] KEYS = {GOBBLER_GOLD, GOBLIN_GREEN, TREASURE_HOARD, BURLAP_SACK, GEM_VAULT,
            AMETHYST_MOSS};
    public static final String[] LABELS = {"Gobbler Gold", "Goblin Green", "Treasure Hoard", "Burlap Sack", "Gem Vault",
            "Amethyst Moss"};

    /**
     * Heading font (tabs, section titles, buttons) and body font (everything else), both bundled.
     */
    public static final String HEAD_FONT = "Jersey 10";
    public static final String BODY_FONT = "Jersey 15";
    public static final String PIXEL_FONT = BODY_FONT;

    private static final String[] FONT_FILES = {"/fonts/Jersey10-Regular.ttf", "/fonts/Jersey15-Regular.ttf"};

    /**
     * Maps saved font names of older versions to a current one.
     */
    public static String normalizeFont(String name) {
        return name == null || name.equals("VT323") || name.equals("Silkscreen") ? PIXEL_FONT : name;
    }
    private static boolean initialized = false;

    /**
     * Maps saved values (including those of older versions) to a current theme key.
     */
    public static String normalize(String key) {
        if (key == null) return TREASURE_HOARD;   // default for new users
        return switch (key) {
            case GOBBLER_GOLD, GOBLIN_GREEN, TREASURE_HOARD, BURLAP_SACK, GEM_VAULT, AMETHYST_MOSS -> key;
            case "goblinBurlap", "leatherSatchel", "mossPouch" -> BURLAP_SACK;   // removed themes: closest replacement
            case "dark", "darcula", "contrastDark", "solarizedDark" -> GOBLIN_GREEN;
            case "ember" -> TREASURE_HOARD;
            case "midnight", "purp" -> GEM_VAULT;
            default -> TREASURE_HOARD;
        };
    }

    public static void install(String key) {
        init();
        FlatLaf laf = switch (normalize(key)) {
            case GOBLIN_GREEN -> new GoblinGreenLaf();
            case TREASURE_HOARD -> new TreasureHoardLaf();
            case BURLAP_SACK -> new BurlapSackLaf();
            case GEM_VAULT -> new GemVaultLaf();
            case AMETHYST_MOSS -> new AmethystMossLaf();
            default -> new GobblerGoldLaf();
        };
        FlatLaf.setup(laf);
        FlatLaf.updateUI();
    }

    /**
     * Registers the bundled fonts ("Jersey 10", "Jersey 15") and the shared theme styling. Runs once.
     */
    public static synchronized void init() {
        if (initialized) return;
        initialized = true;
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (String file : FONT_FILES) {
            try (InputStream in = Themes.class.getResourceAsStream(file)) {
                if (in == null) {
                    System.err.println("Missing font resource " + file);
                    continue;
                }
                ge.registerFont(Font.createFont(Font.TRUETYPE_FONT, in));
            } catch (Exception e) {
                System.err.println("Could not load font " + file + ": " + e.getMessage());
            }
        }

        Properties common = new Properties();
        try (InputStream in = Themes.class.getResourceAsStream("/lootgobbler/themes/common.properties")) {
            if (in != null) common.load(in);
        } catch (Exception e) {
            System.err.println("Could not load theme styling: " + e.getMessage());
        }
        Map<String, String> extra = new HashMap<>();
        for (String k : common.stringPropertyNames()) extra.put(k, common.getProperty(k));
        FlatLaf.setGlobalExtraDefaults(extra);
    }
}
