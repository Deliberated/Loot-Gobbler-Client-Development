package oracle;

import assets.IdToAsset;
import tomato.realmshark.enums.CharacterClass;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prints the game data as the Java client loaded it from ./assets (IdToAsset, CharacterClass), one line per object,
 * tile and class, as `lg-replay gamedata <assets>` does. Only for comparing the Rust port (rust/oracle/compare-gamedata.sh).
 */
public final class GameDataOracle {
    /** The output; System.out goes to the log, as the app's own messages land there ("initialized with ..."). */
    private static final java.io.PrintStream OUT = System.out;

    public static void main(String[] args) throws Exception {
        System.setOut(System.err);
        for (Map.Entry<Integer, Object> e : sorted("objectID").entrySet()) {
            Object a = e.getValue();
            StringBuilder p = new StringBuilder();
            Object projectiles = get(a, "projectiles");
            if (projectiles != null) {
                for (int i = 0; i < Array.getLength(projectiles); i++) {
                    Object x = Array.get(projectiles, i);
                    p.append(get(x, "min")).append('/').append(get(x, "max")).append('/').append(get(x, "ap"))
                            .append('/').append(get(x, "slotType")).append(' ');
                }
            }
            OUT.println("object " + e.getKey() + "|" + IdToAsset.objectName(e.getKey()) + "|" + get(a, "clazz") + "|"
                    + get(a, "group") + "|" + get(a, "label") + "|" + get(a, "texture") + "|" + get(a, "idName") + "|" + p.toString().trim());
        }
        for (Map.Entry<Integer, Object> e : sorted("tileID").entrySet()) {
            Object a = e.getValue();
            OUT.println("tile " + e.getKey() + "|" + get(a, "texture") + "|" + get(a, "tileDmg") + "|" + get(a, "idName"));
        }
        for (CharacterClass c : CharacterClass.CHAR_CLASS_LIST) {
            StringBuilder b = new StringBuilder("class " + c.id + "|" + c.name + "|");
            for (int v : c.maxStats) b.append(v).append(' ');
            b.append('|');
            for (int v : c.baseGear) b.append(v).append(' ');
            b.append('|');
            for (int v : c.weaponGroup) b.append(v).append(' ');
            OUT.println(b.toString().replace(" |", "|").trim());
        }
        abilitiesAndEnchants();
        dungeons();
    }

    /** Dungeon portals and modifiers (ParseDungeon), and the enemies of one dungeon (GameFileEnemies). */
    @SuppressWarnings("unchecked")
    private static void dungeons() throws Exception {
        Class<?> pd = tomato.realmshark.ParseDungeon.class;
        for (String m : new String[]{"ID_TO_NAME_PORTAL", "NAME_TO_ID_PORTAL", "ID_TO_NAME_MODS", "NAME_TO_ID_MODS"}) {
            Field f = pd.getDeclaredField(m);
            f.setAccessible(true);
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) f.get(null)).entrySet()) lines.add(m + " " + e.getKey() + "|" + e.getValue());
            java.util.Collections.sort(lines);
            for (String l : lines) OUT.println(l);
        }
        for (String n : tomato.realmshark.ParseDungeon.allDungeonNames()) OUT.println("dungeon " + n);
        Class<?> gfe = Class.forName("tomato.backend.data.GameFileEnemies");
        java.lang.reflect.Method get = gfe.getDeclaredMethod("get");
        get.setAccessible(true);
        Object enemies = null;
        for (int i = 0; i < 600 && (enemies = get.invoke(null)) == null; i++) Thread.sleep(100);
        for (Map.Entry<Integer, String> e : new TreeMap<>((Map<Integer, String>) enemies).entrySet()) {
            OUT.println("enemy " + e.getKey() + "|" + e.getValue());
        }
    }

    /** Ability scaling (equip.xml) and enchant effects (enchantments.xml). */
    @SuppressWarnings("unchecked")
    private static void abilitiesAndEnchants() throws Exception {
        tomato.backend.data.AbilityScalingManager asm = tomato.backend.data.AbilityScalingManager.getInstance();
        asm.initialize();
        Map<Integer, Object> scaling = new TreeMap<>((Map<Integer, Object>) get(asm, "scalingData"));
        for (Map.Entry<Integer, Object> e : scaling.entrySet()) {
            tomato.backend.data.AbilityScalingManager.AbilityScalingData d =
                    (tomato.backend.data.AbilityScalingManager.AbilityScalingData) e.getValue();
            OUT.printf("scaling %d|%d|%d|%08x|%d%n", e.getKey(), d.scalingStat.get(), d.scalingMin,
                    Float.floatToRawIntBits(d.damagePerStat), d.numShots);
        }
        for (Map.Entry<Integer, Integer> e : new TreeMap<>((Map<Integer, Integer>) get(asm, "projectileToWeaponMap")).entrySet()) {
            OUT.println("shotby " + e.getKey() + "|" + e.getValue());
        }
        Field ef = tomato.realmshark.ParseEnchants.class.getDeclaredField("ENCHANT_EFFECTS");
        ef.setAccessible(true);
        Map<Short, Object> effects = new TreeMap<>((Map<Short, Object>) ef.get(null));
        Field rf = tomato.realmshark.ParseEnchants.class.getDeclaredField("ENCHANT_REGEN");
        rf.setAccessible(true);
        Map<Short, Object> regen = (Map<Short, Object>) rf.get(null);
        Field lf = tomato.realmshark.ParseEnchants.class.getDeclaredField("ENCHANT_LOOT_BONUS");
        lf.setAccessible(true);
        Map<Short, Float> loot = (Map<Short, Float>) lf.get(null);
        for (Map.Entry<Short, Object> e : effects.entrySet()) {
            Object x = e.getValue(), r = regen.get(e.getKey());
            String floats = "";
            for (Object o : new Object[]{get(x, "minDamageMult"), get(x, "maxDamageMult"), get(x, "rateOfFireMult"),
                    get(x, "statDamageMult"), get(r, "manaFlatPerSec"), get(r, "manaPercentOfMaxPerSec"),
                    get(r, "lifeFlatPerSec"), get(r, "lifePercentOfMaxPerSec"), loot.get(e.getKey())}) {
                floats += String.format("%08x ", Float.floatToRawIntBits((Float) o));
            }
            OUT.println("enchant " + e.getKey() + "|" + tomato.realmshark.ParseEnchants.ENCHANTS.get(e.getKey()) + "|"
                    + tomato.realmshark.ParseEnchants.ENCHANT_INTERNAL_IDS.get(e.getKey()) + "|" + floats.trim());
        }
    }

    @SuppressWarnings("unchecked")
    private static TreeMap<Integer, Object> sorted(String field) throws Exception {
        Field f = IdToAsset.class.getDeclaredField(field);
        f.setAccessible(true);
        return new TreeMap<>((Map<Integer, Object>) f.get(null));
    }

    private static Object get(Object o, String field) throws Exception {
        Field f = o.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(o);
    }
}
