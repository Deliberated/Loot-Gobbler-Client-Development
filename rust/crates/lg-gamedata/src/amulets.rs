//! The amulets that save you (Java: `Amulets`): rings named "Amulet ..." in `xml/equip.xml` whose on-hit effect only
//! fires below a share of your life and makes you invulnerable (the Amulet of Restoration and its shiny version).
//!
//! Java finds them with regular expressions over the file's text; they are followed here by hand, match for match:
//! `<Object type="(0x[0-9a-fA-F]+)" id="([^"]+)"[^>]*>(.*?)</Object>` (dot matching newlines), in it
//! `<OnPlayerHitActivate([^>]*hpMinThresholdRelative="([0-9.]+)"[^>]*)>([^<]*)</OnPlayerHitActivate>` and
//! `<DisplayId>([^<]+)</DisplayId>`, and in the trigger's attributes `cooldown="([0-9]+)"` and `effect="([^"]+)"`.

use std::collections::HashMap;
use std::path::Path;

#[derive(Debug, Clone, PartialEq)]
pub struct Amulet {
    pub item: i32,
    pub name: String,
    pub threshold: f64,
    pub effect: String,
    pub cooldown_s: i32,
}

impl Amulet {
    /// The save makes you invulnerable (seen in your status), rather than healing you.
    pub fn invulnerable(&self) -> bool {
        self.effect.contains("Invulnerable")
    }
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Amulets {
    pub by_item: HashMap<i32, Amulet>,
    /// The plain Amulet of Restoration's item id, or -1.
    pub restoration: i32,
}

/// `[^c]+` (or `*` with `min` 0) from `at`: where it ends.
fn run_not(s: &str, at: usize, stop: &[u8], min: usize) -> Option<usize> {
    let n = s.as_bytes()[at..].iter().take_while(|b| !stop.contains(b)).count();
    (n >= min).then_some(at + n)
}

/// `<Object type="(0x[0-9a-fA-F]+)" id="([^"]+)"[^>]*>(.*?)</Object>` at `at`: (type, id, body, end).
fn object_at(s: &str, at: usize) -> Option<(&str, &str, &str, usize)> {
    let r = s.get(at..)?.strip_prefix("<Object type=\"0x")?;
    let mut p = s.len() - r.len();
    let hex_start = p - 2;
    let hex = r.bytes().take_while(u8::is_ascii_hexdigit).count();
    if hex == 0 {
        return None;
    }
    p += hex;
    let kind = &s[hex_start..p];
    let r = s[p..].strip_prefix("\" id=\"")?;
    p = s.len() - r.len();
    let id_end = run_not(s, p, b"\"", 1)?;
    let id = &s[p..id_end];
    p = id_end + 1; // the closing quote
    p = run_not(s, p, b">", 0)?;
    if p >= s.len() {
        return None;
    }
    p += 1;
    let close = s[p..].find("</Object>")?;
    Some((kind, id, &s[p..p + close], p + close + "</Object>".len()))
}

/// Every match of the object pattern, left to right.
fn objects(s: &str) -> Vec<(&str, &str, &str)> {
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(i) = s[from..].find("<Object type=\"") {
        let at = from + i;
        match object_at(s, at) {
            Some((k, id, body, end)) => {
                out.push((k, id, body));
                from = end;
            }
            None => from = at + 1,
        }
    }
    out
}

/// The trigger pattern's first match in an object's body: (attributes, threshold, text).
fn trigger(body: &str) -> Option<(&str, &str, &str)> {
    const OPEN: &str = "<OnPlayerHitActivate";
    const KEY: &str = "hpMinThresholdRelative=\"";
    const CLOSE: &str = "</OnPlayerHitActivate>";
    let mut from = 0;
    while let Some(i) = body[from..].find(OPEN) {
        let start = from + i + OPEN.len();
        let tag_end = run_not(body, start, b">", 0)?; // [^>]* then '>' (none: no match from here on)
        let attrs = &body[start..tag_end];
        // greedy [^>]*: the last place in the tag where the threshold fits
        let mut found = None;
        let mut k = attrs.len();
        while let Some(j) = attrs[..k].rfind(KEY) {
            let v = j + KEY.len();
            let digits = attrs[v..].bytes().take_while(|b| b.is_ascii_digit() || *b == b'.').count();
            if digits > 0 && attrs.as_bytes().get(v + digits) == Some(&b'"') {
                found = Some(&attrs[v..v + digits]);
                break;
            }
            k = j;
        }
        if tag_end < body.len()
            && let Some(threshold) = found
        {
            let text_start = tag_end + 1;
            let text_end = run_not(body, text_start, b"<", 0)?;
            if body[text_end..].starts_with(CLOSE) {
                return Some((attrs, threshold, &body[text_start..text_end]));
            }
        }
        from = from + i + 1;
    }
    None
}

/// `name="([^"]+)"` with `allowed` bytes in the value (None: anything but a quote): the first match's value.
fn attribute<'a>(s: &'a str, name: &str, allowed: Option<fn(&u8) -> bool>) -> Option<&'a str> {
    let key = format!("{name}=\"");
    let mut from = 0;
    while let Some(i) = s[from..].find(&key) {
        let v = from + i + key.len();
        let n = s.as_bytes()[v..].iter().take_while(|b| **b != b'"' && allowed.is_none_or(|f| f(b))).count();
        if n > 0 && s.as_bytes().get(v + n) == Some(&b'"') {
            return Some(&s[v..v + n]);
        }
        from = from + i + 1;
    }
    None
}

