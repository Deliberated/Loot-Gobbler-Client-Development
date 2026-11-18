//! TCP segments: ports, sequence number, flags and payload (Java: `TcpPacket`).

/// What the stream builder needs from a TCP segment.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Segment {
    pub src_port: u16,
    pub dst_port: u16,
    pub seq: u32,
    pub syn: bool,
    pub fin: bool,
    pub rst: bool,
    pub payload: Vec<u8>,
}

impl Segment {
    /// `b`: the IPv4 payload. A header length below 20 is taken as 20, as in Java.
    pub fn parse(b: &[u8]) -> Option<Segment> {
        if b.len() < 20 {
            return None;
        }
        let header = (usize::from(b[12] >> 4) * 4).max(20);
        let flags = b[13];
        Some(Segment {
            src_port: u16::from_be_bytes([b[0], b[1]]),
            dst_port: u16::from_be_bytes([b[2], b[3]]),
            seq: u32::from_be_bytes([b[4], b[5], b[6], b[7]]),
            rst: flags & 0x04 != 0,
            syn: flags & 0x02 != 0,
            fin: flags & 0x01 != 0,
            payload: b.get(header..)?.to_vec(),
        })
    }

    /// A TCP header (no options) followed by the payload. Used to write test captures.
    pub fn build(src_port: u16, dst_port: u16, seq: u32, flags: u8, payload: &[u8]) -> Vec<u8> {
        let mut b = Vec::with_capacity(20 + payload.len());
        b.extend(src_port.to_be_bytes());
        b.extend(dst_port.to_be_bytes());
        b.extend(seq.to_be_bytes());
        b.extend(0u32.to_be_bytes()); // ack
        b.extend([5 << 4, flags]);
        b.extend(65535u16.to_be_bytes()); // window
        b.extend([0, 0, 0, 0]); // checksum (the Java sniffer doesn't check it either), urgent pointer
        b.extend(payload);
        b
    }
}

pub const FLAG_SYN: u8 = 0x02;
pub const FLAG_ACK: u8 = 0x10;
pub const FLAG_PSH: u8 = 0x08;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let b = Segment::build(2050, 50000, 0xFFFF_FFF0, FLAG_PSH | FLAG_ACK, b"hello");
        let s = Segment::parse(&b).unwrap();
        assert_eq!((s.src_port, s.dst_port, s.seq), (2050, 50000, 0xFFFF_FFF0));
        assert!(!s.syn && !s.fin && !s.rst);
        assert_eq!(s.payload, b"hello");
    }
}
