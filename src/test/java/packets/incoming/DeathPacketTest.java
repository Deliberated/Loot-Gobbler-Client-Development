package packets.incoming;

import org.junit.jupiter.api.Test;
import packets.reader.BufferReader;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

class DeathPacketTest {
    /** A real death packet (Nov 2026): a level 1 Rogue killed by a Tundra Yeti, with its 5-byte header. */
    private static final byte[] ROGUE = {0, 0, 0, 108, 46, 0, 16, 49, 50, 51, 52, 53, 54, 55, 56, 57, 48, 49, 50, 51, 52, 53, 54, -68, 7, 0, 11, 84, 117, 110, 100, 114, 97, 32, 89, 101, 116, 105, 0, 0, 7, 35, 0, -92, 1, -128, -105, -41, 24, 0, 0, 56, 68, 118, 67, 116, 117, 103, 65, 81, 65, 66, 115, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 65, 76, 119, 71, 98, 84, 103, 69, 67};

    @Test
    void theCurrentLayoutIsReadToTheEnd() throws Exception {
        BufferReader r = new BufferReader(ByteBuffer.wrap(ROGUE).position(5));
        DeathPacket p = new DeathPacket();
        p.deserialize(r);
        assertEquals("1234567890123456", p.accountId);
        assertEquals(508, p.charId);
        assertEquals("Tundra Yeti", p.killedBy);
        assertEquals(0, p.totalFame);
        assertEquals(0, p.fameData.length);
        assertTrue(p.stats.startsWith("DvCtug"));
        assertTrue(r.isBufferFullyParsed());
    }

    @Test
    void aCutOffPacketStillTellsWhoDied() throws Exception {
        byte[] cut = java.util.Arrays.copyOf(ROGUE, 48);
        DeathPacket p = new DeathPacket();
        p.deserialize(new BufferReader(ByteBuffer.wrap(cut).position(5)));
        assertEquals(508, p.charId);
        assertEquals("Tundra Yeti", p.killedBy);
    }
}
