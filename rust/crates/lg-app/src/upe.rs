//! Game mode runs (Java: `lootgobbler.Upe`): characters played with only what they find themselves (UPE, PPE and
//! their variants). Every item a character holds gets an origin when it arrives (its starting gear, a loot bag,
//! crafted from clean things, the vault, a trade, a reward, unknown); only a proven violation breaks a run.
//!
//! Not yet: the guild trade check with the Loot Gobbler server (trades stay "checking"), syncing, the file, the app
//! log lines.

use lg_gamedata::GameData;
use std::collections::HashMap;

pub const STARTER: &str = "S";
pub const DROP: &str = "D";
pub const CRAFTED: &str = "C";
pub const VAULT: &str = "V";
pub const TRADE: &str = "T";
pub const FORGED_TAINTED: &str = "F";
pub const REWARD: &str = "B";
pub const UNKNOWN: &str = "U";
pub const GUILD: &str = "G";
pub const CHECKING: &str = "P";
pub const PPE: &str = "PPE";
pub const UPE: &str = "UPE";
pub const PUPE: &str = "PUPE";
pub const STPE: &str = "STPE";
pub const SHINY_UPE: &str = "ShinyUPE";
pub const DIVINE_PE: &str = "DivinePE";
pub const MODES: [&str; 12] =
    ["PPE", "gPPE", "UPE", "gUPE", "PUPE", "gPUPE", "STPE", "gSTPE", "ShinyUPE", "gShinyUPE", "DivinePE", "gDivinePE"];
const CONTEXT_MS: i64 = 10_000;
const KEEP_LEFT_MS: i64 = 180 * 24 * 60 * 60 * 1000;
const KEEP_TRADES_MS: i64 = 30 * 24 * 60 * 60 * 1000;
const LOADOUT: usize = 4;

pub fn guild(mode: &str) -> bool {
    mode.starts_with('g')
}

pub fn base(mode: &str) -> &str {
    if guild(mode) { &mode[1..] } else { mode }
}

pub fn label(mode: &str) -> String {
    let b = base(mode);
    let l = match b {
        SHINY_UPE => "Shiny UPE",
        DIVINE_PE => "Divine PE",
        _ => b,
    };
    format!("{}{l}", if guild(mode) { "g" } else { "" })
}

fn tainted(o: &str) -> bool {
    [VAULT, TRADE, FORGED_TAINTED, REWARD].contains(&o)
}

fn tainted_in(o: &str, mode: &str) -> bool {
    if o == GUILD {
        return !guild(mode);
    }
    o == CHECKING || tainted(o)
}

