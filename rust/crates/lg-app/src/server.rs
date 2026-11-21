//! The Loot Gobbler server as the app sees it (Java `GobblerServer`'s state, without its HTTP: the app's network
//! thread makes the calls, see lg-net): the address, the session, whether it is online, who is logged in.

use lg_world::json::Json;

/// The API version this client speaks.
pub const API_VERSION: i64 = 1;

/// A failed call (Java `ServerException`, or the connection's own failure).
#[derive(Debug, Clone)]
pub enum Error {
    /// The server's answer: its HTTP status, its stable code, the message to show; on a revision conflict the
    /// document it has.
    Server { status: u16, code: String, message: String, current: Option<DataItem> },
    /// No answer (Java `message` for the exception).
    Net(String),
}

impl Error {
    /// What to show for it (Java `GobblerServer.message`).
    pub fn message(&self) -> String {
        match self {
            Error::Server { message, .. } => message.clone(),
            Error::Net(m) => m.clone(),
        }
    }

    pub fn code(&self) -> &str {
        match self {
            Error::Server { code, .. } => code,
            Error::Net(_) => "",
        }
    }

    pub fn status(&self) -> u16 {
        match self {
            Error::Server { status, .. } => *status,
            Error::Net(_) => 0,
        }
    }
}

/// What the server reports of itself.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Status {
    pub version: String,
    pub api_version: i64,
    pub admin_claimed: bool,
    pub registration: String,
}

/// An account.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct User {
    pub id: i64,
    pub ign: String,
    pub status: String,
    pub role: String,
    pub root: bool,
    pub created_at: i64,
    pub last_login_at: Option<i64>,
}

impl User {
    pub fn rank(&self) -> i32 {
        if self.root {
            3
        } else {
            match self.role.as_str() {
                "admin" => 2,
                "moderator" => 1,
                _ => 0,
            }
        }
    }

    pub fn at_least_moderator(&self) -> bool {
        self.rank() >= 1
    }

    pub fn at_least_admin(&self) -> bool {
        self.rank() >= 2
    }

    pub fn role_name(&self) -> &'static str {
        if self.root {
            return "Root admin";
        }
        match self.role.as_str() {
            "admin" => "Admin",
            "moderator" => "Moderator",
            _ => "Member",
        }
    }
}

/// One of the player's documents on the server.
#[derive(Debug, Clone, PartialEq)]
pub struct DataItem {
    pub revision: i64,
    pub updated_at: i64,
    pub data: Option<Json>,
}

// --- reading the server's answers (Gson into the Java classes: missing or null fields keep their defaults) ---

pub fn long(v: Option<&Json>) -> i64 {
    match v {
        Some(Json::Number(n)) | Some(Json::String(n)) => n.parse::<f64>().map_or(0, |f| f as i64),
        _ => 0,
    }
}

pub fn text(v: Option<&Json>) -> String {
    match v {
        Some(Json::String(s)) => s.clone(),
        _ => String::new(),
    }
}

pub fn boolean(v: Option<&Json>) -> bool {
    matches!(v, Some(Json::Bool(true)))
}

pub fn user_of(j: &Json) -> User {
    User {
        id: long(j.get("id")),
        ign: text(j.get("ign")),
        status: text(j.get("status")),
        role: j.get("role").map_or("user".into(), |_| text(j.get("role"))),
        root: boolean(j.get("root")),
        created_at: long(j.get("createdAt")),
        last_login_at: j.get("lastLoginAt").filter(|v| !matches!(v, Json::Null)).map(|v| long(Some(v))),
    }
}

pub fn item_of(j: &Json) -> DataItem {
    DataItem {
        revision: long(j.get("revision")),
        updated_at: long(j.get("updatedAt")),
        data: j.get("data").filter(|d| !matches!(d, Json::Null)).cloned(),
    }
}

/// Java `normalize`: "loot.example.com/" is https://loot.example.com; a trailing /api/v1 is dropped.
pub fn normalize(address: &str) -> String {
    let mut s = address.trim().to_string();
    if s.is_empty() {
        return s;
    }
    if !s.contains("://") {
        s = format!("https://{s}");
    }
    while s.ends_with('/') {
        s.pop();
    }
    if let Some(t) = s.strip_suffix("/api/v1") {
        s = t.to_string();
    }
    s
}

/// Java `GobblerServer.State`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum State {
    #[default]
    Disconnected,
    Connecting,
    Online,
    Unreachable,
}

