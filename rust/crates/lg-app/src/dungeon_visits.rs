//! Every dungeon you enter, from the moment you walk in (Java: `DungeonVisits`): the one in progress and the
//! finished visits, newest first, each with the hits you took, the damage you dealt and the way you walked per
//! second, the quick slot items you used, your death, and a link to its run in the run history when you fought a
//! boss.
//!
//! Moonlight Village's lantern and phases are kept here by [`crate::moonlight`], amulet saves by
//! [`crate::amulet_saves`].

use crate::characters::{Char, Characters};
use crate::dps_history::DpsHistory;
use lg_protocol::packets::UseItem;
use lg_protocol::packets::data::WorldPos;
use lg_world::fight::Fight;
use lg_world::{Map, stats};

const MAX_VISITS: usize = 500;
/// A run's start may be a little before the visit's.
const LINK_SLACK_MS: i64 = 60_000;
const HITS_INTERVAL_MS: i64 = 1000;
const MAX_HITS: usize = 5000;
/// Maps that are never dungeons (display names as the game sends them; `{s.rotmg}` is the realm).
const NOT_DUNGEONS: [&str; 7] = ["{s.vault}", "Daily Quest Room", "Pet Yard", "{s.guildhall}", "{s.nexus}", "Grand Bazaar", "{s.rotmg}"];
/// The game sends a quick slot's use with slot 1000000 + its index.
const QUICK_SLOT_ID: i32 = 1_000_000;
const QUICKSLOTS: [u8; 3] = [stats::POTION_ONE_TYPE, stats::POTION_TWO_TYPE, stats::POTION_THREE_TYPE];
/// A jump of more tiles than this is a teleport, not walked.
const MAX_STEP: f64 = 2.5;
/// Exaltation progress reported just after leaving still counts...
const EXALT_LATE_MS: i64 = 120_000;
/// ... also as the next map loads, within this time.
const EXALT_ENTRY_MS: i64 = 90_000;

/// Exaltation progress the game reported during a visit: one stat of one class, from before to after.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Exalt {
    pub class_type: i32,
    pub stat: i32,
    pub before: i32,
    pub after: i32,
}

/// One Moonlight Village phase: a dance (damage) or a lantern phase of one boss, named after its song. Times are ms
/// after entering; `lantern_ms` how long the lantern was there, `range_ms` / `near_ms` how long of that you were
/// within 3.5 / 1.75 tiles of it; `flames` what the phase scored; `beats` the song's beats you were in range.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MvPhase {
    pub boss: i32,
    pub title: String,
    pub start_ms: i64,
    pub duration_ms: i64,
    pub lantern: bool,
    pub lantern_ms: i64,
    pub range_ms: i64,
    pub near_ms: i64,
    pub flames: i32,
    pub beats: i32,
    pub beats_max: i32,
}

/// A character's look in a visit.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Look {
    pub player: String,
    pub class_type: i32,
    pub skin: i32,
    pub tex1: i32,
    pub tex2: i32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Visit {
    pub dungeon: String,
    pub start: i64,
    pub duration_ms: i64,
    /// `Run.start` of the run with your boss damage; 0 = no boss fought.
    pub run_start: i64,
    /// The sniffer started inside it: what happened before is missing.
    pub late: bool,
    pub hidden: bool,
    /// Exaltation progress the game reported in this dungeon.
    pub exalts: Vec<Exalt>,
    /// Every hit you took, oldest first: ms after entering, damage (after your defense), the enemy's object type
    /// (0 = none, below 0 a damaging tile's type, negated).
    pub hit_time: Vec<i32>,
    pub hit_damage: Vec<i32>,
    pub hit_enemy: Vec<i32>,
    /// Your maximum life.
    pub max_hp: i32,
    /// The damage you dealt, per second after entering.
    pub dealt: Vec<i32>,
    /// Moonlight Village: how long its lantern was there, and you within 3.5 / 1.75 tiles of it.
    pub lantern_active_ms: i64,
    pub lantern_range_ms: i64,
    pub lantern_near_ms: i64,
    /// Moonlight Village: its boss phases in order.
    pub mv_phases: Vec<MvPhase>,
    /// When your amulet saved you, ms after entering.
    pub amulet_saves: Vec<i32>,
    /// The consumables you used from a quick slot, and when (ms after entering).
    pub quick_item: Vec<i32>,
    pub quick_time: Vec<i32>,
    /// Your death in it (ms after entering) and what killed you; -1 / None = you survived.
    pub died_at: i32,
    pub killed_by: Option<String>,
    /// How far you walked, in tenths of a tile, per second after entering.
    pub moved: Vec<i32>,
    /// Whether it was completed can be told, and the game's "Victory".
    pub judged: bool,
    pub victory: bool,
    pub look: Option<Look>,
}

