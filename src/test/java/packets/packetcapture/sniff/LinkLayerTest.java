package packets.packetcapture.sniff;

import org.junit.jupiter.api.Test;
import packets.packetcapture.sniff.netpackets.RawPacket;
import packets.packetcapture.sniff.netpackets.TcpPacket;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class LinkLayerTest {

    /** IPv4 + TCP from 1.2.3.4:2050 to 10.0.0.2:40000 carrying "hi". */
    private static byte[] ipTcp() {
        byte[] p = new byte[20 + 20 + 2];
        p[0] = 0x45;
        p[3] = (byte) p.length;
        p[8] = 64;
        p[9] = 6;
        p[12] = 1; p[13] = 2; p[14] = 3; p[15] = 4;
        p[16] = 10; p[19] = 2;
        p[20] = (byte) (2050 >> 8); p[21] = (byte) 2050;
        p[22] = (byte) (40000 >> 8); p[23] = (byte) 40000;
        p[32] = 0x50;   // data offset 5 words
        p[33] = 0x18;   // PSH ACK
        p[40] = 'h'; p[41] = 'i';
        return p;
    }

    private static byte[] withHeader(byte[] header, byte[] ip) {
        byte[] out = new byte[header.length + ip.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(ip, 0, out, header.length, ip.length);
        return out;
    }

    private static TcpPacket parse(byte[] frame, int linkType) {
        RawPacket eth = LinkLayer.toEthernet(RawPacket.newPacket(frame, Instant.EPOCH), linkType);
        return eth.getNewEthernetPacket().getNewIp4Packet().getNewTcpPacket();
    }

    private static void assertGamePacket(TcpPacket tcp) {
        assertNotNull(tcp);
        assertEquals(2050, tcp.getSrcPort());
        assertEquals(40000, tcp.getDstPort());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, tcp.getIp4Packet().getSrcAddr());
        assertArrayEquals(new byte[]{'h', 'i'}, tcp.getPayload());
    }

    @Test
    void ethernetIsUnchanged() {
        byte[] eth = new byte[14];
        eth[12] = 0x08;
        RawPacket raw = RawPacket.newPacket(withHeader(eth, ipTcp()), Instant.EPOCH);
        assertSame(raw, LinkLayer.toEthernet(raw, LinkLayer.DLT_EN10MB));
        assertGamePacket(parse(withHeader(eth, ipTcp()), LinkLayer.DLT_EN10MB));
    }

    @Test
    void rawIpFromVpn() {
        assertGamePacket(parse(ipTcp(), LinkLayer.DLT_RAW));
        assertGamePacket(parse(ipTcp(), LinkLayer.LINKTYPE_RAW));
    }

    @Test
    void linuxCookedV1() {
        byte[] sll = new byte[16];
        sll[14] = 0x08;   // protocol 0x0800
        assertGamePacket(parse(withHeader(sll, ipTcp()), LinkLayer.DLT_LINUX_SLL));
    }

    @Test
    void linuxCookedV2() {
        byte[] sll2 = new byte[20];
        sll2[0] = 0x08;   // protocol 0x0800
        assertGamePacket(parse(withHeader(sll2, ipTcp()), LinkLayer.DLT_LINUX_SLL2));
    }

    @Test
    void bsdLoopback() {
        byte[] nullHeader = {2, 0, 0, 0};   // AF_INET, little endian
        assertGamePacket(parse(withHeader(nullHeader, ipTcp()), LinkLayer.DLT_NULL));
    }
}
