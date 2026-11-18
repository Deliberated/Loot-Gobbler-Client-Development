//! Loot (Java: `TomatoData.lootTick`, its `LootAttributionManager`, `DungeonStatData`): which enemy dropped which
//! bag, and per dungeon what was hit and what dropped.
//!
//! A bag that comes into view is matched a tick later to the closest enemy killed (one you hit that left the view).
//! A few bosses drop their bags only after their last words: those words open a window of two ticks in which bags
//! are theirs. Java keeps bags and killed enemies as objects, so after a map change they are still matched, as they
//! were: here they are kept as copies then ([`Held`]).

use super::*;
use lg_protocol::packets::Text;

/// A bag or killed enemy as Java's loot tick holds it: the object in view, or a copy from before a map change.
#[derive(Debug, Clone)]
pub(crate) enum Held {
    Live(i32),
    Copy(Box<Entity>),
}

/// Who dropped a bag.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Dropper {
    /// An enemy killed that tick.
    Killed { id: i32, object_type: i32 },
    /// A boss told by its last words; HM/TR variants have a suffix.
    Boss { object_type: i32, variant: Option<String> },
}

impl Dropper {
    pub fn object_type(&self) -> i32 {
        match self {
            Dropper::Killed { object_type, .. } | Dropper::Boss { object_type, .. } => *object_type,
        }
    }
}

/// A bag dropped, and by whom.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Drop {
    pub bag: i32,
    pub bag_type: i32,
    /// The bag's slots (0 for empty), as when it was matched.
    pub items: [i32; 8],
    pub dropper: Option<Dropper>,
    pub map: Option<String>,
}

/// One dungeon's statistics (Java: `DungeonInfo`): kept in `dungeon.stats`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DungeonInfo {
    pub entered: i32,
    pub total_time_ms: i64,
    /// Enemy type -> how many of them you hit.
    pub damaged: BTreeMap<i32, i32>,
    /// Enemy type (0: unknown) -> item -> how many dropped.
    pub loot: BTreeMap<i32, BTreeMap<i32, i32>>,
}

/// Java `DungeonStatData`.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DungeonStats {
    pub data: BTreeMap<String, DungeonInfo>,
    /// The dungeon being counted.
    pub info: Option<String>,
}

impl DungeonStats {
    /// The dungeon to count into: Java's `info`, or nothing for a different one ("dungeon mismatch").
    fn current(&mut self, dungeon: &str) -> Option<&mut DungeonInfo> {
        match &self.info {
            None => self.info = Some(dungeon.to_string()),
            Some(i) if i != dungeon => return None,
            Some(_) => {}
        }
        Some(self.data.entry(dungeon.to_string()).or_default())
    }

    /// Java `updateEntityDamage`: you hit an enemy of this type (once per enemy).
    pub(crate) fn entity_damage(&mut self, dungeon: &str, object_type: i32) {
        if object_type == 0 {
            return;
        }
        if let Some(i) = self.current(dungeon) {
            *i.damaged.entry(object_type).or_insert(0) += 1;
        }
    }

    /// Java `updateItems`.
    fn items(&mut self, dungeon: &str, dropper_type: i32, items: &[i32; 8]) {
        if let Some(i) = self.current(dungeon) {
            for &item in items.iter().filter(|&&v| v >= 1) {
                *i.loot.entry(dropper_type).or_default().entry(item).or_insert(0) += 1;
            }
        }
    }

    /// Java `updateDungeon`: the dungeon was left.
    fn left(&mut self, dungeon: &str, time_ms: i64) {
        if self.info.as_deref() == Some(dungeon) {
            if let Some(i) = self.data.get_mut(dungeon) {
                i.total_time_ms += time_ms;
                i.entered += 1;
            }
            self.info = None;
        }
    }
}

/// Java `LootAttributionManager`: the bosses' windows.
#[derive(Debug, Clone, Default)]
pub(crate) struct Windows {
    /// The boss (object type) whose bags these are, 0 for none (Java -1).
    mob: i32,
    variant: Option<String>,
    seed: i32,
    remaining: i32,
}