/// The connection test's result (Java `TestResult`).
#[derive(Debug, Clone, Default)]
pub struct TestResult {
    pub status: Status,
    pub millis: i64,
    pub your_ip: String,
    pub private_ip: bool,
    pub user: Option<User>,
    pub session_sent: bool,
}

/// A call for the network thread to make.
#[derive(Debug, Clone)]
pub enum Job {
    /// The status, then (with a session) who is logged in.
    Connect {
        url: String,
        token: String,
    },
    /// The status, then a ping.
    Test {
        url: String,
        token: String,
    },
    Login {
        url: String,
        ign: String,
        password: String,
        client: String,
    },
    /// Registers; an account that is active at once is logged in.
    Register {
        url: String,
        ign: String,
        password: String,
        role_token: String,
        client: String,
    },
    Logout {
        url: String,
        token: String,
    },
    Redeem {
        url: String,
        token: String,
        role_token: String,
    },
    /// Any other call: its answer comes back as `Done::Call` with the tag.
    Call {
        url: String,
        token: String,
        method: String,
        path: String,
        body: Option<String>,
        tag: String,
    },
    /// A new password with a reset code, then logged in with it.
    Reset {
        url: String,
        ign: String,
        code: String,
        password: String,
        client: String,
        forgot: bool,
    },
    /// A new password, proven with the current one.
    Change {
        url: String,
        token: String,
        current: String,
        password: String,
    },
    Data {
        url: String,
        token: String,
    },
    Put {
        url: String,
        token: String,
        kind: String,
        base: i64,
        text: String,
    },
}

/// A call's answer.
#[derive(Debug, Clone)]
pub enum Done {
    Connect {
        url: String,
        status: Result<Status, Error>,
        me: Option<Result<User, Error>>,
    },
    Test {
        url: String,
        result: Result<TestResult, Error>,
    },
    Login {
        url: String,
        result: Result<(String, User), Error>,
    },
    /// The session if the new account was logged in.
    Register {
        url: String,
        result: Result<(Option<String>, User), Error>,
    },
    Redeem {
        url: String,
        result: Result<User, Error>,
    },
    Call {
        url: String,
        path: String,
        tag: String,
        result: Result<Option<Json>, Error>,
    },
    /// The new session (after a reset), or none (a change).
    Password {
        url: String,
        forgot: bool,
        result: Result<Option<(String, User)>, Error>,
    },
    Data {
        url: String,
        result: Result<Vec<(String, DataItem)>, Error>,
    },
    Put {
        url: String,
        kind: String,
        result: Result<i64, Error>,
    },
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as i64)
}

/// Java `newPasswordProblem`: why a new password cannot be used (as the server checks it).
pub fn new_password_problem(password: &str, again: &str) -> Option<String> {
    let n = password.chars().count();
    if !(8..=128).contains(&n) {
        return Some("The new password must be 8 to 128 characters.".into());
    }
    if password != again {
        return Some("The two new passwords are not the same.".into());
    }
    None
}

const KEY_URL: &str = "serverUrl";
const KEY_TOKEN: &str = "serverToken";

/// Java `GobblerServer`: the server this client is connected to and the session on it. Calls go out as `jobs`,
/// answers come back through `done`; the settings to save go out as `props`.
#[derive(Debug, Default)]
pub struct Connection {
    pub url: String,
    pub token: String,
    pub state: State,
    pub status: Option<Status>,
    pub user: Option<User>,
    /// Why the last connect failed.
    pub error: String,
    /// "Loot Gobbler <version>", sent on logging in.
    pub client: String,
    pub jobs: Vec<Job>,
    pub props: Vec<(String, String)>,
    /// The Server tab's lines under the connection and the account, and the last connection test (None: none;
    /// Some(None): testing).
    pub connection_feedback: String,
    pub account_feedback: String,
    pub test: Option<Option<Result<TestResult, String>>>,
    /// A test waits for the connect to the typed address.
    test_after: bool,
    /// Logged in or registered: the password field empties.
    pub clear_password: bool,
    pub clear_register_token: bool,
    pub clear_redeem: bool,
    /// For moderators and admins: the approval queue, the members, the moderator tokens.
    pub moderation: Moderation,
    /// The item score weights and the guild settings (Java `ItemScore`, `Guildmates.settings`); `loaded` counts
    /// their loads, so the Server tab's fields show them again.
    pub weights: ScoreWeights,
    pub guild_settings: GuildSettings,
    pub loaded: u64,
    /// The Guild tab's leaderboard, drops and member profile.
    pub guild: crate::guild::GuildState,
}

/// An unused moderator token.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct OpenToken {
    pub id: i64,
    pub note: String,
    pub created_by: String,
    pub created_at: i64,
    pub expires_at: Option<i64>,
}

