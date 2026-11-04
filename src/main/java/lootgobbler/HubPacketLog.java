package lootgobbler;

import packets.Packet;
import packets.incoming.MapInfoPacket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Research log for the graveyard: in the Nexus and the Vault, where the game shows your dead characters, every packet
 * that is not part of the constant traffic (ticks, movement, shots, acknowledgements, chat) is written to
 * logs/HubPackets-<date>.log: the decoded ones with their contents, and the ones RealmShark does not know or cannot
 * decode with their raw bytes and the readable text in them (names, "killed by" and the like would show there). So
 * opening the graveyard once shows whether its data comes over the game connection, and in what shape.
 */
public final class HubPacketLog {
    // the constant traffic, not worth a line each
    private static final Set<String> NOISE = Set.of("NewTickPacket", "UpdatePacket", "PingPacket", "PongPacket", "MovePacket",
            "GotoPacket", "GotoAckPacket", "UpdateAckPacket", "ShootAckPacket", "ShowEffectPacket", "EnemyShootPacket",
            "ServerPlayerShootPacket", "AllyShootPacket", "PlayerShootPacket", "DamagePacket", "AoePacket", "AoeAckPacket",
            "TextPacket", "PlayerTextPacket", "OtherHitPacket", "EnemyHitPacket", "PlayerHitPacket", "SquareHitPacket",
            // never written: they carry your login (access, platform, user and client tokens)
            "HelloPacket", "ReconnectPacket");
    // any token or key field in other packets is blanked before writing
    private static final java.util.regex.Pattern SECRET = java.util.regex.Pattern.compile(
            "(?i)(\\w*(?:token|key|password|secret)\\w*)=\\S+");
    private static final int PER_TYPE_PER_MINUTE = 5;   // the same kind of packet at most this often, so a burst stays readable
    private static final int MAX_BYTES = 4096;          // raw bytes written per unknown packet
    private static boolean inHub;
    private static final Map<String, int[]> perMinute = new HashMap<>();   // packet kind -> {minute, count}

    private HubPacketLog() {
    }

    /** Every decoded packet (incoming and outgoing). */
    static synchronized void seen(Packet p) {
        if (p instanceof MapInfoPacket m) {
            boolean hub = "{s.nexus}".equals(m.displayName) || "{s.vault}".equals(m.displayName)
                    || (m.name != null && m.name.toLowerCase(java.util.Locale.ROOT).contains("vault"));
            if (hub && !inHub) write("=== ENTERED " + m.displayName + " (" + m.name + ") ===");
            if (!hub && inHub) write("=== LEFT for " + m.displayName + " ===");
            inHub = hub;
            return;
        }
        if (!inHub) return;
        String kind = p.getClass().getSimpleName();
        if (NOISE.contains(kind) || !allowed(kind)) return;
        String text;
        try {
            text = p.toString();
        } catch (Exception e) {
            text = "(could not show: " + e + ")";
        }
        text = SECRET.matcher(text).replaceAll("$1=(removed)");
        if (text.length() > 2000) text = text.substring(0, 2000) + "... (" + text.length() + " chars)";
        write("PACKET " + kind + "  " + text.replace('\n', ' '));
    }

    /** A packet with an unknown id or one that failed to decode: its raw bytes and the readable text in them. */
    static synchronized void raw(int type, int size, byte[] data, String what) {
        if (!inHub || data == null) return;
        if (!allowed("raw" + type)) return;
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(data.length, MAX_BYTES); i++) hex.append(String.format("%02x", data[i] & 0xFF));
        write(String.format("UNKNOWN id %d  %d bytes  %s%n    text: %s%n    hex: %s%s", type, size, what, strings(data),
                hex, data.length > MAX_BYTES ? "..." : ""));
    }

    /** The runs of readable characters in raw bytes (at least 3 long), joined by " | ". */
    private static String strings(byte[] data) {
        StringBuilder out = new StringBuilder(), run = new StringBuilder();
        for (byte b : data) {
            int c = b & 0xFF;
            if (c >= 32 && c < 127) {
                run.append((char) c);
            } else {
                if (run.length() >= 3) out.append(out.length() > 0 ? " | " : "").append(run);
                run.setLength(0);
            }
        }
        if (run.length() >= 3) out.append(out.length() > 0 ? " | " : "").append(run);
        return out.length() == 0 ? "(none)" : out.toString();
    }

    private static boolean allowed(String kind) {
        int minute = (int) (System.currentTimeMillis() / 60_000);
        int[] c = perMinute.computeIfAbsent(kind, k -> new int[]{minute, 0});
        if (c[0] != minute) {
            c[0] = minute;
            c[1] = 0;
        }
        return ++c[1] <= PER_TYPE_PER_MINUTE;
    }

    private static void write(String line) {
        try {
            Path dir = Path.of("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("HubPackets-" + LocalDate.now() + ".log");
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"));
            Files.writeString(file, time + " " + line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignore) {
        }
    }
}
