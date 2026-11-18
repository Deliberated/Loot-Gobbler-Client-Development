//! Classic pcap files, as `tcpdump -w` writes them: reading, and writing for test captures.
//! pcapng (Wireshark's default) is not read: save as "pcap" there, or record with tcpdump.

use std::fmt;

/// One captured frame and when it was captured.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame<'a> {
    /// Microseconds since 1970.
    pub micros: u64,
    pub data: &'a [u8],
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Error {
    TooShort,
    Pcapng,
    NotPcap(u32),
    Truncated { at: usize },
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::TooShort => write!(f, "too short for a pcap file"),
            Error::Pcapng => write!(f, "this is a pcapng file: record with `tcpdump -w`, or save as pcap in Wireshark"),
            Error::NotPcap(m) => write!(f, "not a pcap file (starts with {m:#010x})"),
            Error::Truncated { at } => write!(f, "the file ends in the middle of a frame (at byte {at})"),
        }
    }
}

impl std::error::Error for Error {}

/// A pcap file in memory: its link-layer type and its frames.
pub struct Reader<'a> {
    data: &'a [u8],
    at: usize,
    big_endian: bool,
    nanos: bool,
    pub link_type: u32,
}

impl<'a> Reader<'a> {
    pub fn new(data: &'a [u8]) -> Result<Self, Error> {
        if data.len() < 24 {
            return Err(Error::TooShort);
        }
        let magic = u32::from_le_bytes([data[0], data[1], data[2], data[3]]);
        let (big_endian, nanos) = match magic {
            0xA1B2_C3D4 => (false, false),
            0xA1B2_3C4D => (false, true),
            0xD4C3_B2A1 => (true, false),
            0x4D3C_B2A1 => (true, true),
            0x0A0D_0D0A => return Err(Error::Pcapng),
            m => return Err(Error::NotPcap(m)),
        };
        let mut r = Reader { data, at: 24, big_endian, nanos, link_type: 0 };
        r.link_type = r.u32(20);
        Ok(r)
    }

    fn u32(&self, at: usize) -> u32 {
        let b = [self.data[at], self.data[at + 1], self.data[at + 2], self.data[at + 3]];
        if self.big_endian { u32::from_be_bytes(b) } else { u32::from_le_bytes(b) }
    }

    /// The next frame, None at the end, an error if the file is cut off.
    pub fn next_frame(&mut self) -> Option<Result<Frame<'a>, Error>> {
        if self.at >= self.data.len() {
            return None;
        }
        if self.at + 16 > self.data.len() {
            return Some(Err(Error::Truncated { at: self.at }));
        }
        let secs = u64::from(self.u32(self.at));
        let frac = u64::from(self.u32(self.at + 4));
        let len = self.u32(self.at + 8) as usize;
        let start = self.at + 16;
        if start + len > self.data.len() {
            return Some(Err(Error::Truncated { at: self.at }));
        }
        self.at = start + len;
        let micros = secs * 1_000_000 + if self.nanos { frac / 1000 } else { frac };
        Some(Ok(Frame { micros, data: &self.data[start..start + len] }))
    }
}

/// Writes a pcap file (microsecond timestamps, little-endian).
pub struct Writer {
    pub bytes: Vec<u8>,
}

impl Writer {
    pub fn new(link_type: u32) -> Self {
        let mut bytes = Vec::new();
        bytes.extend(0xA1B2_C3D4u32.to_le_bytes());
        bytes.extend(2u16.to_le_bytes());
        bytes.extend(4u16.to_le_bytes());
        bytes.extend(0i32.to_le_bytes()); // time zone
        bytes.extend(0u32.to_le_bytes()); // accuracy
        bytes.extend(262_144u32.to_le_bytes()); // snap length
        bytes.extend(link_type.to_le_bytes());
        Writer { bytes }
    }

    pub fn frame(&mut self, micros: u64, data: &[u8]) {
        self.bytes.extend(((micros / 1_000_000) as u32).to_le_bytes());
        self.bytes.extend(((micros % 1_000_000) as u32).to_le_bytes());
        self.bytes.extend((data.len() as u32).to_le_bytes());
        self.bytes.extend((data.len() as u32).to_le_bytes());
        self.bytes.extend(data);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn writes_and_reads_back() {
        let mut w = Writer::new(1);
        w.frame(1_700_000_000_123_456, b"one");
        w.frame(1_700_000_001_000_000, b"two!");
        let mut r = Reader::new(&w.bytes).unwrap();
        assert_eq!(r.link_type, 1);
        assert_eq!(r.next_frame(), Some(Ok(Frame { micros: 1_700_000_000_123_456, data: b"one" })));
        assert_eq!(r.next_frame().unwrap().unwrap().data, b"two!");
        assert_eq!(r.next_frame(), None);
    }

    #[test]
    fn refuses_pcapng_and_cut_files() {
        let mut ng = vec![0x0A, 0x0D, 0x0D, 0x0A];
        ng.extend([0u8; 30]);
        assert_eq!(Reader::new(&ng).err(), Some(Error::Pcapng));
        let mut w = Writer::new(1);
        w.frame(0, b"abcdef");
        let cut = &w.bytes[..w.bytes.len() - 2];
        let mut r = Reader::new(cut).unwrap();
        assert!(matches!(r.next_frame(), Some(Err(Error::Truncated { .. }))));
    }
}
