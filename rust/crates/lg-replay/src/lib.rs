//! Replays a recorded game session: pcap file -> TCP streams -> decrypted game packets.

pub mod synth;

use lg_capture::{Flow, Splitter, StreamEvent, pcap};
use lg_protocol::{Decoder, Direction, Note, Packet};

/// Something the replay found, with when it was captured (microseconds since 1970).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Item {
    Packet { micros: u64, packet: Packet },
    Note { micros: u64, direction: Direction, note: Note },
    Gap { micros: u64, direction: Direction, skipped: u32 },
}

pub struct Replay {
    pub frames: usize,
    pub link_type: u32,
    pub items: Vec<Item>,
}

impl Replay {
    pub fn packets(&self) -> impl Iterator<Item = &Packet> {
        self.items.iter().filter_map(|i| match i {
            Item::Packet { packet, .. } => Some(packet),
            _ => None,
        })
    }
}

fn direction(flow: Flow) -> Direction {
    match flow {
        Flow::Incoming => Direction::Incoming,
        Flow::Outgoing => Direction::Outgoing,
    }
}

/// Replays a whole pcap file.
pub fn replay(file: &[u8]) -> Result<Replay, pcap::Error> {
    let mut reader = pcap::Reader::new(file)?;
    let link_type = reader.link_type;
    let mut splitter = Splitter::new();
    let mut incoming = Decoder::new(Direction::Incoming);
    let mut outgoing = Decoder::new(Direction::Outgoing);
    let (mut events, mut packets, mut notes, mut items) = (Vec::new(), Vec::new(), Vec::new(), Vec::new());
    let mut frames = 0;
    while let Some(frame) = reader.next_frame() {
        let frame = frame?;
        frames += 1;
        splitter.frame(link_type, frame.data, &mut events);
        for event in events.drain(..) {
            let (flow, micros) = match &event {
                StreamEvent::Data { flow, .. } | StreamEvent::Reset { flow } | StreamEvent::Gap { flow, .. } => (*flow, frame.micros),
            };
            let decoder = if flow == Flow::Incoming { &mut incoming } else { &mut outgoing };
            match event {
                StreamEvent::Data { bytes, .. } => decoder.bytes(&bytes, &mut packets, &mut notes),
                StreamEvent::Reset { .. } => decoder.reset(),
                StreamEvent::Gap { skipped, .. } => {
                    decoder.gap();
                    items.push(Item::Gap { micros, direction: direction(flow), skipped });
                }
            }
            items.extend(notes.drain(..).map(|note| Item::Note { micros, direction: direction(flow), note }));
            items.extend(packets.drain(..).map(|packet| Item::Packet { micros, packet }));
        }
    }
    Ok(Replay { frames, link_type, items })
}

/// CRC-32 (IEEE, as java.util.zip.CRC32): a short fingerprint of a packet's content for comparing with Java.
pub fn crc32(data: &[u8]) -> u32 {
    static TABLE: std::sync::OnceLock<[u32; 256]> = std::sync::OnceLock::new();
    let table = TABLE.get_or_init(|| {
        let mut t = [0u32; 256];
        for (n, e) in t.iter_mut().enumerate() {
            let mut c = n as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
            }
            *e = c;
        }
        t
    });
    !data.iter().fold(!0u32, |c, &b| table[((c ^ u32::from(b)) & 0xFF) as usize] ^ (c >> 8))
}

/// One packet as the comparison with the Java client prints it: direction, id, name, size, CRC-32 of its content.
pub fn oracle_line(p: &Packet) -> String {
    format!("{} {} {} {} {:08x}", p.direction.short(), p.id, p.name(), p.size(), crc32(&p.body))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc32_matches_java() {
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
        assert_eq!(crc32(b""), 0);
    }
}