impl Visit {
    fn new(dungeon: &str, start: i64) -> Visit {
        Visit {
            dungeon: dungeon.to_string(),
            start,
            duration_ms: 0,
            run_start: 0,
            late: false,
            hidden: false,
            exalts: Vec::new(),
            hit_time: Vec::new(),
            hit_damage: Vec::new(),
            hit_enemy: Vec::new(),
            max_hp: 0,
            dealt: Vec::new(),
            lantern_active_ms: 0,
            lantern_range_ms: 0,
            lantern_near_ms: 0,
            mv_phases: Vec::new(),
            amulet_saves: Vec::new(),
            quick_item: Vec::new(),
            quick_time: Vec::new(),
            died_at: -1,
            killed_by: None,
            moved: Vec::new(),
            judged: true,
            victory: false,
            look: None,
        }
    }
}

#[derive(Debug)]
pub struct DungeonVisits {
    /// Finished, newest first.
    pub visits: Vec<Visit>,
    pub current: Option<Visit>,
    last_hits: i64,
    /// Your last position seen in this visit (NaN: none yet).
    last_x: f32,
    last_y: f32,
    /// Tiles walked not yet added to the visit (less than a tenth).
    unsaved: f64,
}

impl Default for DungeonVisits {
    fn default() -> Self {
        DungeonVisits { visits: Vec::new(), current: None, last_hits: 0, last_x: f32::NAN, last_y: 0.0, unsaved: 0.0 }
    }
}

/// Java `isDungeon`: a map with a portal (or a dungeon statistic) that is not the realm or a hub.
pub fn is_dungeon(data: &lg_gamedata::GameData, map: Option<&Map>) -> bool {
    let Some(m) = map else { return false };
    if NOT_DUNGEONS.contains(&m.display_name.as_str()) || m.name == "Realm of the Mad God" {
        return false;
    }
    data.dungeons.portal_by_name.contains_key(&m.name) || lg_gamedata::char_stats::is_statistic(&m.name)
}

/// The character you play, or (started mid-map) the one played last.
fn playing_or_last(chars: &Characters) -> Option<&Char> {
    chars.playing().or_else(|| chars.last_played_char())
}

/// Java `lookNow`: your character's look now, None before you played.
pub fn look_now(chars: &Characters) -> Option<Look> {
    let c = playing_or_last(chars)?;
    let name = chars.player_name.as_ref()?;
    if c.class_type <= 0 {
        return None;
    }
    Some(Look { player: name.clone(), class_type: c.class_type, skin: c.skin, tex1: c.tex1, tex2: c.tex2 })
}

impl DungeonVisits {
    /// Java `entered`, on every map change after the run history saved the run of the map left: ends the visit in
    /// progress and starts one if the new map is a dungeon.
    pub fn entered(&mut self, data: &lg_gamedata::GameData, map: Option<&Map>, chars: &Characters, dps: &DpsHistory, now: i64) {
        self.finish_current(chars, dps, now);
        if is_dungeon(data, map) {
            self.current = Some(Visit::new(&map.expect("a dungeon").name, now));
            self.last_x = f32::NAN;
            self.unsaved = 0.0;
        }
    }

    /// Java `enteredLate`: the sniffer started inside a dungeon, told by its enemies.
    pub fn entered_late(&mut self, data: &lg_gamedata::GameData, map: Option<&Map>, chars: &Characters, dps: &DpsHistory, now: i64) {
        self.entered(data, map, chars, dps, now);
        if let Some(v) = &mut self.current {
            v.late = true;
        }
    }

    /// Java `finishCurrent`.
    fn finish_current(&mut self, chars: &Characters, dps: &DpsHistory, now: i64) {
        let Some(mut left) = self.current.take() else { return };
        left.duration_ms = (now - left.start).max(1);
        left.look = look_now(chars);
        if let Some(run) = dps.latest()
            && !run.guild_hall
            && run.dungeon == left.dungeon
            && run.start >= left.start - LINK_SLACK_MS
        {
            left.run_start = run.start;
        }
        self.visits.insert(0, left);
        self.visits.truncate(MAX_VISITS);
    }

