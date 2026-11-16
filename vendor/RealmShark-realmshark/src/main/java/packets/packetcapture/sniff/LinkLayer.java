package packets.packetcapture.sniff;

import packets.packetcapture.sniff.netpackets.RawPacket;

/**
 * Turns captured frames of other link-layer types into Ethernet frames, so the rest of the sniffer only has to parse
 * Ethernet. Windows (Npcap) hands over Ethernet frames on every usual adapter, but on Linux and macOS a capture can
 * also come from a VPN (WireGuard, Tailscale, OpenVPN tun: raw IP), the "any" device (Linux cooked capture) or the
 * loopback/utun devices of macOS (BSD null header).
 */
public final class LinkLayer {
    public static final int DLT_NULL = 0;            // BSD loopback: 4-byte address family in host byte order
    public static final int DLT_EN10MB = 1;          // Ethernet
    public static final int DLT_RAW = 12;            // raw IP (Linux, most systems)
    public static final int DLT_RAW_OPENBSD = 14;    // raw IP (OpenBSD numbering)
    public static final int LINKTYPE_RAW = 101;      // raw IP as some libpcap versions report it
    public static final int DLT_LOOP = 108;          // OpenBSD loopback: like DLT_NULL, network byte order
    public static final int DLT_LINUX_SLL = 113;     // Linux cooked capture v1 (16-byte header)
    public static final int DLT_IPV4 = 228;          // raw IPv4
    public static final int DLT_LINUX_SLL2 = 276;    // Linux cooked capture v2 (20-byte header)

    private static final int ETHERNET_HEADER = 14;
    private static final int ETHERTYPE_IPV4 = 0x0800;

    private LinkLayer() {
    }

    /**
     * @param packet   frame as captured
     * @param linkType the handle's pcap_datalink() value
     * @return the same packet for Ethernet (and unknown types), otherwise a copy with an Ethernet header (zero MAC
     * addresses) in front of the network-layer payload
     */
    public static RawPacket toEthernet(RawPacket packet, int linkType) {
        if (packet == null || linkType == DLT_EN10MB) return packet;
        byte[] data = packet.getPayload();
        switch (linkType) {
            case DLT_RAW:
            case DLT_RAW_OPENBSD:
            case LINKTYPE_RAW:
            case DLT_IPV4:
                return wrap(packet, data, 0, ETHERTYPE_IPV4);
            case DLT_LINUX_SLL:
                if (data.length < 16) return packet;
                return wrap(packet, data, 16, short16(data, 14));
            case DLT_LINUX_SLL2:
                if (data.length < 20) return packet;
                return wrap(packet, data, 20, short16(data, 0));
            case DLT_NULL:
            case DLT_LOOP:
                // the address family's value differs between systems and byte orders; the IP version nibble doesn't
                if (data.length < 5 || (data[4] & 0xF0) != 0x40) return packet;
                return wrap(packet, data, 4, ETHERTYPE_IPV4);
            default:
                return packet;
        }
    }

    private static RawPacket wrap(RawPacket packet, byte[] data, int headerLength, int etherType) {
        int payload = data.length - headerLength;
        byte[] frame = new byte[ETHERNET_HEADER + payload];
        frame[12] = (byte) (etherType >> 8);
        frame[13] = (byte) etherType;
        System.arraycopy(data, headerLength, frame, ETHERNET_HEADER, payload);
        return RawPacket.newPacket(frame, packet.getInstant());
    }

    private static int short16(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }
}
