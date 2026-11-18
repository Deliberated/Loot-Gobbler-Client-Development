//! Made-up game sessions as pcap files, for tests: no real recording (which holds your login) is needed.
//!
//! Both directions start with a SYN, then send packets encrypted as the game does, with tick packets (NEWTICK in,
//! MOVE out) counting up among others. The packets are put into TCP segments of at most 1460 bytes, mostly ending at
//! a packet's end, big packets split over several. Some segments then arrive late, twice, or overlapping, as on a
//! real network; `Options::lose` drops one.

use lg_capture::{link, pcap, tcp};
use lg_protocol::packets::Text;
use lg_protocol::rc4::{INCOMING_KEY, OUTGOING_KEY, Rc4};
use lg_protocol::{Direction, aligner};

const CLIENT: [u8; 4] = [192, 168, 1, 10];
const SERVER: [u8; 4] = [52, 1, 2, 3];
const CLIENT_PORT: u16 = 51234;

/// A packet before encryption.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Plain {
    pub id: u8,
    pub body: Vec<u8>,
}

pub struct Options {
    pub seed: u64,
    /// Tick packets per direction.
    pub ticks: usize,
    /// Mix the order of segments, send some twice or overlapping.
    pub disorder: bool,
    /// Lose the bytes of the incoming segment with this index (counting from the first data segment).
    pub lose: Option<usize>,
}

impl Default for Options {
    fn default() -> Self {
        Options { seed: 1, ticks: 120, disorder: true, lose: None }
    }
}

pub struct Session {
    pub pcap: Vec<u8>,
    pub incoming: Vec<Plain>,
    pub outgoing: Vec<Plain>,
}

/// A small deterministic random number generator (xorshift).
struct Rng(u64);

impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 << 13;
        self.0 ^= self.0 >> 7;
        self.0 ^= self.0 << 17;
        self.0
    }

    fn below(&mut self, n: usize) -> usize {
        (self.next() % n as u64) as usize
    }

    fn bytes(&mut self, n: usize) -> Vec<u8> {
        (0..n).map(|_| self.next() as u8).collect()
    }

    /// `min` to `min + spread - 1` random bytes.
    fn some_bytes(&mut self, min: usize, spread: usize) -> Vec<u8> {
        let n = min + self.below(spread);
        self.bytes(n)
    }
}

fn plain_packets(rng: &mut Rng, direction: Direction, ticks: usize) -> Vec<Plain> {
    let first_tick = 1000 + rng.below(5000) as i32;
    let chat = |name: &str, text: &str| Plain {
        id: 44,
        body: Text {
            name: name.into(),
            object_id: 1,
            num_stars: 70,
            bubble_time: 5,
            recipient: String::new(),
            text: text.into(),
            clean_text: text.into(),
            is_supporter: false,
            star_background: 0,
        }
        .encode(),
    };
    let mut out = Vec::new();
    match direction {
        Direction::Incoming => out.push(chat("Oryx", "Welcome to the Realm")),
        Direction::Outgoing => out.push(Plain { id: 9, body: rng.bytes(12) }), // PLAYERTEXT
    }
    for t in 0..ticks {
        let tick = first_tick + t as i32;
        let mut body = tick.to_be_bytes().to_vec();
        match direction {
            Direction::Incoming => {
                body.extend((t as i32 * 200).to_be_bytes()); // tick time
                body.extend(rng.bytes(6));
                body.extend(0i16.to_be_bytes()); // no statuses
                body.extend(rng.some_bytes(0, 300)); // stands in for them
                out.push(Plain { id: aligner::NEWTICK, body });
            }
            Direction::Outgoing => {
                body.extend(rng.some_bytes(8, 40));
                out.push(Plain { id: aligner::MOVE, body });
            }
        }
        for _ in 0..rng.below(4) {
            out.push(match (direction, rng.below(6)) {
                (Direction::Incoming, 0) => chat("Gobbler", &format!("tick {tick}: loot!")),
                (Direction::Incoming, 1) => Plain { id: 42, body: rng.some_bytes(1500, 4000) }, // UPDATE, big
                (Direction::Incoming, _) => Plain { id: 8, body: rng.bytes(4) },                // PING
                (Direction::Outgoing, 0) => Plain { id: 9, body: rng.some_bytes(10, 30) },      // PLAYERTEXT
                (Direction::Outgoing, _) => Plain { id: 31, body: rng.some_bytes(4, 8) },       // PONG
            });
        }
    }
    out
}

/// The packets encrypted and framed, as the bytes of one TCP stream; and where each packet ends in it.
fn stream_of(direction: Direction, packets: &[Plain]) -> (Vec<u8>, Vec<usize>) {
    let key = if direction == Direction::Incoming { &INCOMING_KEY } else { &OUTGOING_KEY };
    let mut rc4 = Rc4::new(key);
    let mut stream = Vec::new();
    let mut ends = Vec::new();
    for p in packets {
        stream.extend(((p.body.len() + 5) as i32).to_be_bytes());
        stream.push(p.id);
        let mut body = p.body.clone();
        rc4.apply(&mut body);
        stream.extend(body);
        ends.push(stream.len());
    }
    (stream, ends)
}

