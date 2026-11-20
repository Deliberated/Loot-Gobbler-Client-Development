//! Java's string handling where the app's parsing depends on it.

/// Thrown: where Java throws (a `NumberFormatException`, an index out of range...).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Thrown;

/// `Integer.parseInt`: an optional sign and digits, nothing else (no spaces).
pub fn parse_int(s: &str) -> Result<i32, Thrown> {
    s.parse().map_err(|_| Thrown)
}

/// `Short.parseShort`.
pub fn parse_short(s: &str) -> Result<i16, Thrown> {
    s.parse().map_err(|_| Thrown)
}

/// `Long.parseLong`.
pub fn parse_long(s: &str) -> Result<i64, Thrown> {
    s.parse().map_err(|_| Thrown)
}

/// `String.split` with one plain character: trailing empty parts dropped (a string of only separators gives none).
pub fn split(s: &str, sep: char) -> Vec<&str> {
    let mut v: Vec<&str> = s.split(sep).collect();
    while v.len() > 1 && v.last() == Some(&"") {
        v.pop();
    }
    if v == [""] && !s.is_empty() {
        v.clear();
    }
    v
}

/// `String.trim`: what is at or below a space goes from both ends.
pub fn trim(s: &str) -> &str {
    s.trim_matches(|c: char| c <= ' ')
}

/// `String.isBlank`.
pub fn is_blank(s: &str) -> bool {
    s.chars().all(char::is_whitespace)
}

/// `java.util.Properties.load`: the value of `key`, if there.
pub fn property(text: &str, key: &str) -> Option<String> {
    properties(text).into_iter().rev().find(|(k, _)| k == key).map(|(_, v)| v)
}

/// `java.util.Properties.load`: every key and value in the file's order (a key again: the later one counts).
pub fn properties(text: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut logical = String::new();
    let mut lines = text.split('\n').map(|l| l.strip_suffix('\r').unwrap_or(l)).peekable();
    while let Some(first) = lines.next() {
        let line = first.trim_start_matches([' ', '\t', '\u{c}']);
        if line.is_empty() || line.starts_with('#') || line.starts_with('!') {
            continue;
        }
        logical.clear();
        let mut cur = line.to_string();
        // a line ending in an odd number of backslashes goes on in the next one
        while cur.chars().rev().take_while(|&c| c == '\\').count() % 2 == 1 {
            cur.pop();
            logical.push_str(&cur);
            cur = lines.next().unwrap_or("").trim_start_matches([' ', '\t', '\u{c}']).to_string();
        }
        logical.push_str(&cur);
        out.push(split_property(&logical));
    }
    out
}

fn split_property(line: &str) -> (String, String) {
    let chars: Vec<char> = line.chars().collect();
    let mut i = 0;
    let mut key = String::new();
    while i < chars.len() {
        let c = chars[i];
        if c == '\\' {
            i += 1;
            continue_escape(&chars, &mut i, &mut key);
            continue;
        }
        if matches!(c, '=' | ':' | ' ' | '\t' | '\u{c}') {
            break;
        }
        key.push(c);
        i += 1;
    }
    while i < chars.len() && matches!(chars[i], ' ' | '\t' | '\u{c}') {
        i += 1;
    }
    if i < chars.len() && matches!(chars[i], '=' | ':') {
        i += 1;
    }
    while i < chars.len() && matches!(chars[i], ' ' | '\t' | '\u{c}') {
        i += 1;
    }
    let mut value = String::new();
    while i < chars.len() {
        if chars[i] == '\\' {
            i += 1;
            continue_escape(&chars, &mut i, &mut value);
        } else {
            value.push(chars[i]);
            i += 1;
        }
    }
    (key, value)
}

/// After a backslash at `i - 1`: the escaped character into `out`.
fn continue_escape(chars: &[char], i: &mut usize, out: &mut String) {
    let Some(&c) = chars.get(*i) else { return };
    *i += 1;
    match c {
        't' => out.push('\t'),
        'n' => out.push('\n'),
        'r' => out.push('\r'),
        'f' => out.push('\u{c}'),
        'u' => {
            let hex: String = chars.get(*i..*i + 4).map(|h| h.iter().collect()).unwrap_or_default();
            if let Some(ch) = u32::from_str_radix(&hex, 16).ok().and_then(char::from_u32) {
                out.push(ch);
                *i += 4;
            }
        }
        other => out.push(other),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn as_java() {
        assert_eq!((parse_int("+5"), parse_int("-0"), parse_int(" 5"), parse_int("")), (Ok(5), Ok(0), Err(Thrown), Err(Thrown)));
        assert_eq!(parse_short("40000"), Err(Thrown));
        assert_eq!(split("a,,b,,", ','), ["a", "", "b"]);
        assert_eq!(split(",,", ','), Vec::<&str>::new());
        assert_eq!(split("", ','), [""]);
        assert_eq!(trim("\t x \n"), "x");
        let props = "# c\nplayerName=Te\\u0073ter\nother : x\\\n  y\n";
        assert_eq!(property(props, "playerName").as_deref(), Some("Tester"));
        assert_eq!(property(props, "other").as_deref(), Some("xy"));
        assert_eq!(property(props, "none"), None);
    }
}

/// `java.util.Random`: the same numbers from the same seed.
#[derive(Debug, Clone)]
pub struct JavaRandom {
    seed: i64,
}

impl JavaRandom {
    const MULT: i64 = 0x5_DEEC_E66D;
    const MASK: i64 = (1 << 48) - 1;

    pub fn new(seed: i64) -> JavaRandom {
        JavaRandom { seed: (seed ^ Self::MULT) & Self::MASK }
    }

    fn next(&mut self, bits: u32) -> i32 {
        self.seed = (self.seed.wrapping_mul(Self::MULT).wrapping_add(0xB)) & Self::MASK;
        (self.seed >> (48 - bits)) as i32
    }

    /// `nextInt(bound)`.
    pub fn next_int(&mut self, bound: i32) -> i32 {
        let mut r = self.next(31);
        let m = bound - 1;
        if bound & m == 0 {
            return ((i64::from(bound) * i64::from(r)) >> 31) as i32;
        }
        let mut u = r;
        loop {
            r = u % bound;
            if u.wrapping_sub(r).wrapping_add(m) >= 0 {
                return r;
            }
            u = self.next(31);
        }
    }
}

/// `Collections.shuffle(list, random)`.
pub fn shuffle<T>(v: &mut [T], rnd: &mut JavaRandom) {
    for i in (2..=v.len()).rev() {
        let j = rnd.next_int(i as i32) as usize;
        v.swap(i - 1, j);
    }
}

#[cfg(test)]
mod random_tests {
    use super::*;

    /// Numbers Java 25 gives: new Random(42).nextInt(10) x5, and Collections.shuffle of 0..9 with new Random(504).
    #[test]
    fn as_java() {
        let mut r = JavaRandom::new(42);
        let got: Vec<i32> = (0..5).map(|_| r.next_int(10)).collect();
        assert_eq!(got, [0, 3, 8, 4, 0]);
        let mut v: Vec<i32> = (0..10).collect();
        shuffle(&mut v, &mut JavaRandom::new(504));
        assert_eq!(v, [1, 8, 9, 4, 3, 2, 6, 0, 5, 7]);
    }
}
