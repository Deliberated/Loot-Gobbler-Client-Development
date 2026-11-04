package lootgobbler;

import tomato.realmshark.Sound;
import util.PropertiesManager;

import java.io.File;

/**
 * The sound played when your Amulet of Restoration fires: by default the bundled sound/amulet_activation.wav; in
 * the settings a WAV file of your own can be chosen instead, or the sound turned off. Loaded once and kept, so it
 * plays at once when the amulet pops.
 */
public final class AmuletSound {
    private static final String KEY = "amuletSoundFile";   // a WAV file's path; "off"; empty or unset = the default
    private static final String OFF = "off";
    public static final String DEFAULT = "sound/amulet_activation.wav";   // bundled with the app
    private static Sound loaded;
    private static String loadedPath = "";

    private AmuletSound() {
    }

    /** What is set: the default, off, or a file of your own. */
    public static boolean isDefault() {
        String f = PropertiesManager.getProperty(KEY);
        return f == null || f.isBlank();
    }

    public static boolean isOff() {
        return OFF.equals(PropertiesManager.getProperty(KEY));
    }

    /** Your own WAV file, or "" for the default or off. */
    public static String file() {
        return isDefault() || isOff() ? "" : PropertiesManager.getProperty(KEY);
    }

    /** Back to the bundled sound. */
    public static synchronized void useDefault() {
        PropertiesManager.setProperties(KEY, "");
    }

    public static synchronized void turnOff() {
        PropertiesManager.setProperties(KEY, OFF);
    }

    /** Chooses a WAV file of your own; false (and nothing changes) if it cannot be played. */
    public static synchronized boolean set(String path) {
        Sound s = new Sound(path);
        if (!s.isLoaded()) return false;
        PropertiesManager.setProperties(KEY, path);
        loaded = s;
        loadedPath = path;
        return true;
    }

    /** Plays the sound as set: your file, the default, or nothing when off. */
    public static synchronized void play() {
        if (isOff()) return;
        String own = file();
        String path = !own.isEmpty() && new File(own).isFile() ? own : DEFAULT;   // a file that went missing: the default
        if (loaded == null || !path.equals(loadedPath)) {
            loaded = new Sound(path);
            loadedPath = path;
        }
        if (loaded.isLoaded()) loaded.play();
    }
}
