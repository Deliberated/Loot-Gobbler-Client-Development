//! A Loot Gobbler server (Java `GobblerServer`, API in the server's docs/API.md): JSON over HTTPS under `/api/v1`,
//! the session sent as a bearer token. Blocking: the app calls it from a thread of its own.

use lg_app::gson_out::Obj;
use lg_world::json::{self, Json};
use std::time::Duration;

pub use lg_app::server::{API_VERSION, DataItem, Error, Status, User, boolean, item_of, long, normalize, text, user_of};

/// The connection test's answer (Java `TestResult`).
#[derive(Debug, Clone, Default)]
pub struct Ping {
    pub millis: i64,
    pub your_ip: String,
    pub user: Option<User>,
}

/// One server and the session on it.
pub struct Server {
    agent: ureq::Agent,
    pub url: String,
    pub token: String,
}

impl Server {
    pub fn new(url: &str, token: &str) -> Server {
        let agent = ureq::AgentBuilder::new().timeout_connect(Duration::from_secs(10)).timeout(Duration::from_secs(20)).build();
        Server { agent, url: url.to_string(), token: token.to_string() }
    }

    /// One call: the body as JSON text, the answer parsed (None for an empty one).
    pub fn call(&self, method: &str, path: &str, body: Option<&str>) -> Result<Option<Json>, Error> {
        let full = format!("{}/api/v1{path}", self.url);
        if url_parts_bad(&full) {
            return Err(Error::Server {
                status: 0,
                code: "bad_address".into(),
                message: "That is not a valid address".into(),
                current: None,
            });
        }
        let mut req = self.agent.request(method, &full);
        if body.is_some() {
            req = req.set("Content-Type", "application/json");
        }
        if !self.token.is_empty() {
            req = req.set("Authorization", &format!("Bearer {}", self.token));
        }
        let start = std::time::Instant::now();
        let res = match body {
            Some(b) => req.send_string(b),
            None => req.call(),
        };
        // method, path and outcome only: request bodies can hold passwords and tokens
        let took = format!(" ({} ms)", start.elapsed().as_millis());
        match &res {
            Ok(r) => lg_app::applog::line("server", &format!("{method} {path} -> {}{took}", r.status())),
            Err(ureq::Error::Status(code, _)) => lg_app::applog::line("server", &format!("{method} {path} -> {code}{took}")),
            Err(ureq::Error::Transport(t)) => {
                lg_app::applog::line("server", &format!("{method} {path} failed: {}{took}", transport_message(t)))
            }
        }
        match res {
            Ok(r) => {
                let t = r.into_string().map_err(|e| Error::Net(e.to_string()))?;
                if t.trim().is_empty() { Ok(None) } else { json::parse(&t).map(Some).map_err(Error::Net) }
            }
            Err(ureq::Error::Status(code, r)) => Err(server_error(code, &r.into_string().unwrap_or_default())),
            Err(ureq::Error::Transport(t)) => Err(Error::Net(transport_message(&t))),
        }
    }

    pub fn status(&self) -> Result<Status, Error> {
        let j = self.call("GET", "/status", None)?.unwrap_or(Json::Null);
        Ok(Status {
            version: text(j.get("version")),
            api_version: long(j.get("apiVersion")),
            admin_claimed: boolean(j.get("adminClaimed")),
            registration: text(j.get("registration")),
        })
    }

    pub fn me(&self) -> Result<User, Error> {
        let j = self.call("GET", "/me", None)?.unwrap_or(Json::Null);
        Ok(j.get("user").map(user_of).unwrap_or_default())
    }

    /// Java `test`'s ping: the round trip, the address the server sees, who is logged in.
    pub fn ping(&self) -> Result<Ping, Error> {
        let start = std::time::Instant::now();
        let j = self.call("POST", "/ping", None)?.unwrap_or(Json::Null);
        Ok(Ping {
            millis: start.elapsed().as_millis() as i64,
            your_ip: text(j.get("yourIp")),
            user: j.get("user").filter(|u| !matches!(u, Json::Null)).map(user_of),
        })
    }

