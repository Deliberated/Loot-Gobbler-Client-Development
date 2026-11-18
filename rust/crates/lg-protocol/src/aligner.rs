//! Finding where in the key stream a capture started (Java: `TickAligner`, `RC4Aligner`).
//!
//! The cipher runs on from the start of the connection, so a capture that starts later doesn't know its position.
//! Tick packets give it away: the server's NEWTICK (and the client's MOVE) packets start with a tick number one higher
//! than the one before. Two of them in a row, with the bytes between them known, are tried at every key stream
//! position until the second number is the first plus one. Once in step, every tick is checked against the count;
//! a wrong one means the stream is out of step again, and it starts searching anew.

use crate::rc4::Rc4;

/// How far into the key stream to search before giving up.
pub const SEARCH_SIZE: usize = 10_000_000;
pub const NEWTICK: u8 = 10;
pub const MOVE: u8 = 62;

/// What happened to the alignment, for the log.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AlignNote {
    /// Found: this many key stream bytes after the start of the search, at this tick.
    Synced { offset: usize, tick: i32 },
    /// A tick did not follow the one before: searching again.
    Lost { got: i32, expected: i32 },
    /// No position in the search range fits.
    Failed,
}

#[derive(Default)]
pub struct TickAligner {
    synced: bool,
    packet_bytes: i64,
    tick_a: Option<[u8; 4]>,
    current_tick: i32,
}

fn is_tick(ty: u8) -> bool {
    ty == NEWTICK || ty == MOVE
}

fn be_i32(b: &[u8]) -> i32 {
    i32::from_be_bytes([b[0], b[1], b[2], b[3]])
}

impl TickAligner {
    /// A new connection: the tick count starts over (Java: `reset`, which leaves `synced` as it is).
    pub fn reset(&mut self) {
        self.current_tick = -1;
    }

    /// `packet`: a whole encrypted packet, header included. True when the cipher is in step for it, so it can be
    /// decrypted with `rc4` as it is now.
    pub fn check(&mut self, rc4: &mut Rc4, packet: &[u8], ty: u8, note: &mut Option<AlignNote>) -> bool {
        let body = &packet[5..];
        let size = packet.len() as i64;
        if self.synced {
            if is_tick(ty) && body.len() >= 4 {
                let mut tick = [body[0], body[1], body[2], body[3]];
                rc4.clone().apply(&mut tick);
                self.current_tick = self.current_tick.wrapping_add(1);
                let tick = be_i32(&tick);
                if tick != self.current_tick {
                    *note = Some(AlignNote::Lost { got: tick, expected: self.current_tick });
                    rc4.reset();
                    self.synced = false;
                    self.tick_a = None;
                }
            }
            if self.synced {
                self.packet_bytes += size - 5;
            }
        }
        if self.synced {
            return true;
        }
        if is_tick(ty) && body.len() >= 4 {
            let mut tick = [body[0], body[1], body[2], body[3]];
            if let Some(a) = self.tick_a.take() {
                rc4.reset();
                let between = self.packet_bytes.max(0) as usize;
                match sync_cipher(rc4, &a, &tick, between) {
                    Some(offset) => {
                        self.synced = true;
                        rc4.skip(between);
                        rc4.apply(&mut tick);
                        rc4.skip(packet.len() - 5 - 4);
                        self.current_tick = be_i32(&tick);
                        *note = Some(AlignNote::Synced { offset, tick: self.current_tick });
                    }
                    None => *note = Some(AlignNote::Failed),
                }
                self.packet_bytes = 0;
            } else {
                self.tick_a = Some(tick);
                self.packet_bytes = size - 5;
            }
        } else {
            self.packet_bytes += size - 5;
        }
        false
    }
}

/// The key stream position (from `cipher` as it is) at which tick `a`, then `between` bytes later tick `b`, decrypt to
/// two numbers in a row. Moves `cipher` there.
fn sync_cipher(cipher: &mut Rc4, a: &[u8; 4], b: &[u8; 4], between: usize) -> Option<usize> {
    let mut finder_a = cipher.clone();
    let mut finder_b = cipher.clone();
    finder_b.skip(between);
    for offset in 0..SEARCH_SIZE {
        let mut ta = *a;
        finder_a.clone().apply(&mut ta);
        let mut tb = *b;
        finder_b.clone().apply(&mut tb);
        if be_i32(&ta).wrapping_add(1) == be_i32(&tb) {
            cipher.skip(offset);
            return Some(offset);
        }
        finder_a.next_byte();
        finder_b.next_byte();
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::rc4::INCOMING_KEY;

    /// Encrypted packets as a server sends them, the key stream started `skip` bytes before the first.
    fn stream(skip: usize, packets: &[(u8, Vec<u8>)]) -> Vec<Vec<u8>> {
        let mut c = Rc4::new(&INCOMING_KEY);
        c.skip(skip);
        packets
            .iter()
            .map(|(ty, body)| {
                let mut p = ((body.len() + 5) as i32).to_be_bytes().to_vec();
                p.push(*ty);
                let mut b = body.clone();
                c.apply(&mut b);
                p.extend(b);
                p
            })
            .collect()
    }

    fn tick(n: i32) -> (u8, Vec<u8>) {
        let mut b = n.to_be_bytes().to_vec();
        b.extend([0u8; 10]);
        (NEWTICK, b)
    }

    #[test]
    fn finds_its_step_after_two_ticks_then_follows() {
        let plain = vec![(44, vec![1u8; 30]), tick(500), (8, vec![2u8; 4]), (8, vec![3u8; 7]), tick(501), (8, vec![4u8; 5]), tick(502)];
        let enc = stream(12_345, &plain);
        let mut rc4 = Rc4::new(&INCOMING_KEY);
        let mut al = TickAligner::default();
        let mut decrypted = Vec::new();
        let mut notes = Vec::new();
        for (p, (ty, body)) in enc.iter().zip(&plain) {
            let mut note = None;
            if al.check(&mut rc4, p, *ty, &mut note) {
                let mut b = p[5..].to_vec();
                rc4.apply(&mut b);
                assert_eq!(&b, body);
                decrypted.push(*ty);
            }
            notes.extend(note);
        }
        assert_eq!(decrypted, vec![8, NEWTICK]);
        assert!(matches!(notes[0], AlignNote::Synced { tick: 501, .. }));
    }

    #[test]
    fn a_wrong_tick_loses_the_step() {
        let plain = vec![tick(1), tick(2), tick(3), tick(9)];
        let enc = stream(0, &plain);
        let mut rc4 = Rc4::new(&INCOMING_KEY);
        let mut al = TickAligner::default();
        let mut notes = Vec::new();
        for p in &enc {
            let mut note = None;
            if al.check(&mut rc4, p, NEWTICK, &mut note) {
                rc4.apply(&mut p[5..].to_vec());
            }
            notes.extend(note);
        }
        assert_eq!(notes[1], AlignNote::Lost { got: 9, expected: 4 });
    }
}
