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

    /// `nextDouble()`.
    pub fn next_double(&mut self) -> f64 {
        let hi = i64::from(self.next(26)) << 27;
        (hi + i64::from(self.next(27))) as f64 * (1.0 / (1u64 << 53) as f64)
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

    #[test]
    fn next_double_as_java() {
        assert_eq!(JavaRandom::new(42).next_double(), 0.7275636800328681);
    }

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

fn hi(x: f64) -> i32 {
    (x.to_bits() >> 32) as i32
}

fn with_hi(x: f64, hi: i32) -> f64 {
    f64::from_bits((u64::from(hi as u32) << 32) | (x.to_bits() & 0xffff_ffff))
}

fn lo(x: f64) -> u32 {
    x.to_bits() as u32
}

/// Java `Math.hypot` (`StrictMath.hypot`, fdlibm's `e_hypot.c`): bit for bit what the Java app gets; the C
/// library's `hypot` behind `f64::hypot` can differ in the last bit.
pub fn hypot(x: f64, y: f64) -> f64 {
    let (mut ha, mut hb) = (hi(x) & 0x7fff_ffff, hi(y) & 0x7fff_ffff);
    let (mut a, mut b) = if hb > ha {
        std::mem::swap(&mut ha, &mut hb);
        (y, x)
    } else {
        (x, y)
    };
    a = with_hi(a, ha); // |a|
    b = with_hi(b, hb); // |b|
    if ha - hb > 0x3c0_0000 {
        return a + b; // a/b > 2**60
    }
    let mut k = 0;
    if ha > 0x5f30_0000 {
        // a > 2**500
        if ha >= 0x7ff0_0000 {
            // Inf or NaN
            let mut w = a + b;
            if ((ha & 0xf_ffff) as u32 | lo(a)) == 0 {
                w = a;
            }
            if (((hb ^ 0x7ff0_0000) as u32) | lo(b)) == 0 {
                w = b;
            }
            return w;
        }
        // scale a and b by 2**-600
        ha -= 0x2580_0000;
        hb -= 0x2580_0000;
        k += 600;
        a = with_hi(a, ha);
        b = with_hi(b, hb);
    }
    if hb < 0x20b0_0000 {
        // b < 2**-500
        if hb <= 0x000f_ffff {
            // subnormal b or 0
            if (hb as u32 | lo(b)) == 0 {
                return a;
            }
            let t1 = with_hi(0.0, 0x7fd0_0000); // 2^1022
            b *= t1;
            a *= t1;
            k -= 1022;
        } else {
            // scale a and b by 2^600
            ha += 0x2580_0000;
            hb += 0x2580_0000;
            k -= 600;
            a = with_hi(a, ha);
            b = with_hi(b, hb);
        }
    }
    // medium size a and b
    let mut w = a - b;
    if w > b {
        let t1 = with_hi(0.0, ha);
        let t2 = a - t1;
        w = (t1 * t1 - (b * (-b) - t2 * (a + t1))).sqrt();
    } else {
        a += a;
        let y1 = with_hi(0.0, hb);
        let y2 = b - y1;
        let t1 = with_hi(0.0, ha + 0x0010_0000);
        let t2 = a - t1;
        w = (t1 * y1 - (w * (-w) - (t1 * y2 + t2 * b))).sqrt();
    }
    if k != 0 { with_hi(1.0, hi(1.0) + (k << 20)) * w } else { w }
}

#[cfg(test)]
mod hypot_check {
    /// Pairs (as Java's float bits) where the C library's hypot rounds differently from Java's.
    #[test]
    fn as_java() {
        for (x, y, want) in [
            (-1019370814, 1016150166, 0x4067b45841bcd5b8),
            (1026470339, -1080020421, 0x3ff409412c2e2c36),
            (-1001439919, 1059732255, 0x4089e7aaabd4cf60),
            (-1004247834, -1067265918, 0x40848cb04eefba9a),
        ] {
            let (x, y) = (f64::from(f32::from_bits(x as u32)), f64::from(f32::from_bits(y as u32)));
            assert_eq!(super::hypot(x, y).to_bits(), want);
        }
        assert_eq!(super::hypot(3.0, 4.0), 5.0);
        assert!(super::hypot(f64::NAN, f64::INFINITY).is_infinite());
    }

    /// LG_HYPOT=<file of "xbits ybits javahex" lines from Java>: every one the same.
    #[test]
    fn against_java_file() {
        let Ok(f) = std::env::var("LG_HYPOT") else { return };
        let (mut same, mut libm_diff, mut bad) = (0, 0, 0);
        for l in std::fs::read_to_string(f).unwrap().lines() {
            let v: Vec<&str> = l.split(' ').collect();
            let x = f64::from(f32::from_bits(v[0].parse::<i32>().unwrap() as u32));
            let y = f64::from(f32::from_bits(v[1].parse::<i32>().unwrap() as u32));
            let want = u64::from_str_radix(v[2], 16).unwrap();
            if super::hypot(x, y).to_bits() == want {
                same += 1
            } else {
                bad += 1
            }
            if x.hypot(y).to_bits() != want {
                libm_diff += 1
            }
        }
        eprintln!("same {same}, different {bad}; f64::hypot differs on {libm_diff}");
        assert_eq!(bad, 0);
    }
}