/// A moderator token just made: shown once.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct IssuedToken {
    pub id: i64,
    pub token: String,
    pub note: String,
    pub expires_at: i64,
}

/// A password reset code made for a player: shown once.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct ResetCode {
    pub code: String,
    pub ign: String,
    pub expires_at: i64,
}

/// The item score weights (0 to 1 each), set by an admin.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ScoreWeights {
    pub feed_power: f64,
    pub difficulty: f64,
    pub rarity: f64,
}

impl Default for ScoreWeights {
    fn default() -> Self {
        ScoreWeights { feed_power: 1.0, difficulty: 1.0, rarity: 1.0 }
    }
}

/// What the guild views compute with: the hits per minute a run counts as average.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct GuildSettings {
    pub baseline_hpm: f64,
}

impl Default for GuildSettings {
    fn default() -> Self {
        GuildSettings { baseline_hpm: 50.0 }
    }
}

/// The Server tab's moderation state (Java `ServerPanel`'s lists and feedback lines).
#[derive(Debug, Default)]
pub struct Moderation {
    pub pending: Vec<User>,
    pub members: Vec<User>,
    pub tokens: Vec<OpenToken>,
    pub last_issued: Option<IssuedToken>,
    /// The account and role the lists were loaded for.
    loaded_for: String,
    pub feedback: String,
    pub tokens_feedback: String,
    pub scoring_feedback: String,
    pub guild_feedback: String,
    /// A reset code to show (and copy) once.
    pub reset_code: Option<ResetCode>,
    /// A token was made: the note field empties.
    pub clear_note: bool,
}

fn double(v: Option<&Json>, default: f64) -> f64 {
    match v {
        Some(Json::Number(n)) | Some(Json::String(n)) => n.parse().unwrap_or(default),
        _ => default,
    }
}

fn users_of(j: &Json, key: &str) -> Vec<User> {
    match j.get(key) {
        Some(Json::Array(a)) => a.iter().map(user_of).collect(),
        _ => Vec::new(),
    }
}

fn weights_of(j: &Json) -> ScoreWeights {
    ScoreWeights {
        feed_power: double(j.get("feedPower"), 1.0),
        difficulty: double(j.get("difficulty"), 1.0),
        rarity: double(j.get("rarity"), 1.0),
    }
}

/// A number as Gson writes a double.
fn num(d: f64) -> String {
    crate::gson_out::double(d)
}

impl Connection {
    /// The saved server and session (Java reads them on start).
    pub fn new(props: &crate::properties::Properties, client: &str) -> Connection {
        Connection {
            url: props.get(KEY_URL).unwrap_or("").trim().to_string(),
            token: props.get(KEY_TOKEN).unwrap_or("").trim().to_string(),
            client: client.to_string(),
            ..Default::default()
        }
    }

    /// "<url>|<user id>" while logged in and online, else "".
    pub fn account(&self) -> String {
        match (&self.user, self.state) {
            (Some(u), State::Online) => format!("{}|{}", self.url, u.id),
            _ => String::new(),
        }
    }

    pub fn encrypted(&self) -> bool {
        self.url.starts_with("https://")
    }

    fn save(&mut self, key: &str, value: &str) {
        self.props.push((key.to_string(), value.to_string()));
    }

    /// Java `reconnect`: to the saved server, if there is one (on start).
    pub fn reconnect(&mut self) {
        if !self.url.is_empty() {
            let url = self.url.clone();
            self.connect(&url);
        }
    }

    /// Java `connect`: saves the address ("loot.example.com" is https://loot.example.com), asks the server for its
    /// status and, with a saved session, who is logged in.
    pub fn connect(&mut self, address: &str) {
        let normalized = normalize(address);
        if normalized != self.url {
            self.token.clear(); // a session belongs to one server
            self.save(KEY_TOKEN, "");
        }
        self.url = normalized.clone();
        self.save(KEY_URL, &normalized);
        self.state = State::Connecting;
        self.status = None;
        self.user = None;
        self.error.clear();
        if normalized.is_empty() {
            self.state = State::Disconnected;
            return;
        }
        self.jobs.push(Job::Connect { url: normalized, token: self.token.clone() });
    }

    /// The Server tab's "Test connection": connects to the typed address if it changed, then runs the test.
    pub fn test_connection(&mut self, address: &str) {
        if address.trim().is_empty() {
            self.connection_feedback = "Enter the server address first.".into();
            return;
        }
        self.connection_feedback.clear();
        self.test = Some(None);
        if normalize(address) == self.url {
            self.jobs.push(Job::Test { url: self.url.clone(), token: self.token.clone() });
        } else {
            self.test_after = true;
            self.connect(address);
        }
    }

