//! Moonlight Village (Java: `MoonlightLog`): how long its lantern is there and you near it, kept with the dungeon
//! visit, and the boss fights split into their phases (each starts with a progress bar named after its song, belongs
//! to the boss whose condition turns active, is a lantern phase if the lantern is there, and ends with its flames or
//! when its boss turns idle).
//!
//! Java also writes a research log of the dungeon's objects (logs/MoonlightVillage-<date>.log); that diagnostic is
//! not written here.

use crate::dungeon_visits::{DungeonVisits, MvPhase};
use lg_world::World;
use std::collections::{HashMap, HashSet};

pub const DUNGEON: &str = lg_world::MOONLIGHT_VILLAGE;
/// MV Lantern System.
pub const LANTERN: i32 = 20454;
/// The tutorial lantern's inner and outer ring, in tiles.
pub const NEAR: f64 = 1.75;
pub const RANGE: f64 = 3.5;
/// MV Total Counter: the flames a finished phase scores.
const FLAME: i32 = 20518;
/// A boss's condition in its phases.
const DANCING: i32 = 67_108_864;
const LANTERN_UP: i32 = 16_777_216;
/// A boss may turn active just after its phase's bar.
const LATE_BOSS_MS: i64 = 2000;
const TICK_MS: i64 = 1000;

#[derive(Debug, Default)]
pub struct Moonlight {
    active: bool,
    /// Object id -> its name, for the dungeon's objects followed.
    tracked: HashMap<i32, String>,
    /// Object id -> the last condition sent (as Java writes it), for the followed objects.
    conditions: HashMap<i32, String>,
    flames: HashSet<i32>,
    /// The lanterns there now (the world keeps removed objects).
    lanterns: HashSet<i32>,
    types: HashMap<i32, i32>,
    last_tick: i64,
    /// The latest phase is the visit's last one; open while `phase_open`.
    phase: bool,
    phase_open: bool,
    current_boss: i32,
}

/// Java `relevant`: the dungeon's objects worth following.
fn relevant(name: &str) -> bool {
    let n = name.to_lowercase();
    n.starts_with("mv ") || n.contains("flame") || n.contains("umi") || n.contains("lantern") || n.starts_with("moonlight")
}

/// Your distance to the nearest lantern there now, in tiles.
fn lantern_distance(w: &World, lanterns: &HashSet<i32>) -> Option<f64> {
    let me = w.me()?.pos?;
    w.entities
        .values()
        .filter(|e| e.object_type == LANTERN && lanterns.contains(&e.id))
        .filter_map(|e| e.pos)
        .map(|p| {
            let (x, y) = (f64::from(me.x - p.x), f64::from(me.y - p.y));
            (x * x + y * y).sqrt()
        })
        .reduce(f64::min)
}

impl Moonlight {
    /// Java `entered`: a map change (or the map told by its enemies).
    pub fn entered(&mut self, map_name: &str) {
        *self = Moonlight { active: map_name == DUNGEON, ..Default::default() };
    }

    /// Java `added`: a new object in the dungeon; a flame scores the phase and ends it.
    pub fn added(&mut self, w: &World, visits: &mut DungeonVisits, id: i32, object_type: i32, now: i64) {
        if !self.active || !w.in_moonlight() {
            return;
        }
        let key = w.data().object(object_type).map_or_else(|| format!("type {object_type}"), |o| o.id_name.clone());
        if !relevant(&key) {
            return;
        }
        self.tracked.insert(id, key);
        self.types.insert(id, object_type);
        if object_type == LANTERN {
            self.lanterns.insert(id);
        }
        if object_type == FLAME && self.phase && self.flames.insert(id) {
            if let Some(p) = Self::last(visits) {
                p.flames += 1;
            }
            self.end_phase(visits, now);
        }
    }

    /// Java `removed`.
    pub fn removed(&mut self, id: i32) {
        if !self.active {
            return;
        }
        self.lanterns.remove(&id);
        self.tracked.remove(&id);
    }

    /// Java `changed`, for an object's condition: a boss's tells its phase.
    pub fn conditions(&mut self, w: &World, visits: &mut DungeonVisits, id: i32, conditions: &[(String, i32)], now: i64) {
        if !self.active || !w.in_moonlight() || !self.tracked.contains_key(&id) {
            return;
        }
        for (text, value) in conditions {
            if self.conditions.insert(id, text.clone()).as_ref() == Some(text) {
                continue; // unchanged
            }
            let t = self.types.get(&id).copied().unwrap_or(0);
            self.boss_condition(visits, t, *value, now);
        }
    }

