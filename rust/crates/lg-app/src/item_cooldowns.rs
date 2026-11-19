//! Items with a cooldown (Java: `ItemCooldowns`): when one can be used again. The quick slot items Coral Juice
//! Refinery (15 s) and Miniature Alien Generator (45 s), seen in the use packets you send; the Protective Matrix
//! armors (180 s, one cooldown for all), which fire by themselves: on a hit below half your life they give you
//! Invulnerable and Dazed at once, which the tick sees.
//!
//! Java plays each item's sound when it is ready again; that comes with the UI.

use lg_world::{World, stats};

pub const REFINERY: i32 = 44495;
pub const ALIEN_GENERATOR: i32 = 48164;
/// The Protective Matrix armors, all with the same shield and one shared cooldown.
pub const MATRICES: [(i32, &str); 9] = [
    (0x0483, "Heavy Protective Matrix"),
    (0x0484, "Magic Protective Matrix"),
    (0x0485, "Fitted Protective Matrix"),
    (0x1D4A, "Heavy Protective Matrix Shiny"),
    (0x1D49, "Magic Protective Matrix Shiny"),
    (0x1D48, "Fitted Protective Matrix Shiny"),
    (0xdc92, "Neo Heavy Protective Matrix"),
    (0xdc91, "Neo Magic Protective Matrix"),
    (0xdc90, "Neo Fitted Protective Matrix"),
];
const MATRIX_COOLDOWN_MS: i64 = 180_000;
const HIT_LOOK_BACK_MS: i64 = 1500;
const INVULNERABLE: i32 = 0x100_0000;
const DAZED: i32 = 0x20;

/// The sound a cooldown ends with: the matrices share one.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Sound {
    Refinery,
    Alien,
    Matrix,
}

impl Sound {
    /// Java's `ItemSound` constant.
    pub fn java_name(self) -> &'static str {
        match self {
            Sound::Refinery => "REFINERY",
            Sound::Alien => "ALIEN",
            Sound::Matrix => "MATRIX",
        }
    }
}

/// A cooldown running: the item used, its name, when it started and how long it takes (ms).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Running {
    pub item: i32,
    pub name: String,
    pub start: i64,
    pub duration_ms: i64,
}

impl Running {
    pub fn left(&self, now: i64) -> i64 {
        (self.start + self.duration_ms - now).max(0)
    }
}

#[derive(Debug, Default)]
pub struct ItemCooldowns {
    /// By sound, in the order started (a new use goes to the end).
    pub running: Vec<(Sound, Running)>,
    was_invulnerable: bool,
    /// When a matrix fired last (0: not yet), and which.
    pub matrix_fired: i64,
    pub matrix_type: i32,
}

fn watched(item: i32) -> Option<(&'static str, i64, Sound)> {
    match item {
        REFINERY => Some(("Coral Juice Refinery", 15_000, Sound::Refinery)),
        ALIEN_GENERATOR => Some(("Miniature Alien Generator", 45_000, Sound::Alien)),
        _ => MATRICES.iter().find(|m| m.0 == item).map(|m| (m.1, MATRIX_COOLDOWN_MS, Sound::Matrix)),
    }
}

impl ItemCooldowns {
    pub fn new() -> ItemCooldowns {
        ItemCooldowns { matrix_type: -1, ..Default::default() }
    }

    /// The cooldowns running now, in the order they started.
    pub fn running(&self, now: i64) -> Vec<&Running> {
        self.running.iter().map(|(_, r)| r).filter(|r| r.left(now) > 0).collect()
    }

    /// Java `used`: a watched item used starts (or starts over) its cooldown.
    pub fn used(&mut self, item: i32, now: i64) {
        let Some((name, ms, sound)) = watched(item) else { return };
        self.running.retain(|(s, _)| *s != sound);
        self.running.push((sound, Running { item, name: name.to_string(), start: now, duration_ms: ms }));
    }

    /// Java `armorTick`, every tick: wearing a matrix, the game putting Invulnerable on you right after a hit,
    /// with Dazed (or your life at half or less), is the armor firing; not within its cooldown.
    pub fn armor_tick(&mut self, w: &World, now: i64) {
        let Some(me) = w.me() else { return };
        let c = me.value(stats::CONDITION).unwrap_or(0);
        let invulnerable = c & INVULNERABLE != 0;
        let dazed = c & DAZED != 0;
        let fresh = invulnerable && !self.was_invulnerable;
        self.was_invulnerable = invulnerable;
        let item = me.value(stats::INVENTORY_0 + 2).unwrap_or(-1);
        if !fresh || watched(item).is_none_or(|w| w.2 != Sound::Matrix) || now - self.matrix_fired < MATRIX_COOLDOWN_MS {
            return;
        }
        let hit = me.damage_list.iter().any(|d| d.damage > 2 && now - d.time <= HIT_LOOK_BACK_MS);
        let (hp, max) = (me.value(stats::HP).unwrap_or(0), me.value(stats::MAX_HP).unwrap_or(0));
        let low = max > 0 && f64::from(hp) <= f64::from(max) * 0.5;
        if !hit || !(dazed || low) {
            return;
        }
        self.matrix_fired = now;
        self.matrix_type = item;
        self.used(item, now);
    }

    pub fn dump(&self) -> String {
        let r: Vec<String> = self
            .running
            .iter()
            .map(|(s, r)| format!("{}:{}/{}/{}/{}", s.java_name(), r.item, lg_protocol::dump::to_string(&r.name), r.start, r.duration_ms))
            .collect();
        format!("cooldowns [{}] was={} matrix={}/{}\n", r.join(","), self.was_invulnerable, self.matrix_fired, self.matrix_type)
    }
}

/// Your mana as of the latest tick (Java: `PlayerVitals`), for the mana overlay.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct Vitals {
    pub mp: i32,
    pub max_mp: i32,
    pub at: i64,
}

impl Vitals {
    pub fn tick(&mut self, w: &World, now: i64) {
        let Some(me) = w.me() else { return };
        if let (Some(mp), Some(max)) = (me.value(stats::MP), me.value(stats::MAX_MP)) {
            *self = Vitals { mp, max_mp: max, at: now };
        }
    }

    /// Whether the game reported it lately (you are logged in and playing).
    pub fn fresh(&self, now: i64) -> bool {
        self.max_mp > 0 && now - self.at < 5000
    }
}
