//! The RC4 cipher the game encrypts its packets with (Java: `RC4`, `RotMGRC4Keys`).

/// Packets from the game server to the client.
pub const INCOMING_KEY: [u8; 13] = hex("c91d9eec420160730d825604e0");
/// Packets from the client to the game server.
pub const OUTGOING_KEY: [u8; 13] = hex("5a4d2016bc16dc64883194ffd9");

const fn hex(s: &str) -> [u8; 13] {
    const fn digit(c: u8) -> u8 {
        match c {
            b'0'..=b'9' => c - b'0',
            b'a'..=b'f' => c - b'a' + 10,
            _ => panic!("not a hex digit"),
        }
    }
    let b = s.as_bytes();
    let mut out = [0u8; 13];
    let mut i = 0;
    while i < 13 {
        out[i] = digit(b[2 * i]) << 4 | digit(b[2 * i + 1]);
        i += 1;
    }
    out
}

/// The cipher's state: where in its key stream it is. Cloning it is the Java `fork`.
#[derive(Clone)]
pub struct Rc4 {
    state: [u8; 256],
    init: [u8; 256],
    i: u8,
    j: u8,
}

impl Rc4 {
    pub fn new(key: &[u8]) -> Self {
        let mut state = [0u8; 256];
        for (k, s) in state.iter_mut().enumerate() {
            *s = k as u8;
        }
        let mut j = 0u8;
        for i in 0..256 {
            j = j.wrapping_add(state[i]).wrapping_add(key[i % key.len()]);
            state.swap(i, usize::from(j));
        }
        Rc4 { state, init: state, i: 0, j: 0 }
    }

    /// The next key stream byte.
    #[inline]
    pub fn next_byte(&mut self) -> u8 {
        self.i = self.i.wrapping_add(1);
        self.j = self.j.wrapping_add(self.state[usize::from(self.i)]);
        self.state.swap(usize::from(self.i), usize::from(self.j));
        self.state[usize::from(self.state[usize::from(self.i)].wrapping_add(self.state[usize::from(self.j)]))]
    }

    /// Moves on this many key stream bytes.
    pub fn skip(&mut self, n: usize) {
        for _ in 0..n {
            self.next_byte();
        }
    }

    /// Decrypts (or encrypts: it is the same) in place.
    pub fn apply(&mut self, data: &mut [u8]) {
        for b in data {
            *b ^= self.next_byte();
        }
    }

    /// Back to the start of the key stream.
    pub fn reset(&mut self) {
        self.state = self.init;
        self.i = 0;
        self.j = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn known_rc4_vector() {
        // RFC 6229 style check: key "Key", plaintext "Plaintext" -> BBF316E8D940AF0AD3
        let mut c = Rc4::new(b"Key");
        let mut data = *b"Plaintext";
        c.apply(&mut data);
        assert_eq!(data, [0xBB, 0xF3, 0x16, 0xE8, 0xD9, 0x40, 0xAF, 0x0A, 0xD3]);
    }

    #[test]
    fn keys_and_reset() {
        assert_eq!(INCOMING_KEY[0], 0xc9);
        assert_eq!(OUTGOING_KEY[12], 0xd9);
        let mut a = Rc4::new(&INCOMING_KEY);
        let first: Vec<u8> = (0..8).map(|_| a.next_byte()).collect();
        a.reset();
        let again: Vec<u8> = (0..8).map(|_| a.next_byte()).collect();
        assert_eq!(first, again);
        let mut b = Rc4::new(&INCOMING_KEY);
        b.skip(3);
        let mut c = Rc4::new(&INCOMING_KEY);
        c.skip(3);
        assert_eq!(b.next_byte(), c.next_byte());
    }
}
