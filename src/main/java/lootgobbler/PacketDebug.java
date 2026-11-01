package lootgobbler;

import packets.Packet;
import packets.incoming.MapInfoPacket;
import packets.packetcapture.register.Register;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostics for the vault: for 20 seconds after entering a vault map, records which packet types arrive and
 * which packets could not be decoded, then writes vault-debug.txt in the working directory.
 */
public final class PacketDebug {
    private static final Path FILE = Path.of("vault-debug.txt");
    private static final long WINDOW_MS = 20_000;

    private static boolean recording;
    private static long until;
    private static String mapName = "";
    private static final Map<String, Integer> counts = new TreeMap<>();
    private static final List<String> problems = new ArrayList<>();

    public static void install() {
        Register.INSTANCE.registerAll(PacketDebug::seen);
    }

    private static synchronized void seen(Packet p) {
        long now = System.currentTimeMillis();
        if (p instanceof MapInfoPacket m) {
            if (recording) flush();
            if (m.name != null && m.name.toLowerCase().contains("vault")) {
                recording = true;
                until = now + WINDOW_MS;
                mapName = m.name;
                counts.clear();
                problems.clear();
            }
            return;
        }
        if (!recording) return;
        counts.merge(p.getClass().getSimpleName(), 1, Integer::sum);
        if (now > until) flush();
    }

    /**
     * Called by the packet processor for packets with an unknown id or that failed to decode.
     */
    public static synchronized void problem(int type, int size, byte[] data, String what) {
        if (what.startsWith("VAULT_UPDATE")) {   // keep the whole packet so the new layout can be decoded
            try {
                Files.writeString(Path.of("vault-packet.txt"), what + "\n" + Arrays.toString(data) + "\n");
            } catch (Exception e) {
                System.err.println("Could not write vault-packet.txt: " + e.getMessage());
            }
        }
        if (!recording) return;
        byte[] head = Arrays.copyOf(data, Math.min(data.length, 32));
        problems.add(String.format("  id %3d  %6d bytes  %-16s first bytes %s", type, size, what, Arrays.toString(head)));
    }

    private static void flush() {
        recording = false;
        StringBuilder sb = new StringBuilder();
        sb.append("Vault diagnostics ").append(LocalDateTime.now().withNano(0)).append("  map: ").append(mapName).append('\n');
        sb.append("Packets received in the first ").append(WINDOW_MS / 1000).append("s:\n");
        counts.forEach((k, v) -> sb.append(String.format("  %-32s %d%n", k, v)));
        sb.append(counts.containsKey("VaultContentPacket") ? "-> VaultContentPacket WAS received.\n"
                : "-> No VaultContentPacket was decoded.\n");
        sb.append("Unknown or undecodable packets:\n");
        if (problems.isEmpty()) sb.append("  none\n");
        problems.forEach(s -> sb.append(s).append('\n'));
        try {
            Files.writeString(FILE, sb.toString());
            System.out.println("Wrote " + FILE.toAbsolutePath());
        } catch (Exception e) {
            System.err.println("Could not write " + FILE + ": " + e.getMessage());
        }
    }

    private PacketDebug() {
    }
}
