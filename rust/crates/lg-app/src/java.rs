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
    let mut logical = String::new();
    let mut lines = text.split('\n').map(|l| l.strip_suffix('\r').unwrap_or(l)).peekable();
    let mut found = None;
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
        let (k, v) = split_property(&logical);
        if k == key {
            found = Some(v);
        }
    }
    found
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
