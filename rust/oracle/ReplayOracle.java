package oracle;

import packets.PacketType;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.CRC32;

/**
 * The Java client's sniffer pipeline on a pcap file instead of a live capture: the same classes (link layer, IPv4,
 * TCP stream builder, packet constructor with its cipher alignment) from the captured frame on. Prints one line per
 * decrypted packet, as `lg-replay --oracle` does: direction, id, name, size, CRC-32 of the content.
 * <p>
 * Only for comparing the Rust port (rust/oracle/compare.sh); not part of the app.
 */
public final class ReplayOracle {
    private static PrintStream out;

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
            out.printf("%s %d %s %d %08x%n", direction, type, name, size, crc.getValue());
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: ReplayOracle <session.pcap>");
            System.exit(2);
        }
        out = System.out;
        System.setOut(System.err);   // the sniffer's own messages ("Synced. offset...") go beside the packet lines

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
        out.flush();
        System.err.println(frames + " frames");
    }
}