/// The bosses whose last words open a window: (speaker, words, boss type, variant).
const LAST_WORDS: &[(&str, &str, i32, Option<&str>)] = &[
    ("#Kitsune Umi", "This fully concludes the Moonlight Festival!", 20493, None),
    ("#Dancer Miko", "Thank you all for coming tonight.", 20451, None),
    ("#Umi, Goddess of Revelry", "This fully concludes the Moonlight Festival.", 20493, Some("TR")),
    ("#Void Entity", "You fools... You can never truly defeat me! I am in all of you! I AM all of you!", 45076, None),
    ("#The Bridge Sentinel", "I tried to protect you... I have failed.", 29003, None),
    ("#Valen the Unbreakable", "I see now... my strength could not have held against this growing power.", 29003, Some("HM")),
    ("#Twilight Archmage", "Wait, there's still time! I JUST NEED MORE POWER! WAIT!", 29021, None),
    (
        "#Nox the Wild Shadow",
        "Unworthy as you are to know what hides beyond, I've had... an epiphany. So in case you've failed to realize...",
        29021,
        Some("HM"),
    ),
    ("#The Accursed King", "...do you truly think your end will be any different?", 29039, None),
    ("#King Azamoth", "This fate is mine to bear... not hers.", 29039, Some("HM")),
];

impl Windows {
    pub(crate) fn new() -> Windows {
        Windows { mob: -1, variant: None, seed: -1, remaining: 0 }
    }

    /// Java `handleTextPacket`.
    pub(crate) fn text(&mut self, t: &Text, seed: i32) {
        if let Some((_, _, mob, variant)) = LAST_WORDS.iter().find(|(n, w, _, _)| *n == t.name && *w == t.text) {
            self.mob = *mob;
            self.seed = seed;
            self.variant = variant.map(str::to_string);
            self.remaining = 2;
        }
    }

    /// For the dump: boss/seed/ticks left/variant.
    pub(crate) fn describe(&self) -> String {
        format!("{}/{}/{}/{}", self.mob, self.seed, self.remaining, self.variant.as_deref().unwrap_or("null"))
    }

    /// Java `endLootTick`.
    fn end(&mut self) {
        if self.remaining > 0 {
            self.remaining -= 1;
        }
        if self.remaining == 0 {
            self.mob = -1;
            self.variant = None;
            self.seed = -1;
        }
    }
}

impl World {
    fn held<'a>(&'a self, h: &'a Held) -> Option<&'a Entity> {
        match h {
            Held::Live(id) => self.entities.get(id),
            Held::Copy(e) => Some(e),
        }
    }

    fn map_seed(&self) -> i32 {
        self.map.as_ref().map_or(-1, |m| m.seed)
    }

    /// Java `lootTick`, at the end of each tick: last tick's bags matched to who dropped them. Where Java throws
    /// (an enemy or bag without a position), that tick's matching stops, as Java's catch there leaves it.
    pub(crate) fn loot_tick(&mut self) {
        self.loot_tick_toggle ^= 1;
        let bags = std::mem::take(&mut self.loot_tick_bags[self.loot_tick_toggle]);
        if !bags.is_empty() {
            let _ = self.match_bags(&bags);
        }
        self.killed.clear();
    }

