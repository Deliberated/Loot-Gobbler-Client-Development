//! Just enough JSON for the crucible settings the game sends, read as the Java client reads them with Gson: object keys
//! in their order (a repeated key replaces the value in place), numbers kept as written, and Gson's `getAsInt` /
//! `getAsDouble` / `getAsString` rules, including where they throw. Standard JSON only: Gson's lenient extras (comments,
//! single quotes...) are refused, and the game doesn't use them.

#[derive(Debug, Clone, PartialEq)]
pub enum Json {
    Null,
    Bool(bool),
    /// As written.
    Number(String),
    String(String),
    Array(Vec<Json>),
    Object(Vec<(String, Json)>),
}

/// Where Gson would throw.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Thrown;

impl Json {
    pub fn get(&self, key: &str) -> Option<&Json> {
        match self {
            Json::Object(o) => o.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    /// Gson `getAsString`.
    pub fn as_string(&self) -> Result<String, Thrown> {
        match self {
            Json::String(s) | Json::Number(s) => Ok(s.clone()),
            Json::Bool(b) => Ok(b.to_string()),
            Json::Array(a) if a.len() == 1 => a[0].as_string(),
            _ => Err(Thrown),
        }
    }

    /// Gson `getAsInt`: a number's integer part, a string parsed as an int.
    pub fn as_int(&self) -> Result<i32, Thrown> {
        match self {
            Json::Number(n) => {
                if let Ok(v) = n.parse::<i32>() {
                    Ok(v)
                } else if let Ok(v) = n.parse::<i64>() {
                    Ok(v as i32)
                } else {
                    // BigDecimal(value).intValue(): the integer part, wrapped
                    let f: f64 = n.parse().map_err(|_| Thrown)?;
                    Ok(f.trunc() as i64 as i32)
                }
            }
            Json::String(s) => s.parse().map_err(|_| Thrown),
            Json::Array(a) if a.len() == 1 => a[0].as_int(),
            _ => Err(Thrown),
        }
    }

    /// Gson `getAsDouble`.
    pub fn as_double(&self) -> Result<f64, Thrown> {
        match self {
            Json::Number(s) => s.parse().map_err(|_| Thrown),
            Json::String(s) => s.trim_matches(|c: char| c <= ' ').trim_end_matches(['d', 'D', 'f', 'F']).parse().map_err(|_| Thrown),
            Json::Array(a) if a.len() == 1 => a[0].as_double(),
            _ => Err(Thrown),
        }
    }
}

pub fn parse(s: &str) -> Result<Json, String> {
    let mut p = P { s: s.as_bytes(), at: 0, src: s };
    let v = p.value()?;
    p.ws();
    if p.at != p.s.len() {
        return Err(p.err("text after the value"));
    }
    Ok(v)
}

struct P<'a> {
    s: &'a [u8],
    at: usize,
    src: &'a str,
}

impl P<'_> {
    fn err(&self, what: &str) -> String {
        format!("{what} at byte {}", self.at)
    }

    fn ws(&mut self) {
        while self.at < self.s.len() && matches!(self.s[self.at], b' ' | b'\t' | b'\n' | b'\r') {
            self.at += 1;
        }
    }

    fn eat(&mut self, c: u8) -> bool {
        self.ws();
        if self.s.get(self.at) == Some(&c) {
            self.at += 1;
            true
        } else {
            false
        }
    }

