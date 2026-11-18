//! Dye and cloth items' textures, from `xml/dyes.xml` and `xml/textiles.xml` (Java: `Dyes.texture`).

use std::collections::HashMap;
use std::path::Path;

/// Java's `OBJECT` regex: `<Object type="(0x[0-9a-fA-F]+)"[^>]*>(.*?)</Object>`, then the first
/// `<Tex1>`/`<Tex2>` with a hex value inside. Where Java throws (a number too big), the rest of that file is not read.
fn read(s: &str, out: &mut HashMap<i32, i32>) {
    let mut at = 0;
    while let Some(i) = s[at..].find("<Object type=\"0x") {
        let start = at + i;
        at = start + 1;
        let v = &s[start + 16..];
        let hex = v.chars().take_while(char::is_ascii_hexdigit).count();
        if hex == 0 || !v[hex..].starts_with('"') {
            continue;
        }
        let rest = &v[hex + 1..];
        let Some(gt) = rest.find('>') else { continue };
        let body_start = start + 16 + hex + 1 + gt + 1;
        let Some(close) = s[body_start..].find("</Object>") else { continue };
        let body = &s[body_start..body_start + close];
        at = body_start + close + 9;
        if let Some(tex) = first_tex(body) {
            let Ok(kind) = i64::from_str_radix(&v[..hex], 16) else { return };
            let Ok(kind) = i32::try_from(kind) else { return }; // Integer.decode: too big throws
            let Ok(t) = u64::from_str_radix(tex, 16) else { return }; // Long.parseLong: more than 64 bits throws
            if t > i64::MAX as u64 {
                return;
            }
            out.insert(kind, t as i64 as i32);
        }
    }
}

/// The first `<Tex1>0x..</Tex1>` or `<Tex2>0x..</Tex2>`: its hex digits.
fn first_tex(body: &str) -> Option<&str> {
    let mut at = 0;
    while let Some(i) = body[at..].find("<Tex") {
        let p = at + i;
        at = p + 1;
        let r = &body[p + 4..];
        if !r.starts_with('1') && !r.starts_with('2') {
            continue;
        }
        let Some(v) = r[1..].strip_prefix(">0x") else { continue };
        let hex = v.chars().take_while(char::is_ascii_hexdigit).count();
        if hex == 0 || !(v[hex..].starts_with("</Tex1>") || v[hex..].starts_with("</Tex2>")) {
            continue;
        }
        return Some(&v[..hex]);
    }
    None
}

pub fn load(xml_dir: &Path) -> HashMap<i32, i32> {
    let mut out = HashMap::new();
    for f in ["dyes.xml", "textiles.xml"] {
        if let Ok(b) = std::fs::read(xml_dir.join(f))
            && let Ok(s) = String::from_utf8(b)
        {
            read(&s, &mut out);
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn textures() {
        let mut m = HashMap::new();
        read(
            r#"<Objects><Object type="0x1001" id="Red Dye"><Tex1>0x1ff0000</Tex1></Object>
<Object type="0x1002" id="Cloth"><Tex2>0x4000012</Tex2><Tex1>0x9</Tex1></Object><Object type="0x1003"/></Objects>"#,
            &mut m,
        );
        assert_eq!((m[&0x1001], m[&0x1002]), (0x1ff0000, 0x4000012));
        assert!(!m.contains_key(&0x1003));
    }
}
