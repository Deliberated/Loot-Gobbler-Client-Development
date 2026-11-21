//! The records kept in sync with the Loot Gobbler server (Java: each store's `Syncable` side): one JSON document per
//! kind, in the shape of its file. Merging never loses progress: whatever either side has is kept.

use crate::App;
use crate::files;
use lg_world::json::Json;

/// The kinds in Java's order (`ServerSync.kinds`): the server document's name and the Server tab's label.
pub const KINDS: [(&str, &str); 10] = [
    ("collection", "Collection"),
    ("characters", "Characters"),
    ("exalts", "Exaltations"),
    ("pets", "Pets"),
    ("dps_history", "DPS history"),
    ("profile", "Profile"),
    ("dungeon_visits", "Dungeon visits"),
    ("amulet_saves", "Amulet saves"),
    ("holdings", "Inventory & vaults"),
    ("upe", "UPE runs"),
];

impl App {
    /// Java `exportSync`: the kind's data as the server keeps it (the file's shape, compact).
    pub fn sync_export(&mut self, kind: &str) -> String {
        match kind {
            "collection" => files::collection(&self.collection),
            "characters" => files::chars(&self.characters),
            "exalts" => crate::gson_out::map(self.exalts.progress.iter().map(|(k, v)| (k.to_string(), Some(crate::gson_out::ints(v))))),
            "pets" => files::pets(&self.pets),
            "dps_history" => files::runs(&self.dps_history),
            "profile" => files::profile(&self.profile),
            "dungeon_visits" => self.dungeon_visits.export_sync(&self.dps_history),
            "amulet_saves" => files::amulet(&self.amulet_saves),
            "holdings" => files::holdings_json(&self.holdings),
            "upe" => files::upe(&mut self.upe),
            _ => "null".into(),
        }
    }

    /// Java `mergeSync`: a document from the server (or another PC) merged into the local data. True if the local
    /// data changed; Err where Java's Gson would throw (the sync run fails).
    pub fn sync_merge(&mut self, kind: &str, remote: &Json, now: i64) -> Result<bool, crate::java::Thrown> {
        let changed = match kind {
            "collection" => self.collection.merge_sync(remote)?,
            "characters" => self.characters.merge_sync(remote)?,
            "exalts" => self.exalts.merge_sync(remote)?,
            "pets" => self.pets.merge_sync(remote)?,
            "dps_history" => self.dps_history.merge_sync(remote)?,
            "profile" => self.profile.merge_sync(remote)?,
            "dungeon_visits" => self.dungeon_visits.merge_sync(remote, &self.amulet_saves)?,
            "amulet_saves" => self.amulet_saves.merge_sync(remote, &mut self.dungeon_visits, now)?,
            "holdings" => {
                let time = self.zone.local_date_time(now);
                let ctx = crate::holdings::Ctx { data: self.world.data(), now, time: &time };
                let changed = self.holdings.merge_sync(&ctx, remote)?;
                self.vault_changes();
                changed
            }
            "upe" => self.upe.merge_sync(remote),
            _ => false,
        };
        Ok(changed)
    }

    /// Java `syncCount`: how many entries the kind has, for the Server tab.
    pub fn sync_count(&self, kind: &str) -> usize {
        match kind {
            "collection" => self.collection.all().count(),
            "characters" => self.characters.all().count(),
            "exalts" => self.exalts.progress.len(),
            "pets" => self.pets.pets.len(),
            "dps_history" => self.dps_history.runs.len(),
            "profile" => usize::from(!self.profile.ign.is_empty()),
            "dungeon_visits" => self.dungeon_visits.visits.len(),
            "amulet_saves" => self.amulet_saves.count(),
            "holdings" => self.holdings.places.len(),
            "upe" => self.upe.runs.values().filter(|r| r.marked || r.eligible()).count(),
            _ => 0,
        }
    }
}

// --- Java `ServerSync`: downloading, merging and uploading ---

use crate::server::{DataItem, Done, Error, Job};

/// Java `ServerSync.Phase`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Phase {
    #[default]
    Off,
    Syncing,
    UpToDate,
    Waiting,
    Offline,
    OtherAccount,
}

/// The sync state of one kind (Java `Kind`).
#[derive(Debug, Clone, Default)]
pub struct Kind {
    pub kind: &'static str,
    pub label: &'static str,
    /// The server revision our data is based on, 0 = none yet.
    pub revision: i64,
    /// What the server has at that revision (to skip unchanged uploads).
    synced: Option<Json>,
    pub synced_at: i64,
    /// When server data last changed the local data / local data was last uploaded, 0 = not since login.
    pub last_down: i64,
    pub last_up: i64,
    /// The start of the latest sync run that looked at this kind, 0 = none.
    pub checked_at: i64,
    pub dirty: bool,
}