fn own_for_trade(o: &str) -> bool {
    [DROP, CRAFTED, GUILD, STARTER].contains(&o)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Gathered {
    pub item: i32,
    pub enchants: Option<Vec<i32>>,
    pub at: i64,
    pub how: String,
    pub r#where: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TradeItem {
    pub item: i32,
    pub enchants: Option<Vec<i32>>,
    pub clean: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Trade {
    pub at: i64,
    pub char_id: i32,
    pub me: String,
    pub partner: String,
    pub mode: String,
    pub standing: bool,
    pub gave: Vec<TradeItem>,
    pub got: Vec<TradeItem>,
    pub status: String,
    pub why: Option<String>,
}

/// A run (Java: `Upe.Run`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Run {
    pub char_id: i32,
    pub mode: String,
    pub fresh: bool,
    pub marked: bool,
    pub marked_at: i64,
    pub broken: Option<String>,
    pub broken_at: i64,
    pub notes: Vec<String>,
    pub last_seen: i64,
    pub items: Vec<i32>,
    pub enchants: Vec<Option<Vec<i32>>>,
    pub origin: Vec<String>,
    pub since: Vec<i64>,
    pub level: i32,
    pub base_stats: Option<[i32; 8]>,
    pub allowed: Vec<Vec<i32>>,
    pub gathered: Vec<Gathered>,
    pub guild_locked: bool,
    pub trades: Vec<Trade>,
    pub t0_start: Option<bool>,
    pub pet: bool,
    pub no_pet: bool,
}

/// Java `repair`, at loading: old modes renamed, then the per-slot arrays the length of the slots.
fn load_repair(run: &mut Run, mode_missing: bool) {
    if mode_missing || run.mode.trim().is_empty() {
        run.mode = UPE.into(); // runs from before there were modes
    }
    if run.mode == "NPE" {
        run.mode = PPE.into(); // NPE is a PPE without a pet now
        run.no_pet = true;
    }
    repair(run);
}

fn read_run(v: &lg_world::json::Json) -> Result<(Run, bool), crate::java::Thrown> {
    use crate::gson;
    use crate::java::Thrown;
    use lg_world::json::Json;
    let v = gson::object(v)?;
    let mut r = Run::new(0);
    let ints_or = |k: &str| gson::ints(v.get(k)).map(|x| x.map(Option::unwrap_or_default));
    if let Some(x) = gson::int(v.get("charId"))? {
        r.char_id = x;
    }
    let mut mode_missing = false;
    if let Some(x) = gson::string(v.get("mode"))? {
        match x {
            Some(m) => r.mode = m,
            None => mode_missing = true,
        }
    }
    for (f, k) in [
        (&mut r.fresh, "fresh"),
        (&mut r.marked, "marked"),
        (&mut r.guild_locked, "guildLocked"),
        (&mut r.pet, "pet"),
        (&mut r.no_pet, "noPet"),
    ] {
        if let Some(x) = gson::boolean(v.get(k))? {
            *f = x;
        }
    }
    for (f, k) in [(&mut r.marked_at, "markedAt"), (&mut r.broken_at, "brokenAt"), (&mut r.last_seen, "lastSeen")] {
        if let Some(x) = gson::long(v.get(k))? {
            *f = x;
        }
    }
    if let Some(x) = gson::string(v.get("broken"))? {
        r.broken = x;
    }
    if let Some(x) = gson::list(v.get("notes"), |n| gson::string(Some(n)).map(|s| s.flatten().unwrap_or_default()))? {
        r.notes = x.map(|l| l.into_iter().flatten().collect()).unwrap_or_default();
    }
    if let Some(x) = ints_or("items")? {
        r.items = x;
    }
    if let Some(x) = gson::ints2(v.get("enchants"))? {
        r.enchants = x.unwrap_or_default();
    }
    if let Some(x) = gson::list(v.get("origin"), |n| gson::string(Some(n)).map(|s| s.flatten().unwrap_or_default()))? {
        r.origin = x.map(|l| l.into_iter().map(Option::unwrap_or_default).collect()).unwrap_or_default();
    }
    if let Some(x) = gson::list(v.get("since"), |n| gson::long(Some(n))?.ok_or(Thrown))? {
        r.since = x.map(|l| l.into_iter().map(|i| i.unwrap_or(0)).collect()).unwrap_or_default();
    }
    if let Some(x) = gson::int(v.get("level"))? {
        r.level = x;
    }
    if let Some(x) = gson::ints(v.get("baseStats"))? {
        r.base_stats = x.and_then(|b| <[i32; 8]>::try_from(b).ok());
    }
    if let Some(x) = gson::ints2(v.get("allowed"))? {
        r.allowed = x.map(|l| l.into_iter().flatten().collect()).unwrap_or_default();
    }
    if let Some(x) = gson::objects(v.get("gathered"), |g| {
        let g = gson::object(g)?;
        Ok(Gathered {
            item: gson::int(g.get("item"))?.unwrap_or(0),
            enchants: gson::ints(g.get("enchants"))?.flatten(),
            at: gson::long(g.get("at"))?.unwrap_or(0),
            how: gson::string(g.get("how"))?.flatten().unwrap_or_default(),
            r#where: gson::string(g.get("where"))?.map_or(String::new(), Option::unwrap_or_default),
        })
    })? {
        r.gathered = x;
    }
    let item = |t: &Json| -> Result<TradeItem, Thrown> {
        let t = gson::object(t)?;
        Ok(TradeItem {
            item: gson::int(t.get("item"))?.unwrap_or(0),
            enchants: gson::ints(t.get("enchants"))?.flatten(),
            clean: gson::boolean(t.get("clean"))?.unwrap_or(false),
        })
    };
    if let Some(x) = gson::objects(v.get("trades"), |t| {
        let t = gson::object(t)?;
        let st = |k: &str, d: &str| gson::string(t.get(k)).map(|s| s.map_or(d.to_string(), Option::unwrap_or_default));
        Ok(Trade {
            at: gson::long(t.get("at"))?.unwrap_or(0),
            char_id: gson::int(t.get("charId"))?.unwrap_or(0),
            me: st("me", "")?,
            partner: st("partner", "")?,
            mode: st("mode", "gUPE")?,
            standing: gson::boolean(t.get("standing"))?.unwrap_or(false),
            gave: gson::objects(t.get("gave"), item)?.unwrap_or_default(),
            got: gson::objects(t.get("got"), item)?.unwrap_or_default(),
            status: st("status", CHECKING)?,
            why: gson::string(t.get("why"))?.flatten(),
        })
    })? {
        r.trades = x;
    }
    r.t0_start = gson::boolean(v.get("t0Start"))?;
    Ok((r, mode_missing))
}

impl Run {
    fn new(char_id: i32) -> Run {
        Run {
            char_id,
            mode: UPE.into(),
            fresh: false,
            marked: false,
            marked_at: 0,
            broken: None,
            broken_at: 0,
            notes: Vec::new(),
            last_seen: 0,
            items: Vec::new(),
            enchants: Vec::new(),
            origin: Vec::new(),
            since: Vec::new(),
            level: 0,
            base_stats: None,
            allowed: Vec::new(),
            gathered: Vec::new(),
            guild_locked: false,
            trades: Vec::new(),
            t0_start: None,
            pet: false,
            no_pet: false,
        }
    }

    pub fn eligible(&self) -> bool {
        self.fresh && self.t0_start != Some(false)
    }

    pub fn standing(&self) -> bool {
        self.broken.is_none() && (self.marked || self.eligible())
    }

    pub fn potential(&self) -> bool {
        self.eligible() && !self.marked && self.broken.is_none()
    }

    pub fn can_start(&self, mode: &str, no_pet: bool) -> bool {
        self.potential() && MODES.contains(&mode) && !(no_pet && self.pet)
    }

    fn origin_at(&self, slot: i32) -> String {
        usize::try_from(slot).ok().and_then(|s| self.origin.get(s)).cloned().unwrap_or(UNKNOWN.into())
    }
}

/// An item that left a vault while nobody watched.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Left {
    pub item: i32,
    pub enchants: Vec<i32>,
    pub from: i64,
    pub to: i64,
    pub vault: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MissionRun {
    pub char_id: i32,
    pub from_zero: bool,
    pub clean: bool,
    pub started_at: i64,
}

#[derive(Debug, Clone)]
struct Pending {
    char_id: i32,
    slot: i32,
    item: i32,
    origin: String,
    at: i64,
    gain: bool,
}

/// Java `Upe`.
#[derive(Debug)]
pub struct Upe {
    pub runs: HashMap<i32, Run>,
    pub left: Vec<Left>,
    pub missions: HashMap<String, MissionRun>,
    pending: Vec<Pending>,
    forged_at: i64,
    traded_at: i64,
    reward_at: i64,
    quest_at: i64,
    quest_origin: String,
    quest_parts: HashMap<i32, Vec<(i32, bool)>>,
    forged_origin: String,
    forge_inputs: Vec<String>,
    forge_mode: String,
    in_vault_map: bool,
    r#where: String,
    trade_partner: String,
    my_trade: Vec<i32>,
    their_trade: Vec<i32>,
    my_trade_ench: Vec<Option<Vec<i32>>>,
    their_trade_ench: Vec<Option<Vec<i32>>>,
    my_offer: Vec<bool>,
    their_offer: Vec<bool>,
    last_quest: i32,
    last_quest_part: Option<(i32, bool)>,
    last_rewards: Vec<i32>,
    last_choice: bool,
    pub dirty: bool,
}

impl Default for Upe {
    fn default() -> Self {
        Upe {
            runs: HashMap::new(),
            left: Vec::new(),
            missions: HashMap::new(),
            pending: Vec::new(),
            forged_at: 0,
            traded_at: 0,
            reward_at: 0,
            quest_at: 0,
            quest_origin: CRAFTED.into(),
            quest_parts: HashMap::new(),
            forged_origin: CRAFTED.into(),
            forge_inputs: Vec::new(),
            forge_mode: UPE.into(),
            in_vault_map: false,
            r#where: String::new(),
            trade_partner: String::new(),
            my_trade: Vec::new(),
            their_trade: Vec::new(),
            my_trade_ench: Vec::new(),
            their_trade_ench: Vec::new(),
            my_offer: Vec::new(),
            their_offer: Vec::new(),
            last_quest: 0,
            last_quest_part: None,
            last_rewards: Vec::new(),
            last_choice: false,
            dirty: false,
        }
    }
}

/// What the rules need to know of the items (Java: `Upe.Items` on the game data).
struct Items<'a>(&'a GameData);

