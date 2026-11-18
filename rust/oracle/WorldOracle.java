package oracle;

import packets.Packet;
import packets.PacketType;
import packets.data.StatData;
import packets.data.WorldPosData;
import packets.incoming.MapInfoPacket;
import tomato.backend.TomatoPacketCapture;
import tomato.backend.data.Entity;
import tomato.backend.data.TomatoData;

import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32;

/**
 * The Java client's world state (TomatoData, fed by TomatoPacketCapture as the app feeds it) on a recording, for
 * comparing the Rust port: after each packet the app hands to TomatoPacketCapture, a line with the CRC-32 of the
 * whole state; the full state after each map change and at the end. Format: lg-world's `dump`.
 * <p>
 * Run it in an empty folder holding only a copy of the game's assets: the app's parts write their files into the
 * working folder. HELLO packets are never handed on (they make the app log in to the game's servers with your token),
 * and compare.sh points Java's network at a closed local port as well.
 */
final class WorldOracle {
    /** The packets the app registers TomatoPacketCapture for (Tomato.packetRegister), without HELLO. */
    private static final Set<PacketType> FED = Set.of(PacketType.CREATE_SUCCESS, PacketType.ENEMYHIT,
            PacketType.PLAYERSHOOT, PacketType.DAMAGE, PacketType.PLAYERHIT, PacketType.ENEMYSHOOT,
            PacketType.GROUNDDAMAGE, PacketType.AOE, PacketType.MOVE, PacketType.USEITEM, PacketType.DEATH,
            PacketType.SERVERPLAYERSHOOT, PacketType.UPDATE, PacketType.NEWTICK, PacketType.MAPINFO, PacketType.STASIS,
            PacketType.TEXT, PacketType.NOTIFICATION, PacketType.EXALTATION_BONUS_CHANGED, PacketType.VAULT_UPDATE,
            PacketType.INVSWAP, PacketType.INVRESULT, PacketType.QUEST_FETCH_RESPONSE, PacketType.TRADEDONE,
            PacketType.TRADEREQUESTED, PacketType.CRUCIBLE_RESPONSE);

    private final TomatoData data;
    private final TomatoPacketCapture capture;
    private final PrintStream out;

    WorldOracle(PrintStream out) {
        this.out = out;
        data = new TomatoData();
        capture = new TomatoPacketCapture(data);
        // the panels the packet handlers update, as TomatoGUI builds them (panels work without a screen; the window not)
        new tomato.gui.security.ParsePanelGUI();
        new tomato.gui.dps.DpsGUI(data);
        new tomato.gui.chat.ChatGUI(data);
        new tomato.gui.myinfo.MyInfoGUI(data);
        try {
            Field quests = tomato.gui.TomatoGUI.class.getDeclaredField("questPanel");
            quests.setAccessible(true);
            quests.set(null, new tomato.gui.quest.QuestGUI());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        tomato.gui.security.ParsePanelGUI.editFont(new java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12));   // as TomatoGUI
        data.bootload();
        waitForGameFileEnemies();
    }

