package oracle;

import packets.Packet;
import packets.PacketType;
import packets.reader.BufferReader;
import packets.packetcapture.PacketProcessor;
import packets.packetcapture.encryption.RC4;
import packets.packetcapture.encryption.RotMGRC4Keys;
import packets.packetcapture.pconstructor.PacketConstructor;
import packets.packetcapture.sniff.LinkLayer;
import packets.packetcapture.sniff.assembly.Ip4Defragmenter;
import packets.packetcapture.sniff.assembly.TcpStreamBuilder;
import packets.packetcapture.sniff.netpackets.EthernetPacket;
import packets.packetcapture.sniff.netpackets.Ip4Packet;
import packets.packetcapture.sniff.netpackets.RawPacket;
import packets.packetcapture.sniff.netpackets.TcpPacket;

import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * The Java client's sniffer pipeline on a pcap file instead of a live capture: the same classes (link layer, IPv4,
 * TCP stream builder, packet constructor with its cipher alignment) from the captured frame on. Prints one line per
 * decrypted packet, as `lg-replay --oracle` does: direction, id, name, size, CRC-32 of the content.
 * <p>
 * With {@code --fields <ids>}, it instead decodes the packets with those ids as the app does ({@code PacketProcessor}:
 * dropped if decoding throws, kept if bytes are left over) and prints every field, as {@code lg-replay --fields} does
 * (format: {@code lg-protocol/src/dump.rs}).
 * <p>
 * Only for comparing the Rust port (rust/oracle/compare.sh); not part of the app.
 */
public final class ReplayOracle {
    private static PrintStream out;
    /** Packet ids to decode and print field by field; null to print one checksum line per packet. */
    private static Set<Integer> fieldIds;
    /** With --world: the app's world state, fed the decoded packets. */
    private static WorldOracle world;
    /** Fields Java works out from others, which the Rust port leaves out. */
    private static final Set<String> DERIVED = Set.of("StatData.statType", "MapInfoPacket.seed");

    /** Prints the packets instead of handing them to the app. */
    private static final class Printer extends PacketProcessor {
        private final String direction;

        Printer(String direction) {
            this.direction = direction;
        }

        @Override
        public void processPackets(int type, int size, ByteBuffer data) {
            byte[] a = data.array();
            CRC32 crc = new CRC32();
            crc.update(a, 5, a.length - 5);
            String name = PacketType.containsKey(type) ? PacketType.byOrdinal(type).name() : "UNKNOWN";
            if (world != null) {
                if (type == 195 || type == 196) {   // unknown to the packet table: the app reads them raw
                    world.raw(direction, type, size, data.array());
                    return;
                }
                if (!WorldOracle.fed(type)) return;
                Packet packet = PacketType.getPacket(type).factory();   // as PacketProcessor.processPackets
                packet.setData(data.array());
                try {
                    packet.deserialize(new BufferReader(data));
                } catch (Exception | OutOfMemoryError e) {
                    return;   // dropped
                }
                world.packet(direction, type, packet);
                return;
            }
            if (fieldIds == null) {
                out.printf("%s %d %s %d %08x%n", direction, type, name, size, crc.getValue());
                return;
            }
            if (!fieldIds.contains(type) || !PacketType.containsKey(type)) return;
            Packet packet = PacketType.getPacket(type).factory();   // as PacketProcessor.processPackets
            BufferReader reader = new BufferReader(data);
            String result;
            try {
                packet.deserialize(reader);
                result = (reader.isBufferFullyParsed() ? "ok " : "partial ") + dump(packet);
            } catch (Exception | OutOfMemoryError e) {
                // OutOfMemoryError: a garbled count (millions of tiles) makes Java allocate a huge array. The app's
                // PacketProcessor doesn't catch it and loses its sniffer thread; Rust drops the packet. Both "failed".
                result = "failed";
            }
            out.printf("%s %d %s %s%n", direction, type, name, result);
        }
    }

    static String dump(Object v) {
        StringBuilder b = new StringBuilder();
        dump(v, b);
        return b.toString();
    }

    private static void dump(Object v, StringBuilder o) {
        if (v == null) {
            o.append("null");
        } else if (v instanceof Float f) {
            o.append(String.format("f%08x", Float.floatToRawIntBits(f)));
        } else if (v instanceof Boolean || v instanceof Number) {
            o.append(v);
        } else if (v instanceof String s) {
            o.append('"');
            s.codePoints().forEach(c -> {
                if (c == '\\' || c == '"') o.append('\\').appendCodePoint(c);
                else if (c >= ' ' && c <= '~') o.appendCodePoint(c);
                else o.append("\\u{").append(Integer.toHexString(c)).append('}');
            });
            o.append('"');
        } else if (v instanceof Enum<?> e) {
            o.append(e.name());
        } else if (v instanceof byte[] bytes) {
            o.append('x');
            for (byte x : bytes) o.append(String.format("%02x", x & 0xff));
        } else if (v.getClass().isArray()) {
            o.append('[');
            for (int i = 0; i < Array.getLength(v); i++) {
                if (i > 0) o.append(',');
                dump(Array.get(v, i), o);
            }
            o.append(']');
        } else {
            record(v, o);
        }
    }