impl Items<'_> {
    fn untiered(&self, id: i32) -> bool {
        self.0.items.item(id).is_some_and(|i| i.is_ut() || i.is_st())
    }
    fn name(&self, id: i32) -> String {
        match self.0.items.item(id) {
            Some(i) if !i.name.trim().is_empty() => i.name.clone(),
            _ => format!("item {id}"),
        }
    }
    /// Java `StatPotion`: the stat (0 life ... 7 wisdom) of a stat potion, -1 if none.
    fn potion_stat(&self, id: i32) -> i32 {
        const POTIONS: [[i32; 3]; 8] = [
            [2793, 9070, 5471],
            [2794, 9071, 5472],
            [2591, 9064, 5465],
            [2592, 9065, 5466],
            [2593, 9066, 5467],
            [2636, 9069, 5470],
            [2612, 9067, 5468],
            [2613, 9068, 5469],
        ];
        POTIONS.iter().position(|p| p.contains(&id)).map_or(-1, |i| i as i32)
    }
    fn loot_boost(&self, id: i32) -> bool {
        self.0.items.item(id).is_some_and(|i| i.loot_boost)
    }
    fn st(&self, id: i32) -> bool {
        self.0.items.item(id).is_some_and(|i| i.is_st())
    }
    fn shiny(&self, id: i32) -> bool {
        self.0.items.item(id).is_some_and(|i| i.is_shiny())
    }
    fn tier(&self, id: i32) -> i32 {
        match self.0.items.item(id) {
            Some(i) if !i.is_ut() && !i.is_st() => i.tier.trim().parse().unwrap_or(-1),
            _ => -1,
        }
    }
}

fn sorted(a: Option<&Vec<i32>>) -> Vec<i32> {
    let mut c = a.cloned().unwrap_or_default();
    c.sort_unstable();
    c
}

fn key(item: i32, ench: &[i32]) -> String {
    let mut s = ench.to_vec();
    s.sort_unstable();
    format!("{item}:[{}]", s.iter().map(i32::to_string).collect::<Vec<_>>().join(", "))
}

fn vault_name(v: &str) -> &'static str {
    if v == crate::holdings::SEASONAL_VAULT { "seasonal vault" } else { "vault" }
}

/// Java `Upe.date`: `d MMM HH:mm` (English months: the locale the app runs in here).
fn date(zone: &crate::localtime::Zone, t: i64) -> String {
    let s = zone.local_date_time(t);
    let (y_m_d, hm) = s.split_once('T').unwrap_or((&s, ""));
    let mut p = y_m_d.split('-');
    let (_, m, d) =
        (p.next(), p.next().and_then(|m| m.parse::<usize>().ok()).unwrap_or(1), p.next().and_then(|d| d.parse::<u32>().ok()).unwrap_or(1));
    const MONTHS: [&str; 12] = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
    format!("{d} {} {}", MONTHS[m - 1], &hm[..hm.len().min(5)])
}

fn span(zone: &crate::localtime::Zone, l: &Left) -> String {
    format!("between {} and {}", date(zone, l.from), date(zone, l.to))
}

/// The world around the rules.
pub struct Ctx<'a> {
    pub data: &'a GameData,
    pub zone: &'a crate::localtime::Zone,
    pub now: i64,
}

fn note(run: &mut Run, text: String) -> bool {
    if run.notes.contains(&text) {
        return false;
    }
    run.notes.push(text);
    if run.notes.len() > 20 {
        run.notes.remove(0);
    }
    true
}

fn break_run(run: &mut Run, why: String, now: i64) -> bool {
    if run.broken.is_some() {
        return false;
    }
    run.broken = Some(why);
    run.broken_at = now;
    true
}

fn use_allowance(run: &mut Run, item: i32) -> bool {
    if let Some(i) = run.allowed.iter().position(|a| a.contains(&item)) {
        run.allowed.remove(i);
        return true;
    }
    false
}

/// Java `repair`: lists that must line up.
fn repair(run: &mut Run) {
    let n = run.items.len();
    if run.origin.len() != n {
        run.origin = vec![UNKNOWN.into(); n];
    }
    if run.since.len() != n {
        run.since = vec![0; n];
    }
    if run.enchants.len() != n {
        run.enchants = vec![None; n];
    }
}

