//! Reading packet fields: big-endian numbers and length-prefixed strings (Java: `BufferReader`).

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Truncated {
    pub at: usize,
}

pub struct Reader<'a> {
    data: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    pub fn new(data: &'a [u8]) -> Self {
        Reader { data, at: 0 }
    }

    fn take<const N: usize>(&mut self) -> Result<[u8; N], Truncated> {
        let b = self.data.get(self.at..self.at + N).ok_or(Truncated { at: self.at })?;
        self.at += N;
        Ok(b.try_into().expect("N bytes"))
    }

    pub fn u8(&mut self) -> Result<u8, Truncated> {
        Ok(self.take::<1>()?[0])
    }

    pub fn bool(&mut self) -> Result<bool, Truncated> {
        Ok(self.u8()? != 0)
    }

    pub fn i16(&mut self) -> Result<i16, Truncated> {
        Ok(i16::from_be_bytes(self.take()?))
    }

    pub fn u16(&mut self) -> Result<u16, Truncated> {
        Ok(u16::from_be_bytes(self.take()?))
    }

    pub fn i32(&mut self) -> Result<i32, Truncated> {
        Ok(i32::from_be_bytes(self.take()?))
    }

    pub fn u32(&mut self) -> Result<u32, Truncated> {
        Ok(u32::from_be_bytes(self.take()?))
    }

    /// A string: its length (2 bytes), then UTF-8 bytes.
    pub fn string(&mut self) -> Result<String, Truncated> {
        let len = self.u16()? as usize;
        let b = self.data.get(self.at..self.at + len).ok_or(Truncated { at: self.at })?;
        self.at += len;
        Ok(String::from_utf8_lossy(b).into_owned())
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
        assert_eq!(r.u8(), Err(Truncated { at: 10 }));
    }
}