    /// Java `disconnect`: logs out and forgets the server.
    pub fn disconnect(&mut self) {
        if !self.token.is_empty() {
            self.jobs.push(Job::Logout { url: self.url.clone(), token: self.token.clone() });
        }
        self.token.clear();
        self.save(KEY_TOKEN, "");
        self.url.clear();
        self.save(KEY_URL, "");
        self.state = State::Disconnected;
        self.status = None;
        self.user = None;
        self.test = None;
    }

    /// The Server tab's "Log in".
    pub fn login(&mut self, ign: &str, password: &str) {
        if ign.trim().is_empty() || password.is_empty() {
            self.account_feedback = "Enter your IGN and password.".into();
            return;
        }
        self.account_feedback = "Logging in...".into();
        self.jobs.push(Job::Login {
            url: self.url.clone(),
            ign: ign.trim().to_string(),
            password: password.to_string(),
            client: self.client.clone(),
        });
    }

    /// The Server tab's "Register": only with the name the game showed.
    pub fn register(&mut self, known: Option<&str>, password: &str, role_token: &str) {
        let Some(ign) = known.filter(|k| !k.trim().is_empty()) else {
            self.account_feedback = "Enter the Nexus or your vault in-game first, so Loot Gobbler knows your name.".into();
            return;
        };
        if password.is_empty() {
            self.account_feedback = "Enter the IGN and a password (at least 8 characters) for your new account.".into();
            return;
        }
        self.account_feedback = "Registering...".into();
        self.jobs.push(Job::Register {
            url: self.url.clone(),
            ign: ign.trim().to_string(),
            password: password.to_string(),
            role_token: role_token.to_string(),
            client: self.client.clone(),
        });
    }

    /// The Server tab's "Redeem": a token for the logged-in account.
    pub fn redeem(&mut self, role_token: &str) {
        if role_token.trim().is_empty() {
            return;
        }
        self.account_feedback = "Redeeming...".into();
        self.jobs.push(Job::Redeem { url: self.url.clone(), token: self.token.clone(), role_token: role_token.to_string() });
    }

    /// The Forgot password dialog's OK: the IGN, the reset code and the new password twice.
    pub fn forgot_password(&mut self, ign: &str, code: &str, password: &str, again: &str) {
        let problem = if ign.trim().is_empty() || code.trim().is_empty() {
            Some("Enter your IGN and the reset code.".to_string())
        } else {
            new_password_problem(password, again)
        };
        if let Some(p) = problem {
            self.account_feedback = p;
            return;
        }
        self.account_feedback = "Setting the new password...".into();
        self.jobs.push(Job::Reset {
            url: self.url.clone(),
            ign: ign.trim().to_string(),
            code: code.to_string(),
            password: password.to_string(),
            client: self.client.clone(),
            forgot: true,
        });
    }

    /// The Change password dialog's OK: proven with the current password, or with a reset code (which ends every
    /// session, this one too: logged in again with the new password).
    pub fn change_password(&mut self, with_code: bool, proof: &str, password: &str, again: &str) {
        let Some(me) = self.user.clone() else { return };
        let problem = if with_code && proof.trim().is_empty() {
            Some("Enter the reset code.".to_string())
        } else if !with_code && proof.is_empty() {
            Some("Enter your current password.".to_string())
        } else {
            new_password_problem(password, again)
        };
        if let Some(p) = problem {
            self.account_feedback = p;
            return;
        }
        self.account_feedback = "Changing the password...".into();
        self.jobs.push(if with_code {
            Job::Reset {
                url: self.url.clone(),
                ign: me.ign,
                code: proof.to_string(),
                password: password.to_string(),
                client: self.client.clone(),
                forgot: false,
            }
        } else {
            Job::Change { url: self.url.clone(), token: self.token.clone(), current: proof.to_string(), password: password.to_string() }
        });
    }

    fn call(&mut self, method: &str, path: &str, body: Option<String>, tag: &str) {
        self.jobs.push(Job::Call {
            url: self.url.clone(),
            token: self.token.clone(),
            method: method.into(),
            path: path.into(),
            body,
            tag: tag.into(),
        });
    }