const UPLOAD_DELAY_MS: i64 = 60_000; // changes come in bursts while playing
const SOON_MS: i64 = 2_000; // a few bags drop at once
const RETRY_MS: i64 = 60_000;
const MAX_CONFLICT_RETRIES: u32 = 3;
/// "<server url>|<user id>|<ign>" the files belong to.
pub const KEY_OWNER: &str = "serverSyncOwner";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Task {
    Full,
    Upload,
}

/// A run waiting for the server's answer.
#[derive(Debug, Clone)]
enum Waiting {
    Data { acc: String, start: i64 },
    Put { start: i64, index: usize, attempt: u32, data: Json },
}

/// Java `ServerSync`: one run at a time (Java's single sync thread); calls go out as `Job`s.
#[derive(Debug)]
pub struct ServerSync {
    pub kinds: Vec<Kind>,
    /// "<url>|<user id>" while logged in, else "".
    account: String,
    pub phase: Phase,
    /// Details for the phase (the error, or which account the files belong to).
    pub message: String,
    /// When everything last matched the server, 0 = not yet.
    pub last_sync: i64,
    soon: bool,
    /// The next run and when (Java's `scheduled`), and one asked for right away (`syncNow`).
    scheduled: Option<(i64, Task)>,
    now_full: bool,
    waiting: Option<Waiting>,
    /// The settings to save (the owner), and the kinds uploaded since last asked (the guild views reload).
    pub props: Vec<(String, String)>,
    pub uploaded: Vec<&'static str>,
}

impl Default for ServerSync {
    fn default() -> Self {
        ServerSync {
            kinds: KINDS.iter().map(|&(kind, label)| Kind { kind, label, ..Default::default() }).collect(),
            account: String::new(),
            phase: Phase::Off,
            message: String::new(),
            last_sync: 0,
            soon: false,
            scheduled: None,
            now_full: false,
            waiting: None,
            props: Vec::new(),
            uploaded: Vec::new(),
        }
    }
}

/// The connection the sync goes over: where to send its calls.
pub struct Link<'a> {
    pub url: &'a str,
    pub token: &'a str,
    /// The logged-in account's name.
    pub ign: &'a str,
    /// The owner setting (`KEY_OWNER`).
    pub owner: Option<&'a str>,
}

impl ServerSync {
    /// Java `serverChanged`: logged in, out, or to another account (`account`: "<url>|<user id>" or "").
    pub fn account(&mut self, account: &str) {
        if account == self.account {
            return;
        }
        self.account = account.to_string();
        for k in &mut self.kinds {
            k.revision = 0;
            k.synced = None;
            k.synced_at = 0;
            k.last_down = 0;
            k.last_up = 0;
            k.checked_at = 0;
        }
        if account.is_empty() {
            self.set(Phase::Off, "");
        } else {
            self.sync_now();
        }
    }

    /// Java `syncNow`: download, merge and upload everything now.
    pub fn sync_now(&mut self) {
        self.now_full = true;
    }

    /// Java `adoptForThisAccount`: the files the player confirmed as theirs become this account's.
    pub fn adopt(&mut self, ign: &str) {
        if self.account.is_empty() {
            return;
        }
        self.props.push((KEY_OWNER.into(), format!("{}|{ign}", self.account)));
        self.sync_now();
    }

    /// Java `localChanged`: the kind's local data changed.
    pub fn local_changed(&mut self, kind: &str, now: i64) {
        if let Some(k) = self.kinds.iter_mut().find(|k| k.kind == kind) {
            k.dirty = true;
        }
        if self.account.is_empty() || matches!(self.phase, Phase::OtherAccount | Phase::Syncing) {
            return;
        }
        self.schedule(UPLOAD_DELAY_MS, now);
    }

    /// Java `uploadSoon`: a change the guild views show goes up within seconds.
    pub fn upload_soon(&mut self, now: i64) {
        self.soon = true;
        if self.account.is_empty() || matches!(self.phase, Phase::OtherAccount | Phase::Syncing) {
            return; // picked up after
        }
        if let Some((at, Task::Upload)) = self.scheduled
            && at - now > SOON_MS
        {
            self.scheduled = None;
        }
        self.schedule(SOON_MS, now);
    }

    fn schedule(&mut self, delay: i64, now: i64) {
        if self.scheduled.is_some() {
            return;
        }
        self.scheduled = Some((now + delay, Task::Upload));
        if self.phase == Phase::UpToDate {
            self.set(Phase::Waiting, "");
        }
    }

