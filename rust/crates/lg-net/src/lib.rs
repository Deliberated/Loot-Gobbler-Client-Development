//! The game's web API for real (Java: `HttpCharListRequest.webRequest`): a POST to
//! `https://www.realmofthemadgod.com/<type>?<params>` with the same params as the body, your login token in them.
//! An answer is read as Java reads it, line by line with the line ends dropped; an HTTP error or no connection is
//! a refusal (Java prints the error and returns null).

use lg_app::web::{Web, joined_lines};
use std::time::Duration;

const SITE: &str = "https://www.realmofthemadgod.com/";

/// The game's servers.
pub struct Online {
    agent: ureq::Agent,
    site: String,
    log: Vec<String>,
    /// The last refusal's text, for showing.
    pub last_error: Option<String>,
}

impl Default for Online {
    fn default() -> Self {
        Online::new()
    }
}

impl Online {
    pub fn new() -> Online {
        Online::at(SITE)
    }

    /// Another site than the game's (a test server), ending in `/`.
    pub fn at(site: &str) -> Online {
        let agent = ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(20)).timeout(Duration::from_secs(60)).build();
        Online { agent, site: site.to_string(), log: Vec::new(), last_error: None }
    }
}

/// The parameters Java sends, as the query and as the body.
pub fn params(token: &str, extra: &str) -> String {
    format!("do_login=true&accessToken={}&game_net=Unity&play_platform=Unity&game_net_user_id{extra}", url_encode(token))
}

/// Java's `URLEncoder.encode(s, "UTF-8")`: letters, digits and `.-*_` kept, space as `+`, the rest as `%XX` of
/// its UTF-8 bytes.
pub fn url_encode(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    for b in s.bytes() {
        match b {
            b'a'..=b'z' | b'A'..=b'Z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => o.push(b as char),
            b' ' => o.push('+'),
            _ => o.push_str(&format!("%{b:02X}")),
        }
    }
    o
}

impl Web for Online {
    fn request(&mut self, token: &str, kind: &str, extra: &str) -> Option<String> {
        let p = params(token, extra);
        let url = format!("{}{kind}?{p}", self.site);
        let answer = match self.agent.post(&url).set("Content-Type", "text/plain; charset=utf-8").send_string(&p) {
            Ok(r) => match r.into_string() {
                Ok(s) => Some(joined_lines(&s)),
                Err(e) => {
                    self.last_error = Some(e.to_string());
                    None
                }
            },
            Err(ureq::Error::Status(code, r)) => {
                self.last_error = Some(format!("{code}: {}", r.into_string().unwrap_or_default()));
                None
            }
            Err(e) => {
                self.last_error = Some(e.to_string());
                None
            }
        };
        self.log.push(format!(
            "{kind}{}{}",
            if extra.is_empty() { String::new() } else { format!(" {extra}") },
            if answer.is_some() { " answered" } else { " refused" }
        ));
        answer
    }

    fn requests(&self) -> &[String] {
        &self.log
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader, Read, Write};
    use std::net::TcpListener;

    #[test]
    fn encodes_as_java() {
        assert_eq!(url_encode("aZ09.-*_ +/=é~"), "aZ09.-*_+%2B%2F%3D%C3%A9%7E");
    }

    /// One request against a local server: the URL, the body and the answer as Java has them.
    #[test]
    fn posts_and_reads_as_java() {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        let server = std::thread::spawn(move || {
            let mut seen = Vec::new();
            for answer in
                ["HTTP/1.1 200 OK\r\nContent-Length: 14\r\n\r\n<A>\r\n<B/>\n</A>", "HTTP/1.1 500 No\r\nContent-Length: 2\r\n\r\nno"]
            {
                let (s, _) = l.accept().unwrap();
                let mut r = BufReader::new(s);
                let mut head = String::new();
                let mut len = 0;
                loop {
                    let mut line = String::new();
                    r.read_line(&mut line).unwrap();
                    if let Some(v) = line.to_lowercase().strip_prefix("content-length:") {
                        len = v.trim().parse().unwrap();
                    }
                    if line == "\r\n" {
                        break;
                    }
                    head.push_str(&line);
                }
                let mut body = vec![0; len];
                r.read_exact(&mut body).unwrap();
                r.get_mut().write_all(answer.as_bytes()).unwrap();
                seen.push((
                    head.lines().next().unwrap().to_string(),
                    String::from_utf8(body).unwrap(),
                    head.contains("text/plain; charset=utf-8"),
                ));
            }
            seen
        });
        let mut w = Online::at(&format!("http://127.0.0.1:{port}/"));
        let a = w.request("t o+k", "char/list", "&muleDump=true");
        assert_eq!(a.as_deref(), Some("<A><B/></A>"), "{:?}", w.last_error);
        assert_eq!(w.request("t", "account/listPowerUpStats", ""), None);
        assert_eq!(w.last_error.as_deref(), Some("500: no"));
        assert_eq!(w.requests(), ["char/list &muleDump=true answered", "account/listPowerUpStats refused"]);
        let seen = server.join().unwrap();
        let p = "do_login=true&accessToken=t+o%2Bk&game_net=Unity&play_platform=Unity&game_net_user_id&muleDump=true";
        assert_eq!(seen[0], (format!("POST /char/list?{p} HTTP/1.1"), p.to_string(), true));
    }
}
