package packets.packetcapture;

import org.junit.jupiter.api.Test;
import packets.Packet;
import packets.PacketType;
import packets.packetcapture.register.Register;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** What the packet processor does with packets it cannot read. */
class PacketProcessorTest {

    /** The game's compressed int, as BufferReader.readCompressedInt reads it. */
    private static void compressed(ByteArrayOutputStream out, int v) {
        int n = Math.abs(v);
        int first = (n & 63) | (v < 0 ? 64 : 0);
        n >>>= 6;
        if (n > 0) first |= 128;
        out.write(first);
        while (n > 0) {
            int b = n & 127;
            n >>>= 7;
            if (n > 0) b |= 128;
            out.write(b);
        }
    }

    /** A packet as the constructor hands it over: the 5-byte header (size, id), then the content, read from byte 5. */
    private static ByteBuffer packet(int type, byte[] body) {
        ByteBuffer b = ByteBuffer.allocate(5 + body.length);
        b.putInt(5 + body.length).put((byte) type).put(body);
        b.position(5);
        return b;
    }

    @Test
    void aGarbledCountDropsThePacketInsteadOfEndingTheSniffer() {
        // an UPDATE claiming about two billion tiles: Java cannot even allocate the array
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[9]);   // position, level type
        compressed(body, Integer.MAX_VALUE);
        List<Packet> seen = new ArrayList<>();
        Register.INSTANCE.register(PacketType.UPDATE, seen::add);
        PacketProcessor processor = new PacketProcessor();
        int update = PacketType.UPDATE.getIndex();
        byte[] b = body.toByteArray();
        assertDoesNotThrow(() -> processor.processPackets(update, 5 + b.length, packet(update, b)));
        assertTrue(seen.isEmpty(), "dropped, as an unreadable packet");

        // and the next, readable one still arrives
        ByteArrayOutputStream ok = new ByteArrayOutputStream();
        ok.writeBytes(new byte[9]);
        compressed(ok, 0);
        compressed(ok, 0);
        compressed(ok, 0);
        byte[] o = ok.toByteArray();
        processor.processPackets(update, 5 + o.length, packet(update, o));
        assertEquals(1, seen.size());
    }
}
