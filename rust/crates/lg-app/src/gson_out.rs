//! Writing JSON the way the Java app's `new Gson().toJson(...)` does, byte for byte, so the files Rust keeps are the
//! Java app's: compact, fields in the class's order, null fields left out (null array elements written), strings with
//! Gson's escapes (HTML-safe: `<`, `>`, `&`, `=`, `'` as `<`...), numbers as Java writes them.

use std::fmt::Write as _;

/// One JSON value being written.
#[derive(Debug, Default)]
pub struct Out(pub String);

/// Gson's string escapes.
pub fn string(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('"');
    for c in s.chars() {
        match c {
            '"' => o.push_str("\\\""),
            '\\' => o.push_str("\\\\"),
            '\t' => o.push_str("\\t"),
            '\u{8}' => o.push_str("\\b"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            '\u{c}' => o.push_str("\\f"),
            '<' | '>' | '&' | '=' | '\'' | '\u{2028}' | '\u{2029}' => {
                let _ = write!(o, "\\u{:04x}", c as u32);
            }
            c if (c as u32) < 0x20 => {
                let _ = write!(o, "\\u{:04x}", c as u32);
            }
            c => o.push(c),
        }
    }
    o.push('"');
    o
}

/// Java's `Double.toString` (Java 19 and later: the shortest decimal that reads back the same).
pub fn double(d: f64) -> String {
    if d.is_nan() {
        return "NaN".into();
    }
    if d.is_infinite() {
        return if d > 0.0 { "Infinity".into() } else { "-Infinity".into() };
    }
    if d == 0.0 {
        return if d.is_sign_negative() { "-0.0".into() } else { "0.0".into() };
    }
    let sign = if d < 0.0 { "-" } else { "" };
    let a = d.abs();
    // Rust's shortest digits, as d.ddde±x
    let e = format!("{a:e}");
    let (mant, exp) = e.split_once('e').expect("exponent");
    let exp: i32 = exp.parse().expect("exponent");
    let digits: String = mant.chars().filter(|c| *c != '.').collect();
    if (1e-3..1e7).contains(&a) {
        let point = exp + 1; // digits before the point
        let s = if point <= 0 {
            format!("0.{}{digits}", "0".repeat((-point) as usize))
        } else if point as usize >= digits.len() {
            format!("{digits}{}.0", "0".repeat(point as usize - digits.len()))
        } else {
            format!("{}.{}", &digits[..point as usize], &digits[point as usize..])
        };
        format!("{sign}{s}")
    } else {
        let frac = if digits.len() > 1 { &digits[1..] } else { "0" };
        format!("{sign}{}.{frac}E{exp}", &digits[..1])
    }
}

/// An object being written: its fields in order.
pub struct Obj {
    s: String,
    first: bool,
}

impl Obj {
    pub fn new() -> Obj {
        Obj { s: "{".into(), first: true }
    }

    fn name(&mut self, k: &str) {
        if !self.first {
            self.s.push(',');
        }
        self.first = false;
        self.s.push_str(&string(k));
        self.s.push(':');
    }

    /// A field already written as JSON; None (Java null) leaves it out.
    pub fn raw(mut self, k: &str, v: Option<String>) -> Obj {
        if let Some(v) = v {
            self.name(k);
            self.s.push_str(&v);
        }
        self
    }

    pub fn int(self, k: &str, v: impl Into<i64>) -> Obj {
        let v: i64 = v.into();
        self.raw(k, Some(v.to_string()))
    }

    pub fn bool(self, k: &str, v: bool) -> Obj {
        self.raw(k, Some(v.to_string()))
    }

    pub fn str(self, k: &str, v: &str) -> Obj {
        self.raw(k, Some(string(v)))
    }

    pub fn opt_str(self, k: &str, v: Option<&str>) -> Obj {
        self.raw(k, v.map(string))
    }

    pub fn end(mut self) -> String {
        self.s.push('}');
        self.s
    }
}

impl Default for Obj {
    fn default() -> Self {
        Obj::new()
    }
}

/// A JSON array of values already written.
pub fn array(items: impl IntoIterator<Item = String>) -> String {
    format!("[{}]", items.into_iter().collect::<Vec<_>>().join(","))
}

pub fn ints(v: &[i32]) -> String {
    array(v.iter().map(i32::to_string))
}

pub fn longs(v: &[i64]) -> String {
    array(v.iter().map(i64::to_string))
}

/// A map: its entries in the order given, a null value leaving the entry out (Gson, nulls not serialized).
pub fn map(entries: impl IntoIterator<Item = (String, Option<String>)>) -> String {
    let mut o = Obj::new();
    for (k, v) in entries {
        o = o.raw(&k, v);
    }
    o.end()
}

/// Gson's `setPrettyPrinting()` layout of compact JSON: two spaces per level, `": "`, every element on a line of its
/// own, empty arrays and objects as `[]` and `{}`.
pub fn pretty(compact: &str) -> String {
    let b = compact.as_bytes();
    let mut o = String::with_capacity(compact.len() * 2);
    let mut depth = 0usize;
    let mut i = 0;
    let newline = |o: &mut String, depth: usize| {
        o.push('\n');
        for _ in 0..depth {
            o.push_str("  ");
        }
    };
    while i < b.len() {
        let c = b[i];
        match c {
            b'"' => {
                let start = i;
                i += 1;
                while i < b.len() && b[i] != b'"' {
                    if b[i] == b'\\' {
                        i += 1;
                    }
                    i += 1;
                }
                o.push_str(&compact[start..=i.min(b.len() - 1)]);
            }
            b'{' | b'[' => {
                let close = if c == b'{' { b'}' } else { b']' };
                if b.get(i + 1) == Some(&close) {
                    o.push(c as char);
                    o.push(close as char);
                    i += 1;
                } else {
                    o.push(c as char);
                    depth += 1;
                    newline(&mut o, depth);
                }
            }
            b'}' | b']' => {
                depth -= 1;
                newline(&mut o, depth);
                o.push(c as char);
            }
            b',' => {
                o.push(',');
                newline(&mut o, depth);
            }
            b':' => o.push_str(": "),
            _ => {
                // a number, true, false or null: up to the next structural character
                let start = i;
                while i + 1 < b.len() && !matches!(b[i + 1], b',' | b'}' | b']' | b':') {
                    i += 1;
                }
                o.push_str(&compact[start..=i]);
            }
        }
        i += 1;
    }
    o
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strings_as_gson() {
        assert_eq!(string("a\"b\\c<d>&='\n\u{1}\u{2028}é"), "\"a\\\"b\\\\c\\u003cd\\u003e\\u0026\\u003d\\u0027\\n\\u0001\\u2028é\"");
    }

    #[test]
    fn doubles_as_java() {
        let cases = [
            (1.0, "1.0"),
            (1.15, "1.15"),
            (0.001, "0.001"),
            (0.0001, "1.0E-4"),
            (1e7, "1.0E7"),
            (9_999_999.0, "9999999.0"),
            (123.456, "123.456"),
            (-2.5, "-2.5"),
            (1.2345e-5, "1.2345E-5"),
            (0.1 + 0.2, "0.30000000000000004"),
        ];
        for (d, want) in cases {
            assert_eq!(double(d), want, "{d}");
        }
    }

    #[test]
    fn pretty_as_gson() {
        assert_eq!(
            pretty(r#"{"a":1,"b":[],"c":{"d":[1,"x,y"]},"e":{}}"#),
            "{\n  \"a\": 1,\n  \"b\": [],\n  \"c\": {\n    \"d\": [\n      1,\n      \"x,y\"\n    ]\n  },\n  \"e\": {}\n}"
        );
    }

    #[test]
    fn objects_leave_nulls_out() {
        let o = Obj::new().int("a", 1).opt_str("b", None).str("c", "x").raw("d", Some(ints(&[1, -2]))).end();
        assert_eq!(o, r#"{"a":1,"c":"x","d":[1,-2]}"#);
    }
}
