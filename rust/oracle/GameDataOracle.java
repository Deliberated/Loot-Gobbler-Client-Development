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
    public static void main(String[] args) throws Exception {
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
            System.out.println("object " + e.getKey() + "|" + IdToAsset.objectName(e.getKey()) + "|" + get(a, "clazz") + "|"
                    + get(a, "group") + "|" + get(a, "label") + "|" + get(a, "texture") + "|" + get(a, "idName") + "|" + p.toString().trim());
        }
        for (Map.Entry<Integer, Object> e : sorted("tileID").entrySet()) {
            Object a = e.getValue();
            System.out.println("tile " + e.getKey() + "|" + get(a, "texture") + "|" + get(a, "tileDmg") + "|" + get(a, "idName"));
        }
        for (CharacterClass c : CharacterClass.CHAR_CLASS_LIST) {
            StringBuilder b = new StringBuilder("class " + c.id + "|" + c.name + "|");
            for (int v : c.maxStats) b.append(v).append(' ');
            b.append('|');
            for (int v : c.baseGear) b.append(v).append(' ');
            b.append('|');
            for (int v : c.weaponGroup) b.append(v).append(' ');
            System.out.println(b.toString().replace(" |", "|").trim());
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