/// `<DisplayId>([^<]+)</DisplayId>`'s first match.
fn display_id(body: &str) -> Option<&str> {
    let mut from = 0;
    while let Some(i) = body[from..].find("<DisplayId>") {
        let v = from + i + "<DisplayId>".len();
        let n = body.as_bytes()[v..].iter().take_while(|b| **b != b'<').count();
        if n > 0 && body[v + n..].starts_with("</DisplayId>") {
            return Some(&body[v..v + n]);
        }
        from = from + i + 1;
    }
    None
}

impl Amulets {
    /// Reads them from an `assets/xml` folder. Java stops at the first value it cannot read (a threshold, type or
    /// cooldown out of range), keeping those before.
    pub fn load(xml_dir: &Path) -> Amulets {
        let mut a = Amulets { restoration: -1, ..Default::default() };
        let Ok(text) = std::fs::read_to_string(xml_dir.join("equip.xml")) else { return a };
        for (kind, id, body) in objects(&text) {
            let Some((attrs, threshold, inner)) = trigger(body) else { continue };
            let name = display_id(body).unwrap_or(id);
            if !format!("{name} {id}").to_lowercase().contains("amulet") {
                continue;
            }
            let effect = attribute(attrs, "effect", None).unwrap_or(inner.trim_matches(|c: char| c <= ' '));
            if !effect.contains("Invulnerable") {
                continue;
            }
            let Some(item) = i64::from_str_radix(&kind[2..], 16).ok().and_then(|v| i32::try_from(v).ok()) else { break };
            if id == "Amulet of Restoration" {
                a.restoration = item;
            }
            let Ok(threshold) = threshold.parse::<f64>() else { break };
            let cooldown_s = match attribute(attrs, "cooldown", Some(u8::is_ascii_digit)) {
                Some(c) => match c.parse::<i32>() {
                    Ok(v) => v,
                    Err(_) => break,
                },
                None => 0,
            };
            a.by_item.insert(item, Amulet { item, name: name.to_string(), threshold, effect: effect.to_string(), cooldown_s });
        }
        a
    }

    pub fn of(&self, item: i32) -> Option<&Amulet> {
        self.by_item.get(&item)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_the_amulet() {
        let xml = r#"<Objects>
  <Object type="0x5c48" id="Amulet of Restoration" collectionIcon="103">
    <DisplayId>Amulet of Restoration</DisplayId>
    <OnPlayerHitActivate proc="1" cooldown="1800" hpMinThresholdRelative="0.2" damageThreshold="2" effect="Invulnerable" duration="2">ConditionEffectSelf</OnPlayerHitActivate>
    <OnPlayerHitActivate proc="1" cooldown="1800" hpMinThresholdRelative="0.2" amount="120">Heal</OnPlayerHitActivate>
  </Object>
  <Object type="0x14D9" id="Amulet of Restoration Shiny"><DisplayId>Amulet of Restoration</DisplayId>
    <OnPlayerHitActivate hpMinThresholdRelative="0.25">Invulnerable</OnPlayerHitActivate></Object>
  <Object type="0x0100" id="Amulet of Dispersion"><OnPlayerHitActivate hpMinThresholdRelative="0.3" amount="50">Heal</OnPlayerHitActivate></Object>
  <Object type="0x0101" id="Shield Ring"><OnPlayerHitActivate hpMinThresholdRelative="0.3" effect="Invulnerable">X</OnPlayerHitActivate></Object>
</Objects>"#;
        let dir = std::env::temp_dir().join(format!("lg-amulets-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("equip.xml"), xml).unwrap();
        let a = Amulets::load(&dir);
        std::fs::remove_dir_all(&dir).unwrap();
        assert_eq!(a.restoration, 0x5c48);
        assert_eq!(a.by_item.len(), 2, "not the healing one, not a ring that is no amulet");
        let r = a.of(0x5c48).unwrap();
        assert_eq!((r.name.as_str(), r.threshold, r.effect.as_str(), r.cooldown_s), ("Amulet of Restoration", 0.2, "Invulnerable", 1800));
        let s = a.of(0x14d9).unwrap();
        assert_eq!((s.effect.as_str(), s.cooldown_s, s.threshold), ("Invulnerable", 0, 0.25), "the effect from the text");
    }

    #[test]
    fn trigger_takes_the_last_threshold_in_the_tag() {
        let body = r#"<OnPlayerHitActivate hpMinThresholdRelative="0.1" x="1" hpMinThresholdRelative="0.4">A</OnPlayerHitActivate>"#;
        assert_eq!(trigger(body).map(|t| t.1), Some("0.4"));
        // a tag whose text has an element in it does not match; the next one does
        let body = r#"<OnPlayerHitActivate hpMinThresholdRelative="0.1"><b/></OnPlayerHitActivate><OnPlayerHitActivate hpMinThresholdRelative="0.3">B</OnPlayerHitActivate>"#;
        assert_eq!(trigger(body).map(|t| (t.1, t.2)), Some(("0.3", "B")));
    }
}