    /// Java `message`, for a notification: a named progress bar starts a phase, its steps are the song's beats.
    pub fn notification(&mut self, w: &World, visits: &mut DungeonVisits, n: &lg_protocol::packets::Notification, now: i64) {
        if !self.active || !w.in_moonlight() || n.effect != lg_protocol::packets::NotificationEffect::ProgressBar {
            return;
        }
        if n.extra == 42 {
            self.start_phase(visits, n.message.as_deref(), now);
        } else if n.extra == 40 && self.phase_open {
            let d = lantern_distance(w, &self.lanterns);
            let Some(p) = Self::last(visits) else { return };
            if n.progress_max > 0 {
                p.beats_max = n.progress_max;
            }
            if let Some(d) = d {
                p.lantern = true;
                if d <= RANGE {
                    p.beats += 1;
                }
            }
        }
    }

    fn last(visits: &mut DungeonVisits) -> Option<&mut MvPhase> {
        visits.current.as_mut().and_then(|v| v.mv_phases.last_mut())
    }

    fn start_phase(&mut self, visits: &mut DungeonVisits, title: Option<&str>, now: i64) {
        let Some(title) = title else { return };
        if visits.current.is_none() || title.contains("Tutorial") {
            return;
        }
        self.end_phase(visits, now);
        let v = visits.current.as_mut().expect("a visit");
        v.mv_phases.push(MvPhase { boss: self.current_boss, title: title.to_string(), start_ms: now - v.start, ..Default::default() });
        self.phase = true;
        self.phase_open = true;
    }

    fn end_phase(&mut self, visits: &mut DungeonVisits, now: i64) {
        let Some(v) = visits.current.as_mut() else { return };
        if !self.phase_open {
            return;
        }
        self.phase_open = false;
        let start = v.start;
        let p = v.mv_phases.last_mut().expect("the phase");
        p.duration_ms = (now - start - p.start_ms).max(1);
    }

    /// Java `bossCondition`.
    fn boss_condition(&mut self, visits: &mut DungeonVisits, object_type: i32, condition: i32, now: i64) {
        if !crate::dps_history::MOONLIGHT_BOSSES.contains(&object_type) {
            return;
        }
        let open = self.phase_open;
        if condition & (DANCING | LANTERN_UP) != 0 {
            self.current_boss = object_type;
            let Some(v) = visits.current.as_mut() else { return };
            let start = v.start;
            if let Some(p) = v.mv_phases.last_mut().filter(|_| open) {
                if p.boss != object_type && now - start - p.start_ms <= LATE_BOSS_MS {
                    p.boss = object_type;
                }
                if condition & LANTERN_UP != 0 && p.boss == object_type {
                    p.lantern = true;
                }
            }
        } else if open && Self::last(visits).is_some_and(|p| p.boss == object_type) {
            self.end_phase(visits, now);
        }
    }

    /// Java `tick`, once a second: the lantern's presence and your distance to it, added up on the visit (and the
    /// phase in progress, which makes it a lantern phase).
    pub fn tick(&mut self, w: &World, visits: &mut DungeonVisits, now: i64) {
        if !self.active || !w.in_moonlight() || w.me().is_none_or(|m| m.pos.is_none()) {
            return;
        }
        let dt = if self.last_tick == 0 { 0 } else { (now - self.last_tick).min(5000) };
        if self.last_tick != 0 && dt < TICK_MS {
            return;
        }
        self.last_tick = now;
        let best = lantern_distance(w, &self.lanterns);
        let (Some(best), Some(v)) = (best, visits.current.as_mut()) else { return };
        if dt == 0 {
            return;
        }
        v.lantern_active_ms += dt;
        if best <= RANGE {
            v.lantern_range_ms += dt;
        }
        if best <= NEAR {
            v.lantern_near_ms += dt;
        }
        if self.phase_open
            && let Some(p) = v.mv_phases.last_mut()
        {
            p.lantern = true;
            p.lantern_ms += dt;
            if best <= RANGE {
                p.range_ms += dt;
            }
            if best <= NEAR {
                p.near_ms += dt;
            }
        }
    }

    pub fn dump(&self) -> String {
        let mut tracked: Vec<_> = self.tracked.iter().map(|(k, v)| format!("{k}:{}", lg_protocol::dump::to_string(v))).collect();
        tracked.sort();
        let mut lanterns: Vec<_> = self.lanterns.iter().collect();
        lanterns.sort();
        let lanterns: Vec<String> = lanterns.iter().map(|l| l.to_string()).collect();
        format!(
            "moonlight active={} tracked=[{}] lanterns=[{}] flames={} tick={} phase={}/{} boss={}\n",
            self.active,
            tracked.join(","),
            lanterns.join(","),
            self.flames.len(),
            self.last_tick,
            self.phase,
            self.phase_open,
            self.current_boss
        )
    }
}