    fn match_bags(&mut self, bags: &[Held]) -> Result<(), Abort> {
        let seed = self.map_seed();
        let mut found = Vec::new();
        let mut bosses = 0;
        for b in bags {
            let bag = self.held(b).ok_or(Abort("a bag gone"))?;
            let mut best = f64::MAX;
            let mut mob = None;
            for k in &self.killed {
                let Some(k) = self.held(k) else { continue };
                let (bp, kp) =
                    (bag.pos.ok_or(Abort("a bag without a position"))?, k.pos.ok_or(Abort("a killed enemy without a position"))?);
                let (x, y) = (f64::from(bp.x - kp.x), f64::from(bp.y - kp.y));
                let d = x * x + y * y;
                if d < best {
                    best = d;
                    mob = Some(Dropper::Killed { id: k.id, object_type: k.object_type });
                }
            }
            if mob.is_none() && self.windows.remaining > 0 && seed == self.windows.seed && self.windows.mob > 0 {
                mob = Some(Dropper::Boss { object_type: self.windows.mob, variant: None });
                bosses += 1;
            }
            let items = std::array::from_fn(|i| bag.value(stats::INVENTORY_0 + i as u8).unwrap_or(0));
            found.push(Drop {
                bag: bag.id,
                bag_type: bag.object_type,
                items,
                dropper: mob,
                map: self.map.as_ref().map(|m| m.name.clone()),
            });
        }
        // applyPerTickOverrides: the forced variant, or two Moonlight bosses at once being the hard mode
        let variant = self.windows.variant.clone().filter(|v| !v.is_empty());
        for d in &mut found {
            if let Some(Dropper::Boss { object_type, variant: v }) = &mut d.dropper {
                if let Some(f) = &variant {
                    *v = Some(f.clone());
                } else if bosses > 1 && (*object_type == 20493 || *object_type == 20451) {
                    *v = Some("HM".into());
                }
            }
        }
        let seasonal = self.me().map(|me| me.value(stats::SEASONAL) == Some(1));
        for (d, b) in found.into_iter().zip(bags) {
            if let Some(m) = &self.map {
                let name = m.name.clone();
                self.dungeon_stats.items(&name, d.dropper.as_ref().map_or(0, Dropper::object_type), &d.items);
            }
            if let Some(bag) = self.held(b) {
                let bag = Box::new(bag.clone());
                self.events.push(Event::BagDropped { map: map_name(self.map.as_ref()), bag, seasonal });
            }
            self.drops.push(d);
        }
        self.windows.end();
        Ok(())
    }

    /// On a map change: the bags and killed enemies waiting are kept as they are (Java keeps the objects), and the
    /// dungeon left is counted.
    pub(crate) fn loot_new_map(&mut self) {
        let copy = |w: &World, h: &mut Held| {
            if let Held::Live(id) = h
                && let Some(e) = w.entities.get(id)
            {
                *h = Held::Copy(Box::new(e.clone()));
            }
        };
        let mut lists = std::mem::take(&mut self.loot_tick_bags);
        for l in &mut lists {
            for h in l.iter_mut() {
                copy(self, h);
            }
        }
        self.loot_tick_bags = lists;
        let mut killed = std::mem::take(&mut self.killed);
        for h in &mut killed {
            copy(self, h);
        }
        self.killed = killed;
        if let Some(m) = &self.map {
            let name = m.name.clone();
            let time = self.dungeon_time_ms();
            self.dungeon_stats.left(&name, time);
        }
    }

    /// Java `dungeonTime`: from the map's first tick to its last, by this computer's clock.
    fn dungeon_time_ms(&self) -> i64 {
        match (self.first_tick_ms, self.last_tick_ms) {
            (Some(f), Some(l)) if l >= f && f > 0 => l - f,
            _ => 0,
        }
    }

    /// Java `enemiesByDungeon`: every enemy you hit in exactly one dungeon (by `dungeon.stats`), then those the game
    /// files place in exactly one dungeon, for dungeons you have not hit them in.
    pub(crate) fn enemies_by_dungeon(&self) -> HashMap<i32, String> {
        let mut out: HashMap<i32, String> = HashMap::new();
        let mut shared = HashSet::new();
        for (name, info) in &self.dungeon_stats.data {
            if name == "Realm of the Mad God" || name.starts_with('{') {
                continue;
            }
            for &t in info.damaged.keys() {
                match out.get(&t) {
                    None => {
                        out.insert(t, name.clone());
                    }
                    Some(before) if before != name => {
                        shared.insert(t);
                    }
                    Some(_) => {}
                }
            }
        }
        for t in shared {
            out.remove(&t);
        }
        for (t, d) in &self.data.dungeons.enemies {
            out.entry(*t).or_insert_with(|| d.clone());
        }
        out
    }
}