    /// Whether a run waits for the server.
    pub fn busy(&self) -> bool {
        self.waiting.is_some()
    }

    /// Starts the run that is due, if none waits for the server: its first call.
    pub fn tick(&mut self, app: &mut App, link: &Link, now: i64) -> Option<Job> {
        if self.waiting.is_some() {
            return None;
        }
        let task = if std::mem::take(&mut self.now_full) {
            Task::Full
        } else {
            match self.scheduled {
                Some((at, t)) if at <= now => t,
                _ => return None,
            }
        };
        match task {
            Task::Full => self.full_sync(link, now),
            Task::Upload => self.upload(app, link, now, now),
        }
    }

    /// Java `running`: called first by every run, so a change during the run can schedule the next one.
    fn running(&mut self) {
        self.scheduled = None;
        self.soon = false;
    }

    fn full_sync(&mut self, link: &Link, now: i64) -> Option<Job> {
        self.running();
        if self.account.is_empty() {
            return None;
        }
        let acc = self.account.clone();
        if let Some(owner) = link.owner.filter(|o| !o.trim().is_empty())
            && !owner.starts_with(&format!("{acc}|"))
        {
            let parts: Vec<&str> = owner.split('|').collect();
            let who = if parts.len() >= 3 { format!("{} on {}", parts[2], parts[0]) } else { owner.to_string() };
            self.set(Phase::OtherAccount, &who);
            return None;
        }
        self.set(Phase::Syncing, "");
        self.waiting = Some(Waiting::Data { acc, start: now });
        Some(Job::Data { url: link.url.into(), token: link.token.into() })
    }

    /// The answer to the sync's call: the next call, if any.
    pub fn done(&mut self, app: &mut App, link: &Link, d: Done, now: i64) -> Option<Job> {
        match (self.waiting.take(), d) {
            (Some(Waiting::Data { acc, start }), Done::Data { result, .. }) => {
                if acc != self.account {
                    return None; // logged out or switched account meanwhile
                }
                let remote = match result {
                    Ok(r) => r,
                    Err(e) => return self.failed(&e, now),
                };
                for i in 0..self.kinds.len() {
                    let kind = self.kinds[i].kind;
                    self.kinds[i].checked_at = start;
                    match remote.iter().find(|(k, _)| k == kind).map(|(_, item)| item) {
                        Some(DataItem { revision, data: Some(data), .. }) => {
                            match app.sync_merge(kind, data, now) {
                                Ok(true) => self.kinds[i].last_down = now,
                                Ok(false) => {}
                                Err(_) => {
                                    return self.failed(&Error::Net(format!("The server's {kind} could not be read")), now);
                                }
                            }
                            self.kinds[i].revision = *revision;
                            self.kinds[i].synced = Some(data.clone());
                        }
                        _ => {
                            self.kinds[i].revision = 0;
                            self.kinds[i].synced = None;
                        }
                    }
                    self.kinds[i].dirty = true; // the upload decides whether there is anything new
                }
                self.props.push((KEY_OWNER.into(), format!("{acc}|{}", link.ign)));
                self.upload(app, link, start, now)
            }
            (Some(Waiting::Put { start, index, attempt, data }), Done::Put { result, .. }) => match result {
                Ok(revision) => {
                    let k = &mut self.kinds[index];
                    k.revision = revision;
                    k.synced = Some(data);
                    k.synced_at = now;
                    k.last_up = now;
                    self.uploaded.push(k.kind);
                    self.upload_from(app, link, start, index + 1, now)
                }
                Err(Error::Server { code, current: Some(current), .. })
                    if code == "revision_conflict" && attempt < MAX_CONFLICT_RETRIES =>
                {
                    // another PC uploaded in between: its version taken in, then the combination uploaded
                    let kind = self.kinds[index].kind;
                    match app.sync_merge(kind, current.data.as_ref().unwrap_or(&Json::Null), now) {
                        Ok(true) => self.kinds[index].last_down = now,
                        Ok(false) => {}
                        Err(_) => {
                            self.kinds[index].dirty = true;
                            return self.failed(&Error::Net(format!("The server's {kind} could not be read")), now);
                        }
                    }
                    self.kinds[index].revision = current.revision;
                    self.kinds[index].synced = current.data;
                    self.upload_kind(app, link, start, index, attempt + 1, now)
                }
                Err(e) => {
                    self.kinds[index].dirty = true;
                    self.failed(&e, now)
                }
            },
            (w, _) => {
                self.waiting = w; // not this run's answer
                None
            }
        }
    }

