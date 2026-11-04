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
    private static final String LIST = "assets/ObjectID.list";
    private static Map<String, List<Integer>> enemiesByName;   // lower-case name -> real enemies of that name

    private EnemySprites() {
    }

    /**
     * Real enemies the object may stand for, nearest object id first (a dungeon's objects are numbered together, so
     * Oryx 3's helpers lead to Oryx 3, not Oryx 2). Its display name first, then with trailing words cut off
     * ("Oryx Deacon Holy Beam" -> "Oryx Deacon"). Empty if none.
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
                    out.sort((a, b) -> Integer.compare(Math.abs(a - type), Math.abs(b - type)));
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
     * Characters with combat labels by name, from the extracted ObjectID.list
     * (id;display;class;group;stats;texture;labels;idName).
     */
    private static synchronized Map<String, List<Integer>> index() {
        if (enemiesByName != null) return enemiesByName;
        Map<String, List<Integer>> out = new HashMap<>();
        try {
            for (String line : Files.readAllLines(Path.of(LIST), StandardCharsets.UTF_8)) {
                String[] f = line.split(";", -1);
                if (f.length < 8 || !f[2].equals("Character") || f[5].endsWith(",invisible")) continue;
                if (!realEnemy(f[6])) continue;
                String name = (f[1].isEmpty() ? f[7] : f[1]).toLowerCase(Locale.ROOT).trim();
                try {
                    out.computeIfAbsent(name, k -> new ArrayList<>()).add(Integer.parseInt(f[0]));
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
