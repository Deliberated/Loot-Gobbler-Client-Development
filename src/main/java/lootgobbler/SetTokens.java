package lootgobbler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ST pieces the forge crafts from set tokens (assets/xml/forgeProperties.xml): for each piece, the token it takes
 * and how many (usually 2 for weapon and ability, 1 for armor and ring).
 */
public final class SetTokens {
    /** The token a piece is crafted from, and how many it takes. */
    public record Cost(int token, int amount) {
    }

    private static final Pattern RECIPE = Pattern.compile(
            "<ForgeProperties type=\"(0x[0-9A-Fa-f]+)\"[^>]*>(.*?)</ForgeProperties>", Pattern.DOTALL);
    private static final Pattern REQUIRED = Pattern.compile(
            "<RequiredItem quantity=\"(\\d+)\">(0x[0-9A-Fa-f]+)</RequiredItem>");
    private static volatile Map<Integer, Cost> costs;

    private SetTokens() {
    }

    /** The token cost of crafting this piece, or null if the forge cannot make it from a set token. */
    public static Cost of(int pieceId) {
        return all().get(pieceId);
    }

    private static Map<Integer, Cost> all() {
        if (costs == null) {
            synchronized (SetTokens.class) {
                if (costs == null) costs = load(Path.of("assets", "xml", "forgeProperties.xml"));
            }
        }
        return costs;
    }

    private static Map<Integer, Cost> load(Path file) {
        Map<Integer, Cost> out = new HashMap<>();
        try {
            Matcher m = RECIPE.matcher(Files.readString(file));
            while (m.find()) {
                Matcher r = REQUIRED.matcher(m.group(2));
                if (!r.find()) continue;
                int token = Integer.decode(r.group(2));
                ItemDb.Item t = ItemDb.get().item(token);
                if (t == null || !t.name.toLowerCase().contains("set token")) continue;   // other forge materials
                out.put(Integer.decode(m.group(1)), new Cost(token, Integer.parseInt(r.group(1))));
            }
        } catch (Exception e) {
            System.err.println("Could not read " + file + ": " + e.getMessage());
        }
        return out;
    }
}
