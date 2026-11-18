//! The game's protocol: from one direction's byte stream to decrypted packets.
//!
//! A port of RealmShark's `PacketConstructor` (Java): framing (`framer`), finding the cipher's position (`aligner`),
//! RC4 decryption (`rc4`), the packet type table (`packet_types`, generated from the Java enum) and decoders for some
//! packets (`packets`).

pub mod aligner;
pub mod framer;
pub mod packet_types;
pub mod packets;
pub mod rc4;
pub mod reader;

use aligner::{AlignNote, TickAligner};
use framer::{BadSize, Framer};
use rc4::Rc4;

/// Which way a packet travels.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Direction {
    /// From the game server.
    Incoming,
    /// To the game server.
    Outgoing,
}

impl Direction {
    pub fn short(self) -> &'static str {
        match self {
            Direction::Incoming => "in",
            Direction::Outgoing => "out",
        }
    }
}

/// The name of a packet type, None if the table doesn't know it.
pub fn packet_name(id: u8) -> Option<&'static str> {
    packet_types::PACKET_TYPES.iter().find(|(i, _, _)| *i == id).map(|(_, n, _)| *n)
}

/// A decrypted game packet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Packet {
    pub direction: Direction,
    pub id: u8,
    /// Its decrypted content, after the 5-byte header.
    pub body: Vec<u8>,
}

impl Packet {
    /// Its size on the wire, header included.
    pub fn size(&self) -> usize {
        self.body.len() + 5
    }

    pub fn name(&self) -> &'static str {
        packet_name(self.id).unwrap_or("UNKNOWN")
    }
}

/// Something worth logging that is not a packet.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Note {
    Align(AlignNote),
    BadSize(i32),
}

/// One direction's stream to packets (Java: `PacketConstructor`).
pub struct Decoder {
    direction: Direction,
    rc4: Rc4,
    aligner: TickAligner,
    framer: Framer,
    /// The capture may start in the middle of a packet: it waits for a segment below the usual maximum (1460 bytes),
    /// which most likely ends a packet, and starts with the one after it. Only at the start, not after a reset.
    waiting_for_short: bool,
}

/// TCP's usual largest segment: a shorter one most likely ends a packet.
pub const FULL_SEGMENT: usize = 1460;

impl Decoder {
    pub fn new(direction: Direction) -> Self {
        let key = match direction {
            Direction::Incoming => &rc4::INCOMING_KEY,
            Direction::Outgoing => &rc4::OUTGOING_KEY,
        };
        Decoder { direction, rc4: Rc4::new(key), aligner: TickAligner::default(), framer: Framer::default(), waiting_for_short: true }
    }

    /// A segment's bytes, in stream order. The packets decrypted go to `packets`, anything to log to `notes`.
    pub fn bytes(&mut self, data: &[u8], packets: &mut Vec<Packet>, notes: &mut Vec<Note>) {
        if self.waiting_for_short {
            if data.len() < FULL_SEGMENT {
                self.waiting_for_short = false;
            }
            return;
        }
        let (rc4, aligner, direction) = (&mut self.rc4, &mut self.aligner, self.direction);
        let result = self.framer.build(data, |mut raw| {
            let id = raw[4];
            let mut note = None;
            if aligner.check(rc4, &raw, id, &mut note) {
                rc4.apply(&mut raw[5..]);
                packets.push(Packet { direction, id, body: raw.split_off(5) });
            }
            notes.extend(note.map(Note::Align));
        });
        if let Err(BadSize(size)) = result {
            notes.push(Note::BadSize(size));
        }
    }

    /// Bytes were lost in the stream (the capture missed them): the next bytes are read as the start of a packet.
    /// The Java client goes on reading where it was, which reads a size from the middle of a packet; the cipher's
    /// position is lost either way and found again from the next two ticks.
    pub fn gap(&mut self) {
        self.framer.reset();
    }

    /// A new connection: the cipher starts at the beginning of its key stream.
    pub fn reset(&mut self) {
        self.rc4.reset();
        self.aligner.reset();
        self.framer.reset();
    }
}