    /// Java `exaltProgress`: new progress for a class. The game sends it right after a dungeon is left, while the
    /// next map loads: within the first 90 s of a map entered straight after a dungeon it belongs to that dungeon;
    /// later in a dungeon, to that one; outside dungeons, to the one left at most 2 minutes ago.
    pub fn exalt_progress(&mut self, class_type: i32, before: &[i32], after: &[i32], now: i64) {
        let last_end = self.visits.first().map(|l| l.start + l.duration_ms);
        let to_last = match (&self.current, last_end) {
            (Some(c), end) => now - c.start < EXALT_ENTRY_MS && end.is_some_and(|e| c.start - e < EXALT_ENTRY_MS),
            (None, Some(e)) => now - e < EXALT_LATE_MS,
            (None, None) => false,
        };
        let v = if to_last { self.visits.first_mut() } else { self.current.as_mut() };
        let Some(v) = v else { return };
        for (i, (&b, &a)) in before.iter().zip(after).enumerate() {
            if a > b {
                v.exalts.push(Exalt { class_type, stat: i as i32, before: b, after: a });
            }
        }
    }

    /// Java `hits`: the hits you took and the damage you dealt per second, into the visit in progress; once a
    /// second from the tick, and right before a map change or on your death (`force`).
    pub fn hits(&mut self, f: &Fight, chars: &Characters, force: bool, now: i64) {
        let (Some(v), Some(player)) = (self.current.as_mut(), f.me()) else { return };
        if !force && now - self.last_hits < HITS_INTERVAL_MS {
            return;
        }
        self.last_hits = now;
        let cap = player.damage_list.len().min(MAX_HITS);
        let (mut time, mut dmg, mut enemy) = (Vec::new(), Vec::new(), Vec::new());
        for d in &player.damage_list {
            if time.len() >= cap {
                break;
            }
            if d.time < v.start || d.damage <= 0 {
                continue;
            }
            time.push((d.time - v.start) as i32);
            dmg.push(d.damage);
            enemy.push(f.owner_type(d).unwrap_or(d.source_type));
        }
        v.hit_time = time;
        v.hit_damage = dmg;
        v.hit_enemy = enemy;
        let mut max = player.value(stats::MAX_HP).unwrap_or(0);
        if max <= 0
            && v.max_hp <= 0
            && let Some(c) = playing_or_last(chars)
        {
            max = c.max_hp;
        }
        if max > 0 {
            v.max_hp = max;
        }
        let mut dealt = vec![0i32; ((now - v.start) / 1000 + 1).max(1) as usize];
        let mut any = false;
        for e in f.hits() {
            for d in &e.damage_list {
                if !d.owner.and_then(|o| f.entity(o)).is_some_and(|o| o.is_user) || d.time < v.start || d.damage <= 0 {
                    continue;
                }
                let s = ((d.time - v.start) / 1000) as i32 as usize;
                if s >= dealt.len() {
                    dealt.resize(s + 1, 0);
                }
                dealt[s] = dealt[s].wrapping_add(d.damage);
                any = true;
            }
        }
        if any {
            v.dealt = dealt;
        }
    }

    /// Java `itemUsed`: a consumable used from a quick slot counts for the visit in progress (the same item used
    /// from an inventory slot does not).
    pub fn item_used(&mut self, f: &Fight, u: &UseItem, now: i64) {
        let Some(v) = self.current.as_mut() else { return };
        let player = f.me();
        let (item, slot) = (u.slot_object.object_type, u.slot_object.slot_id);
        let quick_slot_id = (QUICK_SLOT_ID..QUICK_SLOT_ID + QUICKSLOTS.len() as i32).contains(&slot);
        if !quick_slot_id && player.is_none_or(|p| u.slot_object.object_id != p.id) {
            return;
        }
        let quick = quick_slot_id || player.is_some_and(|p| QUICKSLOTS.iter().any(|&q| p.value(q) == Some(item)));
        if !quick || item <= 0 {
            return;
        }
        if let Some(p) = player
            && (0..28).contains(&slot)
        {
            let stat = if slot >= 12 { i32::from(stats::BACKPACK_0) + slot - 12 } else { i32::from(stats::INVENTORY_0) + slot };
            if u8::try_from(stat).ok().and_then(|s| p.value(s)) == Some(item) {
                return; // the inventory's copy
            }
        }
        v.quick_item.push(item);
        v.quick_time.push((now - v.start) as i32);
    }

