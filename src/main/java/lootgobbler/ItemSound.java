package lootgobbler;

import tomato.realmshark.Sound;
import util.PropertiesManager;

import java.io.File;
import java.util.List;

/**
 * A sound for something an item does, chosen in the settings (Item Sounds): one of the sounds bundled with the app
 * (the first is the default), a WAV file of your own, or none. Loaded once and kept, so it plays at once.
 */
public final class ItemSound {
    /** A sound bundled with the app: its file and the name shown in the settings. */
    public record Builtin(String file, String name) {
    }

    /** Your Amulet of Restoration saved you (AmuletSaves). */
    public static final ItemSound AMULET = new ItemSound("amuletSoundFile", "Amulet of Restoration",
            List.of(new Builtin("sound/amulet_activation.wav", "Amulet activation")));
    /** Your Coral Juice Refinery can be used again (ItemCooldowns): five quiet cues to choose from (Bubble first). */
    public static final ItemSound REFINERY = new ItemSound("refinerySoundFile", "Coral Juice Refinery", List.of(
            new Builtin("sound/refinery_bubble.wav", "Bubble"),
            new Builtin("sound/refinery_chime.wav", "Chime"),
            new Builtin("sound/refinery_ping.wav", "Ping"),
            new Builtin("sound/refinery_droplet.wav", "Droplet"),
            new Builtin("sound/refinery_shell.wav", "Shell")));
    /** Your Miniature Alien Generator can be used again (ItemCooldowns): five alien-tech cues (Warp first). */
    public static final ItemSound ALIEN = new ItemSound("alienSoundFile", "Miniature Alien Generator", List.of(
            new Builtin("sound/alien_warp.wav", "Warp"),
            new Builtin("sound/alien_beacon.wav", "Beacon"),
            new Builtin("sound/alien_saucer.wav", "Saucer"),
            new Builtin("sound/alien_arpeggio.wav", "Arpeggio"),
            new Builtin("sound/alien_signal.wav", "Signal")));
    /** Your Fitted Protective Matrix is charged again (ItemCooldowns): five cues (a jacket's Zip first). */
    public static final ItemSound MATRIX = new ItemSound("matrixSoundFile", "Fitted Protective Matrix", List.of(
            new Builtin("sound/matrix_zip.wav", "Zip"),
            new Builtin("sound/matrix_shield.wav", "Shield"),
            new Builtin("sound/matrix_pulse.wav", "Pulse"),
            new Builtin("sound/matrix_recharge.wav", "Recharge"),
            new Builtin("sound/matrix_lock.wav", "Lock")));

    private static final String OFF = "off", BUILTIN = "builtin:";
    private final String key;   // setting: "" or unset = the first builtin; "builtin:<name>"; "off"; or a WAV file's path
    public final String item;
    public final List<Builtin> builtins;
    private Sound loaded;
    private String loadedPath = "";

    private ItemSound(String key, String item, List<Builtin> builtins) {
        this.key = key;
        this.item = item;
        this.builtins = builtins;
    }

    private String setting() {
        String s = PropertiesManager.getProperty(key);
        return s == null ? "" : s.trim();
    }

    /** Its own volume, 0 to 100 (100, the default: as loud as the other sounds). */
    public int volume() {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(PropertiesManager.getProperty(key + "Volume").trim())));
        } catch (Exception e) {
            return 100;
        }
    }

    public void setVolume(int v) {
        PropertiesManager.setProperties(key + "Volume", String.valueOf(Math.max(0, Math.min(100, v))));
    }

    public boolean isOff() {
        return OFF.equals(setting());
    }

    /**
     * The bundled sound chosen (0 = the default), or -1 for your own file or off. Kept by name, so reordering the
     * list does not change it; an unknown name (or the number an earlier version kept) is the default.
     */
    public int builtin() {
        String s = setting();
        if (s.isEmpty()) return 0;
        if (!s.startsWith(BUILTIN)) return -1;
        String name = s.substring(BUILTIN.length());
        for (int i = 0; i < builtins.size(); i++) if (builtins.get(i).name().equalsIgnoreCase(name)) return i;
        return 0;
    }

    /** Your own WAV file, or "" for a bundled sound or off. */
    public String file() {
        return isOff() || builtin() >= 0 ? "" : setting();
    }

    /** One of the bundled sounds (0 = the default). */
    public synchronized void useBuiltin(int i) {
        PropertiesManager.setProperties(key, i <= 0 || i >= builtins.size() ? "" : BUILTIN + builtins.get(i).name());
    }

    public synchronized void turnOff() {
        PropertiesManager.setProperties(key, OFF);
    }

    /** Chooses a WAV file of your own; false (and nothing changes) if it cannot be played. */
    public synchronized boolean set(String path) {
        Sound s = new Sound(path);
        if (!s.isLoaded()) return false;
        PropertiesManager.setProperties(key, path);
        loaded = s;
        loadedPath = path;
        return true;
    }

    /** Plays the sound as set: your file, a bundled one, or nothing when off. */
    public synchronized void play() {
        if (isOff()) return;
        String own = file();
        int b = builtin();
        // a file of yours that went missing: the default
        String path = !own.isEmpty() && new File(own).isFile() ? own : builtins.get(Math.max(0, b)).file();
        if (loaded == null || !path.equals(loadedPath)) {
            loaded = new Sound(path);
            loadedPath = path;
        }
        if (loaded.isLoaded()) loaded.play(volume());
    }
}
