//! The times your Amulet of Restoration saved you (Java: `AmuletSaves`). The game data says when it fires: on a hit
//! of more than 2 damage taken below 20% of your life it makes you Invulnerable for 2 seconds and heals 120, at most
//! once every 30 minutes. So a save is the game putting Invulnerable on you while you wear it, if you were hit just
//! before, your life was at 20% or below, the amulet's last save is 30 minutes ago or more, and the game shows its
//! "+120" heal on you within 1.5 seconds of it. Game notifications about you and system texts that mention the amulet
//! count too. Each save is also marked on the dungeon visit.
//!
//! Java also plays a sound for a save and when the amulet is charged again, and logs notifications; not here (yet).

use crate::dungeon_visits::DungeonVisits;
use lg_world::json::{self, Json};
use lg_world::{World, stats};
use std::collections::VecDeque;
use std::path::Path;

const LOW_LIFE_PERCENT: i64 = 20;
const HEAL: i32 = 120;
const MIN_HIT: i32 = 2;
pub const RECHARGE_MS: i64 = 1_800_000;
const LOOK_BACK_MS: i64 = 1500;
/// "Played lately": the last two weeks.
const RECENT_MS: i64 = 14 * 24 * 3_600_000;
/// The "+120" comes with the Invulnerable, before or after it.
const HEAL_WINDOW_MS: i64 = 1500;
/// What the first version counted: any Invulnerable while wearing it, buffs included.
const OLD_RULE: &str = ": Invulnerable (";
const INVULNERABLE: i32 = 0x100_0000;

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Save {
    pub t: i64,
    pub char_id: i32,
    pub amulet: String,
    pub dungeon: String,
    pub message: String,
    /// Turned out not to be a save; kept so that syncing does not bring it back.
    pub removed: bool,
}

#[derive(Debug, Default)]
pub struct AmuletSaves {
    /// Java would write its file now (its `save()`): the app writes it.
    pub dirty: bool,

    /// Oldest first.
    pub saves: Vec<Save>,
    last_heal_at: i64,
    /// An Invulnerable that passed the other checks, waiting for the heal.
    pending_at: i64,
    pending_message: Option<String>,
    was_invulnerable: bool,
    /// Your life over the last moments: time, hp, max hp.
    life: VecDeque<(i64, i32, i32)>,
}

/// Java's `Math.round`: half up.
fn java_round(x: f64) -> i64 {
    let f = x.floor();
    if x - f >= 0.5 { f as i64 + 1 } else { f as i64 }
}

/// The game's heal number on you for the amulet's 120: `{"k":"s.plus_symbol","t":{"amount":"120",}}`.
fn is_amulet_heal(s: &str) -> bool {
    s.contains("plus_symbol") && s.replace(' ', "").contains(&format!("\"amount\":\"{HEAL}\""))
}

fn mentions_amulet(s: &str) -> bool {
    s.to_lowercase().contains("restoration")
}

impl AmuletSaves {
    /// What the Java app kept (`amulet_saves.json`), read only; saves the first version counted from Invulnerable
    /// alone are marked removed, as Java does on starting.
    pub fn import(dir: &Path) -> AmuletSaves {
        let mut a = AmuletSaves::default();
        if let Ok(text) = std::fs::read_to_string(dir.join("amulet_saves.json"))
            && let Some(saves) = parse_saves(&text)
        {
            a.saves = saves;
        }
        for s in &mut a.saves {
            if !s.removed && s.message.contains(OLD_RULE) {
                s.removed = true;
                a.dirty = true;
            }
        }
        a
    }

    /// Java `notification`: the amulet's heal on you (which a waiting Invulnerable needed), or a notification about
    /// you that mentions it.
    pub fn notification(&mut self, w: &World, visits: &mut DungeonVisits, n: &lg_protocol::packets::Notification, now: i64) {
        let msg = n.message.as_deref().unwrap_or("");
        let mine = w.me().is_some_and(|p| n.object_id == p.id || n.sender_object_id == p.id);
        if mine && is_amulet_heal(msg) {
            self.last_heal_at = now;
            if let Some(waiting) = self.take_pending(now) {
                self.record(w, visits, &waiting, now);
            }
        }
        if mine && mentions_amulet(msg) {
            self.record(w, visits, msg, now);
        }
    }

    /// Java `text`: a text the game sent (no player name) that mentions the amulet.
    pub fn text(&mut self, w: &World, visits: &mut DungeonVisits, t: &lg_protocol::packets::Text, now: i64) {
        let system = t.name.chars().all(char::is_whitespace) || t.name.starts_with('*');
        if system && mentions_amulet(&t.text) {
            self.record(w, visits, &t.text, now);
        }
    }