    fn value(&mut self) -> Result<Json, String> {
        self.ws();
        match self.s.get(self.at) {
            Some(b'{') => {
                self.at += 1;
                let mut o: Vec<(String, Json)> = Vec::new();
                if self.eat(b'}') {
                    return Ok(Json::Object(o));
                }
                loop {
                    self.ws();
                    let k = self.string()?;
                    if !self.eat(b':') {
                        return Err(self.err("expected :"));
                    }
                    let v = self.value()?;
                    match o.iter_mut().find(|(key, _)| *key == k) {
                        Some(slot) => slot.1 = v,
                        None => o.push((k, v)),
                    }
                    if self.eat(b',') {
                        continue;
                    }
                    if self.eat(b'}') {
                        return Ok(Json::Object(o));
                    }
                    return Err(self.err("expected , or }"));
                }
            }
            Some(b'[') => {
                self.at += 1;
                let mut a = Vec::new();
                if self.eat(b']') {
                    return Ok(Json::Array(a));
                }
                loop {
                    a.push(self.value()?);
                    if self.eat(b',') {
                        continue;
                    }
                    if self.eat(b']') {
                        return Ok(Json::Array(a));
                    }
                    return Err(self.err("expected , or ]"));
                }
            }
            Some(b'"') => Ok(Json::String(self.string()?)),
            Some(b't') if self.s[self.at..].starts_with(b"true") => {
                self.at += 4;
                Ok(Json::Bool(true))
            }
            Some(b'f') if self.s[self.at..].starts_with(b"false") => {
                self.at += 5;
                Ok(Json::Bool(false))
            }
            Some(b'n') if self.s[self.at..].starts_with(b"null") => {
                self.at += 4;
                Ok(Json::Null)
            }
            Some(c) if *c == b'-' || c.is_ascii_digit() => {
                let start = self.at;
                while self.at < self.s.len() && matches!(self.s[self.at], b'-' | b'+' | b'.' | b'e' | b'E' | b'0'..=b'9') {
                    self.at += 1;
                }
                let n = &self.src[start..self.at];
                n.parse::<f64>().map_err(|_| self.err("not a number"))?;
                Ok(Json::Number(n.to_string()))
            }
            _ => Err(self.err("expected a value")),
        }
    }

    fn string(&mut self) -> Result<String, String> {
        if self.s.get(self.at) != Some(&b'"') {
            return Err(self.err("expected a string"));
        }
        self.at += 1;
        let mut out = String::new();
        let mut units: Vec<u16> = Vec::new();
        loop {
            let start = self.at;
            while self.at < self.s.len() && self.s[self.at] != b'"' && self.s[self.at] != b'\\' {
                self.at += 1;
            }
            out.push_str(&self.src[start..self.at]);
            match self.s.get(self.at) {
                Some(b'"') => {
                    self.at += 1;
                    return Ok(out);
                }
                Some(b'\\') => {
                    let e = *self.s.get(self.at + 1).ok_or_else(|| self.err("cut off"))?;
                    self.at += 2;
                    match e {
                        b'"' => out.push('"'),
                        b'\\' => out.push('\\'),
                        b'/' => out.push('/'),
                        b'b' => out.push('\u{8}'),
                        b'f' => out.push('\u{c}'),
                        b'n' => out.push('\n'),
                        b'r' => out.push('\r'),
                        b't' => out.push('\t'),
                        b'u' => {
                            let h = self.src.get(self.at..self.at + 4).ok_or_else(|| self.err("cut off"))?;
                            let u = u16::from_str_radix(h, 16).map_err(|_| self.err("bad \\u"))?;
                            self.at += 4;
                            units.push(u);
                            // a surrogate pair comes as two escapes
                            if !(0xD800..0xDC00).contains(&u) || units.len() == 2 {
                                out.push_str(&String::from_utf16_lossy(&units));
                                units.clear();
                            }
                            continue;
                        }
                        _ => return Err(self.err("bad escape")),
                    }
                    if !units.is_empty() {
                        out.push_str(&String::from_utf16_lossy(&units));
                        units.clear();
                    }
                }
                _ => return Err(self.err("string not closed")),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn values_and_gson_access() {
        let j = parse(r#"[{"array":[{"id":12,"bonuses":[{"type":"5","amount":1.25}]}],"a":1,"a":2,"s":"xé\n"}]"#).unwrap();
        let Json::Array(top) = &j else { panic!() };
        let o = &top[0];
        assert_eq!(o.get("a"), Some(&Json::Number("2".into())), "a repeated key replaces the value");
        assert_eq!(o.get("s").unwrap().as_string().unwrap(), "xé\n");
        let e = &o.get("array").unwrap();
        let Json::Array(items) = e else { panic!() };
        assert_eq!(items[0].get("id").unwrap().as_string().unwrap(), "12");
        let b = &items[0].get("bonuses").unwrap();
        let Json::Array(bs) = b else { panic!() };
        assert_eq!(bs[0].get("type").unwrap().as_int(), Ok(5));
        assert_eq!(bs[0].get("amount").unwrap().as_double(), Ok(1.25));
        assert_eq!(Json::Number("5.9".into()).as_int(), Ok(5));
        assert_eq!(Json::Bool(true).as_int(), Err(Thrown));
        assert!(parse("[1,]").is_err() && parse("{'a':1}").is_err());
    }
}
