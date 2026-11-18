//! From captured frames to the game's two byte streams.
//!
//! A port of RealmShark's sniffer pipeline as patched in Loot Gobbler (Java): link layer to IPv4, IPv4 fragments put
//! back together, TCP segments on port 2050 sorted into the stream from the server (incoming) and to it (outgoing),
//! each put in order by its sequence numbers. What comes out is what the Java `TcpStreamBuilder` hands to the packet
//! constructor, byte for byte.

pub mod ip;
pub mod link;
pub mod pcap;
pub mod stream;
pub mod tcp;

/// The game server's port.
pub const GAME_PORT: u16 = 2050;

/// Which way bytes travel: from the game server, or to it.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Flow {
    Incoming,
    Outgoing,
}

/// What the two streams produce, in the order it happened.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StreamEvent {
    /// Bytes of one stream, in order. `source`: the IPv4 address they came from.
    Data { flow: Flow, bytes: Vec<u8>, source: [u8; 4] },
    /// A new connection began in this direction (a SYN): the decoder starts over.
    Reset { flow: Flow },
    /// Segments were missing for too long: the stream goes on after a gap of this many bytes.
    Gap { flow: Flow, skipped: u32 },
}

/// Takes captured frames and turns them into stream events: the Java `Sniffer` after the capture.
#[derive(Default)]
pub struct Splitter {
    defrag: ip::Defragmenter,
    incoming: stream::StreamBuilder,
    outgoing: stream::StreamBuilder,
}

impl Splitter {
    pub fn new() -> Self {
        Self::default()
    }

    /// One captured frame of the given link-layer type (pcap's LINKTYPE / DLT value).
    pub fn frame(&mut self, link_type: u32, frame: &[u8], out: &mut Vec<StreamEvent>) {
        let Some(packet) = link::ipv4_of(link_type, frame) else { return };
        let Some(ip) = ip::Ipv4::parse(packet) else { return };
        let Some(ip) = self.defrag.defragment(ip) else { return };
        if ip.protocol != ip::TCP {
            return;
        }
        let Some(tcp) = tcp::Segment::parse(&ip.payload) else { return };
        if tcp.src_port == GAME_PORT {
            self.incoming.segment(Flow::Incoming, &tcp, ip.src, out);
        } else if tcp.dst_port == GAME_PORT {
            self.outgoing.segment(Flow::Outgoing, &tcp, ip.src, out);
        }
    }
}
