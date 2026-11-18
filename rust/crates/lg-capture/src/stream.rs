//! One direction's TCP segments put in order (Java: `TcpStreamBuilder` with `TcpStreamErrorHandler.errorChecker`,
//! as patched in Loot Gobbler v0.9.0).
//!
//! - SYN, FIN and RST segments carry nothing for the stream; a SYN starts it over (a new connection).
//! - Empty segments (plain ACKs) are skipped; data already passed on (retransmissions, duplicates) is dropped, and a
//!   segment reaching past the expected position gives only its new part.
//! - Segments ahead of the expected position wait. When more than 95 wait for one that never comes (the capture
//!   missed it), the stream goes on at the nearest one: the bytes in between are lost.
//!
//! Like the Java sniffer, all connections of a direction share one builder.

use crate::{Flow, StreamEvent, tcp::Segment};
use std::collections::HashMap;

/// How many segments may wait for a missing one before it is given up.
pub const MAX_WAITING: usize = 95;

#[derive(Default)]
pub struct StreamBuilder {
    expected: Option<u32>,
    waiting: HashMap<u32, (Vec<u8>, [u8; 4])>,
}

/// How far `seq` lies before `expected` in the 32-bit sequence space; 0 or less when at or after it.
fn behind(expected: u32, seq: u32) -> i32 {
    expected.wrapping_sub(seq) as i32
}

impl StreamBuilder {
    pub fn segment(&mut self, flow: Flow, s: &Segment, source: [u8; 4], out: &mut Vec<StreamEvent>) {
        if s.rst || s.syn || s.fin {
            if s.syn {
                self.expected = None;
                self.waiting.clear();
                out.push(StreamEvent::Reset { flow });
            }
            return;
        }
        let expected = *self.expected.get_or_insert(s.seq);
        if s.payload.is_empty() {
            return;
        }
        let back = behind(expected, s.seq);
        if back > 0 {
            let back = back as usize;
            if back >= s.payload.len() {
                return; // all of it was passed on already
            }
            let fresh = s.payload[back..].to_vec();
            self.expected = Some(expected.wrapping_add(fresh.len() as u32));
            out.push(StreamEvent::Data { flow, bytes: fresh, source });
        } else {
            self.waiting.insert(s.seq, (s.payload.clone(), source));
        }
        self.skip_lost(flow, out);
        while let Some(at) = self.expected {
            let Some((bytes, source)) = self.waiting.remove(&at) else { break };
            self.expected = Some(at.wrapping_add(bytes.len() as u32));
            out.push(StreamEvent::Data { flow, bytes, source });
        }
    }

    /// Too many wait: go on at the nearest one after the expected position (Java: `errorChecker`).
    fn skip_lost(&mut self, flow: Flow, out: &mut Vec<StreamEvent>) {
        if self.waiting.len() <= MAX_WAITING {
            return;
        }
        let Some(expected) = self.expected else { return };
        let nearest = self.waiting.keys().map(|&seq| (-(behind(expected, seq) as i64), seq)).filter(|(gap, _)| *gap >= 0).min();
        let Some((gap, start)) = nearest else {
            self.waiting.clear();
            return;
        };
        self.waiting.retain(|&seq, _| behind(start, seq) <= 0);
        self.expected = Some(start);
        out.push(StreamEvent::Gap { flow, skipped: gap as u32 });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tcp::{FLAG_SYN, Segment};

    const SRC: [u8; 4] = [1, 2, 3, 4];

    fn seg(seq: u32, payload: &[u8]) -> Segment {
        Segment::parse(&Segment::build(2050, 40000, seq, 0, payload)).unwrap()
    }

    fn feed(b: &mut StreamBuilder, segs: &[Segment]) -> (Vec<u8>, Vec<StreamEvent>) {
        let mut out = Vec::new();
        for s in segs {
            b.segment(Flow::Incoming, s, SRC, &mut out);
        }
        let mut bytes = Vec::new();
        for e in &out {
            if let StreamEvent::Data { bytes: d, .. } = e {
                bytes.extend(d);
            }
        }
        (bytes, out)
    }

    #[test]
    fn orders_segments() {
        let mut b = StreamBuilder::default();
        let (bytes, _) = feed(&mut b, &[seg(100, b"ab"), seg(104, b"ef"), seg(102, b"cd")]);
        assert_eq!(bytes, b"abcdef");
    }

    #[test]
    fn drops_duplicates_and_keeps_the_new_part_of_an_overlap() {
        let mut b = StreamBuilder::default();
        let (bytes, _) = feed(&mut b, &[seg(100, b"abcd"), seg(100, b"abcd"), seg(102, b"cdef"), seg(104, b"")]);
        assert_eq!(bytes, b"abcdef");
    }

    #[test]
    fn wraps_around_the_sequence_space() {
        let mut b = StreamBuilder::default();
        let (bytes, _) = feed(&mut b, &[seg(u32::MAX - 1, b"ab"), seg(2, b"ef"), seg(0, b"cd")]);
        assert_eq!(bytes, b"abcdef");
    }

    #[test]
    fn a_syn_starts_over() {
        let mut b = StreamBuilder::default();
        let syn = Segment::parse(&Segment::build(2050, 40000, 5000, FLAG_SYN, b"")).unwrap();
        let (bytes, out) = feed(&mut b, &[seg(100, b"ab"), seg(104, b"zz"), syn, seg(5001, b"new")]);
        assert_eq!(bytes, b"abnew");
        assert!(out.contains(&StreamEvent::Reset { flow: Flow::Incoming }));
    }

    #[test]
    fn gives_up_on_a_segment_that_never_comes() {
        let mut b = StreamBuilder::default();
        let mut segs = vec![seg(0, b"a")];
        // byte 1..10 are lost; 96 one-byte segments after them wait
        segs.extend((0..=MAX_WAITING as u32).map(|i| seg(10 + i, b"x")));
        let (bytes, out) = feed(&mut b, &segs);
        assert!(out.contains(&StreamEvent::Gap { flow: Flow::Incoming, skipped: 9 }));
        assert_eq!(bytes.len(), 1 + MAX_WAITING + 1);
    }
}
