//! Reading packet fields: big-endian numbers, length-prefixed strings and lists, the game's compressed ints
//! (Java: `BufferReader`).
//!
//! Where Java would throw (too few bytes, a negative length), these return a [`DecodeError`]; the Java client then
//! drops the packet, and so does the Rust one.

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DecodeError {
    /// Where in the packet's content reading stopped.
    pub at: usize,
    pub why: &'static str,
}

impl std::fmt::Display for DecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{} at byte {}", self.why, self.at)
    }
}

pub type Result<T> = std::result::Result<T, DecodeError>;

pub struct Reader<'a> {
    data: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Reader { data, at: 0 }
    }

    pub fn fail(&self, why: &'static str) -> DecodeError {
        DecodeError { at: self.at, why }
    }

    pub fn bytes(&mut self, n: usize) -> Result<&'a [u8]> {
        let b = self.data.get(self.at..self.at + n).ok_or(self.fail("cut off"))?;
        self.at += n;
        Ok(b)
    }

    fn take<const N: usize>(&mut self) -> Result<[u8; N]> {
        Ok(self.bytes(N)?.try_into().expect("N bytes"))
    }

    pub fn u8(&mut self) -> Result<u8> {
        Ok(self.take::<1>()?[0])
    }

    pub fn i8(&mut self) -> Result<i8> {
        Ok(self.u8()? as i8)
    }

    pub fn bool(&mut self) -> Result<bool> {
        Ok(self.u8()? != 0)
    }

    pub fn i16(&mut self) -> Result<i16> {
        Ok(i16::from_be_bytes(self.take()?))
    }

    pub fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_be_bytes(self.take()?))
    }

    pub fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_be_bytes(self.take()?))
    }

    pub fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.take()?))
    }

    pub fn f32(&mut self) -> Result<f32> {
        Ok(f32::from_be_bytes(self.take()?))
    }

    /// The game's variable-length int: 6 bits and a sign in the first byte, 7 bits in each further one. Overlong
    /// values wrap as in Java (`<<` there only uses the low 5 bits of the shift).
    pub fn compressed_int(&mut self) -> Result<i32> {
        let mut b = self.u8()?;
        let negative = b & 64 != 0;
        let mut shift = 6u32;
        let mut value = i32::from(b & 63);
        while b & 128 != 0 {
            b = self.u8()?;
            value |= i32::from(b & 127).wrapping_shl(shift);
            shift += 7;
        }
        Ok(if negative { value.wrapping_neg() } else { value })
    }

    /// A length that Java reads as a signed number: negative fails there, so it fails here.
    fn length(&self, n: i32) -> Result<usize> {
        usize::try_from(n).map_err(|_| self.fail("negative length"))
    }

    /// A string: its length (2 bytes, signed), then UTF-8 bytes.
    pub fn string(&mut self) -> Result<String> {
        let n = self.i16()?;
        let len = self.length(n.into())?;
        Ok(String::from_utf8_lossy(self.bytes(len)?).into_owned())
    }

    /// Bytes with a 2-byte length in front (Java: `readByteArray`).
    pub fn byte_array(&mut self) -> Result<Vec<u8>> {
        let n = self.i16()?;
        let len = self.length(n.into())?;
        Ok(self.bytes(len)?.to_vec())
    }

    /// `n` items, each read by `item`. Never reserves more than the bytes left could hold.
    pub fn list<T>(&mut self, n: i32, mut item: impl FnMut(&mut Self) -> Result<T>) -> Result<Vec<T>> {
        let n = self.length(n)?;
        let mut v = Vec::with_capacity(n.min(self.remaining()));
        for _ in 0..n {
            v.push(item(self)?);
        }
        Ok(v)
    }

    /// A list with a 2-byte count in front.
    pub fn list16<T>(&mut self, item: impl FnMut(&mut Self) -> Result<T>) -> Result<Vec<T>> {
        let n = self.i16()?;
        self.list(n.into(), item)
    }

    /// A list with a compressed-int count in front.
    pub fn list_compressed<T>(&mut self, item: impl FnMut(&mut Self) -> Result<T>) -> Result<Vec<T>> {
        let n = self.compressed_int()?;
        self.list(n, item)
    }

    pub fn remaining(&self) -> usize {
        self.data.len() - self.at
    }

    /// Whether every byte was read (Java: `isBufferFullyParsed`).
    pub fn done(&self) -> bool {
        self.at == self.data.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_fields_in_order() {
        let mut b = vec![0, 3, b'a', b'b', b'c'];
        b.extend((-2i32).to_be_bytes());
        b.push(1);
        let mut r = Reader::new(&b);
        assert_eq!(r.string().unwrap(), "abc");
        assert_eq!(r.i32().unwrap(), -2);
        assert!(r.bool().unwrap());
        assert!(r.done());
        assert_eq!(r.u8().unwrap_err().at, 10);
    }

    #[test]
    fn compressed_ints() {
        let read = |b: &[u8]| Reader::new(b).compressed_int().unwrap();
        assert_eq!(read(&[5]), 5);
        assert_eq!(read(&[0x40 | 5]), -5);
        assert_eq!(read(&[0x80 | 1, 2]), 1 | 2 << 6);
        assert_eq!(read(&[0x80 | 0x40 | 63, 127]), -(63 | 127 << 6));
        // Java: shifts of 34 and more wrap around instead of dropping the bits
        assert_eq!(read(&[0x80, 0x80, 0x80, 0x80, 0x80, 1]), 1 << (34 % 32));
    }

    #[test]
    fn negative_lengths_fail() {
        assert_eq!(Reader::new(&[0xff, 0xff]).string().unwrap_err().why, "negative length");
        assert_eq!(Reader::new(&[0x80, 0]).list16(|r| r.u8()).unwrap_err().why, "negative length");
        // a count far beyond the bytes left fails without reserving room for it
        assert_eq!(Reader::new(&[0x7f, 0xff]).list16(|r| r.i32()).unwrap_err().why, "cut off");
    }
}