    /// Java `rebuild`'s loading, once a frame: logged in as a moderator or admin (or with another role), the
    /// moderation lists are loaded afresh; an admin's item score weights and guild settings too.
    pub fn tick(&mut self) {
        let u = if self.state == State::Online { self.user.clone() } else { None };
        let m = u.as_ref().filter(|u| u.at_least_moderator());
        let key = m.map_or(String::new(), |u| format!("{}:{}", u.id, u.rank()));
        if key == self.moderation.loaded_for {
            return;
        }
        self.moderation.loaded_for = key;
        self.moderation.pending.clear();
        self.moderation.members.clear();
        self.moderation.tokens.clear();
        self.moderation.last_issued = None;
        if let Some(u) = m {
            let admin = u.at_least_admin();
            self.refresh_moderation();
            if admin {
                self.call("GET", "/scoring", None, "scoring");
                self.call("GET", "/guild/settings", None, "guild-settings");
            }
        }
    }

    /// Java `refreshModeration`: the approval queue, the members and (for admins) the unused moderator tokens.
    pub fn refresh_moderation(&mut self) {
        let Some(u) = self.user.clone().filter(|u| u.at_least_moderator()) else { return };
        self.call("GET", "/admin/requests", None, "requests");
        self.call("GET", "/admin/users", None, "users");
        if u.at_least_admin() {
            self.call("GET", "/admin/moderator-tokens", None, "tokens");
        } else {
            self.moderation.tokens.clear();
        }
    }

    /// Java `act`: an admin action; its outcome under the moderation (or the tokens), then the lists again.
    fn act(&mut self, method: &str, path: &str, body: Option<String>, tokens: bool, done: &str) {
        let line = if tokens { &mut self.moderation.tokens_feedback } else { &mut self.moderation.feedback };
        *line = "Working...".into();
        self.call(method, path, body, &format!("act:{}:{done}", if tokens { "tokens" } else { "moderation" }));
    }

    pub fn accept(&mut self, id: i64, ign: &str) {
        self.act("POST", &format!("/admin/requests/{id}/accept"), None, false, &format!("{ign} can log in now."));
    }

    pub fn deny(&mut self, id: i64, ign: &str) {
        self.act("POST", &format!("/admin/requests/{id}/deny"), None, false, &format!("{ign}'s registration was denied."));
    }

    /// Demotes: to "user" (a member again) or "moderator".
    pub fn set_role(&mut self, id: i64, ign: &str, role: &str) {
        let done = if role == "user" { format!("{ign} is a member again.") } else { format!("{ign} is a moderator now.") };
        let body = crate::gson_out::Obj::new().str("role", role).end();
        self.act("PATCH", &format!("/admin/users/{id}/role"), Some(body), false, &done);
    }

    pub fn remove(&mut self, id: i64, ign: &str) {
        self.act("DELETE", &format!("/admin/users/{id}"), None, false, &format!("{ign} was removed."));
    }

    /// Java `resetPasswordFor`: a reset code for a member, shown once.
    pub fn reset_code_for(&mut self, id: i64) {
        self.moderation.feedback = "Making a reset code...".into();
        self.call("POST", &format!("/admin/users/{id}/password-reset"), None, "reset-code");
    }

    /// Java `createToken`: a moderator token for someone, valid for `hours`.
    pub fn create_token(&mut self, note: &str, hours: i32) {
        self.moderation.tokens_feedback = "Creating...".into();
        let body = crate::gson_out::Obj::new().str("note", note.trim()).int("validHours", hours).end();
        self.call("POST", "/admin/moderator-tokens", Some(body), "create-token");
    }

    pub fn revoke_token(&mut self, id: i64) {
        if self.moderation.last_issued.as_ref().is_some_and(|t| t.id == id) {
            self.moderation.last_issued = None;
        }
        self.act("DELETE", &format!("/admin/moderator-tokens/{id}"), None, true, "Token revoked.");
    }

    /// Java `saveScoring`: the item score weights (each 0 to 1, checked by the tab).
    pub fn save_scoring(&mut self, w: ScoreWeights) {
        self.moderation.scoring_feedback = "Saving...".into();
        let body = format!("{{\"feedPower\":{},\"difficulty\":{},\"rarity\":{}}}", num(w.feed_power), num(w.difficulty), num(w.rarity));
        self.call("PUT", "/admin/scoring", Some(body), "put-scoring");
    }

    /// Java `saveGuild`: the guild settings (the baseline 1 to 1000, checked by the tab).
    pub fn save_guild(&mut self, g: GuildSettings) {
        self.moderation.guild_feedback = "Saving...".into();
        self.call("PUT", "/admin/guild/settings", Some(format!("{{\"baselineHpm\":{}}}", num(g.baseline_hpm))), "put-guild");
    }

