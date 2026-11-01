package lootgobbler;

import tomato.realmshark.Sound;
import util.PropertiesManager;

/**
 * The sound played once when Loot Gobbler opens, chosen in Settings (saved as "startupSound").
 */
public final class StartupSound {
    public static final String KEY = "startupSound";
    public static final String[] IDS = {"off", "goblinCackle", "goblinGobble", "pm", "whitebag", "goldbag"};
    public static final String[] LABELS = {"Off", "Goblin cackle", "Goblin gobble", "PM ping", "White bag", "Gold bag"};
    private static final String DEFAULT = "goldbag";

    private StartupSound() {
    }

    public static String saved() {
        String id = PropertiesManager.getProperty(KEY);
        return id != null && java.util.Arrays.asList(IDS).contains(id) ? id : DEFAULT;
    }

    public static void playSaved() {
        play(saved());
    }

    public static void play(String id) {
        Sound s = switch (id) {
            case "goblinCackle" -> Sound.goblinCackle;
            case "goblinGobble" -> Sound.goblinGobble;
            case "pm" -> Sound.pm;
            case "whitebag" -> Sound.whitebag;
            case "goldbag" -> Sound.goldbag;
            default -> null;   // off
        };
        if (s != null) s.play();
    }
}
