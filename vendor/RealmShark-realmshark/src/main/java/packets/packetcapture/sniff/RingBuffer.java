/**
 * Class made by PrgmTrouble. Big thanks to him for implementing it.
 */

package packets.packetcapture.sniff;

import java.util.ArrayDeque;

/**
 * The packets the capture thread has handed over and the processing thread has not taken yet, oldest first.
 * <p>
 * [Loot Gobbler patch] A queue instead of the hand-made ring: when its read index wrapped from the last slot to 0,
 * the ring did not notice it was empty and handed out packets of the previous round again. Those old duplicates
 * piled up in the TCP stream builder until it reported "Packets missing" for every packet that followed.
 */
public class RingBuffer<T> {
    private final ArrayDeque<T> queue;

    public RingBuffer(int capacity) {
        queue = new ArrayDeque<>(capacity);
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized int size() {
        return queue.size();
    }

    /** Adds the item at the end; grows as needed, nothing is dropped. */
    public synchronized void push(T item) {
        if (item != null) queue.addLast(item);
    }

    /** The oldest item, or null when empty. */
    public synchronized T pop() {
        return queue.pollFirst();
    }
}