/// (offset in the stream, length): segments of at most 1460 bytes, ending at packet ends where they can. The first
/// holds only the first packet, so the decoder (which skips the capture's first short segment) starts at a packet.
fn segments(rng: &mut Rng, stream_len: usize, ends: &[usize]) -> Vec<(usize, usize)> {
    let mut segs = vec![(0, ends[0])];
    let mut at = ends[0];
    let mut next_end = 1;
    while at < stream_len {
        let limit = (at + 200 + rng.below(1261)).min(stream_len); // 200..=1460 bytes
        while next_end < ends.len() && ends[next_end] <= at {
            next_end += 1;
        }
        let mut cut = at;
        let mut i = next_end;
        while i < ends.len() && ends[i] <= limit {
            cut = ends[i];
            i += 1;
        }
        if cut == at {
            cut = limit; // a packet bigger than the segment: split it
        }
        segs.push((at, cut - at));
        at = cut;
    }
    segs
}

/// Late, twice, overlapping: reorders neighbours, repeats some, and resends some with part of the one before.
fn disorder(rng: &mut Rng, segs: &mut Vec<(usize, usize)>) {
    let mut out: Vec<(usize, usize)> = Vec::with_capacity(segs.len() + segs.len() / 5);
    let mut i = 0;
    while i < segs.len() {
        let s = segs[i];
        match rng.below(12) {
            0 if i + 1 < segs.len() && i > 0 => {
                out.push(segs[i + 1]);
                out.push(s);
                i += 1;
            }
            1 => {
                out.push(s);
                out.push(s);
            }
            2 if i > 0 => {
                let (pa, pl) = segs[i - 1];
                let back = 1 + rng.below(pl);
                out.push(s);
                out.push((pa + pl - back, back + s.1)); // the end of the one before and all of this one again
            }
            _ => out.push(s),
        }
        i += 1;
    }
    *segs = out;
}

fn frame(src: [u8; 4], dst: [u8; 4], id: u16, tcp: &[u8]) -> Vec<u8> {
    let mut f = vec![0u8; 12];
    f.extend([0x08, 0x00]);
    f.extend([0x45, 0]);
    f.extend(((20 + tcp.len()) as u16).to_be_bytes());
    f.extend(id.to_be_bytes());
    f.extend(0x4000u16.to_be_bytes()); // don't fragment
    f.extend([64, 6, 0, 0]);
    f.extend(src);
    f.extend(dst);
    f.extend(tcp);
    f
}

/// A made-up session as a pcap file (Ethernet), and the packets sent each way.
pub fn session(opts: &Options) -> Session {
    let mut rng = Rng(opts.seed.max(1).wrapping_mul(0x9E37_79B9_7F4A_7C15));
    let incoming = plain_packets(&mut rng, Direction::Incoming, opts.ticks);
    let outgoing = plain_packets(&mut rng, Direction::Outgoing, opts.ticks);
    let mut sides = Vec::new();
    for (direction, packets) in [(Direction::Incoming, &incoming), (Direction::Outgoing, &outgoing)] {
        let (stream, ends) = stream_of(direction, packets);
        let mut segs = segments(&mut rng, stream.len(), &ends);
        let lost = opts.lose.filter(|_| direction == Direction::Incoming).and_then(|i| segs.get(i).copied());
        if opts.disorder {
            disorder(&mut rng, &mut segs);
        }
        if let Some((from, len)) = lost {
            // every copy of those bytes, resends too
            segs.retain(|&(at, l)| at + l <= from || at >= from + len);
        }
        sides.push((direction, stream, segs));
    }

    let mut w = pcap::Writer::new(link::DLT_EN10MB);
    let mut micros = 1_763_000_000_000_000u64;
    let mut ip_id = 1u16;
    let isn = [rng.next() as u32, rng.next() as u32];
    for (k, (direction, _, _)) in sides.iter().enumerate() {
        let (src, dst, sp, dp) = endpoints(*direction);
        let syn = tcp::Segment::build(sp, dp, isn[k].wrapping_sub(1), tcp::FLAG_SYN, &[]);
        w.frame(micros, &frame(src, dst, ip_id, &syn));
        micros += 1000;
        ip_id = ip_id.wrapping_add(1);
    }
    // the two directions take turns, a few segments each
    let mut next = [0usize; 2];
    while next[0] < sides[0].2.len() || next[1] < sides[1].2.len() {
        for k in 0..2 {
            let (direction, stream, segs) = &sides[k];
            let (src, dst, sp, dp) = endpoints(*direction);
            for _ in 0..1 + rng.below(3) {
                let Some(&(at, len)) = segs.get(next[k]) else { break };
                next[k] += 1;
                let seq = isn[k].wrapping_add(at as u32);
                let seg = tcp::Segment::build(sp, dp, seq, tcp::FLAG_ACK | tcp::FLAG_PSH, &stream[at..at + len]);
                w.frame(micros, &frame(src, dst, ip_id, &seg));
                micros += 2000 + rng.below(8000) as u64;
                ip_id = ip_id.wrapping_add(1);
            }
        }
    }
    Session { pcap: w.bytes, incoming, outgoing }
}

fn endpoints(direction: Direction) -> ([u8; 4], [u8; 4], u16, u16) {
    match direction {
        Direction::Incoming => (SERVER, CLIENT, lg_capture::GAME_PORT, CLIENT_PORT),
        Direction::Outgoing => (CLIENT, SERVER, CLIENT_PORT, lg_capture::GAME_PORT),
    }
}