    /// Java `tick`, every tick: the game putting Invulnerable on you while you wear the amulet.
    pub fn tick(&mut self, w: &World, visits: &mut DungeonVisits, now: i64) {
        let Some(me) = w.me() else { return };
        let amulet = me.value(stats::INVENTORY_0 + 3).and_then(|r| w.data().amulets.of(r));
        let invulnerable = me.value(stats::CONDITION).is_some_and(|c| c & INVULNERABLE != 0);
        let (hp, max) = (me.value(stats::HP).unwrap_or(0), me.value(stats::MAX_HP).unwrap_or(0));
        if let Some(a) = amulet
            && a.invulnerable()
            && invulnerable
            && !self.was_invulnerable
            && max > 0
            && self.is_the_amulet(w, now, hp, max)
        {
            let msg = format!("{}: hit at {}% life, Invulnerable", a.name, self.lowest_percent(now, hp, max));
            if now - self.last_heal_at <= HEAL_WINDOW_MS {
                self.record(w, visits, &msg, now);
            } else {
                self.pending_at = now; // the heal may still come
                self.pending_message = Some(msg);
            }
        }
        self.was_invulnerable = invulnerable;
        if self.pending_at > 0 && now - self.pending_at > HEAL_WINDOW_MS {
            self.pending_at = 0; // no +120 heal (e.g. going to the Nexus)
            self.pending_message = None;
        }
        if max > 0 {
            self.life.push_back((now, hp, max));
        }
        while self.life.front().is_some_and(|l| now - l.0 > LOOK_BACK_MS) {
            self.life.pop_front();
        }
    }

    /// Java `notTheAmulet` is None: not within the cooldown of your character's last save, hit just before, low life.
    fn is_the_amulet(&self, w: &World, now: i64, hp: i32, max: i32) -> bool {
        let me = w.me().expect("you");
        if let Some(s) = self.saves.iter().rev().find(|s| !s.removed && s.char_id == me.char_id)
            && now - s.t < RECHARGE_MS
        {
            return false;
        }
        if !me.damage_list.iter().any(|d| d.damage > MIN_HIT && now - d.time <= LOOK_BACK_MS) {
            return false;
        }
        self.lowest_percent(now, hp, max) <= LOW_LIFE_PERCENT
    }

    /// Your lowest life in the last moments, in % (now counted without the amulet's heal).
    fn lowest_percent(&self, now: i64, hp: i32, max: i32) -> i64 {
        let mut low = f64::from((hp - HEAL).max(0)) / f64::from(max);
        for &(t, h, m) in &self.life {
            if now - t <= LOOK_BACK_MS {
                low = low.min(f64::from(h) / f64::from(m));
            }
        }
        java_round(low * 100.0)
    }

    fn take_pending(&mut self, t: i64) -> Option<String> {
        if self.pending_at == 0 || (t - self.pending_at).abs() > HEAL_WINDOW_MS {
            return None;
        }
        self.pending_at = 0;
        self.pending_message.take()
    }

    /// Java `record`: one save per few seconds (the same can come as a notification and a text).
    fn record(&mut self, w: &World, visits: &mut DungeonVisits, message: &str, now: i64) {
        let mut s = Save { t: now, message: message.to_string(), ..Default::default() };
        if let Some(me) = w.me() {
            s.char_id = me.char_id;
            if let Some(a) = me.value(stats::INVENTORY_0 + 3).and_then(|r| w.data().amulets.of(r)) {
                s.amulet = a.name.clone();
            }
        }
        s.dungeon = match (&visits.current, &w.map) {
            (Some(v), _) => v.dungeon.clone(),
            (None, Some(m)) => m.name.clone(),
            (None, None) => String::new(),
        };
        for old in self.saves.iter().rev() {
            if now - old.t >= 3000 {
                break;
            }
            if !old.removed {
                return;
            }
        }
        self.saves.push(s);
        self.dirty = true;
        visits.amulet_saved(now);
    }

