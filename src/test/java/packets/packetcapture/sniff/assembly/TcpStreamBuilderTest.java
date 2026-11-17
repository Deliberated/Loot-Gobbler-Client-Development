package packets.packetcapture.sniff.assembly;

import org.junit.jupiter.api.Test;
import packets.packetcapture.sniff.netpackets.Ip4Packet;
import packets.packetcapture.sniff.netpackets.TcpPacket;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The TCP stream comes out in order and once, whatever the capture hands over twice or late. */
class TcpStreamBuilderTest {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final TcpStreamBuilder builder = new TcpStreamBuilder(() -> {
    }, (data, src) -> out.write(data, 0, data.length));
    private int id = 100;

    /** An IPv4/TCP segment from port 2050 carrying bytes [from, to) of a stream that starts at sequence seq0. */
    private TcpPacket seg(long seq0, int from, int to) {
        int len = to - from;
        byte[] b = new byte[40 + len];
        b[0] = 0x45;
        b[2] = (byte) ((40 + len) >> 8);
        b[3] = (byte) (40 + len);
        b[4] = (byte) (id >> 8);
        b[5] = (byte) id++;
        b[8] = 64;
        b[9] = 6;
        b[12] = 10;
        b[16] = 10;
        b[20] = (byte) (2050 >> 8);
        b[21] = (byte) 2050;
        b[23] = 1;
        long seq = (seq0 + from) & 0xFFFFFFFFL;
        b[24] = (byte) (seq >> 24);
        b[25] = (byte) (seq >> 16);
        b[26] = (byte) (seq >> 8);
        b[27] = (byte) seq;
        b[32] = 0x50;
        b[33] = 0x10;
        for (int i = 0; i < len; i++) b[40 + i] = (byte) (from + i);
        return new Ip4Packet(b, null).getNewTcpPacket();
    }

    private static byte[] expected(int n) {
        byte[] e = new byte[n];
        for (int i = 0; i < n; i++) e[i] = (byte) i;
        return e;
    }

    @Test
    void duplicatesLateCopiesAndOverlapsComeOutOnce() {
        long s = 0xFFFFFF00L;   // wraps around 2^32 on the way
        List<TcpPacket> in = new ArrayList<>(List.of(seg(s, 0, 100), seg(s, 100, 200), seg(s, 0, 100),   // duplicate
                seg(s, 300, 400), seg(s, 200, 300),          // out of order
                seg(s, 150, 250),                             // retransmitted, all known already
                seg(s, 350, 500),                             // overlaps the end: only 400..500 is new
                seg(s, 500, 600)));
        for (TcpPacket p : in) builder.streamBuilder(p);
        assertArrayEquals(expected(600), out.toByteArray());
        assertTrue(builder.packetMap.isEmpty(), "nothing left waiting");
    }

    @Test
    void manyDuplicatesNoLongerLookLikeAGap() {
        long s = 5000;
        for (int i = 0; i < 300; i++) {
            builder.streamBuilder(seg(s, i * 10, i * 10 + 10));
            if (i > 0) builder.streamBuilder(seg(s, (i - 1) * 10, i * 10));   // each one again, late
        }
        assertArrayEquals(expected(3000), out.toByteArray());
        assertTrue(builder.packetMap.isEmpty());
    }

    @Test
    void aSegmentTheCaptureMissedIsSkippedOnceEnoughWait() {
        long s = 1;
        builder.streamBuilder(seg(s, 0, 10));
        for (int i = 2; i < 100; i++) builder.streamBuilder(seg(s, i * 10, i * 10 + 10));   // 10..20 never comes
        assertEquals(990, out.size(), "0..10 and 20..1000: all but the ten bytes never captured");
        assertTrue(builder.packetMap.size() < 5);
    }
}