    /** {name=value,...}: names lower case without '_', sorted; values of secret fields only as a CRC-32. */
    private static void record(Object v, StringBuilder o) {
        List<String[]> fields = new ArrayList<>();
        for (Field f : v.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            if (DERIVED.contains(v.getClass().getSimpleName() + "." + f.getName())) continue;
            f.setAccessible(true);
            String name = f.getName().replace("_", "").toLowerCase();
            String value;
            try {
                value = dump(f.get(v));
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            if (name.contains("token") || name.equals("key")) {
                CRC32 crc = new CRC32();
                crc.update(value.getBytes(StandardCharsets.UTF_8));
                value = String.format("#%08x", crc.getValue());
            }
            fields.add(new String[]{name, value});
        }
        fields.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        o.append('{');
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) o.append(',');
            o.append(fields.get(i)[0]).append('=').append(fields.get(i)[1]);
        }
        o.append('}');
    }

    public static void main(String[] args) throws Exception {
        boolean worldMode = args.length == 2 && args[1].equals("--world");
        if (worldMode) {
            // set up below, once System.out points to the log
        } else if (args.length == 3 && args[1].equals("--fields")) {
            fieldIds = new HashSet<>();
            for (String id : args[2].split(",")) fieldIds.add(Integer.parseInt(id.trim()));
        } else if (args.length != 1) {
            System.err.println("usage: ReplayOracle <session.pcap> [--fields <id,id,...> | --world]");
            System.exit(2);
        }
        out = System.out;
        System.setOut(System.err);   // the sniffer's own messages ("Synced. offset...") go beside the packet lines
        if (worldMode) world = new WorldOracle(out);

        ByteBuffer f = ByteBuffer.wrap(Files.readAllBytes(Path.of(args[0])));
        int magic = f.order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        boolean nanos;
        if (magic == 0xA1B2C3D4 || magic == 0xA1B23C4D) {
            nanos = magic == 0xA1B23C4D;
        } else {
            f.order(ByteOrder.BIG_ENDIAN);
            int m = f.getInt(0);
            if (m != 0xA1B2C3D4 && m != 0xA1B23C4D) throw new IllegalArgumentException("not a pcap file");
            nanos = m == 0xA1B23C4D;
        }
        int linkType = f.getInt(20);

        PacketConstructor incoming = new PacketConstructor(new Printer("in"), new RC4(RotMGRC4Keys.INCOMING_STRING));
        PacketConstructor outgoing = new PacketConstructor(new Printer("out"), new RC4(RotMGRC4Keys.OUTGOING_STRING));
        incoming.startResets();   // as the live sniffer starts: wait for a short segment first
        outgoing.startResets();
        TcpStreamBuilder in = new TcpStreamBuilder(incoming::reset, (data, src) -> incoming.build(data));
        TcpStreamBuilder outStream = new TcpStreamBuilder(outgoing::reset, (data, src) -> outgoing.build(data));

        int at = 24, frames = 0;
        while (at + 16 <= f.limit()) {
            long secs = Integer.toUnsignedLong(f.getInt(at));
            long frac = Integer.toUnsignedLong(f.getInt(at + 4));
            int len = f.getInt(at + 8);
            byte[] frame = new byte[len];
            f.get(at + 16, frame);
            at += 16 + len;
            frames++;
            Instant when = Instant.ofEpochSecond(secs, nanos ? frac : frac * 1000);
            ClockAgent.now = when.toEpochMilli();   // the app's clock, where the agent is loaded (--world)
            try {   // as Sniffer.processBufferedPackets and receivedPackets
                RawPacket raw = LinkLayer.toEthernet(RawPacket.newPacket(frame, when), linkType);
                EthernetPacket ethernet = raw.getNewEthernetPacket();
                if (ethernet == null) continue;
                Ip4Packet ip = Ip4Defragmenter.defragment(ethernet.getNewIp4Packet());
                if (ip == null) continue;
                TcpPacket tcp = ip.getNewTcpPacket();
                if (tcp == null) continue;
                if (tcp.getSrcPort() == 2050) in.streamBuilder(tcp);
                else if (tcp.getDstPort() == 2050) outStream.streamBuilder(tcp);
            } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException | NullPointerException e) {
                System.err.println("frame " + frames + ": " + e);
            }
        }
        if (world != null) world.end();
        out.flush();
        System.err.println(frames + " frames");
    }
}
