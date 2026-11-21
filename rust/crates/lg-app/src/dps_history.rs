//! Boss damage per dungeon run (Java: `DpsHistory`): a small summary of each run left with boss damage (or a guild
//! hall visit with shooting range damage), newest first, and a snapshot of the run you are in, once a second.

use lg_world::fight::Fight;
use lg_world::{Entity, Map, stats};
use std::collections::HashMap;

const MAX_RUNS: usize = 300;
const LIVE_INTERVAL_MS: i64 = 1000;
const GUILD_HALL: &str = "{s.guildhall}";
/// The guild hall's weapon rack: the icon of shooting range results.
pub const WEAPON_RACK: i32 = 480;
/// A shooting range test takes 5 seconds: a block lasts at most this long.
const TEST_MS: i64 = 6000;
/// Moonlight Village's bosses, which carry no boss label (Java `MoonlightLog.BOSSES`).
pub const MOONLIGHT_BOSSES: [i32; 4] = [20450, 20451, 20452, 20493];

/// One player's part in a boss fight.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct PlayerRow {
    pub name: String,
    pub class_type: i32,
    pub damage: i32,
    pub taken: i32,
    pub died: bool,
    pub me: bool,
    /// Their guild in this run ("" none); None in runs saved before it was kept.
    pub guild: Option<String>,
    /// The look in this run: skin (0 = the class's own) and dye textures (0 = none).
    pub skin: i32,
    pub tex1: i32,
    pub tex2: i32,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Boss {
    pub name: Option<String>,
    /// Its object id in its instance.
    pub id: i32,
    pub object_type: i32,
    /// Guild hall: your weapon, ability, armor, ring for this block (-1 = empty).
    pub loadout: Option<[i32; 4]>,
    pub max_hp: i32,
    /// Its life when the run was saved; -1 = not kept (older runs).
    pub end_hp: i32,
    pub fight_ms: i64,
    /// Its first hit (ms since 1970).
    pub fight_start: i64,
    pub players: Vec<PlayerRow>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Run {
    pub dungeon: String,
    pub start: i64,
    pub duration_ms: i64,
    /// A guild hall visit: one block per loadout you shot with.
    pub guild_hall: bool,
    /// Removed by you: kept but no longer shown.
    pub hidden: bool,
    pub bosses: Vec<Boss>,
}

impl Run {
    /// The boss with the most life, the first of equals (Java `mainBoss`: Stream.max keeps the first).
    pub fn main_boss(&self) -> Option<&Boss> {
        self.bosses.iter().fold(None, |best: Option<&Boss>, b| match best {
            Some(m) if m.max_hp >= b.max_hp => Some(m),
            _ => Some(b),
        })
    }
}

#[derive(Debug, Default)]
pub struct DpsHistory {
    /// Java would write its file now (its `save()`): the app writes it.
    pub dirty: bool,

    /// Newest first.
    pub runs: Vec<Run>,
    pub live: Option<Run>,
    last_live: i64,
    live_hits: usize,
    /// Each player's last own look (name/class -> skin, tex1, tex2), for while an ability transforms them.
    looks: HashMap<String, [i32; 3]>,
}

/// Java `isGuildHall`.
pub fn is_guild_hall(map: Option<&Map>) -> bool {
    let Some(m) = map else { return false };
    m.display_name == GUILD_HALL || format!("{} {}", m.display_name, m.name).to_lowercase().contains("guild hall")
}

/// Java `TomatoData.isLoggedDungeon`: maps whose fights the DPS logger keeps.
pub fn is_logged_dungeon(display_name: &str) -> bool {
    !matches!(display_name, "{s.vault}" | "Daily Quest Room" | "Pet Yard" | "{s.guildhall}" | "{s.nexus}" | "Grand Bazaar")
}

/// Java `Entity.isBossMob`.
pub fn is_boss_mob(data: &lg_gamedata::GameData, object_type: i32) -> bool {
    MOONLIGHT_BOSSES.contains(&object_type) || data.object(object_type).is_some_and(|o| o.has_label("BOSS") || o.has_label("MINIBOSS"))
}

/// Java `DeathParser.extractNameFromMessage`: the 10th part between quotes.
pub fn death_name(message: Option<&str>) -> Option<&str> {
    let mut parts: Vec<&str> = message?.split('"').collect();
    while parts.last() == Some(&"") {
        parts.pop(); // Java's split drops trailing empty parts
    }
    parts.get(9).copied()
}

/// Java `CharacterStore.liveTexture`: a dye's texture, or the value itself when it is one already.
pub fn live_texture(data: &lg_gamedata::GameData, v: i32) -> i32 {
    if v == 0 || (v as u32 >> 24) != 0 { v } else { data.dye_texture(v) }
}

/// A run starting before this (2017) has a broken start.
const SANE_START: i64 = 1_500_000_000_000;

/// Java `repair`: runs saved when the sniffer started inside a dungeon counted from 1970: their start becomes the
/// first boss hit, their end stays. Whether the run was changed.
pub fn repair(r: &mut Run) -> bool {
    if r.start >= SANE_START {
        return false;
    }
    let end = r.start + r.duration_ms;
    let first = r.bosses.iter().map(|b| b.fight_start).filter(|&f| f >= SANE_START).min();
    let Some(first) = first.filter(|&f| end >= f) else { return false };
    r.start = first;
    r.duration_ms = end - first;
    true
}

fn read_run(v: &lg_world::json::Json) -> Result<Run, crate::java::Thrown> {
    use crate::gson;
    let v = gson::object(v)?;
    let mut r = Run::default();
    if let Some(x) = gson::string(v.get("dungeon"))? {
        r.dungeon = x.unwrap_or_default();
    }
    if let Some(x) = gson::long(v.get("start"))? {
        r.start = x;
    }
    if let Some(x) = gson::long(v.get("durationMs"))? {
        r.duration_ms = x;
    }
    if let Some(x) = gson::boolean(v.get("guildHall"))? {
        r.guild_hall = x;
    }
    if let Some(x) = gson::boolean(v.get("hidden"))? {
        r.hidden = x;
    }
    r.bosses = gson::objects(v.get("bosses"), |b| {
        let b = gson::object(b)?;
        let mut x = Boss { name: Some(String::new()), end_hp: -1, ..Default::default() };
        if let Some(n) = gson::string(b.get("name"))? {
            x.name = n;
        }
        for (f, k) in [(&mut x.id, "id"), (&mut x.object_type, "objectType"), (&mut x.max_hp, "maxHp"), (&mut x.end_hp, "endHp")] {
            if let Some(n) = gson::int(b.get(k))? {
                *f = n;
            }
        }
        if let Some(l) = gson::ints(b.get("loadout"))? {
            x.loadout = l.and_then(|l| <[i32; 4]>::try_from(l).ok());
        }
        if let Some(n) = gson::long(b.get("fightMs"))? {
            x.fight_ms = n;
        }
        if let Some(n) = gson::long(b.get("fightStart"))? {
            x.fight_start = n;
        }
        x.players = gson::objects(b.get("players"), |p| {
            let p = gson::object(p)?;
            let mut y = PlayerRow::default();
            if let Some(n) = gson::string(p.get("name"))? {
                y.name = n.unwrap_or_default();
            }
            for (f, k) in [
                (&mut y.class_type, "classType"),
                (&mut y.damage, "damage"),
                (&mut y.taken, "taken"),
                (&mut y.skin, "skin"),
                (&mut y.tex1, "tex1"),
                (&mut y.tex2, "tex2"),
            ] {
                if let Some(n) = gson::int(p.get(k))? {
                    *f = n;
                }
            }
            if let Some(n) = gson::boolean(p.get("died"))? {
                y.died = n;
            }
            if let Some(n) = gson::boolean(p.get("me"))? {
                y.me = n;
            }
            if let Some(n) = gson::string(p.get("guild"))? {
                y.guild = n;
            }
            Ok(y)
        })?
        .unwrap_or_default();
        Ok(x)
    })?
    .unwrap_or_default();
    Ok(r)
}

impl DpsHistory {
    /// Java `remove`: the run (by its start) only marked removed, no longer shown.
    pub fn remove(&mut self, start: i64) {
        if let Some(r) = self.runs.iter_mut().find(|r| r.start == start && !r.hidden) {
            r.hidden = true;
            self.dirty = true;
        }
    }

    /// Java `clear`: every saved run gone.
    pub fn clear(&mut self) {
        self.runs.clear();
        self.dirty = true;
    }

    /// What the Java app kept (`dps_history.json`), read only, with Java's repair of broken starts (then newest
    /// first again). Unreadable: nothing.
    pub fn import(dir: &std::path::Path) -> DpsHistory {
        let mut h = DpsHistory::default();
        if let Ok(text) = std::fs::read_to_string(dir.join("dps_history.json")) {
            h.runs = crate::gson::root_list(&text, read_run).unwrap_or_default();
        }
        let mut repaired = false;
        for r in &mut h.runs {
            repaired |= repair(r);
        }
        if repaired {
            h.runs.sort_by_key(|r| std::cmp::Reverse(r.start));
            h.dirty = true;
        }
        h
    }

    /// Java `mergeSync`: another PC's runs taken in (repaired first, so a broken run matches its repaired copy here);
    /// a run removed there is removed here too. Newest first, at most `MAX_RUNS`. True if anything changed.
    pub fn merge_sync(&mut self, remote: &lg_world::json::Json) -> Result<bool, crate::java::Thrown> {
        let theirs = crate::gson::root_list(&remote.write(), read_run)?;
        let mut changed = false;
        let mut known: HashMap<String, usize> =
            self.runs.iter().enumerate().map(|(i, r)| (format!("{}|{}", r.start, r.dungeon), i)).collect();
        for mut r in theirs {
            repair(&mut r);
            let key = format!("{}|{}", r.start, r.dungeon);
            match known.get(&key) {
                None => {
                    known.insert(key, self.runs.len());
                    self.runs.push(r);
                    changed = true;
                }
                Some(&i) => {
                    if r.hidden && !self.runs[i].hidden {
                        // removed on the other side
                        self.runs[i].hidden = true;
                        changed = true;
                    }
                }
            }
        }
        if changed {
            self.runs.sort_by_key(|r| std::cmp::Reverse(r.start));
            self.runs.truncate(MAX_RUNS);
            self.dirty = true;
        }
        Ok(changed)
    }

    /// The newest saved run.
    pub fn latest(&self) -> Option<&Run> {
        self.runs.first()
    }

    /// Java `record`: a logged dungeon or a guild hall visit ended. Runs without boss (or shooting range) damage
    /// are not kept.
    pub fn record(&mut self, f: &Fight, now: i64) {
        let run = self.summarize(f, now);
        self.live = None;
        self.live_hits = 0;
        if let Some(r) = run {
            self.runs.insert(0, r);
            self.runs.truncate(MAX_RUNS);
            self.dirty = true;
        }
    }

    /// Java `liveTick`, every tick: the live snapshot once a second, and right away when an object is hit for the
    /// first time.
    pub fn live_tick(&mut self, f: &Fight, now: i64) {
        let hits = f.hit_list.len();
        if now - self.last_live < LIVE_INTERVAL_MS && hits == self.live_hits {
            return;
        }
        self.last_live = now;
        self.live_hits = hits;
        self.live = self.summarize(f, now);
    }

    /// Java `summarize`; None where Java's read throws (a name of only commas) too.
    fn summarize(&mut self, f: &Fight, now: i64) -> Option<Run> {
        let map = f.map?;
        if is_guild_hall(Some(map)) {
            return shooting_range(f, now);
        }
        let dead: Vec<&str> = f.deaths.iter().filter_map(|n| death_name(n.message.as_deref())).collect();
        let mut bosses: Vec<&Entity> =
            f.hits().filter(|e| !f.data.is_player_class(e.object_type) && is_boss_mob(f.data, e.object_type)).collect();
        if bosses.is_empty() {
            return None;
        }
        bosses.sort_by_key(|e| e.first_damage);
        let mut run = new_run(&map.name, f.dungeon_time, now);
        for e in bosses {
            let mut b = Boss {
                name: f.name(e).ok()?,
                id: e.id,
                object_type: e.object_type,
                max_hp: e.value(stats::MAX_HP).unwrap_or(0),
                end_hp: e.value(stats::HP).unwrap_or(0),
                fight_ms: (e.last_damage - e.first_damage).max(0),
                fight_start: e.first_damage.max(0),
                ..Default::default()
            };
            for (d, owner) in f.player_damage_list(e) {
                let Some(o) = owner else { continue };
                if d.damage <= 0 {
                    continue;
                }
                let mut p =
                    PlayerRow { name: f.name(o).ok()?.unwrap_or_else(|| "?".into()), class_type: o.object_type, ..Default::default() };
                self.look(f.data, &mut p, o);
                p.damage = d.damage;
                p.taken = Fight::damage_taken(o, Some(e)).0;
                p.died = dead.contains(&p.name.as_str());
                p.me = o.is_user;
                p.guild = Some(o.text(stats::GUILD_NAME).unwrap_or("").to_string());
                b.players.push(p);
            }
            if !b.players.is_empty() {
                run.bosses.push(b);
            }
        }
        (!run.bosses.is_empty()).then_some(run)
    }

    /// Java `look`: the skin and dyes worn now; while an ability transforms them, their look from before.
    fn look(&mut self, data: &lg_gamedata::GameData, p: &mut PlayerRow, e: &Entity) {
        let skin = e.value(stats::SKIN_ID);
        let key = format!("{}/{}", p.name, p.class_type);
        if let Some(s) = skin
            && data.items.transform_skins.contains(&s)
        {
            if let Some(seen) = self.looks.get(&key) {
                [p.skin, p.tex1, p.tex2] = *seen;
            }
            return;
        }
        if let Some(s) = skin.filter(|&s| s > 0) {
            p.skin = s;
        }
        if let Some(t) = e.value(stats::TEX1) {
            p.tex1 = live_texture(data, t);
        }
        if let Some(t) = e.value(stats::TEX2) {
            p.tex2 = live_texture(data, t);
        }
        self.looks.insert(key, [p.skin, p.tex1, p.tex2]);
    }

    /// Everything, for the comparison with Java.
    pub fn dump(&self) -> String {
        let mut s = String::new();
        for r in &self.runs {
            s.push_str(&format!("dps run {}\n", run_line(r)));
        }
        s.push_str(&format!(
            "dps live {} at={} hits={}\n",
            self.live.as_ref().map_or("null".into(), run_line),
            self.last_live,
            self.live_hits
        ));
        let mut looks: Vec<_> = self.looks.iter().collect();
        looks.sort();
        let looks: Vec<String> = looks.iter().map(|(k, v)| format!("{}={}/{}/{}", q(k), v[0], v[1], v[2])).collect();
        s.push_str(&format!("dps looks [{}]\n", looks.join(",")));
        s
    }
}

fn new_run(dungeon: &str, duration_ms: i64, now: i64) -> Run {
    let duration_ms = duration_ms.max(0);
    Run { dungeon: dungeon.to_string(), start: now - duration_ms, duration_ms, ..Default::default() }
}

/// Java `shootingRange`: a guild hall visit, one block per test (at most 6 seconds from its first hit, and anew
/// whenever your equipment differs from your previous hit), holding your damage.
fn shooting_range(f: &Fight, now: i64) -> Option<Run> {
    let mut mine = Vec::new();
    for e in f.hits() {
        if f.data.is_player_class(e.object_type) {
            continue;
        }
        for d in &e.damage_list {
            if d.damage > 0 && d.owner.and_then(|o| f.entity(o)).is_some_and(|o| o.is_user) {
                mine.push(d);
            }
        }
    }
    if mine.is_empty() {
        return None;
    }
    mine.sort_by_key(|d| d.time);
    let mut run = new_run("Guild Hall", f.dungeon_time, now);
    run.guild_hall = true;
    let mut spans: Vec<[i64; 2]> = Vec::new();
    for d in mine {
        let owner = f.entity(d.owner.expect("yours")).expect("yours");
        let new_test = !run.bosses.is_empty() && d.time - spans.last().expect("a block")[0] >= TEST_MS;
        if run.bosses.is_empty() || new_test || run.bosses.last().expect("a block").loadout != d.owner_inventory {
            let id = run.bosses.len() as i32 + 1;
            let you = PlayerRow {
                name: f.name(owner).ok()?.unwrap_or_else(|| "?".into()),
                class_type: owner.object_type,
                me: true,
                ..Default::default()
            };
            run.bosses.push(Boss {
                id,
                name: Some(format!("Test {id}")),
                object_type: WEAPON_RACK,
                loadout: d.owner_inventory,
                end_hp: -1,
                players: vec![you],
                ..Default::default()
            });
            spans.push([d.time, d.time]);
        }
        let b = run.bosses.last_mut().expect("a block");
        b.players[0].damage = b.players[0].damage.wrapping_add(d.damage);
        spans.last_mut().expect("a block")[1] = d.time;
    }
    for (b, s) in run.bosses.iter_mut().zip(&spans) {
        b.fight_ms = (s[1] - s[0]).max(1);
    }
    Some(run)
}

fn q(s: &str) -> String {
    lg_protocol::dump::to_string(&s.to_string())
}

fn run_line(r: &Run) -> String {
    let bosses: Vec<String> = r
        .bosses
        .iter()
        .map(|b| {
            let players: Vec<String> = b
                .players
                .iter()
                .map(|p| {
                    format!(
                        "{}/{}/{}/{}/{}/{}/{}/{}/{}/{}",
                        q(&p.name),
                        p.class_type,
                        p.damage,
                        p.taken,
                        p.died,
                        p.me,
                        p.guild.as_deref().map_or("null".into(), q),
                        p.skin,
                        p.tex1,
                        p.tex2
                    )
                })
                .collect();
            let loadout = b.loadout.map_or("null".into(), |l| format!("[{}]", l.map(|v| v.to_string()).join(",")));
            format!(
                "{{{} id={} t={} load={} hp={}/{} ms={} at={} [{}]}}",
                b.name.as_deref().map_or("null".into(), q),
                b.id,
                b.object_type,
                loadout,
                b.end_hp,
                b.max_hp,
                b.fight_ms,
                b.fight_start,
                players.join(",")
            )
        })
        .collect();
    format!("{} start={} ms={} gh={} hidden={} [{}]", q(&r.dungeon), r.start, r.duration_ms, r.guild_hall, r.hidden, bosses.join(","))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn death_names() {
        let m = r#"{"k":"s.death","t":{"player":"Bob","enemy":"x"}}"#;
        // split by quotes: {, k, :, s.death, ,, t, :{, player, :, Bob, ...
        assert_eq!(death_name(Some(m)), Some("Bob"));
        assert_eq!(death_name(Some(r#"a"b"c"#)), None);
        assert_eq!(death_name(None), None);
    }

    #[test]
    fn guild_halls() {
        let m = |d: &str, n: &str| Map { name: n.into(), display_name: d.into(), width: 0, height: 0, seed: 0 };
        assert!(is_guild_hall(Some(&m("{s.guildhall}", "x"))));
        assert!(is_guild_hall(Some(&m("x", "My Guild Hall"))));
        assert!(!is_guild_hall(Some(&m("{s.nexus}", "Nexus"))));
        assert!(!is_guild_hall(None));
    }
}