    /// Logs in: the session token and the account.
    pub fn login(&self, ign: &str, password: &str, client: &str) -> Result<(String, User), Error> {
        let body = Obj::new().str("ign", ign).str("password", password).str("client", client).end();
        let j = self.call("POST", "/auth/login", Some(&body))?.unwrap_or(Json::Null);
        Ok((text(j.get("token")), j.get("user").map(user_of).unwrap_or_default()))
    }

    /// Creates an account (with a role token: active at once); the user as the server answers.
    pub fn register(&self, ign: &str, password: &str, role_token: &str) -> Result<User, Error> {
        let mut o = Obj::new().str("ign", ign).str("password", password);
        if !role_token.trim().is_empty() {
            o = o.str("token", role_token.trim());
        }
        let j = self.call("POST", "/auth/register", Some(&o.end()))?.unwrap_or(Json::Null);
        Ok(j.get("user").map(user_of).unwrap_or_default())
    }

    /// Java `redeem`: an admin or moderator token applied to the logged-in account; the account as it is now.
    pub fn redeem(&self, role_token: &str) -> Result<User, Error> {
        let body = Obj::new().str("token", role_token.trim()).end();
        let j = self.call("POST", "/me/redeem", Some(&body))?.unwrap_or(Json::Null);
        Ok(j.get("user").map(user_of).unwrap_or_default())
    }

    /// Java `resetPassword`: a new password with a reset code (no login needed); every session of it ends.
    pub fn reset_password(&self, ign: &str, code: &str, new_password: &str) -> Result<(), Error> {
        let body = Obj::new().str("ign", ign.trim()).str("code", code.trim()).str("newPassword", new_password).end();
        self.call("POST", "/auth/reset-password", Some(&body)).map(|_| ())
    }

    /// Java `changePassword`: proven with the current one; your other devices are logged out.
    pub fn change_password(&self, current: &str, new_password: &str) -> Result<(), Error> {
        let body = Obj::new().str("currentPassword", current).str("newPassword", new_password).end();
        self.call("PATCH", "/me/password", Some(&body)).map(|_| ())
    }

    pub fn logout(&self) -> Result<(), Error> {
        self.call("POST", "/auth/logout", None).map(|_| ())
    }

    /// All of the player's documents, by kind.
    pub fn data(&self) -> Result<Vec<(String, DataItem)>, Error> {
        let j = self.call("GET", "/me/data", None)?.unwrap_or(Json::Null);
        Ok(match j.get("items") {
            Some(Json::Object(items)) => items.iter().map(|(k, v)| (k.clone(), item_of(v))).collect(),
            _ => Vec::new(),
        })
    }

    /// Uploads a document (`data` as JSON text) based on a revision; the new revision. A revision conflict comes
    /// back as `Error::Server` with code "revision_conflict" and the server's document.
    pub fn put_data(&self, kind: &str, base_revision: i64, data: &str) -> Result<i64, Error> {
        let body = format!("{{\"baseRevision\":{base_revision},\"data\":{data}}}");
        let j = self.call("PUT", &format!("/me/data/{kind}"), Some(&body))?.unwrap_or(Json::Null);
        Ok(long(j.get("revision")))
    }

    /// Any other call whose answer the caller reads itself (the guild views...).
    pub fn get(&self, path: &str) -> Result<Json, Error> {
        Ok(self.call("GET", path, None)?.unwrap_or(Json::Null))
    }
}

fn url_parts_bad(u: &str) -> bool {
    !(u.starts_with("http://") || u.starts_with("https://")) || u.contains(' ')
}