    /// A call's answer, by its tag.
    fn called(&mut self, path: &str, tag: &str, result: Result<Option<Json>, Error>) {
        let j = match result {
            Err(e) => {
                if e.status() == 401 && !path.starts_with("/auth/") {
                    self.signed_out(); // session expired or revoked
                }
                let m = &mut self.moderation;
                match tag.split(':').collect::<Vec<_>>().as_slice() {
                    ["requests" | "users" | "tokens" | "reset-code"] | ["act", "moderation", ..] => m.feedback = e.message(),
                    ["create-token"] | ["act", "tokens", ..] => m.tokens_feedback = e.message(),
                    ["put-scoring"] => m.scoring_feedback = e.message(),
                    ["put-guild"] => m.guild_feedback = e.message(),
                    _ => {}
                }
                if tag.starts_with("act:") {
                    self.refresh_moderation();
                }
                match tag {
                    // Java shows the error in both
                    "board" | "drops" => {
                        self.guild.failed = true;
                        self.guild.board = Some(Err(e.clone()));
                        self.guild.drops = Some(Err(e));
                        self.guild_loaded();
                    }
                    "board-caravan" => {
                        self.guild.board = self.guild.board_wait.take().map(Ok);
                        self.guild_loaded();
                    }
                    "caravan" => self.caravan_done(Err(e)),
                    "questmaps" => self.maps_done(Err(e), now_ms()),
                    t if t.starts_with("member:") => {
                        if let Some((id, shown)) = &mut self.guild.member
                            && t == format!("member:{id}")
                        {
                            *shown = Some(Err(e));
                            self.guild.version += 1;
                        }
                    }
                    _ => {}
                }
                return;
            }
            Ok(j) => j.unwrap_or(Json::Null),
        };
        let m = &mut self.moderation;
        match tag {
            "requests" => m.pending = users_of(&j, "users"),
            "users" => m.members = users_of(&j, "users"),
            "tokens" => {
                m.tokens = match j.get("tokens") {
                    Some(Json::Array(a)) => a
                        .iter()
                        .map(|t| OpenToken {
                            id: long(t.get("id")),
                            note: text(t.get("note")),
                            created_by: text(t.get("createdBy")),
                            created_at: long(t.get("createdAt")),
                            expires_at: t.get("expiresAt").filter(|v| !matches!(v, Json::Null)).map(|v| long(Some(v))),
                        })
                        .collect(),
                    _ => Vec::new(),
                }
            }
            "reset-code" => {
                let rc = ResetCode { code: text(j.get("code")), ign: text(j.get("ign")), expires_at: long(j.get("expiresAt")) };
                m.feedback = format!("Reset code made for {}.", rc.ign);
                m.reset_code = Some(rc);
            }
            "create-token" => {
                m.last_issued = Some(IssuedToken {
                    id: long(j.get("id")),
                    token: text(j.get("token")),
                    note: text(j.get("note")),
                    expires_at: long(j.get("expiresAt")),
                });
                m.clear_note = true;
                m.tokens_feedback.clear();
                self.refresh_moderation();
            }
            "board" => {
                let board = crate::guild::leaderboard_of(&j);
                if board.members.iter().all(|m| m.tally.is_empty()) && !board.members.is_empty() {
                    // servers from before item scores: the all-time caravan's tallies stand in
                    self.guild.board_wait = Some(board);
                    self.call("GET", "/guild/caravan?range=all", None, "board-caravan");
                } else {
                    if !self.guild.failed {
                        self.guild.board = Some(Ok(board));
                    }
                    self.guild_loaded();
                }
            }
            "board-caravan" => {
                let c = crate::guild::caravan_of(&j);
                if let Some(mut board) = self.guild.board_wait.take() {
                    for m in &mut board.members {
                        if let Some(cm) = c.members.iter().find(|cm| cm.id == m.id) {
                            m.tally = if cm.tally.is_empty() {
                                cm.items
                                    .iter()
                                    .map(|it| crate::guild::Tally {
                                        item_id: it.item_id,
                                        r#where: it.r#where.clone(),
                                        enchants: it.enchants,
                                        count: 1,
                                    })
                                    .collect()
                            } else {
                                cm.tally.clone()
                            };
                        }
                    }
                    if !self.guild.failed {
                        self.guild.board = Some(Ok(board));
                    }
                }
                self.guild_loaded();
            }
            "caravan" => self.caravan_done(Ok(crate::guild::caravan_of(&j))),
            "questmaps" => self.maps_done(Ok(crate::guild::quest_maps_of(&j)), now_ms()),
            "drops" => {
                if !self.guild.failed {
                    self.guild.drops = Some(Ok(crate::guild::drops_of(&j)));
                }
                self.guild_loaded();
            }
            t if t.starts_with("member:") => {
                if let Some((id, shown)) = &mut self.guild.member
                    && t == format!("member:{id}")
                {
                    *shown = Some(Ok(crate::guild::profile_of(&j)));
                    self.guild.version += 1;
                }
            }
            "scoring" | "put-scoring" => {
                self.weights = weights_of(&j);
                self.loaded += 1;
                if tag == "put-scoring" {
                    self.moderation.scoring_feedback = "Saved. The Guild tab shows the new scores.".into();
                }
            }
            "guild-settings" | "put-guild" => {
                self.guild_settings = GuildSettings { baseline_hpm: double(j.get("baselineHpm"), 50.0) };
                self.loaded += 1;
                if tag == "put-guild" {
                    self.moderation.guild_feedback = "Saved. Everyone's dungeon details use it.".into();
                }
            }
            t => {
                if let Some(rest) = t.strip_prefix("act:") {
                    let (which, done) = rest.split_once(':').unwrap_or((rest, ""));
                    if which == "tokens" {
                        m.tokens_feedback = done.into();
                    } else {
                        m.feedback = done.into();
                    }
                    self.refresh_moderation();
                }
            }
        }
    }