impl Upe {
    /// What the Java app kept (`upe.json`), read only, with Java's repair of each run. Unreadable: nothing.
    pub fn import(dir: &std::path::Path) -> Upe {
        use crate::gson;
        use lg_world::json::Json;
        let mut u = Upe::default();
        let Ok(text) = std::fs::read_to_string(dir.join("upe.json")) else { return u };
        let read = || -> Result<Upe, crate::java::Thrown> {
            let mut u = Upe::default();
            let root = lg_world::json::parse(&text).map_err(|_| crate::java::Thrown)?;
            if root == Json::Null {
                return Ok(u);
            }
            let root = gson::object(&root)?;
            if let Some(Json::Object(runs)) = root.get("runs") {
                for (k, v) in runs {
                    if *v == Json::Null {
                        continue;
                    }
                    let (mut run, missing) = read_run(v)?;
                    load_repair(&mut run, missing);
                    u.runs.insert(crate::java::parse_int(k)?, run);
                }
            }
            if let Some(l) = gson::objects(root.get("left"), |l| {
                let l = gson::object(l)?;
                Ok(Left {
                    item: gson::int(l.get("item"))?.unwrap_or(0),
                    enchants: gson::ints(l.get("enchants"))?.flatten().unwrap_or_default(),
                    from: gson::long(l.get("from"))?.unwrap_or(0),
                    to: gson::long(l.get("to"))?.unwrap_or(0),
                    vault: gson::string(l.get("vault"))?.flatten().unwrap_or_default(),
                })
            })? {
                u.left = l;
            }
            if let Some(Json::Object(m)) = root.get("missions") {
                for (k, v) in m {
                    let v = gson::object(v)?;
                    u.missions.insert(
                        k.clone(),
                        MissionRun {
                            char_id: gson::int(v.get("charId"))?.unwrap_or(0),
                            from_zero: gson::boolean(v.get("fromZero"))?.unwrap_or(false),
                            clean: gson::boolean(v.get("clean"))?.unwrap_or(false),
                            started_at: gson::long(v.get("startedAt"))?.unwrap_or(0),
                        },
                    );
                }
            }
            Ok(u)
        };
        if let Ok(x) = read() {
            u = x;
        }
        u
    }

    pub fn in_vault(&mut self, vault: bool) {
        self.in_vault_map = vault;
    }

    pub fn set_where(&mut self, map: &str) {
        self.r#where = map.to_string();
    }