    /// Java `died`: the hits up to now first, then when and by what.
    pub fn died(&mut self, f: &Fight, chars: &Characters, killed_by: &str, now: i64) {
        self.hits(f, chars, true, now);
        if let Some(v) = self.current.as_mut() {
            v.died_at = (now - v.start) as i32;
            v.killed_by = Some(killed_by.to_string());
        }
    }

    /// Java `moved`: the way walked between your positions, per second (a jump of more than 2.5 tiles is a
    /// teleport).
    pub fn moved(&mut self, positions: &[WorldPos], now: i64) {
        let Some(v) = self.current.as_mut() else { return };
        for p in positions {
            if !self.last_x.is_nan() {
                let d = f64::from(p.x - self.last_x).hypot(f64::from(p.y - self.last_y));
                if d <= MAX_STEP {
                    self.unsaved += d;
                }
            }
            self.last_x = p.x;
            self.last_y = p.y;
        }
        let tenths = (self.unsaved * 10.0) as i32;
        if tenths <= 0 {
            return;
        }
        self.unsaved -= f64::from(tenths) / 10.0;
        let s = ((now - v.start) / 1000).max(0) as usize;
        if s >= v.moved.len() {
            v.moved.resize(s + 1, 0);
        }
        v.moved[s] = v.moved[s].wrapping_add(tenths);
    }

    /// Java `amuletSaved`: marked on the visit in progress.
    pub fn amulet_saved(&mut self, now: i64) {
        if let Some(v) = self.current.as_mut() {
            v.amulet_saves.push((now - v.start).max(0) as i32);
        }
    }

    /// Java `notification`: the game's "Victory" marks the visit.
    pub fn victory(&mut self) {
        if let Some(v) = self.current.as_mut() {
            v.victory = true;
        }
    }

    /// Everything, for the comparison with Java.
    pub fn dump(&self) -> String {
        let mut s = String::new();
        for v in &self.visits {
            s.push_str(&format!("visit {}\n", visit_line(v)));
        }
        s.push_str(&format!(
            "visit current {} hitsAt={} last={:08x}/{:08x} unsaved={:016x}\n",
            self.current.as_ref().map_or("null".into(), visit_line),
            self.last_hits,
            self.last_x.to_bits(),
            self.last_y.to_bits(),
            self.unsaved.to_bits()
        ));
        s
    }
}

fn visit_line(v: &Visit) -> String {
    let arr = |a: &[i32]| format!("[{}]", a.iter().map(i32::to_string).collect::<Vec<_>>().join(","));
    let q = |s: &str| lg_protocol::dump::to_string(&s.to_string());
    let look = v.look.as_ref().map_or("null".into(), |l| format!("{}/{}/{}/{}/{}", q(&l.player), l.class_type, l.skin, l.tex1, l.tex2));
    format!(
        "{} start={} ms={} run={} late={} hidden={} exalts=[{}] hits={}/{}/{} maxHp={} dealt={} quick={}/{} died={}/{} moved={} judged={} victory={} look={} lantern={}/{}/{} phases=[{}] amulet={}",
        q(&v.dungeon),
        v.start,
        v.duration_ms,
        v.run_start,
        v.late,
        v.hidden,
        v.exalts.iter().map(|e| format!("{}/{}/{}/{}", e.class_type, e.stat, e.before, e.after)).collect::<Vec<_>>().join(","),
        arr(&v.hit_time),
        arr(&v.hit_damage),
        arr(&v.hit_enemy),
        v.max_hp,
        arr(&v.dealt),
        arr(&v.quick_item),
        arr(&v.quick_time),
        v.died_at,
        v.killed_by.as_deref().map_or("null".into(), q),
        arr(&v.moved),
        v.judged,
        v.victory,
        look,
        v.lantern_active_ms,
        v.lantern_range_ms,
        v.lantern_near_ms,
        v.mv_phases
            .iter()
            .map(|p| format!(
                "{}/{}/{}/{}/{}/{}/{}/{}/{}/{}/{}",
                p.boss,
                q(&p.title),
                p.start_ms,
                p.duration_ms,
                p.lantern,
                p.lantern_ms,
                p.range_ms,
                p.near_ms,
                p.flames,
                p.beats,
                p.beats_max
            ))
            .collect::<Vec<_>>()
            .join(","),
        arr(&v.amulet_saves)
    )
}
