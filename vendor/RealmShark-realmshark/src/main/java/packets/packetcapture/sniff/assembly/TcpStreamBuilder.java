package packets.packetcapture.sniff.assembly;

import packets.packetcapture.sniff.netpackets.TcpPacket;

import java.util.Arrays;
import java.util.HashMap;

/**
 * Stream constructor ordering TCP packets in sequence. Payload is extracted and sent back in its raw form.
 */
public class TcpStreamBuilder {

    HashMap<Long, TcpPacket> packetMap = new HashMap<>();
    long sequenseNumber;
    int idNumber;
    private PStream packetStream;
    private PReset packetReset;

    /**
     * Constructor of StreamConstructor which needs a reset class to reset if reset
     * packet is retrieved and a constructor class to send ordered packets to.
     *
     * @param preset  Reset class if a reset packet is retrieved.
     * @param pstream Constructor class to send ordered packets to.
     */
    public TcpStreamBuilder(PReset preset, PStream pstream) {
        packetReset = preset;
        packetStream = pstream;
    }

    /**
     * Build method for ordering packets according to index used by TCP.
     *
     * @param packet TCP packets needing to be ordered.
     */
    public void streamBuilder(TcpPacket packet) {
        if (packet.isResetBit()) {
            if (packet.isSyn()) {
                reset();
            }
            return;
        }
        if (sequenseNumber == 0) {
            sequenseNumber = packet.getSequenceNumber();
            idNumber = packet.getIp4Packet().getIdentification();
        }

        // [Loot Gobbler patch] nothing to add from an empty segment (a plain ACK), and data already passed on (a
        // retransmission, a duplicate) must not wait in the map forever: such leftovers made every later packet
        // look like a gap. A segment reaching over the expected position gives only its new part.
        if (packet.getPayloadSize() <= 0) return;
        long behind = after(sequenseNumber, packet.getSequenceNumber());
        if (behind > 0) {
            if (behind >= packet.getPayloadSize()) return;
            byte[] payload = packet.getPayload();
            if (payload == null) return;
            try {
                byte[] fresh = Arrays.copyOfRange(payload, (int) behind, payload.length);
                sequenseNumber = (sequenseNumber + fresh.length) & 0xFFFFFFFFL;
                idNumber = packet.getIp4Packet().getIdentification();
                packetStream.stream(fresh, packet.getIp4Packet().getSrcAddr());
            } catch (Exception e) {
                TcpStreamErrorHandler.INSTANCE.dumpData("Logging errors caused (by TCP stream builder). \n " + Arrays.toString(e.getStackTrace()));
            }
        } else {
            packetMap.put(packet.getSequenceNumber(), packet);
        }

        TcpStreamErrorHandler.INSTANCE.errorChecker(this);

        try {
            while (packetMap.containsKey(sequenseNumber)) {
                TcpPacket packetSeqed = packetMap.remove(sequenseNumber);
                idNumber = packetSeqed.getIp4Packet().getIdentification();
                if (packetSeqed.getPayload() != null) {
                    sequenseNumber = (sequenseNumber + packetSeqed.getPayloadSize()) & 0xFFFFFFFFL;
                    packetStream.stream(packetSeqed.getPayload(), packetSeqed.getIp4Packet().getSrcAddr());
                }
            }
        } catch (Exception e) {
            TcpStreamErrorHandler.INSTANCE.dumpData("Logging errors caused (by TCP stream builder). \n " + Arrays.toString(e.getStackTrace()));
        }
    }

    /**
     * How far {@code seq} lies before {@code expected} in the 32-bit sequence space (with wrap-around); 0 or less
     * when it is at or after it.
     */
    static long after(long expected, long seq) {
        return (int) (expected - seq);
    }

    /**
     * Reset method if a reset packet is retrieved.
     */
    public void reset() {
        packetReset.reset();
        packetMap.clear();
        sequenseNumber = 0;
        idNumber = 0;
    }
}