/// Java `error`: the server's JSON error, else (a proxy's page) just the status.
fn server_error(code: u16, body: &str) -> Error {
    match json::parse(body) {
        Ok(o @ Json::Object(_)) => {
            let c = o.get("error").map_or(format!("http_{code}"), |_| text(o.get("error")));
            let mut message = o.get("message").map_or(format!("HTTP {code}"), |_| text(o.get("message")));
            if let Some(r) = o.get("retryAfter") {
                let m = (long(Some(r)) + 59) / 60;
                message += &format!(" ({})", if m <= 1 { "wait a minute".to_string() } else { format!("wait {m} minutes") });
            }
            Error::Server { status: code, code: c, message, current: o.get("current").map(item_of) }
        }
        _ => Error::Server { status: code, code: format!("http_{code}"), message: format!("HTTP {code}"), current: None },
    }
}

/// Java's messages for a connection that failed.
fn transport_message(t: &ureq::Transport) -> String {
    match t.kind() {
        ureq::ErrorKind::ConnectionFailed | ureq::ErrorKind::Io => "Server not reachable".into(),
        ureq::ErrorKind::Dns => "Unknown address".into(),
        ureq::ErrorKind::InvalidUrl | ureq::ErrorKind::UnknownScheme => "That is not a valid address".into(),
        _ => match t.message() {
            Some(m) if m.to_lowercase().contains("tls") || m.to_lowercase().contains("certificate") => {
                format!("Secure connection failed ({m})")
            }
            Some(m) => m.to_string(),
            None => t.kind().to_string(),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn normalizes_as_java() {
        assert_eq!(normalize(" loot.example.com/ "), "https://loot.example.com");
        assert_eq!(normalize("http://10.0.0.2:8080/api/v1"), "http://10.0.0.2:8080");
        assert_eq!(normalize(""), "");
    }

    #[test]
    fn reads_error_answers() {
        let e = server_error(429, r#"{"error":"rate_limited","message":"Too many tries","retryAfter":120}"#);
        assert_eq!(e.code(), "rate_limited");
        assert_eq!(e.message(), "Too many tries (wait 2 minutes)");
        let e = server_error(502, "<html>bad gateway</html>");
        assert_eq!((e.code(), e.message().as_str()), ("http_502", "HTTP 502"));
    }
}

/// Against a real server (a local test one): `LG_TEST_SERVER=http://127.0.0.1:18080 LG_TEST_ADMIN=<LGA token>
/// cargo test -p lg-net -- --ignored live`.
#[cfg(test)]
mod live {
    use super::*;

    #[test]
    #[ignore]
    fn live() {
        let (Ok(url), Ok(admin)) = (std::env::var("LG_TEST_SERVER"), std::env::var("LG_TEST_ADMIN")) else { return };
        let s = Server::new(&url, "");
        let st = s.status().expect("status");
        assert_eq!(st.api_version, API_VERSION);
        // the first account with the admin token: active at once
        match s.register("Tester", "password123", &admin) {
            Ok(u) => assert_eq!(u.status, "active"),
            Err(e) => assert_eq!(e.code(), "ign_taken", "{e:?}"),
        }
        let (token, user) = s.login("Tester", "password123", "Loot Gobbler test").expect("login");
        assert!(user.at_least_admin() && token.starts_with("lgs_"));
        let s = Server::new(&url, &token);
        assert_eq!(s.me().expect("me").ign, "Tester");
        assert!(s.ping().expect("ping").user.is_some());
        let base = s.data().expect("data").into_iter().find(|(k, _)| k == "pets").map_or(0, |(_, i)| i.revision);
        let rev = s.put_data("pets", base, "{}").expect("put");
        assert_eq!(rev, base + 1);
        // an old base: the server's document comes back
        match s.put_data("pets", base, "{}") {
            Err(Error::Server { code, current: Some(c), .. }) => assert!(code == "revision_conflict" && c.revision == rev),
            other => panic!("{other:?}"),
        }
        // passwords: a wrong current one, a change and back, a reset code that is no code
        assert_eq!(s.change_password("nope-nope", "password456").err().map(|e| e.code().to_string()).as_deref(), Some("wrong_password"));
        s.change_password("password123", "password456").expect("change");
        let (token, _) = Server::new(&url, "").login("Tester", "password456", "x").expect("new password");
        Server::new(&url, &token).change_password("password456", "password123").expect("back");
        let bad = Server::new(&url, "").reset_password("Tester", "LGR-0000-0000-0000", "password789");
        assert_eq!(bad.err().map(|e| e.code().to_string()).as_deref(), Some("invalid_code"));
        moderation(&url, &Server::new(&url, &token));
        let wrong = Server::new(&url, "").login("Tester", "wrongpassword", "x");
        assert_eq!(wrong.err().map(|e| e.code().to_string()).as_deref(), Some("invalid_credentials"));
    }

    /// The Server tab's moderation, scores and guild settings, as the app's connection state drives them.
    fn moderation(url: &str, s: &Server) {
        use lg_app::server::{Connection, Done, GuildSettings, Job, ScoreWeights, State};
        let run = |c: &mut Connection| {
            while !c.jobs.is_empty() {
                for job in std::mem::take(&mut c.jobs) {
                    if let Job::Call { method, path, body, tag, .. } = job {
                        let result = s.call(&method, &path, body.as_deref());
                        c.done(Done::Call { url: url.into(), path, tag, result });
                    }
                }
            }
        };
        let _ = Server::new(url, "").register("Pendy", "password123", "");
        let mut c = Connection::default();
        (c.url, c.token, c.state, c.user) = (url.into(), s.token.clone(), State::Online, Some(s.me().expect("me")));
        c.tick();
        run(&mut c);
        let m = &c.moderation;
        assert!(m.members.iter().any(|u| u.ign == "Tester"), "{:?} {}", m.members, m.feedback);
        let p = m.pending.iter().find(|u| u.ign == "Pendy").expect("waiting").clone();
        assert_eq!((c.weights, c.guild_settings), (ScoreWeights::default(), GuildSettings::default()));
        c.accept(p.id, &p.ign);
        run(&mut c);
        assert_eq!(c.moderation.feedback, "Pendy can log in now.");
        assert!(c.moderation.members.iter().any(|u| u.ign == "Pendy"));
        c.reset_code_for(p.id);
        run(&mut c);
        assert!(c.moderation.reset_code.take().is_some_and(|r| r.code.starts_with("LGR-") && r.ign == "Pendy"));
        c.remove(p.id, &p.ign);
        run(&mut c);
        assert_eq!(c.moderation.feedback, "Pendy was removed.");
        c.create_token("For a test", 24);
        run(&mut c);
        let t = c.moderation.last_issued.clone().expect("issued");
        assert!(t.token.starts_with("LGM-") && c.moderation.tokens.iter().any(|o| o.id == t.id && o.note == "For a test"));
        c.revoke_token(t.id);
        run(&mut c);
        assert_eq!(c.moderation.tokens_feedback, "Token revoked.");
        assert!(c.moderation.last_issued.is_none() && !c.moderation.tokens.iter().any(|o| o.id == t.id));
        c.save_scoring(ScoreWeights { feed_power: 0.5, difficulty: 0.25, rarity: 1.0 });
        run(&mut c);
        assert_eq!((c.weights.feed_power, c.weights.difficulty), (0.5, 0.25));
        c.save_scoring(ScoreWeights::default());
        c.save_guild(GuildSettings { baseline_hpm: 60.5 });
        run(&mut c);
        assert_eq!((c.weights, c.guild_settings.baseline_hpm), (ScoreWeights::default(), 60.5));
        c.save_guild(GuildSettings::default());
        run(&mut c);
        assert_eq!(c.moderation.guild_feedback, "Saved. Everyone's dungeon details use it.");
    }
}
