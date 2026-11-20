//! The season's missions (Java: `Missions`): what each asks and rewards (`missions/getClientSeasons`, JSON) and your
//! progress and claims (`missions/getPlayerMissions`), from the game's web API; progress steps the game connection
//! sends (packet 165) are added on top until the next fetch.
//!
//! The UI's views of them come with the UI.

use crate::gson;
use crate::java::{self, Thrown};
use lg_world::json::{self, Json};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Cond {
    pub kind: i32,
    pub target: Option<Json>,
    pub amount: i32,
    pub time: Option<i32>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Reward {
    pub kind: i32,
    pub target: Option<Json>,
    pub amount: i64,
}

impl Reward {
    /// Java `itemId`.
    pub fn item_id(&self) -> i32 {
        match &self.target {
            None | Some(Json::Null) => 0,
            Some(t) => t.as_string().ok().and_then(|s| java::parse_int(java::trim(&s)).ok()).unwrap_or(0),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Mission {
    pub id: i32,
    pub name: Option<String>,
    pub desc: Option<String>,
    pub conds: Vec<Option<Cond>>,
    pub cond_spread: Option<String>,
    pub rewards: Vec<Option<Reward>>,
    pub rewards_spread: Option<String>,
    pub worn_restriction: Option<Json>,
    pub parents_str: Option<String>,
    pub icon: Option<String>,
    pub interval: f64,
    pub participants: i32,
}

impl Default for Mission {
    fn default() -> Self {
        Mission {
            id: 0,
            name: Some(String::new()),
            desc: Some(String::new()),
            conds: Vec::new(),
            cond_spread: None,
            rewards: Vec::new(),
            rewards_spread: None,
            worn_restriction: None,
            parents_str: Some(String::new()),
            icon: Some(String::new()),
            interval: 0.0,
            participants: 0,
        }
    }
}

impl Mission {
    /// Java `groups`: the objectives grouped (within a group any one is enough).
    pub fn groups(&self) -> Vec<Vec<usize>> {
        match &self.cond_spread {
            Some(s) if s.encode_utf16().count() == self.conds.len() => {
                let mut by: Vec<(u16, Vec<usize>)> = Vec::new();
                for (i, c) in s.encode_utf16().enumerate() {
                    match by.iter_mut().find(|(k, _)| *k == c) {
                        Some((_, v)) => v.push(i),
                        None => by.push((c, vec![i])),
                    }
                }
                by.into_iter().map(|(_, v)| v).collect()
            }
            _ => (0..self.conds.len()).map(|i| vec![i]).collect(),
        }
    }

    /// Java `complete`: progress meets every group (a null objective throws).
    pub fn complete(&self, progress: &[i32]) -> Result<bool, Thrown> {
        let groups = self.groups();
        if groups.is_empty() {
            return Ok(false);
        }
        for g in groups {
            let mut any = false;
            for i in g {
                if i < progress.len() && progress[i] >= self.conds[i].as_ref().ok_or(Thrown)?.amount {
                    any = true;
                }
            }
            if !any {
                return Ok(false);
            }
        }
        Ok(true)
    }

    /// Java `rewardGroups`: the item rewards per group (one of each group is given).
    pub fn reward_groups(&self) -> Vec<Vec<&Option<Reward>>> {
        match &self.rewards_spread {
            Some(s) if s.encode_utf16().count() == self.rewards.len() => {
                let mut by: Vec<(u16, Vec<&Option<Reward>>)> = Vec::new();
                for (i, c) in s.encode_utf16().enumerate() {
                    match by.iter_mut().find(|(k, _)| *k == c) {
                        Some((_, v)) => v.push(&self.rewards[i]),
                        None => by.push((c, vec![&self.rewards[i]])),
                    }
                }
                by.into_iter().map(|(_, v)| v).collect()
            }
            _ => self.rewards.iter().map(|r| vec![r]).collect(),
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Season {
    pub id: i32,
    pub name: Option<String>,
    pub current: i32,
    pub available: i32,
    pub end_date: i64,
    pub missions: Vec<Option<Mission>>,
    pub game_data: Option<Json>,
}

/// Your state of one mission.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Prog {
    pub progress: Vec<i32>,
    /// 0 working on it, 1 done (to claim), 2/3 claimed.
    pub state: i32,
    pub claimed_at: i64,
    pub claims: i32,
    pub ever_done: bool,
}

#[derive(Debug, Default)]
pub struct Missions {
    pub defs: Option<Vec<Season>>,
    /// By "season:mission", in the order put (Java's `HashMap`: nothing walks it in order).
    pub progs: BTreeMap<String, Prog>,
    pub locked: BTreeSet<String>,
    pub unlocked: BTreeSet<String>,
    pub token: Option<String>,
    pub asked: i64,
    pub fetched: i64,
    pub problem: Option<String>,
    pub fetching: bool,
    /// What Java's missions-seasons.json and missions-player.xml hold: the last answers read.
    pub seasons_file: Option<String>,
    pub player_file: Option<String>,
    /// Java would write its file now (its `save()`): the app writes it.
    pub dirty: bool,
}

fn json_field<'a>(o: &'a Json, k: &str) -> Option<&'a Json> {
    o.get(k)
}

fn object(v: &Json) -> Result<&Json, Thrown> {
    match v {
        Json::Object(_) => Ok(v),
        _ => Err(Thrown), // Gson: expected BEGIN_OBJECT
    }
}

fn cond(v: &Json) -> Result<Cond, Thrown> {
    let o = object(v)?;
    let mut c = Cond::default();
    if let Some(x) = gson::int(json_field(o, "type"))? {
        c.kind = x;
    }
    if let Some(t) = json_field(o, "target") {
        c.target = Some(t.clone());
    }
    if let Some(x) = gson::int(json_field(o, "amount"))? {
        c.amount = x;
    }
    // Integer: null stays null
    c.time = gson::int(json_field(o, "time"))?;
    Ok(c)
}

fn reward(v: &Json) -> Result<Reward, Thrown> {
    let o = object(v)?;
    let mut r = Reward::default();
    if let Some(x) = gson::int(json_field(o, "type"))? {
        r.kind = x;
    }
    if let Some(t) = json_field(o, "target") {
        r.target = Some(t.clone());
    }
    if let Some(x) = gson::long(json_field(o, "amount"))? {
        r.amount = x;
    }
    Ok(r)
}

fn mission(v: &Json) -> Result<Mission, Thrown> {
    let o = object(v)?;
    let mut m = Mission::default();
    let s = |k: &str| gson::string(json_field(o, k));
    if let Some(x) = gson::int(json_field(o, "id"))? {
        m.id = x;
    }
    if let Some(x) = s("name")? {
        m.name = x;
    }
    if let Some(x) = s("desc")? {
        m.desc = x;
    }
    // null lists become empty in parseDefs
    m.conds = gson::list(json_field(o, "conds"), cond)?.flatten().unwrap_or_default();
    if let Some(x) = s("condSpread")? {
        m.cond_spread = x;
    }
    m.rewards = gson::list(json_field(o, "rewards"), reward)?.flatten().unwrap_or_default();
    if let Some(x) = s("rewardsSpread")? {
        m.rewards_spread = x;
    }
    if let Some(w) = json_field(o, "wornRestriction") {
        m.worn_restriction = Some(w.clone());
    }
    if let Some(x) = s("parentsStr")? {
        m.parents_str = x;
    }
    if let Some(x) = s("icon")? {
        m.icon = x;
    }
    if let Some(x) = gson::double(json_field(o, "interval"))? {
        m.interval = x;
    }
    if let Some(x) = gson::int(json_field(o, "participants"))? {
        m.participants = x;
    }
    Ok(m)
}

fn season(v: &Json) -> Result<Season, Thrown> {
    let o = object(v)?;
    let mut s = Season { id: 0, name: Some(String::new()), current: 0, available: 0, end_date: 0, missions: Vec::new(), game_data: None };
    if let Some(x) = gson::int(json_field(o, "id"))? {
        s.id = x;
    }
    if let Some(x) = gson::string(json_field(o, "name"))? {
        s.name = x;
    }
    if let Some(x) = gson::int(json_field(o, "current"))? {
        s.current = x;
    }
    if let Some(x) = gson::int(json_field(o, "available"))? {
        s.available = x;
    }
    if let Some(x) = gson::long(json_field(o, "endDate"))? {
        s.end_date = x;
    }
    s.missions = gson::list(json_field(o, "missions"), mission)?.flatten().unwrap_or_default();
    match json_field(o, "gameData") {
        None => {}
        Some(g @ Json::Object(_)) => s.game_data = Some(g.clone()),
        Some(_) => return Err(Thrown), // a JsonObject field: anything else (null too) throws
    }
    Ok(s)
}

/// Java `parseDefs`: `Err` where Gson (or the null checks after it) throws.
pub fn parse_defs(text: &str) -> Result<Vec<Season>, Thrown> {
    if java::is_blank(text) {
        return Ok(Vec::new()); // fromJson of nothing is null: new Seasons()
    }
    let root = json::parse(text).map_err(|_| Thrown)?;
    if root == Json::Null {
        return Ok(Vec::new());
    }
    let o = object(&root)?;
    let seasons = gson::list(o.get("seasons"), season)?.flatten().unwrap_or_default();
    // parseDefs walks every season and mission: a null one throws
    let mut out = Vec::new();
    for s in seasons {
        let s = s.ok_or(Thrown)?;
        for m in &s.missions {
            m.as_ref().ok_or(Thrown)?;
        }
        out.push(s);
    }
    Ok(out)
}

/// Java `ints`: "a:b:c", a part not a number is 0.
fn ints(s: &str) -> Vec<i32> {
    java::split(java::trim(s), ':').into_iter().map(|p| java::parse_int(java::trim(p)).unwrap_or(0)).collect()
}

/// Java `tag`: the text inside `<name...>...</name>`.
fn tag<'a>(xml: &'a str, name: &str) -> Option<&'a str> {
    let open = xml.find(&format!("<{name}"))?;
    let start = open + xml[open..].find('>')?;
    if start > 0 && xml.as_bytes()[start - 1] == b'/' {
        return None;
    }
    let end = start + xml[start..].find(&format!("</{name}>"))?;
    Some(&xml[start + 1..end])
}

fn digits(s: &str) -> bool {
    !s.is_empty() && s.bytes().all(|b| b.is_ascii_digit())
}

fn pairs(section: Option<&str>, into: &mut BTreeSet<String>) {
    let Some(section) = section else { return };
    for e in java::split(section, ';') {
        let f = java::split(java::trim(e), ',');
        if f.len() == 2 && digits(java::trim(f[0])) && digits(java::trim(f[1])) {
            into.insert(format!("{}:{}", java::trim(f[0]), java::trim(f[1])));
        }
    }
}

/// `<Season\s[^>]*id="(\d+)"[^>]*>(.*?)</Season>` (dot matching newlines): every (id, body).
fn season_blocks(xml: &str) -> Vec<(&str, &str)> {
    let mut out = Vec::new();
    let mut from = 0;
    while let Some(i) = xml[from..].find("<Season") {
        let at = from + i;
        let after = at + "<Season".len();
        let ws = xml[after..].chars().next().is_some_and(|c| matches!(c, ' ' | '\t' | '\n' | '\u{b}' | '\u{c}' | '\r'));
        let gt = xml[after..].find('>').map(|g| after + g);
        if ws && let Some(gt) = gt {
            // greedy [^>]*: the last id="digits" before the '>' (after the whitespace)
            let tag = &xml[after + 1..gt];
            let mut best = None;
            let mut k = 0;
            while let Some(j) = tag[k..].find("id=\"") {
                let v = k + j + 4;
                let n = tag.as_bytes()[v..].iter().take_while(|b| b.is_ascii_digit()).count();
                if n > 0 && tag.as_bytes().get(v + n) == Some(&b'"') {
                    best = Some(&tag[v..v + n]);
                }
                k = k + j + 1;
            }
            if let (Some(id), Some(end)) = (best, xml[gt + 1..].find("</Season>")) {
                out.push((id, &xml[gt + 1..gt + 1 + end]));
                from = gt + 1 + end + "</Season>".len();
                continue;
            }
        }
        from = at + 1;
    }
    out
}

impl Missions {
    /// What the Java app kept (its last answers, `missions-seasons.json` and `missions-player.xml`), read only; the
    /// player file's time is when they were fetched. Java stops at what it cannot read.
    pub fn import(dir: &std::path::Path) -> Missions {
        let mut m = Missions::default();
        let (seasons, player) = (dir.join("missions-seasons.json"), dir.join("missions-player.xml"));
        if let Ok(text) = std::fs::read_to_string(&seasons) {
            match parse_defs(&text) {
                Ok(d) => m.defs = Some(d),
                Err(_) => return m,
            }
            m.seasons_file = Some(text);
        }
        if let Ok(text) = std::fs::read_to_string(&player) {
            if m.apply_player(&text).is_err() {
                return m;
            }
            m.fetched = crate::tinkerer::modified_ms(&player).unwrap_or(0);
            m.player_file = Some(text);
        }
        m
    }

    /// Java `applyPlayer`: your progress per tree ("mission,a:b:c,state,updated,claims,claimedAt|...") and the lock
    /// lists. `Err` where Java throws (a tree id past an int).
    pub fn apply_player(&mut self, xml: &str) -> Result<(), Thrown> {
        self.locked.clear();
        self.unlocked.clear();
        pairs(tag(xml, "LockedMissions"), &mut self.locked);
        pairs(tag(xml, "UnlockedMissions"), &mut self.unlocked);
        for (tree, body) in season_blocks(xml) {
            let tree = java::parse_int(tree)?;
            for entry in java::split(body, '|') {
                let f = java::split(java::trim(entry), ',');
                if f.len() < 3 {
                    continue;
                }
                let read = || -> Result<(i32, Prog), Thrown> {
                    let mut p = Prog::default();
                    let id = java::parse_int(java::trim(f[0]))?;
                    p.progress = ints(f[1]);
                    p.state = java::parse_int(java::trim(f[2]))?;
                    if f.len() > 4 {
                        p.claims = java::parse_int(java::trim(f[4]))?.max(0);
                    }
                    if f.len() > 5 {
                        p.claimed_at = java::parse_long(java::trim(f[5]))?.max(0);
                    }
                    p.ever_done = p.state >= 2 || p.claims > 0 || p.claimed_at > 0;
                    Ok((id, p))
                };
                if let Ok((id, p)) = read() {
                    self.progs.insert(format!("{tree}:{id}"), p);
                }
            }
        }
        Ok(())
    }

    /// The fetch's answers (Java: the end of `fetch`'s thread): both there and no error: read; else the problem.
    pub fn answered(&mut self, seasons: Option<String>, player: Option<String>, now: i64) {
        let (Some(s), Some(p)) = (seasons, player) else {
            self.problem = Some("no answer".into());
            return;
        };
        if s.contains("<Error") || p.contains("<Error") {
            let why = java::trim(if s.contains("<Error") { &s } else { &p }).to_string();
            self.problem = Some(why.chars().take(200).collect());
            return;
        }
        let Ok(parsed) = parse_defs(&s) else {
            self.problem = Some("missions not readable".into());
            return;
        };
        self.defs = Some(parsed);
        self.progs.clear();
        if self.apply_player(&p).is_err() {
            self.problem = Some("missions not readable".into());
            return;
        }
        self.seasons_file = Some(s);
        self.player_file = Some(p);
        self.dirty = true;
        self.fetched = now;
        self.problem = None;
    }

    pub fn dump(&self) -> String {
        let q = |s: &Option<String>| s.as_deref().map_or("null".into(), |s| lg_protocol::dump::to_string(&s.to_string()));
        let mut out = format!(
            "missions-state fetched={} problem={} locked=[{}] unlocked=[{}]\n",
            self.fetched,
            self.problem.is_some(),
            self.locked.iter().cloned().collect::<Vec<_>>().join(","),
            self.unlocked.iter().cloned().collect::<Vec<_>>().join(",")
        );
        for s in self.defs.iter().flatten() {
            let ms: Vec<String> = s
                .missions
                .iter()
                .flatten()
                .map(|m| {
                    format!(
                        "{}/{}/{}c/{}r/{}/{:016x}/{}",
                        m.id,
                        q(&m.name),
                        m.conds.len(),
                        m.rewards.len(),
                        q(&m.parents_str),
                        m.interval.to_bits(),
                        m.participants
                    )
                })
                .collect();
            out.push_str(&format!(
                "season {} name={} cur={} avail={} end={} game={} [{}]\n",
                s.id,
                q(&s.name),
                s.current,
                s.available,
                s.end_date,
                s.game_data.is_some(),
                ms.join(",")
            ));
        }
        for (k, p) in &self.progs {
            out.push_str(&format!(
                "prog {k} [{}] state={} claims={} at={} ever={}\n",
                p.progress.iter().map(i32::to_string).collect::<Vec<_>>().join(","),
                p.state,
                p.claims,
                p.claimed_at,
                p.ever_done
            ));
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_answers() {
        let json = r#"{"seasons":[{"id":3,"name":"Skuld","current":1,"available":1,"endDate":1800000000,"gameData":{"x":1},
            "missions":[{"id":1,"name":"A","conds":[{"type":3,"target":"Pirate Cave","amount":2}],"rewards":[{"type":2,"target":"2594","amount":1}],"interval":1.5,"unknown":[1]}]}]}"#;
        let d = parse_defs(json).unwrap();
        assert_eq!((d[0].id, d[0].missions.len(), d[0].end_date), (3, 1, 1_800_000_000));
        let m = d[0].missions[0].as_ref().unwrap();
        assert_eq!((m.interval, m.rewards[0].as_ref().unwrap().item_id()), (1.5, 2594));
        assert_eq!(parse_defs(r#"{"seasons":[{"gameData":null}]}"#), Err(Thrown));
        assert_eq!(parse_defs(r#"{"seasons":[null]}"#), Err(Thrown));
        let mut ms = Missions::default();
        let player = r#"<Missions><LockedMissions>3,4;x,1</LockedMissions><UnlockedMissions/><Season id="3">1,2:0,1,0,1,170|2,x,0|bad|5,1:1,2,0,0,0</Season></Missions>"#;
        ms.apply_player(player).unwrap();
        assert_eq!(ms.locked.iter().cloned().collect::<Vec<_>>(), ["3:4"]);
        assert_eq!(ms.progs.len(), 3);
        assert_eq!(ms.progs["3:1"], Prog { progress: vec![2, 0], state: 1, claimed_at: 170, claims: 1, ever_done: true });
        assert_eq!(ms.progs["3:2"].progress, vec![0], "a part not a number is 0");
    }
}
