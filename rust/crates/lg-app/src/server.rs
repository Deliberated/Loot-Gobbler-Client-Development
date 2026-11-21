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
