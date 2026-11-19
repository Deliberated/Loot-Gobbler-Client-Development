//! Your in-game friends, by name in lower case (Java: `Friends`): asked from the game's web API (`friends/getList`)
//! with the login token, at most every 10 minutes, and kept so they are known before the next login.
//!
//! Not yet: the asking (it comes with the game's web API, with the character list).

use std::collections::BTreeSet;
use std::path::Path;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Friends {
    pub names: BTreeSet<String>,
}

/// Java's `\s`.
fn space(c: char) -> bool {
    matches!(c, ' ' | '\t' | '\n' | '\u{b}' | '\u{c}' | '\r')
}

/// A character Java's `\b` counts as part of a word.
fn word(c: char) -> bool {
    c.is_alphanumeric() || c == '_'
}

/// `<Name>\s*([^<\s]+)\s*</Name>` at `i`: the name and where the match ends.
fn name_element(s: &str, i: usize) -> Option<(&str, usize)> {
    let r = s[i..].strip_prefix("<Name>")?;
    let r = r.trim_start_matches(space);
    let n = r.find(|c: char| c == '<' || space(c)).unwrap_or(r.len());
    if n == 0 {
        return None;
    }
    let name = &r[..n];
    let rest = r[n..].trim_start_matches(space).strip_prefix("</Name>")?;
    Some((name, s.len() - rest.len()))
}

/// `\bname="([^"]+)"` at `i`.
fn name_attribute(s: &str, i: usize) -> Option<(&str, usize)> {
    let r = s[i..].strip_prefix("name=\"")?;
    // \b before 'n': the character before is not part of a word
    if s[..i].chars().next_back().is_some_and(word) {
        return None;
    }
    let n = r.find('"')?;
    if n == 0 {
        return None;
    }
    Some((&r[..n], i + "name=\"".len() + n + 1))
}

/// Java `parse`: the names in the game's answer, each friend's `<Name>` element (or a `name=""` attribute).
pub fn parse(xml: &str) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    let mut i = 0;
    while i < xml.len() {
        if let Some((name, end)) = name_element(xml, i).or_else(|| name_attribute(xml, i)) {
            out.insert(name.to_lowercase());
            i = end;
        } else {
            i += xml[i..].chars().next().map_or(1, char::len_utf8);
        }
    }
    out
}

impl Friends {
    /// What the Java app kept (`friends.txt`, a name per line), read only.
    pub fn import(dir: &Path) -> Friends {
        let names = std::fs::read_to_string(dir.join("friends.txt"))
            .map(|t| {
                t.lines()
                    .filter(|l| !l.chars().all(char::is_whitespace))
                    .map(|l| l.trim_matches(|c: char| c <= ' ').to_lowercase())
                    .collect()
            })
            .unwrap_or_default();
        Friends { names }
    }

    pub fn is_friend(&self, name: &str) -> bool {
        self.names.contains(&name.to_lowercase())
    }

    /// The game's answer came: its names replace the list; whether it changed.
    pub fn answered(&mut self, xml: &str) -> bool {
        let found = parse(xml);
        if found == self.names {
            return false;
        }
        self.names = found;
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_the_answer() {
        let xml = "<Friends><Friend><Name> Bob </Name></Friend><Friend name=\"ALICE\"/><Friend><Name>two words</Name></Friend>\
                   <Friend nickname=\"x\" name=\"\"/><Name>Eve</Name></Friends>";
        let names: Vec<String> = parse(xml).into_iter().collect();
        assert_eq!(names, ["alice", "bob", "eve"], "no name with a space in it, none after a word, none empty");
    }

    #[test]
    fn friends() {
        let mut f = Friends::default();
        assert!(f.answered("<Name>Bob</Name>"));
        assert!(f.is_friend("BOB") && !f.is_friend("alice"));
        assert!(!f.answered("<Name>bob</Name>"), "the same list");
    }
}