    /// Java `upload`: every changed kind, in order.
    fn upload(&mut self, app: &mut App, link: &Link, start: i64, now: i64) -> Option<Job> {
        self.running();
        if self.account.is_empty() || self.phase == Phase::OtherAccount {
            return None;
        }
        self.upload_from(app, link, start, 0, now)
    }

    fn upload_from(&mut self, app: &mut App, link: &Link, start: i64, from: usize, now: i64) -> Option<Job> {
        for i in from..self.kinds.len() {
            if !self.kinds[i].dirty {
                continue;
            }
            if self.kinds[i].checked_at < start {
                self.kinds[i].checked_at = start;
            }
            if let Some(job) = self.upload_kind(app, link, start, i, 0, now) {
                return Some(job);
            }
        }
        self.last_sync = now;
        let waiting = self.kinds.iter().any(|k| k.dirty);
        self.set(if waiting { Phase::Waiting } else { Phase::UpToDate }, "");
        if waiting {
            // changed again while uploading
            self.schedule(if self.soon { SOON_MS } else { UPLOAD_DELAY_MS }, now);
        }
        None
    }

    /// Java `uploadKind`: the kind's data, unless the server has it already.
    fn upload_kind(&mut self, app: &mut App, link: &Link, start: i64, index: usize, attempt: u32, now: i64) -> Option<Job> {
        let k = &mut self.kinds[index];
        k.dirty = false; // a change from now on marks it again
        let text = app.sync_export(k.kind);
        let data = lg_world::json::parse(&text).unwrap_or(Json::Null);
        if k.synced.as_ref().is_some_and(|s| s.same(&data)) {
            k.synced_at = now;
            return if attempt == 0 { None } else { self.upload_from(app, link, start, index + 1, now) };
        }
        let job = Job::Put { url: link.url.into(), token: link.token.into(), kind: k.kind.into(), base: k.revision, text };
        self.waiting = Some(Waiting::Put { start, index, attempt, data });
        Some(job)
    }

    /// Java `failed`: a 401 ends the sync (logged out); anything else is retried in a minute.
    fn failed(&mut self, e: &Error, now: i64) -> Option<Job> {
        self.waiting = None;
        if e.status() == 401 {
            self.set(Phase::Off, "");
            return None;
        }
        self.set(Phase::Offline, &e.message());
        if self.scheduled.is_none() {
            self.scheduled = Some((now + RETRY_MS, Task::Full));
        }
        None
    }

    fn set(&mut self, p: Phase, msg: &str) {
        self.phase = p;
        self.message = msg.to_string();
    }
}

