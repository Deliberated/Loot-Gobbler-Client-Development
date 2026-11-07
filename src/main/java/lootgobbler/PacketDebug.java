package lootgobbler;

import packets.packetcapture.register.Register;

/**
 * Packets the sniffer could not decode (unknown id or a layout that changed) are handed on raw: to ItemUse for the
 * forge and pet requests it reads itself, and to the hub packet log for research.
 */
public final class PacketDebug {
    public static void install() {
        Register.INSTANCE.registerAll(HubPacketLog::seen);   // what arrives in the Nexus and the vault
    }

    /**
     * Called by the packet processor for packets with an unknown id or that failed to decode.
     */
    public static void problem(int type, int size, byte[] data, String what) {
        if (type == ItemUse.USE_REQUEST || type == ItemUse.USE_RESULT) ItemUse.raw(type, data);   // unknown to RealmShark
        HubPacketLog.raw(type, size, data, what);
    }

    private PacketDebug() {
    }
}
