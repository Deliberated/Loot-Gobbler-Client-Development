//! Link-layer headers: where the IPv4 packet starts in a captured frame (Java: `LinkLayer` and `EthernetPacket`).

pub const DLT_NULL: u32 = 0; // BSD loopback: 4-byte address family in host byte order
pub const DLT_EN10MB: u32 = 1; // Ethernet
pub const DLT_RAW: u32 = 12; // raw IP (Linux, most systems)
pub const DLT_RAW_OPENBSD: u32 = 14; // raw IP (OpenBSD numbering)
pub const LINKTYPE_RAW: u32 = 101; // raw IP as pcap files name it
pub const DLT_LOOP: u32 = 108; // OpenBSD loopback: like DLT_NULL, network byte order
pub const DLT_LINUX_SLL: u32 = 113; // Linux cooked capture v1 (16-byte header), tcpdump -i any
pub const DLT_IPV4: u32 = 228; // raw IPv4
pub const DLT_LINUX_SLL2: u32 = 276; // Linux cooked capture v2 (20-byte header), newer tcpdump -i any

const ETHERTYPE_IPV4: u16 = 0x0800;
const ETHERTYPE_VLAN: u16 = 0x8100;

/// The IPv4 packet in a frame, or None if it carries something else (IPv6, ARP...) or the type is unknown.
pub fn ipv4_of(link_type: u32, frame: &[u8]) -> Option<&[u8]> {
    match link_type {
        DLT_EN10MB => {
            let mut ether_type = be16(frame, 12)?;
            let mut start = 14;
            if ether_type == ETHERTYPE_VLAN {
                ether_type = be16(frame, 16)?;
                start = 18;
            }
            (ether_type == ETHERTYPE_IPV4).then(|| frame.get(start..)).flatten()
        }
        DLT_RAW | DLT_RAW_OPENBSD | LINKTYPE_RAW | DLT_IPV4 => Some(frame),
        DLT_LINUX_SLL => (be16(frame, 14)? == ETHERTYPE_IPV4).then(|| frame.get(16..)).flatten(),
        DLT_LINUX_SLL2 => (be16(frame, 0)? == ETHERTYPE_IPV4).then(|| frame.get(20..)).flatten(),
        // the address family's value differs between systems and byte orders; the IP version nibble doesn't
        DLT_NULL | DLT_LOOP => (*frame.get(4)? & 0xF0 == 0x40).then(|| &frame[4..]),
        _ => None,
    }
}

fn be16(b: &[u8], at: usize) -> Option<u16> {
    Some(u16::from_be_bytes([*b.get(at)?, *b.get(at + 1)?]))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_ipv4_behind_each_header() {
        let ip = [0x45u8, 0, 0, 20];
        let mut eth = vec![0u8; 12];
        eth.extend([0x08, 0x00]);
        eth.extend(ip);
        assert_eq!(ipv4_of(DLT_EN10MB, &eth), Some(&ip[..]));

        let mut vlan = vec![0u8; 12];
        vlan.extend([0x81, 0x00, 0, 5, 0x08, 0x00]);
        vlan.extend(ip);
        assert_eq!(ipv4_of(DLT_EN10MB, &vlan), Some(&ip[..]));

        let mut sll = vec![0u8; 14];
        sll.extend([0x08, 0x00]);
        sll.extend(ip);
        assert_eq!(ipv4_of(DLT_LINUX_SLL, &sll), Some(&ip[..]));

        let mut sll2 = vec![0x08, 0x00];
        sll2.extend([0u8; 18]);
        sll2.extend(ip);
        assert_eq!(ipv4_of(DLT_LINUX_SLL2, &sll2), Some(&ip[..]));

        let mut null = vec![2, 0, 0, 0];
        null.extend(ip);
        assert_eq!(ipv4_of(DLT_NULL, &null), Some(&ip[..]));
        assert_eq!(ipv4_of(DLT_RAW, &ip), Some(&ip[..]));
    }

    #[test]
    fn ignores_other_protocols() {
        let mut ipv6 = vec![0u8; 12];
        ipv6.extend([0x86, 0xDD, 0x60]);
        assert_eq!(ipv4_of(DLT_EN10MB, &ipv6), None);
        assert_eq!(ipv4_of(9999, &[0x45]), None);
    }
}
