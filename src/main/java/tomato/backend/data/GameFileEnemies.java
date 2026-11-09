package tomato.backend.data;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which dungeon an enemy belongs to, from the game files (assets/xml): many dungeons keep their enemies in a file named
 * after them ("moonlightVillageObjects.xml" for Moonlight Village). Used to tell the dungeon you are in when the
 * sniffer started inside it, for dungeons you have not fought in yet. Read once, in the background (the files are
 * large): null until then. Enemies found in more than one dungeon's file are left out.
 */
final class GameFileEnemies {
    private static final Pattern OBJECT = Pattern.compile("<Object\\s[^>]*type=\"(0x[0-9a-fA-F]+|\\d+)\"[^>]*>(.*?)</Object>", Pattern.DOTALL);
    private static final Pattern SUFFIX = Pattern.compile("(Objects|Ground|Static|Mobs|Enemies|Men)\\w*$");
    private static volatile Map<Integer, String> byType;
    private static volatile boolean started;

    private GameFileEnemies() {
    }

    /** Enemy type -> its dungeon, or null while the files are still being read (that starts with the first call). */
    static Map<Integer, String> get() {
        if (byType == null && !started) {
            started = true;
            Thread t = new Thread(GameFileEnemies::load, "game-file-enemies");
            t.setDaemon(true);
            t.start();
        }
        return byType;
    }

    private static void load() {
        Map<Integer, String> out = new HashMap<>();
        try {
            Map<String, String> dungeons = new HashMap<>();   // squashed name -> dungeon name
            for (String name : tomato.realmshark.ParseDungeon.allDungeonNames()) dungeons.put(squash(name), name);
            Set<Integer> shared = new HashSet<>();
            File[] files = new File("assets/xml").listFiles((d, n) -> n.endsWith(".xml"));
            for (File f : files == null ? new File[0] : files) {
                String dungeon = dungeons.get(squash(SUFFIX.matcher(f.getName().replace(".xml", "")).replaceAll("")));
                if (dungeon == null) continue;
                Matcher m = OBJECT.matcher(Files.readString(f.toPath()));
                while (m.find()) {
                    if (!m.group(2).contains("<Enemy")) continue;
                    String t = m.group(1);
                    int type = t.startsWith("0x") ? Integer.parseInt(t.substring(2), 16) : Integer.parseInt(t);
                    String before = out.putIfAbsent(type, dungeon);
                    if (before != null && !before.equals(dungeon)) shared.add(type);
                }
            }
            shared.forEach(out::remove);
        } catch (Exception e) {
            System.err.println("Could not read the dungeons' enemies from the game files: " + e);
        }
        byType = Map.copyOf(out);
    }

    /** "The Nest" -> "nest": lower case letters only, without a leading "the" (files are named without it). */
    private static String squash(String s) {
        String l = s.toLowerCase().replaceAll("[^a-z]", "");
        return l.startsWith("the") && l.length() > 6 ? l.substring(3) : l;
    }
}
