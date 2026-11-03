package lootgobbler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * How hard each dungeon is, as the game rates it (e.g. Undead Lair 3.5): only the dungeon keys carry it, so each key's
 * DungeonDifficulty goes to the dungeon of the portal it opens (assets/xml: portals name their dungeon in
 * DungeonName). Loaded once in the background; unknown until then and for dungeons without a key.
 */
public final class DungeonDifficulty {
    private static final Pattern OBJECT = Pattern.compile("<Object [^>]*id=\"([^\"]+)\"[^>]*>(.*?)</Object>", Pattern.DOTALL);
    private static final Pattern DUNGEON_NAME = Pattern.compile("<DungeonName>([^<]+)</DungeonName>");
    private static final Pattern DIFFICULTY = Pattern.compile("<DungeonDifficulty>([0-9.]+)</DungeonDifficulty>");
    private static final Pattern PORTAL = Pattern.compile("<Activate id=\"([^\"]+)\">CreatePortal</Activate>");
    private static volatile Map<String, Double> byDungeon;

    private DungeonDifficulty() {
    }

    /** Starts loading in the background (on start), so the first dungeon does not wait for it. */
    public static void preload() {
        Thread t = new Thread(DungeonDifficulty::load, "dungeon-difficulty");
        t.setDaemon(true);
        t.start();
    }

    /** The dungeon's difficulty, or null if unknown (or not loaded yet). */
    public static Double of(String dungeon) {
        Map<String, Double> m = byDungeon;
        return m == null || dungeon == null ? null : m.get(dungeon);
    }

    private static synchronized void load() {
        if (byDungeon != null) return;
        Map<String, String> portalToDungeon = new HashMap<>();
        Map<String, Double> keyPortals = new HashMap<>();   // portal id -> difficulty of a key that opens it
        try (Stream<Path> files = Files.list(Path.of("assets", "xml"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml"))::iterator) {
                String xml = Files.readString(f);
                boolean names = xml.contains("<DungeonName>"), keys = xml.contains("<DungeonDifficulty>");
                if (!names && !keys) continue;
                Matcher o = OBJECT.matcher(xml);
                while (o.find()) {
                    String body = o.group(2);
                    if (names) {
                        Matcher n = DUNGEON_NAME.matcher(body);
                        if (n.find()) portalToDungeon.putIfAbsent(o.group(1), n.group(1).trim());
                    }
                    if (keys) {
                        Matcher d = DIFFICULTY.matcher(body), p = PORTAL.matcher(body);
                        if (d.find() && p.find()) keyPortals.putIfAbsent(p.group(1), Double.parseDouble(d.group(1)));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Could not read the dungeon difficulties: " + e.getMessage());
        }
        Map<String, Double> out = new HashMap<>();
        keyPortals.forEach((portal, difficulty) -> {
            String dungeon = portalToDungeon.get(portal);
            if (dungeon == null && portal.endsWith(" Portal")) dungeon = portal.substring(0, portal.length() - 7);
            if (dungeon != null) out.putIfAbsent(dungeon, difficulty);
        });
        byDungeon = out;
    }
}
