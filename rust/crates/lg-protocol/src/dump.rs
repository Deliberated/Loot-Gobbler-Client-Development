//! Every field of a decoded packet as one line of text, in the format `oracle/ReplayOracle.java --fields` writes for
//! Java's decoded packets, so the two can be compared field by field.
//!
//! - a record: `{name=value,...}`, names lower case without `_` (Java's `serverLastTimeRTTMS` and Rust's
//!   `server_last_time_rtt_ms` both become `serverlasttimerttms`), sorted by that name
//! - numbers in decimal, `true`/`false`, floats as `f` and their 8 hex bits, strings in quotes (`\` `"` and
//!   characters outside printable ASCII as `\u{hex}`), byte arrays as `x` and hex, lists as `[a,b]`, missing as `null`,
//!   enum values by their Java name
//! - fields that could hold a login secret (a name containing "token", or "key") only as `#` and the CRC-32 of their
//!   text, so the oracle's output can be shown and diffed without giving the token away

pub trait Dump {
    fn dump(&self, out: &mut String);
}

pub fn to_string(v: &impl Dump) -> String {
    let mut s = String::new();
    v.dump(&mut s);
    s
}

macro_rules! dump_number {
    ($($t:ty),*) => {$(
        impl Dump for $t {
            fn dump(&self, out: &mut String) {
                out.push_str(&self.to_string());
            }
        }
    )*};
}
dump_number!(i8, i16, i32, i64, u8, u16, u32, bool);

impl Dump for f32 {
    fn dump(&self, out: &mut String) {
        out.push_str(&format!("f{:08x}", self.to_bits()));
    }
}

impl Dump for String {
    fn dump(&self, out: &mut String) {
        out.push('"');
        for c in self.chars() {
            match c {
                '\\' | '"' => {
                    out.push('\\');
                    out.push(c);
                }
                ' '..='~' => out.push(c),
                _ => out.push_str(&format!("\\u{{{:x}}}", c as u32)),
            }
        }
        out.push('"');
    }
}

impl<T: Dump> Dump for Option<T> {
    fn dump(&self, out: &mut String) {
        match self {
            Some(v) => v.dump(out),
            None => out.push_str("null"),
        }
    }
}

impl<T: Dump> Dump for Vec<T> {
    fn dump(&self, out: &mut String) {
        out.push('[');
        for (i, v) in self.iter().enumerate() {
            if i > 0 {
                out.push(',');
            }
            v.dump(out);
        }
        out.push(']');
    }
}

/// Raw bytes (Java `byte[]`).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Bytes(pub Vec<u8>);

impl Dump for Bytes {
    fn dump(&self, out: &mut String) {
        out.push('x');
        for b in &self.0 {
            out.push_str(&format!("{b:02x}"));
        }
    }
}

/// A record's fields: `(Rust field name, its dumped value)`, in any order.
pub fn record(out: &mut String, fields: Vec<(&str, String)>) {
    let mut fields: Vec<(String, String)> = fields
        .into_iter()
        .map(|(name, value)| {
            let name: String = name.trim_start_matches("r#").chars().filter(|c| *c != '_').flat_map(char::to_lowercase).collect();
            let value = if secret(&name) { format!("#{:08x}", crc32(value.as_bytes())) } else { value };
            (name, value)
        })
        .collect();
    fields.sort();
    out.push('{');
    for (i, (name, value)) in fields.iter().enumerate() {
        if i > 0 {
            out.push(',');
        }
        out.push_str(name);
        out.push('=');
        out.push_str(value);
    }
    out.push('}');
}

fn secret(name: &str) -> bool {
    name.contains("token") || name == "key"
}

/// CRC-32 (IEEE), as `java.util.zip.CRC32`.
pub fn crc32(data: &[u8]) -> u32 {
    let mut c = !0u32;
    for &b in data {
        c ^= u32::from(b);
        for _ in 0..8 {
            c = if c & 1 != 0 { 0xEDB8_8320 ^ (c >> 1) } else { c >> 1 };
        }
    }
    !c
}

/// Implements [`Dump`] for a struct from its field names.
#[macro_export]
macro_rules! dump_record {
    ($t:ty { $($f:ident),* $(,)? }) => {
        impl $crate::dump::Dump for $t {
            fn dump(&self, out: &mut String) {
                $crate::dump::record(out, vec![$((stringify!($f), $crate::dump::to_string(&self.$f))),*]);
            }
        }
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    struct R {
        tick_id: i32,
        server_last_time_rtt_ms: u16,
        access_token: String,
        pos: Option<f32>,
    }
    dump_record!(R { tick_id, server_last_time_rtt_ms, access_token, pos });

    #[test]
    fn format() {
        let r = R { tick_id: -3, server_last_time_rtt_ms: 9, access_token: "secret".into(), pos: Some(1.0) };
        let tok = format!("#{:08x}", crc32(b"\"secret\""));
        assert_eq!(to_string(&r), format!("{{accesstoken={tok},pos=f3f800000,serverlasttimerttms=9,tickid=-3}}"));
        assert_eq!(to_string(&"a\"é\n".to_string()), r#""a\"\u{e9}\u{a}""#);
        assert_eq!(to_string(&vec![Bytes(vec![1, 0xab])]), "[x01ab]");
        assert_eq!(to_string(&None::<i32>), "null");
    }

    #[test]
    fn crc32_matches_java() {
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    }
}