    fn gathered(&self, run: &mut Run, item: i32, ench: Option<Vec<i32>>, origin: &str, now: i64) {
        if item < 1 || ![DROP, CRAFTED, GUILD].contains(&origin) || !(run.eligible() || run.marked) {
            return;
        }
        run.gathered.push(Gathered { item, enchants: ench, at: now, how: origin.into(), r#where: self.r#where.clone() });
    }

    fn starts_at_t0(items: &Items, run: &Run) -> Option<bool> {
        let mut any = false;
        for i in 0..run.items.len().min(3) {
            if run.items[i] < 1 || run.origin[i] != STARTER {
                continue;
            }
            let tier = items.tier(run.items[i]);
            if tier > 0 {
                return Some(false);
            }
            if tier == 0 {
                any = true;
            }
        }
        any.then_some(true)
    }

    /// Java `seen`: your character as the sniffer sees it (every few seconds).
    #[allow(clippy::too_many_arguments)]
    pub fn seen(
        &mut self,
        c: &Ctx,
        char_id: i32,
        level: i32,
        slots: &[i32],
        enchants: Option<&Vec<Vec<i32>>>,
        base_stats: Option<[i32; 8]>,
        first_sight: bool,
    ) {
        if char_id <= 0 {
            return;
        }
        let items = Items(c.data);
        let n = slots.len();
        let ench: Vec<Option<Vec<i32>>> = match enchants {
            Some(e) if e.len() == n => e.iter().cloned().map(Some).collect(),
            _ => vec![None; n],
        };
        let first = self.runs.get(&char_id).is_none_or(|r| r.last_seen == 0);
        if first {
            let marked = self.runs.get(&char_id).is_some_and(|r| r.marked);
            let mut run = Run::new(char_id);
            run.fresh = first_sight && level <= 1;
            run.marked = marked && run.fresh;
            run.items = slots.to_vec();
            run.enchants = ench;
            run.origin = vec![if run.fresh { STARTER } else { UNKNOWN }.to_string(); n];
            run.since = vec![0; n];
            run.last_seen = c.now;
            run.level = level;
            run.base_stats = base_stats;
            if run.fresh {
                run.t0_start = Self::starts_at_t0(&items, &run);
            }
            self.runs.insert(char_id, run);
            self.dirty = true;
        } else {
            let mut run = self.runs.remove(&char_id).expect("run");
            if self.update(c, &mut run, level, slots, ench, base_stats) {
                self.dirty = true;
            }
            self.runs.insert(char_id, run);
        }
    }

    fn update(
        &mut self,
        c: &Ctx,
        run: &mut Run,
        level: i32,
        slots: &[i32],
        ench: Vec<Option<Vec<i32>>>,
        base_stats: Option<[i32; 8]>,
    ) -> bool {
        let items = Items(c.data);
        let now = c.now;
        repair(run);
        if run.fresh
            && run.t0_start.is_none()
            && let Some(t0) = Self::starts_at_t0(&items, run)
        {
            run.t0_start = Some(t0);
            self.dirty = true;
        }
        let n = slots.len();
        let (old, old_origin, old_since) = (run.items.clone(), run.origin.clone(), run.since.clone());
        let gap = now - run.last_seen > 5 * 60_000;
        let mut origin: Vec<Option<String>> = vec![None; n];
        let mut since = vec![0i64; n];
        let mut taken = vec![false; old.len()];
        let mut changed = false;
        for i in 0..n {
            if i < old.len() && old[i] == slots[i] {
                origin[i] = Some(old_origin[i].clone());
                since[i] = old_since[i];
                taken[i] = true;
            }
        }
        for i in 0..n {
            if origin[i].is_some() {
                continue;
            }
            let item = slots[i];
            changed = true;
            if item < 1 {
                origin[i] = Some(UNKNOWN.into());
                continue;
            }
            if let Some(p) = self.take_pending(run.char_id, i as i32, item, now) {
                origin[i] = Some(p.origin.clone());
                if p.gain {
                    self.gathered(run, item, ench[i].clone(), &p.origin, now);
                }
                continue;
            }
            let from = (0..old.len()).find(|&j| !taken[j] && old[j] == item && (j >= n || slots[j] != item));
            if let Some(j) = from {
                taken[j] = true;
                origin[i] = Some(old_origin[j].clone());
                since[i] = old_since[j];
                continue;
            }
            let o = if !gap && now - self.forged_at < CONTEXT_MS {
                self.forged_origin.clone()
            } else if !gap && now - self.quest_at < CONTEXT_MS {
                self.quest_parts.remove(&self.last_quest);
                if self.quest_origin == CRAFTED {
                    use_allowance(run, item);
                }
                self.quest_origin.clone()
            } else if !gap && now - self.reward_at < CONTEXT_MS {
                REWARD.into()
            } else if !gap && now - self.traded_at < CONTEXT_MS {
                if guild(&run.mode) && run.standing() { CHECKING.into() } else { TRADE.into() }
            } else if !gap && self.in_vault_map {
                if use_allowance(run, item) { CRAFTED.into() } else { VAULT.into() }
            } else {
                since[i] = if gap { run.last_seen } else { now };
                UNKNOWN.into()
            };
            origin[i] = Some(o.clone());
            self.gathered(run, item, ench[i].clone(), &o, now);
        }
        run.items = slots.to_vec();
        run.enchants = ench;
        run.origin = origin.into_iter().map(|o| o.unwrap_or(UNKNOWN.into())).collect();
        run.since = since;
        if gap {
            changed |= self.potions_while_away(c, run, level, base_stats, now);
        }
        run.last_seen = now;
        run.level = level;
        if base_stats.is_some() {
            run.base_stats = base_stats;
        }
        changed |= self.check(c, run, now);
        changed
    }

    fn potions_while_away(&self, c: &Ctx, run: &mut Run, level: i32, base: Option<[i32; 8]>, now: i64) -> bool {
        let (Some(base), Some(before)) = (base, run.base_stats) else { return false };
        if !run.standing() || level != run.level || level < 20 {
            return false;
        }
        let items = Items(c.data);
        let mut changed = false;
        for stat in 0..8 {
            if base[stat] <= before[stat] {
                continue;
            }
            if let Some(l) = self.left.iter().find(|l| items.potion_stat(l.item) == stat as i32 && l.to >= run.last_seen && l.from <= now) {
                let text = format!(
                    "{} left the {} ({}) while this character's stats went up unseen",
                    items.name(l.item),
                    vault_name(&l.vault),
                    span(c.zone, l)
                );
                changed |= note(run, text);
            }
        }
        changed
    }

    fn take_pending(&mut self, char_id: i32, slot: i32, item: i32, now: i64) -> Option<Pending> {
        self.pending.retain(|p| now - p.at <= CONTEXT_MS);
        let i = self.pending.iter().position(|p| p.char_id == char_id && p.slot == slot && p.item == item)?;
        Some(self.pending.remove(i))
    }

    fn announce(&mut self, run: &mut Run, slot: i32, item: i32, origin: &str, now: i64, gain: bool) -> bool {
        if origin == UNKNOWN {
            return false;
        }
        if let Ok(s) = usize::try_from(slot)
            && run.items.get(s) == Some(&item)
        {
            if run.origin[s] == origin {
                return false;
            }
            let was_own = run.origin[s] == DROP || run.origin[s] == CRAFTED;
            run.origin[s] = origin.into();
            run.since[s] = 0;
            if !was_own && gain {
                let e = run.enchants[s].clone();
                self.gathered(run, item, e, origin, now);
            }
            return true;
        }
        self.pending.retain(|p| now - p.at <= CONTEXT_MS);
        self.pending.push(Pending { char_id: run.char_id, slot, item, origin: origin.into(), at: now, gain });
        false
    }

    fn from_vault(run: &mut Run, section: Option<&str>, item: i32) -> String {
        let Some(sec) = section else { return UNKNOWN.into() };
        if sec == crate::holdings::GIFT_CHEST && use_allowance(run, item) {
            return CRAFTED.into();
        }
        VAULT.into()
    }

    /// Java `moved`: a move the game confirmed: the slot of yours that got an item learns where it came from.
    #[allow(clippy::too_many_arguments)]
    pub fn moved(
        &mut self,
        c: &Ctx,
        char_id: i32,
        player_id: i32,
        from: (i32, i32, i32),
        to: (i32, i32, i32),
        from_section: Option<&str>,
        from_bag: bool,
        to_section: Option<&str>,
        to_bag: bool,
    ) {
        if char_id <= 0 {
            return;
        }
        let Some(mut run) = self.runs.remove(&char_id) else { return };
        let now = c.now;
        let mut changed = false;
        if from.0 == player_id && from.2 > 0 {
            let o = if to.0 == player_id {
                run.origin_at(to.1)
            } else if to_bag {
                DROP.into()
            } else {
                Self::from_vault(&mut run, to_section, from.2)
            };
            changed |= self.announce(&mut run, from.1, from.2, &o, now, to.0 != player_id);
        }
        if to.0 == player_id && to.2 > 0 {
            let o = if from.0 == player_id {
                run.origin_at(from.1)
            } else if from_bag {
                DROP.into()
            } else {
                Self::from_vault(&mut run, from_section, to.2)
            };
            changed |= self.announce(&mut run, to.1, to.2, &o, now, from.0 != player_id);
        }
        if changed {
            self.check(c, &mut run, now);
            self.dirty = true;
        }
        self.runs.insert(char_id, run);
    }

    /// Java `used`: an item used from a slot of yours (or straight from a vault container): stat potions and loot
    /// boosts are checked.
    pub fn used(&mut self, c: &Ctx, char_id: i32, slot: i32, item: i32, section: Option<&str>) {
        let items = Items(c.data);
        let potion = items.potion_stat(item) >= 0;
        let boost = items.loot_boost(item);
        if char_id <= 0 || !potion && !boost {
            return;
        }
        let Some(mut run) = self.runs.remove(&char_id) else { return };
        let now = c.now;
        let mut changed = false;
        if run.standing() {
            let (name, verb) = (items.name(item), if potion { "Drank a " } else { "Used a " });
            if let Some(sec) = section {
                changed = break_run(&mut run, format!("{verb}{name} from the {}", sec.to_lowercase()), now);
            } else {
                let o = run.origin_at(slot);
                changed = match o.as_str() {
                    VAULT => break_run(&mut run, format!("{verb}{name} taken from the vault"), now),
                    TRADE => break_run(&mut run, format!("{verb}{name} got in a trade"), now),
                    REWARD => break_run(&mut run, format!("{verb}{name}, bought or given as a reward"), now),
                    FORGED_TAINTED => break_run(&mut run, format!("{verb}{name} made from things of the vault or a trade"), now),
                    GUILD | CHECKING => {
                        if !guild(&run.mode) {
                            break_run(&mut run, format!("{verb}{name} traded from a guild mate (only a guild mode may)"), now)
                        } else if !run.guild_locked {
                            run.guild_locked = true;
                            true
                        } else {
                            false
                        }
                    }
                    UNKNOWN => match usize::try_from(slot).ok().filter(|&s| s < run.items.len()) {
                        Some(s) => match self.left_vault(item, run.enchants[s].as_ref(), run.since[s], now) {
                            Some(l) => {
                                let why = format!(
                                    "{verb}{name} that left the {} while the sniffer was off ({})",
                                    vault_name(&l.vault),
                                    span(c.zone, &l)
                                );
                                break_run(&mut run, why, now)
                            }
                            None => false,
                        },
                        None => false,
                    },
                    _ => false,
                };
            }
        }
        if changed {
            self.dirty = true;
        }
        self.runs.insert(char_id, run);
    }

    /// Java `tradeStart`: a trade window opened.
    pub fn trade_start(
        &mut self,
        partner: &str,
        mine: Vec<i32>,
        mine_ench: Vec<Option<Vec<i32>>>,
        theirs: Vec<i32>,
        theirs_ench: Vec<Option<Vec<i32>>>,
    ) {
        self.trade_partner = partner.to_string();
        self.my_offer = vec![false; mine.len()];
        self.their_offer = vec![false; theirs.len()];
        self.my_trade = mine;
        self.their_trade = theirs;
        self.my_trade_ench = mine_ench;
        self.their_trade_ench = theirs_ench;
    }

    /// Java `tradeOffers`.
    pub fn trade_offers(&mut self, mine: &[bool], theirs: &[bool]) {
        self.my_offer = mine.to_vec();
        self.their_offer = theirs.to_vec();
    }

    /// Java `traded`: a trade went through. Only a guild mode may trade.
    pub fn traded(&mut self, c: &Ctx, char_id: i32, me: Option<&str>) {
        self.traded_at = c.now;
        let Some(mut run) = self.runs.remove(&char_id) else { return };
        if run.standing() {
            repair(&mut run);
            let partner = if self.trade_partner.trim().is_empty() { "another player".to_string() } else { self.trade_partner.clone() };
            if !guild(&run.mode) {
                break_run(&mut run, format!("Traded with {partner}"), self.traded_at);
            } else {
                let item = |i: i32, e: Option<Vec<i32>>, clean: bool| TradeItem { item: i, enchants: e, clean };
                let mut t = Trade {
                    at: self.traded_at,
                    char_id,
                    me: me.unwrap_or("").to_string(),
                    partner: self.trade_partner.clone(),
                    mode: run.mode.clone(),
                    standing: true,
                    gave: Vec::new(),
                    got: Vec::new(),
                    status: CHECKING.into(),
                    why: None,
                };
                for (i, &it) in self.my_trade.iter().enumerate() {
                    if !self.my_offer.get(i).copied().unwrap_or(false) || it < 1 {
                        continue;
                    }
                    t.gave.push(item(it, self.my_trade_ench.get(i).cloned().flatten(), own_for_trade(&run.origin_at(i as i32))));
                }
                for (i, &it) in self.their_trade.iter().enumerate() {
                    if !self.their_offer.get(i).copied().unwrap_or(false) || it < 1 {
                        continue;
                    }
                    t.got.push(item(it, self.their_trade_ench.get(i).cloned().flatten(), false));
                }
                if t.got.is_empty() {
                    t.status = "ok".into();
                }
                run.trades.push(t);
                let cut = self.traded_at - KEEP_TRADES_MS;
                run.trades.retain(|x| x.at >= cut);
            }
            self.dirty = true;
        }
        self.runs.insert(char_id, run);
    }

    /// Java `rewarded`: the game gave or sold you something.
    pub fn rewarded(&mut self, now: i64) {
        self.reward_at = now;
    }

    /// Java `questHandIn`.
    pub fn quest_hand_in(&mut self, char_id: i32, quest_id: i32, slots: &[i32], rewards: Option<&[i32]>, reward_of_choice: bool) {
        let run = self.runs.get(&char_id);
        let mut clean = run.is_some_and(Run::standing);
        if let Some(r) = run {
            for s in slots {
                if tainted_in(&r.origin_at(*s), &r.mode) {
                    clean = false;
                }
            }
        }
        self.last_quest = quest_id;
        self.last_quest_part = Some((char_id, clean));
        self.last_rewards = rewards.map(<[i32]>::to_vec).unwrap_or_default();
        self.last_choice = reward_of_choice;
    }

    /// Java `questAnswer`.
    pub fn quest_answer(&mut self, ok: bool, now: i64) {
        let Some(part) = self.last_quest_part.filter(|_| ok) else { return };
        let parts = self.quest_parts.entry(self.last_quest).or_default();
        parts.push(part);
        let char_id = part.0;
        let clean = parts.iter().all(|p| p.0 == char_id && p.1);
        self.quest_origin = if clean { CRAFTED } else { REWARD }.into();
        self.quest_at = now;
        self.last_quest_part = None;
        if clean
            && !self.last_rewards.is_empty()
            && let Some(run) = self.runs.get_mut(&char_id)
        {
            repair(run);
            if self.last_choice {
                run.allowed.push(self.last_rewards.clone());
            } else {
                for &r in &self.last_rewards {
                    if r > 0 {
                        run.allowed.push(vec![r]);
                    }
                }
            }
            self.dirty = true;
        }
    }

    /// Java `forgeRequest`.
    pub fn forge_request(&mut self, char_id: i32, slots: &[i32]) {
        let run = self.runs.get(&char_id);
        self.forge_inputs = slots.iter().map(|s| run.map_or(UNKNOWN.into(), |r| r.origin_at(*s))).collect();
        self.forge_mode = run.map_or(UPE.into(), |r| r.mode.clone());
    }

    /// Java `forgeResult`.
    pub fn forge_result(&mut self, c: &Ctx, char_id: i32, success: bool, results: Option<(&[i32], &[i32])>) {
        if !success {
            return;
        }
        let now = c.now;
        let clean = !self.forge_inputs.iter().any(|o| tainted_in(o, &self.forge_mode));
        self.forged_origin = if clean { CRAFTED } else { FORGED_TAINTED }.into();
        self.forged_at = now;
        self.forge_inputs.clear();
        let Some((slots, items)) = results else { return };
        let Some(mut run) = self.runs.remove(&char_id) else { return };
        let mut changed = false;
        let o = self.forged_origin.clone();
        for i in 0..slots.len().min(items.len()) {
            if items[i] > 0 {
                changed |= self.announce(&mut run, slots[i], items[i], &o, now, true);
            }
        }
        if changed {
            self.check(c, &mut run, now);
            self.dirty = true;
        }
        self.runs.insert(char_id, run);
    }

    /// Java `vaultChanged`: a vault looked at again: what is gone left it in between.
    pub fn vault_changed(
        &mut self,
        c: &Ctx,
        vault: &str,
        before_at: i64,
        before: &[(i32, Option<Vec<i32>>)],
        now_at: i64,
        now: &[(i32, Option<Vec<i32>>)],
    ) {
        if before_at <= 0 {
            return;
        }
        let mut remaining: HashMap<String, i32> = HashMap::new();
        for (item, e) in now {
            if *item > 0 {
                *remaining.entry(key(*item, e.as_deref().unwrap_or(&[]))).or_insert(0) += 1;
            }
        }
        let mut changed = false;
        for (item, e) in before {
            if *item < 1 {
                continue;
            }
            let ench = e.clone().unwrap_or_default();
            let k = key(*item, &ench);
            let left = remaining.get(&k).copied().unwrap_or(0);
            if left > 0 {
                remaining.insert(k, left - 1);
                continue;
            }
            self.left.push(Left { item: *item, enchants: ench, from: before_at, to: now_at, vault: vault.into() });
            changed = true;
        }
        let cut = c.now - KEEP_LEFT_MS;
        self.left.retain(|l| l.to >= cut);
        if changed {
            let ids: Vec<i32> = self.runs.keys().copied().collect();
            for id in ids {
                let mut run = self.runs.remove(&id).expect("run");
                self.check(c, &mut run, c.now);
                self.runs.insert(id, run);
            }
            self.dirty = true;
        }
    }

    fn left_vault(&self, item: i32, ench: Option<&Vec<i32>>, since: i64, until: i64) -> Option<Left> {
        if since <= 0 {
            return None;
        }
        self.left
            .iter()
            .find(|l| {
                l.item == item
                    && l.to >= since
                    && l.from <= until
                    && ench.is_some_and(|e| !e.is_empty() && sorted(Some(e)) == sorted(Some(&l.enchants)))
            })
            .cloned()
    }

    fn left_vault_any(&self, item: i32, since: i64, until: i64) -> Option<Left> {
        if since <= 0 {
            return None;
        }
        self.left.iter().find(|l| l.item == item && l.to >= since && l.from <= until).cloned()
    }

    /// Java `misfit`: why this item may not be in the loadout of a run in this mode.
    fn misfit(items: &Items, mode: &str, item: i32, ench: Option<&Vec<i32>>) -> Option<&'static str> {
        let b = base(mode);
        if b == PPE {
            return None;
        }
        if b == DIVINE_PE {
            return if ench.is_none_or(|e| e.len() < 4) { Some("not a divine item (four enchantment slots)") } else { None };
        }
        if !items.untiered(item) {
            return Some("a tiered item");
        }
        if b == STPE && !items.st(item) {
            return Some("not a set item (ST)");
        }
        if b == PUPE && items.st(item) {
            return Some("a set item (ST): a pure UPE takes UT only");
        }
        if b == SHINY_UPE && !items.shiny(item) {
            return Some("not a shiny item");
        }
        None
    }

    /// Java `pets`: whether each listed character has a pet (from the character list), for the no-pet rule; in
    /// Java's `HashMap` order of the characters.
    pub fn pets(&mut self, c: &Ctx, pets: &[(i32, bool)], now: i64) {
        if pets.is_empty() {
            return;
        }
        let mut changed = false;
        for id in lg_world::fight::java_hash_order(&pets.iter().map(|p| p.0).collect::<Vec<_>>()) {
            let has = pets.iter().find(|p| p.0 == id).expect("listed").1;
            let Some(mut run) = self.runs.remove(&id) else { continue };
            if run.pet != has {
                run.pet = has;
                changed = true;
                self.check(c, &mut run, now);
            }
            self.runs.insert(id, run);
        }
        if changed {
            self.dirty = true;
        }
    }

    /// Java `check`: the loadout of a standing run.
    fn check(&self, c: &Ctx, run: &mut Run, now: i64) -> bool {
        if !run.standing() {
            return false;
        }
        let items = Items(c.data);
        let mut changed = false;
        if run.marked && run.broken.is_none() {
            if run.t0_start == Some(false) {
                changed |= break_run(run, "Created with upgrades enabled (its starting gear was not tier 0)".into(), now);
            } else if run.no_pet && run.pet {
                changed |= break_run(run, "Played with a pet".into(), now);
            }
        }
        let mut i = 0;
        while i < LOADOUT.min(run.items.len()) && run.broken.is_none() {
            let item = run.items[i];
            if item < 1 || run.origin[i] == STARTER {
                i += 1;
                continue;
            }
            let (o, name) = (run.origin[i].clone(), items.name(item));
            if let Some(m) = Self::misfit(&items, &run.mode, item, run.enchants[i].as_ref()) {
                changed |= break_run(run, format!("Equipped {name}, {m}"), now);
            } else if o == GUILD || o == CHECKING {
                if !guild(&run.mode) {
                    changed |= break_run(run, format!("Equipped {name} traded from a guild mate (only a guild mode may)"), now);
                } else if !run.guild_locked {
                    run.guild_locked = true;
                    changed = true;
                }
            } else if o == VAULT {
                changed |= break_run(run, format!("Equipped {name} taken from the vault"), now);
            } else if o == TRADE {
                changed |= break_run(run, format!("Equipped {name} got in a trade"), now);
            } else if o == REWARD {
                changed |= break_run(run, format!("Equipped {name}, bought or given as a reward (not a drop)"), now);
            } else if o == FORGED_TAINTED {
                changed |= break_run(run, format!("Equipped {name}, forged from things of the vault or a trade"), now);
            } else if o == UNKNOWN {
                if let Some(l) = self.left_vault(item, run.enchants[i].as_ref(), run.since[i], run.last_seen) {
                    let why = format!(
                        "Equipped {name}: the same item, enchantments and all, left the {} while the sniffer was off ({})",
                        vault_name(&l.vault),
                        span(c.zone, &l)
                    );
                    changed |= break_run(run, why, now);
                } else if let Some(any) = self.left_vault_any(item, run.since[i], run.last_seen) {
                    let text = format!(
                        "{name} is equipped, and a copy left the {} in the same time ({}); it may be its own drop",
                        vault_name(&any.vault),
                        span(c.zone, &any)
                    );
                    changed |= note(run, text);
                }
            }
            i += 1;
        }
        changed
    }

    /// Java `mark`: starts a run in this mode (None: keep the run's), or takes the mark off.
    pub fn mark(&mut self, c: &Ctx, char_id: i32, on: bool, mode: Option<&str>, no_pet: Option<bool>) {
        let Some(mut run) = self.runs.remove(&char_id) else { return };
        repair(&mut run);
        let ok = if on {
            let m = mode.filter(|m| MODES.contains(m)).map_or(run.mode.clone(), str::to_string);
            let np = no_pet.unwrap_or(run.no_pet);
            if run.can_start(&m, np) {
                run.mode = m;
                run.no_pet = np;
                run.marked = true;
                true
            } else {
                false
            }
        } else if run.marked {
            run.marked = false;
            true
        } else {
            false
        };
        if ok {
            run.marked_at = c.now;
            self.check(c, &mut run, c.now);
            self.dirty = true;
        }
        self.runs.insert(char_id, run);
    }

    /// For the comparison with Java.
    pub fn dump(&self) -> String {
        let q = |s: &str| lg_protocol::dump::to_string(&s.to_string());
        let ints = |v: &[i32]| format!("[{}]", v.iter().map(i32::to_string).collect::<Vec<_>>().join(","));
        let oints = |v: &Option<Vec<i32>>| v.as_ref().map_or("null".into(), |v| ints(v));
        let mut out = format!(
            "upe forged={}/{} traded={} reward={} quest={}/{} vaultmap={} where={} pending={} left={} missions={}\n",
            self.forged_at,
            self.forged_origin,
            self.traded_at,
            self.reward_at,
            self.quest_at,
            self.quest_origin,
            self.in_vault_map,
            q(&self.r#where),
            self.pending.len(),
            self.left.len(),
            self.missions.len()
        );
        let mut ids: Vec<&i32> = self.runs.keys().collect();
        ids.sort();
        for id in ids {
            let r = &self.runs[id];
            let gathered: Vec<String> =
                r.gathered.iter().map(|g| format!("{}/{}/{}/{}/{}", g.item, oints(&g.enchants), g.at, g.how, q(&g.r#where))).collect();
            let trades: Vec<String> =
                r.trades.iter().map(|t| format!("{}/{}/{}/{}/{}", t.at, q(&t.partner), t.gave.len(), t.got.len(), t.status)).collect();
            out.push_str(&format!(
                "run {} mode={} fresh={} marked={}@{} broken={}@{} notes={} seen={} items={} ench=[{}] origin=[{}] since={:?} lvl={} base={} allowed={} gathered=[{}] locked={} trades=[{}] t0={} pet={} nopet={}\n",
                r.char_id,
                r.mode,
                r.fresh,
                r.marked,
                r.marked_at,
                r.broken.as_deref().map_or("null".into(), q),
                r.broken_at,
                r.notes.iter().map(|n| q(n)).collect::<Vec<_>>().join("|"),
                r.last_seen,
                ints(&r.items),
                r.enchants.iter().map(oints).collect::<Vec<_>>().join(","),
                r.origin.join(","),
                r.since,
                r.level,
                r.base_stats.map_or("null".into(), |b| ints(&b)),
                r.allowed.iter().map(|a| ints(a)).collect::<Vec<_>>().join(";"),
                gathered.join(","),
                r.guild_locked,
                trades.join(","),
                r.t0_start.map_or("null".into(), |b| b.to_string()),
                r.pet,
                r.no_pet
            ).replace(" ", " "));
        }
        out
    }
}
