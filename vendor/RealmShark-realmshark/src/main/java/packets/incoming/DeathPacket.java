package packets.incoming;

import packets.Packet;
import packets.data.FameData;
import packets.reader.BufferReader;

import java.util.Arrays;

/**
 * Received when a player dies
 */
public class DeathPacket extends Packet {
    /**
     * The account id of the player who died
     */
    public String accountId;
    /**
     * The character id of the player who died
     */
    public int charId;
    /**
     * The cause of death
     */
    public String killedBy;
    /**
     * Gravestone type
     */
    public int gravestoneType;
    /**
     * Total death fame of the player
     */
    public int totalFame;
    /**
     * Death fame data
     */
    public FameData[] fameData;
    /**
     * Stats of the dead player
     */
    public String stats;
    /** Two numbers the game added after totalFame (Nov 2026); meaning unknown (seen: 100 and 25880000). */
    public int unknown1, unknown2;

    @Override
    public void deserialize(BufferReader buffer) throws Exception {
        accountId = buffer.readString();
        charId = buffer.readCompressedInt();
        killedBy = buffer.readString();
        gravestoneType = buffer.readInt();
        totalFame = buffer.readCompressedInt();
        // Whose death it was is read above. If the rest changes again, the packet must still arrive: a failed packet
        // is dropped whole, and then the death goes unnoticed (graveyard, DPS logger, dungeon stats).
        try {
            unknown1 = buffer.readCompressedInt();
            unknown2 = buffer.readCompressedInt();
            FameData[] fame = new FameData[buffer.readCompressedInt()];
            for (int i = 0; i < fame.length; i++) {
                fame[i] = new FameData().deserialize(buffer);
            }
            fameData = fame;
            stats = buffer.readString();
        } catch (Exception e) {
            System.out.println("DeathPacket: only partly read (" + e + "), character " + charId + " still counts");
        }
    }

    @Override
    public String toString() {
        return "DeathPacket{" +
                "\n   accountId=" + accountId +
                "\n   charId=" + charId +
                "\n   killedBy=" + killedBy +
                "\n   gravestoneType=" + gravestoneType +
                "\n   totalFame=" + totalFame +
                "\n   unknown1=" + unknown1 +
                "\n   unknown2=" + unknown2 +
                "\n   fameData=" + Arrays.toString(fameData) +
                "\n   stats=" + stats;
    }
}