impl App {
    /// The kinds whose local data changed since the files were last written (their stores' `dirty`), for the
    /// sync's `local_changed`; and whether a change wants up soon (cleared).
    pub fn sync_changes(&mut self) -> (Vec<&'static str>, bool) {
        let dirty = [
            self.collection.dirty,
            self.characters.dirty,
            self.exalts.dirty,
            self.pets.dirty,
            self.dps_history.dirty,
            self.profile.dirty,
            self.dungeon_visits.dirty,
            self.amulet_saves.dirty,
            self.holdings.dirty,
            self.upe.dirty,
        ];
        let kinds = KINDS.iter().zip(dirty).filter(|(_, d)| *d).map(|((k, _), _)| *k).collect();
        let soon = std::mem::take(&mut self.collection.soon)
            | std::mem::take(&mut self.dps_history.soon)
            | std::mem::take(&mut self.dungeon_visits.soon)
            | std::mem::take(&mut self.upe.soon);
        (kinds, soon)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::server::{DataItem, Done, Error, Job};

    fn app() -> App {
        App::new(lg_world::World::new(lg_gamedata::GameData::default()), 0)
    }

    const LINK: Link = Link { url: "http://x", token: "t", ign: "Me", owner: None };

    /// Answers every upload with the next revision until the run ends; the kinds uploaded.
    fn answer_puts(s: &mut ServerSync, a: &mut App, mut job: Option<Job>, now: i64) -> Vec<String> {
        let mut put = Vec::new();
        while let Some(Job::Put { kind, base, .. }) = job {
            put.push(kind.clone());
            job = s.done(a, &LINK, Done::Put { url: "http://x".into(), kind, result: Ok(base + 1) }, now);
        }
        put
    }

    #[test]
    fn syncs_skips_unchanged_and_merges_a_conflict() {
        let (mut a, mut s) = (app(), ServerSync::default());
        s.account("http://x|1");
        let Some(Job::Data { .. }) = s.tick(&mut a, &LINK, 1000) else { panic!("first the data") };
        assert_eq!(s.phase, Phase::Syncing);
        // nothing on the server yet: everything goes up, in Java's order
        let job = s.done(&mut a, &LINK, Done::Data { url: "http://x".into(), result: Ok(Vec::new()) }, 1000);
        let put = answer_puts(&mut s, &mut a, job, 1000);
        assert_eq!(put, KINDS.iter().map(|(k, _)| k.to_string()).collect::<Vec<_>>());
        assert_eq!((s.phase, s.kinds[0].revision), (Phase::UpToDate, 1));
        assert_eq!(s.props, vec![(KEY_OWNER.to_string(), "http://x|1|Me".to_string())]);

        // a change noted but the data the same as the server's: nothing uploaded, a minute later
        s.local_changed("collection", 2000);
        assert_eq!(s.phase, Phase::Waiting);
        assert!(s.tick(&mut a, &LINK, 2000 + 59_000).is_none());
        assert!(s.tick(&mut a, &LINK, 2000 + 60_000).is_none());
        assert_eq!(s.phase, Phase::UpToDate);

        // a new find: uploaded soon; another PC uploaded meanwhile, so its version is merged in and sent again
        a.collection.record(2592, crate::collection::Find::of("drop", 0, "Nexus", "white", 5000, "2026-11-21T10:00"));
        s.local_changed("collection", 5000);
        s.upload_soon(5000);
        let Some(Job::Put { kind, base, .. }) = s.tick(&mut a, &LINK, 7000) else { panic!("an upload") };
        assert_eq!((kind.as_str(), base), ("collection", 1));
        let theirs = lg_world::json::parse(r#"{"2593":{"first":"2026-11-20T09:00","last":"2026-11-20T09:00","count":1}}"#).unwrap();
        let conflict = Error::Server {
            status: 409,
            code: "revision_conflict".into(),
            message: "".into(),
            current: Some(DataItem { revision: 7, updated_at: 0, data: Some(theirs) }),
        };
        let job = s.done(&mut a, &LINK, Done::Put { url: "".into(), kind: "collection".into(), result: Err(conflict) }, 7000);
        let Some(Job::Put { base, text, .. }) = &job else { panic!("sent again") };
        assert_eq!(*base, 7);
        assert!(text.contains("\"2592\"") && text.contains("\"2593\""), "both finds: {text}");
        assert!(a.collection.contains(2593));
        assert_eq!(answer_puts(&mut s, &mut a, job, 7000), vec!["collection"]);
        assert_eq!((s.kinds[0].revision, s.kinds[0].last_down, s.kinds[0].last_up), (8, 7000, 7000));

        // the session ended: the sync stops
        a.collection.record(2594, crate::collection::Find::of("drop", 0, "Nexus", "white", 9000, "2026-11-21T10:01"));
        s.local_changed("collection", 9000);
        let job = s.tick(&mut a, &LINK, 9000 + 60_000);
        let unauthorized = Error::Server { status: 401, code: "unauthorized".into(), message: "".into(), current: None };
        assert!(job.is_some());
        s.done(&mut a, &LINK, Done::Put { url: "".into(), kind: "collection".into(), result: Err(unauthorized) }, 70_000);
        assert_eq!(s.phase, Phase::Off);
        assert!(s.kinds[0].dirty);
    }

    #[test]
    fn unreachable_is_retried_and_another_accounts_files_wait() {
        let (mut a, mut s) = (app(), ServerSync::default());
        s.account("http://x|1");
        s.tick(&mut a, &LINK, 0);
        s.done(&mut a, &LINK, Done::Data { url: "".into(), result: Err(Error::Net("Server not reachable".into())) }, 0);
        assert_eq!((s.phase, s.message.as_str()), (Phase::Offline, "Server not reachable"));
        assert!(s.tick(&mut a, &LINK, 59_999).is_none());
        assert!(matches!(s.tick(&mut a, &LINK, 60_000), Some(Job::Data { .. })));

        let (mut a, mut s) = (app(), ServerSync::default());
        let other = Link { owner: Some("http://x|2|Someone"), ..LINK };
        s.account("http://x|1");
        assert!(s.tick(&mut a, &other, 0).is_none());
        assert_eq!((s.phase, s.message.as_str()), (Phase::OtherAccount, "Someone on http://x"));
        s.local_changed("collection", 0);
        assert!(s.tick(&mut a, &other, 120_000).is_none(), "paused");
        s.adopt("Me");
        assert_eq!(s.props, vec![(KEY_OWNER.to_string(), "http://x|1|Me".to_string())]);
        let mine = Link { owner: Some("http://x|1|Me"), ..LINK };
        assert!(matches!(s.tick(&mut a, &mine, 120_000), Some(Job::Data { .. })));
    }
}