    /// Java `GuildPanel.refresh`: the item score weights, the leaderboard and the recent drops (logged out: nothing).
    pub fn guild_refresh(&mut self) {
        if self.user.is_none() || self.state != State::Online {
            self.guild.board = None;
            self.guild.drops = None;
            return;
        }
        self.call("GET", "/scoring", None, "scoring");
        self.caravan_load();
        self.maps_load();
        if self.guild.loading > 0 {
            return;
        }
        self.guild.loading = 2;
        self.guild.failed = false;
        self.call("GET", "/guild/leaderboard", None, "board");
        self.call("GET", "/guild/drops?limit=100", None, "drops");
    }

    /// Java `CaravanView.load`: the guild's loot caravan (all time); asked while loading: once more afterwards.
    pub fn caravan_load(&mut self) {
        if self.guild.caravan_loading {
            self.guild.caravan_again = true;
            return;
        }
        self.guild.caravan_stale = false;
        if self.user.is_none() || self.state != State::Online {
            self.guild.caravan = None;
            self.guild.version += 1;
            return;
        }
        self.guild.caravan_loading = true;
        self.call("GET", "/guild/caravan?range=all", None, "caravan");
    }

    fn caravan_done(&mut self, c: Result<crate::guild::Caravan, Error>) {
        self.guild.caravan_loading = false;
        self.guild.caravan = Some(c);
        self.guild.version += 1;
        if std::mem::take(&mut self.guild.caravan_again) {
            self.caravan_load();
        }
    }

    /// Java `QuestMapView.load`: the current quest map and the last 20.
    pub fn maps_load(&mut self) {
        if self.guild.maps_loading {
            self.guild.maps_again = true;
            return;
        }
        self.guild.maps_stale = false;
        if self.user.is_none() || self.state != State::Online {
            self.guild.quest_maps = None;
            self.guild.version += 1;
            return;
        }
        self.guild.maps_loading = true;
        self.call("GET", "/guild/questmaps?limit=20", None, "questmaps");
    }

    fn maps_done(&mut self, r: Result<crate::guild::QuestMaps, Error>, now: i64) {
        self.guild.maps_loading = false;
        if let Ok(m) = &r {
            self.guild.skew = m.now - now;
        }
        self.guild.quest_maps = Some(r);
        self.guild.keep_pending(now);
        self.guild.version += 1;
        if std::mem::take(&mut self.guild.maps_again) {
            self.maps_load();
        }
    }

    /// Java `openMember`: a player's profile.
    pub fn open_member(&mut self, id: i64) {
        self.guild.member = Some((id, None));
        self.guild.version += 1;
        self.call("GET", &format!("/guild/members/{id}"), None, &format!("member:{id}"));
    }

    /// One of the guild's two loads is in (Java `allOf`): then the member shown is loaded again.
    fn guild_loaded(&mut self) {
        self.guild.loading = self.guild.loading.saturating_sub(1);
        self.guild.version += 1;
        if self.guild.loading == 0
            && let Some((id, _)) = self.guild.member
        {
            self.call("GET", &format!("/guild/members/{id}"), None, &format!("member:{id}"));
        }
    }