    /// Java `mergeSync`: another PC's saves taken in; one counted by the first version of the rule, or removed there,
    /// is removed here too (and unmarked in its visit). True if anything changed.
    pub fn merge_sync(&mut self, remote: &Json, visits: &mut DungeonVisits, now: i64) -> Result<bool, crate::java::Thrown> {
        if *remote == Json::Null {
            return Ok(false);
        }
        let theirs = parse_saves(&remote.write()).ok_or(crate::java::Thrown)?;
        let mut changed = false;
        for mut s in theirs {
            match self.saves.iter_mut().find(|m| m.t == s.t) {
                None => {
                    if s.message.contains(OLD_RULE) && !s.removed {
                        s.removed = true; // counted by the first version from another client
                        visits.amulet_save_removed(s.t, now);
                    }
                    self.saves.push(s);
                    changed = true;
                }
                Some(mine) => {
                    if s.removed && !mine.removed {
                        mine.removed = true;
                        visits.amulet_save_removed(s.t, now);
                        changed = true;
                    }
                }
            }
        }
        if changed {
            self.saves.sort_by_key(|s| s.t);
            self.dirty = true;
        }
        Ok(changed)
    }

    /// Java `removedAt`: a save near `t` was found not to be one.
    pub fn removed_at(&self, t: i64) -> bool {
        self.saves.iter().any(|s| s.removed && (s.t - t).abs() <= 3000)
    }

    /// Java `count`: every save kept.
    pub fn count(&self) -> usize {
        self.saves.iter().filter(|s| !s.removed).count()
    }

    /// Java `countSince`.
    pub fn count_since(&self, since: i64) -> usize {
        self.saves.iter().filter(|s| !s.removed && s.t >= since).count()
    }

    /// Java `wornRecently`: a living character played in the last two weeks wears an amulet that saves you.
    pub fn worn_recently(chars: &crate::characters::Characters, data: &lg_gamedata::GameData, now: i64) -> bool {
        chars.all().any(|c| {
            c.alive
                && c.last_played >= now - RECENT_MS
                && c.equipment.as_ref().is_some_and(|e| e.len() > 3 && data.amulets.of(e[3]).is_some())
        })
    }

    /// Java `relevant`: worn lately, or a save in the last two weeks.
    pub fn relevant(&self, chars: &crate::characters::Characters, data: &lg_gamedata::GameData, now: i64) -> bool {
        AmuletSaves::worn_recently(chars, data, now) || self.count_since(now - RECENT_MS) > 0
    }

    pub fn dump(&self) -> String {
        let q = |s: &str| lg_protocol::dump::to_string(&s.to_string());
        let saves: Vec<String> = self
            .saves
            .iter()
            .map(|s| format!("{}/{}/{}/{}/{}/{}", s.t, s.char_id, q(&s.amulet), q(&s.dungeon), q(&s.message), s.removed))
            .collect();
        let life: Vec<String> = self.life.iter().map(|(t, h, m)| format!("{t}/{h}/{m}")).collect();
        format!(
            "amulet saves=[{}] heal={} pending={}/{} was={} life=[{}]\n",
            saves.join(","),
            self.last_heal_at,
            self.pending_at,
            self.pending_message.as_deref().map_or("null".into(), q),
            self.was_invulnerable,
            life.join(",")
        )
    }
}

/// Gson reading `ArrayList<Save>`: missing fields keep their defaults.
fn parse_saves(text: &str) -> Option<Vec<Save>> {
    let Json::Array(a) = json::parse(text).ok()? else { return None };
    let mut out = Vec::new();
    for s in a {
        let text = |k: &str| match s.get(k) {
            None => Some(String::new()),
            Some(Json::Null) => Some(String::new()),
            Some(v) => v.as_string().ok(),
        };
        out.push(Save {
            t: match s.get("t") {
                None => 0,
                Some(v) => v.as_string().ok()?.parse().ok()?,
            },
            char_id: s.get("charId").map_or(Some(0), |v| v.as_int().ok())?,
            amulet: text("amulet")?,
            dungeon: text("dungeon")?,
            message: text("message")?,
            removed: matches!(s.get("removed"), Some(Json::Bool(true))),
        });
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rounds_as_java() {
        assert_eq!([java_round(19.5), java_round(20.49), java_round(0.0), java_round(-2.5)], [20, 20, 0, -2]);
    }

    #[test]
    fn heals_and_mentions() {
        assert!(is_amulet_heal(r#"{"k":"s.plus_symbol","t":{"amount": "120",}}"#));
        assert!(!is_amulet_heal(r#"{"k":"s.plus_symbol","t":{"amount":"12",}}"#));
        assert!(mentions_amulet("Your Amulet of RESTORATION shines"));
    }

    #[test]
    fn imports_and_drops_old_rule_saves() {
        let s = parse_saves(r#"[{"t":5,"charId":42,"amulet":"A","dungeon":"D","message":"A: Invulnerable (x)","removed":false},{"t":6}]"#)
            .unwrap();
        assert_eq!(s.len(), 2);
        assert_eq!((s[1].t, s[1].char_id, s[1].message.as_str()), (6, 0, ""));
    }
}
