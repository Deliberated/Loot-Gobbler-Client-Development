package lootgobbler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "The amulet": the Amulet of Restoration (and its shiny version), from assets/xml/equip.xml. Below 20% of your
 * maximum life, taking damage makes you invulnerable for two seconds, once every 30 minutes. Read from the game data:
 * rings named "Amulet ..." whose on-hit effect only fires below a share of your life and makes you invulnerable
 * (the Amulet of Dispersion, which heals instead, is left out).
 */
public final class Amulets {
    public record Amulet(int type, String name, double threshold, String effect, int cooldownS) {
        /** The save makes you invulnerable (seen in your status), rather than healing you. */
        public boolean invulnerable() {
            return effect.contains("Invulnerable");
        }
    }

    private static final Pattern OBJECT = Pattern.compile("<Object type=\"(0x[0-9a-fA-F]+)\" id=\"([^\"]+)\"[^>]*>(.*?)</Object>", Pattern.DOTALL);
    private static final Pattern TRIGGER = Pattern.compile("<OnPlayerHitActivate([^>]*hpMinThresholdRelative=\"([0-9.]+)\"[^>]*)>([^<]*)</OnPlayerHitActivate>");
    private static final Pattern DISPLAY = Pattern.compile("<DisplayId>([^<]+)</DisplayId>");
    private static volatile Map<Integer, Amulet> amulets;

    private Amulets() {
    }

    /** The amulet with this item id, or null if it is none. */
    public static Amulet of(int itemId) {
        return load().get(itemId);
    }

    public static boolean isAmulet(int itemId) {
        return of(itemId) != null;
    }

    private static Map<Integer, Amulet> load() {
        Map<Integer, Amulet> m = amulets;
        if (m != null) return m;
        synchronized (Amulets.class) {
            if (amulets != null) return amulets;
            Map<Integer, Amulet> out = new HashMap<>();
            try {
                Matcher o = OBJECT.matcher(Files.readString(Path.of("assets", "xml", "equip.xml")));
                while (o.find()) {
                    String body = o.group(3);
                    Matcher t = TRIGGER.matcher(body);
                    if (!t.find()) continue;
                    Matcher d = DISPLAY.matcher(body);
                    String name = d.find() ? d.group(1) : o.group(2);
                    if (!(name + " " + o.group(2)).toLowerCase(java.util.Locale.ROOT).contains("amulet")) continue;
                    String attrs = t.group(1);
                    Matcher cd = Pattern.compile("cooldown=\"([0-9]+)\"").matcher(attrs), eff = Pattern.compile("effect=\"([^\"]+)\"").matcher(attrs);
                    String effect = eff.find() ? eff.group(1) : t.group(3).trim();
                    if (!effect.contains("Invulnerable")) continue;   // the Amulet of Restoration only
                    int type = Integer.decode(o.group(1));
                    out.put(type, new Amulet(type, name, Double.parseDouble(t.group(2)), effect, cd.find() ? Integer.parseInt(cd.group(1)) : 0));
                }
            } catch (Exception e) {
                System.err.println("Could not read the amulets: " + e.getMessage());
            }
            amulets = out;
            return out;
        }
    }
}