    /// Java `logout`.
    pub fn logout(&mut self) {
        self.account_feedback.clear();
        if !self.token.is_empty() {
            self.jobs.push(Job::Logout { url: self.url.clone(), token: self.token.clone() });
        }
        self.signed_out();
    }

    /// Java `signedOut`: the session is gone.
    pub fn signed_out(&mut self) {
        self.token.clear();
        self.save(KEY_TOKEN, "");
        self.user = None;
    }

    /// An answer from the network thread. The sync's answers (data, uploads) are handed back for it; a 401 there
    /// means the session expired or was revoked.
    pub fn done(&mut self, d: Done) -> Option<Done> {
        match d {
            Done::Connect { url, status, me } => {
                if url != self.url {
                    return None; // another address meanwhile
                }
                match status {
                    Ok(s) => {
                        self.status = Some(s);
                        self.state = State::Online;
                        match me {
                            Some(Ok(u)) => self.user = Some(u),
                            // an expired session just means "log in again"
                            Some(Err(e)) if e.status() == 401 => self.signed_out(),
                            _ => {}
                        }
                    }
                    Err(e) => {
                        self.state = State::Unreachable;
                        self.error = e.message();
                    }
                }
                if std::mem::take(&mut self.test_after) {
                    self.jobs.push(Job::Test { url: self.url.clone(), token: self.token.clone() });
                }
                None
            }
            Done::Test { url, result } => {
                if url != self.url {
                    return None;
                }
                match &result {
                    Ok(r) => {
                        self.status = Some(r.status.clone());
                        self.state = State::Online;
                        self.error.clear();
                        if r.session_sent && r.user.is_none() {
                            self.signed_out(); // the server no longer knows the session
                        } else if let Some(u) = &r.user {
                            self.user = Some(u.clone());
                        }
                    }
                    Err(e) => {
                        self.state = State::Unreachable;
                        self.error = e.message();
                    }
                }
                self.test = Some(Some(result.map_err(|e| e.message())));
                None
            }
            Done::Login { url, result } => {
                match result {
                    Err(e) => self.account_feedback = e.message(),
                    Ok((token, user)) if url == self.url => {
                        self.token = token.clone();
                        self.save(KEY_TOKEN, &token);
                        self.user = Some(user);
                        self.clear_password = true;
                        self.account_feedback.clear();
                    }
                    Ok(_) => {}
                }
                None
            }
            Done::Register { url, result } => {
                match result {
                    Err(e) => self.account_feedback = e.message(),
                    Ok((token, user)) => {
                        self.clear_password = true;
                        self.clear_register_token = true;
                        if let Some(t) = token.filter(|_| url == self.url) {
                            self.token = t.clone();
                            self.save(KEY_TOKEN, &t);
                            self.user = Some(user.clone());
                        }
                        self.account_feedback = if user.status == "active" {
                            String::new()
                        } else {
                            format!("Registered. A moderator or admin needs to accept {} before you can log in.", user.ign)
                        };
                    }
                }
                None
            }
            Done::Password { url, forgot, result } => {
                match result {
                    Err(e) => {
                        if e.status() == 401 && !forgot {
                            self.signed_out();
                        }
                        self.account_feedback = e.message();
                    }
                    Ok(session) => {
                        if let Some((token, user)) = session.filter(|_| url == self.url) {
                            self.token = token.clone();
                            self.save(KEY_TOKEN, &token);
                            self.user = Some(user);
                        }
                        if forgot {
                            self.clear_password = true;
                            self.account_feedback = "New password set. You are logged in.".into();
                        } else {
                            self.account_feedback = "Password changed. Your other devices need to log in again.".into();
                        }
                    }
                }
                None
            }
            Done::Call { url, path, tag, result } => {
                if url == self.url {
                    self.called(&path, &tag, result);
                }
                None
            }
            Done::Redeem { url, result } => {
                match result {
                    Err(e) => {
                        if e.status() == 401 {
                            self.signed_out();
                        }
                        self.account_feedback = e.message();
                    }
                    Ok(u) => {
                        self.clear_redeem = true;
                        self.account_feedback = format!("You are now {}.", u.role_name().to_lowercase());
                        if url == self.url {
                            self.user = Some(u);
                        }
                    }
                }
                None
            }
            d @ (Done::Data { .. } | Done::Put { .. }) => {
                let unauthorized = match &d {
                    Done::Data { result: Err(e), .. } | Done::Put { result: Err(e), .. } => e.status() == 401,
                    _ => false,
                };
                if unauthorized {
                    self.signed_out();
                }
                Some(d)
            }
        }
    }
}
