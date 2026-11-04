package lootgobbler.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The enemy a helper object stands for. Bosses and minions do much of their damage through invisible or effect-only
 * objects (Oryx's Sanctuary: the minion spawn portals, the Holy Beams, Leucoryx's rays, Oryx 3's artifacts) that carry
 * the real enemy's name as their display name, so the name leads back to a sprite worth showing.
 */
final class EnemySprites {
    private static final String LIST = "assets/ObjectID.list", XML_DIR = "assets/xml";
    private static Map<Integer, Integer> fileOf;
    private static Map<String, List<Integer>> enemiesByName;   // lower-case name -> visible enemies of that name
    private static final java.util.Set<Integer> combatants = new java.util.HashSet<>();   // bosses, minions, quests

    private EnemySprites() {
    }

    /**
     * Enemies the object may stand for: those defined in the same game file first (Oryx 3's helpers lead to Oryx 3,
     * not Oryx 2 or Infested Oryx), then bosses and minions before other visible enemy objects (Dammah's invisible
     * Bloodshed Portals lead to the visible ones), then the nearest object id. Its display name first, then with
     * trailing words cut off ("Oryx Deacon Holy Beam" -> "Oryx Deacon"). Empty if none.
     */
    static List<Integer> candidates(int type) {
        String name = assets.IdToAsset.objectName(type);
        if (name == null) return List.of();
        Map<String, List<Integer>> index = index();
        String key = name.toLowerCase(Locale.ROOT).trim();
        while (true) {
            List<Integer> found = index.get(key);
            if (found != null) {
                List<Integer> out = new ArrayList<>(found);
                out.remove((Integer) type);
                if (!out.isEmpty()) {
                    Map<Integer, Integer> files = files();
                    Integer own = files.get(type);
                    out.sort(java.util.Comparator.comparing((Integer a) -> own == null || !own.equals(files.get(a)))
                            .thenComparing(a -> !combatants.contains(a))
                            .thenComparingInt(a -> Math.abs(a - type)));
                    return out;
                }
            }
            int cut = key.lastIndexOf(' ');
            if (cut <= 0) return List.of();
            key = key.substring(0, cut);
        }
    }

    /** Whether the object has no texture of its own to show. */
    static boolean invisible(int type) {
        String sheet = assets.IdToAsset.getObjectTextureName(type, 0);
        return sheet == null || sheet.equals("invisible");
    }

    /**
     * Visible enemy objects by name, from the extracted ObjectID.list (id;display;class;group;stats;texture;labels;
     * idName); the Characters among them with combat labels are also noted in combatants.
     */
    private static synchronized Map<String, List<Integer>> index() {
        if (enemiesByName != null) return enemiesByName;
        Map<String, List<Integer>> out = new HashMap<>();
        try {
            for (String line : Files.readAllLines(Path.of(LIST), StandardCharsets.UTF_8)) {
                String[] f = line.split(";", -1);
                if (f.length < 8 || f[5].isEmpty() || f[5].endsWith(",invisible")) continue;
                boolean character = f[2].equals("Character");
                if (!character && !f[2].equals("GameObject")) continue;
                boolean combatant = character && realEnemy(f[6]);
                if (!combatant && !("," + f[6] + ",").contains(",ENEMY,")) continue;
                String name = (f[1].isEmpty() ? f[7] : f[1]).toLowerCase(Locale.ROOT).trim();
                try {
                    int id = Integer.parseInt(f[0]);
                    out.computeIfAbsent(name, k -> new ArrayList<>()).add(id);
                    if (combatant) combatants.add(id);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            // not extracted yet: no fallbacks, and the next call tries again
            return Map.of();
        }
        enemiesByName = out;
        return out;
    }

    /** Object type -> the extracted XML file it is defined in (as an index), read once. */
    private static synchronized Map<Integer, Integer> files() {
        if (fileOf != null) return fileOf;
        Map<Integer, Integer> out = new HashMap<>();
        java.io.File[] xml = new java.io.File(XML_DIR).listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".xml"));
        if (xml == null) return Map.of();   // not extracted yet: try again next time
        java.util.regex.Pattern object = java.util.regex.Pattern.compile("<Object type=\"(0x[0-9a-fA-F]+)\"");
        for (int i = 0; i < xml.length; i++) {
            try {
                java.util.regex.Matcher m = object.matcher(Files.readString(xml[i].toPath(), StandardCharsets.UTF_8));
                while (m.find()) out.putIfAbsent(Integer.decode(m.group(1)), i);
            } catch (IOException | RuntimeException ignored) {
                // an unreadable file only costs its own objects
            }
        }
        fileOf = out;
        return out;
    }

    private static boolean realEnemy(String labels) {
        for (String l : labels.split(",")) {
            switch (l) {
                case "BOSS", "MINIBOSS", "MINION", "QUEST", "TRACKLOOT" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }
}