    /** The app reads the dungeons' enemies on a thread of its own; wait for it, so the result does not hang on timing. */
    private static void waitForGameFileEnemies() {
        try {
            Class<?> c = Class.forName("tomato.backend.data.GameFileEnemies");
            java.lang.reflect.Method get = c.getDeclaredMethod("get");
            get.setAccessible(true);
            for (int i = 0; i < 600 && get.invoke(null) == null; i++) Thread.sleep(100);
        } catch (ReflectiveOperationException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean fed(int type) {
        return PacketType.containsKey(type) && FED.contains(PacketType.byOrdinal(type));
    }

    /** One decoded packet, as the app's Register hands it on: an exception ends its handling there. */
    void packet(String direction, int type, Packet packet) {
        String name = PacketType.byOrdinal(type).name();
        String problem = "";
        if (packet instanceof MapInfoPacket) full("before " + name, state());   // how the map left ended
        try {
            capture.packetCapture(packet);
        } catch (Throwable e) {
            if (System.getenv("WORLD_ORACLE_TRACE") != null) e.printStackTrace();
            StackTraceElement where = e.getStackTrace().length > 0 ? e.getStackTrace()[0] : null;
            System.err.println("world: " + name + ": " + e + (where == null ? "" : " at " + where));
            problem = " exception";   // the world stays as far as it got, as in the app
        }
        String state = state();
        CRC32 crc = new CRC32();
        crc.update(state.getBytes(StandardCharsets.UTF_8));
        out.printf("%s %d %s %08x%s%n", direction, type, name, crc.getValue(), problem);
        if (packet instanceof MapInfoPacket) full("after " + name, state);
    }

    void end() {
        full("end", state());
    }

    private void full(String when, String state) {
        out.println("state " + when);
        for (String line : state.split("\n")) out.println("  " + line);
    }

    /** The whole world state as text, one line per part and per entity, sorted. */
    String state() {
        try {
            StringBuilder b = new StringBuilder();
            MapInfoPacket map = (MapInfoPacket) get(data, "map");
            b.append("map ").append(map == null ? "null" : quote(map.name)).append(" guessed=").append(get(data, "mapGuessed"))
                    .append(" me=").append(get(data, "worldPlayerId")).append(" char=").append(get(data, "charId"))
                    .append(" time=").append(data.time).append(" player=").append(data.player == null ? "null" : data.player.id)
                    .append('\n');
            b.append("players ").append(ids(((Map<?, ?>) get(data, "playerList")).keySet())).append('\n');
            b.append("updated ").append(ids(data.playerListUpdated.keySet())).append('\n');
            b.append("dropped ").append(ids(((Map<?, ?>) get(data, "dropList")).keySet())).append('\n');
            b.append("crystals ").append(ids((Collection<?>) get(data, "crystalTracker"))).append('\n');
            b.append("bags ").append(ids((Collection<?>) get(data, "lootBags"))).append('\n');
            for (Entity e : new TreeMap<>(data.entityList).values()) b.append(entity(e)).append('\n');
            return b.toString();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String entity(Entity e) throws ReflectiveOperationException {
        StringBuilder b = new StringBuilder("entity ").append(e.id).append(" type=").append(e.objectType)
                .append(" pos=").append(pos(e.pos)).append(" user=").append(get(e, "isUser"))
                .append(" player=").append(get(e, "isPlayer")).append(" char=").append(get(e, "charId"))
                .append(" base=").append(e.baseStats == null ? "null" : java.util.Arrays.toString(e.baseStats).replace(" ", ""))
                .append(" stats=");
        StatData[] stats = (StatData[]) get(e.stat, "stats");
        boolean first = true;
        for (StatData s : stats) {
            if (s == null) continue;
            b.append(first ? "" : ",").append(s.statTypeNum).append(':');
            b.append(s.stringStatValue != null ? quote(s.stringStatValue) : String.valueOf(s.statValue));
            b.append('/').append(s.statValueTwo);
            first = false;
        }
        return b.toString();
    }

    private static String pos(WorldPosData p) {
        return p == null ? "null" : String.format("%08x,%08x", Float.floatToRawIntBits(p.x), Float.floatToRawIntBits(p.y));
    }

    private static String ids(Collection<?> c) {
        List<String> l = new ArrayList<>();
        for (Object o : new TreeSet<>(c)) l.add(String.valueOf(o));
        return "[" + String.join(",", l) + "]";
    }

    private static String quote(String s) {
        StringBuilder o = new StringBuilder("\"");
        s.codePoints().forEach(c -> {
            if (c == '\\' || c == '"') o.append('\\').appendCodePoint(c);
            else if (c >= ' ' && c <= '~') o.appendCodePoint(c);
            else o.append("\\u{").append(Integer.toHexString(c)).append('}');
        });
        return o.append('"').toString();
    }

    private static Object get(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
                // look in the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
