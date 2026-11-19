//! The game's web API (Java: `HttpCharListRequest.webRequest`): requests made with your login token, such as the
//! character list, exaltations, friends and missions. The app's records only see answers; where they come from is
//! a [`Web`]: the game's servers (not built yet), or files ([`Canned`]) for comparing with Java, which then answers
//! from the same files. Every request is kept in order, for that comparison.

use std::path::PathBuf;

pub trait Web {
    /// The answer to `type` (e.g. `char/list`) with `extra` parameters (e.g. `&muleDump=true`), or None when the
    /// server refused it (Java: an HTTP error).
    fn request(&mut self, token: &str, kind: &str, extra: &str) -> Option<String>;

    /// Every request so far: "type [extra] answered|refused".
    fn requests(&self) -> &[String];
}

/// No web at all: every request refused.
#[derive(Debug, Default)]
pub struct Offline {
    log: Vec<String>,
}

impl Web for Offline {
    fn request(&mut self, _: &str, kind: &str, extra: &str) -> Option<String> {
        self.log.push(entry(kind, extra, false));
        None
    }

    fn requests(&self) -> &[String] {
        &self.log
    }
}

/// Answers from files, as `oracle/ClockAgent.java` gives them to Java: `<dir>/<type, / as _>[-mule].xml`.
#[derive(Debug)]
pub struct Canned {
    dir: PathBuf,
    log: Vec<String>,
}

impl Canned {
    pub fn new(dir: impl Into<PathBuf>) -> Canned {
        Canned { dir: dir.into(), log: Vec::new() }
    }
}

impl Web for Canned {
    fn request(&mut self, _: &str, kind: &str, extra: &str) -> Option<String> {
        let name = format!("{}{}.xml", kind.replace('/', "_"), if extra.contains("muleDump") { "-mule" } else { "" });
        let answer = std::fs::read_to_string(self.dir.join(name)).ok().map(|s| joined_lines(&s));
        self.log.push(entry(kind, extra, answer.is_some()));
        answer
    }

    fn requests(&self) -> &[String] {
        &self.log
    }
}

fn entry(kind: &str, extra: &str, answered: bool) -> String {
    format!(
        "{kind}{}{}",
        if extra.is_empty() { String::new() } else { format!(" {extra}") },
        if answered { " answered" } else { " refused" }
    )
}

/// How Java reads an answer: line by line (ending at `\n`, `\r\n` or `\r`), the line ends dropped.
pub fn joined_lines(s: &str) -> String {
    s.split('\n').map(|l| l.strip_suffix('\r').unwrap_or(l)).flat_map(|l| l.split('\r')).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lines_joined() {
        assert_eq!(joined_lines("a\r\nb\nc\rd\n"), "abcd");
        assert_eq!(joined_lines(""), "");
    }

    #[test]
    fn canned() {
        let dir = std::env::temp_dir().join(format!("lg-web-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("char_list-mule.xml"), "<Chars>\n</Chars>\n").unwrap();
        let mut w = Canned::new(&dir);
        assert_eq!(w.request("t", "char/list", "&muleDump=true").as_deref(), Some("<Chars></Chars>"));
        assert_eq!(w.request("t", "char/list", ""), None);
        std::fs::remove_dir_all(&dir).unwrap();
        assert_eq!(w.requests(), ["char/list &muleDump=true answered", "char/list refused"]);
    }
}
