package packets.packetcapture.sniff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RingBufferTest {
    @Test
    void neverHandsOutAnItemTwiceAcrossManyWraps() {
        RingBuffer<Integer> b = new RingBuffer<>(4);
        int next = 0, expect = 0;
        for (int round = 0; round < 1000; round++) {
            for (int i = 0; i < round % 7; i++) b.push(next++);
            for (int i = 0; i < round % 5 && !b.isEmpty(); i++) assertEquals(expect++, b.pop());
        }
        while (!b.isEmpty()) assertEquals(expect++, b.pop());
        assertEquals(next, expect);
        assertNull(b.pop());
    }
}
