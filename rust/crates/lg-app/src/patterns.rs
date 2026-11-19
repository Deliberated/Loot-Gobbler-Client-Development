//! The regular expressions the Java app reads the raw character list with, followed by hand, match for match
//! (leftmost first, then on after the match, as `Matcher.find` does). `\b` is Java 25's: between an ASCII word
//! character (`[A-Za-z0-9_]`) and anything else.

fn word(c: u8) -> bool {
    c.is_ascii_alphanumeric() || c == b'_'
}

/// `\b` before position `i` when the next character is a word character.
fn boundary_before(s: &str, i: usize) -> bool {
    i == 0 || !word(s.as_bytes()[i - 1])
}

/// `<Char id="(\d+)">(.*?)</Char>` (dot matching newlines): every (id, body).
pub fn chars(xml: &str) -> Vec<(&str, &str)> {
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(i) = xml[from..].find("<Char id=\"") {
        let at = from + i;
        let v = at + "<Char id=\"".len();
        let digits = xml.as_bytes()[v..].iter().take_while(|b| b.is_ascii_digit()).count();
        let rest = &xml[v + digits..];
        if digits > 0
            && let Some(r) = rest.strip_prefix("\">")
            && let Some(end) = r.find("</Char>")
        {
            let body_start = xml.len() - r.len();
            out.push((&xml[v..v + digits], &xml[body_start..body_start + end]));
            from = body_start + end + "</Char>".len();
        } else {
            from = at + 1;
        }
    }
    out
}

/// `replaceAll` of `<Pet[ >].*?</Pet>` with nothing.
pub fn without_pets(s: &str) -> String {
    let mut out = String::new();
    let mut from = 0;
    let mut search = 0;
    while let Some(i) = s[search..].find("<Pet") {
        let at = search + i;
        let next = s.as_bytes().get(at + 4).copied();
        if matches!(next, Some(b' ') | Some(b'>'))
            && let Some(end) = s[at + 5..].find("</Pet>")
        {
            out.push_str(&s[from..at]);
            from = at + 5 + end + "</Pet>".len();
            search = from;
        } else {
            search = at + 1;
        }
    }
    out.push_str(&s[from..]);
    out
}

/// `<tag>([^<]*)</tag>`: every text.
pub fn texts<'a>(s: &'a str, tag: &str) -> Vec<&'a str> {
    let (open, close) = (format!("<{tag}>"), format!("</{tag}>"));
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(i) = s[from..].find(&open) {
        let v = from + i + open.len();
        let n = s[v..].find('<').unwrap_or(s.len() - v);
        if s[v + n..].starts_with(&close) {
            out.push(&s[v..v + n]);
            from = v + n + close.len();
        } else {
            from = from + i + 1;
        }
    }
    out
}

/// Which attribute of an `<ItemData ...>` tag the pattern takes.
#[derive(Clone, Copy)]
pub enum Attr {
    /// `[^>]*\btype="(\d+)"`: the last one (greedy).
    TypeDigits,
    /// `[^>]*?\bid="([^"]+)"`: the first one (lazy).
    Id,
}

/// `<ItemData[^>]*<attr>[^>]*>([^<]*)</ItemData>`: every (attribute value, text).
pub fn item_data(s: &str, attr: Attr) -> Vec<(&str, &str)> {
    const OPEN: &str = "<ItemData";
    const CLOSE: &str = "</ItemData>";
    let (key, digits_only) = match attr {
        Attr::TypeDigits => ("type=\"", true),
        Attr::Id => ("id=\"", false),
    };
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(i) = s[from..].find(OPEN) {
        let at = from + i;
        let attrs_start = at + OPEN.len();
        let Some(gt) = s[attrs_start..].find('>') else { break };
        let tag_end = attrs_start + gt;
        // the candidates within the tag, in order
        let mut found = None;
        let mut k = attrs_start;
        while let Some(j) = s[k..tag_end].find(key) {
            let p = k + j;
            let v = p + key.len();
            let n = s.as_bytes()[v..tag_end].iter().take_while(|b| if digits_only { b.is_ascii_digit() } else { **b != b'"' }).count();
            if boundary_before(s, p) && n > 0 && s.as_bytes().get(v + n) == Some(&b'"') {
                found = Some(&s[v..v + n]);
                if !digits_only {
                    break; // lazy: the first
                }
            }
            k = p + 1;
        }
        let text_start = tag_end + 1;
        let n = s[text_start..].find('<').unwrap_or(s.len() - text_start);
        match found {
            Some(v) if s[text_start + n..].starts_with(CLOSE) => {
                out.push((v, &s[text_start..text_start + n]));
                from = text_start + n + CLOSE.len();
            }
            _ => from = at + 1,
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn as_java_patterns() {
        let xml = r#"<Chars><Char id="5"><A>1</A><Pet x="1"><Char id="9">x</Char></Pet></Char><Char id="x">no</Char><Char id="6">b</Char></Chars>"#;
        let c = chars(xml);
        assert_eq!(c.iter().map(|x| x.0).collect::<Vec<_>>(), ["5", "6"], "the first ends at the first </Char>");
        assert_eq!(without_pets("a<Pet x>b</Pet>c<Pets>d</Pets><Pet>e</Pet>f"), "ac<Pets>d</Pets>f");
        assert_eq!(texts("<Chest>1,2</Chest><Chest><b/></Chest><Chest>3</Chest>", "Chest"), ["1,2", "3"]);
        let d = r#"<ItemData type="1" xtype="2" type="3">c1</ItemData><ItemData id="a" type="x">c2</ItemData><ItemData instanceid="q" id="r">c3</ItemData>"#;
        assert_eq!(item_data(d, Attr::TypeDigits), [("3", "c1")], "the last type; none without digits");
        assert_eq!(item_data(d, Attr::Id), [("a", "c2"), ("r", "c3")], "the first id, not inside another name");
    }
}
