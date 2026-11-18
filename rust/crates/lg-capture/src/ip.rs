//! IPv4 packets and putting fragmented ones back together (Java: `Ip4Packet`, `Ip4Defragmenter`).
//!
//! Differs from Java on purpose: the Java defragmenter (marked "untested" there) copies the fragments to the wrong
//! place and keeps the first fragment's length, so a fragmented packet never comes out whole. Here it does. The game
//! sets "don't fragment" on its packets, so this almost never matters.

use std::collections::HashMap;

pub const TCP: u8 = 6;

/// An IPv4 packet: what the rest needs from its header, and its payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ipv4 {
    pub id: u16,
    pub dont_fragment: bool,
    pub more_fragments: bool,
    /// In units of 8 bytes.
    pub fragment_offset: u16,
    pub protocol: u8,
    pub src: [u8; 4],
    pub dst: [u8; 4],
    pub payload: Vec<u8>,
}

impl Ipv4 {
    /// The payload ends where the header's total length says (Ethernet may pad short frames).
    pub fn parse(b: &[u8]) -> Option<Ipv4> {
        if b.len() < 20 || b[0] >> 4 != 4 {
            return None;
        }
        let header = usize::from(b[0] & 0x0F) * 4;
        let total = usize::from(u16::from_be_bytes([b[2], b[3]]));
        if header < 20 || total < header || total > b.len() {
            return None;
        }
        let flags = u16::from_be_bytes([b[6], b[7]]);
        Some(Ipv4 {
            id: u16::from_be_bytes([b[4], b[5]]),
            dont_fragment: flags & 0x4000 != 0,
            more_fragments: flags & 0x2000 != 0,
            fragment_offset: flags & 0x1FFF,
            protocol: b[9],
            src: [b[12], b[13], b[14], b[15]],
            dst: [b[16], b[17], b[18], b[19]],
            payload: b[header..total].to_vec(),
        })
    }

    fn is_fragment(&self) -> bool {
        !self.dont_fragment && (self.more_fragments || self.fragment_offset != 0)
    }
}

/// Holds fragments until all of a packet's are there.
#[derive(Default)]
pub struct Defragmenter {
    waiting: HashMap<(u16, [u8; 4]), Vec<Ipv4>>,
}

impl Defragmenter {
    /// The packet itself if it is whole; the whole packet once its last missing fragment came; else None.
    pub fn defragment(&mut self, ip: Ipv4) -> Option<Ipv4> {
        if !ip.is_fragment() {
            return Some(ip);
        }
        let key = (ip.id, ip.src);
        let parts = self.waiting.entry(key).or_default();
        parts.push(ip);
        let end = parts.iter().find(|p| !p.more_fragments).map(|p| usize::from(p.fragment_offset) * 8 + p.payload.len())?;
        let mut covered = vec![false; end];
        for p in parts.iter() {
            let at = usize::from(p.fragment_offset) * 8;
            for c in covered.iter_mut().skip(at).take(p.payload.len()) {
                *c = true;
            }
        }
        if !covered.iter().all(|c| *c) {
            return None;
        }
        let parts = self.waiting.remove(&key)?;
        let mut payload = vec![0u8; end];
        for p in &parts {
            let at = usize::from(p.fragment_offset) * 8;
            let n = p.payload.len().min(end - at);
            payload[at..at + n].copy_from_slice(&p.payload[..n]);
        }
        let first = &parts[0];
        Some(Ipv4 { more_fragments: false, fragment_offset: 0, payload, ..first.clone() })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn header(id: u16, flags: u16, payload: &[u8]) -> Vec<u8> {
        let total = (20 + payload.len()) as u16;
        let mut b = vec![0x45, 0];
        b.extend(total.to_be_bytes());
        b.extend(id.to_be_bytes());
        b.extend(flags.to_be_bytes());
        b.extend([64, TCP, 0, 0, 10, 0, 0, 1, 10, 0, 0, 2]);
        b.extend(payload);
        b
    }

    #[test]
    fn parses_and_drops_padding() {
        let mut b = header(7, 0x4000, &[1, 2, 3]);
        b.extend([0, 0, 0]); // Ethernet padding
        let ip = Ipv4::parse(&b).unwrap();
        assert_eq!(ip.payload, vec![1, 2, 3]);
        assert_eq!(ip.src, [10, 0, 0, 1]);
        assert!(ip.dont_fragment);
    }

    #[test]
    fn puts_fragments_together_in_any_order() {
        let data: Vec<u8> = (0..24).collect();
        let a = Ipv4::parse(&header(9, 0x2000, &data[0..8])).unwrap();
        let b = Ipv4::parse(&header(9, 0x2000 | 1, &data[8..16])).unwrap();
        let c = Ipv4::parse(&header(9, 2, &data[16..24])).unwrap();
        let mut d = Defragmenter::default();
        assert!(d.defragment(c).is_none());
        assert!(d.defragment(a).is_none());
        let whole = d.defragment(b).unwrap();
        assert_eq!(whole.payload, data);
        assert!(d.waiting.is_empty());
    }
}
