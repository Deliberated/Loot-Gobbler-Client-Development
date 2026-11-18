//! Cuts a byte stream into game packets: each starts with its total size (4 bytes, big-endian), then its type (1 byte),
//! then the encrypted body (Java: `ROTMGPacketConstructor`).
//!
//! Differs from Java on purpose: a size that cannot be right (below 5 or above 200,000) makes the Java constructor
//! stop working until the next connection. Here the rest of that segment is dropped and the next segment is read
//! as the start of a packet, so it can find its feet again.

/// The largest packet size believed.
pub const MAX_SIZE: usize = 200_000;
const HEADER: usize = 5;

#[derive(Default)]
pub struct Framer {
    buf: Vec<u8>,
    size: usize, // 0: not known yet
}

/// A size that cannot be right: the stream is out of step.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BadSize(pub i32);

impl Framer {
    /// Adds bytes; calls `packet` with every packet completed (header included).
    pub fn build(&mut self, mut data: &[u8], mut packet: impl FnMut(Vec<u8>)) -> Result<(), BadSize> {
        while !data.is_empty() {
            if self.size == 0 {
                let take = (4 - self.buf.len()).min(data.len());
                self.buf.extend_from_slice(&data[..take]);
                data = &data[take..];
                if self.buf.len() < 4 {
                    return Ok(());
                }
                let size = i32::from_be_bytes([self.buf[0], self.buf[1], self.buf[2], self.buf[3]]);
                if size < HEADER as i32 || size as usize > MAX_SIZE {
                    self.reset();
                    return Err(BadSize(size));
                }
                self.size = size as usize;
            }
            let take = (self.size - self.buf.len()).min(data.len());
            self.buf.extend_from_slice(&data[..take]);
            data = &data[take..];
            if self.buf.len() == self.size {
                self.size = 0;
                packet(std::mem::take(&mut self.buf));
            }
        }
        Ok(())
    }

    pub fn reset(&mut self) {
        self.buf.clear();
        self.size = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pkt(ty: u8, body: &[u8]) -> Vec<u8> {
        let mut p = ((body.len() + 5) as i32).to_be_bytes().to_vec();
        p.push(ty);
        p.extend(body);
        p
    }

    #[test]
    fn cuts_across_any_split() {
        let stream: Vec<u8> = [pkt(1, b"abc"), pkt(2, b""), pkt(3, &[9; 40])].concat();
        for split in 1..stream.len() {
            let mut f = Framer::default();
            let mut got = Vec::new();
            for chunk in stream.chunks(split) {
                f.build(chunk, |p| got.push(p)).unwrap();
            }
            assert_eq!(got, vec![pkt(1, b"abc"), pkt(2, b""), pkt(3, &[9; 40])], "chunks of {split}");
        }
    }

    #[test]
    fn a_bad_size_drops_the_segment_and_starts_over() {
        let mut f = Framer::default();
        let mut got = Vec::new();
        assert_eq!(f.build(&[0, 0, 0, 2, 7, 7], |p| got.push(p)), Err(BadSize(2)));
        assert_eq!(f.build(&[0x7F, 0, 0, 0], |p| got.push(p)), Err(BadSize(0x7F00_0000)));
        f.build(&pkt(4, b"ok"), |p| got.push(p)).unwrap();
        assert_eq!(got, vec![pkt(4, b"ok")]);
    }
}
